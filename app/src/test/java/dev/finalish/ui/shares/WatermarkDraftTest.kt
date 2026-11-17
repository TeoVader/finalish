package dev.finalish.ui.shares

import dev.finalish.core.model.WatermarkBlock
import dev.finalish.core.model.WatermarkDataPoint
import dev.finalish.core.model.WatermarkTemplate
import dev.finalish.testing.FakeFrameio.Companion.data
import dev.finalish.testing.ViewModelTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class WatermarkDraftTest : ViewModelTest() {
    private val styled = WatermarkBlock(
        position = "top_left", positionX = 0.3, positionY = 0.7,
        dataPoints = listOf(WatermarkDataPoint("timestamp", order = 2.0), WatermarkDataPoint("custom_text", "CONFIDENTIAL", 1.0)),
        alpha = 0.4, fontSize = "large", textColor = "#FF0000", rotation = 15.0, textShadow = true,
    )
    private val second = WatermarkBlock(position = "bottom_center", dataPoints = listOf(WatermarkDataPoint("email", order = 1.0)))
    private val template = WatermarkTemplate("t1", "Client", listOf(styled, second))

    @Test fun `a template opens with its first block's lines in order`() {
        val d = WatermarkDraft.of(template)
        assertEquals(listOf("custom_text", "timestamp"), d.lines)
        assertEquals("CONFIDENTIAL", d.customText)
        assertEquals("top_left", d.position)
        assertEquals("large", d.fontSize)
        assertEquals(0.4, d.alpha, 0.0)
    }

    @Test fun `saving keeps the block's other settings and the other blocks, and drops free-form coordinates`() {
        val saved = WatermarkDraft.of(template).copy(position = "middle_center", lines = listOf("email")).applyTo(template)
        val b = saved.blocks.first()
        assertEquals("t1", saved.id)
        assertEquals("middle_center", b.position)
        assertNull(b.positionX); assertNull(b.positionY)
        assertEquals("#FF0000", b.textColor); assertEquals(15.0, b.rotation!!, 0.0); assertEquals(true, b.textShadow)
        assertEquals(listOf(WatermarkDataPoint("email", order = 1.0)), b.dataPoints)
        assertEquals(second, saved.blocks[1])
    }

    @Test fun `a draft needs a name, a line, and the fixed text when it shows one`() {
        assertFalse(WatermarkDraft(name = "").valid)
        assertFalse(WatermarkDraft(name = "A", lines = emptyList()).valid)
        assertFalse(WatermarkDraft(name = "A", lines = listOf("custom_text")).valid)
        assertTrue(WatermarkDraft(name = "A", lines = listOf("custom_text"), customText = "DRAFT").valid)
    }

    @Test fun `a new template is created, an existing one patched with every block field`() = runBlocking {
        frameio.on("POST accounts/acc/session_watermark_templates", data("""{"id":"new","name":"Client"}"""), code = 201)
        frameio.on("PATCH accounts/acc/session_watermark_templates/t1", data("""{"id":"t1","name":"Client"}"""))

        frameio.repository.saveWatermarkTemplate("acc", WatermarkDraft(name = "Client").applyTo(null))
        frameio.repository.saveWatermarkTemplate("acc", template)

        assertEquals(listOf("POST accounts/acc/session_watermark_templates", "PATCH accounts/acc/session_watermark_templates/t1"), frameio.bodies.map { it.first })
        val patch = frameio.bodies[1].second
        listOf("\"text_color\":\"#FF0000\"", "\"rotation\":15.0", "\"text_shadow\":true", "\"position_x\":0.3", "\"font_size\":\"large\"").forEach {
            assertTrue("$it in $patch", it in patch)
        }
        assertFalse("nulls are left out: $patch", "null" in patch)
    }
}
