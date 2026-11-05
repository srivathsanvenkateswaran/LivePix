package com.srivathsan.livepix

import android.app.Application
import android.app.PendingIntent
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.srivathsan.livepix.data.Asset
import com.srivathsan.livepix.data.AssetEdit
import com.srivathsan.livepix.data.EditStore
import com.srivathsan.livepix.data.MediaRepository
import com.srivathsan.livepix.data.TraitScanner
import com.srivathsan.livepix.data.LibraryStore
import com.srivathsan.livepix.data.UserAlbum
import com.srivathsan.livepix.data.Traits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
) {
    /** The library as shown: everything except hidden items. */
    val assets: List<Asset> by lazy { if (hidden.isEmpty()) allAssets else allAssets.filter { it.key !in hidden } }

    fun traitsOf(a: Asset): Traits? = traits[a.versionKey]
    fun editOf(a: Asset): AssetEdit = edits[a.key] ?: AssetEdit()
}

class GalleryViewModel(app: Application) : AndroidViewModel(app) {
    private val editStore = EditStore(app)
    private val scanner = TraitScanner(app)
    private val library = LibraryStore(app)
    private val _state = MutableStateFlow(
        LibraryState(edits = editStore.all(), albums = library.albums, hidden = library.hidden),
    )
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = refresh()
    }

    init {
        app.contentResolver.registerContentObserver(MediaStore.Files.getContentUri("external"), true, observer)
    }

    fun refresh() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val ctx = getApplication<Application>()
            val raw = withContext(Dispatchers.IO) { MediaRepository.load(ctx) }
            val trashed = withContext(Dispatchers.IO) { MediaRepository.loadTrashed(ctx) }
            val known = scanner.cached()
            _state.update {
                it.copy(loaded = true, allAssets = MediaRepository.pairByContent(raw, known), traits = known, trashed = trashed)
            }

            // Scan what needs scanning: HEIC/JPEG stills for depth + content id, short movies for content id.
            val todo = raw.filter { (it.isHeif || it.mime == "image/jpeg" || MediaRepository.isLiveCandidate(it)) && it.versionKey !in known }
            if (todo.isEmpty()) return@launch
            _state.update { it.copy(scanning = true) }
            val found = HashMap(known)
            withContext(Dispatchers.IO) {
                todo.forEachIndexed { i, a ->
                    found[a.versionKey] = scanner.scan(a)
                    if (i % 40 == 39 || i == todo.lastIndex) {
                        val snapshot = HashMap(found)
                        _state.update { it.copy(traits = snapshot, allAssets = MediaRepository.pairByContent(raw, snapshot)) }
                    }
                }
                scanner.persist()
            }
            _state.update { it.copy(scanning = false) }
        }
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
    fun setHidden(assets: List<Asset>, hide: Boolean) { library.setHidden(assets.map { it.key }, hide); syncLibrary() }

    private fun syncLibrary() = _state.update { it.copy(albums = library.albums, hidden = library.hidden) }

    private fun uris(assets: List<Asset>) = assets.flatMap { listOfNotNull(it.uri, it.liveVideo) }

    /** Moves items to Recently Deleted (Android's trash, kept for 30 days). Shows the system prompt. */
    fun trashRequest(assets: List<Asset>): PendingIntent =
        MediaStore.createTrashRequest(getApplication<Application>().contentResolver, uris(assets), true)

    fun restoreRequest(assets: List<Asset>): PendingIntent =
        MediaStore.createTrashRequest(getApplication<Application>().contentResolver, uris(assets), false)

    /** Permanent deletion, for Recently Deleted. */
    fun deleteRequest(assets: List<Asset>): PendingIntent =
        MediaStore.createDeleteRequest(getApplication<Application>().contentResolver, uris(assets))

    override fun onCleared() {
        getApplication<Application>().contentResolver.unregisterContentObserver(observer)
    }
}
