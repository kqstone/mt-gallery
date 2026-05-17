package com.kqstone.mtphotos.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Scale

private const val THUMB_SIZE_PX = 256

@Composable
fun ThumbnailImage(
    url: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop
) {
    val context = LocalContext.current
    AsyncImage(
        model = ImageRequest.Builder(context)
            .data(url)
            .size(THUMB_SIZE_PX)
            .scale(Scale.FILL)
            .crossfade(true)
            .build(),
        contentDescription = contentDescription,
        contentScale = contentScale,
        modifier = modifier
    )
}
