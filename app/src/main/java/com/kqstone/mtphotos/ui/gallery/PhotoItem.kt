package com.kqstone.mtphotos.ui.gallery

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.Canvas
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayCircleFilled
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Scale
import com.kqstone.mtphotos.data.local.db.BackupStatus
import com.kqstone.mtphotos.data.local.db.SyncStatus
import com.kqstone.mtphotos.data.model.UnifiedPhotoItem

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PhotoThumbnail(
    photo: UnifiedPhotoItem,
    thumbUrl: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    isSelected: Boolean = false,
    isSelectionMode: Boolean = false,
    showSyncStatus: Boolean = true,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .then(
                if (isSelected) {
                    Modifier.border(3.dp, MaterialTheme.colorScheme.primary)
                } else {
                    Modifier
                }
            )
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(thumbUrl)
                .size(256)
                .scale(Scale.FILL)
                .apply {
                    if (!photo.md5.isNullOrEmpty()) {
                        diskCacheKey("${photo.md5}_256")
                        memoryCacheKey("${photo.md5}_256")
                    }
                }
                .crossfade(false)
                .build(),
            contentDescription = photo.fileName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        val isVideo = photo.isPlayableMedia()

        if (isVideo) {
            Icon(
                imageVector = Icons.Default.PlayCircleFilled,
                contentDescription = "Video",
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(4.dp)
            )
        }

        // 同步状态图标 — 右上角（选择模式时隐藏，选择图标优先）
        if (showSyncStatus && !isSelectionMode) {
            SyncStatusIcon(
                syncStatus = photo.syncStatus,
                backupStatus = photo.backupStatus,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(3.dp)
            )
        }

        // 选择模式图标
        if (isSelectionMode) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
            ) {
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "已选中",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.Circle,
                        contentDescription = "未选中",
                        tint = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }

        if (isSelected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
            )
        }
    }
}

/**
 * 同步状态图标组件
 * - ☁️ CLOUD_ONLY → 白色云轮廓图标（内部半透明黑底）
 * - ✅ SYNCED → 白色 CloudDone 图标（内部半透明黑底）
 * - ⬆️ LOCAL_ONLY + 待备份 → 白色云 + 虚线向上箭头
 * - ⏫ UPLOADING → 白色云 + 动态向上箭头
 * - 其他 LOCAL_ONLY → 不显示
 */
@Composable
private fun SyncStatusIcon(
    syncStatus: SyncStatus,
    backupStatus: BackupStatus,
    modifier: Modifier = Modifier
) {
    val isUploading = backupStatus == BackupStatus.UPLOADING
    val isPendingBackup = syncStatus == SyncStatus.LOCAL_ONLY &&
            backupStatus in listOf(BackupStatus.NOT_STARTED, BackupStatus.FAILED)

    when {
        isUploading -> {
            CloudWithArrowIcon(
                dashed = false,
                animated = true,
                contentDescription = "备份中",
                modifier = modifier
            )
        }
        syncStatus == SyncStatus.SYNCED -> {
            CloudIconWithBackground(
                icon = Icons.Outlined.CloudDone,
                contentDescription = "已同步",
                modifier = modifier
            )
        }
        syncStatus == SyncStatus.CLOUD_ONLY -> {
            CloudIconWithBackground(
                icon = Icons.Outlined.Cloud,
                contentDescription = "仅云端",
                modifier = modifier
            )
        }
        isPendingBackup -> {
            CloudWithArrowIcon(
                dashed = true,
                animated = false,
                contentDescription = "待备份",
                modifier = modifier
            )
        }
        // LOCAL_ONLY with other backupStatus → no icon
    }
}

/**
 * Material 云图标 + 半透明黑色内部填充
 */
@Composable
private fun CloudIconWithBackground(
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier
) {
    val iconSize = 15.dp
    Box(
        modifier = modifier.size(iconSize),
        contentAlignment = Alignment.Center
    ) {
        // 底层：填充云内部为半透明黑色
        Icon(
            imageVector = Icons.Filled.Cloud,
            contentDescription = null,
            tint = Color.Black.copy(alpha = 0.45f),
            modifier = Modifier.size(iconSize)
        )
        // 上层：白色图标
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White.copy(alpha = 0.75f),
            modifier = Modifier.size(iconSize)
        )
    }
}

/**
 * 自定义云 + 箭头图标
 * @param dashed 箭头是否为虚线（待备份状态）
 * @param animated 箭头是否有向上平移动画（备份中状态）
 */
@Composable
private fun CloudWithArrowIcon(
    dashed: Boolean,
    animated: Boolean,
    contentDescription: String,
    modifier: Modifier = Modifier
) {
    val iconSize = 14.dp

    // 动画：箭头向上平移
    val offsetFraction = if (animated) {
        val transition = rememberInfiniteTransition(label = "upload_arrow")
        val fraction by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "arrow_offset"
        )
        fraction
    } else {
        0f
    }

    Box(
        modifier = modifier.size(iconSize),
        contentAlignment = Alignment.Center
    ) {
        // 底层：填充云内部为半透明黑色
        Icon(
            imageVector = Icons.Filled.Cloud,
            contentDescription = null,
            tint = Color.Black.copy(alpha = 0.45f),
            modifier = Modifier.size(iconSize)
        )
        // 中层：白色云轮廓
        Icon(
            imageVector = Icons.Outlined.Cloud,
            contentDescription = contentDescription,
            tint = Color.White.copy(alpha = 0.75f),
            modifier = Modifier.size(iconSize)
        )
        // 顶层：Canvas 绘制箭头
        Canvas(modifier = Modifier.size(iconSize)) {
            val w = size.width
            val h = size.height

            val centerX = w * 0.5f
            val arrowTipY = h * 0.35f
            val arrowBottomY = h * 0.65f
            val arrowLength = arrowBottomY - arrowTipY
            val arrowHeadLen = w * 0.10f
            val strokeW = w * 0.07f

            // 动画偏移
            val maxShift = arrowLength * 0.3f
            val offsetY = -offsetFraction * maxShift

            // 动画透明度（淡入淡出，避免跳变）
            val alpha = if (animated) {
                when {
                    offsetFraction < 0.15f -> offsetFraction / 0.15f
                    offsetFraction > 0.75f -> (1f - offsetFraction) / 0.25f
                    else -> 1f
                }
            } else 1f

            // 裁剪到云内部区域，防止箭头溢出
            clipRect(
                left = w * 0.12f,
                top = h * 0.22f,
                right = w * 0.88f,
                bottom = h * 0.78f
            ) {
                val tipY = arrowTipY + offsetY
                val bottomY = arrowBottomY + offsetY

                val arrowPath = Path().apply {
                    // 竖线
                    moveTo(centerX, bottomY)
                    lineTo(centerX, tipY)
                    // 箭头头部（V 形）
                    moveTo(centerX - arrowHeadLen, tipY + arrowHeadLen)
                    lineTo(centerX, tipY)
                    lineTo(centerX + arrowHeadLen, tipY + arrowHeadLen)
                }

                val pathEffect = if (dashed) {
                    PathEffect.dashPathEffect(
                        floatArrayOf(strokeW * 2.5f, strokeW * 2f)
                    )
                } else null

                drawPath(
                    path = arrowPath,
                    color = Color.White.copy(alpha = 0.75f * alpha),
                    style = Stroke(
                        width = strokeW,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                        pathEffect = pathEffect
                    )
                )
            }
        }
    }
}
