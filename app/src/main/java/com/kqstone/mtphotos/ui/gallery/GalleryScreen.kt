package com.kqstone.mtphotos.ui.gallery

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.kqstone.mtphotos.data.model.UnifiedPhotoItem
import com.kqstone.mtphotos.ui.util.PermissionHelper
import com.kqstone.mtphotos.ui.util.rememberScrollAlpha

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    viewModel: GalleryViewModel,
    onPhotoClick: (UnifiedPhotoItem) -> Unit,
    onSettingsClick: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val selectedIds by viewModel.selectionManager.selectedPhotoIds.collectAsState()
    val isSelectionMode = selectedIds.isNotEmpty()

    BackHandler(enabled = isSelectionMode) {
        viewModel.selectionManager.clearSelection()
    }
    val keyboardController = LocalSoftwareKeyboardController.current
    var isSearchPanelActive by remember { mutableStateOf(false) }
    BackHandler(enabled = !isSelectionMode && isSearchPanelActive) {
        isSearchPanelActive = false
    }
    var showDeleteDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.quickRefresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val gridState = rememberLazyGridState()
    val scrollState = rememberScrollAlpha(
        firstVisibleItemIndex = { gridState.firstVisibleItemIndex },
        firstVisibleItemScrollOffset = { gridState.firstVisibleItemScrollOffset }
    )

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            uiState.isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = scrollState.topBarHeight, bottom = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            uiState.error != null && uiState.months.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = scrollState.topBarHeight, bottom = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = uiState.error!!, color = MaterialTheme.colorScheme.error)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "点击重试",
                            modifier = Modifier.clickable {
                                if (uiState.isSearchMode) {
                                    viewModel.executeSearch()
                                } else {
                                    viewModel.loadTimeline()
                                }
                            },
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            else -> {
                val progressText = uiState.syncProgressText
                val hasSyncProgress = uiState.isSyncing && progressText != null
                
                Box(modifier = Modifier.fillMaxSize()) {
                    PullToRefreshBox(
                        isRefreshing = uiState.isRefreshing,
                        onRefresh = { viewModel.refresh() },
                        modifier = Modifier.fillMaxSize()
                    ) {
                        TimelinePhotoGrid(
                            months = uiState.months,
                            columnCount = uiState.columnCount,
                            selectedPhotoIds = selectedIds,
                            isSelectionMode = isSelectionMode,
                            selectionManager = viewModel.selectionManager,
                            getThumbUrl = viewModel::getThumbUrl,
                            onPhotoClick = onPhotoClick,
                            onColumnCountChange = { viewModel.updateColumnCount(it) },
                            onMonthPlaceholderClick = { viewModel.loadTimelineMonth(it.yearMonth) },
                            stateKey = "gallery:${uiState.isSearchMode}:${uiState.searchQuery}",
                            gridState = gridState,
                            contentPadding = PaddingValues(
                                top = if (hasSyncProgress) scrollState.topBarHeight + 40.dp else scrollState.topBarHeight,
                                bottom = 80.dp,
                                start = 1.dp,
                                end = 1.dp
                            )
                        )
                    }
                    
                    if (hasSyncProgress) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = scrollState.topBarHeight)
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .height(16.dp)
                                    .width(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = progressText!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
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
                    onDelete = { showDeleteDialog = true },
                    onClearSelection = { viewModel.selectionManager.clearSelection() },
                    scrollAlpha = scrollState.scrollAlpha
                )
            } else {
                UnifiedSearchHeader(
                    query = uiState.searchQuery,
                    searchType = uiState.searchType,
                    searchFilters = uiState.searchFilters,
                    suggestions = uiState.searchSuggestions,
                    people = uiState.searchPeople,
                    locations = uiState.searchLocations,
                    isSearching = uiState.isSearching,
                    isClipAvailable = uiState.isClipAvailable,
                    isPanelActive = isSearchPanelActive,
                    onPanelActiveChange = { active ->
                        isSearchPanelActive = active
                        if (active) viewModel.loadSearchFilterCandidates()
                    },
                    onQueryChange = viewModel::updateSearchQuery,
                    onSearch = {
                        keyboardController?.hide()
                        isSearchPanelActive = false
                        viewModel.executeSearch()
                    },
                    onClear = {
                        isSearchPanelActive = false
                        viewModel.clearSearch()
                    },
                    onSearchTypeChange = viewModel::updateSearchType,
                    onPersonFilterChange = viewModel::updatePersonFilter,
                    onLocationFilterChange = viewModel::updateLocationFilter,
                    onSuggestionClick = {
                        keyboardController?.hide()
                        isSearchPanelActive = false
                        viewModel.applySuggestion(it)
                    },
                    onSettingsClick = onSettingsClick,
                    scrollAlpha = scrollState.scrollAlpha
                )
            }
        }
    }

    if (showDeleteDialog) {
        DeleteConfirmDialog(
            selectedCount = selectedIds.size,
            onConfirm = {
                showDeleteDialog = false
                if (PermissionHelper.requestManageStoragePermission(context)) {
                    viewModel.deleteSelected()
                }
            },
            onDismiss = { showDeleteDialog = false }
        )
    }
}
