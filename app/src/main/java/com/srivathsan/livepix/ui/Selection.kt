package com.srivathsan.livepix.ui

import android.hardware.biometrics.BiometricManager.Authenticators
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.srivathsan.livepix.LibraryState
import com.srivathsan.livepix.data.Asset
import com.srivathsan.livepix.data.UserAlbum
import com.srivathsan.livepix.ui.components.GlassIconButton
import com.srivathsan.livepix.ui.components.glass
import com.srivathsan.livepix.ui.theme.Ios
import com.srivathsan.livepix.ui.theme.IosType

/** Extra per-screen action for a selection, e.g. "Remove from Album" or "Unhide". */
data class ExtraAction(val label: String, val icon: ImageVector, val destructive: Boolean = false, val run: (List<Asset>) -> Unit)

/**
 * The bottom bar in selection mode: Share, a count, a "…" menu with the remaining actions, and
 * Delete, as in Photos.
 */
@Composable
fun SelectionBar(
    selected: List<Asset>,
    state: LibraryState,
    modifier: Modifier = Modifier,
    extra: List<ExtraAction> = emptyList(),
    hiddenView: Boolean = false,
) {
    val c = Ios.colors
    val actions = LocalAssetActions.current
    val count = selected.size
    val enabled = count > 0
    var menu by remember { mutableStateOf(false) }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        GlassIconButton(Icons.Outlined.IosShare, "Share", tint = if (enabled) c.blue else c.tertiaryLabel) { if (enabled) actions.share(selected) }
        Spacer(Modifier.weight(1f))
        Text(
            if (count == 0) "Select Items" else Format.count(count, "Item") + " Selected",
            style = IosType.headline, color = c.label,
        )
        Spacer(Modifier.weight(1f))
        Box {
            GlassIconButton(Icons.Outlined.MoreHoriz, "More", tint = if (enabled) c.label else c.tertiaryLabel) { if (enabled) menu = true }
            IosMenu(menu, onDismiss = { menu = false }) {
                val allFav = selected.isNotEmpty() && selected.all { state.editOf(it).favorite }
                MenuRow(if (allFav) "Unfavourite" else "Favourite", icon = if (allFav) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder) {
                    menu = false; actions.toggleFavorite(selected)
                }
                MenuRow("Add to Album", icon = Icons.Outlined.PhotoLibrary) { menu = false; actions.addToAlbum(selected) }
                MenuRow(if (hiddenView) "Unhide" else "Hide", icon = if (hiddenView) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff) {
                    menu = false; actions.setHidden(selected, !hiddenView)
                }
                MenuRow("Adjust Date & Time", icon = Icons.Outlined.CalendarMonth) { menu = false; actions.adjustDate(selected) }
                MenuRow("Adjust Location", icon = Icons.Outlined.Place) { menu = false; actions.adjustLocation(selected) }
                extra.forEach { e ->
                    MenuRow(e.label, icon = e.icon, color = if (e.destructive) c.red else c.label) { menu = false; e.run(selected) }
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        GlassIconButton(Icons.Outlined.Delete, "Delete", tint = if (enabled) c.red else c.tertiaryLabel) { if (enabled) actions.trash(selected) }
    }
}

/** The long-press preview: the photo lifted above a dimmed screen, with an action menu below. */
@Composable
fun PhotoContextMenu(asset: Asset, state: LibraryState, onSelect: () -> Unit, onDismiss: () -> Unit) {
    val c = Ios.colors
    val actions = LocalAssetActions.current
    val edit = state.editOf(asset)
    val hidden = asset.key in state.hidden
    val scale = remember { Animatable(0.85f) }
    LaunchedEffect(Unit) { scale.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = 500f)) }
    // The grid's thumbnail is already cached, so show it at once and sharpen when the large one lands.
    val small = rememberThumbnail(asset, 300.dp)
    val large = rememberThumbnail(asset, 1024.dp)
    val bmp = large ?: small
    fun act(f: () -> Unit) { onDismiss(); f() }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().scale(scale.value),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val ratio = if (asset.width > 0 && asset.height > 0) asset.width.toFloat() / asset.height else 0.75f
            Box(
                Modifier.fillMaxWidth(if (ratio < 1f) 0.78f else 1f).height(if (ratio < 1f) 420.dp else 260.dp)
                    .clip(RoundedCornerShape(16.dp)).background(c.background)
                    .clickable { act { actions.open(asset, Source.Library) } },
            ) {
                if (bmp != null) Image(bmp.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            Spacer(Modifier.height(12.dp))
            Column(Modifier.widthIn(max = 280.dp).fillMaxWidth().glass(RoundedCornerShape(16.dp))) {
                ContextRow("Share", Icons.Outlined.IosShare) { act { actions.share(listOf(asset)) } }
                ContextRow(if (edit.favorite) "Unfavourite" else "Favourite", if (edit.favorite) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder) {
                    act { actions.toggleFavorite(listOf(asset)) }
                }
                ContextRow("Add to Album", Icons.Outlined.PhotoLibrary) { act { actions.addToAlbum(listOf(asset)) } }
                ContextRow("Select", Icons.Outlined.CheckCircle) { act(onSelect) }
                ContextRow(if (hidden) "Unhide" else "Hide", if (hidden) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff) {
                    act { actions.setHidden(listOf(asset), !hidden) }
                }
                HorizontalDivider(thickness = 6.dp, color = c.fill)
                ContextRow("Delete", Icons.Outlined.Delete, color = c.red, last = true) { act { actions.trash(listOf(asset)) } }
            }
        }
    }
}

@Composable
private fun ContextRow(text: String, icon: ImageVector, color: Color = Ios.colors.label, last: Boolean = false, onClick: () -> Unit) {
    Column {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, style = IosType.body, color = color, modifier = Modifier.weight(1f))
            Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
        }
        if (!last) HorizontalDivider(thickness = 0.5.dp, color = Ios.colors.separator)
    }
}

/** "Add to Album": New Album first, then the user's albums with covers and counts. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToAlbumSheet(
    state: LibraryState,
    count: Int,
    onNewAlbum: () -> Unit,
    onPick: (UserAlbum) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Ios.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = if (c.isDark) Color(0xFF1C1C1E) else c.groupedBackground,
    ) {
        Text(
            "Add ${Format.count(count, "Item")} to Album", style = IosType.headline, color = c.label,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        )
        LazyColumn(Modifier.padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
            item {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onNewAlbum).padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(c.fill), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Add, null, tint = c.blue)
                    }
                    Spacer(Modifier.width(14.dp))
                    Text("New Album…", style = IosType.body, color = c.blue)
                }
            }
            items(state.albums, key = { it.id }) { album ->
                val cover = state.allAssets.lastOrNull { it.key in album.keys }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onPick(album) }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(c.fill)) {
                        val bmp = cover?.let { rememberThumbnail(it, 64.dp) }
                        if (bmp != null) Image(bmp.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(album.name, style = IosType.body, color = c.label)
                        Text("%,d".format(album.keys.size), style = IosType.subheadline, color = c.secondaryLabel)
                    }
                }
            }
        }
    }
}

/** UIAlertController with a text field, used for New Album and Rename. */
@Composable
fun TextPromptDialog(
    title: String,
    message: String,
    initial: String,
    confirm: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Ios.colors
    var text by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.width(280.dp).clip(RoundedCornerShape(14.dp)).background(if (c.isDark) Color(0xFF2C2C2E) else Color(0xFFF2F2F2)),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, style = IosType.headline, color = c.label, modifier = Modifier.padding(top = 18.dp))
            Text(message, style = IosType.footnote, color = c.label, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            BasicTextField(
                text, { text = it }, singleLine = true,
                textStyle = IosType.subheadline.copy(color = c.label), cursorBrush = SolidColor(c.blue),
                modifier = Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(7.dp))
                    .background(if (c.isDark) Color(0xFF1C1C1E) else Color.White).padding(8.dp).focusRequester(focus),
            )
            HorizontalDivider(thickness = 0.5.dp, color = c.separator)
            Row(Modifier.height(44.dp)) {
                Text(
                    "Cancel", style = IosType.body, color = c.blue, textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).clickable(onClick = onDismiss).padding(vertical = 11.dp),
                )
                Box(Modifier.width(0.5.dp).height(44.dp).background(c.separator))
                val ok = text.isNotBlank()
                Text(
                    confirm, style = IosType.body.copy(fontWeight = FontWeight.SemiBold), color = if (ok) c.blue else c.tertiaryLabel,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).clickable(enabled = ok) { onConfirm(text.trim()) }.padding(vertical = 11.dp),
                )
            }
        }
    }
}

/**
 * Hidden and Recently Deleted are locked, as on iPhone. Unlocks with the phone's biometrics or
 * screen lock; phones without a screen lock open straight away.
 */
@Composable
fun LockGate(title: String, unlocked: Boolean, onUnlocked: () -> Unit, content: @Composable () -> Unit) {
    if (unlocked) { content(); return }
    val c = Ios.colors
    val activity = LocalContext.current as ComponentActivity
    fun prompt() {
        val km = activity.getSystemService(android.app.KeyguardManager::class.java)
        if (km?.isDeviceSecure != true) { onUnlocked(); return }
        BiometricPrompt.Builder(activity)
            .setTitle("Unlock $title")
            .setAllowedAuthenticators(Authenticators.BIOMETRIC_WEAK or Authenticators.DEVICE_CREDENTIAL)
            .build()
            .authenticate(CancellationSignal(), activity.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onUnlocked()
            })
    }
    LaunchedEffect(Unit) { prompt() }
    Column(
        Modifier.fillMaxSize().background(c.background).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.Lock, null, tint = c.secondaryLabel, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(14.dp))
        Text("$title is Locked", style = IosType.title2, color = c.label)
        Spacer(Modifier.height(6.dp))
        Text("Use your fingerprint, face or screen lock to view this album.", style = IosType.subheadline, color = c.secondaryLabel, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        Text(
            "View Album", style = IosType.headline, color = c.blue,
            modifier = Modifier.clip(RoundedCornerShape(50)).clickable(remember { MutableInteractionSource() }, null) { prompt() }.padding(12.dp),
        )
    }
}
