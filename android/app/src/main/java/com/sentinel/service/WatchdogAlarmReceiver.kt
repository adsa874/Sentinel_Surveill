package com.sentinel.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.sentinel.SentinelApp

class WatchdogAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences("sentinel_prefs", Context.MODE_PRIVATE)
        val serviceEnabled = SentinelApp.isServiceEnabled(context)
        val lastHeartbeat = prefs.getLong("last_heartbeat", 0L)
        val now = System.currentTimeMillis()
        val stale = now - lastHeartbeat > 3 * 60 * 1000L // 3 minutes

        if (serviceEnabled && stale) {
            Log.w(TAG, "Heartbeat stale (${(now - lastHeartbeat) / 1000}s), restarting service")
            val serviceIntent = Intent(context, SentinelService::class.java)
            ContextCompat.startForegroundService(context, serviceIntent)
        }
    }

    companion object {
        private const val TAG = "WatchdogAlarmReceiver"
    }
}
