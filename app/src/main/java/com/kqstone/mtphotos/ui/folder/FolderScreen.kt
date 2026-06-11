package com.kqstone.mtphotos.ui.folder

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.ui.platform.LocalConfiguration
import com.kqstone.mtphotos.ui.util.SectionHeader
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.kqstone.mtphotos.R
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kqstone.mtphotos.ui.search.SearchEntryTopBar
import com.kqstone.mtphotos.ui.util.CoverCard
import com.kqstone.mtphotos.ui.util.GradientPresets
import com.kqstone.mtphotos.ui.util.ToastMessageEffect
import com.kqstone.mtphotos.ui.util.rememberScrollAlpha
import com.kqstone.mtphotos.ui.util.hazeContentSource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderScreen(
    viewModel: FolderViewModel,
    onAlbumClick: (Double, String) -> Unit,
    onFolderClick: (String) -> Unit,
    onCategoryClick: (String) -> Unit,
    onOpenSearch: () -> Unit,
    onSettingsClick: () -> Unit,
    onAboutClick: () -> Unit,
    onOpLogClick: () -> Unit = {},
    onMoreClick: (String) -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val lazyListState = rememberLazyListState()

    ToastMessageEffect(
        message = uiState.toastMessage,
        onConsumed = viewModel::clearToastMessage
    )

    val scrollState = rememberScrollAlpha(
        firstVisibleItemIndex = { lazyListState.firstVisibleItemIndex },
        firstVisibleItemScrollOffset = { lazyListState.firstVisibleItemScrollOffset }
    )

    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val cardSize = (screenWidth - 32.dp - 16.dp) / 3

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            uiState.isLoading && uiState.albums.isEmpty() && uiState.folders.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = scrollState.topBarHeight, bottom = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            else -> {
                val pullRefreshState = rememberPullToRefreshState()
                PullToRefreshBox(
                    isRefreshing = uiState.isRefreshing,
                    onRefresh = { viewModel.refresh() },
                    modifier = Modifier.fillMaxSize().hazeContentSource(),
                    state = pullRefreshState,
                    indicator = {
                        PullToRefreshDefaults.Indicator(
                            modifier = Modifier.align(Alignment.TopCenter).padding(top = scrollState.topBarHeight),
                            isRefreshing = uiState.isRefreshing,
                            state = pullRefreshState
                        )
                    }
                ) {
                    LazyColumn(
                        state = lazyListState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = scrollState.topBarHeight, bottom = 80.dp)
                    ) {

                        if (uiState.albums.isNotEmpty()) {
                            item {
                                CollectionRowSection(
                                    title = stringResource(R.string.section_albums),
                                    showMore = uiState.albums.size > 3,
                                    onMoreClick = { onMoreClick("albums") }
                                ) {
                                    items(
                                        items = uiState.albums.take(3),
                                        key = { it.id }
                                    ) { album ->
                                        CoverCard(
                                            name = album.name,
                                            subtitle = stringResource(R.string.item_count_short, album.fileCount),
                                            thumbUrl = album.coverMd5.takeIf { it.isNotBlank() }?.let(viewModel::getThumbUrlByMd5),
                                            fallbackIcon = Icons.Default.Collections,
                                            fallbackGradient = GradientPresets.Album,
                                            onClick = { onAlbumClick(album.id, album.name) },
                                            thumbKey = album.coverMd5,
                                            cardSize = cardSize
                                        )
                                    }
                                }
                            }
                        }

                        if (uiState.folders.isNotEmpty()) {
                            item {
                                CollectionRowSection(
                                    title = stringResource(R.string.section_folders),
                                    showMore = uiState.folders.size > 3,
                                    onMoreClick = { onMoreClick("folders") }
                                ) {
                                    items(
                                        items = uiState.folders.take(3),
                                        key = { it.id }
                                    ) { folder ->
                                        CoverCard(
                                            name = folder.name,
                                            subtitle = stringResource(R.string.item_count_short, folder.fileCount),
                                            thumbUrl = folder.coverMd5.takeIf { it.isNotBlank() }?.let(viewModel::getThumbUrlByMd5),
                                            fallbackIcon = Icons.Default.Folder,
                                            fallbackGradient = GradientPresets.Folder,
                                            onClick = { onFolderClick(folder.id) },
                                            thumbKey = folder.coverMd5,
                                            cardSize = cardSize
                                        )
                                    }
                                }
                            }
                        }

                        item {
                            CategorySection(
                                categories = uiState.categories,
                                onCategoryClick = onCategoryClick
                            )
                        }

                        if (uiState.error != null) {
                            item {
                                Text(
                                    text = uiState.error!!.asString(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        SearchEntryTopBar(
            onSearchClick = onOpenSearch,
            onSettingsClick = onSettingsClick,
            onAboutClick = onAboutClick,
            onOpLogClick = onOpLogClick,
            scrollAlpha = scrollState.scrollAlpha,
            modifier = Modifier.align(Alignment.TopCenter)
        )
    }
}

@Composable
private fun CollectionRowSection(
    title: String,
    showMore: Boolean,
    onMoreClick: () -> Unit,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit
) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        SectionHeader(
            title = title,
            showMore = showMore,
            onMoreClick = onMoreClick
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            content = content
        )
    }
}

@Composable
private fun CategorySection(
    categories: List<CollectionCategoryItem>,
    onCategoryClick: (String) -> Unit
) {
    Column(modifier = Modifier.padding(top = 12.dp)) {
        Text(
            text = stringResource(R.string.section_categories),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
        )
        categories.forEach { category ->
            CategoryRow(
                category = category,
                onClick = { onCategoryClick(category.type) }
            )
        }
    }
}

@Composable
private fun CategoryRow(
    category: CollectionCategoryItem,
    onClick: () -> Unit
) {
    val icon = when (category.type) {
        "favorites" -> Icons.Default.Favorite
        "recent" -> Icons.Default.History
        "videos" -> Icons.Default.Movie
        "trash" -> Icons.Default.DeleteOutline
        else -> Icons.Default.Collections
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = category.title.asString(),
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = category.subtitle.asString(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
