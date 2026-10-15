package dev.finalish.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class WatermarkTemplateTest {
    @Test fun summaryListsLinesThenPlace() {
        val t = WatermarkTemplate(
            "t", "Client review",
            listOf(
                WatermarkBlock("bottom_right", listOf(WatermarkDataPoint("timestamp", order = 2.0), WatermarkDataPoint("email", order = 1.0))),
                WatermarkBlock("top_left", listOf(WatermarkDataPoint("custom_text", "CONFIDENTIAL", 1.0))),
            ),
        )
        assertEquals("Email · Time · “CONFIDENTIAL” · bottom right, top left", t.summary)
    }

    @Test fun emptyTemplate() = assertEquals("", WatermarkTemplate("t").summary)
}
