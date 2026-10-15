package dev.finalish.ui.viewer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.core.net.toUri
import androidx.lifecycle.SavedStateHandle
import dev.finalish.R
import dev.finalish.core.data.AppSettings
import dev.finalish.core.data.CommentDraft
import dev.finalish.core.model.Comment
import dev.finalish.core.model.MediaKind
import dev.finalish.core.network.userMessage
import dev.finalish.core.notify.ActivityWatch
import dev.finalish.core.transfer.TransferManager
import dev.finalish.core.transfer.TransferStore
import dev.finalish.core.util.Format
import java.io.IOException
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody

private const val DRAFT_KEY = "draft"

/** The viewer's comments: loading and live refresh, the composer, sending, editing and attachments. */
internal class ViewerComments(
    private val session: ViewerSession,
    saved: SavedStateHandle,
    private val settings: StateFlow<AppSettings>,
    private val transfers: TransferStore,
    private val transferManager: TransferManager,
    private val activityWatch: ActivityWatch,
    private val http: OkHttpClient,
) {
    private val state = session.state
    private val messages = session.messages
    private val scope = session.scope
    private val repo = session.repo
    private val player = session.player
    private val app = session.app
    private val account get() = session.account
    private var commentsJob: Job? = null

    // A new comment being written survives the app being killed in the background (not an edit).
    private val _composer = MutableStateFlow(ComposerState(text = saved.get<String>(DRAFT_KEY).orEmpty()))
    val composer: StateFlow<ComposerState> = _composer.asStateFlow()

    init {
        scope.launch {
            _composer.collect { c -> saved[DRAFT_KEY] = if (c.editing == null) c.text else "" }
        }
    }

    /** Polls comments so feedback from teammates appears live. Local optimistic comments are kept. */
    fun refresh() {
        val id = state.value.file?.id ?: return
        if (commentsJob?.isActive == true || _composer.value.sending || commentOps > 0) return
        load(id)
    }

    /** Comment changes still on their way to Frame.io (main thread only). */
    private var commentOps = 0
    private var reloadAfterOps = false

    /**
     * Runs a comment change. A refresh fetched before the change lands would put back what
     * it just changed (a deleted comment, an untick, a missing new one), so a load in flight
     * is dropped and done again once every change is in; none starts meanwhile.
     */
    private fun commentOp(block: suspend () -> Unit) {
        commentOps++
        if (commentsJob?.isActive == true) {
            commentsJob?.cancel()
            reloadAfterOps = true
        }
        scope.launch {
            try {
                block()
            } finally {
                commentOps--
                if (commentOps == 0 && reloadAfterOps) {
                    reloadAfterOps = false
                    state.value.file?.id?.let(::load)
                }
            }
        }
    }

    /** Loads [fileId]'s comments, keeping comments still on their way (queued offline or sending). */
    fun load(fileId: String) {
        commentsJob?.cancel()
        commentsJob = scope.launch {
            repo.comments(account, fileId).collect { r ->
                state.update { s ->
                    val pending = transfers.pendingComments.value.filter { it.fileId == fileId }.map { p ->
                        Comment(
                            id = p.localId, text = p.draft.text, fileId = fileId,
                            timestamp = p.draft.frame?.let { JsonPrimitive(it) }, duration = p.draft.durationFrames,
                            annotation = p.draft.annotation, owner = s.me, pending = true,
                            createdAt = Instant.ofEpochMilli(p.createdAt).toString(),
                        )
                    }
                    s.copy(
                        comments = sortComments(
                            (r.data.orEmpty() + pending + s.comments.filter { it.id.startsWith("local-") })
                                .distinctBy { it.id },
                        ),
                        commentsLoading = r.loading && r.data == null,
                    )
                }
                if (!r.loading && r.data != null) {
                    state.value.file?.takeIf { it.id == fileId }?.let { f ->
                        val all = r.data + r.data.flatMap { it.replies }
                        activityWatch.watchFile(
                            account, f.projectId, f.id, f.name,
                            all.mapNotNull { it.createdAt }.maxByOrNull { dev.finalish.core.notify.ActivityWatch.parse(it) },
                        )
                    }
                }
            }
        }
    }

    /**
     * Frame index for a comment at [ms]. Paused at the very end, the player reports
     * the full duration, which is one frame past the last one and the API rejects it.
     */
    private fun commentFrame(ms: Long, fps: Double): Int {
        val frame = Format.msToFrame(ms, fps)
        val dur = player.currentDurationMs.takeIf { it > 0 } ?: return frame
        val last = (Format.msToFrame(dur, fps) - 1).coerceAtLeast(0)
        return frame.coerceIn(0, last)
    }

    private fun sortComments(list: List<Comment>): List<Comment> =
        list.sortedWith(compareBy<Comment>({ session.commentMs(it) ?: Long.MAX_VALUE }, { it.createdAt }))

    // ---- Composer -------------------------------------------------------------

    /** A fresh composer, for a newly opened version. */
    fun resetComposer() { _composer.value = ComposerState() }

    fun setText(t: String) {
        // Typing into a still-focused field while the video plays starts a new comment too.
        if (_composer.value.text.isEmpty() && t.isNotEmpty()) onComposerFocus(true)
        _composer.update { it.copy(text = t) }
    }
    fun toggleAttachTime() = _composer.update { it.copy(attachTime = !it.attachTime, rangeStartMs = null) }
    fun toggleRange() = _composer.update {
        if (it.rangeStartMs != null) it.copy(rangeStartMs = null)
        else {
            player.pause()
            it.copy(rangeStartMs = player.currentPositionMs, attachTime = true)
        }
    }

    /**
     * Tapping the comment field: pause (default) or, if the user prefers to keep
     * watching, freeze the comment time at this moment.
     */
    fun onComposerFocus(focused: Boolean) {
        val c = _composer.value
        if (c.editing != null) return
        val kind = state.value.file?.kind
        if (kind != MediaKind.Video && kind != MediaKind.Audio) return
        if (focused) {
            if (!player.isPlaying || c.capturedMs != null) return
            if (settings.value.pauseOnComment) player.pause()
            else _composer.update { it.copy(capturedMs = player.currentPositionMs) }
        } else if (c.text.isBlank() && c.shapes.isEmpty() && c.anchor == null && c.attachments.isEmpty()) {
            // Abandoned draft: the next one takes a fresh time.
            _composer.update { it.copy(capturedMs = null) }
        }
    }

    /** Effective comment time: the captured moment, else the live playhead. */
    fun composerTimeMs(): Long = _composer.value.capturedMs ?: player.positionMs

    fun toggleDrawing() {
        player.pause()
        // Drawing pauses anyway; annotate the frame where the comment started.
        _composer.value.capturedMs?.let { player.seekTo(it); _composer.update { c -> c.copy(capturedMs = null) } }
        _composer.update { it.copy(drawing = !it.drawing, placingAnchor = false) }
        state.update { it.copy(selectedCommentId = null) }
    }

    fun setTool(t: DrawTool) = _composer.update { it.copy(tool = t) }
    fun setColor(c: Color) = _composer.update { it.copy(color = c) }
    fun addShape(s: Shape) = _composer.update { it.copy(shapes = it.shapes + s) }
    fun undoShape() = _composer.update { it.copy(shapes = it.shapes.dropLast(1)) }
    fun clearShapes() = _composer.update { it.copy(shapes = emptyList()) }

    fun togglePlacingAnchor() {
        player.pause()
        _composer.value.capturedMs?.let { player.seekTo(it); _composer.update { c -> c.copy(capturedMs = null) } }
        _composer.update { it.copy(placingAnchor = !it.placingAnchor, drawing = false) }
    }

    fun placeAnchor(o: Offset) = _composer.update { it.copy(anchor = o, placingAnchor = false) }
    fun clearAnchor() = _composer.update { it.copy(anchor = null) }

    /** Editing loads the comment's drawing and pin so they can be changed too. */
    fun startEdit(c: Comment) {
        _composer.value = ComposerState(
            text = c.text,
            editing = c,
            attachTime = false,
            shapes = AnnotationCodec.decode(c.annotation),
            anchor = c.anchor?.let { Offset(it.x, it.y) },
        )
        session.commentMs(c)?.let { player.pause(); player.seekTo(it) }
    }

    /** Moves the comment being edited to the current playhead. */
    fun retimeEditing() = _composer.update { it.copy(retime = true) }
    fun cancelEdit() { _composer.value = ComposerState() }

    fun requestComposerFocus(pause: Boolean = true) {
        if (pause) player.pause()
        _composer.update { it.copy(focusRequest = it.focusRequest + 1) }
    }

    fun send() {
        val c = _composer.value
        val file = state.value.file ?: return
        val text = c.text.trim()
        if (text.isEmpty() || c.sending) return

        c.editing?.let { editing ->
            _composer.update { it.copy(sending = true) }
            val fields = buildMap<String, JsonElement> {
                if (text != editing.text) put("text", JsonPrimitive(text))
                val newAnnotation = AnnotationCodec.encode(c.shapes)
                if (newAnnotation != editing.annotation && !(newAnnotation == null && editing.annotation.isNullOrBlank())) {
                    put("annotation", newAnnotation?.let { JsonPrimitive(it) } ?: JsonNull)
                }
                val oldAnchor = editing.anchor?.let { Offset(it.x, it.y) }
                if (c.anchor != oldAnchor) {
                    put("anchor", c.anchor?.let { a -> buildJsonObject { put("x", a.x); put("y", a.y) } } ?: JsonNull)
                }
                if (c.retime) put("timestamp", JsonPrimitive(commentFrame(player.currentPositionMs, session.fps())))
            }
            commentOp {
                if (fields.isEmpty()) { _composer.value = ComposerState(); return@commentOp }
                runCatching { repo.updateCommentFields(account, editing.id, fields) }
                    .onSuccess { updated ->
                        replaceComment(editing.id) {
                            it.copy(
                                text = updated.text, textEditedAt = updated.textEditedAt,
                                annotation = updated.annotation, anchor = updated.anchor,
                                timestamp = updated.timestamp ?: it.timestamp,
                            )
                        }
                    }
                    .onFailure { messages.emit(it.userMessage()) }
                _composer.value = ComposerState()
                persistComments()
            }
            return
        }

        val timed = file.kind == MediaKind.Video || file.kind == MediaKind.Audio
        val fps = session.fps()
        val pos = c.capturedMs ?: player.currentPositionMs
        val start = c.rangeStartMs?.let { minOf(it, pos) } ?: pos
        val end = c.rangeStartMs?.let { maxOf(it, pos) }
        val frame = if (timed && c.attachTime) commentFrame(start, fps) else null
        val draft = CommentDraft(
            text = text,
            frame = frame,
            durationFrames = end?.let { commentFrame(it, fps) - (frame ?: 0) }?.takeIf { frame != null && it > 0 },
            page = if (file.kind == MediaKind.Pdf) state.value.pdfPage else null,
            anchorX = c.anchor?.x,
            anchorY = c.anchor?.y,
            annotation = AnnotationCodec.encode(c.shapes),
        )

        val tempId = "local-" + UUID.randomUUID()
        val optimistic = Comment(
            id = tempId, text = text, fileId = file.id, timestamp = frame?.let { JsonPrimitive(it) },
            duration = draft.durationFrames, annotation = draft.annotation, owner = state.value.me,
            anchor = c.anchor?.let { dev.finalish.core.model.Anchor(it.x, it.y) },
            createdAt = Instant.now().toString(), pending = true, page = draft.page,
        )
        state.update { it.copy(comments = sortComments(it.comments + optimistic), selectedCommentId = tempId) }
        val attachments = c.attachments
        _composer.value = ComposerState(attachTime = c.attachTime)

        submitComment(tempId, file.id, draft, attachments)
    }

    /** Comments Frame.io refused, by temporary id, so "Retry" can resend them as they were. */
    private val failedDrafts = mutableMapOf<String, Pair<CommentDraft, List<PendingAttachment>>>()

    private fun submitComment(tempId: String, fileId: String, draft: CommentDraft, attachments: List<PendingAttachment>) {
        commentOp {
            runCatching { repo.createComment(account, fileId, draft) }
                .onSuccess { created ->
                    failedDrafts.remove(tempId)
                    replaceComment(tempId) { created.copy(owner = created.owner ?: state.value.me) }
                    state.update { it.copy(selectedCommentId = created.id) }
                    if (attachments.isNotEmpty()) uploadAttachments(created.id, attachments)
                }
                .onFailure { err ->
                    // The offline queue keeps only the text: a comment with attachments stays here
                    // with Retry instead, so its files aren't silently dropped.
                    if (err is IOException && attachments.isEmpty()) {
                        failedDrafts.remove(tempId)
                        val queued = transferManager.queueOfflineComment(account, fileId, draft)
                        // Becomes the queued comment, so it isn't shown twice and leaves once it's sent.
                        replaceComment(tempId) { it.copy(id = queued.localId) }
                        state.update { s -> if (s.selectedCommentId == tempId) s.copy(selectedCommentId = queued.localId) else s }
                        messages.emit(session.str(R.string.viewer_msg_offline_comment_queued))
                    } else {
                        failedDrafts[tempId] = draft to attachments
                        replaceComment(tempId) { it.copy(pending = false, failed = true) }
                        messages.emit(
                            if (err is IOException) session.str(R.string.viewer_msg_offline_comment_retry)
                            else err.userMessage(),
                        )
                    }
                }
            persistComments()
        }
    }

    fun retryComment(c: Comment) {
        val (draft, attachments) = failedDrafts[c.id] ?: return
        replaceComment(c.id) { it.copy(pending = true, failed = false) }
        submitComment(c.id, c.fileId.ifBlank { state.value.file?.id ?: return }, draft, attachments)
    }

    fun addAttachments(uris: List<android.net.Uri>) {
        val resolver = app.contentResolver
        val picked = uris.take(6).map { uri ->
            var name = uri.lastPathSegment ?: "attachment"
            var size = 0L
            resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE), null, null, null)
                ?.use { cur -> if (cur.moveToFirst()) { cur.getString(0)?.let { name = it }; if (!cur.isNull(1)) size = cur.getLong(1) } }
            PendingAttachment(uri.toString(), name, size, resolver.getType(uri) ?: "application/octet-stream")
        }
        _composer.update { it.copy(attachments = (it.attachments + picked).take(6)) }
    }

    fun removeAttachment(a: PendingAttachment) = _composer.update { it.copy(attachments = it.attachments - a) }

    /** Creates attachment records then PUTs the bytes to the presigned URLs. */
    private fun uploadAttachments(commentId: String, list: List<PendingAttachment>) {
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val resolver = app.contentResolver
            var ok = 0
            for (a in list) {
                runCatching {
                    val created = repo.createAttachment(account, commentId, a.name, a.size, a.mediaType)
                    val urls = created.uploadUrls.orEmpty()
                    val part = if (urls.isEmpty()) 0L else (a.size + urls.size - 1) / urls.size
                    urls.forEachIndexed { i, url ->
                        val start = i * part
                        val len = minOf(part, a.size - start)
                        val bytes = resolver.openInputStream(a.uri.toUri())!!.use { input ->
                            input.skip(start)
                            val buf = ByteArray(len.toInt())
                            var read = 0
                            while (read < buf.size) { val n = input.read(buf, read, buf.size - read); if (n < 0) break; read += n }
                            buf
                        }
                        val req = okhttp3.Request.Builder().url(url)
                            .put(bytes.toRequestBody(null))
                            .header("x-amz-acl", "private")
                            .header("Content-Type", a.mediaType)
                            .build()
                        http.newCall(req).execute().use { if (!it.isSuccessful) error(session.str(R.string.viewer_msg_upload_failed, it.code)) }
                    }
                    ok++
                }.onFailure { messages.emit(session.str(R.string.viewer_msg_attach_failed, a.name, it.userMessage())) }
            }
            if (ok > 0) {
                messages.emit(app.resources.getQuantityString(R.plurals.viewer_msg_attachments_uploaded, ok, ok))
                refresh()
            }
        }
    }

    fun deleteAttachment(c: Comment, attachmentId: String) {
        replaceComment(c.id) { it.copy(attachments = it.attachments?.filterNot { a -> a.id == attachmentId }) }
        scope.launch {
            runCatching { repo.deleteAttachment(account, c.id, attachmentId) }.onFailure { messages.emit(it.userMessage()); refresh() }
        }
    }

    fun toggleComplete(c: Comment) {
        val done = !c.isCompleted
        replaceComment(c.id) { it.copy(completedAt = if (done) Instant.now().toString() else null) }
        commentOp {
            runCatching { repo.updateComment(account, c.id, completed = done) }
                .onFailure {
                    replaceComment(c.id) { it.copy(completedAt = c.completedAt) }
                    messages.emit(it.userMessage())
                }
            persistComments()
        }
    }

    fun deleteComment(c: Comment) {
        state.update { s -> s.copy(comments = s.comments.filterNot { it.id == c.id }) }
        if (c.failed) {
            failedDrafts.remove(c.id)
            return
        }
        if (c.pending) {
            transfers.removePending(c.id)
            return
        }
        commentOp {
            runCatching { repo.deleteComment(account, c.id) }
                .onSuccess { messages.emit(session.str(R.string.viewer_msg_comment_deleted)) }
                .onFailure {
                    state.update { s -> s.copy(comments = sortComments(s.comments + c)) }
                    messages.emit(it.userMessage())
                }
            persistComments()
        }
    }

    private fun replaceComment(id: String, block: (Comment) -> Comment) =
        state.update { s -> s.copy(comments = sortComments(s.comments.map { if (it.id == id) block(it) else it })) }

    private suspend fun persistComments() {
        val f = state.value.file ?: return
        repo.storeComments(account, f.id, state.value.comments.filterNot { it.pending || it.failed })
    }
}
