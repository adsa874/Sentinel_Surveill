package com.sentinel.ui

import android.graphics.Bitmap

data class ActivityEvent(
    val id: Long = System.currentTimeMillis(),
    val timestamp: Long = System.currentTimeMillis(),
    val type: EventType,
    val label: String,
    val confidence: Float = 0f,
    val duration: Long = 0,
    val thumbnail: Bitmap? = null,
    val details: String? = null
) {
    enum class EventType {
        DETECTION_START,    // Object first detected
        DETECTION_END,      // Object left frame
        FACE_RECOGNIZED,    // Known face identified
        FACE_UNKNOWN,       // Unknown face detected
        VEHICLE_PLATE,      // License plate read
        LOITERING,          // Loitering detected
        SYSTEM              // System events (start/stop)
    }

    fun getFormattedTime(): String {
        val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(timestamp))
    }

    fun getIconResId(): Int {
        return when (type) {
            EventType.DETECTION_START -> android.R.drawable.ic_menu_view
            EventType.DETECTION_END -> android.R.drawable.ic_menu_close_clear_cancel
            EventType.FACE_RECOGNIZED -> android.R.drawable.ic_menu_myplaces
            EventType.FACE_UNKNOWN -> android.R.drawable.ic_menu_help
            EventType.VEHICLE_PLATE -> android.R.drawable.ic_menu_directions
            EventType.LOITERING -> android.R.drawable.ic_menu_recent_history
            EventType.SYSTEM -> android.R.drawable.ic_menu_info_details
        }
    }

    fun getDisplayText(): String {
        return when (type) {
            EventType.DETECTION_START -> "$label detected"
            EventType.DETECTION_END -> "$label left (${formatDuration(duration)})"
            EventType.FACE_RECOGNIZED -> "Recognized: $label"
            EventType.FACE_UNKNOWN -> "Unknown face detected"
            EventType.VEHICLE_PLATE -> "Plate: $label"
            EventType.LOITERING -> "Loitering: $label"
            EventType.SYSTEM -> label
        }
    }

    private fun formatDuration(ms: Long): String {
        val seconds = ms / 1000
        return when {
            seconds < 60 -> "${seconds}s"
            seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
            else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
        }
    }
}
