package com.kqstone.mtphotos.ui.search

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.res.stringResource
import com.kqstone.mtphotos.R
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Tune
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.kqstone.mtphotos.data.model.UnifiedPhotoItem
import com.kqstone.mtphotos.data.repository.LocationItem
import com.kqstone.mtphotos.data.repository.PersonItem
import com.kqstone.mtphotos.data.repository.SearchFilters
import com.kqstone.mtphotos.data.repository.SearchTipItem
import com.kqstone.mtphotos.data.repository.SearchType
import com.kqstone.mtphotos.ui.gallery.DeleteConfirmDialog
import com.kqstone.mtphotos.ui.gallery.MediaSelectionAction
import com.kqstone.mtphotos.ui.gallery.MediaSelectionActionType
import com.kqstone.mtphotos.ui.gallery.PublishSelectionBottomBar
import com.kqstone.mtphotos.ui.gallery.SelectionBottomBar
import com.kqstone.mtphotos.ui.gallery.SelectionBottomBarHeight
import com.kqstone.mtphotos.ui.gallery.SelectionTopBar
import com.kqstone.mtphotos.ui.util.PermissionHelper
import com.kqstone.mtphotos.ui.util.ToastMessageEffect
import com.kqstone.mtphotos.ui.util.hazeContentSource
import com.kqstone.mtphotos.ui.util.OverlayStatusBarStyleEffect
import com.kqstone.mtphotos.ui.util.stableStatusBarsPadding

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CloudSearchOverlay(
    viewModel: CloudSearchViewModel,
    onPhotoClick: (UnifiedPhotoItem, List<UnifiedPhotoItem>) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    OverlayStatusBarStyleEffect(darkOverlay = false)

    val uiState by viewModel.uiState.collectAsState()
    val selectedIds by viewModel.selectionManager.selectedPhotoIds.collectAsState()
    val isSelectionMode = selectedIds.isNotEmpty()

    val keyboardController = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    var showDeleteDialog by remember { mutableStateOf(false) }
    var isFilterPanelVisible by rememberSaveable { mutableStateOf(true) }
    val selectionActions = if (isSelectionMode) {
        listOf(
            MediaSelectionAction(MediaSelectionActionType.SHARE) { viewModel.shareSelected(context) },
            MediaSelectionAction(MediaSelectionActionType.FAVORITE) { viewModel.favoriteSelected() },
            MediaSelectionAction(MediaSelectionActionType.HIDE) { viewModel.hideSelected() },
            MediaSelectionAction(MediaSelectionActionType.DELETE) { showDeleteDialog = true }
        )
    } else {
        emptyList()
    }

    PublishSelectionBottomBar(
        visible = isSelectionMode,
        actions = selectionActions
    )

    ToastMessageEffect(
        message = uiState.toastMessage,
        onConsumed = viewModel::clearToastMessage
    )

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
                    onSelectAll = { viewModel.selectAll() },
                    onClearSelection = { viewModel.selectionManager.clearSelection() },
                    scrollAlpha = 1f
                )
            } else {
                CloudSearchTopBar(
                    query = uiState.query,
                    isSearching = uiState.isSearching,
                    isFilterPanelVisible = isFilterPanelVisible,
                    autoFocus = !uiState.isActive,
                    onQueryChange = viewModel::updateQuery,
                    onQueryFocused = {
                        if (!uiState.isActive) {
                            isFilterPanelVisible = true
                        }
                    },
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

            Box(
                modifier = Modifier
                    .weight(1f)
                    .hazeContentSource()
            ) {
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
                        bottomPadding = if (isSelectionMode) SelectionBottomBarHeight + 16.dp else 16.dp
                    )
                }

                SearchFilterAnimatedPanel(
                    visible = isFilterPanelVisible && !isSelectionMode,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    SearchFilterContent(
                        query = uiState.query,
                        searchType = uiState.searchType,
                        filters = uiState.filters,
                        searchHistory = uiState.searchHistory,
                        suggestions = uiState.suggestions,
                        people = uiState.people,
                        locations = uiState.locations,
                        isLoadingFilters = uiState.isLoadingFilters,
                        isClipAvailable = uiState.isClipAvailable,
                        portraitUrlProvider = { person ->
                            viewModel.getPortraitUrl(person.id, person.coverFileId)
                        },
                        onSearchTypeChange = viewModel::updateSearchType,
                        onPersonFilterChange = viewModel::updatePersonFilter,
                        onLocationFilterChange = viewModel::updateLocationFilter,
                        onHistoryClick = {
                            keyboardController?.hide()
                            isFilterPanelVisible = false
                            viewModel.applyHistory(it)
                        },
                        onHistoryRemove = viewModel::removeSearchHistory,
                        onHistoryClear = viewModel::clearSearchHistory,
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

        if (isSelectionMode) {
            SelectionBottomBar(
                actions = selectionActions,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
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

    com.kqstone.mtphotos.ui.util.ShareProgressOverlay(viewModel.shareManager)
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
    autoFocus: Boolean,
    onQueryChange: (String) -> Unit,
    onQueryFocused: () -> Unit,
    onToggleFilters: () -> Unit,
    onSearch: () -> Unit,
    onClear: () -> Unit,
    onClose: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(autoFocus) {
        if (autoFocus) {
            focusRequester.requestFocus()
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .stableStatusBarsPadding()
            .padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onClose,
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.back),
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Row(
            modifier = Modifier
                .weight(1f)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    shape = CircleShape
                )
                .clip(CircleShape)
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = stringResource(R.string.search),
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
                                text = stringResource(R.string.search_cloud_media),
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
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp
                )
            } else if (query.isNotEmpty()) {
                IconButton(
                    onClick = onClear,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.clear_search),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
            IconButton(
                onClick = onToggleFilters,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    imageVector = if (isFilterPanelVisible) {
                        Icons.Default.KeyboardArrowUp
                    } else {
                        Icons.Default.KeyboardArrowDown
                    },
                    contentDescription = stringResource(R.string.filter_panel),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun SearchFilterContent(
    query: String,
    searchType: SearchType,
    filters: SearchFilters,
    searchHistory: List<SearchHistoryItem>,
    suggestions: List<SearchTipItem>,
    people: List<PersonItem>,
    locations: List<LocationItem>,
    isLoadingFilters: Boolean,
    isClipAvailable: Boolean,
    portraitUrlProvider: (PersonItem) -> String,
    onSearchTypeChange: (SearchType) -> Unit,
    onPersonFilterChange: (PersonItem?) -> Unit,
    onLocationFilterChange: (LocationItem?) -> Unit,
    onHistoryClick: (SearchHistoryItem) -> Unit,
    onHistoryRemove: (SearchHistoryItem) -> Unit,
    onHistoryClear: () -> Unit,
    onSuggestionClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (query.isBlank() && searchHistory.isNotEmpty()) {
            SearchHistorySection(
                history = searchHistory,
                onHistoryClick = onHistoryClick,
                onHistoryRemove = onHistoryRemove,
                onHistoryClear = onHistoryClear
            )
        }

        // 搜索类型 Card
        SectionContainer {
            Column {
                FilterSectionTitle(stringResource(R.string.search_type), icon = Icons.Default.Tune)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    SearchChip(
                        text = stringResource(R.string.search_type_visual),
                        selected = searchType == SearchType.VISUAL_TEXT,
                        enabled = isClipAvailable,
                        leadingIcon = { Icon(Icons.Default.Image, null, modifier = Modifier.size(14.dp)) }
                    ) {
                        onSearchTypeChange(SearchType.VISUAL_TEXT)
                    }
                    SearchChip(
                        text = stringResource(R.string.search_type_ocr),
                        selected = searchType == SearchType.OCR_TEXT,
                        leadingIcon = { Icon(Icons.Default.Translate, null, modifier = Modifier.size(14.dp)) }
                    ) {
                        onSearchTypeChange(SearchType.OCR_TEXT)
                    }
                    SearchChip(
                        text = stringResource(R.string.search_type_filename),
                        selected = searchType == SearchType.FILE_NAME,
                        leadingIcon = { Icon(Icons.Default.Description, null, modifier = Modifier.size(14.dp)) }
                    ) {
                        onSearchTypeChange(SearchType.FILE_NAME)
                    }
                    SearchChip(
                        text = stringResource(R.string.search_type_auto),
                        selected = searchType == SearchType.AUTO,
                        leadingIcon = { Icon(Icons.Default.Search, null, modifier = Modifier.size(14.dp)) }
                    ) {
                        onSearchTypeChange(SearchType.AUTO)
                    }
                }
            }
        }

        if (suggestions.isNotEmpty()) {
            SectionContainer {
                Column {
                    FilterSectionTitle(stringResource(R.string.search_suggestions), icon = Icons.Default.Star)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        suggestions.forEach { tip ->
                            SearchChip(
                                text = tip.label,
                                selected = false,
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Search,
                                        contentDescription = null,
                                        modifier = Modifier.size(12.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                    )
                                }
                            ) {
                                onSuggestionClick(tip.value)
                            }
                        }
                    }
                }
            }
        }

        if (people.isNotEmpty()) {
            SectionContainer {
                Column {
                    FilterSectionTitle(stringResource(R.string.search_people), icon = Icons.Default.Person)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        SearchChip(
                            text = stringResource(R.string.search_unlimited),
                            selected = filters.personId.isNullOrBlank(),
                            leadingIcon = { Icon(Icons.Default.Person, null, modifier = Modifier.size(14.dp)) }
                        ) {
                            onPersonFilterChange(null)
                        }
                        people.forEach { person ->
                            val isSelected = filters.personId == person.id
                            val displayName = when (person.name) {
                                "未知" -> stringResource(R.string.search_unknown)
                                "未命名" -> stringResource(R.string.search_unnamed)
                                else -> person.name
                            }
                            SearchChip(
                                text = displayName,
                                selected = isSelected,
                                leadingIcon = {
                                    PersonChipAvatar(
                                        url = if (person.coverFileId > 0) portraitUrlProvider(person) else null,
                                        name = displayName
                                    )
                                }
                            ) {
                                onPersonFilterChange(person)
                            }
                        }
                    }
                }
            }
        }

        if (locations.isNotEmpty()) {
            SectionContainer {
                Column {
                    FilterSectionTitle(stringResource(R.string.search_locations), icon = Icons.Default.Place)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        SearchChip(
                            text = stringResource(R.string.search_unlimited),
                            selected = filters.location.isNullOrBlank(),
                            leadingIcon = { Icon(Icons.Default.Place, null, modifier = Modifier.size(14.dp)) }
                        ) {
                            onLocationFilterChange(null)
                        }
                        locations.forEach { location ->
                            val isSelected = filters.location == location.city
                            val displayCity = if (location.city == "未知") stringResource(R.string.search_unknown) else location.city
                            SearchChip(
                                text = displayCity,
                                selected = isSelected,
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Place,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = if (isSelected) {
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                                        }
                                    )
                                }
                            ) {
                                onLocationFilterChange(location)
                            }
                        }
                    }
                }
            }
        }

        if (isLoadingFilters) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.loading_filter_options),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun SearchHistorySection(
    history: List<SearchHistoryItem>,
    onHistoryClick: (SearchHistoryItem) -> Unit,
    onHistoryRemove: (SearchHistoryItem) -> Unit,
    onHistoryClear: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, bottom = 6.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(12.dp)
                        .background(
                            color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(2.dp)
                        )
                )
                Spacer(modifier = Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.search_history),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.3.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                text = stringResource(R.string.clear_search_history),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(onClick = onHistoryClear)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            history.forEach { item ->
                SearchHistoryChip(
                    item = item,
                    onClick = { onHistoryClick(item) },
                    onRemove = { onHistoryRemove(item) }
                )
            }
        }
    }
}

@Composable
private fun SearchHistoryChip(
    item: SearchHistoryItem,
    onClick: () -> Unit,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                shape = CircleShape
            )
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.15f),
                shape = CircleShape
            )
            .clickable(onClick = onClick)
            .padding(start = 10.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = null,
            modifier = Modifier.size(12.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = searchHistoryDisplayText(item),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        IconButton(
            onClick = onRemove,
            modifier = Modifier.size(24.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(R.string.remove_search_history),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.size(12.dp)
            )
        }
    }
}

@Composable
private fun searchHistoryDisplayText(item: SearchHistoryItem): String {
    val query = item.query.trim()
    if (query.isNotEmpty()) return query

    val parts = buildList {
        item.filters.personName?.trim()?.takeIf { it.isNotEmpty() }?.let {
            add("${stringResource(R.string.search_people)}: $it")
        }
        item.filters.location?.trim()?.takeIf { it.isNotEmpty() }?.let {
            add("${stringResource(R.string.search_locations)}: $it")
        }
    }
    return parts.joinToString(" / ").ifBlank { stringResource(R.string.search) }
}

@Composable
private fun FilterSectionTitle(
    title: String,
    icon: ImageVector? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Vertical accent bar
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(12.dp)
                .background(
                    color = MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(2.dp)
                )
        )
        Spacer(modifier = Modifier.width(6.dp))
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.3.sp
            ),
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun SearchChip(
    text: String,
    selected: Boolean,
    enabled: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null,
    onClick: () -> Unit
) {
    val backgroundColor by animateColorAsState(
        targetValue = when {
            !enabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
            selected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f)
            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        },
        label = "bgColor"
    )
    val contentColor by animateColorAsState(
        targetValue = when {
            !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
            selected -> MaterialTheme.colorScheme.onPrimaryContainer
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        label = "contentColor"
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            !enabled -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.15f)
            selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
            else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.15f)
        },
        label = "borderColor"
    )
    val scale by animateFloatAsState(
        targetValue = if (selected) 1.02f else 1f,
        label = "scale"
    )

    Row(
        modifier = Modifier
            .scale(scale)
            .background(backgroundColor, shape = CircleShape)
            .border(
                width = 1.dp,
                color = borderColor,
                shape = CircleShape
            )
            .clickable(
                enabled = enabled,
                onClick = onClick
            )
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (leadingIcon != null) {
            leadingIcon()
            Spacer(modifier = Modifier.width(4.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            ),
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SectionContainer(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f),
                shape = RoundedCornerShape(16.dp)
            )
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.12f),
                shape = RoundedCornerShape(16.dp)
            )
            .padding(start = 12.dp, end = 12.dp, bottom = 10.dp, top = 2.dp)
    ) {
        content()
    }
}

@Composable
private fun PersonChipAvatar(
    url: String?,
    name: String
) {
    if (!url.isNullOrEmpty()) {
        AsyncImage(
            model = coil.request.ImageRequest.Builder(LocalContext.current)
                .data(url)
                .size(64)
                .crossfade(true)
                .build(),
            contentDescription = name,
            modifier = Modifier
                .size(16.dp)
                .clip(CircleShape),
            contentScale = ContentScale.Crop
        )
    } else {
        Icon(
            imageVector = Icons.Default.Person,
            contentDescription = null,
            modifier = Modifier.size(12.dp)
        )
    }
}
