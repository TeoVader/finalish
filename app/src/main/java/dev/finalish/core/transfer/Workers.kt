package dev.finalish.core.transfer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.graphics.scale
import androidx.core.net.toUri
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dev.finalish.FinalishApp
import dev.finalish.R
import dev.finalish.core.model.Asset
import dev.finalish.core.model.MediaKind
import dev.finalish.core.network.userMessage
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink

internal object TransferNotifications {
    const val CHANNEL = "transfers"
    private const val CANCEL_ALL_REQUEST = 0x0F1B

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.channel_transfers), NotificationManager.IMPORTANCE_LOW)
                    .apply { description = context.getString(R.string.channel_transfers_desc) },
            )
        }
    }

    /**
     * Ongoing transfer notification: name, destination, percentage, bytes, speed and
     * time left, with a Cancel action; tapping it opens the app.
     */
    fun foreground(
        context: Context,
        id: Int,
        title: String,
        progress: Float?,
        upload: Boolean = true,
        detail: String? = null,
        destination: String? = null,
        workId: java.util.UUID? = null,
        cancelAll: Boolean = false,
    ): ForegroundInfo {
        ensureChannel(context)
        val percent = progress?.let { (it * 100).toInt().coerceIn(0, 100) }
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            android.app.PendingIntent.getActivity(
                context, 0, it,
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
        val text = detail ?: context.getString(if (upload) R.string.core_transfer_preparing_upload else R.string.core_transfer_preparing_download)
        val sub = listOfNotNull(
            percent?.let { "$it%" },
            destination?.let { context.getString(if (upload) R.string.core_transfer_to_destination else R.string.core_transfer_from_source, it) },
        ).joinToString(" · ").ifEmpty { null }
        val b = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(if (upload) android.R.drawable.stat_sys_upload else android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(sub)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .setProgress(100, percent ?: 0, percent == null)
        if (workId != null) {
            b.addAction(
                android.R.drawable.ic_menu_close_clear_cancel, context.getString(R.string.core_transfer_cancel),
                androidx.work.WorkManager.getInstance(context).createCancelPendingIntent(workId),
            )
        } else if (cancelAll) {
            val i = android.content.Intent(context, dev.finalish.core.notify.NotificationActionReceiver::class.java)
                .setAction(dev.finalish.core.notify.NotificationActionReceiver.CANCEL_UPLOADS)
            b.addAction(
                android.R.drawable.ic_menu_close_clear_cancel, context.getString(R.string.core_transfer_cancel_all),
                android.app.PendingIntent.getBroadcast(
                    context, CANCEL_ALL_REQUEST, i,
                    android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        }
        val n = b.build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(id, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id, n)
        }
    }
}

/**
 * Uploads started together share one notification ("Uploading 3 files"), with the
 * batch's combined progress and speed. A batch ends when none of its files is active.
 */
internal object UploadBatch {
    const val NOTIFICATION_ID = 0x0F1A
    private val ids = LinkedHashSet<String>()
    private val meter = RateMeter()

    data class Summary(val count: Int, val active: Int, val done: Long, val total: Long, val title: String, val destination: String?)

    @Synchronized fun join(id: String, store: TransferStore) {
        if (ids.none { store.upload(it)?.state.isActive() }) {
            ids.clear()
            meter.reset()
        }
        ids += id
    }

    @Synchronized fun summary(store: TransferStore, context: Context? = null): Summary? = summarize(ids.mapNotNull { store.upload(it) }, context)

    /** The batch's title is in English without a [context] (tests), in the app's language with one. */
    internal fun summarize(items: List<UploadItem>, context: Context? = null): Summary? {
        val batch = items.filter { it.state != TransferState.Cancelled }
        if (batch.isEmpty()) return null
        val active = batch.count { it.state.isActive() }
        val folders = batch.map { it.folderName }.filter { it.isNotBlank() }.distinct()
        val title = when {
            batch.size == 1 -> batch[0].name
            context != null -> context.resources.getQuantityString(R.plurals.core_transfer_uploading_files, batch.size, batch.size)
            else -> "Uploading ${batch.size} files"
        }
        return Summary(
            count = batch.size,
            active = active,
            done = batch.sumOf { if (it.state == TransferState.Done) it.totalBytes else it.doneBytes },
            total = batch.sumOf { it.totalBytes },
            title = when {
                batch.size <= 1 || active >= batch.size -> title
                context != null -> context.resources.getQuantityString(R.plurals.core_transfer_batch_done, batch.size - active, title, batch.size - active)
                else -> "$title · ${batch.size - active} done"
            },
            destination = folders.singleOrNull(),
        )
    }

    @Synchronized fun notification(context: Context, store: TransferStore, workId: java.util.UUID): ForegroundInfo {
        val s = summary(store, context)
            ?: return TransferNotifications.foreground(context, NOTIFICATION_ID, context.getString(R.string.core_transfer_uploading_to_frameio), null)
        val progress = if (s.total > 0 && s.done > 0) s.done / s.total.toFloat() else null
        if (progress != null) meter.sample(s.done)
        return TransferNotifications.foreground(
            context, NOTIFICATION_ID, s.title, progress,
            detail = if (progress != null) transferLabel(context, s.done, s.total, meter.bytesPerSecond) else null,
            destination = s.destination,
            workId = workId.takeIf { s.count == 1 },
            cancelAll = s.count > 1,
        )
    }

    private fun TransferState?.isActive() = this == TransferState.Queued || this == TransferState.Running
}

/** Downloads a rendition for offline review, plus its metadata and comments. */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val c = FinalishApp.container(context)

    /** Like UploadWorker.updateOwn: a stopped run must not overwrite a newer run's state. */
    private fun TransferStore.updateOwn(fileId: String, persist: Boolean = true, block: (DownloadItem) -> DownloadItem) =
        updateDownload(fileId, persist) { if (owns(it)) block(it) else it }

    private fun owns(item: DownloadItem) = item.workId == null || item.workId == id.toString()

    override suspend fun getForegroundInfo(): ForegroundInfo =
        TransferNotifications.foreground(applicationContext, id.hashCode(), applicationContext.getString(R.string.core_transfer_downloading_offline), null)

    override suspend fun doWork(): Result {
        val fileId = inputData.getString(KEY_FILE) ?: return Result.failure()
        val account = inputData.getString(KEY_ACCOUNT) ?: return Result.failure()
        val store = c.transfers
        val item = store.download(fileId) ?: return Result.failure()
        // Superseded by a newer run of the same download: leave it to that one.
        if (!owns(item)) return Result.success()
        runCatching { setForeground(TransferNotifications.foreground(applicationContext, id.hashCode(), item.name, null, upload = false, workId = id)) }

        return try {
            store.updateOwn(fileId) { it.copy(state = TransferState.Running, error = null) }
            val asset = c.repository.fileNow(account, fileId)
            val url = pickUrl(asset) ?: error(applicationContext.getString(R.string.core_transfer_no_rendition))
            val ext = asset.name.substringAfterLast('.', "").takeIf { it.length in 1..5 && asset.kind != MediaKind.Video } ?: when (asset.kind) {
                MediaKind.Video -> "mp4"
                MediaKind.Image -> "jpg"
                MediaKind.Audio -> "m4a"
                MediaKind.Pdf -> "pdf"
                else -> "bin"
            }
            val target = File(store.offlineDir, "$fileId.$ext")
            // A partial file from an earlier run (stopped by the system, or a lost process) is picked
            // up and continued; one still being written by a stopping run is left alone.
            adoptPartial(target)
            val meter = RateMeter()
            var lastNotify = 0L
            // The size of the rendition, saved once known: a resume after process death checks against it.
            var savedTotal = store.download(fileId)?.totalBytes ?: -1L
            download(url, target, expectedTotal = savedTotal) { done, total ->
                store.updateOwn(fileId, persist = total != savedTotal) { it.copy(doneBytes = done, totalBytes = total) }
                savedTotal = total
                val now = System.currentTimeMillis()
                if (now - lastNotify > 700) {
                    lastNotify = now
                    meter.sample(done)
                    setForegroundAsync(
                        TransferNotifications.foreground(
                            applicationContext, id.hashCode(), item.name, if (total > 0) done / total.toFloat() else null,
                            upload = false, detail = transferLabel(applicationContext, done, total, meter.bytesPerSecond), workId = id,
                        ),
                    )
                }
            }
            val thumb = asset.thumbnailUrl?.let { t ->
                runCatching { File(store.offlineDir, "$fileId.thumb.jpg").also { download(t, it) { _, _ -> } }.absolutePath }.getOrNull()
            }
            // Cache file JSON and comments so the viewer works fully offline.
            runCatching { c.repository.file(account, fileId).collect {} }
            runCatching { c.repository.comments(account, fileId).collect {} }
            store.updateOwn(fileId) {
                it.copy(state = TransferState.Done, localPath = target.absolutePath, thumbnailPath = thumb, doneBytes = target.length(), totalBytes = target.length())
            }
            Result.success()
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) {
                // The system stopping the work (network lost, quota) is not the user cancelling it:
                // WorkManager runs it again, so show it as waiting. A cancel from the app has
                // already removed the item (TransferManager.cancelDownload), so this is a no-op then.
                store.updateOwn(fileId) { it.copy(state = TransferState.Queued) }
                // Cancelled from the app: nothing will resume it.
                if (store.download(fileId) == null) discardPartials(fileId)
                throw t
            }
            if (runAttemptCount < 3 && t.isTransient()) {
                store.updateOwn(fileId) { it.copy(state = TransferState.Queued, error = null) }
                Result.retry()
            } else {
                store.updateOwn(fileId) { it.copy(state = TransferState.Failed, error = t.userMessage(applicationContext)) }
                discardPartials(fileId)
                Result.failure()
            }
        }
    }

    private fun pickUrl(a: Asset): String? {
        val l = a.mediaLinks ?: return null
        return when (a.kind) {
            MediaKind.Video -> l.highQuality?.downloadUrl ?: l.highQuality?.url ?: l.efficient?.downloadUrl ?: l.efficient?.url ?: l.original?.downloadUrl
            MediaKind.Image -> l.original?.downloadUrl ?: l.highQuality?.downloadUrl ?: l.thumbnailHq?.url
            MediaKind.Audio -> l.highQuality?.downloadUrl ?: l.efficient?.downloadUrl ?: l.original?.downloadUrl
            else -> l.original?.downloadUrl ?: l.original?.inlineUrl
        }
    }

    /** This run's partial file for [target]: one per run, so a stopping run can't write into a newer run's. */
    private fun partOf(target: File) = File(target.parentFile, "${target.name}.$id.part")

    /** Takes over the largest partial file earlier runs left for [target]; the others are deleted. */
    private fun adoptPartial(target: File) {
        val now = System.currentTimeMillis()
        val parts = target.parentFile?.listFiles { f -> f.name.startsWith("${target.name}.") && f.name.endsWith(".part") }.orEmpty()
            // Written in the last seconds: a run that is still stopping. Not ours to touch.
            .filter { now - it.lastModified() > 10_000 }
            .sortedByDescending { it.length() }
        val mine = partOf(target)
        parts.firstOrNull()?.takeIf { it != mine }?.renameTo(mine)
        parts.drop(1).forEach { it.delete() }
    }

    private fun discardPartials(fileId: String) {
        c.transfers.offlineDir.listFiles { f -> f.name.startsWith("$fileId.") && f.name.endsWith(".part") }?.forEach { it.delete() }
    }

    /**
     * Downloads [url] into [target], continuing this run's partial file with a Range request when
     * there is one. The partial file stays when the run stops, so the next run can continue it.
     */
    private suspend fun download(url: String, target: File, expectedTotal: Long = -1, onProgress: (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        val tmp = partOf(target)
        fetch(url, tmp, expectedTotal, onProgress)
        if (!tmp.renameTo(target)) throw IOException("Could not save file")
    }

    private suspend fun fetch(url: String, tmp: File, expectedTotal: Long, onProgress: (Long, Long) -> Unit) {
        val have = if (tmp.exists()) tmp.length() else 0L
        val request = Request.Builder().url(url).apply { if (have > 0) header("Range", "bytes=$have-") }.build()
        c.baseHttp.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val start = resumeOffset(have, resp.code, resp.header("Content-Range"), expectedTotal)
            if (resp.code == 206 && start == 0L) {
                // Part of a different file (the rendition changed): start over with the whole file.
                tmp.delete()
                return fetch(url, tmp, expectedTotal, onProgress)
            }
            if (have > 0 && start > 0) dev.finalish.core.util.AppLog.i("Download resumed at $start bytes")
            val body = resp.body
            val length = body.contentLength()
            val total = if (length >= 0) start + length else -1
            var done = start
            var lastReport = 0L
            body.byteStream().use { input ->
                java.io.FileOutputStream(tmp, start > 0).use { out ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val now = System.currentTimeMillis()
                        if (now - lastReport > 250) {
                            lastReport = now
                            onProgress(done, total)
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val KEY_FILE = "file"
        const val KEY_ACCOUNT = "account"
    }
}

/** Multi-part upload to the presigned S3 URLs returned by `local_upload`. */
class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val c = FinalishApp.container(context)

    /**
     * Writes to the upload only while this run is the one it belongs to: a stopped run (cancelled,
     * then retried) can still finish a part or land in its catch after the retry has started, and
     * must not overwrite the new run's state or progress. See TransferManager.enqueueUpload.
     */
    private fun TransferStore.updateOwn(uploadId: String, persist: Boolean = true, block: (UploadItem) -> UploadItem) =
        updateUpload(uploadId, persist) { if (owns(it)) block(it) else it }

    private fun owns(item: UploadItem) = item.workId == null || item.workId == id.toString()

    override suspend fun getForegroundInfo(): ForegroundInfo {
        inputData.getString(KEY_UPLOAD)?.let { UploadBatch.join(it, c.transfers) }
        return UploadBatch.notification(applicationContext, c.transfers, id)
    }

    override suspend fun doWork(): Result {
        val uploadId = inputData.getString(KEY_UPLOAD) ?: return Result.failure()
        val store = c.transfers
        val item = store.upload(uploadId) ?: return Result.failure()
        // Superseded by a newer run of the same upload: leave it to that one.
        if (!owns(item)) return Result.success()
        // After a restart the in-memory batch is empty: rejoin it, so the notification keeps progress and Cancel.
        UploadBatch.join(uploadId, store)
        runCatching { setForeground(UploadBatch.notification(applicationContext, store, id)) }

        var source: PartSource? = null
        return try {
            // Resume an interrupted upload while its presigned URLs are still fresh;
            // otherwise create the file on Frame.io (again) and start over.
            var current = store.upload(uploadId) ?: return Result.failure()
            val fresh = current.partsCreatedAt?.let { System.currentTimeMillis() - it < RESUME_WINDOW_MS } == true
            if (current.partUrls == null || current.remoteFileId == null || !fresh) {
                current.remoteFileId?.takeIf { current.partUrls != null }?.let { stale -> discardRemote(item.accountId, stale) }
                val created = c.api.createLocalUpload(
                    item.accountId, item.folderId,
                    kotlinx.serialization.json.buildJsonObject {
                        put("data", kotlinx.serialization.json.buildJsonObject {
                            put("name", kotlinx.serialization.json.JsonPrimitive(item.name))
                            put("file_size", kotlinx.serialization.json.JsonPrimitive(item.totalBytes))
                        })
                    },
                ).data
                store.updateOwn(uploadId) {
                    it.copy(
                        remoteFileId = created.id, partUrls = created.uploadUrls, partsCreatedAt = System.currentTimeMillis(),
                        remoteMediaType = created.mediaType, doneParts = emptyList(),
                    )
                }
                current = store.upload(uploadId) ?: return Result.failure()
            }
            val remoteId = current.remoteFileId!!
            val urls = current.partUrls!!
            val already = current.doneParts.toSet()
            val resumedBytes = urls.withIndex().filter { it.index in already }.sumOf { it.value.size }
            store.updateOwn(uploadId) { it.copy(state = TransferState.Running, error = null, doneBytes = resumedBytes) }
            val contentType = current.remoteMediaType ?: item.mimeType
            val uri = item.uri.toUri()
            val sent = AtomicLong(resumedBytes)
            var lastReport = 0L
            var lastNotify = 0L

            var offset = 0L
            val parts = urls.mapIndexed { i, part -> Triple(i, offset, part).also { offset += part.size } }
                .filter { it.first !in already }
            val partSource = PartSource(applicationContext.contentResolver, uri).also { source = it }
            fun send(index: Int, start: Long, part: dev.finalish.core.model.UploadUrl) {
                putPart(partSource, start, part.size, part.url, contentType) { delta ->
                    val total = sent.addAndGet(delta)
                    val now = System.currentTimeMillis()
                    if (now - lastReport > 300) {
                        lastReport = now
                        store.updateOwn(uploadId, persist = false) { it.copy(doneBytes = total) }
                        if (now - lastNotify > 700) {
                            lastNotify = now
                            setForegroundAsync(UploadBatch.notification(applicationContext, store, id))
                        }
                    }
                }
                // Part accepted: remember it so a later attempt skips it.
                store.updateOwn(uploadId) { it.copy(doneParts = (it.doneParts + index).distinct(), doneBytes = sent.get()) }
            }
            if (partSource.seekable) {
                // A real file: each part opens its own descriptor at its offset, three at a time.
                val gate = Semaphore(3)
                coroutineScope {
                    parts.map { (index, start, part) ->
                        async(Dispatchers.IO) { gate.withPermit { send(index, start, part) } }
                    }.awaitAll()
                }
            } else {
                // A pipe (e.g. a cloud provider streaming the file): one stream, read once, part after part.
                withContext(Dispatchers.IO) {
                    for ((index, start, part) in parts) {
                        ensureActive()
                        send(index, start, part)
                    }
                }
            }
            c.repository.invalidateFolder(item.accountId, item.folderId)
            store.updateOwn(uploadId) {
                it.copy(state = TransferState.Done, doneBytes = it.totalBytes, remoteFileId = remoteId, partUrls = null)
            }
            if (store.stagedFile(uploadId).exists()) {
                saveThumbnail(store.stagedFile(uploadId), item.mimeType, store.stagedThumb(uploadId))
                    ?.let { t -> store.updateOwn(uploadId) { it.copy(thumbnailPath = t.absolutePath) } }
                store.dropStaged(uploadId, keepThumb = true)
            }
            c.activityWatch.watchUpload(item.accountId, "", remoteId, item.name)
            dev.finalish.core.notify.ActivityWatch.checkAfterUpload(applicationContext)
            item.stackOntoId?.let { target -> stackAsNewVersion(item, target, remoteId) }
            Result.success()
        } catch (t: Throwable) {
            // A stop surfaces as CancellationException, or as the "Upload stopped" IOException when it
            // lands while a part is being sent.
            if (t is kotlinx.coroutines.CancellationException || isStopped) {
                // Only the store knows whether the user cancelled (TransferManager.cancelUpload marks it
                // first); before Android 12 the stop reason can't tell a system stop from a cancel.
                store.updateOwn(uploadId) { if (it.state == TransferState.Cancelled) it else it.copy(state = TransferState.Queued) }
                if (t is kotlinx.coroutines.CancellationException) throw t
                return Result.retry()
            }
            if (t is PartRejected) {
                // Expired/invalid presigned URL: drop it so the next attempt starts fresh.
                store.updateOwn(uploadId) { it.copy(partsCreatedAt = 0) }
            }
            // Network drops are common on mobile: keep retrying (with backoff) and resume each time.
            if (runAttemptCount < 8 && t.isTransient()) {
                store.updateOwn(uploadId) { it.copy(state = TransferState.Queued, error = null) }
                Result.retry()
            } else {
                store.updateOwn(uploadId) { it.copy(state = TransferState.Failed, error = t.userMessage(applicationContext)) }
                Result.failure()
            }
        } finally {
            source?.close()
        }
    }

    /**
     * "As new version of…": move the upload into the target's version stack, or stack the
     * target file with it. The upload stays in the folder (and says so) if that fails.
     */
    private suspend fun stackAsNewVersion(item: UploadItem, targetId: String, uploadedId: String) {
        runCatching {
            if (item.stackOntoType == "version_stack") {
                c.api.move(item.accountId, "files", uploadedId, kotlinx.serialization.json.buildJsonObject {
                    put("data", kotlinx.serialization.json.buildJsonObject { put("parent_id", kotlinx.serialization.json.JsonPrimitive(targetId)) })
                })
                c.repository.invalidateFolder(item.accountId, targetId)
            } else {
                c.repository.stackVersions(item.accountId, item.folderId, listOf(targetId, uploadedId))
            }
            c.repository.invalidateFolder(item.accountId, item.folderId)
        }.onFailure { e ->
            c.transfers.updateOwn(item.id) { it.copy(error = applicationContext.getString(R.string.core_transfer_not_stacked, e.userMessage(applicationContext))) }
        }
    }

    /** Removes a half-uploaded file left on Frame.io by an attempt that can no longer resume. */
    private suspend fun discardRemote(account: String, fileId: String) {
        runCatching { c.api.delete(account, "files", fileId) }
    }

    private class PartRejected(code: Int) : IOException("Upload part rejected ($code)")

    private fun putPart(
        source: PartSource,
        start: Long,
        size: Long,
        url: String,
        contentType: String,
        onBytes: (Long) -> Unit,
    ) {
        // OkHttp may send the body again (connection retry): count each byte once.
        var counted = 0L
        val body = object : RequestBody() {
            override fun contentType() = contentType.toMediaTypeOrNull()
            override fun contentLength() = size
            override fun writeTo(sink: BufferedSink) {
                if (counted > 0) {
                    onBytes(-counted)
                    counted = 0
                }
                source.read(start) { stream ->
                    val buf = ByteArray(128 * 1024)
                    var remaining = size
                    while (remaining > 0) {
                        // A cancelled upload stops sending instead of finishing parts in flight.
                        if (isStopped) throw IOException("Upload stopped")
                        val n = stream.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                        if (n < 0) break
                        sink.write(buf, 0, n)
                        remaining -= n
                        counted += n
                        onBytes(n.toLong())
                    }
                }
            }
        }
        val req = Request.Builder().url(url).put(body).header("x-amz-acl", "private").build()
        c.baseHttp.newCall(req).execute().use { resp ->
            if (resp.code == 403) throw PartRejected(resp.code)
            if (!resp.isSuccessful) throw IOException("Upload part failed (${resp.code})")
        }
    }

    companion object {
        const val KEY_UPLOAD = "upload"
        /** Presigned part URLs are short-lived; only resume within this window. */
        const val RESUME_WINDOW_MS = 6L * 60 * 60 * 1000
    }
}

/**
 * Reads the parts of the file being uploaded. A real file (it has a size) is seekable: each part
 * opens its own descriptor and jumps to its offset, so parts can go in parallel. A pipe (a provider
 * streaming the content, no size) can't seek: one stream stays open and is read once from start to
 * end, part after part, instead of reopening it and skipping everything before each part (quadratic
 * on a long file). Reopening and skipping is left for a resume or a part OkHttp sends again.
 */
internal class PartSource(private val resolver: android.content.ContentResolver, private val uri: android.net.Uri) : java.io.Closeable {
    val seekable: Boolean = runCatching {
        resolver.openFileDescriptor(uri, "r")?.use { it.statSize >= 0 } ?: false
    }.getOrDefault(false)

    private var stream: java.io.InputStream? = null
    private var position = 0L

    /** Calls [block] with a stream positioned at [start]; the stream is only valid inside it. */
    fun read(start: Long, block: (java.io.InputStream) -> Unit) {
        if (seekable) {
            val pfd = resolver.openFileDescriptor(uri, "r") ?: throw IOException("Cannot read file")
            android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
                input.channel.position(start)
                block(input)
            }
            return
        }
        synchronized(this) {
            val input = stream?.takeIf { position == start } ?: reopenAt(start)
            val counting = object : java.io.FilterInputStream(input) {
                override fun read(b: ByteArray, off: Int, len: Int): Int =
                    super.read(b, off, len).also { if (it > 0) position += it }

                override fun close() = Unit // shared: stays open for the next part
            }
            try {
                block(counting)
            } catch (t: Throwable) {
                // Position unknown after a failure: start from a fresh stream next time.
                dropStream()
                throw t
            }
        }
    }

    private fun reopenAt(start: Long): java.io.InputStream {
        dropStream()
        val input = resolver.openInputStream(uri) ?: throw IOException("Cannot read file")
        var skipped = 0L
        while (skipped < start) {
            val s = input.skip(start - skipped)
            if (s <= 0) {
                input.close()
                throw IOException("Seek failed")
            }
            skipped += s
        }
        stream = input
        position = start
        return input
    }

    private fun dropStream() {
        runCatching { stream?.close() }
        stream = null
        position = 0
    }

    override fun close() = synchronized(this) { dropStream() }
}

/** Sends comments that were written while offline. */
class CommentSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val c = FinalishApp.container(context)

    override suspend fun doWork(): Result {
        var failed = false
        for (p in c.transfers.pendingComments.value) {
            runCatching {
                // Idempotence: a send whose answer was lost (connection dropped, process killed) may
                // still have created the comment. Each send is recorded first (attemptedAt, saved to
                // disk); when a pending comment was already sent once, look for it on the file (same
                // text, created since that attempt) before sending it again.
                if (p.attemptedAt != null && alreadyPosted(p, p.attemptedAt)) return@runCatching
                withContext(Dispatchers.IO) { c.transfers.markPendingAttempted(p.localId, System.currentTimeMillis()) }
                c.repository.createComment(p.accountId, p.fileId, p.draft)
            }
                .onSuccess { c.transfers.removePending(p.localId) }
                .onFailure { err ->
                    if (err is kotlinx.coroutines.CancellationException) throw err
                    // Only a refusal of the comment itself drops it; 401 (sign-in), 408 and 429 are worth retrying.
                    if (err is retrofit2.HttpException && err.code() in PERMANENT_REJECTIONS) {
                        c.transfers.removePending(p.localId)
                    } else failed = true
                }
        }
        return if (failed) Result.retry() else Result.success()
    }

    /** True when the file already has a top-level comment with [p]'s text created since [since] (less clock skew). */
    private suspend fun alreadyPosted(p: PendingComment, since: Long): Boolean {
        val earliest = since - CLOCK_SKEW_MS
        var after: String? = null
        var pages = 0
        do {
            val page = c.api.comments(p.accountId, p.fileId, after = after)
            val match = page.data.any { comment ->
                comment.text == p.draft.text &&
                    comment.createdAt?.let { runCatching { java.time.OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }
                        ?.let { it >= earliest } == true
            }
            if (match) return true
            after = page.links?.next?.let { next ->
                runCatching { (if (next.startsWith("http")) next else "https://x$next").toUri().getQueryParameter("after") }.getOrNull()
            }
        } while (after != null && ++pages < 20)
        return false
    }

    private companion object {
        val PERMANENT_REJECTIONS = setOf(400, 403, 404, 410, 422)
        const val CLOCK_SKEW_MS = 5 * 60_000L
    }
}

/**
 * Follows an "Import from link": Frame.io downloads the file itself, so the phone only
 * asks how it's going until it's complete or failed. Runs for a few minutes, then
 * hands over to a later run so long imports don't hold a worker.
 */
class ImportWatchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val c = FinalishApp.container(context)

    override suspend fun doWork(): Result {
        val uploadId = inputData.getString(UploadWorker.KEY_UPLOAD) ?: return Result.failure()
        val item = c.transfers.upload(uploadId) ?: return Result.success()
        val fileId = item.remoteFileId ?: return Result.failure()
        if (item.state != TransferState.Running) return Result.success()
        val until = System.currentTimeMillis() + WATCH_MS
        while (System.currentTimeMillis() < until) {
            val status = runCatching { c.api.fileStatus(item.accountId, fileId).data }.getOrNull()
            when {
                status?.uploadComplete == true -> {
                    c.repository.invalidateFolder(item.accountId, item.folderId)
                    c.transfers.updateUpload(uploadId) { it.copy(state = TransferState.Done, error = null) }
                    c.activityWatch.watchUpload(item.accountId, "", fileId, item.name)
                    dev.finalish.core.notify.ActivityWatch.checkAfterUpload(applicationContext)
                    return Result.success()
                }
                status?.uploadFailed == true -> {
                    c.transfers.updateUpload(uploadId) {
                        it.copy(state = TransferState.Failed, error = applicationContext.getString(R.string.core_transfer_import_failed))
                    }
                    return Result.success()
                }
            }
            if (c.transfers.upload(uploadId)?.state != TransferState.Running) return Result.success() // cancelled
            kotlinx.coroutines.delay(POLL_MS)
        }
        // Still importing: give up after a day, else look again shortly.
        if (System.currentTimeMillis() - item.createdAt > GIVE_UP_MS) {
            c.transfers.updateUpload(uploadId) { it.copy(state = TransferState.Failed, error = applicationContext.getString(R.string.core_transfer_import_timeout)) }
            return Result.success()
        }
        c.transferManager.watchImport(uploadId, delayMs = 30_000)
        return Result.success()
    }

    companion object {
        const val POLL_MS = 5_000L
        const val WATCH_MS = 8 * 60_000L
        const val GIVE_UP_MS = 24 * 60 * 60_000L
    }
}

/** Worth another attempt: network trouble, or Frame.io briefly failing or rate limiting. */
/** A small JPEG of an image or video's first frame, for Transfers once the file itself is gone. */
private fun saveThumbnail(source: File, mimeType: String, target: File): File? = runCatching {
    val bitmap = when {
        mimeType.startsWith("image/") -> {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(source.path, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 320) sample *= 2
            android.graphics.BitmapFactory.decodeFile(source.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
        }
        mimeType.startsWith("video/") -> android.media.MediaMetadataRetriever().run {
            try {
                setDataSource(source.path)
                getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { frame ->
                    val scale = 320f / maxOf(frame.width, frame.height)
                    if (scale >= 1f) frame
                    else frame.scale((frame.width * scale).toInt().coerceAtLeast(1), (frame.height * scale).toInt().coerceAtLeast(1))
                        .also { frame.recycle() }
                }
            } finally {
                release()
            }
        }
        else -> null
    } ?: return@runCatching null
    target.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, it) }
    bitmap.recycle()
    target
}.getOrNull()

/**
 * Where to continue a partial download of [have] bytes, given the answer to `Range: bytes=have-`:
 * [have] when the server sent exactly the rest of the same file (206 from that byte, and the full
 * size matches [expectedTotal] when both are known), 0 to start over otherwise.
 */
internal fun resumeOffset(have: Long, code: Int, contentRange: String?, expectedTotal: Long): Long {
    if (have <= 0 || code != 206 || contentRange == null) return 0
    val m = Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""").find(contentRange) ?: return 0
    if (m.groupValues[1].toLong() != have) return 0
    val full = m.groupValues[3].toLongOrNull()
    if (full != null && expectedTotal > 0 && full != expectedTotal) return 0
    return have
}
