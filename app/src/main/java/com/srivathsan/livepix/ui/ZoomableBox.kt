package com.srivathsan.livepix.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.launch
import kotlin.math.abs

@Stable
class ZoomState {
    var scale by mutableStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)
    /** Swipe-down-to-dismiss progress in px. */
    var dismissY by mutableStateOf(0f)
    val zoomed get() = scale > 1.01f
}

/**
 * Photo container with Photos-style gestures: pinch and double-tap to zoom, pan while zoomed,
 * drag down to dismiss, drag up for info. Horizontal swipes are left to the pager when not zoomed.
 */
@Composable
fun ZoomableBox(
    zoom: ZoomState,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onLongPressEnd: () -> Unit,
    onDismiss: () -> Unit,
    onSwipeUp: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    var size by remember { mutableStateOf(IntSize.Zero) }
    val tap by rememberUpdatedState(onTap)
    val longPress by rememberUpdatedState(onLongPress)
    val longPressEnd by rememberUpdatedState(onLongPressEnd)
    val dismiss by rememberUpdatedState(onDismiss)
    val swipeUp by rememberUpdatedState(onSwipeUp)

    fun clampOffset(o: Offset, s: Float): Offset {
        val maxX = (size.width * (s - 1)) / 2
        val maxY = (size.height * (s - 1)) / 2
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    fun animateTo(targetScale: Float, targetOffset: Offset) {
        val startScale = zoom.scale
        val startOffset = zoom.offset
        scope.launch {
            val a = Animatable(0f)
            a.animateTo(1f, spring(dampingRatio = 0.85f, stiffness = 400f)) {
                zoom.scale = startScale + (targetScale - startScale) * value
                zoom.offset = startOffset + (targetOffset - startOffset) * value
            }
        }
    }

    Box(
        modifier.fillMaxSize().onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { tap() },
                    onDoubleTap = { p ->
                        if (zoom.zoomed) animateTo(1f, Offset.Zero)
                        else {
                            val s = 2.5f
                            val center = Offset(size.width / 2f, size.height / 2f)
                            animateTo(s, clampOffset((center - p) * (s - 1), s))
                        }
                    },
                    onLongPress = { longPress() },
                    onPress = {
                        tryAwaitRelease()
                        longPressEnd()
                    },
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var mode = 0 // 0 undecided, 1 zoom/pan, 2 dismiss/info drag, -1 not ours
                    var totalDrag = Offset.Zero
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val pressed = event.changes.count { it.pressed }
                        if (pressed >= 2) mode = 1
                        when (mode) {
                            1 -> {
                                val z = event.calculateZoom()
                                val pan = event.calculatePan()
                                val centroid = event.calculateCentroid()
                                val newScale = (zoom.scale * z).coerceIn(0.8f, 6f)
                                val center = Offset(size.width / 2f, size.height / 2f)
                                val focal = centroid - center
                                val newOffset = (zoom.offset - focal) * (newScale / zoom.scale) + focal + pan
                                zoom.scale = newScale
                                zoom.offset = if (newScale >= 1f) clampOffset(newOffset, newScale) else newOffset
                                event.changes.forEach { it.consume() }
                            }
                            0 -> if (pressed == 1) {
                                totalDrag += event.changes.first().positionChange()
                                if (zoom.zoomed) {
                                    mode = 1
                                } else if (totalDrag.getDistance() > 18f) {
                                    mode = if (abs(totalDrag.y) > abs(totalDrag.x) * 1.4f) 2 else -1
                                }
                            }
                            2 -> {
                                val dy = event.changes.first().positionChange().y
                                zoom.dismissY += dy
                                event.changes.forEach { it.consume() }
                            }
                        }
                    } while (event.changes.any { it.pressed })
                    when {
                        mode == 2 && zoom.dismissY > size.height * 0.12f -> dismiss()
                        mode == 2 && zoom.dismissY < -size.height * 0.08f -> { swipeUp(); zoom.dismissY = 0f }
                        mode == 2 -> scope.launch {
                            Animatable(zoom.dismissY).animateTo(0f, spring(dampingRatio = 0.8f)) { zoom.dismissY = value }
                        }
                        zoom.scale < 1f -> animateTo(1f, Offset.Zero)
                    }
                }
            },
    ) {
        val progress = (zoom.dismissY.coerceAtLeast(0f) / (size.height.coerceAtLeast(1))).coerceIn(0f, 1f)
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                val s = zoom.scale * (1f - progress * 0.35f)
                scaleX = s; scaleY = s
                translationX = zoom.offset.x
                translationY = zoom.offset.y + zoom.dismissY.coerceAtLeast(-120f) * if (zoom.dismissY < 0) 0.3f else 1f
            },
            content = content,
        )
    }
}
