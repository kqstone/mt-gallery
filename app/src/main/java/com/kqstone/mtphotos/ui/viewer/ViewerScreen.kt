package com.kqstone.mtphotos.ui.viewer

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
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
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import coil.compose.AsyncImage
import com.kqstone.mtphotos.R
import com.kqstone.mtphotos.data.model.UnifiedPhotoItem
import com.kqstone.mtphotos.data.repository.OcrInfoItem
import com.kqstone.mtphotos.data.repository.PeopleDescriptorItem
import com.kqstone.mtphotos.ui.gallery.DeleteConfirmDialog
import com.kqstone.mtphotos.ui.util.PermissionHelper
import com.kqstone.mtphotos.ui.util.findActivity
import com.kqstone.mtphotos.ui.util.frostedGlassEffect
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import android.content.Context
import android.content.res.Configuration
import android.widget.Toast
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.ViewCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale

private val ViewerPageSpacing = 16.dp

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
    val videoPlayerPool = rememberVideoPlayerPool()

    var stopActivePlayback by remember { mutableStateOf<(() -> Unit)?>(null) }
    var isExiting by remember { mutableStateOf(false) }

    // UI visibility state for immersive full screen
    var isUiVisible by remember { mutableStateOf(true) }
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val isLandscapeOrientation = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val restoreSystemBars = ViewerSystemBarsEffect(
        isUiVisible = isUiVisible,
        isLandscape = isLandscapeOrientation
    )

    val stopAndGoBack = {
        if (!isExiting) {
            isExiting = true
            stopActivePlayback?.invoke()
            // Show the viewer UI (action bar + system bars) before navigating
            // away so that the previous screen sees correct inset values.
            isUiVisible = true
            restoreSystemBars()
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

    LaunchedEffect(uiState.currentIndex, photos.size) {
        val targetPage = uiState.currentIndex.coerceIn(0, photos.lastIndex)
        if (pagerState.currentPage != targetPage) {
            pagerState.scrollToPage(targetPage)
        }
    }

    val visiblePage = pagerState.settledPage.coerceIn(0, photos.lastIndex)
    val currentPhoto = photos[visiblePage]
    val nearbyVideoSources = remember(visiblePage, photos, uiState.nearbyVideoSources) {
        listOf(visiblePage + 1, visiblePage - 1)
            .filter { it in photos.indices }
            .mapNotNull { index -> uiState.nearbyVideoSources[photos[index].uniqueKey] }
    }
    val activeVideoIdentity = uiState.resolvedVideoCacheKey ?: uiState.resolvedVideoUrl

    LaunchedEffect(activeVideoIdentity, nearbyVideoSources) {
        videoPlayerPool.prepare(
            sources = nearbyVideoSources,
            activeIdentity = activeVideoIdentity
        )
    }

    var showBottomSheet by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showCastDeviceDialog by remember { mutableStateOf(false) }
    val hazeState = remember { HazeState() }

    LaunchedEffect(uiState.streamFailureCount) {
        if (uiState.streamFailureCount > 0) {
            Toast.makeText(context, context.getString(R.string.stream_link_failed), Toast.LENGTH_SHORT).show()
        }
    }
    LaunchedEffect(uiState.castFailureCount) {
        if (uiState.castFailureCount > 0) {
            Toast.makeText(context, context.getString(R.string.cast_failed), Toast.LENGTH_SHORT).show()
        }
    }
    LaunchedEffect(uiState.castSuccessCount) {
        if (uiState.castSuccessCount > 0) {
            showCastDeviceDialog = false
            Toast.makeText(context, context.getString(R.string.cast_sent), Toast.LENGTH_SHORT).show()
        }
    }
    LaunchedEffect(uiState.peopleDescriptorFailureCount) {
        if (uiState.peopleDescriptorFailureCount > 0) {
            Toast.makeText(context, context.getString(R.string.people_load_failed), Toast.LENGTH_SHORT).show()
        }
    }
    LaunchedEffect(uiState.ocrInfoFailureCount) {
        if (uiState.ocrInfoFailureCount > 0) {
            Toast.makeText(context, context.getString(R.string.ocr_load_failed), Toast.LENGTH_SHORT).show()
        }
    }
    LaunchedEffect(uiState.noPeopleDetectedCount) {
        if (uiState.noPeopleDetectedCount > 0) {
            Toast.makeText(context, "未检出到人物", Toast.LENGTH_SHORT).show()
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .hazeSource(state = hazeState)
    ) {
        val isLandscape = maxWidth > maxHeight

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 1,
            pageSpacing = ViewerPageSpacing,
            userScrollEnabled = !showBottomSheet
        ) { page ->
            val photo = photos[page]
            val isCurrentPage = visiblePage == page
            val isPlayableMedia = photo.isPlayableMedia()

            if (isPlayableMedia && isCurrentPage) {
                val url = uiState.resolvedVideoUrl ?: ""
                val posterUrl = remember(photo.uniqueKey, photo.thumbCachePath, photo.md5, photo.cloudId, photo.localUri) {
                    viewModel.getViewerThumbUrl(photo)
                }
                var isVideoFrameRendered by remember(photo.uniqueKey, url) { mutableStateOf(false) }
                Box(modifier = Modifier.fillMaxSize()) {
                    if (url.isNotEmpty()) {
                        VideoPlayer(
                            videoUrl = url,
                            videoCacheKey = uiState.resolvedVideoCacheKey,
                            playerPool = videoPlayerPool,
                            isCurrentPage = true,
                            isUiVisible = isUiVisible,
                            onToggleUi = { isUiVisible = !isUiVisible },
                            onPlaybackError = { viewModel.fallbackCurrentVideoToOriginal() },
                            onFirstFrameRendered = { isVideoFrameRendered = true },
                            onStopPlaybackReady = { stopActivePlayback = it }
                        )
                        AnimatedVisibility(
                            visible = !isVideoFrameRendered,
                            enter = fadeIn(),
                            exit = fadeOut(),
                            modifier = Modifier.matchParentSize()
                        ) {
                            VideoPoster(
                                photo = photo,
                                thumbUrl = posterUrl,
                                showLoading = true,
                                onTap = { isUiVisible = !isUiVisible }
                            )
                        }
                    } else {
                        VideoPoster(
                            photo = photo,
                            thumbUrl = posterUrl,
                            showLoading = true,
                            onTap = { isUiVisible = !isUiVisible }
                        )
                    }
                    AnimatedVisibility(
                        visible = uiState.isPeopleInfoVisible,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier.matchParentSize()
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clickable(onClick = {
                                    viewModel.hidePeopleInfo()
                                    isUiVisible = false
                                })
                        )
                    }
                    AnimatedVisibility(
                        visible = uiState.isPeopleInfoVisible,
                        enter = fadeIn() + androidx.compose.animation.scaleIn(initialScale = 0.95f),
                        exit = fadeOut() + androidx.compose.animation.scaleOut(targetScale = 0.95f),
                        modifier = if (isLandscape) {
                            Modifier
                                .align(Alignment.CenterEnd)
                                .statusBarsPadding()
                                .padding(end = 112.dp, top = 68.dp, bottom = 24.dp)
                                .fillMaxHeight()
                                .width(96.dp)
                        } else {
                            Modifier
                                .align(Alignment.TopCenter)
                                .statusBarsPadding()
                                .fillMaxWidth()
                                .padding(top = 72.dp, start = 16.dp, end = 16.dp)
                        }
                    ) {
                        PeopleInfoListOverlay(
                            descriptors = uiState.peopleDescriptors,
                            isLoading = false,
                            portraitUrlProvider = viewModel::getPeoplePortraitUrl,
                            isLandscape = isLandscape,
                            hazeState = hazeState,
                            modifier = Modifier.clickable(onClick = {})
                        )
                    }
                }
            } else if (isPlayableMedia) {
                val posterUrl = remember(photo.uniqueKey, photo.thumbCachePath, photo.md5, photo.cloudId, photo.localUri) {
                    viewModel.getViewerThumbUrl(photo)
                }
                VideoPoster(
                    photo = photo,
                    thumbUrl = posterUrl,
                    showLoading = false,
                    onTap = { isUiVisible = !isUiVisible }
                )
            } else {
                val imageUrl = if (isCurrentPage && uiState.isPlayingStreamUrl && !photo.isPlayableMedia()) {
                    uiState.streamMediaUrl ?: viewModel.getFullImageUrl(photo)
                } else {
                    viewModel.getFullImageUrl(photo)
                }
                ZoomableImage(
                    photo = photo,
                    imageUrl = imageUrl,
                    contentDescription = photo.fileName,
                    onTap = { isUiVisible = !isUiVisible },
                    onZoomedChanged = {},
                    peopleDescriptors = if (isCurrentPage && uiState.isPeopleInfoVisible) {
                        uiState.peopleDescriptors
                    } else {
                        emptyList()
                    },
                    isPeopleInfoVisible = isCurrentPage && uiState.isPeopleInfoVisible,
                    isLoadingPeopleInfo = false,
                    ocrItems = if (isCurrentPage && uiState.isOcrInfoVisible) {
                        uiState.ocrItems
                    } else {
                        emptyList()
                    },
                    isOcrInfoVisible = isCurrentPage && uiState.isOcrInfoVisible,
                    isLoadingOcrInfo = isCurrentPage && uiState.isLoadingOcrInfo,
                    onDismissPeopleInfo = {
                        viewModel.hidePeopleInfo()
                        isUiVisible = false
                    },
                    onDismissOcrInfo = {
                        viewModel.hideOcrInfo()
                        isUiVisible = false
                    }
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
                    val showStreamButton = currentPhoto.cloudId != null

                    if (isLandscape) {
                        ViewerLandscapeActionBarActions(
                            isFavorite = uiState.isFavorite,
                            isHide = uiState.isHide,
                            canShowPeopleInfo = currentPhoto.cloudId != null,
                            isPeopleInfoVisible = uiState.isPeopleInfoVisible,
                            isLoadingPeopleDescriptors = uiState.isLoadingPeopleDescriptors,
                            canShowOcrInfo = currentPhoto.cloudId != null && !currentPhoto.isPlayableMedia(),
                            isOcrInfoVisible = uiState.isOcrInfoVisible,
                            isLoadingOcrInfo = uiState.isLoadingOcrInfo,
                            onShare = { viewModel.sharePhoto(context) },
                            onToggleFavorite = { viewModel.toggleFavorite() },
                            onToggleHide = { viewModel.toggleHide() },
                            onPeople = { viewModel.togglePeopleInfo() },
                            onOcr = { viewModel.toggleOcrInfo() },
                            onDelete = { showDeleteDialog = true },
                            onInfo = { showBottomSheet = true }
                        )
                        if (showStreamButton || showLoadOriginalButton) {
                            Box(
                                modifier = Modifier
                                    .padding(horizontal = 4.dp)
                                    .height(28.dp)
                                    .width(1.dp)
                                    .background(Color.White.copy(alpha = 0.32f))
                            )
                        }
                    }
                    if (showStreamButton) {
                        IconButton(
                            onClick = {
                                showCastDeviceDialog = true
                                viewModel.startCastDeviceDiscovery()
                            },
                            enabled = !uiState.isDiscoveringCastDevices && !uiState.isCastingToDevice
                        ) {
                            if (uiState.isDiscoveringCastDevices || uiState.isCastingToDevice) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Cast,
                                    contentDescription = stringResource(R.string.stream_play),
                                    tint = Color.White
                                )
                            }
                        }
                    }
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

        ViewerActionHud(
            visible = isUiVisible && !isLandscape,
            isLandscape = isLandscape,
            isFavorite = uiState.isFavorite,
            isHide = uiState.isHide,
            canShowPeopleInfo = currentPhoto.cloudId != null,
            isPeopleInfoVisible = uiState.isPeopleInfoVisible,
            isLoadingPeopleDescriptors = uiState.isLoadingPeopleDescriptors,
            canShowOcrInfo = currentPhoto.cloudId != null && !currentPhoto.isPlayableMedia(),
            isOcrInfoVisible = uiState.isOcrInfoVisible,
            isLoadingOcrInfo = uiState.isLoadingOcrInfo,
            onShare = { viewModel.sharePhoto(context) },
            onToggleFavorite = { viewModel.toggleFavorite() },
            onToggleHide = { viewModel.toggleHide() },
            onPeople = { viewModel.togglePeopleInfo() },
            onOcr = { viewModel.toggleOcrInfo() },
            onDelete = { showDeleteDialog = true },
            onInfo = { showBottomSheet = true }
        )

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
                    viewModel.deleteCurrentPhoto(onDeleted = { hasRemainingPhotos ->
                        if (!hasRemainingPhotos) {
                            stopAndGoBack()
                        }
                    })
                }
            },
            onDismiss = { showDeleteDialog = false }
        )
    }

    if (showCastDeviceDialog) {
        CastDeviceDialog(
            devices = uiState.castDevices,
            isDiscovering = uiState.isDiscoveringCastDevices,
            isCasting = uiState.isCastingToDevice,
            onRefresh = { viewModel.startCastDeviceDiscovery() },
            onSelectDevice = { device -> viewModel.castCurrentMedia(device) },
            onDismiss = { showCastDeviceDialog = false }
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
                            viewModel.deleteCurrentPhoto(onDeleted = { hasRemainingPhotos ->
                                if (!hasRemainingPhotos) {
                                    stopAndGoBack()
                                }
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
private fun CastDeviceDialog(
    devices: List<DlnaCastDevice>,
    isDiscovering: Boolean,
    isCasting: Boolean,
    onRefresh: () -> Unit,
    onSelectDevice: (DlnaCastDevice) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = {
            if (!isCasting) onDismiss()
        },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Cast,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = stringResource(R.string.cast_devices))
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                when {
                    isDiscovering && devices.isEmpty() -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 20.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(text = stringResource(R.string.searching_cast_devices))
                        }
                    }
                    devices.isEmpty() -> {
                        Text(
                            text = stringResource(R.string.no_cast_devices_found),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 12.dp)
                        )
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 320.dp)
                        ) {
                            items(devices, key = { it.id }) { device ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable(enabled = !isCasting) { onSelectDevice(device) }
                                        .padding(horizontal = 8.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Tv,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = device.name,
                                            style = MaterialTheme.typography.bodyLarge,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = URIHost(device.controlUrl),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    if (isCasting) {
                                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onRefresh,
                enabled = !isDiscovering && !isCasting
            ) {
                Text(text = stringResource(R.string.refresh_btn))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isCasting
            ) {
                Text(text = stringResource(R.string.cancel))
            }
        }
    )
}

private fun URIHost(url: String): String {
    return runCatching { java.net.URI(url).host }.getOrNull().orEmpty()
}

/**
 * Manages system bar visibility and appearance while the viewer is active.
 *
 * Returns a **restore** lambda that the caller must invoke **before** navigating
 * away (i.e. before `onBack()`). This ensures the system bars and their insets
 * are fully restored *before* the previous screen recomposes, preventing the
 * content-under-status-bar glitch that occurs when `DisposableEffect.onDispose`
 * fires too late (during the exit animation).
 *
 * The `DisposableEffect` still acts as a safety net in case the composable is
 * removed without the lambda being called.
 */
@Composable
private fun ViewerSystemBarsEffect(
    isUiVisible: Boolean,
    isLandscape: Boolean
): () -> Unit {
    val view = LocalView.current
    if (view.isInEditMode) return {}

    val window = view.context.findActivity()?.window ?: return {}
    val insetsController = remember(window, view) {
        WindowCompat.getInsetsController(window, view)
    }
    val previousSystemBarsBehavior = remember(insetsController) {
        insetsController.systemBarsBehavior
    }
    val previousLightStatusBars = remember(insetsController) {
        insetsController.isAppearanceLightStatusBars
    }
    val previousLightNavigationBars = remember(insetsController) {
        insetsController.isAppearanceLightNavigationBars
    }

    // Flag to avoid double-restoring from both the eager call and onDispose.
    val restored = remember { mutableStateOf(false) }

    val restore: () -> Unit = remember(insetsController) {
        {
            if (!restored.value) {
                restored.value = true
                // IMPORTANT: Restore behavior BEFORE showing bars.
                // With BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE still active,
                // show() would display bars as transient overlays that report
                // zero insets — causing the returning screen to render content
                // inside the status bar area.
                insetsController.systemBarsBehavior = previousSystemBarsBehavior
                insetsController.isAppearanceLightStatusBars = previousLightStatusBars
                insetsController.isAppearanceLightNavigationBars = previousLightNavigationBars
                insetsController.show(WindowInsetsCompat.Type.systemBars())
                // Force the framework to re-dispatch insets so that Compose's
                // WindowInsets.statusBars picks up the restored values immediately.
                ViewCompat.requestApplyInsets(view)
            }
        }
    }

    SideEffect {
        if (!restored.value) {
            insetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsController.isAppearanceLightStatusBars = false
            insetsController.isAppearanceLightNavigationBars = false

            val statusBars = WindowInsetsCompat.Type.statusBars()
            val navigationBars = WindowInsetsCompat.Type.navigationBars()
            val systemBars = WindowInsetsCompat.Type.systemBars()
            when {
                !isUiVisible -> insetsController.hide(systemBars)
                isLandscape -> {
                    insetsController.show(navigationBars)
                    insetsController.hide(statusBars)
                }
                else -> insetsController.show(systemBars)
            }
        }
    }

    DisposableEffect(insetsController) {
        onDispose {
            restore()
        }
    }

    return restore
}

@Composable
private fun ViewerLandscapeActionBarActions(
    isFavorite: Boolean,
    isHide: Boolean,
    canShowPeopleInfo: Boolean,
    isPeopleInfoVisible: Boolean,
    isLoadingPeopleDescriptors: Boolean,
    canShowOcrInfo: Boolean,
    isOcrInfoVisible: Boolean,
    isLoadingOcrInfo: Boolean,
    onShare: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleHide: () -> Unit,
    onPeople: () -> Unit,
    onOcr: () -> Unit,
    onDelete: () -> Unit,
    onInfo: () -> Unit
) {
    val favScale by animateFloatAsState(
        targetValue = if (isFavorite) 1.25f else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "favorite_actionbar_scale"
    )

    ViewerActionButtons(
        isLandscape = false,
        showLabels = false,
        isFavorite = isFavorite,
        isHide = isHide,
        canShowPeopleInfo = canShowPeopleInfo,
        isPeopleInfoVisible = isPeopleInfoVisible,
        isLoadingPeopleDescriptors = isLoadingPeopleDescriptors,
        canShowOcrInfo = canShowOcrInfo,
        isOcrInfoVisible = isOcrInfoVisible,
        isLoadingOcrInfo = isLoadingOcrInfo,
        favScale = favScale,
        onShare = onShare,
        onToggleFavorite = onToggleFavorite,
        onToggleHide = onToggleHide,
        onPeople = onPeople,
        onOcr = onOcr,
        onDelete = onDelete,
        onInfo = onInfo
    )
}

@Composable
private fun BoxScope.ViewerActionHud(
    visible: Boolean,
    isLandscape: Boolean,
    isFavorite: Boolean,
    isHide: Boolean,
    canShowPeopleInfo: Boolean,
    isPeopleInfoVisible: Boolean,
    isLoadingPeopleDescriptors: Boolean,
    canShowOcrInfo: Boolean,
    isOcrInfoVisible: Boolean,
    isLoadingOcrInfo: Boolean,
    onShare: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleHide: () -> Unit,
    onPeople: () -> Unit,
    onOcr: () -> Unit,
    onDelete: () -> Unit,
    onInfo: () -> Unit
) {
    val favScale by animateFloatAsState(
        targetValue = if (isFavorite) 1.25f else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "favorite_scale"
    )

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.align(if (isLandscape) Alignment.CenterEnd else Alignment.BottomCenter)
    ) {
        if (isLandscape) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(104.dp)
                    .background(
                        brush = Brush.horizontalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.82f))
                        )
                    )
                    .navigationBarsPadding()
                    .statusBarsPadding()
                    .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)
                ) {
                    ViewerActionButtons(
                        isLandscape = true,
                        isFavorite = isFavorite,
                        isHide = isHide,
                        canShowPeopleInfo = canShowPeopleInfo,
                        isPeopleInfoVisible = isPeopleInfoVisible,
                        isLoadingPeopleDescriptors = isLoadingPeopleDescriptors,
                        canShowOcrInfo = canShowOcrInfo,
                        isOcrInfoVisible = isOcrInfoVisible,
                        isLoadingOcrInfo = isLoadingOcrInfo,
                        favScale = favScale,
                        onShare = onShare,
                        onToggleFavorite = onToggleFavorite,
                        onToggleHide = onToggleHide,
                        onPeople = onPeople,
                        onOcr = onOcr,
                        onDelete = onDelete,
                        onInfo = onInfo
                    )
                }
            }
        } else {
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
                    ViewerActionButtons(
                        isLandscape = false,
                        isFavorite = isFavorite,
                        isHide = isHide,
                        canShowPeopleInfo = canShowPeopleInfo,
                        isPeopleInfoVisible = isPeopleInfoVisible,
                        isLoadingPeopleDescriptors = isLoadingPeopleDescriptors,
                        canShowOcrInfo = canShowOcrInfo,
                        isOcrInfoVisible = isOcrInfoVisible,
                        isLoadingOcrInfo = isLoadingOcrInfo,
                        favScale = favScale,
                        onShare = onShare,
                        onToggleFavorite = onToggleFavorite,
                        onToggleHide = onToggleHide,
                        onPeople = onPeople,
                        onOcr = onOcr,
                        onDelete = onDelete,
                        onInfo = onInfo
                    )
                }
            }
        }
    }
}

@Composable
private fun ViewerActionButtons(
    isLandscape: Boolean,
    showLabels: Boolean = true,
    isFavorite: Boolean,
    isHide: Boolean,
    canShowPeopleInfo: Boolean,
    isPeopleInfoVisible: Boolean,
    isLoadingPeopleDescriptors: Boolean,
    canShowOcrInfo: Boolean,
    isOcrInfoVisible: Boolean,
    isLoadingOcrInfo: Boolean,
    favScale: Float,
    onShare: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleHide: () -> Unit,
    onPeople: () -> Unit,
    onOcr: () -> Unit,
    onDelete: () -> Unit,
    onInfo: () -> Unit
) {
    val content: @Composable () -> Unit = {
        HUDButton(
            icon = Icons.Default.Share,
            label = stringResource(R.string.share),
            showLabel = showLabels,
            onClick = onShare
        )
        HUDButton(
            icon = if (isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
            label = stringResource(R.string.favorite),
            iconColor = if (isFavorite) Color(0xFFFFD700) else Color.White,
            iconScale = favScale,
            showLabel = showLabels,
            onClick = onToggleFavorite
        )
        HUDButton(
            icon = if (isHide) Icons.Default.Visibility else Icons.Default.VisibilityOff,
            label = stringResource(R.string.hide),
            showLabel = showLabels,
            onClick = onToggleHide
        )
        HUDButton(
            icon = Icons.Default.Person,
            label = stringResource(R.string.people),
            iconColor = if (isPeopleInfoVisible || isLoadingPeopleDescriptors) Color(0xFFFFD166) else Color.White,
            enabled = canShowPeopleInfo && !isLoadingPeopleDescriptors,
            showLabel = showLabels,
            onClick = onPeople
        )
        HUDButton(
            icon = Icons.Default.TextFields,
            label = stringResource(R.string.ocr),
            iconColor = if (isOcrInfoVisible || isLoadingOcrInfo) Color(0xFFFFD166) else Color.White,
            enabled = canShowOcrInfo && !isLoadingOcrInfo,
            showLabel = showLabels,
            onClick = onOcr
        )
        HUDButton(
            icon = Icons.Default.Delete,
            label = stringResource(R.string.delete),
            showLabel = showLabels,
            onClick = onDelete
        )
        HUDButton(
            icon = Icons.Default.Info,
            label = stringResource(R.string.info),
            showLabel = showLabels,
            onClick = onInfo
        )
    }

    if (isLandscape) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            content = { content() }
        )
    } else {
        Row(
            horizontalArrangement = Arrangement.spacedBy(
                if (showLabels) 12.dp else 0.dp,
                Alignment.CenterHorizontally
            ),
            verticalAlignment = Alignment.CenterVertically,
            content = { content() }
        )
    }
}

@Composable
private fun HUDButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    iconColor: Color = Color.White,
    iconScale: Float = 1.0f,
    enabled: Boolean = true,
    showLabel: Boolean = true,
    onClick: () -> Unit
) {
    val alpha = if (enabled) 1f else 0.38f
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .then(if (showLabel) Modifier else Modifier.size(40.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(8.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = iconColor.copy(alpha = alpha),
            modifier = Modifier
                .size(24.dp)
                .graphicsLayer {
                    scaleX = iconScale
                    scaleY = iconScale
                }
        )
        if (showLabel) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.85f * alpha)
            )
        }
    }
}

@Composable
private fun PeopleInfoListOverlay(
    descriptors: List<PeopleDescriptorItem>,
    isLoading: Boolean,
    portraitUrlProvider: (PeopleDescriptorItem) -> String?,
    isLandscape: Boolean = false,
    hazeState: HazeState? = null,
    modifier: Modifier = Modifier
) {
    val people = remember(descriptors) {
        descriptors.distinctBy { descriptor ->
            descriptor.person.id.takeIf { it > 0.0 }?.toString() ?: descriptor.person.name
        }
    }

    val glassModifier = if (hazeState != null) {
        modifier.frostedGlassEffect(
            state = hazeState,
            tintAlpha = 0.4f,
            blurRadius = 24.dp,
            showTopDivider = false
        )
    } else {
        modifier.background(Color.Black.copy(alpha = 0.68f), RoundedCornerShape(24.dp))
    }

    Surface(
        modifier = glassModifier,
        color = Color.Transparent,
        contentColor = Color.White,
        shape = RoundedCornerShape(24.dp)
    ) {
        if (isLoading) {
            Box(
                modifier = Modifier.padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = Color.White,
                    strokeWidth = 2.dp
                )
            }
        } else if (people.isEmpty()) {
            Text(
                text = stringResource(R.string.no_named_people),
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
        } else {
            if (isLandscape) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(vertical = 14.dp, horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    items(people) { descriptor ->
                        PeoplePortraitItem(descriptor, portraitUrlProvider)
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    people.forEach { descriptor ->
                        PeoplePortraitItem(descriptor, portraitUrlProvider)
                    }
                }
            }
        }
    }
}

@Composable
private fun PeoplePortraitItem(
    descriptor: PeopleDescriptorItem,
    portraitUrlProvider: (PeopleDescriptorItem) -> String?
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(58.dp)
    ) {
        val portraitUrl = portraitUrlProvider(descriptor)
        if (portraitUrl != null) {
            AsyncImage(
                model = portraitUrl,
                contentDescription = descriptor.person.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape)
            )
        } else {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.16f))
                    .border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = descriptor.person.name,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = descriptor.person.name,
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun VideoPoster(
    photo: UnifiedPhotoItem,
    thumbUrl: String,
    showLoading: Boolean,
    onTap: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(onClick = onTap),
        contentAlignment = Alignment.Center
    ) {
        if (thumbUrl.isNotEmpty()) {
            AsyncImage(
                model = thumbUrl,
                contentDescription = photo.fileName,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                imageVector = Icons.Default.Movie,
                contentDescription = photo.fileName,
                tint = Color.White.copy(alpha = 0.55f),
                modifier = Modifier.size(56.dp)
            )
        }

        if (showLoading) {
            CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 2.dp,
                modifier = Modifier.size(32.dp)
            )
        } else {
            Icon(
                imageVector = Icons.Default.PlayCircle,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.86f),
                modifier = Modifier.size(56.dp)
            )
        }
    }
}

@Composable
private fun ZoomableImage(
    photo: UnifiedPhotoItem,
    imageUrl: String,
    contentDescription: String,
    onTap: () -> Unit,
    onZoomedChanged: (Boolean) -> Unit,
    peopleDescriptors: List<PeopleDescriptorItem> = emptyList(),
    isPeopleInfoVisible: Boolean = false,
    isLoadingPeopleInfo: Boolean = false,
    ocrItems: List<OcrInfoItem> = emptyList(),
    isOcrInfoVisible: Boolean = false,
    isLoadingOcrInfo: Boolean = false,
    onDismissPeopleInfo: () -> Unit = {},
    onDismissOcrInfo: () -> Unit = {}
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val app = context.applicationContext as com.kqstone.mtphotos.MTPhotosApp

    val imageRequest = remember(imageUrl, photo.md5) {
        val cacheKey = if (imageUrl.contains("/gateway/stream", ignoreCase = true)) {
            "${photo.md5}_stream_${imageUrl.hashCode()}"
        } else {
            "${photo.md5}_full"
        }
        coil.request.ImageRequest.Builder(context)
            .data(imageUrl)
            .diskCacheKey(cacheKey)
            .memoryCacheKey(cacheKey)
            .diskCachePolicy(coil.request.CachePolicy.ENABLED)
            .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
            .build()
    }

    var scale by remember(photo.uniqueKey) { mutableFloatStateOf(MinZoomScale) }
    var offset by remember(photo.uniqueKey) { mutableStateOf(Offset.Zero) }
    var containerSize by remember(photo.uniqueKey) { mutableStateOf(IntSize.Zero) }
    var lastTapTime by remember(photo.uniqueKey) { mutableStateOf(0L) }
    val tapScope = rememberCoroutineScope()
    var pendingTapJob by remember(photo.uniqueKey) { mutableStateOf<Job?>(null) }
    var transformAnimationJob by remember(photo.uniqueKey) { mutableStateOf<Job?>(null) }

    LaunchedEffect(scale) {
        onZoomedChanged(scale > ZoomedThreshold)
    }

    LaunchedEffect(photo.uniqueKey) {
        scale = MinZoomScale
        offset = Offset.Zero
        onZoomedChanged(false)
    }

    DisposableEffect(photo.uniqueKey) {
        onDispose {
            pendingTapJob?.cancel()
            transformAnimationJob?.cancel()
            onZoomedChanged(false)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { containerSize = it }
            .pointerInput(photo.uniqueKey, containerSize, peopleDescriptors, isPeopleInfoVisible, ocrItems, isOcrInfoVisible) {
                awaitEachGesture {
                    val firstDown = awaitFirstDown(requireUnconsumed = true)
                    val gestureStartedInOcrBox = isOcrInfoVisible && isTapInsideOcrBox(
                        point = firstDown.position,
                        items = ocrItems,
                        containerSize = containerSize,
                        photo = photo,
                        scale = scale,
                        offset = offset
                    )
                    if (gestureStartedInOcrBox) {
                        do {
                            val event = awaitPointerEvent()
                        } while (event.changes.any { it.pressed })
                        return@awaitEachGesture
                    }

                    var isTapCandidate = true
                    var wasTransforming = false
                    var totalPan = Offset.Zero

                    transformAnimationJob?.cancel()

                    do {
                        val event = awaitPointerEvent()
                        val zoom = event.calculateZoom()
                        val centroid = event.calculateCentroid(useCurrent = true)
                        val pan = event.calculateAveragePan()
                        totalPan += pan

                        val isZoomGesture = abs(zoom - 1f) > ZoomChangeSlop
                        val isPanGesture = pan.getDistanceSquared() > PanSlopSquared
                        val shouldPanImage = scale > ZoomedThreshold && pan != Offset.Zero
                        val shouldHandoffToPager = shouldPanImage &&
                            !isZoomGesture &&
                            canHandoffToPager(
                                pan = pan,
                                offset = offset,
                                scale = scale,
                                containerSize = containerSize,
                                photo = photo
                            )

                        if (shouldHandoffToPager) {
                            pendingTapJob?.cancel()
                            isTapCandidate = false
                        } else if (isZoomGesture || shouldPanImage) {
                            pendingTapJob?.cancel()
                            isTapCandidate = false
                            wasTransforming = true

                            val oldScale = scale
                            val newScale = (oldScale * zoom).coerceIn(MinZoomScale, MaxZoomScale)
                            val center = Offset(containerSize.width / 2f, containerSize.height / 2f)
                            val zoomOffset = if (isZoomGesture && oldScale > 0f) {
                                (centroid - center - offset) *
                                    (1f - newScale / oldScale)
                            } else {
                                Offset.Zero
                            }

                            scale = newScale
                            offset += pan + zoomOffset
                            event.changes.forEach { it.consume() }
                        } else if (isPanGesture) {
                            isTapCandidate = false
                        }
                    } while (event.changes.any { it.pressed })

                    val clampedOffset = clampOffset(
                        offset = offset,
                        scale = scale,
                        containerSize = containerSize,
                        photo = photo
                    )

                    if (scale <= ZoomedThreshold) {
                        transformAnimationJob = tapScope.launch {
                            animateTransform(
                                startScale = scale,
                                startOffset = offset,
                                targetScale = MinZoomScale,
                                targetOffset = Offset.Zero,
                                onFrame = { nextScale, nextOffset ->
                                    scale = nextScale
                                    offset = nextOffset
                                }
                            )
                        }
                    } else {
                        transformAnimationJob = tapScope.launch {
                            animateTransform(
                                startScale = scale,
                                startOffset = offset,
                                targetScale = scale,
                                targetOffset = clampedOffset,
                                onFrame = { nextScale, nextOffset ->
                                    scale = nextScale
                                    offset = nextOffset
                                }
                            )
                        }
                    }

                    if (isTapCandidate && !wasTransforming && totalPan.getDistanceSquared() < TapSlopSquared) {
                        val now = System.currentTimeMillis()
                        val isDoubleTap = now - lastTapTime < DoubleTapWindowMillis
                        lastTapTime = if (isDoubleTap) 0L else now

                        if (isDoubleTap && scale > ZoomedThreshold) {
                            pendingTapJob?.cancel()
                            transformAnimationJob?.cancel()
                            transformAnimationJob = tapScope.launch {
                                animateTransform(
                                    startScale = scale,
                                    startOffset = offset,
                                    targetScale = MinZoomScale,
                                    targetOffset = Offset.Zero,
                                    onFrame = { nextScale, nextOffset ->
                                        scale = nextScale
                                        offset = nextOffset
                                    }
                                )
                            }
                        } else if (isDoubleTap) {
                            pendingTapJob?.cancel()
                            transformAnimationJob?.cancel()
                            val targetScale = DoubleTapZoomScale.coerceAtMost(MaxZoomScale)
                            val center = Offset(containerSize.width / 2f, containerSize.height / 2f)
                            val tapOffset = firstDown.position - center
                            val targetOffset = clampOffset(
                                offset = tapOffset * (1f - targetScale / MinZoomScale),
                                scale = targetScale,
                                containerSize = containerSize,
                                photo = photo
                            )
                            transformAnimationJob = tapScope.launch {
                                animateTransform(
                                    startScale = scale,
                                    startOffset = offset,
                                    targetScale = targetScale,
                                    targetOffset = targetOffset,
                                    onFrame = { nextScale, nextOffset ->
                                        scale = nextScale
                                        offset = nextOffset
                                    }
                                )
                            }
                        } else {
                            pendingTapJob?.cancel()
                            pendingTapJob = tapScope.launch {
                                delay(DoubleTapWindowMillis)
                                val isInsidePeopleBox = isTapInsidePeopleBox(
                                    point = firstDown.position,
                                    descriptors = peopleDescriptors,
                                    containerSize = containerSize,
                                    photo = photo,
                                    scale = scale,
                                    offset = offset
                                )
                                val isInsideOcrBox = isTapInsideOcrBox(
                                    point = firstDown.position,
                                    items = ocrItems,
                                    containerSize = containerSize,
                                    photo = photo,
                                    scale = scale,
                                    offset = offset
                                )
                                if (isPeopleInfoVisible && !isInsidePeopleBox) {
                                    onDismissPeopleInfo()
                                } else if (isOcrInfoVisible && !isInsideOcrBox) {
                                    onDismissOcrInfo()
                                } else if (!isPeopleInfoVisible) {
                                    onTap()
                                }
                            }
                        }
                    }
                }
            }
    ) {
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
        )
        if (isPeopleInfoVisible || isLoadingPeopleInfo) {
            PeopleImageOverlay(
                descriptors = peopleDescriptors,
                isLoading = isLoadingPeopleInfo,
                containerSize = containerSize,
                photo = photo,
                scale = scale,
                offset = offset
            )
        }
        if (isOcrInfoVisible || isLoadingOcrInfo) {
            OcrImageOverlay(
                items = ocrItems,
                isLoading = isLoadingOcrInfo,
                containerSize = containerSize,
                photo = photo,
                scale = scale,
                offset = offset
            )
        }
    }
}

@Composable
private fun PeopleImageOverlay(
    descriptors: List<PeopleDescriptorItem>,
    isLoading: Boolean,
    containerSize: IntSize,
    photo: UnifiedPhotoItem,
    scale: Float,
    offset: Offset
) {
    val density = LocalDensity.current
    val boxes = remember(descriptors, containerSize, photo.uniqueKey, scale, offset) {
        descriptors.mapNotNull { descriptor ->
            peopleBoxLayout(
                descriptor = descriptor,
                containerSize = containerSize,
                photo = photo,
                scale = scale,
                offset = offset
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawBehind {
                val maskPath = Path().apply {
                    addRect(Rect(0f, 0f, size.width, size.height))
                    boxes.forEach { box ->
                        addRoundRect(
                            RoundRect(
                                rect = Rect(
                                    left = box.left,
                                    top = box.top,
                                    right = box.left + box.width,
                                    bottom = box.top + box.height
                                ),
                                cornerRadius = CornerRadius(12.dp.toPx())
                            )
                        )
                    }
                    fillType = PathFillType.EvenOdd
                }
                drawPath(maskPath, Color.Black.copy(alpha = 0.65f))
            }
    ) {
        if (isLoading) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 84.dp),
                color = Color.Black.copy(alpha = 0.58f),
                shape = RoundedCornerShape(18.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.loading_people),
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }

        boxes.forEach { box ->
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(box.left.roundToInt(), box.top.roundToInt())
                    }
                    .width(with(density) { box.width.toDp() })
                    .height(with(density) { box.height.toDp() })
                    .drawFocusBrackets(
                        color = Color(0xFFFFD166),
                        strokeWidthDp = 2.dp,
                        cornerLengthRatio = 0.2f
                    )
            )
            val pillHeightPx = with(density) { 26.dp.toPx() }
            val pillPaddingPx = with(density) { 6.dp.toPx() }
            val pillTop = when {
                box.top >= pillHeightPx + pillPaddingPx -> {
                    box.top - pillHeightPx - pillPaddingPx
                }
                box.top + box.height + pillHeightPx + pillPaddingPx <= containerSize.height -> {
                    box.top + box.height + pillPaddingPx
                }
                else -> {
                    box.top + pillPaddingPx
                }
            }
            val safeLeft = box.left.coerceIn(8f, (containerSize.width - 60f).coerceAtLeast(8f))
            Surface(
                modifier = Modifier
                    .offset {
                        IntOffset(safeLeft.roundToInt(), pillTop.roundToInt())
                    },
                color = Color.Black.copy(alpha = 0.6f),
                contentColor = Color(0xFFFFD166),
                shape = RoundedCornerShape(999.dp)
            ) {
                Text(
                    text = box.name,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private fun Modifier.drawFocusBrackets(
    color: Color,
    strokeWidthDp: androidx.compose.ui.unit.Dp,
    cornerLengthRatio: Float
): Modifier = this.drawBehind {
    val strokeWidth = strokeWidthDp.toPx()
    val cornerLength = minOf(size.width, size.height) * cornerLengthRatio

    drawLine(color, Offset(0f, strokeWidth / 2), Offset(cornerLength, strokeWidth / 2), strokeWidth)
    drawLine(color, Offset(strokeWidth / 2, 0f), Offset(strokeWidth / 2, cornerLength), strokeWidth)
    drawLine(color, Offset(size.width - cornerLength, strokeWidth / 2), Offset(size.width, strokeWidth / 2), strokeWidth)
    drawLine(color, Offset(size.width - strokeWidth / 2, 0f), Offset(size.width - strokeWidth / 2, cornerLength), strokeWidth)
    drawLine(color, Offset(0f, size.height - strokeWidth / 2), Offset(cornerLength, size.height - strokeWidth / 2), strokeWidth)
    drawLine(color, Offset(strokeWidth / 2, size.height - cornerLength), Offset(strokeWidth / 2, size.height), strokeWidth)
    drawLine(color, Offset(size.width - cornerLength, size.height - strokeWidth / 2), Offset(size.width, size.height - strokeWidth / 2), strokeWidth)
    drawLine(color, Offset(size.width - strokeWidth / 2, size.height - cornerLength), Offset(size.width - strokeWidth / 2, size.height), strokeWidth)
}

@Composable
private fun OcrImageOverlay(
    items: List<OcrInfoItem>,
    isLoading: Boolean,
    containerSize: IntSize,
    photo: UnifiedPhotoItem,
    scale: Float,
    offset: Offset
) {
    val density = LocalDensity.current
    val boxes = remember(items, containerSize, photo.uniqueKey, scale, offset) {
        items.mapNotNull { item ->
            ocrBoxLayout(
                item = item,
                containerSize = containerSize,
                photo = photo,
                scale = scale,
                offset = offset
            )
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (isLoading) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 84.dp),
                color = Color.Black.copy(alpha = 0.58f),
                shape = RoundedCornerShape(18.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.loading_ocr),
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }

        SelectionContainer {
            Box(modifier = Modifier.fillMaxSize()) {
                boxes.forEach { box ->
                    val fontSize = with(density) { box.fontSizePx.toSp() }
                    val lineHeight = with(density) { (box.fontSizePx * 1.08f).toSp() }
                    val maxLines = (box.height / (box.fontSizePx * 1.08f))
                        .toInt()
                        .coerceAtLeast(1)

                    Box(
                        modifier = Modifier
                            .offset {
                                IntOffset(box.left.roundToInt(), box.top.roundToInt())
                            }
                            .width(with(density) { box.width.toDp() })
                            .height(with(density) { box.height.toDp() })
                            .clipToBounds()
                            .background(Color.White)
                            .padding(horizontal = 1.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Text(
                            text = box.text,
                            color = Color.Black,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = fontSize,
                                lineHeight = lineHeight,
                                fontWeight = FontWeight.Medium
                            ),
                            maxLines = maxLines,
                            overflow = TextOverflow.Clip
                        )
                    }
                }
            }
        }
    }
}

private data class PeopleBoxLayout(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    val name: String
)

private data class OcrBoxLayout(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    val text: String,
    val fontSizePx: Float
)

private fun isTapInsidePeopleBox(
    point: Offset,
    descriptors: List<PeopleDescriptorItem>,
    containerSize: IntSize,
    photo: UnifiedPhotoItem,
    scale: Float,
    offset: Offset
): Boolean {
    return descriptors.any { descriptor ->
        val box = peopleBoxLayout(descriptor, containerSize, photo, scale, offset) ?: return@any false
        point.x in box.left..(box.left + box.width) &&
            point.y in box.top..(box.top + box.height)
    }
}

private fun isTapInsideOcrBox(
    point: Offset,
    items: List<OcrInfoItem>,
    containerSize: IntSize,
    photo: UnifiedPhotoItem,
    scale: Float,
    offset: Offset
): Boolean {
    return items.any { item ->
        val box = ocrBoxLayout(item, containerSize, photo, scale, offset) ?: return@any false
        point.x in box.left..(box.left + box.width) &&
            point.y in box.top..(box.top + box.height)
    }
}

private fun peopleBoxLayout(
    descriptor: PeopleDescriptorItem,
    containerSize: IntSize,
    photo: UnifiedPhotoItem,
    scale: Float,
    offset: Offset
): PeopleBoxLayout? {
    if (containerSize.width <= 0 || containerSize.height <= 0) return null
    val imageWidth = photo.width.toFloat().takeIf { it > 0f } ?: return null
    val imageHeight = photo.height.toFloat().takeIf { it > 0f } ?: return null
    val fitScale = minOf(
        containerSize.width / imageWidth,
        containerSize.height / imageHeight
    )
    if (fitScale <= 0f) return null

    val fittedWidth = imageWidth * fitScale
    val fittedHeight = imageHeight * fitScale
    val imageLeft = (containerSize.width - fittedWidth) / 2f
    val imageTop = (containerSize.height - fittedHeight) / 2f
    val baseLeft = imageLeft + descriptor.box.x.toFloat() * fitScale
    val baseTop = imageTop + descriptor.box.y.toFloat() * fitScale
    val baseWidth = descriptor.box.width.toFloat() * fitScale
    val baseHeight = descriptor.box.height.toFloat() * fitScale
    val center = Offset(containerSize.width / 2f, containerSize.height / 2f)

    return PeopleBoxLayout(
        left = center.x + (baseLeft - center.x) * scale + offset.x,
        top = center.y + (baseTop - center.y) * scale + offset.y,
        width = baseWidth * scale,
        height = baseHeight * scale,
        name = descriptor.person.name
    )
}

private fun ocrBoxLayout(
    item: OcrInfoItem,
    containerSize: IntSize,
    photo: UnifiedPhotoItem,
    scale: Float,
    offset: Offset
): OcrBoxLayout? {
    if (containerSize.width <= 0 || containerSize.height <= 0) return null
    val imageWidth = photo.width.toFloat().takeIf { it > 0f } ?: return null
    val imageHeight = photo.height.toFloat().takeIf { it > 0f } ?: return null
    val fitScale = minOf(
        containerSize.width / imageWidth,
        containerSize.height / imageHeight
    )
    if (fitScale <= 0f) return null

    val fittedWidth = imageWidth * fitScale
    val fittedHeight = imageHeight * fitScale
    val imageLeft = (containerSize.width - fittedWidth) / 2f
    val imageTop = (containerSize.height - fittedHeight) / 2f
    val baseLeft = imageLeft + item.x.toFloat() * fitScale
    val baseTop = imageTop + item.y.toFloat() * fitScale
    val baseWidth = item.width.toFloat() * fitScale
    val baseHeight = item.height.toFloat() * fitScale
    val center = Offset(containerSize.width / 2f, containerSize.height / 2f)
    val textLength = item.text.length.coerceAtLeast(1)
    val width = baseWidth * scale
    val height = baseHeight * scale
    val fontSizePx = minOf(
        height * 0.78f,
        width / (textLength * 0.62f)
    ).coerceIn(8f, 160f)

    return OcrBoxLayout(
        left = center.x + (baseLeft - center.x) * scale + offset.x,
        top = center.y + (baseTop - center.y) * scale + offset.y,
        width = width,
        height = height,
        text = item.text,
        fontSizePx = fontSizePx
    )
}

private const val MinZoomScale = 1f
private const val MaxZoomScale = 5f
private const val DoubleTapZoomScale = 2.5f
private const val ZoomedThreshold = 1.01f
private const val ZoomChangeSlop = 0.01f
private const val PanSlopSquared = 9f
private const val TapSlopSquared = 100f
private const val DoubleTapWindowMillis = 280L
private const val EdgeHandoffTolerance = 2f

private suspend fun animateTransform(
    startScale: Float,
    startOffset: Offset,
    targetScale: Float,
    targetOffset: Offset,
    onFrame: (Float, Offset) -> Unit
) {
    val scaleAnim = Animatable(startScale)
    val offsetXAnim = Animatable(startOffset.x)
    val offsetYAnim = Animatable(startOffset.y)
    val animationSpec = spring<Float>(stiffness = Spring.StiffnessMediumLow)

    kotlinx.coroutines.coroutineScope {
        launch {
            scaleAnim.animateTo(targetScale, animationSpec = animationSpec) {
                onFrame(value, Offset(offsetXAnim.value, offsetYAnim.value))
            }
        }
        launch {
            offsetXAnim.animateTo(targetOffset.x, animationSpec = animationSpec) {
                onFrame(scaleAnim.value, Offset(value, offsetYAnim.value))
            }
        }
        launch {
            offsetYAnim.animateTo(targetOffset.y, animationSpec = animationSpec) {
                onFrame(scaleAnim.value, Offset(offsetXAnim.value, value))
            }
        }
    }

    onFrame(targetScale, targetOffset)
}

private fun PointerEvent.calculateAveragePan(): Offset {
    var panX = 0f
    var panY = 0f
    var panCount = 0

    for (change in changes) {
        if (change.previousPressed) {
            panX += change.position.x - change.previousPosition.x
            panY += change.position.y - change.previousPosition.y
            panCount++
        }
    }

    return if (panCount > 0) Offset(panX / panCount, panY / panCount) else Offset.Zero
}

private fun canHandoffToPager(
    pan: Offset,
    offset: Offset,
    scale: Float,
    containerSize: IntSize,
    photo: UnifiedPhotoItem
): Boolean {
    if (scale <= ZoomedThreshold || abs(pan.x) <= abs(pan.y)) {
        return false
    }

    val maxOffset = maxOffsetFor(scale, containerSize, photo)
    if (maxOffset.x <= EdgeHandoffTolerance) {
        return true
    }

    val targetX = offset.x + pan.x
    val draggingPastLeftEdge = pan.x > 0f && targetX >= maxOffset.x - EdgeHandoffTolerance
    val draggingPastRightEdge = pan.x < 0f && targetX <= -maxOffset.x + EdgeHandoffTolerance

    return draggingPastLeftEdge || draggingPastRightEdge
}

private fun clampOffset(
    offset: Offset,
    scale: Float,
    containerSize: IntSize,
    photo: UnifiedPhotoItem
): Offset {
    val maxOffset = maxOffsetFor(scale, containerSize, photo)
    return Offset(
        x = offset.x.coerceIn(-maxOffset.x, maxOffset.x),
        y = offset.y.coerceIn(-maxOffset.y, maxOffset.y)
    )
}

private fun maxOffsetFor(
    scale: Float,
    containerSize: IntSize,
    photo: UnifiedPhotoItem
): Offset {
    if (containerSize.width <= 0 || containerSize.height <= 0 || scale <= MinZoomScale) {
        return Offset.Zero
    }

    val imageWidth = photo.width.toFloat().takeIf { it > 0f } ?: containerSize.width.toFloat()
    val imageHeight = photo.height.toFloat().takeIf { it > 0f } ?: containerSize.height.toFloat()
    val fitScale = minOf(
        containerSize.width / imageWidth,
        containerSize.height / imageHeight
    )
    val fittedWidth = imageWidth * fitScale
    val fittedHeight = imageHeight * fitScale

    return Offset(
        x = max(0f, (fittedWidth * scale - containerSize.width) / 2f),
        y = max(0f, (fittedHeight * scale - containerSize.height) / 2f)
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
