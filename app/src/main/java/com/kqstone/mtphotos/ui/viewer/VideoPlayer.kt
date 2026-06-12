package com.kqstone.mtphotos.ui.viewer

import android.view.LayoutInflater
import android.os.Handler
import android.os.Looper
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import android.content.res.Configuration
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.ui.PlayerView
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.kqstone.mtphotos.MTPhotosApp
import com.kqstone.mtphotos.R
import kotlinx.coroutines.delay

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun VideoPlayer(
    videoUrl: String,
    videoCacheKey: String? = stableVideoCacheKey(videoUrl),
    playerPool: VideoPlayerPool? = null,
    isCurrentPage: Boolean,
    isUiVisible: Boolean,
    onToggleUi: () -> Unit,
    onPlaybackError: (PlaybackException) -> Unit = {},
    onFirstFrameRendered: () -> Unit = {},
    onStopPlaybackReady: ((() -> Unit)?) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    val mediaItem = remember(videoUrl, videoCacheKey) {
        buildVideoMediaItem(videoUrl, videoCacheKey)
    }

    val exoPlayer = remember(videoUrl, videoCacheKey) {
        playerPool?.take(videoUrl, videoCacheKey)
            ?: createPreparedVideoPlayer(context, videoUrl, mediaItem)
    }

    var isPlaying by remember { mutableStateOf(false) }
    var playbackPosition by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var isMuted by remember { mutableStateOf(false) }

    val listener = remember {
        object : androidx.media3.common.Player.Listener {
            override fun onIsPlayingChanged(isPlayingChanged: Boolean) {
                isPlaying = isPlayingChanged
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == androidx.media3.common.Player.STATE_READY) {
                    duration = exoPlayer.duration.coerceAtLeast(0L)
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                onPlaybackError(error)
            }
            override fun onRenderedFirstFrame() {
                onFirstFrameRendered()
            }
        }
    }

    DisposableEffect(exoPlayer) {
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
        }
    }

    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            while (true) {
                playbackPosition = exoPlayer.currentPosition
                delay(200)
            }
        }
    }

    val pausePlayback = remember(exoPlayer) {
        {
            exoPlayer.playWhenReady = false
            exoPlayer.pause()
        }
    }

    LaunchedEffect(exoPlayer, isCurrentPage) {
        if (isCurrentPage) {
            exoPlayer.playWhenReady = true
            exoPlayer.play()
        } else {
            pausePlayback()
        }
    }

    DisposableEffect(pausePlayback) {
        onStopPlaybackReady(pausePlayback)
        onDispose { onStopPlaybackReady(null) }
    }

    DisposableEffect(exoPlayer) {
        onDispose {
            pausePlayback()
            Handler(Looper.getMainLooper()).postDelayed(
                {
                    exoPlayer.clearVideoSurface()
                    exoPlayer.release()
                },
                220L
            )
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                (LayoutInflater.from(ctx).inflate(R.layout.viewer_player_view, null) as PlayerView).apply {
                    player = exoPlayer
                    useController = false
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                }
            },
            update = { view ->
                if (view.player !== exoPlayer) view.player = exoPlayer
            },
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onToggleUi() }
                    )
                }
        )

        // Scrim layer at the bottom for better visibility
        AnimatedVisibility(
            visible = isUiVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (isLandscape) 120.dp else 200.dp)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))
                        )
                    )
            )
        }

        // Completely Custom Play/Pause Overlay Button in Center
        AnimatedVisibility(
            visible = isUiVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            val interactionSource = remember { MutableInteractionSource() }
            val isPressed by interactionSource.collectIsPressedAsState()
            val scale by animateFloatAsState(targetValue = if (isPressed) 0.85f else 1f, label = "playButtonScale")

            IconButton(
                onClick = {
                    if (isPlaying) {
                        exoPlayer.pause()
                    } else {
                        exoPlayer.play()
                    }
                },
                interactionSource = interactionSource,
                modifier = Modifier
                    .size(72.dp)
                    .scale(scale)
                    .background(Color.Black.copy(alpha = 0.4f), CircleShape)
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = stringResource(if (isPlaying) R.string.pause else R.string.play),
                    tint = Color.White,
                    modifier = Modifier.size(40.dp)
                )
            }
        }

        // Custom Bottom Controller Overlay (Seekbar, time, mute) with reduced width floating pill
        AnimatedVisibility(
            visible = isUiVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = if (isLandscape) 24.dp else 96.dp) // Floating above the HUD buttons bar
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(if (isLandscape) 0.8f else 0.9f)
                    .widthIn(max = 600.dp)
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val timeTextStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontFeatureSettings = "tnum"
                    )
                    Text(
                        text = formatTime(playbackPosition),
                        style = timeTextStyle,
                        color = Color.White
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    Slider(
                        value = playbackPosition.toFloat(),
                        onValueChange = { newValue ->
                            playbackPosition = newValue.toLong()
                            exoPlayer.seekTo(playbackPosition)
                        },
                        valueRange = 0f..(duration.toFloat().coerceAtLeast(1f)),
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = Color.White,
                            inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                        ),
                        modifier = Modifier.weight(1f),
                        thumb = {
                            Box(
                                modifier = Modifier.size(20.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .background(Color.White, CircleShape)
                                )
                            }
                        },
                        track = { sliderState ->
                            val fraction = if (sliderState.valueRange.endInclusive > sliderState.valueRange.start) {
                                (sliderState.value - sliderState.valueRange.start) / (sliderState.valueRange.endInclusive - sliderState.valueRange.start)
                            } else 0f
                            
                            Canvas(modifier = Modifier.fillMaxWidth().height(20.dp)) {
                                val width = size.width
                                val height = size.height
                                val activeWidth = width * fraction
                                val trackHeight = 2.dp.toPx()
                                val startY = (height - trackHeight) / 2f
                                
                                // Inactive track
                                drawRoundRect(
                                    color = Color.White.copy(alpha = 0.3f),
                                    topLeft = Offset(0f, startY),
                                    size = Size(width, trackHeight),
                                    cornerRadius = CornerRadius(trackHeight / 2)
                                )
                                // Active track
                                drawRoundRect(
                                    color = Color.White,
                                    topLeft = Offset(0f, startY),
                                    size = Size(activeWidth, trackHeight),
                                    cornerRadius = CornerRadius(trackHeight / 2)
                                )
                            }
                        }
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    Text(
                        text = formatTime(duration),
                        style = timeTextStyle,
                        color = Color.White
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    IconButton(
                        onClick = {
                            isMuted = !isMuted
                            exoPlayer.volume = if (isMuted) 0f else 1f
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = if (isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = stringResource(if (isMuted) R.string.unmute else R.string.mute),
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }
    }
}

class VideoPlayerPool(private val context: android.content.Context) {
    private val players = linkedMapOf<String, ExoPlayer>()

    fun prepare(sources: List<ViewerVideoSource>, activeIdentity: String?) {
        val desired = sources
            .asSequence()
            .filter { it.url.isNotEmpty() }
            .map { identityFor(it.url, it.cacheKey) to it }
            .filter { (identity, _) -> identity != activeIdentity }
            .distinctBy { it.first }
            .take(MAX_PREPARED_PLAYERS)
            .toList()
        val desiredIdentities = desired.map { it.first }.toSet()

        players.keys
            .filter { it !in desiredIdentities }
            .toList()
            .forEach { identity ->
                players.remove(identity)?.release()
            }

        desired.forEach { (identity, source) ->
            if (!players.containsKey(identity)) {
                val mediaItem = buildVideoMediaItem(source.url, source.cacheKey)
                players[identity] = createPreparedVideoPlayer(context, source.url, mediaItem)
            }
        }
    }

    fun take(videoUrl: String, videoCacheKey: String?): ExoPlayer? {
        return players.remove(identityFor(videoUrl, videoCacheKey))
    }

    fun releaseAll() {
        players.values.forEach { it.release() }
        players.clear()
    }

    private companion object {
        private const val MAX_PREPARED_PLAYERS = 2
    }
}

@Composable
fun rememberVideoPlayerPool(): VideoPlayerPool {
    val context = LocalContext.current.applicationContext
    val pool = remember { VideoPlayerPool(context) }
    DisposableEffect(pool) {
        onDispose { pool.releaseAll() }
    }
    return pool
}

private fun createPreparedVideoPlayer(
    context: android.content.Context,
    videoUrl: String,
    mediaItem: MediaItem
): ExoPlayer {
    return ExoPlayer.Builder(context)
        .setLoadControl(defaultVideoLoadControl())
        .build()
        .apply {
            playWhenReady = false
            if (videoUrl.startsWith("http://") || videoUrl.startsWith("https://")) {
                val app = context.applicationContext as MTPhotosApp
                val cacheDataSourceFactory = CacheDataSource.Factory()
                    .setCache(app.videoCache)
                    .setUpstreamDataSourceFactory(DefaultHttpDataSource.Factory())
                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                val mediaSource = ProgressiveMediaSource.Factory(cacheDataSourceFactory)
                    .createMediaSource(mediaItem)
                setMediaSource(mediaSource)
            } else {
                setMediaItem(mediaItem)
            }
            prepare()
        }
}

private fun buildVideoMediaItem(videoUrl: String, videoCacheKey: String?): MediaItem {
    return MediaItem.Builder()
        .setUri(videoUrl)
        .apply {
            if (!videoCacheKey.isNullOrBlank()) {
                setCustomCacheKey(videoCacheKey)
            }
        }
        .build()
}

private fun defaultVideoLoadControl(): DefaultLoadControl {
    return DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            15_000,
            50_000,
            500,
            1_000
        )
        .build()
}

private fun identityFor(videoUrl: String, videoCacheKey: String?): String {
    return videoCacheKey ?: videoUrl
}

private fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).toInt()
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}
