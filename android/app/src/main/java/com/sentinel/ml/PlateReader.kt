package com.sentinel.ml

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class PlateReader(private val context: Context) {
    private val textRecognizer: TextRecognizer = TextRecognition.getClient(
        TextRecognizerOptions.DEFAULT_OPTIONS
    )

    // Common license plate patterns
    private val platePatterns = listOf(
        // US patterns
        Regex("[A-Z]{1,3}[- ]?\\d{1,4}[- ]?[A-Z]{0,3}"),
        Regex("\\d{1,3}[- ]?[A-Z]{1,3}[- ]?\\d{1,4}"),
        // European patterns
        Regex("[A-Z]{2}[- ]?\\d{2}[- ]?[A-Z]{3}"),
        // Generic pattern
        Regex("[A-Z0-9]{5,8}")
    )

    fun readPlate(bitmap: Bitmap): String? {
        return try {
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            var plateText: String? = null

            textRecognizer.process(inputImage)
                .addOnSuccessListener { result ->
                    val allText = result.text.uppercase()
                        .replace("O", "0")
                        .replace("I", "1")
                        .replace("S", "5")
                        .replace(" ", "")

                    // Try to match license plate patterns
                    plateText = extractPlateNumber(allText)
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Text recognition failed", e)
                }

            plateText
        } catch (e: Exception) {
            Log.e(TAG, "Plate reading failed", e)
            null
        }
    }

    suspend fun readPlateAsync(bitmap: Bitmap): String? = suspendCancellableCoroutine { cont ->
        try {
            val inputImage = InputImage.fromBitmap(bitmap, 0)

            textRecognizer.process(inputImage)
                .addOnSuccessListener { result ->
                    val allText = result.text.uppercase()
                        .replace(" ", "")
                        .replace("\n", "")

                    val plateText = extractPlateNumber(allText)
                    cont.resume(plateText)
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Text recognition failed", e)
                    cont.resume(null)
                }
        } catch (e: Exception) {
            Log.e(TAG, "Plate reading failed", e)
            cont.resume(null)
        }
    }

    private fun extractPlateNumber(text: String): String? {
        // Clean up text
        val cleanedText = text
            .uppercase()
            .filter { it.isLetterOrDigit() || it == '-' }

        // Try each pattern
        for (pattern in platePatterns) {
            val match = pattern.find(cleanedText)
            if (match != null) {
                val candidate = match.value
                // Validate: should have at least 2 letters and 2 numbers
                val hasLetters = candidate.count { it.isLetter() } >= 2
                val hasNumbers = candidate.count { it.isDigit() } >= 2
                val goodLength = candidate.length in 5..10

                if (hasLetters && hasNumbers && goodLength) {
                    return candidate
                }
            }
        }

        // Fallback: return any alphanumeric sequence of good length
        val fallback = cleanedText.filter { it.isLetterOrDigit() }
        return if (fallback.length in 5..10) fallback else null
    }

    fun close() {
        textRecognizer.close()
    }

    companion object {
        private const val TAG = "PlateReader"
    }
}
