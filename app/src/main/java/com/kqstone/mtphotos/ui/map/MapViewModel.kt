package com.kqstone.mtphotos.ui.map

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kqstone.mtphotos.R
import com.kqstone.mtphotos.ui.util.UiText
import com.kqstone.mtphotos.data.local.db.SyncStatus
import com.kqstone.mtphotos.data.model.UnifiedPhotoItem
import com.kqstone.mtphotos.data.repository.GalleryRepository
import com.kqstone.mtphotos.data.repository.MapPhotoItem
import com.kqstone.mtphotos.data.repository.MediaUiMutation
import com.kqstone.mtphotos.data.repository.MediaUiMutationBus
import com.kqstone.mtphotos.data.repository.SyncRepository
import com.kqstone.mtphotos.data.repository.toUnifiedPhotoItem
import com.kqstone.mtphotos.ui.gallery.removePhotos
import com.kqstone.mtphotos.ui.media.MediaThumbnailResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.floor

data class MapCluster(
    val key: String,
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
    val error: UiText? = null,
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

data class MapViewport(
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double
)

class MapViewModel(
    private val galleryRepository: GalleryRepository,
    private val syncRepository: SyncRepository? = null,
    private val mediaUiMutationBus: MediaUiMutationBus? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(MapUiState())
    val uiState: StateFlow<MapUiState> = _uiState

    private var loadJob: Job? = null
    private var cameraIdleJob: Job? = null
    private var currentZoomLevel = DEFAULT_ZOOM_LEVEL
    private var currentViewport: MapViewport? = null
    private val clusterCache = object : LinkedHashMap<Int, List<MapCluster>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, List<MapCluster>>): Boolean {
            return size > MAX_CLUSTER_CACHE_BUCKETS
        }
    }

    init {
        observeMediaUiMutations()
        loadMapPhotos()
    }

    private fun observeMediaUiMutations() {
        val bus = mediaUiMutationBus ?: return
        viewModelScope.launch {
            bus.mutations.collect { mutation ->
                when (mutation) {
                    is MediaUiMutation.Deleted -> removeMapPhotos(mutation.photos)
                    is MediaUiMutation.HideChanged -> {
                        if (mutation.isHide) removeMapPhotos(mutation.photos)
                    }
                    is MediaUiMutation.FavoriteChanged,
                    is MediaUiMutation.PersonRenamed -> Unit
                    else -> Unit
                }
            }
        }
    }

    fun loadMapPhotos() {
        loadJob?.cancel()
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        loadJob = viewModelScope.launch {
            val cachedPhotos = galleryRepository.getCachedMapPhotos()
            if (cachedPhotos.isNotEmpty()) {
                publishPhotos(cachedPhotos, isLoading = false)
            }

            val result = galleryRepository.getAllFilesForMap()
            result.onSuccess { photos ->
                publishPhotos(photos, isLoading = false)
                Log.d("MapViewModel", "loadMapPhotos photos=${photos.size}, clusters=${_uiState.value.clusters.size}")
            }.onFailure { error ->
                Log.e("MapViewModel", "loadMapPhotos failed", error)
                val current = _uiState.value
                _uiState.value = current.copy(
                    isLoading = false,
                    error = if (current.allPhotos.isEmpty()) {
                        val msg = error.message
                        if (msg.isNullOrBlank()) {
                            UiText.StringResource(R.string.load_failed)
                        } else {
                            UiText.DynamicString(msg)
                        }
                    } else null
                )
            }
        }
    }

    fun onZoomChanged(zoomLevel: Float) {
        onCameraIdle(
            lat = _uiState.value.cameraState?.lat ?: 35.0,
            lng = _uiState.value.cameraState?.lng ?: 105.0,
            zoom = zoomLevel,
            tilt = _uiState.value.cameraState?.tilt ?: 0f,
            bearing = _uiState.value.cameraState?.bearing ?: 0f,
            viewport = currentViewport
        )
    }

    fun onCameraIdle(
        lat: Double,
        lng: Double,
        zoom: Float,
        tilt: Float,
        bearing: Float,
        viewport: MapViewport?
    ) {
        updateCameraState(lat, lng, zoom, tilt, bearing)
        currentZoomLevel = zoom
        currentViewport = viewport

        val photos = _uiState.value.allPhotos
        if (photos.isEmpty()) return

        cameraIdleJob?.cancel()
        cameraIdleJob = viewModelScope.launch {
            delay(CAMERA_IDLE_DEBOUNCE_MS)
            _uiState.value = _uiState.value.copy(
                clusters = clustersFor(photos, currentZoomLevel, currentViewport)
            )
        }
    }

    fun getThumbUrl(md5: String, fileId: Double): String {
        return MediaThumbnailResolver.resolveCloudThumb(md5, fileId, galleryRepository)
    }

    fun getThumbUrl(photo: UnifiedPhotoItem): String {
        return MediaThumbnailResolver.resolveTimelineThumb(photo, galleryRepository)
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
            if (currentState.selectedCluster?.key == cluster.key) {
                _uiState.value = currentState.copy(
                    selectedClusterPhotos = resolvedPhotos,
                    isResolvingSelectedCluster = false
                )
            }
        }
    }

    private suspend fun resolveClusterPhotos(photos: List<MapPhotoItem>): List<UnifiedPhotoItem> {
        if (photos.isEmpty()) return emptyList()

        val entitiesByCloudId = withContext(Dispatchers.IO) {
            syncRepository
                ?.findMediaEntitiesByIds(photos.map { it.id })
                ?.associateBy { it.cloudId }
                .orEmpty()
        }

        return withContext(Dispatchers.Default) {
            photos.map { photo ->
                entitiesByCloudId[photo.id]?.toUnifiedPhotoItem() ?: photo.toUnifiedPhotoItem()
            }
        }
    }

    private suspend fun publishPhotos(photos: List<MapPhotoItem>, isLoading: Boolean) {
        synchronized(clusterCache) {
            clusterCache.clear()
        }
        val clusters = clustersFor(photos, currentZoomLevel, currentViewport)
        _uiState.value = _uiState.value.copy(
            isLoading = isLoading,
            allPhotos = photos,
            clusters = clusters,
            error = null
        )
    }

    private suspend fun removeMapPhotos(photos: List<UnifiedPhotoItem>) {
        if (photos.isEmpty()) return
        val cloudIds = photos.mapNotNull { it.cloudId }.toSet()
        val md5s = photos.map { it.md5 }.filter { it.isNotBlank() }.toSet()
        if (cloudIds.isEmpty() && md5s.isEmpty()) return

        val state = _uiState.value
        val updatedMapPhotos = state.allPhotos.filterNot { photo ->
            photo.id in cloudIds || photo.md5 in md5s
        }
        if (updatedMapPhotos.size == state.allPhotos.size) return

        publishPhotos(updatedMapPhotos, isLoading = false)
        val current = _uiState.value
        val updatedSelectedClusterPhotos = current.selectedClusterPhotos.removePhotos(photos)
        val updatedSelectedCluster = current.selectedCluster?.let { cluster ->
            val clusterPhotos = cluster.photos.filterNot { photo ->
                photo.id in cloudIds || photo.md5 in md5s
            }
            if (clusterPhotos.isEmpty()) null else cluster.copy(photos = clusterPhotos)
        }
        _uiState.value = current.copy(
            selectedCluster = updatedSelectedCluster,
            selectedClusterPhotos = if (updatedSelectedCluster == null) {
                emptyList()
            } else {
                updatedSelectedClusterPhotos
            },
            isResolvingSelectedCluster = current.isResolvingSelectedCluster && updatedSelectedCluster != null
        )
    }

    private suspend fun clustersFor(
        photos: List<MapPhotoItem>,
        zoomLevel: Float,
        viewport: MapViewport?
    ): List<MapCluster> = withContext(Dispatchers.Default) {
        if (photos.isEmpty()) return@withContext emptyList()

        val zoomBucket = zoomBucketFor(zoomLevel)
        val cachedClusters = synchronized(clusterCache) {
            clusterCache[zoomBucket]
        }
        val fullClusters = cachedClusters ?: clusterPhotos(photos, zoomBucket).also { computed ->
            synchronized(clusterCache) {
                clusterCache[zoomBucket] = computed
            }
        }
        filterClustersForViewport(fullClusters, viewport)
    }

    private fun clusterPhotos(photos: List<MapPhotoItem>, zoomBucket: Int): List<MapCluster> {
        if (photos.isEmpty()) return emptyList()

        val gridSize = gridSizeFor(zoomBucket)
        val grid = mutableMapOf<Pair<Int, Int>, MutableList<MapPhotoItem>>()
        for (photo in photos) {
            val gridX = floor(photo.lng / gridSize).toInt()
            val gridY = floor(photo.lat / gridSize).toInt()
            val key = gridX to gridY
            grid.getOrPut(key) { mutableListOf() }.add(photo)
        }

        return grid.map { (cell, group) ->
            val centerLat = group.sumOf { it.lat } / group.size
            val centerLng = group.sumOf { it.lng } / group.size
            MapCluster(
                key = "$zoomBucket:${cell.first}:${cell.second}",
                lat = centerLat,
                lng = centerLng,
                photos = group,
                coverMd5 = group.first().md5
            )
        }
    }

    private fun filterClustersForViewport(
        clusters: List<MapCluster>,
        viewport: MapViewport?
    ): List<MapCluster> {
        val bounds = viewport ?: return clusters
        val latPadding = (bounds.north - bounds.south).coerceAtLeast(0.05) * VIEWPORT_PADDING_RATIO
        val lngSpan = if (bounds.east >= bounds.west) {
            bounds.east - bounds.west
        } else {
            360.0 - bounds.west + bounds.east
        }.coerceAtLeast(0.05)
        val lngPadding = lngSpan * VIEWPORT_PADDING_RATIO
        val south = (bounds.south - latPadding).coerceAtLeast(-90.0)
        val north = (bounds.north + latPadding).coerceAtMost(90.0)
        val west = normalizeLng(bounds.west - lngPadding)
        val east = normalizeLng(bounds.east + lngPadding)
        return clusters.filter { cluster ->
            cluster.lat in south..north && lngInRange(cluster.lng, west, east)
        }
    }

    private fun zoomBucketFor(zoomLevel: Float): Int {
        return when {
            zoomLevel >= 18f -> 18
            zoomLevel >= 16f -> 16
            zoomLevel >= 14f -> 14
            zoomLevel >= 12f -> 12
            zoomLevel >= 10f -> 10
            zoomLevel >= 8f -> 8
            zoomLevel >= 6f -> 6
            zoomLevel >= 4f -> 4
            else -> 2
        }
    }

    private fun gridSizeFor(zoomBucket: Int): Double {
        return when {
            zoomBucket >= 18 -> 0.0005
            zoomBucket >= 16 -> 0.002
            zoomBucket >= 14 -> 0.008
            zoomBucket >= 12 -> 0.03
            zoomBucket >= 10 -> 0.1
            zoomBucket >= 8 -> 0.4
            zoomBucket >= 6 -> 1.5
            zoomBucket >= 4 -> 5.0
            else -> 15.0
        }
    }

    private fun normalizeLng(lng: Double): Double {
        var normalized = lng
        while (normalized < -180.0) normalized += 360.0
        while (normalized > 180.0) normalized -= 360.0
        return normalized
    }

    private fun lngInRange(lng: Double, west: Double, east: Double): Boolean {
        val normalized = normalizeLng(lng)
        return if (east >= west) normalized in west..east else normalized >= west || normalized <= east
    }

    class Factory(
        private val galleryRepository: GalleryRepository,
        private val syncRepository: SyncRepository? = null,
        private val mediaUiMutationBus: MediaUiMutationBus? = null
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MapViewModel(galleryRepository, syncRepository, mediaUiMutationBus) as T
        }
    }

    companion object {
        const val DEFAULT_ZOOM_LEVEL = 5f
        const val THUMB_MARKER_MIN_ZOOM = 12f
        private const val CAMERA_IDLE_DEBOUNCE_MS = 180L
        private const val MAX_CLUSTER_CACHE_BUCKETS = 8
        private const val VIEWPORT_PADDING_RATIO = 0.35
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
