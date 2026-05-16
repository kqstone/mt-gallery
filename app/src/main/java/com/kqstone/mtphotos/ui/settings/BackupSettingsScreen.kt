package com.kqstone.mtphotos.ui.settings

import android.widget.Toast
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
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import com.kqstone.mtphotos.ui.util.MediaPermissionRequester
import com.kqstone.mtphotos.ui.util.PermissionHelper
import com.kqstone.mtphotos.ui.util.RequestNotificationPermission

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupSettingsScreen(
    viewModel: BackupSettingsViewModel,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    var showCleanupConfirm by remember { mutableStateOf(false) }
    var showFolderDialog by remember { mutableStateOf(false) }
    // 当备份开启且有通知权限需求时，请求通知权限
    var requestNotificationPerm by remember { mutableStateOf(false) }

    // 使用 MediaPermissionRequester 在整个 Screen 范围内管理权限状态
    MediaPermissionRequester { hasMediaPermission, requestMediaPermission ->

        // 有权限时才加载数据
        LaunchedEffect(hasMediaPermission) {
            if (hasMediaPermission) {
                viewModel.loadStats()
                viewModel.loadFolders()
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("备份与存储") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
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
                // ===== 同步扫描中提示 =====
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
                                        "首次扫描需要一些时间，请稍候",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer
                                    )
                                }
                            }
                        }
                    }
                }

                // ===== 备份概况卡片 =====
                item {
                    BackupStatusCard(
                        syncedCount = uiState.syncedCount,
                        backedUpCount = uiState.backedUpCount,
                        pendingCount = uiState.pendingCount,
                        isBackingUp = uiState.isBackingUp
                    )
                }

                // ===== 自动备份开关 =====
                item {
                    SectionTitle("自动备份")
                }

                item {
                    SettingSwitchRow(
                        title = "开启自动备份",
                        subtitle = "自动将设备照片和视频备份到服务器",
                        icon = Icons.Default.Backup,
                        checked = uiState.backupEnabled,
                        onCheckedChange = { enabled ->
                            if (enabled && !hasMediaPermission) {
                                // 开启备份前先请求媒体权限
                                requestMediaPermission()
                                Toast.makeText(context, "需要授予媒体访问权限才能备份", Toast.LENGTH_SHORT).show()
                            } else {
                                viewModel.setBackupEnabled(enabled)
                                if (enabled) {
                                    requestNotificationPerm = true
                                }
                            }
                        }
                    )
                }

                item {
                    SettingSwitchRow(
                        title = "仅 Wi-Fi 备份",
                        subtitle = "关闭后也可使用移动数据备份",
                        icon = Icons.Default.Cloud,
                        checked = uiState.wifiOnly,
                        onCheckedChange = { viewModel.setWifiOnly(it) },
                        enabled = uiState.backupEnabled
                    )
                }

                // 手动触发备份
                item {
                    SettingActionRow(
                        title = "立即备份",
                        subtitle = if (uiState.pendingCount > 0) "${uiState.pendingCount} 个文件待备份" else "所有文件已备份",
                        icon = Icons.Default.Backup,
                        onClick = {
                            if (!hasMediaPermission) {
                                requestMediaPermission()
                                Toast.makeText(context, "需要授予媒体访问权限才能备份", Toast.LENGTH_SHORT).show()
                            } else {
                                viewModel.triggerBackupNow()
                            }
                        },
                        enabled = uiState.backupEnabled && uiState.pendingCount > 0
                    )
                }

                // ===== 备份文件夹选择 =====
                item {
                    SectionTitle("备份文件夹")
                }

                item {
                    SettingActionRow(
                        title = "选择备份文件夹",
                        subtitle = if (!hasMediaPermission) {
                            "需要授权媒体访问权限"
                        } else if (uiState.selectedFolderCount > 0) {
                            "已选择 ${uiState.selectedFolderCount} 个文件夹"
                        } else {
                            "全部文件夹"
                        },
                        icon = Icons.Default.Folder,
                        onClick = {
                            if (!hasMediaPermission) {
                                // 选择文件夹前先请求权限
                                requestMediaPermission()
                                Toast.makeText(context, "需要授予媒体访问权限才能查看文件夹", Toast.LENGTH_SHORT).show()
                            } else {
                                showFolderDialog = true
                            }
                        },
                        enabled = uiState.backupEnabled
                    )
                }

                // ===== 存储管理 =====
                item {
                    SectionTitle("存储管理")
                }

                item {
                    StorageOptimizationCard(
                        optimizableCount = uiState.optimizableCount,
                        optimizableSize = uiState.optimizableSizeFormatted,
                        onOptimize = { showCleanupConfirm = true },
                        isOptimizing = uiState.isOptimizing
                    )
                }
            }
        }

        // 请求通知权限（开启备份后触发）
        if (requestNotificationPerm) {
            RequestNotificationPermission { granted ->
                requestNotificationPerm = false
                // 无论是否授权都不阻塞，只是通知可能静默
            }
        }

        // 二次确认对话框 — 存储优化
        if (showCleanupConfirm) {
            AlertDialog(
                onDismissRequest = { showCleanupConfirm = false },
                icon = {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFF9800))
                },
                title = { Text("释放存储空间") },
                text = {
                    Column {
                        Text("即将删除 ${uiState.optimizableCount} 个已备份的本地原图/视频文件。")
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "可释放约 ${uiState.optimizableSizeFormatted}",
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "⚠️ 删除后将无法恢复本地文件，但云端备份和缩略图缓存不受影响。",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showCleanupConfirm = false
                            viewModel.optimizeStorage()
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

        // 文件夹选择对话框
        if (showFolderDialog) {
            FolderSelectionDialog(
                folders = uiState.folders,
                onToggle = { path -> viewModel.toggleFolderSelection(path) },
                onDismiss = {
                    showFolderDialog = false
                    viewModel.saveFolderSelection()
                }
            )
        }
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
                StatItem(label = "已同步", value = "$syncedCount 张")
                StatItem(label = "已上传", value = "$backedUpCount 张")
                StatItem(label = "待备份", value = "$pendingCount 张")
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
            containerColor = if (optimizableCount > 0)
                MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
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
                Text("有 $optimizableCount 个已备份的文件可以删除本地原图")
                Text(
                    "可释放约 $optimizableSize",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
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
            tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.38f)
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
            tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.38f)
            )
        }
    }
}

@Composable
private fun FolderSelectionDialog(
    folders: List<FolderUiItem>,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择备份文件夹") },
        text = {
            if (folders.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(100.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(modifier = Modifier.height(400.dp)) {
                    items(folders) { folder ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onToggle(folder.path) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = folder.isSelected,
                                onCheckedChange = { onToggle(folder.path) }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(folder.displayName, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "${folder.fileCount} 个文件",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("确定")
            }
        }
    )
}

data class FolderUiItem(
    val path: String,
    val displayName: String,
    val fileCount: Int,
    val isSelected: Boolean
)
