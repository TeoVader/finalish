package dev.finalish.core.auth

import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OAuthCallbackTest {
    private val loopback = "http://127.0.0.1:8765/callback"
    private val custom = "finalish-ims://auth/callback"
    private val now = 1_000_000_000L
    private val pending = PendingSignIn(state = "s1", verifier = "v1", redirectUri = loopback, startedAt = now - 60_000L)

    @Test fun matchesTheRegisteredRedirectWithAnyQuery() {
        assertTrue(OAuthCallback.matches("$loopback?code=c&state=s", loopback))
        assertTrue(OAuthCallback.matches("$custom?code=c", custom))
        assertTrue(OAuthCallback.matches("FINALISH-IMS://AUTH/callback?code=c", custom))
    }

    // The OAuth Native App redirect Adobe hands out: adobe+<hash>://callback, with no path.
    @Test fun matchesTheAdobeNativeRedirect() {
        val adobe = "adobe+0a1b2c3d4e5f://callback"
        assertTrue(OAuthCallback.matches("$adobe?code=c&state=s", adobe))
        assertTrue(OAuthCallback.matches("adobe+0a1b2c3d4e5f://callback/?code=c", adobe))
        assertFalse(OAuthCallback.matches("adobe+ffffffffffff://callback?code=c", adobe))
        assertFalse(OAuthCallback.matches("adobe+0a1b2c3d4e5f://other?code=c", adobe))
    }

    @Test fun loopbackHostsAreTheSame() {
        assertTrue(OAuthCallback.matches("http://127.0.0.1:8765/callback?code=c", "http://localhost:8765/callback"))
    }

    @Test fun defaultPortsMatchTheirExplicitForm() {
        assertTrue(OAuthCallback.matches("http://127.0.0.1/cb?code=c", "http://127.0.0.1:80/cb"))
        assertTrue(OAuthCallback.matches("https://example.org:443/cb?code=c", "https://example.org/cb"))
    }

    @Test fun refusesAnotherScheme() = assertFalse(OAuthCallback.matches("https://127.0.0.1:8765/callback?code=c", loopback))

    @Test fun refusesAnotherHost() {
        assertFalse(OAuthCallback.matches("finalish-ims://evil/callback?code=c", custom))
        assertFalse(OAuthCallback.matches("http://10.0.0.2:8765/callback?code=c", loopback))
    }

    @Test fun refusesAnotherPort() = assertFalse(OAuthCallback.matches("http://127.0.0.1:9999/callback?code=c", loopback))

    @Test fun refusesAnotherPath() {
        assertFalse(OAuthCallback.matches("http://127.0.0.1:8765/other?code=c", loopback))
        assertFalse(OAuthCallback.matches("http://127.0.0.1:8765/callback/x?code=c", loopback))
        assertFalse(OAuthCallback.matches("finalish-ims://auth/?code=c", custom))
    }

    @Test fun refusesGarbage() {
        assertFalse(OAuthCallback.matches("not a uri", loopback))
        assertFalse(OAuthCallback.matches(loopback, ""))
    }

    @Test fun needsACodeOrAnError() {
        assertTrue(OAuthCallback.carriesResult("$loopback?code=c"))
        assertTrue(OAuthCallback.carriesResult("$loopback?error=access_denied"))
        assertFalse(OAuthCallback.carriesResult("$loopback?state=s"))
    }

    @Test fun acceptsTheCodeOfThePendingAttempt() {
        val outcome = OAuthCallback.check("$loopback?code=a%2Bb&state=s1", pending, now)
        assertEquals(OAuthCallback.Outcome.Code("a+b", pending), outcome)
    }

    @Test fun refusesWithoutAPendingAttempt() =
        assertEquals(OAuthCallback.Outcome.Failed(OAuthCallback.EXPIRED), OAuthCallback.check("$loopback?code=c&state=s1", null, now))

    @Test fun refusesAWrongOrMissingState() {
        val mismatch = OAuthCallback.Outcome.Failed(OAuthCallback.MISMATCH)
        assertEquals(mismatch, OAuthCallback.check("$loopback?code=c&state=other", pending, now))
        assertEquals(mismatch, OAuthCallback.check("$loopback?code=c", pending, now))
    }

    @Test fun refusesARedirectToAnotherPath() =
        assertEquals(
            OAuthCallback.Outcome.Failed(OAuthCallback.MISMATCH),
            OAuthCallback.check("http://127.0.0.1:8765/elsewhere?code=c&state=s1", pending, now),
        )

    @Test fun refusesAnExpiredAttempt() {
        val old = pending.copy(startedAt = now - OAuthCallback.MAX_AGE_MS - 1)
        assertEquals(OAuthCallback.Outcome.Failed(OAuthCallback.EXPIRED), OAuthCallback.check("$loopback?code=c&state=s1", old, now))
        val future = pending.copy(startedAt = now + 60_000L)
        assertEquals(OAuthCallback.Outcome.Failed(OAuthCallback.EXPIRED), OAuthCallback.check("$loopback?code=c&state=s1", future, now))
    }

    @Test fun reportsAdobesErrorForThePendingAttempt() =
        assertEquals(
            OAuthCallback.Outcome.Failed("User cancelled"),
            OAuthCallback.check("$loopback?error=access_denied&error_description=User%20cancelled&state=s1", pending, now),
        )

    @Test fun readsTheState() {
        assertEquals("s1", OAuthCallback.stateOf("$loopback?code=c&state=s1"))
        assertEquals(null, OAuthCallback.stateOf("$loopback?code=c"))
    }

    @Test fun revokesEachTokenWithTheClientIdAndNoSecret() {
        val bodies = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("POST", request.method)
            assertEquals("https://ims.test/ims/revoke", request.url.toString())
            assertEquals(null, request.header("Authorization"))
            bodies += Buffer().also { request.body!!.writeTo(it) }.readUtf8()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body("".toResponseBody()).build()
        }.build()
        assertEquals(2, revokeTokens(client, "https://ims.test", "client", listOf("refresh", "access", "access", "")))
        assertEquals(listOf("client_id=client&token=refresh", "client_id=client&token=access"), bodies)
    }

    @Test fun revocationNeverThrows() {
        var calls = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            if (calls++ == 0) throw IOException("offline")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(400).message("Bad").body("".toResponseBody()).build()
        }.build()
        assertEquals(0, revokeTokens(client, "https://ims.test", "client", listOf("refresh", "access")))
        assertEquals(2, calls)
    }
}
