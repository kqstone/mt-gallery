package com.kqstone.mtphotos.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kqstone.mtphotos.data.repository.BackupDestinationNode
import com.kqstone.mtphotos.ui.util.PermissionHelper

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupSetupScreen(
    viewModel: BackupSettingsViewModel,
    onSetupComplete: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    var step by remember { mutableStateOf(0) }
    var showDestinationDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.ensureInitialBackupDefaults()
        viewModel.loadStats()
        viewModel.loadFolders()
    }

    BackHandler {
        if (step > 0) {
            step--
        } else {
            viewModel.completeInitialSetup(onSetupComplete)
        }
    }

    val backupDestinationSubtitle = backupDestinationSummary(uiState)
    val defaultBackupDestinationSubtitle = defaultBackupDestinationSummary(uiState)
    val folderSubtitle = folderSelectionSummary(uiState)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (step == 0) "选择备份目标" else "选择备份文件夹") },
                navigationIcon = {
                    if (step > 0) {
                        IconButton(onClick = { step-- }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (step == 0) {
                item {
                    SettingActionRow(
                        title = "备份到",
                        subtitle = backupDestinationSubtitle,
                        icon = Icons.Default.Cloud,
                        onClick = {
                            showDestinationDialog = true
                            viewModel.loadBackupDestinationRoot()
                        }
                    )
                }
                item {
                    Button(
                        onClick = { step = 1 },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("下一步")
                    }
                }
            } else {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Folder, contentDescription = null)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("备份文件夹", style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = folderSubtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                item {
                    if (uiState.folders.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    } else {
                        FolderSelectionList(
                            folders = uiState.folders,
                            onToggle = { viewModel.toggleFolderSelection(it) },
                            modifier = Modifier.height(420.dp)
                        )
                    }
                }
                item {
                    Button(
                        onClick = { viewModel.completeInitialSetup(onSetupComplete) },
                        enabled = uiState.selectedFolderCount > 0,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("完成")
                    }
                }
            }
        }
    }

    if (showDestinationDialog) {
        BackupDestinationDialog(
            isDefaultSelected = uiState.isDefaultBackupDestination,
            defaultDestinationSummary = defaultBackupDestinationSubtitle,
            selectedDestinationId = uiState.backupDestinationId,
            selectedDestinationSummary = backupDestinationSubtitle,
            nodes = uiState.backupDestinationNodes,
            breadcrumbs = uiState.backupDestinationBreadcrumbs,
            isLoading = uiState.isLoadingBackupDestinations,
            isCreatingFolder = uiState.isCreatingFolder,
            error = uiState.backupDestinationError,
            onDismiss = { showDestinationDialog = false },
            onReload = { viewModel.loadBackupDestinationRoot() },
            onNavigateUp = { viewModel.navigateUpBackupDestination() },
            onOpen = { node -> viewModel.openBackupDestination(node) },
            onCreateFolder = { name -> viewModel.createFolder(name) },
            onSelectDefault = {
                viewModel.selectDefaultBackupDestination()
                showDestinationDialog = false
            },
            onSelectCurrent = {
                viewModel.selectCurrentBackupDestination()
                showDestinationDialog = false
            },
            onSelect = { node ->
                viewModel.selectBackupDestination(node)
                showDestinationDialog = false
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupSettingsScreen(
    viewModel: BackupSettingsViewModel,
    onBack: () -> Unit,
    setupMode: Boolean = false,
    onSetupComplete: () -> Unit = {},
    onServerConnection: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    var showCleanupConfirm by remember { mutableStateOf(false) }
    var showFolderDialog by remember { mutableStateOf(false) }
    var showDestinationDialog by remember { mutableStateOf(false) }
    var showCacheLimitDialog by remember { mutableStateOf(false) }
    var showClearThumbnailCacheConfirm by remember { mutableStateOf(false) }
    var showClearMediaCacheConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (setupMode) {
            viewModel.ensureInitialBackupDefaults()
            viewModel.loadFolders()
        } else {
            viewModel.loadFolderSelectionSummary()
        }
        viewModel.loadStats()
        viewModel.loadCacheStats()
    }

    val folderSubtitle = when {
        uiState.selectedFolderCount <= 0 -> "未选择任何文件夹"
        uiState.historicalSelectedFolderCount > 0 ->
            "已选择 ${uiState.selectedFolderCount} 个文件夹，其中 ${uiState.historicalSelectedFolderCount} 个已无本地文件"
        else -> "已选择 ${uiState.selectedFolderCount} 个文件夹"
    }
    val backupDestinationSubtitle = backupDestinationSummary(uiState)
    val defaultBackupDestinationSubtitle = defaultBackupDestinationSummary(uiState)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (setupMode) "设置备份" else "备份与存储") },
                navigationIcon = {
                    if (!setupMode) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                },
                actions = {
                    if (setupMode) {
                        TextButton(
                            onClick = { viewModel.completeInitialSetup(onSetupComplete) },
                            enabled = uiState.selectedFolderCount > 0 && !uiState.isSyncing
                        ) {
                            Text("完成")
                        }
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (uiState.isSyncing) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .height(24.dp)
                                    .width(24.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    "正在扫描设备文件...",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "首次扫描可能需要一点时间，请稍候。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            }
                        }
                    }
                }
            }

            item {
                BackupStatusCard(
                    syncedCount = uiState.syncedCount,
                    backedUpCount = uiState.backedUpCount,
                    pendingCount = uiState.pendingCount,
                    isBackingUp = uiState.isBackingUp
                )
            }

            if (!setupMode) {
                item { SectionTitle("服务器") }

                item {
                    SettingActionRow(
                        title = "服务器连接",
                        subtitle = "维护主/备用访问地址并重新登录",
                        icon = Icons.Default.Link,
                        onClick = onServerConnection
                    )
                }
            }

            item { SectionTitle("自动备份") }

            item {
                SettingSwitchRow(
                    title = "开启自动备份",
                    subtitle = "自动将设备照片和视频备份到服务器",
                    icon = Icons.Default.Backup,
                    checked = uiState.backupEnabled,
                    onCheckedChange = { viewModel.setBackupEnabled(it) }
                )
            }

            item {
                SettingSwitchRow(
                    title = "仅在 Wi-Fi 下备份",
                    subtitle = "关闭后也可使用移动数据备份",
                    icon = Icons.Default.Cloud,
                    checked = uiState.wifiOnly,
                    onCheckedChange = { viewModel.setWifiOnly(it) },
                    enabled = uiState.backupEnabled
                )
            }

            item {
                SyncIntervalSetting(
                    currentInterval = uiState.syncInterval,
                    onIntervalChange = { viewModel.setSyncInterval(it) },
                    enabled = uiState.backupEnabled
                )
            }

            item {
                SettingActionRow(
                    title = "立即备份",
                    subtitle = if (uiState.pendingCount > 0) {
                        "${uiState.pendingCount} 个文件待备份"
                    } else {
                        "所有文件已备份"
                    },
                    icon = Icons.Default.Backup,
                    onClick = { viewModel.triggerBackupNow() },
                    enabled = uiState.backupEnabled && uiState.pendingCount > 0
                )
            }

            item {
                SettingActionRow(
                    title = "扫描并补传未备份文件",
                    subtitle = uiState.backupRepairMessage
                        ?: "全量比对本地与云端，重新备份缺失文件",
                    icon = Icons.Default.Cloud,
                    onClick = { viewModel.repairMissingBackups() },
                    enabled = uiState.backupEnabled && !uiState.isRepairingBackups
                )
                if (uiState.isRepairingBackups) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }

            item { SectionTitle("备份目标") }

            item {
                SettingActionRow(
                    title = "备份到",
                    subtitle = backupDestinationSubtitle,
                    icon = Icons.Default.Cloud,
                    onClick = {
                        showDestinationDialog = true
                        viewModel.loadBackupDestinationRoot()
                    }
                )
            }

            item { SectionTitle("备份文件夹") }

            item {
                SettingActionRow(
                    title = "选择备份文件夹",
                    subtitle = folderSubtitle,
                    icon = Icons.Default.Folder,
                    onClick = {
                        showFolderDialog = true
                        viewModel.loadFolders()
                    },
                    enabled = uiState.backupEnabled
                )
            }

            item { SectionTitle("存储管理") }

            item {
                StorageOptimizationCard(
                    optimizableCount = uiState.optimizableCount,
                    optimizableSize = uiState.optimizableSizeFormatted,
                    onOptimize = { showCleanupConfirm = true },
                    isOptimizing = uiState.isOptimizing
                )
            }

            item { SectionTitle("缓存管理") }

            item {
                SettingActionRow(
                    title = "最大缩略图缓存容量",
                    subtitle = formatCacheLimitLabel(uiState.coilDiskCacheMb),
                    icon = Icons.Default.Folder,
                    onClick = { showCacheLimitDialog = true }
                )
            }

            item {
                SettingActionRow(
                    title = "清理缩略图缓存",
                    subtitle = "当前已用: ${uiState.thumbnailCacheSizeFormatted}",
                    icon = Icons.Default.CleaningServices,
                    onClick = { showClearThumbnailCacheConfirm = true }
                )
            }

            item {
                SettingActionRow(
                    title = "清理照片与视频缓存",
                    subtitle = "当前已用: ${uiState.mediaCacheSizeFormatted}",
                    icon = Icons.Default.CleaningServices,
                    onClick = { showClearMediaCacheConfirm = true }
                )
            }

        }
    }

    if (showCleanupConfirm) {
        AlertDialog(
            onDismissRequest = { showCleanupConfirm = false },
            icon = {
                Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFF9800))
            },
            title = { Text("释放存储空间") },
            text = {
                Column {
                    Text("即将删除 ${uiState.optimizableCount} 个已备份的本地原图或视频文件。")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "可释放约 ${uiState.optimizableSizeFormatted}",
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "删除后将无法恢复本地原文件，但云端备份和缩略图缓存不受影响。",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "已选择的备份文件夹配置会被保留，即使该文件夹中的本地文件已被清理。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCleanupConfirm = false
                        if (PermissionHelper.requestManageStoragePermission(context)) {
                            viewModel.optimizeStorage()
                        }
                    }
                ) {
                    Text("确认删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCleanupConfirm = false }) {
                    Text("取消")
                }
            }
        )
    }

    if (showFolderDialog) {
        FolderSelectionDialog(
            folders = uiState.folders,
            isLoading = uiState.isLoadingFolders,
            onToggle = { path -> viewModel.toggleFolderSelection(path) },
            onDismiss = {
                showFolderDialog = false
                if (!uiState.isLoadingFolders) {
                    viewModel.saveFolderSelection()
                }
            }
        )
    }

    if (showDestinationDialog) {
        BackupDestinationDialog(
            isDefaultSelected = uiState.isDefaultBackupDestination,
            defaultDestinationSummary = defaultBackupDestinationSubtitle,
            selectedDestinationId = uiState.backupDestinationId,
            selectedDestinationSummary = backupDestinationSubtitle,
            nodes = uiState.backupDestinationNodes,
            breadcrumbs = uiState.backupDestinationBreadcrumbs,
            isLoading = uiState.isLoadingBackupDestinations,
            isCreatingFolder = uiState.isCreatingFolder,
            error = uiState.backupDestinationError,
            onDismiss = { showDestinationDialog = false },
            onReload = { viewModel.loadBackupDestinationRoot() },
            onNavigateUp = { viewModel.navigateUpBackupDestination() },
            onOpen = { node -> viewModel.openBackupDestination(node) },
            onCreateFolder = { name -> viewModel.createFolder(name) },
            onSelectDefault = {
                viewModel.selectDefaultBackupDestination()
                showDestinationDialog = false
            },
            onSelectCurrent = {
                viewModel.selectCurrentBackupDestination()
                showDestinationDialog = false
            },
            onSelect = { node ->
                viewModel.selectBackupDestination(node)
                showDestinationDialog = false
            }
        )
    }

    if (showCacheLimitDialog) {
        val options = listOf(256, 512, 1024, 2048, 5120)
        AlertDialog(
            onDismissRequest = { showCacheLimitDialog = false },
            title = { Text("最大缓存容量限制") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    options.forEach { limitMb ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.setCoilDiskCacheMb(limitMb)
                                    showCacheLimitDialog = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = uiState.coilDiskCacheMb == limitMb,
                                onClick = {
                                    viewModel.setCoilDiskCacheMb(limitMb)
                                    showCacheLimitDialog = false
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = formatCacheLimitLabel(limitMb) + if (limitMb == 512) " (默认)" else "",
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCacheLimitDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    if (showClearThumbnailCacheConfirm) {
        AlertDialog(
            onDismissRequest = { showClearThumbnailCacheConfirm = false },
            icon = {
                Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            },
            title = { Text("清除缩略图缓存") },
            text = {
                Text("即将清理所有已加载的相册缩略图缓存 (当前占用约 ${uiState.thumbnailCacheSizeFormatted})。清理后，在没有网络连接时将无法加载已离线缓存的缩略图，直到再次联网加载。确定要清除吗？")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearThumbnailCache()
                        showClearThumbnailCacheConfirm = false
                    }
                ) {
                    Text("确认清理", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearThumbnailCacheConfirm = false }) {
                    Text("取消")
                }
            }
        )
    }

    if (showClearMediaCacheConfirm) {
        AlertDialog(
            onDismissRequest = { showClearMediaCacheConfirm = false },
            icon = {
                Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            },
            title = { Text("清除照片与视频缓存") },
            text = {
                Text("即将清理本地查看时缓存的全部高清照片和大图、视频文件 (当前占用约 ${uiState.mediaCacheSizeFormatted})。清理后，在没有网络连接时将需要重新从云端加载原图或视频。确定要清除吗？")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearMediaCache()
                        showClearMediaCacheConfirm = false
                    }
                ) {
                    Text("确认清理", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearMediaCacheConfirm = false }) {
                    Text("取消")
                }
            }
        )
    }
}

private fun formatCacheLimitLabel(mb: Int): String {
    return if (mb >= 1024) {
        val gb = mb / 1024.0
        "%.1f GB".format(gb).replace(".0", "")
    } else {
        "$mb MB"
    }
}

@Composable
private fun BackupStatusCard(
    syncedCount: Int,
    backedUpCount: Int,
    pendingCount: Int,
    isBackingUp: Boolean
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "同步概况",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem(label = "已同步", value = "$syncedCount 项")
                StatItem(label = "已上传", value = "$backedUpCount 项")
                StatItem(label = "待备份", value = "$pendingCount 项")
            }
            if (isBackingUp) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    "正在备份...",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun folderSelectionSummary(uiState: BackupSettingsUiState): String {
    return when {
        uiState.selectedFolderCount <= 0 -> "未选择任何文件夹"
        uiState.historicalSelectedFolderCount > 0 ->
            "已选择 ${uiState.selectedFolderCount} 个文件夹，其中 ${uiState.historicalSelectedFolderCount} 个已无本地文件"
        else -> "已选择 ${uiState.selectedFolderCount} 个文件夹"
    }
}

private fun backupDestinationSummary(uiState: BackupSettingsUiState): String {
    return when {
        uiState.backupDestinationPath == "/" -> uiState.backupDestinationLabel
        uiState.backupDestinationPath.isNotBlank() &&
            uiState.backupDestinationPath != "/" &&
            uiState.backupDestinationPath != uiState.backupDestinationLabel ->
            "${uiState.backupDestinationLabel} · ${uiState.backupDestinationPath}"
        uiState.backupDestinationPath.isNotBlank() -> uiState.backupDestinationPath
        else -> uiState.backupDestinationLabel
    }
}

private fun defaultBackupDestinationSummary(uiState: BackupSettingsUiState): String {
    return when {
        uiState.defaultBackupDestinationPath.isNotBlank() &&
            uiState.defaultBackupDestinationPath != uiState.defaultBackupDestinationLabel ->
            "${uiState.defaultBackupDestinationLabel} · ${uiState.defaultBackupDestinationPath}"
        uiState.defaultBackupDestinationPath.isNotBlank() -> uiState.defaultBackupDestinationPath
        else -> uiState.defaultBackupDestinationLabel
    }
}

@Composable
private fun StorageOptimizationCard(
    optimizableCount: Int,
    optimizableSize: String,
    onOptimize: () -> Unit,
    isOptimizing: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (optimizableCount > 0) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CleaningServices, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("释放存储空间", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(modifier = Modifier.height(8.dp))

            if (optimizableCount > 0) {
                Text("共有 $optimizableCount 个已备份文件可以删除本地原件。")
                Text(
                    "可释放约 $optimizableSize",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "不会移除备份文件夹配置。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                if (isOptimizing) {
                    CircularProgressIndicator()
                } else {
                    TextButton(onClick = onOptimize) {
                        Text("释放空间")
                    }
                }
            } else {
                Text(
                    "暂无可优化的文件",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 4.dp)
    )
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            }
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                }
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                    alpha = if (enabled) 1f else 0.38f
                )
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
}

@Composable
private fun SettingActionRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            }
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                }
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                    alpha = if (enabled) 1f else 0.38f
                )
            )
        }
    }
}

@Composable
private fun FolderSelectionList(
    folders: List<FolderUiItem>,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(modifier = modifier) {
        items(folders) { folder ->
            val checked = folder.isSelected || folder.isCoveredBySelectedAncestor
            val enabled = !folder.isCoveredBySelectedAncestor
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = enabled) { onToggle(folder.path) }
                    .padding(
                        start = (folder.depth * 16).dp,
                        top = 4.dp,
                        bottom = 4.dp
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = checked,
                    enabled = enabled,
                    onCheckedChange = { onToggle(folder.path) }
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        folder.displayName,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = folderStatusText(folder),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (folder.isHistoricalOnly) {
                            MaterialTheme.colorScheme.secondary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun FolderSelectionDialog(
    folders: List<FolderUiItem>,
    isLoading: Boolean,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择备份文件夹") },
        text = {
            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (folders.isEmpty()) {
                Text(
                    text = "未发现可选择的媒体文件夹",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                FolderSelectionList(
                    folders = folders,
                    onToggle = onToggle,
                    modifier = Modifier.height(400.dp)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isLoading
            ) {
                Text("确定")
            }
        }
    )
}

@Composable
private fun BackupDestinationDialog(
    isDefaultSelected: Boolean,
    defaultDestinationSummary: String,
    selectedDestinationId: Long,
    selectedDestinationSummary: String,
    nodes: List<BackupDestinationNode>,
    breadcrumbs: List<BackupDestinationBreadcrumb>,
    isLoading: Boolean,
    isCreatingFolder: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onReload: () -> Unit,
    onNavigateUp: () -> Unit,
    onOpen: (BackupDestinationNode) -> Unit,
    onCreateFolder: (String) -> Unit,
    onSelectDefault: () -> Unit,
    onSelectCurrent: () -> Unit,
    onSelect: (BackupDestinationNode) -> Unit
) {
    val currentLocation = breadcrumbs.lastOrNull()?.path ?: "/"
    var showCreateDialog by remember { mutableStateOf(false) }
    var showCustomPicker by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择备份到") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BackupDestinationModeRow(
                    title = "默认",
                    subtitle = defaultDestinationSummary,
                    selected = isDefaultSelected && !showCustomPicker,
                    onClick = {
                        showCustomPicker = false
                        onSelectDefault()
                    }
                )
                BackupDestinationModeRow(
                    title = "自定义",
                    subtitle = if (isDefaultSelected) {
                        "选择服务端文件夹"
                    } else {
                        selectedDestinationSummary
                    },
                    selected = showCustomPicker || !isDefaultSelected,
                    onClick = { showCustomPicker = true }
                )

                if (showCustomPicker) {
                    Text(
                        text = "当前位置：$currentLocation",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onSelectCurrent) {
                            Text("选择当前目录")
                        }
                        if (breadcrumbs.size > 1) {
                            TextButton(onClick = onNavigateUp) {
                                Text("上一级")
                            }
                        }
                        TextButton(
                            onClick = { showCreateDialog = true },
                            enabled = !isCreatingFolder
                        ) {
                            Text(if (isCreatingFolder) "创建中…" else "新建文件夹")
                        }
                    }

                    when {
                        isLoading -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(160.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator()
                            }
                        }

                        error != null -> {
                            Text(
                                text = error,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }

                        nodes.isEmpty() -> {
                            Text(
                                text = "当前目录下没有可选子目录",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        else -> {
                            LazyColumn(modifier = Modifier.height(320.dp)) {
                                items(nodes) { node ->
                                    BackupDestinationRow(
                                        node = node,
                                        selected = !isDefaultSelected && node.id == selectedDestinationId,
                                        onOpen = { onOpen(node) },
                                        onSelect = { onSelect(node) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        },
        dismissButton = {
            if (showCustomPicker) {
                TextButton(onClick = onReload) {
                    Text("刷新")
                }
            }
        }
    )

    if (showCreateDialog) {
        var folderName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("新建文件夹") },
            text = {
                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    placeholder = { Text("输入文件夹名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (folderName.isNotBlank()) {
                            onCreateFolder(folderName.trim())
                            showCreateDialog = false
                        }
                    },
                    enabled = folderName.isNotBlank()
                ) {
                    Text("创建")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun BackupDestinationModeRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun BackupDestinationRow(
    node: BackupDestinationNode,
    selected: Boolean,
    onOpen: () -> Unit,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = node.name,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = node.path,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TextButton(onClick = onOpen) {
            Text("进入")
        }
        RadioButton(selected = selected, onClick = onSelect)
    }
}

private fun folderStatusText(folder: FolderUiItem): String {
    return when {
        folder.isCoveredBySelectedAncestor -> "已由上级文件夹覆盖"
        folder.hasLocalMedia && folder.directFileCount > 0 && folder.directFileCount != folder.fileCount ->
            "${folder.fileCount} 个文件，含子文件夹"
        folder.hasLocalMedia -> "${folder.fileCount} 个文件"
        folder.isSelected -> "本地文件已清理，仍保留为已选备份文件夹"
        else -> "历史文件夹，当前未发现本地文件"
    }
}

data class FolderUiItem(
    val path: String,
    val displayName: String,
    val fileCount: Int,
    val directFileCount: Int,
    val depth: Int,
    val isSelected: Boolean,
    val isCoveredBySelectedAncestor: Boolean,
    val hasLocalMedia: Boolean,
    val isHistoricalOnly: Boolean
)

@Composable
private fun SyncIntervalSetting(
    currentInterval: Int,
    onIntervalChange: (Int) -> Unit,
    enabled: Boolean
) {
    val options = listOf(30, 60, 120)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Text(
            text = "同步间隔",
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            }
        )
        Text(
            text = "定期扫描本地媒体变化，补充实时检测",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                alpha = if (enabled) 1f else 0.38f
            )
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { minutes ->
                FilterChip(
                    selected = currentInterval == minutes,
                    onClick = { onIntervalChange(minutes) },
                    label = { Text("${minutes} 分钟") },
                    enabled = enabled
                )
            }
        }
    }
}
