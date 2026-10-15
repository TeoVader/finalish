package dev.finalish.ui.viewer

import dev.finalish.core.data.PlaybackQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RenditionTest {
    @Test fun heightFromEncodeName() {
        assertEquals(1080, Rendition.heightFromUrl("https://assets.frame.io/encode/abc/h264_1080_best.mp4?Signature=x"))
        assertEquals(360, Rendition.heightFromUrl("https://assets.frame.io/encode/abc/h264_360.mp4"))
        assertNull(Rendition.heightFromUrl("https://at.adobe.com/dCEzido0ubMRucs"))
    }

    @Test fun nominalHeightIgnoresAspect() {
        assertEquals(1080, Rendition.nominalHeight(1920, 1080))
        assertEquals(1080, Rendition.nominalHeight(1920, 804)) // scope
        assertEquals(1080, Rendition.nominalHeight(1998, 1080)) // DCI flat
        assertEquals(1080, Rendition.nominalHeight(1080, 1920)) // vertical
        assertEquals(2160, Rendition.nominalHeight(3840, 2160))
        assertEquals(360, Rendition.nominalHeight(640, 360))
        assertNull(Rendition.nominalHeight(0, 0))
    }

    @Test fun labelsAndQuality() {
        assertEquals("1080p (HD)", Rendition(RenditionSource.HighQuality, 1080).label)
        assertEquals("Original", Rendition(RenditionSource.Original, null).label)
        assertEquals(PlaybackQuality.P720, Rendition(RenditionSource.Efficient, 720).quality)
        assertEquals("1440p", PlaybackQuality.labelFor(1440))
    }

    @Test fun oldSettingsMigrate() {
        assertEquals(PlaybackQuality.P1080, PlaybackQuality.parse("High"))
        assertEquals(PlaybackQuality.P360, PlaybackQuality.parse("Efficient"))
        assertEquals(PlaybackQuality.P180, PlaybackQuality.parse("Lowest"))
        assertEquals(PlaybackQuality.Auto, PlaybackQuality.parse(null))
        assertEquals(PlaybackQuality.P540, PlaybackQuality.parse("P540"))
    }

    @Test fun ladderOrder() {
        val l = qualityLadder(
            listOf(
                Rendition(RenditionSource.H264180, 180),
                Rendition(RenditionSource.HighQuality, 1080),
                Rendition(RenditionSource.Original, null),
                Rendition(RenditionSource.Efficient, 360),
            ),
        )
        assertEquals(listOf("Original", "1080p (HD)", "360p", "180p"), l.map { it.label })
    }

    @Test fun kindFromFrameioFileType() {
        assertEquals(dev.finalish.core.model.MediaKind.Image, dev.finalish.core.model.MediaKind.ofFileType("Image"))
        assertEquals(dev.finalish.core.model.MediaKind.Pdf, dev.finalish.core.model.MediaKind.ofFileType("PDF"))
        assertEquals(null, dev.finalish.core.model.MediaKind.ofFileType("Text"))
        assertEquals(dev.finalish.core.model.MediaKind.Image, dev.finalish.core.model.MediaKind.of("application/postscript", "logo.ai"))
        assertEquals(dev.finalish.core.model.MediaKind.Audio, dev.finalish.core.model.MediaKind.of(null, "voice.wma"))
        assertEquals(dev.finalish.core.model.MediaKind.Video, dev.finalish.core.model.MediaKind.of(null, "clip.3gp"))
    }

    @Test fun audioPlaysTheOriginalOnlyWhenAndroidDecodesIt() {
        val links = dev.finalish.core.model.MediaLinks(
            original = dev.finalish.core.model.MediaLink(inlineUrl = "https://x/original"),
            highQuality = dev.finalish.core.model.MediaLink(url = "https://x/aac"),
        )
        fun audio(name: String) = dev.finalish.core.model.Asset(id = "a", name = name, mediaLinks = links)
        assertEquals("https://x/original", audioStreamUrl(audio("take.wav")))
        assertEquals("https://x/aac", audioStreamUrl(audio("take.wma")))
        assertEquals("https://x/aac", audioStreamUrl(audio("take.aiff")))
    }
}
