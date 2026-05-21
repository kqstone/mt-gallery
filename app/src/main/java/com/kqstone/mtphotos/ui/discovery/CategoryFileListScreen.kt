package com.kqstone.mtphotos.ui.discovery

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.kqstone.mtphotos.data.model.UnifiedPhotoItem
import com.kqstone.mtphotos.ui.gallery.DeleteConfirmDialog
import com.kqstone.mtphotos.ui.gallery.LazyGridVerticalFastScroller
import com.kqstone.mtphotos.ui.gallery.PhotoThumbnail
import com.kqstone.mtphotos.ui.gallery.SelectionTopBar

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
    var showDeleteDialog by remember { mutableStateOf(false) }

    val gridState = rememberLazyGridState()
    val coroutineScope = rememberCoroutineScope()

    var dragStartPhoto by remember { mutableStateOf<UnifiedPhotoItem?>(null) }
    var initialSelection by remember { mutableStateOf<Set<Double>>(emptySet()) }
    var currentDragPosition by remember { mutableStateOf<Offset?>(null) }

    fun updateDragSelection(pointerOffset: Offset) {
        val currentItem = gridState.layoutInfo.visibleItemsInfo.find { itemInfo ->
            val x = pointerOffset.x.toInt()
            val y = pointerOffset.y.toInt()
            x in itemInfo.offset.x .. (itemInfo.offset.x + itemInfo.size.width) &&
            y in itemInfo.offset.y .. (itemInfo.offset.y + itemInfo.size.height)
        }
        if (currentItem != null) {
            val photoId = currentItem.key as? Double
            if (photoId != null) {
                val currentPhoto = uiState.photos.find { it.id == photoId }
                val startPhoto = dragStartPhoto
                if (currentPhoto != null && startPhoto != null) {
                    val startIndex = uiState.photos.indexOf(startPhoto)
                    val currentIndex = uiState.photos.indexOf(currentPhoto)
                    if (startIndex != -1 && currentIndex != -1) {
                        val min = minOf(startIndex, currentIndex)
                        val max = maxOf(startIndex, currentIndex)
                        val dragRangeIds = uiState.photos.subList(min, max + 1).map { it.id }.toSet()
                        viewModel.selectionManager.setSelectedIds(initialSelection + dragRangeIds)
                    }
                }
            }
        }
    }

    val density = LocalDensity.current
    LaunchedEffect(currentDragPosition) {
        val pos = currentDragPosition
        if (pos != null) {
            val gridHeight = gridState.layoutInfo.viewportSize.height
            if (gridHeight > 0) {
                val threshold = with(density) { 80.dp.toPx() }
                val maxScrollSpeed = 25f

                if (pos.y < threshold) {
                    while (currentDragPosition != null && currentDragPosition!!.y < threshold) {
                        val activePos = currentDragPosition ?: break
                        val ratio = ((threshold - activePos.y) / threshold).coerceIn(0f, 1f)
                        val scrollAmount = -(maxScrollSpeed * ratio)
                        gridState.scrollBy(scrollAmount)
                        updateDragSelection(activePos)
                        delay(16)
                    }
                } else if (pos.y > gridHeight - threshold) {
                    while (currentDragPosition != null && currentDragPosition!!.y > gridHeight - threshold) {
                        val activePos = currentDragPosition ?: break
                        val ratio = ((activePos.y - (gridHeight - threshold)) / threshold).coerceIn(0f, 1f)
                        val scrollAmount = maxScrollSpeed * ratio
                        gridState.scrollBy(scrollAmount)
                        updateDragSelection(activePos)
                        delay(16)
                    }
                }
            }
        }
    }

    LaunchedEffect(loadType, loadParam, loadParam2) {
        when (loadType) {
            "people" -> viewModel.loadPeopleFiles(loadParam)
            "scene" -> viewModel.loadSceneFiles(loadParam, loadParam2)
            "location" -> viewModel.loadLocationFiles(loadParam)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (isSelectionMode) {
            SelectionTopBar(
                selectedCount = selectedIds.size,
                onSelectAll = { viewModel.selectAll() },
                onDelete = { showDeleteDialog = true },
                onClearSelection = { viewModel.selectionManager.clearSelection() }
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
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "点击重试",
                            modifier = Modifier.clickable {
                                when (loadType) {
                                    "people" -> viewModel.loadPeopleFiles(loadParam)
                                    "scene" -> viewModel.loadSceneFiles(loadParam, loadParam2)
                                    "location" -> viewModel.loadLocationFiles(loadParam)
                                }
                            },
                            color = MaterialTheme.colorScheme.primary
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
                Box(modifier = Modifier.fillMaxSize()) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(uiState.columnCount),
                        contentPadding = PaddingValues(2.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        state = gridState,
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(uiState.columnCount) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { offset ->
                                        val startItem = gridState.layoutInfo.visibleItemsInfo.find { itemInfo ->
                                            val x = offset.x.toInt()
                                            val y = offset.y.toInt()
                                            x in itemInfo.offset.x .. (itemInfo.offset.x + itemInfo.size.width) &&
                                            y in itemInfo.offset.y .. (itemInfo.offset.y + itemInfo.size.height)
                                        }
                                        if (startItem != null) {
                                            val photoId = startItem.key as? Double
                                            if (photoId != null) {
                                                val photo = uiState.photos.find { it.id == photoId }
                                                if (photo != null) {
                                                    dragStartPhoto = photo
                                                    val currentSelected = viewModel.selectionManager.selectedPhotoIds.value
                                                    if (photo.id !in currentSelected) {
                                                        viewModel.selectionManager.toggleSelection(photo.id)
                                                    }
                                                    initialSelection = viewModel.selectionManager.selectedPhotoIds.value
                                                }
                                            }
                                        }
                                    },
                                    onDrag = { change, _ ->
                                        change.consume()
                                        currentDragPosition = change.position
                                        updateDragSelection(change.position)
                                    },
                                    onDragEnd = {
                                        currentDragPosition = null
                                        coroutineScope.launch {
                                            delay(150)
                                            dragStartPhoto = null
                                        }
                                    },
                                    onDragCancel = {
                                        currentDragPosition = null
                                        coroutineScope.launch {
                                            delay(150)
                                            dragStartPhoto = null
                                        }
                                    }
                                )
                            }
                    ) {
                        items(
                            items = uiState.photos,
                            key = { it.id }
                        ) { photo ->
                            PhotoThumbnail(
                                photo = photo,
                                thumbUrl = viewModel.getThumbUrl(photo),
                                onClick = {
                                    if (dragStartPhoto?.id != photo.id) {
                                        if (isSelectionMode) {
                                            viewModel.selectionManager.toggleSelection(photo.id)
                                        } else {
                                            onPhotoClick(photo)
                                        }
                                    }
                                },
                                onLongClick = null,
                                isSelected = photo.id in selectedIds,
                                isSelectionMode = isSelectionMode
                            )
                        }
                    }

                    LazyGridVerticalFastScroller(
                        gridState = gridState,
                        modifier = Modifier.align(Alignment.CenterEnd)
                    )
                }
            }
        }
    }

    if (showDeleteDialog) {
        DeleteConfirmDialog(
            selectedCount = selectedIds.size,
            onConfirm = {
                showDeleteDialog = false
                viewModel.deleteSelected()
            },
            onDismiss = { showDeleteDialog = false }
        )
    }
}
