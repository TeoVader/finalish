package dev.finalish.core.transfer

import dev.finalish.core.model.Asset
import dev.finalish.core.model.MediaKind

/** A playback URL never grants permission to retain a copy. */
internal fun offlineDownloadUrl(a: Asset): String? {
    val l = a.mediaLinks ?: return null
    val original = l.original?.downloadUrl ?: return null
    return when (a.kind) {
        MediaKind.Video, MediaKind.Audio -> l.highQuality?.downloadUrl ?: l.efficient?.downloadUrl ?: original
        else -> original
    }
}

