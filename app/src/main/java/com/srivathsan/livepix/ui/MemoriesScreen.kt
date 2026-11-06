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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.srivathsan.livepix.LibraryState
import com.srivathsan.livepix.data.Memory
import com.srivathsan.livepix.ui.components.GlassIconButton
import com.srivathsan.livepix.ui.theme.Ios
import com.srivathsan.livepix.ui.theme.IosType

/** A memory's poster: its cover picture with the title over a dark fade. */
@Composable
fun MemoryCard(memory: Memory, state: LibraryState, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = Ios.colors
    val cover = remember(memory, state.assets) { state.assets.firstOrNull { it.key == memory.cover } }
    Box(modifier.clip(RoundedCornerShape(14.dp)).background(c.fill).clickable(onClick = onClick)) {
        val bmp = cover?.let { rememberThumbnail(it, 270.dp) }
        if (bmp != null) Image(bmp.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color(0xB3000000))))
        Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
            Text(memory.title, style = IosType.headline, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(memory.subtitle, style = IosType.footnote.copy(fontWeight = FontWeight.Medium), color = Color(0xD9FFFFFF), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun MemoriesScreen(state: LibraryState, onBack: () -> Unit, onPlay: (Memory) -> Unit) {
    val c = Ios.colors
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Box(Modifier.fillMaxSize().background(c.background)) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(top = top + 64.dp, bottom = bottom + 24.dp, start = 16.dp, end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(state.memories, key = { it.id }) { m ->
                MemoryCard(m, state, Modifier.fillMaxWidth().height(250.dp)) { onPlay(m) }
            }
        }
        Box(Modifier.fillMaxWidth().padding(top = top + 8.dp, start = 16.dp, end = 16.dp)) {
            GlassIconButton(Icons.AutoMirrored.Filled.ArrowBackIos, "Back", modifier = Modifier.align(Alignment.CenterStart), onClick = onBack)
            Text("Memories", style = IosType.headline, color = c.label, modifier = Modifier.align(Alignment.Center))
        }
    }
}
