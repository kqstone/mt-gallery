package com.kqstone.mtphotos.ui.viewer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.kqstone.mtphotos.data.model.UnifiedPhotoItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(
    viewModel: ViewerViewModel,
    onBack: () -> Unit
) {
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
            Text("无照片", color = Color.White)
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 0,
            userScrollEnabled = true
        ) { page ->
            val photo = photos[page]
            val isCurrentPage = pagerState.settledPage == page
            val isPlayableMedia = photo.isPlayableMedia()

            if (isPlayableMedia && isCurrentPage) {
                val url = viewModel.getVideoUrl(photo)
                if (url.isNotEmpty()) {
                    VideoPlayer(
                        videoUrl = url,
                        isCurrentPage = true,
                        onStopPlaybackReady = { stopActivePlayback = it }
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Cannot play video", color = Color.White)
                    }
                }
            } else if (isPlayableMedia) {
                Box(modifier = Modifier.fillMaxSize())
            } else {
                ZoomableImage(
                    photo = photo,
                    imageUrl = viewModel.getFullImageUrl(photo),
                    contentDescription = photo.fileName
                )
            }
        }

        TopAppBar(
            title = {
                Text(
                    text = currentPhoto.fileName.ifEmpty { "照片 ${pagerState.settledPage + 1}/${photos.size}" },
                    color = Color.White
                )
            },
            navigationIcon = {
                IconButton(onClick = stopAndGoBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = Color.White
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Black.copy(alpha = 0.5f)
            ),
            modifier = Modifier.statusBarsPadding()
        )

        Text(
            text = "${pagerState.settledPage + 1} / ${photos.size}",
            style = MaterialTheme.typography.labelLarge,
            color = Color.White.copy(alpha = 0.85f),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp)
                .background(Color.Black.copy(alpha = 0.55f), shape = RoundedCornerShape(16.dp))
                .padding(horizontal = 16.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun ZoomableImage(
    photo: UnifiedPhotoItem,
    imageUrl: String,
    contentDescription: String
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

    var scale by remember { mutableFloatStateOf(1f) }
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

                    do {
                        val event = awaitPointerEvent()
                        val zoom = event.calculateZoom()

                        // Calculate pan from centroid movement
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
                }
            }
    )
}
