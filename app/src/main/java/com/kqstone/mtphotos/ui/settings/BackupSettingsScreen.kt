package com.kqstone.mtphotos.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import com.kqstone.mtphotos.R
import com.kqstone.mtphotos.ui.util.UiText
import android.content.Context
import androidx.compose.ui.res.stringResource
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

    val context = LocalContext.current
    val backupDestinationSubtitle = backupDestinationSummary(uiState)
    val defaultBackupDestinationSubtitle = defaultBackupDestinationSummary(uiState)
    val folderSubtitle = folderSelectionSummary(context, uiState)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (step == 0) stringResource(R.string.select_backup_destination) else stringResource(R.string.select_backup_folder)) },
                navigationIcon = {
                    if (step > 0) {
                        IconButton(onClick = { step-- }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
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
                        title = stringResource(R.string.backup_destination),
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
                        Text(stringResource(R.string.next_step))
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
                            Text(stringResource(R.string.backup_folder), style = MaterialTheme.typography.titleMedium)
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
                        Text(stringResource(R.string.complete))
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

    val folderSubtitle = folderSelectionSummary(context, uiState)
    val backupDestinationSubtitle = backupDestinationSummary(uiState)
    val defaultBackupDestinationSubtitle = defaultBackupDestinationSummary(uiState)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (setupMode) stringResource(R.string.setup_backup) else stringResource(R.string.backup_and_storage)) },
                navigationIcon = {
                    if (!setupMode) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                    }
                },
                actions = {
                    if (setupMode) {
                        TextButton(
                            onClick = { viewModel.completeInitialSetup(onSetupComplete) },
                            enabled = uiState.selectedFolderCount > 0 && !uiState.isSyncing
                        ) {
                            Text(stringResource(R.string.complete))
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
                                    stringResource(R.string.scanning_device_files),
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    stringResource(R.string.first_scan_takes_time),
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
                item { SectionTitle(stringResource(R.string.server)) }

                item {
                    SettingActionRow(
                        title = stringResource(R.string.server_connection),
                        subtitle = stringResource(R.string.maintain_server_addresses),
                        icon = Icons.Default.Link,
                        onClick = onServerConnection
                    )
                }
            }

            item { SectionTitle(stringResource(R.string.auto_backup)) }

            item {
                SettingSwitchRow(
                    title = stringResource(R.string.enable_auto_backup),
                    subtitle = stringResource(R.string.auto_backup_desc),
                    icon = Icons.Default.Backup,
                    checked = uiState.backupEnabled,
                    onCheckedChange = { viewModel.setBackupEnabled(it) }
                )
            }

            item {
                SettingSwitchRow(
                    title = stringResource(R.string.backup_on_wifi_only),
                    subtitle = stringResource(R.string.backup_on_wifi_only_desc),
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
                    title = stringResource(R.string.backup_now),
                    subtitle = if (uiState.pendingCount > 0) {
                        stringResource(R.string.pending_backup_count_format, uiState.pendingCount)
                    } else {
                        stringResource(R.string.all_files_backed_up)
                    },
                    icon = Icons.Default.Backup,
                    onClick = { viewModel.triggerBackupNow() },
                    enabled = uiState.backupEnabled && uiState.pendingCount > 0
                )
            }

            item {
                SettingActionRow(
                    title = stringResource(R.string.scan_and_upload_missing),
                    subtitle = uiState.backupRepairMessage?.asString()
                        ?: stringResource(R.string.scan_and_upload_missing_desc),
                    icon = Icons.Default.Cloud,
                    onClick = { viewModel.repairMissingBackups() },
                    enabled = uiState.backupEnabled && !uiState.isRepairingBackups
                )
                if (uiState.isRepairingBackups) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }

            item { SectionTitle(stringResource(R.string.backup_destination)) }

            item {
                SettingActionRow(
                    title = stringResource(R.string.backup_destination),
                    subtitle = backupDestinationSubtitle,
                    icon = Icons.Default.Cloud,
                    onClick = {
                        showDestinationDialog = true
                        viewModel.loadBackupDestinationRoot()
                    }
                )
            }

            item { SectionTitle(stringResource(R.string.backup_folder)) }

            item {
                SettingActionRow(
                    title = stringResource(R.string.select_backup_folder),
                    subtitle = folderSubtitle,
                    icon = Icons.Default.Folder,
                    onClick = {
                        showFolderDialog = true
                        viewModel.loadFolders()
                    },
                    enabled = uiState.backupEnabled
                )
            }

            item { SectionTitle(stringResource(R.string.storage_management)) }

            item {
                StorageOptimizationCard(
                    optimizableCount = uiState.optimizableCount,
                    optimizableSize = uiState.optimizableSizeFormatted,
                    onOptimize = { showCleanupConfirm = true },
                    isOptimizing = uiState.isOptimizing
                )
            }

            item { SectionTitle(stringResource(R.string.cache_management)) }

            item {
                SettingActionRow(
                    title = stringResource(R.string.max_thumbnail_cache_size),
                    subtitle = formatCacheLimitLabel(uiState.coilDiskCacheMb),
                    icon = Icons.Default.Folder,
                    onClick = { showCacheLimitDialog = true }
                )
            }

            item {
                SettingActionRow(
                    title = stringResource(R.string.clear_thumbnail_cache),
                    subtitle = stringResource(R.string.current_used_cache_format, uiState.thumbnailCacheSizeFormatted),
                    icon = Icons.Default.CleaningServices,
                    onClick = { showClearThumbnailCacheConfirm = true }
                )
            }

            item {
                SettingActionRow(
                    title = stringResource(R.string.clear_media_cache),
                    subtitle = stringResource(R.string.current_used_cache_format, uiState.mediaCacheSizeFormatted),
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
            title = { Text(stringResource(R.string.free_up_storage_space)) },
            text = {
                Column {
                    Text(stringResource(R.string.free_up_storage_desc, uiState.optimizableCount))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.can_free_up_format, uiState.optimizableSizeFormatted),
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.free_up_warning_desc),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.free_up_warning_folder_config),
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
                    Text(stringResource(R.string.confirm_delete_btn), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCleanupConfirm = false }) {
                    Text(stringResource(R.string.cancel))
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
            title = { Text(stringResource(R.string.max_cache_limit)) },
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
                                text = formatCacheLimitLabel(limitMb) + if (limitMb == 512) stringResource(R.string.default_label) else "",
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCacheLimitDialog = false }) {
                    Text(stringResource(R.string.cancel))
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
            title = { Text(stringResource(R.string.clear_thumbnail_cache)) },
            text = {
                Text(stringResource(R.string.clear_thumbnail_cache_confirm_desc, uiState.thumbnailCacheSizeFormatted))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearThumbnailCache()
                        showClearThumbnailCacheConfirm = false
                    }
                ) {
                    Text(stringResource(R.string.confirm_clear), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearThumbnailCacheConfirm = false }) {
                    Text(stringResource(R.string.cancel))
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
            title = { Text(stringResource(R.string.clear_media_cache)) },
            text = {
                Text(stringResource(R.string.clear_media_cache_confirm_desc, uiState.mediaCacheSizeFormatted))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearMediaCache()
                        showClearMediaCacheConfirm = false
                    }
                ) {
                    Text(stringResource(R.string.confirm_clear), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearMediaCacheConfirm = false }) {
                    Text(stringResource(R.string.cancel))
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
                    stringResource(R.string.sync_summary),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem(label = stringResource(R.string.synced_label_format), value = stringResource(R.string.items_count_suffix_format, syncedCount.toString()))
                StatItem(label = stringResource(R.string.uploaded_label_format), value = stringResource(R.string.items_count_suffix_format, backedUpCount.toString()))
                StatItem(label = stringResource(R.string.pending_backup_label_format), value = stringResource(R.string.items_count_suffix_format, pendingCount.toString()))
            }
            if (isBackingUp) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    stringResource(R.string.backing_up_progress),
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

private fun folderSelectionSummary(context: Context, uiState: BackupSettingsUiState): String {
    return when {
        uiState.selectedFolderCount <= 0 -> context.getString(R.string.no_folder_selected)
        uiState.historicalSelectedFolderCount > 0 ->
            context.getString(R.string.folders_selected_some_empty_format, uiState.selectedFolderCount, uiState.historicalSelectedFolderCount)
        else -> context.getString(R.string.folders_selected_format, uiState.selectedFolderCount)
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
                Text(stringResource(R.string.free_up_storage_space), style = MaterialTheme.typography.titleMedium)
            }
            Spacer(modifier = Modifier.height(8.dp))

            if (optimizableCount > 0) {
                Text(stringResource(R.string.free_up_storage_desc_simple, optimizableCount))
                Text(
                    stringResource(R.string.can_free_up_format, optimizableSize),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    stringResource(R.string.free_up_storage_warning_simple),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                if (isOptimizing) {
                    CircularProgressIndicator()
                } else {
                    TextButton(onClick = onOptimize) {
                        Text(stringResource(R.string.free_up_space_btn))
                    }
                }
            } else {
                Text(
                    stringResource(R.string.no_optimizable_files),
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
                        text = folderStatusText(LocalContext.current, folder),
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
        title = { Text(stringResource(R.string.select_backup_folder)) },
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
                    text = stringResource(R.string.no_media_folders_found),
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
                Text(stringResource(R.string.ok))
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
    error: UiText?,
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
        title = { Text(stringResource(R.string.select_backup_to)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BackupDestinationModeRow(
                    title = stringResource(R.string.default_destination),
                    subtitle = defaultDestinationSummary,
                    selected = isDefaultSelected && !showCustomPicker,
                    onClick = {
                        showCustomPicker = false
                        onSelectDefault()
                    }
                )
                BackupDestinationModeRow(
                    title = stringResource(R.string.custom),
                    subtitle = if (isDefaultSelected) {
                        stringResource(R.string.select_server_folder)
                    } else {
                        selectedDestinationSummary
                    },
                    selected = showCustomPicker || !isDefaultSelected,
                    onClick = { showCustomPicker = true }
                )

                if (showCustomPicker) {
                    Text(
                        text = stringResource(R.string.current_location_format, currentLocation),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onSelectCurrent) {
                            Text(stringResource(R.string.select_current_directory))
                        }
                        if (breadcrumbs.size > 1) {
                            TextButton(onClick = onNavigateUp) {
                                Text(stringResource(R.string.parent_directory))
                            }
                        }
                        TextButton(
                            onClick = { showCreateDialog = true },
                            enabled = !isCreatingFolder
                        ) {
                            Text(if (isCreatingFolder) stringResource(R.string.creating_folder) else stringResource(R.string.new_folder))
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
                                text = error.asString(),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }

                        nodes.isEmpty() -> {
                            Text(
                                text = stringResource(R.string.no_subfolders_in_directory),
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
                Text(stringResource(R.string.close_btn))
            }
        },
        dismissButton = {
            if (showCustomPicker) {
                TextButton(onClick = onReload) {
                    Text(stringResource(R.string.refresh_btn))
                }
            }
        }
    )

    if (showCreateDialog) {
        var folderName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text(stringResource(R.string.new_folder)) },
            text = {
                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    placeholder = { Text(stringResource(R.string.enter_folder_name)) },
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
                    Text(stringResource(R.string.create_btn))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) {
                    Text(stringResource(R.string.cancel))
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
            Text(stringResource(R.string.enter))
        }
        RadioButton(selected = selected, onClick = onSelect)
    }
}

private fun folderStatusText(context: Context, folder: FolderUiItem): String {
    return when {
        folder.isCoveredBySelectedAncestor -> context.getString(R.string.covered_by_parent_folder)
        folder.hasLocalMedia && folder.directFileCount > 0 && folder.directFileCount != folder.fileCount ->
            context.getString(R.string.files_count_with_subfolders_format, folder.fileCount)
        folder.hasLocalMedia -> context.getString(R.string.files_count_format, folder.fileCount)
        folder.isSelected -> context.getString(R.string.folder_retained_after_clear)
        else -> context.getString(R.string.historical_folder_no_files)
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
            text = stringResource(R.string.sync_interval),
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            }
        )
        Text(
            text = stringResource(R.string.sync_interval_desc),
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
                    label = { Text(stringResource(R.string.minutes_format, minutes)) },
                    enabled = enabled
                )
            }
        }
    }
}
