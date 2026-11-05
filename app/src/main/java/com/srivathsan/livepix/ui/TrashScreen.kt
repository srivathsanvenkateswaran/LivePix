package com.srivathsan.livepix.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import kotlin.math.ceil

/** Recently Deleted: Android's trash, with days left and Recover / Delete like Photos. */
@Composable
fun TrashScreen(
    state: LibraryState,
    onBack: () -> Unit,
    onRecover: (List<Asset>) -> Unit,
    onDeleteForever: (List<Asset>) -> Unit,
) {
    val c = Ios.colors
    val items = state.trashed
    var columns by remember { mutableIntStateOf(3) }
    var selection by remember { mutableStateOf<Set<String>?>(null) }
    val grid = rememberLazyGridState()
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val now = System.currentTimeMillis()
    Box(Modifier.fillMaxSize().background(c.background)) {
        AssetGrid(
            assets = items, state = state, gridState = grid, columns = columns, onColumnsChange = { columns = it },
            selection = selection, onSelectionChange = { selection = it }, onOpen = { a -> selection = setOf(a.key) },
            contextMenu = false,
            badge = { a ->
                val days = ceil((a.expiresAt - now) / 86_400_000.0).toInt().coerceAtLeast(0)
                if (days == 1) "1 day" else "$days days"
            },
            contentPadding = PaddingValues(top = top + 160.dp, bottom = bottom + 90.dp),
            footer = {
                fullSpan("footer") {
                    Text(
                        if (items.isEmpty()) "No Photos or Videos" else "Photos and videos show the days remaining before deletion. After that time, items will be permanently deleted. This may take up to 40 days.",
                        style = IosType.footnote, color = c.secondaryLabel, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp, horizontal = 28.dp),
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
            if (items.isNotEmpty()) {
                GlassTextButton(if (selection == null) "Select" else "Cancel", modifier = Modifier.align(Alignment.TopEnd)) {
                    selection = if (selection == null) emptySet() else null
                }
            }
            Column(Modifier.padding(top = 50.dp)) {
                Text("Recently Deleted", style = IosType.largeTitle, color = c.label)
                Text(Format.count(items.size, "Item"), style = IosType.subheadline.copy(fontWeight = FontWeight.SemiBold), color = c.secondaryLabel)
            }
        }
        if (items.isNotEmpty()) {
            val chosen = selection?.let { s -> items.filter { it.key in s } }
            val target = if (chosen.isNullOrEmpty()) items else chosen
            val suffix = if (chosen.isNullOrEmpty()) " All" else ""
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = bottom + 16.dp, start = 16.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlassTextButton("Delete$suffix", color = c.red) { onDeleteForever(target); selection = null }
                Spacer(Modifier.weight(1f))
                GlassTextButton("Recover$suffix", color = c.blue) { onRecover(target); selection = null }
            }
        }
    }
}
