package dev.finalish.core.network

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test

class UserAgentInterceptorTest {
    @Test fun identifiesClientAndReplacesExistingUserAgent() {
        val client = OkHttpClient.Builder()
            .addInterceptor(UserAgentInterceptor("0.2.2", "16"))
            .addInterceptor { chain ->
                assertEquals(
                    listOf("Finalish/0.2.2 (Android 16; +https://github.com/TeoVader/finalish)"),
                    chain.request().headers.values("User-Agent"),
                )
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body("".toResponseBody()).build()
            }.build()
        listOf(null, "Another client").forEach { existing ->
            val request = Request.Builder().url("https://api.frame.io/test").apply {
                if (existing != null) header("User-Agent", existing)
            }.build()
            client.newCall(request).execute().use { assertEquals(200, it.code) }
        }
    }
}
