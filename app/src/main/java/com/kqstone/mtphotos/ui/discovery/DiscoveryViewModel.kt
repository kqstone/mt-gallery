package com.kqstone.mtphotos.ui.discovery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kqstone.mtphotos.data.repository.GalleryRepository
import com.kqstone.mtphotos.data.repository.MediaUiMutation
import com.kqstone.mtphotos.data.repository.MediaUiMutationBus
import com.kqstone.mtphotos.data.repository.LocationItem
import com.kqstone.mtphotos.data.repository.PersonItem
import com.kqstone.mtphotos.data.repository.SceneItem
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

data class DiscoveryUiState(
    val people: List<PersonItem> = emptyList(),
    val scenes: List<SceneItem> = emptyList(),
    val locations: List<LocationItem> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: UiText? = null,
    val toastMessage: UiText? = null
)

class DiscoveryViewModel(
    private val galleryRepository: GalleryRepository,
    private val mediaUiMutationBus: MediaUiMutationBus? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(DiscoveryUiState())
    val uiState: StateFlow<DiscoveryUiState> = _uiState
    private var refreshJob: Job? = null

    init {
        observeMediaUiMutations()
        loadAll()
    }

    private fun observeMediaUiMutations() {
        val bus = mediaUiMutationBus ?: return
        viewModelScope.launch {
            bus.mutations.collect { mutation ->
                if (mutation is MediaUiMutation.PersonRenamed) {
                    updatePersonName(mutation.personId, mutation.newName)
                }
            }
        }
    }

    fun loadAll() {
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
            try {
                val (people, scenes, locations) = coroutineScope {
                    val peopleDeferred = async { galleryRepository.getPeopleList() }
                    val scenesDeferred = async { galleryRepository.getClassifyTopList() }
                    val locationsDeferred = async { galleryRepository.getAddressCountByCity() }
                    Triple(
                        peopleDeferred.await(),
                        scenesDeferred.await(),
                        locationsDeferred.await()
                    )
                }
                _uiState.value = DiscoveryUiState(
                    people = people.getOrNull() ?: emptyList(),
                    scenes = scenes.getOrNull() ?: emptyList(),
                    locations = locations.getOrNull() ?: emptyList()
                )
            } catch (e: Exception) {
                _uiState.value = DiscoveryUiState(
                    error = e.message?.let { UiText.DynamicString(it) } 
                        ?: UiText.StringResource(R.string.load_failed)
                )
            }
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
                val (people, scenes, locations) = coroutineScope {
                    val peopleDeferred = async { galleryRepository.getPeopleList() }
                    val scenesDeferred = async { galleryRepository.getClassifyTopList() }
                    val locationsDeferred = async { galleryRepository.getAddressCountByCity() }
                    Triple(
                        peopleDeferred.await(),
                        scenesDeferred.await(),
                        locationsDeferred.await()
                    )
                }
                resultMessage = buildError(
                    people.exceptionOrNull(),
                    scenes.exceptionOrNull(),
                    locations.exceptionOrNull()
                )
                val nextState = DiscoveryUiState(
                    people = people.getOrNull() ?: emptyList(),
                    scenes = scenes.getOrNull() ?: emptyList(),
                    locations = locations.getOrNull() ?: emptyList(),
                    error = null
                )
                if (nextState.hasContent || resultMessage == null) {
                    _uiState.value = nextState
                } else if (!_uiState.value.hasContent) {
                    _uiState.value = _uiState.value.copy(error = resultMessage)
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

    fun updatePersonName(personId: String, newName: String) {
        _uiState.value = _uiState.value.copy(
            people = _uiState.value.people.map { person ->
                if (person.id == personId) {
                    person.copy(name = newName)
                } else {
                    person
                }
            }
        )
    }

    fun getThumbUrl(md5: String, fileId: Double): String {
        return MediaThumbnailResolver.resolveCloudThumb(md5, fileId, galleryRepository)
    }

    fun getThumbUrlByMd5(md5: String): String {
        return MediaThumbnailResolver.resolveCloudThumbByMd5(md5, galleryRepository)
    }

    fun getPortraitUrl(personId: String, cover: Double): String {
        return galleryRepository.getPortraitUrl(personId, cover)
    }

    class Factory(
        private val galleryRepository: GalleryRepository,
        private val mediaUiMutationBus: MediaUiMutationBus? = null
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return DiscoveryViewModel(galleryRepository, mediaUiMutationBus) as T
        }
    }
}

private fun buildError(vararg errors: Throwable?): UiText? {
    val actualErrors = errors.filterNotNull()
    if (actualErrors.isNotEmpty()) {
        return PullRefreshSupport.toRefreshMessage(actualErrors.first())
    }
    val messages = errors.mapNotNull { it?.message?.takeIf(String::isNotBlank) }
    return when {
        messages.isEmpty() -> null
        messages.size == 1 -> UiText.DynamicString(messages.first())
        else -> UiText.DynamicString(messages.joinToString(" / "))
    }
}

private val DiscoveryUiState.hasContent: Boolean
    get() = people.isNotEmpty() || scenes.isNotEmpty() || locations.isNotEmpty()
