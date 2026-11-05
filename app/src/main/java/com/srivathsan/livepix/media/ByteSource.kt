package com.srivathsan.livepix.media

import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** Random-access reader over a file, so we can parse box headers without loading 5 MB photos. */
interface ByteSource : Closeable {
    val size: Long
    fun read(offset: Long, length: Int): ByteArray
}

class ChannelByteSource(private val channel: FileChannel, private val owner: Closeable) : ByteSource {
    override val size: Long = channel.size()

    override fun read(offset: Long, length: Int): ByteArray {
        val n = minOf(length.toLong(), size - offset).toInt().coerceAtLeast(0)
        val buf = ByteBuffer.allocate(n)
        var pos = offset
        while (buf.hasRemaining()) {
            val r = channel.read(buf, pos)
            if (r <= 0) break
            pos += r
        }
        return buf.array()
    }

    override fun close() = owner.close()

    companion object {
        fun of(file: File): ChannelByteSource {
            val stream = FileInputStream(file)
            return ChannelByteSource(stream.channel, stream)
        }

        fun of(stream: FileInputStream): ChannelByteSource = ChannelByteSource(stream.channel, stream)
    }
}

class ArrayByteSource(private val bytes: ByteArray) : ByteSource {
    override val size: Long = bytes.size.toLong()
    override fun read(offset: Long, length: Int): ByteArray {
        val start = offset.toInt().coerceIn(0, bytes.size)
        return bytes.copyOfRange(start, minOf(bytes.size, start + length))
    }
    override fun close() {}
}

internal fun ByteArray.u8(p: Int): Int = this[p].toInt() and 0xFF
internal fun ByteArray.u16(p: Int): Int = (u8(p) shl 8) or u8(p + 1)
internal fun ByteArray.u32(p: Int): Long =
    (u8(p).toLong() shl 24) or (u8(p + 1).toLong() shl 16) or (u8(p + 2).toLong() shl 8) or u8(p + 3).toLong()
internal fun ByteArray.uN(p: Int, n: Int): Long {
    var v = 0L
    for (i in 0 until n) v = (v shl 8) or u8(p + i).toLong()
    return v
}
internal fun ByteArray.fourCC(p: Int): String = String(this, p, 4, Charsets.ISO_8859_1)

/** One ISO-BMFF box: [start, end) covers the payload after the header. */
internal data class Box(val type: String, val start: Int, val end: Int)

internal fun ByteArray.boxes(start: Int, end: Int): List<Box> {
    val out = ArrayList<Box>()
    var p = start
    while (p + 8 <= end) {
        var size = u32(p)
        val type = fourCC(p + 4)
        var header = 8
        if (size == 1L) {
            size = uN(p + 8, 8); header = 16
        } else if (size == 0L) {
            size = (end - p).toLong()
        }
        if (size < header || p + size > end) break
        out += Box(type, p + header, (p + size).toInt())
        p += size.toInt()
    }
    return out
}
