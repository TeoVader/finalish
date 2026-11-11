package dev.finalish.core.transfer

import java.io.File
import kotlinx.coroutines.test.runTest
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ResumableDownloadTest {
    @get:Rule val dir = TemporaryFolder()

    /** A stand-in for the media host: honours Range and If-Range like S3 does. */
    private class FakeHost(var content: ByteArray, var etag: String?, var honourIfRange: Boolean = true) {
        val requests = mutableListOf<Headers>()
        val client: OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
            val req = chain.request()
            requests += req.headers
            val range = req.header("Range")?.removePrefix("bytes=")?.removeSuffix("-")?.toLongOrNull()
            val ifRange = req.header("If-Range")
            val partial = range != null && (!honourIfRange || ifRange == null || ifRange == etag)
            val body = if (partial) content.copyOfRange(range!!.toInt(), content.size) else content
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1)
                .code(if (partial) 206 else 200).message("OK")
                .apply {
                    etag?.let { header("ETag", it) }
                    if (partial) header("Content-Range", "bytes $range-${content.size - 1}/${content.size}")
                }
                .body(body.toResponseBody()).build()
        }.build()
    }

    private val v1 = ByteArray(5000) { (it % 251).toByte() }
    private val v2 = ByteArray(5000) { (it % 13).toByte() }

    private fun part(): File = File(dir.root, "file.mp4.run.part")

    /** A partial file of [bytes] bytes of [content], left by an earlier run with identity [etag]. */
    private fun leftover(content: ByteArray, bytes: Int, etag: String?): File = part().also { f ->
        f.writeBytes(content.copyOfRange(0, bytes))
        etag?.let { partIdentity(f).writeText(it) }
    }

    @Test fun freshDownloadSavesItsIdentity() = runTest {
        val host = FakeHost(v1, "\"a\"")
        val tmp = part()
        assertEquals(0L, fetchResumable(host.client, "https://media.test/1", tmp, -1) { _, _ -> })
        assertArrayEquals(v1, tmp.readBytes())
        assertEquals("\"a\"", partIdentity(tmp).readText())
        assertNull(host.requests.single()["Range"])
    }

    @Test fun sameRenditionContinuesWithIfRange() = runTest {
        val host = FakeHost(v1, "\"a\"")
        val tmp = leftover(v1, 1000, "\"a\"")
        // A fresh signed URL, as Frame.io gives every run.
        assertEquals(1000L, fetchResumable(host.client, "https://media.test/2?sig=new", tmp, 5000) { _, _ -> })
        assertArrayEquals(v1, tmp.readBytes())
        val sent = host.requests.single()
        assertEquals("bytes=1000-", sent["Range"])
        assertEquals("\"a\"", sent["If-Range"])
    }

    @Test fun changedRenditionComesBackWholeAndReplacesThePartialFile() = runTest {
        val host = FakeHost(v2, "\"b\"")
        val tmp = leftover(v1, 1000, "\"a\"")
        // Same size, so the old offset-and-size check alone would have spliced two files together.
        assertEquals(0L, fetchResumable(host.client, "https://media.test/3", tmp, 5000) { _, _ -> })
        assertArrayEquals(v2, tmp.readBytes())
        assertEquals("\"b\"", partIdentity(tmp).readText())
    }

    @Test fun aHostIgnoringIfRangeIsCaughtByTheETagOfThePart() = runTest {
        val host = FakeHost(v2, "\"b\"", honourIfRange = false)
        val tmp = leftover(v1, 1000, "\"a\"")
        assertEquals(0L, fetchResumable(host.client, "https://media.test/4", tmp, 5000) { _, _ -> })
        assertArrayEquals(v2, tmp.readBytes())
        assertEquals(2, host.requests.size)
        assertNull(host.requests[1]["Range"])
    }

    @Test fun aPartialFileWithoutIdentityStartsOver() = runTest {
        val host = FakeHost(v1, "\"a\"")
        val tmp = leftover(v2, 1000, etag = null)
        assertEquals(0L, fetchResumable(host.client, "https://media.test/5", tmp, 5000) { _, _ -> })
        assertArrayEquals(v1, tmp.readBytes())
        assertNull(host.requests.single()["Range"])
    }

    @Test fun aHostWithoutValidatorsNeverResumes() = runTest {
        val host = FakeHost(v1, etag = null)
        val tmp = part()
        fetchResumable(host.client, "https://media.test/6", tmp, -1) { _, _ -> }
        assertFalse(partIdentity(tmp).exists())
        tmp.writeBytes(v2.copyOfRange(0, 1000))
        fetchResumable(host.client, "https://media.test/6", tmp, -1) { _, _ -> }
        assertArrayEquals(v1, tmp.readBytes())
        assertNull(host.requests[1]["Range"])
    }

    @Test fun validatorPrefersAStrongETag() {
        assertEquals("\"a\"", resumeValidator("\"a\"", "Tue, 10 Nov 2026 10:00:00 GMT"))
        assertEquals("Tue, 10 Nov 2026 10:00:00 GMT", resumeValidator("W/\"a\"", "Tue, 10 Nov 2026 10:00:00 GMT"))
        assertNull(resumeValidator("W/\"a\"", null))
        assertNull(resumeValidator(null, " "))
    }
}
