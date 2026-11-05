package com.srivathsan.livepix.media

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** iPhone's Portrait Lighting options, approximated from the depth map and portrait matte. */
enum class PortraitLighting(val label: String) {
    NATURAL("Natural Light"),
    STUDIO("Studio Light"),
    CONTOUR("Contour Light"),
    STAGE("Stage Light"),
    STAGE_MONO("Stage Light Mono"),
    HIGH_KEY_MONO("High-Key Light Mono"),
}

data class PortraitParams(
    /** f-number, 1.4 (strongest blur) to 16 (almost none), like the iPhone depth control. */
    val aperture: Float = 4.5f,
    /** Disparity (0..255) that stays sharp. */
    val focusDisparity: Float,
    val lighting: PortraitLighting = PortraitLighting.NATURAL,
    /** 0..1, the Portrait Lighting intensity slider. */
    val lightingIntensity: Float = 0.5f,
)

/**
 * Synthetic depth of field. The photo is blurred at several radii; each output pixel blends the
 * two levels around its circle of confusion. Blur layers use normalized convolution weighted by
 * how out of focus each source pixel is, so a sharp subject does not bleed a halo into the
 * blurred background around it.
 */
object PortraitRenderer {
    const val MIN_APERTURE = 1.4f
    const val MAX_APERTURE = 16f
    private const val LEVELS = 6
    private const val MAX_BLUR_FRACTION = 0.028f
    private const val FOCUS_FALLOFF = 70f

    fun strength(aperture: Float): Float {
        val a = aperture.coerceIn(MIN_APERTURE, MAX_APERTURE)
        return ((1f / a - 1f / MAX_APERTURE) / (1f / MIN_APERTURE - 1f / MAX_APERTURE)).coerceIn(0f, 1f)
    }

    fun render(source: Bitmap, depth: DepthData, params: PortraitParams): Bitmap {
        val w = source.width
        val h = source.height
        val src = IntArray(w * h)
        source.getPixels(src, 0, w, 0, 0, w, h)

        // Per-pixel circle of confusion (0..1); subject mask (0..1) only when a light needs it.
        val coc = FloatArray(w * h)
        val subject = if (params.lighting != PortraitLighting.NATURAL) FloatArray(w * h) else null
        val focus = params.focusDisparity
        for (y in 0 until h) {
            val v = (y + 0.5f) / h
            for (x in 0 until w) {
                val u = (x + 0.5f) / w
                val d = depth.disparityAt(u, v)
                val matte = if (depth.matte != null) depth.matteAt(u, v) / 255f else -1f
                var c = smoothstep(0.04f, 1f, abs(d - focus) / FOCUS_FALLOFF)
                val s = if (matte >= 0f) matte else smoothstep(focus - 28f, focus - 6f, d)
                if (matte >= 0f) c *= (1f - matte)
                coc[y * w + x] = c
                subject?.set(y * w + x, s)
            }
        }

        val maxRadius = max(w, h) * MAX_BLUR_FRACTION * strength(params.aperture)
        // Composited in place: each pixel only reads its own source value.
        val out = src
        if (maxRadius >= 0.75f) {
            val levels = (1 until LEVELS).map { k -> BlurLevel.build(src, coc, w, h, maxRadius * k / (LEVELS - 1)) }
            for (i in 0 until w * h) {
                val t = coc[i] * (LEVELS - 1)
                val lo = t.toInt().coerceAtMost(LEVELS - 2)
                val f = t - lo
                val x = i % w
                val y = i / w
                val c0 = if (lo == 0) src[i] else levels[lo - 1].sample(x, y, src[i])
                out[i] = if (f < 0.004f) c0 else lerpColor(c0, levels[lo].sample(x, y, src[i]), f)
            }
        }

        if (subject != null) applyLighting(out, subject, params.lighting, params.lightingIntensity)
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    /** Lighting without changing depth of field, for when Portrait blur is off but a light is chosen. */
    private fun applyLighting(px: IntArray, subject: FloatArray, lighting: PortraitLighting, intensity: Float) {
        if (lighting == PortraitLighting.NATURAL) return
        val k = intensity.coerceIn(0f, 1f)
        for (i in px.indices) {
            val c = px[i]
            var r = (c shr 16 and 0xFF) / 255f
            var g = (c shr 8 and 0xFF) / 255f
            var b = (c and 0xFF) / 255f
            val s = subject[i]
            when (lighting) {
                PortraitLighting.STUDIO -> {
                    val lift = 1f + 0.35f * k * s
                    r = 1f - (1f - r) / lift; g = 1f - (1f - g) / lift; b = 1f - (1f - b) / lift
                }
                PortraitLighting.CONTOUR -> {
                    val contrast = 1f + 0.45f * k * s
                    r = (r - 0.5f) * contrast + 0.5f; g = (g - 0.5f) * contrast + 0.5f; b = (b - 0.5f) * contrast + 0.5f
                    val dim = 1f - 0.25f * k * (1f - s)
                    r *= dim; g *= dim; b *= dim
                }
                PortraitLighting.STAGE, PortraitLighting.STAGE_MONO -> {
                    if (lighting == PortraitLighting.STAGE_MONO) {
                        val l = luma(r, g, b) * (1f + 0.2f * k); r = l; g = l; b = l
                    }
                    val keep = smoothstep(0.15f, 0.85f, s)
                    // Near-black stage like iPhone; intensity lifts it slightly towards the scene.
                    val falloff = keep + (1f - keep) * (0.18f * (1f - k) * (1f - k))
                    r *= falloff; g *= falloff; b *= falloff
                }
                PortraitLighting.HIGH_KEY_MONO -> {
                    val l = (luma(r, g, b) - 0.5f) * (1f + 0.3f * k) + 0.55f
                    val keep = smoothstep(0.15f, 0.85f, s)
                    val v = l * keep + (1f - keep) * (0.92f + 0.08f * k)
                    r = v; g = v; b = v
                }
                PortraitLighting.NATURAL -> Unit
            }
            px[i] = (0xFF shl 24) or (to8(r) shl 16) or (to8(g) shl 8) or to8(b)
        }
    }

    private fun luma(r: Float, g: Float, b: Float) = 0.299f * r + 0.587f * g + 0.114f * b
    private fun to8(v: Float) = (v * 255f).roundToInt().coerceIn(0, 255)

    internal fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun lerpColor(a: Int, b: Int, t: Float): Int {
        val r = ((a shr 16 and 0xFF) + ((b shr 16 and 0xFF) - (a shr 16 and 0xFF)) * t).toInt()
        val g = ((a shr 8 and 0xFF) + ((b shr 8 and 0xFF) - (a shr 8 and 0xFF)) * t).toInt()
        val bl = ((a and 0xFF) + ((b and 0xFF) - (a and 0xFF)) * t).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
    }

    /** One blurred copy of the image, stored downscaled with premultiplied weights. */
    private class BlurLevel(
        val scale: Int, val sw: Int, val sh: Int,
        val r: FloatArray, val g: FloatArray, val b: FloatArray, val wt: FloatArray,
    ) {
        fun sample(x: Int, y: Int, fallback: Int): Int {
            val fx = ((x + 0.5f) / scale - 0.5f).coerceIn(0f, sw - 1f)
            val fy = ((y + 0.5f) / scale - 0.5f).coerceIn(0f, sh - 1f)
            val x0 = fx.toInt(); val y0 = fy.toInt()
            val x1 = min(x0 + 1, sw - 1); val y1 = min(y0 + 1, sh - 1)
            val ax = fx - x0; val ay = fy - y0
            val i00 = y0 * sw + x0; val i10 = y0 * sw + x1; val i01 = y1 * sw + x0; val i11 = y1 * sw + x1
            val w00 = (1 - ax) * (1 - ay); val w10 = ax * (1 - ay); val w01 = (1 - ax) * ay; val w11 = ax * ay
            val ww = wt[i00] * w00 + wt[i10] * w10 + wt[i01] * w01 + wt[i11] * w11
            if (ww < 1e-4f) return fallback
            val rr = (r[i00] * w00 + r[i10] * w10 + r[i01] * w01 + r[i11] * w11) / ww
            val gg = (g[i00] * w00 + g[i10] * w10 + g[i01] * w01 + g[i11] * w11) / ww
            val bb = (b[i00] * w00 + b[i10] * w10 + b[i01] * w01 + b[i11] * w11) / ww
            return (0xFF shl 24) or (rr.toInt().coerceIn(0, 255) shl 16) or (gg.toInt().coerceIn(0, 255) shl 8) or bb.toInt().coerceIn(0, 255)
        }

        companion object {
            fun build(src: IntArray, coc: FloatArray, w: Int, h: Int, radius: Float): BlurLevel {
                // At least half resolution: blurred layers lose nothing visible and use 4x less memory.
                val scale = max(2, (radius / 2.5f).toInt())
                val sw = ceil(w / scale.toFloat()).toInt()
                val sh = ceil(h / scale.toFloat()).toInt()
                val r = FloatArray(sw * sh); val g = FloatArray(sw * sh); val b = FloatArray(sw * sh); val wt = FloatArray(sw * sh)
                for (y in 0 until h) {
                    val row = (y / scale) * sw
                    for (x in 0 until w) {
                        val i = y * w + x
                        // In-focus pixels barely contribute, which keeps the subject out of the bokeh.
                        val weight = max(0.015f, coc[i])
                        val c = src[i]
                        val j = row + x / scale
                        r[j] += (c shr 16 and 0xFF) * weight
                        g[j] += (c shr 8 and 0xFF) * weight
                        b[j] += (c and 0xFF) * weight
                        wt[j] += weight
                    }
                }
                val boxRadius = max(1, (radius / scale * 0.58f).roundToInt())
                val tmp = FloatArray(max(sw, sh))
                for (arr in arrayOf(r, g, b, wt)) repeat(3) { boxBlur(arr, sw, sh, boxRadius, tmp) }
                return BlurLevel(scale, sw, sh, r, g, b, wt)
            }

            private fun boxBlur(a: FloatArray, w: Int, h: Int, rad: Int, tmp: FloatArray) {
                for (y in 0 until h) blurLine(a, y * w, 1, w, rad, tmp)
                for (x in 0 until w) blurLine(a, x, w, h, rad, tmp)
            }

            private fun blurLine(a: FloatArray, start: Int, step: Int, n: Int, rad: Int, tmp: FloatArray) {
                var sum = 0f
                for (k in -rad..rad) sum += a[start + k.coerceIn(0, n - 1) * step]
                val inv = 1f / (2 * rad + 1)
                for (i in 0 until n) {
                    tmp[i] = sum * inv
                    sum += a[start + (i + rad + 1).coerceAtMost(n - 1) * step] - a[start + (i - rad).coerceAtLeast(0) * step]
                }
                for (i in 0 until n) a[start + i * step] = tmp[i]
            }
        }
    }
}
