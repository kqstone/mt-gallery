package com.kqstone.mtphotos.ui.util

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import java.time.LocalDate

@Composable
fun AppTopBarContainer(
    modifier: Modifier = Modifier,
    scrollAlpha: Float = 1f,
    isOpaque: Boolean = false,
    expandedContent: (@Composable () -> Unit)? = null,
    content: @Composable RowScope.() -> Unit
) {
    val isDarkOverlay = !isOpaque && scrollAlpha > 0.05f
    StatusBarStyleEffect(darkOverlay = isDarkOverlay)

    val contentColor = if (isDarkOverlay) androidx.compose.ui.graphics.Color.White else MaterialTheme.colorScheme.onSurface

    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.material3.LocalContentColor provides contentColor
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .then(
                    if (isOpaque) Modifier.background(MaterialTheme.colorScheme.surface)
                    else Modifier.gradientShadowCached(alpha = scrollAlpha, maxAlpha = 0.85f)
                )
                .statusBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = content
            )
            expandedContent?.invoke()
        }
    }
}

@Composable
fun TitleTopBar(
    title: String,
    modifier: Modifier = Modifier,
    scrollAlpha: Float = 1f,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    AppTopBarContainer(
        modifier = modifier,
        scrollAlpha = scrollAlpha
    ) {
        if (navigationIcon != null) {
            navigationIcon()
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.weight(1f)
        )
        actions()
    }
}

@Composable
fun BackTitleTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    scrollAlpha: Float = 1f,
    actions: @Composable RowScope.() -> Unit = {}
) {
    TitleTopBar(
        title = title,
        modifier = modifier,
        scrollAlpha = scrollAlpha,
        navigationIcon = {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回"
                )
            }
        },
        actions = actions
    )
}

@Composable
fun TopBarActionIcon(
    imageVector: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconSize: androidx.compose.ui.unit.Dp = 18.dp,
    buttonSize: androidx.compose.ui.unit.Dp = 32.dp,
    isVariant: Boolean = true
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(buttonSize)
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            tint = if (isVariant) androidx.compose.material3.LocalContentColor.current.copy(alpha = 0.8f)
                   else androidx.compose.material3.LocalContentColor.current,
            modifier = Modifier.size(iconSize)
        )
    }
}

@Composable
fun SimpleTitleHeader(
    title: String,
    onSettingsClick: () -> Unit,
    onAboutClick: () -> Unit,
    onOpLogClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    scrollAlpha: Float = 1f
) {
    var menuExpanded by remember { mutableStateOf(false) }

    TitleTopBar(
        title = title,
        modifier = modifier,
        scrollAlpha = scrollAlpha,
        actions = {
            Box {
                TopBarActionIcon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "更多选项",
                    onClick = { menuExpanded = true }
                )
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    modifier = Modifier
                        .width(135.dp)
                        .background(MaterialTheme.colorScheme.surface)
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                            shape = RoundedCornerShape(16.dp)
                        ),
                    shape = RoundedCornerShape(16.dp),
                    offset = androidx.compose.ui.unit.DpOffset(x = (-8).dp, y = 4.dp)
                ) {
                    DropdownMenuItem(
                        text = { 
                            Text(
                                text = "设置", 
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)
                            ) 
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onSettingsClick()
                        }
                    )
                    DropdownMenuItem(
                        text = { 
                            Text(
                                text = "操作日志", 
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)
                            ) 
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.History,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onOpLogClick()
                        }
                    )
                    DropdownMenuItem(
                        text = { 
                            Text(
                                text = "关于", 
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)
                            ) 
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onAboutClick()
                        }
                    )
                }
            }
        }
    )
}

fun formatDayHeaderDate(dateStr: String): String {
    return try {
        val targetDate = LocalDate.parse(dateStr)
        val today = LocalDate.now()
        val yesterday = today.minusDays(1)
        when (targetDate) {
            today -> "今天"
            yesterday -> "昨天"
            else -> dateStr
        }
    } catch (e: Exception) {
        dateStr
    }
}
