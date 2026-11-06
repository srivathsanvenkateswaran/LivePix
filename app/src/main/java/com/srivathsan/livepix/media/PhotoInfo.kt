package com.srivathsan.livepix.media

import android.content.Context
import androidx.exifinterface.media.ExifInterface
import com.srivathsan.livepix.data.Asset
import com.srivathsan.livepix.data.openOriginal
import java.io.FileInputStream
import kotlin.math.roundToInt

/** Camera details for the info panel. */
data class PhotoInfo(
    val camera: String? = null,
    val lens: String? = null,
    val iso: String? = null,
    val focal: String? = null,
    val ev: String? = null,
    val aperture: String? = null,
    val shutter: String? = null,
    val latLong: DoubleArray? = null,
) {
    companion object {
        fun read(context: Context, asset: Asset): PhotoInfo = runCatching {
            context.contentResolver.openOriginal(asset.uri)!!.use { pfd ->
                val src = ChannelByteSource.of(FileInputStream(pfd.fileDescriptor))
                if (asset.isVideo) {
                    val meta = AppleMetadata.quickTimeMetadata(src)
                    val make = meta["com.apple.quicktime.make"]
                    val model = meta["com.apple.quicktime.model"]
                    return@use PhotoInfo(camera = listOfNotNull(make, model).joinToString(" ").ifBlank { null })
                }
                val heif = HeifParser.parse(src)
                val exif = (if (heif != null) AppleMetadata.heifExif(src, heif) else null)
                    ?: FileInputStream(pfd.fileDescriptor).let { it.channel.position(0); ExifInterface(it) }
                fromExif(exif)
            }
        }.getOrDefault(PhotoInfo())

        private fun fromExif(e: ExifInterface): PhotoInfo {
            val make = e.getAttribute(ExifInterface.TAG_MAKE)?.trim()
            val model = e.getAttribute(ExifInterface.TAG_MODEL)?.trim()
            val camera = when {
                model == null -> make
                make != null && !model.startsWith(make) -> "$make $model"
                else -> model
            }
            val f = e.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0)
            val exposure = e.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0)
            val focal35 = e.getAttributeInt(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, 0)
            val focal = e.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0)
            val iso = e.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0)
            val ev = e.getAttributeDouble(ExifInterface.TAG_EXPOSURE_BIAS_VALUE, Double.NaN)
            return PhotoInfo(
                camera = camera,
                lens = e.getAttribute(ExifInterface.TAG_LENS_MODEL)?.let(::prettyLens),
                iso = if (iso > 0) "ISO $iso" else null,
                focal = when {
                    focal35 > 0 -> "$focal35 mm"
                    focal > 0 -> "${focal.roundToInt()} mm"
                    else -> null
                },
                ev = if (!ev.isNaN()) "%s ev".format(if (ev == 0.0) "0" else "%+.1f".format(ev)) else null,
                aperture = if (f > 0) "ƒ%.2f".format(f).trimEnd('0').trimEnd('.') else null,
                shutter = when {
                    exposure <= 0 -> null
                    exposure >= 1 -> "%.1f s".format(exposure)
                    else -> "1/${(1 / exposure).roundToInt()} s"
                },
                latLong = e.latLong,
            )
        }

        /** "iPhone 14 Pro back triple camera 6.86mm f/1.78" -> "Main Camera — 6.86 mm ƒ1.78". */
        private fun prettyLens(raw: String): String {
            val r = raw.lowercase()
            val kind = when {
                "front" in r -> "Front Camera"
                "telephoto" in r -> "Telephoto Camera"
                "ultra wide" in r || "2.22mm" in r || "1.54mm" in r -> "Ultra Wide Camera"
                "back" in r -> "Main Camera"
                else -> return raw
            }
            val spec = Regex("""([\d.]+)mm f/([\d.]+)""").find(raw)
            return if (spec != null) "$kind — ${spec.groupValues[1]} mm ƒ${spec.groupValues[2]}" else kind
        }
    }
}
