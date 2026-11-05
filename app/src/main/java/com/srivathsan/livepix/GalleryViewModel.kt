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
    /** Oldest first, like the Photos library grid. */
    val assets: List<Asset> = emptyList(),
    val traits: Map<String, Traits> = emptyMap(),
    val edits: Map<String, AssetEdit> = emptyMap(),
    val scanning: Boolean = false,
) {
    fun traitsOf(a: Asset): Traits? = traits[a.versionKey]
    fun editOf(a: Asset): AssetEdit = edits[a.key] ?: AssetEdit()
}

class GalleryViewModel(app: Application) : AndroidViewModel(app) {
    private val editStore = EditStore(app)
    private val scanner = TraitScanner(app)
    private val _state = MutableStateFlow(LibraryState(edits = editStore.all()))
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
            val known = scanner.cached()
            _state.update { it.copy(loaded = true, assets = MediaRepository.pairByContent(raw, known), traits = known) }

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
                        _state.update { it.copy(traits = snapshot, assets = MediaRepository.pairByContent(raw, snapshot)) }
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

    /** System confirmation dialog for deleting the asset (and its Live movie). */
    fun deleteRequest(assets: List<Asset>): PendingIntent {
        val uris = assets.flatMap { listOfNotNull(it.uri, it.liveVideo) }
        return MediaStore.createDeleteRequest(getApplication<Application>().contentResolver, uris)
    }

    override fun onCleared() {
        getApplication<Application>().contentResolver.unregisterContentObserver(observer)
    }
}
