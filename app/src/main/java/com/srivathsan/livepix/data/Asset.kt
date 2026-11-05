package com.srivathsan.livepix.data

import android.content.Context
import android.net.Uri
import com.srivathsan.livepix.media.MotionPhoto

/**
 * One item in the library. A Live Photo is a single asset: the still plus either a separate
 * movie ([liveVideo], iPhone's HEIC + MOV) or a movie inside the file ([embeddedMovie], a
 * Google or Samsung Motion Photo).
 */
data class Asset(
    val id: Long,
    val uri: Uri,
    val name: String,
    val mime: String,
    val dateTaken: Long,
    val dateModified: Long,
    val width: Int,
    val height: Int,
    val size: Long,
    val folder: String,
    val relativePath: String,
    val isVideo: Boolean,
    val durationMs: Long = 0,
    val liveVideo: Uri? = null,
    val liveVideoId: Long? = null,
    val embeddedMovie: MotionPhoto.Location? = null,
    /** For items in Recently Deleted: when Android removes them for good (epoch ms). */
    val expiresAt: Long = 0,
) {
    val key: String get() = (if (isVideo) "v" else "i") + id
    val isLive: Boolean get() = liveVideo != null || embeddedMovie != null
    val isMotionPhoto: Boolean get() = liveVideo == null && embeddedMovie != null

    /** A playable uri for the Live movie, extracting an embedded one on first use. Blocking. */
    fun movieUri(context: Context): Uri? =
        liveVideo ?: embeddedMovie?.let { MotionPhoto.extract(context, uri, versionKey, it) }
    val isHeif: Boolean get() = mime == "image/heic" || mime == "image/heif" || name.endsWith(".heic", true) || name.endsWith(".heif", true)
    val baseName: String get() = name.substringBeforeLast('.').lowercase()
    /** Cache key that changes when the file does. */
    val versionKey: String get() = "$key:$dateModified:$size"
}

/** Facts read from the file itself. Expensive, so cached on disk. */
data class Traits(
    val hasDepth: Boolean = false,
    val hasMatte: Boolean = false,
    val contentId: String? = null,
    val make: String? = null,
    val model: String? = null,
    /** Taken with the front camera, from the Exif lens description. */
    val front: Boolean = false,
    val motionOffset: Long? = null,
    val motionLength: Long? = null,
    /** Where the still sits in the Live movie, when the file records it. */
    val presentationUs: Long? = null,
) {
    val motion: MotionPhoto.Location? get() =
        if (motionOffset != null && motionLength != null) MotionPhoto.Location(motionOffset, motionLength) else null
}

enum class LiveEffect(val label: String) {
    LIVE("Live"),
    LOOP("Loop"),
    BOUNCE("Bounce"),
    LONG_EXPOSURE("Long Exposure"),
    OFF("Off"),
}

/** Non-destructive edits; the original files are never modified. */
data class AssetEdit(
    val effect: LiveEffect = LiveEffect.LIVE,
    val keyFrameUs: Long? = null,
    val muted: Boolean = false,
    val portrait: Boolean = true,
    val aperture: Float = 4.5f,
    /** Normalized focus point chosen by tapping; null means the iPhone-like default. */
    val focusX: Float? = null,
    val focusY: Float? = null,
    val lighting: String = "NATURAL",
    val lightingIntensity: Float = 0.5f,
    val favorite: Boolean = false,
) {
    val liveEnabled: Boolean get() = effect != LiveEffect.OFF

    /** True when the photo looks different from the original file. */
    fun isModified(asset: Asset, traits: Traits?): Boolean =
        keyFrameUs != null || effect == LiveEffect.LONG_EXPOSURE ||
            (traits?.hasDepth == true && (portrait || lighting != "NATURAL"))
}
