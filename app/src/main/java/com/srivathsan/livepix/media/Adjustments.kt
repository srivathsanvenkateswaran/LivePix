package com.srivathsan.livepix.media

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** The Adjust tools from Photos' editor, each from -100 to 100 unless noted. */
enum class Adjust(val label: String, val min: Float = -100f) {
    EXPOSURE("Exposure"),
    BRILLIANCE("Brilliance"),
    HIGHLIGHTS("Highlights"),
    SHADOWS("Shadows"),
    CONTRAST("Contrast"),
    BRIGHTNESS("Brightness"),
    BLACK_POINT("Black Point"),
    SATURATION("Saturation"),
    VIBRANCE("Vibrance"),
    WARMTH("Warmth"),
    TINT("Tint"),
    SHARPNESS("Sharpness", 0f),
    DEFINITION("Definition", 0f),
    NOISE_REDUCTION("Noise Reduction", 0f),
    VIGNETTE("Vignette"),
}

/** Photos' filters. */
enum class PhotoFilter(val label: String) {
    ORIGINAL("Original"),
    VIVID("Vivid"),
    VIVID_WARM("Vivid Warm"),
    VIVID_COOL("Vivid Cool"),
    DRAMATIC("Dramatic"),
    DRAMATIC_WARM("Dramatic Warm"),
    DRAMATIC_COOL("Dramatic Cool"),
    MONO("Mono"),
    SILVERTONE("Silvertone"),
    NOIR("Noir"),
}

/**
 * Colour and tone pipeline for the editor. Everything that depends only on a pixel's colour is
 * baked into a 33³ lookup table, so changing a dial costs ~36k evaluations instead of one per
 * pixel. Sharpness, Definition, Noise Reduction and Vignette depend on neighbours or position and
 * run as separate passes.
 */
object ToneEngine {
    private const val N = 33

    fun isIdentity(values: Map<Adjust, Float>, filter: PhotoFilter, intensity: Float): Boolean =
        values.values.all { it == 0f } && (filter == PhotoFilter.ORIGINAL || intensity <= 0f)

    /**
     * "Auto": pick exposure, contrast, highlights and shadows from the luminance histogram, the
     * way the magic-wand button nudges a photo towards a full, balanced range.
     */
    fun auto(bitmap: Bitmap): Map<Adjust, Float> {
        val step = max(1, (bitmap.width * bitmap.height / 40_000.0).let { sqrt(it).toInt() })
        val hist = IntArray(256)
        var n = 0
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val c = bitmap.getPixel(x, y)
                hist[luma8(c)]++; n++
                x += step
            }
            y += step
        }
        fun pct(p: Double): Int { var acc = 0; for (i in 0 until 256) { acc += hist[i]; if (acc >= n * p) return i }; return 255 }
        val lo = pct(0.01); val mid = pct(0.5); val hi = pct(0.99)
        val exposure = ((118 - mid) / 1.6f).coerceIn(-40f, 40f)
        val contrast = ((200 - (hi - lo)) / 4f).coerceIn(-10f, 30f)
        return mapOf(
            Adjust.EXPOSURE to exposure.roundToInt().toFloat(),
            Adjust.CONTRAST to contrast.roundToInt().toFloat(),
            Adjust.HIGHLIGHTS to if (hi > 245) -25f else 0f,
            Adjust.SHADOWS to if (lo < 12) 20f else 0f,
            Adjust.BRILLIANCE to 15f,
            Adjust.VIBRANCE to 10f,
        ).filterValues { it != 0f }
    }

    fun apply(src: Bitmap, values: Map<Adjust, Float>, filter: PhotoFilter, intensity: Float): Bitmap {
        if (isIdentity(values, filter, intensity)) return src
        val w = src.width; val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        fun v(a: Adjust) = (values[a] ?: 0f) / 100f

        // Spatial passes before tone: noise reduction smooths, sharpening/definition add detail.
        val nr = v(Adjust.NOISE_REDUCTION)
        if (nr > 0f) blend(px, blurred(px, w, h, 1), nr * 0.7f)
        val sharp = v(Adjust.SHARPNESS)
        if (sharp > 0f) unsharp(px, blurred(px, w, h, 1), sharp * 1.2f)
        val def = v(Adjust.DEFINITION)
        if (def > 0f) unsharp(px, blurred(px, w, h, max(4, max(w, h) / 120)), def * 0.6f)

        val lut = buildLut(values, filter, intensity)
        applyLut(px, lut)

        val vig = v(Adjust.VIGNETTE)
        if (vig != 0f) vignette(px, w, h, vig)
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun buildLut(values: Map<Adjust, Float>, filter: PhotoFilter, intensity: Float): FloatArray {
        val lut = FloatArray(N * N * N * 3)
        val out = FloatArray(3)
        var i = 0
        for (bi in 0 until N) for (gi in 0 until N) for (ri in 0 until N) {
            val r = ri / (N - 1f); val g = gi / (N - 1f); val b = bi / (N - 1f)
            tone(r, g, b, values, out)
            if (filter != PhotoFilter.ORIGINAL && intensity > 0f) {
                val fr = out[0]; val fg = out[1]; val fb = out[2]
                filterColor(fr, fg, fb, filter, out)
                out[0] = fr + (out[0] - fr) * intensity
                out[1] = fg + (out[1] - fg) * intensity
                out[2] = fb + (out[2] - fb) * intensity
            }
            lut[i++] = out[0]; lut[i++] = out[1]; lut[i++] = out[2]
        }
        return lut
    }

    private fun tone(r0: Float, g0: Float, b0: Float, values: Map<Adjust, Float>, out: FloatArray) {
        fun v(a: Adjust) = (values[a] ?: 0f) / 100f
        // Work in linear light for exposure, then back to display gamma for tone shaping.
        var r = srgbToLinear(r0); var g = srgbToLinear(g0); var b = srgbToLinear(b0)
        val ev = 2f.pow(v(Adjust.EXPOSURE) * 2f)
        r *= ev; g *= ev; b *= ev
        // Warmth and tint as white-balance gains.
        val warm = v(Adjust.WARMTH); val tint = v(Adjust.TINT)
        r *= 1f + 0.25f * warm; b *= 1f - 0.25f * warm
        g *= 1f - 0.18f * tint; r *= 1f + 0.06f * tint; b *= 1f + 0.06f * tint
        r = linearToSrgb(r); g = linearToSrgb(g); b = linearToSrgb(b)

        var l = luma(r, g, b)
        // Highlights / shadows: lift or pull the ends of the tone curve, weighted by luminance.
        val hiAmt = v(Adjust.HIGHLIGHTS); val shAmt = v(Adjust.SHADOWS)
        val brill = v(Adjust.BRILLIANCE)
        val hiW = smooth(0.45f, 1f, l); val shW = 1f - smooth(0f, 0.55f, l)
        var dl = hiAmt * 0.35f * hiW * (if (hiAmt < 0) l else 1f - l) +
            shAmt * 0.45f * shW * (if (shAmt > 0) (1f - l) else l)
        // Brilliance: brighten shadows and recover highlights together, plus a little contrast.
        dl += brill * (0.25f * shW * (1f - l) - 0.15f * hiW * l)
        // Brightness: midtone gamma.
        val bright = v(Adjust.BRIGHTNESS)
        if (bright != 0f) { val nl = l.coerceIn(0f, 1f).pow(1f / (1f + bright * 0.6f)); dl += nl - l }
        if (dl != 0f) { r += dl; g += dl; b += dl; l += dl }

        // Contrast around mid-grey (brilliance adds a touch).
        val con = v(Adjust.CONTRAST) + brill * 0.15f
        if (con != 0f) {
            val k = 1f + con * 0.75f
            r = (r - 0.5f) * k + 0.5f; g = (g - 0.5f) * k + 0.5f; b = (b - 0.5f) * k + 0.5f
        }
        // Black point: sets where black sits.
        val bp = v(Adjust.BLACK_POINT)
        if (bp != 0f) {
            val t = bp * 0.12f
            r = (r - t) / (1f - t); g = (g - t) / (1f - t); b = (b - t) / (1f - t)
        }
        // Saturation and vibrance (vibrance favours muted colours and spares skin a little).
        l = luma(r, g, b)
        val sat = v(Adjust.SATURATION); val vib = v(Adjust.VIBRANCE)
        if (sat != 0f || vib != 0f) {
            val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
            val chroma = (mx - mn).coerceIn(0f, 1f)
            val skin = if (r > g && g > b) 0.5f else 1f
            val k = 1f + sat + vib * (1f - chroma) * skin
            r = l + (r - l) * k; g = l + (g - l) * k; b = l + (b - l) * k
        }
        out[0] = r.coerceIn(0f, 1f); out[1] = g.coerceIn(0f, 1f); out[2] = b.coerceIn(0f, 1f)
    }

    private fun filterColor(r: Float, g: Float, b: Float, f: PhotoFilter, out: FloatArray) {
        val l = luma(r, g, b)
        fun sat(k: Float) { out[0] = l + (r - l) * k; out[1] = l + (g - l) * k; out[2] = l + (b - l) * k }
        fun curve(x: Float, k: Float) = ((x - 0.5f) * k + 0.5f)
        when (f) {
            PhotoFilter.ORIGINAL -> { out[0] = r; out[1] = g; out[2] = b }
            PhotoFilter.VIVID, PhotoFilter.VIVID_WARM, PhotoFilter.VIVID_COOL -> {
                sat(1.35f)
                for (i in 0..2) out[i] = curve(out[i], 1.1f)
                if (f == PhotoFilter.VIVID_WARM) { out[0] *= 1.06f; out[2] *= 0.92f }
                if (f == PhotoFilter.VIVID_COOL) { out[0] *= 0.93f; out[2] *= 1.07f }
            }
            PhotoFilter.DRAMATIC, PhotoFilter.DRAMATIC_WARM, PhotoFilter.DRAMATIC_COOL -> {
                sat(0.75f)
                for (i in 0..2) out[i] = curve(out[i], 1.35f) - 0.03f
                if (f == PhotoFilter.DRAMATIC_WARM) { out[0] *= 1.08f; out[2] *= 0.88f }
                if (f == PhotoFilter.DRAMATIC_COOL) { out[0] *= 0.9f; out[2] *= 1.1f }
            }
            PhotoFilter.MONO -> { out[0] = l; out[1] = l; out[2] = l }
            PhotoFilter.SILVERTONE -> {
                val m = curve(l, 1.15f) + 0.03f
                out[0] = m * 0.98f; out[1] = m; out[2] = m * 1.03f
            }
            PhotoFilter.NOIR -> {
                val m = curve(l, 1.6f) - 0.05f
                out[0] = m; out[1] = m; out[2] = m
            }
        }
        for (i in 0..2) out[i] = out[i].coerceIn(0f, 1f)
    }

    private fun applyLut(px: IntArray, lut: FloatArray) {
        val s = (N - 1) / 255f
        for (i in px.indices) {
            val c = px[i]
            val fr = (c shr 16 and 0xFF) * s; val fg = (c shr 8 and 0xFF) * s; val fb = (c and 0xFF) * s
            val r0 = min(fr.toInt(), N - 2); val g0 = min(fg.toInt(), N - 2); val b0 = min(fb.toInt(), N - 2)
            val dr = fr - r0; val dg = fg - g0; val db = fb - b0
            var r = 0f; var g = 0f; var b = 0f
            // Trilinear interpolation over the 8 surrounding lattice points.
            for (k in 0..7) {
                val ir = r0 + (k and 1); val ig = g0 + (k shr 1 and 1); val ib = b0 + (k shr 2 and 1)
                val wgt = (if (k and 1 == 1) dr else 1 - dr) * (if (k shr 1 and 1 == 1) dg else 1 - dg) * (if (k shr 2 and 1 == 1) db else 1 - db)
                val j = ((ib * N + ig) * N + ir) * 3
                r += lut[j] * wgt; g += lut[j + 1] * wgt; b += lut[j + 2] * wgt
            }
            px[i] = (c and 0xFF000000.toInt()) or (to8(r) shl 16) or (to8(g) shl 8) or to8(b)
        }
    }

    private fun vignette(px: IntArray, w: Int, h: Int, amount: Float) {
        val cx = w / 2f; val cy = h / 2f
        val rmax = sqrt(cx * cx + cy * cy)
        for (y in 0 until h) for (x in 0 until w) {
            val d = sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy)) / rmax
            val f = smooth(0.35f, 1f, d)
            // Positive darkens the edges, negative brightens them, as in Photos.
            val k = 1f - amount * 0.6f * f
            val i = y * w + x
            val c = px[i]
            px[i] = (c and 0xFF000000.toInt()) or (to8((c shr 16 and 0xFF) / 255f * k) shl 16) or
                (to8((c shr 8 and 0xFF) / 255f * k) shl 8) or to8((c and 0xFF) / 255f * k)
        }
    }

    private fun blurred(px: IntArray, w: Int, h: Int, radius: Int): IntArray {
        val out = px.copyOf()
        val tmp = IntArray(max(w, h))
        for (pass in 0 until 2) {
            for (y in 0 until h) boxLine(out, y * w, 1, w, radius, tmp)
            for (x in 0 until w) boxLine(out, x, w, h, radius, tmp)
        }
        return out
    }

    private fun boxLine(a: IntArray, start: Int, step: Int, n: Int, r: Int, tmp: IntArray) {
        var sr = 0; var sg = 0; var sb = 0
        for (k in -r..r) { val c = a[start + k.coerceIn(0, n - 1) * step]; sr += c shr 16 and 0xFF; sg += c shr 8 and 0xFF; sb += c and 0xFF }
        val d = 2 * r + 1
        for (i in 0 until n) {
            tmp[i] = (0xFF shl 24) or ((sr / d) shl 16) or ((sg / d) shl 8) or (sb / d)
            val add = a[start + min(i + r + 1, n - 1) * step]; val sub = a[start + max(i - r, 0) * step]
            sr += (add shr 16 and 0xFF) - (sub shr 16 and 0xFF)
            sg += (add shr 8 and 0xFF) - (sub shr 8 and 0xFF)
            sb += (add and 0xFF) - (sub and 0xFF)
        }
        for (i in 0 until n) a[start + i * step] = (a[start + i * step] and 0xFF000000.toInt()) or (tmp[i] and 0xFFFFFF)
    }

    private fun unsharp(px: IntArray, blur: IntArray, amount: Float) {
        for (i in px.indices) {
            val c = px[i]; val bl = blur[i]
            fun ch(s: Int) = (c shr s and 0xFF) + ((c shr s and 0xFF) - (bl shr s and 0xFF)) * amount
            px[i] = (c and 0xFF000000.toInt()) or (ch(16).roundToInt().coerceIn(0, 255) shl 16) or
                (ch(8).roundToInt().coerceIn(0, 255) shl 8) or ch(0).roundToInt().coerceIn(0, 255)
        }
    }

    private fun blend(px: IntArray, other: IntArray, t: Float) {
        for (i in px.indices) {
            val a = px[i]; val b = other[i]
            fun ch(s: Int) = ((a shr s and 0xFF) + ((b shr s and 0xFF) - (a shr s and 0xFF)) * t).roundToInt()
            px[i] = (a and 0xFF000000.toInt()) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
    }

    private fun luma(r: Float, g: Float, b: Float) = 0.2126f * r + 0.7152f * g + 0.0722f * b
    private fun luma8(c: Int) = ((c shr 16 and 0xFF) * 54 + (c shr 8 and 0xFF) * 183 + (c and 0xFF) * 19) shr 8
    private fun smooth(e0: Float, e1: Float, x: Float): Float { val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f); return t * t * (3 - 2 * t) }
    private fun to8(v: Float) = (v * 255f).roundToInt().coerceIn(0, 255)
    private fun srgbToLinear(c: Float) = if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
    private fun linearToSrgb(c0: Float): Float { val c = max(0f, c0); return if (c <= 0.0031308f) c * 12.92f else 1.055f * c.pow(1f / 2.4f) - 0.055f }

}
