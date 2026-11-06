package com.srivathsan.livepix.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.srivathsan.livepix.data.Asset

/** Photo actions available from any grid, menu or viewer. Provided once by the app shell. */
class AssetActions(
    val share: (List<Asset>) -> Unit,
    val toggleFavorite: (List<Asset>) -> Unit,
    val addToAlbum: (List<Asset>) -> Unit,
    val setHidden: (List<Asset>, Boolean) -> Unit,
    val trash: (List<Asset>) -> Unit,
    val open: (Asset, Source) -> Unit,
    /** Plays [assets] full screen, starting at the item with the given key if there is one. */
    val slideshow: (List<Asset>, String?) -> Unit,
    val adjustDate: (List<Asset>) -> Unit,
    val adjustLocation: (List<Asset>) -> Unit,
)

val LocalAssetActions = staticCompositionLocalOf<AssetActions> { error("AssetActions not provided") }
