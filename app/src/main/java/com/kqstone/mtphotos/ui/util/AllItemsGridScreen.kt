package com.kqstone.mtphotos.ui.util

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A reusable full-screen grid page with a back-arrow top bar.
 *
 * Used by `AllDiscoveryItemsScreen` and `AllFolderItemsScreen` to avoid
 * duplicating identical Scaffold + TopAppBar + LazyVerticalGrid boilerplate.
 *
 * @param title     Text shown in the top app bar.
 * @param columns   Number of grid columns.
 * @param onBack    Callback for the back navigation icon.
 * @param content   Grid content lambda (same signature as [LazyVerticalGrid]).
 */
@Composable
fun AllItemsGridScreen(
    title: String,
    columns: Int,
    onBack: () -> Unit,
    content: LazyGridScope.() -> Unit
) {
    val gridState = rememberLazyGridState()
    val scrollState = rememberScrollAlpha(
        firstVisibleItemIndex = { gridState.firstVisibleItemIndex },
        firstVisibleItemScrollOffset = { gridState.firstVisibleItemScrollOffset }
    )

    Box(modifier = Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = gridState,
            contentPadding = PaddingValues(
                top = scrollState.topBarHeight + 16.dp,
                start = 16.dp,
                end = 16.dp,
                bottom = 16.dp
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize(),
            content = content
        )

        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
        ) {
            BackTitleTopBar(
                title = title,
                onBack = onBack,
                scrollAlpha = scrollState.scrollAlpha
            )
        }
    }
}
