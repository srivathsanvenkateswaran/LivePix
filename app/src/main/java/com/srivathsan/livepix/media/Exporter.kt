package com.srivathsan.livepix.media

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.srivathsan.livepix.data.Asset
import com.srivathsan.livepix.data.AssetEdit
import com.srivathsan.livepix.data.LiveEffect
import com.srivathsan.livepix.data.Traits
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer

/** Writes edited photos out as ordinary JPEGs (or Motion Photos) without touching originals. */
object Exporter {
    private const val FULL_SIZE = 4096
    private const val ALBUM = "LivePix"

    private val COPIED_TAGS = listOf(
        ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_LENS_MODEL, ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF, ExifInterface.TAG_GPS_ALTITUDE,
        ExifInterface.TAG_GPS_ALTITUDE_REF,
    )

    /** The edited still at full resolution, encoded as JPEG with the original's Exif. */
    fun renderJpeg(context: Context, asset: Asset, traits: Traits?, edit: AssetEdit): File {
        val bmp = if (StillRenderer.needsRender(asset, traits, edit)) StillRenderer.render(context, asset, traits, edit, FULL_SIZE)
        else ImageLoader.decode(context, asset.uri, FULL_SIZE, software = true)
        requireNotNull(bmp) { "Could not decode ${asset.name}" }
        val dir = File(context.cacheDir, "export").apply { mkdirs() }
        val file = File(dir, asset.baseName.uppercase() + (if (StillRenderer.needsRender(asset, traits, edit)) "_LivePix" else "") + ".jpg")
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 94, it) }
        copyExif(context, asset, file)
        return file
    }

    private fun copyExif(context: Context, asset: Asset, target: File) {
        val source: ExifInterface = runCatching {
            context.contentResolver.openFileDescriptor(asset.uri, "r")!!.use { pfd ->
                val src = ChannelByteSource.of(FileInputStream(pfd.fileDescriptor))
                val heif = HeifParser.parse(src)
                if (heif != null) AppleMetadata.heifExif(src, heif)
                else context.contentResolver.openInputStream(asset.uri)!!.use { ExifInterface(it) }
            }
        }.getOrNull() ?: return
        runCatching {
            val out = ExifInterface(target)
            for (tag in COPIED_TAGS) source.getAttribute(tag)?.let { out.setAttribute(tag, it) }
            out.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            out.setAttribute(ExifInterface.TAG_SOFTWARE, "LivePix")
            out.saveAttributes()
        }
    }

    /** Saves into Pictures/LivePix and returns the new item's uri. */
    fun saveToGallery(context: Context, file: File, mime: String, dateTaken: Long): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + ALBUM)
            put(MediaStore.MediaColumns.DATE_TAKEN, dateTaken)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val collection = if (mime.startsWith("video/")) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        if (mime.startsWith("video/")) values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/" + ALBUM)
        val uri = resolver.insert(collection, values) ?: return null
        resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return uri
    }

    fun shareIntent(context: Context, files: List<File>, mime: String): Intent {
        val uris = files.map { FileProvider.getUriForFile(context, context.packageName + ".files", it) }
        val send = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        send.type = mime
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, null)
    }

    /** Copies a file Android can share as-is (no edits) into the export cache. */
    fun original(context: Context, uri: Uri, name: String): File {
        val dir = File(context.cacheDir, "export").apply { mkdirs() }
        val file = File(dir, name)
        context.contentResolver.openInputStream(uri)!!.use { input -> file.outputStream().use { input.copyTo(it) } }
        return file
    }

    /**
     * Converts a Live Photo into Google's Motion Photo format: a JPEG with the movie appended
     * and XMP describing where it starts. Google Photos and most Android galleries play these.
     */
    fun motionPhoto(context: Context, asset: Asset, traits: Traits?, edit: AssetEdit): File {
        val video = requireNotNull(asset.liveVideo) { "not a Live Photo" }
        val still = renderJpeg(context, asset, traits, edit.copy(effect = LiveEffect.LIVE))
        val mp4 = File(context.cacheDir, "export/${asset.baseName}.mp4")
        remuxToMp4(context, video, mp4)
        val stillUs = edit.keyFrameUs ?: -1L
        val jpeg = insertXmp(still.readBytes(), motionPhotoXmp(mp4.length(), stillUs))
        val out = File(context.cacheDir, "export/${asset.baseName.uppercase()}.MP.jpg")
        out.outputStream().use { it.write(jpeg); mp4.inputStream().use { v -> v.copyTo(it) } }
        mp4.delete()
        return out
    }

    private fun motionPhotoXmp(videoLength: Long, presentationUs: Long): String = """
        <x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="LivePix">
         <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
          <rdf:Description rdf:about=""
            xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
            xmlns:Container="http://ns.google.com/photos/1.0/container/"
            xmlns:Item="http://ns.google.com/photos/1.0/container/item/"
            GCamera:MotionPhoto="1"
            GCamera:MotionPhotoVersion="1"
            GCamera:MotionPhotoPresentationTimestampUs="$presentationUs"
            GCamera:MicroVideo="1"
            GCamera:MicroVideoVersion="1"
            GCamera:MicroVideoOffset="$videoLength"
            GCamera:MicroVideoPresentationTimestampUs="$presentationUs">
           <Container:Directory>
            <rdf:Seq>
             <rdf:li rdf:parseType="Resource">
              <Container:Item Item:Mime="image/jpeg" Item:Semantic="Primary" Item:Length="0" Item:Padding="0"/>
             </rdf:li>
             <rdf:li rdf:parseType="Resource">
              <Container:Item Item:Mime="video/mp4" Item:Semantic="MotionPhoto" Item:Length="$videoLength" Item:Padding="0"/>
             </rdf:li>
            </rdf:Seq>
           </Container:Directory>
          </rdf:Description>
         </rdf:RDF>
        </x:xmpmeta>
    """.trimIndent()

    /** Inserts an XMP APP1 segment right after SOI (and after any Exif APP1). */
    internal fun insertXmp(jpeg: ByteArray, xmp: String): ByteArray {
        val header = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.US_ASCII)
        val payload = header + xmp.toByteArray(Charsets.UTF_8)
        val segLen = payload.size + 2
        require(segLen < 0xFFFF) { "XMP too large" }
        var insertAt = 2
        // Keep Exif first; some readers expect it immediately after SOI.
        while (insertAt + 4 < jpeg.size && (jpeg[insertAt].toInt() and 0xFF) == 0xFF &&
            (jpeg[insertAt + 1].toInt() and 0xFF) in listOf(0xE0, 0xE1)
        ) {
            val len = ((jpeg[insertAt + 2].toInt() and 0xFF) shl 8) or (jpeg[insertAt + 3].toInt() and 0xFF)
            insertAt += 2 + len
        }
        val out = ByteArrayOutputStream(jpeg.size + segLen + 2)
        out.write(jpeg, 0, insertAt)
        out.write(0xFF); out.write(0xE1); out.write(segLen shr 8); out.write(segLen and 0xFF)
        out.write(payload)
        out.write(jpeg, insertAt, jpeg.size - insertAt)
        return out.toByteArray()
    }

    /** Copies the video and audio tracks of a QuickTime movie into an MP4 container. */
    private fun remuxToMp4(context: Context, src: Uri, dst: File) {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, src, null)
        val muxer = MediaMuxer(dst.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val trackMap = HashMap<Int, Int>()
        var rotation = 0
        // One video track and one AAC track; Apple's timed-metadata (mebx) and APAC audio are dropped.
        var haveVideo = false
        var haveAudio = false
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/") && !haveVideo) {
                haveVideo = true
                if (format.containsKey(MediaFormat.KEY_ROTATION)) rotation = format.getInteger(MediaFormat.KEY_ROTATION)
                trackMap[i] = muxer.addTrack(format)
            } else if (mime == MediaFormat.MIMETYPE_AUDIO_AAC && !haveAudio) {
                haveAudio = true
                trackMap[i] = muxer.addTrack(format)
            }
        }
        muxer.setOrientationHint(rotation)
        muxer.start()
        val buf = ByteBuffer.allocate(4 * 1024 * 1024)
        val info = MediaCodec.BufferInfo()
        for ((inTrack, outTrack) in trackMap) {
            extractor.selectTrack(inTrack)
            extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            while (true) {
                val n = extractor.readSampleData(buf, 0)
                if (n < 0) break
                info.set(0, n, extractor.sampleTime, if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                muxer.writeSampleData(outTrack, buf, info)
                extractor.advance()
            }
            extractor.unselectTrack(inTrack)
        }
        muxer.stop(); muxer.release(); extractor.release()
    }
}
