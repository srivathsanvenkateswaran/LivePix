package com.srivathsan.livepix

import android.app.Application
import android.app.PendingIntent
import android.database.ContentObserver
import android.graphics.Bitmap
import android.os.PowerManager
import android.util.Log
import android.util.Size
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.srivathsan.livepix.data.Asset
import com.srivathsan.livepix.data.AssetEdit
import com.srivathsan.livepix.data.City
import com.srivathsan.livepix.data.EditStore
import com.srivathsan.livepix.data.Geo
import com.srivathsan.livepix.data.GeoIndex
import com.srivathsan.livepix.data.GeoPoint
import com.srivathsan.livepix.data.MediaRepository
import com.srivathsan.livepix.data.TraitScanner
import com.srivathsan.livepix.data.LibraryStore
import com.srivathsan.livepix.data.UserAlbum
import com.srivathsan.livepix.data.ImageFacts
import com.srivathsan.livepix.data.Memory
import com.srivathsan.livepix.data.MemoryItem
import com.srivathsan.livepix.data.MemoryMaker
import com.srivathsan.livepix.data.PeopleStore
import com.srivathsan.livepix.data.Traits
import com.srivathsan.livepix.data.VisionIndex
import com.srivathsan.livepix.data.VisionStore
import com.srivathsan.livepix.media.ImageLoader
import com.srivathsan.livepix.media.Vision
import com.srivathsan.livepix.data.hasMediaLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LibraryState(
    val loaded: Boolean = false,
    /** Everything on the device, oldest first, including hidden items. */
    val allAssets: List<Asset> = emptyList(),
    val traits: Map<String, Traits> = emptyMap(),
    val edits: Map<String, AssetEdit> = emptyMap(),
    val scanning: Boolean = false,
    val albums: List<UserAlbum> = emptyList(),
    val hidden: Set<String> = emptySet(),
    /** Recently Deleted, from Android's trash. */
    val trashed: List<Asset> = emptyList(),
    /** Offline place names; null until loaded. */
    val geo: GeoIndex? = null,
    val locationOverrides: Map<String, GeoPoint?> = emptyMap(),
    /** People, pets, duplicates and scene labels; null until first built. */
    val vision: VisionIndex? = null,
    val memories: List<Memory> = emptyList(),
    /** Photos the models have got through, while a run is going. */
    val analysisDone: Int = 0,
    val analysisTotal: Int = 0,
) {
    /** The library as shown: everything except hidden items. */
    val assets: List<Asset> by lazy { if (hidden.isEmpty()) allAssets else allAssets.filter { it.key !in hidden } }

    fun traitsOf(a: Asset): Traits? {
        val t = traits[a.versionKey]
        if (a.key !in locationOverrides) return t
        val p = locationOverrides[a.key]
        return (t ?: Traits()).copy(lat = p?.lat, lng = p?.lng, locationEdited = true)
    }

    /** Everything with a location, oldest first. */
    fun located(): List<Pair<Asset, GeoPoint>> = assets.mapNotNull { a -> traitsOf(a)?.point?.let { a to it } }

    /** The city nearest where the item was taken. */
    fun placeOf(a: Asset): City? = traitsOf(a)?.point?.let { geo?.nearest(it.lat, it.lng) }

    fun editOf(a: Asset): AssetEdit = edits[a.key] ?: AssetEdit()
}

class GalleryViewModel(app: Application) : AndroidViewModel(app) {
    private val editStore = EditStore(app)
    private var scanner = TraitScanner(app)
    private val library = LibraryStore(app)
    private val _state = MutableStateFlow(
        LibraryState(edits = editStore.all(), albums = library.albums, hidden = library.hidden, locationOverrides = library.locations),
    )
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var analysisJob: Job? = null
    private val foreground = MutableStateFlow(true)
    private val indexLock = Mutex()
    private val visionStore by lazy { VisionStore(app) }
    private val peopleStore by lazy { PeopleStore(app) }
    private val labelNames by lazy {
        app.assets.open("models/imagenet_classes.txt").bufferedReader().use { it.readLines() }
    }
    private val analysisDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "vision").apply { priority = Thread.MIN_PRIORITY }
    }.asCoroutineDispatcher()
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = refresh()
    }

    init {
        app.contentResolver.registerContentObserver(MediaStore.Files.getContentUri("external"), true, observer)
        viewModelScope.launch(Dispatchers.IO) {
            val geo = Geo.index(app)
            _state.update { it.copy(geo = geo) }
            rebuildIndex()
        }
    }

    fun refresh() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val ctx = getApplication<Application>()
            if (scanner.hasLocationAccess != hasMediaLocation(ctx)) scanner = TraitScanner(ctx)
            val raw = withContext(Dispatchers.IO) { MediaRepository.load(ctx) }
            val trashed = withContext(Dispatchers.IO) { MediaRepository.loadTrashed(ctx) }
            val known = scanner.cached()
            _state.update {
                it.copy(loaded = true, allAssets = MediaRepository.pairByContent(raw, known).withDates(library.dates), traits = known, trashed = trashed)
            }

            // Scan what needs scanning: stills for depth, content id and GPS, movies for content id and GPS.
            val todo = raw.filter { (it.isHeif || it.mime == "image/jpeg" || it.isVideo) && it.versionKey !in known }
            if (todo.isEmpty()) {
                startAnalysis()
                return@launch
            }
            _state.update { it.copy(scanning = true) }
            val found = HashMap(known)
            withContext(Dispatchers.IO) {
                todo.forEachIndexed { i, a ->
                    found[a.versionKey] = scanner.scan(a)
                    if (i % 40 == 39 || i == todo.lastIndex) {
                        val snapshot = HashMap(found)
                        _state.update { it.copy(traits = snapshot, allAssets = MediaRepository.pairByContent(raw, snapshot).withDates(library.dates)) }
                    }
                }
                scanner.persist()
            }
            _state.update { it.copy(scanning = false) }
            startAnalysis()
        }
    }

    fun setForeground(visible: Boolean) { foreground.value = visible }

    /**
     * Runs the models over photos not seen before, newest first, one at a time at low priority. It
     * stops while the app is in the background or the phone is in battery saver, and resumes where it
     * left off, since results are stored as it goes.
     */
    private fun startAnalysis() {
        analysisJob?.cancel()
        analysisJob = viewModelScope.launch(analysisDispatcher) {
            val ctx = getApplication<Application>()
            rebuildIndex()
            val todo = _state.value.allAssets.filter { !it.isVideo && it.mime != "image/gif" && !visionStore.has(it.versionKey) }.reversed()
            if (todo.isEmpty()) return@launch
            _state.update { it.copy(analysisDone = 0, analysisTotal = todo.size) }
            val vision = runCatching { Vision(ctx) }.onFailure { Log.w(TAG, "models: $it") }.getOrNull() ?: return@launch
            val power = ctx.getSystemService(PowerManager::class.java)
            try {
                todo.forEachIndexed { i, asset ->
                    foreground.first { it }
                    while (power.isPowerSaveMode) delay(30_000)
                    ensureActive()
                    val facts = runCatching { analysisBitmap(ctx, asset)?.let(vision::analyze) }
                        .onFailure { Log.w(TAG, "analyze ${asset.name}: $it") }.getOrNull()
                    visionStore.put(asset.versionKey, facts ?: NOTHING_FOUND)
                    if (i % 10 == 9) _state.update { it.copy(analysisDone = i + 1) }
                    if (i % 150 == 149) rebuildIndex()
                }
            } finally {
                vision.close()
                visionStore.flush()
            }
            visionStore.compact(_state.value.allAssets.map { it.versionKey }.toSet())
            _state.update { it.copy(analysisDone = 0, analysisTotal = 0) }
            rebuildIndex()
        }
    }

    /** MediaStore's cached thumbnail where it has one, in a form whose pixels can be read. */
    private fun analysisBitmap(ctx: Application, asset: Asset): Bitmap? {
        val source = runCatching { ctx.contentResolver.loadThumbnail(asset.uri, Size(ANALYSIS_SIDE, ANALYSIS_SIDE), null) }.getOrNull()
            ?: ImageLoader.decode(ctx, asset.uri, ANALYSIS_SIDE, software = true)
            ?: return null
        return if (source.config == Bitmap.Config.ARGB_8888) source else source.copy(Bitmap.Config.ARGB_8888, false)
    }

    /** Recomputes people, duplicates and memories from what is known so far. */
    private suspend fun rebuildIndex() = indexLock.withLock {
        withContext(Dispatchers.Default) {
            val s = _state.value
            val names = labelNames
            val version = peopleStore.version
            val built = VisionIndex.build(s.assets, s::traitsOf, visionStore.snapshot(), peopleStore.records, names)
            // The change that interrupted this asked for another rebuild, which will publish.
            if (!peopleStore.replaceIfUnchanged(version, built.records)) return@withContext
            val index = built.index
            val namesByAsset = HashMap<String, MutableList<String>>()
            val withFace = HashSet<String>()
            index.people.forEach { p ->
                withFace += p.keys
                if (p.name != null) p.keys.forEach { namesByAsset.getOrPut(it) { ArrayList() } += p.name }
            }
            val pets = (index.cats + index.dogs).toSet()
            val facts = visionStore.snapshot()
            val items = s.assets.filterNot { it.relativePath.contains("Screenshot", true) }.map { a ->
                MemoryItem(
                    a.key, a.dateTaken, s.placeOf(a), s.editOf(a).favorite, facts[a.versionKey]?.hash ?: 0,
                    namesByAsset[a.key].orEmpty(), a.key in pets, a.key in withFace,
                )
            }
            val memories = MemoryMaker.make(items)
            _state.update { it.copy(vision = index, memories = memories) }
        }
    }

    private fun rebuildSoon() { viewModelScope.launch { rebuildIndex() } }

    /** Names someone; a name already in use merges the two. Returns the id that remains. */
    fun namePerson(id: String, name: String): String = peopleStore.name(id, name).also { rebuildSoon() }

    /** "Not This Person" for these photos. */
    fun rejectFromPerson(id: String, assets: List<Asset>) {
        val person = _state.value.vision?.person(id) ?: return
        peopleStore.reject(id, assets.flatMap { person.faceIds[it.key].orEmpty() })
        rebuildSoon()
    }

    /**
     * Keeps the first of a group of copies, giving it the favourite and albums of the others, and
     * asks Android to move the rest to Recently Deleted.
     */
    fun mergeDuplicates(groups: List<List<Asset>>): PendingIntent {
        val remove = ArrayList<Asset>()
        for (group in groups) {
            val keep = group.first()
            val rest = group.drop(1)
            if (rest.any { _state.value.editOf(it).favorite }) updateEdit(keep) { it.copy(favorite = true) }
            library.albums.filter { album -> rest.any { it.key in album.keys } }.forEach { library.addToAlbum(it.id, listOf(keep.key)) }
            remove += rest
        }
        syncLibrary()
        return trashRequest(remove)
    }

    fun updateEdit(asset: Asset, transform: (AssetEdit) -> AssetEdit) {
        val next = transform(_state.value.editOf(asset))
        editStore.put(asset.key, next)
        _state.update { it.copy(edits = it.edits + (asset.key to next)) }
    }

    fun toggleFavorite(asset: Asset) = updateEdit(asset) { it.copy(favorite = !it.favorite) }

    /** Favourites all of [assets], or unfavourites them if they already all are. */
    fun setFavorite(assets: List<Asset>) {
        val makeFavorite = !assets.all { _state.value.editOf(it).favorite }
        assets.forEach { a -> updateEdit(a) { it.copy(favorite = makeFavorite) } }
    }

    fun createAlbum(name: String, assets: List<Asset> = emptyList()): UserAlbum =
        library.createAlbum(name, assets.map { it.key }).also { syncLibrary() }

    fun addToAlbum(albumId: String, assets: List<Asset>) { library.addToAlbum(albumId, assets.map { it.key }); syncLibrary() }
    fun removeFromAlbum(albumId: String, assets: List<Asset>) { library.removeFromAlbum(albumId, assets.map { it.key }); syncLibrary() }
    fun renameAlbum(albumId: String, name: String) { library.renameAlbum(albumId, name); syncLibrary() }
    fun deleteAlbum(albumId: String) { library.deleteAlbum(albumId); syncLibrary() }
    fun setHidden(assets: List<Asset>, hide: Boolean) { library.setHidden(assets.map { it.key }, hide); syncLibrary(); rebuildSoon() }

    /** Moves the first item to [firstDate]; the others shift by the same amount. */
    fun adjustDate(assets: List<Asset>, firstDate: Long) {
        val delta = firstDate - assets.first().dateTaken
        library.setDates(assets.associate { it.key to it.dateTaken + delta })
        syncDates()
    }

    fun revertDate(assets: List<Asset>) { library.clearDates(assets.map { it.key }); syncDates() }
    fun setLocation(assets: List<Asset>, point: GeoPoint?) { library.setLocation(assets.map { it.key }, point); syncLibrary() }
    fun revertLocation(assets: List<Asset>) { library.clearLocation(assets.map { it.key }); syncLibrary() }

    private fun syncDates() = _state.update { it.copy(allAssets = it.allAssets.withDates(library.dates)) }

    private fun syncLibrary() = _state.update { it.copy(albums = library.albums, hidden = library.hidden, locationOverrides = library.locations) }

    /** Applies adjusted capture times and restores the library's date order. Safe to re-apply. */
    private fun List<Asset>.withDates(dates: Map<String, Long>): List<Asset> {
        if (dates.isEmpty() && none { it.originalDate != null }) return this
        return map { a ->
            val original = a.originalDate ?: a.dateTaken
            val adjusted = dates[a.key]
            a.copy(dateTaken = adjusted ?: original, originalDate = if (adjusted != null) original else null)
        }.sortedBy { it.dateTaken }
    }

    private fun uris(assets: List<Asset>) = assets.flatMap { listOfNotNull(it.uri, it.liveVideo) }

    /** Moves items to Recently Deleted (Android's trash, kept for 30 days). Shows the system prompt. */
    fun trashRequest(assets: List<Asset>): PendingIntent =
        MediaStore.createTrashRequest(getApplication<Application>().contentResolver, uris(assets), true)

    fun restoreRequest(assets: List<Asset>): PendingIntent =
        MediaStore.createTrashRequest(getApplication<Application>().contentResolver, uris(assets), false)

    /** Permanent deletion, for Recently Deleted. */
    fun deleteRequest(assets: List<Asset>): PendingIntent =
        MediaStore.createDeleteRequest(getApplication<Application>().contentResolver, uris(assets))

    private companion object {
        const val TAG = "GalleryViewModel"
        const val ANALYSIS_SIDE = 1024
        val NOTHING_FOUND = ImageFacts(0, 0, ImageFacts.NO_PET, IntArray(0), emptyList())
    }

    override fun onCleared() {
        analysisDispatcher.close()
        getApplication<Application>().contentResolver.unregisterContentObserver(observer)
    }
}
