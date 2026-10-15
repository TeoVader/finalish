package dev.finalish.core.auth

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.core.net.toUri
import dev.finalish.BuildConfig
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

sealed interface AuthState {
    data object SignedOut : AuthState
    data object SignedIn : AuthState
}

/** Where the OAuth client configuration comes from. */
data class ImsConfig(val clientId: String, val redirectUri: String) {
    val isValid: Boolean get() = clientId.isNotBlank() && redirectUri.isNotBlank()
    val isLoopback: Boolean get() = redirectUri.startsWith("http://127.0.0.1") || redirectUri.startsWith("http://localhost")

    /** Custom-scheme redirects only work if the scheme was compiled into the manifest. */
    val schemeSupported: Boolean
        get() = isLoopback || redirectUri.substringBefore("://").equals(BuildConfig.REDIRECT_SCHEME, ignoreCase = true)
}

/** Adobe answered the token request with a 4xx: the grant (code or refresh token) is no longer valid. */
class ImsRejected(code: Int, body: String) : Exception("Adobe sign-in failed ($code): $body")

@Serializable
private data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
)

/**
 * Adobe IMS OAuth 2.0 (authorization code + PKCE, "OAuth Native App" credential)
 */
class AuthManager(
    context: Context,
    private val http: OkHttpClient,
    private val json: Json,
) {
    private val store = SecureStore(context)
    private val refreshMutex = Mutex()

    private val _state = MutableStateFlow<AuthState>(initialState())
    val state: StateFlow<AuthState> = _state.asStateFlow()

    /** True after Adobe rejected the refresh token: the sign-in screen explains why. */
    private val _sessionExpired = MutableStateFlow(store.get(K_EXPIRED) == "1")
    val sessionExpired: StateFlow<Boolean> = _sessionExpired.asStateFlow()

    /** Demo mode serves a fake account locally (see DemoInterceptor). */
    @Volatile var isDemo: Boolean = store.get(K_ACCESS) == DEMO_TOKEN
        private set

    var config: ImsConfig
        get() = ImsConfig(
            clientId = store.get(K_CLIENT_ID)?.takeIf { it.isNotBlank() } ?: BuildConfig.IMS_CLIENT_ID,
            redirectUri = store.get(K_REDIRECT)?.takeIf { it.isNotBlank() } ?: BuildConfig.IMS_REDIRECT_URI,
        )
        set(value) {
            store.put(K_CLIENT_ID, value.clientId.trim())
            store.put(K_REDIRECT, value.redirectUri.trim())
        }

    val hasBuiltInConfig: Boolean
        get() = BuildConfig.IMS_CLIENT_ID.isNotBlank() && BuildConfig.IMS_REDIRECT_URI.isNotBlank()

    private fun initialState(): AuthState {
        // Sessions from the removed legacy developer-token sign-in can't refresh: start over with the Adobe ID.
        if (store.get(K_LEGACY) == "1" && store.get(K_ACCESS) != DEMO_TOKEN) {
            listOf(K_ACCESS, K_REFRESH, K_LEGACY, K_EXPIRES).forEach { store.put(it, null) }
        }
        return if (store.get(K_ACCESS) != null) AuthState.SignedIn else AuthState.SignedOut
    }

    fun accessToken(): String? = store.get(K_ACCESS)

    /**
     * The access token, refreshed first when it is about to expire, so the first call after a long
     * pause doesn't fail with a 401 before the refresh (blocking: call off the main thread, as OkHttp
     * does). With no network the current token is used and the 401 path still applies.
     */
    fun validAccessToken(): String? {
        val token = accessToken() ?: return null
        if (isDemo || !needsRefresh(store.get(K_EXPIRES)?.toLongOrNull(), System.currentTimeMillis())) return token
        return kotlinx.coroutines.runBlocking { refresh(token) } ?: accessToken()
    }

    // ---- Authorization code + PKCE ----------------------------------------

    /** Builds the IMS authorize URL and remembers the PKCE verifier/state. */
    fun buildAuthorizeUri(): Uri {
        val cfg = config
        val verifier = randomUrlSafe(64)
        val state = randomUrlSafe(24)
        store.put(K_PKCE, verifier)
        store.put(K_STATE, state)
        val challenge = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
        )
        return "$IMS_HOST/ims/authorize/v2".toUri().buildUpon()
            .appendQueryParameter("client_id", cfg.clientId)
            .appendQueryParameter("redirect_uri", cfg.redirectUri)
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("state", state)
            .build()
    }

    /** True if [uri] is our OAuth redirect. */
    fun isRedirect(uri: Uri): Boolean {
        val redirect = config.redirectUri.toUri()
        return uri.scheme.equals(redirect.scheme, ignoreCase = true) &&
            (uri.getQueryParameter("code") != null || uri.getQueryParameter("error") != null)
    }

    suspend fun completeRedirect(uri: Uri): Result<Unit> = runCatching {
        uri.getQueryParameter("error")?.let { err ->
            error(uri.getQueryParameter("error_description") ?: err)
        }
        val code = requireNotNull(uri.getQueryParameter("code")) { "Missing authorization code" }
        val state = uri.getQueryParameter("state")
        // Required: PKCE already stops a stolen code, and this also stops a redirect we never started.
        check(state != null && state == store.get(K_STATE)) { "OAuth state mismatch — please retry." }
        exchangeCode(code)
    }

    suspend fun exchangeCode(code: String) {
        val cfg = config
        val body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("client_id", cfg.clientId)
            .add("code", code)
            .add("code_verifier", requireNotNull(store.get(K_PKCE)) { "Sign-in expired, please retry." })
            .add("redirect_uri", cfg.redirectUri)
            .build()
        val tokens = postToken(body)
        saveTokens(tokens)
        store.put(K_PKCE, null)
        store.put(K_STATE, null)
    }

    fun signInDemo() {
        isDemo = true
        store.put(K_ACCESS, DEMO_TOKEN)
        clearExpired()
        _state.value = AuthState.SignedIn
    }

    /**
     * Refreshes the access token. Concurrent callers share one network round trip:
     * if another call already rotated the token, we return it without hitting IMS.
     */
    suspend fun refresh(staleToken: String?): String? = refreshMutex.withLock {
        val current = store.get(K_ACCESS)
        if (current != null && current != staleToken) return current
        if (isDemo) return null
        val refresh = store.get(K_REFRESH) ?: return null
        val generation = session
        val body = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("client_id", config.clientId)
            .add("refresh_token", refresh)
            .build()
        runCatching { postToken(body) }
            // A sign-out during the round trip wins: don't bring the session back.
            .onSuccess { if (generation == session) saveTokens(it) else return null }
            // Only a rejected token ends the session; no network keeps it for later.
            .onFailure { if (it is ImsRejected && generation == session) signOut(expired = true) }
            .getOrNull()
            ?.accessToken
    }

    /** Bumped on every sign-out, so a refresh still in flight knows its tokens are no longer wanted. */
    @Volatile private var session = 0

    fun signOut(expired: Boolean = false) {
        session++
        isDemo = false
        listOf(K_ACCESS, K_REFRESH, K_LEGACY, K_EXPIRES, K_PKCE, K_STATE).forEach { store.put(it, null) }
        store.put(K_EXPIRED, if (expired) "1" else null)
        _sessionExpired.value = expired
        _state.value = AuthState.SignedOut
    }

    private suspend fun postToken(body: FormBody): TokenResponse = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$IMS_HOST/ims/token/v3").post(body).build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body.string().orEmpty()
            // A refused grant; rate limits (429) and timeouts (408) are temporary and keep the session.
            if (resp.code in 400..499 && resp.code != 408 && resp.code != 429) throw ImsRejected(resp.code, text.take(200))
            if (!resp.isSuccessful) error("Adobe sign-in failed (${resp.code}): ${text.take(200)}")
            json.decodeFromString(TokenResponse.serializer(), text)
        }
    }

    /** Logs non-sensitive JWT claims (client, scopes, expiry) for troubleshooting. */
    private fun logClaims(token: String) = runCatching {
        val payload = String(Base64.decode(token.split('.')[1], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
        val o = json.parseToJsonElement(payload) as kotlinx.serialization.json.JsonObject
        val keep = listOf("client_id", "scope", "as", "type", "expires_in", "created_at")
        dev.finalish.core.util.AppLog.i("Token claims: " + keep.mapNotNull { k -> o[k]?.let { "$k=$it" } }.joinToString())
    }

    private fun clearExpired() {
        store.put(K_EXPIRED, null)
        _sessionExpired.value = false
    }

    private fun saveTokens(t: TokenResponse) {
        logClaims(t.accessToken)
        store.put(K_ACCESS, t.accessToken)
        t.refreshToken?.let { store.put(K_REFRESH, it) }
        store.put(K_LEGACY, null)
        clearExpired()
        // IMS reports seconds; some versions report milliseconds.
        // Without an expiry the old one would ask for a refresh before every call: forget it.
        store.put(
            K_EXPIRES,
            t.expiresIn?.let {
                val ms = if (it > 10_000_000) it else it * 1000
                (System.currentTimeMillis() + ms).toString()
            },
        )
        _state.value = AuthState.SignedIn
    }

    private fun randomUrlSafe(bytes: Int): String {
        val buf = ByteArray(bytes).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(buf, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    companion object {
        const val IMS_HOST = "https://ims-na1.adobelogin.com"
        const val SCOPES = "openid,email,profile,offline_access,additional_info.roles"
        private const val DEMO_TOKEN = "finalish-demo"

        private const val K_ACCESS = "access"
        private const val K_REFRESH = "refresh"
        private const val K_EXPIRES = "expires"
        /** Set by the removed developer-token sign-in; read once to sign those sessions out. */
        private const val K_LEGACY = "legacy"
        private const val K_PKCE = "pkce"
        private const val K_STATE = "state"
        private const val K_CLIENT_ID = "client_id"
        private const val K_REDIRECT = "redirect_uri"
        private const val K_EXPIRED = "session_expired"
    }
}

/** Refresh this long before the token expires: covers clock skew and a slow first call. */
internal const val REFRESH_MARGIN_MS = 2 * 60_000L

/** True when a token expiring at [expiresAt] (epoch ms; null = unknown) should be refreshed at [now]. */
internal fun needsRefresh(expiresAt: Long?, now: Long): Boolean = expiresAt != null && expiresAt - now <= REFRESH_MARGIN_MS
