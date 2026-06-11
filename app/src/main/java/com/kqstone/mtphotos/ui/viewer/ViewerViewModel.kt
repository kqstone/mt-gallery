package com.kqstone.mtphotos.ui.viewer

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kqstone.mtphotos.data.local.db.SyncStatus
import com.kqstone.mtphotos.data.repository.OcrInfoItem
import com.kqstone.mtphotos.data.repository.PeopleDescriptorItem
import com.kqstone.mtphotos.data.model.UnifiedPhotoItem
import com.kqstone.mtphotos.data.repository.GalleryRepository
import com.kqstone.mtphotos.data.repository.MediaUiMutation
import com.kqstone.mtphotos.data.repository.MediaUiMutationBus
import com.kqstone.mtphotos.data.repository.ServerOpTaskRepository
import com.kqstone.mtphotos.ui.gallery.removePhotos
import com.kqstone.mtphotos.ui.gallery.updateFavorite
import com.kqstone.mtphotos.ui.gallery.updateHide
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
    val isHide: Boolean = false,
    val isLoadingDetails: Boolean = false,
    val exifInfo: Map<String, Any>? = null,
    val fileDetailInfo: Map<String, Any>? = null,
    val isDownloadingOriginal: Boolean = false,
    val downloadProgress: Float? = null,
    val originalDownloaded: Boolean = false,
    val resolvedVideoUrl: String? = null,
    val isPlayingTranscode: Boolean = false,
    val streamMediaUrl: String? = null,
    val isResolvingStreamUrl: Boolean = false,
    val isPlayingStreamUrl: Boolean = false,
    val streamFailureCount: Int = 0,
    val castDevices: List<DlnaCastDevice> = emptyList(),
    val isDiscoveringCastDevices: Boolean = false,
    val isCastingToDevice: Boolean = false,
    val castFailureCount: Int = 0,
    val castSuccessCount: Int = 0,
    val peopleDescriptors: List<PeopleDescriptorItem> = emptyList(),
    val isPeopleInfoVisible: Boolean = false,
    val isLoadingPeopleDescriptors: Boolean = false,
    val peopleDescriptorFailureCount: Int = 0,
    val noPeopleDetectedCount: Int = 0,
    val ocrItems: List<OcrInfoItem> = emptyList(),
    val isOcrInfoVisible: Boolean = false,
    val isLoadingOcrInfo: Boolean = false,
    val ocrInfoFailureCount: Int = 0
)

class ViewerViewModel(
    private val galleryRepository: GalleryRepository,
    private val originalDownloadManager: com.kqstone.mtphotos.data.local.OriginalDownloadManager,
    private val serverOpTaskRepository: ServerOpTaskRepository,
    private val appContext: Context,
    private val mediaUiMutationBus: MediaUiMutationBus? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(ViewerUiState())
    val uiState: StateFlow<ViewerUiState> = _uiState

    val shareManager = ShareManager(galleryRepository, viewModelScope)
    private val dlnaCastClient = DlnaCastClient(appContext)

    init {
        observeMediaUiMutations()
        viewModelScope.launch {
            originalDownloadManager.downloadStates.collect { states ->
                val currentMd5 = getCurrentPhoto()?.md5 ?: return@collect
                if (states.containsKey(currentMd5)) {
                    syncDownloadState()
                }
            }
        }
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
            is MediaUiMutation.Deleted -> {
                val beforeKey = getCurrentPhoto()?.uniqueKey
                val hasRemaining = removePhotosFromViewer(mutation.photos)
                if (hasRemaining && beforeKey != getCurrentPhoto()?.uniqueKey) {
                    syncDownloadState()
                    loadExifAndFavoriteForCurrent()
                }
            }
            is MediaUiMutation.FavoriteChanged -> {
                _uiState.update { state ->
                    val updatedPhotos = state.photos.updateFavorite(
                        mutation.photos,
                        mutation.isFavorite
                    )
                    state.copy(
                        photos = updatedPhotos,
                        isFavorite = updatedPhotos.getOrNull(state.currentIndex)?.isFavorite
                            ?: state.isFavorite
                    )
                }
            }
            is MediaUiMutation.HideChanged -> {
                _uiState.update { state ->
                    val updatedPhotos = state.photos.updateHide(mutation.photos, mutation.isHide)
                    state.copy(
                        photos = updatedPhotos,
                        isHide = updatedPhotos.getOrNull(state.currentIndex)?.isHide
                            ?: state.isHide
                    )
                }
            }
            is MediaUiMutation.PersonRenamed -> Unit
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
            isFavorite = photos.getOrNull(index)?.isFavorite ?: false,
            isHide = photos.getOrNull(index)?.isHide ?: false
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
                isHide = it.photos.getOrNull(boundedIndex)?.isHide ?: false,
                exifInfo = null,
                fileDetailInfo = null,
                originalDownloaded = false,
                resolvedVideoUrl = null,
                isPlayingTranscode = false,
                streamMediaUrl = null,
                isResolvingStreamUrl = false,
                isPlayingStreamUrl = false,
                castDevices = emptyList(),
                isDiscoveringCastDevices = false,
                isCastingToDevice = false,
                peopleDescriptors = emptyList(),
                isPeopleInfoVisible = false,
                isLoadingPeopleDescriptors = false,
                ocrItems = emptyList(),
                isOcrInfoVisible = false,
                isLoadingOcrInfo = false
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
            updateResolvedVideoUrl(photo, photo.localUri, isPlayingTranscode = false)
            return
        }
        val cloudId = photo.cloudId
        if (cloudId == null) {
            updateResolvedVideoUrl(photo, "", isPlayingTranscode = false)
            return
        }
        galleryRepository.ensureAuthCode()
        if (photo.isMotionPhoto()) {
            val url = galleryRepository.getMotionPhotoUrl(cloudId, photo.md5)
            updateResolvedVideoUrl(photo, url, isPlayingTranscode = false)
            return
        }
        
        withContext(Dispatchers.IO) {
            val transcodeUrl = galleryRepository.getTranscodeVideoUrl(cloudId, photo.md5)
            val request = Request.Builder().url(transcodeUrl).head().build()
            try {
                val client = OkHttpClient()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        updateResolvedVideoUrl(photo, transcodeUrl, isPlayingTranscode = true)
                        return@withContext
                    }
                }
            } catch (e: Exception) {}
            
            val originalUrl = galleryRepository.getFullImageUrl(cloudId, photo.md5)
            updateResolvedVideoUrl(photo, originalUrl, isPlayingTranscode = false)
        }
    }

    private fun updateResolvedVideoUrl(
        photo: UnifiedPhotoItem,
        url: String?,
        isPlayingTranscode: Boolean
    ) {
        _uiState.update { state ->
            val currentPhoto = state.photos.getOrNull(state.currentIndex)
            if (currentPhoto?.uniqueKey != photo.uniqueKey || state.isPlayingStreamUrl) {
                state
            } else {
                state.copy(
                    resolvedVideoUrl = url,
                    isPlayingTranscode = isPlayingTranscode
                )
            }
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

    fun toggleHide() {
        val photo = getCurrentPhoto() ?: return
        val newHide = !_uiState.value.isHide
        _uiState.update { state ->
            state.copy(
                isHide = newHide,
                photos = state.photos.mapIndexed { index, item ->
                    if (index == state.currentIndex) item.copy(isHide = newHide) else item
                }
            )
        }
        viewModelScope.launch {
            serverOpTaskRepository.enqueueHides(listOf(photo), isHide = newHide)
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
                ?: run {
                    hasRemainingPhotos = state.photos.isNotEmpty()
                    return@update state
                }

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
                isHide = updatedPhotos.getOrNull(updatedIndex)?.isHide ?: false,
                exifInfo = null,
                fileDetailInfo = null,
                originalDownloaded = false,
                resolvedVideoUrl = null,
                isPlayingTranscode = false,
                streamMediaUrl = null,
                isResolvingStreamUrl = false,
                isPlayingStreamUrl = false,
                castDevices = emptyList(),
                isDiscoveringCastDevices = false,
                isCastingToDevice = false,
                peopleDescriptors = emptyList(),
                isPeopleInfoVisible = false,
                isLoadingPeopleDescriptors = false,
                ocrItems = emptyList(),
                isOcrInfoVisible = false,
                isLoadingOcrInfo = false
            )
        }

        return hasRemainingPhotos
    }

    private fun removePhotosFromViewer(photos: List<UnifiedPhotoItem>): Boolean {
        var hasRemainingPhotos = false
        _uiState.update { state ->
            val currentPhoto = state.photos.getOrNull(state.currentIndex)
            val updatedPhotos = state.photos.removePhotos(photos)
            if (updatedPhotos.size == state.photos.size) {
                hasRemainingPhotos = state.photos.isNotEmpty()
                return@update state
            }

            hasRemainingPhotos = updatedPhotos.isNotEmpty()
            val updatedIndex = if (updatedPhotos.isEmpty()) {
                0
            } else {
                currentPhoto
                    ?.let { current ->
                        updatedPhotos.indexOfFirst { it.uniqueKey == current.uniqueKey }
                    }
                    ?.takeIf { it >= 0 }
                    ?: state.currentIndex.coerceAtMost(updatedPhotos.lastIndex)
            }

            state.copy(
                photos = updatedPhotos,
                currentIndex = updatedIndex,
                isFavorite = updatedPhotos.getOrNull(updatedIndex)?.isFavorite ?: false,
                isHide = updatedPhotos.getOrNull(updatedIndex)?.isHide ?: false,
                exifInfo = null,
                fileDetailInfo = null,
                originalDownloaded = false,
                resolvedVideoUrl = null,
                isPlayingTranscode = false,
                streamMediaUrl = null,
                isResolvingStreamUrl = false,
                isPlayingStreamUrl = false,
                castDevices = emptyList(),
                isDiscoveringCastDevices = false,
                isCastingToDevice = false,
                peopleDescriptors = emptyList(),
                isPeopleInfoVisible = false,
                isLoadingPeopleDescriptors = false,
                ocrItems = emptyList(),
                isOcrInfoVisible = false,
                isLoadingOcrInfo = false
            )
        }
        return hasRemainingPhotos
    }

    fun togglePeopleInfo() {
        val photo = getCurrentPhoto() ?: return
        val fileId = photo.cloudId ?: return
        val state = _uiState.value
        if (state.isPeopleInfoVisible) {
            hidePeopleInfo()
            return
        }
        if (state.peopleDescriptors.isNotEmpty()) {
            _uiState.update { it.copy(isPeopleInfoVisible = true) }
            return
        }
        if (state.isLoadingPeopleDescriptors) return

        _uiState.update {
            it.copy(
                isLoadingPeopleDescriptors = true,
                isPeopleInfoVisible = false,
                isOcrInfoVisible = false,
                isLoadingOcrInfo = false
            )
        }

        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                galleryRepository.getPeopleDescriptorsOfFile(fileId)
            }
            _uiState.update { current ->
                val currentPhoto = current.photos.getOrNull(current.currentIndex)
                if (currentPhoto?.uniqueKey != photo.uniqueKey) {
                    current
                } else {
                    result.fold(
                        onSuccess = { descriptors ->
                            if (descriptors.isEmpty()) {
                                current.copy(
                                    isPeopleInfoVisible = false,
                                    isLoadingPeopleDescriptors = false,
                                    noPeopleDetectedCount = current.noPeopleDetectedCount + 1
                                )
                            } else {
                                current.copy(
                                    peopleDescriptors = descriptors,
                                    isPeopleInfoVisible = true,
                                    isLoadingPeopleDescriptors = false
                                )
                            }
                        },
                        onFailure = {
                            current.copy(
                                isPeopleInfoVisible = false,
                                isLoadingPeopleDescriptors = false,
                                peopleDescriptorFailureCount = current.peopleDescriptorFailureCount + 1
                            )
                        }
                    )
                }
            }
        }
    }

    fun hidePeopleInfo() {
        _uiState.update {
            it.copy(
                isPeopleInfoVisible = false,
                isLoadingPeopleDescriptors = false
            )
        }
    }

    fun toggleOcrInfo() {
        val photo = getCurrentPhoto() ?: return
        if (photo.isPlayableMedia()) return
        val fileId = photo.cloudId ?: return
        val state = _uiState.value
        if (state.isOcrInfoVisible) {
            hideOcrInfo()
            return
        }
        if (state.ocrItems.isNotEmpty()) {
            _uiState.update {
                it.copy(
                    isOcrInfoVisible = true,
                    isPeopleInfoVisible = false,
                    isLoadingPeopleDescriptors = false
                )
            }
            return
        }
        if (state.isLoadingOcrInfo) return

        _uiState.update {
            it.copy(
                isLoadingOcrInfo = true,
                isOcrInfoVisible = false,
                isPeopleInfoVisible = false,
                isLoadingPeopleDescriptors = false
            )
        }

        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                galleryRepository.getOcrInfoOfFile(fileId)
            }
            _uiState.update { current ->
                val currentPhoto = current.photos.getOrNull(current.currentIndex)
                if (currentPhoto?.uniqueKey != photo.uniqueKey) {
                    current
                } else {
                    result.fold(
                        onSuccess = { items ->
                            current.copy(
                                ocrItems = items,
                                isOcrInfoVisible = true,
                                isLoadingOcrInfo = false
                            )
                        },
                        onFailure = {
                            current.copy(
                                isOcrInfoVisible = false,
                                isLoadingOcrInfo = false,
                                ocrInfoFailureCount = current.ocrInfoFailureCount + 1
                            )
                        }
                    )
                }
            }
        }
    }

    fun hideOcrInfo() {
        _uiState.update {
            it.copy(
                isOcrInfoVisible = false,
                isLoadingOcrInfo = false
            )
        }
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

    fun startCastDeviceDiscovery() {
        if (_uiState.value.isDiscoveringCastDevices) return

        _uiState.update {
            it.copy(
                castDevices = emptyList(),
                isDiscoveringCastDevices = true
            )
        }

        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { dlnaCastClient.discover() }
            }
            _uiState.update { state ->
                state.copy(
                    castDevices = result.getOrElse { emptyList() },
                    isDiscoveringCastDevices = false,
                    castFailureCount = if (result.isFailure) state.castFailureCount + 1 else state.castFailureCount
                )
            }
        }
    }

    fun castCurrentMedia(device: DlnaCastDevice) {
        val photo = getCurrentPhoto() ?: return
        val cloudId = photo.cloudId ?: run {
            markCastFailed()
            return
        }
        if (_uiState.value.isCastingToDevice) return

        _uiState.update { it.copy(isCastingToDevice = true) }

        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val streamUrl = galleryRepository.getFileStreamUrl(cloudId).getOrThrow()
                    dlnaCastClient.play(device, streamUrl)
                }
            }
            result.fold(
                onSuccess = {
                    _uiState.update { state ->
                        state.copy(
                            isCastingToDevice = false,
                            castSuccessCount = state.castSuccessCount + 1
                        )
                    }
                },
                onFailure = {
                    markCastFailed(photo)
                }
            )
        }
    }

    private fun markCastFailed(photo: UnifiedPhotoItem? = null) {
        _uiState.update { state ->
            val currentPhoto = state.photos.getOrNull(state.currentIndex)
            if (photo != null && currentPhoto?.uniqueKey != photo.uniqueKey) {
                state.copy(isCastingToDevice = false)
            } else {
                state.copy(
                    isCastingToDevice = false,
                    castFailureCount = state.castFailureCount + 1
                )
            }
        }
    }

    fun streamCurrentMedia() {
        val photo = getCurrentPhoto() ?: return
        val cloudId = photo.cloudId ?: run {
            markStreamFailed()
            return
        }
        if (_uiState.value.isResolvingStreamUrl) return

        _uiState.update {
            it.copy(
                isResolvingStreamUrl = true,
                isPlayingStreamUrl = false,
                streamMediaUrl = null
            )
        }

        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                galleryRepository.getFileStreamUrl(cloudId)
            }
            result.fold(
                onSuccess = { url ->
                    _uiState.update { state ->
                        val currentPhoto = state.photos.getOrNull(state.currentIndex)
                        if (currentPhoto?.uniqueKey != photo.uniqueKey) {
                            state
                        } else if (photo.isPlayableMedia()) {
                            state.copy(
                                isResolvingStreamUrl = false,
                                isPlayingStreamUrl = true,
                                streamMediaUrl = url,
                                resolvedVideoUrl = url,
                                isPlayingTranscode = false
                            )
                        } else {
                            state.copy(
                                isResolvingStreamUrl = false,
                                isPlayingStreamUrl = true,
                                streamMediaUrl = url
                            )
                        }
                    }
                },
                onFailure = {
                    markStreamFailed(photo)
                }
            )
        }
    }

    private fun markStreamFailed(photo: UnifiedPhotoItem? = null) {
        _uiState.update { state ->
            val currentPhoto = state.photos.getOrNull(state.currentIndex)
            if (photo != null && currentPhoto?.uniqueKey != photo.uniqueKey) {
                state
            } else {
                state.copy(
                    isResolvingStreamUrl = false,
                    streamFailureCount = state.streamFailureCount + 1
                )
            }
        }
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

    fun getPeoplePortraitUrl(descriptor: PeopleDescriptorItem): String? {
        val personId = descriptor.person.id.takeIf { it > 0.0 } ?: return null
        val cover = descriptor.person.cover.takeIf { it > 0.0 } ?: return null
        val personIdPath = if (personId % 1.0 == 0.0) personId.toLong().toString() else personId.toString()
        return galleryRepository.getPortraitUrl(personIdPath, cover)
    }

    fun getCurrentPhoto(): UnifiedPhotoItem? {
        val state = _uiState.value
        return state.photos.getOrNull(state.currentIndex)
    }

    class Factory(
        private val galleryRepository: GalleryRepository,
        private val originalDownloadManager: com.kqstone.mtphotos.data.local.OriginalDownloadManager,
        private val serverOpTaskRepository: ServerOpTaskRepository,
        private val appContext: Context,
        private val mediaUiMutationBus: MediaUiMutationBus? = null
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ViewerViewModel(
                galleryRepository,
                originalDownloadManager,
                serverOpTaskRepository,
                appContext,
                mediaUiMutationBus
            ) as T
        }
    }
}
