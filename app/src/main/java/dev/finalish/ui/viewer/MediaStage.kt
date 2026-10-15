package dev.finalish.ui.viewer

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.view.TextureView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.magnifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import dev.finalish.ui.components.pressable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ViewDay
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Search
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.rounded.Download
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.request.ImageRequest
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.finalish.R
import dev.finalish.core.model.Asset
import dev.finalish.core.model.Comment
import dev.finalish.core.model.MediaKind
import dev.finalish.ui.components.KindPlaceholder
import dev.finalish.ui.components.SecondaryButton
import dev.finalish.ui.components.stableImageKey
import dev.finalish.ui.theme.Palette
import java.io.File
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import okhttp3.Request

/** Shared overlays rendered on top of the media frame (same coordinate space). */
@Composable
fun BoxScope.FrameOverlays(
    visibleComments: List<Comment>,
    composer: ComposerState,
    onShape: (Shape) -> Unit,
    onPlaceAnchor: (Offset) -> Unit,
    pins: List<NumberedPin> = emptyList(),
    onPin: (String) -> Unit = {},
) {
    visibleComments.forEach { c ->
        val shapes = remember(c.annotation) { AnnotationCodec.decode(c.annotation) }
        AnnotationLayer(shapes)
        // Numbered pins already mark every anchored comment.
        if (pins.isEmpty()) c.anchor?.let { AnchorPin(Offset(it.x, it.y), Palette.Accent) }
    }
    if (pins.isNotEmpty() && !composer.drawing && !composer.placingAnchor) {
        val selected = visibleComments.map { it.id }.toSet()
        BoxWithConstraints(Modifier.matchParentSize()) {
            val w = constraints.maxWidth
            val h = constraints.maxHeight
            val d = 26.dp
            val half = with(androidx.compose.ui.platform.LocalDensity.current) { (d / 2).roundToPx() }
            pins.forEach { pin ->
                val on = pin.commentId in selected
                val pinLabel = stringResource(R.string.viewer_pin_comment, pin.number)
                Box(
                    Modifier
                        .offset { IntOffset((pin.at.x * w).roundToInt() - half, (pin.at.y * h).roundToInt() - half) }
                        .size(d)
                        .clip(CircleShape)
                        .background(if (on) Palette.Amber else Palette.Accent)
                        .border(2.dp, Color.White, CircleShape)
                        .pointerInput(pin.commentId) { detectTapGestures { onPin(pin.commentId) } }
                        .semantics { contentDescription = pinLabel },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("${pin.number}", style = MaterialTheme.typography.labelMedium, color = Palette.OnAccent)
                }
            }
        }
    }
    composer.anchor?.let { AnchorPin(it, Palette.Amber) }
    if (composer.drawing) {
        DrawingLayer(composer.shapes, composer.tool, composer.color, onShape, Modifier.matchParentSize())
    } else if (composer.shapes.isNotEmpty()) {
        AnnotationLayer(composer.shapes, Modifier.matchParentSize())
    }
    if (composer.placingAnchor) {
        Box(
            Modifier
                .matchParentSize()
                .background(Color(0x33000000))
                .pointerInput(Unit) {
                    detectTapGestures { p -> onPlaceAnchor(Offset(p.x / size.width, p.y / size.height)) }
                },
        )
    }
}

/** A comment's pin on an image, numbered like its card in the list. */
data class NumberedPin(val commentId: String, val number: Int, val at: Offset)

@Composable
private fun BoxScope.AnchorPin(at: Offset, color: Color) {
    var appear by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appear = true }
    val s by animateFloatAsState(if (appear) 1f else 0.2f, label = "pin")
    Box(Modifier.matchParentSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(at.x * size.width, at.y * size.height)
            drawCircle(color.copy(alpha = 0.25f), 18.dp.toPx() * s, c)
            drawCircle(Color.White, 8.dp.toPx() * s, c)
            drawCircle(color, 6.dp.toPx() * s, c)
        }
    }
}

/**
 * Pinch-to-zoom (up to 4x) and pan container. Gestures pass through untouched
 * when [enabled] is false (e.g. while drawing).
 */
@Composable
fun ZoomBox(
    enabled: Boolean,
    onTap: () -> Unit,
    onDoubleTapSide: ((left: Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    /** Bump to snap back to fit. */
    resetKey: Int = 0,
    onScaleChange: ((Float) -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    // Fingers move the image 1:1; only taps, resets and the snap back after a pinch are animated.
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var settle by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun animateTo(toScale: Float, toOffset: Offset) {
        settle?.cancel()
        val fromScale = scale
        val fromOffset = offset
        settle = scope.launch {
            androidx.compose.animation.core.animate(0f, 1f, animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.9f, stiffness = 500f)) { t, _ ->
                scale = fromScale + (toScale - fromScale) * t
                offset = fromOffset + (toOffset - fromOffset) * t
            }
        }
    }
    LaunchedEffect(resetKey) { if (resetKey != 0) animateTo(1f, Offset.Zero) }
    // Reported from a snapshot flow, not by keying an effect on the scale: that relaunched it (and
    // recomposed this box) on every frame of a pinch.
    val currentOnScaleChange by androidx.compose.runtime.rememberUpdatedState(onScaleChange)
    LaunchedEffect(Unit) { androidx.compose.runtime.snapshotFlow { scale }.collect { currentOnScaleChange?.invoke(it) } }
    Box(
        modifier
            // A zoomed image stays inside its stage instead of spilling over the title bar.
            .clipToBounds()
            .then(
                if (enabled) Modifier
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { onTap() },
                            onDoubleTap = { p ->
                                if (scale > 1.01f) {
                                    animateTo(1f, Offset.Zero)
                                } else if (onDoubleTapSide != null) {
                                    onDoubleTapSide(p.x < size.width / 2)
                                } else {
                                    // Zoom in on the spot that was tapped.
                                    val z = 2.5f
                                    val c = Offset(size.width / 2f, size.height / 2f)
                                    val maxX = size.width * (z - 1) / 2
                                    val maxY = size.height * (z - 1) / 2
                                    val o = (p - c) * (1 - z)
                                    animateTo(z, Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY)))
                                }
                            },
                        )
                    }
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            do {
                                val event = awaitPointerEvent()
                                val zoom = event.calculateZoom()
                                val pan = event.calculatePan()
                                val multi = event.changes.size > 1
                                // The last event of a gesture has no finger down: its centroid is
                                // undefined (NaN), which would send the image off to nowhere.
                                val centroid = event.calculateCentroid(useCurrent = true)
                                if ((multi || scale > 1.01f) && centroid.isSpecified) {
                                    settle?.cancel()
                                    val newScale = (scale * zoom).coerceIn(1f, 4f)
                                    val z = newScale / scale
                                    // Keep the point between the fingers under the fingers.
                                    val c = centroid - Offset(size.width / 2f, size.height / 2f)
                                    val anchored = c * (1 - z) + offset * z + pan
                                    val maxX = size.width * (newScale - 1) / 2
                                    val maxY = size.height * (newScale - 1) / 2
                                    if (anchored.x.isFinite() && anchored.y.isFinite()) {
                                        offset = Offset(anchored.x.coerceIn(-maxX, maxX), anchored.y.coerceIn(-maxY, maxY))
                                        scale = newScale
                                    }
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                }
                            } while (event.changes.any { it.pressed })
                            if (scale < 1.02f && (scale != 1f || offset != Offset.Zero)) animateTo(1f, Offset.Zero)
                        }
                    }
                else Modifier,
            )
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
        contentAlignment = Alignment.Center,
        content = content,
    )
}

@Composable
fun VideoSurface(controller: PlayerController, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val view = remember { TextureView(context) }
    DisposableEffect(view) {
        controller.attach(view)
        onDispose { controller.detach(view) }
    }
    AndroidView(
        factory = { view },
        modifier = modifier.onGloballyPositioned { c ->
            val r = c.boundsInWindow()
            controller.surfaceBounds = android.graphics.Rect(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt())
        },
    )
}

/** Video or audio frame (letterboxed to the content aspect) plus overlays. */
@Composable
fun VideoStage(
    file: Asset,
    controller: PlayerController,
    overlays: @Composable BoxScope.() -> Unit,
    modifier: Modifier = Modifier,
    view: ViewOptions = ViewOptions(),
) {
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.clipToBounds(), contentAlignment = Alignment.Center) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val boxW = with(density) { maxWidth.toPx() }
        val boxH = with(density) { maxHeight.toPx() }
        if (file.kind == MediaKind.Audio) {
            AudioVisual(file, controller, Modifier.fillMaxSize())
        } else {
            val va = controller.videoAspect
            val fitW = if (boxW / boxH > va) boxH * va else boxW
            val zoom = when (view.zoom) {
                ZoomMode.Fit -> 1f
                ZoomMode.Fill -> maxOf(boxW / fitW, boxH / (fitW / va))
                ZoomMode.Actual -> {
                    val px = controller.player.videoSize.width
                    if (px > 0) px / fitW else 1f
                }
            }
            val animZoom by animateFloatAsState(zoom, label = "zoomMode")
            Box(
                Modifier
                    .aspectRatio(va, matchHeightConstraintsFirst = boxW / boxH > va)
                    .graphicsLayer { scaleX = animZoom; scaleY = animZoom },
            ) {
                VideoSurface(controller, Modifier.fillMaxSize())
                if (!controller.hasVideo) {
                    file.thumbnailUrl?.let { t ->
                        coil3.compose.AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current).data(t)
                                .memoryCacheKey(stableImageKey(t)).diskCacheKey(stableImageKey(t)).build(),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                GuidesOverlay(view)
                overlays()
            }
        }
        if (controller.isBuffering) {
            CircularProgressIndicator(Modifier.size(36.dp), color = Color.White, strokeWidth = 2.5.dp)
        }
        controller.error?.let { err ->
            Text(
                stringResource(R.string.viewer_playback_error, err),
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xCC000000))
                    .padding(12.dp),
            )
        }
    }
}

@Composable
private fun AudioVisual(file: Asset, controller: PlayerController, modifier: Modifier) {
    val peaks = rememberWaveformPeaks(file)
    var phase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(peaks == null) {
        while (peaks == null) { androidx.compose.runtime.withFrameMillis { phase = it / 300f } }
    }
    Box(
        modifier.background(Brush.radialGradient(listOf(Palette.AccentContainer, Palette.Ink))),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxWidth(0.86f).aspectRatio(3.2f)) {
            drawWaveform(peaks, controller.positionMs.toFloat() / controller.durationMs.coerceAtLeast(1), phase)
        }
    }
}

/** The real waveform of an audio file, decoded once per version and cached (never decorative bars). */
@Composable
internal fun rememberWaveformPeaks(file: Asset, bins: Int = 120): FloatArray? {
    val context = LocalContext.current
    val links = file.media.mediaLinks
    val source = links?.original?.inlineUrl ?: links?.original?.downloadUrl ?: links?.highQuality?.streamUrl ?: links?.efficient?.streamUrl
    val peaks by androidx.compose.runtime.produceState<FloatArray?>(null, file.media.id, source) {
        if (source != null) value = dev.finalish.core.media.Waveform.peaks(context, source, "${file.media.id}:${file.media.updatedAt}", bins = bins)
    }
    return peaks
}

/** Bars up to [progress] in [played], the rest dim, and a playhead; a breathing line while [peaks] decode. */
internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawWaveform(
    peaks: FloatArray?,
    progress: Float,
    phase: Float = 0f,
    played: Color = Palette.Accent,
    rest: Color = Color.White.copy(alpha = 0.22f),
    playhead: Boolean = true,
) {
    if (peaks == null) {
        val a = 0.5f + 0.5f * sin(phase)
        drawLine(Palette.AccentBright.copy(alpha = 0.25f + 0.35f * a), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 3.dp.toPx())
        return
    }
    val n = peaks.size
    val gap = size.width / n
    val bw = (gap * 0.62f).coerceAtLeast(1.5f)
    for (i in 0 until n) {
        val h = size.height * peaks[i].coerceIn(0.04f, 1f)
        drawRoundRect(
            if ((i + 0.5f) / n <= progress) played else rest,
            topLeft = Offset(i * gap, (size.height - h) / 2),
            size = androidx.compose.ui.geometry.Size(bw, h),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(bw / 2),
        )
    }
    if (playhead) {
        val x = size.width * progress.coerceIn(0f, 1f)
        drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), 2.dp.toPx())
    }
}

/** Full-resolution image with overlays; reports its aspect once known. */
@Composable
fun ImageStage(
    file: Asset,
    offlinePath: String?,
    overlays: @Composable BoxScope.() -> Unit,
    modifier: Modifier = Modifier,
    view: ViewOptions = ViewOptions(),
) {
    val context = LocalContext.current
    var aspect by remember(file.id) { mutableFloatStateOf(0f) }
    val url = offlinePath?.let { File(it) }
        ?: file.mediaLinks?.highQuality?.streamUrl
        ?: file.mediaLinks?.original?.inlineUrl
        ?: file.mediaLinks?.thumbnailHq?.streamUrl
        // Frame.io makes no picture of an SVG: draw the (tiny) original itself.
        ?: file.mediaLinks?.original?.downloadUrl?.takeIf { file.media.name.endsWith(".svg", ignoreCase = true) }
    val placeholder = file.thumbnailUrl
    Box(modifier, contentAlignment = Alignment.Center) {
        Box(
            if (aspect > 0f) Modifier.aspectRatio(aspect, matchHeightConstraintsFirst = true) else Modifier.fillMaxSize(),
        ) {
            if (placeholder != null && aspect == 0f) {
                AsyncImage(
                    model = ImageRequest.Builder(context).data(placeholder)
                        .memoryCacheKey(stableImageKey(placeholder)).diskCacheKey(stableImageKey(placeholder)).build(),
                    contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize(),
                )
            }
            AsyncImage(
                model = ImageRequest.Builder(context).data(url).apply {
                    (url as? String)?.let { memoryCacheKey(stableImageKey(it)); diskCacheKey(stableImageKey(it)) }
                }.build(),
                contentDescription = file.name,
                contentScale = ContentScale.Fit,
                onState = { s ->
                    if (s is AsyncImagePainter.State.Success) {
                        val img = s.result.image
                        if (img.height > 0) aspect = img.width.toFloat() / img.height
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            if (aspect > 0f) {
                GuidesOverlay(view)
                overlays()
            }
        }
    }
}

/** Renders PDF pages with the platform PdfRenderer; reports the visible page. */
@Composable
fun PdfStage(
    file: Asset,
    offlinePath: String?,
    page: Int,
    onPageChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    commentPages: Set<Int> = emptySet(),
) {
    val context = LocalContext.current
    val container = remember { dev.finalish.FinalishApp.container(context) }
    // The browse listing has no original link: the viewer swaps in the full file a moment later,
    // so the download is keyed on the link too, not just on the file.
    val url = file.mediaLinks?.original?.inlineUrl ?: file.mediaLinks?.original?.downloadUrl
    var attempt by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    // null while loading; a failure shows a message with Retry instead of an endless spinner.
    val local by produceState<Result<File>?>(null, file.id, offlinePath, url, attempt) {
        value = null
        value = withContext(Dispatchers.IO) {
            offlinePath?.let { File(it) }?.takeIf { it.exists() }?.let { return@withContext Result.success(it) }
            val target = File(context.cacheDir, "pdf/${file.id}.pdf").apply { parentFile?.mkdirs() }
            if (target.exists()) return@withContext Result.success(target)
            // No link yet (still the listing's copy): wait for the full file, which restarts this.
            // Still none after a while (downloads turned off on the project): give up.
            val link = url ?: run {
                kotlinx.coroutines.delay(15_000)
                return@withContext Result.failure(IllegalStateException("No original link"))
            }
            // Into a .part first: a cut-off download (or an error page) never passes for the PDF.
            val tmp = File(target.parentFile, target.name + ".part")
            runCatching {
                container.baseHttp.newCall(Request.Builder().url(link).build()).execute().use { r ->
                    if (!r.isSuccessful) error("HTTP ${r.code}")
                    r.body.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
                }
                if (!tmp.renameTo(target)) error("Could not save the PDF")
                target
            }.also {
                tmp.delete()
                it.exceptionOrNull()?.let { e -> android.util.Log.w("PdfStage", "PDF download failed: ${e.message}") }
            }
        }
    }
    val result = local
    if (result == null || result.isFailure) {
        Box(modifier, contentAlignment = Alignment.Center) {
            if (result == null) CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 2.dp)
            else dev.finalish.ui.components.ErrorState(
                androidx.compose.ui.res.stringResource(dev.finalish.R.string.viewer_error_open_file),
                onRetry = { attempt++ },
            )
        }
        return
    }
    val f = result.getOrThrow()
    val renderer = remember(f) {
        runCatching { PdfRenderer(ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)) }.getOrNull()
    }
    val closed = remember(renderer) { java.util.concurrent.atomic.AtomicBoolean(false) }
    DisposableEffect(renderer) {
        onDispose {
            if (renderer != null) {
                closed.set(true)
                // A page may still be rendering on IO, and closing with a page open throws:
                // close under the same lock, off the main thread.
                Dispatchers.IO.asExecutor().execute { synchronized(renderer) { renderer.close() } }
            }
        }
    }
    if (renderer == null) {
        OtherStage(file, modifier)
        return
    }
    val count = renderer.pageCount
    // Page sizes in PDF points, to place search highlights on the rendered bitmaps. Opening every
    // page takes a while on long documents: measured on IO, under the renderer's lock.
    val measured by produceState<List<androidx.compose.ui.geometry.Size>?>(null, renderer) {
        value = withContext(Dispatchers.IO) {
            synchronized(renderer) {
                if (closed.get()) null
                else List(count) { i -> renderer.openPage(i).use { androidx.compose.ui.geometry.Size(it.width.toFloat(), it.height.toFloat()) } }
            }
        }
    }
    val pageSizes = measured ?: run {
        Box(modifier, contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 2.dp)
        }
        return
    }
    var singlePage by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var matches by remember { mutableStateOf<List<PdfMatch>>(emptyList()) }
    var current by remember { mutableIntStateOf(0) }
    val canSearch = android.os.Build.VERSION.SDK_INT >= 35
    LaunchedEffect(query, searching) {
        if (!searching || query.trim().length < 2) { matches = emptyList(); return@LaunchedEffect }
        kotlinx.coroutines.delay(300)
        matches = withContext(Dispatchers.IO) { searchPdf(renderer, query.trim()) }
        current = 0
        matches.firstOrNull()?.let { onPageChange(it.page + 1) }
    }
    val highlights = remember(matches, current) { matches.mapIndexed { i, m -> Triple(m.page, m.rects, i == current) }.groupBy { it.first } }

    val renderPage: @Composable (Int, Modifier) -> Unit = { index, m ->
        val bmp by produceState<Bitmap?>(null, index) {
            value = withContext(Dispatchers.IO) {
                synchronized(renderer) {
                    if (closed.get()) return@withContext null
                    renderer.openPage(index).use { p ->
                        val w = 1400
                        val h = (w * p.height.toFloat() / p.width).roundToInt()
                        createBitmap(w, h).also { b ->
                            b.eraseColor(android.graphics.Color.WHITE)
                            p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    }
                }
            }
        }
        val size = pageSizes[index]
        Box(m.aspectRatio(size.width / size.height).clip(RoundedCornerShape(6.dp)).background(Color.White)) {
            bmp?.let { Image(it.asImageBitmap(), stringResource(R.string.viewer_pdf_page, index + 1), Modifier.fillMaxSize()) }
            highlights[index]?.let { hits ->
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    val k = this.size.width / size.width
                    hits.forEach { (_, rects, active) ->
                        rects.forEach { r ->
                            drawRect(
                                if (active) Palette.Amber.copy(alpha = 0.55f) else Palette.Amber.copy(alpha = 0.25f),
                                topLeft = androidx.compose.ui.geometry.Offset(r.left * k, r.top * k),
                                size = androidx.compose.ui.geometry.Size(r.width() * k, r.height() * k),
                            )
                        }
                    }
                }
            }
            Row(
                Modifier.align(Alignment.BottomEnd).padding(8.dp).clip(CircleShape).background(Color(0x99000000)).padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // A dot on pages that have comments (mockup's page strip).
                if (index + 1 in commentPages) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(Palette.Red))
                    Spacer(Modifier.width(5.dp))
                }
                Text("${index + 1}", style = MaterialTheme.typography.labelSmall, color = Color.White)
            }
        }
    }

    Box(modifier.background(Palette.Surface1)) {
        if (singlePage) {
            val pager = androidx.compose.foundation.pager.rememberPagerState(initialPage = (page - 1).coerceIn(0, count - 1)) { count }
            LaunchedEffect(page) { if (pager.currentPage != page - 1) pager.animateScrollToPage((page - 1).coerceIn(0, count - 1)) }
            LaunchedEffect(pager) { snapshotFlow { pager.currentPage + 1 }.distinctUntilChanged().collect(onPageChange) }
            androidx.compose.foundation.pager.HorizontalPager(pager, Modifier.fillMaxSize(), pageSpacing = 12.dp) { index ->
                Box(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.Center) { renderPage(index, Modifier) }
            }
        } else {
            val listState = rememberLazyListState()
            var widthPx by remember { mutableIntStateOf(0) }
            val padPx = with(androidx.compose.ui.platform.LocalDensity.current) { 12.dp.roundToPx() }
            LaunchedEffect(page) {
                if (listState.firstVisibleItemIndex != page - 1) listState.animateScrollToItem((page - 1).coerceAtLeast(0))
            }
            // Bring the current search hit into view, a little below the search bar.
            LaunchedEffect(matches, current) {
                val m = matches.getOrNull(current) ?: return@LaunchedEffect
                val top = m.rects.minOfOrNull { it.top } ?: return@LaunchedEffect
                val k = (widthPx - 2 * padPx) / pageSizes[m.page].width
                listState.animateScrollToItem(m.page, (top * k).toInt() - widthPx / 3)
            }
            LaunchedEffect(listState) {
                snapshotFlow { listState.firstVisibleItemIndex + 1 }.distinctUntilChanged().collect(onPageChange)
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().onSizeChanged { widthPx = it.width },
                verticalArrangement = Arrangement.spacedBy(12.dp),
                // Room at the top for the floating tools, so they don't sit on the first page.
                contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, end = 12.dp, top = 62.dp, bottom = 12.dp),
            ) {
                items(count, key = { it }) { index -> renderPage(index, Modifier.fillMaxWidth()) }
            }
        }

        // Tools (mockup): single page, search. The search bar takes their place while open.
        if (!searching) Row(
            Modifier.align(Alignment.TopEnd).padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (count > 1) PdfTool(if (singlePage) Icons.Rounded.ViewDay else Icons.Rounded.Description, if (singlePage) stringResource(R.string.viewer_pdf_scroll_pages) else stringResource(R.string.viewer_pdf_single_page), singlePage) { singlePage = !singlePage }
            if (canSearch) PdfTool(Icons.Rounded.Search, stringResource(R.string.viewer_pdf_find), false) { searching = true }
        }
        androidx.compose.animation.AnimatedVisibility(searching, Modifier.align(Alignment.TopCenter).padding(10.dp)) {
            PdfSearchBar(
                query = query,
                onQuery = { query = it },
                status = when {
                    query.trim().length < 2 -> null
                    matches.isEmpty() -> stringResource(R.string.viewer_pdf_no_matches)
                    else -> stringResource(R.string.viewer_pdf_match_position, current + 1, matches.size)
                },
                onStep = { d ->
                    if (matches.isNotEmpty()) {
                        current = (current + d).mod(matches.size)
                        onPageChange(matches[current].page + 1)
                    }
                },
                onClose = { searching = false; query = "" },
            )
        }

        // Where you are in the document (mockup "3 / 12").
        Text(
            stringResource(R.string.viewer_page_counter, page, count),
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 14.dp)
                .clip(RoundedCornerShape(50))
                .background(Color(0xB3000000))
                .padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}

/** One search hit: its page (0-based) and highlight boxes in PDF points. */
private class PdfMatch(val page: Int, val rects: List<android.graphics.RectF>)

@androidx.annotation.RequiresApi(35)
private fun searchPdfPages(renderer: PdfRenderer, query: String): List<PdfMatch> = buildList {
    for (i in 0 until renderer.pageCount) {
        synchronized(renderer) {
            renderer.openPage(i).use { p -> p.searchText(query).forEach { add(PdfMatch(i, it.bounds)) } }
        }
    }
}

private fun searchPdf(renderer: PdfRenderer, query: String): List<PdfMatch> =
    if (android.os.Build.VERSION.SDK_INT >= 35) runCatching { searchPdfPages(renderer, query) }.getOrDefault(emptyList()) else emptyList()

@Composable
private fun PdfTool(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(if (on) Palette.Accent else Color(0xB3000000)).pressable(onClick, scaleTo = 0.9f),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = if (on) Palette.OnAccent else Color.White, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun PdfSearchBar(query: String, onQuery: (String) -> Unit, status: String?, onStep: (Int) -> Unit, onClose: () -> Unit) {
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.Surface3).padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, tint = Palette.TextTertiary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f).padding(vertical = 12.dp)) {
            if (query.isEmpty()) Text(stringResource(R.string.viewer_pdf_find), style = MaterialTheme.typography.bodyMedium, color = Palette.TextTertiary)
            androidx.compose.foundation.text.BasicTextField(
                query, onQuery,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = Palette.Text),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(Palette.Accent),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { onStep(1) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
        status?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = Palette.TextSecondary, modifier = Modifier.padding(horizontal = 6.dp)) }
        androidx.compose.material3.IconButton(onClick = { onStep(-1) }) { Icon(Icons.Rounded.KeyboardArrowUp, stringResource(R.string.viewer_pdf_previous_match)) }
        androidx.compose.material3.IconButton(onClick = { onStep(1) }) { Icon(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.viewer_pdf_next_match)) }
        androidx.compose.material3.IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, stringResource(R.string.viewer_pdf_close_search)) }
    }
}

/** Files the phone can't preview (zip, html, design files…): open elsewhere, download, see a ZIP's contents. */
@Composable
fun OtherStage(
    file: Asset,
    modifier: Modifier = Modifier,
    onOpenWith: (() -> Unit)? = null,
    onDownload: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val isZip = file.name.endsWith(".zip", ignoreCase = true) || file.media.mediaType == "application/zip"
    val entries by produceState<List<dev.finalish.core.media.ZipPeek.Entry>?>(null, file.id) {
        val url = file.mediaLinks?.original?.downloadUrl ?: file.mediaLinks?.original?.inlineUrl
        if (isZip && url != null) value = withContext(Dispatchers.IO) {
            runCatching {
                dev.finalish.core.media.ZipPeek.list(dev.finalish.FinalishApp.container(context).baseHttp, url, file.media.fileSize)
            }.getOrNull()
        }
    }
    Column(
        modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        KindPlaceholder(file.kind, Modifier.size(88.dp).clip(RoundedCornerShape(24.dp)).border(1.dp, Palette.Hairline, RoundedCornerShape(24.dp)))
        Text(stringResource(R.string.viewer_no_preview_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
        Text(
            stringResource(R.string.viewer_no_preview_body),
            style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            onOpenWith?.let { SecondaryButton(stringResource(R.string.viewer_open_with), icon = Icons.AutoMirrored.Rounded.OpenInNew, onClick = it) }
            onDownload?.let { SecondaryButton(stringResource(R.string.viewer_action_download), icon = Icons.Rounded.Download, onClick = it) }
        }
        // Interactive HTML (a zip with index.html) plays on Frame.io's web viewer.
        if (isZip && entries?.any { it.name.substringAfterLast('/') == "index.html" } == true) file.viewUrl?.let { url ->
            androidx.compose.material3.TextButton(onClick = {
                context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, url.toUri()))
            }) { Text(stringResource(R.string.viewer_open_interactive_preview)) }
        }
        entries?.takeIf { it.isNotEmpty() }?.let { list ->
            Column(
                Modifier.padding(top = 18.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.Surface2).padding(vertical = 6.dp),
            ) {
                Text(
                    stringResource(R.string.viewer_zip_contents, list.size), style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                )
                list.take(50).forEach { e ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(e.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(dev.finalish.core.util.Format.bytes(e.size), style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary)
                    }
                }
                if (list.size > 50) {
                    Text(pluralStringResource(R.plurals.viewer_zip_more, list.size - 50, list.size - 50), style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
                }
            }
        }
    }
}

/** Big transient icon for double-tap seek feedback. */
@Composable
fun SeekFlash(visible: Boolean, left: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(visible, enter = scaleIn() + fadeIn(), exit = fadeOut(), modifier = modifier) {
        Text(
            if (left) stringResource(R.string.viewer_seek_back_flash) else stringResource(R.string.viewer_seek_forward_flash),
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(Color(0x66000000))
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .offset { IntOffset.Zero },
        )
    }
}
