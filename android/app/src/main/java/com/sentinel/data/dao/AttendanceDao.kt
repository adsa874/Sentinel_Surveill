package com.sentinel.data.dao

import androidx.room.*
import com.sentinel.data.entities.AttendanceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AttendanceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(attendance: AttendanceEntity): Long

    @Update
    suspend fun update(attendance: AttendanceEntity)

    @Query("SELECT * FROM attendance WHERE employeeId = :employeeId AND date = :date LIMIT 1")
    suspend fun getByEmployeeAndDate(employeeId: String, date: String): AttendanceEntity?

    @Query("SELECT * FROM attendance WHERE date = :date ORDER BY checkInTime ASC")
    fun getAttendanceForDate(date: String): Flow<List<AttendanceEntity>>

    @Query("SELECT * FROM attendance WHERE employeeId = :employeeId ORDER BY date DESC LIMIT :limit")
    fun getAttendanceHistory(employeeId: String, limit: Int = 30): Flow<List<AttendanceEntity>>

    @Query("""
        UPDATE attendance
        SET checkInTime = :checkInTime
        WHERE employeeId = :employeeId AND date = :date AND checkInTime IS NULL
    """)
    suspend fun recordCheckIn(employeeId: String, date: String, checkInTime: Long)

    @Query("""
        UPDATE attendance
        SET checkOutTime = :checkOutTime, totalDuration = :checkOutTime - checkInTime
        WHERE employeeId = :employeeId AND date = :date AND checkOutTime IS NULL
    """)
    suspend fun recordCheckOut(employeeId: String, date: String, checkOutTime: Long)

    @Query("DELETE FROM attendance WHERE date < :before")
    suspend fun deleteOldRecords(before: String)
}
