package com.kqstone.mtphotos.ui.gallery

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable
fun VerticalFastScroller(
    scrollFraction: Float,
    isScrollInProgress: Boolean,
    onScrollToFraction: (Float) -> Unit,
    labelProvider: (Float) -> String?,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val hapticFeedback = LocalHapticFeedback.current

    var isDragging by remember { mutableStateOf(false) }
    var isScrollbarVisible by remember { mutableStateOf(false) }
    var containerHeightPx by remember { mutableFloatStateOf(1f) }
    var dragFraction by remember { mutableStateOf<Float?>(null) }

    // Wrap parameter lambdas and values in updated states to prevent stale closure capture
    val currentLabelProvider by rememberUpdatedState(labelProvider)
    val currentOnScrollToFraction by rememberUpdatedState(onScrollToFraction)
    val currentScrollFraction by rememberUpdatedState(scrollFraction)

    // Control visibility fade out
    LaunchedEffect(isScrollInProgress, isDragging) {
        if (isScrollInProgress || isDragging) {
            isScrollbarVisible = true
        } else {
            // Wait 1.5s then fade out
            delay(1500)
            isScrollbarVisible = false
        }
    }

    val animatedAlpha by animateFloatAsState(
        targetValue = if (isScrollbarVisible) 1f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "scrollbar_alpha"
    )

    if (animatedAlpha <= 0f) return

    val thumbHeight = 50.dp
    val thumbHeightPx = with(density) { thumbHeight.toPx() }

    // Deriving display fraction dynamically from drag state or scroll state
    val displayFraction by remember {
        derivedStateOf {
            if (isDragging) dragFraction ?: currentScrollFraction else currentScrollFraction
        }
    }

    // Deriving tooltip label based on display fraction
    val currentLabel by remember {
        derivedStateOf { currentLabelProvider(displayFraction) }
    }

    // Trigger haptic feedback when the label changes during active drag
    LaunchedEffect(currentLabel) {
        if (isDragging && !currentLabel.isNullOrBlank()) {
            hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(200.dp) // Provide ample space for tooltip bubble without blocking touches
            .alpha(animatedAlpha)
            .onGloballyPositioned { coordinates ->
                containerHeightPx = coordinates.size.height.toFloat()
            }
    ) {
        // 1. Touch Target Area & Visual Track/Thumb (aligned to right end)
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(40.dp) // Drag target area width
                .pointerInput(containerHeightPx) {
                    detectVerticalDragGestures(
                        onDragStart = { offset ->
                            isDragging = true
                            val targetFraction = (offset.y / containerHeightPx).coerceIn(0f, 1f)
                            dragFraction = targetFraction
                            currentOnScrollToFraction(targetFraction)
                        },
                        onDragEnd = {
                            isDragging = false
                            dragFraction = null
                        },
                        onDragCancel = {
                            isDragging = false
                            dragFraction = null
                        },
                        onVerticalDrag = { change, _ ->
                            change.consume()
                            val targetFraction = (change.position.y / containerHeightPx).coerceIn(0f, 1f)
                            dragFraction = targetFraction
                            currentOnScrollToFraction(targetFraction)
                        }
                    )
                }
        ) {
            val thumbTopPx = displayFraction * (containerHeightPx - thumbHeightPx).coerceAtLeast(0f)
            val thumbTopDp = with(density) { thumbTopPx.toDp() }

            // Thumb Active state styles
            val thumbWidth by animateDpAsState(
                targetValue = if (isDragging) 8.dp else 4.dp,
                animationSpec = tween(150),
                label = "thumb_width"
            )
            val thumbColor = if (isDragging) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
            }

            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 6.dp)
                    .offset(y = thumbTopDp)
                    .width(thumbWidth)
                    .height(thumbHeight)
                    .background(
                        color = thumbColor,
                        shape = RoundedCornerShape(4.dp)
                    )
            )
        }

        // 2. Label Indicator Tooltip (placed to the left of the 40.dp touch area)
        val thumbTopPx = displayFraction * (containerHeightPx - thumbHeightPx).coerceAtLeast(0f)
        val thumbTopDp = with(density) { thumbTopPx.toDp() }

        AnimatedVisibility(
            visible = isDragging && !currentLabel.isNullOrBlank(),
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 46.dp) // Align to the left of the 40.dp touch area
                .offset(y = thumbTopDp + (thumbHeight / 2) - 20.dp) // center vertically with thumb
        ) {
            Box(
                modifier = Modifier
                    .background(
                        color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.92f),
                        shape = RoundedCornerShape(12.dp)
                    )
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(12.dp)
                    )
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text(
                    text = currentLabel.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.inverseOnSurface
                )
            }
        }
    }
}

@Composable
fun LazyGridVerticalFastScroller(
    gridState: LazyGridState,
    modifier: Modifier = Modifier,
    labelProvider: (Float) -> String? = { null }
) {
    val coroutineScope = rememberCoroutineScope()

    val scrollFraction by remember(gridState) {
        derivedStateOf {
            val totalItems = gridState.layoutInfo.totalItemsCount
            if (totalItems <= 1) return@derivedStateOf 0f
            val visibleItems = gridState.layoutInfo.visibleItemsInfo
            if (visibleItems.isEmpty()) return@derivedStateOf 0f
            val firstVisible = visibleItems.first()
            val firstIndex = firstVisible.index
            val firstOffset = firstVisible.offset.y
            val firstHeight = firstVisible.size.height.coerceAtLeast(1)
            val fraction = firstIndex.toFloat() + (-firstOffset.toFloat() / firstHeight)
            (fraction / totalItems.toFloat()).coerceIn(0f, 1f)
        }
    }

    VerticalFastScroller(
        scrollFraction = scrollFraction,
        isScrollInProgress = gridState.isScrollInProgress,
        onScrollToFraction = { fraction ->
            val totalItems = gridState.layoutInfo.totalItemsCount
            val targetIndex = (fraction * (totalItems - 1)).toInt().coerceIn(0, (totalItems - 1).coerceAtLeast(0))
            coroutineScope.launch {
                gridState.scrollToItem(targetIndex)
            }
        },
        labelProvider = labelProvider,
        modifier = modifier
    )
}
