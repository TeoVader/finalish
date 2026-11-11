package dev.finalish.core.transfer

import dev.finalish.core.model.Asset
import dev.finalish.core.model.MediaLink
import dev.finalish.core.model.MediaLinks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OfflineDownloadUrlTest {
    private val playback = MediaLink(url = "https://example.com/play", inlineUrl = "https://example.com/inline")

    @Test fun playbackAndRenditionDownloadsDoNotGrantPermission() {
        listOf("video/mp4", "image/jpeg", "audio/mpeg", "application/pdf").forEach { type ->
            val file = Asset("file", mediaType = type, mediaLinks = MediaLinks(
                original = playback, highQuality = playback.copy(downloadUrl = "https://example.com/rendition"),
            ))
            assertNull(offlineDownloadUrl(file))
        }
    }

    @Test fun allowedVideoUsesDownloadRenditionAndFallsBackToOriginal() {
        val file = Asset("file", mediaType = "video/mp4", mediaLinks = MediaLinks(
            original = MediaLink(downloadUrl = "https://example.com/original"), highQuality = playback,
        ))
        assertEquals("https://example.com/original", offlineDownloadUrl(file))
        assertEquals("https://example.com/rendition", offlineDownloadUrl(file.copy(mediaLinks = file.mediaLinks!!.copy(
            highQuality = playback.copy(downloadUrl = "https://example.com/rendition"),
        ))))
    }
}
