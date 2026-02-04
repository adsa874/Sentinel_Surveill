package com.sentinel

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.sentinel.data.AppDatabase
import com.sentinel.network.SentinelApi
import com.sentinel.network.SyncWorker

class SentinelApp : Application() {

    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Initialize Crashlytics
        FirebaseCrashlytics.getInstance().apply {
            setCrashlyticsCollectionEnabled(true)
            setCustomKey("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            setCustomKey("os_version", Build.VERSION.RELEASE)
        }
        Log.i(TAG, "Crashlytics initialized")

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

    companion object {
        private const val TAG = "SentinelApp"
        const val CHANNEL_SURVEILLANCE = "sentinel_surveillance"
        const val CHANNEL_ALERTS = "sentinel_alerts"
        const val NOTIFICATION_ID_SERVICE = 1

        lateinit var instance: SentinelApp
            private set
    }
}
