package com.kqstone.mtphotos.ui.viewer

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kqstone.mtphotos.data.local.db.SyncStatus
import com.kqstone.mtphotos.data.model.UnifiedPhotoItem
import com.kqstone.mtphotos.data.repository.GalleryRepository
import com.kqstone.mtphotos.data.repository.ServerOpTaskRepository
import com.kqstone.mtphotos.worker.BackupScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import com.kqstone.mtphotos.ui.util.ShareManager
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

data class ViewerUiState(
    val photos: List<UnifiedPhotoItem> = emptyList(),
    val currentIndex: Int = 0,
    val isFavorite: Boolean = false,
    val isLoadingDetails: Boolean = false,
    val exifInfo: Map<String, Any>? = null,
    val fileDetailInfo: Map<String, Any>? = null,
    val isDownloadingOriginal: Boolean = false,
    val downloadProgress: Float? = null,
    val originalDownloaded: Boolean = false,
    val resolvedVideoUrl: String? = null,
    val isPlayingTranscode: Boolean = false
)

class ViewerViewModel(
    private val galleryRepository: GalleryRepository,
    private val originalDownloadManager: com.kqstone.mtphotos.data.local.OriginalDownloadManager,
    private val serverOpTaskRepository: ServerOpTaskRepository,
    private val appContext: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(ViewerUiState())
    val uiState: StateFlow<ViewerUiState> = _uiState

    val shareManager = ShareManager(galleryRepository, viewModelScope)

    init {
        viewModelScope.launch {
            originalDownloadManager.downloadStates.collect { states ->
                val currentMd5 = getCurrentPhoto()?.md5 ?: return@collect
                if (states.containsKey(currentMd5)) {
                    syncDownloadState()
                }
            }
        }
    }

    private fun syncDownloadState() {
        val currentMd5 = getCurrentPhoto()?.md5 ?: return
        val state = originalDownloadManager.downloadStates.value[currentMd5]
        
        _uiState.update { stateUi ->
            val updatedPhotos = if (state?.isCompleted == true && state.localUri != null && !stateUi.originalDownloaded) {
                stateUi.photos.map { p ->
                    if (p.md5 == currentMd5) p.copy(
                        syncStatus = com.kqstone.mtphotos.data.local.db.SyncStatus.SYNCED,
                        localUri = state.localUri,
                        isStorageOptimized = false
                    ) else p
                }
            } else {
                stateUi.photos
            }

            stateUi.copy(
                isDownloadingOriginal = state?.isDownloading == true,
                downloadProgress = if (state?.isDownloading == true) state.progress else null,
                originalDownloaded = state?.isCompleted == true || stateUi.originalDownloaded,
                photos = updatedPhotos
            )
        }
    }

    fun setPhotos(photos: List<UnifiedPhotoItem>, initialIndex: Int) {
        val index = initialIndex.coerceIn(0, (photos.size - 1).coerceAtLeast(0))
        _uiState.value = ViewerUiState(
            photos = photos,
            currentIndex = index,
            isFavorite = photos.getOrNull(index)?.isFavorite ?: false
        )
        syncDownloadState()
        loadExifAndFavoriteForCurrent()
    }

    fun updateCurrentIndex(index: Int) {
        _uiState.update {
            val boundedIndex = index.coerceIn(0, (it.photos.size - 1).coerceAtLeast(0))
            it.copy(
                currentIndex = boundedIndex,
                isFavorite = it.photos.getOrNull(boundedIndex)?.isFavorite ?: false,
                exifInfo = null,
                fileDetailInfo = null,
                originalDownloaded = false,
                resolvedVideoUrl = null,
                isPlayingTranscode = false
            )
        }
        syncDownloadState()
        loadExifAndFavoriteForCurrent()
    }

    fun loadExifAndFavoriteForCurrent() {
        val photo = getCurrentPhoto() ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingDetails = true, exifInfo = null, fileDetailInfo = null) }
            
            // 并行/顺序查询收藏状态和 EXIF/详情
            val isFav = galleryRepository.getCachedFavoriteState(
                dbId = photo.dbId,
                cloudId = photo.cloudId,
                md5 = photo.md5
            ) ?: photo.isFavorite
            
            val exifResult = galleryRepository.getFileExifInfo(photo.id)
            val detailResult = galleryRepository.getFileDetail(photo.id, photo.md5)
            
            _uiState.update {
                it.copy(
                    isLoadingDetails = false,
                    isFavorite = isFav,
                    exifInfo = exifResult.getOrNull(),
                    fileDetailInfo = detailResult.getOrNull()
                )
            }
            if (photo.isPlayableMedia()) {
                resolveVideoUrl(photo)
            }
        }
    }

    private suspend fun resolveVideoUrl(photo: UnifiedPhotoItem) {
        if (!photo.isMotionPhoto() && photo.localUri?.isNotEmpty() == true && !photo.isStorageOptimized) {
            _uiState.update { it.copy(resolvedVideoUrl = photo.localUri, isPlayingTranscode = false) }
            return
        }
        val cloudId = photo.cloudId
        if (cloudId == null) {
            _uiState.update { it.copy(resolvedVideoUrl = "", isPlayingTranscode = false) }
            return
        }
        if (photo.isMotionPhoto()) {
            val url = galleryRepository.getMotionPhotoUrl(cloudId, photo.md5)
            _uiState.update { it.copy(resolvedVideoUrl = url, isPlayingTranscode = false) }
            return
        }
        
        withContext(Dispatchers.IO) {
            val transcodeUrl = galleryRepository.getTranscodeVideoUrl(cloudId, photo.md5)
            val request = Request.Builder().url(transcodeUrl).head().build()
            try {
                val client = OkHttpClient()
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    _uiState.update { it.copy(resolvedVideoUrl = transcodeUrl, isPlayingTranscode = true) }
                    return@withContext
                }
            } catch (e: Exception) {}
            
            val originalUrl = galleryRepository.getFullImageUrl(cloudId, photo.md5)
            _uiState.update { it.copy(resolvedVideoUrl = originalUrl, isPlayingTranscode = false) }
        }
    }

    fun toggleFavorite() {
        val photo = getCurrentPhoto() ?: return
        val currentFav = _uiState.value.isFavorite
        val newFav = !currentFav
        // 乐观更新 UI
        _uiState.update { state ->
            state.copy(
                isFavorite = newFav,
                photos = state.photos.mapIndexed { index, item ->
                    if (index == state.currentIndex) item.copy(isFavorite = newFav) else item
                }
            )
        }
        // 通过任务队列执行，保证有操作日志和重试机制
        viewModelScope.launch {
            serverOpTaskRepository.enqueueFavorite(
                cloudId = photo.cloudId,
                dbId = photo.dbId,
                isFavorite = newFav,
                fileName = photo.fileName,
                md5 = photo.md5
            )
            if (photo.cloudId != null) {
                BackupScheduler.triggerServerOpWork(appContext)
            }
        }
    }

    fun deleteCurrentPhoto(onDeleted: (hasRemainingPhotos: Boolean) -> Unit) {
        val photo = getCurrentPhoto() ?: return
        viewModelScope.launch {
            val result = galleryRepository.deletePhotos(listOf(photo))
            if (result.isSuccess) {
                val hasRemainingPhotos = removeDeletedPhoto(photo)
                if (hasRemainingPhotos) {
                    syncDownloadState()
                    loadExifAndFavoriteForCurrent()
                }
                onDeleted(hasRemainingPhotos)
            }
        }
    }

    private fun removeDeletedPhoto(photo: UnifiedPhotoItem): Boolean {
        var hasRemainingPhotos = false

        _uiState.update { state ->
            val deletedIndex = state.photos.indexOfFirst { it.uniqueKey == photo.uniqueKey }
                .takeIf { it >= 0 }
                ?: state.currentIndex.takeIf { state.photos.getOrNull(it)?.uniqueKey == photo.uniqueKey }
                ?: return@update state

            val updatedPhotos = state.photos.toMutableList().also { it.removeAt(deletedIndex) }
            hasRemainingPhotos = updatedPhotos.isNotEmpty()
            val updatedIndex = if (updatedPhotos.isEmpty()) {
                0
            } else {
                deletedIndex.coerceAtMost(updatedPhotos.lastIndex)
            }

            state.copy(
                photos = updatedPhotos,
                currentIndex = updatedIndex,
                isFavorite = updatedPhotos.getOrNull(updatedIndex)?.isFavorite ?: false,
                exifInfo = null,
                fileDetailInfo = null,
                originalDownloaded = false,
                resolvedVideoUrl = null,
                isPlayingTranscode = false
            )
        }

        return hasRemainingPhotos
    }

    fun sharePhoto(context: android.content.Context) {
        val photo = getCurrentPhoto() ?: return
        shareManager.sharePhotos(context, listOf(photo))
    }

    fun getFullImageUrl(photo: UnifiedPhotoItem): String {
        photo.localUri?.let { if (it.isNotEmpty() && !photo.isStorageOptimized) return it }
        val cloudId = photo.cloudId ?: return ""
        return galleryRepository.getFullImageUrl(cloudId, photo.md5)
    }

    fun getOriginalImageUrl(photo: UnifiedPhotoItem): String {
        val cloudId = photo.cloudId ?: return ""
        return galleryRepository.getOriginalImageUrl(cloudId, photo.md5)
    }

    fun getFileDownloadUrl(photo: UnifiedPhotoItem): String {
        val cloudId = photo.cloudId ?: return ""
        return galleryRepository.getFileDownloadUrl(cloudId, photo.md5)
    }

    fun downloadOriginal(context: android.content.Context) {
        val photo = getCurrentPhoto() ?: return
        if (_uiState.value.isDownloadingOriginal) return
        originalDownloadManager.startDownload(photo, _uiState.value.fileDetailInfo)
    }

    fun getVideoUrl(photo: UnifiedPhotoItem): String {
        if (!photo.isMotionPhoto()) {
            photo.localUri?.let { if (it.isNotEmpty() && !photo.isStorageOptimized) return it }
        }
        val cloudId = photo.cloudId ?: return ""
        return if (photo.isMotionPhoto()) {
            galleryRepository.getMotionPhotoUrl(cloudId, photo.md5)
        } else {
            galleryRepository.getTranscodeVideoUrl(cloudId, photo.md5)
        }
    }

    fun getFullImageUrl(id: Double, md5: String): String {
        return galleryRepository.getFullImageUrl(id, md5)
    }

    fun getVideoUrl(id: Double, md5: String): String {
        return galleryRepository.getFullImageUrl(id, md5)
    }

    fun getCurrentPhoto(): UnifiedPhotoItem? {
        val state = _uiState.value
        return state.photos.getOrNull(state.currentIndex)
    }

    class Factory(
        private val galleryRepository: GalleryRepository,
        private val originalDownloadManager: com.kqstone.mtphotos.data.local.OriginalDownloadManager,
        private val serverOpTaskRepository: ServerOpTaskRepository,
        private val appContext: Context
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ViewerViewModel(galleryRepository, originalDownloadManager, serverOpTaskRepository, appContext) as T
        }
    }
}
