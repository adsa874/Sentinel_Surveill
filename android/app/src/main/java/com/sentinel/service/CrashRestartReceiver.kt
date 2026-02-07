package com.sentinel.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.sentinel.SentinelApp

class CrashRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (SentinelApp.isServiceEnabled(context)) {
            Log.d(TAG, "Restarting service after crash")
            val serviceIntent = Intent(context, SentinelService::class.java)
            ContextCompat.startForegroundService(context, serviceIntent)
        }
    }

    companion object {
        private const val TAG = "CrashRestartReceiver"
    }
}
