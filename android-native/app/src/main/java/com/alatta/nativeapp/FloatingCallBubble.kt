package com.alatta.nativeapp

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private object LastCallBubblePosition { var horizontal = 0f; var vertical = .35f }

/** A chat-head style host. Physical coordinates keep dragging correct in Arabic/RTL. */
@Composable
internal fun FloatingCallBubble(minimized: Boolean = true, content: @Composable (Modifier) -> Unit) {
    var horizontal by rememberSaveable { mutableFloatStateOf(LastCallBubblePosition.horizontal) }
    var vertical by rememberSaveable { mutableFloatStateOf(LastCallBubblePosition.vertical) }
    var dragging by remember { mutableStateOf(false) }
    var dragPosition by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    SideEffect { LastCallBubblePosition.horizontal = horizontal; LastCallBubblePosition.vertical = vertical }
    Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val bounds = with(density) { MiniVideoBounds.inViewport(
                IntSize(maxWidth.roundToPx(), maxHeight.roundToPx()), IntSize(62.dp.roundToPx(), 62.dp.roundToPx()),
                8.dp.toPx(), 82.dp.toPx()) }
            val target = Offset(bounds.left + (bounds.right - bounds.left) * horizontal,
                bounds.top + (bounds.bottom - bounds.top) * vertical)
            val animated = remember { Animatable(target, Offset.VectorConverter) }
            LaunchedEffect(target, dragging) {
                if (!dragging) animated.animateTo(target, spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow))
            }
            val position = bounds.clamp(if (dragging) dragPosition else animated.value)
            fun settle() {
                val clamped = bounds.clamp(dragPosition)
                scope.launch {
                    animated.snapTo(clamped)
                    horizontal = if (clamped.x < (bounds.left + bounds.right) / 2f) 0f else 1f
                    vertical = if (bounds.bottom > bounds.top) ((clamped.y - bounds.top) / (bounds.bottom - bounds.top)).coerceIn(0f, 1f) else 0f
                    dragging = false
                }
            }
            val host = if (!minimized) Modifier.fillMaxSize() else Modifier.align(AbsoluteAlignment.TopLeft)
                .absoluteOffset { IntOffset(position.x.roundToInt(), position.y.roundToInt()) }.size(62.dp)
                .pointerInput(bounds) {
                    detectDragGestures(
                        onDragStart = { dragPosition = bounds.clamp(animated.value); dragging = true },
                        onDragEnd = { settle() }, onDragCancel = { settle() },
                        onDrag = { change, delta -> change.consume(); dragPosition = bounds.clamp(dragPosition + delta) }
                    )
                }
            content(host)
        }
    }
}
