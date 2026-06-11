package com.kqstone.mtphotos.ui.discovery

import com.kqstone.mtphotos.ui.util.bounceClick
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.kqstone.mtphotos.R
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kqstone.mtphotos.data.repository.LocationItem
import com.kqstone.mtphotos.data.repository.PersonItem
import com.kqstone.mtphotos.data.repository.SceneItem
import com.kqstone.mtphotos.ui.search.SearchEntryTopBar
import com.kqstone.mtphotos.ui.util.CoverCard
import com.kqstone.mtphotos.ui.util.GradientPresets
import com.kqstone.mtphotos.ui.util.PersonNameUtils
import com.kqstone.mtphotos.ui.util.SectionHeader
import com.kqstone.mtphotos.ui.util.ThumbnailImage
import com.kqstone.mtphotos.ui.util.ToastMessageEffect
import com.kqstone.mtphotos.ui.util.rememberScrollAlpha
import com.kqstone.mtphotos.ui.util.hazeContentSource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoveryScreen(
    viewModel: DiscoveryViewModel,
    onPersonClick: (String, String) -> Unit,
    onSceneClick: (String, String?, String) -> Unit,
    onLocationClick: (String) -> Unit,
    onOpenSearch: () -> Unit,
    onSettingsClick: () -> Unit,
    onAboutClick: () -> Unit,
    onOpLogClick: () -> Unit = {},
    onMoreClick: (String) -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val lazyListState = rememberLazyListState()

    ToastMessageEffect(
        message = uiState.toastMessage,
        onConsumed = viewModel::clearToastMessage
    )

    val scrollState = rememberScrollAlpha(
        firstVisibleItemIndex = { lazyListState.firstVisibleItemIndex },
        firstVisibleItemScrollOffset = { lazyListState.firstVisibleItemScrollOffset }
    )

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            uiState.isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = scrollState.topBarHeight, bottom = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            uiState.error != null && uiState.people.isEmpty() && uiState.scenes.isEmpty() && uiState.locations.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = scrollState.topBarHeight, bottom = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = uiState.error!!.asString(),
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.click_to_retry),
                            modifier = Modifier.clickable { viewModel.loadAll() },
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            else -> {
                val pullRefreshState = rememberPullToRefreshState()
                PullToRefreshBox(
                    isRefreshing = uiState.isRefreshing,
                    onRefresh = { viewModel.refresh() },
                    modifier = Modifier.fillMaxSize().hazeContentSource(),
                    state = pullRefreshState,
                    indicator = {
                        PullToRefreshDefaults.Indicator(
                            modifier = Modifier.align(Alignment.TopCenter).padding(top = scrollState.topBarHeight),
                            isRefreshing = uiState.isRefreshing,
                            state = pullRefreshState
                        )
                    }
                ) {
                    LazyColumn(
                        state = lazyListState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = scrollState.topBarHeight, bottom = 80.dp)
                    ) {
                        if (uiState.people.isNotEmpty()) {
                            item {
                                PeopleSection(
                                    people = uiState.people,
                                    onItemClick = { onPersonClick(it.id, it.name) },
                                    onMoreClick = { onMoreClick("people") },
                                    portraitUrlProvider = { person ->
                                        if (person.coverFileId > 0) {
                                            viewModel.getPortraitUrl(person.id, person.coverFileId)
                                        } else {
                                            null
                                        }
                                    }
                                )
                            }
                        }

                        if (uiState.scenes.isNotEmpty()) {
                            item {
                                CoverCardRowSection(
                                    title = stringResource(R.string.section_scenes),
                                    items = uiState.scenes,
                                    onMoreClick = { onMoreClick("scenes") },
                                    itemKey = { it.id }
                                ) { scene, cardSize ->
                                    CoverCard(
                                        name = scene.name,
                                        subtitle = stringResource(R.string.photo_count_short, scene.count),
                                        thumbUrl = if (scene.coverMd5.isNotEmpty()) viewModel.getThumbUrlByMd5(scene.coverMd5) else null,
                                        onClick = { onSceneClick(scene.id, scene.cid, scene.name) },
                                        thumbKey = scene.coverMd5,
                                        cardSize = cardSize
                                    )
                                }
                            }
                        }

                        if (uiState.locations.isNotEmpty()) {
                            item {
                                CoverCardRowSection(
                                    title = stringResource(R.string.section_locations),
                                    items = uiState.locations,
                                    onMoreClick = { onMoreClick("locations") },
                                    itemKey = { it.city }
                                ) { location, cardSize ->
                                    CoverCard(
                                        name = location.city,
                                        subtitle = stringResource(R.string.photo_count_short, location.count),
                                        thumbUrl = location.coverMd5.takeIf { it.isNotBlank() }?.let { viewModel.getThumbUrlByMd5(it) },
                                        onClick = { onLocationClick(location.city) },
                                        fallbackIcon = Icons.Default.Place,
                                        fallbackGradient = GradientPresets.Location,
                                        thumbKey = location.coverMd5,
                                        cardSize = cardSize
                                    )
                                }
                            }
                        }

                        if (uiState.people.isEmpty() && uiState.scenes.isEmpty() && uiState.locations.isEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(32.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = stringResource(R.string.no_discovery_content),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        SearchEntryTopBar(
            onSearchClick = onOpenSearch,
            onSettingsClick = onSettingsClick,
            onAboutClick = onAboutClick,
            onOpLogClick = onOpLogClick,
            scrollAlpha = scrollState.scrollAlpha,
            modifier = Modifier.align(Alignment.TopCenter)
        )
    }
}

@Composable
private fun PeopleSection(
    people: List<PersonItem>,
    onItemClick: (PersonItem) -> Unit,
    onMoreClick: () -> Unit,
    portraitUrlProvider: (PersonItem) -> String?
) {
    val unnamed = stringResource(R.string.person_unnamed)
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val itemWidth = (screenWidth - 32.dp - 36.dp) / 4

    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        SectionHeader(
            title = stringResource(R.string.section_people),
            showMore = people.size > 4,
            onMoreClick = onMoreClick
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(
                items = people.take(4),
                key = { it.id }
            ) { person ->
                val displayName = PersonNameUtils.displayName(person.name, unnamed)
                PersonCircleItem(
                    name = displayName,
                    count = person.count,
                    thumbUrl = portraitUrlProvider(person),
                    onClick = { onItemClick(person) },
                    key = person.coverMd5,
                    modifier = Modifier.width(itemWidth)
                )
            }
        }
    }
}

/**
 * Generic section that shows a [SectionHeader] + horizontal [LazyRow] of [CoverCard]s.
 * Used for Scenes and Locations (and extensible to any category backed by CoverCard).
 */
@Composable
private fun <T> CoverCardRowSection(
    title: String,
    items: List<T>,
    maxVisible: Int = 3,
    onMoreClick: () -> Unit,
    itemKey: (T) -> Any,
    itemContent: @Composable (item: T, cardSize: Dp) -> Unit
) {
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val cardSize = (screenWidth - 32.dp - 24.dp) / maxVisible

    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        SectionHeader(
            title = title,
            showMore = items.size > maxVisible,
            onMoreClick = onMoreClick
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(
                items = items.take(maxVisible),
                key = itemKey
            ) { item ->
                itemContent(item, cardSize)
            }
        }
    }
}

@Composable
fun PersonCircleItem(
    name: String,
    count: Int,
    thumbUrl: String?,
    onClick: () -> Unit,
    key: String? = null,
    modifier: Modifier = Modifier.width(90.dp)
) {
    Column(
        modifier = modifier
            .bounceClick(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                            MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f)
                        )
                    ),
                    shape = CircleShape
                )
                .border(
                    BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                    shape = CircleShape
                )
        ) {
            if (thumbUrl != null) {
                ThumbnailImage(
                    url = thumbUrl,
                    contentDescription = name,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape),
                    key = key
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PhotoLibrary,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        Text(
            text = stringResource(R.string.photo_count_short, count),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }
}

