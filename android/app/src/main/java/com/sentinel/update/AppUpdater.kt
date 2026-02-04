package com.sentinel.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.sentinel.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.URL
import kotlin.coroutines.resume

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val downloadUrl: String,
    val releaseNotes: String
)

class AppUpdater(private val context: Context) {

    companion object {
        private const val TAG = "AppUpdater"
        private const val VERSION_URL = "https://sentinel-surv-15464.web.app/version.json"
        private const val APK_URL = "https://sentinel-surv-15464.web.app/sentinel.apk"
    }

    suspend fun checkForUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val response = URL(VERSION_URL).readText()
            val json = JSONObject(response)

            val latestVersionCode = json.getInt("versionCode")
            val currentVersionCode = BuildConfig.VERSION_CODE

            Log.d(TAG, "Current version: $currentVersionCode, Latest: $latestVersionCode")

            if (latestVersionCode > currentVersionCode) {
                UpdateInfo(
                    versionCode = latestVersionCode,
                    versionName = json.getString("versionName"),
                    downloadUrl = json.optString("downloadUrl", APK_URL),
                    releaseNotes = json.optString("releaseNotes", "Bug fixes and improvements")
                )
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check for updates", e)
            null
        }
    }

    fun downloadAndInstall(updateInfo: UpdateInfo, onProgress: (Int) -> Unit, onComplete: () -> Unit, onError: (String) -> Unit) {
        try {
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

            // Create updates directory
            val updatesDir = File(context.getExternalFilesDir(null), "updates")
            if (!updatesDir.exists()) {
                updatesDir.mkdirs()
            }

            // Delete old APK if exists
            val apkFile = File(updatesDir, "sentinel-update.apk")
            if (apkFile.exists()) {
                apkFile.delete()
            }

            val request = DownloadManager.Request(Uri.parse(updateInfo.downloadUrl))
                .setTitle("Sentinel Update")
                .setDescription("Downloading version ${updateInfo.versionName}")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(context, null, "updates/sentinel-update.apk")
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)

            val downloadId = downloadManager.enqueue(request)

            // Register receiver for download complete
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                    if (id == downloadId) {
                        context.unregisterReceiver(this)

                        val query = DownloadManager.Query().setFilterById(downloadId)
                        val cursor = downloadManager.query(query)

                        if (cursor.moveToFirst()) {
                            val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                            val status = cursor.getInt(statusIndex)

                            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                                onComplete()
                                installApk(apkFile)
                            } else {
                                onError("Download failed")
                            }
                        }
                        cursor.close()
                    }
                }
            }

            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )

        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            onError(e.message ?: "Download failed")
        }
    }

    private fun installApk(apkFile: File) {
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }

            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install APK", e)
        }
    }

    fun openDownloadPage() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://sentinel-surv-15464.web.app"))
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        context.startActivity(intent)
    }
}
