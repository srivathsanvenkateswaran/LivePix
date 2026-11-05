package com.srivathsan.livepix

import com.srivathsan.livepix.media.AppleMetadata
import com.srivathsan.livepix.media.ArrayByteSource
import com.srivathsan.livepix.media.ChannelByteSource
import com.srivathsan.livepix.media.DepthData
import com.srivathsan.livepix.media.Exporter
import com.srivathsan.livepix.media.GrayImage
import com.srivathsan.livepix.media.HeifParser
import com.srivathsan.livepix.media.MotionPhoto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Parser tests. Real-file tests run only where the git-ignored /samples folder exists. */
class ParsersTest {
    private val samples = File("../samples")

    private fun sample(name: String): File {
        val f = File(samples, name)
        assumeTrue("sample $name not present", f.exists())
        return f
    }

    @Test
    fun findsDepthAndMatteInIphone16Portrait() {
        ChannelByteSource.of(sample("IMG_4395.HEIC")).use { src ->
            val heif = HeifParser.parse(src)!!
            val depth = heif.depthItem!!
            assertEquals(768, depth.width)
            assertEquals(576, depth.height)
            assertEquals(3, depth.rotation)
            assertEquals(0, (depth.hvcC!![16].toInt() and 3)) // monochrome
            assertNotNull(heif.portraitMatteItem)
            assertEquals(heif.readItemData(src, depth).size.toLong(), depth.extents.sumOf { it.length })
        }
    }

    @Test
    fun nonPortraitPhotoHasNoDepth() {
        ChannelByteSource.of(sample("IMG_5750.HEIC")).use { src ->
            val heif = HeifParser.parse(src)!!
            assertEquals("grid", heif.primary!!.type)
            assertNull(heif.depthItem)
        }
    }

    @Test
    fun findsMovieInsideGoogleMotionPhoto() {
        ChannelByteSource.of(sample("motion/IMG_4568.MP.HEIC")).use { src ->
            val loc = MotionPhoto.locate(src)!!
            // The embedded movie is the original iPhone QuickTime file.
            assertEquals("ftypqt  ", String(src.read(loc.offset + 4, 8), Charsets.US_ASCII))
            assertEquals(src.size, loc.offset + loc.length)
            assertNotNull("still is a normal HEIF", HeifParser.parse(src)?.primary)
        }
    }

    @Test
    fun plainHeicIsNotAMotionPhoto() {
        ChannelByteSource.of(sample("IMG_1964.heic")).use { assertNull(MotionPhoto.locate(it)) }
    }

    @Test
    fun rejectsNonHeif() {
        assertNull(HeifParser.parse(ArrayByteSource(ByteArray(64) { 0x41 })))
        assertNull(HeifParser.parse(ArrayByteSource(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte()))))
    }

    @Test
    fun readsContentIdFromMovieWithUdtaMeta() {
        ChannelByteSource.of(sample("live/QuickTime movie-4395.mov")).use { src ->
            assertEquals("121AEFAA-1DD0-43BD-B1F9-F804C9F3F93B", AppleMetadata.contentIdFromMovie(src))
            assertEquals("Apple", AppleMetadata.quickTimeMetadata(src)["com.apple.quicktime.make"])
        }
    }

    @Test
    fun readsMetadataFromRealIphoneMovie() {
        ChannelByteSource.of(sample("live/IMG_5704.MOV")).use { src ->
            assertEquals("iPhone 14 Pro", AppleMetadata.quickTimeMetadata(src)["com.apple.quicktime.model"])
        }
    }

    @Test
    fun rotatesDepthCounterClockwise() {
        // 2x1 image [a b] rotated 90° CCW becomes 1x2 [b; a].
        val img = GrayImage(2, 1, byteArrayOf(1, 2))
        val r = DepthData.orient(img, 1, null)
        assertEquals(1, r.width); assertEquals(2, r.height)
        assertArrayEquals(byteArrayOf(2, 1), r.pixels)
        // Four quarter turns are the identity.
        val big = GrayImage(3, 2, byteArrayOf(1, 2, 3, 4, 5, 6))
        assertArrayEquals(big.pixels, DepthData.orient(DepthData.orient(big, 2, null), 2, null).pixels)
        // irot=3 is 270° CCW == 90° CW: [a b] -> [a; b].
        assertArrayEquals(byteArrayOf(1, 2), DepthData.orient(img, 3, null).pixels)
    }

    @Test
    fun insertsXmpAfterExif() {
        val exif = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), 0, 4, 9, 9)
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + exif + byteArrayOf(0xFF.toByte(), 0xDB.toByte(), 0, 2, 0xFF.toByte(), 0xD9.toByte())
        val out = Exporter.insertXmp(jpeg, "<x/>")
        // SOI, then the untouched Exif segment, then our APP1.
        assertArrayEquals(jpeg.copyOfRange(0, 8), out.copyOfRange(0, 8))
        assertEquals(0xE1, out[9].toInt() and 0xFF)
        val text = String(out, Charsets.ISO_8859_1)
        val ns = text.indexOf("http://ns.adobe.com/xap/1.0/")
        val len = ((out[10].toInt() and 0xFF) shl 8) or (out[11].toInt() and 0xFF)
        assertEquals(12, ns)
        assertEquals("http://ns.adobe.com/xap/1.0/\u0000<x/>".length + 2, len)
        assertArrayEquals(jpeg.copyOfRange(8, jpeg.size), out.copyOfRange(out.size - 6, out.size))
    }
}
