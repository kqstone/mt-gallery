package com.kqstone.mtphotos.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.kqstone.mtphotos.MTPhotosApp
import com.kqstone.mtphotos.ui.gallery.GalleryScreen
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

    val settingsViewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModel.Factory(container.authRepository)
    )
    val galleryViewModel: GalleryViewModel = viewModel(
        factory = GalleryViewModel.Factory(container.galleryRepository)
    )
    val viewerViewModel: ViewerViewModel = viewModel(
        factory = ViewerViewModel.Factory(container.galleryRepository)
    )

    NavHost(navController = navController, startDestination = "settings") {
        composable("settings") {
            SettingsScreen(
                viewModel = settingsViewModel,
                onConnected = {
                    navController.navigate("gallery") {
                        popUpTo("settings") { inclusive = true }
                    }
                }
            )
        }

        composable("gallery") {
            GalleryScreen(
                viewModel = galleryViewModel,
                onPhotoClick = { photo ->
                    val allPhotos = galleryViewModel.getAllLoadedPhotos()
                    val index = allPhotos.indexOfFirst { it.id == photo.id }.coerceAtLeast(0)
                    viewerViewModel.setPhotos(allPhotos, index)
                    navController.navigate("viewer")
                },
                onSettingsClick = {
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
