package com.srivathsan.livepix.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.srivathsan.livepix.LibraryState
import com.srivathsan.livepix.data.Asset
import com.srivathsan.livepix.ui.components.glass
import com.srivathsan.livepix.ui.theme.Ios
import com.srivathsan.livepix.ui.theme.IosType

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(state: LibraryState, query: String, onQueryChange: (String) -> Unit, onOpen: (Asset, Source) -> Unit) {
    val c = Ios.colors
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val results = remember(state, query) { Source.search(state, query) }
    var columns by rememberSaveable { mutableIntStateOf(3) }
    val grid = rememberLazyGridState()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (query.isEmpty()) runCatching { focus.requestFocus() } }

    Box(Modifier.fillMaxSize().background(c.background)) {
        if (query.isBlank()) {
            Column(Modifier.padding(top = top + 70.dp, start = 18.dp, end = 18.dp)) {
                Text("Suggestions", style = IosType.title3, color = c.label)
                Spacer(Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val months = state.assets.map { Format.monthYear(it.dateTaken) }.distinct().takeLast(4).reversed()
                    (MediaType.entries.map { it.title } + months).forEach { s ->
                        Text(
                            s, style = IosType.subheadline.copy(fontWeight = FontWeight.Medium), color = c.blue,
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(c.fill).clickable { onQueryChange(s) }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        } else {
            AssetGrid(
                assets = results, state = state, gridState = grid, columns = columns, onColumnsChange = { columns = it },
                selection = null, onSelectionChange = {}, onOpen = { onOpen(it, Source.Search(query)) },
                contentPadding = PaddingValues(top = top + 70.dp, bottom = bottom + TAB_BAR_SPACE + 80.dp),
                footer = {
                    fullSpan("count") {
                        Text(
                            if (results.isEmpty()) "No Results" else Format.count(results.size, "Result"),
                            style = IosType.subheadline.copy(fontWeight = FontWeight.SemiBold), color = c.secondaryLabel,
                            textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 18.dp).fillMaxWidth(),
                        )
                    }
                },
            )
        }
        Text("Search", style = IosType.largeTitle, color = c.label, modifier = Modifier.padding(top = top + 6.dp, start = 18.dp))

        // iOS 26 keeps the search field at the bottom, above the keyboard.
        val ime = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
        Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = maxOf(ime + 8.dp, bottom + TAB_BAR_SPACE + 8.dp), start = 16.dp, end = 16.dp)
                .fillMaxWidth().height(48.dp).glass(RoundedCornerShape(50)).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Search, null, tint = c.secondaryLabel, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text("Photos, Live, Portrait, Places…", style = IosType.body, color = c.secondaryLabel)
                BasicTextField(
                    query, onQueryChange, singleLine = true,
                    textStyle = IosType.body.copy(color = c.label), cursorBrush = SolidColor(c.blue),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
            if (query.isNotEmpty()) Text("Clear", style = IosType.subheadline, color = c.blue, modifier = Modifier.clickable { onQueryChange("") })
        }
    }
}

