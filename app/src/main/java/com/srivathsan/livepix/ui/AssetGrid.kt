package com.srivathsan.livepix.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.srivathsan.livepix.LibraryState
import com.srivathsan.livepix.data.Asset

val GRID_COLUMNS = listOf(3, 5, 9)

/**
 * Square-cell photo grid. Pinch changes the column count like Photos; long-press starts
 * selection. [header] and [footer] span the full width.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AssetGrid(
    assets: List<Asset>,
    state: LibraryState,
    gridState: LazyGridState,
    columns: Int,
    onColumnsChange: (Int) -> Unit,
    selection: Set<String>?,
    onToggleSelect: (Asset) -> Unit,
    onLongPress: (Asset) -> Unit,
    onOpen: (Asset) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    header: (LazyGridScope.() -> Unit)? = null,
    footer: (LazyGridScope.() -> Unit)? = null,
) {
    val haptic = LocalHapticFeedback.current
    BoxWithConstraints(modifier.fillMaxSize()) {
        val cell = maxWidth / columns
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = gridState,
            contentPadding = contentPadding,
            horizontalArrangement = Arrangement.spacedBy(1.5.dp),
            verticalArrangement = Arrangement.spacedBy(1.5.dp),
            modifier = Modifier.fillMaxSize().pointerInput(columns) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var zoom = 1f
                    var changed = false
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.count { it.pressed } >= 2) {
                            zoom *= event.calculateZoom()
                            event.changes.forEach { it.consume() }
                            val i = GRID_COLUMNS.indexOf(columns).coerceAtLeast(0)
                            val next = when {
                                zoom > 1.3f && i > 0 -> GRID_COLUMNS[i - 1]
                                zoom < 0.77f && i < GRID_COLUMNS.lastIndex -> GRID_COLUMNS[i + 1]
                                else -> null
                            }
                            if (next != null && !changed) {
                                changed = true
                                haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                onColumnsChange(next)
                            }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        ) {
            header?.invoke(this)
            items(assets, key = { it.key }, contentType = { "asset" }) { asset ->
                val edit = state.editOf(asset)
                AssetThumbnail(
                    asset = asset,
                    cellSize = cell,
                    favorite = edit.favorite,
                    selected = selection?.let { asset.key in it },
                    imageModifier = Modifier.sharedAsset(asset.key),
                    modifier = Modifier.aspectRatio(1f).animateItem().combinedClickable(
                        onClick = { if (selection != null) onToggleSelect(asset) else onOpen(asset) },
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onLongPress(asset)
                        },
                    ),
                )
            }
            footer?.invoke(this)
        }
    }
}

fun LazyGridScope.fullSpan(key: String, content: @Composable () -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }, contentType = key) { content() }
}
