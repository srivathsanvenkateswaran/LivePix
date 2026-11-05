package com.srivathsan.livepix.data

import android.content.Context
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.srivathsan.livepix.media.AppleMetadata
import com.srivathsan.livepix.media.ChannelByteSource
import com.srivathsan.livepix.media.HeifParser
import com.srivathsan.livepix.media.MotionPhoto
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream

/** Reads depth/Live metadata from each file once and remembers it across launches. */
class TraitScanner(private val context: Context) {
    // Bump when the parsers change so files are re-read.
    private val cacheFile = File(context.filesDir, "traits-v$VERSION.json")
    private val cache: MutableMap<String, Traits> = load()

    fun cached(): Map<String, Traits> = HashMap(cache)

    fun scan(asset: Asset): Traits {
        cache[asset.versionKey]?.let { return it }
        val traits = runCatching { read(asset) }
            .onFailure { Log.w(TAG, "scan ${asset.name}: ${it.message}") }
            .getOrDefault(Traits())
        cache[asset.versionKey] = traits
        return traits
    }

    private fun read(asset: Asset): Traits {
        val pfd = context.contentResolver.openFileDescriptor(asset.uri, "r") ?: return Traits()
        return pfd.use {
            ChannelByteSource.of(FileInputStream(pfd.fileDescriptor)).let { src ->
                if (asset.isVideo) {
                    val meta = AppleMetadata.quickTimeMetadata(src)
                    Traits(
                        contentId = meta["com.apple.quicktime.content.identifier"],
                        make = meta["com.apple.quicktime.make"],
                        model = meta["com.apple.quicktime.model"],
                    )
                } else {
                    val heif = HeifParser.parse(src)
                    val motion = MotionPhoto.locate(src)
                    val exif = if (heif != null) AppleMetadata.heifExif(src, heif)
                    else runCatching { ExifInterface(FileInputStream(pfd.fileDescriptor).also { it.channel.position(0) }) }.getOrNull()
                    Traits(
                        hasDepth = heif?.depthItem != null,
                        hasMatte = heif?.portraitMatteItem != null,
                        contentId = exif?.let(AppleMetadata::contentIdFromMakerNote),
                        make = exif?.getAttribute(ExifInterface.TAG_MAKE),
                        model = exif?.getAttribute(ExifInterface.TAG_MODEL),
                        motionOffset = motion?.offset,
                        motionLength = motion?.length,
                        presentationUs = if (motion != null) MotionPhoto.presentationUs(src) else null,
                    )
                }
            }
        }
    }

    @Synchronized
    fun persist() {
        val json = JSONObject()
        for ((k, t) in cache) {
            json.put(k, JSONObject().apply {
                put("d", t.hasDepth); put("m", t.hasMatte)
                t.contentId?.let { put("c", it) }; t.make?.let { put("mk", it) }; t.model?.let { put("md", it) }
                t.motionOffset?.let { put("mo", it) }; t.motionLength?.let { put("ml", it) }; t.presentationUs?.let { put("pt", it) }
            })
        }
        runCatching { cacheFile.writeText(json.toString()) }
    }

    private fun load(): MutableMap<String, Traits> {
        val out = HashMap<String, Traits>()
        val json = runCatching { JSONObject(cacheFile.readText()) }.getOrNull() ?: return out
        for (k in json.keys()) {
            val o = json.getJSONObject(k)
            out[k] = Traits(
                o.optBoolean("d"), o.optBoolean("m"), o.optString("c").ifEmpty { null },
                o.optString("mk").ifEmpty { null }, o.optString("md").ifEmpty { null },
                if (o.has("mo")) o.getLong("mo") else null, if (o.has("ml")) o.getLong("ml") else null,
                if (o.has("pt")) o.getLong("pt") else null,
            )
        }
        return out
    }

    private companion object {
        const val TAG = "TraitScanner"
        const val VERSION = 3
    }
}
