package dev.finalish.core.media

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max

/**
 * The real waveform of an audio file: decodes the track and keeps the peak of each of [bins]
 * equal slices (0..1). Cached on disk per [cacheKey] (file id + version), so it's computed once.
 */
object Waveform {

    suspend fun peaks(context: Context, source: String, cacheKey: String, bins: Int = 160): FloatArray? = withContext(Dispatchers.IO) {
        val cache = File(context.cacheDir, "waveforms/${cacheKey.hashCode()}_$bins.bin")
        if (cache.length() == bins * 4L) {
            return@withContext runCatching {
                val buf = ByteBuffer.wrap(cache.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
                FloatArray(bins) { buf.float }
            }.getOrNull()
        }
        val result = runCatching { decode(source, bins) }.getOrNull() ?: return@withContext null
        runCatching {
            cache.parentFile?.mkdirs()
            val buf = ByteBuffer.allocate(bins * 4).order(ByteOrder.LITTLE_ENDIAN)
            result.forEach { buf.putFloat(it) }
            cache.writeBytes(buf.array())
        }
        result
    }

    private suspend fun decode(source: String, bins: Int): FloatArray? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(source)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else return null
            if (durationUs <= 0) return null
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            codec = MediaCodec.createDecoderByType(mime).apply { configure(format, null, null, 0); start() }

            val peaks = FloatArray(bins)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                kotlin.coroutines.coroutineContext.ensureActive()
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buf = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                if (outIndex >= 0) {
                    if (info.size > 0) {
                        val out = codec.getOutputBuffer(outIndex)!!.order(ByteOrder.LITTLE_ENDIAN)
                        out.position(info.offset)
                        val shorts = out.asShortBuffer()
                        val n = info.size / 2
                        // Which slice this output buffer belongs to, by its presentation time.
                        val bin = ((info.presentationTimeUs.toDouble() / durationUs) * bins).toInt().coerceIn(0, bins - 1)
                        var peak = peaks[bin]
                        var i = 0
                        while (i < n) {
                            val v = abs(shorts.get(i).toInt()) / 32768f
                            if (v > peak) peak = v
                            i += 4 // every 4th sample is plenty for a peak meter
                        }
                        peaks[bin] = peak
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
            // Fill any gaps (very short buffers can skip a slice) and normalise to the loudest slice.
            for (b in 1 until bins) if (peaks[b] == 0f) peaks[b] = peaks[b - 1] * 0.6f
            val top = peaks.maxOrNull()?.takeIf { it > 0f } ?: return peaks
            return FloatArray(bins) { max(0.04f, peaks[it] / top) }
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }
}
