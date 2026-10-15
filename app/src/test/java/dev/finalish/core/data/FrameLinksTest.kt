package dev.finalish.core.data

import dev.finalish.core.data.FrameLinks.Parsed
import org.junit.Assert.assertEquals
import org.junit.Test

class FrameLinksTest {
    private fun parse(path: String) = FrameLinks.parse(path.split('/').filter { it.isNotEmpty() })

    @Test
    fun `file view link`() {
        assertEquals(Parsed.View("f1"), parse("/project/p1/view/f1"))
    }

    @Test
    fun `project and folder links`() {
        assertEquals(Parsed.InProject("p1", null), parse("/project/p1"))
        assertEquals(Parsed.InProject("p1", "d1"), parse("/project/p1/d1"))
    }

    @Test
    fun `share link`() {
        assertEquals(Parsed.ShareLink("s1"), parse("/share/s1"))
    }

    @Test
    fun `anything else`() {
        assertEquals(Parsed.Other, parse("/"))
        assertEquals(Parsed.Other, parse("/project"))
        assertEquals(Parsed.Other, parse("/share"))
        assertEquals(Parsed.Other, parse("/settings/account"))
    }
}
