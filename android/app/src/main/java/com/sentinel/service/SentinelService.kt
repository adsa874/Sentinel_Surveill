package com.sentinel.service

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.work.*
import com.sentinel.MainActivity
import com.sentinel.R
import com.sentinel.SentinelApp
import com.sentinel.camera.CameraManager
import com.sentinel.data.AppDatabase
import com.sentinel.events.EventEngine
import com.sentinel.ml.DetectionPipeline
import com.sentinel.network.SyncWorker
import com.sentinel.tracking.MultiObjectTracker
import com.sentinel.web.SentinelWebServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit

class SentinelService : LifecycleService() {

    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var cameraManager: CameraManager
    private lateinit var detectionPipeline: DetectionPipeline
    private lateinit var tracker: MultiObjectTracker
    private lateinit var eventEngine: EventEngine
    private var webServer: SentinelWebServer? = null

    private val database by lazy { AppDatabase.getInstance(this) }
    private val wakeLockHandler = Handler(Looper.getMainLooper())
    private val wakeLockRenewInterval = 9 * 60 * 60 * 1000L // 9 hours

    // Graceful stop flag: only clear service_enabled if user explicitly stopped
    var userRequestedStop = false

    // Heartbeat handler
    private val heartbeatHandler = Handler(Looper.getMainLooper())
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            getSharedPreferences("sentinel_prefs", MODE_PRIVATE).edit()
                .putLong("last_heartbeat", System.currentTimeMillis())
                .apply()
            heartbeatHandler.postDelayed(this, HEARTBEAT_INTERVAL)
        }
    }

    private val wakeLockRenewer = object : Runnable {
        override fun run() {
            renewWakeLock()
            wakeLockHandler.postDelayed(this, wakeLockRenewInterval)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "SentinelService created")

        // Persist running state
        SentinelApp.setServiceEnabled(this, true)

        acquireWakeLock()
        initializeComponents()
        scheduleWatchdog()
        scheduleAlarmWatchdog()
        startHeartbeat()
        startWebServerMonitor()
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        // Check camera permission before starting foreground service
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Camera permission not granted, stopping service")
            stopSelf()
            return START_NOT_STICKY
        }

        Log.d(TAG, "SentinelService started")
        startForegroundWithNotification()
        startSurveillance()

        return START_STICKY
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Sentinel::SurveillanceWakeLock"
        ).apply {
            acquire(10 * 60 * 60 * 1000L) // 10 hours max
        }
        // Schedule renewal before expiry
        wakeLockHandler.postDelayed(wakeLockRenewer, wakeLockRenewInterval)
        Log.d(TAG, "Wake lock acquired, renewal scheduled in 9 hours")
    }

    private fun renewWakeLock() {
        try {
            if (wakeLock.isHeld) {
                wakeLock.release()
            }
            wakeLock.acquire(10 * 60 * 60 * 1000L)
            Log.d(TAG, "Wake lock renewed for another 10 hours")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to renew wake lock", e)
        }
    }

    private fun scheduleWatchdog() {
        val watchdogRequest = PeriodicWorkRequestBuilder<ServiceWatchdogWorker>(
            15, TimeUnit.MINUTES
        )
            .addTag(WATCHDOG_TAG)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            WATCHDOG_TAG,
            ExistingPeriodicWorkPolicy.KEEP,
            watchdogRequest
        )
        Log.d(TAG, "Service watchdog scheduled (every 15 min)")
    }

    private fun scheduleAlarmWatchdog() {
        try {
            val intent = Intent(this, WatchdogAlarmReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                this, 1, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
            alarmManager.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + 5 * 60 * 1000L,
                5 * 60 * 1000L, // every 5 min
                pendingIntent
            )
            Log.d(TAG, "Alarm watchdog scheduled (every ~5 min)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule alarm watchdog", e)
        }
    }

    private fun startHeartbeat() {
        heartbeatHandler.post(heartbeatRunnable)
        Log.d(TAG, "Heartbeat started (every ${HEARTBEAT_INTERVAL / 1000}s)")
    }

    private fun startWebServerMonitor() {
        lifecycleScope.launch(Dispatchers.IO) {
            while (true) {
                delay(60_000L)
                try {
                    if (webServer != null && webServer?.isAlive != true) {
                        Log.w(TAG, "Web server died, restarting...")
                        webServer?.stop()
                        webServer = SentinelWebServer(this@SentinelService, detectionPipeline, database, tracker)
                        webServer?.start()
                        Log.d(TAG, "Web server restarted")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to restart web server", e)
                }
            }
        }
    }

    private fun initializeComponents() {
        tracker = MultiObjectTracker()
        eventEngine = EventEngine(database, lifecycleScope, this)
        detectionPipeline = DetectionPipeline(this, tracker, eventEngine)
        cameraManager = CameraManager(this, detectionPipeline)

        // Set camera error callback for retry
        cameraManager.onCameraError = { e ->
            Log.e(TAG, "Camera error callback triggered", e)
            lifecycleScope.launch(Dispatchers.Main) {
                delay(5000)
                Log.d(TAG, "Retrying camera after error...")
                startSurveillance()
            }
        }

        // Start embedded web server
        try {
            webServer = SentinelWebServer(this, detectionPipeline, database, tracker)
            webServer?.start()
            val ip = getDeviceIp()
            Log.d(TAG, "Web dashboard started at http://$ip:8080")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start web server", e)
        }
    }

    private fun startForegroundWithNotification() {
        val notification = createNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                SentinelApp.NOTIFICATION_ID_SERVICE,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            )
        } else {
            startForeground(SentinelApp.NOTIFICATION_ID_SERVICE, notification)
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val ip = getDeviceIp()
        val dashboardText = if (ip != "127.0.0.1") {
            "Dashboard: http://$ip:8080"
        } else {
            "Monitoring in progress..."
        }

        return NotificationCompat.Builder(this, SentinelApp.CHANNEL_SURVEILLANCE)
            .setContentTitle("Sentinel Active")
            .setContentText(dashboardText)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun getDeviceIp(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return addr.hostAddress ?: "127.0.0.1"
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get device IP", e)
        }
        return "127.0.0.1"
    }

    private fun startSurveillance(retryCount: Int = 0) {
        lifecycleScope.launch(Dispatchers.Main) {
            try {
                cameraManager.startCamera(this@SentinelService)
                Log.d(TAG, "Camera started successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start camera (attempt ${retryCount + 1})", e)
                if (retryCount < MAX_CAMERA_RETRIES) {
                    val delayMs = CAMERA_RETRY_BASE_DELAY * (1 shl retryCount)
                    Log.d(TAG, "Retrying camera in ${delayMs}ms...")
                    delay(delayMs)
                    startSurveillance(retryCount + 1)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "SentinelService destroyed")

        heartbeatHandler.removeCallbacks(heartbeatRunnable)
        wakeLockHandler.removeCallbacks(wakeLockRenewer)

        // Only clear service_enabled if user explicitly stopped (not OS kill)
        if (userRequestedStop) {
            SentinelApp.setServiceEnabled(this, false)
        }

        try {
            webServer?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping web server", e)
        }

        cameraManager.stopCamera()
        detectionPipeline.close()

        if (wakeLock.isHeld) {
            wakeLock.release()
        }

        // Sync events to backend when stopping
        SyncWorker.syncNow(this)

        isRunning = false
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    companion object {
        private const val TAG = "SentinelService"
        private const val WATCHDOG_TAG = "sentinel_watchdog"
        private const val HEARTBEAT_INTERVAL = 60_000L // 60 seconds
        private const val MAX_CAMERA_RETRIES = 3
        private const val CAMERA_RETRY_BASE_DELAY = 2000L // 2s, 4s, 8s with exponential backoff
        var isRunning = false
    }

    class ServiceWatchdogWorker(
        context: Context,
        params: WorkerParameters
    ) : Worker(context, params) {
        override fun doWork(): Result {
            if (!isRunning && SentinelApp.isServiceEnabled(applicationContext)) {
                Log.w("ServiceWatchdog", "Service not running, restarting...")
                val intent = Intent(applicationContext, SentinelService::class.java)
                ContextCompat.startForegroundService(applicationContext, intent)
            } else {
                Log.d("ServiceWatchdog", "Service is running OK")
            }
            return Result.success()
        }
    }
}
