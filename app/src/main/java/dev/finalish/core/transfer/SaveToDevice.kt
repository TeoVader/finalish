package dev.finalish.core.transfer

import android.app.DownloadManager
import android.content.Context
import android.os.Environment
import androidx.core.net.toUri
import dev.finalish.R
import dev.finalish.core.data.FrameioRepository
import dev.finalish.core.model.Asset
import dev.finalish.core.util.Format

/**
 * "Download": the original into Download/Finalish through the system DownloadManager (its own
 * notification, kept after the app closes). Throws with a readable message when downloads are off.
 */
suspend fun saveToDevice(context: Context, repo: FrameioRepository, account: String, file: Asset) {
    // A fresh copy: the link in a listing may have expired or never been included.
    val fresh = repo.fileNow(account, file.id)
    val url = fresh.mediaLinks?.original?.downloadUrl ?: error(context.getString(R.string.viewer_msg_downloads_disabled))
    val req = DownloadManager.Request(url.toUri())
        .setTitle(file.name)
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        // The name comes from the server: keep it one plain file name inside Finalish/.
        .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "Finalish/${Format.safeFileName(file.name)}")
    context.getSystemService(DownloadManager::class.java).enqueue(req)
}
