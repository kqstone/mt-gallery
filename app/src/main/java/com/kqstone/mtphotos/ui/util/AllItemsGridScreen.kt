package com.kqstone.mtphotos.ui.util

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A reusable full-screen grid page with a back-arrow top bar.
 *
 * Used by `AllDiscoveryItemsScreen` and `AllFolderItemsScreen` to avoid
 * duplicating identical Scaffold + TopAppBar + LazyVerticalGrid boilerplate.
 *
 * @param title     Text shown in the top app bar.
 * @param columns   Number of grid columns.
 * @param onBack    Callback for the back navigation icon.
 * @param content   Grid content lambda (same signature as [LazyVerticalGrid]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AllItemsGridScreen(
    title: String,
    columns: Int,
    onBack: () -> Unit,
    content: LazyGridScope.() -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            content = content
        )
    }
}
