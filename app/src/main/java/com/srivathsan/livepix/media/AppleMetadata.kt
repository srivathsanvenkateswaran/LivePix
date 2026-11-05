package com.srivathsan.livepix.media

import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream

/**
 * Apple writes the same UUID into a Live Photo's still (MakerNote tag 0x11) and its movie
 * (`com.apple.quicktime.content.identifier`). Matching them pairs files even after a transfer
 * tool has renamed them.
 */
object AppleMetadata {
    private const val CONTENT_ID_KEY = "com.apple.quicktime.content.identifier"
    private const val MAKERNOTE_CONTENT_ID = 0x0011

    /** Exif for a HEIF file, read from its `Exif` item. */
    fun heifExif(source: ByteSource, heif: HeifParser): ExifInterface? {
        val item = heif.items.values.firstOrNull { it.type == "Exif" } ?: return null
        if (item.constructionMethod != 0) return null
        val data = heif.readItemData(source, item)
        if (data.size < 8) return null
        // The item starts with a 4-byte offset to the TIFF header.
        val tiffStart = 4 + data.u32(0).toInt()
        if (tiffStart >= data.size) return null
        val exifOnly = "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + data.copyOfRange(tiffStart, data.size)
        return runCatching {
            ExifInterface(ByteArrayInputStream(exifOnly), ExifInterface.STREAM_TYPE_EXIF_DATA_ONLY)
        }.getOrNull()
    }

    /** Live Photo content identifier from an Apple MakerNote, or null. */
    fun contentIdFromMakerNote(exif: ExifInterface): String? {
        val note = exif.getAttributeBytes(ExifInterface.TAG_MAKER_NOTE) ?: return null
        if (note.size < 16 || String(note, 0, 9, Charsets.US_ASCII) != "Apple iOS") return null
        val bigEndian = note[12] == 'M'.code.toByte()
        fun r16(p: Int) = if (bigEndian) note.u16(p) else note.u8(p) or (note.u8(p + 1) shl 8)
        fun r32(p: Int): Long = if (bigEndian) note.u32(p) else
            note.u8(p).toLong() or (note.u8(p + 1).toLong() shl 8) or (note.u8(p + 2).toLong() shl 16) or (note.u8(p + 3).toLong() shl 24)
        val count = r16(14)
        for (i in 0 until count) {
            val e = 16 + i * 12
            if (e + 12 > note.size) break
            if (r16(e) != MAKERNOTE_CONTENT_ID || r16(e + 2) != 2) continue
            val len = r32(e + 4).toInt()
            val start = if (len <= 4) e + 8 else r32(e + 8).toInt()
            if (start < 0 || start + len > note.size) return null
            return String(note, start, len, Charsets.US_ASCII).trimEnd('\u0000').ifBlank { null }
        }
        return null
    }

    /** Content identifier from a QuickTime movie's `moov/meta` keys, or null. */
    fun contentIdFromMovie(source: ByteSource): String? = quickTimeMetadata(source)[CONTENT_ID_KEY]

    /** All string values in `moov/meta` (keys + ilst), e.g. make/model/creation date. */
    fun quickTimeMetadata(source: ByteSource): Map<String, String> {
        var offset = 0L
        while (offset + 8 <= source.size) {
            val h = source.read(offset, 16)
            if (h.size < 8) break
            var size = h.u32(0)
            var header = 8
            if (size == 1L) { size = h.uN(8, 8); header = 16 } else if (size == 0L) size = source.size - offset
            if (size < header) break
            if (h.fourCC(4) == "moov") {
                if (size > 64L * 1024 * 1024) return emptyMap()
                val moov = source.read(offset + header, (size - header).toInt())
                return parseMoovMeta(moov)
            }
            offset += size
        }
        return emptyMap()
    }

    private fun parseMoovMeta(moov: ByteArray): Map<String, String> {
        // iPhone writes moov/meta; other muxers (e.g. ffmpeg) write moov/udta/meta.
        val top = moov.boxes(0, moov.size)
        val meta = top.firstOrNull { it.type == "meta" }
            ?: top.firstOrNull { it.type == "udta" }?.let { u -> moov.boxes(u.start, u.end).firstOrNull { it.type == "meta" } }
            ?: return emptyMap()
        // QuickTime `meta` has no version/flags; MP4 `meta` does. Detect by peeking at the first child.
        val start = if (moov.size >= meta.start + 8 && moov.fourCC(meta.start + 4).all { it.isLetter() }) meta.start else meta.start + 4
        val children = moov.boxes(start, meta.end)
        val keysBox = children.firstOrNull { it.type == "keys" } ?: return emptyMap()
        val ilst = children.firstOrNull { it.type == "ilst" } ?: return emptyMap()
        val keys = ArrayList<String>()
        var p = keysBox.start + 8
        val n = moov.u32(keysBox.start + 4).toInt()
        repeat(n) {
            val ks = moov.u32(p).toInt()
            if (ks < 8 || p + ks > keysBox.end) return@repeat
            keys += String(moov, p + 8, ks - 8, Charsets.UTF_8)
            p += ks
        }
        val out = HashMap<String, String>()
        for (entry in moov.boxes(ilst.start, ilst.end)) {
            val index = moov.u32(entry.start - 4).toInt() - 1
            val key = keys.getOrNull(index) ?: continue
            val data = moov.boxes(entry.start, entry.end).firstOrNull { it.type == "data" } ?: continue
            val type = moov.u32(data.start).toInt() and 0xFFFFFF
            if (type == 1 && data.end > data.start + 8) {
                out[key] = String(moov, data.start + 8, data.end - data.start - 8, Charsets.UTF_8)
            }
        }
        return out
    }
}
