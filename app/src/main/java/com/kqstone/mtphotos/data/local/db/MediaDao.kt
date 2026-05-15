package com.kqstone.mtphotos.data.local.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaDao {

    // ===== 插入/更新 =====

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: MediaEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<MediaEntity>)

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

    // ===== 按时间分组查询（时间线视图） =====

    /** 获取月份分组统计 (返回 yearMonth 和 count) */
    @Query("""
        SELECT SUBSTR(mtime, 1, 7) AS yearMonth, COUNT(*) AS count 
        FROM media 
        GROUP BY SUBSTR(mtime, 1, 7) 
        ORDER BY yearMonth DESC
    """)
    suspend fun getTimelineMonths(): List<TimelineMonthCount>

    /** 获取指定月份的所有媒体 */
    @Query("SELECT * FROM media WHERE mtime LIKE :yearMonth || '%' ORDER BY mtime DESC")
    suspend fun getMediaByMonth(yearMonth: String): List<MediaEntity>

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
    @Query("SELECT * FROM media WHERE syncStatus = 'LOCAL_ONLY' AND backupStatus != 'UPLOADING' ORDER BY mtime DESC")
    suspend fun getPendingBackupMedia(): List<MediaEntity>

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
}

/**
 * 时间线月份统计结果
 */
data class TimelineMonthCount(
    val yearMonth: String,
    val count: Int
)
