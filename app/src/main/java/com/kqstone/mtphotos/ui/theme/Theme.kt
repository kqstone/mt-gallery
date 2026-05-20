package com.kqstone.mtphotos.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF2D62EA),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE9EFFF),
    onPrimaryContainer = Color(0xFF0F1B3D),
    secondary = Color(0xFF4C5D8B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0E5F2),
    onSecondaryContainer = Color(0xFF0A122C),
    tertiary = Color(0xFF7E398C),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF9E8FC),
    onTertiaryContainer = Color(0xFF2C0735),
    background = Color(0xFFF8F9FC),
    onBackground = Color(0xFF191C20),
    surface = Color.White,
    onSurface = Color(0xFF191C20),
    surfaceVariant = Color(0xFFE2E4EB),
    onSurfaceVariant = Color(0xFF45474F),
    outline = Color(0xFF75777F)
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF7CA3FF),
    onPrimary = Color(0xFF002D80),
    primaryContainer = Color(0xFF0D41BA),
    onPrimaryContainer = Color(0xFFE9EFFF),
    secondary = Color(0xFFBAC5E8),
    onSecondary = Color(0xFF1C2D5A),
    secondaryContainer = Color(0xFF334471),
    onSecondaryContainer = Color(0xFFE0E5F2),
    tertiary = Color(0xFFEDACF7),
    onTertiary = Color(0xFF4B0058),
    tertiaryContainer = Color(0xFF651B73),
    onTertiaryContainer = Color(0xFFF9E8FC),
    background = Color(0xFF0F1115),
    onBackground = Color(0xFFE2E2E6),
    surface = Color(0xFF15181E),
    onSurface = Color(0xFFE2E2E6),
    surfaceVariant = Color(0xFF232832),
    onSurfaceVariant = Color(0xFFC5C6D0),
    outline = Color(0xFF8F909A)
)

@Composable
fun MTGalleryTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}

