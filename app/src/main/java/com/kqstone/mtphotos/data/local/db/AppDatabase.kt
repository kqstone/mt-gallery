package com.kqstone.mtphotos.data.local.db

import android.content.Context
import androidx.room.Database
import androidx.room.migration.Migration
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [MediaEntity::class, MapPhotoEntity::class, CloudDeleteTaskEntity::class],
    version = 6,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun mediaDao(): MediaDao
    abstract fun mapPhotoDao(): MapPhotoDao
    abstract fun cloudDeleteTaskDao(): CloudDeleteTaskDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "mt_gallery_db"
                )
                    .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE media ADD COLUMN addr TEXT")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                createMapPhotosTable(db)
                createCloudDeleteTasksTable(db)
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                createMapPhotosTable(db)
                createCloudDeleteTasksTable(db)
            }
        }

        private fun createMapPhotosTable(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `map_photos` (
                    `cloudId` REAL NOT NULL,
                    `md5` TEXT NOT NULL,
                    `lat` REAL NOT NULL,
                    `lng` REAL NOT NULL,
                    `fileName` TEXT NOT NULL,
                    `fileType` TEXT NOT NULL,
                    `mtime` TEXT NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`cloudId`)
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_map_photos_md5` ON `map_photos` (`md5`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_map_photos_lat_lng` ON `map_photos` (`lat`, `lng`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_map_photos_updatedAt` ON `map_photos` (`updatedAt`)")
        }

        private fun createCloudDeleteTasksTable(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS cloud_delete_tasks (
                    cloudId REAL NOT NULL PRIMARY KEY,
                    md5 TEXT NOT NULL,
                    fileName TEXT NOT NULL,
                    status TEXT NOT NULL,
                    attemptCount INTEGER NOT NULL,
                    nextAttemptAt INTEGER NOT NULL,
                    lastError TEXT,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_cloud_delete_tasks_status ON cloud_delete_tasks(status)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_cloud_delete_tasks_nextAttemptAt ON cloud_delete_tasks(nextAttemptAt)")
        }
    }
}
