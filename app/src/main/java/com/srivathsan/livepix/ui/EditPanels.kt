package com.srivathsan.livepix.ui

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Brightness6
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Exposure
import androidx.compose.material.icons.outlined.FilterVintage
import androidx.compose.material.icons.outlined.Grain
import androidx.compose.material.icons.outlined.HdrStrong
import androidx.compose.material.icons.outlined.InvertColors
import androidx.compose.material.icons.outlined.Lens
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material.icons.outlined.Details
import androidx.compose.material.icons.outlined.Vignette
import androidx.compose.material.icons.outlined.WbShade
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.srivathsan.livepix.data.AssetEdit
import com.srivathsan.livepix.media.Adjust
import com.srivathsan.livepix.media.CropSpec
import com.srivathsan.livepix.media.PhotoFilter
import com.srivathsan.livepix.media.ToneEngine
import com.srivathsan.livepix.ui.components.RulerDial
import com.srivathsan.livepix.ui.theme.IosType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val ADJUST_ICONS: Map<Adjust, ImageVector> = mapOf(
    Adjust.EXPOSURE to Icons.Outlined.Exposure,
    Adjust.BRILLIANCE to Icons.Outlined.HdrStrong,
    Adjust.HIGHLIGHTS to Icons.Outlined.LightMode,
    Adjust.SHADOWS to Icons.Outlined.DarkMode,
    Adjust.CONTRAST to Icons.Outlined.Contrast,
    Adjust.BRIGHTNESS to Icons.Outlined.Brightness6,
    Adjust.BLACK_POINT to Icons.Outlined.Lens,
    Adjust.SATURATION to Icons.Outlined.Palette,
    Adjust.VIBRANCE to Icons.Outlined.InvertColors,
    Adjust.WARMTH to Icons.Outlined.Thermostat,
    Adjust.TINT to Icons.Outlined.WbShade,
    Adjust.SHARPNESS to Icons.Outlined.Details,
    Adjust.DEFINITION to Icons.Outlined.FilterVintage,
    Adjust.NOISE_REDUCTION to Icons.Outlined.Grain,
    Adjust.VIGNETTE to Icons.Outlined.Vignette,
)

/** Adjust: a strip of tools, each a ring showing its value, and the dial for the selected one. */
@Composable
fun AdjustPanel(draft: AssetEdit, base: Bitmap?, accent: Color, onChange: (AssetEdit) -> Unit) {
    var selected by remember { mutableStateOf<Adjust?>(Adjust.EXPOSURE) }
    var autoOn by remember { mutableStateOf(false) }
    val tools = listOf<Adjust?>(null) + Adjust.entries // null = Auto
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            (selected?.label ?: "Auto").uppercase(), style = IosType.caption2.copy(fontSize = 12.sp),
            color = Color.White.copy(alpha = 0.8f), modifier = Modifier.padding(top = 4.dp),
        )
        Spacer(Modifier.height(8.dp))
        LazyRow(
            state = rememberLazyListState(),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(tools) { a ->
                val value = a?.let { draft.adjust[it.name] ?: 0f } ?: 0f
                val active = a == selected || (a == null && selected == null)
                ToolRing(
                    icon = a?.let { ADJUST_ICONS[it] } ?: Icons.Outlined.AutoFixHigh,
                    value = if (a == null) (if (autoOn) 100f else 0f) else value,
                    active = active, accent = accent,
                ) {
                    if (a == null) {
                        selected = null
                        autoOn = !autoOn
                        val auto = if (autoOn && base != null) ToneEngine.auto(base) else emptyMap()
                        onChange(draft.copy(adjust = if (autoOn) auto.mapKeys { it.key.name } else emptyMap()))
                    } else selected = a
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        selected?.let { a ->
            val value = draft.adjust[a.name] ?: 0f
            RulerDial(
                value = value, range = a.min..100f, step = 2f,
                onValueChange = { v -> onChange(draft.copy(adjust = (draft.adjust + (a.name to v)).filterValues { it != 0f })) },
                label = { "${it.roundToInt()}" },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp), accent = accent,
            )
        }
    }
}

@Composable
private fun ToolRing(icon: ImageVector, value: Float, active: Boolean, accent: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(52.dp).clip(CircleShape).background(if (active) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.07f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // The ring fills clockwise for positive values and anticlockwise for negative, as in Photos.
        Canvas(Modifier.fillMaxSize().padding(2.dp)) {
            drawCircle(Color.White.copy(alpha = 0.18f), style = Stroke(2.dp.toPx()))
            if (value != 0f) {
                drawArc(
                    if (value > 0) accent else Color.White, -90f, 360f * value / 100f, false,
                    style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round),
                )
            }
        }
        Icon(icon, null, tint = if (value != 0f) accent else Color.White, modifier = Modifier.size(22.dp))
    }
}

/** Filters: thumbnails of the photo in each filter, and an intensity dial. */
@Composable
fun FiltersPanel(draft: AssetEdit, base: Bitmap?, accent: Color, onChange: (AssetEdit) -> Unit) {
    val current = runCatching { PhotoFilter.valueOf(draft.filter) }.getOrDefault(PhotoFilter.ORIGINAL)
    val previews by produceState<Map<PhotoFilter, Bitmap>>(emptyMap(), base) {
        val b = base ?: return@produceState
        value = withContext(Dispatchers.Default) {
            val small = Bitmap.createScaledBitmap(b, 140, (140f * b.height / b.width).roundToInt().coerceAtLeast(1), true)
            PhotoFilter.entries.associateWith { ToneEngine.apply(small, emptyMap(), it, 1f) }
        }
    }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(current.label.uppercase(), style = IosType.caption2.copy(fontSize = 12.sp), color = accent, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(8.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(PhotoFilter.entries) { f ->
                val active = f == current
                Box(
                    Modifier.size(width = 54.dp, height = 70.dp).clip(RoundedCornerShape(6.dp))
                        .border(BorderStroke(if (active) 2.dp else 0.dp, if (active) Color.White else Color.Transparent), RoundedCornerShape(6.dp))
                        .clickable { onChange(draft.copy(filter = f.name, filterIntensity = 1f)) },
                ) {
                    previews[f]?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        if (current != PhotoFilter.ORIGINAL) {
            RulerDial(
                value = (draft.filterIntensity * 100).roundToInt().toFloat(), range = 0f..100f, step = 2f,
                onValueChange = { onChange(draft.copy(filterIntensity = it / 100f)) },
                label = { "${it.roundToInt()}" },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp), accent = accent,
            )
        }
    }
}

/** Aspect presets in the crop menu; null ratio means freeform. */
val CROP_ASPECTS: List<Pair<String, Float?>> = listOf(
    "Freeform" to null, "Original" to -1f, "Square" to 1f,
    "16:9" to 16f / 9f, "9:16" to 9f / 16f, "4:3" to 4f / 3f, "3:4" to 3f / 4f,
    "3:2" to 3f / 2f, "2:3" to 2f / 3f, "5:4" to 5f / 4f, "4:5" to 4f / 5f,
)

/** Crop: straighten dial; rotate, flip and aspect live in the top bar. */
@Composable
fun CropPanel(draft: AssetEdit, accent: Color, onChange: (AssetEdit) -> Unit) {
    val crop = draft.crop ?: CropSpec()
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("STRAIGHTEN", style = IosType.caption2.copy(fontSize = 12.sp), color = Color.White.copy(alpha = 0.8f), modifier = Modifier.padding(top = 8.dp))
        RulerDial(
            value = crop.straighten.roundToInt().toFloat(), range = -45f..45f, step = 1f,
            onValueChange = { onChange(draft.copy(crop = crop.copy(straighten = it))) },
            label = { "${it.roundToInt()}°" },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), accent = accent,
        )
    }
}

/**
 * The crop frame over the (rotated, uncropped) photo: dimmed outside, thirds grid while
 * dragging, corner handles. Drag a corner or edge to resize, inside to move.
 */
@Composable
fun CropOverlay(crop: CropSpec, aspect: Float?, imageAspect: Float, onChange: (CropSpec) -> Unit) {
    val current by rememberUpdatedState(crop)
    var dragging by remember { mutableStateOf(false) }
    Canvas(
        Modifier.fillMaxSize().pointerInput(aspect, imageAspect) {
            var mode = 0 // bit flags: 1 left, 2 top, 4 right, 8 bottom; 15 = move
            detectDragGestures(
                onDragStart = { p ->
                    dragging = true
                    val c = current
                    val l = c.left * size.width; val t = c.top * size.height; val r = c.right * size.width; val b = c.bottom * size.height
                    val grab = 28.dp.toPx()
                    mode = 0
                    if (abs(p.x - l) < grab) mode = mode or 1
                    if (abs(p.x - r) < grab) mode = mode or 4
                    if (abs(p.y - t) < grab) mode = mode or 2
                    if (abs(p.y - b) < grab) mode = mode or 8
                    if (mode == 0 && p.x in l..r && p.y in t..b) mode = 15
                },
                onDragEnd = { dragging = false },
                onDragCancel = { dragging = false },
            ) { change, d ->
                change.consume()
                val c = current
                val dx = d.x / size.width; val dy = d.y / size.height
                val minSize = 0.08f
                var l = c.left; var t = c.top; var r = c.right; var b = c.bottom
                if (mode == 15) {
                    val mx = dx.coerceIn(-l, 1f - r); val my = dy.coerceIn(-t, 1f - b)
                    l += mx; r += mx; t += my; b += my
                } else {
                    if (mode and 1 != 0) l = (l + dx).coerceIn(0f, r - minSize)
                    if (mode and 4 != 0) r = (r + dx).coerceIn(l + minSize, 1f)
                    if (mode and 2 != 0) t = (t + dy).coerceIn(0f, b - minSize)
                    if (mode and 8 != 0) b = (b + dy).coerceIn(t + minSize, 1f)
                    // Keep a fixed aspect by deriving height from width.
                    if (aspect != null && aspect > 0f) {
                        val wantH = ((r - l) * imageAspect / aspect)
                        if (mode and 2 != 0) t = (b - wantH).coerceAtLeast(0f) else b = (t + wantH).coerceAtMost(1f)
                        val gotW = (b - t) * aspect / imageAspect
                        if (mode and 1 != 0) l = r - gotW else r = l + gotW
                    }
                }
                onChange(c.copy(left = l, top = t, right = r, bottom = b))
            }
        },
    ) {
        val rect = Rect(crop.left * size.width, crop.top * size.height, crop.right * size.width, crop.bottom * size.height)
        val outside = Path().apply { addRect(Rect(Offset.Zero, size)); addRect(rect) }
        outside.fillType = androidx.compose.ui.graphics.PathFillType.EvenOdd
        drawPath(outside, Color.Black.copy(alpha = 0.55f))
        drawRect(Color.White, rect.topLeft, rect.size, style = Stroke(1.dp.toPx()))
        if (dragging) {
            for (k in 1..2) {
                val x = rect.left + rect.width * k / 3; val y = rect.top + rect.height * k / 3
                drawLine(Color.White.copy(alpha = 0.6f), Offset(x, rect.top), Offset(x, rect.bottom), 0.75.dp.toPx())
                drawLine(Color.White.copy(alpha = 0.6f), Offset(rect.left, y), Offset(rect.right, y), 0.75.dp.toPx())
            }
        }
        val len = 20.dp.toPx(); val w = 3.5.dp.toPx()
        listOf(rect.topLeft to Offset(1f, 1f), Offset(rect.right, rect.top) to Offset(-1f, 1f),
            Offset(rect.left, rect.bottom) to Offset(1f, -1f), rect.bottomRight to Offset(-1f, -1f)).forEach { (p, dir) ->
            drawLine(Color.White, p, Offset(p.x + len * dir.x, p.y), w, cap = StrokeCap.Square)
            drawLine(Color.White, p, Offset(p.x, p.y + len * dir.y), w, cap = StrokeCap.Square)
        }
    }
}

