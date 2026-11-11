package dev.finalish.core.network

import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Honors 429 responses as Frame.io asks: wait at least a second, then at least double (1 s, 2 s,
 * 4 s), or as long as the server's `Retry-After` says (seconds or an HTTP date), plus a little
 * random jitter so calls held back together don't all come back at the same instant.
 *
 * Limits are per user (leaky bucket), so one person's app only competes with their own requests:
 * the wait is shared, and every call (new ones too) holds off until it's over instead of each
 * hitting the limit and sleeping on its own. A call canceled meanwhile stops waiting. A wait longer
 * than [maxWaitMs] isn't worth holding a call for: the 429 goes back to the caller, which shows
 * "Frame.io is rate limiting requests" (screens retry on their own, transfers through WorkManager).
 *
 * Server errors (5xx) are not retried here: Frame.io asks for at least 30 seconds, with jitter and
 * a growing wait, which background work gets from WorkManager ([SERVER_ERROR_BACKOFF_S]) and
 * screens from their slower refresh.
 */
internal class RateLimitRetryInterceptor(
    private val maxRetries: Int = 3,
    private val maxWaitMs: Long = MAX_WAIT_MS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: (Long) -> Unit = Thread::sleep,
    private val jitter: (Long) -> Long = { wait -> (Math.random() * (wait / 4 + 250)).toLong() },
    private val blockedUntil: AtomicLong = sharedBlockedUntil,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        var attempt = 0
        while (true) {
            awaitTurn(chain)
            val response = chain.proceed(chain.request())
            if (response.code != 429 || attempt >= maxRetries) return response
            val asked = retryAfterMs(response.header("Retry-After"), clock())
            val wait = maxOf(asked ?: 0L, 1_000L shl attempt)
            if (wait > maxWaitMs) return response
            response.close()
            val until = clock() + wait + jitter(wait)
            blockedUntil.accumulateAndGet(until) { a, b -> maxOf(a, b) }
            attempt++
        }
    }

    private fun awaitTurn(chain: Interceptor.Chain) {
        while (true) {
            val left = blockedUntil.get() - clock()
            if (left <= 0) return
            if (chain.call().isCanceled()) throw IOException("Canceled")
            try {
                sleep(left.coerceAtMost(250))
            } catch (e: InterruptedException) {
                throw IOException(e)
            }
        }
    }

    companion object {
        /** Longest wait a call is held for before its 429 goes back to the caller. */
        const val MAX_WAIT_MS = 120_000L

        /** When Frame.io accepts requests again (epoch ms), for every API call of the app. */
        private val sharedBlockedUntil = AtomicLong(0)

        /**
         * The wait a `Retry-After` value asks for, in ms: delay-seconds ("120") or an HTTP date
         * ("Wed, 21 Oct 2026 07:28:00 GMT"). Null when missing or unreadable; 0 for a date already past.
         */
        fun retryAfterMs(value: String?, now: Long): Long? {
            val v = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            v.toLongOrNull()?.let { return if (it < 0) null else it * 1000 }
            val at = runCatching { ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
                ?: return null
            return (at - now).coerceAtLeast(0)
        }
    }
}

/**
 * First wait before WorkManager runs a job again after a failure: Frame.io asks for at least
 * 30 seconds before retrying a server error, then a growing wait (WorkManager doubles it).
 */
const val SERVER_ERROR_BACKOFF_S = 30L
