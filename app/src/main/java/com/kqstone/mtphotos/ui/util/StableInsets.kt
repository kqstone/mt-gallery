package com.kqstone.mtphotos.ui.util

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Provides a **stable** status-bar height that never drops to 0 when the
 * media viewer hides system bars with `insetsController.hide()`.
 *
 * Compose's `WindowInsets.statusBars` is reactive: it reports 0 whenever
 * the system bars are hidden, causing every screen on the back stack to
 * recompose with incorrect padding. This CompositionLocal caches the
 * last positive value so that screens behind the viewer keep their layout
 * intact.
 *
 * Provide a value via [ProvideStableStatusBarHeight] at the composition
 * root (e.g. inside `setContent { … }`), then read with
 * [stableStatusBarHeight] or apply via [Modifier.stableStatusBarsPadding].
 */
val LocalStableStatusBarHeight = compositionLocalOf { 0.dp }

/**
 * Reads `WindowInsets.statusBars`, caches the highest seen value, and
 * provides it through [LocalStableStatusBarHeight].
 *
 * Place this composable once at the composition root (e.g. wrapping
 * `AppNavigation()`).
 */
@Composable
fun ProvideStableStatusBarHeight(content: @Composable () -> Unit) {
    val currentHeight = WindowInsets.statusBars
        .asPaddingValues()
        .calculateTopPadding()

    // Cache: keep the last positive value so it never goes to 0
    // when immersive mode temporarily clears the insets.
    var cached by remember { mutableStateOf(currentHeight) }
    if (currentHeight > 0.dp) {
        cached = currentHeight
    }

    androidx.compose.runtime.CompositionLocalProvider(
        LocalStableStatusBarHeight provides cached,
        content = content
    )
}

/**
 * Returns the stable (never-zero) status bar height.
 *
 * Equivalent to `LocalStableStatusBarHeight.current`, but reads as a
 * self-documenting helper call.
 */
@Composable
fun stableStatusBarHeight(): Dp = LocalStableStatusBarHeight.current

/**
 * Adds top padding equal to the stable status-bar height.
 *
 * Drop-in replacement for `Modifier.statusBarsPadding()` that is
 * immune to transient zero-inset values caused by immersive mode.
 */
@Composable
fun Modifier.stableStatusBarsPadding(): Modifier {
    val height = LocalStableStatusBarHeight.current
    return this.padding(PaddingValues(top = height))
}
