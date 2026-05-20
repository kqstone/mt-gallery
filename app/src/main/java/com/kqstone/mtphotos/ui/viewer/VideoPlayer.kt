package com.kqstone.mtphotos.ui.viewer

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

@Composable
fun VideoPlayer(
    videoUrl: String,
    isCurrentPage: Boolean,
    onStopPlaybackReady: ((() -> Unit)?) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val exoPlayer = remember(videoUrl) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(videoUrl))
            prepare()
        }
    }

    val stopPlayback = remember(exoPlayer) {
        {
            exoPlayer.playWhenReady = false
            exoPlayer.pause()
            exoPlayer.clearVideoSurface()
        }
    }

    LaunchedEffect(exoPlayer, isCurrentPage) {
        if (isCurrentPage) {
            exoPlayer.playWhenReady = true
            exoPlayer.play()
        } else {
            stopPlayback()
        }
    }

    DisposableEffect(stopPlayback) {
        onStopPlaybackReady(stopPlayback)
        onDispose { onStopPlaybackReady(null) }
    }

    DisposableEffect(exoPlayer) {
        onDispose {
            stopPlayback()
            exoPlayer.release()
        }
    }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                player = exoPlayer
                useController = true
                setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            }
        },
        update = { view ->
            if (view.player !== exoPlayer) view.player = exoPlayer
        },
        modifier = modifier.fillMaxSize()
    )
}
