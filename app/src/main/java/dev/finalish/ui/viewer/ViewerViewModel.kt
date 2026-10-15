package dev.finalish.ui.viewer

import android.app.Application
import android.net.ConnectivityManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.finalish.R
import dev.finalish.core.data.AppSettings
import dev.finalish.core.data.FrameioRepository
import dev.finalish.core.data.PlaybackQuality
import dev.finalish.core.data.SettingsStore
import dev.finalish.core.model.Asset
import dev.finalish.core.model.Comment
import dev.finalish.core.model.MediaKind
import dev.finalish.core.model.MetadataField
import dev.finalish.core.model.SelectOption
import dev.finalish.core.model.Share
import dev.finalish.core.model.User
import dev.finalish.core.notify.ActivityWatch
import dev.finalish.core.transfer.TransferManager
import dev.finalish.core.transfer.TransferState
import dev.finalish.core.transfer.TransferStore
import dev.finalish.ui.application
import dev.finalish.ui.container
import dev.finalish.ui.report
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * One opened file: playback, versions and the side panel's state. Comments and the composer,
 * metadata edits and file actions live in [ViewerComments], [ViewerMetadata] and
 * [ViewerFileActions]; this class hands their calls through so screens see one ViewModel.
 */
class ViewerViewModel(
    app: Application,
    private val saved: androidx.lifecycle.SavedStateHandle,
    private val repo: FrameioRepository,
    private val settingsStore: SettingsStore,
    private val transfers: TransferStore,
    private val transferManager: TransferManager,
    private val activityWatch: ActivityWatch,
    private val http: OkHttpClient,
    newPlayer: () -> PlayerController,
) : AndroidViewModel(app) {
    val player: PlayerController = newPlayer().also { p ->
        // An original that won't decode after all: fall back to the transcode once.
        p.onError = { failed ->
            val f = session.state.value.file
            val fallback = f?.takeIf { it.kind == MediaKind.Audio && failed != null && failed == it.urlOf(RenditionSource.Original) }
                ?.let { it.mediaLinks?.highQuality?.streamUrl ?: it.mediaLinks?.efficient?.streamUrl }
            if (fallback != null) {
                dev.finalish.core.util.AppLog.i("Original audio didn't decode; playing the transcode")
                p.load(fallback, autoplay = true)
                true
            } else false
        }
    }
    val scrub = ScrubPreviewer(http, app.cacheDir)

    private val session: ViewerSession = ViewerSession(app, viewModelScope, repo, player)
    private val _state = session.state
    val state: StateFlow<ViewerState> = _state.asStateFlow()
    private val _messages = session.messages
    val messages = _messages.asSharedFlow()

    val settings = settingsStore.settings
    private val renditionsProbe = Renditions(http, viewModelScope)
    private val comments = ViewerComments(session, saved, settings, transfers, transferManager, activityWatch, http)
    private val metadata = ViewerMetadata(session)
    private val actions = ViewerFileActions(session, transferManager)
    val composer: StateFlow<ComposerState> = comments.composer
    fun updateSettings(block: (AppSettings) -> AppSettings) = settingsStore.update(block)

    private val _view = MutableStateFlow(ViewOptions())
    val view = _view.asStateFlow()
    fun setView(o: ViewOptions) { _view.value = o }

    private var account: String by session::account
    private var bound = false
    /** The asset this viewer was opened on (a version stack's id for a stack). */
    val boundAssetId: String get() = session.boundId
    private var fileJob: Job? = null

    fun bind(accountId: String, assetId: String, type: String) {
        if (bound) return
        bound = true
        account = accountId
        session.boundId = assetId
        session.boundType = type
        player.setLoop(settings.value.loopPlayback)
        viewModelScope.launch { runCatching { repo.me() }.onSuccess { me -> _state.update { it.copy(me = me) } } }
        if (type == "version_stack") {
            viewModelScope.launch {
                repo.versions(accountId, assetId).collect { r ->
                    // The API lists the stack head-first and that order is user-editable
                    // (reorder), so keep it rather than sorting by date. Oldest first here.
                    val versions = r.data.orEmpty().asReversed().toList()
                    _state.update { it.copy(versions = versions, error = if (versions.isEmpty()) r.error else null) }
                    // Open the head; if that came from the cache and the fresh list has a newer head
                    // (uploaded elsewhere), follow it, unless the user already picked a version.
                    val head = versions.lastOrNull()
                    val current = _state.value.file?.id
                    if (head != null && (current == null || (!r.loading && current == autoHead && head.id != current))) {
                        autoHead = head.id
                        selectFile(head.id)
                    }
                    if (!r.loading && versions.isEmpty()) _state.update { it.copy(loading = false) }
                }
            }
        } else {
            selectFile(assetId)
        }
    }

    /**
     * Swiping to another image of the folder: the same viewer shows it, so the bars, info and
     * comments stay in place and only their content changes.
     */
    fun showSibling(asset: Asset) {
        if (asset.id == _state.value.file?.id || session.boundType != "file") return
        repo.stash(asset)
        session.boundId = asset.id
        selectFile(asset.id)
    }

    /** The version opened by itself as the stack's head, until the user picks one. */
    private var autoHead: String? = null

    fun selectVersion(asset: Asset) {
        if (asset.id == _state.value.file?.id) return
        autoHead = null
        selectFile(asset.id)
    }

    private fun selectFile(fileId: String) {
        val offline = transfers.download(fileId)?.takeIf { it.state == TransferState.Done }?.localPath
            ?.takeIf { File(it).exists() }
        _state.update {
            it.copy(
                file = it.versions.firstOrNull { v -> v.id == fileId } ?: it.file?.takeIf { f -> f.id == fileId }
                    ?: repo.stashed(fileId)?.takeIf { a -> a.isFile },
                offlinePath = offline,
                loading = true,
                error = null,
                comments = emptyList(),
                commentsLoading = true,
                selectedCommentId = null,
                preparing = false,
            )
        }
        comments.resetComposer()
        // Each version gets its own playback recoveries.
        recoveries = 0
        fileJob?.cancel()
        preparingJob?.cancel()
        metadata.load(fileId)
        fileJob = viewModelScope.launch {
            repo.file(account, fileId).collect { r ->
                r.data?.let { f ->
                    if (_state.value.file?.id != f.id) {
                        dev.finalish.core.util.AppLog.i(
                            "Viewer: ${f.kind} .${f.name.substringAfterLast('.', "")} id=${f.id} project=${f.projectId} type=${f.mediaType} size=${f.fileSize} status=${f.media.status}",
                        )
                    }
                    _state.update { it.copy(file = metadata.withMeta(f)) }
                    // The cached copy's signed links may have expired: play from the fresh one (or
                    // from the cache once the refresh failed). An offline copy plays at once.
                    if (!r.loading || _state.value.offlinePath != null) startPlayback(f)
                }
                _state.update { it.copy(loading = r.loading && r.data == null, error = if (r.data == null) r.error else null) }
            }
        }
        comments.load(fileId)
    }

    private fun startPlayback(f: Asset) {
        renditionsProbe.probe(f)
        // Scrub previews cost a small download per video: optionally only on Wi-Fi.
        if (f.kind == MediaKind.Video && (settingsStore.settings.value.scrubOnMobileData || !isMetered())) {
            viewModelScope.launch {
                // The tiny 180p rendition is the scrub source (efficient as a fallback).
                scrub.prepare(f.id, f.mediaLinks?.video180?.streamUrl ?: f.mediaLinks?.efficient?.streamUrl)
            }
        }
        if (f.kind != MediaKind.Video && f.kind != MediaKind.Audio) return
        val local = _state.value.offlinePath
        val url = local?.let { "file://$it" } ?: pickStreamUrl(f) ?: run {
            dev.finalish.core.util.AppLog.i("No playable link (${f.media.mediaType}, status=${f.media.status})")
            waitForRendition(f.id)
            return
        }
        preparingJob?.cancel()
        _state.update { it.copy(preparing = false) }
        player.load(url, autoplay = settings.value.autoplay && f.kind == MediaKind.Video)
    }

    private var preparingJob: Job? = null

    /** Polls the file until Frame.io has a playable rendition, then starts playback by itself. */
    private fun waitForRendition(fileId: String) {
        _state.update { it.copy(preparing = true) }
        if (preparingJob?.isActive == true) return
        preparingJob = viewModelScope.launch {
            while (true) {
                delay(8_000)
                val f = runCatching { repo.fileNow(account, fileId) }.getOrNull() ?: continue
                if (_state.value.file?.id != fileId) return@launch
                if (pickStreamUrl(f) != null) {
                    _state.update { it.copy(file = metadata.withMeta(f), preparing = false) }
                    startPlayback(f)
                    return@launch
                }
            }
        }
    }

    fun setQuality(q: PlaybackQuality) {
        settingsStore.update { it.copy(quality = q) }
        val f = _state.value.file ?: return
        if (_state.value.offlinePath != null) return
        val url = pickStreamUrl(f, q) ?: return
        viewModelScope.launch {
            kotlinx.coroutines.delay(50)
            player.load(url, autoplay = player.isPlaying)
        }
    }

    private fun pickStreamUrl(f: Asset, quality: PlaybackQuality = settings.value.quality): String? =
        renditionsProbe.pickStreamUrl(f, quality, ::isMetered)

    /** Renditions the API exposes for this file, with their real decoded heights. */
    val renditions: StateFlow<List<Rendition>> = renditionsProbe.list

    /** The rendition playing now (Auto resolved to the one it picked); null offline or before load. */
    fun activeRendition(): Rendition? {
        val f = _state.value.file ?: return null
        if (_state.value.offlinePath != null) return null
        val url = pickStreamUrl(f) ?: return null
        return renditions.value.firstOrNull { f.urlOf(it.source) == url }
    }

    /** Called when the decoder reports a size, so a rendition the URL didn't name gets labelled. */
    fun onPlayingHeight(nominal: Int) {
        if (nominal <= 0) return
        val active = activeRendition() ?: return
        if (active.height == null && active.source != RenditionSource.Original) renditionsProbe.setHeight(active.source, nominal)
    }

    private fun isMetered(): Boolean {
        val cm = getApplication<Application>().getSystemService(ConnectivityManager::class.java)
        return cm.isActiveNetworkMetered
    }

    fun fps(): Double = session.fps()
    fun commentMs(c: Comment): Long? = session.commentMs(c)

    // ---- UI state -----------------------------------------------------------

    fun setTab(t: PanelTab) = _state.update { it.copy(tab = t) }
    fun setFilter(f: CommentFilter) = _state.update { it.copy(filter = f) }
    fun setPdfPage(p: Int) = _state.update { it.copy(pdfPage = p) }

    fun selectComment(c: Comment?) {
        _state.update { it.copy(selectedCommentId = c?.id) }
        if (c == null) return
        session.commentMs(c)?.let {
            player.pause()
            player.seekTo(it)
        }
        c.page?.let { p -> _state.update { it.copy(pdfPage = p) } }
    }

    fun clearSelection() = _state.update { it.copy(selectedCommentId = null) }

    /**
     * Reorders a version inside its stack. [above] means "place after this one"
     * (newer), [below] means "place before this one" (older), mirroring the API.
     */
    fun reorderVersion(file: Asset, above: Asset?, below: Asset?) {
        if (session.boundType != "version_stack") return
        val list = _state.value.versions.toMutableList()
        list.remove(file)
        val target = when {
            above != null -> list.indexOf(above) + 1
            below != null -> list.indexOf(below)
            else -> return
        }.coerceIn(0, list.size)
        list.add(target, file)
        val before = _state.value.versions
        _state.update { it.copy(versions = list) }
        viewModelScope.launch {
            _messages.report({ session.str(R.string.viewer_msg_version_order_updated) }) {
                repo.reorderVersion(account, session.boundId, file.id, above = above?.id, below = below?.id)
            }
                .onSuccess { headFirst ->
                    // Trust the server's order, but keep our objects (the response has no media links).
                    val byId = list.associateBy { it.id }
                    val confirmed = headFirst.asReversed().mapNotNull { byId[it.id] }
                    if (confirmed.size == list.size) _state.update { it.copy(versions = confirmed) }
                    // "Make current version": follow the new head of the stack.
                    if (_state.value.versions.lastOrNull() == file && _state.value.file?.id != file.id) selectVersion(file)
                }
                .onFailure { _state.update { s -> s.copy(versions = before) } }
        }
    }

    val accountId: String get() = account

    // ---- Comments and composer (ViewerComments) -----------------------------

    /** Polls comments so feedback from teammates appears live. Local optimistic comments are kept. */
    fun refreshComments() = comments.refresh()
    fun setText(t: String) = comments.setText(t)
    fun toggleAttachTime() = comments.toggleAttachTime()
    fun toggleRange() = comments.toggleRange()
    fun onComposerFocus(focused: Boolean) = comments.onComposerFocus(focused)
    fun composerTimeMs(): Long = comments.composerTimeMs()
    fun toggleDrawing() = comments.toggleDrawing()
    fun setTool(t: DrawTool) = comments.setTool(t)
    fun setColor(c: Color) = comments.setColor(c)
    fun addShape(s: Shape) = comments.addShape(s)
    fun undoShape() = comments.undoShape()
    fun clearShapes() = comments.clearShapes()
    fun togglePlacingAnchor() = comments.togglePlacingAnchor()
    fun placeAnchor(o: Offset) = comments.placeAnchor(o)
    fun clearAnchor() = comments.clearAnchor()
    fun startEdit(c: Comment) = comments.startEdit(c)
    fun retimeEditing() = comments.retimeEditing()
    fun cancelEdit() = comments.cancelEdit()
    fun requestComposerFocus(pause: Boolean = true) = comments.requestComposerFocus(pause)
    fun send() = comments.send()
    fun retryComment(c: Comment) = comments.retryComment(c)
    fun addAttachments(uris: List<android.net.Uri>) = comments.addAttachments(uris)
    fun removeAttachment(a: PendingAttachment) = comments.removeAttachment(a)
    fun deleteAttachment(c: Comment, attachmentId: String) = comments.deleteAttachment(c, attachmentId)
    fun toggleComplete(c: Comment) = comments.toggleComplete(c)
    fun deleteComment(c: Comment) = comments.deleteComment(c)

    // ---- Metadata (ViewerMetadata) ------------------------------------------

    /** Project members (plus the uploader) to resolve and pick users in metadata fields. */
    val people: StateFlow<List<User>> = metadata.people
    fun loadPeople() = metadata.loadPeople()
    fun setSelect(field: MetadataField, option: SelectOption?) = metadata.setSelect(field, option)
    fun setRating(field: MetadataField, rating: Int) = metadata.setRating(field, rating)
    fun setText(field: MetadataField, text: String) = metadata.setText(field, text)
    fun setNumber(field: MetadataField, n: Double?) = metadata.setNumber(field, n)
    fun setToggle(field: MetadataField, b: Boolean) = metadata.setToggle(field, b)
    fun setMultiSelect(field: MetadataField, options: List<SelectOption>) = metadata.setMultiSelect(field, options)
    fun setUsers(field: MetadataField, ids: List<String>) = metadata.setUsers(field, ids)
    fun setDate(field: MetadataField, iso: String?) = metadata.setDate(field, iso)

    // ---- File actions (ViewerFileActions) -----------------------------------

    val projectShares: StateFlow<List<Share>?> = actions.projectShares
    /** Fires when the file is gone (deleted here): the viewer closes. */
    val closed: SharedFlow<Unit> = actions.closed
    fun downloadStill() = actions.downloadStill()
    fun loadProjectShares() = actions.loadProjectShares()
    fun addToShare(share: Share) = actions.addToShare(share)
    suspend fun projectLocation(): Triple<String, String, String>? = actions.projectLocation()
    suspend fun projectRoot(): String? = actions.projectRoot()
    fun copyTo(folderId: String, folderName: String) = actions.copyTo(folderId, folderName)
    fun duplicate() = actions.duplicate()
    fun rename(name: String) = actions.rename(name)
    fun delete() = actions.delete()
    fun moveTo(folderId: String, folderName: String) = actions.moveTo(folderId, folderName)
    fun saveToDevice() = actions.saveToDevice()
    fun openWith(activityContext: android.content.Context) = actions.openWith(activityContext)
    fun downloadForOffline() = actions.downloadForOffline()
    fun uploadNewVersion(uri: android.net.Uri) = actions.uploadNewVersion(uri)
    fun removeOffline() = actions.removeOffline()

    private var recoveries = 0
    private var lastFailure = 0

    /**
     * Called when the player reports a failure. Signed links expire and on-the-fly
     * streams can drop, so fetch fresh links and resume; after that, fall back to
     * other renditions before giving up.
     */
    fun onPlaybackFailure(count: Int) {
        if (count == lastFailure || _state.value.offlinePath != null) return
        lastFailure = count
        val f = _state.value.file ?: return
        if (recoveries >= 3) return
        val attempt = recoveries++
        val position = player.currentPositionMs
        viewModelScope.launch {
            runCatching { repo.fileNow(account, f.id) }.onSuccess { fresh ->
                // The user may have switched versions meanwhile; keep the full metadata too.
                if (_state.value.file?.id != f.id) return@onSuccess
                _state.update { s -> if (s.file?.id == f.id) s.copy(file = metadata.withMeta(fresh)) else s }
                val l = fresh.mediaLinks
                val candidates = listOfNotNull(
                    pickStreamUrl(fresh),
                    // ExoPlayer plays any original audio (mp3/wav/aac…) and mp4/mov video directly.
                    l?.original?.inlineUrl?.takeIf {
                        fresh.kind == MediaKind.Audio || fresh.mediaType?.contains("mp4") == true || fresh.mediaType?.contains("quicktime") == true
                    },
                    l?.efficient?.streamUrl,
                    l?.video180?.streamUrl,
                ).distinct()
                val url = candidates.getOrNull(attempt) ?: candidates.lastOrNull() ?: return@onSuccess
                dev.finalish.core.util.AppLog.i("Playback recovery #${attempt + 1} at ${position}ms")
                player.reload(url, position, play = true)
            }
        }
    }

    override fun onCleared() {
        scrub.close()
        player.release()
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = application
                val c = container
                ViewerViewModel(
                    app, createSavedStateHandle(), c.repository, c.settings, c.transfers, c.transferManager,
                    c.activityWatch, c.baseHttp,
                ) { PlayerController(app) }
            }
        }
    }
}
