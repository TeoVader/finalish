package dev.finalish.core.transfer

import java.io.IOException
import java.net.SocketTimeoutException
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class TransientTest {
    private fun http(code: Int) = HttpException(Response.error<Unit>(code, "".toResponseBody(null)))

    @Test fun `network failures are worth retrying`() {
        assertTrue(IOException("reset").isTransient())
        assertTrue(SocketTimeoutException().isTransient())
    }

    @Test fun `server errors and rate limits are worth retrying`() {
        assertTrue(http(500).isTransient())
        assertTrue(http(503).isTransient())
        assertTrue(http(429).isTransient())
    }

    @Test fun `client errors and bugs are final`() {
        assertFalse(http(400).isTransient())
        assertFalse(http(403).isTransient())
        assertFalse(http(404).isTransient())
        assertFalse(IllegalStateException("bug").isTransient())
    }
}
