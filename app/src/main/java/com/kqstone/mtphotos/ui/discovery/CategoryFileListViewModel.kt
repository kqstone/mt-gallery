package com.kqstone.mtphotos.ui.discovery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kqstone.mtphotos.data.repository.GalleryRepository
import com.kqstone.mtphotos.data.repository.PhotoItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class CategoryFileListUiState(
    val photos: List<PhotoItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val columnCount: Int = 3
)

class CategoryFileListViewModel(private val galleryRepository: GalleryRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(CategoryFileListUiState())
    val uiState: StateFlow<CategoryFileListUiState> = _uiState

    fun loadPeopleFiles(peopleId: String) {
        loadFiles { galleryRepository.getPeopleFiles(peopleId) }
    }

    fun loadSceneFiles(id: String, cid: String?) {
        loadFiles { galleryRepository.getClassifyFileList(id, cid) }
    }

    fun loadLocationFiles(city: String) {
        loadFiles { galleryRepository.getFilesInCity(city) }
    }

    private fun loadFiles(loader: suspend () -> Result<List<PhotoItem>>) {
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
            loader().fold(
                onSuccess = { photos ->
                    _uiState.value = CategoryFileListUiState(photos = photos)
                },
                onFailure = { e ->
                    _uiState.value = CategoryFileListUiState(error = e.message ?: "加载失败")
                }
            )
        }
    }

    fun getThumbUrl(md5: String, fileId: Double): String {
        return galleryRepository.getThumbUrl(md5, fileId)
    }

    fun getAllLoadedPhotos(): List<PhotoItem> = _uiState.value.photos

    fun updateColumnCount(count: Int) {
        _uiState.value = _uiState.value.copy(columnCount = count.coerceIn(2, 6))
    }

    class Factory(private val galleryRepository: GalleryRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return CategoryFileListViewModel(galleryRepository) as T
        }
    }
}
