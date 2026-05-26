package com.kqstone.mtphotos.ui.gallery

import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kqstone.mtphotos.data.model.UnifiedPhotoItem
import com.kqstone.mtphotos.data.model.sortedForTimeline
import com.kqstone.mtphotos.ui.util.formatDayHeaderDate
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sqrt

data class PhotoTimelineLayout(
    val months: List<MonthGroup> = emptyList(),
    val showMonthHeaders: Boolean = true,
    val showDayHeaders: Boolean = true
)

fun buildPhotoTimelineLayout(photos: List<UnifiedPhotoItem>): PhotoTimelineLayout {
    val normalizedPhotos = photos.distinctBy { photo ->
        when {
            photo.cloudId != null -> "cloud:${photo.cloudId}"
            photo.dbId > 0 -> "db:${photo.dbId}:${photo.md5}"
            else -> "raw:${photo.md5}:${photo.fileName}:${photo.mtime}"
        }
    }

    if (normalizedPhotos.isEmpty()) {
        return PhotoTimelineLayout(emptyList(), showMonthHeaders = false, showDayHeaders = false)
    }

    val hasCompleteDateInfo = normalizedPhotos.all { hasTimelineDate(it.mtime) }
    if (!hasCompleteDateInfo) {
        val sortedPhotos = normalizedPhotos.sortedForTimeline()
        return PhotoTimelineLayout(
            months = listOf(
                MonthGroup(
                    yearMonth = "all",
                    displayTitle = "",
                    totalCount = sortedPhotos.size,
                    days = listOf(DayGroup(date = "", photos = sortedPhotos)),
                    isLoaded = true
                )
            ),
            showMonthHeaders = false,
            showDayHeaders = false
        )
    }

    val months = normalizedPhotos
        .sortedForTimeline()
        .groupBy { it.mtime.take(7) }
        .toSortedMap(compareByDescending { it })
        .map { (yearMonth, monthPhotos) ->
            MonthGroup(
                yearMonth = yearMonth,
                displayTitle = formatYearMonthLabel(yearMonth),
                totalCount = monthPhotos.size,
                days = monthPhotos
                    .groupBy { it.mtime.take(10) }
                    .toSortedMap(compareByDescending { it })
                    .map { (date, dayPhotos) ->
                        DayGroup(
                            date = date,
                            photos = dayPhotos,
                            addrSummary = buildAddrSummary(dayPhotos)
                        )
                    },
                isLoaded = true
            )
        }

    return PhotoTimelineLayout(months = months)
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TimelinePhotoGrid(
    months: List<MonthGroup>,
    columnCount: Int,
    selectedPhotoIds: Set<Double>,
    isSelectionMode: Boolean,
    selectionManager: SelectionManager,
    getThumbUrl: (UnifiedPhotoItem) -> String,
    onPhotoClick: (UnifiedPhotoItem) -> Unit,
    onColumnCountChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    showMonthHeaders: Boolean = true,
    showDayHeaders: Boolean = true,
    onMonthPlaceholderClick: ((MonthGroup) -> Unit)? = null,
    stateKey: String? = null,
    leadingContent: (@Composable () -> Unit)? = null
) {
    data class GridItem(
        val type: String,
        val key: String,
        val monthGroup: MonthGroup? = null,
        val dayGroup: DayGroup? = null,
        val photo: UnifiedPhotoItem? = null,
        val parentMonthTitle: String? = null
    )

    val gridItems by remember(months, showMonthHeaders, showDayHeaders, leadingContent) {
        derivedStateOf {
            val items = mutableListOf<GridItem>()
            if (leadingContent != null) {
                items.add(GridItem(type = "leading", key = "leading"))
            }
            for (month in months) {
                val shouldShowMonthPlaceholder = showMonthHeaders &&
                    (!month.isLoaded || (month.days.isEmpty() && month.totalCount > 0))
                if (shouldShowMonthPlaceholder) {
                    items.add(
                        GridItem(
                            type = "month",
                            key = "month_${month.yearMonth}",
                            monthGroup = month,
                            parentMonthTitle = month.displayTitle.takeIf { it.isNotBlank() }
                        )
                    )
                    continue
                }

                if (showDayHeaders) {
                    for (day in month.days) {
                        items.add(
                            GridItem(
                                type = "day",
                                key = "day_${month.yearMonth}_${day.date}",
                                dayGroup = day,
                                parentMonthTitle = month.displayTitle.takeIf { it.isNotBlank() }
                            )
                        )
                        for (photo in day.photos) {
                            items.add(
                                GridItem(
                                    type = "photo",
                                    key = "photo_${photo.uniqueKey}",
                                    photo = photo,
                                    parentMonthTitle = month.displayTitle.takeIf { it.isNotBlank() }
                                )
                            )
                        }
                    }
                } else {
                    for (photo in month.days.flatMap { it.photos }) {
                        items.add(
                            GridItem(
                                type = "photo",
                                key = "photo_${photo.uniqueKey}",
                                photo = photo,
                                parentMonthTitle = month.displayTitle.takeIf {
                                    showMonthHeaders && it.isNotBlank()
                                }
                            )
                        )
                    }
                }
            }
            items
        }
    }

    val gridState = rememberSaveable(stateKey ?: "timeline", saver = LazyGridState.Saver) {
        LazyGridState()
    }
    val coroutineScope = rememberCoroutineScope()
    var initialPinchDistance by remember { mutableFloatStateOf(0f) }
    var isPinching by remember { mutableStateOf(false) }
    var dragStartPhoto by remember { mutableStateOf<UnifiedPhotoItem?>(null) }
    var initialSelection by remember { mutableStateOf<Set<Double>>(emptySet()) }
    var currentDragPosition by remember { mutableStateOf<Offset?>(null) }

    val allPhotos = remember(gridItems) {
        gridItems.filter { it.type == "photo" }.mapNotNull { it.photo }
    }
    val currentGridItems by rememberUpdatedState(gridItems)

    fun updateDragSelection(pointerOffset: Offset) {
        val currentItem = gridState.layoutInfo.visibleItemsInfo.find { itemInfo ->
            val x = pointerOffset.x.toInt()
            val y = pointerOffset.y.toInt()
            x in itemInfo.offset.x..(itemInfo.offset.x + itemInfo.size.width) &&
                y in itemInfo.offset.y..(itemInfo.offset.y + itemInfo.size.height)
        }
        if (currentItem != null) {
            val gridItem = currentGridItems.getOrNull(currentItem.index)
            if (gridItem?.type == "photo") {
                val currentPhoto = gridItem.photo ?: return
                val startPhoto = dragStartPhoto ?: return
                val startIndex = allPhotos.indexOf(startPhoto)
                val currentIndex = allPhotos.indexOf(currentPhoto)
                if (startIndex != -1 && currentIndex != -1) {
                    val min = minOf(startIndex, currentIndex)
                    val max = maxOf(startIndex, currentIndex)
                    val dragRangeIds = allPhotos.subList(min, max + 1).map { it.id }.toSet()
                    selectionManager.setSelectedIds(initialSelection + dragRangeIds)
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
        modifier = modifier
            .fillMaxSize()
            .pointerInteropFilter { event ->
                if (event.pointerCount < 2) {
                    return@pointerInteropFilter false
                }

                val dx = event.getX(0) - event.getX(1)
                val dy = event.getY(0) - event.getY(1)
                val distance = sqrt(dx * dx + dy * dy)

                when (event.actionMasked) {
                    MotionEvent.ACTION_POINTER_DOWN -> {
                        initialPinchDistance = distance
                        isPinching = true
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (isPinching && initialPinchDistance > 50f) {
                            val scale = distance / initialPinchDistance
                            if (abs(scale - 1f) > 0.15f) {
                                val newColumns = if (scale > 1f) {
                                    (columnCount - 1).coerceAtLeast(2)
                                } else {
                                    (columnCount + 1).coerceAtMost(6)
                                }
                                if (newColumns != columnCount) {
                                    onColumnCountChange(newColumns)
                                    initialPinchDistance = distance
                                }
                            }
                        }
                        true
                    }
                    MotionEvent.ACTION_POINTER_UP,
                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL -> {
                        isPinching = false
                        initialPinchDistance = 0f
                        false
                    }
                    else -> false
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
                .pointerInput(columnCount, months) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { offset ->
                            val startItem = gridState.layoutInfo.visibleItemsInfo.find { itemInfo ->
                                val x = offset.x.toInt()
                                val y = offset.y.toInt()
                                x in itemInfo.offset.x..(itemInfo.offset.x + itemInfo.size.width) &&
                                    y in itemInfo.offset.y..(itemInfo.offset.y + itemInfo.size.height)
                            }
                            if (startItem != null) {
                                val gridItem = currentGridItems.getOrNull(startItem.index)
                                if (gridItem?.type == "photo") {
                                    val photo = gridItem.photo ?: return@detectDragGesturesAfterLongPress
                                    dragStartPhoto = photo
                                    val currentSelected = selectionManager.selectedPhotoIds.value
                                    if (photo.id !in currentSelected) {
                                        selectionManager.toggleSelection(photo.id)
                                    }
                                    initialSelection = selectionManager.selectedPhotoIds.value
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
                count = gridItems.size,
                key = { index -> gridItems[index].key },
                contentType = { index -> gridItems[index].type },
                span = { index ->
                    when (gridItems[index].type) {
                        "leading", "month", "day" -> GridItemSpan(maxLineSpan)
                        else -> GridItemSpan(1)
                    }
                }
            ) { index ->
                when (val item = gridItems[index]) {
                    else -> when (item.type) {
                        "leading" -> leadingContent?.invoke()
                        "month" -> MonthPlaceholder(
                            monthGroup = item.monthGroup ?: return@items,
                            onClick = {
                                item.monthGroup?.let { group ->
                                    onMonthPlaceholderClick?.invoke(group)
                                }
                            }
                        )
                        "day" -> DayHeader(dayGroup = item.dayGroup ?: return@items)
                        "photo" -> {
                            val photo = item.photo ?: return@items
                            PhotoThumbnail(
                                photo = photo,
                                thumbUrl = getThumbUrl(photo),
                                onClick = {
                                    if (dragStartPhoto?.id != photo.id) {
                                        if (isSelectionMode) {
                                            selectionManager.toggleSelection(photo.id)
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
        }

        val canShowLabels = showMonthHeaders && gridItems.any { !it.parentMonthTitle.isNullOrBlank() }
        if (allPhotos.isNotEmpty()) {
            LazyGridVerticalFastScroller(
                gridState = gridState,
                labelProvider = { fraction ->
                    if (!canShowLabels) {
                        null
                    } else {
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
                text = monthGroup.loadError ?: "${monthGroup.totalCount} items",
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
    if (dayGroup.date.isBlank()) {
        return
    }

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

private fun hasTimelineDate(mtime: String): Boolean {
    return mtime.length >= 10 &&
        mtime[4] == '-' &&
        mtime[7] == '-' &&
        mtime.take(10).allIndexed { index, char ->
            when (index) {
                4, 7 -> char == '-'
                else -> char.isDigit()
            }
        }
}

private fun String.allIndexed(predicate: (Int, Char) -> Boolean): Boolean {
    forEachIndexed { index, char ->
        if (!predicate(index, char)) return false
    }
    return true
}

private fun formatYearMonthLabel(yearMonth: String): String {
    val parts = yearMonth.split("-")
    return if (parts.size >= 2) {
        val month = parts[1].toIntOrNull()?.toString() ?: parts[1]
        "${parts[0]}年${month}月"
    } else {
        yearMonth
    }
}

private fun buildAddrSummary(photos: List<UnifiedPhotoItem>): String? {
    val addrCounts = photos
        .mapNotNull { normalizeAddr(it.addr) }
        .groupingBy { it }
        .eachCount()

    if (addrCounts.isEmpty()) return null
    val primary = addrCounts.maxWithOrNull(
        compareBy<Map.Entry<String, Int>> { it.value }.thenBy { it.key }
    )?.key ?: return null

    return if (addrCounts.size == 1) {
        primary
    } else {
        "$primary 等${addrCounts.size}地"
    }
}

private fun normalizeAddr(addr: String?): String? {
    val normalized = addr?.trim().orEmpty()
    return normalized.takeIf {
        it.isNotEmpty() &&
            !it.equals("null", ignoreCase = true) &&
            it != "未知"
    }
}
