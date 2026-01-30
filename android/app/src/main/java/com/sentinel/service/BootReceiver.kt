package com.sentinel.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            Log.d(TAG, "Boot completed, starting SentinelService")

            val serviceIntent = Intent(context, SentinelService::class.java)
            ContextCompat.startForegroundService(context, serviceIntent)
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
