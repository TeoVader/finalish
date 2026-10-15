package dev.finalish.core.transfer

import androidx.compose.runtime.Immutable
import dev.finalish.core.data.CommentDraft
import dev.finalish.core.model.MediaKind
import dev.finalish.core.network.AppJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

enum class TransferState { Queued, Running, Done, Failed, Cancelled }

@Immutable
@Serializable
data class DownloadItem(
    val fileId: String,
    val accountId: String,
    val projectId: String = "",
    val name: String,
    val kind: MediaKind,
    val thumbnailPath: String? = null,
    val localPath: String? = null,
    val totalBytes: Long = 0,
    val doneBytes: Long = 0,
    val state: TransferState = TransferState.Queued,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    /** The WorkManager request now in charge of it; only that run may write its state. */
    val workId: String? = null,
) {
    val progress: Float get() = if (totalBytes > 0) (doneBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
}

@Immutable
@Serializable
data class UploadItem(
    val id: String,
    val accountId: String,
    val folderId: String,
    val folderName: String = "",
    val uri: String,
    val name: String,
    val mimeType: String,
    val totalBytes: Long,
    val doneBytes: Long = 0,
    val state: TransferState = TransferState.Queued,
    val error: String? = null,
    val remoteFileId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    /** Presigned part URLs of the file created on Frame.io, kept so an interrupted upload can resume. */
    val partUrls: List<dev.finalish.core.model.UploadUrl>? = null,
    val partsCreatedAt: Long? = null,
    val remoteMediaType: String? = null,
    /** Indexes of parts already accepted by storage. */
    val doneParts: List<Int> = emptyList(),
    /** Once uploaded, stack the file on top of this file or version stack ("As new version of…"). */
    val stackOntoId: String? = null,
    val stackOntoType: String? = null,
    /** A remote import ("Import from link"): Frame.io fetches this URL itself; nothing is sent from the phone. */
    val remoteSource: String? = null,
    /** Small preview kept when the private copy of a shared file is deleted (see TransferStore.dropStaged). */
    val thumbnailPath: String? = null,
    /** The WorkManager request now in charge of it; only that run may write its state. */
    val workId: String? = null,
) {
    val progress: Float get() = if (totalBytes > 0) (doneBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
    val resumable: Boolean get() = partUrls != null && doneParts.isNotEmpty()
}

@Immutable
@Serializable
data class PendingComment(
    val localId: String,
    val accountId: String,
    val fileId: String,
    val draft: CommentDraft,
    val createdAt: Long = System.currentTimeMillis(),
    /** When CommentSyncWorker last sent it; set before sending, so a lost answer can be detected. */
    val attemptedAt: Long? = null,
)

/** Persistent, observable state of downloads, uploads and offline comments. */
class TransferStore(root: File) {
    val offlineDir = File(root, "offline").apply { mkdirs() }
    private val downloadsFile = File(offlineDir, "downloads.json")
    private val uploadsFile = File(root, "uploads.json")
    private val pendingFile = File(root, "pending_comments.json")
    private val stagingDir = File(root, "upload-staging")

    /** Private copy of a shared file an upload reads from (see TransferManager.stage). */
    fun stagedFile(uploadId: String): File = File(stagingDir.apply { mkdirs() }, uploadId)

    fun stagedThumb(uploadId: String): File = File(stagingDir.apply { mkdirs() }, "$uploadId.jpg")

    /** Deletes a finished upload's private copy; [keepThumb] leaves its preview for Transfers. */
    fun dropStaged(uploadId: String, keepThumb: Boolean = false) {
        File(stagingDir, uploadId).delete()
        if (!keepThumb) File(stagingDir, "$uploadId.jpg").delete()
    }

    private val _downloads = MutableStateFlow(read(downloadsFile, DownloadItem.serializer()))
    val downloads: StateFlow<List<DownloadItem>> = _downloads.asStateFlow()

    private val _uploads = MutableStateFlow(
        read(uploadsFile, UploadItem.serializer()).map {
            // Process death mid-transfer: WorkManager resumes them, show as queued. Imports stay
            // Running: their watcher only follows a running import.
            if (it.state == TransferState.Running && it.remoteSource == null) it.copy(state = TransferState.Queued) else it
        },
    )
    val uploads: StateFlow<List<UploadItem>> = _uploads.asStateFlow()

    private val _pending = MutableStateFlow(read(pendingFile, PendingComment.serializer()))
    val pendingComments: StateFlow<List<PendingComment>> = _pending.asStateFlow()

    // ---- Downloads ----------------------------------------------------------

    fun download(fileId: String): DownloadItem? = _downloads.value.firstOrNull { it.fileId == fileId }

    fun upsertDownload(item: DownloadItem) = mutateDownloads { list ->
        list.filterNot { it.fileId == item.fileId } + item
    }

    fun updateDownload(fileId: String, persist: Boolean = true, block: (DownloadItem) -> DownloadItem) {
        _downloads.update { list -> list.map { if (it.fileId == fileId) block(it) else it } }
        if (persist) write(downloadsFile, DownloadItem.serializer(), _downloads)
    }

    fun removeDownload(fileId: String) {
        download(fileId)?.let { d ->
            d.localPath?.let { File(it).delete() }
            d.thumbnailPath?.let { File(it).delete() }
        }
        mutateDownloads { list -> list.filterNot { it.fileId == fileId } }
    }

    fun offlineBytes(): Long = offlineDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun mutateDownloads(block: (List<DownloadItem>) -> List<DownloadItem>) {
        _downloads.update(block)
        write(downloadsFile, DownloadItem.serializer(), _downloads)
    }

    // ---- Uploads -------------------------------------------------------------

    fun addUpload(item: UploadItem) {
        _uploads.update { it + item }
        write(uploadsFile, UploadItem.serializer(), _uploads)
    }

    fun upload(id: String): UploadItem? = _uploads.value.firstOrNull { it.id == id }

    fun updateUpload(id: String, persist: Boolean = true, block: (UploadItem) -> UploadItem) {
        _uploads.update { list -> list.map { if (it.id == id) block(it) else it } }
        if (persist) write(uploadsFile, UploadItem.serializer(), _uploads)
    }

    /** Forgets these finished, failed or cancelled uploads; active ones are kept. */
    fun clearUploads(ids: Set<String>) {
        val cleared = _uploads.value.filter { it.id in ids && it.state != TransferState.Running && it.state != TransferState.Queued }
        cleared.forEach { dropStaged(it.id) }
        _uploads.update { list -> list.filterNot { u -> cleared.any { it.id == u.id } } }
        write(uploadsFile, UploadItem.serializer(), _uploads)
    }

    // ---- Offline comments ----------------------------------------------------

    fun addPending(comment: PendingComment) {
        _pending.update { it + comment }
        write(pendingFile, PendingComment.serializer(), _pending)
    }

    /** Records a send attempt, saved before returning (call off the main thread): the send follows. */
    fun markPendingAttempted(localId: String, at: Long) {
        _pending.update { list -> list.map { if (it.localId == localId) it.copy(attemptedAt = at) else it } }
        writeNow(pendingFile, PendingComment.serializer(), _pending)
    }

    fun removePending(localId: String) {
        _pending.update { list -> list.filterNot { it.localId == localId } }
        write(pendingFile, PendingComment.serializer(), _pending)
    }

    // ---- Sign-out ------------------------------------------------------------

    /** Forgets every transfer and queued comment and deletes offline copies and staged files. */
    fun clearAll() {
        _downloads.value = emptyList()
        _uploads.value = emptyList()
        _pending.value = emptyList()
        offlineDir.listFiles()?.forEach { it.deleteRecursively() }
        stagingDir.deleteRecursively()
        write(downloadsFile, DownloadItem.serializer(), _downloads)
        write(uploadsFile, UploadItem.serializer(), _uploads)
        write(pendingFile, PendingComment.serializer(), _pending)
    }

    // ---- IO ------------------------------------------------------------------

    private fun <T> read(file: File, serializer: kotlinx.serialization.KSerializer<T>): List<T> =
        runCatching { AppJson.decodeFromString(ListSerializer(serializer), file.readText()) }.getOrDefault(emptyList())

    /** One background writer: callers (often the main thread, or a progress loop) never wait on disk. */
    private val writer = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val scheduled = java.util.concurrent.ConcurrentHashMap.newKeySet<File>()

    /** Saves [state] soon. Calls that pile up while a save is pending collapse into that one save. */
    private fun <T> write(file: File, serializer: kotlinx.serialization.KSerializer<T>, state: StateFlow<List<T>>) {
        if (!scheduled.add(file)) return
        writer.launch {
            // Cleared before reading the state, so a change made from here on schedules another save.
            scheduled.remove(file)
            writeNow(file, serializer, state)
        }
    }

    @Synchronized
    private fun <T> writeNow(file: File, serializer: kotlinx.serialization.KSerializer<T>, state: StateFlow<List<T>>) {
        // Read the state under the lock, so a slower writer can't save an older list over a newer one.
        val value = state.value
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(AppJson.encodeToString(ListSerializer(serializer), value))
            tmp.renameTo(file)
        }
    }
}
