package com.kqstone.mtphotos.ui.folder

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kqstone.mtphotos.data.model.UnifiedPhotoItem
import com.kqstone.mtphotos.data.repository.FolderItem
import com.kqstone.mtphotos.data.repository.GalleryRepository
import com.kqstone.mtphotos.data.repository.ServerOpTaskRepository
import com.kqstone.mtphotos.data.repository.SyncRepository
import com.kqstone.mtphotos.data.repository.toCloudOnlyUnifiedPhotoItem
import com.kqstone.mtphotos.ui.gallery.SelectionManager
import com.kqstone.mtphotos.ui.util.LocalVideoThumbnailWarmup
import com.kqstone.mtphotos.ui.util.ShareManager
import com.kqstone.mtphotos.ui.util.ThumbnailUrlResolver
import com.kqstone.mtphotos.worker.BackupScheduler
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
    val error: String? = null,
    val columnCount: Int = 4
)

class FolderDetailViewModel(
    private val galleryRepository: GalleryRepository,
    private val syncRepository: SyncRepository? = null,
    private val serverOpTaskRepository: ServerOpTaskRepository? = null,
    private val appContext: Context? = null
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
                            error = e.message ?: "加载失败"
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
        return galleryRepository.getThumbUrl(md5, fileId)
    }

    fun getVideoThumbUrl(md5: String): String {
        return galleryRepository.getVideoThumbUrl(md5)
    }

    fun getThumbUrlByMd5(md5: String): String {
        return galleryRepository.getThumbUrlByMd5(md5)
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

    fun favoriteSelected() {
        val selectedIds = selectionManager.selectedPhotoIds.value
        if (selectedIds.isEmpty()) return
        val selectedPhotos = _uiState.value.photos.filter { it.id in selectedIds }
        if (selectedPhotos.isEmpty()) return

        _uiState.value = _uiState.value.copy(
            photos = _uiState.value.photos.map { photo ->
                if (photo.id in selectedIds) photo.copy(isFavorite = true) else photo
            }
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

    class Factory(
        private val galleryRepository: GalleryRepository,
        private val syncRepository: SyncRepository? = null,
        private val serverOpTaskRepository: ServerOpTaskRepository? = null,
        private val appContext: Context? = null
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return FolderDetailViewModel(
                galleryRepository,
                syncRepository,
                serverOpTaskRepository,
                appContext
            ) as T
        }
    }
}
