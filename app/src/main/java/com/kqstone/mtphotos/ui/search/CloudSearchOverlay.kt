package com.kqstone.mtphotos.ui.search

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.kqstone.mtphotos.data.model.UnifiedPhotoItem
import com.kqstone.mtphotos.data.repository.LocationItem
import com.kqstone.mtphotos.data.repository.PersonItem
import com.kqstone.mtphotos.data.repository.SearchFilters
import com.kqstone.mtphotos.data.repository.SearchTipItem
import com.kqstone.mtphotos.data.repository.SearchType
import com.kqstone.mtphotos.ui.gallery.DeleteConfirmDialog
import com.kqstone.mtphotos.ui.gallery.SelectionTopBar
import com.kqstone.mtphotos.ui.util.PermissionHelper

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CloudSearchOverlay(
    viewModel: CloudSearchViewModel,
    onPhotoClick: (UnifiedPhotoItem, List<UnifiedPhotoItem>) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val selectedIds by viewModel.selectionManager.selectedPhotoIds.collectAsState()
    val isSelectionMode = selectedIds.isNotEmpty()
    val keyboardController = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    var showDeleteDialog by remember { mutableStateOf(false) }
    var isFilterPanelVisible by remember { mutableStateOf(true) }

    BackHandler(enabled = true) {
        if (isSelectionMode) {
            viewModel.selectionManager.clearSelection()
        } else {
            keyboardController?.hide()
            onClose()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.loadFilterCandidates()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {}
            )
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (isSelectionMode) {
                SelectionTopBar(
                    selectedCount = selectedIds.size,
                    onSelectAll = viewModel::selectAll,
                    onDelete = { showDeleteDialog = true },
                    onClearSelection = { viewModel.selectionManager.clearSelection() },
                    scrollAlpha = 1f
                )
            } else {
                CloudSearchTopBar(
                    query = uiState.query,
                    isSearching = uiState.isSearching,
                    isFilterPanelVisible = isFilterPanelVisible,
                    onQueryChange = viewModel::updateQuery,
                    onQueryFocused = { isFilterPanelVisible = true },
                    onToggleFilters = { isFilterPanelVisible = !isFilterPanelVisible },
                    onSearch = {
                        keyboardController?.hide()
                        isFilterPanelVisible = false
                        viewModel.executeSearch()
                    },
                    onClear = viewModel::clearSearch,
                    onClose = {
                        keyboardController?.hide()
                        onClose()
                    }
                )
            }

            Box(modifier = Modifier.weight(1f)) {
                val hasResults = uiState.resultMonths.isNotEmpty()
                val shouldShowResults = uiState.isActive || uiState.isSearching || uiState.error != null || hasResults
                if (shouldShowResults) {
                    SearchResultsPanel(
                        months = uiState.resultMonths,
                        searchType = uiState.searchType,
                        isSearching = uiState.isSearching,
                        searchError = uiState.error,
                        columnCount = uiState.columnCount,
                        selectedPhotoIds = selectedIds,
                        isSelectionMode = isSelectionMode,
                        selectionManager = viewModel.selectionManager,
                        getThumbUrl = viewModel::getThumbUrl,
                        onPhotoClick = { photo -> onPhotoClick(photo, viewModel.getAllLoadedPhotos()) },
                        onColumnCountChange = viewModel::updateColumnCount,
                        onRetry = {
                            isFilterPanelVisible = false
                            viewModel.executeSearch()
                        },
                        modifier = Modifier.fillMaxSize(),
                        bottomPadding = 16.dp
                    )
                }

                SearchFilterAnimatedPanel(
                    visible = isFilterPanelVisible && !isSelectionMode,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    SearchFilterContent(
                        searchType = uiState.searchType,
                        filters = uiState.filters,
                        suggestions = uiState.suggestions,
                        people = uiState.people,
                        locations = uiState.locations,
                        isLoadingFilters = uiState.isLoadingFilters,
                        isClipAvailable = uiState.isClipAvailable,
                        onSearchTypeChange = viewModel::updateSearchType,
                        onPersonFilterChange = viewModel::updatePersonFilter,
                        onLocationFilterChange = viewModel::updateLocationFilter,
                        onSuggestionClick = {
                            keyboardController?.hide()
                            isFilterPanelVisible = false
                            viewModel.applySuggestion(it)
                        },
                        modifier = Modifier.fillMaxSize()
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
                if (PermissionHelper.requestManageStoragePermission(context)) {
                    viewModel.deleteSelected()
                }
            },
            onDismiss = { showDeleteDialog = false }
        )
    }
}

@Composable
private fun SearchFilterAnimatedPanel(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = slideInVertically(
            animationSpec = tween(durationMillis = 220),
            initialOffsetY = { fullHeight -> -fullHeight / 4 }
        ) + fadeIn(animationSpec = tween(durationMillis = 180)),
        exit = slideOutVertically(
            animationSpec = tween(durationMillis = 180),
            targetOffsetY = { fullHeight -> -fullHeight / 4 }
        ) + fadeOut(animationSpec = tween(durationMillis = 140))
    ) {
        content()
    }
}

@Composable
private fun CloudSearchTopBar(
    query: String,
    isSearching: Boolean,
    isFilterPanelVisible: Boolean,
    onQueryChange: (String) -> Unit,
    onQueryFocused: () -> Unit,
    onToggleFilters: () -> Unit,
    onSearch: () -> Unit,
    onClear: () -> Unit,
    onClose: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onClose,
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Row(
            modifier = Modifier
                .weight(1f)
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                    shape = CircleShape
                )
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                    shape = CircleShape
                )
                .padding(horizontal = 14.dp, vertical = 8.dp),
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
                    .focusRequester(focusRequester)
                    .onFocusChanged { focusState ->
                        if (focusState.isFocused) onQueryFocused()
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
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.62f)
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
            IconButton(
                onClick = onToggleFilters,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = if (isFilterPanelVisible) {
                        Icons.Default.KeyboardArrowUp
                    } else {
                        Icons.Default.KeyboardArrowDown
                    },
                    contentDescription = "筛选面板",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun SearchFilterContent(
    searchType: SearchType,
    filters: SearchFilters,
    suggestions: List<SearchTipItem>,
    people: List<PersonItem>,
    locations: List<LocationItem>,
    isLoadingFilters: Boolean,
    isClipAvailable: Boolean,
    onSearchTypeChange: (SearchType) -> Unit,
    onPersonFilterChange: (PersonItem?) -> Unit,
    onLocationFilterChange: (LocationItem?) -> Unit,
    onSuggestionClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp)
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SearchChip("综合", searchType == SearchType.AUTO) {
                onSearchTypeChange(SearchType.AUTO)
            }
            SearchChip("文件名", searchType == SearchType.FILE_NAME) {
                onSearchTypeChange(SearchType.FILE_NAME)
            }
            SearchChip("文本识别", searchType == SearchType.OCR_TEXT) {
                onSearchTypeChange(SearchType.OCR_TEXT)
            }
            SearchChip("识图", searchType == SearchType.VISUAL_TEXT, enabled = isClipAvailable) {
                onSearchTypeChange(SearchType.VISUAL_TEXT)
            }
        }

        if (suggestions.isNotEmpty()) {
            FilterSectionTitle("建议")
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
            FilterSectionTitle("人物")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SearchChip("不限", filters.personId.isNullOrBlank()) {
                    onPersonFilterChange(null)
                }
                people.forEach { person ->
                    SearchChip(person.name, filters.personId == person.id) {
                        onPersonFilterChange(person)
                    }
                }
            }
        }

        if (locations.isNotEmpty()) {
            FilterSectionTitle("地点")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SearchChip("不限", filters.location.isNullOrBlank()) {
                    onLocationFilterChange(null)
                }
                locations.forEach { location ->
                    SearchChip(location.city, filters.location == location.city) {
                        onLocationFilterChange(location)
                    }
                }
            }
        }

        if (isLoadingFilters) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "正在加载筛选项",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun FilterSectionTitle(title: String) {
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
            },
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
        )
    )
}
