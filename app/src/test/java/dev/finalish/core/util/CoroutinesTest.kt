package dev.finalish.core.util

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoroutinesTest {
    @Test
    fun `failures become a Result`() {
        val r = runCatchingCancellable { error("boom") }
        assertTrue(r.isFailure)
        assertEquals(2, runCatchingCancellable { 2 }.getOrNull())
    }

    @Test(expected = CancellationException::class)
    fun `cancellation is rethrown`() {
        runCatchingCancellable { throw CancellationException("stop") }
    }
}
