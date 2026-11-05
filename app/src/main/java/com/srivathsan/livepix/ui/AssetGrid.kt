package com.srivathsan.livepix.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.srivathsan.livepix.LibraryState
import com.srivathsan.livepix.data.Asset
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

val GRID_COLUMNS = listOf(3, 5, 9)

/**
 * Square-cell photo grid with Photos' gestures:
 * - pinch changes the column count;
 * - in selection mode, a sideways drag selects every photo it crosses (or deselects, if it
 *   started on a selected photo), and auto-scrolls near the top and bottom edges;
 * - outside selection mode, long-press opens the context menu.
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
    onSelectionChange: (Set<String>?) -> Unit,
    onOpen: (Asset) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    contextMenu: Boolean = true,
    badge: ((Asset) -> String?)? = null,
    header: (LazyGridScope.() -> Unit)? = null,
    footer: (LazyGridScope.() -> Unit)? = null,
) {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val currentSelection by rememberUpdatedState(selection)
    val currentAssets by rememberUpdatedState(assets)
    val indexByKey = remember(assets) { assets.withIndex().associate { (i, a) -> a.key to i } }
    var menuFor by remember { mutableStateOf<Asset?>(null) }

    // Item offsets are measured from the viewport start, which sits above the container top by the
    // grid's top content padding; convert the touch point into that space first.
    fun indexAt(p: Offset): Int? {
        val info = gridState.layoutInfo
        val y = p.y + info.viewportStartOffset
        return info.visibleItemsInfo.firstOrNull { item ->
            p.x >= item.offset.x && p.x < item.offset.x + item.size.width &&
                y >= item.offset.y && y < item.offset.y + item.size.height
        }?.let { indexByKey[it.key] }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val cell = maxWidth / columns
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = gridState,
            contentPadding = contentPadding,
            horizontalArrangement = Arrangement.spacedBy(1.5.dp),
            verticalArrangement = Arrangement.spacedBy(1.5.dp),
            modifier = Modifier.fillMaxSize()
                .pointerInput(columns) {
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
                }
                .pointerInput(selection != null, indexByKey) {
                    if (selection == null) return@pointerInput
                    val edge = 72.dp.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val start = indexAt(down.position) ?: return@awaitEachGesture
                        val base = currentSelection ?: return@awaitEachGesture
                        val adding = currentAssets[start].key !in base
                        var total = Offset.Zero
                        var dragging = false
                        var pointer = down.position
                        var autoScroll: Job? = null
                        var lastIndex = -1

                        fun apply(index: Int) {
                            if (index == lastIndex) return
                            lastIndex = index
                            val keys = currentAssets.subList(min(start, index), max(start, index) + 1).map { it.key }
                            onSelectionChange(if (adding) base + keys else base - keys.toSet())
                            haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                        }

                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            if (!dragging) {
                                total += change.positionChange()
                                if (total.getDistance() < viewConfiguration.touchSlop) continue
                                // Sideways starts a selection drag; vertical is left to the grid to scroll.
                                if (abs(total.x) <= abs(total.y)) break
                                dragging = true
                            }
                            change.consume()
                            pointer = change.position
                            indexAt(pointer)?.let(::apply)
                            val speed = when {
                                pointer.y < edge -> -(edge - pointer.y) / 3f
                                pointer.y > size.height - edge -> (pointer.y - (size.height - edge)) / 3f
                                else -> 0f
                            }
                            if (speed != 0f && autoScroll?.isActive != true) {
                                autoScroll = scope.launch {
                                    while (isActive) {
                                        val p = pointer
                                        val s = when {
                                            p.y < edge -> -(edge - p.y) / 3f
                                            p.y > size.height - edge -> (p.y - (size.height - edge)) / 3f
                                            else -> 0f
                                        }
                                        if (s == 0f) break
                                        gridState.scrollBy(s)
                                        indexAt(p)?.let(::apply)
                                        delay(16)
                                    }
                                }
                            }
                        }
                        autoScroll?.cancel()
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
                    label = badge?.invoke(asset),
                    imageModifier = Modifier.sharedAsset(asset.key),
                    modifier = Modifier.aspectRatio(1f).animateItem().combinedClickable(
                        onClick = {
                            val s = selection
                            if (s != null) onSelectionChange(if (asset.key in s) s - asset.key else s + asset.key) else onOpen(asset)
                        },
                        onLongClick = if (contextMenu) ({
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            if (selection != null) onSelectionChange(selection + asset.key) else menuFor = asset
                        }) else null,
                    ),
                )
            }
            footer?.invoke(this)
        }
    }

    menuFor?.let { a ->
        PhotoContextMenu(a, state, onSelect = { onSelectionChange(setOf(a.key)) }, onDismiss = { menuFor = null })
    }
}

fun LazyGridScope.fullSpan(key: String, content: @Composable () -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }, contentType = key) { content() }
}
