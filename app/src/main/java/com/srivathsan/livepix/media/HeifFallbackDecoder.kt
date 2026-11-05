package com.srivathsan.livepix.media

import android.graphics.Bitmap
import android.graphics.Matrix
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Decodes iPhone HEIC with the bundled libde265 for devices whose platform HEIF decoder fails.
 * Small requests use the embedded thumbnail; larger ones assemble the tile grid.
 * Colour is converted as full-range BT.601 without the Display P3 profile, so it can look
 * slightly less saturated than the platform decoder.
 */
object HeifFallbackDecoder {
    fun decode(source: ByteSource, maxSide: Int): Bitmap? {
        val heif = HeifParser.parse(source) ?: return null
        val primary = heif.primary ?: return null
        val thumb = heif.primaryThumbnail
        val bitmap = when {
            // A slightly small preview upscales fine in a grid cell and is far cheaper than the grid.
            thumb != null && max(thumb.width, thumb.height) >= maxSide * 0.6f -> decodeItem(source, heif, thumb)
            primary.type == "grid" -> decodeGrid(source, heif, primary)
            primary.type == "hvc1" -> decodeItem(source, heif, primary)
            else -> null
        } ?: return null
        return orient(scale(bitmap, maxSide), primary.rotation, primary.mirrorAxis)
    }

    private fun decodeItem(source: ByteSource, heif: HeifParser, item: HeifParser.Item): Bitmap? {
        val hvcC = item.hvcC ?: return null
        val stream = HevcCodec.parameterSetsAnnexB(hvcC) + HevcCodec.lengthPrefixedToAnnexB(heif.readItemData(source, item), hvcC)
        val size = IntArray(2)
        val px = NativeHevc.decodeArgb(stream, 6, true, size) ?: return null
        return Bitmap.createBitmap(px, size[0], size[1], Bitmap.Config.ARGB_8888)
    }

    private fun decodeGrid(source: ByteSource, heif: HeifParser, grid: HeifParser.Item): Bitmap? {
        val desc = heif.readItemData(source, grid)
        val large = desc.u8(1) and 1 != 0
        val rows = desc.u8(2) + 1
        val cols = desc.u8(3) + 1
        val outW = if (large) desc.u32(4).toInt() else desc.u16(4)
        val outH = if (large) desc.u32(8).toInt() else desc.u16(6)
        val tiles = heif.tilesOf[grid.id] ?: return null
        if (tiles.size != rows * cols) return null
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        var tileW = 0
        var tileH = 0
        for ((i, id) in tiles.withIndex()) {
            val tile = decodeItem(source, heif, heif.items[id] ?: return null) ?: return null
            if (tileW == 0) { tileW = tile.width; tileH = tile.height }
            canvas.drawBitmap(tile, ((i % cols) * tileW).toFloat(), ((i / cols) * tileH).toFloat(), null)
            tile.recycle()
        }
        return out
    }

    private fun scale(b: Bitmap, maxSide: Int): Bitmap {
        val longest = max(b.width, b.height)
        if (longest <= maxSide) return b
        val s = maxSide.toFloat() / longest
        return Bitmap.createScaledBitmap(b, (b.width * s).roundToInt(), (b.height * s).roundToInt(), true)
    }

    private fun orient(b: Bitmap, rotation: Int, mirrorAxis: Int?): Bitmap {
        if (rotation == 0 && mirrorAxis == null) return b
        val m = Matrix()
        // irot is counter-clockwise; Matrix.postRotate is clockwise.
        if (rotation != 0) m.postRotate(-90f * rotation)
        if (mirrorAxis == 0) m.postScale(-1f, 1f) else if (mirrorAxis == 1) m.postScale(1f, -1f)
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
    }
}
