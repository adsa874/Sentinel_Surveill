package com.sentinel.events

enum class EventType {
    PERSON_ENTERED,
    PERSON_EXITED,
    EMPLOYEE_ARRIVED,
    EMPLOYEE_DEPARTED,
    VEHICLE_ENTERED,
    VEHICLE_EXITED,
    UNKNOWN_FACE_DETECTED,
    LOITERING_DETECTED
}

data class Event(
    val id: Long = 0,
    val type: EventType,
    val timestamp: Long,
    val trackId: Int,
    val employeeId: String? = null,
    val licensePlate: String? = null,
    val duration: Long = 0,
    val confidence: Float = 0f,
    val metadata: Map<String, String> = emptyMap(),
    val synced: Boolean = false,
    val snapshotPath: String? = null
)
