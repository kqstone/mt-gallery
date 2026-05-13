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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.kqstone.mtphotos.data.repository.PhotoItem
import kotlin.math.abs
import kotlin.math.sqrt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    viewModel: GalleryViewModel,
    onPhotoClick: (PhotoItem) -> Unit,
    onSettingsClick: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    var showDeleteDialog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        if (uiState.isSelectionMode) {
            SelectionTopBar(
                selectedCount = uiState.selectedPhotoIds.size,
                onSelectAll = { viewModel.selectAll() },
                onDelete = { showDeleteDialog = true },
                onClearSelection = { viewModel.clearSelection() }
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
                        selectedPhotoIds = uiState.selectedPhotoIds,
                        isSelectionMode = uiState.isSelectionMode,
                        onPhotoClick = onPhotoClick,
                        onToggleSelection = { viewModel.toggleSelection(it) },
                        onStartDragSelection = { viewModel.startDragSelection(it) },
                        onDragSelect = { viewModel.dragSelect(it) },
                        onColumnCountChange = { viewModel.updateColumnCount(it) }
                    )
                }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("确认删除") },
            text = { Text("确定要删除选中的 ${uiState.selectedPhotoIds.size} 张照片吗？\n照片将移入服务端回收站。") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    viewModel.deleteSelected()
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun SelectionTopBar(
    selectedCount: Int,
    onSelectAll: () -> Unit,
    onDelete: () -> Unit,
    onClearSelection: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onClearSelection) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "取消选择")
        }
        Text(
            text = "已选择 $selectedCount 项",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onSelectAll) {
            Icon(Icons.Default.SelectAll, contentDescription = "全选")
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "删除",
                tint = MaterialTheme.colorScheme.error
            )
        }
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
    onPhotoClick: (PhotoItem) -> Unit,
    onToggleSelection: (Double) -> Unit,
    onStartDragSelection: (Double) -> Unit,
    onDragSelect: (Double) -> Unit,
    onColumnCountChange: (Int) -> Unit
) {
    data class GridItem(
        val type: String,
        val key: String,
        val monthYearMonth: String? = null,
        val dayGroup: DayGroup? = null,
        val photo: PhotoItem? = null
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
                            items.add(GridItem("photo", "photo_${photo.id}", photo = photo))
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
                        MonthHeader(
                            month = month!!,
                            onClick = { viewModel.toggleMonthExpand(month.yearMonth) }
                        )
                    }
                    "day" -> {
                        DayHeader(dayGroup = item.dayGroup!!)
                    }
                    "photo" -> {
                        val photo = item.photo!!
                        PhotoThumbnail(
                            photo = photo,
                            thumbUrl = viewModel.getThumbUrl(photo.md5, photo.id),
                            onClick = {
                                if (isSelectionMode) {
                                    onToggleSelection(photo.id)
                                } else {
                                    onPhotoClick(photo)
                                }
                            },
                            onLongClick = {
                                onToggleSelection(photo.id)
                            },
                            isSelected = photo.id in selectedPhotoIds,
                            isSelectionMode = isSelectionMode,
                            modifier = Modifier.pointerInput(photo.id) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { onStartDragSelection(photo.id) },
                                    onDrag = { change, _ ->
                                        change.consume()
                                        onDragSelect(photo.id)
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
private fun MonthHeader(month: MonthGroup, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = month.displayTitle,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${month.totalCount}张",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (month.isLoaded) "收起" else "展开",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
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
