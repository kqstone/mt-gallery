package com.kqstone.mtphotos.ui.viewer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
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
    val isSharing: Boolean = false
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
                fileDetailInfo = null
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
