package com.kqstone.mtphotos.data.repository

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.kqstone.mtphotos.AppContainer
import com.kqstone.mtphotos.data.local.LocalVideoThumbnailGenerator
import com.kqstone.mtphotos.data.local.LocalMediaScanner
import com.kqstone.mtphotos.data.local.MediaChangeObserver
import com.kqstone.mtphotos.data.local.FolderPathMatcher
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
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "SyncRepo"
private const val LOCAL_SCAN_PREFS = "local_media_scan"
private const val KEY_LAST_LOCAL_SCAN_AT = "last_local_scan_at"
private const val KEY_LAST_ORPHAN_CHECK_AT = "last_orphan_check_at"
private const val KEY_MEDIASTORE_FINGERPRINT = "mediastore_fingerprint"
private const val INCREMENTAL_SCAN_LOOKBACK_MS = 2 * 60 * 1000L
private const val INCREMENTAL_ORPHAN_CHECK_INTERVAL_MS = 30 * 1000L

class SyncRepository(
    private val container: AppContainer,
    private val database: AppDatabase
) {
    private val mediaDao: MediaDao get() = database.mediaDao()
    private val serverOpTaskDao get() = database.serverOpTaskDao()
    private val galleryRepository: GalleryRepository get() = container.galleryRepository
    private val localScanner: LocalMediaScanner by lazy { LocalMediaScanner(container.prefsManager.context) }
    private val localVideoThumbnailGenerator: LocalVideoThumbnailGenerator by lazy {
        LocalVideoThumbnailGenerator(container.prefsManager.context, container.thumbnailCacheManager)
    }
    private val scanPrefs by lazy {
        container.prefsManager.context.getSharedPreferences(LOCAL_SCAN_PREFS, Context.MODE_PRIVATE)
    }

    suspend fun performFullSync(localFolders: Set<String>? = null) = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting full sync...")

            val localMedia = localScanner.scanMedia(localFolders, computeMd5 = true)
            Log.d(TAG, "Local scan found ${localMedia.size} files")

            val cloudResult = fetchAllCloudPhotos()
            val fetchedCloudPhotos = cloudResult.entities
            val cloudDeleteChanges = reconcileCloudDeletions(fetchedCloudPhotos)
            val cloudPhotos = normalizeCloudPhotos(fetchedCloudPhotos)
            Log.d(TAG, "Cloud has ${cloudPhotos.size} files")
            if (cloudDeleteChanges.changed > 0) {
                Log.d(TAG, "Cloud deletion reconciliation: $cloudDeleteChanges")
            }

            val cloudMd5Map = cloudPhotos.associateBy { it.md5 }
            val mergedLocal = prepareLocalEntities(
                localMedia,
                cloudMd5Map = cloudMd5Map,
                useCloudFavorite = cloudResult.favoriteStateKnown
            )
            val processedMd5s = mergedLocal.map { it.md5 }.toSet()
            val mergedCloud = buildCloudEntities(
                cloudPhotos,
                processedMd5s,
                useCloudFavorite = cloudResult.favoriteStateKnown
            )
            val mergedEntities = mergedLocal + mergedCloud

            if (mergedEntities.isNotEmpty()) {
                mediaDao.insertAll(mergedEntities)
            }
            rememberCurrentMediaStoreFingerprint()
            Log.d(
                TAG,
                "Full sync complete: ${mergedEntities.size} entities " +
                    "(${mergedEntities.count { it.syncStatus == SyncStatus.SYNCED }} synced, " +
                    "${mergedEntities.count { it.syncStatus == SyncStatus.LOCAL_ONLY }} local-only, " +
                    "${mergedEntities.count { it.syncStatus == SyncStatus.CLOUD_ONLY }} cloud-only)"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Full sync failed", e)
            throw e
        }
    }

    fun performInitialSync(localFolders: Set<String>? = null): Flow<SyncProgress> = flow {
        try {
            Log.d(TAG, "Starting initial sync...")
            emit(SyncProgress(0, 0, "cloud"))

            val cloudResult = fetchAllCloudPhotos()
            val fetchedCloudPhotos = cloudResult.entities
            val cloudDeleteChanges = reconcileCloudDeletions(fetchedCloudPhotos)
            val cloudPhotos = normalizeCloudPhotos(fetchedCloudPhotos)
            val cloudMd5Map = cloudPhotos.associateBy { it.md5 }
            if (cloudDeleteChanges.changed > 0) {
                Log.d(TAG, "Cloud deletion reconciliation: $cloudDeleteChanges")
            }
            emit(SyncProgress(0, 0, "cloud_indexed", cloudPhotos.size))

            val matchedMd5s = mutableSetOf<String>()
            val seenLocalMd5s = mutableSetOf<String>()
            val pendingFavoriteCloudIds = if (cloudResult.favoriteStateKnown) {
                serverOpTaskDao.getPendingFavoriteCloudIds().toSet()
            } else {
                emptySet()
            }
            var scannedCount = 0

            localScanner.scanMediaFlow(
                folderPaths = localFolders,
                batchSize = 50,
                computeMd5 = false
            ).collect { batch ->
                scannedCount += batch.size

                val mergedBatch = mutableListOf<MediaEntity>()
                for (local in batch) {
                    val md5 = local.md5
                    if (md5.isNotEmpty() && !seenLocalMd5s.add(md5)) continue
                    val cloud = md5.takeIf { it.isNotEmpty() }?.let { cloudMd5Map[it] }
                    if (cloud != null) {
                        matchedMd5s.add(md5)
                    }
                    val existing = findExistingEntity(md5, cloud?.cloudId, local.localMediaStoreId)
                    mergedBatch.add(
                        mergeMedia(
                            existing,
                            local = local,
                            cloud = cloud,
                            useCloudFavorite = cloudResult.favoriteStateKnown,
                            pendingFavoriteCloudIds = pendingFavoriteCloudIds
                        )
                    )
                }

                if (mergedBatch.isNotEmpty()) {
                    mediaDao.insertAll(mergedBatch)
                }
                emit(SyncProgress(scannedCount, 0, "scanning", cloudPhotos.size))
            }

            emit(SyncProgress(scannedCount, 0, "finalizing", cloudPhotos.size))
            upsertCloudPhotos(
                cloudPhotos,
                skipMd5s = matchedMd5s,
                useCloudFavorite = cloudResult.favoriteStateKnown
            ) { written, total ->
                emit(SyncProgress(written, total, "finalizing", total))
            }

            // 云端数据已全部写入 Room，通知 UI 可以展开所有月份
            emit(SyncProgress(scannedCount, scannedCount, "cloud_ready", cloudPhotos.size))

            emit(SyncProgress(scannedCount, scannedCount, "cleanup", cloudPhotos.size))
            val cleaned = cleanupOrphanedLocalRecords()
            if (cleaned > 0) Log.d(TAG, "Cleanup: removed $cleaned orphaned records")

            rememberCurrentMediaStoreFingerprint()
            emit(SyncProgress(scannedCount, scannedCount, "done", cloudPhotos.size))
            Log.d(TAG, "Initial sync complete: $scannedCount local files processed")
        } catch (e: Exception) {
            Log.e(TAG, "Initial sync failed", e)
            throw e
        }
    }

    fun performIncrementalSync(localFolders: Set<String>? = null): Flow<SyncProgress> = flow {
        try {
            Log.d(TAG, "Starting incremental sync...")
            emit(SyncProgress(0, 0, "cloud"))

            val cloudResult = fetchAllCloudPhotos()
            val fetchedCloudPhotos = cloudResult.entities
            val cloudDeleteChanges = reconcileCloudDeletions(fetchedCloudPhotos)
            val cloudPhotos = normalizeCloudPhotos(fetchedCloudPhotos)
            Log.d(TAG, "Cloud has ${cloudPhotos.size} files")
            if (cloudDeleteChanges.changed > 0) {
                Log.d(TAG, "Cloud deletion reconciliation: $cloudDeleteChanges")
            }

            upsertCloudPhotos(cloudPhotos, useCloudFavorite = cloudResult.favoriteStateKnown)
            emit(SyncProgress(0, 0, "cloud_done", cloudPhotos.size))

            val cloudMd5Map = cloudPhotos.associateBy { it.md5 }
            val seenLocalMd5s = mutableSetOf<String>()
            var scannedCount = 0

            localScanner.scanMediaFlow(localFolders, batchSize = 50).collect { batch ->
                scannedCount += batch.size

                val batchLocalIds = batch.mapNotNull { it.localMediaStoreId }
                val existingLocalIds = if (batchLocalIds.isNotEmpty()) {
                    batchLocalIds.chunked(500).flatMap { mediaDao.findExistingLocalIds(it) }.toSet()
                } else {
                    emptySet()
                }

            val newEntities = prepareLocalEntities(
                localMedia = batch.filter { it.localMediaStoreId !in existingLocalIds },
                cloudMd5Map = cloudMd5Map,
                seenMd5s = seenLocalMd5s,
                useCloudFavorite = cloudResult.favoriteStateKnown,
                computeMissingMd5 = false
            )

                if (newEntities.isNotEmpty()) {
                    mediaDao.insertAll(newEntities)
                }
                emit(SyncProgress(scannedCount, 0, "scanning", cloudPhotos.size))
            }

            emit(SyncProgress(scannedCount, scannedCount, "cleanup", cloudPhotos.size))
            val cleaned = cleanupOrphanedLocalRecords()
            if (cleaned > 0) Log.d(TAG, "Cleanup: removed $cleaned orphaned records")

            emit(SyncProgress(scannedCount, scannedCount, "done", cloudPhotos.size))
            Log.d(TAG, "Incremental sync complete: $scannedCount local files processed")
        } catch (e: Exception) {
            Log.e(TAG, "Incremental sync failed", e)
            throw e
        }
    }

    data class SyncResult(val newCount: Int, val removedCount: Int)
    data class MediaStoreChange(
        val changed: Boolean,
        val mayIncludeDeletion: Boolean
    )

    data class BackupRepairResult(
        val scannedCount: Int,
        val resetCount: Int
    )

    private data class CloudDeleteChanges(
        val deletedCloudOnly: Int = 0,
        val localRetained: Int = 0
    ) {
        val changed: Int get() = deletedCloudOnly + localRetained
    }

    suspend fun handleMediaStoreChanged(
        uris: List<Uri>,
        folders: Set<String>? = null
    ): SyncResult = withContext(Dispatchers.IO) {
        val quickRemoved = cleanupDeletedMediaUris(uris)
        val result = syncLocalMedia(
            folders = folders,
            incremental = true,
            forceOrphanCheck = quickRemoved == 0
        )
        SyncResult(
            newCount = result.newCount,
            removedCount = result.removedCount + quickRemoved
        )
    }

    suspend fun syncLocalMedia(
        folders: Set<String>? = null,
        incremental: Boolean = false,
        forceOrphanCheck: Boolean = false
    ): SyncResult = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting local-only sync (incremental=$incremental)...")

            val scanStartedAt = System.currentTimeMillis()
            val modifiedSince = if (incremental) {
                (scanPrefs.getLong(KEY_LAST_LOCAL_SCAN_AT, 0L) - INCREMENTAL_SCAN_LOOKBACK_MS)
                    .coerceAtLeast(0L)
                    .takeIf { it > 0L }
            } else {
                null
            }
            val localMedia = localScanner.scanMedia(
                folderPaths = folders,
                computeMd5 = false,
                modifiedSinceMillis = modifiedSince
            )
            Log.d(TAG, "Local scan found ${localMedia.size} files")

            val existingLocalIds = if (localMedia.isNotEmpty()) {
                val batchIds = localMedia.mapNotNull { it.localMediaStoreId }
                batchIds.chunked(500).flatMap { mediaDao.findExistingLocalIds(it) }.toSet()
            } else {
                emptySet()
            }

            val newEntities = prepareLocalEntities(
                localMedia = localMedia.filter { it.localMediaStoreId !in existingLocalIds },
                computeMissingMd5 = false
            )
            if (newEntities.isNotEmpty()) {
                mediaDao.insertAll(newEntities)
                Log.d(TAG, "Inserted ${newEntities.size} new local files")
            }

            val removed = if (incremental) {
                cleanupMissingLocalRecords(localMedia, newEntities.size, forceOrphanCheck)
            } else {
                cleanupOrphanedLocalRecords()
            }
            scanPrefs.edit().putLong(KEY_LAST_LOCAL_SCAN_AT, scanStartedAt).apply()
            rememberCurrentMediaStoreFingerprint()
            MediaChangeObserver.clearDirty()
            SyncResult(newEntities.size, removed)
        } catch (e: Exception) {
            Log.e(TAG, "Local sync failed", e)
            throw e
        }
    }

    suspend fun computeMd5InBackground(
        batchSize: Int = 25,
        onBatchComplete: suspend () -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        try {
            val needMd5 = mediaDao.getMediaBySyncStatus(SyncStatus.LOCAL_ONLY)
                .filter { it.md5.isEmpty() && !it.localPath.isNullOrEmpty() }
                .sortedWith(
                    compareBy<MediaEntity> { it.fileType.startsWith("video", ignoreCase = true) }
                        .thenByDescending { it.mtime }
                )

            if (needMd5.isEmpty()) {
                Log.d(TAG, "No files need MD5 computation")
                return@withContext
            }

            Log.d(TAG, "Computing MD5 for ${needMd5.size} files...")
            val cloudMd5Map = mediaDao.getCloudBoundMedia()
                .mapNotNull { entity ->
                    val md5 = entity.cloudMd5 ?: entity.md5
                    md5.takeIf { it.isNotEmpty() }?.let { it to entity }
                }
                .toMap()

            var updated = 0
            for (chunk in needMd5.chunked(batchSize.coerceAtLeast(1))) {
                for (entity in chunk) {
                    val md5 = LocalMediaScanner.computeFileMd5(entity.localPath!!)
                    if (md5.isEmpty()) continue

                    val cloud = cloudMd5Map[md5]
                    mergeComputedMd5(entity, md5, cloud)
                    updated++
                }
                onBatchComplete()
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

    suspend fun syncCloudOnly() = withContext(Dispatchers.IO) {
        try {
            val cloudResult = fetchAllCloudPhotos()
            val fetchedCloudPhotos = cloudResult.entities
            val cloudDeleteChanges = reconcileCloudDeletions(fetchedCloudPhotos)
            val cloudPhotos = normalizeCloudPhotos(fetchedCloudPhotos)
            Log.d(TAG, "Cloud-only sync: ${cloudPhotos.size} files")
            if (cloudDeleteChanges.changed > 0) {
                Log.d(TAG, "Cloud deletion reconciliation: $cloudDeleteChanges")
            }
            upsertCloudPhotos(cloudPhotos, useCloudFavorite = cloudResult.favoriteStateKnown)
        } catch (e: Exception) {
            Log.e(TAG, "Cloud-only sync failed", e)
            throw e
        }
    }

    /**
     * 刷新云端状态并返回 TimelineSnapshot 供调用方复用，避免重复 API 调用。
     */
    suspend fun refreshCloudState(existingSnapshot: TimelineSnapshot? = null): TimelineSnapshot = withContext(Dispatchers.IO) {
        try {
            val cloudResult = fetchAllCloudPhotos(existingSnapshot)
            val fetchedCloudPhotos = cloudResult.entities
            val snapshot = cloudResult.snapshot
            val cloudDeleteChanges = reconcileCloudDeletions(fetchedCloudPhotos)
            val cloudPhotos = normalizeCloudPhotos(fetchedCloudPhotos)
            if (cloudDeleteChanges.changed > 0) {
                Log.d(TAG, "Cloud deletion reconciliation: $cloudDeleteChanges")
            }
            upsertCloudPhotos(cloudPhotos, useCloudFavorite = cloudResult.favoriteStateKnown)
            Log.d(TAG, "Cloud state refresh complete: ${cloudPhotos.size} files")
            snapshot
        } catch (e: Exception) {
            Log.e(TAG, "Cloud state refresh failed", e)
            throw e
        }
    }

    private data class FetchCloudResult(
        val entities: List<MediaEntity>,
        val snapshot: TimelineSnapshot,
        val favoriteStateKnown: Boolean
    )

    private suspend fun fetchAllCloudPhotos(existingSnapshot: TimelineSnapshot? = null): FetchCloudResult {
        val snapshot = existingSnapshot ?: galleryRepository.getTimelineSnapshot().getOrElse { throw it }
        val photosByMonth = linkedMapOf<String, List<PhotoItem>>()
        photosByMonth.putAll(snapshot.photosByMonth)

        val missingMonths = snapshot.months.filter { !photosByMonth.containsKey(it.yearMonth) }
        if (missingMonths.isNotEmpty()) {
            val batchResult = galleryRepository.getTimelineMonthFilesBatch(missingMonths)
                .getOrElse { throw it }
            photosByMonth.putAll(batchResult)
        }

        val favoriteFileIds = galleryRepository.getFavoriteFileIds().getOrNull()
        val result = photosByMonth.values.flatten().map { it.toCloudEntity(favoriteFileIds) }
        if (snapshot.months.sumOf { it.count } > 0 && result.isEmpty()) {
            throw IllegalStateException("Cloud timeline has items, but no cloud file details were parsed")
        }
        return FetchCloudResult(result, snapshot, favoriteFileIds != null)
    }

    suspend fun upsertCloudPhotoItems(photos: List<PhotoItem>) = withContext(Dispatchers.IO) {
        val cloudPhotos = normalizeCloudPhotos(photos.map { it.toCloudEntity() })
        upsertCloudPhotos(
            cloudPhotos,
            useCloudFavorite = photos.isNotEmpty() && photos.all { it.isFavorite }
        )
    }

    suspend fun getAllPhotos(localFolders: Set<String>? = null): List<UnifiedPhotoItem> = withContext(Dispatchers.IO) {
        val entities = when {
            localFolders == null -> mediaDao.getAllMedia()
            localFolders.isEmpty() -> mediaDao.getAllCloudMedia()
            else -> mediaDao.getAllVisibleMediaByFolders(localFolders.toList())
        }
        val pendingCloudDeleteIds = getPendingCloudDeleteCloudIds()
        entities
            .filterNot { entity -> entity.cloudId in pendingCloudDeleteIds }
            .map { it.toUnifiedPhotoItem(localFolders) }
    }

    suspend fun getInitialPreviewPhotos(
        localFolders: Set<String>? = null,
        limit: Int = 160
    ): List<UnifiedPhotoItem> = withContext(Dispatchers.IO) {
        val safeLimit = limit.coerceAtLeast(1)
        val queryLimit = when {
            localFolders == null || localFolders.isEmpty() -> safeLimit
            else -> safeLimit * 4
        }
        val entities = when {
            localFolders == null -> mediaDao.getRecentMedia(queryLimit)
            localFolders.isEmpty() -> mediaDao.getRecentCloudMedia(queryLimit)
            else -> mediaDao.getRecentMedia(queryLimit).filter { entity ->
                entity.cloudId != null ||
                    FolderPathMatcher.isInAnyScope(entity.localFolderPath, localFolders)
            }
        }
        val pendingCloudDeleteIds = getPendingCloudDeleteCloudIds()
        entities
            .filterNot { entity -> entity.cloudId in pendingCloudDeleteIds }
            .take(safeLimit)
            .map { it.toUnifiedPhotoItem(localFolders) }
    }

    suspend fun hydrateCloudPhotos(
        photos: List<PhotoItem>,
        localFolders: Set<String>? = null
    ): List<UnifiedPhotoItem> = withContext(Dispatchers.IO) {
        if (photos.isEmpty()) return@withContext emptyList()

        val pendingCloudDeleteIds = getPendingCloudDeleteCloudIds()
        hydrateCloudPhotos(photos, localFolders, pendingCloudDeleteIds)
    }

    suspend fun hydrateCloudPhotosByMonth(
        photosByMonth: Map<String, List<PhotoItem>>,
        localFolders: Set<String>? = null
    ): Map<String, List<UnifiedPhotoItem>> = withContext(Dispatchers.IO) {
        if (photosByMonth.isEmpty()) return@withContext emptyMap()

        val pendingCloudDeleteIds = getPendingCloudDeleteCloudIds()
        photosByMonth.mapValues { (_, photos) ->
            hydrateCloudPhotos(photos, localFolders, pendingCloudDeleteIds)
        }
    }

    private suspend fun hydrateCloudPhotos(
        photos: List<PhotoItem>,
        localFolders: Set<String>?,
        pendingCloudDeleteIds: Set<Double>
    ): List<UnifiedPhotoItem> {
        val visiblePhotos = photos.filter { it.id !in pendingCloudDeleteIds }
        if (visiblePhotos.isEmpty()) return emptyList()

        val byCloudId = mediaDao.findByCloudIds(visiblePhotos.map { it.id }.distinct())
            .associateBy { it.cloudId }
        val byMd5 = mediaDao.findByMd5List(visiblePhotos.map { it.md5 }.filter { it.isNotEmpty() }.distinct())
            .associateBy { it.md5.ifEmpty { it.cloudMd5 ?: "" } }

        return visiblePhotos.map { photo ->
            val localEntity = byCloudId[photo.id] ?: byMd5[photo.md5]
            localEntity?.toUnifiedPhotoItem(localFolders)?.copy(
                addr = photo.addr ?: localEntity.addr,
                livePhotosVideoId = photo.livePhotosVideoId,
                isLivePhotosVideo = photo.isLivePhotosVideo,
                livePhotoUuid = photo.livePhotoUuid,
                isFavorite = if (photo.isFavorite) true else localEntity.isFavorite,
                isHide = if (photo.isHide) true else localEntity.isHide
            ) ?: photo.toCloudOnlyUnifiedPhotoItem()
        }
    }

    private suspend fun normalizeCloudPhotos(cloudPhotos: List<MediaEntity>): List<MediaEntity> {
        val pendingCloudDeleteIds = getPendingCloudDeleteCloudIds()
        return cloudPhotos
            .filterNot { it.cloudId in pendingCloudDeleteIds }
            .filter { it.md5.isNotEmpty() }
            .distinctBy { it.md5 }
    }

    private suspend fun getPendingCloudDeleteCloudIds(): Set<Double> {
        return serverOpTaskDao.getPendingCloudDeleteCloudIds().toSet()
    }

    private fun PhotoItem.toCloudEntity(favoriteFileIds: Set<Double>? = null): MediaEntity {
        return MediaEntity(
            cloudId = id,
            md5 = md5,
            cloudMd5 = md5,
            addr = addr,
            fileName = fileName,
            fileType = fileType,
            mtime = mtime,
            width = width.toInt(),
            height = height.toInt(),
            duration = duration,
            livePhotosVideoId = livePhotosVideoId,
            isLivePhotosVideo = isLivePhotosVideo,
            livePhotoUuid = livePhotoUuid,
            isFavorite = favoriteFileIds?.contains(id) ?: isFavorite,
            isHide = isHide,
            syncStatus = SyncStatus.CLOUD_ONLY,
            backupStatus = BackupStatus.NOT_STARTED
        )
    }

    private suspend fun reconcileCloudDeletions(cloudPhotos: List<MediaEntity>): CloudDeleteChanges {
        val currentCloudIds = cloudPhotos.mapNotNull { it.cloudId }.toSet()
        val currentMd5s = cloudPhotos.map { it.md5 }.filter { it.isNotEmpty() }.toSet()
        val cloudBound = mediaDao.getCloudBoundMedia()
        if (cloudBound.isEmpty()) return CloudDeleteChanges()

        val removed = cloudBound.filter { entity ->
            val cloudIdMissing = entity.cloudId?.let { it !in currentCloudIds } ?: false
            val md5Missing = entity.cloudId == null &&
                (entity.cloudMd5 ?: entity.md5).let { it.isNotEmpty() && it !in currentMd5s }
            cloudIdMissing || md5Missing
        }
        if (removed.isEmpty()) return CloudDeleteChanges()

        val deleteIds = mutableListOf<Long>()
        val retainLocalIds = mutableListOf<Long>()

        for (entity in removed) {
            if (entity.hasLocalBinding()) {
                retainLocalIds.add(entity.id)
            } else {
                deleteIds.add(entity.id)
            }
        }

        if (deleteIds.isNotEmpty()) {
            val entities = mediaDao.findByIds(deleteIds)
            deleteThumbCaches(entities)
            mediaDao.deleteByIds(deleteIds)
        }
        if (retainLocalIds.isNotEmpty()) {
            mediaDao.clearCloudFieldsAsRemoteDeleted(retainLocalIds)
        }

        return CloudDeleteChanges(
            deletedCloudOnly = deleteIds.size,
            localRetained = retainLocalIds.size
        )
    }

    private suspend fun prepareLocalEntities(
        localMedia: List<MediaEntity>,
        cloudMd5Map: Map<String, MediaEntity> = emptyMap(),
        seenMd5s: MutableSet<String> = mutableSetOf(),
        useCloudFavorite: Boolean = false,
        computeMissingMd5: Boolean = true
    ): List<MediaEntity> {
        val result = mutableListOf<MediaEntity>()
        val pendingFavoriteCloudIds = if (useCloudFavorite) {
            serverOpTaskDao.getPendingFavoriteCloudIds().toSet()
        } else {
            emptySet()
        }

        for (local in localMedia) {
            val md5 = local.md5.ifEmpty {
                val path = local.localPath
                if (computeMissingMd5 && !path.isNullOrEmpty()) {
                    LocalMediaScanner.computeFileMd5(path)
                } else {
                    ""
                }
            }
            if (md5.isNotEmpty() && !seenMd5s.add(md5)) continue

            val normalizedLocal = local.copy(md5 = md5)
            val cloud = cloudMd5Map[md5]
            val existing = findExistingEntity(md5, cloud?.cloudId, normalizedLocal.localMediaStoreId)
            result.add(
                mergeMedia(
                    existing,
                    local = normalizedLocal,
                    cloud = cloud,
                    useCloudFavorite = useCloudFavorite,
                    pendingFavoriteCloudIds = pendingFavoriteCloudIds
                )
            )
        }

        return result
    }

    private suspend fun buildCloudEntities(
        cloudPhotos: List<MediaEntity>,
        skipMd5s: Set<String> = emptySet(),
        useCloudFavorite: Boolean = false
    ): List<MediaEntity> {
        return cloudPhotos
            .filter { it.md5 !in skipMd5s }
            .chunked(500)
            .flatMap { buildCloudEntitiesChunk(it, useCloudFavorite = useCloudFavorite) }
    }

    private suspend fun buildCloudEntitiesChunk(
        cloudPhotos: List<MediaEntity>,
        useCloudFavorite: Boolean = false
    ): List<MediaEntity> {
        if (cloudPhotos.isEmpty()) return emptyList()
        val md5s = cloudPhotos.map { it.md5 }.filter { it.isNotEmpty() }.distinct()
        val cloudIds = cloudPhotos.mapNotNull { it.cloudId }.distinct()
        val existingByMd5 = if (md5s.isEmpty()) {
            emptyMap()
        } else {
            mediaDao.findByMd5List(md5s).associateBy { it.md5.ifEmpty { it.cloudMd5 ?: "" } }
        }
        val existingByCloudId = if (cloudIds.isEmpty()) {
            emptyMap()
        } else {
            mediaDao.findByCloudIds(cloudIds).associateBy { it.cloudId }
        }

        val pendingFavoriteCloudIds = if (useCloudFavorite) {
            serverOpTaskDao.getPendingFavoriteCloudIds().toSet()
        } else {
            emptySet()
        }
        val result = mutableListOf<MediaEntity>()
        for (cloud in cloudPhotos) {
            val existing = existingByMd5[cloud.md5] ?: existingByCloudId[cloud.cloudId]
            result.add(
                mergeMedia(
                    existing,
                    cloud = cloud,
                    useCloudFavorite = useCloudFavorite,
                    pendingFavoriteCloudIds = pendingFavoriteCloudIds
                )
            )
        }
        return result
    }

    private suspend fun upsertCloudPhotos(
        cloudPhotos: List<MediaEntity>,
        skipMd5s: Set<String> = emptySet(),
        batchSize: Int = 500,
        useCloudFavorite: Boolean = false,
        onProgress: suspend (written: Int, total: Int) -> Unit = { _, _ -> }
    ) {
        val candidates = cloudPhotos.filter { it.md5 !in skipMd5s }
        var written = 0
        for (chunk in candidates.chunked(batchSize.coerceAtLeast(1))) {
            val merged = buildCloudEntitiesChunk(chunk, useCloudFavorite = useCloudFavorite)
            if (merged.isNotEmpty()) {
                mediaDao.insertAll(merged)
                written += merged.size
                onProgress(written, candidates.size)
            }
        }
    }

    private suspend fun findExistingEntity(
        md5: String,
        cloudId: Double?,
        localMediaStoreId: Long?
    ): MediaEntity? {
        if (md5.isNotEmpty()) {
            mediaDao.findByMd5(md5)?.let { return it }
        }
        if (cloudId != null) {
            mediaDao.findByCloudId(cloudId)?.let { return it }
        }
        if (localMediaStoreId != null) {
            mediaDao.findByLocalMediaStoreId(localMediaStoreId)?.let { return it }
        }
        return null
    }

    private fun mergeMedia(
        existing: MediaEntity?,
        local: MediaEntity? = null,
        cloud: MediaEntity? = null,
        useCloudFavorite: Boolean = false,
        pendingFavoriteCloudIds: Set<Double> = emptySet()
    ): MediaEntity {
        val base = existing ?: local ?: cloud ?: error("mergeMedia requires at least one source")
        val localSource = local ?: existing.takeIf { it.hasLocalBinding() }
        val cloudSource = cloud ?: existing.takeIf { it.hasCloudBinding() }
        val hasLocal = localSource.hasLocalBinding()
        val hasCloud = cloudSource?.cloudId != null

        return base.copy(
            localMediaStoreId = if (hasLocal) localSource?.localMediaStoreId else null,
            localUri = if (hasLocal) localSource?.localUri else null,
            localPath = if (hasLocal) localSource?.localPath else null,
            localFolderPath = when {
                hasLocal -> localSource?.localFolderPath
                existing != null -> existing.localFolderPath
                else -> null
            },
            cloudId = if (hasCloud) cloudSource?.cloudId else null,
            cloudMd5 = when {
                hasCloud -> cloudSource?.cloudMd5 ?: cloudSource?.md5
                else -> existing?.cloudMd5
            },
            addr = cloud?.addr?.takeIf { it.isNotBlank() }
                ?: existing?.addr,
            md5 = local?.md5?.takeIf { it.isNotEmpty() }
                ?: cloud?.md5?.takeIf { it.isNotEmpty() }
                ?: existing?.md5
                ?: "",
            fileName = local?.fileName?.takeIf { it.isNotEmpty() }
                ?: cloud?.fileName?.takeIf { it.isNotEmpty() }
                ?: existing?.fileName
                ?: "",
            fileType = local?.fileType?.takeIf { it.isNotEmpty() }
                ?: cloud?.fileType?.takeIf { it.isNotEmpty() }
                ?: existing?.fileType
                ?: "",
            mtime = mergedTimelineMtime(
                existing = existing,
                local = local,
                cloud = cloud,
                cloudSource = cloudSource
            ),
            width = when {
                (local?.width ?: 0) > 0 -> local!!.width
                (cloud?.width ?: 0) > 0 -> cloud!!.width
                else -> existing?.width ?: 0
            },
            height = when {
                (local?.height ?: 0) > 0 -> local!!.height
                (cloud?.height ?: 0) > 0 -> cloud!!.height
                else -> existing?.height ?: 0
            },
            fileSize = when {
                (local?.fileSize ?: 0) > 0 -> local!!.fileSize
                (cloud?.fileSize ?: 0) > 0 -> cloud!!.fileSize
                else -> existing?.fileSize ?: 0
            },
            duration = when {
                (local?.duration ?: 0) > 0 -> local!!.duration
                (cloud?.duration ?: 0) > 0 -> cloud!!.duration
                (cloudSource?.duration ?: 0) > 0 -> cloudSource!!.duration
                else -> existing?.duration ?: 0
            },
            syncStatus = when {
                hasLocal && hasCloud -> SyncStatus.SYNCED
                hasLocal -> SyncStatus.LOCAL_ONLY
                else -> SyncStatus.CLOUD_ONLY
            },
            backupStatus = when {
                hasLocal && hasCloud -> BackupStatus.COMPLETED
                hasLocal && existing?.backupStatus == BackupStatus.UPLOADING -> BackupStatus.UPLOADING
                hasLocal && existing?.backupStatus == BackupStatus.REMOTE_DELETED -> BackupStatus.REMOTE_DELETED
                hasLocal -> BackupStatus.NOT_STARTED
                else -> existing?.backupStatus ?: BackupStatus.NOT_STARTED
            },
            thumbCachePath = existing?.thumbCachePath,
            isStorageOptimized = if (hasLocal) false else existing?.isStorageOptimized ?: false,
            isFavorite = when {
                useCloudFavorite &&
                    cloudSource?.cloudId != null &&
                    cloudSource.cloudId !in pendingFavoriteCloudIds -> cloudSource.isFavorite
                existing != null -> existing.isFavorite
                local != null -> local.isFavorite
                else -> base.isFavorite
            },
            isHide = when {
                cloudSource != null -> cloudSource.isHide
                existing != null -> existing.isHide
                local != null -> local.isHide
                else -> base.isHide
            },
            createdAt = existing?.createdAt ?: base.createdAt,
            updatedAt = System.currentTimeMillis()
        )
    }

    private fun mergedTimelineMtime(
        existing: MediaEntity?,
        local: MediaEntity?,
        cloud: MediaEntity?,
        cloudSource: MediaEntity?
    ): String {
        return cloud?.mtime?.takeIf { it.isNotEmpty() }
            ?: cloudSource?.mtime?.takeIf { it.isNotEmpty() }
            ?: existing?.takeIf { it.hasCloudBinding() }?.mtime?.takeIf { it.isNotEmpty() }
            ?: local?.mtime?.takeIf { it.isNotEmpty() }
            ?: existing?.mtime?.takeIf { it.isNotEmpty() }
            ?: ""
    }

    private fun MediaEntity?.hasLocalBinding(): Boolean {
        return this?.localMediaStoreId != null ||
            !this?.localUri.isNullOrEmpty() ||
            !this?.localPath.isNullOrEmpty()
    }

    private fun MediaEntity?.hasCloudBinding(): Boolean {
        return this?.cloudId != null || !this?.cloudMd5.isNullOrEmpty()
    }

    private fun deleteThumbCaches(entities: List<MediaEntity>) {
        for (entity in entities) {
            entity.thumbCachePath?.let { path ->
                try {
                    File(path).delete()
                } catch (_: Exception) {
                }
            }
        }
    }

    suspend fun reconcileFolderSelection(localFolders: Set<String>?) = withContext(Dispatchers.IO) {
        if (localFolders == null) return@withContext

        val localRecords = mediaDao.getLocalBoundMedia()
        val outOfScope = localRecords.filter {
            !FolderPathMatcher.isInAnyScope(it.localFolderPath, localFolders)
        }
        if (outOfScope.isEmpty()) return@withContext

        val toDelete = outOfScope
            .filter { it.syncStatus == SyncStatus.LOCAL_ONLY || it.cloudId == null }
            .map { it.id }
        val toClearLocal = outOfScope
            .filter { it.syncStatus != SyncStatus.LOCAL_ONLY && it.cloudId != null }
            .map { it.id }

        if (toDelete.isNotEmpty()) {
            mediaDao.deleteByIds(toDelete)
        }
        if (toClearLocal.isNotEmpty()) {
            mediaDao.clearLocalFields(toClearLocal)
        }

        Log.d(
            TAG,
            "Reconciled folder selection: removed ${toDelete.size} local-only records, " +
                "cleared ${toClearLocal.size} synced records"
        )
    }

    suspend fun getTimelineMonths(localFolders: Set<String>? = null): List<TimelineMonthCount> = withContext(Dispatchers.IO) {
        val pendingCloudDeleteIds = getPendingCloudDeleteCloudIds()
        if (pendingCloudDeleteIds.isEmpty() && localFolders == null) {
            return@withContext mediaDao.getTimelineMonths()
        }
        if (pendingCloudDeleteIds.isEmpty() && localFolders?.isEmpty() == true) {
            return@withContext mediaDao.getCloudTimelineMonths()
        }

        val entities = when {
            localFolders == null -> mediaDao.getAllMedia()
            localFolders.isEmpty() -> mediaDao.getAllCloudMedia()
            else -> mediaDao.getAllMedia().filter { entity ->
                entity.cloudId != null ||
                    FolderPathMatcher.isInAnyScope(entity.localFolderPath, localFolders)
            }
        }
        return@withContext entities
            .filterNot { entity -> entity.cloudId in pendingCloudDeleteIds }
            .toTimelineMonthCounts()
    }

    suspend fun getMonthPhotos(
        yearMonth: String,
        localFolders: Set<String>? = null
    ): List<UnifiedPhotoItem> = withContext(Dispatchers.IO) {
        val entities = when {
            localFolders == null -> mediaDao.getMediaByMonth(yearMonth)
            localFolders.isEmpty() -> mediaDao.getCloudMediaByMonth(yearMonth)
            else -> mediaDao.getMediaByMonth(yearMonth).filter { entity ->
                entity.cloudId != null ||
                    FolderPathMatcher.isInAnyScope(entity.localFolderPath, localFolders)
            }
        }
        val pendingCloudDeleteIds = getPendingCloudDeleteCloudIds()
        return@withContext entities
            .filterNot { entity -> entity.cloudId in pendingCloudDeleteIds }
            .map { it.toUnifiedPhotoItem(localFolders) }
    }

    private fun List<MediaEntity>.toTimelineMonthCounts(): List<TimelineMonthCount> {
        return groupBy { it.mtime.take(7) }
            .filterKeys { it.length == 7 }
            .map { (yearMonth, entities) -> TimelineMonthCount(yearMonth, entities.size) }
            .sortedByDescending { it.yearMonth }
    }

    fun getMonthPhotosFlow(yearMonth: String): Flow<List<UnifiedPhotoItem>> {
        return mediaDao.getMediaByMonthFlow(yearMonth).map { entities ->
            entities.map { it.toUnifiedPhotoItem() }
        }
    }

    fun getAllMediaFlow(localFolders: Set<String>? = null): Flow<List<UnifiedPhotoItem>> {
        return mediaDao.getAllMediaFlow().map { entities ->
            entities
                .filter { entity ->
                    localFolders == null ||
                        entity.cloudId != null ||
                        FolderPathMatcher.isInAnyScope(entity.localFolderPath, localFolders)
                }
                .map { it.toUnifiedPhotoItem(localFolders) }
        }
    }

    suspend fun hasMediaStoreChangedSinceLastSync(): Boolean = withContext(Dispatchers.IO) {
        detectMediaStoreChangesSinceLastSync().changed
    }

    suspend fun detectMediaStoreChangesSinceLastSync(): MediaStoreChange = withContext(Dispatchers.IO) {
        val current = readMediaStoreFingerprint()
        val previous = decodeMediaStoreFingerprint(scanPrefs.getString(KEY_MEDIASTORE_FINGERPRINT, null))
        val changed = previous == null || previous.encode() != current.encode()
        val mayIncludeDeletion = previous == null ||
            current.imageCount < previous.imageCount ||
            current.videoCount < previous.videoCount ||
            (
                current.imageCount == previous.imageCount &&
                    current.videoCount == previous.videoCount &&
                    current.latestModifiedSeconds != previous.latestModifiedSeconds
            )
        if (changed) {
            Log.d(
                TAG,
                "MediaStore fingerprint changed: previous=${previous?.encode()} " +
                    "current=${current.encode()} mayIncludeDeletion=$mayIncludeDeletion"
            )
        }
        MediaStoreChange(changed, mayIncludeDeletion)
    }

    private fun rememberCurrentMediaStoreFingerprint() {
        val current = readMediaStoreFingerprint().encode()
        scanPrefs.edit().putString(KEY_MEDIASTORE_FINGERPRINT, current).apply()
    }

    private data class MediaStoreFingerprint(
        val generation: Long,
        val imageCount: Int,
        val videoCount: Int,
        val latestModifiedSeconds: Long
    ) {
        fun encode(): String = "$generation|$imageCount|$videoCount|$latestModifiedSeconds"
    }

    private fun decodeMediaStoreFingerprint(encoded: String?): MediaStoreFingerprint? {
        if (encoded.isNullOrBlank()) return null
        val parts = encoded.split('|')
        if (parts.size != 4) return null
        return MediaStoreFingerprint(
            generation = parts[0].toLongOrNull() ?: return null,
            imageCount = parts[1].toIntOrNull() ?: return null,
            videoCount = parts[2].toIntOrNull() ?: return null,
            latestModifiedSeconds = parts[3].toLongOrNull() ?: return null
        )
    }

    private data class MediaStoreStats(
        val count: Int = 0,
        val latestModifiedSeconds: Long = 0L
    )

    private fun readMediaStoreFingerprint(): MediaStoreFingerprint {
        val context = container.prefsManager.context
        val generation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { MediaStore.getGeneration(context, MediaStore.VOLUME_EXTERNAL) }
                .getOrElse {
                    Log.w(TAG, "Failed to read MediaStore generation", it)
                    -1L
                }
        } else {
            -1L
        }
        val imageStats = queryMediaStoreStats(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        val videoStats = queryMediaStoreStats(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
        return MediaStoreFingerprint(
            generation = generation,
            imageCount = imageStats.count,
            videoCount = videoStats.count,
            latestModifiedSeconds = maxOf(
                imageStats.latestModifiedSeconds,
                videoStats.latestModifiedSeconds
            )
        )
    }

    private fun queryMediaStoreStats(collection: Uri): MediaStoreStats {
        return try {
            container.prefsManager.context.contentResolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns.DATE_MODIFIED),
                visibleMediaSelection(),
                null,
                "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
            )?.use { cursor ->
                val modifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                val latest = if (cursor.moveToFirst()) cursor.getLong(modifiedColumn) else 0L
                MediaStoreStats(count = cursor.count, latestModifiedSeconds = latest)
            } ?: MediaStoreStats()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query MediaStore stats for $collection", e)
            MediaStoreStats()
        }
    }

    private fun visibleMediaSelection(): String? {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                "${MediaStore.MediaColumns.IS_PENDING} = 0 AND ${MediaStore.MediaColumns.IS_TRASHED} = 0"
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                "${MediaStore.MediaColumns.IS_PENDING} = 0"
            else -> null
        }
    }

    private suspend fun mergeComputedMd5(
        entity: MediaEntity,
        md5: String,
        cloud: MediaEntity?
    ) {
        val localWithMd5 = entity.copy(md5 = md5)
        val existing = findExistingEntity(md5, cloud?.cloudId, entity.localMediaStoreId)
        val target = if (existing != null && existing.id != entity.id) existing else entity
        val merged = mergeMedia(target, local = localWithMd5, cloud = cloud)

        if (existing != null && existing.id != entity.id) {
            mediaDao.deleteById(entity.id)
        }
        mediaDao.insert(merged)
        val duplicateIds = mediaDao.findByMd5List(listOf(md5))
            .map { it.id }
            .filter { it != merged.id }
        if (duplicateIds.isNotEmpty()) {
            mediaDao.deleteByIds(duplicateIds)
        }
    }

    suspend fun getPendingBackupMedia(localFolders: Set<String>? = null): List<MediaEntity> {
        return when {
            localFolders == null -> mediaDao.getPendingBackupMedia()
            localFolders.isEmpty() -> emptyList()
            else -> mediaDao.getPendingBackupMedia().filter {
                FolderPathMatcher.isInAnyScope(it.localFolderPath, localFolders)
            }
        }
    }

    suspend fun getPendingBackupCount(localFolders: Set<String>? = null): Int {
        return when {
            localFolders == null -> mediaDao.getPendingBackupCount()
            localFolders.isEmpty() -> 0
            else -> getPendingBackupMedia(localFolders).size
        }
    }

    suspend fun retryBackupsMissingFromCloud(dbIds: List<Long>): Int = withContext(Dispatchers.IO) {
        if (dbIds.isEmpty()) return@withContext 0

        val entities = mediaDao.findByIds(dbIds.distinct())
        if (entities.isEmpty()) return@withContext 0

        val cloudPhotos = normalizeCloudPhotos(fetchAllCloudPhotos().entities)
        val cloudIds = cloudPhotos.mapNotNull { it.cloudId }.toSet()
        val cloudMd5s = cloudPhotos.map { it.md5 }.filter { it.isNotEmpty() }.toSet()

        val missingIds = entities.filter { entity ->
            val cloudIdMissing = entity.cloudId?.let { it !in cloudIds } ?: true
            val md5 = entity.md5.ifEmpty { entity.cloudMd5 ?: "" }
            val md5Missing = md5.isEmpty() || md5 !in cloudMd5s
            cloudIdMissing && md5Missing
        }.map { it.id }

        if (missingIds.isEmpty()) {
            0
        } else {
            mediaDao.markBackupsForRetry(missingIds)
        }
    }

    suspend fun markBackupsForRetry(dbIds: List<Long>): Int = withContext(Dispatchers.IO) {
        if (dbIds.isEmpty()) 0 else mediaDao.markBackupsForRetry(dbIds.distinct())
    }

    suspend fun repairMissingBackups(
        localFolders: Set<String>? = null,
        includeRemoteDeleted: Boolean = false
    ): BackupRepairResult = withContext(Dispatchers.IO) {
        val cloudResult = fetchAllCloudPhotos()
        val cloudPhotos = normalizeCloudPhotos(cloudResult.entities)
        val cloudMd5Map = cloudPhotos.associateBy { it.md5 }
        val cloudMd5s = cloudMd5Map.keys

        val localMedia = localScanner.scanMedia(localFolders, computeMd5 = true)
        val merged = prepareLocalEntities(
            localMedia,
            cloudMd5Map = cloudMd5Map,
            useCloudFavorite = cloudResult.favoriteStateKnown
        )
        if (merged.isNotEmpty()) {
            mediaDao.insertAll(merged)
        }

        val missingMd5s = localMedia
            .map { it.md5 }
            .filter { it.isNotEmpty() && it !in cloudMd5s }
            .distinct()

        var resetCount = 0
        for (chunk in missingMd5s.chunked(400)) {
            resetCount += if (includeRemoteDeleted) {
                mediaDao.markMissingBackupsForRetryIncludingRemoteDeleted(chunk)
            } else {
                mediaDao.markMissingBackupsForRetry(chunk)
            }
        }

        BackupRepairResult(
            scannedCount = localMedia.size,
            resetCount = resetCount
        )
    }

    suspend fun getOptimizableMedia(): List<MediaEntity> {
        return mediaDao.getOptimizableMedia()
    }

    suspend fun getOptimizableCount(): Int {
        return mediaDao.getOptimizableCount()
    }

    suspend fun getOptimizableSize(): Long {
        return mediaDao.getOptimizableSize()
    }

    suspend fun getSyncedCount(): Int {
        return mediaDao.getCountBySyncStatus(SyncStatus.SYNCED)
    }

    suspend fun getBackedUpCount(): Int {
        return mediaDao.getBackedUpCount()
    }

    suspend fun getBackedUpSize(): Long {
        return mediaDao.getBackedUpSize()
    }

    suspend fun markAsBackedUp(dbId: Long, cloudId: Double, cloudMd5: String) {
        mediaDao.markAsBackedUp(dbId, cloudId = cloudId, cloudMd5 = cloudMd5)
    }

    suspend fun claimPendingUpload(dbId: Long): Boolean {
        return mediaDao.claimForUpload(dbId) > 0
    }

    suspend fun resetStaleUploading(staleBefore: Long): Int {
        return mediaDao.resetStaleUploading(staleBefore)
    }

    suspend fun markAsStorageOptimized(dbId: Long) {
        mediaDao.markAsStorageOptimized(dbId)
    }

    suspend fun recordBackupFolderHistory(paths: Collection<String?>) {
        container.prefsManager.addBackupFolderHistory(paths)
    }

    suspend fun deleteByCloudId(cloudId: Double) {
        mediaDao.deleteByCloudId(cloudId)
    }

    suspend fun deleteByLocalId(localId: Long) {
        mediaDao.deleteByLocalMediaStoreId(localId)
    }

    suspend fun findByMd5(md5: String): MediaEntity? {
        return mediaDao.findByMd5(md5)
    }

    suspend fun markOriginalDownloaded(md5: String, localUri: String, localPath: String) {
        mediaDao.markOriginalDownloaded(md5, localUri, localPath)
    }

    suspend fun findByCloudId(cloudId: Double): MediaEntity? {
        return mediaDao.findByCloudId(cloudId)
    }

    suspend fun updateThumbCachePath(dbId: Long, path: String) {
        mediaDao.updateThumbCachePath(dbId, path)
    }

    suspend fun ensureLocalVideoThumbnail(photo: UnifiedPhotoItem): String? {
        if (!photo.isVideo() || photo.localUri.isNullOrEmpty() || photo.isStorageOptimized) return null
        if (!photo.thumbCachePath.isNullOrEmpty() && File(photo.thumbCachePath).let { it.exists() && it.length() > 0 }) {
            return photo.thumbCachePath
        }

        val cacheKey = if (photo.md5.isNotEmpty()) {
            photo.md5
        } else if (photo.dbId > 0) {
            "local_${photo.dbId}"
        } else {
            return null
        }
        val path = localVideoThumbnailGenerator.generate(photo.localUri, cacheKey) ?: return null
        if (photo.dbId > 0) {
            updateThumbCachePath(photo.dbId, path)
        }
        return path
    }

    suspend fun hasData(): Boolean {
        return mediaDao.getTotalCount() > 0
    }

    suspend fun getKnownLocalFolders(): List<String> {
        return mediaDao.getAllLocalFolders()
    }

    suspend fun findMediaEntitiesByIds(ids: List<Double>): List<MediaEntity> {
        if (ids.isEmpty()) return emptyList()
        val cloudIds = ids.filter { it > 0 }
        val dbIds = ids.map { it.toLong() }.filter { it > 0 }
        return (mediaDao.findByCloudIds(cloudIds) + mediaDao.findByIds(dbIds)).distinctBy { it.id }
    }

    suspend fun findMediaEntitiesForPhotos(photos: List<UnifiedPhotoItem>): List<MediaEntity> {
        if (photos.isEmpty()) return emptyList()
        val dbIds = photos.map { it.dbId }.filter { it > 0 }.distinct()
        val cloudIds = photos.mapNotNull { it.cloudId?.takeIf { id -> id > 0 } }.distinct()
        val byDbId = if (dbIds.isEmpty()) emptyMap() else mediaDao.findByIds(dbIds).associateBy { it.id }
        val byCloudId = if (cloudIds.isEmpty()) {
            emptyMap()
        } else {
            mediaDao.findByCloudIds(cloudIds).associateBy { it.cloudId }
        }
        return photos.mapNotNull { photo ->
            byDbId[photo.dbId] ?: photo.cloudId?.let { byCloudId[it] }
        }.distinctBy { it.id }
    }

    fun ensureLocalDeleteAllowed(entities: List<MediaEntity>) {
        val localEntities = entities.filter { it.localMediaStoreId != null }
        if (localEntities.isNotEmpty() && !isManageStorageGranted()) {
            throw SecurityException("MANAGE_EXTERNAL_STORAGE is required for direct local delete")
        }
    }

    /** 委托给 ServerOpTaskRepository */
    suspend fun enqueueCloudDeleteTasks(entities: List<MediaEntity>) = withContext(Dispatchers.IO) {
        container.serverOpTaskRepository.enqueueCloudDelete(entities)
    }

    suspend fun deleteLocalMediaFiles(entities: List<MediaEntity>) = withContext(Dispatchers.IO) {
        if (entities.isEmpty()) return@withContext
        recordBackupFolderHistory(entities.map { it.localFolderPath })
        ensureLocalDeleteAllowed(entities)

        val localEntities = entities.filter { it.localMediaStoreId != null }
        val failed = localEntities.filterNot { deleteLocalMediaDirect(it) }
        if (failed.isNotEmpty()) {
            throw IllegalStateException("Local delete failed for ${failed.size} file(s)")
        }

        for (entity in entities) {
            entity.thumbCachePath?.let { path ->
                try {
                    File(path).delete()
                } catch (_: Exception) {
                }
            }
        }
    }

    suspend fun deleteMediaRecords(entities: List<MediaEntity>) {
        if (entities.isEmpty()) return
        mediaDao.deleteByIds(entities.map { it.id })
    }

    suspend fun deleteLocalMediaForOptimization(entity: MediaEntity): Boolean = withContext(Dispatchers.IO) {
        val localId = entity.localMediaStoreId ?: return@withContext false
        if (!isManageStorageGranted()) {
            Log.w(TAG, "Skip optimization delete without MANAGE_EXTERNAL_STORAGE: $localId")
            return@withContext false
        }
        deleteLocalMediaDirect(entity)
    }

    private fun isManageStorageGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.os.Environment.isExternalStorageManager()
        } else {
            true
        }
    }

    private fun deleteLocalMediaDirect(entity: MediaEntity): Boolean {
        val localId = entity.localMediaStoreId ?: return true
        val context = container.prefsManager.context
        val contentUri = if (entity.isVideoMedia()) {
            ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, localId)
        } else {
            ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, localId)
        }

        return try {
            val deletedRows = context.contentResolver.delete(contentUri, null, null)
            if (deletedRows > 0 || !mediaStoreUriExists(contentUri)) {
                true
            } else {
                deleteLocalPathFallback(entity) && !File(entity.localPath.orEmpty()).exists()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Direct local delete failed: $contentUri", e)
            deleteLocalPathFallback(entity) && !File(entity.localPath.orEmpty()).exists()
        }
    }

    private fun mediaStoreUriExists(uri: android.net.Uri): Boolean {
        val context = container.prefsManager.context
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns._ID),
                null,
                null,
                null
            )?.use { cursor -> cursor.moveToFirst() } ?: false
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore verify failed: $uri", e)
            true
        }
    }

    private suspend fun cleanupDeletedMediaUris(uris: List<Uri>): Int {
        var changed = 0
        for (hint in uris.mapNotNull { it.toLocalMediaHint() }.distinct()) {
            val contentUri = ContentUris.withAppendedId(
                if (hint.isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                else MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                hint.localMediaStoreId
            )
            if (mediaStoreUriExists(contentUri)) continue

            val uriPattern = if (hint.isVideo) "%/video/%" else "%/images/%"
            changed += mediaDao.deleteLocalOnlyByLocalId(hint.localMediaStoreId, uriPattern)
            changed += mediaDao.clearCloudBackedLocalFieldsByLocalId(hint.localMediaStoreId, uriPattern)
        }
        if (changed > 0) {
            Log.d(TAG, "Fast-cleared $changed deleted local media binding(s)")
            MediaChangeObserver.clearDirty()
        }
        return changed
    }

    private data class LocalMediaHint(
        val localMediaStoreId: Long,
        val isVideo: Boolean
    )

    private fun Uri.toLocalMediaHint(): LocalMediaHint? {
        val uriText = toString().lowercase()
        val isVideo = uriText.contains("/video/")
        val isImage = uriText.contains("/images/")
        if (!isVideo && !isImage) return null

        val localId = runCatching { ContentUris.parseId(this) }.getOrNull()
            ?.takeIf { it > 0 }
            ?: return null
        return LocalMediaHint(localMediaStoreId = localId, isVideo = isVideo)
    }

    private fun deleteLocalPathFallback(entity: MediaEntity): Boolean {
        val path = entity.localPath ?: return false
        return try {
            val file = File(path)
            !file.exists() || file.delete()
        } catch (e: Exception) {
            Log.w(TAG, "File fallback delete failed: $path", e)
            false
        }
    }

    private fun MediaEntity.isVideoMedia(): Boolean {
        val type = fileType.lowercase()
        return type.startsWith("video") ||
            type in setOf("mp4", "mov", "avi", "mkv") ||
            fileName.endsWith(".mp4", true) ||
            fileName.endsWith(".mov", true) ||
            fileName.endsWith(".avi", true) ||
            fileName.endsWith(".mkv", true)
    }

    private suspend fun cleanupMissingLocalRecords(
        scannedMedia: List<MediaEntity>,
        newCount: Int,
        forceCheck: Boolean = false
    ): Int {
        val now = System.currentTimeMillis()
        val lastCheckAt = scanPrefs.getLong(KEY_LAST_ORPHAN_CHECK_AT, 0L)
        val shouldCheck = forceCheck ||
            scannedMedia.isEmpty() ||
            newCount == 0 ||
            now - lastCheckAt >= INCREMENTAL_ORPHAN_CHECK_INTERVAL_MS
        if (!shouldCheck) return 0

        scanPrefs.edit().putLong(KEY_LAST_ORPHAN_CHECK_AT, now).apply()
        return cleanupOrphanedLocalRecords()
    }

    suspend fun cleanupOrphanedLocalRecords(): Int {
        val refs = mediaDao.getLocalFileRefs()
        if (refs.isEmpty()) return 0

        val existingRefIds = findExistingLocalRefIds(refs)
        val orphanedRefs = refs.filter { it.id !in existingRefIds }
        if (orphanedRefs.isEmpty()) return 0

        Log.d(TAG, "Found ${orphanedRefs.size} orphaned local records")

        val toDelete = mutableListOf<Long>()
        val toClearLocal = mutableListOf<Long>()

        for (ref in orphanedRefs) {
            if (ref.syncStatus == SyncStatus.LOCAL_ONLY || ref.cloudId == null) {
                toDelete.add(ref.id)
            } else {
                toClearLocal.add(ref.id)
            }
        }

        if (toDelete.isNotEmpty()) {
            val entities = mediaDao.findByIds(toDelete)
            recordBackupFolderHistory(entities.map { it.localFolderPath })
            for (entity in entities) {
                entity.thumbCachePath?.let { path ->
                    try {
                        File(path).delete()
                    } catch (_: Exception) {
                    }
                }
            }
            mediaDao.deleteByIds(toDelete)
            Log.d(TAG, "Deleted ${toDelete.size} orphaned LOCAL_ONLY records")
        }

        if (toClearLocal.isNotEmpty()) {
            val entities = mediaDao.findByIds(toClearLocal)
            recordBackupFolderHistory(entities.map { it.localFolderPath })
            mediaDao.clearLocalFields(toClearLocal)
            Log.d(TAG, "Cleared local fields for ${toClearLocal.size} SYNCED records to CLOUD_ONLY")
        }

        MediaChangeObserver.clearDirty()
        return orphanedRefs.size
    }

    private fun findExistingLocalRefIds(refs: List<LocalFileRef>): Set<Long> {
        if (refs.isEmpty()) return emptySet()
        val existing = mutableSetOf<Long>()
        val refsByVideo = refs.groupBy { it.isVideoRef() }

        for ((isVideo, groupedRefs) in refsByVideo) {
            val collection = if (isVideo) {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            groupedRefs.chunked(950).forEach { chunk ->
                val placeholders = chunk.joinToString(",") { "?" }
                val byLocalId = chunk.groupBy { it.localMediaStoreId }
                container.prefsManager.context.contentResolver.query(
                    collection,
                    arrayOf(MediaStore.MediaColumns._ID),
                    "${MediaStore.MediaColumns._ID} IN ($placeholders)",
                    chunk.map { it.localMediaStoreId.toString() }.toTypedArray(),
                    null
                )?.use { cursor ->
                    val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    while (cursor.moveToNext()) {
                        val localId = cursor.getLong(idColumn)
                        byLocalId[localId].orEmpty().mapTo(existing) { it.id }
                    }
                }
            }
        }

        return existing
    }

    private fun LocalFileRef.isVideoRef(): Boolean {
        return localUri?.contains("/video/", ignoreCase = true) == true ||
            fileType.startsWith("video", ignoreCase = true) ||
            fileType in setOf("mp4", "mov", "avi", "mkv")
    }
}

fun MediaEntity.toUnifiedPhotoItem(localFolders: Set<String>? = null): UnifiedPhotoItem {
    val localVisible = localFolders == null ||
        FolderPathMatcher.isInAnyScope(localFolderPath, localFolders)
    val hideOutOfScopeLocal = !localVisible && cloudId != null

    return UnifiedPhotoItem(
        dbId = id,
        cloudId = cloudId,
        md5 = md5.ifEmpty { cloudMd5 ?: "" },
        fileName = fileName,
        fileType = fileType,
        mtime = mtime,
        width = width.toDouble(),
        height = height.toDouble(),
        syncStatus = if (hideOutOfScopeLocal) SyncStatus.CLOUD_ONLY else syncStatus,
        backupStatus = backupStatus,
        localUri = if (localVisible) localUri else null,
        thumbCachePath = thumbCachePath,
        isStorageOptimized = if (localVisible) isStorageOptimized else true,
        isFavorite = isFavorite,
        isHide = isHide,
        fileSize = fileSize,
        duration = duration,
        addr = addr,
        livePhotosVideoId = livePhotosVideoId,
        isLivePhotosVideo = isLivePhotosVideo,
        livePhotoUuid = livePhotoUuid
    )
}

fun PhotoItem.toCloudOnlyUnifiedPhotoItem(): UnifiedPhotoItem {
    return UnifiedPhotoItem(
        cloudId = id,
        md5 = md5,
        fileName = fileName,
        fileType = fileType,
        mtime = mtime,
        width = width,
        height = height,
        syncStatus = SyncStatus.CLOUD_ONLY,
        backupStatus = BackupStatus.NOT_STARTED,
        isFavorite = isFavorite,
        isHide = isHide,
        addr = addr,
        livePhotosVideoId = livePhotosVideoId,
        isLivePhotosVideo = isLivePhotosVideo,
        livePhotoUuid = livePhotoUuid,
        duration = duration
    )
}
