package com.kqstone.mtphotos.ui.discovery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kqstone.mtphotos.data.local.db.SyncStatus
import com.kqstone.mtphotos.data.model.UnifiedPhotoItem
import com.kqstone.mtphotos.data.repository.GalleryRepository
import com.kqstone.mtphotos.data.repository.PhotoItem
import com.kqstone.mtphotos.ui.gallery.SelectionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class CategoryFileListUiState(
    val photos: List<UnifiedPhotoItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val columnCount: Int = 4
)

class CategoryFileListViewModel(private val galleryRepository: GalleryRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(CategoryFileListUiState())
    val uiState: StateFlow<CategoryFileListUiState> = _uiState

    val selectionManager = SelectionManager(
        scope = viewModelScope,
        onDelete = { ids -> galleryRepository.deleteFiles(ids) },
        onError = { msg -> _uiState.value = _uiState.value.copy(error = msg) }
    )

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
                    val unified = photos.map { p ->
                        UnifiedPhotoItem(
                            cloudId = p.id, md5 = p.md5, fileName = p.fileName,
                            fileType = p.fileType, mtime = p.mtime,
                            width = p.width, height = p.height,
                            syncStatus = SyncStatus.CLOUD_ONLY
                        )
                    }
                    _uiState.value = CategoryFileListUiState(photos = unified)
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

    fun getVideoThumbUrl(md5: String): String {
        return galleryRepository.getVideoThumbUrl(md5)
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
            return CategoryFileListViewModel(galleryRepository) as T
        }
    }
}
