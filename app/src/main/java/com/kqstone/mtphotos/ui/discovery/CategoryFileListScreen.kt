package com.kqstone.mtphotos.ui.discovery

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kqstone.mtphotos.R
import com.kqstone.mtphotos.data.model.UnifiedPhotoItem
import com.kqstone.mtphotos.ui.gallery.MediaSelectionAction
import com.kqstone.mtphotos.ui.gallery.MediaSelectionActionType
import com.kqstone.mtphotos.ui.media.MediaGridHost
import com.kqstone.mtphotos.ui.media.buildPhotoTimelineLayout
import com.kqstone.mtphotos.ui.util.BackTitleTopBar
import com.kqstone.mtphotos.ui.util.TopBarActionIcon
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
    val unnamedPersonName = stringResource(R.string.person_unnamed)
    var currentTitle by remember(loadType, loadParam, title) { mutableStateOf(title) }
    var showRenameDialog by remember(loadType, loadParam) { mutableStateOf(false) }
    var renameInput by remember(loadType, loadParam) { mutableStateOf("") }
    val displayTitle = if (loadType == "people") {
        personDisplayName(currentTitle, unnamedPersonName)
    } else {
        currentTitle
    }
    val timelineLayout = remember(uiState.photos) {
        buildPhotoTimelineLayout(uiState.photos)
    }

    val context = LocalContext.current

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

    fun retryLoad() {
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
    }

    MediaGridHost(
        months = timelineLayout.months,
        columnCount = uiState.columnCount,
        selectedPhotoIds = selectedIds,
        isSelectionMode = isSelectionMode,
        selectionManager = viewModel.selectionManager,
        getThumbUrl = viewModel::getThumbUrl,
        onPhotoClick = onPhotoClick,
        onColumnCountChange = viewModel::updateColumnCount,
        onSelectAll = { viewModel.selectAll() },
        onDeleteSelected = { viewModel.deleteSelected() },
        onClearSelection = { viewModel.selectionManager.clearSelection() },
        normalTopBar = {
            BackTitleTopBar(
                title = displayTitle,
                onBack = onBack,
                scrollAlpha = scrollState.scrollAlpha,
                actions = {
                    if (loadType == "people") {
                        TopBarActionIcon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = stringResource(R.string.edit_person_name),
                            onClick = {
                                renameInput = editablePersonName(currentTitle)
                                showRenameDialog = true
                            }
                        )
                    }
                }
            )
        },
        isLoading = !isCurrentPage || uiState.isLoading,
        error = uiState.error,
        isEmpty = uiState.photos.isEmpty(),
        emptyContent = {
            Text(
                text = stringResource(R.string.no_photos),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        onRetry = ::retryLoad,
        contentTopPadding = scrollState.topBarHeight,
        showMonthHeaders = timelineLayout.showMonthHeaders,
        showDayHeaders = timelineLayout.showDayHeaders,
        stateKey = expectedPageKey,
        gridState = gridState,
        contentPadding = PaddingValues(
            top = if (hasDistricts) scrollState.topBarHeight + 48.dp else scrollState.topBarHeight,
            bottom = navigationBarsHeight + 16.dp,
            start = 1.dp,
            end = 1.dp
        ),
        overlayContent = {
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
        },
        shareManager = viewModel.shareManager,
        scrollAlpha = scrollState.scrollAlpha,
        selectionActions = buildList {
            add(MediaSelectionAction(MediaSelectionActionType.SHARE) { viewModel.shareSelected(context) })
            if (loadType == "favorites") {
                add(MediaSelectionAction(MediaSelectionActionType.UNFAVORITE) { viewModel.unfavoriteSelected() })
            } else {
                add(MediaSelectionAction(MediaSelectionActionType.FAVORITE) { viewModel.favoriteSelected() })
            }
            add(MediaSelectionAction(MediaSelectionActionType.HIDE) { viewModel.hideSelected() })
        }
    )

    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text(stringResource(R.string.edit_person_name)) },
            text = {
                OutlinedTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    label = { Text(stringResource(R.string.person_name)) },
                    placeholder = { Text(unnamedPersonName) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.renamePerson(
                            personId = loadParam,
                            currentName = displayTitle,
                            newName = renameInput
                        ) { updatedName ->
                            currentTitle = updatedName
                            showRenameDialog = false
                        }
                    },
                    enabled = renameInput.isNotBlank()
                ) {
                    Text(stringResource(R.string.save_btn))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

private fun personDisplayName(name: String, unnamedName: String): String {
    return if (isPersonNameMissing(name)) unnamedName else name
}

private fun editablePersonName(name: String): String {
    return if (isPersonNameMissing(name)) "" else name
}

private fun isPersonNameMissing(name: String): Boolean {
    val normalized = name.trim()
    return normalized.isBlank() ||
        normalized == "未知" ||
        normalized == "未命名" ||
        normalized.equals("unknown", ignoreCase = true) ||
        normalized.equals("unnamed", ignoreCase = true)
}
