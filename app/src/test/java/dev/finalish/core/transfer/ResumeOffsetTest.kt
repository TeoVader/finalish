package dev.finalish.core.transfer

import org.junit.Assert.assertEquals
import org.junit.Test

class ResumeOffsetTest {
    @Test
    fun continuesTheSameFile() = assertEquals(1000L, resumeOffset(1000, 206, "bytes 1000-4999/5000", 5000))

    @Test
    fun unknownTotalStillContinues() = assertEquals(1000L, resumeOffset(1000, 206, "bytes 1000-4999/*", 5000))

    @Test
    fun noExpectedTotalStillContinues() = assertEquals(1000L, resumeOffset(1000, 206, "bytes 1000-4999/5000", -1))

    @Test
    fun serverIgnoringRangeStartsOver() = assertEquals(0L, resumeOffset(1000, 200, null, 5000))

    @Test
    fun anotherRenditionStartsOver() = assertEquals(0L, resumeOffset(1000, 206, "bytes 1000-7999/8000", 5000))

    @Test
    fun wrongStartStartsOver() = assertEquals(0L, resumeOffset(1000, 206, "bytes 0-4999/5000", 5000))

    @Test
    fun nothingToResume() = assertEquals(0L, resumeOffset(0, 206, "bytes 0-4999/5000", 5000))
}
