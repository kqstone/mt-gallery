package com.kqstone.mtphotos.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.kqstone.mtphotos.AppContainer
import com.kqstone.mtphotos.data.repository.PhotoItem
import com.kqstone.mtphotos.ui.discovery.CategoryFileListScreen
import com.kqstone.mtphotos.ui.discovery.CategoryFileListViewModel
import com.kqstone.mtphotos.ui.discovery.DiscoveryScreen
import com.kqstone.mtphotos.ui.discovery.DiscoveryViewModel
import com.kqstone.mtphotos.ui.folder.FolderDetailScreen
import com.kqstone.mtphotos.ui.folder.FolderDetailViewModel
import com.kqstone.mtphotos.ui.folder.FolderScreen
import com.kqstone.mtphotos.ui.folder.FolderViewModel
import com.kqstone.mtphotos.ui.gallery.GalleryScreen
import com.kqstone.mtphotos.ui.gallery.GalleryViewModel
import com.kqstone.mtphotos.ui.viewer.ViewerViewModel

private data class TabItem(
    val route: String,
    val label: String,
    val icon: ImageVector
)

private val tabs = listOf(
    TabItem("photos", "照片", Icons.Default.PhotoLibrary),
    TabItem("folders", "文件夹", Icons.Default.Folder),
    TabItem("discovery", "发现", Icons.Default.Explore)
)

private val topLevelRoutes = setOf("photos", "folders", "discovery")

@Composable
fun MainScreen(
    container: AppContainer,
    galleryViewModel: GalleryViewModel,
    viewerViewModel: ViewerViewModel,
    onNavigateToViewer: (List<PhotoItem>, Int) -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val innerNavController = rememberNavController()
    val navBackStackEntry by innerNavController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val currentRoute = currentDestination?.route

    val showBottomBar = currentRoute in topLevelRoutes

    val folderViewModel: FolderViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = FolderViewModel.Factory(container.galleryRepository)
    )
    val folderDetailViewModel: FolderDetailViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = FolderDetailViewModel.Factory(container.galleryRepository)
    )
    val discoveryViewModel: DiscoveryViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = DiscoveryViewModel.Factory(container.galleryRepository)
    )
    val categoryFileListViewModel: CategoryFileListViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = CategoryFileListViewModel.Factory(container.galleryRepository)
    )

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    tabs.forEach { tab ->
                        val selected = currentDestination?.hierarchy?.any { it.route == tab.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                innerNavController.navigate(tab.route) {
                                    popUpTo(innerNavController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = innerNavController,
            startDestination = "photos",
            modifier = Modifier.padding(innerPadding)
        ) {
            composable("photos") {
                GalleryScreen(
                    viewModel = galleryViewModel,
                    onPhotoClick = { photo ->
                        val allPhotos = galleryViewModel.getAllLoadedPhotos()
                        val index = allPhotos.indexOfFirst { it.id == photo.id }.coerceAtLeast(0)
                        onNavigateToViewer(allPhotos, index)
                    },
                    onSettingsClick = onNavigateToSettings
                )
            }

            composable("folders") {
                FolderScreen(
                    viewModel = folderViewModel,
                    onFolderClick = { folderId ->
                        innerNavController.navigate("folder/$folderId")
                    },
                    onSettingsClick = onNavigateToSettings
                )
            }

            composable("discovery") {
                DiscoveryScreen(
                    viewModel = discoveryViewModel,
                    onPersonClick = { peopleId ->
                        innerNavController.navigate("people/$peopleId")
                    },
                    onSceneClick = { id, cid ->
                        innerNavController.navigate("scene/$id?cid=$cid")
                    },
                    onLocationClick = { city ->
                        innerNavController.navigate("location/$city")
                    },
                    onSettingsClick = onNavigateToSettings
                )
            }

            composable("folder/{folderId}") { backStackEntry ->
                val folderId = backStackEntry.arguments?.getString("folderId") ?: return@composable
                FolderDetailScreen(
                    folderId = folderId,
                    viewModel = folderDetailViewModel,
                    onFolderClick = { subFolderId ->
                        innerNavController.navigate("folder/$subFolderId")
                    },
                    onPhotoClick = { photo ->
                        val allPhotos = folderDetailViewModel.getAllLoadedPhotos()
                        val index = allPhotos.indexOfFirst { it.id == photo.id }.coerceAtLeast(0)
                        onNavigateToViewer(allPhotos, index)
                    },
                    onBack = { innerNavController.popBackStack() }
                )
            }

            composable("people/{peopleId}") { backStackEntry ->
                val peopleId = backStackEntry.arguments?.getString("peopleId") ?: return@composable
                CategoryFileListScreen(
                    viewModel = categoryFileListViewModel,
                    loadType = "people",
                    loadParam = peopleId,
                    title = "人物照片",
                    onPhotoClick = { photo ->
                        val allPhotos = categoryFileListViewModel.getAllLoadedPhotos()
                        val index = allPhotos.indexOfFirst { it.id == photo.id }.coerceAtLeast(0)
                        onNavigateToViewer(allPhotos, index)
                    },
                    onBack = { innerNavController.popBackStack() }
                )
            }

            composable("scene/{id}?cid={cid}") { backStackEntry ->
                val id = backStackEntry.arguments?.getString("id") ?: return@composable
                val cid = backStackEntry.arguments?.getString("cid")
                CategoryFileListScreen(
                    viewModel = categoryFileListViewModel,
                    loadType = "scene",
                    loadParam = id,
                    loadParam2 = cid,
                    title = "场景照片",
                    onPhotoClick = { photo ->
                        val allPhotos = categoryFileListViewModel.getAllLoadedPhotos()
                        val index = allPhotos.indexOfFirst { it.id == photo.id }.coerceAtLeast(0)
                        onNavigateToViewer(allPhotos, index)
                    },
                    onBack = { innerNavController.popBackStack() }
                )
            }

            composable("location/{city}") { backStackEntry ->
                val city = backStackEntry.arguments?.getString("city") ?: return@composable
                CategoryFileListScreen(
                    viewModel = categoryFileListViewModel,
                    loadType = "location",
                    loadParam = city,
                    title = city,
                    onPhotoClick = { photo ->
                        val allPhotos = categoryFileListViewModel.getAllLoadedPhotos()
                        val index = allPhotos.indexOfFirst { it.id == photo.id }.coerceAtLeast(0)
                        onNavigateToViewer(allPhotos, index)
                    },
                    onBack = { innerNavController.popBackStack() }
                )
            }
        }
    }
}
