package dev.finalish.core.media

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipPeekTest {

    private fun zip(vararg files: Pair<String, Int>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            files.forEach { (name, size) ->
                z.putNextEntry(ZipEntry(name))
                if (!name.endsWith("/")) z.write(ByteArray(size) { 7 })
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** The central directory, located the way ZipPeek finds it on the server. */
    private fun directory(bytes: ByteArray): Pair<ByteArray, Int> {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val eocd = (bytes.size - 22 downTo 0).first { b.getInt(it) == 0x06054b50 }
        val count = b.getShort(eocd + 10).toInt()
        val size = b.getInt(eocd + 12)
        val offset = b.getInt(eocd + 16)
        return bytes.copyOfRange(offset, offset + size) to count
    }

    @Test fun listsFilesWithSizes() {
        val (dir, count) = directory(zip("logo_white.svg" to 420, "renders/endcard.png" to 6000))
        assertEquals(
            listOf(ZipPeek.Entry("logo_white.svg", 420), ZipPeek.Entry("renders/endcard.png", 6000)),
            ZipPeek.parseDirectory(dir, count),
        )
    }

    @Test fun skipsFoldersAndMacMetadata() {
        val (dir, count) = directory(
            zip("site/" to 0, "site/index.html" to 10, "__MACOSX/site/._index.html" to 5, "site/._thumb" to 3),
        )
        assertEquals(listOf(ZipPeek.Entry("site/index.html", 10)), ZipPeek.parseDirectory(dir, count))
    }
}
