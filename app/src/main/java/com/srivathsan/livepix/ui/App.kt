package com.srivathsan.livepix.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.srivathsan.livepix.GalleryViewModel
import com.srivathsan.livepix.data.Asset
import com.srivathsan.livepix.media.Exporter
import com.srivathsan.livepix.media.StillRenderer
import com.srivathsan.livepix.media.VideoTrim
import com.srivathsan.livepix.ui.components.FloatingTabBar
import com.srivathsan.livepix.ui.components.TabItem
import com.srivathsan.livepix.ui.theme.Ios
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface Route {
    data object Home : Route
    data class Collection(val source: Source) : Route
    data class Viewer(val source: Source, val startKey: String) : Route
    data class Edit(val key: String, val source: Source) : Route
    data object Trash : Route
    data object Places : Route
    data class Slideshow(val keys: List<String>, val startKey: String? = null, val title: String? = null, val subtitle: String? = null) : Route
}

private enum class Tab { LIBRARY, COLLECTIONS, SEARCH }

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun LivePixApp(vm: GalleryViewModel, externalUri: Uri?) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = Ios.colors

    val stack = remember { mutableStateListOf<Route>(Route.Home) }
    var tab by rememberSaveable { mutableStateOf(Tab.LIBRARY) }
    var libraryMode by rememberSaveable { mutableStateOf(LibraryMode.ALL) }
    var columns by rememberSaveable { mutableIntStateOf(3) }
    var selection by remember { mutableStateOf<Set<String>?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf<String?>(null) }
    // Pending "Add to Album" / "New Album" flows, holding the photos they apply to.
    var addingToAlbum by remember { mutableStateOf<List<Asset>?>(null) }
    var newAlbumFor by remember { mutableStateOf<List<Asset>?>(null) }
    var adjustingDate by remember { mutableStateOf<List<Asset>?>(null) }
    var adjustingLocation by remember { mutableStateOf<List<Asset>?>(null) }
    // Hidden and Recently Deleted unlock once per visit to the app.
    var unlocked by remember { mutableStateOf(false) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_STOP) unlocked = false
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    val libraryGrid = rememberLazyGridState()
    val years = rememberLazyListState()
    val months = rememberLazyListState()
    val collectionsList = rememberLazyListState()
    val albumGrids = remember { HashMap<Source, LazyGridState>() }

    // Photos opens at the newest item, at the bottom of the grid.
    var scrolledToEnd by remember { mutableStateOf(false) }
    LaunchedEffect(state.assets.size) {
        if (!scrolledToEnd && state.assets.isNotEmpty()) {
            libraryGrid.scrollToItem(state.assets.size)
            scrolledToEnd = true
        }
    }
    LaunchedEffect(externalUri, state.loaded) {
        if (externalUri != null && state.loaded) {
            val id = externalUri.lastPathSegment?.toLongOrNull()
            val match = state.assets.firstOrNull { it.uri == externalUri || (id != null && it.id == id && externalUri.authority == "media") }
            if (match != null) stack.add(Route.Viewer(Source.Library, match.key))
            else Toast.makeText(context, "Open this photo from LivePix's library to use Live and Portrait.", Toast.LENGTH_LONG).show()
        }
    }

    val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        selection = null
        vm.refresh()
    }

    fun runExport(label: String, block: suspend () -> Unit) {
        busy = label
        scope.launch {
            runCatching { block() }.onFailure {
                Toast.makeText(context, "Couldn't finish: ${it.message}", Toast.LENGTH_LONG).show()
            }
            busy = null
        }
    }

    fun share(assets: List<Asset>) = runExport("Preparing…") {
        val files = withContext(Dispatchers.IO) {
            assets.map { a ->
                val edit = state.editOf(a)
                val traits = state.traitsOf(a)
                when {
                    a.isVideo && edit.isTrimmed -> null
                    !a.isVideo && (StillRenderer.needsRender(a, traits, edit) || a.isHeif) -> Exporter.renderJpeg(context, a, traits, edit)
                    else -> Exporter.original(context, a.uri, a.name)
                } ?: VideoTrim.export(context, a.uri, a.name, edit.trimStartUs, edit.trimEndUs)
            }
        }
        val mime = when {
            assets.all { it.isVideo } -> "video/*"
            assets.any { it.isVideo } -> "*/*"
            else -> "image/jpeg"
        }
        context.startActivity(Exporter.shareIntent(context, files, mime))
    }

    fun launch(pi: android.app.PendingIntent) = deleteLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())

    /** Delete in Photos means "move to Recently Deleted". */
    fun trash(assets: List<Asset>) = launch(vm.trashRequest(assets))

    val assetActions = AssetActions(
        share = ::share,
        toggleFavorite = vm::setFavorite,
        addToAlbum = { addingToAlbum = it },
        setHidden = { list, hide ->
            vm.setHidden(list, hide)
            selection = null
            Toast.makeText(
                context,
                if (hide) "${Format.count(list.size, "item")} hidden. Find them in Collections → Hidden." else "${Format.count(list.size, "item")} unhidden",
                Toast.LENGTH_SHORT,
            ).show()
        },
        trash = ::trash,
        open = { a, s -> stack.add(Route.Viewer(s, a.key)) },
        slideshow = { list, start -> stack.add(Route.Slideshow(list.map { it.key }, start)) },
        adjustDate = { adjustingDate = it },
        adjustLocation = { adjustingLocation = it },
    )

    val viewerActions = { source: Source ->
        ViewerActions(
            onBack = { stack.removeLastOrNull() },
            onEdit = { a -> stack.add(Route.Edit(a.key, source)) },
            onEditChange = { a, t -> vm.updateEdit(a, t) },
            onFavorite = vm::toggleFavorite,
            onShare = { share(listOf(it)) },
            onDelete = { trash(listOf(it)) },
            onSaveCopy = { a ->
                runExport("Saving…") {
                    val e = state.editOf(a)
                    if (a.isVideo) {
                        val f = if (e.isTrimmed) VideoTrim.export(context, a.uri, a.name, e.trimStartUs, e.trimEndUs)
                        else withContext(Dispatchers.IO) { Exporter.original(context, a.uri, a.name) }
                        withContext(Dispatchers.IO) { Exporter.saveToGallery(context, f, "video/mp4", a.dateTaken) }
                    } else withContext(Dispatchers.IO) {
                        val f = Exporter.renderJpeg(context, a, state.traitsOf(a), e)
                        Exporter.saveToGallery(context, f, "image/jpeg", a.dateTaken)
                    }
                    Toast.makeText(context, "Saved to Pictures/LivePix", Toast.LENGTH_SHORT).show()
                }
            },
            onMotionPhoto = { a ->
                runExport("Creating Motion Photo…") {
                    val f: File = withContext(Dispatchers.IO) {
                        Exporter.motionPhoto(context, a, state.traitsOf(a), state.editOf(a)).also {
                            Exporter.saveToGallery(context, it, "image/jpeg", a.dateTaken)
                        }
                    }
                    Toast.makeText(context, "Saved ${f.name} to Pictures/LivePix", Toast.LENGTH_SHORT).show()
                }
            },
            onShareOriginal = { a ->
                runExport("Preparing…") {
                    val files = withContext(Dispatchers.IO) {
                        listOfNotNull(
                            Exporter.original(context, a.uri, a.name),
                            a.liveVideo?.let { Exporter.original(context, it, a.baseName.uppercase() + ".MOV") },
                        )
                    }
                    context.startActivity(Exporter.shareIntent(context, files, "*/*"))
                }
            },
        )
    }

    BackHandler(stack.size > 1 || selection != null || tab != Tab.LIBRARY) {
        when {
            selection != null && stack.size == 1 -> selection = null
            stack.size > 1 -> stack.removeLastOrNull()
            else -> tab = Tab.LIBRARY
        }
    }

    SharedTransitionLayout(Modifier.fillMaxSize().background(c.background)) {
        AnimatedContent(
            targetState = stack.last(),
            transitionSpec = {
                val forward = targetState !is Route.Home && (initialState is Route.Home || initialState is Route.Collection && targetState !is Route.Collection)
                when {
                    targetState is Route.Collection && initialState is Route.Home ->
                        slideInHorizontally(tween(320)) { it } togetherWith slideOutHorizontally(tween(320)) { -it / 4 }
                    initialState is Route.Collection && targetState is Route.Home ->
                        slideInHorizontally(tween(320)) { -it / 4 } togetherWith slideOutHorizontally(tween(320)) { it }
                    else -> fadeIn(tween(if (forward) 260 else 220)) togetherWith fadeOut(tween(220))
                }
            },
            label = "route",
        ) { route ->
            CompositionLocalProvider(
                LocalShared provides SharedScopes(this@SharedTransitionLayout, this@AnimatedContent),
                LocalAssetActions provides assetActions,
            ) {
                when (route) {
                    Route.Home -> Box(Modifier.fillMaxSize()) {
                        when (tab) {
                            Tab.LIBRARY -> LibraryScreen(
                                state = state, gridState = libraryGrid, yearsState = years, monthsState = months,
                                columns = columns, onColumnsChange = { columns = it },
                                mode = libraryMode, onModeChange = { libraryMode = it },
                                selection = selection, onSelectionChange = { selection = it },
                                onOpen = { stack.add(Route.Viewer(Source.Library, it.key)) },
                            )
                            Tab.COLLECTIONS -> CollectionsScreen(
                                state, collectionsList,
                                onOpenSource = { stack.add(Route.Collection(it)) },
                                onNewAlbum = { newAlbumFor = emptyList() },
                                onUtility = { u ->
                                    stack.add(if (u == Utility.RECENTLY_DELETED) Route.Trash else Route.Collection(Source.Hidden))
                                },
                                onPlaces = { stack.add(Route.Places) },
                            )
                            Tab.SEARCH -> SearchScreen(state, query, { query = it }) { a, s -> stack.add(Route.Viewer(s, a.key)) }
                        }
                        if (selection == null) {
                            val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                            FloatingTabBar(
                                tabs = listOf(
                                    TabItem("Library", Icons.Outlined.PhotoLibrary, Icons.Filled.PhotoLibrary),
                                    TabItem("Collections", Icons.Outlined.Collections, Icons.Filled.Collections),
                                ),
                                selected = if (tab == Tab.COLLECTIONS) 1 else 0,
                                onSelect = { i ->
                                    val next = if (i == 0) Tab.LIBRARY else Tab.COLLECTIONS
                                    if (next == tab && next == Tab.LIBRARY) scope.launch { libraryGrid.animateScrollToItem(state.assets.size) }
                                    tab = next
                                },
                                searchIcon = Icons.Outlined.Search,
                                searchSelected = tab == Tab.SEARCH,
                                onSearch = { tab = Tab.SEARCH },
                                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bottom + 12.dp),
                            )
                        }
                    }
                    is Route.Collection -> {
                        val source = route.source
                        val content = @Composable {
                            AlbumScreen(
                                source = source, state = state,
                                gridState = albumGrids.getOrPut(source) { LazyGridState() },
                                onBack = { stack.removeLastOrNull() },
                                onOpen = { stack.add(Route.Viewer(source, it.key)) },
                                albumActions = (source as? Source.UserAlbumSource)?.let { s ->
                                    UserAlbumActions(
                                        rename = { name -> vm.renameAlbum(s.id, name); stack[stack.lastIndex] = Route.Collection(s.copy(title = name)) },
                                        delete = { vm.deleteAlbum(s.id); stack.removeLastOrNull() },
                                        remove = { vm.removeFromAlbum(s.id, it) },
                                    )
                                },
                            )
                        }
                        if (source == Source.Hidden) LockGate("Hidden", unlocked, { unlocked = true }, content) else content()
                    }
                    Route.Trash -> LockGate("Recently Deleted", unlocked, { unlocked = true }) {
                        TrashScreen(
                            state = state,
                            onBack = { stack.removeLastOrNull() },
                            onRecover = { launch(vm.restoreRequest(it)) },
                            onDeleteForever = { launch(vm.deleteRequest(it)) },
                        )
                    }
                    is Route.Slideshow -> {
                        val assets = remember(route.keys) {
                            val byKey = state.allAssets.associateBy { it.key }
                            route.keys.mapNotNull { byKey[it] }
                        }
                        val showAll = route.title?.let { t -> { stack[stack.lastIndex] = Route.Collection(Source.Keys(t, route.keys)) } }
                        SlideshowScreen(assets, state, route.title, route.subtitle, route.startKey, onClose = { stack.removeLastOrNull() }, onShowAll = showAll)
                    }
                    Route.Places -> PlacesScreen(state, onBack = { stack.removeLastOrNull() }, onOpenSource = { stack.add(Route.Collection(it)) })
                    is Route.Viewer -> {
                        val assets = remember(state, route.source) { route.source.assets(state) }
                        ViewerScreen(
                            assets = assets, startKey = route.startKey, state = state,
                            actions = viewerActions(route.source),
                            onPageChange = { a ->
                                // Keep the grid underneath on the photo being viewed, so closing zooms back to it.
                                val i = state.assets.indexOf(a)
                                if (route.source == Source.Library && i >= 0) scope.launch { libraryGrid.scrollToItem(i) }
                                val idx = stack.indexOf(route)
                                if (idx >= 0) stack[idx] = route.copy(startKey = a.key)
                            },
                        )
                    }
                    is Route.Edit -> {
                        val asset = state.allAssets.firstOrNull { it.key == route.key }
                        if (asset == null) LaunchedEffect(Unit) { stack.removeLastOrNull() }
                        else if (asset.isVideo) VideoEditScreen(
                            asset = asset, initial = state.editOf(asset),
                            onCancel = { stack.removeLastOrNull() },
                            onDone = { e -> vm.updateEdit(asset) { e }; stack.removeLastOrNull() },
                        )
                        else EditScreen(
                            asset = asset, traits = state.traitsOf(asset), initial = state.editOf(asset),
                            onCancel = { stack.removeLastOrNull() },
                            onDone = { e -> vm.updateEdit(asset) { e }; stack.removeLastOrNull() },
                        )
                    }
                }
            }
        }
        busy?.let { BusyOverlay(it) }
    }

    addingToAlbum?.let { list ->
        AddToAlbumSheet(
            state = state, count = list.size,
            onNewAlbum = { addingToAlbum = null; newAlbumFor = list },
            onPick = { album ->
                vm.addToAlbum(album.id, list)
                addingToAlbum = null
                selection = null
                Toast.makeText(context, "Added to ${album.name}", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { addingToAlbum = null },
        )
    }
    CompositionLocalProvider(LocalAssetActions provides assetActions) {
        adjustingDate?.let { list ->
            AdjustDateSheet(
                list,
                onAdjust = { vm.adjustDate(list, it); adjustingDate = null; selection = null },
                onRevert = { vm.revertDate(list); adjustingDate = null; selection = null },
                onDismiss = { adjustingDate = null },
            )
        }
        adjustingLocation?.let { list ->
            AdjustLocationSheet(
                list, state,
                onPick = { vm.setLocation(list, it); adjustingLocation = null; selection = null },
                onRevert = { vm.revertLocation(list); adjustingLocation = null; selection = null },
                onDismiss = { adjustingLocation = null },
            )
        }
    }
    newAlbumFor?.let { list ->
        TextPromptDialog(
            title = "New Album", message = "Enter a name for this album.", initial = "", confirm = "Save",
            onConfirm = { name ->
                vm.createAlbum(name, list)
                newAlbumFor = null
                selection = null
            },
            onDismiss = { newAlbumFor = null },
        )
    }
}
