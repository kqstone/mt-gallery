package com.kqstone.mtphotos.ui.folder

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kqstone.mtphotos.data.local.db.SyncStatus
import com.kqstone.mtphotos.data.model.UnifiedPhotoItem
import com.kqstone.mtphotos.data.repository.FolderItem
import com.kqstone.mtphotos.data.repository.GalleryRepository
import com.kqstone.mtphotos.ui.gallery.SelectionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class FolderDetailUiState(
    val folderName: String = "",
    val subfolders: List<FolderItem> = emptyList(),
    val photos: List<UnifiedPhotoItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val columnCount: Int = 4
)

class FolderDetailViewModel(private val galleryRepository: GalleryRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(FolderDetailUiState())
    val uiState: StateFlow<FolderDetailUiState> = _uiState

    val selectionManager = SelectionManager(
        scope = viewModelScope,
        onDelete = { ids -> galleryRepository.deleteFiles(ids) },
        onError = { msg -> _uiState.value = _uiState.value.copy(error = msg) }
    )

    fun loadFolder(folderId: String) {
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
            val detailResult = galleryRepository.getFolderDetail(folderId)
            val filesResult = galleryRepository.getFolderFiles(folderId)

            detailResult.fold(
                onSuccess = { detail ->
                    val photos = (filesResult.getOrNull() ?: emptyList()).map { p ->
                        UnifiedPhotoItem(
                            cloudId = p.id, md5 = p.md5, fileName = p.fileName,
                            fileType = p.fileType, mtime = p.mtime,
                            width = p.width, height = p.height,
                            syncStatus = SyncStatus.CLOUD_ONLY
                        )
                    }
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

    fun getVideoThumbUrl(md5: String): String {
        return galleryRepository.getVideoThumbUrl(md5)
    }

    fun getThumbUrlByMd5(md5: String): String {
        return galleryRepository.getThumbUrlByMd5(md5)
    }

    fun getAllLoadedPhotos(): List<UnifiedPhotoItem> = _uiState.value.photos

    fun updateColumnCount(count: Int) {
        _uiState.value = _uiState.value.copy(columnCount = count.coerceIn(2, 6))
    }

    fun selectAll() {
        selectionManager.selectAll(_uiState.value.photos.map { it.id })
    }

    fun deleteSelected() {
        val selectedIds = selectionManager.selectedPhotoIds.value
        selectionManager.deleteSelected {
            val remaining = _uiState.value.photos.filter { it.id !in selectedIds }
            _uiState.value = _uiState.value.copy(photos = remaining)
        }
    }

    class Factory(private val galleryRepository: GalleryRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return FolderDetailViewModel(galleryRepository) as T
        }
    }
}
