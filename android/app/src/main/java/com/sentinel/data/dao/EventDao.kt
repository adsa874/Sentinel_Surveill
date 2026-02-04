package com.sentinel.data.dao

import androidx.room.*
import com.sentinel.data.entities.EventEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface EventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: EventEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(events: List<EventEntity>)

    @Query("SELECT * FROM events ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentEvents(limit: Int = 100): Flow<List<EventEntity>>

    @Query("SELECT * FROM events WHERE synced = 0 ORDER BY timestamp ASC LIMIT :limit")
    suspend fun getUnsyncedEvents(limit: Int = 100): List<EventEntity>

    @Query("UPDATE events SET synced = 1 WHERE id IN (:ids)")
    suspend fun markAsSynced(ids: List<Long>)

    @Query("SELECT COUNT(*) FROM events WHERE timestamp > :startOfDay")
    fun getTodayEventCount(startOfDay: Long): Flow<Int>

    @Query("SELECT * FROM events WHERE type = :type AND timestamp BETWEEN :start AND :end")
    suspend fun getEventsByTypeAndTimeRange(type: String, start: Long, end: Long): List<EventEntity>

    @Query("SELECT * FROM events WHERE employeeId = :employeeId ORDER BY timestamp DESC")
    fun getEventsByEmployee(employeeId: String): Flow<List<EventEntity>>

    @Query("DELETE FROM events WHERE timestamp < :before")
    suspend fun deleteOldEvents(before: Long)

    @Query("DELETE FROM events")
    suspend fun deleteAll()

    companion object {
        fun getTodayStartMillis(): Long {
            val calendar = java.util.Calendar.getInstance()
            calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
            calendar.set(java.util.Calendar.MINUTE, 0)
            calendar.set(java.util.Calendar.SECOND, 0)
            calendar.set(java.util.Calendar.MILLISECOND, 0)
            return calendar.timeInMillis
        }
    }
}
