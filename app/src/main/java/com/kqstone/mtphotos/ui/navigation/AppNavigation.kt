package com.kqstone.mtphotos.ui.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import com.kqstone.mtphotos.ui.oplog.OpLogScreen
import com.kqstone.mtphotos.ui.oplog.OpLogViewModel
import com.kqstone.mtphotos.ui.settings.BackupSetupScreen
import com.kqstone.mtphotos.ui.settings.BackupSettingsScreen
import com.kqstone.mtphotos.ui.settings.BackupSettingsViewModel
import com.kqstone.mtphotos.ui.settings.SettingsScreen
import com.kqstone.mtphotos.ui.settings.SettingsViewModel
import com.kqstone.mtphotos.ui.settings.AboutScreen
import com.kqstone.mtphotos.ui.settings.AboutViewModel
import com.kqstone.mtphotos.ui.util.AppPermissionGate
import com.kqstone.mtphotos.ui.viewer.ViewerScreen
import com.kqstone.mtphotos.ui.viewer.ViewerViewModel
import com.kqstone.mtphotos.worker.BackupScheduler

@Composable
fun AppNavigation() {
    val context = LocalContext.current
    val app = context.applicationContext as MTPhotosApp
    val container = app.container

    // 权限请求包裹整个 App — 在登录页之前就弹出
    AppPermissionGate(
        onContinueCloudOnly = {
            // 用户选择跳过权限，仍然可以进入（仅云端模式）
        }
    ) {
        AppContent(container = container)
    }
}

@Composable
private fun AppContent(container: com.kqstone.mtphotos.AppContainer) {
    val navController = rememberNavController()

    var startDestination by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val token = container.prefsManager.getTokenSync()
        if (token.isEmpty()) {
            startDestination = "settings"
        } else {
            // 已登录：检查是否完成了文件夹选择
            val folderSetupDone = container.prefsManager.isFolderSetupComplete()
            startDestination = if (folderSetupDone) "main" else "backup_setup"
        }
    }

    if (startDestination == null) return

    val context = LocalContext.current
    val mainActivity = context as? com.kqstone.mtphotos.MainActivity

    LaunchedEffect(navController, mainActivity) {
        mainActivity?.let { activity ->
            activity.intent?.let { intent ->
                if (intent.getBooleanExtra("open_backup_settings", false)) {
                    navController.navigate("backup_settings") {
                        launchSingleTop = true
                    }
                    intent.removeExtra("open_backup_settings")
                }
            }

            activity.intentFlow.collect { intent ->
                if (intent.getBooleanExtra("open_backup_settings", false)) {
                    navController.navigate("backup_settings") {
                        launchSingleTop = true
                    }
                    intent.removeExtra("open_backup_settings")
                }
            }
        }
    }

    val viewerContext = LocalContext.current
    val viewerViewModel: ViewerViewModel = viewModel(
        factory = ViewerViewModel.Factory(
            container.galleryRepository,
            container.originalDownloadManager,
            container.serverOpTaskRepository,
            viewerContext.applicationContext
        )
    )

    NavHost(navController = navController, startDestination = startDestination!!) {
        composable("settings") {
            val settingsViewModel: SettingsViewModel = viewModel(
                factory = SettingsViewModel.Factory(
                    container.authRepository,
                    credentialsEditable = true
                )
            )
            SettingsScreen(
                viewModel = settingsViewModel,
                onConnected = {
                    // 登录成功：检查是否需要文件夹选择
                    val folderSetupDone = container.prefsManager.isFolderSetupComplete()
                    val target = if (folderSetupDone) "main" else "backup_setup"
                    navController.navigate(target) {
                        popUpTo("settings") { inclusive = true }
                    }
                }
            )
        }

        composable("server_connection") {
            val settingsViewModel: SettingsViewModel = viewModel(
                factory = SettingsViewModel.Factory(
                    container.authRepository,
                    credentialsEditable = false
                )
            )
            SettingsScreen(
                viewModel = settingsViewModel,
                reconnectMode = true,
                onBack = { navController.popBackStack() },
                onConnected = {
                    if (container.prefsManager.getBackupEnabledSync()) {
                        com.kqstone.mtphotos.worker.BackupScheduler.scheduleAll(
                            context,
                            container.prefsManager.getBackupWifiOnlySync(),
                            container.prefsManager.getSyncIntervalSync().toLong()
                        )
                    }
                    navController.navigate("main") {
                        popUpTo("main") { inclusive = true }
                        launchSingleTop = true
                    }
                }
            )
        }

        composable("backup_setup") {
            val backupSettingsViewModel: BackupSettingsViewModel = viewModel(
                factory = BackupSettingsViewModel.Factory(
                    container.prefsManager,
                    container.syncRepository,
                    container.storageOptimizer,
                    container.localMediaScanner,
                    container.backupDestinationRepository
                )
            )
            BackupSetupScreen(
                viewModel = backupSettingsViewModel,
                onSetupComplete = {
                    navController.navigate("main") {
                        popUpTo("backup_setup") { inclusive = true }
                    }
                }
            )
        }

        composable("main") {
            val galleryViewModel: GalleryViewModel = viewModel(
                factory = GalleryViewModel.Factory(
                    container.galleryRepository,
                    container.syncRepository,
                    container.prefsManager,
                    container.serverOpTaskRepository,
                    viewerContext.applicationContext
                )
            )
            MainScreen(
                container = container,
                galleryViewModel = galleryViewModel,
                viewerViewModel = viewerViewModel,
                onNavigateToViewer = { photos, index ->
                    galleryViewModel.skipNextResumeRefresh()
                    viewerViewModel.setPhotos(photos, index)
                    navController.navigate("viewer")
                },
                onNavigateToSettings = {
                    navController.navigate("backup_settings")
                },
                onNavigateToAbout = {
                    navController.navigate("about")
                },
                onNavigateToOpLog = {
                    navController.navigate("op_log")
                }
            )
        }

        composable(
            route = "viewer",
            enterTransition = { fadeIn(animationSpec = tween(120)) },
            exitTransition = { fadeOut(animationSpec = tween(90)) },
            popEnterTransition = { fadeIn(animationSpec = tween(90)) },
            popExitTransition = { fadeOut(animationSpec = tween(120)) }
        ) {
            ViewerScreen(
                viewModel = viewerViewModel,
                onBack = { navController.popBackStack() }
            )
        }

        composable("backup_settings") {
            val backupSettingsViewModel: BackupSettingsViewModel = viewModel(
                factory = BackupSettingsViewModel.Factory(
                    container.prefsManager,
                    container.syncRepository,
                    container.storageOptimizer,
                    container.localMediaScanner,
                    container.backupDestinationRepository
                )
            )
            BackupSettingsScreen(
                viewModel = backupSettingsViewModel,
                onBack = { navController.popBackStack() },
                onServerConnection = {
                    navController.navigate("server_connection")
                }
            )
        }

        composable("about") {
            val aboutViewModel: AboutViewModel = viewModel(
                factory = AboutViewModel.Factory()
            )
            AboutScreen(
                viewModel = aboutViewModel,
                onBack = { navController.popBackStack() }
            )
        }

        composable("op_log") {
            val opLogContext = LocalContext.current
            val opLogViewModel: OpLogViewModel = viewModel(
                factory = OpLogViewModel.Factory(
                    container.serverOpTaskRepository,
                    triggerServerOp = { BackupScheduler.triggerServerOpWork(opLogContext) }
                )
            )
            OpLogScreen(
                viewModel = opLogViewModel,
                onBack = { navController.popBackStack() },
                getThumbUrl = { md5, _ -> container.galleryRepository.getThumbUrlByMd5(md5) }
            )
        }
    }
}
