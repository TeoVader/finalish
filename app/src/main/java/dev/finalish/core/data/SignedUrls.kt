package dev.finalish.core.data

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.time.Duration
import java.time.Instant

/** A link this close to its expiry is treated as expired, so it isn't handed out just before it stops working. */
val SIGNED_URL_EXPIRY_MARGIN: Duration = Duration.ofMinutes(5)

/**
 * True when [url] is a signed link whose `Expires` (thumbnails) or `exp` (avatars) query parameter
 * (epoch seconds) is past, or within [SIGNED_URL_EXPIRY_MARGIN] of [now]. A link without one, or
 * with one that isn't an integer, never expires.
 */
fun signedUrlExpired(url: String, now: Instant = Instant.now()): Boolean {
    val parsed = url.toHttpUrlOrNull() ?: return false
    val expires = EXPIRY_PARAMETERS.firstNotNullOfOrNull { parsed.queryParameter(it)?.toLongOrNull() } ?: return false
    // Compared as numbers: Instant.ofEpochSecond throws on a value outside its range.
    return expires <= now.plus(SIGNED_URL_EXPIRY_MARGIN).epochSecond
}

private val EXPIRY_PARAMETERS = listOf("Expires", "exp")
