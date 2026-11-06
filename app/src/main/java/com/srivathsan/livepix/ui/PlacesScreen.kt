package com.srivathsan.livepix.ui

import android.graphics.Paint
import android.graphics.Rect
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.ViewList
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.srivathsan.livepix.LibraryState
import com.srivathsan.livepix.data.Asset
import com.srivathsan.livepix.data.Geo
import com.srivathsan.livepix.data.GeoIndex
import com.srivathsan.livepix.data.GeoPoint
import com.srivathsan.livepix.ui.components.GlassIconButton
import com.srivathsan.livepix.ui.theme.Ios
import com.srivathsan.livepix.ui.theme.IosType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

private const val MAX_SCALE = 1_500_000f
private const val DETAIL_SCALE = 4_000f

/** Coastlines ready to draw: detailed and thinned-out versions, each with bounds for culling. */
private class MapShapes(rings: List<FloatArray>) {
    class Shape(val path: Path, val bounds: ComposeRect)

    val detail = rings.map(::shape)
    val coarse = rings.filter { it.size >= 16 }.map { ring ->
        val thin = FloatArray((ring.size / 2 / 4 + 1) * 2)
        var n = 0
        var i = 0
        while (i < ring.size && n < thin.size) { thin[n++] = ring[i]; thin[n++] = ring[i + 1]; i += 8 }
        shape(thin.copyOf(n))
    }

    private fun shape(ring: FloatArray): Shape {
        val path = Path()
        var minX = 1f; var minY = 1f; var maxX = 0f; var maxY = 0f
        var i = 0
        while (i < ring.size) {
            val x = ring[i]; val y = ring[i + 1]
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y)
            i += 2
        }
        path.close()
        return Shape(path, ComposeRect(minX, minY, maxX, maxY))
    }

    companion object {
        @Volatile private var loaded: MapShapes? = null

        fun get(context: android.content.Context): MapShapes = loaded ?: synchronized(this) {
            loaded ?: MapShapes(Geo.land(context)).also { loaded = it }
        }
    }
}

@Composable
private fun rememberMapShapes(): MapShapes? {
    val context = LocalContext.current
    val shapes by produceState<MapShapes?>(null) { value = withContext(Dispatchers.Default) { MapShapes.get(context) } }
    return shapes
}

private data class MapColors(val water: Color, val land: Color, val text: Color, val halo: Color)

@Composable
private fun mapColors(): MapColors =
    if (Ios.colors.isDark) MapColors(Color(0xFF1B2A36), Color(0xFF39403B), Color(0xFFDADADA), Color(0xCC000000))
    else MapColors(Color(0xFFA9D3E8), Color(0xFFF3F0E8), Color(0xFF4A4A4A), Color(0xCCFFFFFF))

private fun DrawScope.drawLand(shapes: MapShapes, colors: MapColors, cx: Float, cy: Float, scale: Float) {
    drawRect(colors.water)
    val halfW = size.width / 2 / scale
    val halfH = size.height / 2 / scale
    withTransform({
        translate(size.width / 2 - cx * scale, size.height / 2 - cy * scale)
        scale(scale, scale, Offset.Zero)
    }) {
        for (shape in if (scale < DETAIL_SCALE) shapes.coarse else shapes.detail) {
            val b = shape.bounds
            if (b.right < cx - halfW || b.left > cx + halfW || b.bottom < cy - halfH || b.top > cy + halfH) continue
            drawPath(shape.path, colors.land)
        }
    }
}

/** City names, biggest first, skipping any that would overlap one already placed. */
private fun DrawScope.drawCityLabels(geo: GeoIndex, colors: MapColors, cx: Float, cy: Float, scale: Float, paint: Paint) {
    if (scale < 1_500f) return
    val halfW = size.width / 2
    val halfH = size.height / 2
    paint.color = colors.text.toArgb()
    paint.setShadowLayer(4f, 0f, 0f, colors.halo.toArgb())
    val placed = ArrayList<Rect>(24)
    val bounds = Rect()
    for (city in geo.cities) {
        val x = (Geo.mapX(city.lng).toFloat() - cx) * scale + halfW
        val y = (Geo.mapY(city.lat).toFloat() - cy) * scale + halfH
        if (x < 0 || x > size.width || y < 0 || y > size.height) continue
        paint.getTextBounds(city.name, 0, city.name.length, bounds)
        val r = Rect(x.toInt() - bounds.width() / 2 - 12, y.toInt() - bounds.height() - 12, x.toInt() + bounds.width() / 2 + 12, y.toInt() + 12)
        if (placed.any { Rect.intersects(it, r) }) continue
        placed += r
        drawContext.canvas.nativeCanvas.drawText(city.name, x - bounds.width() / 2, y, paint)
        if (placed.size >= 18) break
    }
}

private class Cluster(val assets: List<Asset>, val x: Float, val y: Float) {
    val cover: Asset get() = assets.last()
}

/** Groups pins on a grid in map space, so they stay put while panning and only change with zoom. */
private fun clusters(located: List<Pair<Asset, GeoPoint>>, scale: Float, cellPx: Float): List<Cluster> {
    val cell = cellPx / scale
    val groups = HashMap<Long, MutableList<Pair<Asset, GeoPoint>>>()
    for (entry in located) {
        val gx = floor(Geo.mapX(entry.second.lng) / cell).toLong()
        val gy = floor(Geo.mapY(entry.second.lat) / cell).toLong()
        groups.getOrPut((gx shl 32) xor gy) { ArrayList() }.add(entry)
    }
    return groups.values.map { g ->
        Cluster(
            g.map { it.first },
            g.sumOf { Geo.mapX(it.second.lng) }.div(g.size).toFloat(),
            g.sumOf { Geo.mapY(it.second.lat) }.div(g.size).toFloat(),
        )
    }
}

/** Places: every geotagged item on an offline map, with a list of places as the alternative view. */
@Composable
fun PlacesScreen(state: LibraryState, onBack: () -> Unit, onOpenSource: (Source) -> Unit) {
    val c = Ios.colors
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val located = remember(state) { state.located() }
    var asList by rememberSaveable { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(c.background)) {
        if (asList) PlaceList(state, located, onOpenSource)
        else PlacesMap(state, located, onOpenSource)
        Box(Modifier.fillMaxWidth().padding(top = top + 8.dp, start = 16.dp, end = 16.dp)) {
            GlassIconButton(Icons.AutoMirrored.Filled.ArrowBackIos, "Back", modifier = Modifier.align(Alignment.CenterStart), onClick = onBack)
            Text("Places", style = IosType.headline, color = c.label, modifier = Modifier.align(Alignment.Center))
            GlassIconButton(
                if (asList) Icons.Outlined.Map else Icons.Outlined.ViewList,
                if (asList) "Show map" else "Show list",
                modifier = Modifier.align(Alignment.CenterEnd),
            ) { asList = !asList }
        }
    }
}

@Composable
private fun PlacesMap(state: LibraryState, located: List<Pair<Asset, GeoPoint>>, onOpenSource: (Source) -> Unit) {
    val c = Ios.colors
    val shapes = rememberMapShapes()
    val colors = mapColors()
    val density = LocalDensity.current
    val cellPx = with(density) { 84.dp.toPx() }
    val labelPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = with(density) { 12.dp.toPx() } } }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val minScale = max(w, h) / 1.2f
        val fit = remember(located.isEmpty()) { fitting(located, w, h, minScale) }
        var cx by remember { mutableFloatStateOf(fit.first) }
        var cy by remember { mutableFloatStateOf(fit.second) }
        var scale by remember { mutableFloatStateOf(fit.third) }

        // Re-cluster at half-octave zoom steps, not every frame.
        val bucket = (ln(scale) / ln(2f) * 2).roundToInt()
        val pins = remember(located, bucket) { clusters(located, 2f.pow(bucket / 2f), cellPx) }

        Canvas(
            Modifier.fillMaxSize()
                .pointerInput(w, h) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val next = (scale * zoom).coerceIn(minScale, MAX_SCALE)
                        // Keep the point under the fingers where it is while zooming.
                        val fx = cx + (centroid.x - w / 2) / scale
                        val fy = cy + (centroid.y - h / 2) / scale
                        cx = (fx - (centroid.x + pan.x - w / 2) / next).coerceIn(0f, 1f)
                        cy = (fy - (centroid.y + pan.y - h / 2) / next).coerceIn(0f, 1f)
                        scale = next
                    }
                }
                .pointerInput(w, h) {
                    detectTapGestures(onDoubleTap = { p ->
                        val fx = cx + (p.x - w / 2) / scale
                        val fy = cy + (p.y - h / 2) / scale
                        scale = (scale * 2).coerceAtMost(MAX_SCALE)
                        cx = fx.coerceIn(0f, 1f)
                        cy = fy.coerceIn(0f, 1f)
                    })
                },
        ) {
            if (shapes == null) drawRect(colors.water)
            else {
                drawLand(shapes, colors, cx, cy, scale)
                state.geo?.let { drawCityLabels(it, colors, cx, cy, scale, labelPaint) }
            }
        }

        val half = with(density) { 32.dp.toPx() }
        for (pin in pins) {
            val x = (pin.x - cx) * scale + w / 2
            val y = (pin.y - cy) * scale + h / 2
            if (x < -half || x > w + half || y < -half || y > h + half) continue
            androidx.compose.runtime.key(pin.cover.key) {
                MapPin(
                    pin,
                    Modifier.offset { IntOffset((x - half).roundToInt(), (y - half).roundToInt()) },
                ) {
                    val place = state.placeOf(pin.cover)
                    onOpenSource(Source.Keys(place?.name ?: "Places", pin.assets.map { it.key }))
                }
            }
        }
        // GeoNames' licence asks for credit where its names appear.
        Text(
            "Place names: GeoNames (CC BY 4.0)", style = IosType.caption2, color = colors.text,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 34.dp),
        )
        if (located.isEmpty() && state.loaded) {
            Text(
                if (state.scanning) "Looking for photo locations…" else "No photos with a location yet",
                style = IosType.subheadline.copy(fontWeight = FontWeight.Medium), color = c.label,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

/** A map centre and zoom showing all [located] items. */
private fun fitting(located: List<Pair<Asset, GeoPoint>>, w: Float, h: Float, minScale: Float): Triple<Float, Float, Float> {
    if (located.isEmpty()) return Triple(0.5f, 0.45f, minScale)
    val xs = located.map { Geo.mapX(it.second.lng).toFloat() }
    val ys = located.map { Geo.mapY(it.second.lat).toFloat() }
    val spanX = xs.max() - xs.min()
    val spanY = ys.max() - ys.min()
    // Leave room around the outermost pins, and don't start closer than a region's view.
    val scale = min(w * 0.6f / max(spanX, 1e-6f), h * 0.6f / max(spanY, 1e-6f)).coerceIn(minScale, 40_000f)
    return Triple((xs.max() + xs.min()) / 2, (ys.max() + ys.min()) / 2, scale)
}

@Composable
private fun MapPin(pin: Cluster, modifier: Modifier, onClick: () -> Unit) {
    val c = Ios.colors
    Box(modifier.size(64.dp).clickable(onClick = onClick)) {
        val bmp = rememberThumbnail(pin.cover, 64.dp)
        Box(
            Modifier.padding(4.dp).fillMaxSize().clip(RoundedCornerShape(10.dp)).background(Color.White)
                .border(2.dp, Color.White, RoundedCornerShape(10.dp)),
        ) {
            if (bmp != null) Image(bmp.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().padding(2.dp).clip(RoundedCornerShape(8.dp)))
        }
        if (pin.assets.size > 1) {
            Text(
                "%,d".format(pin.assets.size), style = IosType.caption2.copy(fontWeight = FontWeight.Bold), color = Color.White,
                modifier = Modifier.align(Alignment.TopEnd).clip(RoundedCornerShape(50)).background(c.blue).padding(horizontal = 5.dp),
            )
        }
    }
}

@Composable
private fun PlaceList(state: LibraryState, located: List<Pair<Asset, GeoPoint>>, onOpenSource: (Source) -> Unit) {
    val c = Ios.colors
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val places = remember(located, state.geo) {
        located.groupBy { (a, _) -> state.placeOf(a)?.fullName ?: "Unknown Place" }.entries
            .sortedByDescending { it.value.size }.map { it.key to it.value.map { e -> e.first } }
    }
    LazyColumn(contentPadding = PaddingValues(top = top + 64.dp, bottom = bottom + 24.dp), modifier = Modifier.fillMaxSize()) {
        items(places, key = { it.first }) { (name, assets) ->
            Row(
                Modifier.fillMaxWidth().clickable { onOpenSource(Source.Keys(name.substringBefore(','), assets.map { it.key })) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(c.fill)) {
                    val bmp = rememberThumbnail(assets.last(), 64.dp)
                    if (bmp != null) Image(bmp.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, style = IosType.body, color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("%,d".format(assets.size), style = IosType.subheadline, color = c.secondaryLabel)
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = c.tertiaryLabel)
            }
            HorizontalDivider(Modifier.padding(start = 86.dp), thickness = 0.5.dp, color = c.separator)
        }
    }
}

/** A small map, not interactive: Collections' Places card, and a photo's location in its info panel. */
@Composable
fun MiniMap(points: List<GeoPoint>, modifier: Modifier = Modifier, maxScale: Float = 6_000f) {
    val shapes = rememberMapShapes()
    val colors = mapColors()
    val dot = Ios.colors.blue
    val density = LocalDensity.current
    Canvas(modifier) {
        if (shapes == null) { drawRect(colors.water); return@Canvas }
        val xs = points.map { Geo.mapX(it.lng).toFloat() }
        val ys = points.map { Geo.mapY(it.lat).toFloat() }
        val spanX = if (xs.isEmpty()) 1f else xs.max() - xs.min()
        val spanY = if (ys.isEmpty()) 1f else ys.max() - ys.min()
        val scale = min(size.width * 0.7f / max(spanX, 1e-6f), size.height * 0.7f / max(spanY, 1e-6f)).coerceIn(size.width, maxScale)
        val cx = if (xs.isEmpty()) 0.5f else (xs.max() + xs.min()) / 2
        val cy = if (ys.isEmpty()) 0.45f else (ys.max() + ys.min()) / 2
        drawLand(shapes, colors, cx, cy, scale)
        val r = with(density) { 5.dp.toPx() }
        for (i in xs.indices) {
            val p = Offset((xs[i] - cx) * scale + size.width / 2, (ys[i] - cy) * scale + size.height / 2)
            drawCircle(Color.White, r + 2f, p)
            drawCircle(dot, r, p)
        }
    }
}
