package dev.finalish.core.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class FormatTest {

    @Test fun bytes() {
        assertEquals("512 B", Format.bytes(512))
        assertEquals("1.0 KB", Format.bytes(1000))
        assertEquals("82.8 MB", Format.bytes(82_837_504))
        assertEquals("2.3 GB", Format.bytes(2_254_857_830))
        assertEquals("2.0 GB", Format.bytes(2_000_000_000))
        assertEquals("535 MB", Format.bytes(534_773_760))
    }

    @Test fun timecodeAt24fps() {
        assertEquals("00:00:00:00", Format.timecode(0, 24.0))
        assertEquals("00:00:01:12", Format.timecode(1_500, 24.0))
        assertEquals("01:00:00:00", Format.timecode(3_600_000, 24.0))
    }

    @Test fun timecodeAt23976RoundsToTwentyFourFrames() {
        // Frame numbers from 23.976 fps keep a 24-frame second in the readout.
        assertEquals("00:00:01:00", Format.timecode(1_001, 23.976))
    }

    @Test fun frameConversionsRoundTrip() {
        for (frame in listOf(0, 1, 23, 24, 1_000, 86_399)) {
            assertEquals(frame, Format.msToFrame(Format.framesToMs(frame, 25.0), 25.0))
            assertEquals(frame, Format.msToFrame(Format.framesToMs(frame, 23.976), 23.976))
        }
    }

    @Test fun timecodeToFrame() {
        assertEquals(36, Format.timecodeToFrame("00:00:01:12", 24.0))
        assertEquals(36, Format.timecodeToFrame("00:00:01;12", 24.0))
        assertEquals(null, Format.timecodeToFrame("1:12", 24.0))
    }

    @Test fun relativeTime() {
        val now = Instant.parse("2026-10-09T12:00:00Z")
        assertEquals("just now", Format.relative("2026-10-09T11:59:30Z", now))
        assertEquals("1m ago", Format.relative("2026-10-09T11:59:10Z", now)) // never "0m ago"
        assertEquals("5m ago", Format.relative("2026-10-09T11:55:00Z", now))
        assertEquals("2h ago", Format.relative("2026-10-09T10:00:00Z", now))
        assertEquals("3d ago", Format.relative("2026-10-06T12:00:00Z", now))
        assertEquals("", Format.relative("not a date", now))
    }

    @Test fun clock() {
        assertEquals("0:05", Format.clock(5_000))
        assertEquals("1:25", Format.clock(85_000))
        assertEquals("1:00:00", Format.clock(3_600_000))
        assertEquals("0:00", Format.clock(-10))
    }

    @Test fun initials() {
        assertEquals("NL", Format.initials("Noa Lindqvist"))
        assertEquals("E", Format.initials("Emery"))
        assertEquals("JD", Format.initials("john.doe@example.com"))
        assertEquals("?", Format.initials(""))
    }

    @Test fun timeLeftRoundsUp() {
        val now = java.time.Instant.parse("2026-10-13T12:00:00Z")
        assertEquals("7d", Format.remaining("2026-10-20T11:59:30Z", now))
        assertEquals(7L, Format.daysLeft("2026-10-20T11:59:30Z", now))
        assertEquals("3h", Format.remaining("2026-10-13T14:10:00Z", now))
        assertEquals("5m", Format.remaining("2026-10-13T12:05:00Z", now))
        assertEquals(null, Format.remaining("2026-10-12T12:00:00Z", now))
    }

    @Test fun safeFileName() {
        assertEquals("clip.mov", Format.safeFileName("clip.mov"))
        assertEquals("_.._etc_passwd", Format.safeFileName("/../etc/passwd"))
        assertEquals("download", Format.safeFileName(".."))
        assertEquals("download", Format.safeFileName(null))
        assertEquals("a_b_c.txt", Format.safeFileName("a\\b\nc.txt"))
        assertEquals("evil_gpj.exe", Format.safeFileName("evil\u202Egpj.exe"))
        val long = Format.safeFileName("x".repeat(300) + ".mp4")
        assertEquals(120, long.length)
        assertEquals(true, long.endsWith(".mp4"))
    }
}
