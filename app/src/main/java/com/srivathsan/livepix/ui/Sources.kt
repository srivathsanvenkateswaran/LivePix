package com.srivathsan.livepix.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Animation
import androidx.compose.material.icons.outlined.Camera
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.Panorama
import androidx.compose.material.icons.outlined.RawOn
import androidx.compose.material.icons.outlined.Screenshot
import androidx.compose.material.icons.outlined.ShutterSpeed
import androidx.compose.material.icons.outlined.TripOrigin
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.ui.graphics.vector.ImageVector
import com.srivathsan.livepix.LibraryState
import com.srivathsan.livepix.data.Asset
import com.srivathsan.livepix.data.AssetEdit
import com.srivathsan.livepix.data.LiveEffect

enum class MediaType(val title: String, val icon: ImageVector) {
    FAVORITES("Favourites", Icons.Outlined.FavoriteBorder),
    VIDEOS("Videos", Icons.Outlined.Videocam),
    SELFIES("Selfies", Icons.Outlined.Face),
    LIVE("Live Photos", Icons.Outlined.TripOrigin),
    PORTRAIT("Portrait", Icons.Outlined.Camera),
    LONG_EXPOSURE("Long Exposure", Icons.Outlined.ShutterSpeed),
    PANORAMAS("Panoramas", Icons.Outlined.Panorama),
    SCREENSHOTS("Screenshots", Icons.Outlined.Screenshot),
    ANIMATED("Animated", Icons.Outlined.Animation),
    RAW("RAW", Icons.Outlined.RawOn),
    EDITED("Recently Edited", Icons.Outlined.Edit),
}

/** Which list of assets a screen (and the viewer opened from it) shows. */
sealed interface Source {
    val title: String

    data object Library : Source { override val title = "Library" }
    /** A device folder, e.g. DCIM/Camera or Download. */
    data class Album(val folder: String) : Source { override val title = folder }
    /** An album the user made in LivePix. */
    data class UserAlbumSource(val id: String, override val title: String) : Source
    data class Type(val type: MediaType) : Source { override val title = type.title }
    data class Search(val query: String) : Source { override val title = query }
    data object Hidden : Source { override val title = "Hidden" }
    /** A fixed set of items: a place on the map, a memory, a person. */
    data class Keys(override val title: String, val keys: List<String>) : Source

    fun assets(state: LibraryState): List<Asset> = when (this) {
        Library -> state.assets
        is Album -> state.assets.filter { it.folder == folder }
        is UserAlbumSource -> state.albums.firstOrNull { it.id == id }?.keys?.toSet()
            ?.let { keys -> state.assets.filter { it.key in keys } }.orEmpty()
        is Type -> state.assets.filter { matches(type, it, state) }
        is Search -> search(state, query)
        Hidden -> state.allAssets.filter { it.key in state.hidden }
        is Keys -> keys.toSet().let { set -> state.assets.filter { it.key in set } }
    }

    companion object {
        private val RAW_EXT = setOf("dng", "cr2", "cr3", "nef", "arw", "orf", "rw2", "raf")

        fun matches(type: MediaType, a: Asset, state: LibraryState): Boolean = when (type) {
            MediaType.FAVORITES -> state.editOf(a).favorite
            MediaType.VIDEOS -> a.isVideo
            MediaType.SELFIES -> state.traitsOf(a)?.front == true
            MediaType.LIVE -> a.isLive
            MediaType.PORTRAIT -> state.traitsOf(a)?.hasDepth == true
            MediaType.LONG_EXPOSURE -> a.isLive && state.editOf(a).effect == LiveEffect.LONG_EXPOSURE
            MediaType.PANORAMAS -> !a.isVideo && a.width > 0 && a.height > 0 &&
                maxOf(a.width, a.height).toFloat() / minOf(a.width, a.height) >= 2.2f
            MediaType.SCREENSHOTS -> !a.isVideo && (a.relativePath.contains("Screenshot", true) || a.name.startsWith("Screenshot", true))
            MediaType.ANIMATED -> a.mime == "image/gif" || a.name.endsWith(".gif", true)
            MediaType.RAW -> a.name.substringAfterLast('.', "").lowercase() in RAW_EXT
            MediaType.EDITED -> state.edits[a.key]?.let { it.copy(favorite = false) != AssetEdit() } == true
        }

        fun search(state: LibraryState, query: String): List<Asset> {
            val q = query.trim().lowercase()
            if (q.isEmpty()) return emptyList()
            MediaType.entries.firstOrNull { it.title.lowercase().startsWith(q) || (q == "favorites" && it == MediaType.FAVORITES) }?.let { t ->
                return state.assets.filter { matches(t, it, state) }
            }
            state.albums.firstOrNull { it.name.lowercase() == q }?.let { album ->
                return state.assets.filter { it.key in album.keys }
            }
            return state.assets.filter { a ->
                a.name.lowercase().contains(q) || a.folder.lowercase().contains(q) ||
                    Format.monthYear(a.dateTaken).lowercase().contains(q) ||
                    (state.traitsOf(a)?.model?.lowercase()?.contains(q) == true) ||
                    state.placeOf(a)?.fullName?.lowercase()?.contains(q) == true
            }
        }
    }
}

/** Utilities rows on the Collections screen. */
enum class Utility(val title: String, val icon: ImageVector) {
    HIDDEN("Hidden", Icons.Outlined.VisibilityOff),
    RECENTLY_DELETED("Recently Deleted", Icons.Outlined.Delete),
}

