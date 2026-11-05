package com.srivathsan.livepix.ui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier

/** Scopes for the grid-to-viewer zoom transition, passed implicitly to any screen. */
@OptIn(ExperimentalSharedTransitionApi::class)
class SharedScopes(val shared: SharedTransitionScope, val visibility: AnimatedVisibilityScope)

val LocalShared = compositionLocalOf<SharedScopes?> { null }

/** Marks an asset image as the same element across screens so it zooms between them. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedAsset(key: String): Modifier {
    val s = LocalShared.current ?: return this
    return with(s.shared) {
        this@sharedAsset.sharedElement(rememberSharedContentState("asset-$key"), s.visibility)
    }
}
