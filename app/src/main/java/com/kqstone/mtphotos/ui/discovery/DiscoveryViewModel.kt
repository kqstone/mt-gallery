package com.kqstone.mtphotos.ui.discovery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kqstone.mtphotos.data.repository.GalleryRepository
import com.kqstone.mtphotos.data.repository.LocationItem
import com.kqstone.mtphotos.data.repository.PersonItem
import com.kqstone.mtphotos.data.repository.SceneItem
import com.kqstone.mtphotos.R
import com.kqstone.mtphotos.ui.util.UiText
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
    val error: UiText? = null
)

class DiscoveryViewModel(private val galleryRepository: GalleryRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(DiscoveryUiState())
    val uiState: StateFlow<DiscoveryUiState> = _uiState

    init {
        loadAll()
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
        _uiState.value = _uiState.value.copy(isRefreshing = true)
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
                _uiState.value = _uiState.value.copy(
                    isRefreshing = false,
                    error = e.message?.let { UiText.DynamicString(it) }
                        ?: UiText.StringResource(R.string.load_failed)
                )
            }
        }
    }

    fun getThumbUrl(md5: String, fileId: Double): String {
        return galleryRepository.getThumbUrl(md5, fileId)
    }

    fun getThumbUrlByMd5(md5: String): String {
        return galleryRepository.getThumbUrlByMd5(md5)
    }

    fun getPortraitUrl(personId: String, cover: Double): String {
        return galleryRepository.getPortraitUrl(personId, cover)
    }

    class Factory(private val galleryRepository: GalleryRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return DiscoveryViewModel(galleryRepository) as T
        }
    }
}
