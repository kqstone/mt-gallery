package com.kqstone.mtphotos.ui.folder

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kqstone.mtphotos.data.repository.AlbumItem
import com.kqstone.mtphotos.data.repository.FolderItem
import com.kqstone.mtphotos.data.repository.GalleryRepository
import com.kqstone.mtphotos.R
import com.kqstone.mtphotos.ui.media.MediaThumbnailResolver
import com.kqstone.mtphotos.ui.util.PullRefreshSupport
import com.kqstone.mtphotos.ui.util.UiText
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class CollectionCategoryItem(
    val type: String,
    val title: UiText,
    val subtitle: UiText
)

data class FolderUiState(
    val albums: List<AlbumItem> = emptyList(),
    val folders: List<FolderItem> = emptyList(),
    val categories: List<CollectionCategoryItem> = defaultCollectionCategories(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: UiText? = null,
    val toastMessage: UiText? = null
)

class FolderViewModel(private val galleryRepository: GalleryRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(FolderUiState())
    val uiState: StateFlow<FolderUiState> = _uiState
    private var refreshJob: Job? = null

    init {
        loadFolders()
    }

    fun loadFolders() {
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
            val result = loadCollections()
            _uiState.value = FolderUiState(
                albums = result.albums,
                folders = result.folders,
                categories = defaultCollectionCategories(),
                error = result.error
            )
        }
    }

    fun refresh() {
        if (refreshJob?.isActive == true || _uiState.value.isRefreshing) return

        _uiState.value = _uiState.value.copy(
            isRefreshing = true,
            error = null,
            toastMessage = null
        )
        refreshJob = viewModelScope.launch {
            var resultMessage: UiText? = null
            val refreshError = PullRefreshSupport.run(
                isDeviceOffline = { galleryRepository.isDeviceOffline() },
                onOffline = { galleryRepository.markNetworkRetryPending() }
            ) {
                val result = loadCollections()
                resultMessage = result.error
                if (result.hasContent || result.error == null) {
                    _uiState.value = _uiState.value.copy(
                        albums = result.albums,
                        folders = result.folders,
                        categories = defaultCollectionCategories(),
                        error = null
                    )
                } else if (!_uiState.value.hasContent) {
                    _uiState.value = _uiState.value.copy(error = result.error)
                }
            }

            val message = refreshError ?: resultMessage
            if (message != null) {
                val hasContent = _uiState.value.hasContent
                _uiState.value = _uiState.value.copy(
                    error = if (hasContent) _uiState.value.error else message,
                    toastMessage = message
                )
            }
            _uiState.value = _uiState.value.copy(isRefreshing = false)
        }
    }

    fun clearToastMessage() {
        _uiState.value = _uiState.value.copy(toastMessage = null)
    }

    fun getThumbUrlByMd5(md5: String): String {
        return MediaThumbnailResolver.resolveCloudThumbByMd5(md5, galleryRepository)
    }

    private suspend fun loadCollections(): CollectionLoadResult {
        return try {
            val (albumsResult, foldersResult) = coroutineScope {
                val albums = async { galleryRepository.getAlbums() }
                val folders = async { galleryRepository.getRootFolders() }
                albums.await() to folders.await()
            }
            CollectionLoadResult(
                albums = albumsResult.getOrDefault(emptyList()),
                folders = foldersResult.getOrDefault(emptyList()),
                error = buildError(albumsResult.exceptionOrNull(), foldersResult.exceptionOrNull())
            )
        } catch (e: Exception) {
            CollectionLoadResult(
                error = e.message?.let { UiText.DynamicString(it) }
                    ?: UiText.StringResource(R.string.load_failed)
            )
        }
    }

    private fun buildError(albumError: Throwable?, folderError: Throwable?): UiText? {
        val errors = listOfNotNull(albumError, folderError)
        if (errors.isNotEmpty()) {
            return PullRefreshSupport.toRefreshMessage(errors.first())
        }
        val albumMessage = albumError?.message?.takeIf { it.isNotBlank() }
        val folderMessage = folderError?.message?.takeIf { it.isNotBlank() }
        return when {
            albumMessage == null && folderMessage == null -> null
            albumMessage != null && folderMessage != null -> UiText.DynamicString("$albumMessage / $folderMessage")
            else -> (albumMessage ?: folderMessage)?.let { UiText.DynamicString(it) }
        }
    }

    class Factory(private val galleryRepository: GalleryRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return FolderViewModel(galleryRepository) as T
        }
    }
}

private data class CollectionLoadResult(
    val albums: List<AlbumItem> = emptyList(),
    val folders: List<FolderItem> = emptyList(),
    val error: UiText? = null
) {
    val hasContent: Boolean get() = albums.isNotEmpty() || folders.isNotEmpty()
}

private val FolderUiState.hasContent: Boolean
    get() = albums.isNotEmpty() || folders.isNotEmpty()

private fun defaultCollectionCategories(): List<CollectionCategoryItem> {
    return listOf(
        CollectionCategoryItem(
            type = "favorites",
            title = UiText.StringResource(R.string.category_favorites),
            subtitle = UiText.StringResource(R.string.category_favorites_desc)
        ),
        CollectionCategoryItem(
            type = "recent",
            title = UiText.StringResource(R.string.category_recent),
            subtitle = UiText.StringResource(R.string.category_recent_desc)
        ),
        CollectionCategoryItem(
            type = "videos",
            title = UiText.StringResource(R.string.category_videos),
            subtitle = UiText.StringResource(R.string.category_videos_desc)
        ),
        CollectionCategoryItem(
            type = "trash",
            title = UiText.StringResource(R.string.category_trash),
            subtitle = UiText.StringResource(R.string.category_trash_desc)
        )
    )
}
