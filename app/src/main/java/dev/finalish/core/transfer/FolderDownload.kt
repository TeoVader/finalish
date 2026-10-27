package dev.finalish.core.transfer

import android.app.DownloadManager
import android.app.NotificationManager
import android.content.Context
import android.os.Environment
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dev.finalish.FinalishApp
import dev.finalish.R
import dev.finalish.core.data.FrameioRepository
import dev.finalish.core.model.Asset
import dev.finalish.core.util.Format
import dev.finalish.core.util.runCatchingCancellable
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A folder as found on Frame.io, for "Download" on a folder. */
@Serializable
data class FolderTree(
    val name: String,
    val files: List<RemoteFile> = emptyList(),
    val stacks: List<RemoteStack> = emptyList(),
    val folders: List<FolderTree> = emptyList(),
    val id: String = "",
)

@Serializable
data class RemoteFile(val id: String, val name: String, val size: Long)

/** A version stack; [versions] newest first, as Frame.io lists them. */
@Serializable
data class RemoteStack(val name: String, val versions: List<RemoteFile>, val id: String = "")

/** One file to fetch, at [path] under Download/Finalish/. */
@Serializable
data class PlannedDownload(val fileId: String, val path: String, val size: Long)

data class FolderDownloadSummary(val files: Int, val bytes: Long, val folders: Int, val stacks: Int)

object FolderDownloadPlan {
    /**
     * Where each file goes, starting with the folder's own name. Every name from the server is made
     * one plain path segment; names that clash in a folder (also by case only) get " (2)", " (3)"…
     * A version stack is its newest version, or with [allVersions] a folder named after the stack
     * holding "v1 - name", "v2 - name"… (v1 the oldest).
     */
    fun plan(tree: FolderTree, allVersions: Boolean): List<PlannedDownload> {
        val out = mutableListOf<PlannedDownload>()
        fun walk(node: FolderTree, dir: String) {
            val names = UniqueNames()
            node.folders.forEach { walk(it, "$dir/${names.take(Format.safeFileName(it.name, "folder"))}") }
            node.files.forEach { out += PlannedDownload(it.id, "$dir/${names.take(Format.safeFileName(it.name))}", it.size) }
            node.stacks.forEach { s ->
                val head = s.versions.firstOrNull() ?: return@forEach
                if (!allVersions || s.versions.size == 1) {
                    out += PlannedDownload(head.id, "$dir/${names.take(Format.safeFileName(head.name))}", head.size)
                } else {
                    val stackDir = "$dir/${names.take(Format.safeFileName(s.name.substringBeforeLast('.').ifBlank { s.name }, "versions"))}"
                    val inStack = UniqueNames()
                    s.versions.forEachIndexed { i, v ->
                        val n = s.versions.size - i
                        out += PlannedDownload(v.id, "$stackDir/${inStack.take(Format.safeFileName("v$n - ${v.name}"))}", v.size)
                    }
                }
            }
        }
        walk(tree, Format.safeFileName(tree.name, "folder"))
        return out
    }

    fun summary(tree: FolderTree, allVersions: Boolean): FolderDownloadSummary {
        val plan = plan(tree, allVersions)
        fun folders(t: FolderTree): Int = t.folders.size + t.folders.sumOf { folders(it) }
        fun stacks(t: FolderTree): Int = t.stacks.count { it.versions.isNotEmpty() } + t.folders.sumOf { stacks(it) }
        return FolderDownloadSummary(plan.size, plan.sumOf { it.size }, folders(tree), stacks(tree))
    }

    /** Names already used in one folder, compared without case (shared storage ignores it). */
    private class UniqueNames {
        private val used = HashSet<String>()
        fun take(name: String): String {
            if (used.add(name.lowercase())) return name
            val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
            var i = 2
            while (true) {
                val candidate = name.substring(0, dot) + " ($i)" + name.substring(dot)
                if (used.add(candidate.lowercase())) return candidate
                i++
            }
        }
    }
}

/**
 * Walks [folder] and its subfolders (and the versions of each stack), four requests at a time.
 * [onFound] gets the running count of files found.
 */
suspend fun scanFolder(repo: FrameioRepository, account: String, folder: Asset, onFound: (Int) -> Unit): FolderTree {
    val gate = Semaphore(4)
    val found = AtomicInteger()
    suspend fun walk(id: String, name: String): FolderTree = coroutineScope {
        val children = gate.withPermit { repo.childrenNow(account, id) }
        val files = children.filter { it.isFile }.map { RemoteFile(it.id, it.name, it.fileSize) }
        onFound(found.addAndGet(files.size))
        val stacks = children.filter { it.isVersionStack }.map { s ->
            async {
                val versions = gate.withPermit { repo.versionsNow(account, s.id) }.filter { it.isFile }
                onFound(found.addAndGet(versions.size.coerceAtMost(1)))
                RemoteStack(s.name, versions.map { RemoteFile(it.id, it.name, it.fileSize) }, s.id)
            }
        }
        val folders = children.filter { it.isFolder }.map { f -> async { walk(f.id, f.name) } }
        FolderTree(name, files, stacks.awaitAll(), folders.awaitAll(), id)
    }
    return walk(folder.id, folder.name)
}

/** A queued folder download, saved so a retried run carries on from [next]. */
@Serializable
internal data class FolderDownloadJob(
    val account: String,
    val folderName: String,
    val items: List<PlannedDownload>,
    val allowMetered: Boolean,
    val next: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0,
    /** Its [DeviceDownload] in Transfers. */
    val entryId: String = "",
)

internal val folderJobJson = Json { ignoreUnknownKeys = true }

internal fun folderJobDir(context: Context) = File(context.noBackupFilesDir, "folder-downloads")

/**
 * Hands the files of a folder download to the system DownloadManager a few at a time, each with a
 * download link fetched just before, so links don't expire while a big folder waits in the queue.
 * Files whose downloads are off (or that are gone) are skipped and counted in the final notification.
 */
class FolderDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val c = FinalishApp.container(context)
    private val dm = context.getSystemService(DownloadManager::class.java)

    override suspend fun getForegroundInfo(): ForegroundInfo =
        TransferNotifications.foreground(applicationContext, id.hashCode(), inputData.getString(KEY_TITLE).orEmpty(), null, upload = false)

    override suspend fun doWork(): Result {
        val file = File(folderJobDir(applicationContext), inputData.getString(KEY_JOB) ?: return Result.failure())
        var job = runCatching { folderJobJson.decodeFromString(FolderDownloadJob.serializer(), file.readText()) }.getOrNull()
            ?: return Result.failure()
        entryId = job.entryId.takeIf { it.isNotEmpty() && c.transfers.deviceDownload(it) != null }
        c.transfers.deviceDownload(entryId.orEmpty())?.let { e ->
            saved = e.saved; failedItems = e.failedItems.toMutableList(); savedBytes = e.savedBytes
        }
        val inFlight = mutableMapOf<Long, PlannedDownload>()
        track { it.copy(state = TransferState.Running, error = null) }
        suspend fun progress() = runCatchingCancellable {
            setForeground(
                TransferNotifications.foreground(
                    applicationContext, id.hashCode(), job.folderName, job.next.toFloat() / job.items.size.coerceAtLeast(1), upload = false,
                    detail = applicationContext.resources.getQuantityString(R.plurals.core_folder_download_progress, job.items.size, job.next, job.items.size),
                    workId = id,
                ),
            )
        }
        try {
            progress()
            while (job.next < job.items.size) {
                waitForRoom(inFlight)
                val item = job.items[job.next]
                val url = runCatchingCancellable { c.repository.fileNow(job.account, item.fileId).mediaLinks?.original?.downloadUrl }
                    .getOrElse { err ->
                        // Offline: save where we are and let WorkManager try again later.
                        if (err is IOException) {
                            file.writeText(folderJobJson.encodeToString(FolderDownloadJob.serializer(), job))
                            track { it.copy(state = TransferState.Queued) }
                            return Result.retry()
                        }
                        null
                    }
                job = if (url == null) {
                    skippedNow++
                    job.copy(next = job.next + 1, skipped = job.skipped + 1)
                } else {
                    val dmId = dm.enqueue(
                        DownloadManager.Request(url.toUri())
                            .setTitle(item.path.substringAfterLast('/'))
                            .setDescription(job.folderName)
                            .setAllowedOverMetered(job.allowMetered)
                            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "Finalish/${item.path}"),
                    )
                    inFlight[dmId] = item
                    job.copy(next = job.next + 1)
                }
                if (job.next % 10 == 0) file.writeText(folderJobJson.encodeToString(FolderDownloadJob.serializer(), job))
                progress()
            }
            // Wait for the last ones too, to count failures in the summary.
            while (inFlight.isNotEmpty()) waitForRoom(inFlight, room = 1)
        } catch (e: CancellationException) {
            // Cancelled from the notification or Transfers: stop the files already handed over as well.
            if (inFlight.isNotEmpty()) runCatching { dm.remove(*inFlight.keys.toLongArray()) }
            file.delete()
            track { it.copy(state = TransferState.Cancelled, doneBytes = savedBytes) }
            throw e
        }
        file.delete()
        track { it.copy(state = TransferState.Done, doneBytes = savedBytes) }
        notifyDone(job.copy(failed = job.failed + failedCount))
        return Result.success()
    }

    private var failedCount = 0
    private var entryId: String? = null
    private var saved = 0
    private var skippedNow = 0
    private var savedBytes = 0L
    private var failedItems = mutableListOf<PlannedDownload>()

    /** Mirrors this run's counts into the folder's entry in Transfers. */
    private fun track(block: (DeviceDownload) -> DeviceDownload) {
        val id = entryId ?: return
        c.transfers.updateDeviceDownload(id) {
            block(it.copy(saved = saved, skipped = it.skipped + skippedNow, failed = failedItems.size, failedItems = failedItems.toList(), savedBytes = savedBytes))
        }
        skippedNow = 0
    }

    /** Waits until fewer than [room] of this run's downloads are still pending or running. */
    private suspend fun waitForRoom(inFlight: MutableMap<Long, PlannedDownload>, room: Int = MAX_IN_FLIGHT) {
        while (true) {
            var moving = 0L
            if (inFlight.isNotEmpty()) {
                dm.query(DownloadManager.Query().setFilterById(*inFlight.keys.toLongArray()))?.use { cur ->
                    val idCol = cur.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
                    val statusCol = cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                    val soFarCol = cur.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                    val listed = HashSet<Long>()
                    while (cur.moveToNext()) {
                        val dmId = cur.getLong(idCol)
                        listed += dmId
                        val item = inFlight[dmId] ?: continue
                        when (cur.getInt(statusCol)) {
                            DownloadManager.STATUS_SUCCESSFUL -> { saved++; savedBytes += item.size; inFlight -= dmId }
                            DownloadManager.STATUS_FAILED -> { failedCount++; failedItems += item; inFlight -= dmId }
                            else -> moving += cur.getLong(soFarCol).coerceAtLeast(0)
                        }
                    }
                    // Removed by the user from the system's download list: counted as failed, so it can be retried.
                    (inFlight.keys - listed).forEach { gone -> failedCount++; failedItems += inFlight.getValue(gone); inFlight -= gone }
                }
            }
            track { it.copy(doneBytes = savedBytes + moving) }
            if (inFlight.size < room) return
            delay(1_500)
        }
    }

    private fun notifyDone(job: FolderDownloadJob) {
        val ctx = applicationContext
        val res = ctx.resources
        val sent = job.items.size - job.skipped - job.failed
        val text = listOfNotNull(
            res.getQuantityString(R.plurals.core_folder_download_saved, sent, sent, Format.safeFileName(job.folderName, "folder")),
            job.skipped.takeIf { it > 0 }?.let { res.getQuantityString(R.plurals.core_folder_download_skipped, it, it) },
            job.failed.takeIf { it > 0 }?.let { res.getQuantityString(R.plurals.core_folder_download_failed, it, it) },
        ).joinToString(" · ")
        TransferNotifications.ensureChannel(ctx)
        val n = NotificationCompat.Builder(ctx, TransferNotifications.CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(ctx.getString(R.string.core_folder_download_done, job.folderName))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(id.toString(), 0, n) }
    }

    companion object {
        const val KEY_JOB = "job"
        const val KEY_TITLE = "title"
        private const val MAX_IN_FLIGHT = 3
    }
}
