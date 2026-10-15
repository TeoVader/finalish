package dev.finalish.core.model

import dev.finalish.core.network.AppJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parsing of trimmed real V4 payloads, including fields the app doesn't model. */
class ModelsTest {

    private val stack = """
        {
          "id": "vs1", "type": "version_stack", "name": "Full Spot - Graded.mp4",
          "parent_id": "f1", "project_id": "p1", "some_future_field": {"x": 1},
          "head_version": {
            "id": "file2", "type": "file", "name": "Full Spot - Graded.mp4", "media_type": "video/mp4",
            "file_size": 534773760,
            "media_links": {
              "thumbnail_high_quality": {"url": "https://assets.example/thumb_hq.jpg"},
              "thumbnail": {"url": "https://picture2.example/thumb.jpg"},
              "high_quality": {"download_url": "https://cdn.example/hq.mp4"}
            },
            "metadata": [
              {"field_type": "select", "field_definition_id": "d1", "field_definition_name": "Status", "mutable": true,
               "value": [{"id": "o1", "display_name": "In Progress"}],
               "field_options": [{"id": "o1", "display_name": "In Progress"}, {"id": "o2", "display_name": "Approved"}]}
            ]
          }
        }
    """.trimIndent()

    @Test fun versionStackUsesHeadVersionMedia() {
        val a = AppJson.decodeFromString(Asset.serializer(), stack)
        assertTrue(a.isVersionStack)
        assertEquals(MediaKind.Video, a.kind)
        assertEquals("https://assets.example/thumb_hq.jpg", a.thumbnailUrl)
        assertEquals(listOf("https://assets.example/thumb_hq.jpg", "https://picture2.example/thumb.jpg"), a.thumbnailCandidates)
        assertEquals("https://cdn.example/hq.mp4", a.media.mediaLinks?.highQuality?.streamUrl)
    }

    @Test fun statusField() {
        val status = AppJson.decodeFromString(Asset.serializer(), stack).media.metadata.single()
        assertTrue(status.isStatus)
        assertEquals("In Progress", status.textValue)
        assertEquals(listOf("o1"), status.selectedOptions.map { it.id })
    }

    @Test fun commentTimestampFrameOrTimecode() {
        val framed = AppJson.decodeFromString(Comment.serializer(), """{"id":"c1","text":"hi","timestamp":36,"duration":12}""")
        assertEquals(36, framed.frame)
        assertNull(framed.timecode)
        assertEquals(12, framed.duration)
        assertFalse(framed.isCompleted)

        val tc = AppJson.decodeFromString(Comment.serializer(), """{"id":"c2","timestamp":"00:00:01:12","completed_at":"2026-01-01T00:00:00Z"}""")
        assertNull(tc.frame)
        assertEquals("00:00:01:12", tc.timecode)
        assertTrue(tc.isCompleted)
    }

    @Test fun mediaKindFromTypeOrExtension() {
        assertEquals(MediaKind.Video, MediaKind.of(null, "clip.MOV"))
        assertEquals(MediaKind.Audio, MediaKind.of("audio/mpeg", "x"))
        assertEquals(MediaKind.Image, MediaKind.of(null, "still.heic"))
        assertEquals(MediaKind.Pdf, MediaKind.of("application/pdf", "doc"))
        assertEquals(MediaKind.Other, MediaKind.of(null, "notes.txt"))
    }

    @Test fun processingOnlyWhileFreshAndNotPlayable() {
        val now = java.time.Instant.now()
        fun f(status: String?, efficient: Boolean = false, ago: java.time.Duration = java.time.Duration.ofMinutes(1)) = Asset(
            "f", status = status, updatedAt = now.minus(ago).toString(),
            mediaLinks = if (efficient) MediaLinks(efficient = MediaLink(url = "https://x")) else null,
        )
        assertTrue(f("uploaded").isProcessing)
        assertFalse(f("transcoded").isProcessing)
        // Audio that Frame.io never transcodes but already streams.
        assertFalse(f("uploaded", efficient = true).isProcessing)
        // Stuck for hours: stop polling fast.
        assertFalse(f("uploaded", ago = java.time.Duration.ofHours(3)).isProcessing)
        assertFalse(Asset("d", type = "folder", status = "uploaded").isProcessing)
    }
}
