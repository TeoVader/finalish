package dev.finalish.core.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class SignedUrlsTest {
    private val now = Instant.ofEpochSecond(1_800_000_000)

    @Test fun `a past expiry has expired`() =
        assertTrue(signedUrlExpired("https://example.com/t.jpg?Expires=1700000000", now))

    @Test fun `a future expiry has not`() =
        assertFalse(signedUrlExpired("https://example.com/t.jpg?Expires=1800086400", now))

    @Test fun `a link about to expire counts as expired`() =
        assertTrue(signedUrlExpired("https://example.com/t.jpg?Expires=${now.epochSecond + 60}", now))

    @Test fun `no expiry never expires`() {
        assertFalse(signedUrlExpired("https://example.com/t.jpg", now))
        assertFalse(signedUrlExpired("https://example.com/t.jpg?w=200", now))
    }

    @Test fun `an unparsable expiry or url never expires`() {
        assertFalse(signedUrlExpired("https://example.com/t.jpg?Expires=soon", now))
        assertFalse(signedUrlExpired("https://example.com/t.jpg?Expires=", now))
        assertFalse(signedUrlExpired("not a url", now))
        assertFalse(signedUrlExpired("https://example.com/t.jpg?Expires=${Long.MAX_VALUE}", now))
    }

    @Test fun `the expiry is found among other parameters`() {
        assertTrue(signedUrlExpired("https://example.com/t.jpg?Signature=abc&Expires=1700000000&Key-Pair-Id=K1", now))
        assertFalse(signedUrlExpired("https://example.com/t.jpg?Signature=abc&Expires=1800086400&Key-Pair-Id=K1", now))
    }
}
