package com.kqstone.mtphotos.ui.map

import android.os.Bundle
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil.ImageLoader
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.CameraPosition
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.LatLngBounds
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.MarkerOptions
import com.kqstone.mtphotos.ui.util.ThumbnailImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    viewModel: MapViewModel,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val imageLoader = remember { ImageLoader(context) }

    // BottomSheet 状态
    var selectedCluster by remember { mutableStateOf<MapCluster?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    // MapView 引用
    var mapView by remember { mutableStateOf<MapView?>(null) }
    var aMap by remember { mutableStateOf<AMap?>(null) }

    // 当前标记列表（用于清除重绘）
    var currentMarkers by remember { mutableStateOf<List<Marker>>(emptyList()) }

    // 标记地图缩放级别，避免重复刷新
    var lastZoomLevel by remember { mutableStateOf(MapViewModel.DEFAULT_ZOOM_LEVEL) }

    Box(modifier = Modifier.fillMaxSize()) {
        // 高德地图 AndroidView
        AndroidView(
            factory = { ctx ->
                // 高德地图隐私合规接口调用（8.1.0及以上版本必须在 MapView 实例化之前调用）
                com.amap.api.maps.MapsInitializer.updatePrivacyShow(ctx, true, true)
                com.amap.api.maps.MapsInitializer.updatePrivacyAgree(ctx, true)

                MapView(ctx).also { mv ->
                    mv.onCreate(Bundle())
                    mapView = mv
                    val map = mv.map
                    aMap = map

                    // 设置地图风格
                    map.uiSettings.apply {
                        isZoomControlsEnabled = false
                        isCompassEnabled = true
                        isScaleControlsEnabled = true
                        isMyLocationButtonEnabled = false
                    }

                    // 设置初始中心（中国中心）和缩放级别
                    map.moveCamera(
                        CameraUpdateFactory.newCameraPosition(
                            CameraPosition(
                                LatLng(35.0, 105.0), // 中国中心
                                MapViewModel.DEFAULT_ZOOM_LEVEL,
                                0f, 0f
                            )
                        )
                    )

                    // 监听缩放变化
                    map.setOnCameraChangeListener(object : AMap.OnCameraChangeListener {
                        override fun onCameraChange(pos: CameraPosition?) {}
                        override fun onCameraChangeFinish(pos: CameraPosition?) {
                            pos?.let {
                                val newZoom = it.zoom
                                // 缩放级别变化超过 0.5 才触发重新聚合
                                if (kotlin.math.abs(newZoom - lastZoomLevel) > 0.5f) {
                                    lastZoomLevel = newZoom
                                    viewModel.onZoomChanged(newZoom)
                                }
                            }
                        }
                    })

                    // 点击标记
                    map.setOnMarkerClickListener { marker ->
                        val cluster = marker.`object` as? MapCluster
                        if (cluster != null) {
                            if (cluster.count <= 5) {
                                // 少量照片：放大到可以分散显示
                                val boundsBuilder = LatLngBounds.Builder()
                                cluster.photos.forEach { photo ->
                                    boundsBuilder.include(LatLng(photo.lat, photo.lng))
                                }
                                map.animateCamera(
                                    CameraUpdateFactory.newLatLngBounds(
                                        boundsBuilder.build(), 120
                                    )
                                )
                            } else {
                                // 大量照片：弹出 BottomSheet
                                selectedCluster = cluster
                            }
                        }
                        true
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // 生命周期管理
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> mapView?.onResume()
                    Lifecycle.Event.ON_PAUSE -> mapView?.onPause()
                    Lifecycle.Event.ON_DESTROY -> mapView?.onDestroy()
                    else -> {}
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
                mapView?.onDestroy()
            }
        }

        // 当聚合数据更新时，重新绘制标记
        LaunchedEffect(uiState.clusters) {
            val map = aMap ?: return@LaunchedEffect
            val clusters = uiState.clusters
            if (clusters.isEmpty() && !uiState.isLoading) return@LaunchedEffect

            // 清除旧标记
            currentMarkers.forEach { it.remove() }
            val newMarkers = mutableListOf<Marker>()

            for (cluster in clusters) {
                val markerBitmap = if (cluster.count <= 3) {
                    // 少量照片：带缩略图的 Marker
                    val coverPhoto = cluster.photos.first()
                    val thumbUrl = viewModel.getThumbUrl(coverPhoto.md5, coverPhoto.id)
                    withContext(Dispatchers.IO) {
                        MapClusterRenderer.createMarkerBitmap(
                            context, thumbUrl, cluster.count, imageLoader
                        )
                    }
                } else {
                    // 大量照片：简易数量标记
                    MapClusterRenderer.createSimpleMarkerBitmap(context, cluster.count)
                }

                val markerOptions = MarkerOptions()
                    .position(LatLng(cluster.lat, cluster.lng))
                    .icon(BitmapDescriptorFactory.fromBitmap(markerBitmap))
                    .anchor(0.5f, 1.0f)

                val marker = map.addMarker(markerOptions)
                marker?.setObject(cluster)
                marker?.let { newMarkers.add(it) }
            }

            currentMarkers = newMarkers

            // 首次加载时，自动缩放到包含所有标记的范围
            if (uiState.allPhotos.isNotEmpty() && lastZoomLevel == MapViewModel.DEFAULT_ZOOM_LEVEL) {
                val boundsBuilder = LatLngBounds.Builder()
                uiState.allPhotos.forEach { photo ->
                    boundsBuilder.include(LatLng(photo.lat, photo.lng))
                }
                try {
                    map.animateCamera(
                        CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), 80)
                    )
                } catch (_: Exception) {
                    // 防止只有一个点时 bounds 异常
                }
            }
        }

        // 顶部半透明状态栏 + 返回按钮
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.4f),
                            Color.Transparent
                        )
                    )
                )
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "返回",
                        tint = Color.White
                    )
                }
                Text(
                    text = "足迹地图",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.weight(1f))
                if (!uiState.isLoading && uiState.allPhotos.isNotEmpty()) {
                    Text(
                        text = "${uiState.allPhotos.size} 张照片",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.padding(end = 16.dp)
                    )
                }
            }
        }

        // 加载指示器
        if (uiState.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "正在加载照片位置...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        // 错误提示
        if (uiState.error != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
                    .background(
                        MaterialTheme.colorScheme.errorContainer,
                        RoundedCornerShape(12.dp)
                    )
                    .padding(16.dp)
            ) {
                Text(
                    text = uiState.error ?: "",
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }

    // 照片列表 BottomSheet
    if (selectedCluster != null) {
        ModalBottomSheet(
            onDismissRequest = { selectedCluster = null },
            sheetState = sheetState,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            dragHandle = { BottomSheetDefaults.DragHandle() }
        ) {
            ClusterPhotoGrid(
                cluster = selectedCluster!!,
                thumbUrlProvider = { md5, id -> viewModel.getThumbUrl(md5, id) },
                onDismiss = { selectedCluster = null }
            )
        }
    }
}

@Composable
private fun ClusterPhotoGrid(
    cluster: MapCluster,
    thumbUrlProvider: (String, Double) -> String,
    onDismiss: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
    ) {
        // 标题行
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${cluster.count} 张照片",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "关闭"
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 照片网格
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            contentPadding = PaddingValues(bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier.height(400.dp)
        ) {
            items(
                items = cluster.photos,
                key = { it.id }
            ) { photo ->
                Box(
                    modifier = Modifier
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(4.dp))
                ) {
                    ThumbnailImage(
                        url = thumbUrlProvider(photo.md5, photo.id),
                        contentDescription = photo.fileName,
                        modifier = Modifier.fillMaxSize(),
                        key = photo.md5
                    )
                }
            }
        }
    }
}
