package com.sentinel.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.sentinel.databinding.FragmentLivePreviewBinding
import com.sentinel.service.SentinelService
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class LivePreviewFragment : Fragment() {

    private var _binding: FragmentLivePreviewBinding? = null
    private val binding get() = _binding!!

    private val recentAdapter = ActivityFeedAdapter()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLivePreviewBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecentDetections()
        observeDetections()
        observeStats()
        updatePreviewState()
    }

    private fun setupRecentDetections() {
        binding.recentDetections.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = recentAdapter
        }

        // Load existing events
        recentAdapter.submitList(DetectionEventManager.getRecentEvents().take(10))
    }

    private fun observeDetections() {
        viewLifecycleOwner.lifecycleScope.launch {
            DetectionEventManager.currentDetections.collectLatest { detections ->
                binding.detectionOverlay.setDetections(
                    detections,
                    DetectionEventManager.previewWidth,
                    DetectionEventManager.previewHeight
                )
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            DetectionEventManager.activityEvents.collectLatest { event ->
                val currentList = recentAdapter.currentList.toMutableList()
                currentList.add(0, event)
                if (currentList.size > 10) {
                    currentList.removeAt(currentList.lastIndex)
                }
                recentAdapter.submitList(currentList)
            }
        }
    }

    private fun observeStats() {
        viewLifecycleOwner.lifecycleScope.launch {
            DetectionEventManager.fps.collectLatest { fps ->
                binding.tvFps.text = String.format("%.1f", fps)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            DetectionEventManager.activeCount.collectLatest { count ->
                binding.tvActiveDetections.text = count.toString()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            DetectionEventManager.inferenceTime.collectLatest { time ->
                binding.tvInferenceTime.text = "${time}ms"
            }
        }
    }

    private fun updatePreviewState() {
        if (SentinelService.isRunning) {
            binding.tvPreviewStatus.visibility = View.GONE
            startCameraPreview()
        } else {
            binding.tvPreviewStatus.visibility = View.VISIBLE
            binding.tvPreviewStatus.text = "Start surveillance to see preview"
        }
    }

    private fun startCameraPreview() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())

        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(binding.previewView.surfaceProvider)
                }

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(viewLifecycleOwner, cameraSelector, preview)

                binding.tvPreviewStatus.visibility = View.GONE
            } catch (e: Exception) {
                binding.tvPreviewStatus.text = "Camera error: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    override fun onResume() {
        super.onResume()
        updatePreviewState()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
