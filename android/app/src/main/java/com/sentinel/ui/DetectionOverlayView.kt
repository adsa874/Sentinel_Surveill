package com.sentinel.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.sentinel.ml.Detection

class DetectionOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var detections: List<Detection> = emptyList()
    private var previewWidth: Int = 1
    private var previewHeight: Int = 1

    private val boxPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }

    private val labelBackgroundPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val labelTextPaint = Paint().apply {
        color = Color.WHITE
        textSize = 36f
        isAntiAlias = true
        typeface = Typeface.DEFAULT_BOLD
    }

    private val colors = mapOf(
        "person" to Color.parseColor("#4CAF50"),      // Green
        "car" to Color.parseColor("#2196F3"),         // Blue
        "truck" to Color.parseColor("#2196F3"),       // Blue
        "motorcycle" to Color.parseColor("#FF9800"),  // Orange
        "bicycle" to Color.parseColor("#FF9800"),     // Orange
        "face" to Color.parseColor("#E91E63"),        // Pink
        "unknown" to Color.parseColor("#9E9E9E")      // Gray
    )

    fun setDetections(detections: List<Detection>, previewWidth: Int, previewHeight: Int) {
        this.detections = detections
        this.previewWidth = previewWidth
        this.previewHeight = previewHeight
        invalidate()
    }

    fun clear() {
        this.detections = emptyList()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (detections.isEmpty()) return

        val scaleX = width.toFloat() / previewWidth
        val scaleY = height.toFloat() / previewHeight

        for (detection in detections) {
            val color = colors[detection.label.lowercase()] ?: colors["unknown"]!!
            boxPaint.color = color
            labelBackgroundPaint.color = color

            // Scale bounding box to view size
            val left = detection.boundingBox.left * scaleX
            val top = detection.boundingBox.top * scaleY
            val right = detection.boundingBox.right * scaleX
            val bottom = detection.boundingBox.bottom * scaleY

            // Draw bounding box
            canvas.drawRect(left, top, right, bottom, boxPaint)

            // Draw label background
            val label = "${detection.label} ${(detection.confidence * 100).toInt()}%"
            val textBounds = Rect()
            labelTextPaint.getTextBounds(label, 0, label.length, textBounds)

            val labelPadding = 8f
            val labelRect = RectF(
                left,
                top - textBounds.height() - labelPadding * 2,
                left + textBounds.width() + labelPadding * 2,
                top
            )
            canvas.drawRect(labelRect, labelBackgroundPaint)

            // Draw label text
            canvas.drawText(
                label,
                left + labelPadding,
                top - labelPadding,
                labelTextPaint
            )
        }
    }
}
