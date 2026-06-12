package com.kqstone.mtphotos.ui.discovery

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kqstone.mtphotos.data.model.UnifiedPhotoItem
import com.kqstone.mtphotos.data.repository.GalleryRepository
import com.kqstone.mtphotos.data.repository.LocationItem
import com.kqstone.mtphotos.data.repository.MediaUiMutation
import com.kqstone.mtphotos.data.repository.MediaUiMutationBus
import com.kqstone.mtphotos.data.repository.PersonId
import com.kqstone.mtphotos.data.repository.PhotoItem
import com.kqstone.mtphotos.data.repository.ServerOpTaskRepository
import com.kqstone.mtphotos.data.repository.SyncRepository
import com.kqstone.mtphotos.data.repository.toCloudOnlyUnifiedPhotoItem
import com.kqstone.mtphotos.ui.gallery.SelectionManager
import com.kqstone.mtphotos.ui.gallery.removePhotos
import com.kqstone.mtphotos.ui.gallery.updateFavorite
import com.kqstone.mtphotos.ui.gallery.updateHide
import com.kqstone.mtphotos.ui.media.MediaThumbnailResolver
import com.kqstone.mtphotos.ui.util.ShareManager
import com.kqstone.mtphotos.worker.BackupScheduler
import com.kqstone.mtphotos.R
import com.kqstone.mtphotos.ui.util.UiText
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
    val error: UiText? = null,
    val columnCount: Int = 4
)

class CategoryFileListViewModel(
    private val galleryRepository: GalleryRepository,
    private val syncRepository: SyncRepository? = null,
    private val serverOpTaskRepository: ServerOpTaskRepository? = null,
    private val appContext: Context? = null,
    private val mediaUiMutationBus: MediaUiMutationBus? = null
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

    init {
        observeMediaUiMutations()
    }

    private fun observeMediaUiMutations() {
        val bus = mediaUiMutationBus ?: return
        viewModelScope.launch {
            bus.mutations.collect { mutation ->
                applyMediaUiMutation(mutation)
            }
        }
    }

    private fun applyMediaUiMutation(mutation: MediaUiMutation) {
        when (mutation) {
            is MediaUiMutation.Deleted -> updateLoadedPhotoPages { _, photos ->
                photos.removePhotos(mutation.photos)
            }
            is MediaUiMutation.FavoriteChanged -> updateLoadedPhotoPages { currentPageKey, photos ->
                val favoritesKey = pageKey("favorites", "")
                if (!mutation.isFavorite && currentPageKey == favoritesKey) {
                    photos.removePhotos(mutation.photos)
                } else {
                    photos.updateFavorite(mutation.photos, mutation.isFavorite)
                }
            }
            is MediaUiMutation.HideChanged -> updateLoadedPhotoPages { _, photos ->
                if (mutation.isHide) {
                    photos.removePhotos(mutation.photos)
                } else {
                    photos.updateHide(mutation.photos, isHide = false)
                }
            }
            is MediaUiMutation.PersonRenamed -> Unit
            else -> Unit
        }
    }

    private fun updateLoadedPhotoPages(
        transform: (pageKey: String?, photos: List<UnifiedPhotoItem>) -> List<UnifiedPhotoItem>
    ) {
        val current = _uiState.value
        val updatedCurrent = current.copy(photos = transform(current.pageKey, current.photos))
        _uiState.value = updatedCurrent

        val keys = pageCache.keys.toList()
        for (key in keys) {
            val state = pageCache[key] ?: continue
            pageCache[key] = state.copy(photos = transform(key, state.photos))
        }
        updateActiveCache(_uiState.value)
    }

    fun loadPeopleFiles(peopleId: String, force: Boolean = false) {
        val normalizedPeopleId = PersonId.normalize(peopleId)
        loadFiles(cacheKey = pageKey("people", normalizedPeopleId), force = force) {
            galleryRepository.getPeopleFiles(normalizedPeopleId)
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
                            error = e.message?.let { UiText.DynamicString(it) }
                                ?: UiText.StringResource(R.string.load_failed),
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
                            error = e.message?.let { UiText.DynamicString(it) }
                                ?: UiText.StringResource(R.string.load_failed)
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
        return MediaThumbnailResolver.resolveCloudThumb(md5, fileId, galleryRepository)
    }

    fun getVideoThumbUrl(md5: String): String {
        return galleryRepository.getVideoThumbUrl(md5)
    }

    fun getThumbUrl(photo: UnifiedPhotoItem): String {
        return MediaThumbnailResolver.resolveTimelineThumb(photo, galleryRepository)
    }

    fun getAllLoadedPhotos(): List<UnifiedPhotoItem> = _uiState.value.photos

    private fun warmLocalVideoThumbnails(photos: List<UnifiedPhotoItem>) {
        localVideoThumbJob?.cancel()
        localVideoThumbJob = viewModelScope.launch {
            updatePhotoThumbCachePaths(
                MediaThumbnailResolver.warmLocalVideoThumbs(photos, syncRepository)
            )
        }
    }

    private fun updatePhotoThumbCachePaths(updates: List<Pair<Long, String>>) {
        val byDbId = updates
            .filter { (dbId, path) -> dbId > 0 && path.isNotBlank() }
            .toMap()
        if (byDbId.isEmpty()) return

        _uiState.value = _uiState.value.copy(
            photos = _uiState.value.photos.map { photo ->
                byDbId[photo.dbId]?.let { path -> photo.copy(thumbCachePath = path) } ?: photo
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
        val selectedPhotos = _uiState.value.photos.filter { it.id in selectedIds }
        selectionManager.deleteSelected(
            photos = _uiState.value.photos,
            onDeletePhotos = { photos -> galleryRepository.deletePhotos(photos) }
        ) {
            _uiState.value = _uiState.value.copy(
                photos = _uiState.value.photos.removePhotos(selectedPhotos)
            )
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

    fun favoriteSelected() {
        val selectedIds = selectionManager.selectedPhotoIds.value
        if (selectedIds.isEmpty()) return
        val selectedPhotos = _uiState.value.photos.filter { it.id in selectedIds }
        if (selectedPhotos.isEmpty()) return

        _uiState.value = _uiState.value.copy(
            photos = _uiState.value.photos.updateFavorite(selectedPhotos, isFavorite = true)
        )
        updateActiveCache(_uiState.value)
        selectionManager.clearSelection()

        val repo = serverOpTaskRepository ?: return
        viewModelScope.launch {
            repo.enqueueFavorites(selectedPhotos, isFavorite = true)
            if (selectedPhotos.any { it.cloudId != null }) {
                appContext?.let { BackupScheduler.triggerServerOpWork(it) }
            }
        }
    }

    fun unfavoriteSelected() {
        val selectedIds = selectionManager.selectedPhotoIds.value
        if (selectedIds.isEmpty()) return
        val selectedPhotos = _uiState.value.photos.filter { it.id in selectedIds }
        if (selectedPhotos.isEmpty()) return

        _uiState.value = _uiState.value.copy(
            photos = _uiState.value.photos.removePhotos(selectedPhotos)
        )
        updateActiveCache(_uiState.value)
        selectionManager.clearSelection()

        val repo = serverOpTaskRepository ?: return
        viewModelScope.launch {
            repo.enqueueFavorites(selectedPhotos, isFavorite = false)
            if (selectedPhotos.any { it.cloudId != null }) {
                appContext?.let { BackupScheduler.triggerServerOpWork(it) }
            }
        }
    }

    fun hideSelected() {
        val selectedIds = selectionManager.selectedPhotoIds.value
        if (selectedIds.isEmpty()) return
        val selectedPhotos = _uiState.value.photos.filter { it.id in selectedIds }
        if (selectedPhotos.isEmpty()) return

        _uiState.value = _uiState.value.copy(
            photos = _uiState.value.photos.removePhotos(selectedPhotos)
        )
        updateActiveCache(_uiState.value)
        selectionManager.clearSelection()

        val repo = serverOpTaskRepository ?: return
        viewModelScope.launch {
            repo.enqueueHides(selectedPhotos, isHide = true)
            if (selectedPhotos.any { it.cloudId != null }) {
                appContext?.let { BackupScheduler.triggerServerOpWork(it) }
            }
        }
    }

    fun renamePerson(
        personId: String,
        currentName: String,
        newName: String,
        onSuccess: (String) -> Unit
    ) {
        val cleanName = newName.trim()
        if (cleanName.isBlank()) return

        val personCloudId = personId.toDoubleOrNull()
        if (personCloudId == null) {
            _uiState.value = _uiState.value.copy(error = UiText.StringResource(R.string.load_failed))
            return
        }

        val repo = serverOpTaskRepository ?: return
        viewModelScope.launch {
            try {
                repo.enqueueRenamePerson(
                    personId = personCloudId,
                    newName = cleanName,
                    personName = currentName
                )
                appContext?.let { BackupScheduler.triggerServerOpWork(it) }
                onSuccess(cleanName)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = e.message?.let { UiText.DynamicString(it) }
                        ?: UiText.StringResource(R.string.load_failed)
                )
            }
        }
    }

    class Factory(
        private val galleryRepository: GalleryRepository,
        private val syncRepository: SyncRepository? = null,
        private val serverOpTaskRepository: ServerOpTaskRepository? = null,
        private val appContext: Context? = null,
        private val mediaUiMutationBus: MediaUiMutationBus? = null
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return CategoryFileListViewModel(
                galleryRepository,
                syncRepository,
                serverOpTaskRepository,
                appContext,
                mediaUiMutationBus
            ) as T
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
                "people" -> "people:${PersonId.normalize(loadParam)}:${loadParam2.orEmpty()}"
                else -> "$loadType:$loadParam:${loadParam2.orEmpty()}"
            }
        }
    }
}
