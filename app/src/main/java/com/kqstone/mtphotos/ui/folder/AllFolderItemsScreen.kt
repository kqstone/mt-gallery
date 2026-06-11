package com.kqstone.mtphotos.ui.folder

import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Folder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.graphicsLayer
import com.kqstone.mtphotos.R
import com.kqstone.mtphotos.ui.util.AllItemsGridScreen
import com.kqstone.mtphotos.ui.util.CoverCard
import com.kqstone.mtphotos.ui.util.GradientPresets

import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect

@Composable
fun AllFolderItemsScreen(
    viewModel: FolderViewModel,
    type: String,
    onAlbumClick: (Double, String) -> Unit,
    onFolderClick: (String) -> Unit,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    val titleRes = when (type) {
        "albums" -> R.string.section_albums
        "folders" -> R.string.section_folders
        else -> R.string.app_name
    }

    var albumsList by remember { mutableStateOf(uiState.albums) }
    var foldersList by remember { mutableStateOf(uiState.folders) }

    LaunchedEffect(uiState.albums) { albumsList = uiState.albums }
    LaunchedEffect(uiState.folders) { foldersList = uiState.folders }

    val gridState = rememberLazyGridState()
    val state = rememberReorderableLazyGridState(
        lazyGridState = gridState,
        onMove = { from, to ->
            when (type) {
                "albums" -> albumsList = albumsList.toMutableList().apply { add(to.index, removeAt(from.index)) }
                "folders" -> foldersList = foldersList.toMutableList().apply { add(to.index, removeAt(from.index)) }
            }
        }
    )

    AllItemsGridScreen(
        title = stringResource(titleRes),
        columns = 3,
        onBack = onBack,
        modifier = Modifier,
        gridState = gridState
    ) {
        when (type) {
            "albums" -> {
                items(albumsList, key = { it.id }) { album ->
                    ReorderableItem(state, key = album.id) { isDragging ->
                        val scale = if (isDragging) 1.05f else 1f
                        val alpha = if (isDragging) 0.8f else 1f
                        CoverCard(
                            name = album.name,
                            subtitle = stringResource(R.string.item_count_short, album.fileCount),
                            thumbUrl = album.coverMd5.takeIf { it.isNotBlank() }?.let(viewModel::getThumbUrlByMd5),
                            fallbackIcon = Icons.Default.Collections,
                            fallbackGradient = GradientPresets.Album,
                            onClick = { onAlbumClick(album.id, album.name) },
                            thumbKey = album.coverMd5,
                            cardSize = Dp.Unspecified,
                            modifier = Modifier
                                .longPressDraggableHandle(
                                    onDragStopped = {
                                        viewModel.saveCustomOrder("album", albumsList.map { it.id.toString() })
                                    }
                                )
                                .animateItem()
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .graphicsLayer {
                                    scaleX = scale
                                    scaleY = scale
                                    this.alpha = alpha
                                }
                        )
                    }
                }
            }
            "folders" -> {
                items(foldersList, key = { it.id }) { folder ->
                    ReorderableItem(state, key = folder.id) { isDragging ->
                        val scale = if (isDragging) 1.05f else 1f
                        val alpha = if (isDragging) 0.8f else 1f
                        CoverCard(
                            name = folder.name,
                            subtitle = stringResource(R.string.item_count_short, folder.fileCount),
                            thumbUrl = folder.coverMd5.takeIf { it.isNotBlank() }?.let(viewModel::getThumbUrlByMd5),
                            fallbackIcon = Icons.Default.Folder,
                            fallbackGradient = GradientPresets.Folder,
                            onClick = { onFolderClick(folder.id) },
                            thumbKey = folder.coverMd5,
                            cardSize = Dp.Unspecified,
                            modifier = Modifier
                                .longPressDraggableHandle(
                                    onDragStopped = {
                                        viewModel.saveCustomOrder("folder", foldersList.map { it.id })
                                    }
                                )
                                .animateItem()
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .graphicsLayer {
                                    scaleX = scale
                                    scaleY = scale
                                    this.alpha = alpha
                                }
                        )
                    }
                }
            }
        }
    }
}
