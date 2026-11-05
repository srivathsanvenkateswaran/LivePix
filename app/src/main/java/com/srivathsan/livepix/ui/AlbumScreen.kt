package com.srivathsan.livepix.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.RemoveCircleOutline
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

/** Album operations that only make sense for albums the user made. */
class UserAlbumActions(
    val rename: (String) -> Unit,
    val delete: () -> Unit,
    val remove: (List<Asset>) -> Unit,
)

/** A single collection (folder, user album, media type, Hidden) as a grid. */
@Composable
fun AlbumScreen(
    source: Source,
    state: LibraryState,
    gridState: LazyGridState,
    onBack: () -> Unit,
    onOpen: (Asset) -> Unit,
    albumActions: UserAlbumActions? = null,
) {
    val c = Ios.colors
    val assets = remember(state, source) { source.assets(state) }
    var columns by rememberSaveable { mutableIntStateOf(3) }
    var selection by remember { mutableStateOf<Set<String>?>(null) }
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Box(Modifier.fillMaxSize().background(c.background)) {
        AssetGrid(
            assets = assets, state = state, gridState = gridState, columns = columns, onColumnsChange = { columns = it },
            selection = selection, onSelectionChange = { selection = it },
            onOpen = onOpen,
            contentPadding = PaddingValues(top = top + 142.dp, bottom = bottom + 80.dp),
            footer = {
                fullSpan("footer") {
                    Text(
                        if (assets.isEmpty()) {
                            if (albumActions != null) "No Photos. Add photos from the Library with Select → … → Add to Album." else "No Photos or Videos"
                        } else Format.count(assets.size, "Item"),
                        style = IosType.subheadline.copy(fontWeight = FontWeight.SemiBold), color = c.secondaryLabel,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp, horizontal = 24.dp),
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
            Row(Modifier.align(Alignment.TopEnd), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                selection?.let { s ->
                    val all = s.size == assets.size && assets.isNotEmpty()
                    GlassTextButton(if (all) "Deselect All" else "Select All") { selection = if (all) emptySet() else assets.map { it.key }.toSet() }
                }
                if (assets.isNotEmpty()) {
                    GlassTextButton(if (selection == null) "Select" else "Cancel") { selection = if (selection == null) emptySet() else null }
                }
                if (albumActions != null && selection == null) {
                    Box {
                        GlassIconButton(Icons.Outlined.MoreHoriz, "Album options") { menu = true }
                        IosMenu(menu, onDismiss = { menu = false }) {
                            MenuRow("Rename Album", icon = Icons.Outlined.Edit) { menu = false; renaming = true }
                            MenuRow("Delete Album", icon = Icons.Outlined.Delete, color = c.red) { menu = false; albumActions.delete() }
                        }
                    }
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
                selected = assets.filter { it.key in s },
                state = state,
                hiddenView = source == Source.Hidden,
                extra = listOfNotNull(
                    albumActions?.let { a -> ExtraAction("Remove from Album", Icons.Outlined.RemoveCircleOutline, destructive = true) { a.remove(it); selection = null } },
                ),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bottom + 16.dp, start = 16.dp, end = 16.dp),
            )
        }
    }
    if (renaming && albumActions != null) {
        TextPromptDialog(
            title = "Rename Album", message = "Enter a new name for this album.", initial = source.title, confirm = "Save",
            onConfirm = { albumActions.rename(it); renaming = false }, onDismiss = { renaming = false },
        )
    }
}
