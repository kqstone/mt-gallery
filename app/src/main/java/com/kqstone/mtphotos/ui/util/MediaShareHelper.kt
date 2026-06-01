package com.kqstone.mtphotos.ui.util

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.kqstone.mtphotos.MTPhotosApp
import com.kqstone.mtphotos.data.model.UnifiedPhotoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

object MediaShareHelper {

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    @kotlin.OptIn(coil.annotation.ExperimentalCoilApi::class)
    suspend fun prepareShareUris(
        context: Context,
        photos: List<UnifiedPhotoItem>,
        getFullImageUrl: (UnifiedPhotoItem) -> String,
        getVideoUrl: (UnifiedPhotoItem) -> String,
        onProgress: (Int, Int, Float?) -> Unit
    ): List<Uri> = withContext(Dispatchers.IO) {
        val uris = mutableListOf<Uri>()
        val sharedMediaDir = File(context.cacheDir, "shared_media")
        if (!sharedMediaDir.exists()) sharedMediaDir.mkdirs()

        val app = context.applicationContext as MTPhotosApp

        for ((index, photo) in photos.withIndex()) {
            onProgress(index, photos.size, 0f)
            
            val uri = if (photo.localUri != null && photo.localUri.isNotEmpty() && !photo.isStorageOptimized) {
                Uri.parse(photo.localUri)
            } else {
                val downloadUrl = if (photo.isVideo()) getVideoUrl(photo) else getFullImageUrl(photo)
                val extension = photo.fileName.substringAfterLast('.', "")
                val actualExt = if (extension.isNotEmpty()) ".$extension" else if (photo.isVideo()) ".mp4" else ".jpg"
                val nameWithoutExt = if (extension.isNotEmpty()) photo.fileName.substringBeforeLast('.') else photo.fileName
                val shortMd5 = if (photo.md5.length >= 8) photo.md5.substring(0, 8) else photo.md5
                val tempFile = File(sharedMediaDir, "${nameWithoutExt}_${shortMd5}_share$actualExt")

                if (!tempFile.exists()) {
                    if (photo.isVideo()) {
                        val videoCache = app.videoCache
                        val upstreamFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
                        val cacheDataSource = androidx.media3.datasource.cache.CacheDataSource.Factory()
                            .setCache(videoCache)
                            .setUpstreamDataSourceFactory(upstreamFactory)
                            .setFlags(androidx.media3.datasource.cache.CacheDataSource.FLAG_BLOCK_ON_CACHE)
                            .createDataSource()

                        val dataSpec = androidx.media3.datasource.DataSpec(Uri.parse(downloadUrl))
                        var length = cacheDataSource.open(dataSpec)
                        if (length <= 0) length = photo.fileSize
                        FileOutputStream(tempFile).use { output ->
                            val buffer = ByteArray(8 * 1024)
                            var read = cacheDataSource.read(buffer, 0, buffer.size)
                            var totalRead = 0L
                            var lastReportTime = 0L
                            while (read != androidx.media3.common.C.RESULT_END_OF_INPUT) {
                                output.write(buffer, 0, read)
                                totalRead += read
                                val now = System.currentTimeMillis()
                                if (length > 0 && now - lastReportTime > 50) {
                                    val ratio = (totalRead.toFloat() / length).coerceIn(0f, 0.99f)
                                    onProgress(index, photos.size, ratio)
                                    lastReportTime = now
                                }
                                read = cacheDataSource.read(buffer, 0, buffer.size)
                            }
                        }
                        cacheDataSource.close()
                    } else {
                        val diskCache = app.fullImageLoader.diskCache
                        val snapshot = diskCache?.openSnapshot(downloadUrl)
                        if (snapshot != null) {
                            snapshot.use {
                                val cachedFile = java.io.File(it.data.toString())
                                cachedFile.copyTo(tempFile, overwrite = true)
                            }
                        } else {
                            val client = OkHttpClient()
                            val request = Request.Builder().url(downloadUrl).build()
                            val response = client.newCall(request).execute()
                            if (!response.isSuccessful) throw Exception("Download failed")
                            var contentLength = response.body?.contentLength() ?: -1L
                            if (contentLength <= 0) contentLength = photo.fileSize
                            response.body?.byteStream()?.use { input ->
                                FileOutputStream(tempFile).use { output ->
                                    val buffer = ByteArray(8 * 1024)
                                    var read = input.read(buffer)
                                    var totalRead = 0L
                                    var lastReportTime = 0L
                                    while (read != -1) {
                                        output.write(buffer, 0, read)
                                        totalRead += read
                                        val now = System.currentTimeMillis()
                                        if (contentLength > 0 && now - lastReportTime > 50) {
                                            val ratio = (totalRead.toFloat() / contentLength).coerceIn(0f, 0.99f)
                                            onProgress(index, photos.size, ratio)
                                            lastReportTime = now
                                        }
                                        read = input.read(buffer)
                                    }
                                }
                            }
                        }
                    }
                }

                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    tempFile
                )
            }
            uris.add(uri)
        }
        return@withContext uris
    }
}
