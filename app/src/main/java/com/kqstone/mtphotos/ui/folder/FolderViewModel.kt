package com.kqstone.mtphotos.ui.folder

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kqstone.mtphotos.data.repository.FolderItem
import com.kqstone.mtphotos.data.repository.GalleryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class FolderUiState(
    val folders: List<FolderItem> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String? = null
)

class FolderViewModel(private val galleryRepository: GalleryRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(FolderUiState())
    val uiState: StateFlow<FolderUiState> = _uiState

    init {
        loadFolders()
    }

    fun loadFolders() {
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
            galleryRepository.getRootFolders().fold(
                onSuccess = { folders ->
                    _uiState.value = FolderUiState(folders = folders)
                },
                onFailure = { e ->
                    _uiState.value = FolderUiState(error = e.message ?: "加载失败")
                }
            )
        }
    }

    fun refresh() {
        _uiState.value = _uiState.value.copy(isRefreshing = true)
        viewModelScope.launch {
            galleryRepository.getRootFolders().fold(
                onSuccess = { folders ->
                    _uiState.value = FolderUiState(folders = folders)
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(isRefreshing = false, error = e.message)
                }
            )
        }
    }

    fun getThumbUrl(md5: String, fileId: Double): String {
        return galleryRepository.getThumbUrl(md5, fileId)
    }

    fun getThumbUrlByMd5(md5: String): String {
        return galleryRepository.getThumbUrlByMd5(md5)
    }

    class Factory(private val galleryRepository: GalleryRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return FolderViewModel(galleryRepository) as T
        }
    }
}
