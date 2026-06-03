package com.kqstone.mtphotos.data.local

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import com.kqstone.mtphotos.data.model.UnifiedPhotoItem
import com.kqstone.mtphotos.data.repository.GalleryRepository
import com.kqstone.mtphotos.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class DownloadState(
    val isDownloading: Boolean = false,
    val progress: Float = 0f,
    val isCompleted: Boolean = false,
    val localUri: String? = null,
    val error: String? = null
)

class OriginalDownloadManager(
    private val context: Context,
    private val galleryRepository: GalleryRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val _downloadStates = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val downloadStates: StateFlow<Map<String, DownloadState>> = _downloadStates

    fun startDownload(photo: UnifiedPhotoItem, fileDetailInfo: Map<String, Any>?) {
        val md5 = photo.md5
        if (_downloadStates.value[md5]?.isDownloading == true) return

        _downloadStates.update { it + (md5 to DownloadState(isDownloading = true)) }

        scope.launch {
            try {
                val cloudId = photo.cloudId ?: throw Exception("Invalid cloudId")
                val downloadUrl = galleryRepository.getFileDownloadUrl(cloudId, md5)
                if (downloadUrl.isEmpty()) throw Exception(context.getString(R.string.download_err_get_url_failed))

                val request = Request.Builder().url(downloadUrl).build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) throw Exception(context.getString(R.string.download_err_http_failed, response.code))
                
                val body = response.body ?: throw Exception(context.getString(R.string.download_err_empty_response))
                val totalBytes = body.contentLength()

                val resolver = context.contentResolver
                val mimeType = when {
                    photo.fileName.endsWith(".png", true) -> "image/png"
                    photo.fileName.endsWith(".webp", true) -> "image/webp"
                    photo.fileName.endsWith(".gif", true) -> "image/gif"
                    photo.fileName.endsWith(".mp4", true) -> "video/mp4"
                    photo.fileName.endsWith(".mov", true) -> "video/quicktime"
                    photo.fileName.endsWith(".mkv", true) -> "video/x-matroska"
                    photo.fileName.endsWith(".avi", true) -> "video/x-msvideo"
                    photo.isVideo() -> "video/mp4"
                    else -> "image/jpeg"
                }

                val fileDetailMtime = fileDetailInfo?.get("mtime")
                val dateTakenMillis = try {
                    if (fileDetailMtime is Number) {
                        val mtimeMs = fileDetailMtime.toLong()
                        if (mtimeMs > 9999999999L) mtimeMs else mtimeMs * 1000L
                    } else {
                        val rawMtime = (fileDetailMtime as? String)?.takeIf { it.isNotBlank() } ?: photo.mtime
                        val clean = rawMtime.replace("T", " ")
                            .substringBefore("+").substringBefore("Z")
                        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                            .parse(clean)?.time
                    }
                } catch (_: Exception) { null }

                val idStr = photo.id.toLong().toString()
                val extRaw = (fileDetailInfo?.get("fileType") as? String)
                    ?.takeIf { it.isNotBlank() }
                    ?: photo.fileType.takeIf { it.isNotBlank() }
                    ?: photo.fileName.substringAfterLast('.', "jpg")
                val ext = extRaw.trim().lowercase().removePrefix(".")
                val newFileName = "${photo.md5}.$ext"

                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, newFileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${MediaConstants.MT_GALLERY_DOWNLOAD_FOLDER_RELATIVE}/$idStr")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                    if (dateTakenMillis != null) {
                        put(MediaStore.MediaColumns.DATE_TAKEN, dateTakenMillis)
                        put(MediaStore.MediaColumns.DATE_MODIFIED, dateTakenMillis / 1000)
                        put(MediaStore.MediaColumns.DATE_ADDED, dateTakenMillis / 1000)
                    }
                }
                
                val collectionUri = if (photo.isVideo()) {
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                } else {
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                }
                val uri = resolver.insert(collectionUri, contentValues)
                    ?: throw Exception(context.getString(R.string.download_err_mediastore_failed))

                resolver.openOutputStream(uri)?.use { outputStream ->
                    body.byteStream().use { inputStream ->
                        val buffer = ByteArray(8 * 1024)
                        var bytesCopied: Long = 0
                        var lastProgressUpdate = 0L
                        while (true) {
                            val bytes = inputStream.read(buffer)
                            if (bytes < 0) break
                            outputStream.write(buffer, 0, bytes)
                            bytesCopied += bytes
                            
                            val currentTime = System.currentTimeMillis()
                            if (totalBytes > 0 && currentTime - lastProgressUpdate > 100) {
                                lastProgressUpdate = currentTime
                                val progress = bytesCopied.toFloat() / totalBytes
                                _downloadStates.update { it + (md5 to DownloadState(isDownloading = true, progress = progress)) }
                            }
                        }
                    }
                } ?: throw Exception(context.getString(R.string.download_err_write_failed))

                val filePath = resolver.query(
                    uri,
                    arrayOf(MediaStore.MediaColumns.DATA),
                    null, null, null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
                
                if (filePath != null && dateTakenMillis != null) {
                    try {
                        val file = java.io.File(filePath)
                        if (file.exists()) {
                            file.setLastModified(dateTakenMillis)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                val updateValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                    if (dateTakenMillis != null) {
                        put(MediaStore.MediaColumns.DATE_TAKEN, dateTakenMillis)
                        put(MediaStore.MediaColumns.DATE_MODIFIED, dateTakenMillis / 1000)
                        put(MediaStore.MediaColumns.DATE_ADDED, dateTakenMillis / 1000)
                    }
                }
                resolver.update(uri, updateValues, null, null)

                galleryRepository.markOriginalDownloaded(md5, uri.toString(), filePath ?: "")
                _downloadStates.update { it + (md5 to DownloadState(isDownloading = false, isCompleted = true, progress = 1f, localUri = uri.toString())) }
                
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(context, context.getString(R.string.download_completed), android.widget.Toast.LENGTH_SHORT).show()
                }

            } catch (e: Exception) {
                _downloadStates.update { it + (md5 to DownloadState(isDownloading = false, error = e.message)) }
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(context, context.getString(R.string.download_failed_format, e.message.orEmpty()), android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
