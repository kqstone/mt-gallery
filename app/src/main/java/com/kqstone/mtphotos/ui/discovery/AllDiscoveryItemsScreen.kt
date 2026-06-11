package com.kqstone.mtphotos.ui.discovery

import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.graphicsLayer
import com.kqstone.mtphotos.R
import com.kqstone.mtphotos.ui.util.AllItemsGridScreen
import com.kqstone.mtphotos.ui.util.CoverCard
import com.kqstone.mtphotos.ui.util.GradientPresets
import com.kqstone.mtphotos.ui.util.PersonNameUtils

import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect

@Composable
fun AllDiscoveryItemsScreen(
    viewModel: DiscoveryViewModel,
    type: String,
    onPersonClick: (String, String) -> Unit,
    onSceneClick: (String, String?, String) -> Unit,
    onLocationClick: (String) -> Unit,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    val titleRes = when (type) {
        "people" -> R.string.section_people
        "scenes" -> R.string.section_scenes
        "locations" -> R.string.section_locations
        else -> R.string.app_name
    }
    val columns = if (type == "people") 4 else 3

    var peopleList by remember { mutableStateOf(uiState.people) }
    var scenesList by remember { mutableStateOf(uiState.scenes) }
    var locationsList by remember { mutableStateOf(uiState.locations) }

    LaunchedEffect(uiState.people) { peopleList = uiState.people }
    LaunchedEffect(uiState.scenes) { scenesList = uiState.scenes }
    LaunchedEffect(uiState.locations) { locationsList = uiState.locations }

    val gridState = rememberLazyGridState()
    val state = rememberReorderableLazyGridState(
        lazyGridState = gridState,
        onMove = { from, to ->
            when (type) {
                "people" -> peopleList = peopleList.toMutableList().apply { add(to.index, removeAt(from.index)) }
                "scenes" -> scenesList = scenesList.toMutableList().apply { add(to.index, removeAt(from.index)) }
                "locations" -> locationsList = locationsList.toMutableList().apply { add(to.index, removeAt(from.index)) }
            }
        }
    )

    AllItemsGridScreen(
        title = stringResource(titleRes),
        columns = columns,
        onBack = onBack,
        modifier = Modifier,
        gridState = gridState
    ) {
        when (type) {
            "people" -> {
                items(peopleList, key = { it.id }) { person ->
                    ReorderableItem(state, key = person.id) { isDragging ->
                        val scale = if (isDragging) 1.05f else 1f
                        val alpha = if (isDragging) 0.8f else 1f
                        val displayName = PersonNameUtils.displayName(person.name, stringResource(R.string.person_unnamed))
                        PersonCircleItem(
                            name = displayName,
                            count = person.count,
                            thumbUrl = if (person.coverFileId > 0) viewModel.getPortraitUrl(person.id, person.coverFileId) else null,
                            onClick = { onPersonClick(person.id, person.name) },
                            key = person.coverMd5,
                            modifier = Modifier
                                .longPressDraggableHandle(
                                    onDragStopped = {
                                        viewModel.saveCustomOrder("people", peopleList.map { it.id })
                                    }
                                )
                                .animateItem()
                                .fillMaxWidth()
                                .graphicsLayer {
                                    scaleX = scale
                                    scaleY = scale
                                    this.alpha = alpha
                                }
                        )
                    }
                }
            }
            "scenes" -> {
                items(scenesList, key = { it.id }) { scene ->
                    ReorderableItem(state, key = scene.id) { isDragging ->
                        val scale = if (isDragging) 1.05f else 1f
                        val alpha = if (isDragging) 0.8f else 1f
                        CoverCard(
                            name = scene.name,
                            subtitle = stringResource(R.string.photo_count_short, scene.count),
                            thumbUrl = if (scene.coverMd5.isNotEmpty()) viewModel.getThumbUrlByMd5(scene.coverMd5) else null,
                            onClick = { onSceneClick(scene.id, scene.cid, scene.name) },
                            thumbKey = scene.coverMd5,
                            cardSize = Dp.Unspecified,
                            modifier = Modifier
                                .longPressDraggableHandle(
                                    onDragStopped = {
                                        viewModel.saveCustomOrder("scene", scenesList.map { it.id })
                                    }
                                )
                                .animateItem()
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .graphicsLayer {
                                    scaleX = scale
                                    scaleY = scale
                                    this.alpha = alpha
                                }
                        )
                    }
                }
            }
            "locations" -> {
                items(locationsList, key = { it.city }) { location ->
                    ReorderableItem(state, key = location.city) { isDragging ->
                        val scale = if (isDragging) 1.05f else 1f
                        val alpha = if (isDragging) 0.8f else 1f
                        CoverCard(
                            name = location.city,
                            subtitle = stringResource(R.string.photo_count_short, location.count),
                            thumbUrl = location.coverMd5.takeIf { it.isNotBlank() }?.let { viewModel.getThumbUrlByMd5(it) },
                            onClick = { onLocationClick(location.city) },
                            fallbackIcon = Icons.Default.Place,
                            fallbackGradient = GradientPresets.Location,
                            thumbKey = location.coverMd5,
                            cardSize = Dp.Unspecified,
                            modifier = Modifier
                                .longPressDraggableHandle(
                                    onDragStopped = {
                                        viewModel.saveCustomOrder("location", locationsList.map { it.city })
                                    }
                                )
                                .animateItem()
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .graphicsLayer {
                                    scaleX = scale
                                    scaleY = scale
                                    this.alpha = alpha
                                }
                        )
                    }
                }
            }
        }
    }
}
