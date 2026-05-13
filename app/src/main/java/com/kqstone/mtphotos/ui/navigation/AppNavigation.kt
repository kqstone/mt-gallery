package com.kqstone.mtphotos.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.kqstone.mtphotos.MTPhotosApp
import com.kqstone.mtphotos.ui.gallery.GalleryViewModel
import com.kqstone.mtphotos.ui.settings.SettingsScreen
import com.kqstone.mtphotos.ui.settings.SettingsViewModel
import com.kqstone.mtphotos.ui.viewer.ViewerScreen
import com.kqstone.mtphotos.ui.viewer.ViewerViewModel

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val context = LocalContext.current
    val app = context.applicationContext as MTPhotosApp
    val container = app.container

    var startDestination by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val token = container.prefsManager.getTokenSync()
        startDestination = if (token.isNotEmpty()) "main" else "settings"
    }

    if (startDestination == null) return

    val settingsViewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModel.Factory(container.authRepository)
    )
    val galleryViewModel: GalleryViewModel = viewModel(
        factory = GalleryViewModel.Factory(container.galleryRepository)
    )
    val viewerViewModel: ViewerViewModel = viewModel(
        factory = ViewerViewModel.Factory(container.galleryRepository)
    )

    NavHost(navController = navController, startDestination = startDestination!!) {
        composable("settings") {
            SettingsScreen(
                viewModel = settingsViewModel,
                onConnected = {
                    navController.navigate("main") {
                        popUpTo("settings") { inclusive = true }
                    }
                }
            )
        }

        composable("main") {
            MainScreen(
                container = container,
                galleryViewModel = galleryViewModel,
                viewerViewModel = viewerViewModel,
                onNavigateToViewer = { photos, index ->
                    viewerViewModel.setPhotos(photos, index)
                    navController.navigate("viewer")
                },
                onNavigateToSettings = {
                    settingsViewModel.resetLoginState()
                    navController.navigate("settings")
                }
            )
        }

        composable("viewer") {
            ViewerScreen(
                viewModel = viewerViewModel,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
