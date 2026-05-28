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
import androidx.compose.runtime.Composable
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
    onAboutClick: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val lazyListState = rememberLazyListState()

    val scrollState = rememberScrollAlpha(
        firstVisibleItemIndex = { lazyListState.firstVisibleItemIndex },
        firstVisibleItemScrollOffset = { lazyListState.firstVisibleItemScrollOffset }
    )

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
                PullToRefreshBox(
                    isRefreshing = uiState.isRefreshing,
                    onRefresh = { viewModel.refresh() },
                    modifier = Modifier.fillMaxSize().hazeContentSource()
                ) {
                    LazyColumn(
                        state = lazyListState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = scrollState.topBarHeight, bottom = 80.dp)
                    ) {
                        if (uiState.albums.isNotEmpty()) {
                            item {
                                CollectionRowSection(
                                    title = "相册"
                                ) {
                                    items(
                                        items = uiState.albums,
                                        key = { it.id }
                                    ) { album ->
                                        CoverCard(
                                            name = album.name,
                                            subtitle = "${album.fileCount} 项",
                                            thumbUrl = album.coverMd5.takeIf { it.isNotBlank() }?.let(viewModel::getThumbUrlByMd5),
                                            fallbackIcon = Icons.Default.Collections,
                                            fallbackGradient = AlbumGradient,
                                            onClick = { onAlbumClick(album.id, album.name) },
                                            thumbKey = album.coverMd5
                                        )
                                    }
                                }
                            }
                        }

                        if (uiState.folders.isNotEmpty()) {
                            item {
                                CollectionRowSection(
                                    title = "文件夹"
                                ) {
                                    items(
                                        items = uiState.folders,
                                        key = { it.id }
                                    ) { folder ->
                                        CoverCard(
                                            name = folder.name,
                                            subtitle = "${folder.fileCount} 项",
                                            thumbUrl = folder.coverMd5.takeIf { it.isNotBlank() }?.let(viewModel::getThumbUrlByMd5),
                                            fallbackIcon = Icons.Default.Folder,
                                            fallbackGradient = FolderGradient,
                                            onClick = { onFolderClick(folder.id) },
                                            thumbKey = folder.coverMd5
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
                                    text = uiState.error!!,
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
            scrollAlpha = scrollState.scrollAlpha,
            modifier = Modifier.align(Alignment.TopCenter)
        )
    }
}

@Composable
private fun CollectionRowSection(
    title: String,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit
) {
    Column(modifier = Modifier.padding(top = 8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            content = content
        )
    }
}

// Gradient presets for collection cover cards
private val AlbumGradient = listOf(Color(0xFFFDA085), Color(0xFFF6D365))
private val FolderGradient = listOf(Color(0xFF38EF7D), Color(0xFF11998E))

@Composable
private fun CategorySection(
    categories: List<CollectionCategoryItem>,
    onCategoryClick: (String) -> Unit
) {
    Column(modifier = Modifier.padding(top = 12.dp)) {
        Text(
            text = "类别",
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
                text = category.title,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = category.subtitle,
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
