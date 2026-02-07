package com.sentinel.ml

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
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
    private var faceLandmarker: FaceLandmarker? = null
    private var tfliteInterpreter: Interpreter? = null

    // Reusable input buffer to avoid allocating 150KB every call
    private val inputBuffer: ByteBuffer = ByteBuffer.allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * 4).apply {
        order(ByteOrder.nativeOrder())
    }

    // Reusable pixel array and output buffer to avoid 50KB allocation per face per frame
    private val reusablePixels = IntArray(INPUT_SIZE * INPUT_SIZE)
    private val reusableOutput = Array(1) { FloatArray(EMBEDDING_SIZE) }

    init {
        initializeDetectors()
    }

    private fun initializeDetectors() {
        try {
            // Initialize Face Landmarker for face DETECTION (cropping)
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
            Log.d(TAG, "Face landmarker initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize face landmarker", e)
        }

        try {
            // Initialize MobileFaceNet TFLite interpreter for EMBEDDING generation
            val model = loadModelFile("mobilefacenet.tflite")
            val options = Interpreter.Options().apply {
                setNumThreads(2)
            }
            tfliteInterpreter = Interpreter(model, options)
            Log.d(TAG, "MobileFaceNet interpreter initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize MobileFaceNet interpreter", e)
        }
    }

    private fun loadModelFile(modelName: String): MappedByteBuffer {
        return context.assets.openFd(modelName).use { fileDescriptor ->
            FileInputStream(fileDescriptor.fileDescriptor).use { inputStream ->
                val fileChannel = inputStream.channel
                fileChannel.map(
                    FileChannel.MapMode.READ_ONLY,
                    fileDescriptor.startOffset,
                    fileDescriptor.declaredLength
                )
            }
        }
    }

    fun processFace(bitmap: Bitmap): FaceResult? {
        val landmarker = faceLandmarker ?: return null
        val interpreter = tfliteInterpreter ?: return null

        return try {
            val mpImage = BitmapImageBuilder(bitmap).build()
            val result = landmarker.detect(mpImage)

            if (result.faceLandmarks().isNotEmpty()) {
                val confidence = calculateConfidence(result)
                val embedding = generateMobileFaceNetEmbedding(bitmap, interpreter)
                Log.d(TAG, "Face embedding generated: ${embedding.size}-dim")
                FaceResult(embedding, confidence)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Face processing failed", e)
            null
        }
    }

    private fun generateMobileFaceNetEmbedding(faceBitmap: Bitmap, interpreter: Interpreter): FloatArray {
        // Resize face crop to 112x112 (MobileFaceNet input)
        val resized = Bitmap.createScaledBitmap(faceBitmap, INPUT_SIZE, INPUT_SIZE, true)

        // Prepare input: normalize pixel values (pixel - 127.5) / 128.0
        inputBuffer.clear()

        resized.getPixels(reusablePixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        resized.recycle()

        for (pixel in reusablePixels) {
            val r = ((pixel shr 16) and 0xFF).toFloat()
            val g = ((pixel shr 8) and 0xFF).toFloat()
            val b = (pixel and 0xFF).toFloat()
            inputBuffer.putFloat((r - 127.5f) / 128.0f)
            inputBuffer.putFloat((g - 127.5f) / 128.0f)
            inputBuffer.putFloat((b - 127.5f) / 128.0f)
        }

        // Run inference into reusable output buffer
        inputBuffer.rewind()
        interpreter.run(inputBuffer, reusableOutput)

        // L2 normalize the embedding
        val embedding = reusableOutput[0]
        val norm = sqrt(embedding.map { it * it }.sum())
        if (norm > 0) {
            for (i in embedding.indices) {
                embedding[i] /= norm
            }
        }

        return embedding
    }

    private fun calculateConfidence(result: com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult): Float {
        val blendshapes = result.faceBlendshapes()
        return if (blendshapes.isPresent && blendshapes.get().isNotEmpty()) {
            blendshapes.get().firstOrNull()?.firstOrNull()?.score() ?: 0.8f
        } else {
            0.8f
        }
    }

    fun matchFace(embedding1: FloatArray, embedding2: FloatArray): Float {
        // Cosine similarity
        var dotProduct = 0f
        var norm1 = 0f
        var norm2 = 0f

        val minSize = minOf(embedding1.size, embedding2.size)
        for (i in 0 until minSize) {
            dotProduct += embedding1[i] * embedding2[i]
            norm1 += embedding1[i] * embedding1[i]
            norm2 += embedding2[i] * embedding2[i]
        }

        val denominator = sqrt(norm1) * sqrt(norm2)
        return if (denominator > 0) dotProduct / denominator else 0f
    }

    fun close() {
        faceLandmarker?.close()
        tfliteInterpreter?.close()
    }

    companion object {
        private const val TAG = "FaceProcessor"
        private const val MIN_FACE_CONFIDENCE = 0.5f
        private const val MIN_TRACKING_CONFIDENCE = 0.5f
        private const val INPUT_SIZE = 112
        const val EMBEDDING_SIZE = 192
        const val MATCH_THRESHOLD = 0.7f
    }
}
