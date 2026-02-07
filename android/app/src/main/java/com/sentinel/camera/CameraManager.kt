package com.sentinel.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.sentinel.ml.DetectionPipeline
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class CameraManager(
    private val context: Context,
    private val detectionPipeline: DetectionPipeline
) {
    private var cameraProvider: ProcessCameraProvider? = null
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val isProcessing = AtomicBoolean(false)
    private val lastProcessedTime = AtomicLong(0)

    private var targetFps = DEFAULT_FPS
    private val frameIntervalMs: Long
        get() = 1000L / targetFps

    // Camera error callback for service-level retry
    var onCameraError: ((Exception) -> Unit)? = null

    // Reusable buffers for imageProxyToBitmap
    private var reusableByteArray: ByteArray? = null
    private var reusableBitmap: Bitmap? = null
    private val reusableMatrix = Matrix()

    // Adaptive FPS
    private var lastAdaptTime = 0L

    fun startCamera(lifecycleOwner: LifecycleOwner) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases(lifecycleOwner)
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bindCameraUseCases(lifecycleOwner: LifecycleOwner) {
        val cameraProvider = cameraProvider ?: return

        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build()

        val imageAnalysis = ImageAnalysis.Builder()
            .setTargetResolution(Size(ANALYSIS_WIDTH, ANALYSIS_HEIGHT))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()

        imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
            processFrame(imageProxy)
        }

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                imageAnalysis
            )
            Log.d(TAG, "Camera bound successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind camera use cases", e)
            onCameraError?.invoke(e)
        }
    }

    private fun processFrame(imageProxy: ImageProxy) {
        val currentTime = System.currentTimeMillis()

        // Adaptive frame rate based on battery/thermal state (every 10s)
        if (currentTime - lastAdaptTime > 10_000L) {
            lastAdaptTime = currentTime
            adaptFrameRate(context)
        }

        // Rate limiting
        if (currentTime - lastProcessedTime.get() < frameIntervalMs) {
            imageProxy.close()
            return
        }

        // Skip if still processing previous frame
        if (!isProcessing.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }

        try {
            lastProcessedTime.set(currentTime)

            val bitmap = imageProxyToBitmap(imageProxy)
            if (bitmap != null) {
                try {
                    detectionPipeline.processFrame(bitmap, currentTime)
                } finally {
                    bitmap.recycle()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing frame", e)
        } finally {
            isProcessing.set(false)
            imageProxy.close()
        }
    }

    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        return try {
            val buffer = imageProxy.planes[0].buffer
            val size = buffer.remaining()

            // Reuse byte array if size matches
            val bytes = reusableByteArray.let {
                if (it != null && it.size == size) it
                else ByteArray(size).also { arr -> reusableByteArray = arr }
            }
            buffer.get(bytes)

            // Reuse source bitmap if dimensions match
            val w = imageProxy.width
            val h = imageProxy.height
            val bitmap = reusableBitmap.let {
                if (it != null && !it.isRecycled && it.width == w && it.height == h) {
                    it
                } else {
                    it?.recycle()
                    Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp -> reusableBitmap = bmp }
                }
            }
            bitmap.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(bytes))

            // Rotate bitmap based on image rotation
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            if (rotationDegrees != 0) {
                reusableMatrix.reset()
                reusableMatrix.postRotate(rotationDegrees.toFloat())
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, reusableMatrix, true)
            } else {
                // Return a copy since the reusable bitmap will be overwritten next frame
                bitmap.copy(bitmap.config, false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to convert ImageProxy to Bitmap", e)
            null
        }
    }

    private fun adaptFrameRate(context: Context) {
        try {
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val batteryLevel = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100

            var newFps = DEFAULT_FPS

            // Throttle on low battery
            if (batteryLevel <= 15) {
                newFps = 1
            } else if (batteryLevel <= 30) {
                newFps = 2
            }

            // Throttle on thermal stress (API 29+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                val thermalStatus = powerManager?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE
                if (thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) {
                    newFps = 1
                } else if (thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE) {
                    newFps = minOf(newFps, 2)
                } else if (thermalStatus >= PowerManager.THERMAL_STATUS_LIGHT) {
                    newFps = minOf(newFps, 3)
                }
            }

            if (newFps != targetFps) {
                Log.d(TAG, "Adapting FPS: $targetFps -> $newFps (battery=$batteryLevel%)")
                targetFps = newFps
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to adapt frame rate", e)
        }
    }

    fun stopCamera() {
        cameraProvider?.unbindAll()
        analysisExecutor.shutdown()
    }

    fun setTargetFps(fps: Int) {
        targetFps = fps.coerceIn(1, 30)
    }

    companion object {
        private const val TAG = "CameraManager"
        private const val DEFAULT_FPS = 5
        private const val ANALYSIS_WIDTH = 640
        private const val ANALYSIS_HEIGHT = 480
    }
}
