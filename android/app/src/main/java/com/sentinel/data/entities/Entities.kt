package com.sentinel.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class Converters {
    private val gson = Gson()

    @TypeConverter
    fun fromFloatArray(value: FloatArray?): String? {
        return value?.let { gson.toJson(it) }
    }

    @TypeConverter
    fun toFloatArray(value: String?): FloatArray? {
        return value?.let {
            val type = object : TypeToken<FloatArray>() {}.type
            gson.fromJson(it, type)
        }
    }

    @TypeConverter
    fun fromStringMap(value: Map<String, String>?): String? {
        return value?.let { gson.toJson(it) }
    }

    @TypeConverter
    fun toStringMap(value: String?): Map<String, String>? {
        return value?.let {
            val type = object : TypeToken<Map<String, String>>() {}.type
            gson.fromJson(it, type)
        }
    }
}

@Entity(tableName = "events")
data class EventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val type: String,
    val timestamp: Long,
    val trackId: Int,
    val employeeId: String? = null,
    val licensePlate: String? = null,
    val duration: Long = 0,
    val confidence: Float = 0f,
    val synced: Boolean = false
)

@Entity(tableName = "persons")
data class PersonEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val trackId: Int,
    val firstSeen: Long,
    val lastSeen: Long,
    @TypeConverters(Converters::class)
    val faceEmbedding: FloatArray? = null,
    val employeeId: String? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as PersonEntity
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}

@Entity(tableName = "employees")
data class EmployeeEntity(
    @PrimaryKey
    val employeeId: String,
    val name: String,
    val department: String? = null,
    @TypeConverters(Converters::class)
    val faceEmbedding: FloatArray? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as EmployeeEntity
        return employeeId == other.employeeId
    }

    override fun hashCode(): Int = employeeId.hashCode()
}

@Entity(tableName = "vehicles")
data class VehicleEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val licensePlate: String,
    val vehicleType: String? = null,
    val firstSeen: Long,
    val lastSeen: Long,
    val ownerId: String? = null
)

@Entity(tableName = "attendance")
data class AttendanceEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val employeeId: String,
    val date: String, // YYYY-MM-DD format
    val checkInTime: Long? = null,
    val checkOutTime: Long? = null,
    val totalDuration: Long = 0
)
