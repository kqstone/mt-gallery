package com.kqstone.mtphotos.ui.viewer

import android.view.LayoutInflater
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.kqstone.mtphotos.MTPhotosApp
import com.kqstone.mtphotos.R
import kotlinx.coroutines.delay

@Composable
fun VideoPlayer(
    videoUrl: String,
    isCurrentPage: Boolean,
    isUiVisible: Boolean,
    onToggleUi: () -> Unit,
    onStopPlaybackReady: ((() -> Unit)?) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val exoPlayer = remember(videoUrl) {
        ExoPlayer.Builder(context).build().apply {
            if (videoUrl.startsWith("http://") || videoUrl.startsWith("https://")) {
                val app = context.applicationContext as MTPhotosApp
                val simpleCache = app.videoCache
                val httpDataSourceFactory = DefaultHttpDataSource.Factory()
                val cacheDataSourceFactory = CacheDataSource.Factory()
                    .setCache(simpleCache)
                    .setUpstreamDataSourceFactory(httpDataSourceFactory)
                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                val mediaSource = ProgressiveMediaSource.Factory(cacheDataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(videoUrl))
                setMediaSource(mediaSource)
            } else {
                setMediaItem(MediaItem.fromUri(videoUrl))
            }
            prepare()
        }
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
            exoPlayer.clearVideoSurface()
            exoPlayer.release()
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

        // Completely Custom Play/Pause Overlay Button in Center
        AnimatedVisibility(
            visible = isUiVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            IconButton(
                onClick = {
                    if (isPlaying) {
                        exoPlayer.pause()
                    } else {
                        exoPlayer.play()
                    }
                },
                modifier = Modifier
                    .size(72.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "暂停" else "播放",
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
                .padding(bottom = 96.dp) // Floating above the HUD buttons bar
        ) {
            Box(
                modifier = Modifier
                    .width(320.dp)
                    .background(
                        color = Color.Black.copy(alpha = 0.65f),
                        shape = RoundedCornerShape(24.dp)
                    )
                    .border(
                        width = 1.dp,
                        color = Color.White.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(24.dp)
                    )
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = formatTime(playbackPosition),
                        style = MaterialTheme.typography.bodyMedium,
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
                        modifier = Modifier.weight(1f)
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    Text(
                        text = formatTime(duration),
                        style = MaterialTheme.typography.bodyMedium,
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
                            contentDescription = if (isMuted) "取消静音" else "静音",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }
    }
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
