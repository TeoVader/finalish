package dev.finalish.ui.browser

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.finalish.core.data.FrameioRepository
import dev.finalish.core.data.SettingsStore
import dev.finalish.core.transfer.TransferManager
import dev.finalish.core.transfer.TransferStore
import dev.finalish.ui.application
import dev.finalish.ui.container
import dev.finalish.ui.messageFlow
import dev.finalish.ui.report
import dev.finalish.R
import dev.finalish.core.data.BrowseLayout
import dev.finalish.core.data.Remote
import dev.finalish.core.data.SortOrder
import dev.finalish.core.model.Asset
import dev.finalish.core.network.userMessage
import dev.finalish.core.transfer.TransferState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class FolderViewModel(
    app: Application,
    private val saved: SavedStateHandle,
    private val repo: FrameioRepository,
    private val settingsStore: SettingsStore,
    private val transfers: TransferStore,
    private val transferManager: TransferManager,
) : AndroidViewModel(app) {
    private val res get() = getApplication<Application>().resources
    private fun str(@StringRes id: Int) = res.getString(id)
    private fun plural(id: Int, n: Int) = res.getQuantityString(id, n, n)

    private val _remote = MutableStateFlow<Remote<List<Asset>>>(Remote.loading())
    val remote: StateFlow<Remote<List<Asset>>> = _remote.asStateFlow()

    private val _selection = MutableStateFlow<Set<String>>(emptySet())
    val selection = _selection.asStateFlow()

    // The search field, type chip and field filters survive the app being killed in the background.
    private val _filter = MutableStateFlow(saved.get<String>("filter").orEmpty())
    val filter = _filter.asStateFlow()

    private val _messages = messageFlow()
    val messages = _messages.asSharedFlow()

    val settings = settingsStore.settings

    val layout: StateFlow<BrowseLayout> = settings.map { it.layout }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), settings.value.layout)

    val sort: StateFlow<SortOrder> = settings.map { it.sort }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), settings.value.sort)

    val downloads = transfers.downloads

    private var account = ""
    private var folderId = ""
    private var job: Job? = null

    private val _kind = MutableStateFlow(saved.get<String>("kind")?.let { n -> KindFilter.entries.find { it.name == n } } ?: KindFilter.All)
    val kind = _kind.asStateFlow()
    fun setKind(k: KindFilter) { _kind.value = if (_kind.value == k) KindFilter.All else k }

    private val _filters = MutableStateFlow(
        saved.get<ArrayList<String>>("fieldFilters").orEmpty().mapNotNull { e ->
            e.split('\u0000').takeIf { it.size == 3 }?.let { (f, op, v) -> FilterOp.entries.find { it.name == op }?.let { FieldFilter(f, it, v) } }
        },
    )

    init {
        viewModelScope.launch { _filter.collect { saved["filter"] = it } }
        viewModelScope.launch { _kind.collect { saved["kind"] = it.name } }
        viewModelScope.launch {
            _filters.collect { list -> saved["fieldFilters"] = ArrayList(list.map { "${it.field}\u0000${it.op.name}\u0000${it.value}" }) }
        }
    }
    /** Field filters ("Bitrate greater than 20"); for this visit only, like the official Filters. */
    val filters = _filters.asStateFlow()
    fun addFilter(f: FieldFilter) { _filters.update { it + f } }
    fun removeFilter(f: FieldFilter) { _filters.update { it - f } }
    fun clearFilters() { _filters.value = emptyList() }

    /** Subfolders' files for "Flatten folders" (null until loaded or when off). */
    private val _flat = MutableStateFlow<List<Asset>?>(null)
    private var flatJob: Job? = null
    val flattening: StateFlow<Boolean> = combine(settings, _flat) { s, f -> s.flattenFolders && f == null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

    /** Fields present on the folder's files, for the Filters / Sort / card-fields pickers. */
    val fields: StateFlow<List<FieldInfo>> = combine(_remote, _flat) { r, flat -> fieldsIn(flat ?: r.data.orEmpty()) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    /** Visible items: folders first, filtered by the search field, the type chips and the field filters. */
    private fun arrange(all: List<Asset>, f: String, k: KindFilter, filters: List<FieldFilter>, s: dev.finalish.core.data.AppSettings): List<Asset> {
        val q = f.trim()
        val filtered = (if (q.isBlank()) all else all.filter { it.name.contains(q, ignoreCase = true) })
            .filter { k == KindFilter.All || (!it.isFolder && k.matches(it)) }
            .filter { a -> filters.all { it.matches(a) } }
        val files = s.sortField?.let { fieldComparator(it, s.sortReversed) }
            ?: localOrder(s.sort).let { if (s.sortReversed) it.reversed() else it }
        return filtered.sortedWith(
            compareBy<Asset> { if (it.isFolder) 0 else 1 }
                .then { a, b -> if (a.isFolder && b.isFolder) a.name.compareTo(b.name, ignoreCase = true) else files.compare(a, b) },
        )
    }

    private val _items = MutableStateFlow<List<Asset>>(emptyList())
    val items: StateFlow<List<Asset>> = _items.asStateFlow()

    init {
        viewModelScope.launch {
            combine(combine(_remote, _flat) { r, flat -> flat ?: r.data.orEmpty() }, _filter, _kind, _filters, settings) { all, f, k, fl, s ->
                arrange(if (s.flattenFolders) all.filterNot { it.isFolder } else all, f, k, fl, s)
            }.flowOn(Dispatchers.Default).collect { _items.value = it }
        }
        viewModelScope.launch {
            // Flatten on/off: load (or drop) the subfolders' files.
            settings.map { it.flattenFolders }.distinctUntilChanged().collect { on -> if (on) loadFlat() else { flatJob?.cancel(); _flat.value = null } }
        }
    }

    private fun loadFlat() {
        if (account.isBlank()) return
        flatJob?.cancel()
        flatJob = viewModelScope.launch {
            runCatching { repo.descendantFiles(account, folderId, settings.value.sort) }
                .onSuccess { _flat.value = it }
                .onFailure {
                    // A newer load replacing this one isn't a failure.
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    _messages.emit(it.userMessage()); _flat.value = emptyList()
                }
        }
    }

    /** Each order's natural direction: A→Z, newest first, biggest first. Reversed by the direction switch. */
    private fun localOrder(order: SortOrder): Comparator<Asset> = when (order) {
        SortOrder.NameAsc -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        SortOrder.UpdatedDesc -> compareByDescending { it.updatedAt.orEmpty() }
        SortOrder.CreatedDesc -> compareByDescending { it.createdAt.orEmpty() }
        SortOrder.Type -> compareBy { it.kind.ordinal }
        SortOrder.SizeDesc -> compareByDescending { it.media.fileSize }
        // Files with a status first, grouped by it; unlabelled ones after.
        SortOrder.Status -> compareBy<Asset> { it.statusField?.textValue == null }.thenBy { it.statusField?.textValue.orEmpty() }
    }

    /** How many files fall in each chip (chips with zero are hidden). */
    val kindCounts: StateFlow<Map<KindFilter, Int>> = combine(_remote, _flat) { r, flat ->
        val all = flat ?: r.data.orEmpty()
        val files = all.filterNot { it.isFolder }
        KindFilter.entries.associateWith { k -> if (k == KindFilter.All) all.size else files.count(k::matches) }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyMap())

    /** Uploads into this folder that are still in flight. */
    val activeUploads = transfers.uploads.map { list ->
        // Imports from a link have no byte progress here; their card shows them as processing.
        list.filter { it.folderId == folderId && it.remoteSource == null && (it.state == TransferState.Running || it.state == TransferState.Queued) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    /** Whether the upload strip shows at all; its progress is collected by the strip alone. */
    val hasActiveUploads: StateFlow<Boolean> = activeUploads.map { it.isNotEmpty() }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

    fun bind(accountId: String, folder: String) {
        if (accountId == account && folder == folderId) return
        account = accountId
        folderId = folder
        if (settings.value.flattenFolders) loadFlat()
        // Cached children go straight into the first frame, so the folder slides in already filled.
        repo.cachedChildren(accountId, folder, settings.value.sort)?.let {
            _remote.value = Remote.loading(it)
            _items.value = arrange(it, _filter.value, _kind.value, _filters.value, settings.value)
        }
        refresh()
        viewModelScope.launch {
            // Refresh after an upload into this folder completes: only a new Done counts, so a
            // failed or cancelled one next to an older finished upload doesn't reload the folder.
            var done = -1
            transfers.uploads.collect { list ->
                val now = list.count { it.folderId == folder && it.state == TransferState.Done }
                if (done in 0 until now) refresh()
                done = now
            }
        }
    }

    /** True only for refreshes the user asked for (pull-to-refresh), never for background polling. */
    private val _userRefreshing = MutableStateFlow(false)
    val userRefreshing = _userRefreshing.asStateFlow()

    /** Poll faster while files are still uploading or transcoding. */
    val hasProcessing: Boolean
        get() = current().any { it.isProcessing }

    /** What the grid shows: every file below this folder with "Flatten folders" on, else its children. */
    private fun current(): List<Asset> = _flat.value ?: _remote.value.data.orEmpty()

    /** [force]: after an edit, replace a load already in flight (it may predate the change). */
    fun refresh(order: SortOrder = settings.value.sort, user: Boolean = false, force: Boolean = false) {
        if (account.isBlank()) return
        if (user) {
            _userRefreshing.value = true
            reloadPreviews()
            if (settings.value.flattenFolders) loadFlat()
        }
        if (!user && !force && job?.isActive == true) return // a load is already in flight
        job?.cancel()
        // Background polls over a folder already on screen apply only the final result: the cached
        // and partial (first page) emissions in between would redraw the whole grid for nothing.
        val quiet = !user && _remote.value.data != null
        job = viewModelScope.launch {
            repo.children(account, folderId, order).collect { r ->
                if (quiet && r.loading) return@collect
                val shown = _remote.value.data
                // Keep current items visible while revalidating after an edit.
                _remote.value = if (r.data == null && r.error == null) r.copy(data = shown)
                else r.copy(data = r.data?.let { reuseUnchanged(shown, it, takeFreshLinks = !r.loading && urlsAreOld()) })
                if (!r.loading) _userRefreshing.value = false
            }
        }
    }

    /** When the cards' signed links were last taken from the server (0: never, e.g. only the disk cache). */
    private var linksFetchedAt = 0L

    private fun urlsAreOld(): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        if (linksFetchedAt != 0L && now - linksFetchedAt < LINKS_MAX_AGE_MS) return false
        linksFetchedAt = now
        return true
    }

    /**
     * Keeps the instance already on screen for every asset that hasn't changed, so its card isn't
     * recomposed. Signed links rotate their query string on every load: those are ignored unless
     * [takeFreshLinks] (the shown ones are getting old).
     */
    private fun reuseUnchanged(shown: List<Asset>?, fresh: List<Asset>, takeFreshLinks: Boolean): List<Asset> {
        if (shown.isNullOrEmpty()) return fresh
        val byId = shown.associateBy { it.id }
        return fresh.map { n ->
            val o = byId[n.id]
            if (o != null && (o == n || (!takeFreshLinks && o.unsigned() == n.unsigned()))) o else n
        }
    }

    private val _previews = MutableStateFlow<Map<String, dev.finalish.core.model.FolderPreview>>(emptyMap())
    val previews = _previews.asStateFlow()
    private val requestedPreviews = mutableSetOf<String>()

    /** Re-fetches folder previews, bypassing the cache (all folders when [ids] is null). */
    private fun reloadPreviews(ids: Set<String>? = null) {
        val folders = _remote.value.data.orEmpty().filter { it.isFolder && (ids == null || it.id in ids) }
        folders.forEach { folder ->
            viewModelScope.launch {
                repo.folderPreview(account, folder, force = true)?.let { p -> _previews.update { it + (folder.id to p) } }
            }
        }
    }

    /** Copies/moves finish asynchronously on the server; refresh the target's card a little later. */
    private fun reloadPreviewSoon(folderId: String) = viewModelScope.launch {
        for (wait in listOf(2_500L, 10_000L)) {
            kotlinx.coroutines.delay(wait)
            reloadPreviews(setOf(folderId))
        }
    }

    fun toast(message: String) { _messages.tryEmit(message) }

    private val _versionCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val versionCounts = _versionCounts.asStateFlow()
    private val requestedCounts = mutableSetOf<String>()

    /** Loads a stack's version count for its "V3" badge, once per stack and change. */
    fun requestVersionCount(stack: Asset) {
        if (!requestedCounts.add("${stack.id}:${stack.updatedAt}")) return
        viewModelScope.launch {
            repo.versionCount(account, stack)?.let { n -> _versionCounts.update { it + (stack.id to n) } }
        }
    }

    fun requestPreview(folder: Asset) {
        if (!requestedPreviews.add(folder.id)) return
        viewModelScope.launch {
            repo.folderPreview(account, folder)?.let { p -> _previews.update { it + (folder.id to p) } }
        }
    }

    private val _shares = MutableStateFlow<List<dev.finalish.core.model.Share>?>(null)
    val shares = _shares.asStateFlow()
    private var sharesJob: Job? = null

    /** Project-level share links, shown at the project root. */
    fun loadShares(projectId: String) {
        if (sharesJob?.isActive == true) return
        // Cached links first, so the section doesn't arrive late and push the folder down.
        if (_shares.value == null) repo.cachedShares(account, projectId)?.let { _shares.value = it }
        sharesJob = viewModelScope.launch {
            repo.shares(account, projectId).collect { r ->
                if (r.data != null) _shares.value = r.data else if (!r.loading) _shares.value = emptyList()
            }
        }
    }

    fun replaceShare(s: dev.finalish.core.model.Share) =
        _shares.update { list -> list?.map { if (it.id == s.id) s else it } }

    fun updateSettings(block: (dev.finalish.core.data.AppSettings) -> dev.finalish.core.data.AppSettings) =
        settingsStore.update(block)

    fun setLayout(l: BrowseLayout) = settingsStore.update { it.copy(layout = l) }

    fun setSort(s: SortOrder) {
        settingsStore.update { it.copy(sort = s) }
        refresh(s)
    }

    fun setFilter(q: String) {
        _filter.value = q
    }

    fun toggleSelect(id: String) = _selection.update { if (id in it) it - id else it + id }
    fun clearSelection() { _selection.value = emptySet() }
    fun selectAll() { _selection.value = items.value.map { it.id }.toSet() }

    fun selected(): List<Asset> = current().filter { it.id in _selection.value }

    fun createFolder(name: String) = op(str(R.string.browser_msg_folder_created)) { repo.createFolder(account, folderId, name) }

    fun rename(asset: Asset, name: String) = op(str(R.string.browser_msg_renamed)) { repo.rename(account, asset, name) }

    fun delete(assets: List<Asset>) {
        // Optimistic removal keeps the grid responsive.
        val ids = assets.map { it.id }.toSet()
        _remote.update { r -> r.copy(data = r.data?.filterNot { it.id in ids }) }
        _flat.update { list -> list?.filterNot { it.id in ids } }
        op(if (assets.size == 1) str(R.string.browser_msg_deleted) else plural(R.plurals.browser_msg_deleted_many, assets.size)) {
            assets.forEach { repo.delete(account, it) }
        }
    }

    fun move(assets: List<Asset>, destination: String) {
        val ids = assets.map { it.id }.toSet()
        _remote.update { r -> r.copy(data = r.data?.filterNot { it.id in ids }) }
        _flat.update { list -> list?.filterNot { it.id in ids } }
        op(plural(R.plurals.browser_msg_moved, assets.size)) {
            repo.moveAssets(account, assets, destination)
            reloadPreviewSoon(destination)
        }
    }

    fun copy(assets: List<Asset>, destination: String) =
        op(plural(R.plurals.browser_msg_copied, assets.size)) {
            repo.copyAssets(account, assets, destination)
            reloadPreviewSoon(destination)
        }

    /** Imports a file from a public URL straight into this folder (server-side transfer). */
    fun importFromUrl(url: String, name: String, folderName: String) = op(str(R.string.browser_msg_importing)) {
        val file = repo.importFromUrl(account, folderId, name, url)
        transferManager.trackImport(account, folderId, folderName, file.id, file.name.ifBlank { name }, url)
    }

    fun stackAsVersions(assets: List<Asset>) {
        val files = assets.filter { it.isFile }.sortedBy { it.createdAt }
        val stack = assets.singleOrNull { it.isVersionStack }
        if (stack != null) {
            op(str(R.string.browser_msg_added_to_stack)) { repo.addToStack(account, stack.id, files) }
        } else {
            op(str(R.string.browser_msg_stack_created)) { repo.stackVersions(account, folderId, files.map { it.id }) }
        }
    }

    fun download(assets: List<Asset>) {
        assets.filterNot { it.isFolder }.forEach { transferManager.download(account, it) }
        _messages.tryEmit(plural(R.plurals.browser_msg_saving_offline, assets.size))
        clearSelection()
    }

    /** "Download": the original into Download/Finalish, as in the viewer's menu. */
    fun saveToDevice(asset: Asset) {
        viewModelScope.launch {
            dev.finalish.core.util.runCatchingCancellable { dev.finalish.core.transfer.saveToDevice(getApplication(), repo, account, asset) }
                .onSuccess { _messages.emit(res.getString(R.string.viewer_msg_downloading, asset.name)) }
                .onFailure { _messages.emit(it.message ?: it.userMessage()) }
        }
    }

    private val _folderDownload = MutableStateFlow<FolderDownloadUi?>(null)
    /** "Download" on a folder: walking it, then waiting for the confirmation. */
    val folderDownload = _folderDownload.asStateFlow()
    private var folderScan: Job? = null

    fun downloadFolder(folder: Asset) {
        folderScan?.cancel()
        _folderDownload.value = FolderDownloadUi.Scanning(folder.name)
        folderScan = viewModelScope.launch {
            dev.finalish.core.util.runCatchingCancellable {
                dev.finalish.core.transfer.scanFolder(repo, account, folder) { n ->
                    _folderDownload.update { (it as? FolderDownloadUi.Scanning)?.copy(found = n) ?: it }
                }
            }.onSuccess { tree ->
                val ready = FolderDownloadUi.Ready(folder.name, tree)
                if (ready.summary.files == 0) {
                    _folderDownload.value = null
                    _messages.emit(res.getString(R.string.browser_folder_download_empty, folder.name))
                } else {
                    _folderDownload.value = ready
                }
            }.onFailure {
                _folderDownload.value = null
                _messages.emit(it.userMessage())
            }
        }
    }

    fun setFolderDownloadAllVersions(all: Boolean) =
        _folderDownload.update { (it as? FolderDownloadUi.Ready)?.copy(allVersions = all) ?: it }

    fun cancelFolderDownload() {
        folderScan?.cancel()
        _folderDownload.value = null
    }

    fun confirmFolderDownload() {
        val ready = _folderDownload.value as? FolderDownloadUi.Ready ?: return
        _folderDownload.value = null
        val items = dev.finalish.core.transfer.FolderDownloadPlan.plan(ready.tree, ready.allVersions)
        transferManager.downloadFolder(account, ready.folderName, items)
        _messages.tryEmit(plural(R.plurals.browser_folder_download_started, items.size))
    }

    private val _folderUpload = MutableStateFlow<FolderUploadUi?>(null)
    /** "Upload folder": reading the picked folder, then waiting for the confirmation. */
    val folderUpload = _folderUpload.asStateFlow()
    private var localScan: Job? = null

    fun pickUploadFolder(treeUri: Uri) {
        if (!transferManager.holdFolder(treeUri)) {
            _messages.tryEmit(str(R.string.core_transfer_read_failed))
            return
        }
        localScan?.cancel()
        _folderUpload.value = FolderUploadUi.Scanning(treeUri)
        localScan = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            dev.finalish.core.util.runCatchingCancellable {
                dev.finalish.core.transfer.scanLocalFolder(getApplication(), treeUri) { n ->
                    _folderUpload.update { (it as? FolderUploadUi.Scanning)?.copy(found = n) ?: it }
                }
            }.onSuccess { root ->
                val ready = FolderUploadUi.Ready(treeUri, root)
                if (ready.summary.files == 0) {
                    _folderUpload.value = null
                    transferManager.releaseFolder(treeUri)
                    _messages.emit(res.getString(R.string.browser_folder_upload_empty, root.name))
                } else {
                    _folderUpload.value = ready
                }
            }.onFailure {
                _folderUpload.value = null
                transferManager.releaseFolder(treeUri)
                _messages.emit(it.userMessage())
            }
        }
    }

    fun cancelFolderUpload() {
        localScan?.cancel()
        _folderUpload.value?.let { transferManager.releaseFolder(it.treeUri) }
        _folderUpload.value = null
    }

    fun confirmFolderUpload() {
        val ready = _folderUpload.value as? FolderUploadUi.Ready ?: return
        _folderUpload.value = null
        transferManager.uploadFolder(account, folderId, ready.treeUri, dev.finalish.core.transfer.FolderUploadPlan.plan(ready.root))
        _messages.tryEmit(plural(R.plurals.browser_uploading_files, ready.summary.files))
    }

    fun upload(uris: List<Uri>, folderName: String) {
        if (uris.isEmpty()) return
        transferManager.upload(account, folderId, folderName, uris)
        _messages.tryEmit(plural(R.plurals.browser_uploading_files, uris.size))
    }

    /** Runs a change, reports it, then reloads the folder (and its flattened files) from the server. */
    private fun op(success: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            _messages.report({ success }) { block() }
            clearSelection()
            repo.invalidateFolder(account, folderId)
            refresh(force = true)
            if (settings.value.flattenFolders) loadFlat()
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val c = container
                FolderViewModel(application, createSavedStateHandle(), c.repository, c.settings, c.transfers, c.transferManager)
            }
        }
    }
}

/** Quick type chips above the folder content. */
enum class KindFilter(@StringRes val labelRes: Int) {
    All(R.string.browser_kind_all), Video(R.string.browser_kind_video), Images(R.string.browser_kind_images),
    Audio(R.string.browser_kind_audio), Documents(R.string.browser_kind_docs), Review(R.string.browser_kind_review);

    fun matches(a: Asset): Boolean = when (this) {
        All -> true
        Video -> a.kind == dev.finalish.core.model.MediaKind.Video
        Images -> a.kind == dev.finalish.core.model.MediaKind.Image
        Audio -> a.kind == dev.finalish.core.model.MediaKind.Audio
        Documents -> a.kind == dev.finalish.core.model.MediaKind.Pdf || a.kind == dev.finalish.core.model.MediaKind.Other
        Review -> a.statusField?.textValue.orEmpty().lowercase().let { "review" in it || "await" in it || "pending" in it }
    }
}

/** Derived flows keep running this long after the screen stops watching (rotation, a quick back-and-forth). */
private const val STOP_TIMEOUT_MS = 5_000L

/** Background refreshes keep the cards' signed links this long before taking the server's newer ones. */
private const val LINKS_MAX_AGE_MS = 30 * 60_000L

private fun String.unsigned(): String = substringBefore('?')
private fun dev.finalish.core.model.MediaLink.unsigned() =
    copy(url = url?.unsigned(), downloadUrl = downloadUrl?.unsigned(), inlineUrl = inlineUrl?.unsigned())

/** The asset with the rotating part of its signed links (the query string) dropped, for comparisons. */
private fun Asset.unsigned(): Asset = copy(
    mediaLinks = mediaLinks?.let { l ->
        l.copy(
            original = l.original?.unsigned(), thumbnail = l.thumbnail?.unsigned(), thumbnailHq = l.thumbnailHq?.unsigned(),
            video180 = l.video180?.unsigned(), highQuality = l.highQuality?.unsigned(), efficient = l.efficient?.unsigned(),
            scrubSheet = l.scrubSheet?.unsigned(),
        )
    },
    creator = creator?.let { it.copy(avatarUrl = it.avatarUrl?.unsigned()) },
    headVersion = headVersion?.unsigned(),
)
