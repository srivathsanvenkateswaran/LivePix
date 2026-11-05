package com.srivathsan.livepix.media

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import com.srivathsan.livepix.data.Asset
import com.srivathsan.livepix.data.AssetEdit
import com.srivathsan.livepix.data.LiveEffect
import com.srivathsan.livepix.data.Traits
import java.io.File
import java.io.FileInputStream

/**
 * Produces the still a photo should display with its edits applied: chosen Live key frame or
 * Long Exposure as the base, then Portrait depth of field and lighting.
 */
object StillRenderer {
    private val depthCache = LruCache<String, DepthData>(6)
    private val renderCache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    /** Whether this asset needs our renderer instead of showing the file as-is. */
    fun needsRender(asset: Asset, traits: Traits?, edit: AssetEdit): Boolean =
        edit.hasImageEdits ||
            (asset.isLive && (edit.keyFrameUs != null || edit.effect == LiveEffect.LONG_EXPOSURE)) ||
            (traits?.hasDepth == true && (edit.portrait || edit.lighting != PortraitLighting.NATURAL.name))

    fun cacheKey(asset: Asset, edit: AssetEdit, maxSide: Int) = "${asset.versionKey}:$maxSide:${edit.copy(favorite = false, muted = false)}"

    fun cached(asset: Asset, edit: AssetEdit, maxSide: Int): Bitmap? = renderCache.get(cacheKey(asset, edit, maxSide))

    fun depth(context: Context, asset: Asset): DepthData? {
        depthCache.get(asset.versionKey)?.let { return it }
        val pfd = context.contentResolver.openFileDescriptor(asset.uri, "r") ?: return null
        return pfd.use {
            ChannelByteSource.of(FileInputStream(it.fileDescriptor)).let { src ->
                val heif = HeifParser.parse(src) ?: return null
                DepthData.load(src, heif)
            }
        }?.also { depthCache.put(asset.versionKey, it) }
    }

    /** One render at a time: each needs tens of MB of working memory. */
    private val lock = Any()

    /** Blocking; call off the main thread. */
    fun render(context: Context, asset: Asset, traits: Traits?, edit: AssetEdit, maxSide: Int): Bitmap? {
        val key = cacheKey(asset, edit, maxSide)
        renderCache.get(key)?.let { return it }
        return synchronized(lock) { renderCache.get(key) ?: renderLocked(context, asset, traits, edit, maxSide, key) }
    }

    private fun renderLocked(context: Context, asset: Asset, traits: Traits?, edit: AssetEdit, maxSide: Int, key: String): Bitmap? {

        val video = if (asset.isLive) asset.movieUri(context) else null
        var fromStill = true
        val base: Bitmap = when {
            video != null && edit.effect == LiveEffect.LONG_EXPOSURE -> {
                fromStill = false
                longExposure(context, asset, maxSide) ?: return null
            }
            video != null && edit.keyFrameUs != null -> {
                fromStill = false
                LiveMedia(context, video).use { it.frameAt(edit.keyFrameUs, maxSide) } ?: return null
            }
            else -> ImageLoader.decode(context, asset.uri, maxSide, software = true) ?: return null
        }

        var result = base
        // Depth belongs to the still; a different movie frame no longer lines up with it.
        if (fromStill && traits?.hasDepth == true) {
            val lighting = runCatching { PortraitLighting.valueOf(edit.lighting) }.getOrDefault(PortraitLighting.NATURAL)
            if (edit.portrait || lighting != PortraitLighting.NATURAL) {
                val depth = depth(context, asset)
                if (depth != null) {
                    val focus = if (edit.focusX != null && edit.focusY != null) depth.disparityAt(edit.focusX, edit.focusY)
                    else depth.defaultFocusDisparity()
                    val params = PortraitParams(
                        aperture = if (edit.portrait) edit.aperture else PortraitRenderer.MAX_APERTURE * 4,
                        focusDisparity = focus,
                        lighting = lighting,
                        lightingIntensity = edit.lightingIntensity,
                    )
                    result = PortraitRenderer.render(base, depth, params)
                }
            }
        }
        result = applyImageEdits(result, edit)
        renderCache.put(key, result)
        return result
    }

    /** Adjust, Filters, then Crop: the last stage of the pipeline, shared with the editor preview. */
    fun applyImageEdits(src: Bitmap, edit: AssetEdit): Bitmap {
        var out = src
        val values = edit.adjust.mapNotNull { (k, v) -> runCatching { Adjust.valueOf(k) }.getOrNull()?.let { it to v } }.toMap()
        val filter = runCatching { PhotoFilter.valueOf(edit.filter) }.getOrDefault(PhotoFilter.ORIGINAL)
        if (!ToneEngine.isIdentity(values, filter, edit.filterIntensity)) out = ToneEngine.apply(out, values, filter, edit.filterIntensity)
        edit.crop?.takeUnless { it.isIdentity }?.let { out = it.apply(out) }
        return out
    }

    /** Long Exposure is slow to compute, so it is cached on disk per movie. */
    private fun longExposure(context: Context, asset: Asset, maxSide: Int): Bitmap? {
        val dir = File(context.cacheDir, "longexposure").apply { mkdirs() }
        val file = File(dir, "${asset.versionKey.replace(':', '_')}_$maxSide.jpg")
        if (file.exists()) ImageLoader.decode(context, android.net.Uri.fromFile(file), maxSide, software = true)?.let { return it }
        val bmp = LiveMedia(context, asset.movieUri(context) ?: return null).use { it.longExposure(maxSide) } ?: return null
        runCatching { file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) } }
        return bmp
    }
}
