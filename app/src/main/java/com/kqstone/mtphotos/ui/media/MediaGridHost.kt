package com.kqstone.mtphotos.ui.media

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kqstone.mtphotos.R
import com.kqstone.mtphotos.data.model.UnifiedPhotoItem
import com.kqstone.mtphotos.ui.gallery.DeleteConfirmDialog
import com.kqstone.mtphotos.ui.gallery.MediaSelectionAction
import com.kqstone.mtphotos.ui.gallery.MediaSelectionActionType
import com.kqstone.mtphotos.ui.gallery.MonthGroup
import com.kqstone.mtphotos.ui.gallery.PublishSelectionBottomBar
import com.kqstone.mtphotos.ui.gallery.SelectionManager
import com.kqstone.mtphotos.ui.gallery.SelectionTopBar
import com.kqstone.mtphotos.ui.gallery.SelectionBottomBarHeight
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
    onMonthPlaceholderClick: ((MonthGroup) -> Unit)? = null,
    stateKey: String? = null,
    gridState: LazyGridState,
    contentPadding: PaddingValues = PaddingValues(1.dp),
    leadingContent: (@Composable () -> Unit)? = null,
    gridContainer: @Composable (@Composable () -> Unit) -> Unit = { content -> content() },
    overlayContent: @Composable BoxScope.() -> Unit = {},
    shareManager: ShareManager? = null,
    scrollAlpha: Float = 1f,
    showTopBar: Boolean = true,
    showSelectionTopBar: Boolean = true,
    handleSelectionBack: Boolean = true,
    selectionActions: List<MediaSelectionAction> = emptyList()
) {
    val context = LocalContext.current
    val layoutDirection = LocalLayoutDirection.current
    var showDeleteDialog by remember { mutableStateOf(false) }
    val publishedSelectionActions = remember(selectionActions, isSelectionMode) {
        if (isSelectionMode) {
            selectionActions + MediaSelectionAction(MediaSelectionActionType.DELETE) {
                showDeleteDialog = true
            }
        } else {
            emptyList()
        }
    }
    val selectionBottomPadding = SelectionBottomBarHeight + 16.dp
    val contentBottomPadding = contentPadding.calculateBottomPadding()
    val effectiveContentPadding = PaddingValues(
        start = contentPadding.calculateStartPadding(layoutDirection),
        top = contentPadding.calculateTopPadding(),
        end = contentPadding.calculateEndPadding(layoutDirection),
        bottom = if (isSelectionMode && contentBottomPadding < selectionBottomPadding) {
            selectionBottomPadding
        } else {
            contentBottomPadding
        }
    )

    PublishSelectionBottomBar(
        visible = isSelectionMode,
        actions = publishedSelectionActions
    )

    BackHandler(enabled = handleSelectionBack && isSelectionMode) {
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
                gridContainer {
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
                        onMonthPlaceholderClick = onMonthPlaceholderClick,
                        stateKey = stateKey,
                        modifier = Modifier.fillMaxSize(),
                        gridState = gridState,
                        contentPadding = effectiveContentPadding,
                        leadingContent = leadingContent
                    )
                }
            }
        }

        overlayContent()

        if (showTopBar) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
            ) {
                if (isSelectionMode && showSelectionTopBar) {
                    SelectionTopBar(
                        selectedCount = selectedPhotoIds.size,
                        onSelectAll = onSelectAll,
                        onClearSelection = onClearSelection,
                        scrollAlpha = scrollAlpha
                    )
                } else {
                    normalTopBar()
                }
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
