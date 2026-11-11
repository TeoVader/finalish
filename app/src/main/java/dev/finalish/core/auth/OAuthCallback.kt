package dev.finalish.core.auth

import java.net.URI
import java.net.URLDecoder
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/** A sign-in started with [AuthManager.buildAuthorizeUri] and not yet completed. */
internal data class PendingSignIn(
    val state: String,
    val verifier: String,
    val redirectUri: String,
    val startedAt: Long,
)

/** Checks an OAuth redirect against the registered redirect URI and the pending sign-in. */
internal object OAuthCallback {
    /** A sign-in left longer than this is refused: Adobe's codes are short-lived anyway. */
    const val MAX_AGE_MS = 10 * 60_000L

    /**
     * True when [callback] has the scheme, host, port and path of [registered]: only the query (code,
     * state, error) may differ. The loopback listener reports `127.0.0.1` whatever the registered
     * loopback host, so `localhost` and `127.0.0.1` count as the same host there.
     */
    fun matches(callback: String, registered: String): Boolean {
        val c = parse(callback) ?: return false
        val r = parse(registered) ?: return false
        if (!c.scheme.equals(r.scheme, ignoreCase = true)) return false
        if (hostKey(c) != hostKey(r)) return false
        if (effectivePort(c) != effectivePort(r)) return false
        return c.rawPath.orEmpty().ifEmpty { "/" } == r.rawPath.orEmpty().ifEmpty { "/" }
    }

    /** The `state` parameter of [callback], if any. */
    fun stateOf(callback: String): String? = query(callback)?.get("state")

    /** True if [callback] looks like an answer from the authorize endpoint (a code or an error). */
    fun carriesResult(callback: String): Boolean {
        val q = query(callback) ?: return false
        return "code" in q || "error" in q
    }

    sealed interface Outcome {
        data class Code(val code: String, val pending: PendingSignIn) : Outcome
        data class Failed(val message: String) : Outcome
    }

    /**
     * Decides what to do with [callback] given the [pending] sign-in at [now]. The caller must have
     * consumed [pending] already (see [AuthManager.completeRedirect]), so the same attempt never
     * completes twice.
     */
    fun check(callback: String, pending: PendingSignIn?, now: Long): Outcome {
        if (pending == null) return Outcome.Failed(EXPIRED)
        if (!matches(callback, pending.redirectUri)) return Outcome.Failed(MISMATCH)
        val q = query(callback) ?: return Outcome.Failed(MISMATCH)
        // Required: PKCE already stops a stolen code, and this also stops a redirect we never started.
        if (q["state"] != pending.state) return Outcome.Failed(MISMATCH)
        if (now - pending.startedAt !in 0..MAX_AGE_MS) return Outcome.Failed(EXPIRED)
        q["error"]?.let { return Outcome.Failed(q["error_description"] ?: it) }
        val code = q["code"]?.takeIf { it.isNotEmpty() } ?: return Outcome.Failed("Missing authorization code")
        return Outcome.Code(code, pending)
    }

    const val EXPIRED = "Sign-in expired, please retry."
    const val MISMATCH = "OAuth state mismatch — please retry."

    private fun parse(uri: String): URI? = runCatching { URI(uri.trim()) }.getOrNull()?.takeIf { it.scheme != null }

    private fun hostKey(u: URI): String? {
        val host = (u.host ?: u.rawAuthority?.substringAfterLast('@')?.substringBefore(':'))?.lowercase()
        return if (host == "localhost") "127.0.0.1" else host
    }

    private fun effectivePort(u: URI): Int = when {
        u.port != -1 -> u.port
        u.scheme.equals("http", ignoreCase = true) -> 80
        u.scheme.equals("https", ignoreCase = true) -> 443
        else -> -1
    }

    /** The decoded query parameters (first value wins), or null for an unparseable URI. */
    private fun query(uri: String): Map<String, String>? {
        val u = parse(uri) ?: return null
        val out = LinkedHashMap<String, String>()
        u.rawQuery?.split('&')?.forEach { pair ->
            if (pair.isEmpty()) return@forEach
            val name = decode(pair.substringBefore('='))
            val value = if ('=' in pair) decode(pair.substringAfter('=')) else ""
            out.putIfAbsent(name, value)
        }
        return out
    }

    private fun decode(s: String) = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
}

/**
 * Token revocation at Adobe IMS (`POST /ims/revoke`, client id + token; no secret for public
 * "OAuth Native App" credentials). Best effort: returns how many tokens Adobe confirmed revoked and
 * never throws.
 */
internal fun revokeTokens(http: OkHttpClient, imsHost: String, clientId: String, tokens: List<String>): Int =
    tokens.filter { it.isNotBlank() }.distinct().count { token ->
        runCatching {
            val body = FormBody.Builder().add("client_id", clientId).add("token", token).build()
            http.newCall(Request.Builder().url("$imsHost/ims/revoke").post(body).build()).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }
