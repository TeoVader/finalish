package dev.finalish.ui.viewer

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Instant thumbnails while scrubbing. Frame.io's scrub-sheet layout isn't exposed
 * publicly, so we fetch the tiny 180p rendition once (a few MB) and decode frames
 * locally — fast enough to follow a finger in real time.
 */
class ScrubPreviewer(private val http: OkHttpClient, private val cacheDir: File) {

    private val mutex = Mutex()
    private var retriever: MediaMetadataRetriever? = null
    private var preparedFor: String? = null
    private val frames = LruCache<Long, ImageBitmap>(120)
    /** Keyframe thumbnails spread over the whole clip, decoded ahead of time for instant lookups. */
    private val ladder = java.util.concurrent.ConcurrentSkipListMap<Long, ImageBitmap>()

    val isReady: Boolean get() = retriever != null

    suspend fun prepare(fileId: String, url: String?) = withContext(Dispatchers.IO) {
        if (url == null || preparedFor == fileId) return@withContext
        mutex.withLock {
            release()
            preparedFor = fileId
            val dir = File(cacheDir, "scrub").apply { mkdirs() }
            val target = File(dir, "$fileId.mp4")
            runCatching {
                if (!target.exists() || target.length() == 0L) {
                    val tmp = File(dir, "$fileId.part")
                    http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                        if (!r.isSuccessful) error("HTTP ${r.code}")
                        r.body.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    }
                    tmp.renameTo(target)
                }
                retriever = MediaMetadataRetriever().apply { setDataSource(target.absolutePath) }
                trimCache(dir, keep = target)
            }.onFailure {
                target.delete()
                preparedFor = null
            }
        }
        warmUp(fileId)
    }

    /**
     * Decodes ~120 keyframes across the clip, coarse to fine (every 8th slot first, then
     * the gaps), so a drag anywhere finds a nearby thumbnail straight away. Sync frames
     * are cheap to decode; the mutex is taken per frame so live requests interleave.
     */
    private suspend fun warmUp(fileId: String) {
        val duration = mutex.withLock {
            retriever?.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } ?: return
        val slots = 120
        val step = (duration / slots).coerceAtLeast(250)
        val order = listOf(8, 4, 2, 1).flatMap { stride -> (0 until slots step stride).toList() }.distinct()
        for (i in order) {
            if (preparedFor != fileId) return
            val t = (i * step).coerceAtMost(duration - 1)
            if (ladder.containsKey(t)) continue
            mutex.withLock {
                val r = retriever ?: return
                runCatching { decode(r, t * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 240) }.getOrNull()
            }?.let { ladder[t] = it }
        }
    }

    /** Best thumbnail already in memory for [ms] — never blocks, for the first frame of a drag. */
    fun nearest(ms: Long): ImageBitmap? {
        frames.get(ms / 100)?.let { return it }
        val lo = ladder.floorEntry(ms)
        val hi = ladder.ceilingEntry(ms)
        return when {
            lo == null -> hi?.value
            hi == null -> lo.value
            else -> if (ms - lo.key <= hi.key - ms) lo.value else hi.value
        }
    }

    private fun decode(r: MediaMetadataRetriever, us: Long, option: Int, size: Int): ImageBitmap? {
        val bmp: Bitmap? = if (Build.VERSION.SDK_INT >= 27) r.getScaledFrameAtTime(us, option, size, size)
        else r.getFrameAtTime(us, option)
        return bmp?.asImageBitmap()
    }

    /** Frame nearest to [ms], quantised to 100 ms so repeated drags hit the cache. */
    suspend fun frameAt(ms: Long): ImageBitmap? {
        val key = ms / 100
        frames.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                val r = retriever ?: return@withLock null
                runCatching {
                    decode(r, key * 100_000, MediaMetadataRetriever.OPTION_CLOSEST, 320)?.also { frames.put(key, it) }
                }.getOrNull()
            }
        }
    }

    /**
     * For the owner going away: waits for a decode in progress (cancelling doesn't stop the
     * native call) before freeing the retriever under it.
     */
    fun close() {
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch { mutex.withLock { release() } }
    }

    private fun release() {
        runCatching { retriever?.release() }
        retriever = null
        frames.evictAll()
        ladder.clear()
    }

    /** Keep the scrub cache small: only the most recent few files. */
    private fun trimCache(dir: File, keep: File) {
        dir.listFiles()?.filter { it != keep && it.extension == "mp4" }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(8)
            ?.forEach { it.delete() }
    }
}
