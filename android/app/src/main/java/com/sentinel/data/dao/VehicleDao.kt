package com.sentinel.data.dao

import androidx.room.*
import com.sentinel.data.entities.VehicleEntity

@Dao
interface VehicleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(vehicle: VehicleEntity): Long

    @Query("SELECT * FROM vehicles ORDER BY lastSeen DESC LIMIT :limit")
    suspend fun getRecentVehicles(limit: Int = 50): List<VehicleEntity>

    @Query("SELECT * FROM vehicles WHERE licensePlate = :plate LIMIT 1")
    suspend fun getByLicensePlate(plate: String): VehicleEntity?

    @Query("SELECT COUNT(*) FROM vehicles")
    suspend fun getCount(): Int
}
