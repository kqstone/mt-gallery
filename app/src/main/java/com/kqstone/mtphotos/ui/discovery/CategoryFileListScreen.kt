package com.kqstone.mtphotos.ui.discovery

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kqstone.mtphotos.R
import com.kqstone.mtphotos.data.model.UnifiedPhotoItem
import com.kqstone.mtphotos.ui.gallery.DeleteConfirmDialog
import com.kqstone.mtphotos.ui.gallery.SelectionTopBar
import com.kqstone.mtphotos.ui.gallery.TimelinePhotoGrid
import com.kqstone.mtphotos.ui.gallery.buildPhotoTimelineLayout
import com.kqstone.mtphotos.ui.util.BackTitleTopBar
import com.kqstone.mtphotos.ui.util.PermissionHelper
import com.kqstone.mtphotos.ui.util.rememberScrollAlpha

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryFileListScreen(
    viewModel: CategoryFileListViewModel,
    loadType: String,
    loadParam: String,
    loadParam2: String? = null,
    title: String,
    onPhotoClick: (UnifiedPhotoItem) -> Unit,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val targetDistrict = remember(loadParam) { mutableStateOf<String?>(null) }
    val expectedPageKey = remember(loadType, loadParam, loadParam2, targetDistrict.value) {
        CategoryFileListViewModel.pageKey(
            loadType = loadType,
            loadParam = loadParam,
            loadParam2 = if (loadType == "location") targetDistrict.value else loadParam2
        )
    }
    val isCurrentPage = uiState.pageKey == expectedPageKey
    val selectedIds by viewModel.selectionManager.selectedPhotoIds.collectAsState()
    val isSelectionMode = isCurrentPage && selectedIds.isNotEmpty()
    val timelineLayout = remember(uiState.photos) {
        buildPhotoTimelineLayout(uiState.photos)
    }

    BackHandler(enabled = isSelectionMode) {
        viewModel.selectionManager.clearSelection()
    }
    val context = LocalContext.current
    val showDeleteDialog = remember { mutableStateOf(false) }

    LaunchedEffect(loadType, loadParam, loadParam2) {
        when (loadType) {
            "people" -> viewModel.loadPeopleFiles(loadParam)
            "scene" -> viewModel.loadSceneFiles(loadParam, loadParam2)
            "location" -> {
                targetDistrict.value = null
                viewModel.loadLocationFiles(loadParam)
            }
            "album" -> viewModel.loadAlbumFiles(loadParam.toDoubleOrNull() ?: 0.0)
            "favorites" -> viewModel.loadFavoritesFiles()
            "recent" -> viewModel.loadRecentFiles()
            "videos" -> viewModel.loadVideoFiles()
            "trash" -> viewModel.loadTrashFiles()
        }
    }

    val gridState = rememberLazyGridState()
    val scrollState = rememberScrollAlpha(
        firstVisibleItemIndex = { gridState.firstVisibleItemIndex },
        firstVisibleItemScrollOffset = { gridState.firstVisibleItemScrollOffset }
    )

    val navigationBarsHeight = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    val hasDistricts = isCurrentPage && loadType == "location" && uiState.locationDistricts.isNotEmpty()

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            !isCurrentPage || uiState.isLoading -> {
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
                            modifier = Modifier.clickable {
                                when (loadType) {
                                    "people" -> viewModel.loadPeopleFiles(loadParam, force = true)
                                    "scene" -> viewModel.loadSceneFiles(loadParam, loadParam2, force = true)
                                    "location" -> viewModel.loadLocationFiles(loadParam, uiState.selectedDistrict, force = true)
                                    "album" -> viewModel.loadAlbumFiles(loadParam.toDoubleOrNull() ?: 0.0, force = true)
                                    "favorites" -> viewModel.loadFavoritesFiles(force = true)
                                    "recent" -> viewModel.loadRecentFiles(force = true)
                                    "videos" -> viewModel.loadVideoFiles(force = true)
                                    "trash" -> viewModel.loadTrashFiles(force = true)
                                }
                            },
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            uiState.photos.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = scrollState.topBarHeight),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.no_photos),
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
                    stateKey = expectedPageKey,
                    modifier = Modifier.fillMaxSize(),
                    gridState = gridState,
                    contentPadding = PaddingValues(
                        top = if (hasDistricts) scrollState.topBarHeight + 48.dp else scrollState.topBarHeight,
                        bottom = navigationBarsHeight + 16.dp,
                        start = 1.dp,
                        end = 1.dp
                    )
                )
            }
        }

        if (hasDistricts) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = scrollState.topBarHeight)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f * scrollState.scrollAlpha))
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = uiState.selectedDistrict == null,
                    onClick = {
                        targetDistrict.value = null
                        viewModel.loadLocationFiles(loadParam, null)
                    },
                    label = { Text(stringResource(R.string.filter_all)) }
                )
                uiState.locationDistricts.forEach { district ->
                    FilterChip(
                        selected = uiState.selectedDistrict == district.city,
                        onClick = {
                            targetDistrict.value = district.city
                            viewModel.loadLocationFiles(loadParam, district.city)
                        },
                        label = { Text("${district.city} ${district.count}") }
                    )
                }
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
                    onFavorite = if (loadType == "favorites") null else ({ viewModel.favoriteSelected() }),
                    onUnfavorite = if (loadType == "favorites") ({ viewModel.unfavoriteSelected() }) else null,
                    onClearSelection = { viewModel.selectionManager.clearSelection() },
                    scrollAlpha = scrollState.scrollAlpha
                )
            } else {
                BackTitleTopBar(
                    title = title,
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
