package dev.finalish.ui.viewer

import androidx.compose.runtime.Immutable
import dev.finalish.core.data.PlaybackQuality
import dev.finalish.core.model.Asset
import dev.finalish.core.model.MediaKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

@Immutable
/** Where a rendition comes from in the file's media links. */
enum class RenditionSource { HighQuality, Efficient, H264180, Original }

/** A playable version of the file; [height] is its nominal resolution (1080 for a 1920×804 scope transcode). */
@Immutable
data class Rendition(val source: RenditionSource, val height: Int?) {
    /** The setting that plays exactly this rendition. */
    val quality: PlaybackQuality
        get() = if (source == RenditionSource.Original) PlaybackQuality.Auto
        else PlaybackQuality.entries.filter { it.height != null && it.height <= (height ?: 0) }.maxByOrNull { it.height!! }
            ?: PlaybackQuality.P180

    val label: String get() = if (source == RenditionSource.Original) "Original" else height?.let { PlaybackQuality.labelFor(it) } ?: "Original"

    companion object {
        private val STANDARD = listOf(2160, 1440, 1080, 720, 540, 360, 180)

        /** Frame.io names its encodes after the resolution: …/encode/<id>/h264_1080_best.mp4. */
        fun heightFromUrl(url: String?): Int? =
            url?.let { Regex("""h264_(\d{3,4})""").find(it)?.groupValues?.get(1)?.toIntOrNull() }

        /** 1920×1080, 1920×804 and 1998×1080 are all "1080p"; portrait uses the same rule turned. */
        fun nominalHeight(width: Int, height: Int): Int? {
            if (width <= 0 || height <= 0) return null
            val long = maxOf(width, height); val short = minOf(width, height)
            val h = if (long * 9 >= short * 16) long * 9 / 16 else short
            val near = STANDARD.minBy { kotlin.math.abs(it - h) }
            return if (kotlin.math.abs(near - h) <= near * 0.15) near else h
        }
    }
}

/** The stream link for [source] in the file's media links. */
internal fun Asset.urlOf(source: RenditionSource): String? = mediaLinks?.let { l ->
    when (source) {
        RenditionSource.HighQuality -> l.highQuality?.streamUrl
        RenditionSource.Efficient -> l.efficient?.streamUrl
        RenditionSource.H264180 -> l.video180?.streamUrl
        RenditionSource.Original -> l.original?.inlineUrl ?: l.original?.downloadUrl
    }
}

/** The renditions a video offers, with their real heights, and the one to play for a quality setting. */
internal class Renditions(private val http: OkHttpClient, private val scope: CoroutineScope) {
    private val _list = MutableStateFlow<List<Rendition>>(emptyList())
    /** Renditions the API exposes for this file, with their real decoded heights. */
    val list: StateFlow<List<Rendition>> = _list.asStateFlow()
    private var probedFor: String? = null

    /** Labels a rendition the URL didn't name, once the decoder reports its size. */
    fun setHeight(source: RenditionSource, nominal: Int) =
        _list.update { list -> list.map { if (it.source == source) it.copy(height = nominal) else it } }

    fun probe(f: Asset) {
        if (probedFor == f.id || f.kind != MediaKind.Video) return
        probedFor = f.id
        // The original isn't offered as a quality: it's often ProRes or 4K the phone can't play smoothly.
        val sources = RenditionSource.entries.filter { it != RenditionSource.Original }.mapNotNull { src -> f.urlOf(src)?.let { src to it } }
        // The encode name gives the resolution right away; 180 is in the link's own name.
        _list.value = sources.map { (src, url) ->
            Rendition(
                src,
                when (src) {
                    RenditionSource.H264180 -> 180
                    RenditionSource.Original -> null
                    else -> Rendition.heightFromUrl(url)
                },
            )
        }
        sources.filter { (src, _) -> src != RenditionSource.Original && _list.value.first { it.source == src }.height == null }.forEach { (src, url) ->
            scope.launch(Dispatchers.IO) {
                val height = encodeHeightBehindRedirect(url) ?: headerHeight(url)
                // Only for the version still on screen: another one's height would mislabel its renditions.
                if (height != null && probedFor == f.id) {
                    _list.update { list -> list.map { if (it.source == src && it.height == null) it.copy(height = height) else it } }
                }
            }
        }
    }

    /** Short links redirect to the named encode; read the name without downloading anything. */
    private fun encodeHeightBehindRedirect(url: String): Int? = runCatching {
        val client = http.newBuilder().followRedirects(false).followSslRedirects(false).build()
        client.newCall(okhttp3.Request.Builder().url(url).head().build()).execute().use { Rendition.heightFromUrl(it.header("Location")) }
    }.getOrNull()

    /** Last resort: the container header's frame size. */
    private fun headerHeight(url: String): Int? = runCatching {
        val mmr = android.media.MediaMetadataRetriever()
        try {
            mmr.setDataSource(url, emptyMap())
            Rendition.nominalHeight(
                mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0,
                mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0,
            )
        } finally {
            mmr.release()
        }
    }.getOrNull()

    /** The link to stream [f] from at [quality]; [metered] is asked only for Auto. */
    fun pickStreamUrl(f: Asset, quality: PlaybackQuality, metered: () -> Boolean): String? {
        // Quality levels don't apply to audio.
        if (f.kind == MediaKind.Audio) return audioStreamUrl(f)
        val l = f.mediaLinks ?: return null
        val hq = l.highQuality?.streamUrl
        val eff = l.efficient?.streamUrl
        val orig = f.urlOf(RenditionSource.Original)
        val target = quality.height
        return when {
            quality == PlaybackQuality.Auto -> if (metered()) eff ?: hq else hq ?: eff
            else -> {
                // The closest rendition at or below the target, else the smallest one.
                val known = _list.value.filter { it.source != RenditionSource.Original && it.height != null }
                val pick = known.filter { it.height!! <= target!! }.maxByOrNull { it.height!! } ?: known.minByOrNull { it.height!! }
                pick?.let { f.urlOf(it.source) } ?: when {
                    target!! >= 720 -> hq ?: eff
                    target >= 360 -> eff ?: hq
                    else -> l.video180?.streamUrl ?: eff ?: hq
                }
            }
        } ?: orig ?: l.video180?.streamUrl
    }
}

/** Audio containers Android plays natively; anything else uses Frame.io's AAC transcode. */
private val PLAYABLE_AUDIO = setOf("mp3", "m4a", "aac", "wav", "flac", "ogg", "oga", "opus")

/**
 * The URL to play an audio file from, for the viewer and Compare alike. Originals Android decodes
 * play as uploaded (exact sample rate and channels; the AAC transcode sometimes answers 403); others
 * (WMA, AIFF…) use Frame.io's AAC transcode, with the original only as a last resort.
 */
internal fun audioStreamUrl(f: Asset): String? {
    val l = f.mediaLinks ?: return null
    val hq = l.highQuality?.streamUrl
    val eff = l.efficient?.streamUrl
    val orig = l.original?.inlineUrl ?: l.original?.downloadUrl
    val ext = f.media.name.substringAfterLast('.', "").lowercase()
    return if (ext in PLAYABLE_AUDIO) orig ?: hq ?: eff else hq ?: eff ?: orig
}
