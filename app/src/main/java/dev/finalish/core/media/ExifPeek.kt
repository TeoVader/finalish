package dev.finalish.core.media

import androidx.exifinterface.media.ExifInterface
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.util.Locale

/**
 * Camera details of a photo on Frame.io (which doesn't keep EXIF): the tags sit at the
 * start of the file, so one ranged read of the original is enough.
 */
object ExifPeek {
    private const val HEAD = 256 * 1024

    /** Label → value rows ("Camera", "Lens", "Exposure", "Taken"); empty when the file has none. */
    fun read(http: OkHttpClient, url: String): List<Pair<String, String>> {
        val req = Request.Builder().url(url).header("Range", "bytes=0-${HEAD - 1}").build()
        val head = http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return emptyList()
            // A server that ignores Range sends everything; only the head matters.
            val src = r.body.source()
            src.request(HEAD.toLong())
            src.buffer.readByteArray(minOf(src.buffer.size, HEAD.toLong()))
        }
        val exif = runCatching { ExifInterface(ByteArrayInputStream(head)) }.getOrNull() ?: return emptyList()
        return describe { exif.getAttribute(it) }
    }

    internal fun describe(tag: (String) -> String?): List<Pair<String, String>> {
        fun t(name: String) = tag(name)?.trim()?.takeIf { it.isNotEmpty() }
        fun num(name: String) = t(name)?.let(::rational)
        val make = t("Make")
        val model = t("Model")
        // "Sony ILCE-7M4"; skip the make when the model already starts with it ("Canon EOS R5").
        val camera = when {
            model == null -> make
            make == null || model.startsWith(make, ignoreCase = true) -> model
            else -> "$make $model"
        }
        val exposure = listOfNotNull(
            num("ExposureTime")?.let { s -> if (s >= 1) "${trim(s)} s" else "1/${Math.round(1 / s)} s" },
            num("FNumber")?.let { "f/${trim(it)}" },
            (t("PhotographicSensitivity") ?: t("ISOSpeedRatings"))?.let { "ISO $it" },
            num("FocalLength")?.let { "${trim(it)} mm" },
        ).joinToString(" · ").ifEmpty { null }
        val taken = t("DateTimeOriginal")?.let { d ->
            // "2026:10:01 11:48:07" → "2026-10-01 11:48"
            Regex("""(\d{4}):(\d{2}):(\d{2}) (\d{2}):(\d{2})""").find(d)?.destructured?.let { (y, mo, da, h, mi) -> "$y-$mo-$da $h:$mi" } ?: d
        }
        return listOfNotNull(
            camera?.let { "Camera" to it },
            t("LensModel")?.let { "Lens" to it },
            exposure?.let { "Exposure" to it },
            taken?.let { "Taken" to it },
        )
    }

    /** The label of a [read] row in the app's language ([read] returns English keys). */
    @androidx.annotation.StringRes
    fun labelRes(label: String): Int? = when (label) {
        "Camera" -> dev.finalish.R.string.core_exif_camera
        "Lens" -> dev.finalish.R.string.core_exif_lens
        "Exposure" -> dev.finalish.R.string.core_exif_exposure
        "Taken" -> dev.finalish.R.string.core_exif_taken
        else -> null
    }

    /** EXIF numbers come as "1/125", "28/10" or "0.008". */
    private fun rational(v: String): Double? {
        val parts = v.split('/')
        return if (parts.size == 2) {
            val d = parts[1].toDoubleOrNull()?.takeIf { it != 0.0 } ?: return null
            parts[0].toDoubleOrNull()?.div(d)
        } else v.toDoubleOrNull()
    }

    private fun trim(v: Double) = if (v % 1.0 == 0.0) v.toLong().toString() else String.format(Locale.US, "%.1f", v)
}
