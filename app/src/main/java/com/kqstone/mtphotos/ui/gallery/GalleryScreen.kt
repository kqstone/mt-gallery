package com.kqstone.mtphotos.ui.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kqstone.mtphotos.data.model.UnifiedPhotoItem
import kotlin.math.abs
import kotlin.math.sqrt

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
    var showDeleteDialog by remember { mutableStateOf(false) }

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
                title = { Text("MT Gallery") },
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
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
            uiState.error != null && uiState.months.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = uiState.error!!,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "点击重试",
                            modifier = Modifier.clickable { viewModel.loadTimeline() },
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            else -> {
                PullToRefreshBox(
                    isRefreshing = uiState.isRefreshing,
                    onRefresh = { viewModel.refresh() },
                    modifier = Modifier.fillMaxSize()
                ) {
                    PhotoGrid(
                        months = uiState.months,
                        viewModel = viewModel,
                        columnCount = uiState.columnCount,
                        selectedPhotoIds = selectedIds,
                        isSelectionMode = isSelectionMode,
                        onPhotoClick = onPhotoClick,
                        onColumnCountChange = { viewModel.updateColumnCount(it) }
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

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun PhotoGrid(
    months: List<MonthGroup>,
    viewModel: GalleryViewModel,
    columnCount: Int,
    selectedPhotoIds: Set<Double>,
    isSelectionMode: Boolean,
    onPhotoClick: (UnifiedPhotoItem) -> Unit,
    onColumnCountChange: (Int) -> Unit
) {
    data class GridItem(
        val type: String,
        val key: String,
        val monthYearMonth: String? = null,
        val dayGroup: DayGroup? = null,
        val photo: UnifiedPhotoItem? = null
    )

    val gridItems by remember(months) {
        derivedStateOf {
            val items = mutableListOf<GridItem>()
            for (month in months) {
                items.add(GridItem("month", "month_${month.yearMonth}", monthYearMonth = month.yearMonth))
                if (month.isLoaded) {
                    for (day in month.days) {
                        items.add(GridItem("day", "day_${month.yearMonth}_${day.date}", dayGroup = day))
                        for (photo in day.photos) {
                            items.add(GridItem("photo", "photo_${photo.uniqueKey}", photo = photo))
                        }
                    }
                }
            }
            items
        }
    }

    val gridState = rememberLazyGridState()
    AutoLoadVisibleMonths(gridState, months, viewModel)

    // Pinch-to-zoom state via Android MotionEvent
    var initialPinchDistance by remember { mutableFloatStateOf(0f) }
    var isPinching by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInteropFilter { event ->
                val pointerCount = event.pointerCount
                if (pointerCount >= 2) {
                    val dx = event.getX(0) - event.getX(1)
                    val dy = event.getY(0) - event.getY(1)
                    val distance = sqrt(dx * dx + dy * dy)

                    when (event.actionMasked) {
                        android.view.MotionEvent.ACTION_POINTER_DOWN -> {
                            initialPinchDistance = distance
                            isPinching = true
                            true // consume to prevent grid scroll during pinch
                        }
                        android.view.MotionEvent.ACTION_MOVE -> {
                            if (isPinching && initialPinchDistance > 50f) {
                                val scale = distance / initialPinchDistance
                                if (abs(scale - 1f) > 0.15f) {
                                    val newCols = if (scale > 1f) {
                                        (columnCount - 1).coerceAtLeast(2)
                                    } else {
                                        (columnCount + 1).coerceAtMost(6)
                                    }
                                    if (newCols != columnCount) {
                                        onColumnCountChange(newCols)
                                        initialPinchDistance = distance
                                    }
                                }
                            }
                            true
                        }
                        android.view.MotionEvent.ACTION_POINTER_UP,
                        android.view.MotionEvent.ACTION_UP,
                        android.view.MotionEvent.ACTION_CANCEL -> {
                            isPinching = false
                            initialPinchDistance = 0f
                            false // let Compose handle the up event
                        }
                        else -> false
                    }
                } else {
                    // Single finger: pass through for scrolling and clicks
                    false
                }
            }
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columnCount),
            contentPadding = PaddingValues(2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            state = gridState,
            modifier = Modifier.fillMaxSize()
        ) {
            items(
                items = gridItems,
                key = { it.key },
                span = { item ->
                    if (item.type == "month" || item.type == "day") {
                        GridItemSpan(maxLineSpan)
                    } else {
                        GridItemSpan(1)
                    }
                }
            ) { item ->
                when (item.type) {
                    "month" -> {
                        val month = months.find { it.yearMonth == item.monthYearMonth }
                        MonthHeader(month = month!!)
                    }
                    "day" -> {
                        DayHeader(dayGroup = item.dayGroup!!)
                    }
                    "photo" -> {
                        val photo = item.photo!!
                        val thumbUrl = viewModel.getThumbUrl(photo)
                        PhotoThumbnail(
                            photo = photo,
                            thumbUrl = thumbUrl,
                            onClick = {
                                if (isSelectionMode) {
                                    viewModel.selectionManager.toggleSelection(photo.id)
                                } else {
                                    onPhotoClick(photo)
                                }
                            },
                            onLongClick = {
                                viewModel.selectionManager.toggleSelection(photo.id)
                            },
                            isSelected = photo.id in selectedPhotoIds,
                            isSelectionMode = isSelectionMode,
                            modifier = Modifier.pointerInput(photo.id) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { viewModel.selectionManager.startDragSelection(photo.id) },
                                    onDrag = { change, _ ->
                                        change.consume()
                                        viewModel.selectionManager.dragSelect(photo.id)
                                    },
                                    onDragEnd = {},
                                    onDragCancel = {}
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AutoLoadVisibleMonths(
    gridState: LazyGridState,
    months: List<MonthGroup>,
    viewModel: GalleryViewModel
) {
    val loadedMonths = months.filter { it.isLoaded }.map { it.yearMonth }.toSet()

    LaunchedEffect(gridState, months) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo }
            .collect { visibleItems ->
                for (item in visibleItems) {
                    val key = item.key as? String ?: continue
                    if (key.startsWith("month_")) {
                        val yearMonth = key.removePrefix("month_")
                        if (yearMonth !in loadedMonths) {
                            viewModel.loadMonthFiles(yearMonth)
                        }
                    }
                }
            }
    }
}

@Composable
private fun MonthHeader(month: MonthGroup) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = month.displayTitle,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "${month.totalCount}张",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DayHeader(dayGroup: DayGroup) {
    Text(
        text = dayGroup.date,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f))
            .padding(horizontal = 16.dp, vertical = 8.dp)
    )
}
