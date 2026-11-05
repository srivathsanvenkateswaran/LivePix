package com.srivathsan.livepix.ui

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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.srivathsan.livepix.LibraryState
import com.srivathsan.livepix.data.Asset
import com.srivathsan.livepix.ui.theme.Ios
import com.srivathsan.livepix.ui.components.LargeTitleHeader
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import com.srivathsan.livepix.ui.theme.IosType

@Composable
fun CollectionsScreen(
    state: LibraryState,
    listState: LazyListState,
    onOpenSource: (Source) -> Unit,
    onNewAlbum: () -> Unit,
    onUtility: (Utility) -> Unit,
) {
    val c = Ios.colors
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val folders = remember(state.assets) {
        state.assets.groupBy { it.folder }.entries.sortedByDescending { it.value.size }.map { it.key to it.value }
    }
    val typeCounts = remember(state) { MediaType.entries.associateWith { Source.Type(it).assets(state) } }
    val pinned = listOf(MediaType.FAVORITES, MediaType.LIVE, MediaType.PORTRAIT, MediaType.VIDEOS)

    Box(Modifier.fillMaxSize().background(c.background)) {
        LazyColumn(state = listState, contentPadding = PaddingValues(top = top + 72.dp, bottom = bottom + TAB_BAR_SPACE + 24.dp)) {
            item { SectionHeader("Pinned") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(pinned) { type ->
                        CollectionCard(type.title, typeCounts.getValue(type), size = 160.dp) { onOpenSource(Source.Type(type)) }
                    }
                }
            }

            item { SectionHeader("My Albums") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    item { NewAlbumTile(160.dp, onNewAlbum) }
                    items(state.albums, key = { it.id }) { album ->
                        val list = remember(state, album) { Source.UserAlbumSource(album.id, album.name).assets(state) }
                        CollectionCard(album.name, list, size = 160.dp) { onOpenSource(Source.UserAlbumSource(album.id, album.name)) }
                    }
                }
            }

            item { SectionHeader("On This Phone") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(folders, key = { it.first }) { (name, list) ->
                        CollectionCard(name, list, size = 120.dp) { onOpenSource(Source.Album(name)) }
                    }
                }
            }

            item { SectionHeader("Media Types", chevron = false) }
            item {
                GroupedList {
                    val shown = MediaType.entries.filter { it == MediaType.FAVORITES || typeCounts.getValue(it).isNotEmpty() }
                    shown.forEachIndexed { i, type ->
                        ListRow(type.icon, type.title, "%,d".format(typeCounts.getValue(type).size), last = i == shown.lastIndex) {
                            onOpenSource(Source.Type(type))
                        }
                    }
                }
            }

            item { SectionHeader("Utilities", chevron = false) }
            item {
                GroupedList {
                    Utility.entries.forEachIndexed { i, u ->
                        ListRow(u.icon, u.title, null, trailingIcon = Icons.Outlined.Lock, last = i == Utility.entries.lastIndex) { onUtility(u) }
                    }
                }
            }
        }
        val collapsed by remember { derivedStateOf { listState.firstVisibleItemIndex > 1 || (listState.firstVisibleItemIndex == 1 && listState.firstVisibleItemScrollOffset > 40) } }
        LargeTitleHeader("Collections", null, collapsed, top, hasButtons = false)
    }
}

@Composable
private fun GroupedList(content: @Composable () -> Unit) {
    val c = Ios.colors
    Column(
        Modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(14.dp))
            .background(if (c.isDark) c.secondaryGroupedBackground else c.groupedBackground),
    ) { content() }
}

@Composable
private fun ListRow(icon: ImageVector, title: String, value: String?, trailingIcon: ImageVector? = null, last: Boolean, onClick: () -> Unit) {
    val c = Ios.colors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = c.blue, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(14.dp))
        Text(title, style = IosType.body, color = c.label, modifier = Modifier.weight(1f))
        if (value != null) Text(value, style = IosType.body, color = c.secondaryLabel)
        if (trailingIcon != null) Icon(trailingIcon, null, tint = c.tertiaryLabel, modifier = Modifier.size(16.dp))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = c.tertiaryLabel)
    }
    if (!last) HorizontalDivider(Modifier.padding(start = 52.dp), thickness = 0.5.dp, color = c.separator)
}

@Composable
private fun SectionHeader(title: String, chevron: Boolean = true) {
    val c = Ios.colors
    Row(Modifier.padding(start = 18.dp, end = 16.dp, top = 22.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = IosType.title2, color = c.label)
        if (chevron) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = c.secondaryLabel, modifier = Modifier.size(26.dp))
    }
}

@Composable
private fun NewAlbumTile(size: Dp, onClick: () -> Unit) {
    val c = Ios.colors
    Column(Modifier.width(size).clickable(onClick = onClick)) {
        Box(Modifier.size(size).clip(RoundedCornerShape(12.dp)).background(c.fill), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Add, null, tint = c.blue, modifier = Modifier.size(40.dp))
        }
        Text("New Album", style = IosType.subheadline.copy(fontWeight = FontWeight.Medium), color = c.label, modifier = Modifier.padding(top = 6.dp))
        Text(" ", style = IosType.subheadline)
    }
}

@Composable
private fun CollectionCard(title: String, assets: List<Asset>, size: Dp, onClick: () -> Unit) {
    val c = Ios.colors
    Column(Modifier.width(size).clickable(onClick = onClick)) {
        Box(Modifier.size(size).clip(RoundedCornerShape(12.dp)).background(c.fill), contentAlignment = Alignment.Center) {
            val cover = assets.lastOrNull { !it.isVideo } ?: assets.lastOrNull()
            if (cover != null) {
                val bmp = rememberThumbnail(cover, size)
                if (bmp != null) Image(bmp.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(Icons.Outlined.PhotoLibrary, null, tint = c.tertiaryLabel, modifier = Modifier.size(40.dp))
            }
        }
        Text(title, style = IosType.subheadline.copy(fontWeight = FontWeight.Medium), color = c.label, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        Text("%,d".format(assets.size), style = IosType.subheadline, color = c.secondaryLabel)
    }
}
