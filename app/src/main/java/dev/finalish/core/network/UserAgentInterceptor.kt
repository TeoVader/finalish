package dev.finalish.core.network

import android.os.Build
import dev.finalish.BuildConfig
import okhttp3.Interceptor
import okhttp3.Response

/** Identify API requests independently of OkHttp's default user agent. */
internal class UserAgentInterceptor(
    versionName: String = BuildConfig.VERSION_NAME,
    androidRelease: String = Build.VERSION.RELEASE,
) : Interceptor {
    private val userAgent = "Finalish/$versionName (Android $androidRelease; +https://github.com/TeoVader/finalish)"

    override fun intercept(chain: Interceptor.Chain): Response = chain.proceed(
        chain.request().newBuilder().header("User-Agent", userAgent).build(),
    )
}
