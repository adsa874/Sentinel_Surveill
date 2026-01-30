package com.sentinel.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.sentinel.data.dao.AttendanceDao
import com.sentinel.data.dao.EmployeeDao
import com.sentinel.data.dao.EventDao
import com.sentinel.data.dao.PersonDao
import com.sentinel.data.entities.*

@Database(
    entities = [
        EventEntity::class,
        PersonEntity::class,
        EmployeeEntity::class,
        VehicleEntity::class,
        AttendanceEntity::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun eventDao(): EventDao
    abstract fun personDao(): PersonDao
    abstract fun employeeDao(): EmployeeDao
    abstract fun attendanceDao(): AttendanceDao

    companion object {
        private const val DATABASE_NAME = "sentinel_database"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
