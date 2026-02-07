package com.sentinel.network

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.sentinel.data.AppDatabase
import com.sentinel.data.entities.EmployeeEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SyncManager(private val context: Context) {
    private val database = AppDatabase.getInstance(context)
    private val prefs: SharedPreferences = createEncryptedPrefs()

    private val deviceId: String
        get() = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)

    private val apiKey: String?
        get() = prefs.getString(PREF_API_KEY, null)

    private fun createEncryptedPrefs(): SharedPreferences {
        return try {
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            EncryptedSharedPreferences.create(
                "sentinel_secure_prefs",
                masterKeyAlias,
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create encrypted prefs, falling back to regular prefs", e)
            context.getSharedPreferences("sentinel_prefs", Context.MODE_PRIVATE)
        }
    }

    suspend fun registerDevice(): Boolean = withContext(Dispatchers.IO) {
        try {
            val registration = DeviceRegistration(
                deviceId = deviceId,
                deviceName = "${Build.MANUFACTURER} ${Build.MODEL}",
                model = Build.MODEL,
                osVersion = "Android ${Build.VERSION.RELEASE}"
            )

            val response = SentinelApi.service.registerDevice(registration)
            if (response.isSuccessful && response.body()?.success == true) {
                response.body()?.apiKey?.let { key ->
                    prefs.edit().putString(PREF_API_KEY, key).apply()
                    Log.d(TAG, "Device registered successfully")
                    return@withContext true
                }
            }
            Log.e(TAG, "Device registration failed: ${response.errorBody()?.string()}")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Device registration error", e)
            false
        }
    }

    suspend fun syncEvents(): SyncResult = withContext(Dispatchers.IO) {
        val key = apiKey ?: return@withContext SyncResult.NotRegistered

        try {
            val unsyncedEvents = database.eventDao().getUnsyncedEvents(BATCH_SIZE)
            if (unsyncedEvents.isEmpty()) {
                return@withContext SyncResult.Success(0)
            }

            val eventRequests = unsyncedEvents.map { event ->
                EventRequest(
                    type = event.type,
                    timestamp = event.timestamp,
                    trackId = event.trackId,
                    employeeId = event.employeeId,
                    licensePlate = event.licensePlate,
                    duration = event.duration,
                    deviceId = deviceId
                )
            }

            val request = BatchEventRequest(events = eventRequests, deviceId = deviceId)
            val response = SentinelApi.service.sendEvents(key, request)

            if (response.isSuccessful && response.body()?.success == true) {
                val syncedIds = unsyncedEvents.map { it.id }
                database.eventDao().markAsSynced(syncedIds)
                Log.d(TAG, "Synced ${syncedIds.size} events")
                SyncResult.Success(syncedIds.size)
            } else {
                Log.e(TAG, "Event sync failed: ${response.errorBody()?.string()}")
                SyncResult.Error("Sync failed: ${response.code()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Event sync error", e)
            SyncResult.Error(e.message ?: "Unknown error")
        }
    }

    suspend fun syncEmployees(): SyncResult = withContext(Dispatchers.IO) {
        val key = apiKey ?: return@withContext SyncResult.NotRegistered

        try {
            val response = SentinelApi.service.getEmployees(key)

            if (response.isSuccessful) {
                val employees = response.body()?.employees ?: emptyList()

                val entities = employees.map { emp ->
                    EmployeeEntity(
                        employeeId = emp.employeeId,
                        name = emp.name,
                        department = emp.department,
                        faceEmbedding = emp.faceEmbedding?.toFloatArray()
                    )
                }

                database.employeeDao().insertAll(entities)
                Log.d(TAG, "Synced ${entities.size} employees")
                SyncResult.Success(entities.size)
            } else {
                Log.e(TAG, "Employee sync failed: ${response.errorBody()?.string()}")
                SyncResult.Error("Sync failed: ${response.code()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Employee sync error", e)
            SyncResult.Error(e.message ?: "Unknown error")
        }
    }

    suspend fun checkHealth(): Boolean = withContext(Dispatchers.IO) {
        try {
            val response = SentinelApi.service.healthCheck()
            response.isSuccessful
        } catch (e: Exception) {
            Log.e(TAG, "Health check failed", e)
            false
        }
    }

    sealed class SyncResult {
        data class Success(val count: Int) : SyncResult()
        data class Error(val message: String) : SyncResult()
        data object NotRegistered : SyncResult()
    }

    companion object {
        private const val TAG = "SyncManager"
        private const val PREF_API_KEY = "api_key"
        private const val BATCH_SIZE = 100
    }
}
