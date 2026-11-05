package com.srivathsan.livepix.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Crop tool state: quarter turns, horizontal flip, straighten angle, and a crop rectangle in
 * normalized coordinates of the rotated, straightened image.
 */
data class CropSpec(
    val quarterTurns: Int = 0,
    val flip: Boolean = false,
    val straighten: Float = 0f,
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f,
) {
    val isIdentity: Boolean
        get() = quarterTurns % 4 == 0 && !flip && straighten == 0f && left == 0f && top == 0f && right == 1f && bottom == 1f

    /** Rotation and flip only, before cropping. */
    fun oriented(src: Bitmap): Bitmap {
        if (quarterTurns % 4 == 0 && !flip && straighten == 0f) return src
        val turns = ((quarterTurns % 4) + 4) % 4
        val m = Matrix()
        if (flip) m.postScale(-1f, 1f)
        if (turns != 0) m.postRotate(90f * turns)
        var out = if (flip || turns != 0) Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true) else src
        if (straighten != 0f) out = straightened(out, straighten)
        return out
    }

    fun apply(src: Bitmap): Bitmap {
        if (isIdentity) return src
        val o = oriented(src)
        val l = (left.coerceIn(0f, 1f) * o.width).roundToInt()
        val t = (top.coerceIn(0f, 1f) * o.height).roundToInt()
        val r = (right.coerceIn(0f, 1f) * o.width).roundToInt()
        val b = (bottom.coerceIn(0f, 1f) * o.height).roundToInt()
        if (r - l < 2 || b - t < 2) return o
        return Bitmap.createBitmap(o, l, t, r - l, b - t)
    }

    companion object {
        /** Scale needed so a rotated image still covers its original frame without empty corners. */
        fun coverScale(w: Int, h: Int, degrees: Float): Float {
            val a = Math.toRadians(abs(degrees).toDouble())
            return (cos(a) + sin(a) * max(w, h).toFloat() / min(w, h)).toFloat()
        }

        private fun straightened(src: Bitmap, degrees: Float): Bitmap {
            val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            val s = coverScale(src.width, src.height, degrees)
            val m = Matrix().apply {
                postTranslate(-src.width / 2f, -src.height / 2f)
                postRotate(degrees)
                postScale(s, s)
                postTranslate(src.width / 2f, src.height / 2f)
            }
            Canvas(out).drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG))
            return out
        }

        /** A centred crop rectangle with the given aspect (w/h) inside an image of [imageAspect]. */
        fun centred(aspect: Float, imageAspect: Float): RectF =
            if (aspect > imageAspect) {
                val h = imageAspect / aspect
                RectF(0f, (1 - h) / 2, 1f, (1 + h) / 2)
            } else {
                val w = aspect / imageAspect
                RectF((1 - w) / 2, 0f, (1 + w) / 2, 1f)
            }
    }
}
