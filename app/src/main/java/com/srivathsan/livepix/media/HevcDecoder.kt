package com.srivathsan.livepix.media

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/** An 8-bit single-channel image (depth map or matte), row-major, no padding. */
class GrayImage(val width: Int, val height: Int, val pixels: ByteArray) {
    operator fun get(x: Int, y: Int): Int = pixels[y * width + x].toInt() and 0xFF
}

object HevcCodec {
    private const val START_CODE_SIZE = 4

    /** Parameter sets (VPS/SPS/PPS) from an `hvcC` record, as Annex-B. */
    fun parameterSetsAnnexB(hvcC: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var p = 22
        val arrays = hvcC.u8(p); p += 1
        repeat(arrays) {
            p += 1 // array_completeness | nal type
            val n = hvcC.u16(p); p += 2
            repeat(n) {
                val len = hvcC.u16(p); p += 2
                out.write(byteArrayOf(0, 0, 0, 1))
                out.write(hvcC, p, len)
                p += len
            }
        }
        return out.toByteArray()
    }

    /** Converts length-prefixed NAL units (as stored in HEIF items) to Annex-B. */
    fun lengthPrefixedToAnnexB(data: ByteArray, hvcC: ByteArray): ByteArray {
        val lengthSize = (hvcC.u8(21) and 3) + 1
        val out = ByteArrayOutputStream(data.size + 64)
        var p = 0
        while (p + lengthSize <= data.size) {
            val len = data.uN(p, lengthSize).toInt()
            p += lengthSize
            if (len <= 0 || p + len > data.size) break
            out.write(byteArrayOf(0, 0, 0, 1), 0, START_CODE_SIZE)
            out.write(data, p, len)
            p += len
        }
        return out.toByteArray()
    }

    /** Software decoder first: hardware HEVC decoders often reject monochrome (4:0:0) streams. */
    private fun decoderNames(): List<String> {
        val all = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, true) } }
        val (soft, hard) = all.partition { it.isSoftwareOnly }
        return (soft + hard).map(MediaCodecInfo::getName)
    }

    /**
     * Decodes one HEVC still (a HEIF item) and returns its luma plane. Depth maps and mattes are
     * monochrome, so luma is the whole signal.
     */
    fun decodeLuma(hvcC: ByteArray, data: ByteArray, width: Int, height: Int): GrayImage? {
        val csd = parameterSetsAnnexB(hvcC)
        val frame = lengthPrefixedToAnnexB(data, hvcC)
        val chromaFormat = hvcC.u8(16) and 3
        if (chromaFormat == 0) {
            runCatching { NativeHevc.decodeLuma(csd + frame) }
                .onFailure { Log.w(TAG, "libde265 failed: ${it.message}") }
                .getOrNull()?.let { return it }
        }
        for (name in decoderNames()) {
            val result = runCatching { decodeWith(name, csd, frame, width, height) }
                .onFailure { Log.w(TAG, "decoder $name failed: ${it.message}") }
                .getOrNull()
            if (result != null) return result
        }
        return null
    }

    private fun decodeWith(name: String, csd: ByteArray, frame: ByteArray, width: Int, height: Int): GrayImage? {
        val codec = MediaCodec.createByCodecName(name)
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, width, height).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(csd))
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            }
            codec.configure(format, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var eosQueued = false
            val deadline = System.nanoTime() + 3_000_000_000L
            while (System.nanoTime() < deadline) {
                if (!eosQueued) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buf = codec.getInputBuffer(index)!!
                        buf.clear()
                        if (!inputDone) {
                            buf.put(frame)
                            codec.queueInputBuffer(index, 0, frame.size, 0, 0)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            eosQueued = true
                        }
                    }
                }
                val out = codec.dequeueOutputBuffer(info, 10_000)
                if (out >= 0) {
                    if (info.size > 0) {
                        val image = codec.getOutputImage(out)
                        if (image != null) {
                            val plane = image.planes[0]
                            val crop = image.cropRect
                            val w = crop.width()
                            val h = crop.height()
                            val px = ByteArray(w * h)
                            val buf = plane.buffer
                            for (y in 0 until h) {
                                for (x in 0 until w) {
                                    px[y * w + x] = buf.get((y + crop.top) * plane.rowStride + (x + crop.left) * plane.pixelStride)
                                }
                            }
                            image.close()
                            codec.releaseOutputBuffer(out, false)
                            return GrayImage(w, h, px)
                        }
                    }
                    codec.releaseOutputBuffer(out, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return null
                }
            }
            return null
        } finally {
            runCatching { codec.stop() }
            codec.release()
        }
    }

    private const val TAG = "HevcCodec"
}
