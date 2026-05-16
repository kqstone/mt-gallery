package com.kqstone.mtphotos.ui.util

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * 统一权限请求工具类
 */
object PermissionHelper {

    /**
     * 获取读取媒体文件所需的权限列表
     */
    fun getMediaPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
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
        return getMediaPermissions().all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
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
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        hasPermission = allGranted
        onResult(allGranted)
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
    ) { permissions ->
        hasPermission = permissions.values.all { it }
    }

    content(hasPermission) {
        launcher.launch(PermissionHelper.getMediaPermissions())
    }
}
