package com.kqstone.mtphotos.ui.settings

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
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kqstone.mtphotos.data.local.LocalMediaScanner
import com.kqstone.mtphotos.data.local.MediaFolder
import com.kqstone.mtphotos.data.local.PrefsManager
import com.google.gson.Gson
import kotlinx.coroutines.launch

/**
 * 首次登录后的文件夹选择页面。
 * 让用户选择要在 Gallery 中显示（和备份）的媒体文件夹。
 */
@Composable
fun FolderSetupScreen(
    prefsManager: PrefsManager,
    localMediaScanner: LocalMediaScanner,
    onSetupComplete: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var isLoading by remember { mutableStateOf(true) }
    val folders = remember { mutableStateListOf<MediaFolder>() }
    val selectedPaths = remember { mutableStateListOf<String>() }

    // 加载文件夹列表
    LaunchedEffect(Unit) {
        val mediaFolders = localMediaScanner.getMediaFolders()
        folders.clear()
        folders.addAll(mediaFolders)
        isLoading = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        Spacer(modifier = Modifier.height(48.dp))

        // 标题区域
        Icon(
            imageVector = Icons.Default.PhotoLibrary,
            contentDescription = null,
            modifier = Modifier
                .height(56.dp)
                .width(56.dp)
                .align(Alignment.CenterHorizontally),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "选择媒体文件夹",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "选中的文件夹将显示在 Gallery 中，并可备份到云端。\n你可以稍后在设置中修改。",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(24.dp))

        if (isLoading) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("正在扫描设备文件夹...")
                }
            }
        } else {
            // 选择计数
            Text(
                text = "已选择 ${selectedPaths.size} / ${folders.size} 个文件夹",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // 文件夹列表
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(folders) { folder ->
                    val isSelected = folder.path in selectedPaths
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (isSelected) selectedPaths.remove(folder.path)
                                else selectedPaths.add(folder.path)
                            }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = {
                                if (isSelected) selectedPaths.remove(folder.path)
                                else selectedPaths.add(folder.path)
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            Icons.Default.Folder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                folder.displayName,
                                style = MaterialTheme.typography.bodyLarge
                            )
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

        Spacer(modifier = Modifier.height(16.dp))

        // 底部按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = {
                    selectedPaths.clear()
                    selectedPaths.addAll(folders.map { it.path })
                },
                modifier = Modifier.weight(1f),
                enabled = !isLoading
            ) {
                Text("全部选择")
            }

            Button(
                onClick = {
                    scope.launch {
                        val json = Gson().toJson(selectedPaths.toSet())
                        prefsManager.saveBackupFolders(json)
                        prefsManager.saveFolderSetupComplete(true)
                        onSetupComplete()
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = !isLoading && selectedPaths.isNotEmpty()
            ) {
                Text("开始使用")
            }
        }
    }
}
