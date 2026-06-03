package com.kqstone.mtphotos.ui.oplog

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.kqstone.mtphotos.data.local.db.ServerOpStatus
import com.kqstone.mtphotos.data.local.db.ServerOpType
import com.kqstone.mtphotos.ui.oplog.OpLogViewModel.Companion.opTypeDisplayName
import com.kqstone.mtphotos.ui.oplog.OpLogViewModel.Companion.statusDisplayName
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.res.stringResource
import com.kqstone.mtphotos.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpLogScreen(
    viewModel: OpLogViewModel,
    onBack: () -> Unit,
    getThumbUrl: (String, Double?) -> String
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.op_log_title),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.clearSuccessLogs() }) {
                        Icon(
                            Icons.Default.CleaningServices,
                            contentDescription = stringResource(R.string.clear_success_logs),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // 过滤栏
            FilterSection(
                statusFilter = uiState.statusFilter,
                typeFilter = uiState.typeFilter,
                onStatusFilterChange = { viewModel.setStatusFilter(it) },
                onTypeFilterChange = { viewModel.setTypeFilter(it) }
            )

            // 任务列表
            if (uiState.tasks.isEmpty() && !uiState.isLoading) {
                EmptyState()
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(uiState.tasks, key = { it.id }) { task ->
                        OpLogItemCard(
                            item = task,
                            thumbUrl = if (task.mediaMd5.isNotEmpty()) {
                                getThumbUrl(task.mediaMd5, task.mediaCloudId)
                            } else null,
                            onRetry = { viewModel.retryTask(task.id) }
                        )
                    }
                    item { Spacer(modifier = Modifier.height(16.dp)) }
                }
            }
        }
    }
}

@Composable
private fun FilterSection(
    statusFilter: ServerOpStatus?,
    typeFilter: ServerOpType?,
    onStatusFilterChange: (ServerOpStatus?) -> Unit,
    onTypeFilterChange: (ServerOpType?) -> Unit
) {
    Column(modifier = Modifier.padding(bottom = 4.dp)) {
        // 状态过滤
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                FilterChip(
                    selected = statusFilter == null,
                    onClick = { onStatusFilterChange(null) },
                    label = { Text(stringResource(R.string.all_statuses), style = MaterialTheme.typography.labelMedium) },
                    colors = filterChipColors()
                )
            }
            val statusList = listOf(
                ServerOpStatus.SUCCESS,
                ServerOpStatus.ERROR,
                ServerOpStatus.FAILED,
                ServerOpStatus.RUNNING,
                ServerOpStatus.PENDING
            )
            items(statusList) { status ->
                FilterChip(
                    selected = statusFilter == status,
                    onClick = {
                        onStatusFilterChange(if (statusFilter == status) null else status)
                    },
                    label = { Text(stringResource(statusDisplayName(status)), style = MaterialTheme.typography.labelMedium) },
                    colors = filterChipColors()
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // 操作类型过滤
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                FilterChip(
                    selected = typeFilter == null,
                    onClick = { onTypeFilterChange(null) },
                    label = { Text(stringResource(R.string.all_types), style = MaterialTheme.typography.labelMedium) },
                    colors = filterChipColors()
                )
            }
            val typeList = listOf(
                ServerOpType.CLOUD_DELETE,
                ServerOpType.BACKUP_UPLOAD,
                ServerOpType.FAVORITE,
                ServerOpType.TAG,
                ServerOpType.HIDE,
                ServerOpType.RENAME_PERSON
            )
            items(typeList) { type ->
                FilterChip(
                    selected = typeFilter == type,
                    onClick = {
                        onTypeFilterChange(if (typeFilter == type) null else type)
                    },
                    label = { Text(stringResource(opTypeDisplayName(type)), style = MaterialTheme.typography.labelMedium) },
                    colors = filterChipColors()
                )
            }
        }
    }
}

@Composable
private fun filterChipColors() = FilterChipDefaults.filterChipColors(
    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
    labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
)

@Composable
private fun OpLogItemCard(
    item: OpLogViewModel.OpLogItem,
    thumbUrl: String?,
    onRetry: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 缩略图
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (thumbUrl != null) {
                    AsyncImage(
                        model = thumbUrl,
                        contentDescription = item.mediaFileName,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        imageVector = opTypeIcon(item.opType),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // 文件名 + 操作 + 状态
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.mediaFileName.ifEmpty { "—" },
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = item.opDescription.asString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(4.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusIndicator(item.status)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = item.statusText.asString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor(item.status),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // 右侧: 时间 / 重试按钮
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = formatTime(item.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )

                if (item.canRetry) {
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedButton(
                        onClick = onRetry,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        modifier = Modifier.height(28.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.retry),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            stringResource(R.string.retry),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusIndicator(status: ServerOpStatus) {
    when (status) {
        ServerOpStatus.SUCCESS -> Icon(
            Icons.Default.CheckCircle,
            contentDescription = stringResource(R.string.status_success),
            tint = Color(0xFF4CAF50),
            modifier = Modifier.size(14.dp)
        )
        ServerOpStatus.PENDING -> Icon(
            Icons.Default.HourglassEmpty,
            contentDescription = stringResource(R.string.status_pending),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(14.dp)
        )
        ServerOpStatus.RUNNING -> {
            val infiniteTransition = rememberInfiniteTransition(label = "running")
            val rotation by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1000, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "rotation"
            )
            Icon(
                Icons.Default.Sync,
                contentDescription = stringResource(R.string.status_running_simple),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(14.dp)
                    .rotate(rotation)
            )
        }
        ServerOpStatus.ERROR -> Icon(
            Icons.Default.Schedule,
            contentDescription = stringResource(R.string.status_error),
            tint = Color(0xFFFF9800),
            modifier = Modifier.size(14.dp)
        )
        ServerOpStatus.FAILED -> Icon(
            Icons.Default.Error,
            contentDescription = stringResource(R.string.status_failed),
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(14.dp)
        )
    }
}

@Composable
private fun statusColor(status: ServerOpStatus): Color {
    return when (status) {
        ServerOpStatus.SUCCESS -> Color(0xFF4CAF50)
        ServerOpStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        ServerOpStatus.RUNNING -> MaterialTheme.colorScheme.primary
        ServerOpStatus.ERROR -> Color(0xFFFF9800)
        ServerOpStatus.FAILED -> MaterialTheme.colorScheme.error
    }
}

private fun opTypeIcon(type: ServerOpType): ImageVector {
    return when (type) {
        ServerOpType.CLOUD_DELETE -> Icons.Default.Delete
        ServerOpType.FAVORITE -> Icons.Default.Favorite
        ServerOpType.RENAME_PERSON -> Icons.Default.Person
        ServerOpType.TAG -> Icons.Default.Label
        ServerOpType.HIDE -> Icons.Default.VisibilityOff
        ServerOpType.BACKUP_UPLOAD -> Icons.Default.CloudUpload
    }
}

@Composable
private fun EmptyState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f),
                modifier = Modifier.size(64.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.no_op_logs),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        }
    }
}

private fun formatTime(timestamp: Long): String {
    val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
