package com.kqstone.mtphotos

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.kqstone.mtphotos.ui.navigation.AppNavigation
import com.kqstone.mtphotos.ui.theme.MTGalleryTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        enableHighRefreshRate()
        setContent {
            MTGalleryTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavigation()
                }
            }
        }
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
