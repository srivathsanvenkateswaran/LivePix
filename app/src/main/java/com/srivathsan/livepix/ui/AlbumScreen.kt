package com.srivathsan.livepix.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.srivathsan.livepix.LibraryState
import com.srivathsan.livepix.data.Asset
import com.srivathsan.livepix.ui.components.GlassIconButton
import com.srivathsan.livepix.ui.components.GlassTextButton
import com.srivathsan.livepix.ui.theme.Ios
import com.srivathsan.livepix.ui.theme.IosType

/** A single collection (album, media type or search result) as a grid. */
@Composable
fun AlbumScreen(
    source: Source,
    state: LibraryState,
    gridState: LazyGridState,
    onBack: () -> Unit,
    onOpen: (Asset) -> Unit,
    onShare: (List<Asset>) -> Unit,
    onDelete: (List<Asset>) -> Unit,
) {
    val c = Ios.colors
    val assets = remember(state, source) { source.assets(state) }
    var columns by rememberSaveable { mutableIntStateOf(3) }
    var selection by remember { mutableStateOf<Set<String>?>(null) }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Box(Modifier.fillMaxSize().background(c.background)) {
        AssetGrid(
            assets = assets, state = state, gridState = gridState, columns = columns, onColumnsChange = { columns = it },
            selection = selection,
            onToggleSelect = { a -> selection?.let { s -> selection = if (a.key in s) s - a.key else s + a.key } },
            onLongPress = { a -> selection = (selection ?: emptySet()) + a.key },
            onOpen = onOpen,
            contentPadding = PaddingValues(top = top + 112.dp, bottom = bottom + 80.dp),
            footer = {
                fullSpan("footer") {
                    Text(
                        if (assets.isEmpty()) "No Photos or Videos" else Format.count(assets.size, "Item"),
                        style = IosType.subheadline.copy(fontWeight = FontWeight.SemiBold), color = c.secondaryLabel,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
                    )
                }
            },
        )
        Box(
            Modifier.fillMaxWidth()
                .background(Brush.verticalGradient(listOf(c.background, c.background.copy(alpha = 0.9f), c.background.copy(alpha = 0f))))
                .padding(top = top + 6.dp, start = 16.dp, end = 16.dp, bottom = 18.dp),
        ) {
            GlassIconButton(Icons.AutoMirrored.Filled.ArrowBackIos, "Back", modifier = Modifier.align(Alignment.TopStart), onClick = onBack)
            if (assets.isNotEmpty()) {
                GlassTextButton(if (selection == null) "Select" else "Cancel", modifier = Modifier.align(Alignment.TopEnd)) {
                    selection = if (selection == null) emptySet() else null
                }
            }
            Column(Modifier.padding(top = 50.dp)) {
                Text(source.title, style = IosType.largeTitle, color = c.label)
                if (assets.isNotEmpty()) Text(
                    Format.dateRange(assets.first().dateTaken, assets.last().dateTaken),
                    style = IosType.subheadline.copy(fontWeight = FontWeight.SemiBold), color = c.secondaryLabel,
                )
            }
        }
        selection?.let { s ->
            SelectionBar(
                count = s.size,
                onShare = { onShare(assets.filter { it.key in s }) },
                onDelete = { onDelete(assets.filter { it.key in s }); selection = null },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bottom + 16.dp, start = 16.dp, end = 16.dp),
            )
        }
    }
}
