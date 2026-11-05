package com.srivathsan.livepix.media

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Applies a video trim when sharing or saving, using Media3 Transformer. */
object VideoTrim {
    fun mediaItem(uri: Uri, startUs: Long, endUs: Long?): MediaItem {
        val clip = MediaItem.ClippingConfiguration.Builder().setStartPositionUs(startUs)
        if (endUs != null) clip.setEndPositionUs(endUs)
        return MediaItem.Builder().setUri(uri).setClippingConfiguration(clip.build()).build()
    }

    /** Writes the trimmed clip to the export cache. Transformer must run on a looper thread. */
    @OptIn(UnstableApi::class)
    suspend fun export(context: Context, uri: Uri, name: String, startUs: Long, endUs: Long?): File {
        val out = File(context.cacheDir, "export/${name.substringBeforeLast('.')}_trimmed.mp4").apply { parentFile?.mkdirs(); delete() }
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val transformer = Transformer.Builder(context)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, result: ExportResult) = cont.resume(Unit)
                        override fun onError(composition: Composition, result: ExportResult, e: ExportException) = cont.resumeWithException(e)
                    })
                    .build()
                transformer.start(mediaItem(uri, startUs, endUs), out.absolutePath)
                cont.invokeOnCancellation { transformer.cancel() }
            }
        }
        return out
    }
}
