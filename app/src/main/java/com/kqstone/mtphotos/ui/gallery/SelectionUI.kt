package com.kqstone.mtphotos.ui.gallery

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Alignment
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
    UNHIDE,
    DELETE
}

data class MediaSelectionAction(
    val type: MediaSelectionActionType,
    val enabled: Boolean = true,
    val onClick: () -> Unit
)

val SelectionBottomBarHeight = 56.dp

class SelectionBottomBarHostState {
    var actions by mutableStateOf<List<MediaSelectionAction>>(emptyList())
        private set

    private var owner: Any? = null

    fun show(owner: Any, actions: List<MediaSelectionAction>) {
        this.owner = owner
        this.actions = actions
    }

    fun clear(owner: Any) {
        if (this.owner == owner) {
            this.owner = null
            actions = emptyList()
        }
    }
}

val LocalSelectionBottomBarHost = staticCompositionLocalOf<SelectionBottomBarHostState?> { null }

@Composable
fun rememberSelectionBottomBarHostState(): SelectionBottomBarHostState {
    return remember { SelectionBottomBarHostState() }
}

@Composable
fun PublishSelectionBottomBar(
    visible: Boolean,
    actions: List<MediaSelectionAction>
) {
    val host = LocalSelectionBottomBarHost.current
    val owner = remember { Any() }

    DisposableEffect(host, owner) {
        onDispose {
            host?.clear(owner)
        }
    }

    SideEffect {
        if (visible) {
            host?.show(owner, actions)
        } else {
            host?.clear(owner)
        }
    }
}

@Composable
fun SelectionTopBar(
    selectedCount: Int,
    onSelectAll: () -> Unit,
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
        IconButton(
            onClick = onSelectAll,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                Icons.Default.SelectAll,
                contentDescription = stringResource(R.string.select_all)
            )
        }
    }
}

@Composable
fun SelectionBottomBar(
    actions: List<MediaSelectionAction>,
    modifier: Modifier = Modifier
) {
    NavigationBar(
        modifier = modifier
            .fillMaxWidth()
            .height(SelectionBottomBarHeight),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            actions.forEach { action ->
                SelectionBottomBarItem(
                    action = action,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun SelectionBottomBarItem(
    action: MediaSelectionAction,
    modifier: Modifier = Modifier
) {
    val icon = when (action.type) {
        MediaSelectionActionType.SHARE -> Icons.Default.Share
        MediaSelectionActionType.FAVORITE -> Icons.Default.Star
        MediaSelectionActionType.UNFAVORITE -> Icons.Default.StarBorder
        MediaSelectionActionType.HIDE -> Icons.Default.VisibilityOff
        MediaSelectionActionType.UNHIDE -> Icons.Default.Visibility
        MediaSelectionActionType.DELETE -> Icons.Default.Delete
    }
    val label = when (action.type) {
        MediaSelectionActionType.SHARE -> stringResource(R.string.share)
        MediaSelectionActionType.FAVORITE -> stringResource(R.string.favorite)
        MediaSelectionActionType.UNFAVORITE -> stringResource(R.string.unfavorite)
        MediaSelectionActionType.HIDE -> stringResource(R.string.hide)
        MediaSelectionActionType.UNHIDE -> stringResource(R.string.unhide)
        MediaSelectionActionType.DELETE -> stringResource(R.string.delete)
    }
    val isDelete = action.type == MediaSelectionActionType.DELETE
    val contentColor = when {
        !action.enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
        isDelete -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier = modifier
            .height(SelectionBottomBarHeight)
            .clickable(enabled = action.enabled, onClick = action.onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = contentColor
        )
        Text(
            text = label,
            color = contentColor,
            style = MaterialTheme.typography.labelSmall
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
