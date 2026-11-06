package com.srivathsan.livepix.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Pets
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.srivathsan.livepix.LibraryState
import com.srivathsan.livepix.data.Asset
import com.srivathsan.livepix.data.FaceFacts
import com.srivathsan.livepix.ui.components.GlassIconButton
import com.srivathsan.livepix.ui.theme.Ios
import com.srivathsan.livepix.ui.theme.IosType
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private class Tile(val title: String, val count: Int, val cover: Asset?, val face: FaceFacts?, val pet: Boolean, val source: Source)

private fun tiles(state: LibraryState): List<Tile> {
    val vision = state.vision ?: return emptyList()
    val byKey = state.assets.associateBy { it.key }
    val people = vision.people.map { p -> Tile(p.title, p.keys.size, byKey[p.coverKey], p.cover, false, Source.Person(p.id, p.title)) }
    val pets = listOf("Dogs" to vision.dogs, "Cats" to vision.cats).filter { it.second.isNotEmpty() }.map { (name, keys) ->
        Tile(name, keys.size, byKey[keys.last()], null, true, Source.Keys(name, keys))
    }
    return people + pets
}

/** The People & Pets row on Collections. */
@Composable
fun PeopleRow(state: LibraryState, onOpenSource: (Source) -> Unit) {
    val c = Ios.colors
    val tiles = remember(state.vision, state.assets) { tiles(state) }
    Column {
        if (tiles.isNotEmpty()) {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                items(tiles, key = { it.source.toString() }) { t -> TileView(t, 96.dp) { onOpenSource(t.source) } }
            }
        } else if (state.analysisTotal == 0) {
            Text(
                "People and pets in your photos will appear here.", style = IosType.subheadline, color = c.secondaryLabel,
                modifier = Modifier.padding(horizontal = 18.dp),
            )
        }
        AnalysisProgress(state)
    }
}

@Composable
private fun AnalysisProgress(state: LibraryState) {
    if (state.analysisTotal == 0) return
    Text(
        "Looking through your photos on this phone: ${"%,d".format(state.analysisDone)} of ${"%,d".format(state.analysisTotal)}. This pauses when you leave LivePix.",
        style = IosType.footnote, color = Ios.colors.secondaryLabel,
        modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 10.dp),
    )
}

@Composable
private fun TileView(tile: Tile, size: Dp, onClick: () -> Unit) {
    val c = Ios.colors
    Column(Modifier.width(size).clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(size).clip(CircleShape).background(c.fill), contentAlignment = Alignment.Center) {
            val bmp = tile.cover?.let { rememberThumbnail(it, 340.dp) }?.asImageBitmap()
            when {
                bmp != null && tile.face != null -> Image(remember(bmp, tile.face) { facePainter(bmp, tile.face) }, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                bmp != null -> Image(bmp, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else -> Icon(if (tile.pet) Icons.Outlined.Pets else Icons.Outlined.Person, null, tint = c.tertiaryLabel, modifier = Modifier.size(size / 2))
            }
        }
        Text(
            tile.title, style = IosType.subheadline.copy(fontWeight = FontWeight.Medium),
            color = if (tile.title == "Add Name") c.blue else c.label,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text("%,d".format(tile.count), style = IosType.footnote, color = c.secondaryLabel)
    }
}

/** A square around the face with room for hair and chin, kept inside the picture. */
private fun facePainter(bmp: ImageBitmap, f: FaceFacts): BitmapPainter {
    val side = (max(f.w * bmp.width, f.h * bmp.height) * 1.7f).roundToInt().coerceIn(1, min(bmp.width, bmp.height))
    val cx = (f.x + f.w / 2) * bmp.width
    val cy = (f.y + f.h / 2) * bmp.height
    val left = (cx - side / 2f).roundToInt().coerceIn(0, bmp.width - side)
    val top = (cy - side / 2f).roundToInt().coerceIn(0, bmp.height - side)
    return BitmapPainter(bmp, IntOffset(left, top), IntSize(side, side))
}

/** Everyone, in a grid. */
@Composable
fun PeopleScreen(state: LibraryState, onBack: () -> Unit, onOpenSource: (Source) -> Unit) {
    val c = Ios.colors
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val tiles = remember(state.vision, state.assets) { tiles(state) }
    Box(Modifier.fillMaxSize().background(c.background)) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(top = top + 64.dp, bottom = bottom + 24.dp, start = 12.dp, end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) { AnalysisProgress(state) }
            items(tiles, key = { it.source.toString() }) { t ->
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { TileView(t, 100.dp) { onOpenSource(t.source) } }
            }
        }
        Box(Modifier.fillMaxWidth().padding(top = top + 8.dp, start = 16.dp, end = 16.dp)) {
            GlassIconButton(Icons.AutoMirrored.Filled.ArrowBackIos, "Back", modifier = Modifier.align(Alignment.CenterStart), onClick = onBack)
            Text("People & Pets", style = IosType.headline, color = c.label, modifier = Modifier.align(Alignment.Center))
        }
    }
}
