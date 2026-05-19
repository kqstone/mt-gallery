package com.kqstone.mtphotos.data.repository

import android.content.ContentUris
import android.os.Build
import android.provider.MediaStore
import android.util.Log
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
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "SyncRepo"

class SyncRepository(
    private val container: AppContainer,
    private val database: AppDatabase
) {
    private val mediaDao: MediaDao get() = database.mediaDao()
    private val galleryRepository: GalleryRepository get() = container.galleryRepository
    private val localScanner: LocalMediaScanner by lazy { LocalMediaScanner(container.prefsManager.context) }

    suspend fun performFullSync(localFolders: Set<String>? = null) = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting full sync...")

            val localMedia = localScanner.scanMedia(localFolders, computeMd5 = true)
            Log.d(TAG, "Local scan found ${localMedia.size} files")

            val fetchedCloudPhotos = fetchAllCloudPhotos()
            val cloudDeleteChanges = reconcileCloudDeletions(fetchedCloudPhotos)
            val cloudPhotos = normalizeCloudPhotos(fetchedCloudPhotos)
            Log.d(TAG, "Cloud has ${cloudPhotos.size} files")
            if (cloudDeleteChanges.changed > 0) {
                Log.d(TAG, "Cloud deletion reconciliation: $cloudDeleteChanges")
            }

            val cloudMd5Map = cloudPhotos.associateBy { it.md5 }
            val mergedLocal = prepareLocalEntities(localMedia, cloudMd5Map = cloudMd5Map)
            val processedMd5s = mergedLocal.map { it.md5 }.toSet()
            val mergedCloud = buildCloudEntities(cloudPhotos, processedMd5s)
            val mergedEntities = mergedLocal + mergedCloud

            if (mergedEntities.isNotEmpty()) {
                mediaDao.insertAll(mergedEntities)
            }
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

            val fetchedCloudPhotos = fetchAllCloudPhotos()
            val cloudDeleteChanges = reconcileCloudDeletions(fetchedCloudPhotos)
            val cloudPhotos = normalizeCloudPhotos(fetchedCloudPhotos)
            val cloudMd5Map = cloudPhotos.associateBy { it.md5 }
            if (cloudDeleteChanges.changed > 0) {
                Log.d(TAG, "Cloud deletion reconciliation: $cloudDeleteChanges")
            }
            emit(SyncProgress(0, 0, "cloud_indexed", cloudPhotos.size))

            val matchedMd5s = mutableSetOf<String>()
            val seenLocalMd5s = mutableSetOf<String>()
            var scannedCount = 0

            localScanner.scanMediaFlow(
                folderPaths = localFolders,
                batchSize = 50,
                computeMd5 = true
            ).collect { batch ->
                scannedCount += batch.size

                val mergedBatch = mutableListOf<MediaEntity>()
                for (local in batch) {
                    val md5 = local.md5
                    if (md5.isEmpty()) {
                        Log.w(TAG, "Skipping local item without md5 during initial sync: ${local.localPath}")
                        continue
                    }
                    if (!seenLocalMd5s.add(md5)) continue

                    val cloud = cloudMd5Map[md5]
                    if (cloud != null) {
                        matchedMd5s.add(md5)
                    }
                    val existing = findExistingEntity(md5, cloud?.cloudId, local.localMediaStoreId)
                    mergedBatch.add(mergeMedia(existing, local = local, cloud = cloud))
                }

                if (mergedBatch.isNotEmpty()) {
                    mediaDao.insertAll(mergedBatch)
                }
                emit(SyncProgress(scannedCount, 0, "scanning", cloudPhotos.size))
            }

            emit(SyncProgress(scannedCount, 0, "finalizing", cloudPhotos.size))
            upsertCloudPhotos(cloudPhotos, skipMd5s = matchedMd5s)

            emit(SyncProgress(scannedCount, scannedCount, "cleanup", cloudPhotos.size))
            val cleaned = cleanupOrphanedLocalRecords()
            if (cleaned > 0) Log.d(TAG, "Cleanup: removed $cleaned orphaned records")

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

            val fetchedCloudPhotos = fetchAllCloudPhotos()
            val cloudDeleteChanges = reconcileCloudDeletions(fetchedCloudPhotos)
            val cloudPhotos = normalizeCloudPhotos(fetchedCloudPhotos)
            Log.d(TAG, "Cloud has ${cloudPhotos.size} files")
            if (cloudDeleteChanges.changed > 0) {
                Log.d(TAG, "Cloud deletion reconciliation: $cloudDeleteChanges")
            }

            upsertCloudPhotos(cloudPhotos)
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
                    seenMd5s = seenLocalMd5s
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

    private data class CloudDeleteChanges(
        val deletedCloudOnly: Int = 0,
        val localRetained: Int = 0
    ) {
        val changed: Int get() = deletedCloudOnly + localRetained
    }

    suspend fun syncLocalMedia(folders: Set<String>? = null): SyncResult = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting local-only sync...")

            val localMedia = localScanner.scanMedia(folders, computeMd5 = false)
            Log.d(TAG, "Local scan found ${localMedia.size} files")

            val existingLocalIds = if (localMedia.isNotEmpty()) {
                val batchIds = localMedia.mapNotNull { it.localMediaStoreId }
                batchIds.chunked(500).flatMap { mediaDao.findExistingLocalIds(it) }.toSet()
            } else {
                emptySet()
            }

            val newEntities = prepareLocalEntities(
                localMedia = localMedia.filter { it.localMediaStoreId !in existingLocalIds }
            )
            if (newEntities.isNotEmpty()) {
                mediaDao.insertAll(newEntities)
                Log.d(TAG, "Inserted ${newEntities.size} new local files")
            }

            val removed = cleanupOrphanedLocalRecords()
            SyncResult(newEntities.size, removed)
        } catch (e: Exception) {
            Log.e(TAG, "Local sync failed", e)
            throw e
        }
    }

    suspend fun computeMd5InBackground() = withContext(Dispatchers.IO) {
        try {
            val needMd5 = mediaDao.getMediaBySyncStatus(SyncStatus.LOCAL_ONLY)
                .filter { it.md5.isEmpty() && !it.localPath.isNullOrEmpty() }

            if (needMd5.isEmpty()) {
                Log.d(TAG, "No files need MD5 computation")
                return@withContext
            }

            Log.d(TAG, "Computing MD5 for ${needMd5.size} files...")
            val cloudPhotos = normalizeCloudPhotos(fetchAllCloudPhotos())
            val cloudMd5Map = cloudPhotos.associateBy { it.md5 }

            var updated = 0
            for (entity in needMd5) {
                val md5 = LocalMediaScanner.computeFileMd5(entity.localPath!!)
                if (md5.isEmpty()) continue

                val cloud = cloudMd5Map[md5]
                val localWithMd5 = entity.copy(md5 = md5)
                val existing = findExistingEntity(md5, cloud?.cloudId, entity.localMediaStoreId)
                val target = if (existing != null && existing.id != entity.id) existing else entity
                val merged = mergeMedia(target, local = localWithMd5, cloud = cloud)

                if (existing != null && existing.id != entity.id) {
                    mediaDao.deleteById(entity.id)
                }
                mediaDao.insert(merged)
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

    suspend fun syncCloudOnly() = withContext(Dispatchers.IO) {
        try {
            val fetchedCloudPhotos = fetchAllCloudPhotos()
            val cloudDeleteChanges = reconcileCloudDeletions(fetchedCloudPhotos)
            val cloudPhotos = normalizeCloudPhotos(fetchedCloudPhotos)
            Log.d(TAG, "Cloud-only sync: ${cloudPhotos.size} files")
            if (cloudDeleteChanges.changed > 0) {
                Log.d(TAG, "Cloud deletion reconciliation: $cloudDeleteChanges")
            }
            upsertCloudPhotos(cloudPhotos)
        } catch (e: Exception) {
            Log.e(TAG, "Cloud-only sync failed", e)
            throw e
        }
    }

    suspend fun refreshCloudState() = withContext(Dispatchers.IO) {
        try {
            val fetchedCloudPhotos = fetchAllCloudPhotos()
            val cloudDeleteChanges = reconcileCloudDeletions(fetchedCloudPhotos)
            val cloudPhotos = normalizeCloudPhotos(fetchedCloudPhotos)
            if (cloudDeleteChanges.changed > 0) {
                Log.d(TAG, "Cloud deletion reconciliation: $cloudDeleteChanges")
            }
            upsertCloudPhotos(cloudPhotos)
            Log.d(TAG, "Cloud state refresh complete: ${cloudPhotos.size} files")
        } catch (e: Exception) {
            Log.e(TAG, "Cloud state refresh failed", e)
            throw e
        }
    }

    private suspend fun fetchAllCloudPhotos(): List<MediaEntity> {
        val snapshot = galleryRepository.getTimelineSnapshot().getOrElse { throw it }
        val result = snapshot.photos.map { photo ->
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
        }
        if (snapshot.months.sumOf { it.count } > 0 && result.isEmpty()) {
            throw IllegalStateException("Cloud timeline has items, but no cloud file details were parsed")
        }
        return result
    }

    suspend fun getAllPhotos(localFolders: Set<String>? = null): List<UnifiedPhotoItem> {
        val entities = when {
            localFolders == null -> mediaDao.getAllMedia()
            localFolders.isEmpty() -> mediaDao.getAllCloudMedia()
            else -> mediaDao.getAllVisibleMediaByFolders(localFolders.toList())
        }
        return entities.map { it.toUnifiedPhotoItem(localFolders) }
    }

    private fun normalizeCloudPhotos(cloudPhotos: List<MediaEntity>): List<MediaEntity> {
        return cloudPhotos
            .filter { it.md5.isNotEmpty() }
            .distinctBy { it.md5 }
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
        seenMd5s: MutableSet<String> = mutableSetOf()
    ): List<MediaEntity> {
        val result = mutableListOf<MediaEntity>()

        for (local in localMedia) {
            val md5 = local.md5.ifEmpty {
                val path = local.localPath
                if (path.isNullOrEmpty()) "" else LocalMediaScanner.computeFileMd5(path)
            }
            if (md5.isEmpty()) {
                Log.w(TAG, "Skipping local item without md5: ${local.localPath}")
                continue
            }
            if (!seenMd5s.add(md5)) continue

            val normalizedLocal = local.copy(md5 = md5)
            val cloud = cloudMd5Map[md5]
            val existing = findExistingEntity(md5, cloud?.cloudId, normalizedLocal.localMediaStoreId)
            result.add(mergeMedia(existing, local = normalizedLocal, cloud = cloud))
        }

        return result
    }

    private suspend fun buildCloudEntities(
        cloudPhotos: List<MediaEntity>,
        skipMd5s: Set<String> = emptySet()
    ): List<MediaEntity> {
        val result = mutableListOf<MediaEntity>()
        for (cloud in cloudPhotos) {
            if (cloud.md5 in skipMd5s) continue
            val existing = findExistingEntity(cloud.md5, cloud.cloudId, null)
            result.add(mergeMedia(existing, cloud = cloud))
        }
        return result
    }

    private suspend fun upsertCloudPhotos(
        cloudPhotos: List<MediaEntity>,
        skipMd5s: Set<String> = emptySet()
    ) {
        val merged = buildCloudEntities(cloudPhotos, skipMd5s)
        if (merged.isNotEmpty()) {
            mediaDao.insertAll(merged)
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
        cloud: MediaEntity? = null
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
            mtime = local?.mtime?.takeIf { it.isNotEmpty() }
                ?: cloud?.mtime?.takeIf { it.isNotEmpty() }
                ?: existing?.mtime
                ?: "",
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
            createdAt = existing?.createdAt ?: base.createdAt,
            updatedAt = System.currentTimeMillis()
        )
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

        val localRecords = mediaDao.getAllMedia().filter { it.localMediaStoreId != null }
        val outOfScope = localRecords.filter { it.localFolderPath !in localFolders }
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

    suspend fun getTimelineMonths(localFolders: Set<String>? = null): List<TimelineMonthCount> {
        return when {
            localFolders == null -> mediaDao.getTimelineMonths()
            localFolders.isEmpty() -> mediaDao.getCloudTimelineMonths()
            else -> mediaDao.getTimelineMonthsByVisibleFolders(localFolders.toList())
        }
    }

    suspend fun getMonthPhotos(
        yearMonth: String,
        localFolders: Set<String>? = null
    ): List<UnifiedPhotoItem> {
        val entities = when {
            localFolders == null -> mediaDao.getMediaByMonth(yearMonth)
            localFolders.isEmpty() -> mediaDao.getCloudMediaByMonth(yearMonth)
            else -> mediaDao.getMediaByMonthVisibleFolders(yearMonth, localFolders.toList())
        }
        return entities.map { it.toUnifiedPhotoItem(localFolders) }
    }

    fun getMonthPhotosFlow(yearMonth: String): Flow<List<UnifiedPhotoItem>> {
        return mediaDao.getMediaByMonthFlow(yearMonth).map { entities ->
            entities.map { it.toUnifiedPhotoItem() }
        }
    }

    fun getAllMediaFlow(): Flow<List<UnifiedPhotoItem>> {
        return mediaDao.getAllMediaFlow().map { entities ->
            entities.map { it.toUnifiedPhotoItem() }
        }
    }

    suspend fun getPendingBackupMedia(localFolders: Set<String>? = null): List<MediaEntity> {
        return when {
            localFolders == null -> mediaDao.getPendingBackupMedia()
            localFolders.isEmpty() -> emptyList()
            else -> mediaDao.getPendingBackupMediaByFolders(localFolders.toList())
        }
    }

    suspend fun getOptimizableMedia(): List<MediaEntity> {
        return mediaDao.getOptimizableMedia()
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

    suspend fun deleteByCloudId(cloudId: Double) {
        mediaDao.deleteByCloudId(cloudId)
    }

    suspend fun deleteByLocalId(localId: Long) {
        mediaDao.deleteByLocalMediaStoreId(localId)
    }

    suspend fun findByMd5(md5: String): MediaEntity? {
        return mediaDao.findByMd5(md5)
    }

    suspend fun findByCloudId(cloudId: Double): MediaEntity? {
        return mediaDao.findByCloudId(cloudId)
    }

    suspend fun updateThumbCachePath(dbId: Long, path: String) {
        mediaDao.updateThumbCachePath(dbId, path)
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

    suspend fun deleteLocalMediaFiles(entities: List<MediaEntity>, useDirectDelete: Boolean = false) {
        if (entities.isEmpty()) return
        val context = container.prefsManager.context

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

        if (urisToDelete.isNotEmpty()) {
            if (useDirectDelete && isManageStorageGranted()) {
                for (uri in urisToDelete) {
                    try {
                        context.contentResolver.delete(uri, null, null)
                    } catch (e: Exception) {
                        Log.w(TAG, "Direct local delete failed: $uri", e)
                    }
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    val pendingIntent = MediaStore.createDeleteRequest(context.contentResolver, urisToDelete)
                    pendingIntent.send()
                } catch (e: Exception) {
                    Log.w(TAG, "createDeleteRequest failed", e)
                }
            } else {
                for (uri in urisToDelete) {
                    try {
                        context.contentResolver.delete(uri, null, null)
                    } catch (e: Exception) {
                        Log.w(TAG, "Local delete failed: $uri", e)
                    }
                }
            }
        }

        for (entity in entities) {
            entity.thumbCachePath?.let { path ->
                try {
                    File(path).delete()
                } catch (_: Exception) {
                }
            }
        }

        mediaDao.deleteByIds(entities.map { it.id })
    }

    private fun isManageStorageGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.os.Environment.isExternalStorageManager()
        } else {
            true
        }
    }

    suspend fun cleanupOrphanedLocalRecords(): Int {
        val refs = mediaDao.getLocalFileRefs()
        if (refs.isEmpty()) return 0

        val trackedIds = refs.map { it.localMediaStoreId }.toSet()
        val existingIds = localScanner.verifyIdsExist(trackedIds)
        val orphanedRefs = refs.filter { it.localMediaStoreId !in existingIds }
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
            mediaDao.clearLocalFields(toClearLocal)
            Log.d(TAG, "Cleared local fields for ${toClearLocal.size} SYNCED records to CLOUD_ONLY")
        }

        MediaChangeObserver.clearDirty()
        return orphanedRefs.size
    }
}

fun MediaEntity.toUnifiedPhotoItem(localFolders: Set<String>? = null): UnifiedPhotoItem {
    val localVisible = localFolders == null || localFolderPath in localFolders
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
        fileSize = fileSize
    )
}
