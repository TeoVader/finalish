package dev.finalish.ui.viewer

import androidx.compose.animation.AnimatedContent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CompareArrows
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CropFree
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PlayCircleOutline
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import dev.finalish.R
import dev.finalish.core.data.PlaybackQuality
import dev.finalish.core.model.Asset
import dev.finalish.ui.components.pressable
import dev.finalish.ui.theme.Palette

// ---- View options (mirrors the Frame.io player settings menu) ------------------

enum class Guide(val label: String, val ratio: Float?) {
    Off("Off", null),
    R239("2.39:1", 2.39f),
    R185("1.85:1", 1.85f),
    R16x9("16:9", 16f / 9f),
    R4x3("4:3", 4f / 3f),
    R1x1("1:1", 1f),
    R4x5("4:5", 4f / 5f),
    R9x16("9:16", 9f / 16f),
}

enum class ZoomMode(@androidx.annotation.StringRes val labelRes: Int) {
    Fit(R.string.viewer_zoom_fit),
    Fill(R.string.viewer_zoom_fill),
    Actual(R.string.viewer_zoom_actual),
}

/** [Guide.label], with the one word ("Off") translated; the ratios read the same everywhere. */
@Composable
internal fun Guide.displayLabel(): String = if (this == Guide.Off) stringResource(R.string.viewer_guide_off) else label

/** [Rendition.label], with "Original" translated. */
@Composable
internal fun Rendition.displayLabel(): String =
    if (source == RenditionSource.Original || height == null) stringResource(R.string.viewer_quality_original) else label

@Immutable
data class ViewOptions(
    val guide: Guide = Guide.Off,
    val titleSafe: Boolean = false,
    val actionSafe: Boolean = false,
    /** Rule-of-thirds grid. */
    val thirds: Boolean = false,
    val zoom: ZoomMode = ZoomMode.Fit,
    val showAnnotations: Boolean = true,
    val showMarkers: Boolean = true,
) {
    val anyGuide: Boolean get() = guide != Guide.Off || titleSafe || actionSafe || thirds
}

/** "4K" / "HD" / "SD" badge for a decoded frame height. */
fun resolutionBadge(height: Int): String? = when {
    height >= 2160 -> "4K"
    height >= 1440 -> "2K"
    height >= 720 -> "HD"
    height > 0 -> "SD"
    else -> null
}

fun resolutionLabel(height: Int): String? = height.takeIf { it > 0 }?.let { "${it}p" }

/** Quality menu rows, like the official apps: Original, then each resolution from the highest (Auto is added by the menus). */
fun qualityLadder(renditions: List<Rendition>): List<Rendition> =
    renditions.filter { it.source == RenditionSource.Original } +
        renditions.filter { it.source != RenditionSource.Original && it.height != null }
            .sortedByDescending { it.height }
            .distinctBy { it.height }

private enum class Page { Main, Legacy, Quality, Guides, Zoom, View }

/** Live player controls the Tools sheet drives directly. */
@Immutable
data class ExtraToggles(
    val loop: Boolean,
    val muted: Boolean,
    val onLoop: (Boolean) -> Unit,
    val onMute: (Boolean) -> Unit,
    val speed: Float = 1f,
    val onSpeed: (Float) -> Unit = {},
    /** Opens the versions list in compare mode; null when there's only one version. */
    val onCompare: (() -> Unit)? = null,
    /** Shrinks the player into a floating window; null where PiP isn't available. */
    val onPip: (() -> Unit)? = null,
)

/**
 * Player settings, structured like the official web player's gear menu:
 * Quality ›, Guides ›, Zoom ›, View on Asset ›, then Download Still.
 */
@Composable
fun PlaybackOptionsSheet(
    file: Asset,
    quality: PlaybackQuality,
    active: Rendition?,
    renditions: List<Rendition>,
    playingHeight: Int,
    offline: Boolean,
    options: ViewOptions,
    onQuality: (PlaybackQuality) -> Unit,
    onOptions: (ViewOptions) -> Unit,
    onDownloadStill: () -> Unit,
    onDismiss: () -> Unit,
    extraToggles: ExtraToggles? = null,
) {
    var page by remember { mutableStateOf(Page.Main) }
    // One row per distinct resolution, highest first, then Auto.
    val ladder = qualityLadder(renditions)
    val playing = playingHeight.takeIf { it > 0 }?.let(PlaybackQuality::labelFor)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Palette.Surface2,
        dragHandle = null,
    ) {
        // The sheet never shrinks while paging: a shorter sub-page would make it jump down
        // mid-transition. It only grows (once) if a sub-page is taller than the menu.
        val density = androidx.compose.ui.platform.LocalDensity.current
        var minHeight by remember { mutableStateOf(0.dp) }
        androidx.activity.compose.BackHandler(page != Page.Main) { page = Page.Main }
        AnimatedContent(
            page,
            transitionSpec = {
                dev.finalish.ui.theme.sheetPage(forward = targetState != Page.Main) using
                    androidx.compose.animation.SizeTransform(clip = false)
            },
            contentAlignment = Alignment.TopStart,
            modifier = Modifier
                .heightIn(min = minHeight)
                .onSizeChanged { with(density) { it.height.toDp() }.let { h -> if (h > minHeight) minHeight = h } },
            label = "options",
        ) { p ->
            Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()).navigationBarsPadding().padding(vertical = 10.dp)) {
                when (p) {
                    Page.Main -> ToolsMain(
                        file = file,
                        video = file.kind == dev.finalish.core.model.MediaKind.Video,
                        quality = if (offline) null else qualityValue(quality, playing),
                        options = options,
                        controls = extraToggles,
                        onOptions = onOptions,
                        onPage = { page = it },
                        onStill = { onDownloadStill(); onDismiss() },
                    )
                    Page.Legacy -> if (file.kind != dev.finalish.core.model.MediaKind.Video) {
                        ToggleRow(stringResource(R.string.viewer_opt_comment_markers), options.showMarkers, Icons.Rounded.Visibility) { onOptions(options.copy(showMarkers = it)) }
                    } else {
                        if (!offline) {
                            MenuRow(
                                Icons.Rounded.PlayCircleOutline, stringResource(R.string.viewer_opt_quality),
                                value = qualityValue(quality, playing),
                                chevron = true,
                            ) { page = Page.Quality }
                            HorizontalDivider(color = Palette.Hairline, modifier = Modifier.padding(vertical = 4.dp))
                        }
                        MenuRow(Icons.Rounded.CropFree, stringResource(R.string.viewer_opt_guides), value = guideValue(options), chevron = true) { page = Page.Guides }
                        MenuRow(Icons.Rounded.ZoomIn, stringResource(R.string.viewer_opt_zoom), value = stringResource(options.zoom.labelRes), chevron = true) { page = Page.Zoom }
                        MenuRow(Icons.Rounded.Visibility, stringResource(R.string.viewer_opt_view_on_asset), chevron = true) { page = Page.View }
                        HorizontalDivider(color = Palette.Hairline, modifier = Modifier.padding(vertical = 4.dp))
                        MenuRow(Icons.Rounded.Image, stringResource(R.string.viewer_opt_download_still)) { onDownloadStill(); onDismiss() }
                        extraToggles?.let { t ->
                            HorizontalDivider(color = Palette.Hairline, modifier = Modifier.padding(vertical = 4.dp))
                            ToggleRow(stringResource(R.string.viewer_opt_loop), t.loop, Icons.Rounded.Repeat, t.onLoop)
                            ToggleRow(stringResource(R.string.viewer_opt_mute), t.muted, Icons.AutoMirrored.Rounded.VolumeOff, t.onMute)
                        }
                    }
                    Page.Quality -> {
                        SubHeader(stringResource(R.string.viewer_opt_quality)) { page = Page.Main }
                        CheckRow(
                            label = stringResource(R.string.viewer_quality_auto),
                            detail = playing?.let { stringResource(R.string.viewer_quality_currently, it) },
                            checked = quality == PlaybackQuality.Auto,
                        ) { onQuality(PlaybackQuality.Auto); page = Page.Main }
                        ladder.forEach { r ->
                            CheckRow(
                                label = r.displayLabel(),
                                detail = if (r.source == RenditionSource.Original) stringResource(R.string.viewer_quality_source_file) else null,
                                checked = quality != PlaybackQuality.Auto && r == active,
                            ) { onQuality(r.quality); page = Page.Main }
                        }
                    }
                    Page.Guides -> {
                        SubHeader(stringResource(R.string.viewer_opt_guides)) { page = Page.Main }
                        Guide.entries.forEach { g ->
                            CheckRow(g.displayLabel(), checked = options.guide == g) { onOptions(options.copy(guide = g)) }
                        }
                        HorizontalDivider(color = Palette.Hairline, modifier = Modifier.padding(vertical = 4.dp))
                        ToggleRow(stringResource(R.string.viewer_opt_title_safe_80), options.titleSafe) { onOptions(options.copy(titleSafe = it)) }
                        ToggleRow(stringResource(R.string.viewer_opt_action_safe_90), options.actionSafe) { onOptions(options.copy(actionSafe = it)) }
                        ToggleRow(stringResource(R.string.viewer_opt_rule_of_thirds), options.thirds) { onOptions(options.copy(thirds = it)) }
                    }
                    Page.Zoom -> {
                        SubHeader(stringResource(R.string.viewer_opt_zoom)) { page = Page.Main }
                        ZoomMode.entries.forEach { z ->
                            CheckRow(stringResource(z.labelRes), checked = options.zoom == z) { onOptions(options.copy(zoom = z)); page = Page.Main }
                        }
                    }
                    Page.View -> {
                        SubHeader(stringResource(R.string.viewer_opt_view_on_asset)) { page = Page.Main }
                        ToggleRow(stringResource(R.string.viewer_opt_annotations), options.showAnnotations) { onOptions(options.copy(showAnnotations = it)) }
                        ToggleRow(stringResource(R.string.viewer_opt_comment_markers), options.showMarkers) { onOptions(options.copy(showMarkers = it)) }
                    }
                }
            }
        }
    }
}

/**
 * The Tools sheet (mockup "Strumenti"): speed, a grid of tool tiles, then the display switches.
 * Sub-pages (quality, guides, zoom) slide in from the right as before.
 */
@Composable
private fun ToolsMain(
    file: Asset,
    video: Boolean,
    quality: String?,
    options: ViewOptions,
    controls: ExtraToggles?,
    onOptions: (ViewOptions) -> Unit,
    onPage: (Page) -> Unit,
    onStill: () -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(14.dp)) {
        Box(Modifier.align(Alignment.CenterHorizontally).size(36.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(Palette.Surface4))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(stringResource(R.string.viewer_tools), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Text(
                file.name, style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.width(180.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
            )
        }
        if (controls != null) {
            SectionLabel(stringResource(R.string.viewer_opt_speed))
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.Surface3).padding(4.dp)) {
                listOf(0.25f, 0.5f, 1f, 1.5f, 2f).forEach { sp ->
                    val on = controls.speed == sp
                    Box(
                        Modifier.weight(1f).height(34.dp).clip(RoundedCornerShape(10.dp))
                            .background(if (on) Palette.Text else Color.Transparent)
                            .pressable({ controls.onSpeed(sp) }, scaleTo = 0.94f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            (if (sp % 1f == 0f) "${sp.toInt()}" else "$sp") + "\u00d7",
                            style = dev.finalish.ui.theme.TimecodeStyle.copy(fontWeight = FontWeight.SemiBold),
                            color = if (on) Palette.Ink else Palette.TextSecondary,
                        )
                    }
                }
            }
        }
        val tiles = buildList {
            if (video) {
                add(Tile(stringResource(R.string.viewer_opt_guides), Icons.Rounded.CropFree, options.anyGuide) { onPage(Page.Guides) })
                add(Tile(stringResource(R.string.viewer_opt_zoom), Icons.Rounded.ZoomIn, options.zoom != ZoomMode.Fit) { onPage(Page.Zoom) })
            }
            controls?.let { c ->
                add(Tile(stringResource(R.string.viewer_opt_loop), Icons.Rounded.Repeat, c.loop) { c.onLoop(!c.loop) })
                c.onCompare?.let { add(Tile(stringResource(R.string.viewer_compare), Icons.AutoMirrored.Rounded.CompareArrows, false, it)) }
            }
            if (video) add(Tile(stringResource(R.string.viewer_action_save_frame), Icons.Rounded.Image, false, onStill))
            controls?.onPip?.let { if (video) add(Tile(stringResource(R.string.viewer_opt_pip), Icons.Rounded.PictureInPictureAlt, false, it)) }
        }
        tiles.chunked(3).forEach { rowTiles ->
            Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                rowTiles.forEach { t -> ToolTile(t, Modifier.weight(1f)) }
                repeat(3 - rowTiles.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        if (video) {
            // Aspect-ratio masks, one at a time: Off then cinema to social, an even 4 × 2 grid.
            SectionLabel(stringResource(R.string.viewer_opt_aspect_ratio))
            Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                Guide.entries.chunked(4).forEach { row ->
                    Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                        row.forEach { g -> GuideChip(g.displayLabel(), options.guide == g, Modifier.weight(1f)) { onOptions(options.copy(guide = g)) } }
                    }
                }
            }
            // Overlay guides, each toggled on its own, filling the row.
            SectionLabel(stringResource(R.string.viewer_opt_guides))
            Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                GuideChip(stringResource(R.string.viewer_opt_title_safe), options.titleSafe, Modifier.weight(1f)) { onOptions(options.copy(titleSafe = !options.titleSafe)) }
                GuideChip(stringResource(R.string.viewer_opt_action_safe), options.actionSafe, Modifier.weight(1f)) { onOptions(options.copy(actionSafe = !options.actionSafe)) }
                GuideChip(stringResource(R.string.viewer_opt_thirds), options.thirds, Modifier.weight(1f)) { onOptions(options.copy(thirds = !options.thirds)) }
            }
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Palette.Surface3)) {
            if (quality != null && video) {
                MenuRow(Icons.Rounded.PlayCircleOutline, stringResource(R.string.viewer_opt_quality), value = quality, chevron = true) { onPage(Page.Quality) }
            }
            if (video) ToggleRow(stringResource(R.string.viewer_opt_drawings_on_frames), options.showAnnotations) { onOptions(options.copy(showAnnotations = it)) }
            ToggleRow(stringResource(R.string.viewer_opt_comment_pins), options.showMarkers) { onOptions(options.copy(showMarkers = it)) }
            controls?.let { c -> ToggleRow(stringResource(R.string.viewer_opt_mute_audio), c.muted) { c.onMute(it) } }
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun GuideChip(label: String, on: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = if (on) Palette.OnAccent else Palette.TextSecondary,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        maxLines = 1,
        modifier = modifier.clip(RoundedCornerShape(50)).background(if (on) Palette.Accent else Palette.Surface3)
            .pressable(onClick, scaleTo = 0.94f).padding(horizontal = 8.dp, vertical = 9.dp),
    )
}

private class Tile(val label: String, val icon: ImageVector, val active: Boolean, val onClick: () -> Unit)

@Composable
private fun ToolTile(t: Tile, modifier: Modifier) {
    Column(
        modifier
            .height(84.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (t.active) Palette.Accent else Palette.Surface3)
            .pressable(t.onClick, scaleTo = 0.95f)
            .padding(12.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
    ) {
        Icon(t.icon, null, tint = if (t.active) Palette.OnAccent else Palette.TextSecondary, modifier = Modifier.size(20.dp))
        Text(t.label, style = MaterialTheme.typography.labelLarge, color = if (t.active) Palette.OnAccent else Palette.Text, maxLines = 1)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary)
}

@Composable
private fun qualityValue(q: PlaybackQuality, playing: String?): String =
    if (q == PlaybackQuality.Auto) listOfNotNull(stringResource(R.string.viewer_quality_auto), playing).joinToString(" · ") else playing ?: q.label

@Composable
private fun guideValue(o: ViewOptions): String = when {
    o.guide == Guide.Off && (o.titleSafe || o.actionSafe) -> stringResource(R.string.viewer_guide_safe)
    o.guide == Guide.Off && o.thirds -> stringResource(R.string.viewer_opt_thirds)
    else -> o.guide.displayLabel()
}

@Composable
private fun MenuRow(
    icon: ImageVector,
    label: String,
    value: String? = null,
    badge: String? = null,
    chevron: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(onClick, scaleTo = 0.985f)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Palette.TextSecondary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        value?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Palette.TextTertiary) }
        badge?.let { ResolutionBadge(it, Modifier.padding(start = 6.dp)) }
        if (chevron) Icon(Icons.Rounded.ChevronRight, null, tint = Palette.TextTertiary, modifier = Modifier.padding(start = 4.dp))
    }
}

@Composable
private fun SubHeader(title: String, onBack: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(onBack, scaleTo = 0.985f)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.viewer_back), modifier = Modifier.padding(8.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, badge: String? = null, detail: String? = null, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(onClick, scaleTo = 0.985f)
            .padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.bodyLarge)
                badge?.let { ResolutionBadge(it, Modifier.padding(start = 6.dp)) }
            }
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary) }
        }
        if (checked) Icon(Icons.Rounded.Check, null, tint = Palette.AccentBright)
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, icon: ImageVector? = null, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = Palette.TextSecondary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(16.dp))
        }
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Palette.Accent, checkedThumbColor = Palette.OnAccent),
        )
    }
}

/** Small superscript-style pill, like the "4K" badge on the official gear icon. */
@Composable
fun ResolutionBadge(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        color = Color.White,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Palette.Accent)
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/** Badge anchored to the top-right of an icon. */
@Composable
fun BoxScope.CornerBadge(text: String?) {
    if (text == null) return
    ResolutionBadge(text, Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-4).dp))
}

// ---- Guides overlay ---------------------------------------------------------------

/** Aspect-ratio masks and safe areas drawn over the media frame. */
@Composable
fun GuidesOverlay(options: ViewOptions, modifier: Modifier = Modifier) {
    if (!options.anyGuide) return
    Canvas(modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val line = Stroke(width = 1.dp.toPx())
        options.guide.ratio?.let { r ->
            val frameRatio = w / h
            val rect = if (r > frameRatio) {
                val gh = w / r
                Rect(Offset(0f, (h - gh) / 2), Size(w, gh))
            } else {
                val gw = h * r
                Rect(Offset((w - gw) / 2, 0f), Size(gw, h))
            }
            val hole = Path().apply { addRect(rect) }
            clipPath(hole, ClipOp.Difference) { drawRect(Color.Black.copy(alpha = 0.6f)) }
            drawRect(Color.White.copy(alpha = 0.7f), rect.topLeft, rect.size, style = line)
        }
        if (options.thirds) {
            for (i in 1..2) {
                drawLine(Color.White.copy(alpha = 0.4f), Offset(w * i / 3, 0f), Offset(w * i / 3, h), line.width)
                drawLine(Color.White.copy(alpha = 0.4f), Offset(0f, h * i / 3), Offset(w, h * i / 3), line.width)
            }
        }
        if (options.titleSafe || options.actionSafe) {
            fun inset(f: Float) = Rect(Offset(w * (1 - f) / 2, h * (1 - f) / 2), Size(w * f, h * f))
            val action = inset(0.9f)
            val title = inset(0.8f)
            if (options.actionSafe) drawRect(Color.White.copy(alpha = 0.55f), action.topLeft, action.size, style = line)
            if (options.titleSafe) drawRect(Palette.Amber.copy(alpha = 0.7f), title.topLeft, title.size, style = line)
            // Center cross
            val c = Offset(w / 2, h / 2)
            val k = 10.dp.toPx()
            drawLine(Color.White.copy(alpha = 0.6f), c.copy(x = c.x - k), c.copy(x = c.x + k), line.width)
            drawLine(Color.White.copy(alpha = 0.6f), c.copy(y = c.y - k), c.copy(y = c.y + k), line.width)
        }
    }
}
