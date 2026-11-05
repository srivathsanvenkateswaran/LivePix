package com.srivathsan.livepix.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import kotlin.math.max
import kotlin.math.roundToInt

/** Frame access for the movie half of a Live Photo. */
class LiveMedia(context: Context, uri: Uri) : AutoCloseable {
    private val retriever = MediaMetadataRetriever().apply { setDataSource(context, uri) }

    val durationUs: Long =
        (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1000
    val frameCount: Int =
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull() ?: 0

    /** Exact frame at [timeUs], scaled so its longest side is at most [maxSide]. */
    fun frameAt(timeUs: Long, maxSide: Int): Bitmap? {
        val (w, h) = scaledSize(maxSide)
        val params = MediaMetadataRetriever.BitmapParams().apply { preferredConfig = Bitmap.Config.ARGB_8888 }
        return runCatching {
            retriever.getScaledFrameAtTime(timeUs.coerceIn(0, durationUs), MediaMetadataRetriever.OPTION_CLOSEST, w, h, params)
        }.getOrNull()
    }

    /** Evenly spaced frames, for the edit-screen filmstrip and Bounce playback. */
    fun frames(
        count: Int,
        maxSide: Int,
        config: Bitmap.Config = Bitmap.Config.ARGB_8888,
        onFrame: (index: Int, Bitmap) -> Unit = { _, _ -> },
    ): List<Pair<Long, Bitmap>> {
        if (count <= 0) return emptyList()
        val started = System.nanoTime()
        val (w, h) = scaledSize(maxSide)
        val params = MediaMetadataRetriever.BitmapParams().apply { preferredConfig = config }
        val out = ArrayList<Pair<Long, Bitmap>>(count)
        for (k in 0 until count) {
            val t = durationUs * k / max(1, count - 1)
            // Scaled in native code, which is much cheaper than full-size frames scaled here.
            val frame = runCatching {
                retriever.getScaledFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST, w, h, params)
            }.getOrNull() ?: continue
            out += t to frame
            onFrame(k, frame)
        }
        Log.d(TAG, "frames($count, $maxSide): ${(System.nanoTime() - started) / 1_000_000} ms")
        return out
    }

    /**
     * Long Exposure: the average of every frame, like iPhone's effect that turns moving water
     * silky. Frames are first aligned to the first frame so hand shake doesn't smear the
     * static parts of the scene. Frames are accumulated one at a time so memory stays flat.
     */
    fun longExposure(maxSide: Int): Bitmap? {
        val total = if (frameCount > 0) frameCount else 0
        if (total == 0) return null
        val params = MediaMetadataRetriever.BitmapParams().apply { preferredConfig = Bitmap.Config.ARGB_8888 }
        var sums: IntArray? = null
        var counts: IntArray? = null
        var w = 0; var h = 0; var n = 0
        var px = IntArray(0)
        var reference: Pyramid? = null
        for (i in 0 until total) {
            val raw = runCatching { retriever.getFrameAtIndex(i, params) }.getOrNull() ?: continue
            val frame = scaleDown(raw, maxSide)
            if (sums == null) {
                w = frame.width; h = frame.height
                sums = IntArray(w * h * 3); counts = IntArray(w * h); px = IntArray(w * h)
            }
            if (frame.width != w || frame.height != h) continue
            frame.getPixels(px, 0, w, 0, 0, w, h)
            frame.recycle()
            val pyramid = Pyramid.of(px, w, h)
            val (dx, dy) = reference?.let { FrameAligner.offset(it, pyramid) } ?: (0 to 0)
            if (reference == null) reference = pyramid
            val s = sums
            val c = counts!!
            // Pixel (x, y) of the reference view comes from (x + dx, y + dy) in this frame.
            for (y in 0 until h) {
                val sy = y + dy
                if (sy < 0 || sy >= h) continue
                for (x in 0 until w) {
                    val sx = x + dx
                    if (sx < 0 || sx >= w) continue
                    val col = px[sy * w + sx]
                    val p = y * w + x
                    s[p * 3] += col shr 16 and 0xFF
                    s[p * 3 + 1] += col shr 8 and 0xFF
                    s[p * 3 + 2] += col and 0xFF
                    c[p]++
                }
            }
            n++
        }
        val s = sums ?: return null
        val c = counts!!
        for (p in px.indices) {
            val k = c[p].coerceAtLeast(1)
            px[p] = (0xFF shl 24) or ((s[p * 3] / k) shl 16) or ((s[p * 3 + 1] / k) shl 8) or (s[p * 3 + 2] / k)
        }
        return if (n == 0) null else Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun scaledSize(maxSide: Int): Pair<Int, Int> {
        var w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: maxSide
        var h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: maxSide
        val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        if (rotation % 180 != 0) { val t = w; w = h; h = t }
        val s = minOf(1f, maxSide.toFloat() / max(w, h))
        return (w * s).roundToInt().coerceAtLeast(1) to (h * s).roundToInt().coerceAtLeast(1)
    }

    private fun scaleDown(b: Bitmap, maxSide: Int): Bitmap {
        val longest = max(b.width, b.height)
        if (longest <= maxSide) return b
        val s = maxSide.toFloat() / longest
        return Bitmap.createScaledBitmap(b, (b.width * s).roundToInt(), (b.height * s).roundToInt(), true).also {
            if (it !== b) b.recycle()
        }
    }

    override fun close() = retriever.release()

    private companion object { const val TAG = "LiveMedia" }
}
