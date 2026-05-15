package com.kqstone.mtphotos.data.local

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.kqstone.mtphotos.data.local.db.MediaEntity
import com.kqstone.mtphotos.data.repository.SyncRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "StorageOptimizer"

/**
 * 存储空间优化器。
 * 负责删除已备份的本地原图/视频，仅保留缩略图缓存。
 * 这是一个高风险操作，需要用户手动触发并二次确认。
 */
class StorageOptimizer(
    private val context: Context,
    private val syncRepository: SyncRepository
) {

    data class OptimizationStats(
        val totalFiles: Int,
        val totalSize: Long,
        val files: List<MediaEntity>
    ) {
        val totalSizeMB: Double get() = totalSize / (1024.0 * 1024.0)
        val totalSizeGB: Double get() = totalSize / (1024.0 * 1024.0 * 1024.0)

        fun formattedSize(): String {
            return if (totalSizeGB >= 1.0) {
                "%.2f GB".format(totalSizeGB)
            } else {
                "%.1f MB".format(totalSizeMB)
            }
        }
    }

    /**
     * 获取可优化的文件统计（已备份但本地原图未清理的文件）
     */
    suspend fun getOptimizationStats(): OptimizationStats {
        val files = syncRepository.getOptimizableMedia()
        val totalSize = files.sumOf { it.fileSize }
        return OptimizationStats(
            totalFiles = files.size,
            totalSize = totalSize,
            files = files
        )
    }

    /**
     * 执行存储优化：删除已备份的本地原图/视频。
     * ⚠️ 高风险操作，调用前务必已确认备份完整性。
     *
     * @param filesToOptimize 要优化的文件列表
     * @return Pair<成功数, 失败数>
     */
    suspend fun optimizeStorage(filesToOptimize: List<MediaEntity>): Pair<Int, Int> = withContext(Dispatchers.IO) {
        var successCount = 0
        var failCount = 0

        for (media in filesToOptimize) {
            try {
                val deleted = deleteLocalMedia(media)
                if (deleted) {
                    syncRepository.markAsStorageOptimized(media.id)
                    successCount++
                    Log.d(TAG, "Optimized: ${media.fileName}")
                } else {
                    failCount++
                    Log.w(TAG, "Failed to delete local: ${media.fileName}")
                }
            } catch (e: Exception) {
                failCount++
                Log.e(TAG, "Optimization error: ${media.fileName}", e)
            }
        }

        Log.d(TAG, "Storage optimization: $successCount success, $failCount failed")
        Pair(successCount, failCount)
    }

    /**
     * 从 MediaStore 中删除本地文件
     */
    private fun deleteLocalMedia(media: MediaEntity): Boolean {
        val localId = media.localMediaStoreId ?: return false

        return try {
            val contentUri = if (media.fileType.startsWith("video")) {
                ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, localId)
            } else {
                ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, localId)
            }

            val deleted = context.contentResolver.delete(contentUri, null, null)
            deleted > 0
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException deleting $localId, may need user consent", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting $localId", e)
            false
        }
    }
}
