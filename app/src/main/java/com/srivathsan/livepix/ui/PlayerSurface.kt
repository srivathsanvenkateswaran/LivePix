package com.srivathsan.livepix.ui

import android.view.TextureView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout

/**
 * Renders a player into a TextureView, letterboxed to the video's aspect ratio. TextureView is
 * part of the normal view hierarchy, so it zooms, fades and animates with the Compose content
 * around it; PlayerView's default SurfaceView is a separate window layer and does not.
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerTexture(player: ExoPlayer, modifier: Modifier = Modifier) {
    val holder = androidx.compose.runtime.remember(player) { arrayOfNulls<AspectRatioFrameLayout>(1) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(size: VideoSize) {
                if (size.width > 0 && size.height > 0) {
                    holder[0]?.setAspectRatio(size.width * size.pixelWidthHeightRatio / size.height)
                }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener); player.clearVideoSurface() }
    }
    AndroidView(
        factory = { ctx ->
            AspectRatioFrameLayout(ctx).apply {
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                val texture = TextureView(ctx)
                addView(texture, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                player.setVideoTextureView(texture)
                player.videoSize.takeIf { it.width > 0 }?.let { setAspectRatio(it.width * it.pixelWidthHeightRatio / it.height) }
                holder[0] = this
            }.let { inner ->
                // Centre the letterboxed frame in the available space.
                FrameLayout(ctx).apply {
                    addView(inner, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, android.view.Gravity.CENTER))
                }
            }
        },
        modifier = modifier,
    )
}
