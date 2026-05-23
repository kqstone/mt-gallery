package com.kqstone.mtphotos.ui.gallery

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.kqstone.mtphotos.data.model.UnifiedPhotoItem
import com.kqstone.mtphotos.data.repository.LocationItem
import com.kqstone.mtphotos.data.repository.PersonItem
import com.kqstone.mtphotos.data.repository.SearchFilters
import com.kqstone.mtphotos.data.repository.SearchTipItem
import com.kqstone.mtphotos.data.repository.SearchType
import com.kqstone.mtphotos.ui.util.PermissionHelper
import com.kqstone.mtphotos.ui.util.formatDayHeaderDate
import kotlinx.coroutines.flow.distinctUntilChanged
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

    BackHandler(enabled = isSelectionMode) {
        viewModel.selectionManager.clearSelection()
    }
    val keyboardController = LocalSoftwareKeyboardController.current
    var isSearchPanelActive by remember { mutableStateOf(false) }
    BackHandler(enabled = !isSelectionMode && isSearchPanelActive) {
        isSearchPanelActive = false
    }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showDeleteModeDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

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

    Column(modifier = Modifier.fillMaxSize()) {
        if (isSelectionMode) {
            SelectionTopBar(
                selectedCount = selectedIds.size,
                onSelectAll = { viewModel.selectAll() },
                onDelete = { showDeleteDialog = true },
                onClearSelection = { viewModel.selectionManager.clearSelection() }
            )
        } else {
            SearchHeader(
                query = uiState.searchQuery,
                searchType = uiState.searchType,
                searchFilters = uiState.searchFilters,
                suggestions = uiState.searchSuggestions,
                people = uiState.searchPeople,
                locations = uiState.searchLocations,
                isSearching = uiState.isSearching,
                isClipAvailable = uiState.isClipAvailable,
                isPanelActive = isSearchPanelActive,
                onPanelActiveChange = { active ->
                    isSearchPanelActive = active
                    if (active) viewModel.loadSearchFilterCandidates()
                },
                onQueryChange = viewModel::updateSearchQuery,
                onSearch = {
                    keyboardController?.hide()
                    isSearchPanelActive = false
                    viewModel.executeSearch()
                },
                onClear = {
                    isSearchPanelActive = false
                    viewModel.clearSearch()
                },
                onSearchTypeChange = viewModel::updateSearchType,
                onPersonFilterChange = viewModel::updatePersonFilter,
                onLocationFilterChange = viewModel::updateLocationFilter,
                onSuggestionClick = {
                    keyboardController?.hide()
                    isSearchPanelActive = false
                    viewModel.applySuggestion(it)
                },
                onSettingsClick = onSettingsClick
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
                        Text(text = uiState.error!!, color = MaterialTheme.colorScheme.error)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "点击重试",
                            modifier = Modifier.clickable {
                                if (uiState.isSearchMode) {
                                    viewModel.executeSearch()
                                } else {
                                    viewModel.loadTimeline()
                                }
                            },
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            else -> {
                val progressText = uiState.syncProgressText
                if (uiState.isSyncing && progressText != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
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
                            text = progressText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
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
                        isSearchMode = uiState.isSearchMode,
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
                if (viewModel.getDeleteMode().isEmpty()) {
                    showDeleteModeDialog = true
                } else {
                    viewModel.deleteSelected()
                }
            },
            onDismiss = { showDeleteDialog = false }
        )
    }

    if (showDeleteModeDialog) {
        DeleteModeDialog(
            onChooseDirect = {
                showDeleteModeDialog = false
                viewModel.saveDeleteMode("direct")
                context.startActivity(PermissionHelper.getManageStorageIntent(context))
                viewModel.deleteSelected()
            },
            onChooseConfirm = {
                showDeleteModeDialog = false
                viewModel.saveDeleteMode("confirm")
                viewModel.deleteSelected()
            },
            onDismiss = { showDeleteModeDialog = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SearchHeader(
    query: String,
    searchType: SearchType,
    searchFilters: SearchFilters,
    suggestions: List<SearchTipItem>,
    people: List<PersonItem>,
    locations: List<LocationItem>,
    isSearching: Boolean,
    isClipAvailable: Boolean,
    isPanelActive: Boolean,
    onPanelActiveChange: (Boolean) -> Unit,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClear: () -> Unit,
    onSearchTypeChange: (SearchType) -> Unit,
    onPersonFilterChange: (PersonItem?) -> Unit,
    onLocationFilterChange: (LocationItem?) -> Unit,
    onSuggestionClick: (String) -> Unit,
    onSettingsClick: () -> Unit
) {
    LaunchedEffect(isPanelActive) {
        if (isPanelActive) onPanelActiveChange(true)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        shape = CircleShape
                    )
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                        shape = CircleShape
                    )
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "搜索",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier
                        .weight(1f)
                        .onFocusChanged { focusState ->
                            if (focusState.isFocused) onPanelActiveChange(true)
                        },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                    decorationBox = { innerTextField ->
                        Box(modifier = Modifier.fillMaxWidth()) {
                            if (query.isEmpty()) {
                                Text(
                                    text = "搜索云端媒体",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                            }
                            innerTextField()
                        }
                    }
                )
                if (isSearching) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                } else if (query.isNotEmpty()) {
                    IconButton(
                        onClick = onClear,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "清空搜索",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(4.dp))
                IconButton(
                    onClick = { onPanelActiveChange(!isPanelActive) },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = if (isPanelActive) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = "筛选菜单",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(10.dp))
            IconButton(
                onClick = onSettingsClick,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = "设置",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        AnimatedVisibility(visible = isPanelActive) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SearchChip("综合", searchType == SearchType.AUTO) { onSearchTypeChange(SearchType.AUTO) }
                    SearchChip("文件名", searchType == SearchType.FILE_NAME) { onSearchTypeChange(SearchType.FILE_NAME) }
                    SearchChip("文本识别", searchType == SearchType.OCR_TEXT) { onSearchTypeChange(SearchType.OCR_TEXT) }
                    SearchChip("文搜图", searchType == SearchType.VISUAL_TEXT) {
                        onSearchTypeChange(SearchType.VISUAL_TEXT)
                    }
                }

                if (suggestions.isNotEmpty()) {
                    FilterSection(title = "建议")
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        suggestions.forEach { tip ->
                            SearchChip(tip.label, false) { onSuggestionClick(tip.value) }
                        }
                    }
                }

                if (people.isNotEmpty()) {
                    FilterSection(title = "人物")
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SearchChip("不限", searchFilters.personId.isNullOrBlank()) {
                            onPersonFilterChange(null)
                        }
                        people.forEach { person ->
                            SearchChip(person.name, searchFilters.personId == person.id) {
                                onPersonFilterChange(person)
                            }
                        }
                    }
                }

                if (locations.isNotEmpty()) {
                    FilterSection(title = "地点")
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SearchChip("不限", searchFilters.location.isNullOrBlank()) {
                            onLocationFilterChange(null)
                        }
                        locations.forEach { location ->
                            SearchChip(location.city, searchFilters.location == location.city) {
                                onLocationFilterChange(location)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterSection(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)
    )
}

@Composable
private fun SearchChip(
    text: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    AssistChip(
        onClick = onClick,
        enabled = enabled,
        label = { Text(text) },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            labelColor = if (selected) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    )
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun PhotoGrid(
    months: List<MonthGroup>,
    viewModel: GalleryViewModel,
    columnCount: Int,
    selectedPhotoIds: Set<Double>,
    isSelectionMode: Boolean,
    isSearchMode: Boolean,
    onPhotoClick: (UnifiedPhotoItem) -> Unit,
    onColumnCountChange: (Int) -> Unit
) {
    data class GridItem(
        val type: String,
        val key: String,
        val monthTitle: String? = null,
        val monthCount: Int = 0,
        val monthGroup: MonthGroup? = null,
        val dayGroup: DayGroup? = null,
        val photo: UnifiedPhotoItem? = null,
        val parentMonthTitle: String? = null
    )

    val gridItems by remember(months, isSearchMode) {
        derivedStateOf {
            val items = mutableListOf<GridItem>()
            for (month in months) {
                if (!isSearchMode && (!month.isLoaded || (month.days.isEmpty() && month.totalCount > 0))) {
                    items.add(
                        GridItem(
                            "month",
                            "month_${month.yearMonth}",
                            monthTitle = month.displayTitle,
                            monthCount = month.totalCount,
                            monthGroup = month,
                            parentMonthTitle = month.displayTitle
                        )
                    )
                    continue
                }
                for (day in month.days) {
                    items.add(
                        GridItem(
                            "day",
                            "day_${month.yearMonth}_${day.date}",
                            dayGroup = day,
                            parentMonthTitle = month.displayTitle
                        )
                    )
                    for (photo in day.photos) {
                        items.add(
                            GridItem(
                                "photo",
                                "photo_${photo.uniqueKey}",
                                photo = photo,
                                parentMonthTitle = month.displayTitle
                            )
                        )
                    }
                }
            }
            items
        }
    }

    val gridState = rememberLazyGridState()
    val coroutineScope = rememberCoroutineScope()
    var initialPinchDistance by remember { mutableFloatStateOf(0f) }
    var isPinching by remember { mutableStateOf(false) }

    val allPhotos = remember(gridItems) {
        gridItems.filter { it.type == "photo" }.map { it.photo!! }
    }
    val currentGridItems by rememberUpdatedState(gridItems)

    LaunchedEffect(gridState, isSearchMode) {
        if (isSearchMode) return@LaunchedEffect
        snapshotFlow {
            gridState.layoutInfo.visibleItemsInfo
                .mapNotNull { itemInfo -> currentGridItems.getOrNull(itemInfo.index)?.monthGroup?.yearMonth }
                .distinct()
        }
            .distinctUntilChanged()
            .collect { visibleMonths ->
                visibleMonths.forEach(viewModel::loadTimelineMonth)
            }
    }

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
            val gridItem = gridItems.getOrNull(currentItem.index)
            if (gridItem?.type == "photo") {
                val currentPhoto = gridItem.photo!!
                val startPhoto = dragStartPhoto
                if (startPhoto != null) {
                    val startIndex = allPhotos.indexOf(startPhoto)
                    val currentIndex = allPhotos.indexOf(currentPhoto)
                    if (startIndex != -1 && currentIndex != -1) {
                        val min = minOf(startIndex, currentIndex)
                        val max = maxOf(startIndex, currentIndex)
                        val dragRangeIds = allPhotos.subList(min, max + 1).map { it.id }.toSet()
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
                            true
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
                            false
                        }
                        else -> false
                    }
                } else {
                    false
                }
            }
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columnCount),
            contentPadding = PaddingValues(1.dp),
            horizontalArrangement = Arrangement.spacedBy(1.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
            state = gridState,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(columnCount) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { offset ->
                            val startItem = gridState.layoutInfo.visibleItemsInfo.find { itemInfo ->
                                val x = offset.x.toInt()
                                val y = offset.y.toInt()
                                x in itemInfo.offset.x .. (itemInfo.offset.x + itemInfo.size.width) &&
                                y in itemInfo.offset.y .. (itemInfo.offset.y + itemInfo.size.height)
                            }
                            if (startItem != null) {
                                val gridItem = gridItems.getOrNull(startItem.index)
                                if (gridItem?.type == "photo") {
                                    val photo = gridItem.photo!!
                                    dragStartPhoto = photo
                                    
                                    val currentSelected = viewModel.selectionManager.selectedPhotoIds.value
                                    if (photo.id !in currentSelected) {
                                        viewModel.selectionManager.toggleSelection(photo.id)
                                    }
                                    initialSelection = viewModel.selectionManager.selectedPhotoIds.value
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
                items = gridItems,
                key = { it.key },
                contentType = { it.type },
                span = { item ->
                    if (item.type == "day" || item.type == "month") {
                        GridItemSpan(maxLineSpan)
                    } else {
                        GridItemSpan(1)
                    }
                }
            ) { item ->
                when (item.type) {
                    "month" -> MonthPlaceholder(
                        monthGroup = item.monthGroup!!,
                        onClick = { viewModel.loadTimelineMonth(item.monthGroup.yearMonth) }
                    )
                    "day" -> DayHeader(dayGroup = item.dayGroup!!)
                    "photo" -> {
                        val photo = item.photo!!
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
                            isSelected = photo.id in selectedPhotoIds,
                            isSelectionMode = isSelectionMode
                        )
                    }
                }
            }
        }

        if (gridItems.isNotEmpty()) {
            LazyGridVerticalFastScroller(
                gridState = gridState,
                labelProvider = { fraction ->
                    if (isSearchMode || gridItems.isEmpty()) null else {
                        val lastIndex = gridItems.lastIndex
                        val targetIndex = (fraction * lastIndex).toInt().coerceIn(0, lastIndex)
                        gridItems.getOrNull(targetIndex)?.parentMonthTitle
                    }
                },
                modifier = Modifier.align(Alignment.CenterEnd)
            )
        }
    }
}

@Composable
private fun MonthPlaceholder(
    monthGroup: MonthGroup,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.92f))
            .clickable(enabled = !monthGroup.isLoading, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = monthGroup.displayTitle,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = monthGroup.loadError ?: "${monthGroup.totalCount} 项",
                style = MaterialTheme.typography.bodySmall,
                color = if (monthGroup.loadError == null) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                }
            )
        }
        if (monthGroup.isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp
            )
        }
    }
}

@Composable
private fun DayHeader(dayGroup: DayGroup) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.9f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = formatDayHeaderDate(dayGroup.date),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        val addrSummary = dayGroup.addrSummary
        if (addrSummary != null) {
            Spacer(modifier = Modifier.width(12.dp))
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = addrSummary,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
