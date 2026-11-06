package com.srivathsan.livepix.data

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
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
    /** Android blanks GPS in files unless the app holds this; files are re-read once it does. */
    val hasLocationAccess = hasMediaLocation(context)

    // Bump when the parsers change so files are re-read.
    private val cacheFile = File(context.filesDir, "traits-v$VERSION${if (hasLocationAccess) "" else "-noloc"}.json")
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
        val pfd = context.contentResolver.openOriginal(asset.uri) ?: return Traits()
        return pfd.use {
            ChannelByteSource.of(FileInputStream(pfd.fileDescriptor)).let { src ->
                if (asset.isVideo) {
                    // Only Live movies need their metadata read; a long video's index can be tens of MB.
                    val meta = if (MediaRepository.isLiveCandidate(asset)) AppleMetadata.quickTimeMetadata(src) else emptyMap()
                    val location = videoLocation(pfd)
                    Traits(
                        contentId = meta["com.apple.quicktime.content.identifier"],
                        make = meta["com.apple.quicktime.make"],
                        model = meta["com.apple.quicktime.model"],
                        lat = location?.lat,
                        lng = location?.lng,
                    )
                } else {
                    val heif = HeifParser.parse(src)
                    val motion = MotionPhoto.locate(src)
                    val exif = if (heif != null) AppleMetadata.heifExif(src, heif)
                    else runCatching { ExifInterface(FileInputStream(pfd.fileDescriptor).also { it.channel.position(0) }) }.getOrNull()
                    val latLong = exif?.latLong
                    Traits(
                        lat = latLong?.get(0),
                        lng = latLong?.get(1),
                        hasDepth = heif?.depthItem != null,
                        hasMatte = heif?.portraitMatteItem != null,
                        contentId = exif?.let(AppleMetadata::contentIdFromMakerNote),
                        make = exif?.getAttribute(ExifInterface.TAG_MAKE),
                        model = exif?.getAttribute(ExifInterface.TAG_MODEL),
                        front = exif?.getAttribute(ExifInterface.TAG_LENS_MODEL)?.contains("front", ignoreCase = true) == true,
                        motionOffset = motion?.offset,
                        motionLength = motion?.length,
                        presentationUs = if (motion != null) MotionPhoto.presentationUs(src) else null,
                    )
                }
            }
        }
    }

    private fun videoLocation(pfd: ParcelFileDescriptor): GeoPoint? = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(pfd.fileDescriptor)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)?.let(::parseIso6709)
        } finally {
            retriever.release()
        }
    }.getOrNull()

    @Synchronized
    fun persist() {
        val json = JSONObject()
        for ((k, t) in cache) {
            json.put(k, JSONObject().apply {
                put("d", t.hasDepth); put("m", t.hasMatte)
                t.contentId?.let { put("c", it) }; t.make?.let { put("mk", it) }; t.model?.let { put("md", it) }
                if (t.front) put("f", true)
                t.motionOffset?.let { put("mo", it) }; t.motionLength?.let { put("ml", it) }; t.presentationUs?.let { put("pt", it) }
                t.lat?.let { put("la", it) }; t.lng?.let { put("ln", it) }
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
                o.optString("mk").ifEmpty { null }, o.optString("md").ifEmpty { null }, o.optBoolean("f"),
                if (o.has("mo")) o.getLong("mo") else null, if (o.has("ml")) o.getLong("ml") else null,
                if (o.has("pt")) o.getLong("pt") else null,
                if (o.has("la")) o.getDouble("la") else null, if (o.has("ln")) o.getDouble("ln") else null,
            )
        }
        return out
    }

    private companion object {
        const val TAG = "TraitScanner"
        const val VERSION = 5
    }
}

fun hasMediaLocation(context: Context): Boolean =
    context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED

/** Opens a media file with its GPS intact when the app may read it, and without otherwise. */
fun ContentResolver.openOriginal(uri: Uri): ParcelFileDescriptor? =
    runCatching { openFileDescriptor(MediaStore.setRequireOriginal(uri), "r") }.getOrNull() ?: openFileDescriptor(uri, "r")

/** "+37.5090-122.2500/" to a point. */
internal fun parseIso6709(text: String): GeoPoint? {
    val m = Regex("""^([+-]\d+(?:\.\d+)?)([+-]\d+(?:\.\d+)?)""").find(text) ?: return null
    return GeoPoint(m.groupValues[1].toDouble(), m.groupValues[2].toDouble())
}
