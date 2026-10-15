package dev.finalish.ui.viewer

import androidx.compose.ui.layout.layout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.mandatorySystemGestures
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.mutableFloatStateOf
import kotlin.math.roundToInt
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.stringResource
import dev.finalish.R
import dev.finalish.core.model.Asset
import dev.finalish.core.model.Comment
import dev.finalish.core.model.MediaKind
import dev.finalish.ui.components.GlassIconButton
import dev.finalish.ui.components.pressable
import dev.finalish.ui.theme.Palette
import kotlinx.coroutines.delay

@Composable
internal fun MediaArea(
    file: Asset,
    state: ViewerState,
    composer: ComposerState,
    controller: PlayerController,
    vm: ViewerViewModel,
    frameComments: List<Comment>,
    immersive: Boolean,
    fullscreen: Boolean,
    timeLabel: (Long) -> String,
    fps: Double,
    settingsLoop: Boolean,
    onToggleFullscreen: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier,
    onMore: () -> Unit = {},
    commentsOpen: Boolean = false,
    onToggleComments: () -> Unit = {},
    onCommentHere: () -> Unit = {},
    siblings: List<Asset>? = null,
) {
    var chromeVisible by remember { mutableStateOf(true) }
    val viewerContext = LocalContext.current
    val res = androidx.compose.ui.platform.LocalResources.current
    var seekFlash by remember { mutableIntStateOf(0) } // -1 left, 1 right
    LaunchedEffect(chromeVisible, controller.isPlaying, immersive) {
        if (immersive && chromeVisible && controller.isPlaying) {
            delay(2800)
            chromeVisible = false
        }
    }
    LaunchedEffect(seekFlash) { if (seekFlash != 0) { delay(450); seekFlash = 0 } }

    val view by vm.view.collectAsStateWithLifecycle()
    val overlays: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {
        FrameOverlays(
            frameComments, composer, vm::addShape, vm::placeAnchor,
            pins = if (file.kind == MediaKind.Image && view.showAnnotations) imagePins(state.comments) else emptyList(),
            onPin = { id -> state.comments.firstOrNull { it.id == id }?.let(vm::selectComment) },
        )
    }

    var zoom by remember { mutableFloatStateOf(1f) }
    var zoomReset by remember { mutableIntStateOf(0) }
    // The stage only cares whether it's zoomed; the percentage is read by the chip alone, so a pinch
    // doesn't recompose the whole stage every frame.
    val zoomed by remember { derivedStateOf { zoom > 1.01f } }
    LaunchedEffect(file.id) { zoomReset++ }
    Box(modifier.background(Color.Black)) {
        val stage: @Composable () -> Unit = {
            ZoomBox(
                enabled = !composer.drawing && !composer.placingAnchor && state.kind != MediaKind.Pdf,
                resetKey = zoomReset,
                onScaleChange = { zoom = it },
                onTap = {
                    if (immersive) chromeVisible = !chromeVisible
                    else if (state.kind == MediaKind.Video || state.kind == MediaKind.Audio) controller.toggle()
                    vm.clearSelection()
                },
                onDoubleTapSide = if (state.kind == MediaKind.Video) { left ->
                    controller.seekTo(controller.player.currentPosition + if (left) -5_000 else 5_000)
                    seekFlash = if (left) -1 else 1
                } else null,
                modifier = Modifier.fillMaxSize(),
            ) {
                when (file.kind) {
                    MediaKind.Video, MediaKind.Audio -> VideoStage(file, controller, overlays, Modifier.fillMaxSize(), view)
                    MediaKind.Image -> ImageStage(file, state.offlinePath, overlays, Modifier.fillMaxSize(), view)
                    MediaKind.Pdf -> PdfStage(
                        file, state.offlinePath, state.pdfPage, vm::setPdfPage, Modifier.fillMaxSize(),
                        commentPages = remember(state.comments) { state.comments.mapNotNull { it.page }.toSet() },
                    )
                    else -> if (file.media.name.substringAfterLast('.', "").lowercase() in dev.finalish.core.media.OfficePreview.EXTENSIONS) {
                        OfficeStage(
                            file, state.offlinePath, Modifier.fillMaxSize(),
                            onOpenWith = { vm.openWith(viewerContext) },
                            onDownload = vm::saveToDevice,
                        )
                    } else OtherStage(
                        file, Modifier.fillMaxSize(),
                        onOpenWith = { vm.openWith(viewerContext) },
                        onDownload = vm::saveToDevice,
                    )
                }
            }
        }
        val pages = siblings?.takeIf { file.kind == MediaKind.Image && it.size > 1 && it.any { a -> a.id == file.id } }
        if (pages == null) stage() else {
            // Swiping through the folder's images: only the picture slides; bars, info and comments
            // stay and follow the image that settles (the viewer rebinds to it).
            val pager = androidx.compose.foundation.pager.rememberPagerState(initialPage = pages.indexOfFirst { it.id == file.id }.coerceAtLeast(0)) { pages.size }
            LaunchedEffect(pager, pages) {
                androidx.compose.runtime.snapshotFlow { pager.settledPage }.collect { page -> pages.getOrNull(page)?.let(vm::showSibling) }
            }
            // The shown file changed some other way: keep the pager on it.
            LaunchedEffect(file.id) {
                val i = pages.indexOfFirst { it.id == file.id }
                if (i >= 0 && i != pager.settledPage && !pager.isScrollInProgress) pager.scrollToPage(i)
            }
            androidx.compose.foundation.pager.HorizontalPager(
                pager,
                userScrollEnabled = !zoomed && !composer.drawing && !composer.placingAnchor,
                key = { pages[it].id },
                beyondViewportPageCount = 1,
                pageSpacing = 12.dp,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val p = pages[page]
                if (p.id == file.id) stage()
                else ImageStage(p, null, {}, Modifier.fillMaxSize(), view)
            }
        }

        // Image tools (mockup): zoom level, tap to go back to Fit.
        if (file.kind == MediaKind.Image && !composer.drawing && !composer.placingAnchor && chromeVisible && zoomed) {
            Row(
                Modifier.align(Alignment.BottomStart).padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZoomChip({ zoom }) { zoomReset++ }
            }
        }

        SeekFlash(seekFlash == -1, left = true, modifier = Modifier.align(Alignment.CenterStart).padding(32.dp))
        SeekFlash(seekFlash == 1, left = false, modifier = Modifier.align(Alignment.CenterEnd).padding(32.dp))

        if (state.preparing) {
            PreparingCard(file.kind == MediaKind.Audio, Modifier.align(Alignment.Center).padding(24.dp))
        } else if (!immersive && !controller.isPlaying && file.kind == MediaKind.Video && !composer.drawing && !controller.isBuffering && frameComments.isEmpty() && composer.shapes.isEmpty()) {
            Box(Modifier.align(Alignment.Center)) {
                GlassIconButton(Icons.Rounded.PlayArrow, stringResource(R.string.viewer_play), controller::toggle, size = 60.dp)
            }
        }

        // Images and PDFs have no transport bar, so they get their own fullscreen button.
        if (!immersive && (file.kind == MediaKind.Image || file.kind == MediaKind.Pdf) && !composer.drawing && !composer.placingAnchor) {
            GlassIconButton(Icons.Rounded.Fullscreen, stringResource(R.string.viewer_fullscreen), onToggleFullscreen, Modifier.align(Alignment.BottomEnd).padding(12.dp))
        }

        if (composer.placingAnchor) {
            Text(
                stringResource(R.string.viewer_tap_to_place_pin),
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 12.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xB3000000))
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            )
        }

        if (immersive) {
            AnimatedVisibility(
                chromeVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxWidth().height(110.dp).background(Palette.ScrimTop))
                    val commentable = file.kind == MediaKind.Video || file.kind == MediaKind.Image
                    Row(
                        Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.viewer_back), { if (fullscreen) onToggleFullscreen() else onBack() })
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    file.name, style = MaterialTheme.typography.titleMedium, color = Color.White,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                                )
                                if (state.versions.size > 1) {
                                    Spacer(Modifier.width(6.dp))
                                    VersionPill(state.versionNumber) { vm.setTab(PanelTab.Versions); if (!commentsOpen) onToggleComments() }
                                }
                            }
                            val subtitle = listOfNotNull(
                                file.statusField?.textValue,
                                controller.durationMs.takeIf { it > 0 && file.kind == MediaKind.Video }?.let { dev.finalish.core.util.Format.clock(it) },
                            ).joinToString(" · ")
                            if (subtitle.isNotEmpty()) {
                                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.75f))
                            }
                        }
                        if (commentable) {
                            Spacer(Modifier.width(6.dp))
                            CommentsToggle(state.comments.count { !it.isCompleted }, commentsOpen, onToggleComments)
                        }
                        // Share, rename, versions, status: the file menu stays reachable in fullscreen.
                        Spacer(Modifier.width(6.dp))
                        GlassIconButton(Icons.Rounded.MoreHoriz, stringResource(R.string.viewer_more), onMore)
                    }
                    if (file.kind == MediaKind.Video || file.kind == MediaKind.Audio) {
                        Column(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .background(Palette.ScrimBottom)
                                .navigationBarsPadding()
                                // With the bars hidden their insets are zero: keep clear of the
                                // gesture bar and a side camera cutout anyway.
                                .windowInsetsPadding(
                                    WindowInsets.mandatorySystemGestures.union(WindowInsets.displayCutout)
                                        .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
                                )
                                .padding(top = 40.dp),
                        ) {
                            if (file.kind == MediaKind.Video && !commentsOpen) {
                                LiveText(
                                    { res.getString(R.string.viewer_comment_at, timeLabel(controller.positionMs)) },
                                    style = MaterialTheme.typography.labelLarge,
                                    color = Palette.OnAccent,
                                    modifier = Modifier
                                        .align(Alignment.End)
                                        .padding(end = 16.dp, bottom = 4.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Palette.Accent)
                                        .pressable(onCommentHere, scaleTo = 0.94f)
                                        .padding(horizontal = 14.dp, vertical = 9.dp),
                                )
                            }
                            TransportBar(controller, state, vm, timeLabel, fps, onToggleFullscreen, overlay = true)
                        }
                        Box(Modifier.align(Alignment.Center)) {
                            GlassIconButton(
                                if (controller.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                stringResource(R.string.viewer_play_pause), controller::toggle, size = 68.dp,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Every anchored comment on an image gets a number, oldest first (the same one shows on its card). */
fun pinNumbers(comments: List<Comment>): Map<String, Int> =
    comments.filter { it.anchor != null && !it.pending }
        .sortedBy { dev.finalish.core.util.Format.parseInstant(it.createdAt) ?: java.time.Instant.EPOCH }
        .mapIndexed { i, c -> c.id to i + 1 }.toMap()

private fun imagePins(comments: List<Comment>): List<NumberedPin> {
    val numbers = pinNumbers(comments)
    return comments.mapNotNull { c -> val n = numbers[c.id]; val a = c.anchor; if (n != null && a != null) NumberedPin(c.id, n, Offset(a.x, a.y)) else null }
}

@Composable
private fun StageChip(label: String, on: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = if (on) Palette.OnAccent else Color.White,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) Palette.Accent else Color(0xB3000000))
            .pressable(onClick, scaleTo = 0.94f).padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

/** "4000×2667 · 3.4 MB", "12 pages", "48 kHz · 24 bit · stereo": what the file is, from Frame.io's metadata. */
internal fun formatLine(file: Asset, res: android.content.res.Resources): String? {
    fun num(name: String) = file.meta(name)?.doubleValue
    val size = dev.finalish.core.util.Format.bytes(file.media.fileSize).takeIf { file.media.fileSize > 0 }
    return when (file.kind) {
        MediaKind.Image -> listOfNotNull(
            num("Resolution - Width")?.let { w -> num("Resolution - Height")?.let { h -> "${w.toInt()}×${h.toInt()}" } },
            size,
        )
        MediaKind.Pdf -> listOfNotNull(num("Page Count")?.toInt()?.let { res.getQuantityString(R.plurals.viewer_page_count, it, it) }, size)
        MediaKind.Audio -> listOfNotNull(
            num("Audio Sample Rate")?.let { r -> if (r % 1000.0 == 0.0) "${(r / 1000).toInt()} kHz" else String.format(java.util.Locale.US, "%.1f kHz", r / 1000) },
            num("Audio Bit Depth")?.toInt()?.takeIf { it > 0 }?.let { "$it bit" },
            num("Audio Channels")?.toInt()?.let { when (it) { 1 -> res.getString(R.string.viewer_audio_mono); 2 -> res.getString(R.string.viewer_audio_stereo); else -> "$it ch" } },
        )
        MediaKind.Video -> emptyList()
        else -> listOfNotNull(size)
    }.joinToString(" · ").ifBlank { null }
}

@Composable
private fun CommentsToggle(open: Int, active: Boolean, onClick: () -> Unit) {
    val toggleLabel = if (active) stringResource(R.string.viewer_hide_comments) else stringResource(R.string.viewer_show_comments)
    Row(
        Modifier
            .height(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (active) Color.White.copy(alpha = 0.92f) else Color(0x800A0A0C))
            .pressable(onClick, scaleTo = 0.94f)
            .padding(horizontal = 12.dp)
            .semantics { contentDescription = toggleLabel },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val fg = if (active) Palette.Ink else Color.White
        Icon(Icons.Rounded.ChatBubbleOutline, null, tint = fg, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text("$open", style = MaterialTheme.typography.labelLarge, color = fg)
    }
}

/** Shown while Frame.io transcodes a fresh upload; playback starts by itself when ready. */
@Composable
private fun PreparingCard(audio: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Palette.Surface2.copy(alpha = 0.92f))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        androidx.compose.material3.CircularProgressIndicator(Modifier.size(22.dp), color = Palette.Amber, strokeWidth = 2.dp)
        Column {
            Text(
                if (audio) stringResource(R.string.viewer_preparing_audio) else stringResource(R.string.viewer_preparing_video),
                style = MaterialTheme.typography.titleSmall,
                color = Palette.Text,
            )
            Text(stringResource(R.string.viewer_preparing_body), style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
        }
    }
}

/** "250%" while zoomed; tap to go back to Fit. Reads the zoom itself so only the chip follows a pinch. */
@Composable
private fun ZoomChip(zoom: () -> Float, onReset: () -> Unit) {
    val percent by remember { derivedStateOf { (zoom() * 100).roundToInt() } }
    StageChip(stringResource(R.string.viewer_zoom_chip, percent), false, onReset)
}
