package dev.finalish.ui.viewer

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import dev.finalish.core.data.TimeFormat
import dev.finalish.core.model.MediaLink
import dev.finalish.core.util.Format
import dev.finalish.ui.browser.ScrubFrame
import dev.finalish.ui.theme.Motion
import dev.finalish.ui.theme.Palette
import dev.finalish.ui.theme.TimecodeStyle
import kotlin.math.abs
import kotlin.math.roundToInt

@Immutable
data class TimelineMarker(
    val id: String,
    val startMs: Long,
    val endMs: Long?,
    val color: Color,
    /** Author, shown as a small avatar above the track (Frame.io style). */
    val user: dev.finalish.core.model.User? = null,
    val completed: Boolean = false,
)

@Composable
fun Timeline(
    /** Read while drawing only, so playback redraws the track without recomposing the bar. */
    positionMs: () -> Long,
    durationMs: Long,
    bufferedMs: () -> Long,
    markers: List<TimelineMarker>,
    selectedMarker: String?,
    scrubSheet: MediaLink?,
    timeLabel: (Long) -> String,
    onScrubStart: () -> Unit,
    onScrub: (ms: Long, exact: Boolean) -> Unit,
    onMarkerTap: (TimelineMarker) -> Unit,
    modifier: Modifier = Modifier,
    rangeStartMs: Long? = null,
    previewAspect: Float = 16f / 9f,
    previewFrame: (suspend (Long) -> ImageBitmap?)? = null,
    previewNearest: ((Long) -> ImageBitmap?)? = null,
) {
    val haptics = LocalHapticFeedback.current
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val trackHeight by animateDpAsState(if (dragging) 8.dp else 4.dp, Motion.snappy(), label = "track")
    val knobRadius by animateDpAsState(if (dragging) 9.dp else 7.dp, Motion.snappy(), label = "knob")
    val duration = durationMs.coerceAtLeast(1)
    fun fraction() = if (dragging) dragFraction else (positionMs().toFloat() / duration).coerceIn(0f, 1f)
    val density = LocalDensity.current
    // The gesture blocks below outlive recompositions (keyed on duration only): read the latest
    // markers and callbacks through these, or a drag would use the comments of when it started.
    val currentMarkers by rememberUpdatedState(markers)
    val currentOnScrubStart by rememberUpdatedState(onScrubStart)
    val currentOnScrub by rememberUpdatedState(onScrub)
    val currentOnMarkerTap by rememberUpdatedState(onMarkerTap)

    // The selected pin last, so it's drawn on top; sorted once per change, not on every frame.
    val pinOrder = remember(markers, selectedMarker) { markers.sortedBy { it.id == selectedMarker } }

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val widthPx = with(density) { maxWidth.toPx() }

        // Scrub preview bubble
        if (dragging) {
            val bubbleW = if (previewAspect < 1f) 96.dp else 150.dp
            val bubbleWPx = with(density) { bubbleW.toPx() }
            val meta = scrubSheet?.metadata
            // Unobserved: this first read mustn't tie the bar's composition to every move of the finger.
            var frame by remember {
                mutableStateOf(androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation { previewNearest?.invoke((dragFraction * duration).toLong()) })
            }
            if (previewFrame != null) {
                LaunchedEffect(previewFrame) {
                    snapshotFlow { (dragFraction * duration).toLong() / 100 }
                        .distinctUntilChanged()
                        .collectLatest { bucket ->
                            // Instant: nearest pre-decoded keyframe. Exact frame once the finger settles.
                            previewNearest?.invoke(bucket * 100)?.let { frame = it }
                            kotlinx.coroutines.delay(60)
                            previewFrame(bucket * 100)?.let { frame = it }
                        }
                }
            }
            Column(
                Modifier
                    // Zero-size layout: the bubble floats above the track without pushing the controls.
                    // It follows the finger in layout, so dragging doesn't recompose the bar.
                    .layout { measurable, constraints ->
                        val p = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0, maxHeight = Constraints.Infinity))
                        val x = (fraction() * widthPx - bubbleWPx / 2).coerceIn(0f, widthPx - bubbleWPx)
                        layout(0, 0) { p.place(x.roundToInt(), -p.height - 6.dp.roundToPx()) }
                    }
                    .width(bubbleW),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val f = frame
                if (f != null) {
                    androidx.compose.foundation.Image(
                        f, null,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(bubbleHeight(bubbleW, previewAspect))
                            .clip(RoundedCornerShape(10.dp))
                            .border(1.5.dp, Color.White.copy(alpha = 0.85f), RoundedCornerShape(10.dp)),
                    )
                } else if (meta != null && meta.tileX > 0 && meta.tileY > 0 && meta.thumbWidth > 0 && meta.thumbHeight > 0 && scrubSheet.streamUrl != null) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(bubbleW * (meta.thumbHeight.toFloat() / meta.thumbWidth.coerceAtLeast(1)))
                            .clip(RoundedCornerShape(10.dp))
                            .border(1.5.dp, Color.White.copy(alpha = 0.85f), RoundedCornerShape(10.dp)),
                    ) {
                        ScrubFrame(scrubSheet.streamUrl!!, meta, ::fraction, Modifier.fillMaxSize())
                    }
                }
                BubbleTime { timeLabel((fraction() * duration).toLong()) }
            }
        }

        Box(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .pointerInput(duration) {
                    detectTapGestures { pos ->
                        // Tap near a marker selects its comment, otherwise seeks.
                        val hit = currentMarkers.minByOrNull { abs(it.startMs.toFloat() / duration * size.width - pos.x) }
                        if (hit != null && abs(hit.startMs.toFloat() / duration * size.width - pos.x) < 14.dp.toPx() && pos.y < size.height / 2) {
                            currentOnMarkerTap(hit)
                        } else {
                            currentOnScrub((pos.x / size.width * duration).toLong(), true)
                        }
                    }
                }
                .pointerInput(duration) {
                    var lastMarkerHit: String? = null
                    detectHorizontalDragGestures(
                        onDragStart = { o ->
                            dragging = true
                            dragFraction = (o.x / size.width).coerceIn(0f, 1f)
                            currentOnScrubStart()
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        },
                        onDragEnd = {
                            currentOnScrub((dragFraction * duration).toLong(), true)
                            dragging = false
                        },
                        onDragCancel = { dragging = false },
                    ) { change, _ ->
                        change.consume()
                        dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                        val ms = (dragFraction * duration).toLong()
                        currentOnScrub(ms, false)
                        // Haptic detent when passing a comment.
                        val near = currentMarkers.firstOrNull { abs(it.startMs - ms) < duration / 200 + 40 }
                        if (near != null && near.id != lastMarkerHit) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                        lastMarkerHit = near?.id
                    }
                },
            contentAlignment = Alignment.BottomCenter,
        ) {
            Canvas(Modifier.fillMaxWidth().height(24.dp)) {
                val th = trackHeight.toPx()
                val cy = size.height / 2
                val r = CornerRadius(th / 2)
                drawRoundRect(Color.White.copy(alpha = 0.16f), Offset(0f, cy - th / 2), Size(size.width, th), r)
                val buf = (bufferedMs().toFloat() / duration).coerceIn(0f, 1f)
                drawRoundRect(Color.White.copy(alpha = 0.28f), Offset(0f, cy - th / 2), Size(size.width * buf, th), r)
                rangeStartMs?.let { start ->
                    val a = start.toFloat() / duration * size.width
                    val b = fraction() * size.width
                    drawRoundRect(
                        Palette.Amber.copy(alpha = 0.55f),
                        Offset(minOf(a, b), cy - th / 2 - 3f), Size(abs(b - a).coerceAtLeast(3f), th + 6f), r,
                    )
                }
                drawRoundRect(Palette.Accent, Offset(0f, cy - th / 2), Size(size.width * fraction(), th), r)

                markers.forEach { m ->
                    val x = m.startMs.toFloat() / duration * size.width
                    val selected = m.id == selectedMarker
                    m.endMs?.let { end ->
                        val x2 = end.toFloat() / duration * size.width
                        drawRoundRect(
                            m.color.copy(alpha = if (selected) 0.75f else 0.4f),
                            Offset(x, cy - th / 2 - 2f), Size((x2 - x).coerceAtLeast(2f), th + 4f), r,
                        )
                    }
                }

                // Comment pins: a dot in the author's colour on a short stem that touches the track.
                // Drawn after the fill so they sit on top; the selected one is bigger with a soft halo.
                pinOrder.forEach { m ->
                    val x = m.startMs.toFloat() / duration * size.width
                    val selected = m.id == selectedMarker
                    val alpha = if (m.completed && !selected) 0.4f else 1f
                    val dotR = if (selected) 7.dp.toPx() else 5.dp.toPx()
                    val stem = 5.dp.toPx()
                    val trackTop = cy - th / 2
                    val dotY = trackTop - stem - dotR
                    drawLine(m.color.copy(alpha = 0.7f * alpha), Offset(x, trackTop), Offset(x, dotY), 1.5.dp.toPx())
                    if (selected) drawCircle(m.color.copy(alpha = 0.3f), dotR + 3.dp.toPx(), Offset(x, dotY))
                    drawCircle(Palette.Ink.copy(alpha = 0.6f * alpha), dotR + 1.5.dp.toPx(), Offset(x, dotY))
                    drawCircle(m.color.copy(alpha = alpha), dotR, Offset(x, dotY))
                }

                val knob = knobRadius.toPx()
                drawCircle(Color.White, knob, Offset(size.width * fraction(), cy))
            }
        }
    }
}

/** The scrub bubble's time; its own scope, so the label changing as the finger moves recomposes only it. */
@Composable
private fun BubbleTime(text: () -> String) {
    Text(
        text(),
        style = TimecodeStyle,
        color = Color.White,
        modifier = Modifier
            .padding(top = 6.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xCC000000))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** Compact time readout under the scrubber. */
private fun bubbleHeight(w: androidx.compose.ui.unit.Dp, aspect: Float) = w / aspect.coerceIn(0.4f, 3f)

@Composable
fun TimeReadout(text: String, modifier: Modifier = Modifier) {
    Text(text, style = TimecodeStyle, color = Color.White.copy(alpha = 0.9f), modifier = modifier)
}

fun formatTime(ms: Long, fps: Double, format: TimeFormat): String = when (format) {
    TimeFormat.Timecode -> Format.timecode(ms, fps)
    TimeFormat.Standard -> Format.clock(ms)
    TimeFormat.Frames -> Format.msToFrame(ms, fps).toString()
}

/** Text that changes every frame (a running timecode) in its own small recomposition scope. */
@Composable
fun LiveText(
    text: () -> String,
    style: androidx.compose.ui.text.TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
) {
    Text(text(), style = style, color = color, maxLines = maxLines, modifier = modifier)
}
