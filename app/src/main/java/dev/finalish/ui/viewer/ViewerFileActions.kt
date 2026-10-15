package dev.finalish.ui.viewer

import androidx.core.net.toUri
import dev.finalish.R
import dev.finalish.core.model.Asset
import dev.finalish.core.network.userMessage
import dev.finalish.core.transfer.TransferManager
import dev.finalish.core.util.Format
import dev.finalish.ui.report
import java.io.File
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The viewer's file actions: stills, shares, copy and move, rename, delete, downloads and new versions. */
internal class ViewerFileActions(private val session: ViewerSession, private val transferManager: TransferManager) {
    private val state = session.state
    private val messages = session.messages
    private val scope = session.scope
    private val repo = session.repo
    private val player = session.player
    private val app = session.app
    private val account get() = session.account

    /** Saves the current frame as a full-resolution PNG in Pictures/Finalish. */
    fun downloadStill() {
        val bitmap = player.captureFrame() ?: run {
            messages.tryEmit(session.str(R.string.viewer_msg_no_frame))
            return
        }
        val f = state.value.file
        val tc = Format.timecode(player.currentPositionMs, session.fps()).replace(':', '-')
        val name = "${f?.name?.substringBeforeLast('.') ?: "frame"}_$tc.png"
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val ok = runCatching { saveImage(bitmap, name) }.isSuccess
            messages.emit(if (ok) session.str(R.string.viewer_msg_still_saved) else session.str(R.string.viewer_msg_still_failed))
        }
    }

    private fun saveImage(bitmap: android.graphics.Bitmap, name: String) {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name)
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Finalish")
            }
            val uri = app.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("insert failed")
            app.contentResolver.openOutputStream(uri)!!.use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        } else {
            val dir = File(app.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES), "Finalish").apply { mkdirs() }
            File(dir, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private val _projectShares = MutableStateFlow<List<dev.finalish.core.model.Share>?>(null)
    val projectShares = _projectShares.asStateFlow()

    private var projectSharesJob: kotlinx.coroutines.Job? = null

    /** Called each time the action sheet opens: one collector at a time, the previous one cancelled. */
    fun loadProjectShares() {
        val f = state.value.file ?: return
        projectSharesJob?.cancel()
        projectSharesJob = scope.launch {
            repo.shares(account, f.projectId).collect { r ->
                if (r.data != null) _projectShares.value = r.data else if (!r.loading) _projectShares.value = emptyList()
            }
        }
    }

    fun addToShare(share: dev.finalish.core.model.Share) {
        val a = session.browsedAsset() ?: return
        scope.launch {
            messages.report({ session.str(R.string.viewer_msg_added_to_share, share.name ?: session.str(R.string.viewer_msg_share_fallback)) }) {
                repo.addToShare(account, share.id, a.id)
            }
        }
    }

    /** Project root + parent folder, for "View in project" and "Move to…". */
    suspend fun projectLocation(): Triple<String, String, String>? = runCatching {
        val f = state.value.file ?: return null
        val project = repo.project(account, f.projectId)
        val parentId = (if (session.boundType == "version_stack") null else f.parentId)
            ?: runCatching { repo.versionStackParent(account, session.boundId) }.getOrNull()
            ?: project.rootFolderId
        val name = if (parentId == project.rootFolderId) project.name
        else runCatching { repo.folder(account, parentId).name }.getOrDefault(project.name)
        Triple(project.rootFolderId, parentId, name)
    }.getOrNull()

    suspend fun projectRoot(): String? = runCatching {
        repo.project(account, state.value.file?.projectId ?: return null).rootFolderId
    }.getOrNull()

    fun copyTo(folderId: String, folderName: String) {
        val a = session.browsedAsset() ?: return
        scope.launch {
            messages.report({ session.str(R.string.viewer_msg_copied_to, folderName) }) { repo.copy(account, a, folderId) }
        }
    }

    /** "Duplicate": a copy next to the original (a stack's copy goes beside the stack). */
    fun duplicate() {
        val a = session.browsedAsset() ?: return
        scope.launch {
            messages.report({ session.str(R.string.viewer_msg_duplicated, a.name) }) {
                val parent = if (a.type == "version_stack") repo.versionStackParent(account, a.id) else a.parentId
                repo.copy(account, a, parent ?: error(session.str(R.string.viewer_msg_no_folder_to_copy)))
            }
        }
    }

    fun rename(name: String) {
        val a = session.browsedAsset() ?: return
        val clean = name.trim().takeIf { it.isNotEmpty() && it != a.name } ?: return
        scope.launch {
            messages.report({ session.str(R.string.viewer_msg_renamed) }) { repo.rename(account, a, clean) }
                .onSuccess { state.update { s -> s.copy(file = s.file?.copy(name = clean)) } }
        }
    }

    private val _closed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Fires when the file is gone (deleted here): the viewer closes. */
    val closed = _closed.asSharedFlow()

    fun delete() {
        val a = session.browsedAsset() ?: return
        scope.launch {
            messages.report { repo.delete(account, a) }.onSuccess { _closed.emit(Unit) }
        }
    }

    fun moveTo(folderId: String, folderName: String) {
        val a = session.browsedAsset() ?: return
        scope.launch {
            messages.report({ session.str(R.string.viewer_msg_moved_to, folderName) }) { repo.move(account, a, folderId) }
        }
    }

    /** Saves the original file to Downloads/Finalish via the system download manager. */
    fun saveToDevice() {
        val f = state.value.file ?: return
        scope.launch {
            runCatching {
                dev.finalish.core.transfer.saveToDevice(app, repo, account, f)
            }.onSuccess { messages.emit(session.str(R.string.viewer_msg_downloading, f.name)) }
                .onFailure { messages.emit(it.message ?: it.userMessage()) }
        }
    }

    /** Hands the file to another app: the offline copy if there is one, else the original's link. */
    fun openWith(activityContext: android.content.Context) {
        val f = state.value.file ?: return
        val type = f.media.mediaType ?: "*/*"
        // The link may need a network call: don't keep the Activity alive across it.
        val context = activityContext.applicationContext
        scope.launch {
            runCatching {
                val uri = state.value.offlinePath?.let { File(it) }?.takeIf { it.exists() }?.let {
                    androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", it)
                } ?: (repo.fileNow(account, f.id).mediaLinks?.original?.downloadUrl ?: error(session.str(R.string.viewer_msg_downloads_disabled))).toUri()
                val view = android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(uri, type)
                    .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.startActivity(
                    android.content.Intent.createChooser(view, session.str(R.string.viewer_open_file_with, f.name))
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure { messages.emit(it.message ?: it.userMessage()) }
        }
    }

    fun downloadForOffline() {
        val f = state.value.file ?: return
        transferManager.download(account, f)
        scope.launch { messages.emit(session.str(R.string.viewer_msg_saving_offline, f.name)) }
    }

    /** "+ New version": upload [uri] next to this file and stack it on top (of the stack, or of this file). */
    fun uploadNewVersion(uri: android.net.Uri) {
        val f = state.value.file ?: return
        scope.launch {
            val (_, folderId, folderName) = projectLocation() ?: return@launch messages.emit(session.str(R.string.viewer_msg_folder_not_found))
            val target = if (session.boundType == "version_stack") Asset(id = session.boundId, type = "version_stack", name = f.name) else f
            transferManager.upload(account, folderId, folderName, listOf(uri), stackOnto = target)
            messages.emit(session.str(R.string.viewer_msg_uploading_version))
        }
    }

    fun removeOffline() {
        val f = state.value.file ?: return
        transferManager.cancelDownload(f.id)
        state.update { it.copy(offlinePath = null) }
    }
}
