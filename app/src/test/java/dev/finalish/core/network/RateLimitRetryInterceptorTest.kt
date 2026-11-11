package dev.finalish.core.network

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RateLimitRetryInterceptorTest {
    private val start = 1_800_000_000_000L
    private var now = start
    private val slept = mutableListOf<Long>()
    private val sent = mutableListOf<Long>()

    /** A client whose server answers with [codes] in turn (each with its Retry-After, if any). */
    private fun client(vararg codes: Pair<Int, String?>, jitter: Long = 0, maxWaitMs: Long = 120_000): OkHttpClient {
        val queue = ArrayDeque(codes.toList())
        return OkHttpClient.Builder()
            .addInterceptor(
                RateLimitRetryInterceptor(
                    maxWaitMs = maxWaitMs,
                    clock = { now },
                    sleep = { ms -> slept += ms; now += ms },
                    jitter = { jitter },
                    blockedUntil = AtomicLong(0),
                ),
            )
            .addInterceptor { chain ->
                sent += now
                val (code, retryAfter) = queue.removeFirst()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(code).message("x").apply { retryAfter?.let { header("Retry-After", it) } }
                    .body("".toResponseBody()).build()
            }
            .build()
    }

    private fun OkHttpClient.get(): Int =
        newCall(Request.Builder().url("https://api.frame.io/v4/me").build()).execute().use { it.code }

    @Test fun `waits as long as Retry-After seconds asks, beyond the old 5 s cap`() {
        assertEquals(200, client(429 to "30", 200 to null).get())
        assertEquals(listOf(start, start + 30_000), sent)
    }

    @Test fun `reads Retry-After as an HTTP date`() {
        val at = DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(start + 12_000).atOffset(ZoneOffset.UTC))
        assertEquals(200, client(429 to at, 200 to null).get())
        assertEquals(start + 12_000, sent[1])
    }

    @Test fun `without Retry-After waits 1 s then doubles`() {
        assertEquals(200, client(429 to null, 429 to null, 429 to null, 200 to null).get())
        assertEquals(listOf(start, start + 1_000, start + 3_000, start + 7_000), sent)
    }

    @Test fun `never waits less than the doubling, even if Retry-After says 0`() {
        assertEquals(200, client(429 to "0", 200 to null).get())
        assertEquals(start + 1_000, sent[1])
    }

    @Test fun `adds jitter to the wait`() {
        assertEquals(200, client(429 to "2", 200 to null, jitter = 400).get())
        assertEquals(start + 2_400, sent[1])
    }

    @Test fun `gives the 429 back when the wait is beyond the cap`() {
        assertEquals(429, client(429 to "600", 200 to null).get())
        assertEquals(listOf(start), sent)
        assertTrue(slept.isEmpty())
    }

    @Test fun `stops after three retries`() {
        assertEquals(429, client(429 to "1", 429 to "1", 429 to "1", 429 to "1", 200 to null).get())
        assertEquals(4, sent.size)
    }

    @Test fun `does not retry server errors`() {
        assertEquals(503, client(503 to "1", 200 to null).get())
        assertEquals(1, sent.size)
    }

    @Test fun `parses Retry-After forms`() {
        assertEquals(120_000L, RateLimitRetryInterceptor.retryAfterMs(" 120 ", start))
        assertEquals(0L, RateLimitRetryInterceptor.retryAfterMs("Thu, 01 Jan 1970 00:00:00 GMT", start))
        assertNull(RateLimitRetryInterceptor.retryAfterMs("soon", start))
        assertNull(RateLimitRetryInterceptor.retryAfterMs("-5", start))
        assertNull(RateLimitRetryInterceptor.retryAfterMs(null, start))
    }
}
