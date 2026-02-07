package com.sentinel

import android.app.AlarmManager
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.sentinel.data.AppDatabase
import com.sentinel.network.SentinelApi
import com.sentinel.network.SyncWorker
import com.sentinel.service.CrashRestartReceiver

class SentinelApp : Application() {

    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this

        installCrashHandler()

        // Initialize Firebase manually (delayed to avoid startup crash on older devices)
        android.os.Handler(mainLooper).postDelayed({
            try {
                FirebaseApp.initializeApp(this)
                FirebaseCrashlytics.getInstance().apply {
                    setCrashlyticsCollectionEnabled(true)
                    setCustomKey("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                    setCustomKey("os_version", Build.VERSION.RELEASE)
                }
                Log.i(TAG, "Firebase & Crashlytics initialized")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize Firebase", e)
            }
        }, 2000)

        configureBackendUrl()
        createNotificationChannels()
        scheduleSyncWorker()
    }

    private fun scheduleSyncWorker() {
        // Delay sync worker to avoid crash on app startup
        android.os.Handler(mainLooper).postDelayed({
            try {
                SyncWorker.schedule(this)
                Log.i(TAG, "Sync worker scheduled")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to schedule sync worker", e)
            }
        }, 3000)
    }

    private fun configureBackendUrl() {
        try {
            val backendUrl = getString(R.string.backend_url)
            if (backendUrl.isNotBlank()) {
                // Ensure URL ends with /
                val normalizedUrl = if (backendUrl.endsWith("/")) backendUrl else "$backendUrl/"
                SentinelApi.configure(normalizedUrl)
                Log.i(TAG, "Backend URL configured: $normalizedUrl")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to configure backend URL", e)
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)

            // Main surveillance channel
            val surveillanceChannel = NotificationChannel(
                CHANNEL_SURVEILLANCE,
                "Surveillance Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when Sentinel is actively monitoring"
                setShowBadge(false)
            }

            // Alerts channel
            val alertsChannel = NotificationChannel(
                CHANNEL_ALERTS,
                "Security Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Important security notifications"
                enableVibration(true)
            }

            notificationManager.createNotificationChannel(surveillanceChannel)
            notificationManager.createNotificationChannel(alertsChannel)
        }
    }

    private fun installCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                Log.e(TAG, "Uncaught exception, scheduling restart", throwable)
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putLong("crash_timestamp", System.currentTimeMillis())
                    .apply()

                if (isServiceEnabled(this)) {
                    val intent = Intent(this, CrashRestartReceiver::class.java)
                    val pendingIntent = PendingIntent.getBroadcast(
                        this, 0, intent,
                        PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
                    )
                    val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
                    alarmManager.set(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        SystemClock.elapsedRealtime() + 3000,
                        pendingIntent
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to schedule crash restart", e)
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        private const val TAG = "SentinelApp"
        private const val PREFS_NAME = "sentinel_prefs"
        const val CHANNEL_SURVEILLANCE = "sentinel_surveillance"
        const val CHANNEL_ALERTS = "sentinel_alerts"
        const val NOTIFICATION_ID_SERVICE = 1

        lateinit var instance: SentinelApp
            private set

        fun isServiceEnabled(context: Context): Boolean {
            return context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean("service_enabled", false)
        }

        fun setServiceEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putBoolean("service_enabled", enabled)
                .apply()
        }
    }
}
