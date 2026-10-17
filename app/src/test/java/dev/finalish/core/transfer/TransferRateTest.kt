package dev.finalish.core.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferRateTest {

    private val second = 1_000_000_000L

    @Test fun rateNeedsTwoSamples() {
        val m = RateMeter()
        m.sample(0, nowNanos = 0)
        assertNull(m.bytesPerSecond)
        m.sample(4_000_000, nowNanos = second)
        assertEquals(4_000_000.0, m.bytesPerSecond!!, 1.0)
    }

    @Test fun rateIsSmoothed() {
        val m = RateMeter()
        m.sample(0, 0)
        m.sample(1_000_000, second) // 1 MB/s
        m.sample(11_000_000, 2 * second) // a 10 MB/s burst
        val r = m.bytesPerSecond!!
        assertTrue("smoothed rate $r should sit between the samples", r > 1_000_000 && r < 10_000_000)
    }

    @Test fun resetForgetsHistory() {
        val m = RateMeter()
        m.sample(0, 0)
        m.sample(1_000, second)
        m.reset()
        assertNull(m.bytesPerSecond)
    }

    @Test fun labelWithoutRate() {
        assertEquals("26.2 MB of 82.8 MB", transferLabel(26_214_400, 82_837_504, null))
    }

    @Test fun labelWithSpeedAndEta() {
        // 50 MB left at 5 MB/s → 10 s.
        val mb = 1_000_000L
        assertEquals("30.0 MB of 80.0 MB · 5.0 MB/s · 10s left", transferLabel(30 * mb, 80 * mb, 5.0 * mb))
        assertEquals("0 B of 600 MB · 1.0 MB/s · 10m 0s left", transferLabel(0, 600 * mb, 1.0 * mb))
    }

    @Test fun labelWithoutTotal() {
        // A download without Content-Length reports a total of -1: show only what arrived.
        val mb = 1_000_000L
        assertEquals("12.3 MB", transferLabel(12_300_000, -1, null))
        assertEquals("12.3 MB · 5.0 MB/s", transferLabel(12_300_000, -1, 5.0 * mb))
        assertEquals("0 B", transferLabel(0, 0, null))
    }
}
