package com.kqstone.mtphotos.data.local

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.util.Log
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

private const val TAG = "LocalVideoThumbGen"
private const val LOCAL_VIDEO_THUMB_SIZE = 256

class LocalVideoThumbnailGenerator(
    private val context: Context,
    private val thumbnailCacheManager: ThumbnailCacheManager
) {
    suspend fun generate(uriString: String, cacheKey: String): String? = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@withContext null

        try {
            val cachedPath = thumbnailCacheManager.getCachedThumbPath(cacheKey)
            if (cachedPath != null) return@withContext cachedPath

            val thumbnail = context.contentResolver.loadThumbnail(
                Uri.parse(uriString),
                Size(LOCAL_VIDEO_THUMB_SIZE, LOCAL_VIDEO_THUMB_SIZE),
                null
            )
            try {
                thumbnailCacheManager.saveThumbToCache(cacheKey, thumbnail.toWebpBytes())
            } finally {
                thumbnail.recycle()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to generate local video thumbnail: $uriString", e)
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun Bitmap.toWebpBytes(): ByteArray {
        return ByteArrayOutputStream().use { stream ->
            compress(Bitmap.CompressFormat.WEBP, 85, stream)
            stream.toByteArray()
        }
    }
}
