package com.kqstone.mtphotos.ui.folder

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kqstone.mtphotos.data.repository.AlbumItem
import com.kqstone.mtphotos.data.repository.FolderItem
import com.kqstone.mtphotos.data.repository.GalleryRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class CollectionCategoryItem(
    val type: String,
    val title: String,
    val subtitle: String
)

data class FolderUiState(
    val albums: List<AlbumItem> = emptyList(),
    val folders: List<FolderItem> = emptyList(),
    val categories: List<CollectionCategoryItem> = defaultCollectionCategories(),
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
        _uiState.value = _uiState.value.copy(isRefreshing = true, error = null)
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

    fun getThumbUrlByMd5(md5: String): String {
        return galleryRepository.getThumbUrlByMd5(md5)
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
            CollectionLoadResult(error = e.message ?: "加载失败")
        }
    }

    private fun buildError(albumError: Throwable?, folderError: Throwable?): String? {
        val albumMessage = albumError?.message?.takeIf { it.isNotBlank() }
        val folderMessage = folderError?.message?.takeIf { it.isNotBlank() }
        return when {
            albumMessage == null && folderMessage == null -> null
            albumMessage != null && folderMessage != null -> "$albumMessage / $folderMessage"
            else -> albumMessage ?: folderMessage
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
    val error: String? = null
)

private fun defaultCollectionCategories(): List<CollectionCategoryItem> {
    return listOf(
        CollectionCategoryItem(type = "favorites", title = "收藏", subtitle = "收藏相册"),
        CollectionCategoryItem(type = "recent", title = "最近添加", subtitle = "最近入库的媒体"),
        CollectionCategoryItem(type = "videos", title = "视频", subtitle = "全部视频"),
        CollectionCategoryItem(type = "trash", title = "回收站", subtitle = "已删除但未清空")
    )
}
