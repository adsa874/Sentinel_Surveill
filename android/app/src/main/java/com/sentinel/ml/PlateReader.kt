package com.sentinel.ml

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class PlateReader(private val context: Context) {
    private val textRecognizer: TextRecognizer = TextRecognition.getClient(
        TextRecognizerOptions.DEFAULT_OPTIONS
    )

    // License plate patterns ordered by priority (Indian first)
    private val platePatterns = listOf(
        // Indian pattern: XX 00 XX 0000 (e.g., MH 12 AB 1234, KA 01 A 1234)
        Regex("[A-Z]{2}[- ]?\\d{2}[- ]?[A-Z]{1,2}[- ]?\\d{4}"),
        // US patterns
        Regex("[A-Z]{1,3}[- ]?\\d{1,4}[- ]?[A-Z]{0,3}"),
        Regex("\\d{1,3}[- ]?[A-Z]{1,3}[- ]?\\d{1,4}"),
        // European patterns
        Regex("[A-Z]{2}[- ]?\\d{2}[- ]?[A-Z]{3}"),
        // Generic pattern
        Regex("[A-Z0-9]{5,8}")
    )

    // Character substitutions for OCR correction (only applied as fallback)
    private val ocrSubstitutions = mapOf(
        'O' to '0',
        'I' to '1',
        'S' to '5'
    )

    suspend fun readPlateAsync(bitmap: Bitmap): String? = withTimeoutOrNull(3000L) {
        suspendCancellableCoroutine { cont ->
        try {
            val inputImage = InputImage.fromBitmap(bitmap, 0)

            textRecognizer.process(inputImage)
                .addOnSuccessListener { result ->
                    val rawText = result.text.uppercase()
                        .replace(" ", "")
                        .replace("\n", "")

                    // First try matching on raw text (preserves letters like O, I, S)
                    var plateText = extractPlateNumber(rawText)

                    // If no match, try with OCR substitutions
                    if (plateText == null) {
                        val correctedText = rawText.map { ch ->
                            ocrSubstitutions[ch] ?: ch
                        }.joinToString("")
                        plateText = extractPlateNumber(correctedText)
                    }

                    if (plateText != null) {
                        Log.d(TAG, "Plate detected: $plateText")
                    }
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
    }

    private fun extractPlateNumber(text: String): String? {
        val cleanedText = text
            .uppercase()
            .filter { it.isLetterOrDigit() || it == '-' }

        for (pattern in platePatterns) {
            val match = pattern.find(cleanedText)
            if (match != null) {
                val candidate = match.value
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
