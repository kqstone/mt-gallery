package com.kqstone.mtphotos.ui.util

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The fixed height used for the custom top bar across all screens. */
private val TopBarHeight = 56.dp

/**
 * Holds the scroll-driven alpha and the calculated top bar height
 * (topBarHeight + statusBar inset) so each screen doesn't have to
 * repeat the same 15-line boilerplate.
 */
@Stable
data class ScrollAlphaState(
    /** 0f when at the top, 1f once scrolled. Animated over 300 ms. */
    val scrollAlpha: Float,
    /** [TopBarHeight] + status bar inset – use as `contentPadding.top`. */
    val topBarHeight: Dp
)

/**
 * Derives a [ScrollAlphaState] from any scrollable list's first-visible-item info.
 *
 * Usage:
 * ```
 * val listState = rememberLazyListState()          // or rememberLazyGridState()
 * val scrollState = rememberScrollAlpha(
 *     firstVisibleItemIndex = { listState.firstVisibleItemIndex },
 *     firstVisibleItemScrollOffset = { listState.firstVisibleItemScrollOffset }
 * )
 * ```
 */
@Composable
fun rememberScrollAlpha(
    firstVisibleItemIndex: () -> Int,
    firstVisibleItemScrollOffset: () -> Int,
    animDurationMillis: Int = 300
): ScrollAlphaState {
    val hasScrolled by remember {
        derivedStateOf {
            firstVisibleItemIndex() > 0 || firstVisibleItemScrollOffset() > 0
        }
    }
    val scrollAlpha by animateFloatAsState(
        targetValue = if (hasScrolled) 1f else 0f,
        animationSpec = tween(durationMillis = animDurationMillis),
        label = "actionBarAlpha"
    )
    val statusBarHeight = stableStatusBarHeight()
    val topBarHeight = TopBarHeight + statusBarHeight

    return ScrollAlphaState(scrollAlpha = scrollAlpha, topBarHeight = topBarHeight)
}

