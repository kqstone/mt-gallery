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
import com.kqstone.mtphotos.ui.util.ShareManager
import com.kqstone.mtphotos.ui.util.ThumbnailUrlResolver
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.LinkedHashMap

data class CategoryFileListUiState(
    val pageKey: String? = null,
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
    private var loadJob: Job? = null
    private var loadingKey: String? = null
    private var activeCacheKey: String? = null
    private val locationDistrictCache = mutableMapOf<String, List<LocationItem>>()
    private val pageCache = object : LinkedHashMap<String, CategoryFileListUiState>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CategoryFileListUiState>?): Boolean {
            return size > 24
        }
    }

    val selectionManager = SelectionManager(
        scope = viewModelScope,
        onDelete = { ids -> galleryRepository.deleteFiles(ids) },
        onError = { msg -> _uiState.value = _uiState.value.copy(error = msg) }
    )

    val shareManager = ShareManager(galleryRepository, viewModelScope)

    fun loadPeopleFiles(peopleId: String, force: Boolean = false) {
        loadFiles(cacheKey = pageKey("people", peopleId), force = force) {
            galleryRepository.getPeopleFiles(peopleId)
        }
    }

    fun loadSceneFiles(id: String, cid: String?, force: Boolean = false) {
        loadFiles(cacheKey = pageKey("scene", id, cid), force = force) {
            galleryRepository.getClassifyFileList(id, cid)
        }
    }

    fun loadAlbumFiles(albumId: Double, force: Boolean = false) {
        loadFiles(cacheKey = pageKey("album", albumId.toInt().toString()), force = force) {
            galleryRepository.getAlbumFiles(albumId)
        }
    }

    fun loadFavoritesFiles(force: Boolean = false) {
        loadFiles(cacheKey = pageKey("favorites", ""), force = force) {
            galleryRepository.getFavoriteFiles()
        }
    }

    fun loadRecentFiles(force: Boolean = false) {
        loadFiles(cacheKey = pageKey("recent", ""), force = force) { galleryRepository.getRecentFiles() }
    }

    fun loadVideoFiles(force: Boolean = false) {
        loadFiles(cacheKey = pageKey("videos", ""), force = force) { galleryRepository.getVideoFiles() }
    }

    fun loadTrashFiles(force: Boolean = false) {
        loadFiles(cacheKey = pageKey("trash", ""), force = force) { galleryRepository.getTrashFiles() }
    }

    fun loadLocationFiles(city: String) {
        loadLocationFiles(city, district = null)
    }

    fun loadLocationFiles(city: String, district: String?, force: Boolean = false) {
        val cacheKey = pageKey("location", city, district)
        if (restoreCached(cacheKey, force)) return
        if (!force && loadJob?.isActive == true && loadingKey == cacheKey) return

        loadJob?.cancel()
        localVideoThumbJob?.cancel()
        activatePage(cacheKey)
        loadingKey = cacheKey
        val cachedDistricts = locationDistrictCache[city].orEmpty()
        _uiState.value = CategoryFileListUiState(
            pageKey = cacheKey,
            isLoading = true,
            locationDistricts = cachedDistricts,
            selectedDistrict = district,
            columnCount = _uiState.value.columnCount
        )

        loadJob = viewModelScope.launch {
            val filesResult = galleryRepository.getFilesInCity(city, district)
            val districts = locationDistrictCache[city]
                ?: galleryRepository.getAddressCountByDistrict(city).getOrDefault(emptyList())
                    .also { locationDistrictCache[city] = it }
            filesResult.fold(
                onSuccess = { photos ->
                    val unified = syncRepository?.hydrateCloudPhotos(photos)
                        ?: photos.map { it.toCloudOnlyUnifiedPhotoItem() }
                    val newState = CategoryFileListUiState(
                        pageKey = cacheKey,
                        photos = unified,
                        locationDistricts = districts,
                        selectedDistrict = district,
                        columnCount = _uiState.value.columnCount
                    )
                    cacheAndShow(cacheKey, newState)
                    if (activeCacheKey == cacheKey) warmLocalVideoThumbnails(unified)
                },
                onFailure = { e ->
                    if (activeCacheKey == cacheKey) {
                        _uiState.value = _uiState.value.copy(
                            pageKey = cacheKey,
                            isLoading = false,
                            error = e.message ?: "加载失败",
                            locationDistricts = districts,
                            selectedDistrict = district
                        )
                    }
                }
            )
            if (loadingKey == cacheKey) loadingKey = null
        }
    }

    private fun loadFiles(
        cacheKey: String,
        force: Boolean,
        loader: suspend () -> Result<List<PhotoItem>>
    ) {
        if (restoreCached(cacheKey, force)) return
        if (!force && loadJob?.isActive == true && loadingKey == cacheKey) return

        loadJob?.cancel()
        localVideoThumbJob?.cancel()
        activatePage(cacheKey)
        loadingKey = cacheKey
        _uiState.value = CategoryFileListUiState(
            pageKey = cacheKey,
            isLoading = true,
            columnCount = _uiState.value.columnCount
        )

        loadJob = viewModelScope.launch {
            loader().fold(
                onSuccess = { photos ->
                    if (photos.any { it.isFavorite }) {
                        syncRepository?.upsertCloudPhotoItems(photos)
                    }
                    val unified = syncRepository?.hydrateCloudPhotos(photos)
                        ?: photos.map { it.toCloudOnlyUnifiedPhotoItem() }
                    val newState = CategoryFileListUiState(
                        pageKey = cacheKey,
                        photos = unified,
                        columnCount = _uiState.value.columnCount
                    )
                    cacheAndShow(cacheKey, newState)
                    if (activeCacheKey == cacheKey) warmLocalVideoThumbnails(unified)
                },
                onFailure = { e ->
                    if (activeCacheKey == cacheKey) {
                        _uiState.value = _uiState.value.copy(
                            pageKey = cacheKey,
                            isLoading = false,
                            error = e.message ?: "加载失败"
                        )
                    }
                }
            )
            if (loadingKey == cacheKey) loadingKey = null
        }
    }

    private fun restoreCached(cacheKey: String, force: Boolean): Boolean {
        activatePage(cacheKey)
        if (force) return false
        val cached = pageCache[cacheKey] ?: return false
        _uiState.value = cached.copy(isLoading = false, error = null)
        return true
    }

    private fun cacheAndShow(cacheKey: String, state: CategoryFileListUiState) {
        val cacheable = state.copy(isLoading = false, error = null)
        pageCache[cacheKey] = cacheable
        if (activeCacheKey == cacheKey) {
            _uiState.value = cacheable
        }
    }

    private fun updateActiveCache(state: CategoryFileListUiState) {
        activeCacheKey?.let { pageCache[it] = state.copy(isLoading = false, error = null) }
    }

    private fun activatePage(cacheKey: String) {
        if (activeCacheKey != cacheKey) {
            selectionManager.clearSelection()
        }
        activeCacheKey = cacheKey
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
        updateActiveCache(_uiState.value)
    }

    fun updateColumnCount(count: Int) {
        _uiState.value = _uiState.value.copy(columnCount = count.coerceIn(2, 6))
        updateActiveCache(_uiState.value)
    }

    fun selectAll() {
        selectionManager.selectAll(_uiState.value.photos.map { it.id })
    }

    fun deleteSelected() {
        val selectedIds = selectionManager.selectedPhotoIds.value
        selectionManager.deleteSelected {
            val remaining = _uiState.value.photos.filter { it.id !in selectedIds }
            _uiState.value = _uiState.value.copy(photos = remaining)
            updateActiveCache(_uiState.value)
        }
    }

    fun shareSelected(context: android.content.Context) {
        val selectedIds = selectionManager.selectedPhotoIds.value
        val photos = _uiState.value.photos.filter { it.id in selectedIds }
        shareManager.sharePhotos(context, photos) {
            selectionManager.clearSelection()
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

    companion object {
        fun pageKey(loadType: String, loadParam: String, loadParam2: String? = null): String {
            return when (loadType) {
                "album" -> "album:${loadParam.toDoubleOrNull()?.toInt() ?: 0}"
                "favorites" -> "album:1"
                "recent" -> "recent:"
                "videos" -> "videos:"
                "trash" -> "trash:"
                else -> "$loadType:$loadParam:${loadParam2.orEmpty()}"
            }
        }
    }
}
