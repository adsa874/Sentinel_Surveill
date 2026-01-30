package com.sentinel

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.sentinel.data.AppDatabase
import com.sentinel.databinding.ActivityMainBinding
import com.sentinel.service.SentinelService
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val database by lazy { AppDatabase.getInstance(this) }

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

        updateServiceStatus()
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
