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

    fun processFrame(bitmap: Bitmap, timestamp: Long) {
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
