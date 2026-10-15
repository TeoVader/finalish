package dev.finalish.core.media

import okhttp3.OkHttpClient
import okhttp3.Request
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Lists a remote ZIP's contents without downloading it: two HTTP range requests,
 * one for the end-of-central-directory record and one for the central directory.
 */
object ZipPeek {
    data class Entry(val name: String, val size: Long)

    private const val EOCD_SIG = 0x06054b50
    private const val CDH_SIG = 0x02014b50
    private const val EOCD_MIN = 22
    private const val MAX_COMMENT = 0xFFFF
    /** Larger directories (thousands of files) aren't worth listing on a phone. */
    private const val MAX_DIRECTORY = 2 * 1024 * 1024

    /** Null when the server ignores ranges or the archive is ZIP64/unreadable. */
    fun list(http: OkHttpClient, url: String, fileSize: Long): List<Entry>? {
        if (fileSize < EOCD_MIN) return null
        val tailLen = minOf(fileSize, (EOCD_MIN + MAX_COMMENT).toLong()).toInt()
        val tail = range(http, url, fileSize - tailLen, fileSize - 1) ?: return null
        val buf = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN)
        val eocd = (tail.size - EOCD_MIN downTo 0).firstOrNull { buf.getInt(it) == EOCD_SIG } ?: return null
        val count = buf.getShort(eocd + 10).toInt() and 0xFFFF
        val dirSize = buf.getInt(eocd + 12).toLong() and 0xFFFFFFFFL
        val dirOffset = buf.getInt(eocd + 16).toLong() and 0xFFFFFFFFL
        if (dirOffset == 0xFFFFFFFFL || dirSize > MAX_DIRECTORY || dirOffset + dirSize > fileSize) return null
        if (dirSize == 0L) return emptyList()
        val dir = range(http, url, dirOffset, dirOffset + dirSize - 1) ?: return null
        return parseDirectory(dir, count)
    }

    internal fun parseDirectory(dir: ByteArray, count: Int): List<Entry> {
        val b = ByteBuffer.wrap(dir).order(ByteOrder.LITTLE_ENDIAN)
        val out = ArrayList<Entry>(count)
        var p = 0
        while (p + 46 <= dir.size && b.getInt(p) == CDH_SIG) {
            val size = b.getInt(p + 24).toLong() and 0xFFFFFFFFL
            val nameLen = b.getShort(p + 28).toInt() and 0xFFFF
            val extraLen = b.getShort(p + 30).toInt() and 0xFFFF
            val commentLen = b.getShort(p + 32).toInt() and 0xFFFF
            if (p + 46 + nameLen > dir.size) break
            val name = String(dir, p + 46, nameLen, Charsets.UTF_8)
            // Folders and macOS metadata are noise in a contents list.
            if (!name.endsWith("/") && !name.startsWith("__MACOSX/") && !name.substringAfterLast('/').startsWith("._")) {
                out += Entry(name, size)
            }
            p += 46 + nameLen + extraLen + commentLen
        }
        return out
    }

    private fun range(http: OkHttpClient, url: String, from: Long, to: Long): ByteArray? {
        val req = Request.Builder().url(url).header("Range", "bytes=$from-$to").build()
        return http.newCall(req).execute().use { r -> if (r.code == 206) r.body.bytes() else null }
    }
}
