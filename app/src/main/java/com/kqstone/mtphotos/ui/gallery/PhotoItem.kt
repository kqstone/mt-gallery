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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayCircleFilled
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.shadow
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
                .crossfade(true)
                .build(),
            contentDescription = photo.fileName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        val isVideo = photo.isVideo()

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
 * - ☁️ CLOUD_ONLY → 云图标（蓝色）
 * - 📱 LOCAL_ONLY → 手机图标（橙色）
 * - ✅ SYNCED → 云端已同步图标（绿色）
 * - ⏫ UPLOADING → 上传中（旋转动画）
 */
@Composable
private fun SyncStatusIcon(
    syncStatus: SyncStatus,
    backupStatus: BackupStatus,
    modifier: Modifier = Modifier
) {
    // 上传中有旋转动画
    val isUploading = backupStatus == BackupStatus.UPLOADING

    val (icon, tint, description) = when {
        isUploading -> Triple(
            Icons.Outlined.CloudUpload,
            Color(0xFF2196F3),  // 蓝色
            "上传中"
        )
        syncStatus == SyncStatus.SYNCED -> Triple(
            Icons.Outlined.CloudDone,
            Color(0xFF4CAF50),  // 绿色
            "已同步"
        )
        syncStatus == SyncStatus.CLOUD_ONLY -> Triple(
            Icons.Outlined.Cloud,
            Color(0xFF2196F3),  // 蓝色
            "仅云端"
        )
        syncStatus == SyncStatus.LOCAL_ONLY -> Triple(
            Icons.Outlined.PhoneAndroid,
            Color(0xFFFF9800),  // 橙色
            "仅本地"
        )
        else -> return // 不显示图标
    }

    Box(
        modifier = modifier
            .size(20.dp)
            .shadow(2.dp, CircleShape)
            .background(Color.Black.copy(alpha = 0.45f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (isUploading) {
            val infiniteTransition = rememberInfiniteTransition(label = "upload_rotate")
            val rotationAngle by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1500, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "rotation"
            )
            Icon(
                imageVector = icon,
                contentDescription = description,
                tint = tint,
                modifier = Modifier
                    .size(14.dp)
                    .graphicsLayer { rotationZ = rotationAngle }
            )
        } else {
            Icon(
                imageVector = icon,
                contentDescription = description,
                tint = tint,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}
