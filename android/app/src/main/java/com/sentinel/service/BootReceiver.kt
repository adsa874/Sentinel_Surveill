package com.sentinel.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.sentinel.SentinelApp

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            if (SentinelApp.isServiceEnabled(context)) {
                Log.d(TAG, "Boot completed, service was enabled — restarting SentinelService")
                val serviceIntent = Intent(context, SentinelService::class.java)
                ContextCompat.startForegroundService(context, serviceIntent)
            } else {
                Log.d(TAG, "Boot completed, but service was not enabled — skipping")
            }
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
