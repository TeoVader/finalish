package dev.finalish.core.transfer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import dev.finalish.R
import dev.finalish.core.data.CommentDraft
import dev.finalish.core.data.SettingsStore
import dev.finalish.core.model.Asset
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class TransferManager(
    private val context: Context,
    private val store: TransferStore,
    private val settings: SettingsStore,
    private val scope: kotlinx.coroutines.CoroutineScope,
) {
    private val wm get() = WorkManager.getInstance(context)

    // The saved settings, not the defaults [SettingsStore.settings] starts from right after launch.
    private suspend fun constraints() = Constraints.Builder()
        .setRequiredNetworkType(if (settings.current().cellularTransfers) NetworkType.CONNECTED else NetworkType.UNMETERED)
        .build()

    /**
     * Fires when a transfer starts and notifications aren't allowed yet, so the UI can
     * ask for the permission in context (instead of on first launch).
     */
    val notificationPermissionNeeded = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var askedForNotifications = false

    private fun askForNotificationsIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT < 33 || askedForNotifications) return
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            askedForNotifications = true
            notificationPermissionNeeded.tryEmit(Unit)
        }
    }

    init {
        // Read grants are only taken for uploads (see upload); there are at most 512 per app.
        // Give each back once no listed upload still needs it: done, or cleared from Transfers.
        // Failed and cancelled ones keep theirs while listed, as they can be retried.
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            store.uploads
                .map { list -> list.filter { it.state != TransferState.Done }.map { it.uri }.toSet() }
                .distinctUntilChanged()
                .collect { releaseUnusedGrants() }
        }
    }

    private fun releaseUnusedGrants() {
        val resolver = context.contentResolver
        val held = runCatching { resolver.persistedUriPermissions }.getOrDefault(emptyList())
        // The list as it is now, read after the grants: an upload added meanwhile keeps its grant.
        val needed = store.uploads.value.filter { it.state != TransferState.Done }.map { it.uri }.toSet()
        held
            .filter { it.uri.toString() !in needed }
            .forEach { runCatching { resolver.releasePersistableUriPermission(it.uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
    }

    fun download(accountId: String, asset: Asset) {
        askForNotificationsIfNeeded()
        val media = asset.media
        val existing = store.download(media.id)
        if (existing != null && existing.state in setOf(TransferState.Done, TransferState.Running, TransferState.Queued)) return
        store.upsertDownload(
            DownloadItem(
                fileId = media.id,
                accountId = accountId,
                projectId = media.projectId,
                name = media.name,
                kind = asset.kind,
                totalBytes = media.fileSize,
                // A run still stopping (cancel, then download again) no longer owns it from here on.
                workId = PENDING_WORK,
            ),
        )
        scope.launch {
            val req = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(workDataOf(DownloadWorker.KEY_FILE to media.id, DownloadWorker.KEY_ACCOUNT to accountId))
                .setConstraints(constraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .addTag(TAG_DOWNLOAD)
                .build()
            // Cancelled while the settings loaded: nothing to start.
            if (store.download(media.id) == null) return@launch
            store.updateDownload(media.id) { it.copy(workId = req.id.toString()) }
            wm.enqueueUniqueWork("dl:${media.id}", ExistingWorkPolicy.REPLACE, req)
        }
    }

    /** "Download" on a folder: [items] into Download/Finalish/, through [FolderDownloadWorker]. */
    fun downloadFolder(accountId: String, folderName: String, items: List<PlannedDownload>) {
        if (items.isEmpty()) return
        askForNotificationsIfNeeded()
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val name = "${UUID.randomUUID()}.json"
            val job = FolderDownloadJob(accountId, folderName, items, allowMetered = settings.current().cellularTransfers)
            File(folderJobDir(context).apply { mkdirs() }, name).writeText(folderJobJson.encodeToString(FolderDownloadJob.serializer(), job))
            val req = OneTimeWorkRequestBuilder<FolderDownloadWorker>()
                .setInputData(workDataOf(FolderDownloadWorker.KEY_JOB to name, FolderDownloadWorker.KEY_TITLE to folderName))
                .setConstraints(constraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .addTag(TAG_DOWNLOAD)
                .build()
            wm.enqueueUniqueWork("fdl:$name", ExistingWorkPolicy.KEEP, req)
        }
    }

    fun cancelDownload(fileId: String) {
        wm.cancelUniqueWork("dl:$fileId")
        store.removeDownload(fileId)
        // Partial files kept for a resume (a run still stopping deletes its own, see DownloadWorker).
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            store.offlineDir.listFiles { f -> f.name.startsWith("$fileId.") && f.name.endsWith(".part") }?.forEach { it.delete() }
        }
    }

    /**
     * Uploads [uris] into [folderId]. With [stackOnto] (a single file only), the upload
     * becomes the newest version of that file or version stack once it's on Frame.io.
     */
    fun upload(accountId: String, folderId: String, folderName: String, uris: List<Uri>, stackOnto: Asset? = null) {
        askForNotificationsIfNeeded()
        uris.forEach { uri ->
            val (name, size) = describe(uri)
            val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull() ?: "application/octet-stream"
            val id = UUID.randomUUID().toString()
            // Listed before the grant is taken, so releaseUnusedGrants never sees a grant without its upload.
            store.addUpload(
                UploadItem(
                    id = id, accountId = accountId, folderId = folderId, folderName = folderName,
                    uri = uri.toString(), name = name, mimeType = mime, totalBytes = size,
                    stackOntoId = stackOnto?.takeIf { uris.size == 1 }?.id,
                    stackOntoType = stackOnto?.takeIf { uris.size == 1 }?.type,
                ),
            )
            val persisted = uri.scheme == "file" || runCatching {
                context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }.isSuccess
            if (persisted) enqueueUpload(id) else stage(id, uri)
        }
    }

    /**
     * Files shared from another app come with a read grant that ends with our activity, while
     * the upload may wait for Wi-Fi or retry much later: upload from a private copy instead.
     */
    private fun stage(id: String, uri: Uri) {
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val copy = store.stagedFile(id)
            runCatching {
                (context.contentResolver.openInputStream(uri) ?: error("Cannot read file")).use { input ->
                    copy.outputStream().use { input.copyTo(it) }
                }
            }.onSuccess {
                if (store.upload(id)?.state == TransferState.Cancelled) {
                    copy.delete()
                    return@launch
                }
                // The copy also knows the size when the sharing app didn't say.
                store.updateUpload(id) { it.copy(uri = Uri.fromFile(copy).toString(), totalBytes = copy.length()) }
                enqueueUpload(id)
            }.onFailure {
                copy.delete()
                store.updateUpload(id) { it.copy(state = TransferState.Failed, error = context.getString(R.string.core_transfer_read_failed)) }
            }
        }
    }

    fun retryUpload(id: String) = enqueueUpload(id)

    /**
     * Shows an "Import from link" in Transfers and follows it: Frame.io already accepted
     * it as [remoteFileId] and is fetching [url] itself.
     */
    fun trackImport(accountId: String, folderId: String, folderName: String, remoteFileId: String, name: String, url: String) {
        val id = UUID.randomUUID().toString()
        store.addUpload(
            UploadItem(
                id = id, accountId = accountId, folderId = folderId, folderName = folderName,
                uri = url, name = name, mimeType = "", totalBytes = 0,
                state = TransferState.Running, remoteFileId = remoteFileId, remoteSource = url,
            ),
        )
        watchImport(id)
    }

    internal fun watchImport(id: String, delayMs: Long = 0) {
        val req = OneTimeWorkRequestBuilder<ImportWatchWorker>()
            .setInputData(workDataOf(UploadWorker.KEY_UPLOAD to id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .addTag(TAG_UPLOAD)
            .build()
        wm.enqueueUniqueWork("import:$id", ExistingWorkPolicy.REPLACE, req)
    }

    fun cancelUpload(id: String) {
        val item = store.upload(id)
        // Only an upload still under way: a finished or already stopped one keeps its file.
        if (item == null || (item.state != TransferState.Queued && item.state != TransferState.Running)) return
        // Mark it first: the stopping worker keeps Cancelled and turns any other stop into Queued.
        // The half-made file goes below, so forget it too: a later Retry starts over and never
        // deletes it a second time, nor resumes into it.
        val remote = item.remoteFileId?.takeIf { item.remoteSource == null }
        store.updateUpload(id) {
            it.copy(
                state = TransferState.Cancelled,
                remoteFileId = if (remote != null) null else it.remoteFileId,
                partUrls = if (remote != null) null else it.partUrls,
                partsCreatedAt = if (remote != null) null else it.partsCreatedAt,
                doneParts = if (remote != null) emptyList() else it.doneParts,
            )
        }
        wm.cancelUniqueWork("up:$id")
        wm.cancelUniqueWork("import:$id")
        // A local upload already created on Frame.io would stay there half-uploaded: remove it.
        if (remote != null) {
            scope.launch {
                runCatching { dev.finalish.FinalishApp.container(context).api.delete(item.accountId, "files", remote) }
            }
        }
    }

    /**
     * After process death, re-starts uploads that show as queued but have no work behind them:
     * ones killed while their shared file was being copied, before any work was enqueued.
     */
    fun reconcileUploads() {
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            store.uploads.value
                .filter { it.state == TransferState.Queued && it.remoteSource == null }
                .forEach { item ->
                    val infos = runCatching { wm.getWorkInfosForUniqueWork("up:${item.id}").get() }.getOrDefault(emptyList())
                    if (infos.any { !it.state.isFinished }) return@forEach
                    val readable = item.uri.startsWith("file:") ||
                        context.contentResolver.persistedUriPermissions.any { it.uri.toString() == item.uri && it.isReadPermission }
                    if (readable) {
                        enqueueUpload(item.id)
                    } else {
                        // The copy was cut short and the sharing app's grant is gone.
                        store.dropStaged(item.id)
                        store.updateUpload(item.id) { it.copy(state = TransferState.Failed, error = context.getString(R.string.core_transfer_read_failed)) }
                    }
                }
        }
    }

    /**
     * Signing out: stops every transfer and offline-comment sync and forgets them, so the next
     * account never sends the previous one's uploads or comments, nor sees its offline files.
     */
    fun signOut() {
        wm.cancelAllWorkByTag(TAG_UPLOAD)
        wm.cancelAllWorkByTag(TAG_DOWNLOAD)
        wm.cancelUniqueWork(COMMENT_SYNC)
        // Deleting the offline copies can take a while: off the main thread.
        scope.launch(kotlinx.coroutines.Dispatchers.IO) { store.clearAll() }
    }

    private fun enqueueUpload(id: String) {
        UploadBatch.join(id, store)
        // A run still stopping (cancel, then retry) no longer owns the upload from here on.
        store.updateUpload(id) { it.copy(state = TransferState.Queued, error = null, workId = PENDING_WORK) }
        scope.launch {
            val req = OneTimeWorkRequestBuilder<UploadWorker>()
                .setInputData(workDataOf(UploadWorker.KEY_UPLOAD to id))
                .setConstraints(constraints())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .addTag(TAG_UPLOAD)
                .build()
            // Cancelled while the settings loaded: nothing to start.
            if (store.upload(id)?.state != TransferState.Queued) return@launch
            store.updateUpload(id) { it.copy(workId = req.id.toString()) }
            wm.enqueueUniqueWork("up:$id", ExistingWorkPolicy.REPLACE, req)
        }
    }

    fun queueOfflineComment(accountId: String, fileId: String, draft: CommentDraft): PendingComment {
        val p = PendingComment(UUID.randomUUID().toString(), accountId, fileId, draft)
        store.addPending(p)
        scheduleCommentSync()
        return p
    }

    fun scheduleCommentSync() {
        if (store.pendingComments.value.isEmpty()) return
        val req = OneTimeWorkRequestBuilder<CommentSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        // Append: a comment queued while a sync runs is sent by a run after it, not skipped.
        wm.enqueueUniqueWork(COMMENT_SYNC, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
    }

    /** Display name and size of a shared or picked file. */
    fun describe(uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment ?: "upload"
        var size = -1L
        // A shared file whose read grant is gone answers with a SecurityException: no crash, the
        // upload then fails with "Couldn't read the shared file" (feedback #16's crash).
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        c.getString(0)?.let { name = it }
                        if (!c.isNull(1)) size = c.getLong(1)
                    }
                }
        }
        if (size < 0) {
            // UNKNOWN_LENGTH (-1) is no size to give Frame.io.
            size = runCatching { context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull()
                ?.takeIf { it >= 0 } ?: 0L
        }
        return name to size
    }

    private companion object {
        const val TAG_DOWNLOAD = "download"
        const val TAG_UPLOAD = "upload"
        const val COMMENT_SYNC = "comment-sync"
        /** [UploadItem.workId] / [DownloadItem.workId] between queueing and enqueueing: no run owns it. */
        const val PENDING_WORK = "pending"
    }
}
