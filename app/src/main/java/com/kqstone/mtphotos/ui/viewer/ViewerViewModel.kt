package com.kqstone.mtphotos.ui.viewer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.kqstone.mtphotos.data.model.UnifiedPhotoItem
import com.kqstone.mtphotos.data.repository.GalleryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class ViewerUiState(
    val photos: List<UnifiedPhotoItem> = emptyList(),
    val currentIndex: Int = 0
)

class ViewerViewModel(private val galleryRepository: GalleryRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(ViewerUiState())
    val uiState: StateFlow<ViewerUiState> = _uiState

    fun setPhotos(photos: List<UnifiedPhotoItem>, initialIndex: Int) {
        _uiState.value = ViewerUiState(
            photos = photos,
            currentIndex = initialIndex.coerceIn(0, (photos.size - 1).coerceAtLeast(0))
        )
    }

    fun updateCurrentIndex(index: Int) {
        _uiState.value = _uiState.value.copy(currentIndex = index)
    }

    fun getFullImageUrl(photo: UnifiedPhotoItem): String {
        // 优先使用本地文件
        photo.localUri?.let { if (it.isNotEmpty() && !photo.isStorageOptimized) return it }
        // 使用云端原图
        val cloudId = photo.cloudId ?: return ""
        return galleryRepository.getFullImageUrl(cloudId, photo.md5)
    }

    fun getVideoUrl(photo: UnifiedPhotoItem): String {
        // 优先使用本地文件
        photo.localUri?.let { if (it.isNotEmpty() && !photo.isStorageOptimized) return it }
        // 使用云端
        val cloudId = photo.cloudId ?: return ""
        return galleryRepository.getFullImageUrl(cloudId, photo.md5)
    }

    // 兼容旧代码
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
