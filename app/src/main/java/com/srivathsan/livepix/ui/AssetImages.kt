package com.srivathsan.livepix.ui

import android.graphics.Bitmap
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.srivathsan.livepix.data.Asset
import com.srivathsan.livepix.data.AssetEdit
import com.srivathsan.livepix.data.Traits
import com.srivathsan.livepix.media.StillRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.srivathsan.livepix.media.ImageLoader
import com.srivathsan.livepix.ui.theme.Ios
import com.srivathsan.livepix.ui.theme.IosType

/**
 * Thumbnail that reflects edits, like Photos: crops, filters, Portrait blur and chosen key photos
 * show in the grid. Unedited items use MediaStore's thumbnail.
 */
@Composable
fun rememberEditedThumbnail(asset: Asset, edit: AssetEdit, traits: Traits?, sizeDp: Dp): Bitmap? {
    val plain = rememberThumbnail(asset, sizeDp)
    if (!StillRenderer.needsRender(asset, traits, edit)) return plain
    val context = LocalContext.current
    val side = 512
    var bmp by remember(asset.versionKey, edit) { mutableStateOf(StillRenderer.cached(asset, edit, side)) }
    LaunchedEffect(asset.versionKey, edit) {
        if (bmp == null) bmp = withContext(Dispatchers.Default) { runCatching { StillRenderer.render(context, asset, traits, edit, side) }.getOrNull() }
    }
    return bmp ?: plain
}

/** MediaStore thumbnail for an asset, sized for the grid cell. */
@Composable
fun rememberThumbnail(asset: Asset, sizeDp: Dp): Bitmap? {
    val context = LocalContext.current
    // A few fixed sizes so cells, cards and the filmstrip share cache entries.
    val px = with(LocalDensity.current) { sizeDp.roundToPx() }.let {
        when {
            it <= 256 -> 256
            it <= 512 -> 512
            else -> 1024
        }
    }
    var bmp by remember(asset.uri, px) { mutableStateOf(ImageLoader.peek("t:${asset.uri}:$px")) }
    LaunchedEffect(asset.uri, px) {
        if (bmp == null) bmp = ImageLoader.thumbnail(context, asset.uri, px)
    }
    return bmp
}

@Composable
fun AssetThumbnail(
    asset: Asset,
    cellSize: Dp,
    favorite: Boolean,
    modifier: Modifier = Modifier,
    selected: Boolean? = null,
    label: String? = null,
    imageModifier: Modifier = Modifier,
    edit: AssetEdit? = null,
    traits: Traits? = null,
) {
    val c = Ios.colors
    val bmp = if (edit != null) rememberEditedThumbnail(asset, edit, traits, cellSize) else rememberThumbnail(asset, cellSize)
    Box(modifier.background(c.fill)) {
        if (bmp != null) {
            Image(
                bmp.asImageBitmap(), null,
                contentScale = ContentScale.Crop,
                modifier = imageModifier.fillMaxSize(),
            )
        }
        ThumbBadges(asset, favorite, cellSize)
        if (label != null) {
            Text(
                label, style = IosType.caption.copy(fontWeight = FontWeight.SemiBold, shadow = badgeShadow), color = Color.White,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 5.dp, bottom = 3.dp),
            )
        }
        if (selected != null) SelectionMark(selected)
    }
}

private val badgeShadow = Shadow(Color(0x80000000), blurRadius = 4f)

@Composable
private fun BoxScope.ThumbBadges(asset: Asset, favorite: Boolean, cellSize: Dp) {
    if (cellSize < 60.dp) return
    if (asset.isVideo) {
        Text(
            Format.duration(asset.durationMs),
            style = IosType.caption.copy(fontWeight = FontWeight.SemiBold, shadow = badgeShadow),
            color = Color.White,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 5.dp, bottom = 3.dp),
        )
    }
    if (favorite) {
        Icon(
            Icons.Filled.Favorite, null, tint = Color.White,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 5.dp, bottom = 5.dp).size(13.dp),
        )
    }
}

@Composable
private fun BoxScope.SelectionMark(selected: Boolean) {
    val c = Ios.colors
    if (selected) {
        Box(Modifier.matchParentSize().background(Color(0x40FFFFFF)))
        Box(
            Modifier.align(Alignment.BottomEnd).padding(5.dp).size(22.dp).clip(CircleShape).background(Color.White),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.CheckCircle, null, tint = c.blue, modifier = Modifier.size(22.dp)) }
    }
}

/** Crossfades from whatever is available (thumbnail) to the best bitmap once it arrives. */
@Composable
fun FadingBitmap(bitmap: Bitmap?, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Fit) {
    Crossfade(bitmap, animationSpec = tween(220), label = "bmp", modifier = modifier) { b ->
        if (b != null) Image(b.asImageBitmap(), null, contentScale = contentScale, modifier = Modifier.fillMaxSize())
        else Box(Modifier.fillMaxSize())
    }
}
