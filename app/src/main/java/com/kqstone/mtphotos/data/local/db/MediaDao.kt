package com.kqstone.mtphotos.data.local.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaDao {

    // ===== 插入/更新 =====

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: MediaEntity): Long

    /** 批量删除指定 ID */
    @Query("DELETE FROM media WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    /** 批量按 Room 主键查询 */
    @Query("SELECT * FROM media WHERE id IN (:ids)")
    suspend fun findByIds(ids: List<Long>): List<MediaEntity>

    /** 批量按 cloudId 查询 */
    @Query("SELECT * FROM media WHERE cloudId IN (:cloudIds)")
    suspend fun findByCloudIds(cloudIds: List<Double>): List<MediaEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<MediaEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIgnore(entities: List<MediaEntity>)

    /** 查找已存在的 localMediaStoreId 集合（用于去重） */
    @Query("SELECT localMediaStoreId FROM media WHERE localMediaStoreId IN (:ids)")
    suspend fun findExistingLocalIds(ids: List<Long>): List<Long>

    /** 查找已存在的 cloudId 集合（用于去重） */
    @Query("SELECT cloudId FROM media WHERE cloudId IN (:ids)")
    suspend fun findExistingCloudIds(ids: List<Double>): List<Double>

    @Update
    suspend fun update(entity: MediaEntity)

    @Delete
    suspend fun delete(entity: MediaEntity)

    @Query("DELETE FROM media WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM media WHERE cloudId = :cloudId")
    suspend fun deleteByCloudId(cloudId: Double)

    @Query("DELETE FROM media WHERE localMediaStoreId = :localMediaStoreId")
    suspend fun deleteByLocalMediaStoreId(localMediaStoreId: Long)

    // ===== 查询全部 =====

    /** 获取所有媒体（按拍摄时间降序），返回 Flow 以实时观察变化 */
    @Query("SELECT * FROM media ORDER BY mtime DESC")
    fun getAllMediaFlow(): Flow<List<MediaEntity>>

    @Query("SELECT * FROM media ORDER BY mtime DESC")
    suspend fun getAllMedia(): List<MediaEntity>

    @Query("SELECT * FROM media WHERE cloudId IS NOT NULL ORDER BY mtime DESC")
    suspend fun getAllCloudMedia(): List<MediaEntity>

    @Query("""
        SELECT * FROM media
        WHERE cloudId IS NOT NULL OR localFolderPath IN (:folderPaths)
        ORDER BY mtime DESC
    """)
    suspend fun getAllVisibleMediaByFolders(folderPaths: List<String>): List<MediaEntity>

    // ===== 按时间分组查询（时间线视图） =====

    /** 获取月份分组统计 (返回 yearMonth 和 count) */
    @Query("""
        SELECT SUBSTR(mtime, 1, 7) AS yearMonth, COUNT(*) AS count 
        FROM media 
        GROUP BY SUBSTR(mtime, 1, 7) 
        ORDER BY yearMonth DESC
    """)
    suspend fun getTimelineMonths(): List<TimelineMonthCount>

    @Query("""
        SELECT SUBSTR(mtime, 1, 7) AS yearMonth, COUNT(*) AS count
        FROM media
        WHERE cloudId IS NOT NULL
        GROUP BY SUBSTR(mtime, 1, 7)
        ORDER BY yearMonth DESC
    """)
    suspend fun getCloudTimelineMonths(): List<TimelineMonthCount>

    @Query("""
        SELECT SUBSTR(mtime, 1, 7) AS yearMonth, COUNT(*) AS count
        FROM media
        WHERE cloudId IS NOT NULL OR localFolderPath IN (:folderPaths)
        GROUP BY SUBSTR(mtime, 1, 7)
        ORDER BY yearMonth DESC
    """)
    suspend fun getTimelineMonthsByVisibleFolders(folderPaths: List<String>): List<TimelineMonthCount>

    /** 获取指定月份的所有媒体 */
    @Query("SELECT * FROM media WHERE mtime LIKE :yearMonth || '%' ORDER BY mtime DESC")
    suspend fun getMediaByMonth(yearMonth: String): List<MediaEntity>

    @Query("""
        SELECT * FROM media
        WHERE mtime LIKE :yearMonth || '%'
        AND cloudId IS NOT NULL
        ORDER BY mtime DESC
    """)
    suspend fun getCloudMediaByMonth(yearMonth: String): List<MediaEntity>

    @Query("""
        SELECT * FROM media
        WHERE mtime LIKE :yearMonth || '%'
        AND (cloudId IS NOT NULL OR localFolderPath IN (:folderPaths))
        ORDER BY mtime DESC
    """)
    suspend fun getMediaByMonthVisibleFolders(
        yearMonth: String,
        folderPaths: List<String>
    ): List<MediaEntity>

    /** 获取指定月份的所有媒体（Flow） */
    @Query("SELECT * FROM media WHERE mtime LIKE :yearMonth || '%' ORDER BY mtime DESC")
    fun getMediaByMonthFlow(yearMonth: String): Flow<List<MediaEntity>>

    // ===== 按 MD5 查找（去重匹配） =====

    @Query("SELECT * FROM media WHERE md5 = :md5 LIMIT 1")
    suspend fun findByMd5(md5: String): MediaEntity?

    @Query("SELECT * FROM media WHERE md5 IN (:md5List)")
    suspend fun findByMd5List(md5List: List<String>): List<MediaEntity>

    // ===== 按 ID 查找 =====

    @Query("SELECT * FROM media WHERE id = :id")
    suspend fun findById(id: Long): MediaEntity?

    @Query("SELECT * FROM media WHERE cloudId = :cloudId LIMIT 1")
    suspend fun findByCloudId(cloudId: Double): MediaEntity?

    @Query("SELECT * FROM media WHERE localMediaStoreId = :localId LIMIT 1")
    suspend fun findByLocalMediaStoreId(localId: Long): MediaEntity?

    // ===== 按同步状态过滤 =====

    @Query("SELECT * FROM media WHERE syncStatus = :status ORDER BY mtime DESC")
    suspend fun getMediaBySyncStatus(status: SyncStatus): List<MediaEntity>

    @Query("SELECT * FROM media WHERE syncStatus = :status ORDER BY mtime DESC")
    fun getMediaBySyncStatusFlow(status: SyncStatus): Flow<List<MediaEntity>>

    /** 获取所有仅本地的文件（待备份） */
    @Query("SELECT * FROM media WHERE syncStatus = 'LOCAL_ONLY' AND backupStatus IN ('NOT_STARTED', 'FAILED') AND md5 != '' ORDER BY mtime DESC")
    suspend fun getPendingBackupMedia(): List<MediaEntity>

    @Query("""
        SELECT * FROM media
        WHERE syncStatus = 'LOCAL_ONLY'
        AND backupStatus IN ('NOT_STARTED', 'FAILED')
        AND md5 != ''
        AND localFolderPath IN (:folderPaths)
        ORDER BY mtime DESC
    """)
    suspend fun getPendingBackupMediaByFolders(folderPaths: List<String>): List<MediaEntity>

    @Query("SELECT * FROM media WHERE cloudId IS NOT NULL")
    suspend fun getCloudBoundMedia(): List<MediaEntity>

    /** 获取已备份但本地原图未清理的文件 */
    @Query("""
        SELECT * FROM media 
        WHERE backupStatus = 'COMPLETED' 
        AND localUri IS NOT NULL 
        AND isStorageOptimized = 0 
        ORDER BY fileSize DESC
    """)
    suspend fun getOptimizableMedia(): List<MediaEntity>

    // ===== 批量更新备份状态 =====

    @Query("UPDATE media SET backupStatus = :status, updatedAt = :now WHERE id = :id")
    suspend fun updateBackupStatus(id: Long, status: BackupStatus, now: Long = System.currentTimeMillis())

    @Query("""
        UPDATE media
        SET syncStatus = 'LOCAL_ONLY',
            backupStatus = 'FAILED',
            cloudId = NULL,
            cloudMd5 = NULL,
            updatedAt = :now
        WHERE id IN (:ids)
    """)
    suspend fun markBackupsForRetry(ids: List<Long>, now: Long = System.currentTimeMillis()): Int

    @Query("""
        UPDATE media
        SET syncStatus = 'LOCAL_ONLY',
            backupStatus = 'FAILED',
            cloudId = NULL,
            cloudMd5 = NULL,
            updatedAt = :now
        WHERE md5 IN (:md5s)
        AND localMediaStoreId IS NOT NULL
        AND backupStatus != 'REMOTE_DELETED'
    """)
    suspend fun markMissingBackupsForRetry(md5s: List<String>, now: Long = System.currentTimeMillis()): Int

    @Query("""
        UPDATE media
        SET syncStatus = 'LOCAL_ONLY',
            backupStatus = 'FAILED',
            cloudId = NULL,
            cloudMd5 = NULL,
            updatedAt = :now
        WHERE md5 IN (:md5s)
        AND localMediaStoreId IS NOT NULL
    """)
    suspend fun markMissingBackupsForRetryIncludingRemoteDeleted(
        md5s: List<String>,
        now: Long = System.currentTimeMillis()
    ): Int

    @Query("""
        UPDATE media
        SET backupStatus = :status, updatedAt = :now
        WHERE id = :id
        AND syncStatus = 'LOCAL_ONLY'
        AND backupStatus IN ('NOT_STARTED', 'FAILED')
    """)
    suspend fun claimForUpload(
        id: Long,
        status: BackupStatus = BackupStatus.UPLOADING,
        now: Long = System.currentTimeMillis()
    ): Int

    @Query("""
        UPDATE media
        SET backupStatus = 'FAILED', updatedAt = :now
        WHERE backupStatus = 'UPLOADING'
        AND updatedAt < :staleBefore
    """)
    suspend fun resetStaleUploading(
        staleBefore: Long,
        now: Long = System.currentTimeMillis()
    ): Int

    @Query("UPDATE media SET syncStatus = :syncStatus, backupStatus = :backupStatus, cloudId = :cloudId, cloudMd5 = :cloudMd5, updatedAt = :now WHERE id = :id")
    suspend fun markAsBackedUp(
        id: Long,
        syncStatus: SyncStatus = SyncStatus.SYNCED,
        backupStatus: BackupStatus = BackupStatus.COMPLETED,
        cloudId: Double,
        cloudMd5: String,
        now: Long = System.currentTimeMillis()
    )

    @Query("UPDATE media SET isStorageOptimized = 1, localUri = NULL, localPath = NULL, updatedAt = :now WHERE id = :id")
    suspend fun markAsStorageOptimized(id: Long, now: Long = System.currentTimeMillis())

    @Query("UPDATE media SET thumbCachePath = :path, updatedAt = :now WHERE id = :id")
    suspend fun updateThumbCachePath(id: Long, path: String, now: Long = System.currentTimeMillis())

    // ===== 统计 =====

    @Query("SELECT COUNT(*) FROM media")
    suspend fun getTotalCount(): Int

    @Query("SELECT COUNT(*) FROM media WHERE syncStatus = :status")
    suspend fun getCountBySyncStatus(status: SyncStatus): Int

    @Query("SELECT COUNT(*) FROM media WHERE backupStatus = :status")
    suspend fun getCountByBackupStatus(status: BackupStatus): Int

    @Query("SELECT COUNT(*) FROM media WHERE syncStatus = 'LOCAL_ONLY' AND backupStatus IN ('NOT_STARTED', 'FAILED') AND md5 != ''")
    suspend fun getPendingBackupCount(): Int

    @Query("SELECT COUNT(*) FROM media WHERE backupStatus = 'COMPLETED' AND localUri IS NOT NULL AND isStorageOptimized = 0")
    suspend fun getOptimizableCount(): Int

    @Query("SELECT COALESCE(SUM(fileSize), 0) FROM media WHERE backupStatus = 'COMPLETED' AND localUri IS NOT NULL AND isStorageOptimized = 0")
    suspend fun getOptimizableSize(): Long

    @Query("SELECT COUNT(*) FROM media WHERE backupStatus = 'COMPLETED'")
    suspend fun getBackedUpCount(): Int

    @Query("SELECT COALESCE(SUM(fileSize), 0) FROM media WHERE backupStatus = 'COMPLETED'")
    suspend fun getBackedUpSize(): Long

    // ===== 按文件夹查询 =====

    @Query("SELECT DISTINCT localFolderPath FROM media WHERE localFolderPath IS NOT NULL ORDER BY localFolderPath")
    suspend fun getAllLocalFolders(): List<String>

    @Query("SELECT * FROM media WHERE localFolderPath = :folderPath ORDER BY mtime DESC")
    suspend fun getMediaByFolder(folderPath: String): List<MediaEntity>

    // ===== 孤立记录清理 =====

    /** 查询所有有 localMediaStoreId 的记录（仅返回清理所需字段） */
    @Query("SELECT id, localMediaStoreId, syncStatus, cloudId FROM media WHERE localMediaStoreId IS NOT NULL")
    suspend fun getLocalFileRefs(): List<LocalFileRef>

    /** 批量清除本地文件字段（SYNCED → CLOUD_ONLY） */
    @Query("UPDATE media SET syncStatus = 'CLOUD_ONLY', localUri = NULL, localPath = NULL, localMediaStoreId = NULL, updatedAt = :now WHERE id IN (:ids)")
    suspend fun clearLocalFields(ids: List<Long>, now: Long = System.currentTimeMillis())

    @Query("UPDATE media SET syncStatus = 'LOCAL_ONLY', backupStatus = 'REMOTE_DELETED', cloudId = NULL, cloudMd5 = NULL, updatedAt = :now WHERE id IN (:ids)")
    suspend fun clearCloudFieldsAsRemoteDeleted(ids: List<Long>, now: Long = System.currentTimeMillis())
}

/**
 * 时间线月份统计结果
 */
data class TimelineMonthCount(
    val yearMonth: String,
    val count: Int
)

/**
 * 孤立记录清理用的轻量数据类
 */
data class LocalFileRef(
    val id: Long,
    val localMediaStoreId: Long,
    val syncStatus: SyncStatus,
    val cloudId: Double?
)
