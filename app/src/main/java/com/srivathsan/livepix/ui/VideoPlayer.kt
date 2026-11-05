package com.srivathsan.livepix.ui

import android.graphics.Bitmap
import androidx.annotation.OptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.srivathsan.livepix.data.Asset
import com.srivathsan.livepix.data.AssetEdit
import com.srivathsan.livepix.media.LiveMedia
import com.srivathsan.livepix.media.VideoTrim
import com.srivathsan.livepix.ui.components.glass
import com.srivathsan.livepix.ui.theme.DarkIos
import com.srivathsan.livepix.ui.theme.Ios
import com.srivathsan.livepix.ui.theme.IosType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** Playback state for the video on screen, shared by the surface and the controls. */
@Stable
class VideoState(val player: ExoPlayer) {
    var positionMs by mutableLongStateOf(0L)
    var durationMs by mutableLongStateOf(0L)
    var playing by mutableStateOf(false)
    var muted by mutableStateOf(false)
    var firstFrame by mutableStateOf(false)

    fun toggle() { if (player.isPlaying) player.pause() else { if (player.playbackState == Player.STATE_ENDED) player.seekTo(0); player.play() } }
    fun toggleMute() { muted = !muted; player.volume = if (muted) 0f else 1f }
    fun seekFraction(f: Float) { player.seekTo((f.coerceIn(0f, 1f) * durationMs).toLong()) }
}

/** An autoplaying player for [asset] (trimmed per [edit]) while [active]; null for photos. */
@Composable
fun rememberVideoState(asset: Asset, edit: AssetEdit, active: Boolean, loop: Boolean = false): VideoState? {
    if (!asset.isVideo || !active) return null
    val context = LocalContext.current
    val state = remember(asset.key, edit.trimStartUs, edit.trimEndUs) {
        VideoState(ExoPlayer.Builder(context).build().apply {
            setMediaItem(VideoTrim.mediaItem(asset.uri, edit.trimStartUs, edit.trimEndUs))
            repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            playWhenReady = true
            prepare()
        })
    }
    DisposableEffect(state) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { state.playing = isPlaying }
            override fun onRenderedFirstFrame() { state.firstFrame = true }
        }
        state.player.addListener(listener)
        onDispose { state.player.removeListener(listener); state.player.release() }
    }
    LaunchedEffect(state) {
        while (isActive) {
            state.positionMs = state.player.currentPosition
            state.player.duration.takeIf { it > 0 }?.let { state.durationMs = it }
            delay(40)
        }
    }
    return state
}

@Composable
fun VideoSurface(state: VideoState, modifier: Modifier = Modifier) {
    PlayerTexture(state.player, modifier.fillMaxSize().alpha(if (state.firstFrame) 1f else 0f))
}

/** Frames along the video, for the scrubber and trim strip. */
@Composable
fun rememberVideoFrames(asset: Asset, count: Int = 10): List<Bitmap> {
    val context = LocalContext.current
    val frames by produceState<List<Bitmap>>(emptyList(), asset.key) {
        value = withContext(Dispatchers.IO) { runCatching { LiveMedia(context, asset.uri).use { m -> m.frames(count, 160).map { it.second } } }.getOrDefault(emptyList()) }
    }
    return frames
}

/** The viewer's video bar: play/pause, a frame strip with a playhead you can drag, and mute. */
@Composable
fun VideoControls(state: VideoState, asset: Asset, modifier: Modifier = Modifier) {
    val c = Ios.colors
    val frames = rememberVideoFrames(asset)
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).glass(CircleShape).clickable { state.toggle() }, contentAlignment = Alignment.Center) {
            Icon(if (state.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play", tint = c.label, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(10.dp))
        BoxWithConstraints(Modifier.weight(1f).height(40.dp)) {
            FrameStrip(frames, Modifier.fillMaxSize()) { f -> state.seekFraction(f) }
            val f = if (state.durationMs > 0) state.positionMs.toFloat() / state.durationMs else 0f
            Box(Modifier.offset(x = maxWidth * f.coerceIn(0f, 1f) - 1.5.dp).width(3.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(Color.White))
        }
        Spacer(Modifier.width(10.dp))
        Box(Modifier.size(40.dp).glass(CircleShape).clickable { state.toggleMute() }, contentAlignment = Alignment.Center) {
            Icon(if (state.muted) Icons.AutoMirrored.Outlined.VolumeOff else Icons.AutoMirrored.Outlined.VolumeUp, "Mute", tint = c.label, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun FrameStrip(frames: List<Bitmap>, modifier: Modifier, onSeek: (Float) -> Unit) {
    val seek by rememberUpdatedState(onSeek)
    BoxWithConstraints(modifier.systemGestureExclusion().clip(RoundedCornerShape(6.dp)).background(Color(0xFF2C2C2E))) {
        val widthPx = constraints.maxWidth.toFloat()
        Row(
            Modifier.fillMaxSize()
                .pointerInput(Unit) { detectTapGestures { p -> seek(p.x / widthPx) } }
                .pointerInput(Unit) { detectDragGestures { change, _ -> change.consume(); seek(change.position.x / widthPx) } },
        ) {
            frames.forEach { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.weight(1f).fillMaxHeight()) }
        }
    }
}

/** Video editing: Photos' trim strip with yellow handles, a looping preview, and play/pause. */
@Composable
fun VideoEditScreen(asset: Asset, initial: AssetEdit, onCancel: () -> Unit, onDone: (AssetEdit) -> Unit) {
    val ios = DarkIos
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    var draft by remember { mutableStateOf(initial) }
    // Preview plays the whole clip; the trim is applied when you press Done.
    val state = rememberVideoState(asset, initial.copy(trimStartUs = 0, trimEndUs = null), active = true, loop = true)
    val frames = rememberVideoFrames(asset, 12)
    val durationUs = (state?.durationMs ?: asset.durationMs).coerceAtLeast(1) * 1000
    var start by remember { mutableFloatStateOf(draft.trimStartUs.toFloat() / durationUs) }
    var end by remember { mutableFloatStateOf(draft.trimEndUs?.let { it.toFloat() / durationUs } ?: 1f) }
    LaunchedEffect(state) {
        // Keep playback inside the trimmed range.
        while (state != null && isActive) {
            val d = state.durationMs
            if (d > 0) {
                val p = state.player.currentPosition
                if (p < start * d - 50 || p > end * d) state.player.seekTo((start * d).toLong())
            }
            delay(30)
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(top = top + 6.dp, start = 16.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(Color(0xFF3A3A3C)).clickable(onClick = onCancel), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Close, "Cancel", tint = Color.White, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.weight(1f))
                Text("Trim", style = IosType.headline, color = Color.White)
                Spacer(Modifier.weight(1f))
                val changed = start > 0.001f || end < 0.999f
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(if (changed != initial.isTrimmed || changed) ios.yellow else Color(0xFF3A3A3C))
                        .clickable {
                            val d = durationUs
                            onDone(draft.copy(trimStartUs = if (start > 0.001f) (start * d).toLong() else 0, trimEndUs = if (end < 0.999f) (end * d).toLong() else null))
                        },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Check, "Done", tint = if (changed) Color.Black else Color.White, modifier = Modifier.size(22.dp)) }
            }
            if (start > 0.001f || end < 0.999f) {
                Text(
                    "REVERT", style = IosType.footnote.copy(fontWeight = FontWeight.SemiBold), color = ios.red,
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp).clickable { start = 0f; end = 1f },
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 12.dp)) { state?.let { VideoSurface(it) } }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(Color(0xFF3A3A3C)).clickable { state?.toggle() }, contentAlignment = Alignment.Center) {
                    Icon(if (state?.playing == true) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play", tint = Color.White)
                }
                Spacer(Modifier.width(10.dp))
                TrimStrip(frames, start, end, if (state != null && state.durationMs > 0) state.positionMs.toFloat() / state.durationMs else 0f, ios.yellow,
                    Modifier.weight(1f).height(52.dp)) { s, e -> start = s; end = e; state?.player?.seekTo((s * (state.durationMs)).toLong()) }
            }
            Text(
                "${Format.duration(((end - start) * durationUs / 1000).toLong())} selected",
                style = IosType.footnote, color = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = bottom + 24.dp),
            )
        }
    }
}

@Composable
private fun TrimStrip(frames: List<Bitmap>, start: Float, end: Float, playhead: Float, accent: Color, modifier: Modifier, onChange: (Float, Float) -> Unit) {
    val s by rememberUpdatedState(start)
    val e by rememberUpdatedState(end)
    val density = LocalDensity.current
    // Handles sit near the screen edges, where Android's Back swipe would otherwise take the drag.
    BoxWithConstraints(modifier.systemGestureExclusion()) {
        val w = constraints.maxWidth.toFloat()
        val grab = with(density) { 24.dp.toPx() }
        Row(Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp)).background(Color(0xFF2C2C2E))) {
            frames.forEach { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.weight(1f).fillMaxHeight()) }
        }
        // Dim the parts that will be cut, outline the kept range in yellow, as in Photos.
        Canvas(
            Modifier.fillMaxSize().pointerInput(Unit) {
                var which = 0
                detectDragGestures(onDragStart = { p: Offset -> which = if (kotlin.math.abs(p.x - s * w) < kotlin.math.abs(p.x - e * w) && kotlin.math.abs(p.x - s * w) < grab * 2) 1 else 2 }) { change, d ->
                    change.consume()
                    val dx = d.x / w
                    if (which == 1) onChange((s + dx).coerceIn(0f, e - 0.05f), e) else onChange(s, (e + dx).coerceIn(s + 0.05f, 1f))
                }
            },
        ) {
            val l = start * size.width; val r = end * size.width
            drawRect(Color.Black.copy(alpha = 0.6f), Offset.Zero, androidx.compose.ui.geometry.Size(l, size.height))
            drawRect(Color.Black.copy(alpha = 0.6f), Offset(r, 0f), androidx.compose.ui.geometry.Size(size.width - r, size.height))
            val bw = 3.dp.toPx(); val hw = 12.dp.toPx()
            drawRect(accent, Offset(l, 0f), androidx.compose.ui.geometry.Size(r - l, bw))
            drawRect(accent, Offset(l, size.height - bw), androidx.compose.ui.geometry.Size(r - l, bw))
            drawRect(accent, Offset(l, 0f), androidx.compose.ui.geometry.Size(hw, size.height))
            drawRect(accent, Offset(r - hw, 0f), androidx.compose.ui.geometry.Size(hw, size.height))
            val px = playhead * size.width
            drawRect(Color.White, Offset(px - 1.5.dp.toPx(), 0f), androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height))
        }
    }
}

