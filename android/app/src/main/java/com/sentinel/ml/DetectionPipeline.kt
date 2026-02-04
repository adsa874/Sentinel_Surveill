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

class DetectionPipeline(
    private val context: Context,
    private val tracker: MultiObjectTracker,
    private val eventEngine: EventEngine
) {
    private var objectDetector: ObjectDetector? = null
    private var faceProcessor: FaceProcessor? = null
    private var plateReader: PlateReader? = null

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
    private var lastFpsUpdate = 0L
    private var frameCount = 0

    fun processFrame(bitmap: Bitmap, timestamp: Long) {
        val startTime = System.currentTimeMillis()
        val detectedObjects = mutableListOf<DetectedObject>()

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

        // Generate events from tracked objects
        eventEngine.processTrackedObjects(trackedObjects, timestamp)

        // Update UI with detections
        val inferenceTime = System.currentTimeMillis() - startTime
        updateUI(detectedObjects, bitmap.width, bitmap.height, inferenceTime)
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
                // trackId is assigned by the tracker, not available on DetectedObject
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

        // Emit new detection events for activity feed
        uiScope.launch {
            for (detection in uiDetections) {
                if (detection.trackId >= 0) {
                    // Only emit for new tracks (this is simplified - in production
                    // you'd track which IDs have already been announced)
                }
            }
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

                    // Try to detect face and generate embedding
                    faceProcessor?.let { fp ->
                        val faceBitmap = cropBitmap(bitmap, rectF)
                        val faceResult = fp.processFace(faceBitmap)
                        if (faceResult != null) {
                            obj.faceEmbedding = faceResult.embedding
                            obj.faceConfidence = faceResult.confidence
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

                    // Try to read license plate
                    plateReader?.let { pr ->
                        val vehicleBitmap = cropBitmap(bitmap, rectF)
                        obj.licensePlate = pr.readPlate(vehicleBitmap)
                    }

                    detectedObjects.add(obj)
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
    }

    companion object {
        private const val TAG = "DetectionPipeline"
        private const val MAX_DETECTIONS = 10
        private const val CONFIDENCE_THRESHOLD = 0.5f
    }
}
