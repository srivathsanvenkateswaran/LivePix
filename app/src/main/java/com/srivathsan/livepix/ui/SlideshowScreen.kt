package com.srivathsan.livepix.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.srivathsan.livepix.LibraryState
import com.srivathsan.livepix.data.Asset
import com.srivathsan.livepix.media.ImageLoader
import com.srivathsan.livepix.media.StillRenderer
import com.srivathsan.livepix.ui.components.GlassGroup
import com.srivathsan.livepix.ui.components.GlassIconButton
import com.srivathsan.livepix.ui.components.GroupIcon
import com.srivathsan.livepix.ui.theme.IosType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs

private enum class SlideTheme(val label: String) { KEN_BURNS("Ken Burns"), DISSOLVE("Dissolve"), SLIDE("Slide") }

private val SPEEDS = listOf(2_000L to "Fast", 3_500L to "Medium", 6_000L to "Slow")
private const val SIDE = 1600

/** Settings that outlive a single slideshow. */
private class SlideshowPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("slideshow", Context.MODE_PRIVATE)
    var theme: SlideTheme
        get() = runCatching { SlideTheme.valueOf(prefs.getString("theme", null) ?: "") }.getOrDefault(SlideTheme.KEN_BURNS)
        set(v) = prefs.edit().putString("theme", v.name).apply()
    var speed: Long
        get() = prefs.getLong("speed", SPEEDS[1].first)
        set(v) = prefs.edit().putLong("speed", v).apply()
    var repeat: Boolean
        get() = prefs.getBoolean("repeat", true)
        set(v) = prefs.edit().putBoolean("repeat", v).apply()
    var music: String?
        get() = prefs.getString("music", null)
        set(v) = prefs.edit().putString("music", v).apply()
}

private suspend fun loadSlide(context: Context, asset: Asset, state: LibraryState): Bitmap? {
    val edit = state.editOf(asset)
    val traits = state.traitsOf(asset)
    return when {
        asset.isVideo -> ImageLoader.thumbnail(context, asset.uri, 1024)
        StillRenderer.needsRender(asset, traits, edit) -> withContext(Dispatchers.Default) {
            runCatching { StillRenderer.render(context, asset, traits, edit, SIDE) }.getOrNull()
        }
        else -> ImageLoader.full(context, asset.uri, SIDE)
    }
}

/**
 * Full-screen slideshow with Ken Burns, Dissolve and Slide themes, optional music chosen from the
 * phone, and a title card when it plays a memory.
 */
@Composable
fun SlideshowScreen(
    assets: List<Asset>,
    state: LibraryState,
    title: String?,
    subtitle: String?,
    startKey: String?,
    onClose: () -> Unit,
    onShowAll: (() -> Unit)?,
) {
    if (assets.isEmpty()) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val context = LocalContext.current
    val view = LocalView.current
    val prefs = remember { SlideshowPrefs(context) }
    var theme by remember { mutableStateOf(prefs.theme) }
    var speed by remember { mutableStateOf(prefs.speed) }
    var repeat by remember { mutableStateOf(prefs.repeat) }
    var music by remember { mutableStateOf(prefs.music?.let(Uri::parse)) }
    var index by remember { mutableIntStateOf(assets.indexOfFirst { it.key == startKey }.coerceAtLeast(0)) }
    var playing by remember { mutableStateOf(true) }
    var controls by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    // The slide on screen; it only changes once the next picture has been decoded.
    var slide by remember { mutableStateOf<Pair<Int, Bitmap?>?>(null) }

    DisposableEffect(Unit) {
        val window = (context as Activity).window
        val insets = WindowCompat.getInsetsController(window, view)
        view.keepScreenOn = true
        insets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insets.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            view.keepScreenOn = false
            insets.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    val player = remember {
        ExoPlayer.Builder(context).build().apply { repeatMode = Player.REPEAT_MODE_ALL }
    }
    DisposableEffect(Unit) { onDispose { player.release() } }
    LaunchedEffect(music) {
        val uri = music
        if (uri == null) player.clearMediaItems()
        else { player.setMediaItem(MediaItem.fromUri(uri)); player.prepare() }
    }
    LaunchedEffect(playing, music) { player.playWhenReady = playing && music != null }

    val pickMusic = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            prefs.music = uri.toString()
            music = uri
        }
    }

    LaunchedEffect(index) {
        val bmp = loadSlide(context, assets[index], state)
        slide = index to bmp
    }
    // Advance only after the current slide is showing, so slow decodes don't skip a picture.
    LaunchedEffect(slide?.first, playing, speed) {
        if (!playing || slide?.first != index) return@LaunchedEffect
        // Decode ahead so the change is on time.
        assets.getOrNull((index + 1) % assets.size)?.let { loadSlide(context, it, state) }
        delay(speed)
        if (index == assets.lastIndex && !repeat) onClose() else index = (index + 1) % assets.size
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .clickable(remember { MutableInteractionSource() }, null) { controls = !controls },
    ) {
        AnimatedContent(
            targetState = slide,
            transitionSpec = {
                if (theme == SlideTheme.SLIDE) slideInHorizontally(tween(600)) { it } togetherWith slideOutHorizontally(tween(600)) { -it }
                else fadeIn(tween(900)) togetherWith fadeOut(tween(900))
            },
            label = "slide",
            modifier = Modifier.fillMaxSize(),
        ) { shown ->
            val (i, bmp) = shown ?: return@AnimatedContent
            if (bmp != null) SlideImage(bmp, i, theme, speed, playing)
        }

        if (title != null) {
            // The title card sits over the first picture for a few seconds.
            var showTitle by remember { mutableStateOf(true) }
            LaunchedEffect(Unit) { delay(3_500); showTitle = false }
            AnimatedVisibility(showTitle, enter = fadeIn(), exit = fadeOut(tween(800)), modifier = Modifier.align(Alignment.Center)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 32.dp)) {
                    Text(title, style = IosType.largeTitle, color = Color.White, textAlign = TextAlign.Center)
                    if (subtitle != null) Text(subtitle, style = IosType.subheadline.copy(fontWeight = FontWeight.Medium), color = Color.White, textAlign = TextAlign.Center)
                }
            }
        }

        val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        AnimatedVisibility(controls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(top = top + 8.dp, start = 16.dp, end = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    GlassIconButton(Icons.Outlined.Close, "Close", onClick = onClose)
                    if (onShowAll != null) GlassIconButton(Icons.Outlined.GridView, "Show all photos", onClick = onShowAll)
                }
                Row(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = bottom + 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    GlassGroup(dark = true) {
                        GroupIcon(Icons.Outlined.SkipPrevious, "Previous", Color.White) { index = (index - 1 + assets.size) % assets.size }
                        GroupIcon(if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, if (playing) "Pause" else "Play", Color.White) { playing = !playing }
                        GroupIcon(Icons.Outlined.SkipNext, "Next", Color.White) { index = (index + 1) % assets.size }
                    }
                    Box {
                        GlassIconButton(Icons.Outlined.MoreHoriz, "Slideshow options") { menu = true }
                        IosMenu(menu, onDismiss = { menu = false }) {
                            SlideTheme.entries.forEach { t ->
                                MenuRow(t.label, checked = t == theme) { theme = t; prefs.theme = t; menu = false }
                            }
                            SPEEDS.forEach { (ms, label) ->
                                MenuRow(label, checked = ms == speed) { speed = ms; prefs.speed = ms; menu = false }
                            }
                            MenuRow("Repeat", checked = repeat) { repeat = !repeat; prefs.repeat = repeat; menu = false }
                            MenuRow(if (music == null) "Choose Music…" else "Change Music (${musicName(context, music)})") {
                                menu = false; pickMusic.launch(arrayOf("audio/*"))
                            }
                            if (music != null) MenuRow("No Music") { music = null; prefs.music = null; menu = false }
                        }
                    }
                }
            }
        }
    }
}

private fun musicName(context: Context, uri: Uri?): String {
    uri ?: return ""
    return runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0).substringBeforeLast('.') else null
        }
    }.getOrNull()?.take(24) ?: "Track"
}

@Composable
private fun SlideImage(bitmap: Bitmap, index: Int, theme: SlideTheme, speed: Long, playing: Boolean) {
    val view = LocalView.current
    val progress = remember { Animatable(0f) }
    if (theme == SlideTheme.KEN_BURNS) {
        // The drift continues through the dissolve into the next picture, and holds while paused.
        LaunchedEffect(playing, speed) {
            if (playing) progress.animateTo(1f, tween(((1f - progress.value) * (speed + 1_800)).toInt(), easing = LinearEasing))
        }
    }
    val screen = view.width.toFloat() / view.height.coerceAtLeast(1)
    val picture = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1)
    // Fill the screen when the shapes are close; otherwise show the whole picture on black.
    val fill = theme == SlideTheme.KEN_BURNS && abs(screen - picture) / screen < 0.35f
    // Alternate zooming in and out, and the direction of drift, from one picture to the next.
    val zoomIn = index % 2 == 0
    val direction = if (index % 4 < 2) 1f else -1f
    Image(
        bitmap.asImageBitmap(), null,
        contentScale = if (fill) ContentScale.Crop else ContentScale.Fit,
        modifier = Modifier.fillMaxSize().graphicsLayer {
            if (theme == SlideTheme.KEN_BURNS) {
                val t = if (zoomIn) progress.value else 1f - progress.value
                val s = 1.03f + 0.11f * t
                scaleX = s
                scaleY = s
                translationX = direction * (t - 0.5f) * size.width * 0.03f
                translationY = direction * (0.5f - t) * size.height * 0.015f
            }
        },
    )
}
