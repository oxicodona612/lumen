package app.lumen.photos

import android.app.Application
import android.content.IntentSender
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.lumen.photos.data.Album
import app.lumen.photos.data.MediaItem
import app.lumen.photos.data.MediaRepository
import app.lumen.photos.data.Origin
import app.lumen.photos.data.Vault
import app.lumen.photos.data.buildAlbums
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Routes MediaStore consent sheets (trash, delete, favorite) through the Activity. */
class ConsentBroker {
    class Request(val sender: IntentSender, val result: CompletableDeferred<Boolean>)

    private val channel = Channel<Request>(Channel.UNLIMITED)
    val requests: Flow<Request> = channel.receiveAsFlow()

    suspend fun ask(sender: IntentSender): Boolean {
        val request = Request(sender, CompletableDeferred())
        channel.send(request)
        return request.result.await()
    }
}

class GalleryViewModel(app: Application) : AndroidViewModel(app) {

    val repo = MediaRepository(app)
    private val vault = Vault(app)
    val consent = ConsentBroker()

    private val _library = MutableStateFlow<List<MediaItem>?>(null)
    val library: StateFlow<List<MediaItem>?> = _library.asStateFlow()

    private val _trash = MutableStateFlow<List<MediaItem>>(emptyList())
    val trash: StateFlow<List<MediaItem>> = _trash.asStateFlow()

    val hidden: StateFlow<List<MediaItem>> = vault.entries
        .map { entries -> entries.map(vault::toItem).sortedBy { it.dateTaken } }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val albums: StateFlow<List<Album>> = library
        .map { buildAlbums(it.orEmpty()) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _vaultUnlocked = MutableStateFlow(false)
    val vaultUnlocked: StateFlow<Boolean> = _vaultUnlocked.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    private var observer: Job? = null
    private var trashWanted = false

    fun start() {
        if (observer != null) return
        observer = viewModelScope.launch {
            var first = true
            repo.changes()
                .onStart { emit(Unit) }
                .collectLatest {
                    // collectLatest + delay = debounce: a burst of inserts reloads once.
                    if (!first) delay(250)
                    first = false
                    reload()
                }
        }
    }

    fun refresh() = viewModelScope.launch { reload() }

    fun wantTrash() {
        trashWanted = true
        viewModelScope.launch { _trash.value = withContext(Dispatchers.IO) { repo.load(trashed = true) } }
    }

    private suspend fun reload() {
        _library.value = withContext(Dispatchers.IO) { repo.load() }
        if (trashWanted) _trash.value = withContext(Dispatchers.IO) { repo.load(trashed = true) }
    }

    fun unlockVault() { _vaultUnlocked.value = true }
    fun lockVault() { _vaultUnlocked.value = false }

    fun describe(uri: Uri, mime: String?): MediaItem = repo.describe(uri, mime)

    // ---- actions -------------------------------------------------------------------------

    fun moveToTrash(items: List<MediaItem>) = act(items) { store ->
        if (consent.ask(repo.trashRequest(store, trash = true))) {
            say(if (store.size == 1) "Moved to Recently Deleted" else "${store.size} items moved to Recently Deleted")
        }
    }

    fun recover(items: List<MediaItem>) = act(items) { store ->
        if (consent.ask(repo.trashRequest(store, trash = false))) {
            say(if (store.size == 1) "Recovered" else "${store.size} items recovered")
            wantTrash()
        }
    }

    fun setFavorite(items: List<MediaItem>, favorite: Boolean) = act(items) { store ->
        consent.ask(repo.favoriteRequest(store, favorite))
    }

    fun deleteForever(items: List<MediaItem>) = viewModelScope.launch {
        val inVault = items.filter { it.origin == Origin.Vault }.mapNotNull(vault::entryFor)
        val inStore = items.filter { it.origin == Origin.Store }
        if (inStore.isNotEmpty() && consent.ask(repo.deleteRequest(inStore))) wantTrash()
        if (inVault.isNotEmpty()) vault.delete(inVault)
        if (inVault.isNotEmpty()) say(if (inVault.size == 1) "Deleted" else "${inVault.size} items deleted")
    }

    fun hide(items: List<MediaItem>) = viewModelScope.launch {
        val store = items.filter { it.origin == Origin.Store }
        if (store.isEmpty()) return@launch
        val staged = ArrayList<Vault.Entry>(store.size)
        try {
            for (item in store) staged += vault.stage(item)
        } catch (t: Throwable) {
            vault.discard(staged)
            say("Couldn't hide: ${t.message ?: "copy failed"}")
            return@launch
        }
        // The vault copy exists before the original goes, so a refused sheet loses nothing.
        if (consent.ask(repo.deleteRequest(store))) {
            vault.commit(staged)
            say(if (store.size == 1) "Hidden" else "${store.size} items hidden")
        } else {
            vault.discard(staged)
        }
    }

    fun unhide(items: List<MediaItem>) = viewModelScope.launch {
        val entries = items.mapNotNull(vault::entryFor)
        val restored = vault.restore(entries)
        when {
            restored == entries.size -> say(if (restored == 1) "Unhidden" else "$restored items unhidden")
            else -> say("Unhid $restored of ${entries.size}")
        }
    }

    private fun act(items: List<MediaItem>, block: suspend (List<MediaItem>) -> Unit) = viewModelScope.launch {
        val store = items.filter { it.origin == Origin.Store }
        if (store.isNotEmpty()) block(store)
    }

    private fun say(text: String) {
        _messages.tryEmit(text)
    }
}
