package com.sentinel.ml

import android.graphics.RectF

/**
 * Represents a single detection result for UI display.
 */
data class Detection(
    val label: String,
    val confidence: Float,
    val boundingBox: RectF,
    val trackId: Int = -1
)
