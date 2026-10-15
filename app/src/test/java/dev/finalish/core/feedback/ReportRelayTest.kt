package dev.finalish.core.feedback

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class ReportRelayTest {

    private fun report(kind: FeedbackKind = FeedbackKind.Bug) = FeedbackReport(
        id = "1", kind = kind, title = "Player freezes", description = "Seek then pause",
        createdAt = 0, includeScreenshot = true, includeLogs = true,
        device = mapOf("App" to "0.1.0 (debug)", "Device" to "Pixel 11 Pro XL"),
        screens = listOf("00:01 Viewer"),
    )

    /** Same rule as relay/src/worker.js: sha256("seed:nonce") starts with `bits` zero bits. */
    @Test fun proofOfWorkMatchesTheRelay() {
        val nonce = ReportRelay.solvePow("abc123", 12)
        val digest = MessageDigest.getInstance("SHA-256").digest("abc123:$nonce".toByteArray())
        assertTrue(ReportRelay.leadingZeroBits(digest) >= 12)
        assertEquals(12, ReportRelay.leadingZeroBits(byteArrayOf(0, 0x0f)))
    }

    @Test fun reportJsonCarriesWhatTheIssueShows() {
        val logs = "=== Recent app log ===\n10-12 I API GET /v4/me -> 200\n10-12 W Upload part failed (503)"
        val j = ReportRelay.reportJson(report(), logs)
        assertEquals("bug", j["kind"]!!.jsonPrimitive.content)
        assertEquals("Player freezes", j["title"]!!.jsonPrimitive.content)
        assertEquals("Pixel 11 Pro XL", j["device"]!!.jsonObject["Device"]!!.jsonPrimitive.content)
        assertEquals("00:01 Viewer", j["screens"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("10-12 W Upload part failed (503)", j["logExcerpt"]!!.jsonPrimitive.content)
        assertEquals("change", ReportRelay.reportJson(report(FeedbackKind.Change), null)["kind"]!!.jsonPrimitive.content)
    }

    @Test fun logExcerptPrefersProblemsAndStaysShort() {
        assertEquals("10-12 I Screen: Home", FeedbackStore.logExcerpt("=== Recent app log ===\n10-12 I Screen: Home"))
        val long = (1..5000).joinToString("\n") { "10-12 E line $it with some noise to make it long" }
        val out = FeedbackStore.logExcerpt(long)
        assertTrue(out.length <= 5000)
        assertTrue(out.endsWith("line 5000 with some noise to make it long"))
    }
}
