package com.srivathsan.livepix.ui

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Camera
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.srivathsan.livepix.data.Asset
import com.srivathsan.livepix.data.AssetEdit
import com.srivathsan.livepix.data.LiveEffect
import com.srivathsan.livepix.data.Traits
import com.srivathsan.livepix.media.ImageLoader
import com.srivathsan.livepix.media.LiveMedia
import com.srivathsan.livepix.media.PortraitLighting
import com.srivathsan.livepix.media.StillRenderer
import com.srivathsan.livepix.ui.components.RulerDial
import com.srivathsan.livepix.ui.components.glass
import com.srivathsan.livepix.ui.theme.DarkIos
import com.srivathsan.livepix.ui.theme.IosType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.min

private const val PREVIEW_SIDE = 1440
private const val STRIP_FRAMES = 12

/** iPhone's depth control stops. */
private val F_STOPS = listOf(1.4f, 1.6f, 1.8f, 2f, 2.2f, 2.5f, 2.8f, 3.2f, 3.5f, 4f, 4.5f, 5f, 5.6f, 6.3f, 7.1f, 8f, 9f, 10f, 11f, 13f, 14f, 16f)

private enum class EditTab(val label: String) { LIVE("Live"), PORTRAIT("Portrait") }

private fun fLabel(f: Float) = if (f % 1f == 0f) "ƒ ${f.toInt()}" else "ƒ $f"

@Composable
fun EditScreen(
    asset: Asset,
    traits: Traits?,
    initial: AssetEdit,
    onCancel: () -> Unit,
    onDone: (AssetEdit) -> Unit,
) {
    val ios = DarkIos // the editor is always dark, like Photos
    val context = LocalContext.current
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val tabs = buildList {
        if (asset.isLive) add(EditTab.LIVE)
        if (traits?.hasDepth == true) add(EditTab.PORTRAIT)
    }
    var tab by remember { mutableStateOf(tabs.firstOrNull()) }
    var draft by remember { mutableStateOf(initial) }
    var showDepthDial by remember { mutableStateOf(true) }

    // Live: frame being scrubbed (null = showing the key photo).
    var scrubUs by remember { mutableStateOf<Long?>(null) }
    var scrubFrame by remember { mutableStateOf<Bitmap?>(null) }

    val original by produceState<Bitmap?>(null, asset.key) { value = ImageLoader.full(context, asset.uri, PREVIEW_SIDE) }
    var rendered by remember { mutableStateOf<Bitmap?>(null) }
    var rendering by remember { mutableStateOf(false) }
    LaunchedEffect(asset.key) {
        snapshotFlow { draft }.conflate().collectLatest { d ->
            if (StillRenderer.needsRender(asset, traits, d)) {
                rendering = true
                rendered = withContext(Dispatchers.Default) { StillRenderer.render(context, asset, traits, d, PREVIEW_SIDE) }
                rendering = false
            } else rendered = null
        }
    }
    val shown = scrubFrame?.takeIf { scrubUs != null } ?: rendered ?: original

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize()) {
            // Top bar: cancel, mode toggles, done.
            Row(
                Modifier.fillMaxWidth().padding(top = top + 6.dp, start = 16.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoundButton(Icons.Filled.Close, "Cancel", Color(0xFF3A3A3C), Color.White, onClick = onCancel)
                Spacer(Modifier.weight(1f))
                when (tab) {
                    EditTab.LIVE -> {
                        TogglePill("LIVE", draft.liveEnabled) {
                            draft = draft.copy(effect = if (draft.liveEnabled) LiveEffect.OFF else LiveEffect.LIVE)
                        }
                        Spacer(Modifier.width(8.dp))
                        RoundButton(
                            if (draft.muted) Icons.AutoMirrored.Outlined.VolumeOff else Icons.AutoMirrored.Outlined.VolumeUp,
                            "Sound", Color(0xFF3A3A3C), if (draft.muted) Color.White else ios.yellow, size = 34.dp,
                        ) { draft = draft.copy(muted = !draft.muted) }
                    }
                    EditTab.PORTRAIT -> {
                        TogglePill("PORTRAIT", draft.portrait) { draft = draft.copy(portrait = !draft.portrait) }
                        Spacer(Modifier.width(8.dp))
                        TogglePill(fLabel(draft.aperture), showDepthDial && draft.portrait, outlineOnly = true) {
                            if (!draft.portrait) draft = draft.copy(portrait = true)
                            showDepthDial = !showDepthDial || !draft.portrait
                        }
                    }
                    null -> Text("Edit", style = IosType.headline, color = Color.White)
                }
                Spacer(Modifier.weight(1f))
                val changed = draft != initial
                RoundButton(Icons.Filled.Check, "Done", if (changed) ios.yellow else Color(0xFF3A3A3C), if (changed) Color.Black else Color.White) {
                    onDone(draft)
                }
            }
            if (draft.copy(favorite = false, muted = false) != AssetEdit()) {
                Text(
                    "REVERT", style = IosType.footnote.copy(fontWeight = FontWeight.SemiBold), color = ios.red,
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp)
                        .clickable { draft = AssetEdit(favorite = draft.favorite); scrubUs = null },
                )
            }

            // Photo with tap-to-focus for Portrait.
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                val bmp = shown
                if (bmp != null) {
                    val ratio = bmp.width.toFloat() / bmp.height
                    val boxRatio = constraints.maxWidth.toFloat() / constraints.maxHeight
                    val w = if (ratio > boxRatio) maxWidth else maxHeight * ratio
                    val h = if (ratio > boxRatio) maxWidth / ratio else maxHeight
                    var focusMark by remember { mutableStateOf<Offset?>(null) }
                    Box(
                        Modifier.size(w, h).pointerInput(tab, draft.portrait) {
                            detectTapGestures { p ->
                                if (tab == EditTab.PORTRAIT && draft.portrait) {
                                    focusMark = p
                                    draft = draft.copy(focusX = p.x / size.width, focusY = p.y / size.height)
                                }
                            }
                        },
                    ) {
                        Image(bmp.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                        focusMark?.let { FocusSquare(it, ios.yellow) { focusMark = null } }
                    }
                }
                if (rendering) {
                    CircularProgressIndicator(
                        Modifier.align(Alignment.TopEnd).padding(12.dp).size(18.dp), color = Color.White, strokeWidth = 2.dp,
                    )
                }
            }

            // Tool panel.
            Box(Modifier.fillMaxWidth().height(150.dp)) {
                when (tab) {
                    EditTab.LIVE -> LivePanel(
                        asset = asset,
                        presentationUs = traits?.presentationUs,
                        keyFrameUs = draft.keyFrameUs,
                        onScrub = { us, frame -> scrubUs = us; scrubFrame = frame },
                        onMakeKey = { us ->
                            // A new key photo should be visible, so effects that replace the still revert to Live.
                            val effect = if (draft.effect == LiveEffect.OFF || draft.effect == LiveEffect.LONG_EXPOSURE) LiveEffect.LIVE else draft.effect
                            draft = draft.copy(keyFrameUs = us, effect = effect)
                            scrubUs = null
                        },
                        scrubUs = scrubUs,
                        accent = ios.yellow,
                    )
                    EditTab.PORTRAIT -> PortraitPanel(draft, showDepthDial, ios.yellow) { draft = it }
                    null -> Text(
                        "This photo has no Live or Portrait data.",
                        style = IosType.subheadline, color = Color.White.copy(alpha = 0.6f), modifier = Modifier.align(Alignment.Center),
                    )
                }
            }

            // Mode tabs.
            if (tabs.size > 1 || tabs.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = bottom + 12.dp, top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally),
                ) {
                    tabs.forEach { t ->
                        val active = t == tab
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.clickable { tab = t; scrubUs = null },
                        ) {
                            Icon(
                                if (t == EditTab.LIVE) LiveIcon else Icons.Outlined.Camera, t.label,
                                tint = if (active) Color.White else Color.White.copy(alpha = 0.5f), modifier = Modifier.size(26.dp),
                            )
                            Text(t.label, style = IosType.caption.copy(fontWeight = FontWeight.SemiBold),
                                color = if (active) Color.White else Color.White.copy(alpha = 0.5f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RoundButton(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, bg: Color, tint: Color, size: androidx.compose.ui.unit.Dp = 40.dp, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape).background(bg).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, desc, tint = tint, modifier = Modifier.size(size * 0.55f)) }
}

/** Yellow capsule when on, grey when off: iPhone's LIVE / PORTRAIT toggles. */
@Composable
private fun TogglePill(text: String, on: Boolean, outlineOnly: Boolean = false, onClick: () -> Unit) {
    val yellow = DarkIos.yellow
    val bg = when {
        outlineOnly -> Color(0xFF3A3A3C)
        on -> yellow
        else -> Color(0xFF3A3A3C)
    }
    val fg = when {
        outlineOnly && on -> yellow
        outlineOnly -> Color.White
        on -> Color.Black
        else -> Color.White
    }
    Row(
        Modifier.height(30.dp).clip(RoundedCornerShape(50)).background(bg).clickable(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (text == "LIVE") {
            Icon(LiveIcon, null, tint = fg, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(text, style = IosType.caption2.copy(fontSize = 12.sp), color = fg)
    }
}

@Composable
private fun FocusSquare(p: Offset, color: Color, onGone: () -> Unit) {
    LaunchedEffect(p) { kotlinx.coroutines.delay(1400); onGone() }
    val d = LocalDensity.current
    val half = 36.dp
    Canvas(Modifier.offset(x = with(d) { p.x.toDp() } - half, y = with(d) { p.y.toDp() } - half).size(half * 2)) {
        drawRect(color, style = Stroke(1.5.dp.toPx()), size = Size(size.width, size.height))
        val t = 6.dp.toPx()
        drawLine(color, Offset(size.width / 2, 0f), Offset(size.width / 2, t), 1.5.dp.toPx())
        drawLine(color, Offset(size.width / 2, size.height), Offset(size.width / 2, size.height - t), 1.5.dp.toPx())
        drawLine(color, Offset(0f, size.height / 2), Offset(t, size.height / 2), 1.5.dp.toPx())
        drawLine(color, Offset(size.width, size.height / 2), Offset(size.width - t, size.height / 2), 1.5.dp.toPx())
    }
}

/**
 * Filmstrip of the Live movie with a white frame you drag to pick a moment, a dot over the
 * current key photo, and "Make Key Photo" once you move off it.
 */
@Composable
private fun LivePanel(
    asset: Asset,
    presentationUs: Long?,
    keyFrameUs: Long?,
    scrubUs: Long?,
    onScrub: (Long?, Bitmap?) -> Unit,
    onMakeKey: (Long) -> Unit,
    accent: Color,
) {
    val context = LocalContext.current
    val video = produceState<android.net.Uri?>(asset.liveVideo, asset.versionKey) {
        if (value == null) value = withContext(Dispatchers.IO) { asset.movieUri(context) }
    }.value ?: return
    // Frames appear one by one as they decode, so the strip fills in like Photos'.
    var duration by remember(video) { mutableLongStateOf(0L) }
    val frames = remember(video) { androidx.compose.runtime.mutableStateListOf<Bitmap?>() }
    LaunchedEffect(video) {
        withContext(Dispatchers.IO) {
            LiveMedia(context, video).use { m ->
                duration = m.durationUs
                withContext(Dispatchers.Main) { frames.clear(); repeat(STRIP_FRAMES) { frames.add(null) } }
                m.frames(STRIP_FRAMES, 200) { i, bmp -> frames[i] = bmp }
            }
        }
    }
    // Motion Photos record where the still sits; iPhone's sits mid-movie.
    val keyUs = keyFrameUs ?: presentationUs?.coerceAtMost(duration) ?: (duration / 2)
    var position by remember(duration) { mutableFloatStateOf(if (duration > 0) keyUs.toFloat() / duration else 0.5f) }
    var requestUs by remember { mutableLongStateOf(-1L) }

    LaunchedEffect(video) {
        snapshotFlow { requestUs }.conflate().collectLatest { us ->
            if (us < 0) return@collectLatest
            val frame = withContext(Dispatchers.IO) { LiveMedia(context, video).use { it.frameAt(us, PREVIEW_SIDE) } }
            onScrub(us, frame)
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        val showMake = scrubUs != null && duration > 0 && abs(scrubUs - keyUs) > duration / 60
        AnimatedVisibility(showMake, enter = fadeIn(), exit = fadeOut()) {
            Text(
                "Make Key Photo", style = IosType.subheadline.copy(fontWeight = FontWeight.SemiBold), color = Color.White,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0xFF3A3A3C))
                    .clickable { scrubUs?.let(onMakeKey) }.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        if (!showMake) Spacer(Modifier.height(36.dp))
        Spacer(Modifier.height(14.dp))
        BoxWithConstraints(Modifier.fillMaxWidth().height(64.dp)) {
            val width = constraints.maxWidth.toFloat()
            val frameW = 34.dp

            // Key photo dot.
            if (duration > 0) {
                val keyX = maxWidth * (keyUs.toFloat() / duration)
                Box(Modifier.offset(x = keyX - 3.dp).size(6.dp).clip(CircleShape).background(Color.White))
            }
            Row(
                Modifier.padding(top = 12.dp).fillMaxWidth().height(48.dp).clip(RoundedCornerShape(6.dp))
                    .pointerInput(duration) {
                        detectHorizontalDragGestures(
                            onDragEnd = {},
                        ) { change, dx ->
                            change.consume()
                            position = (position + dx / width).coerceIn(0f, 1f)
                            requestUs = (position * duration).toLong()
                        }
                    }
                    .pointerInput(duration) {
                        detectTapGestures { p ->
                            position = (p.x / width).coerceIn(0f, 1f)
                            requestUs = (position * duration).toLong()
                        }
                    },
            ) {
                frames.forEach { f ->
                    if (f != null) Image(f.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.weight(1f).fillMaxSize())
                    else Box(Modifier.weight(1f).fillMaxSize().background(Color(0xFF2C2C2E)))
                }
            }
            // Selector frame.
            val x = (maxWidth - frameW) * position
            Box(
                Modifier.offset(x = x, y = 8.dp).width(frameW).height(56.dp)
                    .border(BorderStroke(3.dp, Color.White), RoundedCornerShape(6.dp)),
            )
        }
        if (keyFrameUs != null) {
            Text("Key photo chosen from the Live movie", style = IosType.caption, color = Color.White.copy(alpha = 0.55f),
                modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun PortraitPanel(draft: AssetEdit, showDepthDial: Boolean, accent: Color, onChange: (AssetEdit) -> Unit) {
    val lighting = runCatching { PortraitLighting.valueOf(draft.lighting) }.getOrDefault(PortraitLighting.NATURAL)
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        when {
            showDepthDial && draft.portrait -> {
                val index = F_STOPS.indexOfFirst { it >= draft.aperture - 0.01f }.coerceAtLeast(0)
                RulerDial(
                    value = index.toFloat(), range = 0f..(F_STOPS.size - 1).toFloat(), step = 1f,
                    onValueChange = { onChange(draft.copy(aperture = F_STOPS[it.toInt()])) },
                    label = { fLabel(F_STOPS[it.toInt().coerceIn(F_STOPS.indices)]) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp), accent = accent, majorEvery = 3,
                )
            }
            lighting != PortraitLighting.NATURAL -> RulerDial(
                value = (draft.lightingIntensity * 100).toInt().toFloat(), range = 0f..100f, step = 2f,
                onValueChange = { onChange(draft.copy(lightingIntensity = it / 100f)) },
                label = { "${it.toInt()}" },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp), accent = accent,
            )
            else -> Spacer(Modifier.height(64.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(lighting.label.uppercase(), style = IosType.caption2.copy(fontSize = 12.sp), color = accent)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PortraitLighting.entries.forEach { l ->
                val active = l == lighting
                Box(
                    Modifier.size(if (active) 46.dp else 40.dp).clip(CircleShape)
                        .background(if (active) Color.White.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.08f))
                        .border(BorderStroke(if (active) 1.5.dp else 0.dp, if (active) accent else Color.Transparent), CircleShape)
                        .clickable { onChange(draft.copy(lighting = l.name)) },
                    contentAlignment = Alignment.Center,
                ) { LightingGlyph(l, if (active) accent else Color.White) }
            }
        }
    }
}

/** Small drawings standing in for iPhone's lighting cube icons. */
@Composable
private fun LightingGlyph(l: PortraitLighting, color: Color) {
    Canvas(Modifier.size(22.dp)) {
        val r = size.minDimension / 2
        val c = Offset(size.width / 2, size.height / 2)
        when (l) {
            PortraitLighting.NATURAL -> drawCircle(color, r * 0.55f, c, style = Stroke(1.5.dp.toPx()))
            PortraitLighting.STUDIO -> { drawCircle(color, r * 0.55f, c); drawCircle(color, r * 0.95f, c, style = Stroke(1.dp.toPx())) }
            PortraitLighting.CONTOUR -> { drawCircle(color, r * 0.55f, c, style = Stroke(1.5.dp.toPx())); drawArc(color, 90f, 180f, true, topLeft = Offset(c.x - r * 0.55f, c.y - r * 0.55f), size = Size(r * 1.1f, r * 1.1f)) }
            PortraitLighting.STAGE -> { drawRect(color.copy(alpha = 0.35f)); drawCircle(color, r * 0.5f, c) }
            PortraitLighting.STAGE_MONO -> { drawRect(color.copy(alpha = 0.2f)); drawCircle(Color.Gray, r * 0.5f, c) }
            PortraitLighting.HIGH_KEY_MONO -> { drawCircle(color, r, c); drawCircle(Color.DarkGray, r * 0.45f, c) }
        }
    }
}
