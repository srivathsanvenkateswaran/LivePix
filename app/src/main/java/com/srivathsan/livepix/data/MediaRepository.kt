package com.srivathsan.livepix.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlin.math.abs

object MediaRepository {
    /** Live Photo movies run about 3 s; anything much longer is a normal video. */
    private const val MAX_LIVE_MS = 4_500L
    private const val PAIR_WINDOW_MS = 3_000L

    /** Items in Android's trash (Recently Deleted), newest deletion first. */
    fun loadTrashed(context: Context): List<Asset> {
        val out = ArrayList<Asset>()
        for ((base, isVideo) in listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI to false, MediaStore.Video.Media.EXTERNAL_CONTENT_URI to true)) {
            val args = android.os.Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY) }
            val cols = arrayOf(
                MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.DATE_TAKEN, MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.WIDTH,
                MediaStore.MediaColumns.HEIGHT, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
                MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DATE_EXPIRES, MediaStore.MediaColumns.DURATION,
            )
            runCatching {
                context.contentResolver.query(base, cols, args, null)?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        out += Asset(
                            id = id, uri = ContentUris.withAppendedId(base, id), name = c.getString(1) ?: "",
                            mime = c.getString(2) ?: "", dateTaken = c.getLong(3), dateModified = c.getLong(4),
                            width = c.getInt(5), height = c.getInt(6), size = c.getLong(7), folder = c.getString(8) ?: "",
                            relativePath = c.getString(9) ?: "", isVideo = isVideo, durationMs = c.getLong(11),
                            expiresAt = c.getLong(10) * 1000,
                        )
                    }
                }
            }
        }
        // A deleted Live Photo's movie is trashed with it; show the pair as one item.
        val stills = out.filter { !it.isVideo }.map { it.relativePath + "/" + it.baseName }.toSet()
        return out.filterNot { it.isVideo && isLiveCandidate(it) && (it.relativePath + "/" + it.baseName) in stills }
            .sortedByDescending { it.expiresAt }
    }

    fun load(context: Context): List<Asset> {
        val images = queryImages(context)
        val videos = queryVideos(context)
        return pairByName(images, videos)
    }

    private fun queryImages(context: Context): List<Asset> {
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val cols = arrayOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_TAKEN, MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT, MediaStore.MediaColumns.ORIENTATION,
            MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.BUCKET_DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH,
        )
        val out = ArrayList<Asset>()
        context.contentResolver.query(uri, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val orientation = c.getInt(8)
                var w = c.getInt(6); var h = c.getInt(7)
                if (orientation % 180 != 0) { val t = w; w = h; h = t }
                val taken = c.getLong(3).takeIf { it > 0 } ?: (c.getLong(4) * 1000)
                out += Asset(
                    id = id, uri = ContentUris.withAppendedId(uri, id), name = c.getString(1) ?: "",
                    mime = c.getString(2) ?: "image/*", dateTaken = taken, dateModified = c.getLong(5),
                    width = w, height = h, size = c.getLong(9), folder = c.getString(10) ?: "Other",
                    relativePath = c.getString(11) ?: "", isVideo = false,
                )
            }
        }
        return out
    }

    private fun queryVideos(context: Context): List<Asset> {
        val uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val cols = arrayOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_TAKEN, MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT, MediaStore.MediaColumns.ORIENTATION,
            MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.BUCKET_DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.DURATION,
        )
        val out = ArrayList<Asset>()
        context.contentResolver.query(uri, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val orientation = c.getInt(8)
                var w = c.getInt(6); var h = c.getInt(7)
                if (orientation % 180 != 0) { val t = w; w = h; h = t }
                val taken = c.getLong(3).takeIf { it > 0 } ?: (c.getLong(4) * 1000)
                out += Asset(
                    id = id, uri = ContentUris.withAppendedId(uri, id), name = c.getString(1) ?: "",
                    mime = c.getString(2) ?: "video/*", dateTaken = taken, dateModified = c.getLong(5),
                    width = w, height = h, size = c.getLong(9), folder = c.getString(10) ?: "Other",
                    relativePath = c.getString(11) ?: "", isVideo = true, durationMs = c.getLong(12),
                )
            }
        }
        return out
    }

    fun isLiveCandidate(video: Asset) = video.isVideo && video.durationMs in 1..MAX_LIVE_MS

    /** First pass: `IMG_1234.HEIC` + `IMG_1234.MOV` in the same folder. */
    private fun pairByName(images: List<Asset>, videos: List<Asset>): List<Asset> {
        val byName = videos.filter(::isLiveCandidate).associateBy { it.relativePath + "/" + it.baseName }
        val used = HashSet<Long>()
        val paired = images.map { img ->
            val v = byName[img.relativePath + "/" + img.baseName]
            if (v != null && v.id !in used) {
                used += v.id
                img.copy(liveVideo = v.uri, liveVideoId = v.id)
            } else img
        }
        return (paired + videos.filter { it.id !in used }).sortedBy { it.dateTaken }
    }

    /**
     * Second pass, once traits are known: pair stills and short movies that share an Apple
     * content identifier, falling back to capture time for files whose metadata was stripped.
     */
    fun pairByContent(assets: List<Asset>, traits: Map<String, Traits>): List<Asset> {
        val freeVideos = assets.filter(::isLiveCandidate)
        val videosById = freeVideos.mapNotNull { v -> traits[v.versionKey]?.contentId?.let { it to v } }.toMap()
        val used = HashSet<Long>()
        val result = assets.map { a ->
            if (a.isVideo || a.isLive) return@map a
            val t = traits[a.versionKey]
            val cid = t?.contentId
            val match = cid?.let { videosById[it] }
                ?: freeVideos.firstOrNull { v ->
                    val vt = traits[v.versionKey]
                    v.id !in used && v.relativePath == a.relativePath && t?.make == "Apple" &&
                        vt?.make == "Apple" && vt.contentId == null && abs(v.dateTaken - a.dateTaken) <= PAIR_WINDOW_MS
                }
            if (match != null && match.id !in used) {
                used += match.id
                a.copy(liveVideo = match.uri, liveVideoId = match.id)
            } else a
        }
        return result.filter { !(it.isVideo && it.id in used) }.map { a ->
            val motion = if (!a.isVideo && a.liveVideo == null) traits[a.versionKey]?.motion else null
            if (motion != null) a.copy(embeddedMovie = motion) else a
        }
    }

    fun videoUri(id: Long): Uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
}
