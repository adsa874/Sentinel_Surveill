package com.sentinel.events

import android.util.Log
import com.sentinel.data.AppDatabase
import com.sentinel.data.entities.EventEntity
import com.sentinel.ml.FaceProcessor
import com.sentinel.tracking.ObjectType
import com.sentinel.tracking.TrackState
import com.sentinel.tracking.TrackedObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class EventEngine(
    private val database: AppDatabase,
    private val scope: CoroutineScope
) {
    private val processedTracks = ConcurrentHashMap<Int, TrackState>()
    private val eventQueue = mutableListOf<Event>()
    private var lastSyncTime = 0L

    // Known employee embeddings (loaded from database)
    private val employeeEmbeddings = mutableMapOf<String, FloatArray>()

    init {
        loadEmployeeEmbeddings()
    }

    private fun loadEmployeeEmbeddings() {
        scope.launch(Dispatchers.IO) {
            try {
                val employees = database.employeeDao().getAllEmployeesSync()
                for (employee in employees) {
                    employee.faceEmbedding?.let { embedding ->
                        employeeEmbeddings[employee.employeeId] = embedding
                    }
                }
                Log.d(TAG, "Loaded ${employeeEmbeddings.size} employee embeddings")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load employee embeddings", e)
            }
        }
    }

    fun processTrackedObjects(trackedObjects: List<TrackedObject>, timestamp: Long) {
        for (obj in trackedObjects) {
            val previousState = processedTracks[obj.trackId]

            when {
                // New track - generate entry event
                previousState == null && obj.state == TrackState.NEW -> {
                    handleNewObject(obj, timestamp)
                }

                // Track exited - generate exit event
                obj.state == TrackState.EXITED && previousState != TrackState.EXITED -> {
                    handleExitedObject(obj, timestamp)
                }

                // Check for loitering
                obj.state == TrackState.TRACKED &&
                        obj.type == ObjectType.PERSON &&
                        obj.duration > LOITERING_THRESHOLD -> {
                    handleLoitering(obj, timestamp)
                }
            }

            processedTracks[obj.trackId] = obj.state
        }

        // Cleanup old processed tracks
        cleanupOldTracks()

        // Batch sync events to database
        if (timestamp - lastSyncTime > SYNC_INTERVAL) {
            syncEvents()
            lastSyncTime = timestamp
        }
    }

    private fun handleNewObject(obj: TrackedObject, timestamp: Long) {
        when (obj.type) {
            ObjectType.PERSON -> {
                // Try to identify as employee
                val employeeId = obj.faceEmbedding?.let { matchEmployee(it) }
                obj.employeeId = employeeId

                val eventType = if (employeeId != null) {
                    EventType.EMPLOYEE_ARRIVED
                } else if (obj.faceEmbedding != null) {
                    EventType.UNKNOWN_FACE_DETECTED
                } else {
                    EventType.PERSON_ENTERED
                }

                queueEvent(
                    Event(
                        type = eventType,
                        timestamp = timestamp,
                        trackId = obj.trackId,
                        employeeId = employeeId
                    )
                )
            }

            ObjectType.VEHICLE -> {
                queueEvent(
                    Event(
                        type = EventType.VEHICLE_ENTERED,
                        timestamp = timestamp,
                        trackId = obj.trackId,
                        licensePlate = obj.licensePlate,
                        metadata = mapOf("vehicleType" to (obj.vehicleType ?: "unknown"))
                    )
                )
            }
        }
    }

    private fun handleExitedObject(obj: TrackedObject, timestamp: Long) {
        when (obj.type) {
            ObjectType.PERSON -> {
                val eventType = if (obj.employeeId != null) {
                    EventType.EMPLOYEE_DEPARTED
                } else {
                    EventType.PERSON_EXITED
                }

                queueEvent(
                    Event(
                        type = eventType,
                        timestamp = timestamp,
                        trackId = obj.trackId,
                        employeeId = obj.employeeId,
                        duration = obj.duration
                    )
                )
            }

            ObjectType.VEHICLE -> {
                queueEvent(
                    Event(
                        type = EventType.VEHICLE_EXITED,
                        timestamp = timestamp,
                        trackId = obj.trackId,
                        licensePlate = obj.licensePlate,
                        duration = obj.duration
                    )
                )
            }
        }
    }

    private fun handleLoitering(obj: TrackedObject, timestamp: Long) {
        // Only generate loitering event once per track
        val key = "loitering_${obj.trackId}"
        if (processedTracks.containsKey(key.hashCode())) return

        queueEvent(
            Event(
                type = EventType.LOITERING_DETECTED,
                timestamp = timestamp,
                trackId = obj.trackId,
                duration = obj.duration
            )
        )

        processedTracks[key.hashCode()] = TrackState.TRACKED
    }

    private fun matchEmployee(embedding: FloatArray): String? {
        var bestMatch: String? = null
        var bestScore = FaceProcessor.MATCH_THRESHOLD

        for ((employeeId, empEmbedding) in employeeEmbeddings) {
            val score = cosineSimilarity(embedding, empEmbedding)
            if (score > bestScore) {
                bestScore = score
                bestMatch = employeeId
            }
        }

        return bestMatch
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f

        var dotProduct = 0f
        var normA = 0f
        var normB = 0f

        for (i in a.indices) {
            dotProduct += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }

        val denominator = kotlin.math.sqrt(normA) * kotlin.math.sqrt(normB)
        return if (denominator > 0) dotProduct / denominator else 0f
    }

    private fun queueEvent(event: Event) {
        synchronized(eventQueue) {
            eventQueue.add(event)
        }
    }

    private fun syncEvents() {
        val eventsToSync: List<Event>
        synchronized(eventQueue) {
            eventsToSync = eventQueue.toList()
            eventQueue.clear()
        }

        if (eventsToSync.isEmpty()) return

        scope.launch(Dispatchers.IO) {
            try {
                val entities = eventsToSync.map { event ->
                    EventEntity(
                        type = event.type.name,
                        timestamp = event.timestamp,
                        trackId = event.trackId,
                        employeeId = event.employeeId,
                        licensePlate = event.licensePlate,
                        duration = event.duration,
                        synced = false
                    )
                }
                database.eventDao().insertAll(entities)
                Log.d(TAG, "Synced ${entities.size} events to database")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to sync events", e)
            }
        }
    }

    private fun cleanupOldTracks() {
        // Remove tracks that haven't been seen in a while
        val staleKeys = processedTracks.filter { (_, state) ->
            state == TrackState.EXITED
        }.keys
        staleKeys.forEach { processedTracks.remove(it) }
    }

    fun refreshEmployeeEmbeddings() {
        loadEmployeeEmbeddings()
    }

    companion object {
        private const val TAG = "EventEngine"
        private const val LOITERING_THRESHOLD = 300_000L // 5 minutes
        private const val SYNC_INTERVAL = 5_000L // 5 seconds
    }
}
