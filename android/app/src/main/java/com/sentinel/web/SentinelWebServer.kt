package com.sentinel.web

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import com.google.gson.Gson
import com.sentinel.data.AppDatabase
import com.sentinel.ml.DetectionPipeline
import com.sentinel.tracking.MultiObjectTracker
import com.sentinel.ui.DetectionEventManager
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayInputStream
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream

class SentinelWebServer(
    private val context: Context,
    private val detectionPipeline: DetectionPipeline,
    private val database: AppDatabase,
    private val tracker: MultiObjectTracker,
    port: Int = 8080
) : NanoHTTPD("0.0.0.0", port) {

    private val gson = Gson()
    private val snapshotFilenamePattern = Regex("^[a-zA-Z0-9_\\-]+\\.(jpg|jpeg|png)$")

    private fun <T> dbQuery(timeoutMs: Long = 5000L, block: suspend () -> T): T? {
        return runBlocking(Dispatchers.IO) {
            withTimeoutOrNull(timeoutMs) { block() }
        }
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        return try {
            when {
                // Static assets
                method == Method.GET && uri == "/" -> serveAsset("web/index.html", "text/html")
                method == Method.GET && uri == "/camera" -> serveAsset("web/camera.html", "text/html")
                method == Method.GET && uri == "/style.css" -> serveAsset("web/style.css", "text/css")
                method == Method.GET && uri == "/app.js" -> serveAsset("web/app.js", "application/javascript")
                method == Method.GET && uri == "/camera.js" -> serveAsset("web/camera.js", "application/javascript")

                // API endpoints
                method == Method.GET && uri == "/api/stats" -> handleStats()
                method == Method.GET && uri.startsWith("/api/events/stream") -> handleSSE()
                method == Method.GET && uri.startsWith("/api/events") -> handleEvents(session)
                method == Method.GET && uri == "/api/employees" -> handleEmployees()
                method == Method.GET && uri.startsWith("/api/attendance") -> handleAttendance(session)
                method == Method.GET && uri == "/api/vehicles" -> handleVehicles()
                method == Method.GET && uri == "/api/tracks" -> handleTracks()
                method == Method.GET && uri.startsWith("/api/snapshot/") -> handleSnapshot(uri)

                // Streaming
                method == Method.GET && uri == "/stream/mjpeg" -> handleMjpeg()

                // Detection API
                method == Method.POST && uri == "/api/detect" -> handleDetect(session)

                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error serving $uri", e)
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "application/json",
                gson.toJson(mapOf("error" to (e.message ?: "Internal error")))
            )
        }
    }

    private fun serveAsset(path: String, mimeType: String): Response {
        return try {
            val inputStream = context.assets.open(path)
            val bytes = inputStream.readBytes()
            inputStream.close()
            val response = newFixedLengthResponse(Response.Status.OK, mimeType, ByteArrayInputStream(bytes), bytes.size.toLong())
            response.addHeader("Cache-Control", "no-cache")
            response
        } catch (e: Exception) {
            Log.e(TAG, "Asset not found: $path", e)
            newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Asset not found: $path")
        }
    }

    private fun handleStats(): Response {
        val fps = DetectionEventManager.fps.value
        val activeCount = DetectionEventManager.activeCount.value
        val inferenceTime = DetectionEventManager.inferenceTime.value
        val uptime = System.currentTimeMillis() - detectionPipeline.startTime
        val todayCount = dbQuery {
            try {
                database.eventDao().getRecentEventsSync(1000).size
            } catch (e: Exception) { 0 }
        } ?: 0

        val stats = mapOf(
            "fps" to fps,
            "activeCount" to activeCount,
            "inferenceTime" to inferenceTime,
            "uptime" to uptime,
            "todayEvents" to todayCount
        )
        return jsonResponse(stats)
    }

    private fun handleEvents(session: IHTTPSession): Response {
        val params = session.parameters
        val limit = params["limit"]?.firstOrNull()?.toIntOrNull() ?: 50

        val events = dbQuery { database.eventDao().getRecentEventsSync(limit) } ?: emptyList()

        val eventList = events.map { e ->
            mapOf(
                "id" to e.id,
                "type" to e.type,
                "timestamp" to e.timestamp,
                "trackId" to e.trackId,
                "employeeId" to (e.employeeId ?: ""),
                "licensePlate" to (e.licensePlate ?: ""),
                "duration" to e.duration,
                "snapshotPath" to (e.snapshotPath?.let { File(it).name } ?: "")
            )
        }
        return jsonResponse(eventList)
    }

    private fun handleSSE(): Response {
        val pipedOut = PipedOutputStream()
        val pipedIn = PipedInputStream(pipedOut)

        Thread {
            try {
                var lastActivityTime = System.currentTimeMillis()
                runBlocking {
                    DetectionEventManager.activityEvents.collect { event ->
                        // Idle timeout check
                        if (System.currentTimeMillis() - lastActivityTime > 300_000L) {
                            Log.d(TAG, "SSE idle timeout")
                            return@collect
                        }
                        val data = gson.toJson(
                            mapOf(
                                "id" to event.id,
                                "type" to event.type.name,
                                "label" to event.label,
                                "confidence" to event.confidence,
                                "duration" to event.duration,
                                "timestamp" to event.timestamp
                            )
                        )
                        try {
                            pipedOut.write("data: $data\n\n".toByteArray())
                            pipedOut.flush()
                            lastActivityTime = System.currentTimeMillis()
                        } catch (e: Exception) {
                            return@collect
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "SSE connection closed")
            } finally {
                try { pipedOut.close() } catch (_: Exception) {}
            }
        }.apply {
            isDaemon = true
            start()
        }

        val response = newChunkedResponse(Response.Status.OK, "text/event-stream", pipedIn)
        response.addHeader("Cache-Control", "no-cache")
        response.addHeader("Connection", "keep-alive")
        response.addHeader("Access-Control-Allow-Origin", "*")
        return response
    }

    private fun handleEmployees(): Response {
        val employees = dbQuery { database.employeeDao().getAllEmployeesSync() } ?: emptyList()

        val list = employees.map { e ->
            mapOf(
                "employeeId" to e.employeeId,
                "name" to e.name,
                "department" to (e.department ?: ""),
                "hasEmbedding" to (e.faceEmbedding != null),
                "createdAt" to e.createdAt
            )
        }
        return jsonResponse(list)
    }

    private fun handleAttendance(session: IHTTPSession): Response {
        val params = session.parameters
        val date = params["date"]?.firstOrNull()
            ?: java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                .format(java.util.Date())

        val records = dbQuery { database.attendanceDao().getAttendanceForDateSync(date) } ?: emptyList()

        val list = records.map { a ->
            mapOf(
                "id" to a.id,
                "employeeId" to a.employeeId,
                "date" to a.date,
                "checkInTime" to (a.checkInTime ?: 0),
                "checkOutTime" to (a.checkOutTime ?: 0),
                "totalDuration" to a.totalDuration
            )
        }
        return jsonResponse(list)
    }

    private fun handleVehicles(): Response {
        val vehicles = dbQuery { database.vehicleDao().getRecentVehicles(50) } ?: emptyList()

        val list = vehicles.map { v ->
            mapOf(
                "id" to v.id,
                "licensePlate" to v.licensePlate,
                "vehicleType" to (v.vehicleType ?: ""),
                "firstSeen" to v.firstSeen,
                "lastSeen" to v.lastSeen,
                "ownerId" to (v.ownerId ?: "")
            )
        }
        return jsonResponse(list)
    }

    private fun handleTracks(): Response {
        val tracks = tracker.getActiveTracks()
        val list = tracks.map { t ->
            mapOf(
                "trackId" to t.trackId,
                "type" to t.type.name,
                "state" to t.state.name,
                "bbox" to mapOf(
                    "left" to t.boundingBox.left,
                    "top" to t.boundingBox.top,
                    "right" to t.boundingBox.right,
                    "bottom" to t.boundingBox.bottom
                ),
                "duration" to t.duration,
                "employeeId" to (t.employeeId ?: ""),
                "licensePlate" to (t.licensePlate ?: "")
            )
        }
        return jsonResponse(list)
    }

    private fun handleSnapshot(uri: String): Response {
        val name = uri.removePrefix("/api/snapshot/")

        // Path traversal protection
        if (!snapshotFilenamePattern.matches(name)) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Invalid filename")
        }

        val snapshotDir = File(context.filesDir, "snapshots")
        val file = File(snapshotDir, name)

        // Verify canonical path is within snapshot directory
        if (!file.canonicalPath.startsWith(snapshotDir.canonicalPath)) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Access denied")
        }

        return if (file.exists()) {
            val bytes = file.readBytes()
            newFixedLengthResponse(Response.Status.OK, "image/jpeg", ByteArrayInputStream(bytes), bytes.size.toLong())
        } else {
            newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Snapshot not found")
        }
    }

    private fun handleMjpeg(): Response {
        val pipedOut = PipedOutputStream()
        val pipedIn = PipedInputStream(pipedOut, 256 * 1024)
        val boundary = "sentinelframe"

        Thread {
            try {
                var lastActivityTime = System.currentTimeMillis()
                while (true) {
                    // Read pre-compressed JPEG bytes directly (no bitmap re-compression)
                    val jpegBytes = detectionPipeline.currentFrameBytes.value
                    if (jpegBytes != null) {
                        val header = "--$boundary\r\n" +
                                "Content-Type: image/jpeg\r\n" +
                                "Content-Length: ${jpegBytes.size}\r\n\r\n"
                        pipedOut.write(header.toByteArray())
                        pipedOut.write(jpegBytes)
                        pipedOut.write("\r\n".toByteArray())
                        pipedOut.flush()
                        lastActivityTime = System.currentTimeMillis()
                    }

                    // Idle timeout: close stream if no frames for 60s
                    if (System.currentTimeMillis() - lastActivityTime > 60_000L) {
                        Log.d(TAG, "MJPEG idle timeout, closing stream")
                        break
                    }

                    Thread.sleep(100) // ~10 FPS
                }
            } catch (e: Exception) {
                Log.d(TAG, "MJPEG stream ended")
            } finally {
                try { pipedOut.close() } catch (_: Exception) {}
            }
        }.apply {
            isDaemon = true
            start()
        }

        val response = newChunkedResponse(
            Response.Status.OK,
            "multipart/x-mixed-replace; boundary=$boundary",
            pipedIn
        )
        response.addHeader("Cache-Control", "no-cache")
        response.addHeader("Connection", "keep-alive")
        return response
    }

    private fun handleDetect(session: IHTTPSession): Response {
        val contentType = session.headers["content-type"] ?: ""

        // Parse multipart form data
        val files = mutableMapOf<String, String>()
        session.parseBody(files)

        val imageData: ByteArray? = when {
            contentType.contains("multipart/form-data") -> {
                val tmpFile = files["image"]
                if (tmpFile != null) File(tmpFile).readBytes() else null
            }
            contentType.contains("image/jpeg") || contentType.contains("application/octet-stream") -> {
                val tmpFile = files["content"]
                if (tmpFile != null) File(tmpFile).readBytes() else null
            }
            else -> null
        }

        if (imageData == null) {
            return jsonResponse(mapOf("error" to "No image provided"), Response.Status.BAD_REQUEST)
        }

        val bitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.size)
            ?: return jsonResponse(mapOf("error" to "Invalid image"), Response.Status.BAD_REQUEST)

        val width = bitmap.width
        val height = bitmap.height
        val results = try {
            detectionPipeline.processExternalFrame(bitmap)
        } finally {
            bitmap.recycle()
        }

        return jsonResponse(mapOf(
            "detections" to results,
            "count" to results.size,
            "width" to width,
            "height" to height
        ))
    }

    private fun jsonResponse(data: Any, status: Response.Status = Response.Status.OK): Response {
        val json = gson.toJson(data)
        val response = newFixedLengthResponse(status, "application/json", json)
        response.addHeader("Access-Control-Allow-Origin", "*")
        return response
    }

    companion object {
        private const val TAG = "SentinelWebServer"
    }
}
