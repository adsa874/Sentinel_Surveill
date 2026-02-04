package com.sentinel

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.sentinel.data.AppDatabase
import com.sentinel.databinding.ActivityMainBinding
import com.sentinel.service.SentinelService
import com.sentinel.update.AppUpdater
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val database by lazy { AppDatabase.getInstance(this) }
    private val appUpdater by lazy { AppUpdater(this) }

    private val requiredPermissions = buildList {
        add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            startSurveillanceService()
        } else {
            Toast.makeText(this, "Camera permission required for surveillance", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        observeStats()
    }

    private fun setupUI() {
        binding.btnStartService.setOnClickListener {
            checkPermissionsAndStart()
        }

        binding.btnStopService.setOnClickListener {
            stopSurveillanceService()
        }

        binding.btnDashboard.setOnClickListener {
            openDashboard()
        }

        binding.btnCheckUpdate.setOnClickListener {
            checkForUpdates()
        }

        binding.tvVersion.text = "Version ${BuildConfig.VERSION_NAME}"

        updateServiceStatus()
    }

    private fun openDashboard() {
        val dashboardUrl = getString(R.string.backend_url)
        if (dashboardUrl.isNotBlank()) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(dashboardUrl))
            startActivity(intent)
        } else {
            Toast.makeText(this, "Dashboard URL not configured", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkForUpdates() {
        binding.btnCheckUpdate.isEnabled = false
        binding.btnCheckUpdate.text = "Checking..."

        lifecycleScope.launch {
            val updateInfo = appUpdater.checkForUpdate()

            binding.btnCheckUpdate.isEnabled = true
            binding.btnCheckUpdate.text = "Check for Updates"

            if (updateInfo != null) {
                showUpdateDialog(updateInfo)
            } else {
                Toast.makeText(this@MainActivity, "You're on the latest version", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showUpdateDialog(updateInfo: com.sentinel.update.UpdateInfo) {
        AlertDialog.Builder(this)
            .setTitle("Update Available")
            .setMessage("Version ${updateInfo.versionName} is available.\n\n${updateInfo.releaseNotes}")
            .setPositiveButton("Update Now") { _, _ ->
                appUpdater.downloadAndInstall(
                    updateInfo,
                    onProgress = { progress ->
                        // Could show progress here
                    },
                    onComplete = {
                        Toast.makeText(this, "Download complete. Installing...", Toast.LENGTH_SHORT).show()
                    },
                    onError = { error ->
                        Toast.makeText(this, "Update failed: $error", Toast.LENGTH_LONG).show()
                        // Fallback to browser
                        appUpdater.openDownloadPage()
                    }
                )
            }
            .setNegativeButton("Later", null)
            .setNeutralButton("Download Page") { _, _ ->
                appUpdater.openDownloadPage()
            }
            .show()
    }

    private fun observeStats() {
        lifecycleScope.launch {
            database.eventDao().getTodayEventCount().collectLatest { count ->
                binding.tvEventCount.text = "Events Today: $count"
            }
        }

        lifecycleScope.launch {
            database.personDao().getRecentPersonCount().collectLatest { count ->
                binding.tvPersonCount.text = "People Detected: $count"
            }
        }
    }

    private fun checkPermissionsAndStart() {
        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            startSurveillanceService()
        } else {
            permissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    private fun startSurveillanceService() {
        val intent = Intent(this, SentinelService::class.java)
        ContextCompat.startForegroundService(this, intent)
        updateServiceStatus()
        Toast.makeText(this, "Surveillance started", Toast.LENGTH_SHORT).show()
    }

    private fun stopSurveillanceService() {
        val intent = Intent(this, SentinelService::class.java)
        stopService(intent)
        updateServiceStatus()
        Toast.makeText(this, "Surveillance stopped", Toast.LENGTH_SHORT).show()
    }

    private fun updateServiceStatus() {
        val isRunning = SentinelService.isRunning
        binding.tvStatus.text = if (isRunning) "Status: ACTIVE" else "Status: INACTIVE"
        binding.btnStartService.isEnabled = !isRunning
        binding.btnStopService.isEnabled = isRunning
    }

    override fun onResume() {
        super.onResume()
        updateServiceStatus()
    }
}
