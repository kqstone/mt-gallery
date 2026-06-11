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
import com.kqstone.mtphotos.R
import com.kqstone.mtphotos.ui.util.AllItemsGridScreen
import com.kqstone.mtphotos.ui.util.CoverCard
import com.kqstone.mtphotos.ui.util.GradientPresets
import com.kqstone.mtphotos.ui.util.PersonNameUtils

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

    AllItemsGridScreen(
        title = stringResource(titleRes),
        columns = columns,
        onBack = onBack
    ) {
        when (type) {
            "people" -> {
                items(uiState.people, key = { it.id }) { person ->
                    val displayName = PersonNameUtils.displayName(person.name, stringResource(R.string.person_unnamed))
                    PersonCircleItem(
                        name = displayName,
                        count = person.count,
                        thumbUrl = if (person.coverFileId > 0) viewModel.getPortraitUrl(person.id, person.coverFileId) else null,
                        onClick = { onPersonClick(person.id, person.name) },
                        key = person.coverMd5,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            "scenes" -> {
                items(uiState.scenes, key = { it.id }) { scene ->
                    CoverCard(
                        name = scene.name,
                        subtitle = stringResource(R.string.photo_count_short, scene.count),
                        thumbUrl = if (scene.coverMd5.isNotEmpty()) viewModel.getThumbUrlByMd5(scene.coverMd5) else null,
                        onClick = { onSceneClick(scene.id, scene.cid, scene.name) },
                        thumbKey = scene.coverMd5,
                        cardSize = Dp.Unspecified,
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                    )
                }
            }
            "locations" -> {
                items(uiState.locations, key = { it.city }) { location ->
                    CoverCard(
                        name = location.city,
                        subtitle = stringResource(R.string.photo_count_short, location.count),
                        thumbUrl = location.coverMd5.takeIf { it.isNotBlank() }?.let { viewModel.getThumbUrlByMd5(it) },
                        onClick = { onLocationClick(location.city) },
                        fallbackIcon = Icons.Default.Place,
                        fallbackGradient = GradientPresets.Location,
                        thumbKey = location.coverMd5,
                        cardSize = Dp.Unspecified,
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                    )
                }
            }
        }
    }
}
