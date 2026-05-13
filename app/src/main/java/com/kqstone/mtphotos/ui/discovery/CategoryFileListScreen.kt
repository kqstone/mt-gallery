package com.kqstone.mtphotos.ui.discovery

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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kqstone.mtphotos.data.repository.PhotoItem
import com.kqstone.mtphotos.ui.gallery.PhotoThumbnail

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryFileListScreen(
    viewModel: CategoryFileListViewModel,
    loadType: String,
    loadParam: String,
    loadParam2: String? = null,
    title: String,
    onPhotoClick: (PhotoItem) -> Unit,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(loadType, loadParam, loadParam2) {
        when (loadType) {
            "people" -> viewModel.loadPeopleFiles(loadParam)
            "scene" -> viewModel.loadSceneFiles(loadParam, loadParam2)
            "location" -> viewModel.loadLocationFiles(loadParam)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(title) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            }
        )

        when {
            uiState.isLoading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            uiState.error != null -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = uiState.error!!,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "点击重试",
                            modifier = Modifier.clickable {
                                when (loadType) {
                                    "people" -> viewModel.loadPeopleFiles(loadParam)
                                    "scene" -> viewModel.loadSceneFiles(loadParam, loadParam2)
                                    "location" -> viewModel.loadLocationFiles(loadParam)
                                }
                            },
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            uiState.photos.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "暂无照片",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(uiState.columnCount),
                    contentPadding = PaddingValues(2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(
                        items = uiState.photos,
                        key = { it.id }
                    ) { photo ->
                        PhotoThumbnail(
                            photo = photo,
                            thumbUrl = viewModel.getThumbUrl(photo.md5, photo.id),
                            onClick = { onPhotoClick(photo) },
                            onLongClick = {},
                            isSelected = false,
                            isSelectionMode = false
                        )
                    }
                }
            }
        }
    }
}
