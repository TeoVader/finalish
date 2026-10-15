package dev.finalish.ui.viewer

import dev.finalish.core.model.Asset
import dev.finalish.core.model.MetadataField
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TechnicalFormatTest {

    private fun field(name: String, v: Any) = MetadataField(
        name = name,
        value = when (v) {
            is Number -> JsonPrimitive(v)
            else -> JsonPrimitive(v.toString())
        },
    )

    @Test fun videoReadsLikeAnEditor() {
        val a = Asset(
            "v", name = "Reel 2.mp4", mediaType = "video/mp4",
            metadata = listOf(
                field("Video Codec", "AVC"), field("Resolution - Width", 1080), field("Resolution - Height", 1920), field("Frame Rate", 25),
            ),
        )
        assertEquals("H.264 · 1080×1920 · 25 fps", technicalFormat(a))
    }

    @Test fun fractionalFrameRate() {
        val a = Asset("v", name = "a.mov", mediaType = "video/quicktime", metadata = listOf(field("Video Codec", "ProRes 422 HQ"), field("Frame Rate", 23.976)))
        assertEquals("ProRes 422 HQ · 23.976 fps", technicalFormat(a))
    }

    @Test fun audio() {
        val a = Asset("a", name = "mix.wav", mediaType = "audio/wav", metadata = listOf(field("Audio Codec", "PCM"), field("Audio Sample Rate", 48000), field("Audio Channels", 2)))
        assertEquals("PCM · 48 kHz · stereo", technicalFormat(a))
    }

    @Test fun nothingKnown() {
        assertNull(technicalFormat(Asset("x", name = "notes.txt")))
    }
}
