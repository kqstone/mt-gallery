package com.kqstone.mtphotos.ui.gallery

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kqstone.mtphotos.ui.util.AppTopBarContainer

import androidx.compose.ui.res.stringResource
import com.kqstone.mtphotos.R
import androidx.compose.material.icons.filled.Share

@Composable
fun SelectionTopBar(
    selectedCount: Int,
    onSelectAll: () -> Unit,
    onDelete: () -> Unit,
    onShare: (() -> Unit)? = null,
    onFavorite: (() -> Unit)? = null,
    onUnfavorite: (() -> Unit)? = null,
    onClearSelection: () -> Unit,
    modifier: Modifier = Modifier,
    scrollAlpha: Float = 1f
) {
    AppTopBarContainer(
        modifier = modifier,
        scrollAlpha = scrollAlpha,
        isOpaque = true
    ) {
        IconButton(
            onClick = onClearSelection,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.cancel_selection)
            )
        }
        Text(
            text = stringResource(R.string.selected_count_format, selectedCount),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.weight(1f)
        )
        if (onShare != null) {
            IconButton(
                onClick = onShare,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Default.Share,
                    contentDescription = stringResource(R.string.share)
                )
            }
        }
        if (onFavorite != null) {
            IconButton(
                onClick = onFavorite,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Default.Star,
                    contentDescription = stringResource(R.string.favorite)
                )
            }
        }
        if (onUnfavorite != null) {
            IconButton(
                onClick = onUnfavorite,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Default.StarBorder,
                    contentDescription = stringResource(R.string.unfavorite)
                )
            }
        }
        IconButton(
            onClick = onSelectAll,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                Icons.Default.SelectAll,
                contentDescription = stringResource(R.string.select_all)
            )
        }
        IconButton(
            onClick = onDelete,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                Icons.Default.Delete,
                contentDescription = stringResource(R.string.delete),
                tint = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun LegacyDeleteConfirmDialog(
    selectedCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.confirm_delete_title)) },
        text = { Text(stringResource(R.string.delete_confirm_desc_legacy, selectedCount)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
fun DeleteConfirmDialog(
    selectedCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.confirm_delete_title)) },
        text = { Text(stringResource(R.string.delete_confirm_desc)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
