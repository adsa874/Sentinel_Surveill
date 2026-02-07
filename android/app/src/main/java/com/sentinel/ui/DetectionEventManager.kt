package com.sentinel.ui

import com.sentinel.ml.Detection
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Singleton to manage detection events and broadcast them to UI components.
 */
object DetectionEventManager {

    // Current detections (for overlay)
    private val _currentDetections = MutableStateFlow<List<Detection>>(emptyList())
    val currentDetections: StateFlow<List<Detection>> = _currentDetections.asStateFlow()

    // Activity events (for feed)
    private val _activityEvents = MutableSharedFlow<ActivityEvent>(replay = 10, extraBufferCapacity = 20)
    val activityEvents: SharedFlow<ActivityEvent> = _activityEvents.asSharedFlow()

    // Stats
    private val _fps = MutableStateFlow(0f)
    val fps: StateFlow<Float> = _fps.asStateFlow()

    private val _inferenceTime = MutableStateFlow(0L)
    val inferenceTime: StateFlow<Long> = _inferenceTime.asStateFlow()

    private val _activeCount = MutableStateFlow(0)
    val activeCount: StateFlow<Int> = _activeCount.asStateFlow()

    // Preview dimensions
    var previewWidth: Int = 640
    var previewHeight: Int = 480

    // Recent events list (in-memory)
    private val recentEvents = mutableListOf<ActivityEvent>()
    private const val MAX_RECENT_EVENTS = 50

    fun updateDetections(detections: List<Detection>) {
        _currentDetections.value = detections
        _activeCount.value = detections.size
    }

    fun updateStats(fps: Float, inferenceTimeMs: Long) {
        _fps.value = fps
        _inferenceTime.value = inferenceTimeMs
    }

    suspend fun addEvent(event: ActivityEvent) {
        synchronized(recentEvents) {
            recentEvents.add(0, event)
            if (recentEvents.size > MAX_RECENT_EVENTS) {
                recentEvents.removeAt(recentEvents.lastIndex)
            }
        }
        _activityEvents.emit(event)
    }

    fun getRecentEvents(): List<ActivityEvent> = synchronized(recentEvents) { recentEvents.toList() }

    fun clearEvents() {
        synchronized(recentEvents) { recentEvents.clear() }
    }

    // Helper to create detection events
    suspend fun onDetectionStart(label: String, confidence: Float) {
        addEvent(
            ActivityEvent(
                type = ActivityEvent.EventType.DETECTION_START,
                label = label,
                confidence = confidence
            )
        )
    }

    suspend fun onDetectionEnd(label: String, duration: Long) {
        addEvent(
            ActivityEvent(
                type = ActivityEvent.EventType.DETECTION_END,
                label = label,
                duration = duration
            )
        )
    }

    suspend fun onSystemEvent(message: String) {
        addEvent(
            ActivityEvent(
                type = ActivityEvent.EventType.SYSTEM,
                label = message
            )
        )
    }
}
