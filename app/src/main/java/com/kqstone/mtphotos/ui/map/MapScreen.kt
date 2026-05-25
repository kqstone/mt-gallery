package com.kqstone.mtphotos.ui.map

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil.Coil
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.CameraPosition
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.MyLocationStyle
import com.kqstone.mtphotos.data.model.UnifiedPhotoItem
import com.kqstone.mtphotos.ui.gallery.SelectionManager
import com.kqstone.mtphotos.ui.gallery.TimelinePhotoGrid
import com.kqstone.mtphotos.ui.gallery.buildPhotoTimelineLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

@Composable
fun MapScreen(
    viewModel: MapViewModel,
    onPhotoClick: (UnifiedPhotoItem, List<UnifiedPhotoItem>) -> Unit = { _, _ -> }
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val imageLoader = remember(context) { Coil.imageLoader(context) }
    val markerBitmapCache = remember {
        object : LinkedHashMap<String, Bitmap>(128, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>): Boolean {
                return size > MAX_MARKER_BITMAP_CACHE_SIZE
            }
        }
    }
    val markerRenderSemaphore = remember { Semaphore(MAX_MARKER_RENDER_PARALLELISM) }

    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineLocationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseLocationGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
        if (fineLocationGranted || coarseLocationGranted) {
            hasLocationPermission = true
        }
    }

    LaunchedEffect(Unit) {
        if (!hasLocationPermission) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    val selectedCluster = uiState.selectedCluster

    var mapView by remember { mutableStateOf<MapView?>(null) }
    var aMap by remember { mutableStateOf<AMap?>(null) }
    val markersByClusterKey = remember { mutableMapOf<String, Marker>() }
    val markerRenderKeys = remember { mutableMapOf<String, String>() }
    val markerRenderJobs = remember { mutableMapOf<String, Job>() }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                com.amap.api.maps.MapsInitializer.updatePrivacyShow(ctx, true, true)
                com.amap.api.maps.MapsInitializer.updatePrivacyAgree(ctx, true)

                MapView(ctx).also { mv ->
                    mv.onCreate(Bundle())
                    mapView = mv
                    val map = mv.map
                    aMap = map

                    map.uiSettings.apply {
                        isZoomControlsEnabled = false
                        isCompassEnabled = true
                        isScaleControlsEnabled = true
                        isMyLocationButtonEnabled = false
                    }

                    val initialCameraState = uiState.cameraState
                    map.moveCamera(
                        CameraUpdateFactory.newCameraPosition(
                            if (initialCameraState != null) {
                                CameraPosition(
                                    LatLng(initialCameraState.lat, initialCameraState.lng),
                                    initialCameraState.zoom,
                                    initialCameraState.tilt,
                                    initialCameraState.bearing
                                )
                            } else {
                                CameraPosition(
                                    LatLng(35.0, 105.0),
                                    MapViewModel.DEFAULT_ZOOM_LEVEL,
                                    0f,
                                    0f
                                )
                            }
                        )
                    )

                    map.setOnCameraChangeListener(object : AMap.OnCameraChangeListener {
                        override fun onCameraChange(pos: CameraPosition?) = Unit

                        override fun onCameraChangeFinish(pos: CameraPosition?) {
                            val cameraPosition = pos ?: return
                            viewModel.onCameraIdle(
                                lat = cameraPosition.target.latitude,
                                lng = cameraPosition.target.longitude,
                                zoom = cameraPosition.zoom,
                                tilt = cameraPosition.tilt,
                                bearing = cameraPosition.bearing,
                                viewport = map.visibleViewport()
                            )
                        }
                    })

                    map.setOnMarkerClickListener { marker ->
                        (marker.`object` as? MapCluster)?.let(viewModel::selectCluster)
                        true
                    }

                    mv.post {
                        val cameraPosition = map.cameraPosition ?: return@post
                        viewModel.onCameraIdle(
                            lat = cameraPosition.target.latitude,
                            lng = cameraPosition.target.longitude,
                            zoom = cameraPosition.zoom,
                            tilt = cameraPosition.tilt,
                            bearing = cameraPosition.bearing,
                            viewport = map.visibleViewport()
                        )
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> mapView?.onResume()
                    Lifecycle.Event.ON_PAUSE -> mapView?.onPause()
                    Lifecycle.Event.ON_DESTROY -> mapView?.onDestroy()
                    else -> Unit
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
                mapView?.onDestroy()
            }
        }

        LaunchedEffect(aMap, hasLocationPermission) {
            val map = aMap ?: return@LaunchedEffect
            if (hasLocationPermission) {
                val myLocationStyle = MyLocationStyle()
                myLocationStyle.myLocationType(MyLocationStyle.LOCATION_TYPE_SHOW)
                map.setMyLocationStyle(myLocationStyle)
                map.isMyLocationEnabled = true
                map.uiSettings.isMyLocationButtonEnabled = true
            }
        }

        LaunchedEffect(aMap, uiState.clusters) {
            val map = aMap ?: return@LaunchedEffect
            val clusters = uiState.clusters
            Log.d("MapScreen", "draw markers clusters=${clusters.size}")

            val visibleClusterKeys = clusters.mapTo(mutableSetOf()) { it.key }
            val removedKeys = markersByClusterKey.keys - visibleClusterKeys
            for (key in removedKeys) {
                markerRenderJobs.remove(key)?.cancel()
                markerRenderKeys.remove(key)
                markersByClusterKey.remove(key)?.remove()
            }
            if (clusters.isEmpty()) return@LaunchedEffect

            val pendingMarkers = mutableListOf<PendingMarkerRender>()
            val useThumbMarkers = shouldUseThumbMarkers(uiState.cameraState?.zoom, clusters.size)

            for (cluster in clusters) {
                val coverPhoto = cluster.photos.firstOrNull()
                val thumbUrl = coverPhoto?.let { viewModel.getThumbUrl(it.md5, it.id) }
                val cacheKey = if (useThumbMarkers) {
                    buildMarkerCacheKey(cluster, thumbUrl)
                } else {
                    buildSimpleMarkerCacheKey(cluster.count)
                }
                val cachedBitmap = synchronized(markerBitmapCache) {
                    markerBitmapCache[cacheKey]
                }
                val initialBitmap = cachedBitmap ?: synchronized(markerBitmapCache) {
                    markerBitmapCache.getOrPut(buildSimpleMarkerCacheKey(cluster.count)) {
                        MapClusterRenderer.createSimpleMarkerBitmap(context, cluster.count)
                    }
                }

                val marker = markersByClusterKey[cluster.key] ?: map.addMarker(
                    MarkerOptions()
                        .position(LatLng(cluster.lat, cluster.lng))
                        .icon(BitmapDescriptorFactory.fromBitmap(initialBitmap))
                        .anchor(0.5f, 0.5f)
                )?.also {
                    markersByClusterKey[cluster.key] = it
                }

                marker?.let {
                    it.position = LatLng(cluster.lat, cluster.lng)
                    it.setObject(cluster)
                    if (markerRenderKeys[cluster.key] != cacheKey) {
                        it.setIcon(BitmapDescriptorFactory.fromBitmap(initialBitmap))
                        it.setAnchor(0.5f, 0.5f)
                        markerRenderKeys[cluster.key] = cacheKey
                    }
                    if (useThumbMarkers && cachedBitmap == null && !thumbUrl.isNullOrEmpty()) {
                        pendingMarkers += PendingMarkerRender(
                            clusterKey = cluster.key,
                            cacheKey = cacheKey,
                            cluster = cluster,
                            thumbUrl = thumbUrl,
                            marker = it
                        )
                    }
                }
            }

            pendingMarkers.forEach { pending ->
                markerRenderJobs.remove(pending.clusterKey)?.cancel()
                markerRenderJobs[pending.clusterKey] = launch(Dispatchers.IO) {
                    val bitmap = markerRenderSemaphore.withPermit {
                        MapClusterRenderer.createCircularThumbMarkerBitmap(
                            context = context,
                            thumbUrl = pending.thumbUrl,
                            count = pending.cluster.count,
                            imageLoader = imageLoader
                        )
                    }
                    synchronized(markerBitmapCache) {
                        markerBitmapCache[pending.cacheKey] = bitmap
                    }
                    withContext(Dispatchers.Main) {
                        val currentMarker = markersByClusterKey[pending.clusterKey]
                        if (currentMarker == pending.marker && currentMarker.`object` == pending.cluster) {
                            currentMarker.setIcon(BitmapDescriptorFactory.fromBitmap(bitmap))
                            currentMarker.setAnchor(0.5f, 0.5f)
                        }
                        markerRenderJobs.remove(pending.clusterKey)
                    }
                }
            }
        }

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
                Spacer(modifier = Modifier.width(12.dp))
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

        if (uiState.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "正在加载照片位置...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

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
                    text = uiState.error.orEmpty(),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        if (!uiState.isLoading && uiState.error == null && uiState.allPhotos.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
                    .background(
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                        RoundedCornerShape(12.dp)
                    )
                    .padding(16.dp)
            ) {
                Text(
                    text = "没有找到带位置信息的照片",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        AnimatedVisibility(
            visible = selectedCluster != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { viewModel.selectCluster(null) }
                    )
            )
        }

        AnimatedVisibility(
            visible = selectedCluster != null,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            selectedCluster?.let { cluster ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
                        )
                ) {
                    Column {
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .padding(top = 12.dp, bottom = 8.dp)
                                .size(width = 32.dp, height = 4.dp)
                                .background(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                    shape = CircleShape
                                )
                        )
                        ClusterPhotoGrid(
                            cluster = cluster,
                            photos = uiState.selectedClusterPhotos,
                            isLoading = uiState.isResolvingSelectedCluster,
                            thumbUrlProvider = { md5, id -> viewModel.getThumbUrl(md5, id) },
                            onDismiss = { viewModel.selectCluster(null) },
                            onPhotoClick = onPhotoClick
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ClusterPhotoGrid(
    cluster: MapCluster,
    photos: List<UnifiedPhotoItem>,
    isLoading: Boolean,
    thumbUrlProvider: (String, Double) -> String,
    onDismiss: () -> Unit,
    onPhotoClick: (UnifiedPhotoItem, List<UnifiedPhotoItem>) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val selectionManager = remember {
        SelectionManager(
            scope = coroutineScope,
            onDelete = { Result.success(Unit) },
            onError = {}
        )
    }

    LaunchedEffect(photos) {
        selectionManager.clearSelection()
    }

    val selectedPhotoIds by selectionManager.selectedPhotoIds.collectAsState()
    val isSelectionMode = selectedPhotoIds.isNotEmpty()
    var columnCount by remember { mutableStateOf(4) }

    val timelineLayout = remember(photos) {
        buildPhotoTimelineLayout(photos)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
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

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(400.dp),
            contentAlignment = Alignment.Center
        ) {
            when {
                isLoading -> {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }

                photos.isEmpty() -> {
                    Text(
                        text = "未找到可展示的照片",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                else -> {
                    TimelinePhotoGrid(
                        months = timelineLayout.months,
                        columnCount = columnCount,
                        selectedPhotoIds = selectedPhotoIds,
                        isSelectionMode = isSelectionMode,
                        selectionManager = selectionManager,
                        getThumbUrl = { photo -> thumbUrlProvider(photo.md5, photo.id) },
                        onPhotoClick = { photo -> onPhotoClick(photo, photos) },
                        onColumnCountChange = { columnCount = it },
                        showMonthHeaders = timelineLayout.showMonthHeaders,
                        showDayHeaders = timelineLayout.showDayHeaders,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

private data class PendingMarkerRender(
    val clusterKey: String,
    val cacheKey: String,
    val cluster: MapCluster,
    val thumbUrl: String,
    val marker: Marker
)

private fun buildMarkerCacheKey(cluster: MapCluster, thumbUrl: String?): String {
    return listOf(
        "thumb",
        cluster.key,
        cluster.coverMd5,
        cluster.count.toString(),
        thumbUrl.orEmpty()
    ).joinToString("|")
}

private fun buildSimpleMarkerCacheKey(count: Int): String {
    val countBucket = when {
        count >= 1000 -> "${count / 1000}k"
        count > 99 -> "99+"
        else -> count.toString()
    }
    return "simple|$countBucket"
}

private fun shouldUseThumbMarkers(zoom: Float?, clusterCount: Int): Boolean {
    return (zoom ?: MapViewModel.DEFAULT_ZOOM_LEVEL) >= MapViewModel.THUMB_MARKER_MIN_ZOOM &&
        clusterCount <= MAX_THUMB_MARKERS
}

private fun AMap.visibleViewport(): MapViewport? {
    return runCatching {
        val bounds = projection.visibleRegion.latLngBounds
        MapViewport(
            south = bounds.southwest.latitude,
            west = bounds.southwest.longitude,
            north = bounds.northeast.latitude,
            east = bounds.northeast.longitude
        )
    }.getOrNull()
}

private const val MAX_THUMB_MARKERS = 160
private const val MAX_MARKER_RENDER_PARALLELISM = 4
private const val MAX_MARKER_BITMAP_CACHE_SIZE = 240
