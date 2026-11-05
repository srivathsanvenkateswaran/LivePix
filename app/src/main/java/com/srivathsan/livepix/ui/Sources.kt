package com.srivathsan.livepix.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Camera
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ShutterSpeed
import androidx.compose.material.icons.outlined.TripOrigin
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.ui.graphics.vector.ImageVector
import com.srivathsan.livepix.LibraryState
import com.srivathsan.livepix.data.Asset
import com.srivathsan.livepix.data.LiveEffect

enum class MediaType(val title: String, val icon: ImageVector) {
    FAVORITES("Favourites", Icons.Outlined.FavoriteBorder),
    VIDEOS("Videos", Icons.Outlined.Videocam),
    LIVE("Live Photos", Icons.Outlined.TripOrigin),
    PORTRAIT("Portrait", Icons.Outlined.Camera),
    LONG_EXPOSURE("Long Exposure", Icons.Outlined.ShutterSpeed),
    EDITED("Recently Edited", Icons.Outlined.Edit),
}

/** Which list of assets a screen (and the viewer opened from it) shows. */
sealed interface Source {
    val title: String

    data object Library : Source { override val title = "Library" }
    data class Album(val folder: String) : Source { override val title = folder }
    data class Type(val type: MediaType) : Source { override val title = type.title }
    data class Search(val query: String) : Source { override val title = query }
    /** A file opened from another app. */
    data class External(val uri: android.net.Uri) : Source { override val title = "" }

    fun assets(state: LibraryState): List<Asset> = when (this) {
        Library -> state.assets
        is Album -> state.assets.filter { it.folder == folder }
        is Type -> state.assets.filter { matches(type, it, state) }
        is Search -> search(state, query)
        is External -> emptyList()
    }

    companion object {
        fun matches(type: MediaType, a: Asset, state: LibraryState): Boolean = when (type) {
            MediaType.FAVORITES -> state.editOf(a).favorite
            MediaType.VIDEOS -> a.isVideo
            MediaType.LIVE -> a.isLive
            MediaType.PORTRAIT -> state.traitsOf(a)?.hasDepth == true
            MediaType.LONG_EXPOSURE -> a.isLive && state.editOf(a).effect == LiveEffect.LONG_EXPOSURE
            MediaType.EDITED -> state.edits[a.key]?.let { it.copy(favorite = false) != com.srivathsan.livepix.data.AssetEdit() } == true
        }

        fun search(state: LibraryState, query: String): List<Asset> {
            val q = query.trim().lowercase()
            if (q.isEmpty()) return emptyList()
            MediaType.entries.firstOrNull { it.title.lowercase().startsWith(q) || (q == "favorites" && it == MediaType.FAVORITES) }?.let { t ->
                return state.assets.filter { matches(t, it, state) }
            }
            return state.assets.filter { a ->
                a.name.lowercase().contains(q) || a.folder.lowercase().contains(q) ||
                    Format.monthYear(a.dateTaken).lowercase().contains(q) ||
                    (state.traitsOf(a)?.model?.lowercase()?.contains(q) == true)
            }
        }
    }
}
