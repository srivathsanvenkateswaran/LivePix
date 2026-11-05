package com.srivathsan.livepix.media

import kotlin.math.abs

/** Luma at 1/4 and 1/16 scale, for estimating camera shake between frames. */
class Pyramid(val quarter: Level, val sixteenth: Level) {
    class Level(val w: Int, val h: Int, val luma: IntArray, val scale: Int)

    companion object {
        fun of(argb: IntArray, w: Int, h: Int): Pyramid = Pyramid(level(argb, w, h, 4), level(argb, w, h, 16))

        private fun level(argb: IntArray, w: Int, h: Int, scale: Int): Level {
            val lw = maxOf(1, w / scale)
            val lh = maxOf(1, h / scale)
            val out = IntArray(lw * lh)
            for (y in 0 until lh) for (x in 0 until lw) {
                var sum = 0
                // Sample a 2x2 grid inside each block; enough for alignment and much cheaper.
                for (j in 0 until 2) for (i in 0 until 2) {
                    val c = argb[(y * scale + j * scale / 2) * w + x * scale + i * scale / 2]
                    sum += ((c shr 16 and 0xFF) * 77 + (c shr 8 and 0xFF) * 150 + (c and 0xFF) * 29) shr 8
                }
                out[y * lw + x] = sum / 4
            }
            return Level(lw, lh, out, scale)
        }
    }
}

/** Global translation between two frames by coarse-to-fine block matching. */
object FrameAligner {
    private const val COARSE_RANGE = 5
    private const val FINE_RANGE = 3

    /** Offset (dx, dy) in full-resolution pixels such that frame(x + dx, y + dy) ≈ reference(x, y). */
    fun offset(reference: Pyramid, frame: Pyramid): Pair<Int, Int> {
        val (cx, cy) = search(reference.sixteenth, frame.sixteenth, 0, 0, COARSE_RANGE)
        val ratio = reference.sixteenth.scale / reference.quarter.scale
        val (fx, fy) = search(reference.quarter, frame.quarter, cx * ratio, cy * ratio, FINE_RANGE)
        return fx * reference.quarter.scale to fy * reference.quarter.scale
    }

    private fun search(a: Pyramid.Level, b: Pyramid.Level, cx: Int, cy: Int, range: Int): Pair<Int, Int> {
        var best = Long.MAX_VALUE
        var bx = cx; var by = cy
        // Ignore a border so shifted windows stay inside the frame.
        val margin = range + maxOf(abs(cx), abs(cy)) + 1
        if (a.w <= 2 * margin || a.h <= 2 * margin) return cx to cy
        for (dy in cy - range..cy + range) for (dx in cx - range..cx + range) {
            var cost = 0L
            var y = margin
            while (y < a.h - margin) {
                val ra = y * a.w
                val rb = (y + dy) * b.w + dx
                var x = margin
                while (x < a.w - margin) {
                    cost += abs(a.luma[ra + x] - b.luma[rb + x])
                    x++
                }
                if (cost >= best) break
                y++
            }
            if (cost < best) { best = cost; bx = dx; by = dy }
        }
        return bx to by
    }
}
