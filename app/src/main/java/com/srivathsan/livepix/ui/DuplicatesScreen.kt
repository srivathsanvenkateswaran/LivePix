package com.srivathsan.livepix.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.srivathsan.livepix.LibraryState
import com.srivathsan.livepix.data.Asset
import com.srivathsan.livepix.ui.components.GlassIconButton
import com.srivathsan.livepix.ui.components.GlassTextButton
import com.srivathsan.livepix.ui.theme.Ios
import com.srivathsan.livepix.ui.theme.IosType

/**
 * Duplicates: groups of copies with the best one marked. Merge keeps that one, passes on its
 * favourite and albums, and sends the rest to Recently Deleted after Android's own prompt.
 */
@Composable
fun DuplicatesScreen(state: LibraryState, onBack: () -> Unit, onMerge: (List<List<Asset>>) -> Unit) {
    val c = Ios.colors
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val groups = remember(state.vision, state.assets) {
        val byKey = state.assets.associateBy { it.key }
        state.vision?.duplicates.orEmpty().map { g -> g.mapNotNull { byKey[it.key] } }.filter { it.size > 1 }
    }
    Box(Modifier.fillMaxSize().background(c.background)) {
        LazyColumn(contentPadding = PaddingValues(top = top + 64.dp, bottom = bottom + 24.dp)) {
            if (groups.isEmpty()) {
                item { Text("No Duplicates", style = IosType.subheadline, color = c.secondaryLabel, modifier = Modifier.padding(18.dp)) }
            }
            items(groups, key = { it.first().key }) { group ->
                Column(Modifier.padding(vertical = 12.dp)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(Format.count(group.size, "Copy", "Copies"), style = IosType.headline, color = c.label)
                            Text(Format.shortDate(group.first().dateTaken), style = IosType.footnote, color = c.secondaryLabel)
                        }
                        Text(
                            "Merge", style = IosType.subheadline.copy(fontWeight = FontWeight.SemiBold), color = c.blue,
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(c.fill).clickable { onMerge(listOf(group)) }.padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                    }
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(group, key = { it.key }) { a -> Copy(a, keep = a == group.first()) }
                    }
                    HorizontalDivider(Modifier.padding(start = 16.dp), thickness = 0.5.dp, color = c.separator)
                }
            }
        }
        Box(Modifier.fillMaxWidth().padding(top = top + 8.dp, start = 16.dp, end = 16.dp)) {
            GlassIconButton(Icons.AutoMirrored.Filled.ArrowBackIos, "Back", modifier = Modifier.align(Alignment.CenterStart), onClick = onBack)
            Text("Duplicates", style = IosType.headline, color = c.label, modifier = Modifier.align(Alignment.Center))
            if (groups.size > 1) GlassTextButton("Merge All", modifier = Modifier.align(Alignment.CenterEnd)) { onMerge(groups) }
        }
    }
}

@Composable
private fun Copy(asset: Asset, keep: Boolean) {
    val c = Ios.colors
    val actions = LocalAssetActions.current
    Column(Modifier.width(120.dp).clickable { actions.open(asset, Source.Keys("Duplicates", listOf(asset.key))) }) {
        Box(Modifier.size(120.dp).clip(RoundedCornerShape(10.dp)).background(c.fill)) {
            val bmp = rememberThumbnail(asset, 120.dp)
            if (bmp != null) Image(bmp.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (keep) {
                Text(
                    "Keep", style = IosType.caption2.copy(fontWeight = FontWeight.Bold), color = Color.White,
                    modifier = Modifier.align(Alignment.TopStart).padding(5.dp).clip(RoundedCornerShape(50)).background(c.blue).padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
        }
        Text(Format.bytes(asset.size), style = IosType.footnote, color = c.label, modifier = Modifier.padding(top = 4.dp))
        Text("${asset.width} × ${asset.height}", style = IosType.caption2, color = c.secondaryLabel)
    }
}
