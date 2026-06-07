package com.kqstone.mtphotos

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.kqstone.mtphotos.ui.navigation.AppNavigation
import com.kqstone.mtphotos.ui.theme.MTGalleryTheme
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class MainActivity : ComponentActivity() {
    private val _intentFlow = MutableSharedFlow<Intent>(extraBufferCapacity = 64)
    val intentFlow = _intentFlow.asSharedFlow()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        intent?.let { _intentFlow.tryEmit(it) }
        enableEdgeToEdge()
        enableHighRefreshRate()

        // 注册最低优先级的返回键回调（在 setContent 之前注册，
        // 使所有 Compose BackHandler 和 Navigation 回调拥有更高优先级）。
        // 当导航栈为空、无 BackHandler 处理时，此回调兜底：
        // 将 task 移至后台而非 finish Activity，避免 ChooserActivity 等
        // 外部 Activity 关闭后 isTaskRoot() 短暂失效导致的意外销毁。
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                moveTaskToBack(true)
            }
        })

        setContent {
            MTGalleryTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    com.kqstone.mtphotos.ui.util.ProvideStableStatusBarHeight {
                        AppNavigation()
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        _intentFlow.tryEmit(intent)
    }

    private fun enableHighRefreshRate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val display = display ?: return
            val highestMode = display.supportedModes
                .maxByOrNull { it.refreshRate } ?: return
            if (highestMode.refreshRate > display.mode.refreshRate) {
                val params = window.attributes
                params.preferredDisplayModeId = highestMode.modeId
                window.attributes = params
            }
        }
    }
}
