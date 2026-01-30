package com.sentinel.ml

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.facedetector.FaceDetector
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import kotlin.math.sqrt

data class FaceResult(
    val embedding: FloatArray,
    val confidence: Float
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as FaceResult
        return embedding.contentEquals(other.embedding) && confidence == other.confidence
    }

    override fun hashCode(): Int {
        var result = embedding.contentHashCode()
        result = 31 * result + confidence.hashCode()
        return result
    }
}

class FaceProcessor(private val context: Context) {
    private var faceDetector: FaceDetector? = null
    private var faceLandmarker: FaceLandmarker? = null

    init {
        initializeDetectors()
    }

    private fun initializeDetectors() {
        try {
            // Initialize Face Detector
            val detectorOptions = FaceDetector.FaceDetectorOptions.builder()
                .setBaseOptions(
                    BaseOptions.builder()
                        .setModelAssetPath("blaze_face_short_range.tflite")
                        .build()
                )
                .setMinDetectionConfidence(MIN_FACE_CONFIDENCE)
                .build()

            faceDetector = FaceDetector.createFromOptions(context, detectorOptions)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize face detector", e)
        }

        try {
            // Initialize Face Landmarker for embeddings
            val landmarkerOptions = FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(
                    BaseOptions.builder()
                        .setModelAssetPath("face_landmarker.task")
                        .build()
                )
                .setMinFaceDetectionConfidence(MIN_FACE_CONFIDENCE)
                .setMinFacePresenceConfidence(MIN_FACE_CONFIDENCE)
                .setMinTrackingConfidence(MIN_TRACKING_CONFIDENCE)
                .setNumFaces(1)
                .build()

            faceLandmarker = FaceLandmarker.createFromOptions(context, landmarkerOptions)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize face landmarker", e)
        }
    }

    fun processFace(bitmap: Bitmap): FaceResult? {
        val landmarker = faceLandmarker ?: return null

        return try {
            val mpImage = BitmapImageBuilder(bitmap).build()
            val result = landmarker.detect(mpImage)

            if (result.faceLandmarks().isNotEmpty()) {
                val embedding = generateEmbedding(result)
                val confidence = calculateConfidence(result)
                FaceResult(embedding, confidence)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Face processing failed", e)
            null
        }
    }

    private fun generateEmbedding(result: FaceLandmarkerResult): FloatArray {
        // Generate a simple embedding from face landmarks
        // Uses key facial landmarks to create a normalized feature vector
        val landmarks = result.faceLandmarks().firstOrNull() ?: return FloatArray(EMBEDDING_SIZE)

        val embedding = FloatArray(EMBEDDING_SIZE)
        val keyPoints = listOf(
            // Eyes
            33, 133, 362, 263,  // Left/right eye corners
            159, 145, 386, 374, // Eye top/bottom
            // Nose
            1, 2, 98, 327,
            // Mouth
            61, 291, 0, 17,
            // Face contour
            234, 454, 10, 152
        )

        var idx = 0
        for (i in keyPoints.indices) {
            if (i >= landmarks.size) break
            val point = landmarks[keyPoints[i].coerceIn(0, landmarks.size - 1)]
            embedding[idx++] = point.x()
            embedding[idx++] = point.y()
            embedding[idx++] = point.z()
            if (idx >= EMBEDDING_SIZE) break
        }

        // Normalize embedding
        val norm = sqrt(embedding.map { it * it }.sum())
        if (norm > 0) {
            for (i in embedding.indices) {
                embedding[i] /= norm
            }
        }

        return embedding
    }

    private fun calculateConfidence(result: FaceLandmarkerResult): Float {
        // Use presence confidence if available
        return result.faceBlendshapes()
            .firstOrNull()
            ?.firstOrNull()
            ?.score()
            ?: 0.8f
    }

    fun matchFace(embedding1: FloatArray, embedding2: FloatArray): Float {
        // Cosine similarity
        var dotProduct = 0f
        var norm1 = 0f
        var norm2 = 0f

        for (i in embedding1.indices) {
            dotProduct += embedding1[i] * embedding2[i]
            norm1 += embedding1[i] * embedding1[i]
            norm2 += embedding2[i] * embedding2[i]
        }

        val denominator = sqrt(norm1) * sqrt(norm2)
        return if (denominator > 0) dotProduct / denominator else 0f
    }

    fun close() {
        faceDetector?.close()
        faceLandmarker?.close()
    }

    companion object {
        private const val TAG = "FaceProcessor"
        private const val MIN_FACE_CONFIDENCE = 0.5f
        private const val MIN_TRACKING_CONFIDENCE = 0.5f
        const val EMBEDDING_SIZE = 128
        const val MATCH_THRESHOLD = 0.7f
    }
}
