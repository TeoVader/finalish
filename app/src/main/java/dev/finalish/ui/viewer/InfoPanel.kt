package dev.finalish.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.rounded.Search
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.automirrored.rounded.CompareArrows
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.finalish.core.model.Asset
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.finalish.R
import dev.finalish.core.model.MetadataField
import dev.finalish.core.util.Format
import dev.finalish.ui.browser.TextPromptDialog
import dev.finalish.ui.components.Avatar
import dev.finalish.ui.components.RatingStars
import dev.finalish.ui.components.RemoteImage
import dev.finalish.ui.components.pressable
import dev.finalish.ui.shares.copyToClipboard
import dev.finalish.ui.theme.Palette
import dev.finalish.ui.theme.statusColor

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InfoPanel(
    file: Asset,
    offline: Boolean,
    vm: ViewerViewModel,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var editing by remember { mutableStateOf<MetadataField?>(null) }
    var multiEditing by remember { mutableStateOf<MetadataField?>(null) }
    var userEditing by remember { mutableStateOf<MetadataField?>(null) }
    var dateEditing by remember { mutableStateOf<MetadataField?>(null) }
    val people by vm.people.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(file.projectId) { vm.loadPeople() }
    val peopleById = remember(people) { people.associateBy { it.id.orEmpty() } }
    val status = file.statusField
    val rating = file.ratingField
    val seenBy = file.metadata.firstOrNull { it.name.equals("Seen By", ignoreCase = true) }
    // Hide empty read-only system fields; editable fields stay visible so they can be filled in.
    var fieldFilter by remember { mutableStateOf(FieldFilter.All) }
    var fieldQuery by remember { mutableStateOf("") }
    val others = file.metadata
        .filter { it !== status && it !== rating && it !== seenBy }
        .filter { it.mutable || !it.isEmpty() }
        .filter { fieldFilter.keeps(it) && (fieldQuery.isBlank() || it.name.contains(fieldQuery.trim(), ignoreCase = true)) }
        .sortedBy { if (it.mutable) 0 else 1 }

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QuickAction(
                    if (offline) Icons.Rounded.DownloadDone else Icons.Rounded.DownloadForOffline,
                    if (offline) stringResource(R.string.viewer_action_offline) else stringResource(R.string.viewer_save_offline),
                    Modifier.weight(1f),
                    tint = if (offline) Palette.Green else Palette.Text,
                ) { if (offline) vm.removeOffline() else vm.downloadForOffline() }
                QuickAction(Icons.Rounded.Link, stringResource(R.string.viewer_share_link), Modifier.weight(1f), onClick = onShare)
                QuickAction(Icons.AutoMirrored.Rounded.OpenInNew, stringResource(R.string.viewer_open_web), Modifier.weight(1f)) {
                    file.viewUrl?.let {
                        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, it.toUri()))
                    }
                }
            }
        }

        technicalSummary(file).takeIf { it.isNotEmpty() }?.let { cells -> item { SummaryStrip(cells) } }

        seenBy?.userIds()?.takeIf { it.isNotEmpty() }?.let { ids ->
            item {
                Card(stringResource(R.string.viewer_card_seen_by)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ids.take(8).forEachIndexed { i, id ->
                            Avatar(peopleById[id] ?: dev.finalish.core.model.User(id = id), 28.dp, modifier = Modifier.offset(x = (-8 * i).dp).border(2.dp, Palette.Surface2, CircleShape))
                        }
                        Text(
                            ids.mapNotNull { peopleById[it]?.displayName }.let { names ->
                                when {
                                    names.isEmpty() -> pluralStringResource(R.plurals.viewer_seen_by_people, ids.size, ids.size)
                                    ids.size == 1 -> names[0]
                                    else -> pluralStringResource(R.plurals.viewer_seen_by_name_and_others, ids.size - 1, names[0], ids.size - 1)
                                }
                            },
                            style = MaterialTheme.typography.bodyMedium, color = Palette.TextSecondary,
                            modifier = Modifier.offset(x = (-8 * (ids.take(8).size - 1)).dp).padding(start = 10.dp),
                        )
                    }
                }
            }
        }

        if (status != null) {
            item {
                Card(stringResource(R.string.viewer_card_status)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val current = status.selectedOptions.firstOrNull()?.id
                        status.options.forEach { opt ->
                            val sel = opt.id == current
                            val c = statusColor(opt.displayName)
                            Row(
                                Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(if (sel) c.copy(alpha = 0.2f) else Palette.Surface3)
                                    .border(1.dp, if (sel) c else Color.Transparent, RoundedCornerShape(50))
                                    .pressable({ vm.setSelect(status, if (sel) null else opt) }, enabled = status.mutable)
                                    .padding(horizontal = 12.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(Modifier.size(8.dp).clip(CircleShape).background(c))
                                Spacer(Modifier.width(7.dp))
                                Text(opt.displayName, style = MaterialTheme.typography.labelLarge, color = if (sel) c else Palette.TextSecondary)
                            }
                        }
                    }
                }
            }
        }
        if (rating != null) {
            item {
                Card(stringResource(R.string.viewer_card_rating)) {
                    RatingStars(rating.intValue ?: 0, size = 30.dp, onChange = if (rating.mutable) { n -> vm.setRating(rating, n) } else null)
                }
            }
        }

        item {
            Card(stringResource(R.string.viewer_card_details)) {
                DetailRow(stringResource(R.string.viewer_detail_name), file.name)
                DetailRow(stringResource(R.string.viewer_detail_type), file.mediaType ?: "—")
                technicalFormat(file)?.let { DetailRow(stringResource(R.string.viewer_detail_format), it) }
                DetailRow(stringResource(R.string.viewer_detail_size), Format.bytes(file.fileSize))
                DetailRow(stringResource(R.string.viewer_detail_uploaded), Format.dateTime(file.createdAt))
                DetailRow(stringResource(R.string.viewer_detail_updated), Format.dateTime(file.updatedAt))
                file.creator?.let { u ->
                    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.viewer_detail_uploader), style = MaterialTheme.typography.bodyMedium, color = Palette.TextTertiary, modifier = Modifier.width(96.dp))
                        Avatar(u, 22.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(u.displayName, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                file.viewUrl?.let { url ->
                    Row(
                        Modifier.padding(top = 6.dp).clip(RoundedCornerShape(8.dp)).pressable({ copyToClipboard(context, url) }).padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.ContentCopy, null, tint = Palette.AccentBright, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.viewer_copy_link), style = MaterialTheme.typography.labelLarge, color = Palette.AccentBright)
                    }
                }
            }
        }

        if (file.kind == dev.finalish.core.model.MediaKind.Image) item { ExifCard(file) }

        item {
            Card(stringResource(R.string.viewer_card_metadata)) {
                // Like the official "All fields ▾" + search: narrow a long list of fields.
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    FieldFilter.entries.forEach { ff ->
                        val sel = ff == fieldFilter
                        Text(
                            stringResource(ff.labelRes), style = MaterialTheme.typography.labelMedium,
                            color = if (sel) Palette.OnAccent else Palette.TextSecondary,
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(if (sel) Palette.Accent else Palette.Surface3)
                                .pressable({ fieldFilter = ff }, scaleTo = 0.94f).padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                androidx.compose.foundation.text.BasicTextField(
                    fieldQuery, { fieldQuery = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = Palette.Text),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(Palette.Accent),
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Palette.Surface3).padding(horizontal = 12.dp, vertical = 9.dp),
                    decorationBox = { inner ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Search, null, tint = Palette.TextTertiary, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Box { if (fieldQuery.isEmpty()) Text(stringResource(R.string.viewer_search_fields), style = MaterialTheme.typography.bodyMedium, color = Palette.TextTertiary); inner() }
                        }
                    },
                )
                Spacer(Modifier.height(6.dp))
                if (others.isEmpty()) Text(stringResource(R.string.viewer_no_fields_match), style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary, modifier = Modifier.padding(vertical = 8.dp))
                run {
                    others.forEach { f ->
                        MetadataFieldRow(
                            f, peopleById,
                            onText = { editing = f },
                            onToggle = { vm.setToggle(f, it) },
                            onSelect = { vm.setSelect(f, it) },
                            onMultiSelect = { multiEditing = f },
                            onUsers = { userEditing = f },
                            onDate = { dateEditing = f },
                            onRating = { vm.setRating(f, it) },
                        )
                    }
                }
            }
        }
    }

    multiEditing?.let { f -> MultiSelectSheet(f, onDone = { vm.setMultiSelect(f, it) }, onDismiss = { multiEditing = null }) }
    userEditing?.let { f -> UserPickerSheet(f, people, onDone = { vm.setUsers(f, it) }, onDismiss = { userEditing = null }) }
    dateEditing?.let { f -> DateFieldDialog(f, onPick = { vm.setDate(f, it) }, onDismiss = { dateEditing = null }) }
    editing?.let { f ->
        TextPromptDialog(f.name, f.name, f.textValue.orEmpty(), stringResource(R.string.viewer_save), onDismiss = { editing = null }) { v ->
            editing = null
            if (f.fieldType == "number") vm.setNumber(f, v.toDoubleOrNull()) else vm.setText(f, v)
        }
    }
}

/** Camera, lens and exposure of a photo, read from the original's EXIF (hidden when there is none). */
@Composable
private fun ExifCard(file: Asset) {
    val context = LocalContext.current
    val rows by androidx.compose.runtime.produceState<List<Pair<String, String>>>(emptyList(), file.id) {
        val url = file.mediaLinks?.original?.let { it.downloadUrl ?: it.inlineUrl } ?: return@produceState
        val http = dev.finalish.FinalishApp.container(context).baseHttp
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { dev.finalish.core.media.ExifPeek.read(http, url) }.getOrDefault(emptyList())
        }
    }
    if (rows.isNotEmpty()) Card(stringResource(R.string.viewer_card_camera)) { rows.forEach { (k, v) -> DetailRow(k, v) } }
}

/** "H.264 · 1920×1080 · 25 fps", "AAC · 48 kHz · stereo", "4000×2667": from Frame.io's technical metadata. */
internal fun technicalFormat(file: Asset): String? {
    fun num(name: String) = file.meta(name)?.doubleValue
    fun text(name: String) = file.meta(name)?.textValue?.takeIf { it.isNotBlank() }
    val size = num("Resolution - Width")?.let { w -> num("Resolution - Height")?.let { h -> "${w.toInt()}×${h.toInt()}" } }
    val codec = text("Video Codec")?.let { c ->
        when (c.uppercase()) {
            "AVC" -> "H.264"
            "HEVC" -> "H.265"
            else -> c
        }
    }
    val fps = num("Frame Rate")?.let { r -> (if (r % 1.0 == 0.0) r.toInt().toString() else String.format(java.util.Locale.US, "%.3f", r).trimEnd('0')) + " fps" }
    val audio = listOfNotNull(
        text("Audio Codec"),
        num("Audio Sample Rate")?.takeIf { it > 0 }?.let { r -> if (r % 1000.0 == 0.0) "${(r / 1000).toInt()} kHz" else String.format(java.util.Locale.US, "%.1f kHz", r / 1000) },
        num("Audio Channels")?.toInt()?.takeIf { it > 0 }?.let { when (it) { 1 -> "mono"; 2 -> "stereo"; else -> "$it ch" } },
    )
    return when (file.kind) {
        dev.finalish.core.model.MediaKind.Video -> listOfNotNull(codec, size, fps)
        dev.finalish.core.model.MediaKind.Audio -> audio
        else -> listOfNotNull(size)
    }.joinToString(" · ").ifBlank { null }
}

/** "All fields" / "Filled" / "Editable", like the official field-group menu. */
private enum class FieldFilter(@androidx.annotation.StringRes val labelRes: Int) {
    All(R.string.viewer_fields_all), Filled(R.string.viewer_fields_filled), Editable(R.string.viewer_fields_editable);

    fun keeps(f: MetadataField) = when (this) {
        All -> true
        Filled -> !f.isEmpty()
        Editable -> f.mutable
    }
}

/** The official panel's header row: codec, resolution and bitrate at a glance. */
internal fun technicalSummary(file: Asset): List<Pair<Int, String>> {
    fun num(name: String) = file.meta(name)?.doubleValue
    fun text(name: String) = file.meta(name)?.textValue?.takeIf { it.isNotBlank() }
    val bitrate = num("Bit Rate")?.takeIf { it > 0 }?.let { b ->
        if (b >= 1_000_000) String.format(java.util.Locale.US, "%.2f Mbps", b / 1_000_000) else String.format(java.util.Locale.US, "%.0f kbps", b / 1000)
    }
    val size = num("Resolution - Width")?.let { w -> num("Resolution - Height")?.let { h -> "${w.toInt()} × ${h.toInt()}" } }
    return when (file.kind) {
        dev.finalish.core.model.MediaKind.Video -> listOfNotNull(text("Video Codec")?.let { R.string.viewer_summary_codec to it }, size?.let { R.string.viewer_summary_resolution to it }, bitrate?.let { R.string.viewer_summary_bitrate to it })
        dev.finalish.core.model.MediaKind.Audio -> listOfNotNull(
            text("Audio Codec")?.let { R.string.viewer_summary_codec to it },
            num("Audio Sample Rate")?.takeIf { it > 0 }?.let { R.string.viewer_summary_sample_rate to String.format(java.util.Locale.US, "%.1f kHz", it / 1000).replace(".0 ", " ") },
            bitrate?.let { R.string.viewer_summary_bitrate to it },
        )
        dev.finalish.core.model.MediaKind.Image -> listOfNotNull(text("Format")?.let { R.string.viewer_summary_format to it }, size?.let { R.string.viewer_summary_resolution to it })
        else -> emptyList()
    }
}

@Composable
private fun SummaryStrip(cells: List<Pair<Int, String>>) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Palette.Surface2).padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        cells.forEachIndexed { i, (label, value) ->
            if (i > 0) Box(Modifier.width(1.dp).height(28.dp).background(Palette.Hairline))
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(label).uppercase(), style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary)
                Text(value, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun Card(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Palette.Surface2)
            .padding(16.dp),
    ) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary)
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.padding(vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Palette.TextTertiary, modifier = Modifier.width(96.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun QuickAction(icon: ImageVector, label: String, modifier: Modifier, tint: Color = Palette.Text, onClick: () -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Palette.Surface2)
            .pressable(onClick, scaleTo = 0.94f)
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = Palette.TextSecondary, maxLines = 1)
    }
}

@Composable
fun VersionsPanel(
    versions: List<Asset>,
    currentId: String?,
    onSelect: (Asset) -> Unit,
    onCompare: (Asset, Asset) -> Unit,
    modifier: Modifier = Modifier,
    onReorder: ((file: Asset, above: Asset?, below: Asset?) -> Unit)? = null,
    onNewVersion: (() -> Unit)? = null,
) {
    var compareMode by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var picked by remember { mutableStateOf<List<String>>(emptyList()) }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (compareMode) stringResource(R.string.viewer_pick_two_versions) else pluralStringResource(R.plurals.viewer_versions_count, versions.size, versions.size),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (versions.size >= 2) {
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (compareMode) Palette.Accent else Palette.Surface3)
                            .pressable({ compareMode = !compareMode; picked = emptyList() })
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.CompareArrows, null, modifier = Modifier.size(16.dp), tint = if (compareMode) Palette.OnAccent else Color.White)
                        Spacer(Modifier.width(6.dp))
                        Text(if (compareMode) stringResource(R.string.viewer_cancel) else stringResource(R.string.viewer_compare), style = MaterialTheme.typography.labelLarge, color = if (compareMode) Palette.OnAccent else Color.White)
                    }
                }
            }
        }
        items(versions.reversed(), key = { it.id }) { v ->
            val number = versions.indexOf(v) + 1
            val current = v.id == currentId
            val isPicked = v.id in picked
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (current || isPicked) Palette.Surface2 else Color.Transparent)
                    .border(1.dp, if (isPicked) Palette.Accent else Color.Transparent, RoundedCornerShape(16.dp))
                    .pressable({
                        if (compareMode) {
                            picked = (if (isPicked) picked - v.id else (picked + v.id).takeLast(2))
                            if (picked.size == 2) {
                                val a = versions.first { it.id == picked[0] }
                                val b = versions.first { it.id == picked[1] }
                                val (older, newer) = listOf(a, b).sortedBy { versions.indexOf(it) }
                                compareMode = false
                                picked = emptyList()
                                onCompare(older, newer)
                            }
                        } else onSelect(v)
                    }, onLongClick = if (onReorder != null && versions.size > 1) ({ menuFor = v.id }) else null, scaleTo = 0.98f)
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .width(96.dp)
                        .aspectRatio(16f / 10f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Palette.Surface3),
                ) {
                    RemoteImage(v.thumbnailUrl, Modifier.fillMaxSize(), fallback = {})
                    Text(
                        stringResource(R.string.viewer_version_short, number),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier
                            .padding(5.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(if (current) Palette.Accent else Color(0xB3000000))
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(v.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            listOfNotNull(v.creator?.displayName, Format.relative(v.createdAt).ifBlank { null }).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                        )
                        // How much feedback each version got, at a glance.
                        v.commentCount?.takeIf { it > 0 }?.let { n ->
                            val commentsLabel = pluralStringResource(R.plurals.viewer_comment_count, n, n)
                            Row(
                                Modifier.padding(start = 8.dp).clip(RoundedCornerShape(50)).background(Palette.Surface3)
                                    .semantics { contentDescription = commentsLabel }
                                    .padding(horizontal = 7.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Rounded.ChatBubbleOutline, null, tint = Palette.TextSecondary, modifier = Modifier.size(11.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("$n", style = MaterialTheme.typography.labelSmall, color = Palette.TextSecondary)
                            }
                        }
                    }
                }
                if (!compareMode) {
                    // The head of the stack is what reviewers see; "Viewing" is what's on screen here.
                    val head = v.id == versions.lastOrNull()?.id
                    Column(horizontalAlignment = Alignment.End) {
                        if (head) Text(stringResource(R.string.viewer_version_current), style = MaterialTheme.typography.labelMedium, color = Palette.Green)
                        if (current) Text(stringResource(R.string.viewer_version_viewing), style = MaterialTheme.typography.labelMedium, color = Palette.AccentBright)
                    }
                    if (onReorder != null && versions.size > 1) {
                        Icon(
                            Icons.Rounded.MoreHoriz, stringResource(R.string.viewer_version_actions), tint = Palette.TextTertiary,
                            modifier = Modifier.padding(start = 4.dp).size(32.dp).clip(RoundedCornerShape(10.dp)).pressable({ menuFor = v.id }, scaleTo = 0.9f).padding(6.dp),
                        )
                    }
                }
                // Newest is shown first; "up" means a higher version number.
                val idx = versions.indexOf(v)
                androidx.compose.material3.DropdownMenu(menuFor == v.id, onDismissRequest = { menuFor = null }, shape = dev.finalish.ui.theme.MenuShape) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(stringResource(R.string.viewer_version_move_up)) }, enabled = idx < versions.lastIndex,
                        onClick = { menuFor = null; onReorder?.invoke(v, versions.getOrNull(idx + 1), null) },
                    )
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(stringResource(R.string.viewer_version_move_down)) }, enabled = idx > 0,
                        onClick = { menuFor = null; onReorder?.invoke(v, null, versions.getOrNull(idx - 1)) },
                    )
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(stringResource(R.string.viewer_version_make_current)) }, enabled = idx < versions.lastIndex,
                        onClick = { menuFor = null; onReorder?.invoke(v, versions.last(), null) },
                    )
                }
            }
        }
        if (onNewVersion != null || onReorder != null) item(key = "footer") {
            Column(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (onNewVersion != null) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(1.dp, Palette.Outline, RoundedCornerShape(16.dp))
                            .pressable(onNewVersion, scaleTo = 0.98f).padding(vertical = 14.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Add, null, tint = Palette.AccentBright, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.viewer_new_version), style = MaterialTheme.typography.labelLarge, color = Palette.AccentBright)
                    }
                }
                if (onReorder != null && versions.size > 1) {
                    Text(
                        stringResource(R.string.viewer_versions_reorder_hint),
                        style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
        }
    }
}
