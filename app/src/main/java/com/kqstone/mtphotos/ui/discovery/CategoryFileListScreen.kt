package com.kqstone.mtphotos.ui.discovery

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kqstone.mtphotos.data.model.UnifiedPhotoItem
import com.kqstone.mtphotos.ui.gallery.DeleteConfirmDialog
import com.kqstone.mtphotos.ui.gallery.TimelinePhotoGrid
import com.kqstone.mtphotos.ui.gallery.buildPhotoTimelineLayout
import com.kqstone.mtphotos.ui.gallery.SelectionTopBar
import com.kqstone.mtphotos.ui.util.PermissionHelper

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
    val selectedIds by viewModel.selectionManager.selectedPhotoIds.collectAsState()
    val isSelectionMode = selectedIds.isNotEmpty()
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
            "location" -> viewModel.loadLocationFiles(loadParam)
            "album" -> viewModel.loadAlbumFiles(loadParam.toDoubleOrNull() ?: 0.0)
            "favorites" -> viewModel.loadFavoritesFiles()
            "recent" -> viewModel.loadRecentFiles()
            "videos" -> viewModel.loadVideoFiles()
            "trash" -> viewModel.loadTrashFiles()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (isSelectionMode) {
            SelectionTopBar(
                selectedCount = selectedIds.size,
                onSelectAll = { viewModel.selectAll() },
                onDelete = { showDeleteDialog.value = true },
                onClearSelection = { viewModel.selectionManager.clearSelection() },
                modifier = Modifier.statusBarsPadding()
            )
        } else {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }

        if (loadType == "location" && uiState.locationDistricts.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = uiState.selectedDistrict == null,
                    onClick = { viewModel.loadLocationFiles(loadParam, null) },
                    label = { Text("全部") }
                )
                uiState.locationDistricts.forEach { district ->
                    FilterChip(
                        selected = uiState.selectedDistrict == district.city,
                        onClick = { viewModel.loadLocationFiles(loadParam, district.city) },
                        label = { Text("${district.city} ${district.count}") }
                    )
                }
            }
        }

        when {
            uiState.isLoading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            uiState.error != null -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = uiState.error!!,
                            color = MaterialTheme.colorScheme.error
                        )
                        androidx.compose.foundation.text.ClickableText(
                            text = androidx.compose.ui.text.AnnotatedString("点击重试"),
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.primary
                            ),
                            onClick = {
                                when (loadType) {
                                    "people" -> viewModel.loadPeopleFiles(loadParam)
                                    "scene" -> viewModel.loadSceneFiles(loadParam, loadParam2)
                                    "location" -> viewModel.loadLocationFiles(loadParam, uiState.selectedDistrict)
                                    "album" -> viewModel.loadAlbumFiles(loadParam.toDoubleOrNull() ?: 0.0)
                                    "favorites" -> viewModel.loadFavoritesFiles()
                                    "recent" -> viewModel.loadRecentFiles()
                                    "videos" -> viewModel.loadVideoFiles()
                                    "trash" -> viewModel.loadTrashFiles()
                                }
                            }
                        )
                    }
                }
            }
            uiState.photos.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "暂无照片",
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
                    modifier = Modifier.fillMaxSize()
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
}
