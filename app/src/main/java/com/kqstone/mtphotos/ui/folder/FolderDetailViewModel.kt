package com.kqstone.mtphotos.ui.folder

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kqstone.mtphotos.data.repository.FolderItem
import com.kqstone.mtphotos.data.repository.GalleryRepository
import com.kqstone.mtphotos.data.repository.PhotoItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class FolderDetailUiState(
    val folderName: String = "",
    val subfolders: List<FolderItem> = emptyList(),
    val photos: List<PhotoItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val columnCount: Int = 3
)

class FolderDetailViewModel(private val galleryRepository: GalleryRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(FolderDetailUiState())
    val uiState: StateFlow<FolderDetailUiState> = _uiState

    fun loadFolder(folderId: String) {
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
            val detailResult = galleryRepository.getFolderDetail(folderId)
            val filesResult = galleryRepository.getFolderFiles(folderId)

            detailResult.fold(
                onSuccess = { detail ->
                    val photos = filesResult.getOrNull() ?: emptyList()
                    _uiState.value = FolderDetailUiState(
                        folderName = detail.name,
                        subfolders = detail.subfolders,
                        photos = photos
                    )
                },
                onFailure = { e ->
                    _uiState.value = FolderDetailUiState(error = e.message ?: "加载失败")
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
            return FolderDetailViewModel(galleryRepository) as T
        }
    }
}
