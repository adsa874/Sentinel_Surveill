package com.sentinel.events

import android.content.Context
import android.util.Log
import com.sentinel.data.AppDatabase
import com.sentinel.data.entities.EventEntity
import com.sentinel.ml.FaceProcessor
import com.sentinel.tracking.ObjectType
import com.sentinel.tracking.TrackState
import com.sentinel.tracking.TrackedObject
import com.sentinel.ui.ActivityEvent
import com.sentinel.ui.DetectionEventManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

class EventEngine(
    private val database: AppDatabase,
    private val scope: CoroutineScope,
    private val context: Context? = null
) {
    private val processedTracks = ConcurrentHashMap<Int, Pair<TrackState, Long>>()
    private val eventQueue = mutableListOf<Event>()
    private var lastSyncTime = 0L
    private var lastPruneTime = 0L

    // Known employee embeddings (loaded from database) — ConcurrentHashMap for thread safety
    private val employeeEmbeddings = ConcurrentHashMap<String, FloatArray>()

    // Current frame JPEG bytes for evidence capture (volatile for cross-thread visibility)
    @Volatile
    private var currentFrameJpeg: ByteArray? = null

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

    fun setCurrentFrameJpeg(bytes: ByteArray?) {
        currentFrameJpeg = bytes
    }

    fun processTrackedObjects(trackedObjects: List<TrackedObject>, timestamp: Long) {
        for (obj in trackedObjects) {
            val previous = processedTracks[obj.trackId]
            val previousState = previous?.first

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

            processedTracks[obj.trackId] = Pair(obj.state, timestamp)
        }

        // Cleanup old processed tracks
        cleanupOldTracks()

        // Batch sync events to database
        if (timestamp - lastSyncTime > SYNC_INTERVAL) {
            syncEvents()
            lastSyncTime = timestamp
        }

        // Daily pruning of old synced events
        if (timestamp - lastPruneTime > PRUNE_INTERVAL) {
            pruneOldEvents()
            lastPruneTime = timestamp
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

                val snapshotPath = if (eventType in HIGH_PRIORITY_EVENTS) {
                    saveSnapshot(timestamp)
                } else null

                queueEvent(
                    Event(
                        type = eventType,
                        timestamp = timestamp,
                        trackId = obj.trackId,
                        employeeId = employeeId,
                        snapshotPath = snapshotPath
                    )
                )
            }

            ObjectType.VEHICLE -> {
                val snapshotPath = saveSnapshot(timestamp)

                queueEvent(
                    Event(
                        type = EventType.VEHICLE_ENTERED,
                        timestamp = timestamp,
                        trackId = obj.trackId,
                        licensePlate = obj.licensePlate,
                        metadata = mapOf("vehicleType" to (obj.vehicleType ?: "unknown")),
                        snapshotPath = snapshotPath
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
        val key = "loitering_${obj.trackId}".hashCode()
        if (processedTracks.containsKey(key)) return

        val snapshotPath = saveSnapshot(timestamp)

        queueEvent(
            Event(
                type = EventType.LOITERING_DETECTED,
                timestamp = timestamp,
                trackId = obj.trackId,
                duration = obj.duration,
                snapshotPath = snapshotPath
            )
        )

        processedTracks[key] = Pair(TrackState.TRACKED, timestamp)
    }

    private fun saveSnapshot(timestamp: Long): String? {
        val jpegBytes = currentFrameJpeg ?: return null
        val ctx = context ?: return null

        return try {
            val snapshotDir = File(ctx.filesDir, "snapshots")
            if (!snapshotDir.exists()) snapshotDir.mkdirs()

            // Enforce snapshot limit
            enforceSnapshotLimit(snapshotDir)

            val file = File(snapshotDir, "event_${timestamp}.jpg")
            FileOutputStream(file).use { out ->
                out.write(jpegBytes)
            }
            Log.d(TAG, "Snapshot saved: ${file.absolutePath}")
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save snapshot", e)
            null
        }
    }

    private fun enforceSnapshotLimit(snapshotDir: File) {
        val files = snapshotDir.listFiles()?.sortedBy { it.lastModified() } ?: return
        if (files.size >= MAX_SNAPSHOTS) {
            val toDelete = files.take(files.size - MAX_SNAPSHOTS + 1)
            for (file in toDelete) {
                file.delete()
            }
        }
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

        // Emit to UI activity feed
        scope.launch {
            val activityEvent = when (event.type) {
                EventType.PERSON_ENTERED -> ActivityEvent(
                    type = ActivityEvent.EventType.DETECTION_START,
                    label = "Person",
                    confidence = 0f
                )
                EventType.PERSON_EXITED -> ActivityEvent(
                    type = ActivityEvent.EventType.DETECTION_END,
                    label = "Person",
                    duration = event.duration
                )
                EventType.EMPLOYEE_ARRIVED -> ActivityEvent(
                    type = ActivityEvent.EventType.FACE_RECOGNIZED,
                    label = event.employeeId ?: "Employee"
                )
                EventType.EMPLOYEE_DEPARTED -> ActivityEvent(
                    type = ActivityEvent.EventType.DETECTION_END,
                    label = event.employeeId ?: "Employee",
                    duration = event.duration
                )
                EventType.UNKNOWN_FACE_DETECTED -> ActivityEvent(
                    type = ActivityEvent.EventType.FACE_UNKNOWN,
                    label = "Unknown person"
                )
                EventType.VEHICLE_ENTERED -> ActivityEvent(
                    type = ActivityEvent.EventType.DETECTION_START,
                    label = event.metadata?.get("vehicleType") as? String ?: "Vehicle"
                )
                EventType.VEHICLE_EXITED -> ActivityEvent(
                    type = ActivityEvent.EventType.DETECTION_END,
                    label = "Vehicle",
                    duration = event.duration
                )
                EventType.LOITERING_DETECTED -> ActivityEvent(
                    type = ActivityEvent.EventType.LOITERING,
                    label = "Person loitering",
                    duration = event.duration
                )
            }
            DetectionEventManager.addEvent(activityEvent)
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
                        synced = false,
                        snapshotPath = event.snapshotPath
                    )
                }
                database.eventDao().insertAll(entities)
                Log.d(TAG, "Synced ${entities.size} events to database")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to sync events", e)
            }
        }
    }

    private fun pruneOldEvents() {
        scope.launch(Dispatchers.IO) {
            try {
                val sevenDaysAgo = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L
                database.eventDao().deleteSyncedEventsBefore(sevenDaysAgo)
                Log.d(TAG, "Pruned old synced events")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to prune old events", e)
            }
        }
    }

    private fun cleanupOldTracks() {
        val now = System.currentTimeMillis()
        val staleThreshold = 30 * 60 * 1000L // 30 minutes
        val staleKeys = processedTracks.filter { (_, pair) ->
            pair.first == TrackState.EXITED || (now - pair.second > staleThreshold)
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
        private const val PRUNE_INTERVAL = 24 * 60 * 60 * 1000L // 24 hours
        private const val MAX_SNAPSHOTS = 500

        private val HIGH_PRIORITY_EVENTS = setOf(
            EventType.EMPLOYEE_ARRIVED,
            EventType.UNKNOWN_FACE_DETECTED,
            EventType.LOITERING_DETECTED,
            EventType.VEHICLE_ENTERED
        )
    }
}
