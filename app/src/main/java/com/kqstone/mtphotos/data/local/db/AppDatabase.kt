package com.kqstone.mtphotos.data.local.db

import android.content.Context
import androidx.room.Database
import androidx.room.migration.Migration
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [MediaEntity::class, MapPhotoEntity::class, ServerOpTaskEntity::class],
    version = 9,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun mediaDao(): MediaDao
    abstract fun mapPhotoDao(): MapPhotoDao
    abstract fun serverOpTaskDao(): ServerOpTaskDao

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
                    .addMigrations(
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7,
                        MIGRATION_7_8,
                        MIGRATION_8_9
                    )
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

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. 创建统一的 server_op_tasks 表
                createServerOpTasksTable(db)

                // 2. 将 cloud_delete_tasks 数据迁移到 server_op_tasks
                db.execSQL("""
                    INSERT OR IGNORE INTO server_op_tasks
                        (opType, status, mediaFileName, mediaMd5, mediaCloudId,
                         params, attemptCount, maxAttempts, nextAttemptAt,
                         lastError, createdAt, updatedAt)
                    SELECT
                        'CLOUD_DELETE',
                        CASE status
                            WHEN 'FAILED_WAITING' THEN 'ERROR'
                            WHEN 'RUNNING' THEN 'PENDING'
                            ELSE 'PENDING'
                        END,
                        fileName,
                        md5,
                        cloudId,
                        '{}',
                        attemptCount,
                        10,
                        nextAttemptAt,
                        lastError,
                        createdAt,
                        updatedAt
                    FROM cloud_delete_tasks
                """.trimIndent())

                // 3. 删除旧的 cloud_delete_tasks 表
                db.execSQL("DROP TABLE IF EXISTS cloud_delete_tasks")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE media ADD COLUMN isFavorite INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_isFavorite` ON `media` (`isFavorite`)")
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE media ADD COLUMN isHide INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_isHide` ON `media` (`isHide`)")
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

        private fun createServerOpTasksTable(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS server_op_tasks (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    opType TEXT NOT NULL,
                    status TEXT NOT NULL,
                    mediaFileName TEXT NOT NULL DEFAULT '',
                    mediaMd5 TEXT NOT NULL DEFAULT '',
                    mediaCloudId REAL,
                    params TEXT NOT NULL DEFAULT '{}',
                    attemptCount INTEGER NOT NULL DEFAULT 0,
                    maxAttempts INTEGER NOT NULL DEFAULT 10,
                    nextAttemptAt INTEGER NOT NULL,
                    lastError TEXT,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_server_op_tasks_status ON server_op_tasks(status)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_server_op_tasks_opType ON server_op_tasks(opType)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_server_op_tasks_nextAttemptAt ON server_op_tasks(nextAttemptAt)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_server_op_tasks_createdAt ON server_op_tasks(createdAt)")
        }
    }
}
