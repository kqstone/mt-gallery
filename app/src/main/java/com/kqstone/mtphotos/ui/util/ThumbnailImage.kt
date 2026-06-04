package com.kqstone.mtphotos.ui.util

import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
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
    contentScale: ContentScale = ContentScale.Crop,
    key: String? = null
) {
    val context = LocalContext.current
    AsyncImage(
        model = ImageRequest.Builder(context)
            .data(url)
            .size(THUMB_SIZE_PX)
            .scale(Scale.FILL)
            .apply {
                ThumbnailCacheKeys.forUrl(key, url)?.let { cacheKey ->
                    diskCacheKey(cacheKey)
                    memoryCacheKey(cacheKey)
                }
            }
            .crossfade(false)
            .build(),
        contentDescription = contentDescription,
        contentScale = contentScale,
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant)
    )
}
