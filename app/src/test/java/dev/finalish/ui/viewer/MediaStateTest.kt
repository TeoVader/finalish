package dev.finalish.ui.viewer

import dev.finalish.core.model.Asset
import dev.finalish.core.model.MediaLink
import dev.finalish.core.model.MediaLinks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaStateTest {
    /** What Frame.io returns when it withholds media: every link present, every URL null. */
    private val emptyLinks = MediaLinks(
        thumbnailHq = MediaLink(), video180 = MediaLink(), highQuality = MediaLink(),
        efficient = MediaLink(), scrubSheet = MediaLink(),
    )

    @Test fun transcodedWithoutLinkIsUnavailableNotPreparing() {
        val video = Asset("v", name = "Cut.mp4", mediaType = "video/mp4", status = "transcoded", mediaLinks = emptyLinks)
        assertEquals(MediaState.Unavailable, mediaState(video, hasLink = false))
    }

    @Test fun stillProcessingWithoutLinkIsPreparing() {
        for (status in listOf("uploaded", "created", null)) {
            val video = Asset("v", name = "Cut.mp4", mediaType = "video/mp4", status = status, mediaLinks = emptyLinks)
            assertEquals(MediaState.Preparing, mediaState(video, hasLink = false))
        }
    }

    @Test fun aLinkIsReadyWhateverTheStatus() {
        val video = Asset("v", name = "Cut.mp4", mediaType = "video/mp4", status = "uploaded")
        assertEquals(MediaState.Ready, mediaState(video, hasLink = true))
        assertEquals(MediaState.Ready, mediaState(video.copy(status = "transcoded"), hasLink = true))
    }

    @Test fun versionStackUsesTheHeadsStatus() {
        val head = Asset("v", name = "Cut.mp4", mediaType = "video/mp4", status = "transcoded", mediaLinks = emptyLinks)
        val stack = Asset("s", type = "version_stack", headVersion = head)
        assertEquals(MediaState.Unavailable, mediaState(stack, hasLink = false))
    }

    @Test fun imageWithNullLinksHasNoUrl() {
        val image = Asset("i", name = "Still.jpg", mediaType = "image/jpeg", status = "transcoded", mediaLinks = emptyLinks)
        assertNull(imageStreamUrl(image))
        assertEquals(MediaState.Unavailable, mediaState(image, hasLink = imageStreamUrl(image) != null))
        val shown = image.copy(mediaLinks = emptyLinks.copy(highQuality = MediaLink(url = "https://example.com/hq.jpg")))
        assertEquals("https://example.com/hq.jpg", imageStreamUrl(shown))
    }
}
