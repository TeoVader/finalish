package dev.finalish.core.util

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToLong

object Format {

    /**
     * Decimal units (1 GB = 1000³ bytes), like Frame.io and Android: a Free plan reads "2.0 GB".
     * A negative size (an unknown length, -1) reads "0 B".
     */
    fun bytes(size: Long): String {
        if (size < 0) return "0 B"
        if (size < 1000) return "$size B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        val exp = (ln(size.toDouble()) / ln(1000.0)).toInt().coerceIn(1, units.size)
        val v = size / 1000.0.pow(exp)
        return String.format(Locale.US, if (v >= 100) "%.0f %s" else "%.1f %s", v, units[exp - 1])
    }

    /**
     * Time left until [iso], rounded up: a link made "for 7 days" reads "7d", not "6d",
     * a few seconds later. Null once it has passed.
     */
    fun remaining(iso: String?, now: Instant = Instant.now()): String? {
        val end = parseInstant(iso) ?: return null
        val mins = java.time.Duration.between(now, end).toMinutes()
        if (mins < 0) return null
        return when {
            mins < 60 -> "${mins.coerceAtLeast(1)}m"
            mins < 24 * 60 -> "${(mins + 59) / 60}h"
            else -> "${(mins + 24 * 60 - 1) / (24 * 60)}d"
        }
    }

    /** Whole days left until [iso], rounded up (null if unset or passed). */
    fun daysLeft(iso: String?, now: Instant = Instant.now()): Long? {
        val end = parseInstant(iso) ?: return null
        val mins = java.time.Duration.between(now, end).toMinutes()
        return if (mins < 0) null else (mins + 24 * 60 - 1) / (24 * 60)
    }

    fun parseInstant(iso: String?): Instant? = iso?.let { runCatching { Instant.parse(it) }.getOrNull() }

    fun relative(iso: String?, now: Instant = Instant.now()): String {
        val t = parseInstant(iso) ?: return ""
        val d = Duration.between(t, now)
        val s = abs(d.seconds)
        // In the app's language when the app is running; English in plain unit tests.
        val res = AppLocale.appContext?.resources
        fun ago(id: Int, n: Long, english: String) = res?.getString(id, n.toInt()) ?: english
        return when {
            s < 45 -> res?.getString(dev.finalish.R.string.core_time_just_now) ?: "just now"
            s < 3_600 -> (s / 60).coerceAtLeast(1).let { ago(dev.finalish.R.string.core_time_minutes_ago, it, "${it}m ago") }
            s < 86_400 -> (s / 3_600).let { ago(dev.finalish.R.string.core_time_hours_ago, it, "${it}h ago") }
            s < 7 * 86_400 -> (s / 86_400).let { ago(dev.finalish.R.string.core_time_days_ago, it, "${it}d ago") }
            else -> DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
                .withZone(ZoneId.systemDefault()).format(t)
        }
    }

    fun dateTime(iso: String?): String {
        val t = parseInstant(iso) ?: return "—"
        return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withZone(ZoneId.systemDefault()).format(t)
    }

    /** mm:ss or h:mm:ss */
    fun clock(ms: Long): String {
        val total = (ms.coerceAtLeast(0) / 1000)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }

    /** SMPTE-style HH:MM:SS:FF */
    fun timecode(ms: Long, fps: Double): String {
        val f = fps.coerceAtLeast(1.0)
        val frames = (ms / 1000.0 * f).roundToLong()
        val fpsInt = f.roundToLong().coerceAtLeast(1)
        val ff = frames % fpsInt
        val totalSec = frames / fpsInt
        return String.format(
            Locale.US, "%02d:%02d:%02d:%02d",
            totalSec / 3600, (totalSec % 3600) / 60, totalSec % 60, ff,
        )
    }

    fun framesToMs(frame: Int, fps: Double): Long = (frame / fps.coerceAtLeast(1.0) * 1000).roundToLong()
    fun msToFrame(ms: Long, fps: Double): Int = (ms / 1000.0 * fps.coerceAtLeast(1.0)).roundToLong().toInt()

    /** Parses HH:MM:SS:FF (or HH:MM:SS;FF) into a frame number. */
    fun timecodeToFrame(tc: String, fps: Double): Int? {
        val parts = tc.split(':', ';').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4) return null
        val r = fps.roundToLong().coerceAtLeast(1).toInt()
        return ((parts[0] * 3600 + parts[1] * 60 + parts[2]) * r) + parts[3]
    }

    fun initials(name: String): String =
        name.split(' ', '.', '_', '@', '-').filter { it.firstOrNull()?.isLetterOrDigit() == true }.take(2)
            .joinToString("") { it.first().uppercase() }.ifBlank { "?" }

    /**
     * A server-supplied name made safe as a single file name on the phone: no path separators,
     * no control, bidi-override or reserved characters, not "." or "..", at most 120 characters (extension kept),
     * [fallback] when nothing usable is left.
     */
    fun safeFileName(name: String?, fallback: String = "download"): String {
        val cleaned = name.orEmpty()
            .map { c -> if (c.isISOControl() || Character.getType(c) == Character.FORMAT.toInt() || c in "/\\:*?\"<>|") '_' else c }
            .joinToString("")
            .trim().trim('.').trim()
        if (cleaned.isEmpty() || cleaned.all { it == '_' }) return fallback
        val max = 120
        if (cleaned.length <= max) return cleaned
        val dot = cleaned.lastIndexOf('.')
        val ext = if (dot > 0 && cleaned.length - dot <= 16) cleaned.substring(dot) else ""
        return cleaned.take(max - ext.length).trimEnd('.', ' ') + ext
    }
}
