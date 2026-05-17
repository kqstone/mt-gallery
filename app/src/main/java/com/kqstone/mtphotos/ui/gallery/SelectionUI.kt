package com.kqstone.mtphotos.ui.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun SelectionTopBar(
    selectedCount: Int,
    onSelectAll: () -> Unit,
    onDelete: () -> Unit,
    onClearSelection: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onClearSelection) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "取消选择")
        }
        Text(
            text = "已选择 $selectedCount 项",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onSelectAll) {
            Icon(Icons.Default.SelectAll, contentDescription = "全选")
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "删除",
                tint = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
fun DeleteConfirmDialog(
    selectedCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("确认删除") },
        text = { Text("确定要删除选中的 $selectedCount 张照片吗？\n照片将移入服务端回收站。") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

/**
 * 删除方式选择对话框。
 * 首次删除本地文件时弹出，让用户选择直接删除或系统确认删除。
 */
@Composable
fun DeleteModeDialog(
    onChooseDirect: () -> Unit,
    onChooseConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    var selected by remember { mutableStateOf("confirm") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择删除方式") },
        text = {
            Column {
                Text("删除本地文件时，请选择一种方式：", style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { selected = "direct" }
                        .padding(vertical = 8.dp)
                ) {
                    RadioButton(selected = selected == "direct", onClick = { selected = "direct" })
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text("直接删除", style = MaterialTheme.typography.bodyLarge)
                        Text("需授权「所有文件访问权限」，删除时无弹窗", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { selected = "confirm" }
                        .padding(vertical = 8.dp)
                ) {
                    RadioButton(selected = selected == "confirm", onClick = { selected = "confirm" })
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text("确认后删除", style = MaterialTheme.typography.bodyLarge)
                        Text("每次删除时系统弹窗确认，无需特殊权限", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (selected == "direct") onChooseDirect() else onChooseConfirm()
            }) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
