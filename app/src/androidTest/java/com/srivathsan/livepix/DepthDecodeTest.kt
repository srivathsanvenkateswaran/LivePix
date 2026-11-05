package com.srivathsan.livepix

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.srivathsan.livepix.media.ArrayByteSource
import com.srivathsan.livepix.media.HeifParser
import com.srivathsan.livepix.media.HevcCodec
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Runs against real iPhone files copied into /samples (git-ignored). */
@RunWith(AndroidJUnit4::class)
class DepthDecodeTest {
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets

    @Test
    fun decodesDepthAndMatteFromIphoneHeic() {
        val names = assets.list("")!!.filter { it.endsWith(".heic", ignoreCase = true) }
        assertTrue("no samples bundled", names.isNotEmpty())
        var decoded = 0
        for (name in names) {
            val bytes = assets.open(name).use { it.readBytes() }
            val src = ArrayByteSource(bytes)
            val heif = HeifParser.parse(src)
            assertNotNull(name, heif)
            val depth = heif!!.depthItem ?: continue
            val img = HevcCodec.decodeLuma(depth.hvcC!!, heif.readItemData(src, depth), depth.width, depth.height)
            assertNotNull("depth decode failed for $name", img)
            val distinct = img!!.pixels.toSet().size
            println("LIVEPIX $name depth ${img.width}x${img.height} distinct=$distinct rot=${heif.primary?.rotation}")
            assertTrue("depth for $name looks flat", distinct > 8)
            heif.portraitMatteItem?.let { m ->
                val matte = HevcCodec.decodeLuma(m.hvcC!!, heif.readItemData(src, m), m.width, m.height)
                println("LIVEPIX $name matte ${matte?.width}x${matte?.height}")
                assertNotNull("matte decode failed for $name", matte)
            }
            decoded++
        }
        assertTrue(decoded > 0)
    }
}
