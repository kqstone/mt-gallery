package com.kqstone.mtphotos.ui.map

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kqstone.mtphotos.data.local.db.SyncStatus
import com.kqstone.mtphotos.data.model.UnifiedPhotoItem
import com.kqstone.mtphotos.data.repository.GalleryRepository
import com.kqstone.mtphotos.data.repository.MapPhotoItem
import com.kqstone.mtphotos.data.repository.SyncRepository
import com.kqstone.mtphotos.data.repository.toUnifiedPhotoItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class MapCluster(
    val lat: Double,
    val lng: Double,
    val photos: List<MapPhotoItem>,
    val coverMd5: String
) {
    val count: Int get() = photos.size
}

data class MapUiState(
    val isLoading: Boolean = true,
    val allPhotos: List<MapPhotoItem> = emptyList(),
    val clusters: List<MapCluster> = emptyList(),
    val error: String? = null,
    val selectedCluster: MapCluster? = null,
    val selectedClusterPhotos: List<UnifiedPhotoItem> = emptyList(),
    val isResolvingSelectedCluster: Boolean = false,
    val cameraState: MapCameraState? = null
)

data class MapCameraState(
    val lat: Double,
    val lng: Double,
    val zoom: Float,
    val tilt: Float,
    val bearing: Float
)

class MapViewModel(
    private val galleryRepository: GalleryRepository,
    private val syncRepository: SyncRepository? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(MapUiState())
    val uiState: StateFlow<MapUiState> = _uiState

    init {
        loadMapPhotos()
    }

    fun loadMapPhotos() {
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
            val result = galleryRepository.getAllFilesForMap()
            result.onSuccess { photos ->
                val clusters = clusterPhotos(photos, DEFAULT_ZOOM_LEVEL)
                Log.d("MapViewModel", "loadMapPhotos photos=${photos.size}, clusters=${clusters.size}")
                _uiState.value = MapUiState(
                    isLoading = false,
                    allPhotos = photos,
                    clusters = clusters
                )
            }.onFailure { error ->
                Log.e("MapViewModel", "loadMapPhotos failed", error)
                _uiState.value = MapUiState(
                    isLoading = false,
                    error = error.message ?: "加载失败"
                )
            }
        }
    }

    fun onZoomChanged(zoomLevel: Float) {
        val photos = _uiState.value.allPhotos
        if (photos.isEmpty()) return
        _uiState.value = _uiState.value.copy(
            clusters = clusterPhotos(photos, zoomLevel)
        )
    }

    fun getThumbUrl(md5: String, fileId: Double): String {
        return galleryRepository.getThumbUrl(md5, fileId)
    }

    fun updateCameraState(
        lat: Double,
        lng: Double,
        zoom: Float,
        tilt: Float,
        bearing: Float
    ) {
        val newState = MapCameraState(
            lat = lat,
            lng = lng,
            zoom = zoom,
            tilt = tilt,
            bearing = bearing
        )
        if (_uiState.value.cameraState != newState) {
            _uiState.value = _uiState.value.copy(cameraState = newState)
        }
    }

    fun selectCluster(cluster: MapCluster?) {
        if (cluster == null) {
            _uiState.value = _uiState.value.copy(
                selectedCluster = null,
                selectedClusterPhotos = emptyList(),
                isResolvingSelectedCluster = false
            )
            return
        }

        _uiState.value = _uiState.value.copy(
            selectedCluster = cluster,
            selectedClusterPhotos = emptyList(),
            isResolvingSelectedCluster = true
        )

        viewModelScope.launch {
            val resolvedPhotos = resolveClusterPhotos(cluster.photos)
            val currentState = _uiState.value
            if (currentState.selectedCluster == cluster) {
                _uiState.value = currentState.copy(
                    selectedClusterPhotos = resolvedPhotos,
                    isResolvingSelectedCluster = false
                )
            }
        }
    }

    private suspend fun resolveClusterPhotos(photos: List<MapPhotoItem>): List<UnifiedPhotoItem> {
        if (photos.isEmpty()) return emptyList()

        val entitiesByCloudId = syncRepository
            ?.findMediaEntitiesByIds(photos.map { it.id })
            ?.associateBy { it.cloudId }
            .orEmpty()

        return photos.map { photo ->
            entitiesByCloudId[photo.id]?.toUnifiedPhotoItem() ?: photo.toUnifiedPhotoItem()
        }
    }

    private fun clusterPhotos(photos: List<MapPhotoItem>, zoomLevel: Float): List<MapCluster> {
        if (photos.isEmpty()) return emptyList()

        val gridSize = when {
            zoomLevel >= 18 -> 0.0005
            zoomLevel >= 16 -> 0.002
            zoomLevel >= 14 -> 0.008
            zoomLevel >= 12 -> 0.03
            zoomLevel >= 10 -> 0.1
            zoomLevel >= 8 -> 0.4
            zoomLevel >= 6 -> 1.5
            zoomLevel >= 4 -> 5.0
            else -> 15.0
        }

        val grid = mutableMapOf<Pair<Int, Int>, MutableList<MapPhotoItem>>()
        for (photo in photos) {
            val gridX = (photo.lng / gridSize).toInt()
            val gridY = (photo.lat / gridSize).toInt()
            val key = gridX to gridY
            grid.getOrPut(key) { mutableListOf() }.add(photo)
        }

        return grid.values.map { group ->
            val centerLat = group.sumOf { it.lat } / group.size
            val centerLng = group.sumOf { it.lng } / group.size
            MapCluster(
                lat = centerLat,
                lng = centerLng,
                photos = group,
                coverMd5 = group.first().md5
            )
        }
    }

    class Factory(
        private val galleryRepository: GalleryRepository,
        private val syncRepository: SyncRepository? = null
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MapViewModel(galleryRepository, syncRepository) as T
        }
    }

    companion object {
        const val DEFAULT_ZOOM_LEVEL = 5f
    }
}

private fun MapPhotoItem.toUnifiedPhotoItem(): UnifiedPhotoItem {
    return UnifiedPhotoItem(
        cloudId = id,
        md5 = md5,
        fileName = fileName,
        fileType = fileType,
        mtime = mtime,
        width = 0.0,
        height = 0.0,
        syncStatus = SyncStatus.CLOUD_ONLY
    )
}
