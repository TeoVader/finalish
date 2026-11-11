package dev.finalish.core.transfer

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The identity of a partial download, kept next to it: the rendition's validator (see
 * [resumeValidator]) from the response that started the file. Frame.io hands out a fresh signed URL
 * for every run, so the URL can't say whether the bytes on disk belong to the same rendition.
 */
internal fun partIdentity(part: File) = File(part.parentFile, "${part.name}.id")

/**
 * The validator to send as `If-Range` when continuing a download: the strong ETag, else
 * Last-Modified; null when the server gives neither (or only a weak ETag, which If-Range can't use),
 * and then a partial file can't be resumed safely.
 */
internal fun resumeValidator(etag: String?, lastModified: String?): String? =
    etag?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("W/") }
        ?: lastModified?.trim()?.takeIf { it.isNotEmpty() }

/**
 * Where to continue a partial download of [have] bytes, given the answer to `Range: bytes=have-`
 * (with `If-Range: [sent]`): [have] when the server sent exactly the rest of the same file (206 from
 * that byte, the full size matches [expectedTotal] when both are known, and the response's
 * validator [received] matches [sent] when the server gives one), 0 to start over otherwise.
 */
internal fun resumeOffset(
    have: Long,
    code: Int,
    contentRange: String?,
    expectedTotal: Long,
    sent: String? = null,
    received: String? = null,
): Long {
    if (have <= 0 || code != 206 || contentRange == null) return 0
    val m = Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""").find(contentRange) ?: return 0
    if (m.groupValues[1].toLong() != have) return 0
    val full = m.groupValues[3].toLongOrNull()
    if (full != null && expectedTotal > 0 && full != expectedTotal) return 0
    if (sent != null && received != null && sent != received) return 0
    return have
}

/**
 * Downloads [url] into [tmp], continuing what is already there only when it is the same rendition:
 * the request carries `If-Range` with the validator saved when the file was started, so a changed
 * rendition comes back whole (200) and replaces the partial bytes. A partial file with no saved
 * validator (an older version of the app, or a server that gave none) starts over. The file stays
 * when the run stops, with its identity, so the next run can continue it. Returns the offset the
 * download continued from (0 for a fresh start).
 */
internal suspend fun fetchResumable(
    http: OkHttpClient,
    url: String,
    tmp: File,
    expectedTotal: Long,
    onProgress: (Long, Long) -> Unit,
): Long {
    val identity = partIdentity(tmp)
    val validator = identity.takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
    if (validator == null) tmp.delete()
    val have = if (tmp.exists()) tmp.length() else 0L
    val request = Request.Builder().url(url).apply {
        if (have > 0 && validator != null) {
            header("Range", "bytes=$have-")
            header("If-Range", validator)
        }
    }.build()
    http.newCall(request).execute().use { resp ->
        if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
        val received = resumeValidator(resp.header("ETag"), resp.header("Last-Modified"))
        val start = resumeOffset(have, resp.code, resp.header("Content-Range"), expectedTotal, validator, received)
        if (resp.code == 206 && start == 0L) {
            // Part of a different file (the rendition changed): start over with the whole file.
            tmp.delete()
            identity.delete()
            return fetchResumable(http, url, tmp, expectedTotal, onProgress)
        }
        if (start == 0L) {
            // A fresh file: its identity is saved before the first byte, so a later run can check it.
            if (received != null) identity.writeText(received) else identity.delete()
        }
        val body = resp.body
        val length = body.contentLength()
        val total = if (length >= 0) start + length else -1
        var done = start
        var lastReport = 0L
        body.byteStream().use { input ->
            FileOutputStream(tmp, start > 0).use { out ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    val now = System.currentTimeMillis()
                    if (now - lastReport > 250) {
                        lastReport = now
                        onProgress(done, total)
                    }
                }
            }
        }
        return start
    }
}
