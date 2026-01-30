package com.sentinel.data.dao

import androidx.room.*
import com.sentinel.data.entities.EmployeeEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface EmployeeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(employee: EmployeeEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(employees: List<EmployeeEntity>)

    @Update
    suspend fun update(employee: EmployeeEntity)

    @Delete
    suspend fun delete(employee: EmployeeEntity)

    @Query("SELECT * FROM employees WHERE employeeId = :employeeId")
    suspend fun getById(employeeId: String): EmployeeEntity?

    @Query("SELECT * FROM employees ORDER BY name ASC")
    fun getAllEmployees(): Flow<List<EmployeeEntity>>

    @Query("SELECT * FROM employees")
    suspend fun getAllEmployeesSync(): List<EmployeeEntity>

    @Query("SELECT * FROM employees WHERE faceEmbedding IS NOT NULL")
    suspend fun getEmployeesWithEmbeddings(): List<EmployeeEntity>

    @Query("UPDATE employees SET faceEmbedding = :embedding, updatedAt = :timestamp WHERE employeeId = :employeeId")
    suspend fun updateEmbedding(employeeId: String, embedding: FloatArray, timestamp: Long = System.currentTimeMillis())

    @Query("SELECT COUNT(*) FROM employees")
    fun getEmployeeCount(): Flow<Int>

    @Query("DELETE FROM employees")
    suspend fun deleteAll()
}
