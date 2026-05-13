package com.kqstone.mtphotos.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
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
                    val imageUrl = viewerViewModel.getFullImageUrl(photo.id, photo.md5)
                    navController.navigate(
                        "viewer/${photo.id}/${photo.md5}/${photo.fileName}"
                    )
                },
                onSettingsClick = {
                    navController.navigate("settings")
                }
            )
        }

        composable(
            route = "viewer/{fileId}/{md5}/{fileName}",
            arguments = listOf(
                navArgument("fileId") { type = NavType.StringType },
                navArgument("md5") { type = NavType.StringType },
                navArgument("fileName") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val fileId = backStackEntry.arguments?.getString("fileId")?.toDoubleOrNull() ?: 0.0
            val md5 = backStackEntry.arguments?.getString("md5") ?: ""
            val fileName = backStackEntry.arguments?.getString("fileName") ?: ""

            ViewerScreen(
                imageUrl = viewerViewModel.getFullImageUrl(fileId, md5),
                fileName = fileName,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
