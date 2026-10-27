package dev.finalish.ui.browser

import dev.finalish.ui.navigation.sharedMedia
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import dev.finalish.R
import dev.finalish.core.model.Asset
import dev.finalish.core.model.MediaKind
import dev.finalish.core.model.ScrubSheetMeta
import dev.finalish.core.util.Format
import dev.finalish.ui.components.KindPlaceholder
import dev.finalish.ui.components.RatingStars
import dev.finalish.ui.components.RemoteImage
import dev.finalish.ui.components.StatusPill
import dev.finalish.ui.components.icon
import dev.finalish.ui.components.pressable
import dev.finalish.ui.components.shimmer
import dev.finalish.ui.components.stableImageKey
import dev.finalish.ui.theme.Motion
import dev.finalish.ui.theme.Palette

private fun Asset.durationLabel(): String? =
    media.metadata.firstOrNull { it.name.equals("Duration", true) }?.let { f ->
        f.doubleValue?.let { Format.clock((it * 1000).toLong()) } ?: f.textValue
    }

@Composable
private fun Asset.subtitle(): String = when {
    isFolder -> Format.relative(updatedAt)
    else -> listOfNotNull(
        commentCount?.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.browser_comment_count, it, it) },
        Format.bytes(media.fileSize).takeIf { media.fileSize > 0 },
        Format.relative(updatedAt).ifBlank { null },
    ).joinToString(" · ")
}

@Composable
fun AssetGridCard(
    asset: Asset,
    selected: Boolean,
    selecting: Boolean,
    offline: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    aspect: Float = 16f / 10f,
    fit: Boolean = false,
    titleLines: Int = 1,
    showInfo: Boolean = true,
    coverUrl: String? = null,
    compact: Boolean = false,
    onAction: ((FileAction) -> Unit)? = null,
    online: Boolean = true,
    /** Fields picked in Sort & view, e.g. "Status: Approved · Duration: 1:23". */
    fieldsLine: String? = null,
    /** A stack's number of versions, once known ("V3"). */
    versionCount: Int? = null,
) {
    // Read in layout, not composition: the selection spring re-lays the thumbnail out each frame
    // without recomposing the card.
    val inset = animateDpAsState(if (selected) 6.dp else 0.dp, Motion.snappy(), label = "inset")
    val away = !online && !offline && !asset.isFolder
    Column(modifier.pressable(onClick, onLongClick).alpha(if (away) DIMMED else 1f)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(aspect)
                .clip(RoundedCornerShape(14.dp))
                .background(Palette.Surface2)
                .then(if (selected) Modifier.border(2.dp, Palette.Accent, RoundedCornerShape(14.dp)) else Modifier),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .layout { measurable, constraints ->
                        val p = inset.value.coerceAtLeast(0.dp).roundToPx() // the spring overshoots below zero
                        val placeable = measurable.measure(constraints.offset(-2 * p, -2 * p))
                        layout(placeable.width + 2 * p, placeable.height + 2 * p) { placeable.place(p, p) }
                    }
                    .clip(RoundedCornerShape(if (selected) 9.dp else 14.dp)),
            ) {
                AssetThumbnail(asset, Modifier.fillMaxSize().then(if (!asset.isFolder) Modifier.sharedMedia(asset.id) else Modifier), scrubbable = !selecting, fit = fit, coverUrl = coverUrl)
            }
            if (showInfo) ThumbOverlays(asset, offline, compact, versionCount)
            SelectionBadge(selecting, selected)
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Top) {
            Text(
                asset.name, style = MaterialTheme.typography.titleSmall, maxLines = titleLines,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            if (onAction != null && !selecting) FileMenuButton(asset, onAction, compact = true)
        }
        if (showInfo) Row(verticalAlignment = Alignment.CenterVertically) {
            val (line, tint) = offlineLine(asset, online, offline)
            Text(
                line,
                style = MaterialTheme.typography.bodySmall,
                color = tint,
                maxLines = 1,
                modifier = Modifier.weight(1f, fill = false),
            )
            asset.ratingField?.intValue?.takeIf { it > 0 }?.let {
                Spacer(Modifier.width(6.dp))
                RatingStars(it, size = 11.dp)
            }
        }
        fieldsLine?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
fun AssetListRow(
    asset: Asset,
    selected: Boolean,
    selecting: Boolean,
    offline: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    onAction: ((FileAction) -> Unit)? = null,
    online: Boolean = true,
    versionCount: Int? = null,
    fieldsLine: String? = null,
) {
    val away = !online && !offline && !asset.isFolder
    Row(
        modifier
            .fillMaxWidth()
            .alpha(if (away) DIMMED else 1f)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) Palette.AccentContainer else Color.Transparent)
            .pressable(onClick, onLongClick, scaleTo = 0.98f)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(92.dp)
                .aspectRatio(16f / 10f)
                .clip(RoundedCornerShape(10.dp))
                .background(Palette.Surface2),
        ) {
            AssetThumbnail(asset, Modifier.fillMaxSize(), scrubbable = false)
            if (asset.isVersionStack) VersionBadge(Modifier.align(Alignment.TopStart).padding(4.dp), versionCount)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(asset.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusPill(asset.statusField?.textValue, compact = true)
                val (line, tint) = offlineLine(asset, online, offline)
                Text(line, style = MaterialTheme.typography.bodySmall, color = tint, maxLines = 1)
            }
            fieldsLine?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        if (offline) {
            Icon(Icons.Rounded.DownloadDone, stringResource(R.string.browser_available_offline), tint = Palette.Green, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        if (onAction != null && !selecting) FileMenuButton(asset, onAction)
        AnimatedVisibility(selecting, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
            Icon(
                if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                null,
                tint = if (selected) Palette.Accent else Palette.TextTertiary,
                modifier = Modifier.padding(end = 6.dp),
            )
        }
    }
}

/** Cards of files that can't open without a connection fade back. */
private const val DIMMED = 0.45f

/** The info line under a file; offline it says which files can still open. */
@Composable
private fun offlineLine(asset: Asset, online: Boolean, offline: Boolean): Pair<String, Color> = when {
    online || asset.isFolder -> asset.subtitle() to Palette.TextTertiary
    offline -> stringResource(R.string.browser_available_offline) to Palette.Green
    else -> stringResource(R.string.browser_needs_network) to Palette.TextTertiary
}

/** What the "⋯" on a file or folder can do; the folder screen runs the same actions as the selection bar. */
enum class FileAction { Share, SaveToDevice, Download, Members, Rename, Move, Copy, Delete }

/** "⋯" on a card: a small menu that opens from where you tapped (mockup's context menu). */
@Composable
fun FileMenuButton(asset: Asset, onAction: (FileAction) -> Unit, compact: Boolean = false) {
    var open by remember { mutableStateOf(false) }
    // Compact: under a card, the 32 dp target overflows a text-high row instead of pushing the info line down.
    Box(if (compact) Modifier.height(20.dp).wrapContentHeight(unbounded = true) else Modifier) {
        Icon(
            Icons.Rounded.MoreHoriz, stringResource(R.string.browser_more_actions_for, asset.name), tint = Palette.TextTertiary,
            modifier = Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).pressable({ open = true }, scaleTo = 0.9f).padding(6.dp),
        )
        androidx.compose.material3.DropdownMenu(
            open, onDismissRequest = { open = false },
            containerColor = Palette.Surface3,
            shape = dev.finalish.ui.theme.MenuShape,
        ) {
            @Composable
            fun item(action: FileAction, label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, danger: Boolean = false) =
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(label, color = if (danger) Palette.Red else Palette.Text) },
                    leadingIcon = { Icon(icon, null, tint = if (danger) Palette.Red else Palette.TextSecondary) },
                    onClick = { open = false; onAction(action) },
                )
            item(FileAction.Share, stringResource(R.string.browser_action_share_link), Icons.Rounded.Link)
            item(FileAction.SaveToDevice, stringResource(R.string.viewer_action_download), Icons.Rounded.FileDownload)
            if (!asset.isFolder) item(FileAction.Download, stringResource(R.string.browser_action_save_offline), Icons.Rounded.DownloadForOffline)
            // Who can open this folder (restricted folders have their own members).
            if (asset.isFolder) item(FileAction.Members, stringResource(R.string.browser_action_members), Icons.Rounded.Group)
            item(FileAction.Rename, stringResource(R.string.browser_action_rename), Icons.Rounded.Edit)
            item(FileAction.Move, stringResource(R.string.browser_action_move_to), Icons.AutoMirrored.Rounded.DriveFileMove)
            item(FileAction.Copy, stringResource(R.string.browser_action_copy_to), Icons.Rounded.ContentCopy)
            androidx.compose.material3.HorizontalDivider(color = Palette.Hairline, modifier = Modifier.padding(vertical = 4.dp, horizontal = 8.dp))
            item(FileAction.Delete, stringResource(R.string.browser_action_delete), Icons.Rounded.DeleteOutline, danger = true)
        }
    }
}

@Composable
private fun BoxScope.ThumbOverlays(asset: Asset, offline: Boolean, compact: Boolean = false, versionCount: Int? = null) {
    Row(
        Modifier.align(Alignment.TopStart).padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (asset.isVersionStack) VersionBadge(count = versionCount)
        if (offline) {
            Box(Modifier.size(22.dp).clip(CircleShape).background(Color(0x99000000)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.DownloadDone, null, tint = Palette.Green, modifier = Modifier.size(14.dp))
            }
        }
    }
    asset.statusField?.textValue?.let {
        if (compact) {
            // Small cards: a coloured dot keeps the thumbnail readable.
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp)
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(Color(0xB3000000))
                    .padding(2.dp)
                    .clip(CircleShape)
                    .background(dev.finalish.ui.theme.statusColor(it)),
            )
        } else {
            Box(Modifier.align(Alignment.BottomStart).padding(8.dp).clip(RoundedCornerShape(50)).background(Color(0xB3000000))) {
                StatusPill(it, compact = true)
            }
        }
    }
    asset.durationLabel()?.let {
        Text(
            it,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(8.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xB3000000))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
    if (!asset.isFolder && asset.media.status == "created") {
        Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
            Icon(Icons.Rounded.HourglassTop, stringResource(R.string.browser_processing), tint = Color.White, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun BoxScope.SelectionBadge(selecting: Boolean, selected: Boolean) {
    AnimatedVisibility(
        selecting,
        enter = scaleIn() + fadeIn(),
        exit = scaleOut() + fadeOut(),
        modifier = Modifier.align(Alignment.TopEnd).padding(10.dp),
    ) {
        Box(
            Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(if (selected) Palette.Accent else Color(0x66000000))
                .border(1.5.dp, Color.White, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Icon(Icons.Rounded.CheckCircle, null, tint = Color.White, modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
private fun VersionBadge(modifier: Modifier = Modifier, count: Int? = null) {
    Row(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Palette.Accent)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Layers, null, tint = Color.White, modifier = Modifier.size(11.dp))
        Spacer(Modifier.width(3.dp))
        // "V3": the head is the newest of [count] versions (mockup); "Versions" until counted.
        Text(count?.let { stringResource(R.string.browser_version_badge, it) } ?: stringResource(R.string.browser_versions), style = MaterialTheme.typography.labelSmall, color = Color.White)
    }
}

@Composable
fun AssetThumbnail(asset: Asset, modifier: Modifier, scrubbable: Boolean, fit: Boolean = false, coverUrl: String? = null) {
    if (asset.isFolder) {
        FolderTile(asset, modifier, coverUrl)
        return
    }
    val sheet = asset.media.mediaLinks?.scrubSheet
    val meta = sheet?.metadata
    var scrub by remember { mutableFloatStateOf(-1f) }
    // Composition only follows whether a scrub is on; the position itself is read while drawing.
    val scrubbing by remember { derivedStateOf { scrub >= 0f } }
    Box(
        modifier.then(
            if (scrubbable && asset.kind == MediaKind.Video && meta != null && meta.tileX > 0 && meta.tileY > 0 && meta.thumbWidth > 0 && meta.thumbHeight > 0 && sheet.streamUrl != null) {
                Modifier.pointerInput(asset.id) {
                    detectHorizontalDragGestures(
                        onDragStart = { scrub = (it.x / size.width).coerceIn(0f, 1f) },
                        onDragEnd = { scrub = -1f },
                        onDragCancel = { scrub = -1f },
                    ) { change, _ ->
                        change.consume()
                        scrub = (change.position.x / size.width).coerceIn(0f, 1f)
                    }
                }
            } else Modifier,
        ),
    ) {
        val thumb = asset.thumbnailUrl
        if (thumb != null) {
            dev.finalish.ui.components.RemoteImageChain(
                asset.thumbnailCandidates, Modifier.fillMaxSize(),
                contentScale = if (fit) androidx.compose.ui.layout.ContentScale.Fit else androidx.compose.ui.layout.ContentScale.Crop,
                fallback = { KindPlaceholder(asset.kind, Modifier.fillMaxSize()) },
            )
        } else if (asset.kind == MediaKind.Audio) {
            // Audio without a picture: just the waveform (drawn below), no file icon behind it.
            Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(Palette.Surface3, Palette.Surface2))))
        } else {
            KindPlaceholder(asset.kind, Modifier.fillMaxSize())
        }
        if (scrubbing && meta != null && sheet.streamUrl != null) {
            ScrubFrame(sheet.streamUrl!!, meta, { scrub }, Modifier.fillMaxSize())
            val accent = Palette.Accent
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(3.dp)
                    .drawBehind { drawRect(accent, size = size.copy(width = size.width * scrub.coerceIn(0f, 1f))) },
            )
        }
        if (asset.kind == MediaKind.Audio && thumb == null) {
            AudioBars(Modifier.align(Alignment.Center).fillMaxWidth(0.6f).fillMaxHeight(0.4f))
        }
    }
}

@Composable
private fun FolderTile(asset: Asset, modifier: Modifier, coverUrl: String? = null) {
    Box(
        modifier.background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(Palette.Surface3, Palette.Surface2))),
        contentAlignment = Alignment.Center,
    ) {
        if (coverUrl != null) {
            // Cover image with a folder tab badge, like the official cards.
            RemoteImage(coverUrl, Modifier.fillMaxSize(), fallback = {})
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(Color(0xB3000000))
                    .padding(5.dp),
            ) {
                Icon(Icons.Rounded.Folder, null, tint = Color.White, modifier = Modifier.size(14.dp))
            }
        } else {
            Icon(Icons.Rounded.Folder, null, tint = Palette.AccentBright.copy(alpha = 0.9f), modifier = Modifier.size(40.dp))
        }
        if (asset.restricted == true) {
            Icon(Icons.Rounded.Lock, stringResource(R.string.browser_restricted), tint = Palette.TextSecondary, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(14.dp))
        }
    }
}

@Composable
private fun AudioBars(modifier: Modifier) {
    Canvas(modifier) {
        val n = 24
        val w = size.width / (n * 1.6f)
        for (i in 0 until n) {
            val h = size.height * (0.25f + 0.75f * ((kotlin.math.sin(i * 1.7) + 1) / 2).toFloat())
            drawRoundRect(
                Palette.AccentBright.copy(alpha = 0.7f),
                topLeft = androidx.compose.ui.geometry.Offset(i * w * 1.6f, (size.height - h) / 2),
                size = androidx.compose.ui.geometry.Size(w, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(w / 2),
            )
        }
    }
}

/** Renders one tile of the scrub-sheet sprite for the 0..1 [position], read while drawing. */
@Composable
fun ScrubFrame(url: String, meta: ScrubSheetMeta, position: () -> Float, modifier: Modifier) {
    val context = LocalContext.current
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) {
        val key = stableImageKey(url)
        val req = ImageRequest.Builder(context).data(url).memoryCacheKey(key).diskCacheKey(key).allowHardware(false).build()
        val result = SingletonImageLoader.get(context).execute(req)
        if (result is SuccessResult) bitmap = result.image.toBitmap().asImageBitmap()
    }
    val bmp = bitmap
    if (bmp == null) {
        Box(modifier.shimmer(RoundedCornerShape(0.dp)))
        return
    }
    val count = meta.tileX * meta.tileY
    Canvas(modifier) {
        val index = (position() * (count - 1)).toInt().coerceIn(0, count - 1)
        val col = index % meta.tileX
        val row = index / meta.tileX
        drawImage(
            bmp,
            srcOffset = IntOffset(col * (meta.thumbWidth + meta.padding), row * (meta.thumbHeight + meta.padding)),
            srcSize = IntSize(meta.thumbWidth, meta.thumbHeight),
            dstSize = IntSize(size.width.toInt(), size.height.toInt()),
        )
    }
}

@Composable
fun FolderSkeletonCard() {
    Column {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f).shimmer(RoundedCornerShape(14.dp)))
        Spacer(Modifier.height(9.dp))
        Box(Modifier.fillMaxWidth(0.75f).height(12.dp).shimmer())
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth(0.45f).height(10.dp).shimmer())
    }
}
