package com.sentinel.network

import android.content.Context
import android.util.Log
import androidx.work.*
import java.util.concurrent.TimeUnit

class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val syncManager = SyncManager(context)

    override suspend fun doWork(): Result {
        Log.d(TAG, "Starting sync work")

        // Check backend connectivity first
        if (!syncManager.checkHealth()) {
            Log.w(TAG, "Backend not reachable, will retry later")
            return Result.retry()
        }

        // Sync events to backend
        return when (val result = syncManager.syncEvents()) {
            is SyncManager.SyncResult.Success -> {
                Log.d(TAG, "Sync completed: ${result.count} events")
                Result.success()
            }
            is SyncManager.SyncResult.NotRegistered -> {
                Log.d(TAG, "Device not registered, attempting registration")
                if (syncManager.registerDevice()) {
                    // Retry sync after registration
                    when (val retryResult = syncManager.syncEvents()) {
                        is SyncManager.SyncResult.Success -> Result.success()
                        else -> Result.retry()
                    }
                } else {
                    Result.retry()
                }
            }
            is SyncManager.SyncResult.Error -> {
                Log.e(TAG, "Sync failed: ${result.message}")
                Result.retry()
            }
        }
    }

    companion object {
        private const val TAG = "SyncWorker"
        private const val SYNC_WORK_NAME = "sentinel_sync"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            // Periodic sync every 15 minutes
            val syncRequest = PeriodicWorkRequestBuilder<SyncWorker>(
                15, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                SYNC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                syncRequest
            )

            Log.d(TAG, "Sync worker scheduled")
        }

        fun syncNow(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val syncRequest = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueue(syncRequest)
            Log.d(TAG, "Immediate sync requested")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(SYNC_WORK_NAME)
        }
    }
}
