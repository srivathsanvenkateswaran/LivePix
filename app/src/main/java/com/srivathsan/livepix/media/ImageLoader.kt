package com.srivathsan.livepix.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Log
import android.util.LruCache
import android.util.Size
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/** Bitmap decoding with a shared memory cache. Thumbnails come from MediaStore's own cache. */
object ImageLoader {
    private const val TAG = "ImageLoader"
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 5).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val thumbDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(6)
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val fullDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(2)

    fun peek(key: String): Bitmap? = cache.get(key)
    fun put(key: String, bitmap: Bitmap) { cache.put(key, bitmap) }

    suspend fun thumbnail(context: Context, uri: Uri, sizePx: Int): Bitmap? {
        val key = "t:$uri:$sizePx"
        cache.get(key)?.let { return it }
        return withContext(thumbDispatcher) {
            (runCatching { context.contentResolver.loadThumbnail(uri, Size(sizePx, sizePx), null) }
                .onFailure { Log.w(TAG, "thumbnail $uri: $it") }.getOrNull()
                ?: videoFrame(context, uri, sizePx)
                ?: diskThumbnail(context, uri, sizePx))?.also { cache.put(key, it) }
        }
    }

    /** MediaStore can fail on some videos (e.g. 10-bit HDR HEVC); fall back to decoding a frame ourselves. */
    private fun videoFrame(context: Context, uri: Uri, sizePx: Int): Bitmap? {
        val mime = context.contentResolver.getType(uri) ?: return null
        if (!mime.startsWith("video/")) return null
        return runCatching { LiveMedia(context, uri).use { it.frameAt(0, sizePx * 2) } }.getOrNull()
    }

    /** Full image, downscaled so its longest side is at most [maxSide], orientation applied. */
    suspend fun full(context: Context, uri: Uri, maxSide: Int, mutable: Boolean = false): Bitmap? {
        val key = "f:$uri:$maxSide:$mutable"
        cache.get(key)?.let { return it }
        return withContext(fullDispatcher) { decode(context, uri, maxSide, mutable)?.also { cache.put(key, it) } }
    }

    fun decode(context: Context, uri: Uri, maxSide: Int, software: Boolean): Bitmap? =
        platformDecode(context, uri, maxSide, software) ?: heifFallback(context, uri, maxSide)

    /**
     * Fallback thumbnails can mean decoding a 48-tile HEIC grid in software, so they are kept on
     * disk and only made once per file.
     */
    private fun diskThumbnail(context: Context, uri: Uri, sizePx: Int): Bitmap? {
        val dir = java.io.File(context.cacheDir, "thumbs").apply { mkdirs() }
        val file = java.io.File(dir, "${uri.lastPathSegment}_$sizePx.jpg")
        if (file.exists()) BitmapFactory.decodeFile(file.path)?.let { return it }
        val bmp = heifFallback(context, uri, sizePx) ?: return null
        runCatching { file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) } }
        return bmp
    }

    private fun heifFallback(context: Context, uri: Uri, maxSide: Int): Bitmap? = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            ChannelByteSource.of(java.io.FileInputStream(pfd.fileDescriptor)).let { HeifFallbackDecoder.decode(it, maxSide) }
        }
    }.onFailure { Log.w(TAG, "fallback $uri: $it") }.getOrNull()

    private fun platformDecode(context: Context, uri: Uri, maxSide: Int, software: Boolean): Bitmap? = runCatching {
        val src = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            val longest = max(info.size.width, info.size.height)
            if (longest > maxSide) {
                val s = maxSide.toFloat() / longest
                decoder.setTargetSize((info.size.width * s).roundToInt(), (info.size.height * s).roundToInt())
            }
            if (software) decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }.onFailure { Log.w(TAG, "decode $uri: $it") }.getOrNull()
}
