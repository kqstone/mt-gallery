package com.kqstone.mtphotos.ui.folder

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kqstone.mtphotos.data.model.UnifiedPhotoItem
import com.kqstone.mtphotos.data.repository.FolderItem
import com.kqstone.mtphotos.data.repository.GalleryRepository
import com.kqstone.mtphotos.data.repository.MediaUiMutation
import com.kqstone.mtphotos.data.repository.MediaUiMutationBus
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

data class FolderDetailUiState(
    val folderId: String? = null,
    val folderName: String = "",
    val subfolders: List<FolderItem> = emptyList(),
    val photos: List<UnifiedPhotoItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: UiText? = null,
    val columnCount: Int = 4
)

class FolderDetailViewModel(
    private val galleryRepository: GalleryRepository,
    private val syncRepository: SyncRepository? = null,
    private val serverOpTaskRepository: ServerOpTaskRepository? = null,
    private val appContext: Context? = null,
    private val mediaUiMutationBus: MediaUiMutationBus? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(FolderDetailUiState())
    val uiState: StateFlow<FolderDetailUiState> = _uiState

    private var localVideoThumbJob: Job? = null
    private var loadJob: Job? = null
    private var loadingFolderId: String? = null
    private var activeFolderId: String? = null
    private val folderCache = object : LinkedHashMap<String, FolderDetailUiState>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FolderDetailUiState>?): Boolean {
            return size > 16
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
            is MediaUiMutation.Deleted -> updateLoadedPhotoPages { photos ->
                photos.removePhotos(mutation.photos)
            }
            is MediaUiMutation.FavoriteChanged -> updateLoadedPhotoPages { photos ->
                photos.updateFavorite(mutation.photos, mutation.isFavorite)
            }
            is MediaUiMutation.HideChanged -> updateLoadedPhotoPages { photos ->
                if (mutation.isHide) {
                    photos.removePhotos(mutation.photos)
                } else {
                    photos.updateHide(mutation.photos, isHide = false)
                }
            }
            is MediaUiMutation.PersonRenamed -> Unit
        }
    }

    private fun updateLoadedPhotoPages(
        transform: (List<UnifiedPhotoItem>) -> List<UnifiedPhotoItem>
    ) {
        _uiState.value = _uiState.value.copy(photos = transform(_uiState.value.photos))
        val keys = folderCache.keys.toList()
        for (key in keys) {
            val state = folderCache[key] ?: continue
            folderCache[key] = state.copy(photos = transform(state.photos))
        }
        updateActiveCache(_uiState.value)
    }

    fun loadFolder(folderId: String, force: Boolean = false) {
        if (restoreCached(folderId, force)) return
        if (!force && loadJob?.isActive == true && loadingFolderId == folderId) return

        loadJob?.cancel()
        localVideoThumbJob?.cancel()
        activateFolder(folderId)
        loadingFolderId = folderId
        _uiState.value = FolderDetailUiState(
            folderId = folderId,
            isLoading = true,
            columnCount = _uiState.value.columnCount
        )

        loadJob = viewModelScope.launch {
            val detailResult = galleryRepository.getFolderDetail(folderId)
            val filesResult = galleryRepository.getFolderFiles(folderId)

            detailResult.fold(
                onSuccess = { detail ->
                    val cloudPhotos = filesResult.getOrNull() ?: emptyList()
                    val photos = syncRepository?.hydrateCloudPhotos(cloudPhotos)
                        ?: cloudPhotos.map { it.toCloudOnlyUnifiedPhotoItem() }
                    val newState = FolderDetailUiState(
                        folderId = folderId,
                        folderName = detail.name,
                        subfolders = detail.subfolders,
                        photos = photos,
                        columnCount = _uiState.value.columnCount
                    )
                    cacheAndShow(folderId, newState)
                    if (activeFolderId == folderId) warmLocalVideoThumbnails(photos)
                },
                onFailure = { e ->
                    if (activeFolderId == folderId) {
                        _uiState.value = _uiState.value.copy(
                            folderId = folderId,
                            isLoading = false,
                            error = e.message?.let { UiText.DynamicString(it) }
                                ?: UiText.StringResource(R.string.load_failed)
                        )
                    }
                }
            )
            if (loadingFolderId == folderId) loadingFolderId = null
        }
    }

    private fun restoreCached(folderId: String, force: Boolean): Boolean {
        activateFolder(folderId)
        if (force) return false
        val cached = folderCache[folderId] ?: return false
        _uiState.value = cached.copy(isLoading = false, error = null)
        return true
    }

    private fun cacheAndShow(folderId: String, state: FolderDetailUiState) {
        val cacheable = state.copy(isLoading = false, error = null)
        folderCache[folderId] = cacheable
        if (activeFolderId == folderId) {
            _uiState.value = cacheable
        }
    }

    private fun updateActiveCache(state: FolderDetailUiState) {
        activeFolderId?.let { folderCache[it] = state.copy(isLoading = false, error = null) }
    }

    private fun activateFolder(folderId: String) {
        if (activeFolderId != folderId) {
            selectionManager.clearSelection()
        }
        activeFolderId = folderId
    }

    fun getThumbUrl(md5: String, fileId: Double): String {
        return MediaThumbnailResolver.resolveCloudThumb(md5, fileId, galleryRepository)
    }

    fun getVideoThumbUrl(md5: String): String {
        return galleryRepository.getVideoThumbUrl(md5)
    }

    fun getThumbUrlByMd5(md5: String): String {
        return MediaThumbnailResolver.resolveCloudThumbByMd5(md5, galleryRepository)
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

    class Factory(
        private val galleryRepository: GalleryRepository,
        private val syncRepository: SyncRepository? = null,
        private val serverOpTaskRepository: ServerOpTaskRepository? = null,
        private val appContext: Context? = null,
        private val mediaUiMutationBus: MediaUiMutationBus? = null
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return FolderDetailViewModel(
                galleryRepository,
                syncRepository,
                serverOpTaskRepository,
                appContext,
                mediaUiMutationBus
            ) as T
        }
    }
}
