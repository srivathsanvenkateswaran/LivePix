package com.srivathsan.livepix.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.srivathsan.livepix.LibraryState
import com.srivathsan.livepix.data.Asset
import com.srivathsan.livepix.ui.components.GlassIconButton
import com.srivathsan.livepix.ui.components.GlassTextButton
import com.srivathsan.livepix.ui.components.SegmentedControl
import com.srivathsan.livepix.ui.theme.Ios
import com.srivathsan.livepix.ui.theme.IosType
import kotlinx.coroutines.launch

enum class LibraryMode(val label: String) { YEARS("Years"), MONTHS("Months"), ALL("All") }

/** Height reserved at the bottom for the floating tab bar. */
val TAB_BAR_SPACE = 92.dp

@Composable
fun LibraryScreen(
    state: LibraryState,
    gridState: LazyGridState,
    yearsState: LazyListState,
    monthsState: LazyListState,
    columns: Int,
    onColumnsChange: (Int) -> Unit,
    mode: LibraryMode,
    onModeChange: (LibraryMode) -> Unit,
    selection: Set<String>?,
    onSelectionChange: (Set<String>?) -> Unit,
    onOpen: (Asset) -> Unit,
    onShare: (List<Asset>) -> Unit,
    onDelete: (List<Asset>) -> Unit,
) {
    val c = Ios.colors
    val assets = state.assets
    val scope = rememberCoroutineScope()
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(Modifier.fillMaxSize().background(c.background)) {
        AnimatedContent(mode, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "mode") { m ->
            when (m) {
                LibraryMode.ALL -> AssetGrid(
                    assets = assets, state = state, gridState = gridState, columns = columns,
                    onColumnsChange = onColumnsChange, selection = selection,
                    onToggleSelect = { a -> selection?.let { s -> onSelectionChange(if (a.key in s) s - a.key else s + a.key) } },
                    onLongPress = { a -> onSelectionChange((selection ?: emptySet()) + a.key) },
                    onOpen = onOpen,
                    contentPadding = PaddingValues(top = top + 96.dp, bottom = bottom + TAB_BAR_SPACE + 64.dp),
                    footer = {
                        fullSpan("footer") { LibraryFooter(state) }
                    },
                )
                LibraryMode.MONTHS -> PeriodCards(
                    groups = remember(assets) { assets.filter { true }.groupBy { Format.monthKey(it.dateTaken) }.values.toList() },
                    listState = monthsState,
                    title = { Format.monthYear(it.first().dateTaken) },
                    subtitle = { g -> Format.count(g.size, "item") },
                    padding = PaddingValues(top = top + 96.dp, bottom = bottom + TAB_BAR_SPACE + 64.dp, start = 16.dp, end = 16.dp),
                ) { group ->
                    onModeChange(LibraryMode.ALL)
                    scope.launch { gridState.scrollToItem(assets.indexOf(group.first()).coerceAtLeast(0)) }
                }
                LibraryMode.YEARS -> PeriodCards(
                    groups = remember(assets) { assets.groupBy { Format.year(it.dateTaken) }.values.toList() },
                    listState = yearsState,
                    title = { Format.year(it.first().dateTaken).toString() },
                    subtitle = { g -> Format.count(g.size, "item") },
                    padding = PaddingValues(top = top + 96.dp, bottom = bottom + TAB_BAR_SPACE + 64.dp, start = 16.dp, end = 16.dp),
                    tall = true,
                ) { group ->
                    onModeChange(LibraryMode.MONTHS)
                    val months = assets.groupBy { Format.monthKey(it.dateTaken) }.keys.toList()
                    val index = months.indexOf(Format.monthKey(group.first().dateTaken)).coerceAtLeast(0)
                    scope.launch { monthsState.scrollToItem(index) }
                }
            }
        }

        LibraryHeader(state, gridState, mode, selection, onSelectionChange, top)

        // Bottom chrome: segmented control in browse mode, action bar while selecting.
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, c.background.copy(alpha = 0.85f))))
                .padding(bottom = bottom + TAB_BAR_SPACE + 4.dp, top = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (selection == null) {
                SegmentedControl(
                    options = LibraryMode.entries.map { it.label },
                    selected = mode.ordinal,
                    onSelect = { onModeChange(LibraryMode.entries[it]) },
                    modifier = Modifier.width(260.dp),
                )
            }
        }
        if (selection != null) {
            SelectionBar(
                count = selection.size,
                onShare = { onShare(assets.filter { it.key in selection }) },
                onDelete = { onDelete(assets.filter { it.key in selection }) },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bottom + 16.dp, start = 16.dp, end = 16.dp),
            )
        }
    }
}

@Composable
private fun LibraryHeader(
    state: LibraryState,
    gridState: LazyGridState,
    mode: LibraryMode,
    selection: Set<String>?,
    onSelectionChange: (Set<String>?) -> Unit,
    top: androidx.compose.ui.unit.Dp,
) {
    val c = Ios.colors
    val assets = state.assets
    val subtitle by remember(assets, mode) {
        derivedStateOf {
            if (assets.isEmpty()) "" else when (mode) {
                LibraryMode.ALL -> {
                    val info = gridState.layoutInfo.visibleItemsInfo.filter { it.index < assets.size }
                    if (info.isEmpty()) Format.shortDate(assets.last().dateTaken)
                    else Format.dateRange(assets[info.first().index].dateTaken, assets[info.last().index].dateTaken)
                }
                else -> Format.count(assets.size, "item")
            }
        }
    }
    Box(
        Modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(c.background, c.background.copy(alpha = 0.92f), c.background.copy(alpha = 0f))))
            .padding(top = top + 6.dp, start = 18.dp, end = 16.dp, bottom = 22.dp),
    ) {
        Column(Modifier.align(Alignment.CenterStart)) {
            Text(
                if (selection != null) (if (selection.isEmpty()) "Select Items" else Format.count(selection.size, "Item") + " Selected") else "Library",
                style = IosType.largeTitle, color = c.label,
            )
            Text(subtitle, style = IosType.subheadline.copy(fontWeight = FontWeight.SemiBold), color = c.secondaryLabel)
        }
        Row(Modifier.align(Alignment.CenterEnd), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (mode == LibraryMode.ALL) {
                GlassTextButton(if (selection == null) "Select" else "Cancel") {
                    onSelectionChange(if (selection == null) emptySet() else null)
                }
            }
        }
    }
}

@Composable
private fun LibraryFooter(state: LibraryState) {
    val c = Ios.colors
    val photos = state.assets.count { !it.isVideo }
    val videos = state.assets.size - photos
    Column(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            listOfNotNull(Format.count(photos, "Photo"), if (videos > 0) Format.count(videos, "Video") else null).joinToString(", "),
            style = IosType.subheadline.copy(fontWeight = FontWeight.SemiBold), color = c.label, textAlign = TextAlign.Center,
        )
        if (state.scanning) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                CircularProgressIndicator(Modifier.height(12.dp).width(12.dp), strokeWidth = 1.5.dp, color = c.secondaryLabel)
                Spacer(Modifier.width(6.dp))
                Text("Finding Live and Portrait photos…", style = IosType.footnote, color = c.secondaryLabel)
            }
        }
    }
}

/** Years/Months: large rounded cards, each showing a key photo from the period. */
@Composable
private fun PeriodCards(
    groups: List<List<Asset>>,
    listState: LazyListState,
    title: (List<Asset>) -> String,
    subtitle: (List<Asset>) -> String,
    padding: PaddingValues,
    tall: Boolean = false,
    onOpen: (List<Asset>) -> Unit,
) {
    LazyColumn(state = listState, contentPadding = padding, verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxSize()) {
        items(groups, key = { title(it) }) { group ->
            val cover = group.firstOrNull { !it.isVideo } ?: group.first()
            val bmp = rememberThumbnail(cover, 512.dp)
            Box(
                Modifier.fillMaxWidth().height(if (tall) 300.dp else 220.dp).clip(RoundedCornerShape(18.dp))
                    .background(Ios.colors.fill).clickable { onOpen(group) },
            ) {
                if (bmp != null) Image(bmp.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0x66000000), 0.45f to Color.Transparent)))
                Column(Modifier.padding(16.dp)) {
                    val shadow = Shadow(Color(0x66000000), blurRadius = 8f)
                    Text(title(group), style = (if (tall) IosType.largeTitle else IosType.title2).copy(shadow = shadow), color = Color.White)
                    Text(subtitle(group), style = IosType.subheadline.copy(fontWeight = FontWeight.SemiBold, shadow = shadow), color = Color.White.copy(alpha = 0.9f))
                }
            }
        }
    }
}

@Composable
fun SelectionBar(count: Int, onShare: () -> Unit, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    val c = Ios.colors
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        GlassIconButton(Icons.Outlined.IosShare, "Share", tint = if (count > 0) c.blue else c.tertiaryLabel) { if (count > 0) onShare() }
        Spacer(Modifier.weight(1f))
        Text(
            if (count == 0) "Select Items" else Format.count(count, "Item") + " Selected",
            style = IosType.headline.copy(fontSize = 15.sp), color = c.label,
        )
        Spacer(Modifier.weight(1f))
        GlassIconButton(Icons.Outlined.Delete, "Delete", tint = if (count > 0) c.red else c.tertiaryLabel) { if (count > 0) onDelete() }
    }
}
