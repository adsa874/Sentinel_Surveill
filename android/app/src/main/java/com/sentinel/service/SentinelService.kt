package com.sentinel.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.sentinel.MainActivity
import com.sentinel.R
import com.sentinel.SentinelApp
import com.sentinel.camera.CameraManager
import com.sentinel.data.AppDatabase
import com.sentinel.events.EventEngine
import com.sentinel.ml.DetectionPipeline
import com.sentinel.tracking.MultiObjectTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SentinelService : LifecycleService() {

    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var cameraManager: CameraManager
    private lateinit var detectionPipeline: DetectionPipeline
    private lateinit var tracker: MultiObjectTracker
    private lateinit var eventEngine: EventEngine

    private val database by lazy { AppDatabase.getInstance(this) }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "SentinelService created")

        acquireWakeLock()
        initializeComponents()
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
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
    }

    private fun initializeComponents() {
        tracker = MultiObjectTracker()
        eventEngine = EventEngine(database, lifecycleScope)
        detectionPipeline = DetectionPipeline(this, tracker, eventEngine)
        cameraManager = CameraManager(this, detectionPipeline)
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

        return NotificationCompat.Builder(this, SentinelApp.CHANNEL_SURVEILLANCE)
            .setContentTitle("Sentinel Active")
            .setContentText("Monitoring in progress...")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun startSurveillance() {
        lifecycleScope.launch(Dispatchers.Main) {
            try {
                cameraManager.startCamera(this@SentinelService)
                Log.d(TAG, "Camera started successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start camera", e)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "SentinelService destroyed")

        cameraManager.stopCamera()
        detectionPipeline.close()

        if (wakeLock.isHeld) {
            wakeLock.release()
        }

        isRunning = false
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    companion object {
        private const val TAG = "SentinelService"
        var isRunning = false
            private set
    }
}
