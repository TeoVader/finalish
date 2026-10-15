@file:OptIn(UnstableApi::class)

package dev.finalish.ui.viewer

import android.content.Context
import android.net.Uri
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import dev.finalish.FinalishApp
import kotlin.math.roundToLong

/**
 * Compose-friendly wrapper around ExoPlayer. Lives in a ViewModel so playback
 * survives rotation and fullscreen transitions without re-buffering.
 */
@Stable
class PlayerController(context: Context) {

    private val container = FinalishApp.container(context)

    /**
     * Built on first use: a viewer page for an image or PDF never needs one, and swiping
     * through a folder's images would otherwise keep an ExoPlayer per page.
     */
    private val exo = lazy {
        build(context).also {
            it.addListener(listener)
            it.repeatMode = if (looping) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        }
    }
    val player: ExoPlayer by exo

    private fun build(context: Context): ExoPlayer = run {
        val upstream = OkHttpDataSource.Factory(container.baseHttp)
        val cached = CacheDataSource.Factory()
            .setCache(container.mediaCache)
            .setUpstreamDataSourceFactory(DefaultDataSource.Factory(context, upstream))
            .setCacheKeyFactory { spec -> cacheKey(spec.uri) }
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        // If a device's preferred decoder fails on a stream, retry with the next one
        // (e.g. a software decoder) instead of surfacing "decoding failed".
        val renderers = androidx.media3.exoplayer.DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
            .setMediaCodecSelector(codecSelector)
        ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(cached))
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(2_500, 40_000, 600, 1_200)
                    .build(),
            )
            .setSeekBackIncrementMs(5_000)
            .setSeekForwardIncrementMs(5_000)
            .build()
            .apply {
                setSeekParameters(SeekParameters.EXACT)
                setHandleAudioBecomingNoisy(true)
            }
    }

    var isPlaying by mutableStateOf(false); private set
    var isBuffering by mutableStateOf(false); private set
    var durationMs by mutableLongStateOf(0L); private set
    var bufferedMs by mutableLongStateOf(0L); private set
    var videoAspect by mutableFloatStateOf(16f / 9f); private set
    var frameRate by mutableFloatStateOf(0f); private set
    var speed by mutableFloatStateOf(1f); private set
    var looping by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set

    /** Bumped on every playback failure so the ViewModel can try to recover. */
    var failureCount by mutableIntStateOf(0); private set
    var hasVideo by mutableStateOf(false); private set

    /** Where the video surface sits in the window: the picture-in-picture animation starts from it. */
    var surfaceBounds by mutableStateOf<android.graphics.Rect?>(null)

    /** Nominal resolution of the rendition currently playing (1080 for 1080p). */
    var videoHeight by mutableIntStateOf(0); private set

    private var surface: TextureView? = null

    /** Updated by the UI frame loop for buttery-smooth scrubber motion. */
    var positionMs by mutableLongStateOf(0L)

    private var currentUri: String? = null

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(playing: Boolean) {
            isPlaying = playing
        }

        override fun onPlaybackStateChanged(state: Int) {
            isBuffering = state == Player.STATE_BUFFERING
            if (state == Player.STATE_READY) {
                durationMs = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
            }
            syncPosition()
        }

        override fun onVideoSizeChanged(size: VideoSize) {
            if (size.width > 0 && size.height > 0) {
                videoAspect = size.width * size.pixelWidthHeightRatio / size.height
                // Nominal "p": 1920×804 scope and vertical 1080×1920 are both 1080p.
                videoHeight = Rendition.nominalHeight(size.width, size.height) ?: minOf(size.width, size.height)
                hasVideo = true
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            if (tracks.isEmpty) return
            // Audio-only media has no rate: don't keep the previous video's.
            frameRate = tracks.groups.flatMap { g -> (0 until g.length).map { g.getTrackFormat(it).frameRate } }
                .firstOrNull { it > 0f } ?: 0f
        }

        override fun onPlayerError(e: PlaybackException) {
            val http = generateSequence(e as Throwable) { it.cause }
                .filterIsInstance<androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException>().firstOrNull()
            dev.finalish.core.util.AppLog.w(
                "Playback error ${e.errorCodeName} http=${http?.responseCode} host=${http?.dataSpec?.uri?.host} " +
                    "path=${http?.dataSpec?.uri?.path?.takeLast(40)} pos=${http?.dataSpec?.position}",
                e,
            )
            // The owner may switch to another rendition (e.g. a WMA original → Frame.io's AAC).
            if (onError?.invoke(currentUri) == true) return
            // Only failures the owner didn't handle go to the generic recovery, which would undo its fallback.
            failureCount++
            error = e.errorCodeName.removePrefix("ERROR_CODE_").replace('_', ' ').lowercase()
                .replaceFirstChar { it.uppercase() }
        }

        override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
            syncPosition()
        }
    }

    /** The playhead, without building a player for media that has none (images, PDFs). */
    val currentPositionMs: Long get() = if (exo.isInitialized()) player.currentPosition else 0L

    /** The loaded media's length, or 0 (also when no player was needed). */
    val currentDurationMs: Long get() = if (exo.isInitialized()) player.duration.coerceAtLeast(0L) else 0L

    /** Called with the failing URL; return true when another rendition was loaded instead. */
    var onError: ((String?) -> Boolean)? = null

    /** Reloads a (refreshed) URL at the given position, even if it was loaded before. */
    fun reload(uri: String, positionMs: Long, play: Boolean) {
        currentUri = uri
        error = null
        player.setMediaItem(MediaItem.fromUri(uri.toUri()), positionMs)
        player.prepare()
        player.playWhenReady = play
    }

    fun load(uri: String, startMs: Long = 0, autoplay: Boolean) {
        if (uri == currentUri) return
        val keepPosition = currentUri != null
        val pos = if (keepPosition) player.currentPosition else startMs
        currentUri = uri
        error = null
        // Another version can be shorter: don't clamp seeks to the previous one's length until this one is ready.
        durationMs = 0L
        player.setMediaItem(MediaItem.fromUri(uri.toUri()), pos)
        player.prepare()
        player.playWhenReady = autoplay || (keepPosition && player.playWhenReady)
    }

    fun attach(view: TextureView) {
        surface = view
        player.setVideoTextureView(view)
        // A new surface (after a rotation) stays black while paused or ended.
        redraw()
    }

    /** While paused or ended, seeks to the same spot so the decoder draws the frame again (at the end, just before it). */
    fun redraw() {
        if (!exo.isInitialized() || surface == null) return
        if (!player.isPlaying && player.playbackState != androidx.media3.common.Player.STATE_IDLE) {
            val d = player.duration
            val at = player.currentPosition.let { if (d > 0) it.coerceAtMost(d - 50) else it }.coerceAtLeast(0)
            player.seekTo(at)
        }
    }

    fun detach(view: TextureView) {
        if (surface === view) surface = null
        player.clearVideoTextureView(view)
    }

    /** Current frame at full decoded resolution (for "Download still"). */
    fun captureFrame(): android.graphics.Bitmap? {
        val view = surface ?: return null
        val size = player.videoSize
        return if (size.width > 0 && size.height > 0) view.getBitmap(size.width, size.height) else view.bitmap
    }

    fun toggle() {
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
        if (player.isPlaying) player.pause() else player.play()
    }

    fun play() = player.play()
    fun pause() {
        if (exo.isInitialized()) player.pause()
    }

    fun seekTo(ms: Long) {
        val clamped = ms.coerceIn(0, durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE)
        player.seekTo(clamped)
        positionMs = clamped
    }

    /** Scrubbing uses fast keyframe seeks; release snaps to the exact frame. */
    fun scrubTo(ms: Long, exact: Boolean) {
        player.setSeekParameters(if (exact) SeekParameters.EXACT else SeekParameters.CLOSEST_SYNC)
        seekTo(ms)
        if (exact) player.setSeekParameters(SeekParameters.EXACT)
    }

    fun fps(fallback: Double = 24.0): Double = frameRate.takeIf { it > 1f }?.toDouble() ?: fallback

    // The frame the last step landed on, and where: positions are whole milliseconds, so the
    // next step continues from that frame instead of re-deriving it from a rounded position.
    private var steppedFrame = -1L
    private var steppedAt = -1L

    fun stepFrames(frames: Int, fallbackFps: Double) {
        player.pause()
        val frameMs = 1000.0 / fps(fallbackFps)
        val pos = player.currentPosition
        // Frame numbers as the timecode counts them (Format.timecode rounds).
        val current = if (pos == steppedAt && steppedFrame >= 0) steppedFrame else (pos / frameMs).roundToLong()
        val target = (current + frames).coerceAtLeast(0)
        // The first whole millisecond at or after the frame's start: an exact seek shows that
        // frame, and the timecode rounds back to it. (The middle of the frame sat on the
        // timecode's rounding edge, so the label and picture jumped by 0 or 2.)
        val at = kotlin.math.ceil(target * frameMs).toLong()
        seekTo(at)
        steppedAt = positionMs
        steppedFrame = if (positionMs == at) target else -1L
    }

    fun setPlaybackSpeed(s: Float) {
        speed = s
        player.playbackParameters = PlaybackParameters(s)
    }

    fun setLoop(loop: Boolean) {
        looping = loop
        if (exo.isInitialized()) player.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    fun setVolume(muted: Boolean) {
        player.volume = if (muted) 0f else 1f
    }

    // Audio-only playback reports its position in coarse steps (the audio sink updates it per
    // buffer), which makes the playhead stutter. Between reports, extrapolate from the wall clock.
    private var anchorPos = -1L
    private var anchorNanos = 0L

    fun syncPosition() {
        if (!exo.isInitialized()) return
        val raw = player.currentPosition
        positionMs = if (player.isPlaying) {
            val now = System.nanoTime()
            if (raw != anchorPos) { anchorPos = raw; anchorNanos = now }
            val est = (raw + (now - anchorNanos) / 1_000_000f * speed).toLong().coerceAtMost(raw + 400)
            // Never step back by a hair when a fresh report lands slightly behind the estimate.
            if (est < positionMs && positionMs - est < 150) positionMs else est
        } else {
            anchorPos = -1L
            raw
        }
        bufferedMs = player.bufferedPosition
        if (durationMs <= 0) durationMs = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
    }

    fun release() {
        if (exo.isInitialized()) player.release()
    }

    companion object {
        /**
         * Static files on assets.frame.io are uniquely identified by their path, so the rotating
         * signature is ignored. On-the-fly streams (stream-download.frame.io/manifest/...) share
         * one path for every file and quality, so they must be keyed by the full URL.
         */
        fun cacheKey(uri: Uri): String =
            if (uri.host == "assets.frame.io") "${uri.host}${uri.path}" else uri.toString()
    }
}

/**
 * The Android 17 emulator's "goldfish" hardware decoders fail mid-stream for apps
 * targeting API 37 (a sepolicy gap in the emulator image), so on emulators prefer
 * the software decoders. Real devices keep the default order.
 */
private val codecSelector = androidx.media3.exoplayer.mediacodec.MediaCodecSelector { mime, secure, tunneling ->
    val infos = androidx.media3.exoplayer.mediacodec.MediaCodecUtil.getDecoderInfos(mime, secure, tunneling)
    if (android.os.Build.HARDWARE == "ranchu" || android.os.Build.HARDWARE == "goldfish") {
        infos.sortedBy { it.name.contains("goldfish") }
    } else infos
}
