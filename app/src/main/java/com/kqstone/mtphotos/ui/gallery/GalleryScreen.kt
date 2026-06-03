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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kqstone.mtphotos.data.model.UnifiedPhotoItem
import com.kqstone.mtphotos.ui.search.SearchEntryTopBar
import com.kqstone.mtphotos.ui.util.PermissionHelper
import com.kqstone.mtphotos.ui.util.rememberScrollAlpha
import com.kqstone.mtphotos.ui.util.hazeContentSource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    viewModel: GalleryViewModel,
    onPhotoClick: (UnifiedPhotoItem, List<UnifiedPhotoItem>) -> Unit,
    onOpenSearch: () -> Unit,
    onSettingsClick: () -> Unit,
    onAboutClick: () -> Unit,
    onOpLogClick: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val gallerySelectedIds by viewModel.selectionManager.selectedPhotoIds.collectAsState()
    val isSelectionMode = gallerySelectedIds.isNotEmpty()

    BackHandler(enabled = isSelectionMode) {
        viewModel.selectionManager.clearSelection()
    }
    var showDeleteDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    // 后台同步完成后显示 Snackbar 轻量提示
    val syncCompleteMessage = uiState.syncCompleteMessage
    LaunchedEffect(syncCompleteMessage) {
        if (syncCompleteMessage != null) {
            snackbarHostState.showSnackbar(syncCompleteMessage)
            viewModel.clearSyncCompleteMessage()
        }
    }

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
                                viewModel.loadTimeline()
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
                        modifier = Modifier.fillMaxSize().hazeContentSource()
                    ) {
                        TimelinePhotoGrid(
                            months = uiState.months,
                            columnCount = uiState.columnCount,
                            selectedPhotoIds = gallerySelectedIds,
                            isSelectionMode = isSelectionMode,
                            selectionManager = viewModel.selectionManager,
                            getThumbUrl = viewModel::getThumbUrl,
                            onPhotoClick = { photo -> onPhotoClick(photo, viewModel.getAllLoadedPhotos()) },
                            onColumnCountChange = { viewModel.updateColumnCount(it) },
                            onMonthPlaceholderClick = { viewModel.loadTimelineMonth(it.yearMonth) },
                            stateKey = "gallery:timeline",
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
                    selectedCount = gallerySelectedIds.size,
                    onSelectAll = viewModel::selectAll,
                    onDelete = { showDeleteDialog = true },
                    onShare = { viewModel.shareSelected(context) },
                    onFavorite = { viewModel.favoriteSelected() },
                    onClearSelection = { viewModel.selectionManager.clearSelection() },
                    scrollAlpha = scrollState.scrollAlpha
                )
            } else {
                SearchEntryTopBar(
                    onSearchClick = onOpenSearch,
                    onSettingsClick = onSettingsClick,
                    onAboutClick = onAboutClick,
                    onOpLogClick = onOpLogClick,
                    scrollAlpha = scrollState.scrollAlpha
                )
            }
        }

        // Snackbar 显示在底部
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp)
        ) { data ->
            Snackbar(
                snackbarData = data,
                containerColor = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface
            )
        }
    }

    if (showDeleteDialog) {
        DeleteConfirmDialog(
            selectedCount = gallerySelectedIds.size,
            onConfirm = {
                showDeleteDialog = false
                if (PermissionHelper.requestManageStoragePermission(context)) {
                    viewModel.deleteSelected()
                }
            },
            onDismiss = { showDeleteDialog = false }
        )
    }

    com.kqstone.mtphotos.ui.util.ShareProgressOverlay(viewModel.shareManager)
}
