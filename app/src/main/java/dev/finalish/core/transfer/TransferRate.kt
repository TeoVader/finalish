package dev.finalish.core.transfer

import android.content.Context
import dev.finalish.R
import dev.finalish.core.util.Format

/** Smoothed bytes-per-second from successive progress samples (exponential average). */
class RateMeter {
    private var lastBytes = -1L
    private var lastNanos = 0L
    var bytesPerSecond: Double? = null
        private set

    fun sample(doneBytes: Long, nowNanos: Long = System.nanoTime()) {
        if (lastBytes in 0..doneBytes) {
            val dt = (nowNanos - lastNanos) / 1e9
            if (dt < 0.05) return
            val inst = (doneBytes - lastBytes) / dt
            bytesPerSecond = bytesPerSecond?.let { it * 0.7 + inst * 0.3 } ?: inst
        }
        lastBytes = doneBytes
        lastNanos = nowNanos
    }

    fun reset() {
        lastBytes = -1
        bytesPerSecond = null
    }
}

/** "25.1 MB of 79.0 MB · 4.1 MB/s · 13s left" (speed and ETA once a rate is known). */
fun transferLabel(done: Long, total: Long, rate: Double?): String {
    val base = "${Format.bytes(done)} of ${Format.bytes(total)}"
    if (rate == null || rate < 1 || total <= 0) return base
    return "$base · ${Format.bytes(rate.toLong())}/s · ${eta(((total - done) / rate).toLong().coerceAtLeast(0))}"
}

private fun eta(s: Long) = when {
    s < 60 -> "${s}s left"
    s < 3600 -> "${s / 60}m ${s % 60}s left"
    else -> "${s / 3600}h ${s % 3600 / 60}m left"
}

/** [transferLabel] in the app's language. */
fun transferLabel(context: Context, done: Long, total: Long, rate: Double?): String {
    val base = context.getString(R.string.core_transfer_progress, Format.bytes(done), Format.bytes(total))
    if (rate == null || rate < 1 || total <= 0) return base
    val s = ((total - done) / rate).toLong().coerceAtLeast(0)
    val eta = when {
        s < 60 -> context.getString(R.string.core_transfer_eta_seconds, s)
        s < 3600 -> context.getString(R.string.core_transfer_eta_minutes, s / 60, s % 60)
        else -> context.getString(R.string.core_transfer_eta_hours, s / 3600, s % 3600 / 60)
    }
    return "$base · ${context.getString(R.string.core_transfer_speed, Format.bytes(rate.toLong()))} · $eta"
}
