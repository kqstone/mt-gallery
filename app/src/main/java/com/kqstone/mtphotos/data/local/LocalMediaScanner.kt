package com.kqstone.mtphotos.data.local

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.kqstone.mtphotos.data.local.db.BackupStatus
import com.kqstone.mtphotos.data.local.db.MediaEntity
import com.kqstone.mtphotos.data.local.db.SyncStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "LocalMediaScanner"

/**
 * 本地媒体扫描器。
 * 使用 MediaStore API 读取设备上的照片和视频文件。
 */
class LocalMediaScanner(private val context: Context) {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())

    /**
     * 扫描设备上指定文件夹（或全部）中的照片和视频。
     * @param folderPaths 要扫描的文件夹路径列表，null 则扫描全部
     * @param computeMd5 是否计算 MD5（耗时操作，初次扫描建议 true，后续增量可 false）
     */
    suspend fun scanMedia(
        folderPaths: Set<String>? = null,
        computeMd5: Boolean = true
    ): List<MediaEntity> = withContext(Dispatchers.IO) {
        val results = mutableListOf<MediaEntity>()
        results.addAll(scanImages(folderPaths, computeMd5))
        results.addAll(scanVideos(folderPaths, computeMd5))
        Log.d(TAG, "Scanned ${results.size} media files (${results.count { it.fileType.startsWith("image") }} images, ${results.count { it.fileType.startsWith("video") }} videos)")
        results
    }

    /**
     * 获取设备上所有包含媒体的文件夹路径列表（用于设置页面让用户选择备份范围）
     */
    suspend fun getMediaFolders(): List<MediaFolder> = withContext(Dispatchers.IO) {
        val folderMap = mutableMapOf<String, Int>()

        // 扫描图片文件夹
        scanFolderPaths(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, folderMap)
        // 扫描视频文件夹
        scanFolderPaths(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, folderMap)

        folderMap.map { (path, count) ->
            val name = File(path).name
            MediaFolder(path = path, displayName = name, fileCount = count)
        }.sortedByDescending { it.fileCount }
    }

    private fun scanFolderPaths(
        contentUri: android.net.Uri,
        folderMap: MutableMap<String, Int>
    ) {
        val projection = arrayOf(MediaStore.MediaColumns.DATA)
        context.contentResolver.query(
            contentUri, projection, null, null, null
        )?.use { cursor ->
            val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
            while (cursor.moveToNext()) {
                val path = cursor.getString(dataColumn) ?: continue
                val folder = File(path).parent ?: continue
                folderMap[folder] = (folderMap[folder] ?: 0) + 1
            }
        }
    }

    private suspend fun scanImages(
        folderPaths: Set<String>?,
        computeMd5: Boolean
    ): List<MediaEntity> = withContext(Dispatchers.IO) {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

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
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

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
        val results = mutableListOf<MediaEntity>()

        // 构建选择条件（按文件夹过滤）
        var selection: String? = null
        var selectionArgs: Array<String>? = null
        if (folderPaths != null && folderPaths.isNotEmpty()) {
            val placeholders = folderPaths.joinToString(",") { "?" }
            // 使用 DATA 列的父目录匹配
            val conditions = folderPaths.map { "${MediaStore.MediaColumns.DATA} LIKE ?" }
            selection = conditions.joinToString(" OR ")
            selectionArgs = folderPaths.map { "$it/%" }.toTypedArray()
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
                    val mediaStoreId = cursor.getLong(idColumn)
                    val name = cursor.getString(nameColumn) ?: "unknown"
                    val mimeType = cursor.getString(mimeColumn) ?: if (isVideo) "video/mp4" else "image/jpeg"
                    val dateModified = cursor.getLong(dateModifiedColumn) * 1000 // seconds to millis
                    val dateTaken = if (dateTakenColumn >= 0) cursor.getLong(dateTakenColumn) else 0L
                    val width = cursor.getInt(widthColumn)
                    val height = cursor.getInt(heightColumn)
                    val size = cursor.getLong(sizeColumn)
                    val data = cursor.getString(dataColumn) ?: ""
                    val duration = if (durationColumn >= 0) cursor.getLong(durationColumn) else 0L

                    // 使用拍摄时间优先，否则使用修改时间
                    val timestamp = if (dateTaken > 0) dateTaken else dateModified
                    val mtime = dateFormat.format(Date(timestamp))

                    // 计算 content URI
                    val contentUri = ContentUris.withAppendedId(
                        if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                        else MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        mediaStoreId
                    )

                    // 计算文件夹路径
                    val folderPath = File(data).parent

                    // 计算 MD5
                    val md5 = if (computeMd5 && data.isNotEmpty()) {
                        computeFileMd5(data)
                    } else {
                        ""
                    }

                    results.add(
                        MediaEntity(
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
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to process media item", e)
                }
            }
        }

        return results
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
}

/**
 * 媒体文件夹信息（用于设置页面的文件夹选择）
 */
data class MediaFolder(
    val path: String,
    val displayName: String,
    val fileCount: Int,
    val isSelected: Boolean = true // 默认选中
)
