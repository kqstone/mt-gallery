package com.kqstone.mtphotos.data.local

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "ThumbCacheManager"
private const val THUMB_DIR = "thumbs"
private const val DEFAULT_MAX_CACHE_SIZE = 500L * 1024 * 1024 // 500MB

/**
 * 缩略图本地缓存管理器。
 * 将云端缩略图下载缓存到 App 内部存储，加速后续加载。
 * 采用 LRU 策略管理缓存大小。
 */
class ThumbnailCacheManager(private val context: Context) {

    private val cacheDir: File by lazy {
        File(context.cacheDir, THUMB_DIR).also { it.mkdirs() }
    }

    /**
     * 获取缩略图的本地缓存路径。
     * @return 本地路径（如果已缓存），否则 null
     */
    fun getCachedThumbPath(md5: String): String? {
        val file = getThumbFile(md5)
        return if (file.exists() && file.length() > 0) {
            // 更新文件最后修改时间（LRU 标记）
            file.setLastModified(System.currentTimeMillis())
            file.absolutePath
        } else {
            null
        }
    }

    /**
     * 保存缩略图到本地缓存
     */
    suspend fun saveThumbToCache(md5: String, data: ByteArray): String? = withContext(Dispatchers.IO) {
        try {
            val file = getThumbFile(md5)
            file.writeBytes(data)
            file.absolutePath
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cache thumb $md5", e)
            null
        }
    }

    /**
     * 获取缓存目录总大小
     */
    fun getCacheSize(): Long {
        return cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    /**
     * 清理缓存到指定大小限制（LRU 淘汰）
     */
    suspend fun trimToSize(maxSize: Long = DEFAULT_MAX_CACHE_SIZE) = withContext(Dispatchers.IO) {
        val files = cacheDir.listFiles()?.toList() ?: return@withContext
        val totalSize = files.sumOf { it.length() }
        if (totalSize <= maxSize) return@withContext

        // 按最后修改时间排序（最旧的先删）
        val sorted = files.sortedBy { it.lastModified() }
        var currentSize = totalSize

        for (file in sorted) {
            if (currentSize <= maxSize * 0.8) break // 清理到 80% 以下
            val fileSize = file.length()
            if (file.delete()) {
                currentSize -= fileSize
                Log.d(TAG, "Evicted cache: ${file.name}")
            }
        }
        Log.d(TAG, "Cache trimmed: $totalSize -> $currentSize bytes")
    }

    /**
     * 清除全部缓存
     */
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        cacheDir.listFiles()?.forEach { it.delete() }
        Log.d(TAG, "All thumbnail cache cleared")
    }

    private fun getThumbFile(md5: String): File {
        return File(cacheDir, "$md5.webp")
    }
}
