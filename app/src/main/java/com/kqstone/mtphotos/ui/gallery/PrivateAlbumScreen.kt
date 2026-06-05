package com.kqstone.mtphotos.ui.gallery

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.kqstone.mtphotos.R
import com.kqstone.mtphotos.data.model.UnifiedPhotoItem
import com.kqstone.mtphotos.ui.util.BackTitleTopBar
import com.kqstone.mtphotos.ui.util.PermissionHelper
import com.kqstone.mtphotos.ui.util.ShareProgressOverlay
import com.kqstone.mtphotos.ui.util.ToastMessageEffect
import com.kqstone.mtphotos.ui.util.hazeContentSource
import com.kqstone.mtphotos.ui.util.rememberScrollAlpha

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivateAlbumScreen(
    viewModel: PrivateAlbumViewModel,
    onPhotoClick: (UnifiedPhotoItem, List<UnifiedPhotoItem>) -> Unit,
    onPhotosUnhidden: (List<UnifiedPhotoItem>) -> Unit,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val selectedIds by viewModel.selectionManager.selectedPhotoIds.collectAsState()
    val isSelectionMode = selectedIds.isNotEmpty()
    val context = LocalContext.current
    var showDeleteDialog by remember { mutableStateOf(false) }

    fun leavePrivateAlbum() {
        viewModel.lock()
        onBack()
    }

    ToastMessageEffect(
        message = uiState.toastMessage,
        onConsumed = viewModel::clearToastMessage
    )

    BackHandler(enabled = true) {
        if (isSelectionMode) {
            viewModel.selectionManager.clearSelection()
        } else {
            leavePrivateAlbum()
        }
    }

    val gridState = rememberLazyGridState()
    val scrollState = rememberScrollAlpha(
        firstVisibleItemIndex = { gridState.firstVisibleItemIndex },
        firstVisibleItemScrollOffset = { gridState.firstVisibleItemScrollOffset }
    )

    Box(modifier = Modifier.fillMaxSize()) {
        if (uiState.isLocked) {
            PrivateAlbumUnlockContent(
                password = uiState.password,
                isLoading = uiState.isLoading,
                error = uiState.error?.asString(),
                onPasswordChange = viewModel::updatePassword,
                onUnlock = viewModel::unlock,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = scrollState.topBarHeight, start = 24.dp, end = 24.dp)
            )
        } else {
            val pullRefreshState = rememberPullToRefreshState()
            PullToRefreshBox(
                isRefreshing = uiState.isLoading,
                onRefresh = viewModel::refresh,
                modifier = Modifier.fillMaxSize().hazeContentSource(),
                state = pullRefreshState,
                indicator = {
                    PullToRefreshDefaults.Indicator(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = scrollState.topBarHeight),
                        isRefreshing = uiState.isLoading,
                        state = pullRefreshState
                    )
                }
            ) {
                if (!uiState.isLoading && uiState.months.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = scrollState.topBarHeight),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.private_album_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    TimelinePhotoGrid(
                        months = uiState.months,
                        columnCount = uiState.columnCount,
                        selectedPhotoIds = selectedIds,
                        isSelectionMode = isSelectionMode,
                        selectionManager = viewModel.selectionManager,
                        getThumbUrl = viewModel::getThumbUrl,
                        onPhotoClick = { photo -> onPhotoClick(photo, viewModel.getAllLoadedPhotos()) },
                        onColumnCountChange = viewModel::updateColumnCount,
                        onMonthPlaceholderClick = {},
                        stateKey = "gallery:private",
                        gridState = gridState,
                        contentPadding = PaddingValues(
                            top = scrollState.topBarHeight,
                            bottom = 80.dp,
                            start = 1.dp,
                            end = 1.dp
                        )
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
                    onSelectAll = viewModel::selectAll,
                    onDelete = { showDeleteDialog = true },
                    onShare = { viewModel.shareSelected(context) },
                    onUnhide = { viewModel.unhideSelected(onPhotosUnhidden) },
                    onClearSelection = { viewModel.selectionManager.clearSelection() },
                    scrollAlpha = 1f
                )
            } else {
                BackTitleTopBar(
                    title = stringResource(R.string.private_album_title),
                    onBack = { leavePrivateAlbum() },
                    scrollAlpha = scrollState.scrollAlpha
                )
            }
        }
    }

    if (uiState.showIntro) {
        AlertDialog(
            onDismissRequest = viewModel::acknowledgeIntro,
            title = { Text(stringResource(R.string.private_album_intro_title)) },
            text = { Text(stringResource(R.string.private_album_intro_message)) },
            confirmButton = {
                TextButton(onClick = viewModel::acknowledgeIntro) {
                    Text(stringResource(R.string.ok))
                }
            }
        )
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

    ShareProgressOverlay(viewModel.shareManager)
}

@Composable
private fun PrivateAlbumUnlockContent(
    password: String,
    isLoading: Boolean,
    error: String?,
    onPasswordChange: (String) -> Unit,
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.private_album_password_prompt),
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = password,
                onValueChange = onPasswordChange,
                label = { Text(stringResource(R.string.password)) },
                singleLine = true,
                enabled = !isLoading,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = { onUnlock() }),
                modifier = Modifier.fillMaxWidth()
            )
            if (error != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                enabled = !isLoading,
                onClick = onUnlock,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isLoading) {
                    CircularProgressIndicator()
                } else {
                    Text(stringResource(R.string.private_album_unlock))
                }
            }
            if (!isLoading) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.private_album_use_current_password),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable { onUnlock() }
                )
            }
        }
    }
}
