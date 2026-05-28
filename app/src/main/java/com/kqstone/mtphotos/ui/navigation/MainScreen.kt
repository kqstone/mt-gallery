package com.kqstone.mtphotos.ui.navigation

import android.net.Uri
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.kqstone.mtphotos.AppContainer
import com.kqstone.mtphotos.data.model.UnifiedPhotoItem
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
import com.kqstone.mtphotos.ui.map.MapScreen
import com.kqstone.mtphotos.ui.map.MapViewModel
import com.kqstone.mtphotos.ui.search.CloudSearchOverlay
import com.kqstone.mtphotos.ui.search.CloudSearchViewModel
import com.kqstone.mtphotos.ui.viewer.ViewerViewModel
import com.kqstone.mtphotos.ui.util.frostedGlassEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

private data class TabItem(
    val route: String,
    val label: String,
    val icon: ImageVector
)

private val tabs = listOf(
    TabItem("photos", "照片", Icons.Default.PhotoLibrary),
    TabItem("folders", "图集", Icons.Default.Folder),
    TabItem("map", "足迹", Icons.Default.Map),
    TabItem("discovery", "发现", Icons.Default.Explore)
)

private val topLevelRoutes = setOf("photos", "folders", "map", "discovery")

private val BottomNavigationBarHeight = 56.dp

@Composable
fun MainScreen(
    container: AppContainer,
    galleryViewModel: GalleryViewModel,
    viewerViewModel: ViewerViewModel,
    onNavigateToViewer: (List<UnifiedPhotoItem>, Int) -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToAbout: () -> Unit
) {
    val innerNavController = rememberNavController()
    val navBackStackEntry by innerNavController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val currentRoute = currentDestination?.route

    val showBottomBar = currentRoute in topLevelRoutes
    val hazeState = rememberHazeState()

    val folderViewModel: FolderViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = FolderViewModel.Factory(container.galleryRepository)
    )
    val folderDetailViewModel: FolderDetailViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = FolderDetailViewModel.Factory(container.galleryRepository, container.syncRepository)
    )
    val discoveryViewModel: DiscoveryViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = DiscoveryViewModel.Factory(container.galleryRepository)
    )
    val categoryFileListViewModel: CategoryFileListViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = CategoryFileListViewModel.Factory(container.galleryRepository, container.syncRepository)
    )
    val cloudSearchViewModel: CloudSearchViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = CloudSearchViewModel.Factory(container.galleryRepository, container.syncRepository)
    )
    var isSearchOverlayVisible by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                if (showBottomBar) {
                    NavigationBar(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(BottomNavigationBarHeight)
                            .frostedGlassEffect(state = hazeState),
                        containerColor = Color.Transparent,
                        tonalElevation = 0.dp
                    ) {
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
                                icon = {
                                    Icon(
                                        imageVector = tab.icon,
                                        contentDescription = tab.label,
                                        modifier = Modifier.size(20.dp)
                                    )
                                },
                                label = {
                                    Text(
                                        text = tab.label,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    indicatorColor = Color.Transparent,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            )
                        }
                    }
                }
            }
        ) { _ -> // innerPadding intentionally ignored: content extends behind the translucent navbar
            NavHost(
                navController = innerNavController,
                startDestination = "photos",
                modifier = Modifier
                    .padding(bottom = 0.dp)
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .graphicsLayer { alpha = 0.99f }
                    .hazeSource(state = hazeState),
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None },
                popEnterTransition = { EnterTransition.None },
                popExitTransition = { ExitTransition.None }
            ) {
            composable("photos") {
                GalleryScreen(
                    viewModel = galleryViewModel,
                    onPhotoClick = { photo, allPhotos ->
                        val index = allPhotos.indexOfFirst { it.id == photo.id }.coerceAtLeast(0)
                        onNavigateToViewer(allPhotos, index)
                    },
                    onOpenSearch = { isSearchOverlayVisible = true },
                    onSettingsClick = onNavigateToSettings,
                    onAboutClick = onNavigateToAbout
                )
            }

            composable("folders") {
                FolderScreen(
                    viewModel = folderViewModel,
                    onAlbumClick = { albumId, title ->
                        innerNavController.navigate("album/$albumId/${Uri.encode(title)}")
                    },
                    onFolderClick = { folderId ->
                        innerNavController.navigate("folder/$folderId")
                    },
                    onCategoryClick = { type ->
                        innerNavController.navigate("collectionCategory/$type")
                    },
                    onOpenSearch = { isSearchOverlayVisible = true },
                    onSettingsClick = onNavigateToSettings,
                    onAboutClick = onNavigateToAbout
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
                        innerNavController.navigate("location/${Uri.encode(city)}")
                    },
                    onOpenSearch = { isSearchOverlayVisible = true },
                    onSettingsClick = onNavigateToSettings,
                    onAboutClick = onNavigateToAbout
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

            composable("album/{albumId}/{title}") { backStackEntry ->
                val albumId = backStackEntry.arguments?.getString("albumId") ?: return@composable
                val title = backStackEntry.arguments?.getString("title")?.let(Uri::decode) ?: "相册"
                CategoryFileListScreen(
                    viewModel = categoryFileListViewModel,
                    loadType = "album",
                    loadParam = albumId,
                    title = title,
                    onPhotoClick = { photo ->
                        val allPhotos = categoryFileListViewModel.getAllLoadedPhotos()
                        val index = allPhotos.indexOfFirst { it.id == photo.id }.coerceAtLeast(0)
                        onNavigateToViewer(allPhotos, index)
                    },
                    onBack = { innerNavController.popBackStack() }
                )
            }

            composable("collectionCategory/{type}") { backStackEntry ->
                val type = backStackEntry.arguments?.getString("type") ?: return@composable
                CategoryFileListScreen(
                    viewModel = categoryFileListViewModel,
                    loadType = type,
                    loadParam = "",
                    title = collectionCategoryTitle(type),
                    onPhotoClick = { photo ->
                        val allPhotos = categoryFileListViewModel.getAllLoadedPhotos()
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
                val city = backStackEntry.arguments?.getString("city")?.let(Uri::decode) ?: return@composable
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

            composable("map") {
                val mapViewModel: MapViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
                    factory = MapViewModel.Factory(
                        galleryRepository = container.galleryRepository,
                        syncRepository = container.syncRepository
                    )
                )
                MapScreen(
                    viewModel = mapViewModel,
                    isActive = currentRoute == "map",
                    onSettingsClick = onNavigateToSettings,
                    onAboutClick = onNavigateToAbout,
                    onPhotoClick = { photo, list ->
                        val index = list.indexOfFirst { it.id == photo.id }.coerceAtLeast(0)
                        onNavigateToViewer(list, index)
                    }
                )
            }
            }
        }

        if (isSearchOverlayVisible) {
            CloudSearchOverlay(
                viewModel = cloudSearchViewModel,
                onPhotoClick = { photo, allPhotos ->
                    val index = allPhotos.indexOfFirst { it.id == photo.id }.coerceAtLeast(0)
                    onNavigateToViewer(allPhotos, index)
                },
                onClose = { isSearchOverlayVisible = false }
            )
        }
    }
}

private fun collectionCategoryTitle(type: String): String {
    return when (type) {
        "favorites" -> "收藏"
        "recent" -> "最近添加"
        "videos" -> "视频"
        "trash" -> "回收站"
        else -> "类别"
    }
}
