package com.sentinel.data.dao

import androidx.room.*
import com.sentinel.data.entities.PersonEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PersonDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(person: PersonEntity): Long

    @Update
    suspend fun update(person: PersonEntity)

    @Query("SELECT * FROM persons WHERE trackId = :trackId LIMIT 1")
    suspend fun getByTrackId(trackId: Int): PersonEntity?

    @Query("SELECT * FROM persons ORDER BY lastSeen DESC LIMIT :limit")
    fun getRecentPersons(limit: Int = 50): Flow<List<PersonEntity>>

    @Query("SELECT COUNT(*) FROM persons WHERE lastSeen > :since")
    fun getRecentPersonCount(since: Long): Flow<Int>

    @Query("SELECT * FROM persons WHERE employeeId IS NULL AND faceEmbedding IS NOT NULL")
    suspend fun getUnidentifiedPersons(): List<PersonEntity>

    @Query("UPDATE persons SET employeeId = :employeeId WHERE id = :personId")
    suspend fun linkToEmployee(personId: Long, employeeId: String)

    @Query("DELETE FROM persons WHERE lastSeen < :before")
    suspend fun deleteOldPersons(before: Long)
}
