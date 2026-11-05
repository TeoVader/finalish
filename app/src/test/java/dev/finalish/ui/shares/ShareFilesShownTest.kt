package dev.finalish.ui.shares

import dev.finalish.core.model.Asset
import org.junit.Assert.assertEquals
import org.junit.Test

class ShareFilesShownTest {
    private fun assets(vararg ids: String) = ids.map { Asset(it, name = it) }
    private fun ids(list: List<Asset>) = list.map { it.id }

    @Test
    fun `an item added while loading stays through later pages and the final list`() {
        val added = assets("new")
        assertEquals(listOf("a", "new"), ids(shareFilesShown(assets("a"), added, emptySet())))
        assertEquals(listOf("a", "b", "new"), ids(shareFilesShown(assets("a", "b"), added, emptySet())))
    }

    @Test
    fun `an item removed while loading stays gone when a later page or the final list has it`() {
        assertEquals(listOf("b"), ids(shareFilesShown(assets("a", "b"), emptyList(), setOf("a"))))
        assertEquals(listOf("b", "c"), ids(shareFilesShown(assets("a", "b", "c"), emptyList(), setOf("a"))))
    }

    @Test
    fun `an added item that also arrives in a page shows once, in the server's place and version`() {
        val fromServer = Asset("x", name = "server")
        val shown = shareFilesShown(listOf(fromServer, Asset("y", name = "y")), listOf(Asset("x", name = "local")), emptySet())
        assertEquals(listOf("x", "y"), ids(shown))
        assertEquals("server", shown.first().name)
    }

    @Test
    fun `an item in two pages shows once`() {
        assertEquals(listOf("a", "b"), ids(shareFilesShown(assets("a", "b", "a"), emptyList(), emptySet())))
    }
}
