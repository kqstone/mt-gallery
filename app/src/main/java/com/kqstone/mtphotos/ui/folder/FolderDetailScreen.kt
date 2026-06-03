package com.kqstone.mtphotos.ui.folder

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kqstone.mtphotos.R
import com.kqstone.mtphotos.data.model.UnifiedPhotoItem
import com.kqstone.mtphotos.data.repository.FolderItem
import com.kqstone.mtphotos.ui.gallery.DeleteConfirmDialog
import com.kqstone.mtphotos.ui.gallery.SelectionManager
import com.kqstone.mtphotos.ui.gallery.SelectionTopBar
import com.kqstone.mtphotos.ui.gallery.TimelinePhotoGrid
import com.kqstone.mtphotos.ui.gallery.buildPhotoTimelineLayout
import com.kqstone.mtphotos.ui.util.BackTitleTopBar
import com.kqstone.mtphotos.ui.util.PermissionHelper
import com.kqstone.mtphotos.ui.util.ShareManager
import com.kqstone.mtphotos.ui.util.ThumbnailImage
import com.kqstone.mtphotos.ui.util.bounceClick
import com.kqstone.mtphotos.ui.util.rememberScrollAlpha

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderDetailScreen(
    folderId: String,
    viewModel: FolderDetailViewModel,
    onFolderClick: (String) -> Unit,
    onPhotoClick: (UnifiedPhotoItem) -> Unit,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val isCurrentFolder = uiState.folderId == folderId
    val selectedIds by viewModel.selectionManager.selectedPhotoIds.collectAsState()
    val isSelectionMode = isCurrentFolder && selectedIds.isNotEmpty()
    val timelineLayout = remember(uiState.photos) {
        buildPhotoTimelineLayout(uiState.photos)
    }

    BackHandler(enabled = isSelectionMode) {
        viewModel.selectionManager.clearSelection()
    }

    val showDeleteDialog = remember { mutableStateOf(false) }
    val context = LocalContext.current

    LaunchedEffect(folderId) {
        viewModel.loadFolder(folderId)
    }

    val gridState = rememberLazyGridState()
    val scrollState = rememberScrollAlpha(
        firstVisibleItemIndex = { gridState.firstVisibleItemIndex },
        firstVisibleItemScrollOffset = { gridState.firstVisibleItemScrollOffset }
    )

    val navigationBarsHeight = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            !isCurrentFolder || uiState.isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = scrollState.topBarHeight),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            uiState.error != null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = scrollState.topBarHeight),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = uiState.error!!.asString(),
                            color = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = stringResource(R.string.click_to_retry),
                            modifier = Modifier.clickable { viewModel.loadFolder(folderId, force = true) },
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            uiState.photos.isEmpty() && uiState.subfolders.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = scrollState.topBarHeight),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.folder_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            else -> {
                TimelinePhotoGrid(
                    months = timelineLayout.months,
                    columnCount = uiState.columnCount,
                    selectedPhotoIds = selectedIds,
                    isSelectionMode = isSelectionMode,
                    selectionManager = viewModel.selectionManager,
                    getThumbUrl = viewModel::getThumbUrl,
                    onPhotoClick = onPhotoClick,
                    onColumnCountChange = viewModel::updateColumnCount,
                    showMonthHeaders = timelineLayout.showMonthHeaders,
                    showDayHeaders = timelineLayout.showDayHeaders,
                    stateKey = "folder:$folderId",
                    modifier = Modifier.fillMaxSize(),
                    gridState = gridState,
                    contentPadding = PaddingValues(top = scrollState.topBarHeight, bottom = navigationBarsHeight + 16.dp, start = 1.dp, end = 1.dp),
                    leadingContent = if (uiState.subfolders.isNotEmpty()) {
                        {
                            SubfolderSection(
                                subfolders = uiState.subfolders,
                                onFolderClick = onFolderClick,
                                thumbUrlProvider = viewModel::getThumbUrlByMd5
                            )
                        }
                    } else {
                        null
                    }
                )
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
        ) {
            if (isSelectionMode) {
                SelectionTopBar(
                    selectedCount = selectedIds.size,
                    onSelectAll = { viewModel.selectAll() },
                    onDelete = { showDeleteDialog.value = true },
                    onShare = { viewModel.shareSelected(context) },
                    onFavorite = { viewModel.favoriteSelected() },
                    onClearSelection = { viewModel.selectionManager.clearSelection() },
                    scrollAlpha = scrollState.scrollAlpha
                )
            } else {
                BackTitleTopBar(
                    title = uiState.folderName.ifEmpty { stringResource(R.string.section_folders) },
                    onBack = onBack,
                    scrollAlpha = scrollState.scrollAlpha
                )
            }
        }
    }

    if (showDeleteDialog.value) {
        DeleteConfirmDialog(
            selectedCount = selectedIds.size,
            onConfirm = {
                showDeleteDialog.value = false
                if (PermissionHelper.requestManageStoragePermission(context)) {
                    viewModel.deleteSelected()
                }
            },
            onDismiss = { showDeleteDialog.value = false }
        )
    }

    com.kqstone.mtphotos.ui.util.ShareProgressOverlay(viewModel.shareManager)
}

@Composable
private fun SubfolderSection(
    subfolders: List<FolderItem>,
    onFolderClick: (String) -> Unit,
    thumbUrlProvider: (String) -> String
) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(
            text = stringResource(R.string.section_subfolders),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(
                items = subfolders,
                key = { it.id }
            ) { folder ->
                SubfolderCard(
                    folder = folder,
                    thumbUrl = folder.coverMd5.takeIf { it.isNotBlank() }?.let(thumbUrlProvider),
                    onClick = { onFolderClick(folder.id) }
                )
            }
        }
    }
}

@Composable
private fun SubfolderCard(
    folder: FolderItem,
    thumbUrl: String?,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .width(120.dp)
            .bounceClick(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(MaterialTheme.shapes.small)
            ) {
                if (thumbUrl != null) {
                    ThumbnailImage(
                        url = thumbUrl,
                        contentDescription = folder.name,
                        modifier = Modifier.fillMaxSize(),
                        key = folder.coverMd5
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            Column(modifier = Modifier.padding(8.dp)) {
                Text(
                    text = folder.name,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(R.string.item_count_short, folder.fileCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
