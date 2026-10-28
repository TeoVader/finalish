package dev.finalish.core.transfer

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.provider.DocumentsContract
import androidx.core.net.toUri
import dev.finalish.FinalishApp
import dev.finalish.R
import dev.finalish.core.data.FrameioRepository
import dev.finalish.core.model.Asset
import dev.finalish.core.util.Format
import java.util.UUID

/**
 * "Download": the original into Download/Finalish through the system DownloadManager (its own
 * notification, kept after the app closes), listed in Transfers › Downloads. Throws with a
 * readable message when downloads are off. [retryOf]: the entry in Transfers it replaces.
 */
suspend fun saveToDevice(context: Context, repo: FrameioRepository, account: String, file: Asset, retryOf: String? = null) {
    // A fresh copy: the link in a listing may have expired or never been included.
    val fresh = repo.fileNow(account, file.id)
    val url = fresh.mediaLinks?.original?.downloadUrl ?: error(context.getString(R.string.viewer_msg_downloads_disabled))
    // The name comes from the server: keep it one plain file name inside Finalish/.
    val path = "Finalish/${Format.safeFileName(file.name)}"
    val req = DownloadManager.Request(url.toUri())
        .setTitle(file.name)
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, path)
    val dmId = context.getSystemService(DownloadManager::class.java).enqueue(req)
    FinalishApp.container(context).transfers.upsertDeviceDownload(
        DeviceDownload(
            id = retryOf ?: UUID.randomUUID().toString(), accountId = account, name = file.name, folder = false,
            kind = file.kind, path = path, fileId = file.id, systemIds = listOf(dmId),
            totalBytes = fresh.fileSize, state = TransferState.Running,
        ),
    )
}

/**
 * Brings single-file entries in line with the system DownloadManager (progress, done, failed, or
 * removed from its list). Folders are kept up to date by their worker.
 */
fun syncDeviceDownloads(context: Context, store: TransferStore) {
    val active = store.deviceDownloads.value.filter { !it.folder && (it.state == TransferState.Running || it.state == TransferState.Queued) }
    if (active.isEmpty()) return
    val dm = context.getSystemService(DownloadManager::class.java)
    val byId = active.associateBy { it.systemIds.firstOrNull() ?: -1L }
    val seen = HashSet<Long>()
    runCatching {
        dm.query(DownloadManager.Query().setFilterById(*byId.keys.toLongArray()))?.use { cur ->
            val idCol = cur.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
            val statusCol = cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
            val soFarCol = cur.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val totalCol = cur.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            while (cur.moveToNext()) {
                val dmId = cur.getLong(idCol)
                val entry = byId[dmId] ?: continue
                seen += dmId
                val total = cur.getLong(totalCol).takeIf { it > 0 } ?: entry.totalBytes
                val soFar = cur.getLong(soFarCol).coerceAtLeast(0)
                val state = when (cur.getInt(statusCol)) {
                    DownloadManager.STATUS_SUCCESSFUL -> TransferState.Done
                    DownloadManager.STATUS_FAILED -> TransferState.Failed
                    DownloadManager.STATUS_RUNNING -> TransferState.Running
                    else -> TransferState.Queued
                }
                if (state != entry.state || soFar != entry.doneBytes || total != entry.totalBytes) {
                    store.updateDeviceDownload(entry.id, persist = state != entry.state) {
                        it.copy(
                            state = state, totalBytes = total,
                            doneBytes = if (state == TransferState.Done) total else soFar,
                            saved = if (state == TransferState.Done) 1 else 0, failed = if (state == TransferState.Failed) 1 else 0,
                        )
                    }
                }
            }
        }
        // No longer in the system's list (removed there): nothing left to follow.
        byId.filterKeys { it !in seen }.values.forEach { e -> store.updateDeviceDownload(e.id) { it.copy(state = TransferState.Cancelled) } }
    }
}

/** Opens a finished download: the file in an app that handles it, a folder in the Files app. */
fun openDeviceDownload(context: Context, d: DeviceDownload) {
    val dm = context.getSystemService(DownloadManager::class.java)
    val intent = if (d.folder) {
        val doc = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:${Environment.DIRECTORY_DOWNLOADS}/${d.path}")
        Intent(Intent.ACTION_VIEW).setDataAndType(doc, DocumentsContract.Document.MIME_TYPE_DIR)
    } else {
        val uri = d.systemIds.firstOrNull()?.let { runCatching { dm.getUriForDownloadedFile(it) }.getOrNull() }
        uri?.let { Intent(Intent.ACTION_VIEW).setDataAndType(it, dm.getMimeTypeForDownloadedFile(d.systemIds.first())).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }
    val fallback = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)
    runCatching { context.startActivity((intent ?: fallback).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { runCatching { context.startActivity(fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
}
