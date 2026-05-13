package com.kqstone.mtphotos.ui.viewer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.kqstone.mtphotos.data.repository.GalleryRepository
import com.kqstone.mtphotos.data.repository.PhotoItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class ViewerUiState(
    val photos: List<PhotoItem> = emptyList(),
    val currentIndex: Int = 0
)

class ViewerViewModel(private val galleryRepository: GalleryRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(ViewerUiState())
    val uiState: StateFlow<ViewerUiState> = _uiState

    fun setPhotos(photos: List<PhotoItem>, initialIndex: Int) {
        _uiState.value = ViewerUiState(
            photos = photos,
            currentIndex = initialIndex.coerceIn(0, (photos.size - 1).coerceAtLeast(0))
        )
    }

    fun updateCurrentIndex(index: Int) {
        _uiState.value = _uiState.value.copy(currentIndex = index)
    }

    fun getFullImageUrl(id: Double, md5: String): String {
        return galleryRepository.getFullImageUrl(id, md5)
    }

    fun getCurrentPhoto(): PhotoItem? {
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
