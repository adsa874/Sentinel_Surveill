package com.sentinel.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult
import com.sentinel.events.EventEngine
import com.sentinel.tracking.DetectedObject
import com.sentinel.tracking.MultiObjectTracker
import com.sentinel.tracking.ObjectType
import com.sentinel.ui.DetectionEventManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream

class DetectionPipeline(
    private val context: Context,
    private val tracker: MultiObjectTracker,
    private val eventEngine: EventEngine
) {
    private var objectDetector: ObjectDetector? = null
    private var faceProcessor: FaceProcessor? = null
    private var plateReader: PlateReader? = null

    // Frame counter for skipping expensive operations
    private var detectionFrameCount = 0

    // Expose latest frame as pre-compressed JPEG bytes for MJPEG streaming
    private val _currentFrameBytes = MutableStateFlow<ByteArray?>(null)
    val currentFrameBytes: StateFlow<ByteArray?> = _currentFrameBytes.asStateFlow()

    // Reusable JPEG compression buffer
    private val jpegBuffer = ByteArrayOutputStream(64 * 1024)

    // Self-healing: timestamp of last detector re-init attempt
    private var lastReinitAttempt = 0L

    // Uptime tracking
    val startTime: Long = System.currentTimeMillis()

    init {
        initializeDetectors()
    }

    private fun initializeDetectors() {
        try {
            // Initialize MediaPipe Object Detector
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath("efficientdet_lite0.tflite")
                .build()

            val options = ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(baseOptions)
                .setMaxResults(MAX_DETECTIONS)
                .setScoreThreshold(CONFIDENCE_THRESHOLD)
                .build()

            objectDetector = ObjectDetector.createFromOptions(context, options)
            Log.d(TAG, "Object detector initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize object detector", e)
        }

        try {
            faceProcessor = FaceProcessor(context)
            Log.d(TAG, "Face processor initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize face processor", e)
        }

        try {
            plateReader = PlateReader(context)
            Log.d(TAG, "Plate reader initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize plate reader", e)
        }
    }

    private val uiScope = CoroutineScope(Dispatchers.Main)
    private val ioScope = CoroutineScope(Dispatchers.IO)
    private var lastFpsUpdate = 0L
    private var frameCount = 0

    fun processFrame(bitmap: Bitmap, timestamp: Long) {
        val startTime = System.currentTimeMillis()
        val detectedObjects = mutableListOf<DetectedObject>()
        detectionFrameCount++

        // Self-healing: retry initialization of failed detectors every 60s
        reinitializeFailedDetectors()

        // Compress bitmap to JPEG once, reuse for both MJPEG streaming and event snapshots
        jpegBuffer.reset()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, jpegBuffer)
        val frameBytes = jpegBuffer.toByteArray()
        _currentFrameBytes.value = frameBytes

        // Run object detection
        objectDetector?.let { detector ->
            try {
                val mpImage = BitmapImageBuilder(bitmap).build()
                val results = detector.detect(mpImage)

                processObjectDetectionResults(results, bitmap, detectedObjects, timestamp)
            } catch (e: Exception) {
                Log.e(TAG, "Object detection failed", e)
            }
        }

        // Update tracker with new detections
        val trackedObjects = tracker.update(detectedObjects, timestamp)

        // Provide current frame JPEG for evidence capture
        eventEngine.setCurrentFrameJpeg(frameBytes)

        // Generate events from tracked objects
        eventEngine.processTrackedObjects(trackedObjects, timestamp)

        // Update UI with detections
        val inferenceTime = System.currentTimeMillis() - startTime
        updateUI(detectedObjects, bitmap.width, bitmap.height, inferenceTime)
    }

    private fun reinitializeFailedDetectors() {
        val now = System.currentTimeMillis()
        if (now - lastReinitAttempt < 60_000L) return

        if (objectDetector == null) {
            lastReinitAttempt = now
            try {
                val baseOptions = BaseOptions.builder()
                    .setModelAssetPath("efficientdet_lite0.tflite")
                    .build()
                val options = ObjectDetector.ObjectDetectorOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setMaxResults(MAX_DETECTIONS)
                    .setScoreThreshold(CONFIDENCE_THRESHOLD)
                    .build()
                objectDetector = ObjectDetector.createFromOptions(context, options)
                Log.d(TAG, "Object detector re-initialized successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to re-initialize object detector", e)
            }
        }
        if (faceProcessor == null) {
            lastReinitAttempt = now
            try {
                faceProcessor = FaceProcessor(context)
                Log.d(TAG, "Face processor re-initialized successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to re-initialize face processor", e)
            }
        }
    }

    /**
     * Process an external frame (e.g. from browser camera upload).
     * Returns detection results as a list of maps for JSON serialization.
     */
    fun processExternalFrame(bitmap: Bitmap): List<Map<String, Any>> {
        val detectedObjects = mutableListOf<DetectedObject>()
        val timestamp = System.currentTimeMillis()

        objectDetector?.let { detector ->
            try {
                val mpImage = BitmapImageBuilder(bitmap).build()
                val results = detector.detect(mpImage)
                processObjectDetectionResults(results, bitmap, detectedObjects, timestamp)
            } catch (e: Exception) {
                Log.e(TAG, "External frame detection failed", e)
            }
        }

        return detectedObjects.map { obj ->
            mapOf(
                "type" to when (obj.type) {
                    ObjectType.PERSON -> "person"
                    ObjectType.VEHICLE -> obj.vehicleType ?: "vehicle"
                },
                "confidence" to obj.confidence,
                "bbox" to mapOf(
                    "left" to obj.boundingBox.left,
                    "top" to obj.boundingBox.top,
                    "right" to obj.boundingBox.right,
                    "bottom" to obj.boundingBox.bottom
                ),
                "licensePlate" to (obj.licensePlate ?: ""),
                "hasFace" to (obj.faceEmbedding != null)
            )
        }
    }

    private fun updateUI(detections: List<DetectedObject>, width: Int, height: Int, inferenceTime: Long) {
        // Convert DetectedObject to Detection for UI
        val uiDetections = detections.map { obj ->
            Detection(
                label = when (obj.type) {
                    ObjectType.PERSON -> "Person"
                    ObjectType.VEHICLE -> obj.vehicleType?.replaceFirstChar { it.uppercase() } ?: "Vehicle"
                    else -> "Unknown"
                },
                confidence = obj.confidence,
                boundingBox = obj.boundingBox
            )
        }

        // Update detection overlay
        DetectionEventManager.previewWidth = width
        DetectionEventManager.previewHeight = height
        DetectionEventManager.updateDetections(uiDetections)

        // Update FPS
        frameCount++
        val now = System.currentTimeMillis()
        if (now - lastFpsUpdate >= 1000) {
            val fps = frameCount * 1000f / (now - lastFpsUpdate)
            DetectionEventManager.updateStats(fps, inferenceTime)
            frameCount = 0
            lastFpsUpdate = now
        }
    }

    private fun processObjectDetectionResults(
        results: ObjectDetectorResult,
        bitmap: Bitmap,
        detectedObjects: MutableList<DetectedObject>,
        timestamp: Long
    ) {
        for (detection in results.detections()) {
            val category = detection.categories().firstOrNull() ?: continue
            val label = category.categoryName().lowercase()
            val confidence = category.score()
            val boundingBox = detection.boundingBox()

            val rectF = RectF(
                boundingBox.left.toFloat(),
                boundingBox.top.toFloat(),
                boundingBox.right.toFloat(),
                boundingBox.bottom.toFloat()
            )

            when {
                label == "person" -> {
                    val obj = DetectedObject(
                        type = ObjectType.PERSON,
                        boundingBox = rectF,
                        confidence = confidence,
                        timestamp = timestamp
                    )

                    // Face processing every 3rd frame to save CPU/memory
                    if (detectionFrameCount % 3 == 0) {
                        faceProcessor?.let { fp ->
                            val faceBitmap = cropBitmap(bitmap, rectF)
                            try {
                                val faceResult = fp.processFace(faceBitmap)
                                if (faceResult != null) {
                                    obj.faceEmbedding = faceResult.embedding
                                    obj.faceConfidence = faceResult.confidence
                                }
                            } finally {
                                faceBitmap.recycle()
                            }
                        }
                    }

                    detectedObjects.add(obj)
                }

                label in listOf("car", "truck", "bus", "motorcycle") -> {
                    val obj = DetectedObject(
                        type = ObjectType.VEHICLE,
                        boundingBox = rectF,
                        confidence = confidence,
                        timestamp = timestamp,
                        vehicleType = label
                    )

                    detectedObjects.add(obj)

                    // Async plate reading — fire-and-forget to avoid blocking detection thread
                    if (rectF.width() > 100f) {
                        plateReader?.let { pr ->
                            val vehicleBitmap = cropBitmap(bitmap, rectF)
                            val trackId = obj.hashCode() // will be updated after tracker assigns real ID
                            ioScope.launch {
                                try {
                                    val plate = withTimeoutOrNull(2000L) { pr.readPlateAsync(vehicleBitmap) }
                                    if (plate != null) {
                                        // Update tracker directly with async result
                                        tracker.updatePlate(trackId, plate)
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Async plate read failed", e)
                                } finally {
                                    vehicleBitmap.recycle()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun cropBitmap(bitmap: Bitmap, rect: RectF): Bitmap {
        val left = rect.left.toInt().coerceIn(0, bitmap.width - 1)
        val top = rect.top.toInt().coerceIn(0, bitmap.height - 1)
        val width = (rect.width().toInt()).coerceIn(1, bitmap.width - left)
        val height = (rect.height().toInt()).coerceIn(1, bitmap.height - top)

        return Bitmap.createBitmap(bitmap, left, top, width, height)
    }

    fun close() {
        objectDetector?.close()
        faceProcessor?.close()
        plateReader?.close()
        _currentFrameBytes.value = null
    }

    companion object {
        private const val TAG = "DetectionPipeline"
        private const val MAX_DETECTIONS = 10
        private const val CONFIDENCE_THRESHOLD = 0.5f
    }
}
