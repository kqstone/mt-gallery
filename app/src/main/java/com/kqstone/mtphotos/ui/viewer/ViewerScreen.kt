package com.kqstone.mtphotos.ui.viewer

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import coil.compose.AsyncImage
import com.kqstone.mtphotos.R
import com.kqstone.mtphotos.data.model.UnifiedPhotoItem
import com.kqstone.mtphotos.ui.gallery.DeleteConfirmDialog
import com.kqstone.mtphotos.ui.util.PermissionHelper
import com.kqstone.mtphotos.ui.util.frostedGlassEffect
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import android.content.Context
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(
    viewModel: ViewerViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val photos = uiState.photos
    val initialIndex = uiState.currentIndex

    var stopActivePlayback by remember { mutableStateOf<(() -> Unit)?>(null) }
    var isExiting by remember { mutableStateOf(false) }

    val stopAndGoBack = {
        if (!isExiting) {
            isExiting = true
            stopActivePlayback?.invoke()
            onBack()
        }
    }

    BackHandler(onBack = stopAndGoBack)

    if (photos.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Text(stringResource(R.string.no_photos), color = Color.White)
        }
        return
    }

    val pagerState = rememberPagerState(
        initialPage = initialIndex,
        pageCount = { photos.size }
    )

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            viewModel.updateCurrentIndex(page)
        }
    }

    val currentPhoto = photos.getOrNull(pagerState.settledPage) ?: photos[initialIndex]

    // UI visibility state for immersive full screen
    var isUiVisible by remember { mutableStateOf(true) }
    var showBottomSheet by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    val hazeState = remember { HazeState() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .hazeSource(state = hazeState)
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 0,
            userScrollEnabled = !showBottomSheet
        ) { page ->
            val photo = photos[page]
            val isCurrentPage = pagerState.settledPage == page
            val isPlayableMedia = photo.isPlayableMedia()

            if (isPlayableMedia && isCurrentPage) {
                val url = uiState.resolvedVideoUrl ?: ""
                if (url.isNotEmpty()) {
                    VideoPlayer(
                        videoUrl = url,
                        isCurrentPage = true,
                        isUiVisible = isUiVisible,
                        onToggleUi = { isUiVisible = !isUiVisible },
                        onStopPlaybackReady = { stopActivePlayback = it }
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = Color.White)
                    }
                }
            } else if (isPlayableMedia) {
                Box(modifier = Modifier.fillMaxSize())
            } else {
                ZoomableImage(
                    photo = photo,
                    imageUrl = viewModel.getFullImageUrl(photo),
                    contentDescription = photo.fileName,
                    onTap = { isUiVisible = !isUiVisible }
                )
            }
        }

        // Top Gradient & AppBar
        AnimatedVisibility(
            visible = isUiVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)
                        )
                    )
                    .statusBarsPadding()
                    .padding(vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = stopAndGoBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                            tint = Color.White
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.Center
                    ) {
                        val address = uiState.fileDetailInfo?.let {
                            (it["addr"] ?: it["address"] ?: it["location"])?.toString()
                        } ?: currentPhoto.addr
                        val dateText = remember(currentPhoto.mtime) { formatFriendlyDateShort(context, currentPhoto.mtime) }

                        if (!address.isNullOrBlank()) {
                            Text(
                                text = address,
                                color = Color.White,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = dateText,
                                color = Color.White.copy(alpha = 0.7f),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        } else {
                            Text(
                                text = dateText,
                                color = Color.White,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    val needsOriginal = currentPhoto.syncStatus == com.kqstone.mtphotos.data.local.db.SyncStatus.CLOUD_ONLY ||
                        currentPhoto.isStorageOptimized
                    val showLoadOriginalButton = needsOriginal &&
                        (!currentPhoto.isVideo() || uiState.isPlayingTranscode) &&
                        !uiState.originalDownloaded

                    if (showLoadOriginalButton) {
                        IconButton(onClick = { viewModel.downloadOriginal(context) }) {
                            if (uiState.isDownloadingOriginal) {
                                Box(contentAlignment = Alignment.Center) {
                                    val progress = uiState.downloadProgress
                                    if (progress != null) {
                                        CircularProgressIndicator(
                                            progress = progress,
                                            modifier = Modifier.size(24.dp),
                                            color = Color.White,
                                            strokeWidth = 2.dp
                                        )
                                        Text(
                                            text = "${(progress * 100).toInt()}%",
                                            color = Color.White,
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp)
                                        )
                                    } else {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(20.dp),
                                            color = Color.White,
                                            strokeWidth = 2.dp
                                        )
                                    }
                                }
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Download,
                                    contentDescription = stringResource(R.string.load_original),
                                    tint = Color.White
                                )
                            }
                        }
                    }
                }
            }
        }

        // Bottom Gradient & Floating Action HUD Bar
        AnimatedVisibility(
            visible = isUiVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))
                        )
                    )
                    .navigationBarsPadding()
                    .padding(bottom = 20.dp, top = 24.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Pager Number Indicator
                    Text(
                        text = "${pagerState.settledPage + 1} / ${photos.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier
                            .background(Color.Black.copy(alpha = 0.55f), shape = RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    // Control Buttons (Narrower width, no pill background)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(32.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Share
                        HUDButton(
                            icon = Icons.Default.Share,
                            label = stringResource(R.string.share),
                            onClick = { viewModel.sharePhoto(context) }
                        )

                        // Favorite with scale animation
                        val favScale by animateFloatAsState(
                            targetValue = if (uiState.isFavorite) 1.25f else 1.0f,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessLow
                            ),
                            label = "favorite_scale"
                        )

                        HUDButton(
                            icon = if (uiState.isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                            label = if (uiState.isFavorite) stringResource(R.string.favorited) else stringResource(R.string.favorite),
                            iconColor = if (uiState.isFavorite) Color(0xFFFFD700) else Color.White,
                            iconScale = favScale,
                            onClick = { viewModel.toggleFavorite() }
                        )

                        // Delete
                        HUDButton(
                            icon = Icons.Default.Delete,
                            label = stringResource(R.string.delete),
                            onClick = { showDeleteDialog = true }
                        )

                        // Info Details
                        HUDButton(
                            icon = Icons.Default.Info,
                            label = stringResource(R.string.info),
                            onClick = { showBottomSheet = true }
                        )
                    }
                }
            }
        }

        // Sharing Overlay Indicator
        com.kqstone.mtphotos.ui.util.ShareProgressOverlay(viewModel.shareManager)
    }

    // Modal Bottom Sheet Details Drawer
    if (showBottomSheet) {
        DetailsBottomSheet(
            photo = currentPhoto,
            uiState = uiState,
            hazeState = hazeState,
            onDismiss = { showBottomSheet = false }
        )
    }

    if (showDeleteDialog) {
        DeleteConfirmDialog(
            selectedCount = 1,
            onConfirm = {
                showDeleteDialog = false
                if (PermissionHelper.requestManageStoragePermission(context)) {
                    viewModel.deleteCurrentPhoto(onSuccess = {
                        stopAndGoBack()
                    })
                }
            },
            onDismiss = { showDeleteDialog = false }
        )
    }

    // Elegant Delete Confirmation Dialog
    if (false && showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = stringResource(R.string.delete_this_media), fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Text(
                    text = stringResource(R.string.delete_media_confirm_desc),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteDialog = false
                        if (PermissionHelper.requestManageStoragePermission(context)) {
                            viewModel.deleteCurrentPhoto(onSuccess = {
                                stopAndGoBack()
                            })
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteDialog = false },
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
            shape = RoundedCornerShape(16.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        )
    }
}

@Composable
private fun HUDButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    iconColor: Color = Color.White,
    iconScale: Float = 1.0f,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(8.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = iconColor,
            modifier = Modifier
                .size(24.dp)
                .graphicsLayer {
                    scaleX = iconScale
                    scaleY = iconScale
                }
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.85f)
        )
    }
}

@Composable
private fun ZoomableImage(
    photo: UnifiedPhotoItem,
    imageUrl: String,
    contentDescription: String,
    onTap: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val app = context.applicationContext as com.kqstone.mtphotos.MTPhotosApp

    val imageRequest = remember(imageUrl, photo.md5) {
        coil.request.ImageRequest.Builder(context)
            .data(imageUrl)
            .diskCacheKey("${photo.md5}_full")
            .memoryCacheKey("${photo.md5}_full")
            .diskCachePolicy(coil.request.CachePolicy.ENABLED)
            .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
            .build()
    }

    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    AsyncImage(
        model = imageRequest,
        imageLoader = app.fullImageLoader,
        contentDescription = contentDescription,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = true)
                    var isClick = true
                    var totalPan = Offset.Zero
                    do {
                        val event = awaitPointerEvent()
                        val zoom = event.calculateZoom()

                        var panX = 0f
                        var panY = 0f
                        var panCount = 0
                        for (change in event.changes) {
                            if (change.previousPressed) {
                                panX += change.position.x - change.previousPosition.x
                                panY += change.position.y - change.previousPosition.y
                                panCount++
                            }
                        }
                        val pan = if (panCount > 0) Offset(panX / panCount, panY / panCount) else Offset.Zero
                        totalPan += pan

                        if (zoom != 1f || pan.getDistanceSquared() > 10f) {
                            isClick = false
                        }

                        if (zoom != 1f) {
                            scale = (scale * zoom).coerceIn(1f, 5f)
                            if (scale <= 1.01f) {
                                scale = 1f
                                offset = Offset.Zero
                            }
                            event.changes.forEach { it.consume() }
                        } else if (scale > 1.01f && pan != Offset.Zero) {
                            offset += pan
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })

                    if (isClick && totalPan.getDistanceSquared() < 100f) {
                        onTap()
                    }
                }
            }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailsBottomSheet(
    photo: UnifiedPhotoItem,
    uiState: ViewerUiState,
    hazeState: HazeState,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = null,
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        scrimColor = Color.Black.copy(alpha = 0.45f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(BottomSheetDefaults.ExpandedShape)
                .frostedGlassEffect(state = hazeState, showTopDivider = false, tintAlpha = 0.75f)
                .navigationBarsPadding()
        ) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                BottomSheetDefaults.DragHandle()
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            ) {
                Text(
                text = stringResource(R.string.details),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            // Date and time
            val friendlyDate = remember(photo.mtime) { formatFriendlyDate(context, photo.mtime) }

            // Specs Section
            InfoSectionHeader(title = stringResource(R.string.file_info))
            val sizeBytes = uiState.fileDetailInfo?.let {
                (it["size"] ?: it["fileSize"])?.toString()?.toLongOrNull()
            } ?: photo.fileSize
            val widthVal = uiState.fileDetailInfo?.let {
                (it["width"] ?: it["imageWidth"])?.toString()?.toDoubleOrNull()
            } ?: photo.width
            val heightVal = uiState.fileDetailInfo?.let {
                (it["height"] ?: it["imageHeight"])?.toString()?.toDoubleOrNull()
            } ?: photo.height

            val formattedSize = remember(sizeBytes) { formatFileSize(sizeBytes) }
            val formattedRes = remember(widthVal, heightVal) {
                if (widthVal > 0 && heightVal > 0) "${widthVal.toInt()} × ${heightVal.toInt()}" else ""
            }
            val localPath = uiState.fileDetailInfo?.let {
                (it["path"] ?: it["filePath"] ?: it["localUri"])?.toString()
            } ?: photo.localUri.orEmpty()

            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(bottom = 12.dp)
            ) {
                if (friendlyDate.isNotEmpty()) {
                    InfoRowItem(label = stringResource(R.string.taken_time), value = friendlyDate)
                }
                InfoRowItem(label = stringResource(R.string.file_name), value = photo.fileName)
                if (formattedSize.isNotEmpty()) {
                    InfoRowItem(label = stringResource(R.string.file_size), value = formattedSize)
                }
                if (formattedRes.isNotEmpty()) {
                    InfoRowItem(label = stringResource(R.string.resolution), value = formattedRes)
                }
                if (localPath.isNotEmpty()) {
                    InfoRowItem(label = stringResource(R.string.storage_path), value = localPath)
                }
            }

            // EXIF Camera Specs Grid
            val make = uiState.exifInfo?.let { exif ->
                (exif["Make"] ?: exif["make"] ?: exif["cameraMake"] ?: exif["CameraMake"])?.toString()?.trim()
            }
            val model = uiState.exifInfo?.let { exif ->
                (exif["Model"] ?: exif["model"] ?: exif["cameraModel"] ?: exif["CameraModel"])?.toString()?.trim()
            }
            val lens = uiState.exifInfo?.let { exif ->
                (exif["LensModel"] ?: exif["lensModel"] ?: exif["lens"] ?: exif["Lens"])?.toString()?.trim()
            }
            val aperture = uiState.exifInfo?.let { exif ->
                val ap = exif["FNumber"] ?: exif["fNumber"] ?: exif["aperture"] ?: exif["Aperture"] ?: exif["f_number"]
                ap?.let { "f/$it" }
            }
            val shutter = uiState.exifInfo?.let { exif ->
                val sh = exif["ExposureTime"] ?: exif["exposureTime"] ?: exif["shutterSpeed"] ?: exif["shutter"] ?: exif["exposure_time"]
                sh?.let {
                    val doubleVal = it.toString().toDoubleOrNull()
                    if (doubleVal != null && doubleVal > 0) {
                        if (doubleVal < 1.0) {
                            val shutterFraction = Math.round(1.0 / doubleVal)
                            context.getString(R.string.shutter_fraction_format, shutterFraction)
                        } else {
                            context.getString(R.string.shutter_seconds_format, doubleVal.toString())
                        }
                    } else {
                        it.toString()
                    }
                }
            }
            val iso = uiState.exifInfo?.let { exif ->
                (exif["ISOSpeedRatings"] ?: exif["isoSpeedRatings"] ?: exif["iso"] ?: exif["ISO"])?.toString()
            }
            val focal = uiState.exifInfo?.let { exif ->
                val foc = exif["FocalLength"] ?: exif["focalLength"] ?: exif["focal"] ?: exif["focal_length"]
                foc?.let { "$it mm" }
            }

            val exifCards = remember(make, model, lens, aperture, shutter, iso, focal) {
                val list = mutableListOf<Pair<Int, String>>()
                if (!make.isNullOrBlank() || !model.isNullOrBlank()) {
                    val deviceName = listOfNotNull(make, model).distinct().joinToString(" ")
                    list.add(R.string.camera_brand_model to deviceName)
                }
                if (!lens.isNullOrBlank()) {
                    list.add(R.string.lens_model to lens)
                }
                if (!aperture.isNullOrBlank()) {
                    list.add(R.string.aperture to aperture)
                }
                if (!shutter.isNullOrBlank()) {
                    list.add(R.string.shutter_speed to shutter)
                }
                if (!iso.isNullOrBlank()) {
                    list.add(R.string.iso_speed to iso)
                }
                if (!focal.isNullOrBlank()) {
                    list.add(R.string.focal_length to focal)
                }
                list
            }

            if (exifCards.isNotEmpty()) {
                InfoSectionHeader(title = stringResource(R.string.exif_parameters))
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                ) {
                    exifCards.forEach { (label, valStr) ->
                        ExifCardItem(label = stringResource(label), value = valStr)
                    }
                }
            }

            // Geographic Info
            val addr = uiState.fileDetailInfo?.let {
                (it["addr"] ?: it["address"] ?: it["location"])?.toString()
            } ?: photo.addr

            val gpsInfo = (uiState.fileDetailInfo?.get("gpsInfo") ?: uiState.fileDetailInfo?.get("gps_info") ?: uiState.exifInfo?.get("gpsInfo")) as? Map<*, *>
            val lat = gpsInfo?.let { it["latitude"] ?: it["lat"] ?: it["Latitude"] }?.toString()?.toDoubleOrNull()
            val lon = gpsInfo?.let { it["longitude"] ?: it["lon"] ?: it["Longitude"] }?.toString()?.toDoubleOrNull()

            if (!addr.isNullOrBlank() || (lat != null && lon != null)) {
                val displayAddr = addr ?: context.getString(R.string.photo_location_coords_format, lat ?: 0.0, lon ?: 0.0)
                InfoSectionHeader(title = stringResource(R.string.location))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.LocationOn,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = displayAddr,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = {
                                try {
                                    val uri = if (lat != null && lon != null) {
                                        android.net.Uri.parse("geo:$lat,$lon?q=$lat,$lon(${URLEncoder.encode(displayAddr, "UTF-8")})")
                                    } else {
                                        android.net.Uri.parse("geo:0,0?q=${URLEncoder.encode(displayAddr, "UTF-8")}")
                                    }
                                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    android.widget.Toast.makeText(context, context.getString(R.string.open_map_failed, e.message.orEmpty()), android.widget.Toast.LENGTH_SHORT).show()
                                }
                            },
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.align(Alignment.End),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.Map,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(text = stringResource(R.string.view_in_map), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun InfoSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 8.dp)
    )
}

@Composable
private fun InfoRowItem(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            modifier = Modifier
                .weight(1f)
                .padding(start = 16.dp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ExifCardItem(label: String, value: String) {
    Box(
        modifier = Modifier
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                shape = RoundedCornerShape(8.dp)
            )
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
                shape = RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

private fun formatFriendlyDate(context: Context, mtime: String): String {
    if (mtime.isEmpty()) return ""
    return try {
        val clean = mtime.replace("T", " ").substringBefore("+").substringBefore("Z")
        val formatIn = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val date = formatIn.parse(clean) ?: return mtime
        val pattern = context.getString(R.string.friendly_date_format)
        val formatOut = SimpleDateFormat(pattern, Locale.getDefault())
        formatOut.format(date)
    } catch (e: Exception) {
        mtime
    }
}

private fun formatFriendlyDateShort(context: Context, mtime: String): String {
    if (mtime.isEmpty()) return ""
    return try {
        val clean = mtime.replace("T", " ").substringBefore("+").substringBefore("Z")
        val formatIn = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val date = formatIn.parse(clean) ?: return mtime
        val pattern = context.getString(R.string.friendly_date_short_format)
        val formatOut = SimpleDateFormat(pattern, Locale.getDefault())
        formatOut.format(date)
    } catch (e: Exception) {
        mtime
    }
}

private fun formatFileSize(sizeInBytes: Long): String {
    if (sizeInBytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(sizeInBytes.toDouble()) / Math.log10(1024.0)).toInt()
    if (digitGroups >= units.size) return "$sizeInBytes B"
    return String.format(Locale.getDefault(), "%.2f %s", sizeInBytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}
