package com.sentinel.data

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.sentinel.data.dao.AttendanceDao
import com.sentinel.data.dao.EmployeeDao
import com.sentinel.data.dao.EventDao
import com.sentinel.data.dao.PersonDao
import com.sentinel.data.dao.VehicleDao
import com.sentinel.data.entities.*
import net.sqlcipher.database.SupportFactory
import net.sqlcipher.database.SQLiteDatabase
import java.io.File

@Database(
    entities = [
        EventEntity::class,
        PersonEntity::class,
        EmployeeEntity::class,
        VehicleEntity::class,
        AttendanceEntity::class
    ],
    version = 2,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun eventDao(): EventDao
    abstract fun personDao(): PersonDao
    abstract fun employeeDao(): EmployeeDao
    abstract fun attendanceDao(): AttendanceDao
    abstract fun vehicleDao(): VehicleDao

    companion object {
        private const val DATABASE_NAME = "sentinel_database"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                // Delete old unencrypted database if migrating to SQLCipher
                migrateToEncryptedDb(context)

                val passphrase = getOrCreatePassphrase(context)
                val factory = SupportFactory(passphrase)

                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME
                )
                    .openHelperFactory(factory)
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .build()
                INSTANCE = instance
                instance
            }
        }

        private fun migrateToEncryptedDb(context: Context) {
            val prefs = context.getSharedPreferences("sentinel_migration", Context.MODE_PRIVATE)
            if (prefs.getBoolean("encrypted_db_migrated", false)) return

            // If old unencrypted DB exists, delete it so SQLCipher can create a fresh one
            val dbFile = context.getDatabasePath(DATABASE_NAME)
            if (dbFile.exists()) {
                Log.d("AppDatabase", "Deleting old unencrypted database for SQLCipher migration")
                dbFile.delete()
                // Also delete WAL/SHM journal files
                File(dbFile.path + "-wal").delete()
                File(dbFile.path + "-shm").delete()
                File(dbFile.path + "-journal").delete()
            }
            prefs.edit().putBoolean("encrypted_db_migrated", true).apply()
        }

        private fun getOrCreatePassphrase(context: Context): ByteArray {
            val prefs = try {
                val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
                EncryptedSharedPreferences.create(
                    "sentinel_db_secure_prefs",
                    masterKeyAlias,
                    context,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (e: Exception) {
                Log.e("AppDatabase", "Failed to create encrypted prefs for DB passphrase", e)
                context.getSharedPreferences("sentinel_db_prefs", Context.MODE_PRIVATE)
            }

            val existing = prefs.getString("db_passphrase", null)
            if (existing != null) {
                return existing.toByteArray()
            }
            val passphrase = SQLiteDatabase.getBytes(
                java.util.UUID.randomUUID().toString().toCharArray()
            )
            prefs.edit().putString("db_passphrase", String(passphrase)).apply()
            return passphrase
        }
    }
}
