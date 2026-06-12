package com.kqstone.mtphotos.ui.gallery

import androidx.compose.foundation.background
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
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
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
import com.kqstone.mtphotos.ui.media.MediaGridHost
import com.kqstone.mtphotos.ui.search.SearchEntryTopBar
import androidx.compose.ui.res.stringResource
import com.kqstone.mtphotos.R
import com.kqstone.mtphotos.ui.util.ToastMessageEffect
import com.kqstone.mtphotos.ui.util.rememberScrollAlpha
import com.kqstone.mtphotos.ui.util.hazeContentSource
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.graphicsLayer

private const val PRIVATE_ALBUM_PULL_THRESHOLD = 1.85f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    viewModel: GalleryViewModel,
    onPhotoClick: (UnifiedPhotoItem, List<UnifiedPhotoItem>) -> Unit,
    onOpenSearch: () -> Unit,
    onSettingsClick: () -> Unit,
    onAboutClick: () -> Unit,
    onOpenPrivateAlbum: () -> Unit,
    onOpLogClick: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val gallerySelectedIds by viewModel.selectionManager.selectedPhotoIds.collectAsState()
    val isSelectionMode = gallerySelectedIds.isNotEmpty()

    val context = LocalContext.current

    // 后台同步和刷新结果使用轻量 Toast 提示
    ToastMessageEffect(
        message = uiState.toastMessage,
        onConsumed = viewModel::clearToastMessage
    )

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

    val progressText = uiState.syncProgressText?.asString()
    val hasSyncProgress = uiState.isSyncing && progressText != null
    val pullRefreshState = rememberPullToRefreshState()
    var privatePullArmed by remember { mutableStateOf(false) }

    LaunchedEffect(pullRefreshState.distanceFraction, uiState.isRefreshing) {
        if (!uiState.isRefreshing) {
            if (pullRefreshState.distanceFraction >= PRIVATE_ALBUM_PULL_THRESHOLD) {
                privatePullArmed = true
            } else if (pullRefreshState.distanceFraction < 1.0f) {
                privatePullArmed = false
            }
        } else {
            privatePullArmed = false
        }
    }

    MediaGridHost(
        months = uiState.months,
        columnCount = uiState.columnCount,
        selectedPhotoIds = gallerySelectedIds,
        isSelectionMode = isSelectionMode,
        selectionManager = viewModel.selectionManager,
        getThumbUrl = viewModel::getThumbUrl,
        onPhotoClick = { photo -> onPhotoClick(photo, viewModel.getAllLoadedPhotos()) },
        onColumnCountChange = { viewModel.updateColumnCount(it) },
        onSelectAll = viewModel::selectAll,
        onDeleteSelected = viewModel::deleteSelected,
        onClearSelection = { viewModel.selectionManager.clearSelection() },
        normalTopBar = {
            SearchEntryTopBar(
                onSearchClick = onOpenSearch,
                onSettingsClick = onSettingsClick,
                onAboutClick = onAboutClick,
                onOpLogClick = onOpLogClick,
                scrollAlpha = scrollState.scrollAlpha
            )
        },
        isLoading = uiState.isLoading,
        error = uiState.error.takeIf { uiState.months.isEmpty() },
        isEmpty = !uiState.isLoading && uiState.months.isEmpty(),
        emptyContent = {
            Text(
                text = stringResource(R.string.no_photos),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        onRetry = viewModel::loadTimeline,
        contentTopPadding = scrollState.topBarHeight,
        onMonthPlaceholderClick = { viewModel.loadTimelineMonth(it.yearMonth) },
        stateKey = "gallery:timeline",
        gridState = gridState,
        contentPadding = PaddingValues(
            top = if (hasSyncProgress) scrollState.topBarHeight + 40.dp else scrollState.topBarHeight,
            bottom = 80.dp,
            start = 1.dp,
            end = 1.dp
        ),
        gridContainer = { gridContent ->
            PullToRefreshBox(
                isRefreshing = uiState.isRefreshing,
                onRefresh = {
                    if (privatePullArmed) {
                        privatePullArmed = false
                        onOpenPrivateAlbum()
                    } else {
                        viewModel.refresh()
                    }
                },
                modifier = Modifier.fillMaxSize().hazeContentSource(),
                state = pullRefreshState,
                indicator = {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = scrollState.topBarHeight),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        AnimatedVisibility(
                            visible = !privatePullArmed || uiState.isRefreshing,
                            enter = fadeIn(),
                            exit = fadeOut()
                        ) {
                            PullToRefreshDefaults.Indicator(
                                isRefreshing = uiState.isRefreshing,
                                state = pullRefreshState
                            )
                        }

                        AnimatedVisibility(
                            visible = privatePullArmed && !uiState.isRefreshing,
                            enter = fadeIn() + scaleIn(
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                    stiffness = Spring.StiffnessLow
                                )
                            ),
                            exit = fadeOut() + scaleOut()
                        ) {
                            Surface(
                                modifier = Modifier
                                    .padding(top = 16.dp)
                                    .size(40.dp)
                                    .graphicsLayer {
                                        translationY = (pullRefreshState.distanceFraction * 80.dp.toPx()).coerceAtLeast(0f) - 16.dp.toPx()
                                    },
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                shadowElevation = 2.dp
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Outlined.Lock,
                                        contentDescription = stringResource(R.string.private_album_title),
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            ) {
                gridContent()
            }
        },
        overlayContent = {
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
                        text = progressText.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        shareManager = viewModel.shareManager,
        scrollAlpha = scrollState.scrollAlpha,
        selectionActions = listOf(
            MediaSelectionAction(MediaSelectionActionType.SHARE) { viewModel.shareSelected(context) },
            MediaSelectionAction(MediaSelectionActionType.FAVORITE) { viewModel.favoriteSelected() },
            MediaSelectionAction(MediaSelectionActionType.HIDE) { viewModel.hideSelected() }
        )
    )
}
