package dev.finalish.core.transfer

import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dev.finalish.FinalishApp
import dev.finalish.R
import dev.finalish.core.util.runCatchingCancellable
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable

/** A file inside a folder picked on the phone for "Upload folder". */
@Serializable
data class LocalFile(val uri: String, val name: String, val size: Long)

/** A folder picked on the phone, with its subfolders. */
@Serializable
data class LocalFolder(val name: String, val files: List<LocalFile> = emptyList(), val folders: List<LocalFolder> = emptyList())

/** One folder to create on Frame.io and the files that go in it. [parent] indexes the plan; -1 is the destination. */
@Serializable
data class PlannedFolder(val name: String, val parent: Int, val files: List<LocalFile>)

data class FolderUploadSummary(val files: Int, val bytes: Long, val folders: Int)

object FolderUploadPlan {
    /** The folders to create, each after its parent, starting with the picked folder itself. */
    fun plan(root: LocalFolder): List<PlannedFolder> {
        val out = mutableListOf<PlannedFolder>()
        val queue = ArrayDeque(listOf(root to -1))
        while (queue.isNotEmpty()) {
            val (folder, parent) = queue.removeFirst()
            out += PlannedFolder(folder.name, parent, folder.files)
            val index = out.lastIndex
            folder.folders.forEach { queue += it to index }
        }
        return out
    }

    fun summary(root: LocalFolder): FolderUploadSummary {
        val plan = plan(root)
        return FolderUploadSummary(plan.sumOf { it.files.size }, plan.sumOf { f -> f.files.sumOf { it.size } }, plan.size - 1)
    }
}

/**
 * Walks a folder picked with the system's folder picker ([treeUri]). Hidden entries (".nomedia",
 * ".thumbnails"…) stay out. [onFound] gets the running count of files found.
 */
suspend fun scanLocalFolder(context: Context, treeUri: Uri, onFound: (Int) -> Unit): LocalFolder {
    val resolver = context.contentResolver
    var found = 0
    val cols = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
    )
    suspend fun walk(docId: String, name: String): LocalFolder {
        coroutineContext.ensureActive()
        val files = mutableListOf<LocalFile>()
        val dirs = mutableListOf<Pair<String, String>>()
        resolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId), cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val childName = c.getString(1) ?: continue
                if (childName.startsWith(".")) continue
                if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                    dirs += id to childName
                } else {
                    val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                    files += LocalFile(uri.toString(), childName, if (c.isNull(3)) 0 else c.getLong(3))
                }
            }
        }
        found += files.size
        onFound(found)
        return LocalFolder(name, files.sortedBy { it.name.lowercase() }, dirs.sortedBy { it.second.lowercase() }.map { (id, n) -> walk(id, n) })
    }
    val rootId = DocumentsContract.getTreeDocumentId(treeUri)
    val rootName = resolver.query(DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId), arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        ?: rootId.substringAfterLast(':').substringAfterLast('/').ifBlank { "Folder" }
    return walk(rootId, rootName)
}

/** A queued folder upload, saved so a retried run doesn't create the same folders twice. */
@Serializable
internal data class FolderUploadJob(
    val account: String,
    val destinationId: String,
    val treeUri: String,
    val folders: List<PlannedFolder>,
    /** Frame.io id of each planned folder once created. */
    val created: List<String?> = folders.map { null },
    /** How many folders already have their files queued in Transfers. */
    val queued: Int = 0,
)

internal fun folderUploadJobDir(context: Context) = File(context.noBackupFilesDir, "folder-uploads")

/** Tree grants that folder uploads not yet queued still need (see TransferManager.releaseUnusedGrants). */
internal fun pendingFolderUploadGrants(context: Context): Set<String> =
    folderUploadJobDir(context).listFiles()?.mapNotNull { f ->
        runCatching { folderJobJson.decodeFromString(FolderUploadJob.serializer(), f.readText()).treeUri }.getOrNull()
    }?.toSet().orEmpty()

/**
 * "Upload folder": creates the picked folder and its subfolders on Frame.io, parents first, then
 * hands each folder's files to the usual uploads (Transfers, resume, notifications).
 */
class FolderUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val c = FinalishApp.container(context)

    override suspend fun getForegroundInfo(): ForegroundInfo =
        TransferNotifications.foreground(applicationContext, id.hashCode(), inputData.getString(KEY_TITLE).orEmpty(), null, upload = true)

    override suspend fun doWork(): Result {
        val file = File(folderUploadJobDir(applicationContext), inputData.getString(KEY_JOB) ?: return Result.failure())
        var job = runCatching { folderJobJson.decodeFromString(FolderUploadJob.serializer(), file.readText()) }.getOrNull()
            ?: return Result.failure()
        fun save() = file.writeText(folderJobJson.encodeToString(FolderUploadJob.serializer(), job))
        val title = job.folders.first().name
        try {
            job.folders.forEachIndexed { i, folder ->
                if (job.created[i] != null) return@forEachIndexed
                runCatchingCancellable {
                    setForeground(
                        TransferNotifications.foreground(
                            applicationContext, id.hashCode(), title, i.toFloat() / job.folders.size, upload = true,
                            detail = applicationContext.getString(R.string.core_folder_upload_creating), workId = id,
                        ),
                    )
                }
                val parentId = if (folder.parent < 0) job.destinationId else job.created[folder.parent]!!
                val made = runCatchingCancellable { c.repository.createFolder(job.account, parentId, folder.name) }.getOrElse { err ->
                    save()
                    // Offline: carry on later from this folder. Anything else (no rights, folder gone): give up.
                    if (err is IOException) return Result.retry()
                    file.delete()
                    notifyFailed(title, err.message)
                    return Result.failure()
                }
                job = job.copy(created = job.created.toMutableList().also { it[i] = made.id })
                save()
            }
            val treeUri = Uri.parse(job.treeUri)
            for (i in job.queued until job.folders.size) {
                val folder = job.folders[i]
                if (folder.files.isNotEmpty()) {
                    c.transferManager.upload(job.account, job.created[i]!!, folder.name, folder.files.map { Uri.parse(it.uri) }, grantUri = treeUri)
                }
                job = job.copy(queued = i + 1)
                save()
            }
        } catch (e: CancellationException) {
            // Cancelled from the notification: folders already made stay, nothing more is queued.
            file.delete()
            throw e
        }
        file.delete()
        return Result.success()
    }

    private fun notifyFailed(folderName: String, reason: String?) {
        val ctx = applicationContext
        TransferNotifications.ensureChannel(ctx)
        val n = NotificationCompat.Builder(ctx, TransferNotifications.CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(ctx.getString(R.string.core_folder_upload_failed, folderName))
            .setContentText(reason)
            .setAutoCancel(true)
            .build()
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(id.toString(), 0, n) }
    }

    companion object {
        const val KEY_JOB = "job"
        const val KEY_TITLE = "title"
    }
}
