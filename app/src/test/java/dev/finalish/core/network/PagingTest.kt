package dev.finalish.core.network

import dev.finalish.core.model.Links
import dev.finalish.core.model.PageEnvelope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PagingTest {
    /** Three pages of two items each, chained by `links.next` the way Frame.io returns them. */
    private val pages = mapOf(
        null to PageEnvelope(listOf(1, 2), Links("/v4/accounts/a/folders/f/children?page_size=2&after=c1")),
        "c1" to PageEnvelope(listOf(3, 4), Links("https://api.frame.io/v4/accounts/a/folders/f/children?after=c2&page_size=2")),
        "c2" to PageEnvelope(listOf(5, 6), Links(null)),
    )

    @Test
    fun `reads every page in order`() = runBlocking {
        val asked = mutableListOf<String>()
        val partials = mutableListOf<List<Int>>()
        val all = allPages(
            first = { pages.getValue(null) },
            next = { asked += it; pages.getValue(it) },
            partial = { partials += it },
        )
        assertEquals(listOf(1, 2, 3, 4, 5, 6), all)
        assertEquals(listOf("c1", "c2"), asked)
        assertEquals(listOf(listOf(1, 2), listOf(1, 2, 3, 4)), partials)
    }

    @Test
    fun `a single page makes one call`() = runBlocking {
        var calls = 0
        val all = allPages<Int>(first = { PageEnvelope(listOf(7)) }, next = { calls++; PageEnvelope() })
        assertEquals(listOf(7), all)
        assertEquals(0, calls)
    }

    @Test
    fun `stops once the limit is reached`() = runBlocking {
        val all = allPages(first = { pages.getValue(null) }, next = { pages.getValue(it) }, limit = 3)
        assertEquals(listOf(1, 2, 3, 4), all)
    }

    @Test
    fun `stops when a page repeats its own cursor`() = runBlocking {
        val asked = mutableListOf<String>()
        val all = allPages(
            first = { PageEnvelope(listOf(1), Links("/x?after=c1")) },
            next = { asked += it; PageEnvelope(listOf(2), Links("/x?after=c1")) },
        )
        assertEquals(listOf(1, 2), all)
        assertEquals(listOf("c1"), asked)
    }

    @Test
    fun `stops when cursors go round in a cycle`() = runBlocking {
        val asked = mutableListOf<String>()
        val cycle = mapOf(
            "c1" to PageEnvelope(listOf(2), Links("/x?after=c2")),
            "c2" to PageEnvelope(listOf(3), Links("/x?after=c1")),
        )
        val all = allPages(
            first = { PageEnvelope(listOf(1), Links("/x?after=c1")) },
            next = { asked += it; cycle.getValue(it) },
        )
        assertEquals(listOf(1, 2, 3), all)
        assertEquals(listOf("c1", "c2"), asked)
    }

    @Test
    fun `stops at an empty page that still has a next link`() = runBlocking {
        val asked = mutableListOf<String>()
        val partials = mutableListOf<List<Int>>()
        val all = allPages(
            first = { PageEnvelope(listOf(1), Links("/x?after=c1")) },
            next = { asked += it; PageEnvelope(emptyList(), Links("/x?after=c2")) },
            partial = { partials += it },
        )
        assertEquals(listOf(1), all)
        assertEquals(listOf("c1"), asked)
        assertEquals(listOf(listOf(1)), partials)
    }

    @Test
    fun `an empty first page with a next link makes one call`() = runBlocking {
        var calls = 0
        val all = allPages<Int>(
            first = { PageEnvelope(emptyList(), Links("/x?after=c1")) },
            next = { calls++; PageEnvelope() },
        )
        assertEquals(emptyList<Int>(), all)
        assertEquals(0, calls)
    }

    @Test
    fun `cursor comes from a path or a full URL`() {
        assertEquals("abc", afterCursor("/v4/accounts/a/shares?after=abc&page_size=100"))
        assertEquals("x y", afterCursor("https://api.frame.io/v4/x?after=x%20y"))
        assertNull(afterCursor("/v4/accounts/a/shares?page_size=100"))
    }
}
