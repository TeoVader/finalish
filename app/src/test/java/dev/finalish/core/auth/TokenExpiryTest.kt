package dev.finalish.core.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenExpiryTest {
    private val now = 1_000_000_000L

    @Test
    fun unknownExpiryNeverRefreshesAhead() = assertFalse(needsRefresh(null, now))

    @Test
    fun farFromExpiryKeepsTheToken() = assertFalse(needsRefresh(now + 60 * 60_000L, now))

    @Test
    fun refreshesWithinTheMargin() = assertTrue(needsRefresh(now + REFRESH_MARGIN_MS - 1, now))

    @Test
    fun refreshesAnExpiredToken() = assertTrue(needsRefresh(now - 1, now))
}
