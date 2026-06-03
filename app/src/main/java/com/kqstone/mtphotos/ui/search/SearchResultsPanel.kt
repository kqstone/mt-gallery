package com.kqstone.mtphotos.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.kqstone.mtphotos.R
import com.kqstone.mtphotos.data.model.UnifiedPhotoItem
import com.kqstone.mtphotos.data.repository.SearchType
import com.kqstone.mtphotos.ui.gallery.MonthGroup
import com.kqstone.mtphotos.ui.gallery.SelectionManager
import com.kqstone.mtphotos.ui.gallery.TimelinePhotoGrid
import com.kqstone.mtphotos.ui.util.UiText

@Composable
fun SearchResultsPanel(
    months: List<MonthGroup>,
    searchType: SearchType,
    isSearching: Boolean,
    searchError: UiText?,
    columnCount: Int,
    selectedPhotoIds: Set<Double>,
    isSelectionMode: Boolean,
    selectionManager: SelectionManager,
    getThumbUrl: (UnifiedPhotoItem) -> String,
    onPhotoClick: (UnifiedPhotoItem) -> Unit,
    onColumnCountChange: (Int) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    topPadding: Dp = 0.dp,
    bottomPadding: Dp = 80.dp
) {
    val gridState = rememberLazyGridState()
    val resultCount = remember(months) {
        months.sumOf { month -> month.days.sumOf { day -> day.photos.size } }
    }
    val stateKey = "$searchType:$resultCount:${searchError?.hashCode() ?: 0}"

    LaunchedEffect(stateKey) {
        gridState.scrollToItem(0)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(top = topPadding)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.search_results),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = when {
                    isSearching && resultCount == 0 -> stringResource(R.string.searching_loading)
                    resultCount > 0 -> stringResource(R.string.item_count_short, resultCount)
                    else -> ""
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        when {
            isSearching && resultCount == 0 -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            searchError != null && resultCount == 0 -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = searchError.asString(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.click_to_retry),
                            modifier = Modifier.clickable(onClick = onRetry),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
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
                    modifier = Modifier.weight(1f),
                    showMonthHeaders = false,
                    showDayHeaders = searchType != SearchType.VISUAL_TEXT,
                    stateKey = "search-results",
                    gridState = gridState,
                    contentPadding = PaddingValues(
                        start = 1.dp,
                        end = 1.dp,
                        bottom = bottomPadding
                    )
                )
            }
        }
    }
}
