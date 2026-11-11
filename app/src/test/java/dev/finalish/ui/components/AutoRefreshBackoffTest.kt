package dev.finalish.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoRefreshBackoffTest {
    @Test fun `doubles while nothing changes, up to the cap, and resets on a change`() {
        val b = AutoRefreshBackoff(maxMs = 60_000)
        assertEquals(10_000, b.next(10_000))
        b.record(changed = false)
        assertEquals(20_000, b.next(10_000))
        b.record(changed = false)
        assertEquals(40_000, b.next(10_000))
        repeat(10) { b.record(changed = false) }
        assertEquals(60_000, b.next(10_000))
        b.record(changed = true)
        assertEquals(10_000, b.next(10_000))
    }

    @Test fun `never waits less than the base interval`() {
        val b = AutoRefreshBackoff(maxMs = 5_000)
        repeat(3) { b.record(changed = false) }
        assertEquals(15_000, b.next(15_000))
    }
}
