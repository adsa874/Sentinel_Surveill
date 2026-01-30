package com.sentinel.tracking

import android.graphics.RectF
import java.util.concurrent.atomic.AtomicInteger

enum class ObjectType {
    PERSON, VEHICLE
}

enum class TrackState {
    NEW,        // Just detected
    TRACKED,    // Being tracked
    LOST,       // Temporarily lost
    EXITED      // Left the scene
}

data class DetectedObject(
    val type: ObjectType,
    val boundingBox: RectF,
    val confidence: Float,
    val timestamp: Long,
    var faceEmbedding: FloatArray? = null,
    var faceConfidence: Float = 0f,
    var vehicleType: String? = null,
    var licensePlate: String? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as DetectedObject
        return type == other.type && boundingBox == other.boundingBox
    }

    override fun hashCode(): Int {
        return 31 * type.hashCode() + boundingBox.hashCode()
    }
}

data class TrackedObject(
    val trackId: Int,
    val type: ObjectType,
    var boundingBox: RectF,
    var state: TrackState,
    val firstSeenTime: Long,
    var lastSeenTime: Long,
    var faceEmbedding: FloatArray? = null,
    var employeeId: String? = null,
    var vehicleType: String? = null,
    var licensePlate: String? = null,
    var framesLost: Int = 0
) {
    val duration: Long
        get() = lastSeenTime - firstSeenTime

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as TrackedObject
        return trackId == other.trackId
    }

    override fun hashCode(): Int = trackId
}

class MultiObjectTracker {
    private val activeTracks = mutableMapOf<Int, TrackedObject>()
    private val exitedTracks = mutableListOf<TrackedObject>()
    private val trackIdCounter = AtomicInteger(0)

    fun update(detections: List<DetectedObject>, timestamp: Long): List<TrackedObject> {
        // Match detections to existing tracks using IoU
        val matched = mutableSetOf<Int>()
        val unmatchedDetections = mutableListOf<DetectedObject>()

        for (detection in detections) {
            var bestMatch: TrackedObject? = null
            var bestIoU = IOU_THRESHOLD

            for ((trackId, track) in activeTracks) {
                if (trackId in matched) continue
                if (track.type != detection.type) continue

                val iou = calculateIoU(track.boundingBox, detection.boundingBox)
                if (iou > bestIoU) {
                    bestIoU = iou
                    bestMatch = track
                }
            }

            if (bestMatch != null) {
                // Update existing track
                bestMatch.apply {
                    boundingBox = detection.boundingBox
                    lastSeenTime = timestamp
                    state = TrackState.TRACKED
                    framesLost = 0

                    // Update face embedding if better
                    if (detection.faceEmbedding != null &&
                        (faceEmbedding == null || detection.faceConfidence > 0.8f)
                    ) {
                        faceEmbedding = detection.faceEmbedding
                    }

                    // Update license plate if found
                    if (detection.licensePlate != null && licensePlate == null) {
                        licensePlate = detection.licensePlate
                    }
                }
                matched.add(bestMatch.trackId)
            } else {
                unmatchedDetections.add(detection)
            }
        }

        // Handle lost tracks
        val tracksToRemove = mutableListOf<Int>()
        for ((trackId, track) in activeTracks) {
            if (trackId !in matched) {
                track.framesLost++
                if (track.framesLost > MAX_FRAMES_LOST) {
                    track.state = TrackState.EXITED
                    exitedTracks.add(track)
                    tracksToRemove.add(trackId)
                } else {
                    track.state = TrackState.LOST
                }
            }
        }
        tracksToRemove.forEach { activeTracks.remove(it) }

        // Create new tracks for unmatched detections
        for (detection in unmatchedDetections) {
            val newTrackId = trackIdCounter.incrementAndGet()
            val newTrack = TrackedObject(
                trackId = newTrackId,
                type = detection.type,
                boundingBox = detection.boundingBox,
                state = TrackState.NEW,
                firstSeenTime = timestamp,
                lastSeenTime = timestamp,
                faceEmbedding = detection.faceEmbedding,
                vehicleType = detection.vehicleType,
                licensePlate = detection.licensePlate
            )
            activeTracks[newTrackId] = newTrack
        }

        // Return all current and recently exited tracks
        val result = activeTracks.values.toList() + exitedTracks
        exitedTracks.clear()

        return result
    }

    private fun calculateIoU(box1: RectF, box2: RectF): Float {
        val intersectionLeft = maxOf(box1.left, box2.left)
        val intersectionTop = maxOf(box1.top, box2.top)
        val intersectionRight = minOf(box1.right, box2.right)
        val intersectionBottom = minOf(box1.bottom, box2.bottom)

        if (intersectionRight <= intersectionLeft || intersectionBottom <= intersectionTop) {
            return 0f
        }

        val intersectionArea = (intersectionRight - intersectionLeft) *
                (intersectionBottom - intersectionTop)
        val box1Area = box1.width() * box1.height()
        val box2Area = box2.width() * box2.height()
        val unionArea = box1Area + box2Area - intersectionArea

        return if (unionArea > 0) intersectionArea / unionArea else 0f
    }

    fun getActiveTracks(): List<TrackedObject> = activeTracks.values.toList()

    fun getTrackById(trackId: Int): TrackedObject? = activeTracks[trackId]

    fun clear() {
        activeTracks.clear()
        exitedTracks.clear()
    }

    companion object {
        private const val IOU_THRESHOLD = 0.3f
        private const val MAX_FRAMES_LOST = 15
    }
}
