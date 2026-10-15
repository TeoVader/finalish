package dev.finalish.core.data

import android.util.LruCache
import dev.finalish.core.network.AppJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/**
 * Two-level (memory + disk) cache of API responses. Screens render the cached copy
 * instantly and then revalidate in the background, which is what makes navigation
 * feel immediate even on slow connections — and gives a read-only offline mode.
 *
 * Both levels are bounded: memory by the entries' JSON size, disk by [MAX_DISK_BYTES] and
 * [MAX_AGE_MS] (keys that include `updatedAt` leave old files behind), trimmed on the first
 * write of a session and every [TRIM_EVERY] writes after.
 */
class JsonCache(root: File) {
    private val dir = File(root, "api").apply { mkdirs() }
    private val memory = object : LruCache<String, Entry>(MAX_MEMORY_CHARS) {
        override fun sizeOf(key: String, value: Entry) = value.size
    }
    private val writes = AtomicInteger()

    /** A decoded value and its weight: JSON length plus a fixed overhead, so tiny entries count too. */
    private class Entry(val value: Any, val size: Int)

    @Suppress("UNCHECKED_CAST")
    fun <T> peek(key: String): T? = memory.get(key)?.value as T?

    suspend fun <T> get(key: String, serializer: KSerializer<T>): T? {
        peek<T>(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            val f = file(key)
            if (!f.exists()) return@withContext null
            runCatching {
                val json = f.readText()
                AppJson.decodeFromString(serializer, json).also { remember(key, it, json.length) }
            }
                // Read again: the age cap and the oldest-first trim spare it.
                .onSuccess { f.setLastModified(System.currentTimeMillis()) }
                .getOrNull()
        }
    }

    suspend fun <T> put(key: String, serializer: KSerializer<T>, value: T) {
        withContext(Dispatchers.IO) {
            runCatching {
                val json = AppJson.encodeToString(serializer, value)
                remember(key, value, json.length)
                // A temp file of its own: two writes of the same key never share one.
                val tmp = File.createTempFile(hash(key), ".tmp", dir)
                tmp.writeText(json)
                if (!tmp.renameTo(file(key))) tmp.delete()
            }
            if (writes.getAndIncrement() % TRIM_EVERY == 0) trimDir(dir)
        }
    }

    private fun remember(key: String, value: Any?, jsonLength: Int) {
        memory.put(key, Entry(value as Any, jsonLength + ENTRY_OVERHEAD))
    }

    fun invalidate(key: String) {
        memory.remove(key)
        file(key).delete()
    }

    fun clear() {
        memory.evictAll()
        dir.listFiles()?.forEach { it.delete() }
    }

    fun sizeBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0

    private fun file(key: String) = File(dir, "${hash(key)}.json")

    private fun hash(key: String): String =
        MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        /** ~16 MB of JSON decoded in memory: room for a few very large folders. */
        const val MAX_MEMORY_CHARS = 16 * 1024 * 1024
        const val ENTRY_OVERHEAD = 512
        const val MAX_DISK_BYTES = 50L * 1024 * 1024
        const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000
        const val TRIM_EVERY = 200

        /** Leftover temp files older than this come from a write that never finished. */
        private const val STALE_TMP_MS = 60L * 60 * 1000

        /**
         * Deletes files not written or read for [maxAgeMs] (and abandoned temp files), then the
         * least recently used ones until the folder fits in [maxBytes].
         */
        internal fun trimDir(
            dir: File,
            maxBytes: Long = MAX_DISK_BYTES,
            maxAgeMs: Long = MAX_AGE_MS,
            now: Long = System.currentTimeMillis(),
        ) {
            val files = dir.listFiles()?.filter { it.isFile }.orEmpty()
                .map { Triple(it, it.lastModified(), it.length()) }
                .sortedBy { it.second }
            var total = files.sumOf { it.third }
            for ((f, modified, length) in files) {
                val tmp = f.name.endsWith(".tmp")
                val stale = now - modified > if (tmp) STALE_TMP_MS else maxAgeMs
                // A recent temp file may be a write in progress: only age removes it.
                if ((stale || (total > maxBytes && !tmp)) && f.delete()) total -= length
            }
        }
    }
}
