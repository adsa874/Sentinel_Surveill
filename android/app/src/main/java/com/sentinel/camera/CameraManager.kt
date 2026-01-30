package com.sentinel.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
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
        }
    }

    private fun processFrame(imageProxy: ImageProxy) {
        val currentTime = System.currentTimeMillis()

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
                detectionPipeline.processFrame(bitmap, currentTime)
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
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)

            val bitmap = Bitmap.createBitmap(
                imageProxy.width,
                imageProxy.height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(bytes))

            // Rotate bitmap based on image rotation
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            if (rotationDegrees != 0) {
                val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            } else {
                bitmap
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to convert ImageProxy to Bitmap", e)
            null
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
