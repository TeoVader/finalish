package dev.finalish.core.data

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class JsonCacheTrimTest {
    @get:Rule val tmp = TemporaryFolder()

    private val now = 1_000_000_000_000L
    private val day = 24 * 60 * 60 * 1000L

    private fun file(dir: File, name: String, bytes: Int, ageMs: Long) =
        File(dir, name).apply { writeBytes(ByteArray(bytes)); setLastModified(now - ageMs) }

    private fun names(dir: File) = dir.list()!!.sorted()

    @Test
    fun `old entries go, recent ones stay`() {
        val dir = tmp.newFolder()
        file(dir, "a.json", 10, 31 * day)
        file(dir, "b.json", 10, 2 * day)
        JsonCache.trimDir(dir, maxBytes = 1_000, maxAgeMs = 30 * day, now = now)
        assertEquals(listOf("b.json"), names(dir))
    }

    @Test
    fun `over the size cap the least recently used go first`() {
        val dir = tmp.newFolder()
        file(dir, "old.json", 400, 3 * day)
        file(dir, "mid.json", 400, 2 * day)
        file(dir, "new.json", 400, 1 * day)
        JsonCache.trimDir(dir, maxBytes = 900, maxAgeMs = 30 * day, now = now)
        assertEquals(listOf("mid.json", "new.json"), names(dir))
    }

    @Test
    fun `temp files go only once abandoned`() {
        val dir = tmp.newFolder()
        file(dir, "x1.tmp", 400, 2 * 60 * 60 * 1000L)
        file(dir, "x2.tmp", 400, 60 * 1000L)
        file(dir, "a.json", 400, 1 * day)
        JsonCache.trimDir(dir, maxBytes = 100, maxAgeMs = 30 * day, now = now)
        assertEquals(listOf("x2.tmp"), names(dir))
    }
}
