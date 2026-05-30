package com.kqstone.mtphotos.ui.viewer

import android.content.ContentValues
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kqstone.mtphotos.data.local.db.SyncStatus
import com.kqstone.mtphotos.data.model.UnifiedPhotoItem
import com.kqstone.mtphotos.data.repository.GalleryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

data class ViewerUiState(
    val photos: List<UnifiedPhotoItem> = emptyList(),
    val currentIndex: Int = 0,
    val isFavorite: Boolean = false,
    val isLoadingDetails: Boolean = false,
    val exifInfo: Map<String, Any>? = null,
    val fileDetailInfo: Map<String, Any>? = null,
    val isSharing: Boolean = false,
    val isDownloadingOriginal: Boolean = false,
    val originalDownloaded: Boolean = false,
    val resolvedVideoUrl: String? = null,
    val isPlayingTranscode: Boolean = false
)

class ViewerViewModel(private val galleryRepository: GalleryRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(ViewerUiState())
    val uiState: StateFlow<ViewerUiState> = _uiState

    fun setPhotos(photos: List<UnifiedPhotoItem>, initialIndex: Int) {
        val index = initialIndex.coerceIn(0, (photos.size - 1).coerceAtLeast(0))
        _uiState.value = ViewerUiState(
            photos = photos,
            currentIndex = index
        )
        loadExifAndFavoriteForCurrent()
    }

    fun updateCurrentIndex(index: Int) {
        _uiState.update {
            it.copy(
                currentIndex = index,
                isFavorite = false,
                exifInfo = null,
                fileDetailInfo = null,
                originalDownloaded = false,
                resolvedVideoUrl = null,
                isPlayingTranscode = false
            )
        }
        loadExifAndFavoriteForCurrent()
    }

    fun loadExifAndFavoriteForCurrent() {
        val photo = getCurrentPhoto() ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingDetails = true, exifInfo = null, fileDetailInfo = null) }
            
            // 并行/顺序查询收藏状态和 EXIF/详情
            val favResult = galleryRepository.isFileInFavorites(photo.id)
            val isFav = favResult.getOrDefault(false)
            
            val exifResult = galleryRepository.getFileExifInfo(photo.id)
            val detailResult = galleryRepository.getFileDetail(photo.id, photo.md5)
            
            _uiState.update {
                it.copy(
                    isLoadingDetails = false,
                    isFavorite = isFav,
                    exifInfo = exifResult.getOrNull(),
                    fileDetailInfo = detailResult.getOrNull()
                )
            }
            if (photo.isPlayableMedia()) {
                resolveVideoUrl(photo)
            }
        }
    }

    private suspend fun resolveVideoUrl(photo: UnifiedPhotoItem) {
        if (!photo.isMotionPhoto() && photo.localUri?.isNotEmpty() == true && !photo.isStorageOptimized) {
            _uiState.update { it.copy(resolvedVideoUrl = photo.localUri, isPlayingTranscode = false) }
            return
        }
        val cloudId = photo.cloudId
        if (cloudId == null) {
            _uiState.update { it.copy(resolvedVideoUrl = "", isPlayingTranscode = false) }
            return
        }
        if (photo.isMotionPhoto()) {
            val url = galleryRepository.getMotionPhotoUrl(cloudId, photo.md5)
            _uiState.update { it.copy(resolvedVideoUrl = url, isPlayingTranscode = false) }
            return
        }
        
        withContext(Dispatchers.IO) {
            val transcodeUrl = galleryRepository.getTranscodeVideoUrl(cloudId, photo.md5)
            val request = Request.Builder().url(transcodeUrl).head().build()
            try {
                val client = OkHttpClient()
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    _uiState.update { it.copy(resolvedVideoUrl = transcodeUrl, isPlayingTranscode = true) }
                    return@withContext
                }
            } catch (e: Exception) {}
            
            val originalUrl = galleryRepository.getFullImageUrl(cloudId, photo.md5)
            _uiState.update { it.copy(resolvedVideoUrl = originalUrl, isPlayingTranscode = false) }
        }
    }

    fun toggleFavorite() {
        val photo = getCurrentPhoto() ?: return
        val currentFav = _uiState.value.isFavorite
        viewModelScope.launch {
            val result = galleryRepository.toggleFavorite(photo.id, !currentFav)
            if (result.isSuccess) {
                _uiState.update { it.copy(isFavorite = !currentFav) }
            }
        }
    }

    fun deleteCurrentPhoto(onSuccess: () -> Unit) {
        val photo = getCurrentPhoto() ?: return
        viewModelScope.launch {
            val result = galleryRepository.deleteFiles(listOf(photo.id))
            if (result.isSuccess) {
                onSuccess()
            }
        }
    }

    fun sharePhoto(context: android.content.Context, photo: UnifiedPhotoItem) {
        if (_uiState.value.isSharing) return
        _uiState.update { it.copy(isSharing = true) }
        
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val uri = if (photo.localUri != null && photo.localUri.isNotEmpty() && !photo.isStorageOptimized) {
                    android.net.Uri.parse(photo.localUri)
                } else {
                    val downloadUrl = if (photo.isVideo()) getVideoUrl(photo) else getFullImageUrl(photo)
                    val client = OkHttpClient()
                    val request = Request.Builder().url(downloadUrl).build()
                    val response = client.newCall(request).execute()
                    if (!response.isSuccessful) throw Exception("Download failed")
                    
                    val sharedMediaDir = File(context.cacheDir, "shared_media")
                    if (!sharedMediaDir.exists()) sharedMediaDir.mkdirs()
                    
                    val ext = if (photo.isVideo()) "mp4" else "jpg"
                    val tempFile = File(sharedMediaDir, "${photo.md5}_share.$ext")
                    
                    response.body?.byteStream()?.use { input ->
                        FileOutputStream(tempFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    
                    androidx.core.content.FileProvider.getUriForFile(
                        context,
                        "com.kqstone.mtphotos.fileprovider",
                        tempFile
                    )
                }
                
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(isSharing = false) }
                    val shareIntent = android.content.Intent().apply {
                        action = android.content.Intent.ACTION_SEND
                        putExtra(android.content.Intent.EXTRA_STREAM, uri)
                        type = if (photo.isVideo()) "video/*" else "image/*"
                        flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    }
                    context.startActivity(android.content.Intent.createChooser(shareIntent, "分享媒体"))
                }
            } catch (e: Exception) {
                android.util.Log.e("ViewerVM", "Sharing failed", e)
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(isSharing = false) }
                    android.widget.Toast.makeText(context, "分享失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun getFullImageUrl(photo: UnifiedPhotoItem): String {
        photo.localUri?.let { if (it.isNotEmpty() && !photo.isStorageOptimized) return it }
        val cloudId = photo.cloudId ?: return ""
        return galleryRepository.getFullImageUrl(cloudId, photo.md5)
    }

    fun getOriginalImageUrl(photo: UnifiedPhotoItem): String {
        val cloudId = photo.cloudId ?: return ""
        return galleryRepository.getOriginalImageUrl(cloudId, photo.md5)
    }

    fun getFileDownloadUrl(photo: UnifiedPhotoItem): String {
        val cloudId = photo.cloudId ?: return ""
        return galleryRepository.getFileDownloadUrl(cloudId, photo.md5)
    }

    fun downloadOriginal(context: android.content.Context) {
        val photo = getCurrentPhoto() ?: return
        if (_uiState.value.isDownloadingOriginal) return
        _uiState.update { it.copy(isDownloadingOriginal = true) }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val downloadUrl = getFileDownloadUrl(photo)
                if (downloadUrl.isEmpty()) throw Exception("无法获取原图地址")

                // OkHttp 下载原图
                val client = OkHttpClient()
                val request = Request.Builder().url(downloadUrl).build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) throw Exception("下载失败: ${response.code}")
                val bytes = response.body?.bytes() ?: throw Exception("响应为空")

                // 通过 MediaStore 写入 Pictures/MtGallery/
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
                // 保留原始拍摄时间
                val fileDetailMtime = _uiState.value.fileDetailInfo?.get("mtime")
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
                val extRaw = (_uiState.value.fileDetailInfo?.get("fileType") as? String)
                    ?.takeIf { it.isNotBlank() }
                    ?: photo.fileType.takeIf { it.isNotBlank() }
                    ?: photo.fileName.substringAfterLast('.', "jpg")
                val ext = extRaw.trim().lowercase().removePrefix(".")
                val newFileName = "${photo.md5}.$ext"

                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, newFileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/MtGallery/$idStr")
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
                    ?: throw Exception("MediaStore 创建失败")
                resolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: throw Exception("无法写入文件")

                // 查询实际文件路径
                val filePath = resolver.query(
                    uri,
                    arrayOf(MediaStore.Images.Media.DATA),
                    null, null, null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                } ?: ""

                // 修改底层文件系统的修改时间，避免后续被扫描时因为没有 EXIF 而使用当前时间
                if (filePath.isNotEmpty() && dateTakenMillis != null) {
                    try {
                        val file = java.io.File(filePath)
                        if (file.exists()) {
                            file.setLastModified(dateTakenMillis)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                // 取消 PENDING 状态，并再次写入时间避免被系统覆盖
                val updateValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                    if (dateTakenMillis != null) {
                        put(MediaStore.MediaColumns.DATE_TAKEN, dateTakenMillis)
                        put(MediaStore.MediaColumns.DATE_MODIFIED, dateTakenMillis / 1000)
                        put(MediaStore.MediaColumns.DATE_ADDED, dateTakenMillis / 1000)
                    }
                }
                resolver.update(uri, updateValues, null, null)

                // 更新 Room 数据库
                galleryRepository.markOriginalDownloaded(
                    photo.md5, uri.toString(), filePath
                )

                // 更新内存中的照片状态
                withContext(Dispatchers.Main) {
                    _uiState.update { state ->
                        state.copy(
                            isDownloadingOriginal = false,
                            originalDownloaded = true,
                            photos = state.photos.map { p ->
                                if (p.md5 == photo.md5) p.copy(
                                    syncStatus = SyncStatus.SYNCED,
                                    localUri = uri.toString(),
                                    isStorageOptimized = false
                                ) else p
                            }
                        )
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("ViewerVM", "Download original failed", e)
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(isDownloadingOriginal = false) }
                    android.widget.Toast.makeText(
                        context, "下载原图失败: ${e.message}",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    fun getVideoUrl(photo: UnifiedPhotoItem): String {
        if (!photo.isMotionPhoto()) {
            photo.localUri?.let { if (it.isNotEmpty() && !photo.isStorageOptimized) return it }
        }
        val cloudId = photo.cloudId ?: return ""
        return if (photo.isMotionPhoto()) {
            galleryRepository.getMotionPhotoUrl(cloudId, photo.md5)
        } else {
            galleryRepository.getFullImageUrl(cloudId, photo.md5)
        }
    }

    fun getFullImageUrl(id: Double, md5: String): String {
        return galleryRepository.getFullImageUrl(id, md5)
    }

    fun getVideoUrl(id: Double, md5: String): String {
        return galleryRepository.getFullImageUrl(id, md5)
    }

    fun getCurrentPhoto(): UnifiedPhotoItem? {
        val state = _uiState.value
        return state.photos.getOrNull(state.currentIndex)
    }

    class Factory(private val galleryRepository: GalleryRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ViewerViewModel(galleryRepository) as T
        }
    }
}
