package dev.finalish.ui.viewer

import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.finalish.ui.application
import dev.finalish.ui.container
import dev.finalish.core.data.FrameioRepository
import android.app.Application
import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.border
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Forward5
import androidx.compose.material.icons.rounded.Replay5
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.finalish.core.model.Asset
import dev.finalish.core.model.MediaKind
import dev.finalish.core.network.userMessage
import dev.finalish.ui.components.RemoteImage
import dev.finalish.ui.components.pressable
import dev.finalish.ui.home.Chip
import dev.finalish.ui.theme.Palette
import dev.finalish.ui.theme.TimecodeStyle
import androidx.compose.ui.res.stringResource
import dev.finalish.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs

data class CompareUiState(
    val left: Asset? = null,
    val right: Asset? = null,
    /** Version numbers (V1 = oldest) when both files belong to the same stack. */
    val leftVersion: Int? = null,
    val rightVersion: Int? = null,
    val ready: Boolean = false,
    /** Why the two files couldn't load (offline, removed…); the screen offers Retry. */
    val error: String? = null,
    /** Which side is audible (0 = A, 1 = B); both keep playing, so switching never breaks the flow. */
    val listening: Int = 1,
)

sealed interface CompareEvent {
    data class Bind(val accountId: String, val leftId: String, val rightId: String) : CompareEvent
    data class Retry(val accountId: String, val leftId: String, val rightId: String) : CompareEvent
    data object Pause : CompareEvent
    data object TogglePlay : CompareEvent
    data class Listen(val side: Int) : CompareEvent
    data class Nudge(val ms: Long) : CompareEvent
    data class Seek(val ms: Long, val exact: Boolean) : CompareEvent
    data class Step(val frames: Int) : CompareEvent
}

class CompareViewModel(app: Application, private val repo: FrameioRepository, newPlayer: () -> PlayerController) : AndroidViewModel(app) {
    val left = newPlayer()
    val right = newPlayer()
    private val _state = MutableStateFlow(CompareUiState())
    val state: StateFlow<CompareUiState> = _state.asStateFlow()
    private var bound = false

    fun onEvent(e: CompareEvent) {
        when (e) {
            is CompareEvent.Bind -> bind(e.accountId, e.leftId, e.rightId)
            is CompareEvent.Retry -> {
                bound = false
                _state.update { it.copy(error = null) }
                bind(e.accountId, e.leftId, e.rightId)
            }
            CompareEvent.Pause -> { left.pause(); right.pause() }
            CompareEvent.TogglePlay -> toggle()
            is CompareEvent.Listen -> listen(e.side)
            is CompareEvent.Nudge -> nudge(e.ms)
            is CompareEvent.Seek -> { left.scrubTo(e.ms, e.exact); right.scrubTo(e.ms, e.exact) }
            is CompareEvent.Step -> { left.stepFrames(e.frames, fallbackFps()); right.seekTo(left.player.currentPosition) }
        }
    }

    /** Frames per second of the left side: decoder info first, then the file's metadata, then 24 (as the viewer). */
    fun fps(): Double = left.fps(fallbackFps())

    private fun fallbackFps(): Double = _state.value.left?.metadataFps() ?: 24.0

    private fun bind(account: String, leftId: String, rightId: String) {
        if (bound) return
        bound = true
        viewModelScope.launch {
            dev.finalish.core.util.runCatchingCancellable {
                val a = repo.fileNow(account, leftId)
                val b = repo.fileNow(account, rightId)
                _state.update { it.copy(left = a, right = b) }
                launch {
                    val stack = a.parentId?.takeIf { it == b.parentId } ?: return@launch
                    val headFirst = runCatching { repo.versionsNow(account, stack) }.getOrNull() ?: return@launch
                    fun number(id: String) = headFirst.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { headFirst.size - it }
                    _state.update { it.copy(leftVersion = number(a.id), rightVersion = number(b.id)) }
                }
                listOf(a to left, b to right).forEach { (f, p) ->
                    val l = f.mediaLinks
                    // Audio: the same rendition as the viewer (the original only when Android decodes it).
                    val url = if (f.kind == MediaKind.Audio) audioStreamUrl(f) else l?.highQuality?.streamUrl ?: l?.efficient?.streamUrl
                    if ((f.kind == MediaKind.Video || f.kind == MediaKind.Audio) && url != null) {
                        p.load(url, autoplay = false)
                    }
                }
                // Video: hear the right side. Audio: start on take A and switch at will.
                listen(if (a.kind == MediaKind.Audio) 0 else 1)
                _state.update { it.copy(ready = true) }
            }.onFailure { e -> _state.update { it.copy(error = e.userMessage()) } }
        }
    }

    /** Keeps the follower within a frame or two of the leader; runs only while playing. */
    suspend fun followWhilePlaying() {
        while (left.isPlaying) {
            if (abs(left.player.currentPosition - right.player.currentPosition) > 90) right.seekTo(left.player.currentPosition)
            delay(400)
        }
    }

    private fun listen(side: Int) {
        _state.update { it.copy(listening = side) }
        left.setVolume(side != 0)
        right.setVolume(side != 1)
    }

    private fun nudge(ms: Long) {
        val to = (left.player.currentPosition + ms).coerceAtLeast(0)
        left.seekTo(to)
        right.seekTo(to)
    }

    private fun toggle() {
        if (left.isPlaying) { left.pause(); right.pause() } else {
            // At the end, play starts both again from the top.
            if (left.player.playbackState == androidx.media3.common.Player.STATE_ENDED) left.seekTo(0)
            right.seekTo(left.player.currentPosition)
            left.play(); right.play()
        }
    }

    override fun onCleared() {
        left.release()
        right.release()
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = application
                CompareViewModel(app, container.repository) { PlayerController(app) }
            }
        }
    }
}

private class WipeShape(private val fraction: Float, private val keepLeft: Boolean) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val x = size.width * fraction
        return Outline.Rectangle(if (keepLeft) Rect(0f, 0f, x, size.height) else Rect(x, 0f, size.width, size.height))
    }
}

@Composable
fun CompareScreen(accountId: String, leftId: String, rightId: String, onBack: () -> Unit, vm: CompareViewModel = viewModel(factory = CompareViewModel.Factory)) {
    LaunchedEffect(Unit) { vm.onEvent(CompareEvent.Bind(accountId, leftId, rightId)) }
    val state by vm.state.collectAsStateWithLifecycle()
    val numbers = state.leftVersion to state.rightVersion
    var wipe by remember { mutableStateOf(true) }
    var split by remember { mutableFloatStateOf(0.5f) }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val a = state.left
    val b = state.right
    val error = state.error

    LaunchedEffect(vm.left.isPlaying) {
        if (vm.left.isPlaying) while (true) withFrameMillis { vm.left.syncPosition(); vm.right.syncPosition() }
        else { vm.left.syncPosition(); vm.right.syncPosition() }
    }
    LaunchedEffect(vm.left.isPlaying) { if (vm.left.isPlaying) vm.followWhilePlaying() }

    // Pause when the app goes to the background, like the viewer.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_STOP) vm.onEvent(CompareEvent.Pause) }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.viewer_back)) }
            Text(if (a?.kind == MediaKind.Audio) stringResource(R.string.viewer_compare_takes) else stringResource(R.string.viewer_compare_versions), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (a?.kind != MediaKind.Audio) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(end = 8.dp)) {
                Chip(stringResource(R.string.viewer_compare_wipe), wipe) { wipe = true }
                Chip(stringResource(R.string.viewer_compare_side_by_side), !wipe) { wipe = false }
            }
        }

        val listening = state.listening
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (a != null && b != null && a.kind == MediaKind.Audio) {
                AudioTakes(a, b, numbers, listening, vm, Modifier.fillMaxSize())
            } else if ((a == null || b == null) && error != null) {
                dev.finalish.ui.components.ErrorState(error.orEmpty(), onRetry = { vm.onEvent(CompareEvent.Retry(accountId, leftId, rightId)) })
            } else if (a == null || b == null) {
                androidx.compose.material3.CircularProgressIndicator(color = Palette.AccentBright, strokeWidth = 2.dp)
            } else if (wipe) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectHorizontalDragGestures { change, _ ->
                                change.consume()
                                split = (change.position.x / size.width).coerceIn(0f, 1f)
                            }
                        },
                ) {
                    CompareLayer(b, vm.right, Modifier.fillMaxSize().graphicsLayer { clip = true; shape = WipeShape(split, keepLeft = false) })
                    CompareLayer(a, vm.left, Modifier.fillMaxSize().graphicsLayer { clip = true; shape = WipeShape(split, keepLeft = true) })
                    WipeHandle(split)
                    Label(numbers.first, a, Modifier.align(Alignment.TopStart).padding(12.dp), fallback = "A")
                    Label(numbers.second, b, Modifier.align(Alignment.TopEnd).padding(12.dp), fallback = "B", end = true)
                }
            } else {
                val content: @Composable (Modifier) -> Unit = { m ->
                    Box(m) {
                        CompareLayer(a, vm.left, Modifier.fillMaxSize())
                        Label(numbers.first, a, Modifier.align(Alignment.TopStart).padding(8.dp), fallback = "A")
                    }
                    Box(m) {
                        CompareLayer(b, vm.right, Modifier.fillMaxSize())
                        Label(numbers.second, b, Modifier.align(Alignment.TopStart).padding(8.dp), fallback = "B")
                    }
                }
                if (landscape) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    content(Modifier.weight(1f).fillMaxHeight())
                } else Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    content(Modifier.weight(1f).fillMaxWidth())
                }
            }
        }

        if (a?.kind == MediaKind.Audio) {
            Column(Modifier.navigationBarsPadding().padding(horizontal = 14.dp, vertical = 8.dp)) {
                Timeline(
                    positionMs = { vm.left.positionMs },
                    durationMs = vm.left.durationMs,
                    bufferedMs = { vm.left.bufferedMs },
                    markers = emptyList(),
                    selectedMarker = null,
                    scrubSheet = null,
                    timeLabel = { formatTime(it, 0.0, dev.finalish.core.data.TimeFormat.Standard) },
                    onScrubStart = { vm.left.pause(); vm.right.pause() },
                    onScrub = { ms, exact -> vm.onEvent(CompareEvent.Seek(ms, exact)) },
                    onMarkerTap = {},
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                    LiveText({ formatTime(vm.left.positionMs, 0.0, dev.finalish.core.data.TimeFormat.Standard) }, style = TimecodeStyle, color = Palette.TextSecondary, modifier = Modifier.weight(1f))
                    IconButton(onClick = { vm.onEvent(CompareEvent.Nudge(-5_000)) }) { Icon(Icons.Rounded.Replay5, stringResource(R.string.viewer_back_5_seconds)) }
                    Box(
                        Modifier.size(48.dp).clip(CircleShape).background(Color.White).pressable({ vm.onEvent(CompareEvent.TogglePlay) }, scaleTo = 0.88f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(if (vm.left.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, stringResource(if (vm.left.isPlaying) R.string.viewer_pause else R.string.viewer_play), tint = Color.Black)
                    }
                    IconButton(onClick = { vm.onEvent(CompareEvent.Nudge(5_000)) }) { Icon(Icons.Rounded.Forward5, stringResource(R.string.viewer_forward_5_seconds)) }
                    // A | B: which take you hear.
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                        Row(Modifier.clip(RoundedCornerShape(12.dp)).background(Palette.Surface3).padding(3.dp)) {
                            listOf("A", "B").forEachIndexed { i, l ->
                                Box(
                                    Modifier.size(40.dp, 34.dp).clip(RoundedCornerShape(9.dp))
                                        .background(if (listening == i) Palette.Accent else Color.Transparent)
                                        .pressable({ vm.onEvent(CompareEvent.Listen(i)) }, scaleTo = 0.92f),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(l, style = MaterialTheme.typography.labelLarge, color = if (listening == i) Palette.OnAccent else Palette.TextSecondary)
                                }
                            }
                        }
                    }
                }
            }
        }
        if (a?.kind == MediaKind.Video) {
            Column(Modifier.navigationBarsPadding().padding(horizontal = 14.dp, vertical = 8.dp)) {
                Timeline(
                    positionMs = { vm.left.positionMs },
                    durationMs = vm.left.durationMs,
                    bufferedMs = { vm.left.bufferedMs },
                    markers = emptyList(),
                    selectedMarker = null,
                    scrubSheet = a.mediaLinks?.scrubSheet,
                    timeLabel = { formatTime(it, vm.fps(), dev.finalish.core.data.TimeFormat.Standard) },
                    onScrubStart = { vm.left.pause(); vm.right.pause() },
                    onScrub = { ms, exact -> vm.onEvent(CompareEvent.Seek(ms, exact)) },
                    onMarkerTap = {},
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                    LiveText({ formatTime(vm.left.positionMs, vm.fps(), dev.finalish.core.data.TimeFormat.Timecode) }, style = TimecodeStyle, color = Palette.TextSecondary, modifier = Modifier.weight(1f))
                    IconButton(onClick = { vm.onEvent(CompareEvent.Step(-1)) }) { Icon(Icons.Rounded.ChevronLeft, stringResource(R.string.viewer_previous_frame)) }
                    Box(
                        Modifier.size(48.dp).clip(CircleShape).background(Color.White).pressable({ vm.onEvent(CompareEvent.TogglePlay) }, scaleTo = 0.88f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(if (vm.left.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, stringResource(if (vm.left.isPlaying) R.string.viewer_pause else R.string.viewer_play), tint = Color.Black)
                    }
                    IconButton(onClick = { vm.onEvent(CompareEvent.Step(1)) }) { Icon(Icons.Rounded.ChevronRight, stringResource(R.string.viewer_next_frame)) }
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun CompareLayer(file: Asset, controller: PlayerController, modifier: Modifier) {
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        if (file.kind == MediaKind.Video) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Box(Modifier.aspectRatio(controller.videoAspect.coerceIn(0.3f, 4f), matchHeightConstraintsFirst = true)) {
                    VideoSurface(controller, Modifier.fillMaxSize())
                }
                // The paused first frame could stay black on one side of the wipe until Play:
                // draw it again once the video's size is known and the surface resized.
                LaunchedEffect(controller, controller.videoAspect) { controller.redraw() }
            }
        } else {
            RemoteImage(
                file.mediaLinks?.highQuality?.streamUrl ?: file.mediaLinks?.original?.inlineUrl ?: file.thumbnailUrl,
                Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

/**
 * Two takes, one above the other, playing in sync; the one you hear is lit. Tap a take
 * (or A | B below) to switch without stopping, the way an A/B switch works in a studio.
 */
@Composable
private fun AudioTakes(a: Asset, b: Asset, numbers: Pair<Int?, Int?>, listening: Int, vm: CompareViewModel, modifier: Modifier) {
    Column(modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf(Triple(a, vm.left, numbers.first), Triple(b, vm.right, numbers.second)).forEachIndexed { i, (file, player, version) ->
            val on = listening == i
            val peaks = rememberWaveformPeaks(file)
            Column(
                Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(20.dp))
                    .background(if (on) Palette.AccentContainer else Palette.Surface2)
                    .border(1.5.dp, if (on) Palette.Accent else Color.Transparent, RoundedCornerShape(20.dp))
                    .pressable({ vm.onEvent(CompareEvent.Listen(i)) }, scaleTo = 0.98f)
                    .padding(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (i == 0) "A" else "B",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (on) Palette.OnAccent else Palette.TextSecondary,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (on) Palette.Accent else Palette.Surface3).padding(horizontal = 10.dp, vertical = 2.dp),
                    )
                    Spacer(Modifier.size(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(file.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(
                                version?.let { stringResource(R.string.viewer_version_short, it) },
                                (player.durationMs.takeIf { it > 0 } ?: file.meta("Duration")?.doubleValue?.let { (it * 1000).toLong() })
                                    ?.let { formatTime(it, 0.0, dev.finalish.core.data.TimeFormat.Standard) },
                            )
                                .joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary,
                        )
                    }
                    if (on) Icon(Icons.AutoMirrored.Rounded.VolumeUp, stringResource(R.string.viewer_listening), tint = Palette.AccentBright)
                }
                androidx.compose.foundation.Canvas(Modifier.weight(1f).fillMaxWidth().padding(top = 10.dp)) {
                    drawWaveform(
                        peaks, player.positionMs.toFloat() / player.durationMs.coerceAtLeast(1),
                        played = if (on) Palette.Accent else Color.White.copy(alpha = 0.55f),
                        rest = Color.White.copy(alpha = if (on) 0.25f else 0.14f),
                    )
                }
            }
        }
        Text(
            stringResource(R.string.viewer_compare_audio_hint),
            style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}

@Composable
private fun WipeHandle(fraction: Float) {
    Box(Modifier.fillMaxSize()) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val x = size.width * fraction
            drawLine(Color.White, androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Offset(x, size.height), 2.dp.toPx())
            drawCircle(Color.White, 16.dp.toPx(), androidx.compose.ui.geometry.Offset(x, size.height / 2))
        }
    }
}

/** Version badge ("V2") with the file name in small type underneath. */
@Composable
private fun Label(version: Int?, file: Asset, modifier: Modifier, fallback: String, end: Boolean = false) {
    Column(
        modifier
            .widthIn(max = 220.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0x99000000))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = if (end) Alignment.End else Alignment.Start,
    ) {
        Text(
            version?.let { stringResource(R.string.viewer_version_short, it) } ?: fallback,
            style = MaterialTheme.typography.labelLarge,
            color = if (version != null) Palette.AccentBright else Color.White,
        )
        Text(
            file.name,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.75f),
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}
