package com.srivathsan.livepix.ui

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.graphicsLayer
import com.srivathsan.livepix.LibraryState
import com.srivathsan.livepix.data.Asset
import com.srivathsan.livepix.data.AssetEdit
import com.srivathsan.livepix.data.LiveEffect
import com.srivathsan.livepix.data.Traits
import com.srivathsan.livepix.media.ImageLoader
import com.srivathsan.livepix.media.StillRenderer
import com.srivathsan.livepix.ui.components.GlassGroup
import com.srivathsan.livepix.ui.components.GlassIconButton
import com.srivathsan.livepix.ui.components.GroupIcon
import com.srivathsan.livepix.ui.components.glass
import com.srivathsan.livepix.ui.theme.Ios
import com.srivathsan.livepix.ui.theme.IosType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

/** Long side used for on-screen rendering; exports use full resolution. */
private const val VIEW_SIDE = 1600

data class ViewerActions(
    val onBack: () -> Unit,
    val onEdit: (Asset) -> Unit,
    val onEditChange: (Asset, (AssetEdit) -> AssetEdit) -> Unit,
    val onFavorite: (Asset) -> Unit,
    val onShare: (Asset) -> Unit,
    val onDelete: (Asset) -> Unit,
    val onSaveCopy: (Asset) -> Unit,
    val onMotionPhoto: (Asset) -> Unit,
    val onShareOriginal: (Asset) -> Unit,
)

@Composable
fun ViewerScreen(
    assets: List<Asset>,
    startKey: String,
    state: LibraryState,
    actions: ViewerActions,
    onPageChange: (Asset) -> Unit,
) {
    val c = Ios.colors
    if (assets.isEmpty()) {
        LaunchedEffect(Unit) { actions.onBack() }
        return
    }
    val startIndex = remember(assets, startKey) { assets.indexOfFirst { it.key == startKey }.coerceAtLeast(0) }
    val pager = rememberPagerState(initialPage = startIndex) { assets.size }
    var chrome by rememberSaveable { mutableStateOf(true) }
    var showInfo by remember { mutableStateOf(false) }
    val zoomStates = remember { HashMap<String, ZoomState>() }
    val current = assets.getOrNull(pager.currentPage.coerceIn(0, assets.lastIndex)) ?: assets.first()
    val currentZoom = zoomStates.getOrPut(current.key) { ZoomState() }
    val dismissProgress = (currentZoom.dismissY / 900f).coerceIn(0f, 1f)
    val bg by animateColorAsState(if (chrome) c.background else Color.Black, tween(200), label = "bg")
    // One player for the video that has settled on screen; it autoplays, as in Photos.
    val settled = assets.getOrNull(pager.settledPage.coerceIn(0, assets.lastIndex)) ?: current
    val video = rememberVideoState(settled, state.editOf(settled), active = settled.isVideo)

    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.distinctUntilChanged().collect { assets.getOrNull(it)?.let(onPageChange) }
    }

    Box(Modifier.fillMaxSize().background(bg.copy(alpha = 1f - dismissProgress))) {
        HorizontalPager(
            state = pager,
            pageSpacing = 14.dp,
            beyondViewportPageCount = 1,
            key = { assets[it].key },
            userScrollEnabled = !currentZoom.zoomed,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val asset = assets[page]
            val zoom = zoomStates.getOrPut(asset.key) { ZoomState() }
            PhotoPage(
                asset = asset,
                traits = state.traitsOf(asset),
                edit = state.editOf(asset),
                isCurrent = page == pager.settledPage,
                video = if (asset.key == settled.key) video else null,
                zoom = zoom,
                onTap = { chrome = !chrome },
                onDismiss = actions.onBack,
                onSwipeUp = { showInfo = true },
            )
        }

        AnimatedVisibility(chrome && dismissProgress < 0.05f, enter = fadeIn(), exit = fadeOut()) {
            ViewerChrome(
                asset = current,
                assets = assets,
                pager = pager,
                state = state,
                actions = actions,
                video = if (current.key == settled.key) video else null,
                onInfo = { showInfo = true },
            )
        }
    }

    if (showInfo) InfoSheet(current, state.traitsOf(current), state.editOf(current), state.placeOf(current)) { showInfo = false }
}

@Composable
private fun PhotoPage(
    asset: Asset,
    traits: Traits?,
    edit: AssetEdit,
    isCurrent: Boolean,
    video: VideoState?,
    zoom: ZoomState,
    onTap: () -> Unit,
    onDismiss: () -> Unit,
    onSwipeUp: () -> Unit,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val thumb = rememberThumbnail(asset, 512.dp)
    val needsRender = StillRenderer.needsRender(asset, traits, edit)

    // Full-resolution original first, then (if edits apply) the rendered version on top.
    val full by produceState<Bitmap?>(ImageLoader.peek("f:${asset.uri}:$VIEW_SIDE:false"), asset.versionKey) {
        if (!asset.isVideo) value = ImageLoader.full(context, asset.uri, VIEW_SIDE)
    }
    val rendered by produceState(StillRenderer.cached(asset, edit, VIEW_SIDE), asset.versionKey, edit, needsRender, isCurrent) {
        if (needsRender && isCurrent) {
            value = withContext(Dispatchers.Default) { StillRenderer.render(context, asset, traits, edit, VIEW_SIDE) }
        } else if (!needsRender) value = null
    }

    var holdPlaying by remember { mutableStateOf(false) }
    var previewPlaying by remember { mutableStateOf(false) }
    // Like Photos, a Live Photo moves briefly when you arrive on it.
    LaunchedEffect(isCurrent, asset.key) {
        previewPlaying = isCurrent && asset.isLive && edit.effect == LiveEffect.LIVE
        if (!isCurrent) holdPlaying = false
    }

    ZoomableBox(
        zoom = zoom,
        onTap = onTap,
        onLongPress = {
            if (asset.isLive && edit.liveEnabled) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                previewPlaying = false
                holdPlaying = true
            }
        },
        onLongPressEnd = { },
        onDismiss = onDismiss,
        onSwipeUp = onSwipeUp,
    ) {
        val best = if (needsRender) rendered ?: full ?: thumb else full ?: thumb
        Box(Modifier.fillMaxSize().sharedAsset(asset.key)) {
            FadingBitmap(best, Modifier.fillMaxSize(), ContentScale.Fit)
        }
        if (video != null) VideoSurface(video)

        val video by produceState<android.net.Uri?>(asset.liveVideo, asset.versionKey, isCurrent) {
            if (isCurrent && asset.isLive && value == null) value = withContext(Dispatchers.IO) { asset.movieUri(context) }
        }
        if (video != null && isCurrent) {
            when {
                holdPlaying -> LiveVideoLayer(video!!, loop = false, withSound = !edit.muted, onEnded = { holdPlaying = false })
                edit.effect == LiveEffect.LOOP -> LiveVideoLayer(video!!, loop = true, withSound = false, onEnded = {})
                edit.effect == LiveEffect.BOUNCE -> BounceLayer(video!!)
                previewPlaying -> LiveVideoLayer(video!!, loop = false, withSound = false, onEnded = { previewPlaying = false })
            }
        }
    }
}

@Composable
private fun ViewerChrome(
    asset: Asset,
    assets: List<Asset>,
    pager: PagerState,
    state: LibraryState,
    actions: ViewerActions,
    video: VideoState?,
    onInfo: () -> Unit,
) {
    val c = Ios.colors
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val edit = state.editOf(asset)
    val traits = state.traitsOf(asset)
    Box(Modifier.fillMaxSize()) {
        // Top bar
        Box(
            Modifier.fillMaxWidth()
                .background(Brush.verticalGradient(listOf(c.background.copy(alpha = 0.75f), Color.Transparent)))
                .padding(top = top + 4.dp, start = 16.dp, end = 16.dp, bottom = 12.dp),
        ) {
            GlassIconButton(Icons.AutoMirrored.Filled.ArrowBackIos, "Back", modifier = Modifier.align(Alignment.CenterStart), onClick = actions.onBack)
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(Format.viewerTitle(asset.dateTaken), style = IosType.headline, color = c.label)
                Text(Format.time(asset.dateTaken), style = IosType.footnote, color = c.secondaryLabel)
            }
            Box(Modifier.align(Alignment.CenterEnd)) {
                var menu by remember { mutableStateOf(false) }
                GlassIconButton(Icons.Outlined.MoreHoriz, "More") { menu = true }
                val shared = LocalAssetActions.current
                val hidden = asset.key in state.hidden
                IosMenu(menu, onDismiss = { menu = false }) {
                    MenuRow("Add to Album", icon = Icons.Outlined.PhotoLibrary) { menu = false; shared.addToAlbum(listOf(asset)) }
                    MenuRow(if (hidden) "Unhide" else "Hide", icon = if (hidden) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff) {
                        menu = false; shared.setHidden(listOf(asset), !hidden)
                    }
                    MenuRow("Slideshow") { menu = false; shared.slideshow(assets, asset.key) }
                    MenuRow("Save a Copy") { menu = false; actions.onSaveCopy(asset) }
                    if (asset.liveVideo != null) MenuRow("Export as Motion Photo") { menu = false; actions.onMotionPhoto(asset) }
                    MenuRow("Share Original File") { menu = false; actions.onShareOriginal(asset) }
                    if (edit.isModified(asset, traits) && (edit.keyFrameUs != null || edit.effect != LiveEffect.LIVE)) {
                        MenuRow("Revert to Original", color = c.red) {
                            menu = false
                            actions.onEditChange(asset) { AssetEdit(favorite = it.favorite) }
                        }
                    }
                }
            }
        }

        // Badges under the top bar: the LIVE effect menu, and PORTRAIT for depth photos.
        Row(
            Modifier.padding(top = top + 66.dp, start = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (asset.isLive) LiveBadge(edit) { effect -> actions.onEditChange(asset) { it.copy(effect = effect) } }
            if (traits?.hasDepth == true) {
                Badge(if (edit.portrait) "PORTRAIT" else "PORTRAIT OFF", icon = null) { actions.onEdit(asset) }
            }
        }

        // Bottom: filmstrip, then toolbar.
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = bottom + 12.dp)) {
            if (asset.isVideo && video != null) VideoControls(video, asset) else Filmstrip(assets, pager, state)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                GlassIconButton(Icons.Outlined.IosShare, "Share", size = 48.dp) { actions.onShare(asset) }
                Spacer(Modifier.weight(1f))
                GlassGroup {
                    GroupIcon(
                        if (edit.favorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder, "Favourite",
                        tint = if (edit.favorite) c.red else c.label,
                    ) { actions.onFavorite(asset) }
                    GroupIcon(Icons.Outlined.Info, "Info", onClick = onInfo)
                    GroupIcon(Icons.Outlined.Tune, "Edit") { actions.onEdit(asset) }
                }
                Spacer(Modifier.weight(1f))
                GlassIconButton(Icons.Outlined.Delete, "Delete", size = 48.dp) { actions.onDelete(asset) }
            }
        }
    }
}

/** The thumbnail strip above the toolbar; the current item is drawn wider, like Photos. */
@Composable
private fun Filmstrip(assets: List<Asset>, pager: PagerState, state: LibraryState) {
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val width = LocalConfiguration.current.screenWidthDp.dp
    // The start padding is half the screen, so offset 0 puts the item in the middle.
    LaunchedEffect(pager.currentPage) { list.animateScrollToItem(pager.currentPage) }
    LazyRow(
        state = list,
        contentPadding = PaddingValues(horizontal = width / 2 - 22.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.fillMaxWidth().height(44.dp),
    ) {
        itemsIndexed(assets, key = { _, a -> a.key }) { i, a ->
            val currentItem = i == pager.currentPage
            val bmp = rememberEditedThumbnail(a, state.editOf(a), state.traitsOf(a), 64.dp)
            Box(
                Modifier.padding(horizontal = if (currentItem) 6.dp else 0.dp)
                    .width(if (currentItem) 44.dp else 30.dp).fillMaxHeight()
                    .clip(RoundedCornerShape(if (currentItem) 4.dp else 2.dp))
                    .background(Ios.colors.fill)
                    .clickable { scope.launch { pager.scrollToPage(i) } },
            ) {
                if (bmp != null) Image(bmp.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun LiveBadge(edit: AssetEdit, onSelect: (LiveEffect) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = when (edit.effect) {
        LiveEffect.LIVE -> "LIVE"
        LiveEffect.OFF -> "LIVE OFF"
        else -> edit.effect.label.uppercase()
    }
    Box {
        Badge(label, icon = LiveIcon, struck = edit.effect == LiveEffect.OFF, chevron = true) { open = true }
        IosMenu(open, onDismiss = { open = false }) {
            LiveEffect.entries.forEach { e ->
                MenuRow(e.label, checked = e == edit.effect) { open = false; onSelect(e) }
                if (e == LiveEffect.LONG_EXPOSURE) HorizontalDivider(thickness = 6.dp, color = Ios.colors.fill)
            }
        }
    }
}

/** The concentric-circles Live Photos symbol. */
val LiveIcon: ImageVector by lazy {
    ImageVector.Builder("live", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            pathData = androidx.compose.ui.graphics.vector.PathParser().parsePathString(
                "M12,9.2a2.8,2.8 0 1,0 0.01,0z",
            ).toNodes(),
            fill = androidx.compose.ui.graphics.SolidColor(Color.Black),
        )
        addPath(
            pathData = androidx.compose.ui.graphics.vector.PathParser().parsePathString(
                "M12,5.6a6.4,6.4 0 1,0 0.01,0z",
            ).toNodes(),
            stroke = androidx.compose.ui.graphics.SolidColor(Color.Black), strokeLineWidth = 1.6f,
        )
        addPath(
            pathData = androidx.compose.ui.graphics.vector.PathParser().parsePathString(
                "M12,2.2a9.8,9.8 0 1,0 0.01,0z",
            ).toNodes(),
            stroke = androidx.compose.ui.graphics.SolidColor(Color.Black), strokeLineWidth = 1.4f,
            strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round,
            pathFillType = androidx.compose.ui.graphics.PathFillType.NonZero,
        )
    }.build()
}

@Composable
fun Badge(
    text: String,
    icon: ImageVector?,
    struck: Boolean = false,
    chevron: Boolean = false,
    tint: Color = Ios.colors.label,
    background: Modifier? = null,
    onClick: () -> Unit,
) {
    Row(
        (background ?: Modifier.glass(RoundedCornerShape(50))).height(30.dp).clickable(onClick = onClick).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = tint, modifier = Modifier.size(17.dp))
                if (struck) Box(Modifier.width(20.dp).height(1.6.dp).background(tint).graphicsLayer { rotationZ = -45f })
            }
        }
        Text(text, style = IosType.caption2.copy(fontSize = 12.sp, letterSpacing = 0.05.em), color = tint)
        if (chevron) Icon(Icons.Filled.KeyboardArrowDown, null, tint = tint, modifier = Modifier.size(16.dp))
    }
}


/** UIMenu look: rounded glass card with rows and an optional checkmark. */
@Composable
fun IosMenu(expanded: Boolean, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val c = Ios.colors
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(16.dp),
        // Opaque with a modest shadow: translucent popups show their own shadow through them.
        containerColor = if (c.isDark) Color(0xFF2C2C2E) else Color(0xFFF7F7F7),
        tonalElevation = 0.dp,
        shadowElevation = 6.dp,
        modifier = Modifier.width(250.dp),
    ) { content() }
}

@Composable
fun MenuRow(text: String, checked: Boolean = false, color: Color = Ios.colors.label, icon: ImageVector? = null, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text, style = IosType.body, color = color) },
        leadingIcon = {
            Box(Modifier.width(18.dp)) { if (checked) Icon(Icons.Filled.Check, null, tint = color, modifier = Modifier.size(18.dp)) }
        },
        trailingIcon = icon?.let { { Icon(it, null, tint = color, modifier = Modifier.size(20.dp)) } },
        onClick = onClick,
    )
}
