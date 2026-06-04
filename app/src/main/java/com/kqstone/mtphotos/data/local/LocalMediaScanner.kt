package com.kqstone.mtphotos.data.local

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.kqstone.mtphotos.data.local.db.BackupStatus
import com.kqstone.mtphotos.data.local.db.MediaEntity
import com.kqstone.mtphotos.data.local.db.SyncStatus
import com.kqstone.mtphotos.data.model.MediaTimeParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.Locale

private const val TAG = "LocalMediaScanner"

/**
 * 本地媒体扫描器。
 * 使用 MediaStore API 读取设备上的照片和视频文件。
 */
class LocalMediaScanner(private val context: Context) {

    /**
     * 扫描设备上指定文件夹（或全部）中的照片和视频。
     * @param folderPaths 要扫描的文件夹路径列表，null 则扫描全部
     * @param computeMd5 是否计算 MD5（耗时操作，初次扫描建议 true，后续增量可 false）
     */
    suspend fun scanMedia(
        folderPaths: Set<String>? = null,
        computeMd5: Boolean = true
    ): List<MediaEntity> = withContext(Dispatchers.IO) {
        if (folderPaths != null && folderPaths.isEmpty()) {
            Log.d(TAG, "Folder filter is empty, returning no media")
            return@withContext emptyList()
        }
        val results = mutableListOf<MediaEntity>()
        results.addAll(scanImages(folderPaths, computeMd5))
        results.addAll(scanVideos(folderPaths, computeMd5))
        Log.d(TAG, "Scanned ${results.size} media files (${results.count { it.fileType.startsWith("image") }} images, ${results.count { it.fileType.startsWith("video") }} videos)")
        results
    }

    /**
     * 流式扫描：每 batchSize 条记录产出一个批次。
     * 按时间倒序扫描，最新的照片最先产出。
     * 默认不计算 MD5 以加快速度。
     */
    fun scanMediaFlow(
        folderPaths: Set<String>? = null,
        batchSize: Int = 50,
        computeMd5: Boolean = false
    ): Flow<List<MediaEntity>> = flow {
        if (folderPaths != null && folderPaths.isEmpty()) {
            Log.d(TAG, "Folder filter is empty, skipping flow scan")
            return@flow
        }
        // 先扫描图片
        val imageBatch = mutableListOf<MediaEntity>()
        queryMediaFlow(
            collection = imageCollectionUri(),
            projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.MIME_TYPE,
                MediaStore.Images.Media.DATE_MODIFIED,
                MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.WIDTH,
                MediaStore.Images.Media.HEIGHT,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.DATA
            ),
            folderPaths = folderPaths,
            isVideo = false,
            computeMd5 = computeMd5
        ) { entity ->
            imageBatch.add(entity)
            if (imageBatch.size >= batchSize) {
                emit(imageBatch.toList())
                imageBatch.clear()
            }
        }
        if (imageBatch.isNotEmpty()) {
            emit(imageBatch.toList())
            imageBatch.clear()
        }

        // 再扫描视频
        val videoBatch = mutableListOf<MediaEntity>()
        queryMediaFlow(
            collection = videoCollectionUri(),
            projection = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.MIME_TYPE,
                MediaStore.Video.Media.DATE_MODIFIED,
                MediaStore.Video.Media.DATE_TAKEN,
                MediaStore.Video.Media.WIDTH,
                MediaStore.Video.Media.HEIGHT,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.DATA,
                MediaStore.Video.Media.DURATION
            ),
            folderPaths = folderPaths,
            isVideo = true,
            computeMd5 = computeMd5
        ) { entity ->
            videoBatch.add(entity)
            if (videoBatch.size >= batchSize) {
                emit(videoBatch.toList())
                videoBatch.clear()
            }
        }
        if (videoBatch.isNotEmpty()) {
            emit(videoBatch.toList())
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 逐行遍历 cursor，每条记录通过 onItem 回调产出。
     */
    @Suppress("DEPRECATION")
    private suspend fun queryMediaFlow(
        collection: android.net.Uri,
        projection: Array<String>,
        folderPaths: Set<String>?,
        isVideo: Boolean,
        computeMd5: Boolean,
        onItem: suspend (MediaEntity) -> Unit
    ) {
        if (folderPaths != null && folderPaths.isEmpty()) return
        var selection: String? = null
        var selectionArgs: Array<String>? = null
        if (folderPaths != null && folderPaths.isNotEmpty()) {
            val conditions = folderPaths.map { "${MediaStore.MediaColumns.DATA} LIKE ?" }
            selection = conditions.joinToString(" OR ")
            selectionArgs = folderPaths.map { "$it/%" }.toTypedArray()
        }
        // 过滤正在写入和已标记删除的文件
        val pendingFilter = visibleMediaSelection()
        if (pendingFilter != null) {
            selection = if (selection != null) "($selection) AND $pendingFilter" else pendingFilter
        }

        val sortOrder = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"

        context.contentResolver.query(
            collection, projection, selection, selectionArgs, sortOrder
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val dateModifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val dateTakenColumn = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_TAKEN)
            val widthColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
            val heightColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
            val durationColumn = if (isVideo) cursor.getColumnIndex(MediaStore.Video.Media.DURATION) else -1

            while (cursor.moveToNext()) {
                try {
                    val entity = parseCursorRow(
                        cursor, idColumn, nameColumn, mimeColumn,
                        dateModifiedColumn, dateTakenColumn, widthColumn, heightColumn,
                        sizeColumn, dataColumn, durationColumn, isVideo, computeMd5
                    )
                    if (entity != null) onItem(entity)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to process media item", e)
                }
            }
        }
    }

    /**
     * 获取设备上所有包含媒体的文件夹路径列表（用于设置页面让用户选择备份范围）
     */
    suspend fun getMediaFolders(): List<MediaFolder> = withContext(Dispatchers.IO) {
        val folderMap = mutableMapOf<String, Int>()

        // 扫描图片文件夹
        scanFolderPaths(imageCollectionUri(), folderMap)
        // 扫描视频文件夹
        scanFolderPaths(videoCollectionUri(), folderMap)

        buildFolderTree(folderMap)
    }

    private fun scanFolderPaths(
        contentUri: android.net.Uri,
        folderMap: MutableMap<String, Int>
    ) {
        val projection = arrayOf(MediaStore.MediaColumns.DATA)
        val selection = visibleMediaSelection()
        context.contentResolver.query(
            contentUri, projection, selection, null, null
        )?.use { cursor ->
            val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
            while (cursor.moveToNext()) {
                val path = cursor.getString(dataColumn) ?: continue
                val folder = File(path).parent ?: continue
                folderMap[folder] = (folderMap[folder] ?: 0) + 1
            }
        }
    }

    private fun buildFolderTree(directFolderCounts: Map<String, Int>): List<MediaFolder> {
        if (directFolderCounts.isEmpty()) return emptyList()

        val externalRoot = android.os.Environment.getExternalStorageDirectory().absolutePath
        val storageRoots = mutableSetOf(FolderPathMatcher.normalize(externalRoot))
        directFolderCounts.keys.forEach { path ->
            inferStorageRoot(path)?.let { storageRoots.add(it) }
        }

        val childrenByParent = mutableMapOf<String, MutableSet<String>>()
        val directCounts = directFolderCounts.mapKeys { FolderPathMatcher.normalize(it.key) }
        val allPaths = linkedSetOf<String>()

        directCounts.keys.forEach { folder ->
            val root = storageRoots
                .filter { FolderPathMatcher.contains(it, folder) }
                .maxByOrNull { it.length }
            var current = folder
            while (current.isNotEmpty() && current !in storageRoots) {
                allPaths.add(current)
                val parent = File(current).parent?.let(FolderPathMatcher::normalize).orEmpty()
                if (parent.isEmpty() || parent == current) break
                childrenByParent.getOrPut(parent) { linkedSetOf() }.add(current)
                if (root != null && parent == root) break
                current = parent
            }
        }

        fun totalCount(path: String): Int {
            val own = directCounts[path] ?: 0
            val children = childrenByParent[path].orEmpty().sumOf { totalCount(it) }
            return own + children
        }

        fun depth(path: String): Int {
            val root = storageRoots
                .filter { FolderPathMatcher.contains(it, path) }
                .maxByOrNull { it.length }
            val relative = root?.let {
                FolderPathMatcher.normalize(path).removePrefix(it).trim('/')
            } ?: FolderPathMatcher.normalize(path)
            return relative.split('/').count { it.isNotEmpty() } - 1
        }

        val orderedPaths = mutableListOf<String>()
        fun appendTree(path: String) {
            orderedPaths.add(path)
            childrenByParent[path]
                .orEmpty()
                .filter { it in allPaths }
                .sortedWith(compareByDescending<String> { totalCount(it) }.thenBy { it.lowercase(Locale.getDefault()) })
                .forEach { appendTree(it) }
        }
        allPaths
            .filter { path ->
                val parent = File(path).parent?.let(FolderPathMatcher::normalize)
                parent == null || parent !in allPaths
            }
            .sortedWith(compareByDescending<String> { totalCount(it) }.thenBy { it.lowercase(Locale.getDefault()) })
            .forEach { appendTree(it) }

        return orderedPaths
            .map { path ->
                MediaFolder(
                    path = path,
                    displayName = File(path).name.ifBlank { path },
                    fileCount = totalCount(path),
                    directFileCount = directCounts[path] ?: 0,
                    depth = depth(path).coerceAtLeast(0),
                    hasDirectMedia = directCounts.containsKey(path)
                )
            }
    }

    private fun inferStorageRoot(path: String): String? {
        val parts = FolderPathMatcher.normalize(path)
            .split('/')
            .filter { it.isNotEmpty() }
        if (parts.size < 3 || parts.first() != "storage") return null
        return "/" + parts.take(3).joinToString("/")
    }

    private suspend fun scanImages(
        folderPaths: Set<String>?,
        computeMd5: Boolean
    ): List<MediaEntity> = withContext(Dispatchers.IO) {
        val collection = imageCollectionUri()

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATA
        )

        queryMedia(collection, projection, folderPaths, computeMd5, isVideo = false)
    }

    private suspend fun scanVideos(
        folderPaths: Set<String>?,
        computeMd5: Boolean
    ): List<MediaEntity> = withContext(Dispatchers.IO) {
        val collection = videoCollectionUri()

        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.DATE_TAKEN,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.DURATION
        )

        queryMedia(collection, projection, folderPaths, computeMd5, isVideo = true)
    }

    @Suppress("DEPRECATION")
    private fun queryMedia(
        collection: android.net.Uri,
        projection: Array<String>,
        folderPaths: Set<String>?,
        computeMd5: Boolean,
        isVideo: Boolean
    ): List<MediaEntity> {
        if (folderPaths != null && folderPaths.isEmpty()) return emptyList()
        val results = mutableListOf<MediaEntity>()

        // 构建选择条件（按文件夹过滤）
        var selection: String? = null
        var selectionArgs: Array<String>? = null
        if (folderPaths != null && folderPaths.isNotEmpty()) {
            val conditions = folderPaths.map { "${MediaStore.MediaColumns.DATA} LIKE ?" }
            selection = conditions.joinToString(" OR ")
            selectionArgs = folderPaths.map { "$it/%" }.toTypedArray()
        }
        // 过滤正在写入和已标记删除的文件
        val pendingFilter = visibleMediaSelection()
        if (pendingFilter != null) {
            selection = if (selection != null) "($selection) AND $pendingFilter" else pendingFilter
        }

        val sortOrder = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"

        context.contentResolver.query(
            collection, projection, selection, selectionArgs, sortOrder
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val dateModifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val dateTakenColumn = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_TAKEN)
            val widthColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
            val heightColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
            val durationColumn = if (isVideo) cursor.getColumnIndex(MediaStore.Video.Media.DURATION) else -1

            while (cursor.moveToNext()) {
                try {
                    val entity = parseCursorRow(
                        cursor, idColumn, nameColumn, mimeColumn,
                        dateModifiedColumn, dateTakenColumn, widthColumn, heightColumn,
                        sizeColumn, dataColumn, durationColumn, isVideo, computeMd5
                    )
                    if (entity != null) results.add(entity)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to process media item", e)
                }
            }
        }

        return results
    }

    /**
     * 从 cursor 当前行解析出 MediaEntity
     */
    private fun parseCursorRow(
        cursor: android.database.Cursor,
        idColumn: Int, nameColumn: Int, mimeColumn: Int,
        dateModifiedColumn: Int, dateTakenColumn: Int,
        widthColumn: Int, heightColumn: Int,
        sizeColumn: Int, dataColumn: Int, durationColumn: Int,
        isVideo: Boolean, computeMd5: Boolean
    ): MediaEntity? {
        val mediaStoreId = cursor.getLong(idColumn)
        val name = cursor.getString(nameColumn) ?: "unknown"
        val mimeType = cursor.getString(mimeColumn) ?: if (isVideo) "video/mp4" else "image/jpeg"
        val dateModified = cursor.getLong(dateModifiedColumn) * 1000
        val dateTaken = if (dateTakenColumn >= 0) cursor.getLong(dateTakenColumn) else 0L
        val width = cursor.getInt(widthColumn)
        val height = cursor.getInt(heightColumn)
        val size = cursor.getLong(sizeColumn)
        val data = cursor.getString(dataColumn) ?: ""
        val duration = if (durationColumn >= 0) cursor.getLong(durationColumn) else 0L

        val timestamp = if (dateTaken > 0) dateTaken else dateModified
        val mtime = MediaTimeParser.formatTimelineMillis(timestamp)

        val contentUri = ContentUris.withAppendedId(
            if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            mediaStoreId
        )

        val folderPath = File(data).parent
        val md5 = if (computeMd5 && data.isNotEmpty()) computeFileMd5(data) else ""

        return MediaEntity(
            localMediaStoreId = mediaStoreId,
            md5 = md5,
            fileName = name,
            fileType = mimeType,
            mtime = mtime,
            width = width,
            height = height,
            localUri = contentUri.toString(),
            localPath = data,
            syncStatus = SyncStatus.LOCAL_ONLY,
            backupStatus = BackupStatus.NOT_STARTED,
            fileSize = size,
            duration = duration,
            localFolderPath = folderPath
        )
    }

    private fun imageCollectionUri(): android.net.Uri {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
    }

    private fun videoCollectionUri(): android.net.Uri {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
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

    companion object {
        /**
         * 计算文件的 MD5 哈希值
         */
        fun computeFileMd5(filePath: String): String {
            return try {
                val file = File(filePath)
                if (!file.exists() || !file.canRead()) return ""
                val digest = MessageDigest.getInstance("MD5")
                FileInputStream(file).use { fis ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (fis.read(buffer).also { bytesRead = it } != -1) {
                        digest.update(buffer, 0, bytesRead)
                    }
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to compute MD5 for $filePath", e)
                ""
            }
        }
    }

    /**
     * 批量验证 MediaStore ID 是否仍存在。
     * 返回仍存在的 ID 集合。分批 950 个查询避免 SQLite 参数限制。
     */
    suspend fun verifyIdsExist(localIds: Set<Long>): Set<Long> = withContext(Dispatchers.IO) {
        if (localIds.isEmpty()) return@withContext emptySet()
        val existing = mutableSetOf<Long>()

        localIds.chunked(950).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            for (collection in listOf(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            )) {
                context.contentResolver.query(
                    collection,
                    arrayOf(MediaStore.MediaColumns._ID),
                    "${MediaStore.MediaColumns._ID} IN ($placeholders)",
                    chunk.map { it.toString() }.toTypedArray(),
                    null
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    while (cursor.moveToNext()) {
                        existing.add(cursor.getLong(idCol))
                    }
                }
            }
        }
        existing
    }
}

/**
 * 媒体文件夹信息（用于设置页面的文件夹选择）
 */
data class MediaFolder(
    val path: String,
    val displayName: String,
    val fileCount: Int,
    val directFileCount: Int = fileCount,
    val depth: Int = 0,
    val hasDirectMedia: Boolean = true,
    val isSelected: Boolean = true
)
