package com.kqstone.mtphotos.ui.util

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

enum class MediaPermissionStatus {
    FULL,
    PARTIAL,
    DENIED
}

/**
 * 统一权限请求工具类
 */
object PermissionHelper {

    /**
     * 获取读取媒体文件所需的权限列表
     */
    fun getMediaPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO
            )
        } else {
            // Android 12 及以下
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    /**
     * 获取通知权限
     */
    fun getNotificationPermission(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            emptyArray() // Android 12 及以下不需要运行时请求
        }
    }

    /**
     * 检查是否已有媒体读取权限
     */
    fun hasMediaPermissions(context: android.content.Context): Boolean {
        return getMediaPermissionStatus(context) != MediaPermissionStatus.DENIED
    }

    fun getMediaPermissionStatus(context: android.content.Context): MediaPermissionStatus {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasImages = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_MEDIA_IMAGES
            ) == PackageManager.PERMISSION_GRANTED
            val hasVideo = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_MEDIA_VIDEO
            ) == PackageManager.PERMISSION_GRANTED
            if (hasImages && hasVideo) return MediaPermissionStatus.FULL

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val hasPartial = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
                ) == PackageManager.PERMISSION_GRANTED
                if (hasPartial) return MediaPermissionStatus.PARTIAL
            }
            return MediaPermissionStatus.DENIED
        }

        return if (
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            MediaPermissionStatus.FULL
        } else {
            MediaPermissionStatus.DENIED
        }
    }

    /**
     * 检查是否已有通知权限
     */
    fun hasNotificationPermission(context: android.content.Context): Boolean {
        val perms = getNotificationPermission()
        if (perms.isEmpty()) return true
        return perms.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * 检查是否有所有文件访问权限（直接删除模式需要）
     */
    fun hasManageStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else true
    }

    /**
     * 获取跳转至所有文件访问权限设置页的 Intent
     */
    fun getManageStorageIntent(context: android.content.Context): Intent {
        return Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = Uri.parse("package:${context.packageName}")
        }
    }
}

/**
 * Compose 权限请求封装 — 自动弹出请求。
 * 会在首次进入时自动请求权限。
 * 使用方式：
 * ```
 * RequestMediaPermissions { granted ->
 *     if (granted) { ... }
 * }
 * ```
 */
@Composable
fun RequestMediaPermissions(
    onResult: (Boolean) -> Unit
) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(PermissionHelper.hasMediaPermissions(context))
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val granted = PermissionHelper.hasMediaPermissions(context)
        hasPermission = granted
        onResult(granted)
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) {
            launcher.launch(PermissionHelper.getMediaPermissions())
        } else {
            onResult(true)
        }
    }
}

/**
 * Compose 通知权限请求
 */
@Composable
fun RequestNotificationPermission(
    onResult: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val perms = PermissionHelper.getNotificationPermission()

    if (perms.isEmpty()) {
        // Android 12 及以下，不需要请求
        LaunchedEffect(Unit) { onResult(true) }
        return
    }

    var hasPermission by remember {
        mutableStateOf(PermissionHelper.hasNotificationPermission(context))
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        hasPermission = allGranted
        onResult(allGranted)
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) {
            launcher.launch(perms)
        } else {
            onResult(true)
        }
    }
}

/**
 * 按需请求媒体权限的 Composable。
 * 不会自动弹出，而是由调用者通过 requestPermission lambda 手动触发。
 *
 * 使用方式：
 * ```
 * MediaPermissionRequester { hasPermission, requestPermission ->
 *     Button(onClick = {
 *         if (!hasPermission) requestPermission()
 *         else doSomething()
 *     }) { ... }
 * }
 * ```
 */
@Composable
fun MediaPermissionRequester(
    content: @Composable (hasPermission: Boolean, requestPermission: () -> Unit) -> Unit
) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(PermissionHelper.hasMediaPermissions(context))
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        hasPermission = PermissionHelper.hasMediaPermissions(context)
    }

    content(hasPermission) {
        launcher.launch(PermissionHelper.getMediaPermissions())
    }
}

/**
 * 应用启动时的权限 Gate。
 * 自动请求媒体权限 + 通知权限。
 * - 授权后显示 [content]
 * - 拒绝后显示说明页面，含重新授权和前往设置按钮，也可选择"仅云端模式"跳过
 */
@Composable
fun AppPermissionGate(
    onContinueCloudOnly: () -> Unit,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current

    var mediaGranted by remember {
        mutableStateOf(PermissionHelper.hasMediaPermissions(context))
    }
    var notificationGranted by remember {
        mutableStateOf(PermissionHelper.hasNotificationPermission(context))
    }
    var permissionRequested by remember { mutableStateOf(false) }
    var skipped by remember { mutableStateOf(false) }

    // 合并所有需要请求的权限
    val allPermissions = remember {
        PermissionHelper.getMediaPermissions() + PermissionHelper.getNotificationPermission()
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        permissionRequested = true
        mediaGranted = PermissionHelper.hasMediaPermissions(context)
        notificationGranted = PermissionHelper.getNotificationPermission().let { perms ->
            perms.isEmpty() || perms.all { results[it] == true }
        }
    }

    // 首次进入自动请求
    LaunchedEffect(Unit) {
        if (!mediaGranted) {
            launcher.launch(allPermissions)
        }
    }

    if (mediaGranted || skipped) {
        // 权限已授予 或 用户跳过，显示主内容
        content()
    } else if (permissionRequested) {
        // 权限被拒绝，显示说明页面
        PermissionDeniedScreen(
            onRetry = { launcher.launch(allPermissions) },
            onOpenSettings = {
                val intent = android.content.Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.fromParts("package", context.packageName, null)
                )
                context.startActivity(intent)
            },
            onSkip = {
                skipped = true
                onContinueCloudOnly()
            }
        )
    }
    // else: 正在等待权限请求结果，什么都不显示
}

@Composable
private fun PermissionDeniedScreen(
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    onSkip: () -> Unit
) {
    androidx.compose.foundation.layout.Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.PhotoLibrary,
            contentDescription = null,
            modifier = Modifier
                .height(72.dp)
                .width(72.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "需要媒体访问权限",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "MT Gallery 需要访问您设备上的照片和视频，以便在本地和云端之间管理和备份您的媒体文件。",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(32.dp))
        Button(onClick = onRetry) {
            Text("授予权限")
        }
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedButton(onClick = onOpenSettings) {
            Text("前往系统设置")
        }
        Spacer(modifier = Modifier.height(12.dp))
        TextButton(onClick = onSkip) {
            Text("跳过，仅使用云端模式")
        }
    }
}
