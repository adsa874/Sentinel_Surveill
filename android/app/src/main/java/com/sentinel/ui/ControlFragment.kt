package com.sentinel.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.sentinel.BuildConfig
import com.sentinel.R
import com.sentinel.SentinelApp
import com.sentinel.data.AppDatabase
import com.sentinel.databinding.FragmentControlBinding
import com.sentinel.service.SentinelService
import com.sentinel.update.AppUpdater
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ControlFragment : Fragment() {

    private var _binding: FragmentControlBinding? = null
    private val binding get() = _binding!!

    private val database by lazy { AppDatabase.getInstance(requireContext()) }
    private val appUpdater by lazy { AppUpdater(requireContext()) }

    private var serviceStartTime: Long = 0
    private val uptimeHandler = Handler(Looper.getMainLooper())
    private val uptimeRunnable = object : Runnable {
        override fun run() {
            if (SentinelService.isRunning) {
                val elapsed = System.currentTimeMillis() - serviceStartTime
                binding.tvUptime.text = "Uptime: ${formatDuration(elapsed)}"
                uptimeHandler.postDelayed(this, 1000)
            }
        }
    }

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
            Toast.makeText(requireContext(), "Camera permission required", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentControlBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupButtons()
        observeStats()
        updateServiceStatus()

        binding.tvVersion.text = "Version ${BuildConfig.VERSION_NAME}"
    }

    private fun setupButtons() {
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
    }

    private fun observeStats() {
        viewLifecycleOwner.lifecycleScope.launch {
            val startOfDay = com.sentinel.data.dao.EventDao.getTodayStartMillis()
            database.eventDao().getTodayEventCount(startOfDay).collectLatest { count ->
                binding.tvStatEvents.text = count.toString()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val since = System.currentTimeMillis() - 24 * 60 * 60 * 1000
            database.personDao().getRecentPersonCount(since).collectLatest { count ->
                binding.tvStatPeople.text = count.toString()
            }
        }
    }

    private fun checkPermissionsAndStart() {
        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            startSurveillanceService()
        } else {
            permissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    private fun startSurveillanceService() {
        SentinelApp.setServiceEnabled(requireContext(), true)
        val intent = Intent(requireContext(), SentinelService::class.java)
        ContextCompat.startForegroundService(requireContext(), intent)
        serviceStartTime = System.currentTimeMillis()
        setServiceUi(running = true)

        viewLifecycleOwner.lifecycleScope.launch {
            DetectionEventManager.onSystemEvent("Surveillance started")
        }

        Toast.makeText(requireContext(), "Surveillance started", Toast.LENGTH_SHORT).show()
    }

    private fun stopSurveillanceService() {
        SentinelApp.setServiceEnabled(requireContext(), false)
        val intent = Intent(requireContext(), SentinelService::class.java)
        requireContext().stopService(intent)
        SentinelService.isRunning = false
        setServiceUi(running = false)

        viewLifecycleOwner.lifecycleScope.launch {
            DetectionEventManager.onSystemEvent("Surveillance stopped")
        }

        Toast.makeText(requireContext(), "Surveillance stopped", Toast.LENGTH_SHORT).show()
    }

    private fun updateServiceStatus() {
        setServiceUi(SentinelService.isRunning)
    }

    private fun setServiceUi(running: Boolean) {
        binding.tvStatus.text = if (running) "ACTIVE" else "INACTIVE"
        binding.tvStatus.setTextColor(if (running) Color.parseColor("#4CAF50") else Color.GRAY)

        binding.statusIndicator.setBackgroundResource(
            if (running) R.drawable.bg_pulse_indicator else R.drawable.bg_confidence_badge
        )

        binding.btnStartService.isEnabled = !running
        binding.btnStopService.isEnabled = running

        if (running) {
            if (serviceStartTime == 0L) {
                serviceStartTime = System.currentTimeMillis()
            }
            uptimeHandler.post(uptimeRunnable)
        } else {
            binding.tvUptime.text = "Uptime: --"
            uptimeHandler.removeCallbacks(uptimeRunnable)
        }
    }

    private fun openDashboard() {
        val dashboardUrl = getString(R.string.backend_url)
        if (dashboardUrl.isNotBlank()) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(dashboardUrl))
            startActivity(intent)
        } else {
            Toast.makeText(requireContext(), "Dashboard URL not configured", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkForUpdates() {
        binding.btnCheckUpdate.isEnabled = false
        binding.btnCheckUpdate.text = "Checking..."

        viewLifecycleOwner.lifecycleScope.launch {
            val updateInfo = appUpdater.checkForUpdate()

            binding.btnCheckUpdate.isEnabled = true
            binding.btnCheckUpdate.text = "Check for Updates"

            if (updateInfo != null) {
                showUpdateDialog(updateInfo)
            } else {
                Toast.makeText(requireContext(), "You're on the latest version", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showUpdateDialog(updateInfo: com.sentinel.update.UpdateInfo) {
        AlertDialog.Builder(requireContext())
            .setTitle("Update Available")
            .setMessage("Version ${updateInfo.versionName} is available.\n\n${updateInfo.releaseNotes}")
            .setPositiveButton("Update Now") { _, _ ->
                appUpdater.downloadAndInstall(
                    updateInfo,
                    onProgress = { },
                    onComplete = {
                        Toast.makeText(requireContext(), "Download complete. Installing...", Toast.LENGTH_SHORT).show()
                    },
                    onError = { error ->
                        Toast.makeText(requireContext(), "Update failed: $error", Toast.LENGTH_LONG).show()
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

    private fun formatDuration(ms: Long): String {
        val seconds = ms / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        return when {
            hours > 0 -> String.format("%d:%02d:%02d", hours, minutes % 60, seconds % 60)
            else -> String.format("%d:%02d", minutes, seconds % 60)
        }
    }

    override fun onResume() {
        super.onResume()
        updateServiceStatus()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        uptimeHandler.removeCallbacks(uptimeRunnable)
        _binding = null
    }
}
