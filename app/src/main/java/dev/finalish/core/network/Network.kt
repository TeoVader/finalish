package dev.finalish.core.network

import android.content.Context
import androidx.annotation.StringRes
import dev.finalish.BuildConfig
import dev.finalish.R
import dev.finalish.core.auth.AuthManager
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Authenticator
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

val AppJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
    isLenient = true
    encodeDefaults = false
}

object Network {

    /** Plain client: IMS token calls, S3 uploads, media downloads. */
    fun baseClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun apiClient(base: OkHttpClient, auth: AuthManager): OkHttpClient = base.newBuilder()
        .addInterceptor(DemoInterceptor { auth.isDemo })
        .addInterceptor(AuthInterceptor(auth))
        .addInterceptor(RateLimitRetryInterceptor())
        .addInterceptor(ErrorLogInterceptor())
        .authenticator(TokenAuthenticator(auth))
        .apply {
            if (BuildConfig.DEBUG) {
                addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
            }
        }
        .build()

    fun api(client: OkHttpClient): FrameioApi = Retrofit.Builder()
        .baseUrl(FrameioApi.BASE_URL)
        .client(client)
        .addConverterFactory(AppJson.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(FrameioApi::class.java)
}

/**
 * Hosts that get the Frame.io bearer token. Every call through [Network.apiClient] goes to the
 * V4 API ([FrameioApi.BASE_URL]); uploads (presigned S3) and media downloads use the plain client,
 * but should a redirect or a full URL ever lead elsewhere, the token must not follow it.
 */
private val TOKEN_HOSTS = setOf(FrameioApi.BASE_URL.toHttpUrl().host)

private class AuthInterceptor(private val auth: AuthManager) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!request.isHttps || request.url.host !in TOKEN_HOSTS) return chain.proceed(request)
        val token = auth.validAccessToken() ?: return chain.proceed(request)
        return chain.proceed(request.withToken(token))
    }
}

private class TokenAuthenticator(private val auth: AuthManager) : Authenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        if (response.priorResponse != null) return null // already retried once
        if (response.request.url.host !in TOKEN_HOSTS) return null
        val stale = response.request.header("Authorization")?.removePrefix("Bearer ")
        val fresh = runBlocking { auth.refresh(stale) } ?: return null
        return response.request.withToken(fresh)
    }
}

/**
 * Logs Frame.io API calls (method, path, status, duration — never headers/tokens/queries) so a
 * bug report shows what the app asked for, and the body of failed calls to diagnose them.
 */
/**
 * What an error body says, short, for the log (which can end up in a bug report): the API's own
 * error codes and titles, not the whole body, which may quote names the user typed.
 */
internal fun errorSummary(body: String): String {
    val parts = Regex(""""(?:code|title|error)"\s*:\s*"([^"]{1,80})"""").findAll(body).map { it.groupValues[1] }.distinct().take(4).toList()
    return if (parts.isNotEmpty()) parts.joinToString(" · ") else "(${body.length} chars)"
}

private class ErrorLogInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val started = System.nanoTime()
        val response = chain.proceed(chain.request())
        if (response.isSuccessful && chain.request().url.host == "api.frame.io") {
            val ms = (System.nanoTime() - started) / 1_000_000
            dev.finalish.core.util.AppLog.i("API ${chain.request().method} ${chain.request().url.encodedPath} -> ${response.code} ${ms}ms")
        }
        if (!response.isSuccessful) {
            val body = runCatching { response.peekBody(2048).string() }.getOrDefault("")
            dev.finalish.core.util.AppLog.w("${chain.request().method} ${chain.request().url.encodedPath} -> ${response.code} ${errorSummary(body)}")
            // Plan refusals come as 422 (shares) or 400 (groups).
            if (response.code == 400 || response.code == 422) {
                PlanLimits.accountOf(chain.request().url.pathSegments)?.let { PlanLimits.record(it, PlanLimits.featuresIn(body)) }
            }
        }
        return response
    }
}

/**
 * Honors 429 responses as Frame.io asks: wait at least a second, then double (1 s, 2 s, 4 s).
 * Limits are per user (leaky bucket), so one person's app only competes with their own requests:
 * the wait is shared, and every call (new ones too) holds off until it's over instead of each
 * hitting the limit and sleeping on its own. A call canceled meanwhile stops waiting.
 */
private class RateLimitRetryInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        var attempt = 0
        while (true) {
            awaitTurn(chain)
            val response = chain.proceed(chain.request())
            if (response.code != 429 || attempt >= 3) return response
            val wait = response.header("Retry-After")?.toLongOrNull()?.times(1000) ?: (1_000L shl attempt)
            response.close()
            val until = System.currentTimeMillis() + wait.coerceAtMost(5_000)
            blockedUntil.accumulateAndGet(until) { a, b -> maxOf(a, b) }
            attempt++
        }
    }

    private fun awaitTurn(chain: Interceptor.Chain) {
        while (true) {
            val left = blockedUntil.get() - System.currentTimeMillis()
            if (left <= 0) return
            if (chain.call().isCanceled()) throw IOException("Canceled")
            try {
                Thread.sleep(left.coerceAtMost(250))
            } catch (e: InterruptedException) {
                throw IOException(e)
            }
        }
    }

    companion object {
        /** When Frame.io accepts requests again (epoch ms), for every API call of the app. */
        private val blockedUntil = java.util.concurrent.atomic.AtomicLong(0)
    }
}

private fun Request.withToken(token: String): Request = newBuilder()
    .header("Authorization", "Bearer $token")
    .build()

/** True for a [userMessage] that says the account's plan lacks the feature (nothing to retry). */
fun isPlanLimitMessage(message: String?): Boolean =
    message?.startsWith("Your Frame.io plan doesn't include") == true ||
        dev.finalish.core.util.AppLocale.appContext?.let { c ->
            val prefix = c.getString(R.string.core_plan_limit, "\u0000").substringBefore('\u0000')
            prefix.isNotEmpty() && message?.startsWith(prefix) == true
        } == true

/** As [isPlanLimitMessage], for a message from [userMessage] with a Context (in the app's language). */
fun isPlanLimitMessage(context: Context, message: String?): Boolean {
    if (message == null) return false
    val prefix = context.getString(R.string.core_plan_limit, "\u0000").substringBefore('\u0000')
    return (prefix.isNotEmpty() && message.startsWith(prefix)) || isPlanLimitMessage(message)
}

/** Human readable message for API/network errors, in the app's language (English in plain unit tests). */
fun Throwable.userMessage(): String = describe(dev.finalish.core.util.AppLocale.appContext)

/** Human readable message for API/network errors, in the app's language. */
fun Throwable.userMessage(context: Context): String = describe(context)

private fun Throwable.describe(context: Context?): String {
    fun text(@StringRes id: Int, english: String, vararg args: Any): String = context?.getString(id, *args) ?: english
    return when (this) {
        is HttpException -> {
            val body = runCatching { response()?.errorBody()?.string() }.getOrNull()
            val detail = body?.let {
                runCatching {
                    AppJson.parseToJsonElement(it).jsonObject["errors"]?.jsonArray?.firstOrNull()
                        ?.jsonObject?.get("detail")?.jsonPrimitive?.content
                }.getOrNull()
            }
                // 422 details are prefixed with the field name ("private: Cannot …").
                ?.replaceFirst(Regex("""^[a-z_]+:\s+(?=[A-Z])"""), "")
            // "comment_attachments not included in plan" / "feature(s) not included in plan: secure_sharing",
            // possibly several errors at once (expiration and passphrase on a Free plan).
            val planLimits = PlanLimits.featuresIn(body)
            when {
                planLimits.isNotEmpty() -> context?.let { PlanLimits.message(it, planLimits) } ?: PlanLimits.message(planLimits)
                detail?.startsWith("no route found") == true ->
                    text(R.string.core_error_no_api_route, "Frame.io doesn't offer this through its API for your account yet.")
                else -> null
            } ?: when (code()) {
                401 -> detail ?: text(R.string.core_error_session_expired, "Your session has expired. Please sign in again.")
                403 -> detail ?: text(R.string.core_error_forbidden, "You don't have permission to do that.")
                404 -> detail ?: text(R.string.core_error_not_found, "This item no longer exists.")
                429 -> text(R.string.core_error_rate_limited, "Frame.io is rate limiting requests. Try again in a moment.")
                else -> detail ?: text(R.string.core_error_http, "Frame.io returned an error (${code()}).", code())
            }
        }
        is IOException -> {
            val chain = generateSequence<Throwable>(this) { it.cause }.take(8).toList()
            when {
                chain.any { it.isStorageFull() } -> text(R.string.core_error_storage_full, "Not enough free space on this phone.")
                chain.any { it.isConnectivity() } -> text(R.string.core_error_offline, "You appear to be offline.")
                chain.any { it is java.io.FileNotFoundException } -> text(R.string.core_error_file, "Couldn't read or write the file on this phone.")
                else -> text(R.string.core_error_network, "The connection to Frame.io failed. Try again.")
            }
        }
        else -> message ?: text(R.string.core_error_generic, "Something went wrong.")
    }
}

/** Disk full (ENOSPC), as the platform reports it from a write. */
private fun Throwable.isStorageFull(): Boolean =
    message?.let { "ENOSPC" in it || "No space left" in it } == true

/** The network isn't there or doesn't answer: no route, DNS failure, refused/reset connection, timeout. */
private fun Throwable.isConnectivity(): Boolean =
    this is java.net.UnknownHostException ||
        this is java.net.SocketException || // ConnectException, NoRouteToHostException, connection reset
        this is java.net.SocketTimeoutException ||
        (this is java.io.InterruptedIOException && message?.contains("timeout", ignoreCase = true) == true)
