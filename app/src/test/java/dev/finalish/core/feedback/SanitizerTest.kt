package dev.finalish.core.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reports leave the device: nothing secret or personal may survive sanitising. */
class SanitizerTest {

    @Test fun bearerTokens() {
        val out = Sanitizer.clean("Authorization: Bearer eyJhbGciOi.abc-123_xyz")
        assertEquals("Authorization: Bearer [token]", out)
    }

    @Test fun oauthTokensAndCodes() {
        val out = Sanitizer.clean("""{"access_token":"abc.def","refresh_token": "xyz"} callback?code=SECRET123&state=1""")
        assertFalse("abc.def" in out)
        assertFalse("xyz\"" in out)
        assertFalse("SECRET123" in out)
    }

    @Test fun signedUrlsKeepPathOnly() {
        val out = Sanitizer.clean("GET https://assets.frame.io/uploads/a/b.mp4?X-Amz-Signature=deadbeef&X-Amz-Credential=K -> 403")
        assertTrue(out.startsWith("GET https://assets.frame.io/uploads/a/b.mp4?[…]"))
        assertFalse("deadbeef" in out)
    }

    @Test fun emails() {
        assertEquals("user [email] signed in", Sanitizer.clean("user alex.rivera@example.com signed in"))
    }

    @Test fun ordinaryTextUntouched() {
        val line = "10-10 01:00:00.000 W Playback error ERROR_CODE_DECODING_FAILED"
        assertEquals(line, Sanitizer.clean(line))
    }
}
