package com.kqstone.mtphotos.ui.discovery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kqstone.mtphotos.data.model.UnifiedPhotoItem
import com.kqstone.mtphotos.data.repository.GalleryRepository
import com.kqstone.mtphotos.data.repository.LocationItem
import com.kqstone.mtphotos.data.repository.PhotoItem
import com.kqstone.mtphotos.data.repository.SyncRepository
import com.kqstone.mtphotos.data.repository.toCloudOnlyUnifiedPhotoItem
import com.kqstone.mtphotos.ui.gallery.SelectionManager
import com.kqstone.mtphotos.ui.util.LocalVideoThumbnailWarmup
import com.kqstone.mtphotos.ui.util.ThumbnailUrlResolver
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class CategoryFileListUiState(
    val photos: List<UnifiedPhotoItem> = emptyList(),
    val locationDistricts: List<LocationItem> = emptyList(),
    val selectedDistrict: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val columnCount: Int = 4
)

class CategoryFileListViewModel(
    private val galleryRepository: GalleryRepository,
    private val syncRepository: SyncRepository? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(CategoryFileListUiState())
    val uiState: StateFlow<CategoryFileListUiState> = _uiState
    private var localVideoThumbJob: Job? = null

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
        loadLocationFiles(city, district = null)
    }

    fun loadLocationFiles(city: String, district: String?) {
        localVideoThumbJob?.cancel()
        _uiState.value = _uiState.value.copy(
            isLoading = true,
            error = null,
            selectedDistrict = district
        )
        viewModelScope.launch {
            val filesResult = galleryRepository.getFilesInCity(city, district)
            val districts = _uiState.value.locationDistricts.takeIf { it.isNotEmpty() }
                ?: galleryRepository.getAddressCountByDistrict(city).getOrDefault(emptyList())
            filesResult.fold(
                onSuccess = { photos ->
                    val unified = syncRepository?.hydrateCloudPhotos(photos)
                        ?: photos.map { it.toCloudOnlyUnifiedPhotoItem() }
                    _uiState.value = CategoryFileListUiState(
                        photos = unified,
                        locationDistricts = districts,
                        selectedDistrict = district
                    )
                    warmLocalVideoThumbnails(unified)
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = e.message ?: "鍔犺浇澶辫触",
                        locationDistricts = districts,
                        selectedDistrict = district
                    )
                }
            )
        }
    }

    private fun loadFiles(loader: suspend () -> Result<List<PhotoItem>>) {
        localVideoThumbJob?.cancel()
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
            loader().fold(
                onSuccess = { photos ->
                    val unified = syncRepository?.hydrateCloudPhotos(photos)
                        ?: photos.map { it.toCloudOnlyUnifiedPhotoItem() }
                    _uiState.value = CategoryFileListUiState(photos = unified)
                    warmLocalVideoThumbnails(unified)
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

    fun getThumbUrl(photo: UnifiedPhotoItem): String {
        return ThumbnailUrlResolver.resolve(
            photo = photo,
            galleryRepository = galleryRepository,
            imageCloudUrl = { galleryRepository.getThumbUrl(it.md5, it.id) }
        )
    }

    fun getAllLoadedPhotos(): List<UnifiedPhotoItem> = _uiState.value.photos

    private fun warmLocalVideoThumbnails(photos: List<UnifiedPhotoItem>) {
        localVideoThumbJob?.cancel()
        localVideoThumbJob = viewModelScope.launch {
            LocalVideoThumbnailWarmup.warm(photos, syncRepository) { photo, path ->
                updatePhotoThumbCachePath(photo.dbId, path)
            }
        }
    }

    private fun updatePhotoThumbCachePath(dbId: Long, path: String) {
        if (dbId <= 0) return
        _uiState.value = _uiState.value.copy(
            photos = _uiState.value.photos.map { photo ->
                if (photo.dbId == dbId) photo.copy(thumbCachePath = path) else photo
            }
        )
    }

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

    class Factory(
        private val galleryRepository: GalleryRepository,
        private val syncRepository: SyncRepository? = null
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return CategoryFileListViewModel(galleryRepository, syncRepository) as T
        }
    }
}
