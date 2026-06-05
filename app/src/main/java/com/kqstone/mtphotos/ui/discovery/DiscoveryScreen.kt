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
import androidx.compose.ui.unit.dp
import com.kqstone.mtphotos.data.repository.LocationItem
import com.kqstone.mtphotos.data.repository.PersonItem
import com.kqstone.mtphotos.data.repository.SceneItem
import com.kqstone.mtphotos.ui.search.SearchEntryTopBar
import com.kqstone.mtphotos.ui.util.CoverCard
import com.kqstone.mtphotos.ui.util.ThumbnailImage
import com.kqstone.mtphotos.ui.util.ToastMessageEffect
import com.kqstone.mtphotos.ui.util.rememberScrollAlpha
import com.kqstone.mtphotos.ui.util.hazeContentSource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoveryScreen(
    viewModel: DiscoveryViewModel,
    onPersonClick: (String) -> Unit,
    onSceneClick: (String, String?) -> Unit,
    onLocationClick: (String) -> Unit,
    onOpenSearch: () -> Unit,
    onSettingsClick: () -> Unit,
    onAboutClick: () -> Unit,
    onOpLogClick: () -> Unit = {}
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
                                    onItemClick = { onPersonClick(it.id) },
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
                                SceneSection(
                                    scenes = uiState.scenes,
                                    onItemClick = { onSceneClick(it.id, it.cid) },
                                    thumbUrlProvider = { md5, _ -> viewModel.getThumbUrlByMd5(md5) }
                                )
                            }
                        }

                        if (uiState.locations.isNotEmpty()) {
                            item {
                                LocationSection(
                                    locations = uiState.locations,
                                    onItemClick = { onLocationClick(it.city) },
                                    thumbUrlProvider = { md5 -> viewModel.getThumbUrlByMd5(md5) }
                                )
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
    portraitUrlProvider: (PersonItem) -> String?
) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(
            text = stringResource(R.string.section_people),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(
                items = people,
                key = { it.id }
            ) { person ->
                PersonCircleItem(
                    name = person.name,
                    count = person.count,
                    thumbUrl = portraitUrlProvider(person),
                    onClick = { onItemClick(person) },
                    key = person.coverMd5
                )
            }
        }
    }
}

@Composable
private fun SceneSection(
    scenes: List<SceneItem>,
    onItemClick: (SceneItem) -> Unit,
    thumbUrlProvider: (String, Double) -> String
) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(
            text = stringResource(R.string.section_scenes),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(
                items = scenes,
                key = { it.id }
            ) { scene ->
                CoverCard(
                    name = scene.name,
                    subtitle = stringResource(R.string.photo_count_short, scene.count),
                    thumbUrl = if (scene.coverMd5.isNotEmpty()) {
                        thumbUrlProvider(scene.coverMd5, scene.coverFileId)
                    } else null,
                    onClick = { onItemClick(scene) },
                    thumbKey = scene.coverMd5
                )
            }
        }
    }
}

@Composable
private fun LocationSection(
    locations: List<LocationItem>,
    onItemClick: (LocationItem) -> Unit,
    thumbUrlProvider: (String) -> String
) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(
            text = stringResource(R.string.section_locations),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(
                items = locations,
                key = { it.city }
            ) { location ->
                CoverCard(
                    name = location.city,
                    subtitle = stringResource(R.string.photo_count_short, location.count),
                    thumbUrl = location.coverMd5.takeIf { it.isNotBlank() }?.let(thumbUrlProvider),
                    onClick = { onItemClick(location) },
                    fallbackIcon = Icons.Default.Place,
                    fallbackGradient = LocationGradient,
                    thumbKey = location.coverMd5
                )
            }
        }
    }
}

@Composable
private fun PersonCircleItem(
    name: String,
    count: Int,
    thumbUrl: String?,
    onClick: () -> Unit,
    key: String? = null
) {
    Column(
        modifier = Modifier
            .width(90.dp)
            .bounceClick(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(76.dp)
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

// Gradient preset for location cards
private val LocationGradient = listOf(Color(0xFF8E9DFB), Color(0xFFEDACF7))
