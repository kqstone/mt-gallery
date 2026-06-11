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
import com.kqstone.mtphotos.R
import com.kqstone.mtphotos.ui.util.AllItemsGridScreen
import com.kqstone.mtphotos.ui.util.CoverCard
import com.kqstone.mtphotos.ui.util.GradientPresets

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

    AllItemsGridScreen(
        title = stringResource(titleRes),
        columns = 3,
        onBack = onBack
    ) {
        when (type) {
            "albums" -> {
                items(uiState.albums, key = { it.id }) { album ->
                    CoverCard(
                        name = album.name,
                        subtitle = stringResource(R.string.item_count_short, album.fileCount),
                        thumbUrl = album.coverMd5.takeIf { it.isNotBlank() }?.let(viewModel::getThumbUrlByMd5),
                        fallbackIcon = Icons.Default.Collections,
                        fallbackGradient = GradientPresets.Album,
                        onClick = { onAlbumClick(album.id, album.name) },
                        thumbKey = album.coverMd5,
                        cardSize = Dp.Unspecified,
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                    )
                }
            }
            "folders" -> {
                items(uiState.folders, key = { it.id }) { folder ->
                    CoverCard(
                        name = folder.name,
                        subtitle = stringResource(R.string.item_count_short, folder.fileCount),
                        thumbUrl = folder.coverMd5.takeIf { it.isNotBlank() }?.let(viewModel::getThumbUrlByMd5),
                        fallbackIcon = Icons.Default.Folder,
                        fallbackGradient = GradientPresets.Folder,
                        onClick = { onFolderClick(folder.id) },
                        thumbKey = folder.coverMd5,
                        cardSize = Dp.Unspecified,
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                    )
                }
            }
        }
    }
}
