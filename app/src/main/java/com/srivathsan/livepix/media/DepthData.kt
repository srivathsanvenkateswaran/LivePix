package com.srivathsan.livepix.media

/**
 * Depth (disparity) map and optional portrait matte for one photo, already rotated to match the
 * displayed image. Disparity is 0..255 where larger means closer to the camera.
 */
class DepthData(val disparity: GrayImage, val matte: GrayImage?) {

    /** Bilinear sample at normalized image coordinates. */
    fun disparityAt(u: Float, v: Float): Float = sample(disparity, u, v)
    fun matteAt(u: Float, v: Float): Float = matte?.let { sample(it, u, v) } ?: 0f

    /**
     * Where iPhone would focus by default: the subject the matte marks, otherwise the nearest
     * large region (90th percentile disparity).
     */
    fun defaultFocusDisparity(): Float {
        val m = matte
        if (m != null) {
            var sum = 0.0; var count = 0
            val w = disparity.width; val h = disparity.height
            for (y in 0 until h) for (x in 0 until w) {
                val mv = sample(m, (x + 0.5f) / w, (y + 0.5f) / h)
                if (mv > 160f) { sum += disparity[x, y]; count++ }
            }
            if (count > w * h / 200) return (sum / count).toFloat()
        }
        val hist = IntArray(256)
        disparity.pixels.forEach { hist[it.toInt() and 0xFF]++ }
        var acc = 0
        val target = disparity.pixels.size * 0.9
        for (i in 0 until 256) { acc += hist[i]; if (acc >= target) return i.toFloat() }
        return 200f
    }

    companion object {
        fun sample(img: GrayImage, u: Float, v: Float): Float {
            val x = (u * img.width - 0.5f).coerceIn(0f, img.width - 1f)
            val y = (v * img.height - 0.5f).coerceIn(0f, img.height - 1f)
            val x0 = x.toInt(); val y0 = y.toInt()
            val x1 = minOf(x0 + 1, img.width - 1); val y1 = minOf(y0 + 1, img.height - 1)
            val fx = x - x0; val fy = y - y0
            val a = img[x0, y0] + (img[x1, y0] - img[x0, y0]) * fx
            val b = img[x0, y1] + (img[x1, y1] - img[x0, y1]) * fx
            return a + (b - a) * fy
        }

        /** Applies HEIF `irot` (counter-clockwise quarter turns) then `imir`, like the primary image. */
        fun orient(img: GrayImage, rotation: Int, mirrorAxis: Int?): GrayImage {
            var out = img
            repeat(rotation and 3) { out = rotateCcw(out) }
            if (mirrorAxis != null) out = mirror(out, mirrorAxis)
            return out
        }

        private fun rotateCcw(img: GrayImage): GrayImage {
            val w = img.width; val h = img.height
            val px = ByteArray(w * h)
            // New image is h wide, w tall. Source (x, y) lands at (y, w - 1 - x).
            for (y in 0 until h) for (x in 0 until w) px[(w - 1 - x) * h + y] = img.pixels[y * w + x]
            return GrayImage(h, w, px)
        }

        private fun mirror(img: GrayImage, axis: Int): GrayImage {
            val w = img.width; val h = img.height
            val px = ByteArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                val sx = if (axis == 1) x else w - 1 - x
                val sy = if (axis == 1) h - 1 - y else y
                px[y * w + x] = img.pixels[sy * w + sx]
            }
            return GrayImage(w, h, px)
        }

        /** Loads depth + matte from a HEIF file, or null when the photo has no depth map. */
        fun load(source: ByteSource, heif: HeifParser): DepthData? {
            val depthItem = heif.depthItem ?: return null
            val disparity = decode(source, heif, depthItem) ?: return null
            val matte = heif.portraitMatteItem?.let { decode(source, heif, it) }
            return DepthData(disparity, matte)
        }

        private fun decode(source: ByteSource, heif: HeifParser, item: HeifParser.Item): GrayImage? {
            val hvcC = item.hvcC ?: return null
            val raw = HevcCodec.decodeLuma(hvcC, heif.readItemData(source, item), item.width, item.height) ?: return null
            return orient(raw, item.rotation, item.mirrorAxis)
        }
    }
}
