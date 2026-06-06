package com.kqstone.mtphotos.ui.media

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kqstone.mtphotos.R
import com.kqstone.mtphotos.data.model.UnifiedPhotoItem
import com.kqstone.mtphotos.ui.gallery.DeleteConfirmDialog
import com.kqstone.mtphotos.ui.gallery.MonthGroup
import com.kqstone.mtphotos.ui.gallery.SelectionManager
import com.kqstone.mtphotos.ui.gallery.SelectionTopBar
import com.kqstone.mtphotos.ui.gallery.TimelinePhotoGrid
import com.kqstone.mtphotos.ui.util.PermissionHelper
import com.kqstone.mtphotos.ui.util.ShareManager
import com.kqstone.mtphotos.ui.util.ShareProgressOverlay
import com.kqstone.mtphotos.ui.util.UiText

@Composable
fun MediaGridHost(
    months: List<MonthGroup>,
    columnCount: Int,
    selectedPhotoIds: Set<Double>,
    isSelectionMode: Boolean,
    selectionManager: SelectionManager,
    getThumbUrl: (UnifiedPhotoItem) -> String,
    onPhotoClick: (UnifiedPhotoItem) -> Unit,
    onColumnCountChange: (Int) -> Unit,
    onSelectAll: () -> Unit,
    onDeleteSelected: () -> Unit,
    onClearSelection: () -> Unit,
    normalTopBar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
    error: UiText? = null,
    isEmpty: Boolean = false,
    emptyContent: @Composable () -> Unit,
    onRetry: (() -> Unit)? = null,
    contentTopPadding: Dp = 0.dp,
    showMonthHeaders: Boolean = true,
    showDayHeaders: Boolean = true,
    stateKey: String? = null,
    gridState: LazyGridState,
    contentPadding: PaddingValues = PaddingValues(1.dp),
    leadingContent: (@Composable () -> Unit)? = null,
    overlayContent: @Composable BoxScope.() -> Unit = {},
    shareManager: ShareManager? = null,
    scrollAlpha: Float = 1f,
    onShare: (() -> Unit)? = null,
    onFavorite: (() -> Unit)? = null,
    onUnfavorite: (() -> Unit)? = null,
    onHide: (() -> Unit)? = null,
    onUnhide: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var showDeleteDialog by remember { mutableStateOf(false) }

    BackHandler(enabled = isSelectionMode) {
        onClearSelection()
    }

    Box(modifier = modifier.fillMaxSize()) {
        when {
            isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = contentTopPadding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            error != null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = contentTopPadding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = error.asString(), color = MaterialTheme.colorScheme.error)
                        if (onRetry != null) {
                            Text(
                                text = stringResource(R.string.click_to_retry),
                                modifier = Modifier
                                    .padding(top = 8.dp)
                                    .clickable(onClick = onRetry),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            isEmpty -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = contentTopPadding),
                    contentAlignment = Alignment.Center
                ) {
                    emptyContent()
                }
            }

            else -> {
                TimelinePhotoGrid(
                    months = months,
                    columnCount = columnCount,
                    selectedPhotoIds = selectedPhotoIds,
                    isSelectionMode = isSelectionMode,
                    selectionManager = selectionManager,
                    getThumbUrl = getThumbUrl,
                    onPhotoClick = onPhotoClick,
                    onColumnCountChange = onColumnCountChange,
                    showMonthHeaders = showMonthHeaders,
                    showDayHeaders = showDayHeaders,
                    stateKey = stateKey,
                    modifier = Modifier.fillMaxSize(),
                    gridState = gridState,
                    contentPadding = contentPadding,
                    leadingContent = leadingContent
                )
            }
        }

        overlayContent()

        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
        ) {
            if (isSelectionMode) {
                SelectionTopBar(
                    selectedCount = selectedPhotoIds.size,
                    onSelectAll = onSelectAll,
                    onDelete = { showDeleteDialog = true },
                    onShare = onShare,
                    onFavorite = onFavorite,
                    onUnfavorite = onUnfavorite,
                    onHide = onHide,
                    onUnhide = onUnhide,
                    onClearSelection = onClearSelection,
                    scrollAlpha = scrollAlpha
                )
            } else {
                normalTopBar()
            }
        }
    }

    if (showDeleteDialog) {
        DeleteConfirmDialog(
            selectedCount = selectedPhotoIds.size,
            onConfirm = {
                showDeleteDialog = false
                if (PermissionHelper.requestManageStoragePermission(context)) {
                    onDeleteSelected()
                }
            },
            onDismiss = { showDeleteDialog = false }
        )
    }

    if (shareManager != null) {
        ShareProgressOverlay(shareManager)
    }
}
