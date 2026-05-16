package com.kqstone.mtphotos.data.repository

import android.util.Log
import com.kqstone.mtphotos.AppContainer
import com.kqstone.mtphotos.data.local.LocalMediaScanner
import com.kqstone.mtphotos.data.local.db.AppDatabase
import com.kqstone.mtphotos.data.local.db.BackupStatus
import com.kqstone.mtphotos.data.local.db.MediaDao
import com.kqstone.mtphotos.data.local.db.MediaEntity
import com.kqstone.mtphotos.data.local.db.SyncStatus
import com.kqstone.mtphotos.data.local.db.TimelineMonthCount
import com.kqstone.mtphotos.data.model.UnifiedPhotoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private const val TAG = "SyncRepo"

/**
 * 同步仓库：负责合并本地 MediaStore 数据与云端服务器数据。
 * 使用 MD5 作为匹配键，确定每个文件的 SyncStatus。
 * 将合并结果写入 Room 数据库，为 UI 提供统一的数据源。
 */
class SyncRepository(
    private val container: AppContainer,
    private val database: AppDatabase
) {
    private val mediaDao: MediaDao get() = database.mediaDao()
    private val galleryRepository: GalleryRepository get() = container.galleryRepository
    private val localScanner: LocalMediaScanner by lazy { LocalMediaScanner(container.prefsManager.context) }

    /**
     * 执行完整的本地+云端数据合并。
     * 1. 扫描本地 MediaStore
     * 2. 拉取云端时间线数据
     * 3. 基于 MD5 匹配，确定每个文件的 SyncStatus
     * 4. 将结果写入 Room
     *
     * @param localFolders 要扫描的本地文件夹列表，null 为全部
     */
    suspend fun performFullSync(localFolders: Set<String>? = null) = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting full sync...")

            // Step 1: 扫描本地
            val localMedia = localScanner.scanMedia(localFolders, computeMd5 = true)
            Log.d(TAG, "Local scan found ${localMedia.size} files")

            // Step 2: 拉取云端时间线（获取所有月份及其文件）
            val cloudPhotos = fetchAllCloudPhotos()
            Log.d(TAG, "Cloud has ${cloudPhotos.size} files")

            // Step 3: 基于 MD5 合并
            val cloudMd5Map = cloudPhotos.associateBy { it.md5 }
            val localMd5Map = localMedia.filter { it.md5.isNotEmpty() }.associateBy { it.md5 }

            val mergedEntities = mutableListOf<MediaEntity>()
            val processedMd5s = mutableSetOf<String>()

            // 处理本地文件
            for (local in localMedia) {
                if (local.md5.isEmpty()) {
                    // MD5 计算失败，视为仅本地
                    mergedEntities.add(local)
                    continue
                }

                val cloud = cloudMd5Map[local.md5]
                if (cloud != null) {
                    // 本地和云端都有 → SYNCED
                    val existing = mediaDao.findByMd5(local.md5)
                    mergedEntities.add(
                        (existing ?: local).copy(
                            localMediaStoreId = local.localMediaStoreId,
                            localUri = local.localUri,
                            localPath = local.localPath,
                            localFolderPath = local.localFolderPath,
                            cloudId = cloud.cloudId,
                            cloudMd5 = cloud.md5,
                            md5 = local.md5,
                            fileName = local.fileName.ifEmpty { cloud.fileName },
                            fileType = local.fileType.ifEmpty { cloud.fileType },
                            mtime = local.mtime.ifEmpty { cloud.mtime },
                            width = if (local.width > 0) local.width else cloud.width,
                            height = if (local.height > 0) local.height else cloud.height,
                            fileSize = if (local.fileSize > 0) local.fileSize else cloud.fileSize,
                            syncStatus = SyncStatus.SYNCED,
                            backupStatus = BackupStatus.COMPLETED,
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                    processedMd5s.add(local.md5)
                } else {
                    // 仅本地
                    val existing = mediaDao.findByMd5(local.md5)
                    mergedEntities.add(
                        (existing ?: local).copy(
                            localMediaStoreId = local.localMediaStoreId,
                            localUri = local.localUri,
                            localPath = local.localPath,
                            localFolderPath = local.localFolderPath,
                            md5 = local.md5,
                            fileName = local.fileName,
                            fileType = local.fileType,
                            mtime = local.mtime,
                            width = local.width,
                            height = local.height,
                            fileSize = local.fileSize,
                            syncStatus = SyncStatus.LOCAL_ONLY,
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                    processedMd5s.add(local.md5)
                }
            }

            // 处理仅云端文件
            for (cloud in cloudPhotos) {
                if (cloud.md5 in processedMd5s) continue
                val existing = mediaDao.findByMd5(cloud.md5)
                mergedEntities.add(
                    (existing ?: cloud).copy(
                        cloudId = cloud.cloudId,
                        cloudMd5 = cloud.md5,
                        md5 = cloud.md5,
                        fileName = cloud.fileName,
                        fileType = cloud.fileType,
                        mtime = cloud.mtime,
                        width = cloud.width,
                        height = cloud.height,
                        syncStatus = SyncStatus.CLOUD_ONLY,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }

            // Step 4: 写入 Room（批量操作）
            mediaDao.insertAll(mergedEntities)
            Log.d(TAG, "Full sync complete: ${mergedEntities.size} entities (${mergedEntities.count { it.syncStatus == SyncStatus.SYNCED }} synced, ${mergedEntities.count { it.syncStatus == SyncStatus.LOCAL_ONLY }} local-only, ${mergedEntities.count { it.syncStatus == SyncStatus.CLOUD_ONLY }} cloud-only)")

        } catch (e: Exception) {
            Log.e(TAG, "Full sync failed", e)
            throw e
        }
    }

    /**
     * 仅同步云端数据（不扫描本地），用于未开启备份模式时。
     */
    suspend fun syncCloudOnly() = withContext(Dispatchers.IO) {
        try {
            val cloudPhotos = fetchAllCloudPhotos()
            Log.d(TAG, "Cloud-only sync: ${cloudPhotos.size} files")
            mediaDao.insertAll(cloudPhotos)
        } catch (e: Exception) {
            Log.e(TAG, "Cloud-only sync failed", e)
            throw e
        }
    }

    /**
     * 从云端获取所有照片信息。
     * 遍历时间线月份列表，逐月加载详情。
     */
    private suspend fun fetchAllCloudPhotos(): List<MediaEntity> {
        val result = mutableListOf<MediaEntity>()
        val timelineResult = galleryRepository.getTimeline()
        val months = timelineResult.getOrNull() ?: return result

        for (month in months) {
            val filesResult = galleryRepository.getMonthFiles(month.yearMonth)
            val files = filesResult.getOrNull() ?: continue
            for (photo in files) {
                result.add(
                    MediaEntity(
                        cloudId = photo.id,
                        md5 = photo.md5,
                        cloudMd5 = photo.md5,
                        fileName = photo.fileName,
                        fileType = photo.fileType,
                        mtime = photo.mtime,
                        width = photo.width.toInt(),
                        height = photo.height.toInt(),
                        syncStatus = SyncStatus.CLOUD_ONLY,
                        backupStatus = BackupStatus.NOT_STARTED
                    )
                )
            }
        }
        return result
    }

    // ===== 数据查询接口 =====

    /** 获取时间线月份统计 */
    suspend fun getTimelineMonths(): List<TimelineMonthCount> {
        return mediaDao.getTimelineMonths()
    }

    /** 获取指定月份的统一照片列表 */
    suspend fun getMonthPhotos(yearMonth: String): List<UnifiedPhotoItem> {
        return mediaDao.getMediaByMonth(yearMonth).map { it.toUnifiedPhotoItem() }
    }

    /** 获取指定月份的统一照片列表（Flow） */
    fun getMonthPhotosFlow(yearMonth: String): Flow<List<UnifiedPhotoItem>> {
        return mediaDao.getMediaByMonthFlow(yearMonth).map { entities ->
            entities.map { it.toUnifiedPhotoItem() }
        }
    }

    /** 获取所有媒体（Flow） */
    fun getAllMediaFlow(): Flow<List<UnifiedPhotoItem>> {
        return mediaDao.getAllMediaFlow().map { entities ->
            entities.map { it.toUnifiedPhotoItem() }
        }
    }

    /** 获取待备份的文件列表 */
    suspend fun getPendingBackupMedia(): List<MediaEntity> {
        return mediaDao.getPendingBackupMedia()
    }

    /** 获取可优化存储的文件列表 */
    suspend fun getOptimizableMedia(): List<MediaEntity> {
        return mediaDao.getOptimizableMedia()
    }

    /** 获取可释放的存储空间 */
    suspend fun getOptimizableSize(): Long {
        return mediaDao.getOptimizableSize()
    }

    /** 获取已同步的文件数量（服务端已有的文件） */
    suspend fun getSyncedCount(): Int {
        return mediaDao.getCountBySyncStatus(SyncStatus.SYNCED)
    }

    /** 获取已备份的文件数量（APP 上传成功或已标记为完成的） */
    suspend fun getBackedUpCount(): Int {
        return mediaDao.getBackedUpCount()
    }

    /** 获取已备份的文件总大小 */
    suspend fun getBackedUpSize(): Long {
        return mediaDao.getBackedUpSize()
    }

    /** 标记文件备份完成 */
    suspend fun markAsBackedUp(dbId: Long, cloudId: Double, cloudMd5: String) {
        mediaDao.markAsBackedUp(dbId, cloudId = cloudId, cloudMd5 = cloudMd5)
    }

    /** 标记文件存储已优化 */
    suspend fun markAsStorageOptimized(dbId: Long) {
        mediaDao.markAsStorageOptimized(dbId)
    }

    /** 按 cloudId 删除 */
    suspend fun deleteByCloudId(cloudId: Double) {
        mediaDao.deleteByCloudId(cloudId)
    }

    /** 按 localMediaStoreId 删除 */
    suspend fun deleteByLocalId(localId: Long) {
        mediaDao.deleteByLocalMediaStoreId(localId)
    }

    /** 按 md5 查找 */
    suspend fun findByMd5(md5: String): MediaEntity? {
        return mediaDao.findByMd5(md5)
    }

    /** 按 cloudId 查找 */
    suspend fun findByCloudId(cloudId: Double): MediaEntity? {
        return mediaDao.findByCloudId(cloudId)
    }

    /** 更新缩略图缓存路径 */
    suspend fun updateThumbCachePath(dbId: Long, path: String) {
        mediaDao.updateThumbCachePath(dbId, path)
    }

    /** 数据库中是否有数据 */
    suspend fun hasData(): Boolean {
        return mediaDao.getTotalCount() > 0
    }
}

/**
 * MediaEntity → UnifiedPhotoItem 转换
 */
fun MediaEntity.toUnifiedPhotoItem(): UnifiedPhotoItem {
    return UnifiedPhotoItem(
        dbId = id,
        cloudId = cloudId,
        md5 = md5.ifEmpty { cloudMd5 ?: "" },
        fileName = fileName,
        fileType = fileType,
        mtime = mtime,
        width = width.toDouble(),
        height = height.toDouble(),
        syncStatus = syncStatus,
        backupStatus = backupStatus,
        localUri = localUri,
        thumbCachePath = thumbCachePath,
        isStorageOptimized = isStorageOptimized,
        fileSize = fileSize
    )
}
