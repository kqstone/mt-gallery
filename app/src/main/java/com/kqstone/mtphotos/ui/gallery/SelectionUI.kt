package com.kqstone.mtphotos.ui.gallery

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
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

enum class MediaSelectionActionType {
    SHARE,
    FAVORITE,
    UNFAVORITE,
    HIDE,
    UNHIDE
}

data class MediaSelectionAction(
    val type: MediaSelectionActionType,
    val onClick: () -> Unit
)

@Composable
fun SelectionTopBar(
    selectedCount: Int,
    onSelectAll: () -> Unit,
    onDelete: () -> Unit,
    actions: List<MediaSelectionAction> = emptyList(),
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
        actions.forEach { action ->
            SelectionActionIcon(action = action)
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
private fun SelectionActionIcon(action: MediaSelectionAction) {
    val icon = when (action.type) {
        MediaSelectionActionType.SHARE -> Icons.Default.Share
        MediaSelectionActionType.FAVORITE -> Icons.Default.Star
        MediaSelectionActionType.UNFAVORITE -> Icons.Default.StarBorder
        MediaSelectionActionType.HIDE -> Icons.Default.VisibilityOff
        MediaSelectionActionType.UNHIDE -> Icons.Default.Visibility
    }
    val label = when (action.type) {
        MediaSelectionActionType.SHARE -> stringResource(R.string.share)
        MediaSelectionActionType.FAVORITE -> stringResource(R.string.favorite)
        MediaSelectionActionType.UNFAVORITE -> stringResource(R.string.unfavorite)
        MediaSelectionActionType.HIDE -> stringResource(R.string.hide)
        MediaSelectionActionType.UNHIDE -> stringResource(R.string.unhide)
    }
    IconButton(
        onClick = action.onClick,
        modifier = Modifier.size(32.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label
        )
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
