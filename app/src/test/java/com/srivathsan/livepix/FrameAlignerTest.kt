package com.srivathsan.livepix

import com.srivathsan.livepix.media.FrameAligner
import com.srivathsan.livepix.media.Pyramid
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.sin

class FrameAlignerTest {
    private val w = 640
    private val h = 480

    /** Smooth texture with features at several scales, sampled at (x + ox, y + oy). */
    private fun frame(ox: Int, oy: Int) = IntArray(w * h) { i ->
        val x = (i % w + ox).toDouble()
        val y = (i / w + oy).toDouble()
        val v = (128 + 50 * sin(x / 23.0) * sin(y / 31.0) + 40 * sin((x + 2 * y) / 9.0) + 30 * sin(x * y / 4000.0)).toInt().coerceIn(0, 255)
        (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }

    @Test
    fun recoversHandShake() {
        val reference = Pyramid.of(frame(0, 0), w, h)
        for ((dx, dy) in listOf(0 to 0, 12 to -8, -20 to 16, 36 to 4)) {
            // frame(x + dx, y + dy) == reference(x, y) when the frame content is shifted by (-dx, -dy).
            val moved = Pyramid.of(frame(-dx, -dy), w, h)
            assertEquals("shift ($dx, $dy)", dx to dy, FrameAligner.offset(reference, moved))
        }
    }
}
