package com.kqstone.mtphotos.data.repository

import android.content.ContentUris
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import java.io.File
import com.kqstone.mtphotos.AppContainer
import com.kqstone.mtphotos.data.local.LocalMediaScanner
import com.kqstone.mtphotos.data.local.MediaChangeObserver
import com.kqstone.mtphotos.data.local.db.AppDatabase
import com.kqstone.mtphotos.data.local.db.BackupStatus
import com.kqstone.mtphotos.data.local.db.LocalFileRef
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
     * 渐进式同步：流式扫描本地文件，每批次写入 Room 后 emit 进度。
     * 不计算 MD5（速度快），用于首次加载和刷新。
     * 通过 localMediaStoreId / cloudId 去重，不会产生重复记录。
     */
    fun performIncrementalSync(
        localFolders: Set<String>? = null
    ): Flow<SyncProgress> = kotlinx.coroutines.flow.flow {
        try {
            Log.d(TAG, "Starting incremental sync...")
            emit(SyncProgress(0, 0, "cloud"))

            // Step 1: 拉取云端数据
            val cloudPhotos = fetchAllCloudPhotos()
            Log.d(TAG, "Cloud has ${cloudPhotos.size} files")

            // 只插入尚不存在的云端记录
            if (cloudPhotos.isNotEmpty()) {
                val existingCloudIds = cloudPhotos
                    .mapNotNull { it.cloudId }
                    .chunked(500)
                    .flatMap { mediaDao.findExistingCloudIds(it) }
                    .toSet()
                val newCloudPhotos = cloudPhotos.filter { it.cloudId !in existingCloudIds }
                if (newCloudPhotos.isNotEmpty()) {
                    mediaDao.insertAll(newCloudPhotos)
                    Log.d(TAG, "Inserted ${newCloudPhotos.size} new cloud files (skipped ${cloudPhotos.size - newCloudPhotos.size} existing)")
                }
                emit(SyncProgress(0, 0, "cloud_done", cloudPhotos.size))
            }

            // Step 2: 流式扫描本地（去重）
            val cloudFileKeys = cloudPhotos.map { "${it.fileName}_${it.fileSize}" }.toSet()
            var scannedCount = 0

            localScanner.scanMediaFlow(localFolders, batchSize = 50).collect { batch ->
                scannedCount += batch.size

                // 查询已存在的 localMediaStoreId
                val batchLocalIds = batch.mapNotNull { it.localMediaStoreId }
                val existingLocalIds = if (batchLocalIds.isNotEmpty()) {
                    batchLocalIds.chunked(500).flatMap { mediaDao.findExistingLocalIds(it) }.toSet()
                } else emptySet()

                // 只插入不存在的本地文件
                val newEntities = batch
                    .filter { it.localMediaStoreId !in existingLocalIds }
                    .map { local ->
                        val fileKey = "${local.fileName}_${local.fileSize}"
                        val probablyOnCloud = fileKey in cloudFileKeys
                        local.copy(
                            syncStatus = if (probablyOnCloud) SyncStatus.SYNCED else SyncStatus.LOCAL_ONLY,
                            backupStatus = if (probablyOnCloud) BackupStatus.COMPLETED else BackupStatus.NOT_STARTED
                        )
                    }

                if (newEntities.isNotEmpty()) {
                    mediaDao.insertAll(newEntities)
                }
                emit(SyncProgress(scannedCount, 0, "scanning"))
            }

            // Step 3: 清理孤立记录（本地文件已被外部删除的记录）
            emit(SyncProgress(scannedCount, scannedCount, "cleanup"))
            val cleaned = cleanupOrphanedLocalRecords()
            if (cleaned > 0) Log.d(TAG, "Cleanup: removed $cleaned orphaned records")

            emit(SyncProgress(scannedCount, scannedCount, "done"))
            Log.d(TAG, "Incremental sync complete: $scannedCount local files processed")

        } catch (e: Exception) {
            Log.e(TAG, "Incremental sync failed", e)
            throw e
        }
    }

    data class SyncResult(val newCount: Int, val removedCount: Int)

    /**
     * 轻量本地同步：仅扫描本地 MediaStore，不拉取云端数据。
     * 用于后台 SyncWorker，速度快。
     * 1. 扫描本地文件（不计算 MD5）
     * 2. 新文件 → 插入 Room (LOCAL_ONLY, PENDING)
     * 3. 本地已消失的记录 → 清理（LOCAL_ONLY 删除，SYNCED 变 CLOUD_ONLY）
     *
     * @return SyncResult 新增数 + 清理数
     */
    suspend fun syncLocalMedia(folders: Set<String>? = null): SyncResult = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting local-only sync...")

            // 1. 扫描本地
            val localMedia = localScanner.scanMedia(folders, computeMd5 = false)
            Log.d(TAG, "Local scan found ${localMedia.size} files")

            // 2. 查询 Room 已有的 localMediaStoreId
            val existingLocalIds = if (localMedia.isNotEmpty()) {
                val batchIds = localMedia.mapNotNull { it.localMediaStoreId }
                batchIds.chunked(500).flatMap { mediaDao.findExistingLocalIds(it) }.toSet()
            } else emptySet()

            // 3. 插入新文件
            val newEntities = localMedia
                .filter { it.localMediaStoreId !in existingLocalIds }
                .map { it.copy(
                    syncStatus = SyncStatus.LOCAL_ONLY,
                    backupStatus = BackupStatus.NOT_STARTED
                ) }
            if (newEntities.isNotEmpty()) {
                mediaDao.insertAll(newEntities)
                Log.d(TAG, "Inserted ${newEntities.size} new local files")
            }

            // 4. 清理孤立记录（本地已删除的文件）
            val removed = cleanupOrphanedLocalRecords()

            SyncResult(newEntities.size, removed)
        } catch (e: Exception) {
            Log.e(TAG, "Local sync failed", e)
            throw e
        }
    }

    /**
     * 后台异步补算 MD5 并精确匹配云端。
     * 在首次渐进式加载完成后调用。
     */
    suspend fun computeMd5InBackground() = withContext(Dispatchers.IO) {
        try {
            val needMd5 = mediaDao.getMediaBySyncStatus(SyncStatus.LOCAL_ONLY)
                .filter { it.md5.isEmpty() && !it.localPath.isNullOrEmpty() }

            if (needMd5.isEmpty()) {
                Log.d(TAG, "No files need MD5 computation")
                return@withContext
            }

            Log.d(TAG, "Computing MD5 for ${needMd5.size} files...")
            val cloudPhotos = fetchAllCloudPhotos()
            val cloudMd5Map = cloudPhotos.associateBy { it.md5 }

            var updated = 0
            for (entity in needMd5) {
                val md5 = LocalMediaScanner.computeFileMd5(entity.localPath!!)
                if (md5.isEmpty()) continue

                val cloud = cloudMd5Map[md5]
                if (cloud != null) {
                    // 精确匹配到云端文件
                    mediaDao.update(entity.copy(
                        md5 = md5,
                        cloudId = cloud.cloudId,
                        cloudMd5 = cloud.md5,
                        syncStatus = SyncStatus.SYNCED,
                        backupStatus = BackupStatus.COMPLETED,
                        updatedAt = System.currentTimeMillis()
                    ))
                } else {
                    mediaDao.update(entity.copy(
                        md5 = md5,
                        updatedAt = System.currentTimeMillis()
                    ))
                }
                updated++
            }
            Log.d(TAG, "MD5 computation complete: $updated files updated")
        } catch (e: Exception) {
            Log.e(TAG, "MD5 background computation failed", e)
        }
    }

    data class SyncProgress(
        val scanned: Int,
        val total: Int,
        val phase: String,
        val cloudCount: Int = 0
    )

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

    /**
     * 根据 UI 层的 UnifiedPhotoItem.id（cloudId 或 dbId）查找 Room 实体。
     * 同时按 cloudId 和 dbId 查询，覆盖 SYNCED/CLOUD_ONLY/LOCAL_ONLY 所有情况。
     */
    suspend fun findMediaEntitiesByIds(ids: List<Double>): List<MediaEntity> {
        if (ids.isEmpty()) return emptyList()
        val cloudIds = ids.filter { it > 0 }
        val dbIds = ids.map { it.toLong() }.filter { it > 0 }
        return (mediaDao.findByCloudIds(cloudIds) + mediaDao.findByIds(dbIds)).distinctBy { it.id }
    }

    /**
     * 删除本地媒体文件、缩略图缓存和 Room 记录。
     * 云端删除由调用方负责。
     * @param useDirectDelete true = 直接删除（需 MANAGE_EXTERNAL_STORAGE），false = 系统确认删除
     */
    suspend fun deleteLocalMediaFiles(entities: List<MediaEntity>, useDirectDelete: Boolean = false) {
        if (entities.isEmpty()) return
        val context = container.prefsManager.context

        // 收集需要删除的本地文件 URI
        val urisToDelete = mutableListOf<android.net.Uri>()
        for (entity in entities) {
            val localId = entity.localMediaStoreId ?: continue
            val contentUri = if (entity.fileType.startsWith("video")) {
                ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, localId)
            } else {
                ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, localId)
            }
            urisToDelete.add(contentUri)
        }

        // 删除本地文件
        if (urisToDelete.isNotEmpty()) {
            if (useDirectDelete && isManageStorageGranted()) {
                // 直接删除模式：有 MANAGE_EXTERNAL_STORAGE 权限
                for (uri in urisToDelete) {
                    try {
                        context.contentResolver.delete(uri, null, null)
                    } catch (e: Exception) {
                        Log.w(TAG, "直接删除本地文件失败: $uri", e)
                    }
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // 系统确认删除模式：使用 createDeleteRequest
                try {
                    val pendingIntent = MediaStore.createDeleteRequest(context.contentResolver, urisToDelete)
                    pendingIntent.send()
                } catch (e: Exception) {
                    Log.w(TAG, "createDeleteRequest 失败", e)
                }
            } else {
                // API 30 以下直接删除
                for (uri in urisToDelete) {
                    try {
                        context.contentResolver.delete(uri, null, null)
                    } catch (e: Exception) {
                        Log.w(TAG, "删除本地文件失败: $uri", e)
                    }
                }
            }
        }

        // 删除缩略图缓存
        for (entity in entities) {
            entity.thumbCachePath?.let { path ->
                try { File(path).delete() } catch (_: Exception) {}
            }
        }

        // 删除 Room 记录
        val dbIds = entities.map { it.id }
        mediaDao.deleteByIds(dbIds)
    }

    private fun isManageStorageGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.os.Environment.isExternalStorageManager()
        } else true
    }

    /**
     * 轻量级孤立记录清理：验证 Room 中 localMediaStoreId 是否仍存在于 MediaStore。
     * 仅在 ContentObserver 检测到变化时调用，避免每次刷新都执行。
     * @return 清理的记录数
     */
    suspend fun cleanupOrphanedLocalRecords(): Int {
        val refs = mediaDao.getLocalFileRefs()
        if (refs.isEmpty()) return 0

        val trackedIds = refs.map { it.localMediaStoreId }.toSet()
        val existingIds = localScanner.verifyIdsExist(trackedIds)
        val orphanedRefs = refs.filter { it.localMediaStoreId !in existingIds }
        if (orphanedRefs.isEmpty()) return 0

        Log.d(TAG, "Found ${orphanedRefs.size} orphaned local records")

        // 分类处理
        val toDelete = mutableListOf<Long>()      // LOCAL_ONLY 或无 cloudId 的 SYNCED → 删除
        val toClearLocal = mutableListOf<Long>()   // 有 cloudId 的 SYNCED → 变为 CLOUD_ONLY

        for (ref in orphanedRefs) {
            if (ref.syncStatus == SyncStatus.LOCAL_ONLY || ref.cloudId == null) {
                toDelete.add(ref.id)
            } else {
                toClearLocal.add(ref.id)
            }
        }

        // 删除 LOCAL_ONLY 记录 + 缩略图缓存
        if (toDelete.isNotEmpty()) {
            val entities = mediaDao.findByIds(toDelete)
            for (entity in entities) {
                entity.thumbCachePath?.let { path ->
                    try { File(path).delete() } catch (_: Exception) {}
                }
            }
            mediaDao.deleteByIds(toDelete)
            Log.d(TAG, "Deleted ${toDelete.size} orphaned LOCAL_ONLY records")
        }

        // SYNCED → CLOUD_ONLY（清除本地字段，保留云端引用）
        if (toClearLocal.isNotEmpty()) {
            mediaDao.clearLocalFields(toClearLocal)
            Log.d(TAG, "Cleared local fields for ${toClearLocal.size} SYNCED records → CLOUD_ONLY")
        }

        MediaChangeObserver.clearDirty()
        return orphanedRefs.size
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
