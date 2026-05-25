package com.kqstone.mtphotos.ui.map

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kqstone.mtphotos.data.repository.GalleryRepository
import com.kqstone.mtphotos.data.repository.MapPhotoItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * 地图上一个聚合标记点，包含一组相邻照片。
 */
data class MapCluster(
    val lat: Double,
    val lng: Double,
    val photos: List<MapPhotoItem>,
    /** 用作封面缩略图的 MD5 */
    val coverMd5: String
) {
    val count: Int get() = photos.size
}

data class MapUiState(
    val isLoading: Boolean = true,
    val allPhotos: List<MapPhotoItem> = emptyList(),
    val clusters: List<MapCluster> = emptyList(),
    val error: String? = null
)

class MapViewModel(private val galleryRepository: GalleryRepository) : ViewModel() {

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

    /**
     * 地图缩放级别变化时，重新计算聚合。
     * @param zoomLevel 高德地图缩放级别 (3-20)
     */
    fun onZoomChanged(zoomLevel: Float) {
        val photos = _uiState.value.allPhotos
        if (photos.isEmpty()) return
        _uiState.value = _uiState.value.copy(
            clusters = clusterPhotos(photos, zoomLevel)
        )
    }

    fun getThumbUrlByMd5(md5: String): String {
        return galleryRepository.getThumbUrlByMd5(md5)
    }

    fun getThumbUrl(md5: String, fileId: Double): String {
        return galleryRepository.getThumbUrl(md5, fileId)
    }

    /**
     * 基于网格的快速聚合算法。
     * 将地图按经纬度网格划分，同一网格内的照片聚合为一个标记。
     * 网格大小根据缩放级别动态调整。
     */
    private fun clusterPhotos(photos: List<MapPhotoItem>, zoomLevel: Float): List<MapCluster> {
        if (photos.isEmpty()) return emptyList()

        // 根据缩放级别计算网格大小（度）
        // 缩放级别越高，网格越小，标记越分散
        val gridSize = when {
            zoomLevel >= 18 -> 0.0005   // 街道级别：几乎不聚合
            zoomLevel >= 16 -> 0.002
            zoomLevel >= 14 -> 0.008
            zoomLevel >= 12 -> 0.03
            zoomLevel >= 10 -> 0.1
            zoomLevel >= 8  -> 0.4
            zoomLevel >= 6  -> 1.5
            zoomLevel >= 4  -> 5.0
            else -> 15.0              // 世界级别：大范围聚合
        }

        // 按网格分组
        val grid = mutableMapOf<Pair<Int, Int>, MutableList<MapPhotoItem>>()
        for (photo in photos) {
            val gridX = (photo.lng / gridSize).toInt()
            val gridY = (photo.lat / gridSize).toInt()
            val key = gridX to gridY
            grid.getOrPut(key) { mutableListOf() }.add(photo)
        }

        // 每个网格生成一个聚合点
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

    class Factory(private val galleryRepository: GalleryRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MapViewModel(galleryRepository) as T
        }
    }

    companion object {
        /** 默认缩放级别 */
        const val DEFAULT_ZOOM_LEVEL = 5f
    }
}
