package com.srivathsan.livepix.media

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileInputStream

/**
 * Motion Photos keep the Live movie inside the image file. Google Photos converts iPhone Live
 * Photos to this form (`IMG_0409.MP.HEIC`), and Pixel and Samsung cameras write it natively.
 *
 * - HEIF: the movie is the payload of a top-level `mpvd` box (Motion Photo format 1.0).
 * - JPEG (Google): XMP gives the movie's length; it sits at the end of the file.
 * - JPEG (Samsung): the movie follows a `MotionPhoto_Data` marker near the end.
 */
object MotionPhoto {
    data class Location(val offset: Long, val length: Long)

    private val SAMSUNG_MARKER = "MotionPhoto_Data".toByteArray(Charsets.US_ASCII)
    private val LENGTH_PATTERNS = listOf(
        Regex("""Item:Semantic="MotionPhoto"[^>]*?Item:Length="(\d+)""""),
        Regex("""Item:Length="(\d+)"[^>]*?Item:Semantic="MotionPhoto""""),
        Regex("""MicroVideoOffset="(\d+)""""),
        Regex("""<GCamera:MicroVideoOffset>(\d+)<"""),
    )

    fun locate(source: ByteSource): Location? = heifMovie(source) ?: jpegMovie(source)

    private val PRESENTATION = Regex("""(?:MotionPhotoPresentationTimestampUs|MicroVideoPresentationTimestampUs)(?:="|>)(-?\d+)""")

    /** When the still was taken within the movie, if the file says; -1 or absent means unknown. */
    fun presentationUs(source: ByteSource): Long? {
        val head = String(source.read(0, minOf(source.size, 512 * 1024L).toInt()), Charsets.ISO_8859_1)
        return PRESENTATION.find(head)?.groupValues?.get(1)?.toLongOrNull()?.takeIf { it >= 0 }
    }

    private fun heifMovie(source: ByteSource): Location? {
        var offset = 0L
        while (offset + 8 <= source.size) {
            val h = source.read(offset, 16)
            if (h.size < 8) return null
            var size = h.u32(0)
            var header = 8
            if (size == 1L) { size = h.uN(8, 8); header = 16 } else if (size == 0L) size = source.size - offset
            if (size < header) return null
            val type = h.fourCC(4)
            if (offset == 0L && type != "ftyp") return null
            if (type == "mpvd") return Location(offset + header, size - header).takeIf { looksLikeMovie(source, it) }
            offset += size
        }
        return null
    }

    private fun jpegMovie(source: ByteSource): Location? {
        val head = source.read(0, 2)
        if (head.size < 2 || head.u8(0) != 0xFF || head.u8(1) != 0xD8) return null
        // XMP lives in the first APP1 segments.
        val xmp = String(source.read(0, minOf(source.size, 256 * 1024L).toInt()), Charsets.ISO_8859_1)
        for (pattern in LENGTH_PATTERNS) {
            val length = pattern.find(xmp)?.groupValues?.get(1)?.toLongOrNull() ?: continue
            if (length <= 0 || length >= source.size) continue
            val loc = Location(source.size - length, length)
            if (looksLikeMovie(source, loc)) return loc
        }
        // Samsung: scan the tail for the marker.
        val tailSize = minOf(source.size, 64L * 1024 * 1024)
        val tailStart = source.size - tailSize
        val tail = source.read(tailStart, tailSize.toInt())
        val at = indexOf(tail, SAMSUNG_MARKER)
        if (at >= 0) {
            val start = tailStart + at + SAMSUNG_MARKER.size
            val loc = Location(start, source.size - start)
            if (looksLikeMovie(source, loc)) return loc
        }
        return null
    }

    private fun looksLikeMovie(source: ByteSource, loc: Location): Boolean =
        loc.length > 16 && source.read(loc.offset + 4, 4).let { it.size == 4 && it.fourCC(0) == "ftyp" }

    private fun indexOf(hay: ByteArray, needle: ByteArray): Int {
        outer@ for (i in hay.size - needle.size downTo 0) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    /**
     * The embedded movie as a playable file. Movies are copied out on first use into a size-capped
     * cache, so a large library doesn't double its storage.
     */
    fun extract(context: Context, image: Uri, cacheKey: String, loc: Location): Uri? {
        val dir = File(context.cacheDir, "motion").apply { mkdirs() }
        val file = File(dir, cacheKey.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".mov")
        if (file.length() == loc.length) {
            file.setLastModified(System.currentTimeMillis())
            return Uri.fromFile(file)
        }
        val pfd: ParcelFileDescriptor = context.contentResolver.openFileDescriptor(image, "r") ?: return null
        pfd.use {
            FileInputStream(it.fileDescriptor).channel.use { ch ->
                val tmp = File(dir, file.name + ".part")
                tmp.outputStream().channel.use { out -> ch.transferTo(loc.offset, loc.length, out) }
                if (tmp.length() != loc.length) { tmp.delete(); return null }
                tmp.renameTo(file)
            }
        }
        trim(dir)
        return Uri.fromFile(file)
    }

    private const val CACHE_LIMIT = 400L * 1024 * 1024

    private fun trim(dir: File) {
        val files = dir.listFiles()?.filter { it.name.endsWith(".mov") }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= CACHE_LIMIT) break
            total -= f.length()
            f.delete()
        }
    }
}
