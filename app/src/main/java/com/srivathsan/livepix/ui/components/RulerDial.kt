package com.srivathsan.livepix.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.srivathsan.livepix.ui.theme.IosType
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The tick-mark dial iPhone uses for depth (f-number) and light intensity. The value under the
 * yellow center line is selected; dragging scrolls the ticks.
 */
@Composable
fun RulerDial(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    onValueChange: (Float) -> Unit,
    label: (Float) -> String,
    modifier: Modifier = Modifier,
    accent: Color = Color(0xFFFFD60A),
    majorEvery: Int = 5,
) {
    val density = LocalDensity.current
    val spacingPx = with(density) { 9.dp.toPx() }
    val current by rememberUpdatedState(value)
    val haptic = LocalHapticFeedback.current
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label(value), style = IosType.footnote.copy(fontWeight = FontWeight.SemiBold), color = accent)
        Box(
            Modifier.fillMaxWidth().height(44.dp).pointerInput(range, step) {
                var acc = 0f
                detectHorizontalDragGestures(onDragStart = { acc = 0f }) { change, dx ->
                    change.consume()
                    acc -= dx
                    val steps = (acc / spacingPx).toInt()
                    if (steps != 0) {
                        acc -= steps * spacingPx
                        val next = (current + steps * step).coerceIn(range.start, range.endInclusive)
                        if (next != current) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onValueChange((next / step).roundToInt() * step)
                        }
                    }
                }
            },
        ) {
            Canvas(Modifier.fillMaxWidth().height(44.dp)) {
                val cx = size.width / 2
                val total = ((range.endInclusive - range.start) / step).roundToInt()
                val currentIndex = (value - range.start) / step
                for (i in 0..total) {
                    val x = cx + (i - currentIndex) * spacingPx
                    if (x < -spacingPx || x > size.width + spacingPx) continue
                    val major = i % majorEvery == 0
                    val fade = (1f - abs(x - cx) / (size.width / 2)).coerceIn(0.15f, 1f)
                    val h = if (major) 18.dp.toPx() else 10.dp.toPx()
                    drawLine(
                        Color.White.copy(alpha = (if (major) 0.9f else 0.5f) * fade),
                        Offset(x, size.height / 2 - h / 2), Offset(x, size.height / 2 + h / 2),
                        strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round,
                    )
                }
                drawLine(accent, Offset(cx, 4.dp.toPx()), Offset(cx, size.height - 4.dp.toPx()), strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round)
            }
        }
    }
}
