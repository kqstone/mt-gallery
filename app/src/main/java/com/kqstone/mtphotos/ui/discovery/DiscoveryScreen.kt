package com.kqstone.mtphotos.ui.discovery

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import com.kqstone.mtphotos.ui.util.bounceClick
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kqstone.mtphotos.data.repository.LocationItem
import com.kqstone.mtphotos.data.repository.PersonItem
import com.kqstone.mtphotos.data.repository.SceneItem
import com.kqstone.mtphotos.ui.util.SimpleTitleHeader
import com.kqstone.mtphotos.ui.util.ThumbnailImage
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Map

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoveryScreen(
    viewModel: DiscoveryViewModel,
    onPersonClick: (String) -> Unit,
    onSceneClick: (String, String?) -> Unit,
    onLocationClick: (String) -> Unit,
    onMapClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        SimpleTitleHeader(
            title = "发现",
            onSettingsClick = onSettingsClick
        )

        when {
            uiState.isLoading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            uiState.error != null && uiState.people.isEmpty() && uiState.scenes.isEmpty() && uiState.locations.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = uiState.error!!,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "点击重试",
                            modifier = Modifier.clickable { viewModel.loadAll() },
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
                    LazyColumn(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        // 足迹地图入口卡片
                        item {
                            MapEntryCard(onClick = onMapClick)
                        }

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
                                        text = "暂无发现内容",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
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
            text = "人物",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(
                items = people,
                key = { it.id }
            ) { person ->
                DiscoveryCard(
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
            text = "场景",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(
                items = scenes,
                key = { it.id }
            ) { scene ->
                DiscoveryCard(
                    name = scene.name,
                    count = scene.count,
                    thumbUrl = if (scene.coverMd5.isNotEmpty()) {
                        thumbUrlProvider(scene.coverMd5, scene.coverFileId)
                    } else null,
                    onClick = { onItemClick(scene) },
                    key = scene.coverMd5
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
            text = "地点",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(
                items = locations,
                key = { it.city }
            ) { location ->
                LocationCard(
                    location = location,
                    onClick = { onItemClick(location) },
                    thumbUrl = location.coverMd5.takeIf { it.isNotBlank() }?.let(thumbUrlProvider)
                )
            }
        }
    }
}

@Composable
private fun DiscoveryCard(
    name: String,
    count: Int,
    thumbUrl: String?,
    onClick: () -> Unit,
    key: String? = null
) {
    Card(
        modifier = Modifier
            .width(140.dp)
            .bounceClick(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            ) {
                if (thumbUrl != null) {
                    ThumbnailImage(
                        url = thumbUrl,
                        contentDescription = name,
                        modifier = Modifier.fillMaxSize(),
                        key = key
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                brush = Brush.linearGradient(
                                    colors = listOf(
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                                        MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.7f)
                                    )
                                )
                            )
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PhotoLibrary,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }
            }
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "$count 张",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun LocationCard(
    location: LocationItem,
    onClick: () -> Unit,
    thumbUrl: String?
) {
    Card(
        modifier = Modifier
            .width(120.dp)
            .bounceClick(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .background(
                        brush = Brush.linearGradient(
                            colors = listOf(Color(0xFF8E9DFB), Color(0xFFEDACF7))
                        ),
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (thumbUrl != null) {
                    ThumbnailImage(
                        url = thumbUrl,
                        contentDescription = location.city,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape),
                        key = location.coverMd5
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Place,
                        contentDescription = "位置",
                        tint = Color.White,
                        modifier = Modifier.size(30.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = location.city,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${location.count} 张",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MapEntryCard(onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .bounceClick(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.Transparent
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color(0xFF6C8CF5),
                            Color(0xFF9B7CF5),
                            Color(0xFFB06CF5)
                        )
                    ),
                    shape = RoundedCornerShape(16.dp)
                )
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(
                            Color.White.copy(alpha = 0.2f),
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Map,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "足迹地图",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "在地图上查看你的照片足迹",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                }
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}
