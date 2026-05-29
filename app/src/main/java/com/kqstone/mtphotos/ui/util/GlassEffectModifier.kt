package com.kqstone.mtphotos.ui.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Global CompositionLocal to share the [HazeState] from the host screen
 * down to deep UI components (like search bars) without explicit threading.
 */
val LocalHazeState = staticCompositionLocalOf<HazeState?> { null }

// ──────────────────────────────────────────────────────────
//  Reusable Haze Modifier Extensions
// ──────────────────────────────────────────────────────────

/**
 * Marks this composable as a Haze blur **source** (the content that will
 * be blurred behind glass-effect consumers).
 *
 * Reads [LocalHazeState]; if no state is provided the modifier is a no-op.
 * Use this on top-level content containers (e.g. `PullToRefreshBox`,
 * scrollable `Column` / `LazyColumn`) inside each screen.
 */
@Composable
fun Modifier.hazeContentSource(): Modifier {
    val hazeState = LocalHazeState.current ?: return this
    return this.hazeSource(state = hazeState)
}

/**
 * Applies a frosted-glass background suitable for **search bars**.
 *
 * Reads [LocalHazeState]; when available, delegates to [frostedGlassEffect]
 * with search-bar-tuned defaults (`showTopDivider = false`, `tintAlpha = 0.25f`).
 * Falls back to a semi-transparent [surfaceVariant] background when Haze is
 * not available.
 *
 * @param fallbackAlphaOverride  Alpha for the non-blur fallback background.
 */
@Composable
fun Modifier.frostedGlassSearchBar(
    fallbackAlphaOverride: Float = 0.4f
): Modifier {
    val hazeState = LocalHazeState.current
    return if (hazeState != null) {
        this.frostedGlassEffect(
            state = hazeState,
            showTopDivider = false
        )
    } else {
        val fallbackColor = MaterialTheme.colorScheme.surfaceVariant
            .copy(alpha = fallbackAlphaOverride)
        this.background(color = fallbackColor)
    }
}

// ──────────────────────────────────────────────────────────
//  Haze Frosted-Glass Blur
// ──────────────────────────────────────────────────────────

/**
 * Applies a frosted-glass (Haze) blur effect to this composable.
 *
 * Encapsulates the full [hazeEffect] + optional top-edge divider line
 * so that the same look used on the bottom NavigationBar can be applied
 * to **any** composable (e.g. top bars, bottom sheets, floating panels).
 *
 * The caller still needs to mark the content area with `Modifier.hazeSource(state)`.
 *
 * @param state          The shared [HazeState] between source and effect.
 * @param tintAlpha      Alpha of the surface tint overlay (default 0.58f).
 * @param fallbackAlpha  Alpha used when blur is not supported (default 0.92f).
 * @param blurRadius     Radius of the Gaussian blur (default 24.dp).
 * @param noiseFactor    Amount of noise texture (default 0f = none).
 * @param inputScale     Down-scale factor for the blur input (default 0.5f).
 * @param showTopDivider Whether to draw a thin divider at the top edge (default true).
 * @param dividerAlpha   Alpha of the top divider line (default 0.3f).
 */
@OptIn(ExperimentalHazeApi::class)
@Composable
fun Modifier.frostedGlassEffect(
    state: HazeState,
    tintAlpha: Float = 0.70f,
    fallbackAlpha: Float = 0.92f,
    blurRadius: Dp = 24.dp,
    noiseFactor: Float = 0f,
    inputScale: Float = 0.5f,
    showTopDivider: Boolean = true,
    dividerAlpha: Float = 0.3f
): Modifier {
    val surfaceColor = MaterialTheme.colorScheme.surface
    val tintColor = surfaceColor.copy(alpha = tintAlpha)
    val fallbackColor = surfaceColor.copy(alpha = fallbackAlpha)
    val outlineColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = dividerAlpha)

    var result = this
        .hazeEffect(
            state = state,
            style = HazeStyle(
                backgroundColor = surfaceColor,
                tints = listOf(HazeTint(tintColor)),
                blurRadius = blurRadius,
                noiseFactor = noiseFactor,
                fallbackTint = HazeTint(fallbackColor)
            )
        ) {
            this.inputScale = HazeInputScale.Fixed(inputScale)
        }

    if (showTopDivider) {
        result = result.drawBehind {
            drawLine(
                color = outlineColor,
                start = Offset(0f, 0f),
                end = Offset(size.width, 0f),
                strokeWidth = 1f
            )
        }
    }

    return result
}

// ──────────────────────────────────────────────────────────
//  Gradient Shadow
// ──────────────────────────────────────────────────────────


/** Direction of the gradient shadow. */
enum class GradientDirection {
    /** Dark at top, fading to transparent at bottom (for top bars). */
    TopToBottom,
    /** Dark at bottom, fading to transparent at top (for bottom overlays). */
    BottomToTop
}

/**
 * Applies a gradient shadow overlay to this composable's background.
 *
 * Typically used on top-bar containers to create a "frosted / dark veil"
 * effect that deepens as the user scrolls the content underneath.
 *
 * @param alpha  Current intensity (0f = fully transparent, 1f = fully opaque).
 *               Usually driven by `scrollAlpha` from [rememberScrollAlpha].
 * @param maxAlpha  Peak opacity of the shadow colour when [alpha] is 1f.
 * @param color  Base shadow colour (default [Color.Black]).
 * @param direction  Gradient direction (default [GradientDirection.TopToBottom]).
 */
fun Modifier.gradientShadow(
    alpha: Float,
    maxAlpha: Float = 0.76f,
    color: Color = Color.Black,
    direction: GradientDirection = GradientDirection.TopToBottom
): Modifier {
    val opaqueColor = color.copy(alpha = maxAlpha * alpha)
    val transparentColor = color.copy(alpha = 0f)
    val colors = when (direction) {
        GradientDirection.TopToBottom -> listOf(opaqueColor, transparentColor)
        GradientDirection.BottomToTop -> listOf(transparentColor, opaqueColor)
    }
    return this.background(Brush.verticalGradient(colors = colors))
}

/**
 * Composable-only variant of [gradientShadow] that uses [remember] to
 * cache the [Brush] instance across recompositions for better performance.
 *
 * Use this when calling from a `@Composable` context; use the plain
 * [Modifier.gradientShadow] extension when you only have a [Modifier] chain.
 */
@Composable
fun Modifier.gradientShadowCached(
    alpha: Float,
    maxAlpha: Float = 0.76f,
    color: Color = Color.Black,
    direction: GradientDirection = GradientDirection.TopToBottom
): Modifier {
    val brush = remember(alpha, maxAlpha, color, direction) {
        val opaqueColor = color.copy(alpha = maxAlpha * alpha)
        val transparentColor = color.copy(alpha = 0f)
        val colors = when (direction) {
            GradientDirection.TopToBottom -> listOf(opaqueColor, transparentColor)
            GradientDirection.BottomToTop -> listOf(transparentColor, opaqueColor)
        }
        Brush.verticalGradient(colors = colors)
    }
    return this.background(brush)
}

/**
 * Side-effect that controls the status-bar icon appearance (light vs dark).
 *
 * When a dark overlay (gradient shadow) is visible over the status bar area
 * the icons must switch to light (white) so they remain legible.
 *
 * @param darkOverlay  `true` when a dark overlay is active (→ light/white icons).
 *                     `false` falls back to the system theme default.
 */
@Composable
fun StatusBarStyleEffect(darkOverlay: Boolean) {
    val view = LocalView.current
    val darkTheme = isSystemInDarkTheme()

    if (!view.isInEditMode) {
        val isLightStatusBars = if (darkOverlay) false else !darkTheme
        SideEffect {
            val window = view.context.findActivity()?.window
            if (window != null) {
                val insetsController = WindowCompat.getInsetsController(window, view)
                insetsController.isAppearanceLightStatusBars = isLightStatusBars
            }
        }
    }
}

/**
 * Walk the [ContextWrapper] chain to find the hosting [Activity], if any.
 */
internal fun Context.findActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}
