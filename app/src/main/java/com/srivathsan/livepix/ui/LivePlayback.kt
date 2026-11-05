package com.srivathsan.livepix.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.srivathsan.livepix.media.LiveMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Plays the movie half of a Live Photo over the still. It stays invisible until its first frame
 * renders, so the still never flashes black, and fades out when done.
 */
@OptIn(UnstableApi::class)
@Composable
fun LiveVideoLayer(
    uri: Uri,
    loop: Boolean,
    withSound: Boolean,
    onEnded: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var firstFrame by remember(uri) { mutableStateOf(false) }
    var ending by remember(uri) { mutableStateOf(false) }
    val alpha by animateFloatAsState(if (firstFrame && !ending) 1f else 0f, tween(if (ending) 260 else 120), label = "live")
    val endedCallback by rememberUpdatedState(onEnded)
    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            volume = if (withSound) 1f else 0f
            playWhenReady = true
            prepare()
        }
    }
    LaunchedEffect(loop, withSound) {
        player.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        player.volume = if (withSound) 1f else 0f
    }
    LaunchedEffect(ending) {
        if (ending) { delay(260); endedCallback() }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() { firstFrame = true }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) ending = true
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener); player.release() }
    }
    AndroidView(
        factory = {
            PlayerView(it).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                setKeepContentOnPlayerReset(true)
                this.player = player
            }
        },
        update = { it.player = player },
        modifier = modifier.fillMaxSize().alpha(alpha),
    )
}

/** Bounce: frames played forward then backward, forever. */
@Composable
fun BounceLayer(uri: Uri, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var frames by remember(uri) { mutableStateOf<List<Bitmap>>(emptyList()) }
    var durationUs by remember(uri) { mutableStateOf(3_000_000L) }
    var index by remember(uri) { mutableIntStateOf(0) }
    LaunchedEffect(uri) {
        withContext(Dispatchers.IO) {
            LiveMedia(context, uri).use { m ->
                durationUs = m.durationUs.coerceAtLeast(500_000)
                frames = m.frames(36, 1080, Bitmap.Config.RGB_565).map { it.second }
            }
        }
    }
    LaunchedEffect(frames) {
        if (frames.size < 2) return@LaunchedEffect
        val frameMs = (durationUs / 1000 / frames.size).coerceIn(20, 80)
        var dir = 1
        while (isActive) {
            delay(frameMs)
            val next = index + dir
            if (next !in frames.indices) dir = -dir
            index = (index + dir).coerceIn(frames.indices)
        }
    }
    frames.getOrNull(index)?.let {
        Image(it.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = modifier.fillMaxSize())
    }
}
