package dev.finalish.ui.transfers

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.finalish.FinalishApp
import dev.finalish.R
import dev.finalish.core.transfer.DeviceDownload
import dev.finalish.core.transfer.DownloadItem
import dev.finalish.core.transfer.TransferState
import dev.finalish.core.transfer.UploadItem
import dev.finalish.core.util.Format
import dev.finalish.ui.components.EmptyState
import dev.finalish.ui.components.KindPlaceholder
import dev.finalish.ui.components.pressable
import dev.finalish.ui.home.Chip
import dev.finalish.ui.theme.Palette
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun TransfersTab(onOpenOffline: (DownloadItem) -> Unit, contentPadding: PaddingValues, onOpenUpload: (UploadItem) -> Unit = {}) {
    val context = LocalContext.current
    val container = remember { FinalishApp.container(context) }
    val downloads by container.transfers.downloads.collectAsStateWithLifecycle()
    val uploads by container.transfers.uploads.collectAsStateWithLifecycle()
    val deviceDownloads by container.transfers.deviceDownloads.collectAsStateWithLifecycle()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var confirmRemoveAll by remember { androidx.compose.runtime.mutableStateOf(false) }
    // Walks the offline folder: off the main thread, again only when the downloads change.
    val offlineBytes by androidx.compose.runtime.produceState(0L, downloads) {
        value = withContext(Dispatchers.IO) { container.transfers.offlineBytes() }
    }
    // One network watcher and settings read for the whole screen (not one per row): what queued work waits for.
    val net = dev.finalish.ui.components.rememberNetworkStatus()
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val waiting = if (net.online && net.metered && !settings.cellularTransfers) R.string.transfers_waiting_wifi else R.string.transfers_waiting
    if (confirmRemoveAll) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmRemoveAll = false },
            title = { Text(stringResource(R.string.transfers_remove_all_title)) },
            text = { Text(stringResource(R.string.transfers_remove_all_message, Format.bytes(offlineBytes))) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    downloads.forEach { container.transferManager.cancelDownload(it.fileId) }
                    confirmRemoveAll = false
                }) { Text(stringResource(R.string.transfers_remove_all), color = Palette.Red) }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmRemoveAll = false }) { Text(stringResource(R.string.transfers_cancel)) } },
            containerColor = Palette.Surface2,
        )
    }

    androidx.compose.runtime.CompositionLocalProvider(LocalWaitingLabel provides waiting) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Text(stringResource(R.string.transfers_title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 14.dp))
            // Segmented Uploads | Offline (mockup), with "Clear done" just under it when useful.
            Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.Surface2).padding(4.dp)) {
                listOf(
                    stringResource(R.string.transfers_tab_uploads, uploads.size),
                    stringResource(R.string.transfers_tab_downloads, deviceDownloads.size),
                    stringResource(R.string.transfers_tab_offline, downloads.size),
                ).forEachIndexed { i, label ->
                    val on = tab == i
                    Box(
                        Modifier.weight(1f).height(38.dp).clip(RoundedCornerShape(11.dp))
                            .background(if (on) Palette.Surface4 else androidx.compose.ui.graphics.Color.Transparent)
                            .pressable({ tab = i }, scaleTo = 0.97f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(label, style = MaterialTheme.typography.labelLarge, color = if (on) Palette.Text else Palette.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (tab == 0) UploadsSummary(uploads)
            if (tab == 1) {
                DownloadsSummary(deviceDownloads)
                // The system DownloadManager moves single files along: follow it while this list shows.
                LaunchedEffect(Unit) {
                    while (true) {
                        withContext(Dispatchers.IO) { dev.finalish.core.transfer.syncDeviceDownloads(context, container.transfers) }
                        kotlinx.coroutines.delay(1_000)
                    }
                }
            }
            if (tab == 2 && downloads.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.transfers_on_this_phone, Format.bytes(offlineBytes)),
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.TextTertiary,
                        modifier = Modifier.weight(1f),
                    )
                    TextLink(stringResource(R.string.transfers_remove_all)) { confirmRemoveAll = true }
                }
            }
            val sortedDownloads = remember(downloads) { downloads.sortedByDescending { it.createdAt } }
            // Moving first, then what needs a hand (failed or paused), then what's done.
            val sections = remember(uploads) {
                uploads.sortedByDescending { it.createdAt }.groupBy { section(it.state) }.toSortedMap()
            }
            // A folder with files that didn't make it needs a hand too.
            val deviceSections = remember(deviceDownloads) {
                deviceDownloads.sortedByDescending { it.createdAt }
                    .groupBy { if (it.state == TransferState.Done && it.failed > 0) 1 else section(it.state) }.toSortedMap()
            }
            AnimatedContent(tab, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "tt") { t ->
                LazyColumn(
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp, bottom = contentPadding.calculateBottomPadding() + 16.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (t == 1) {
                        if (deviceDownloads.isEmpty()) item {
                            EmptyState(Icons.Rounded.FileDownload, stringResource(R.string.transfers_downloads_empty_title), stringResource(R.string.transfers_downloads_empty_message))
                        }
                        deviceSections.forEach { (section, list) ->
                            item(key = "h$section") {
                                SectionHeader(section, list.size, Modifier.animateItem()) { container.transfers.clearDeviceDownloads(list.map { it.id }.toSet()) }
                            }
                            items(list, key = { it.id }) { d ->
                                DeviceDownloadRow(
                                    d,
                                    onRetry = {
                                        if (d.folder) {
                                            container.transferManager.downloadFolder(d.accountId, d.name, d.failedItems, retryOf = d.id)
                                        } else scope.launch {
                                            dev.finalish.core.util.runCatchingCancellable {
                                                val file = container.repository.fileNow(d.accountId, d.fileId ?: error(""))
                                                dev.finalish.core.transfer.saveToDevice(context, container.repository, d.accountId, file, retryOf = d.id)
                                            }.onFailure { e -> container.transfers.updateDeviceDownload(d.id) { it.copy(state = TransferState.Failed, error = e.message) } }
                                        }
                                    },
                                    onCancel = { container.transferManager.cancelDeviceDownload(d.id) },
                                    onOpen = { dev.finalish.core.transfer.openDeviceDownload(context, d) },
                                    modifier = Modifier.animateItem(),
                                )
                            }
                        }
                    } else if (t == 2) {
                        if (downloads.isEmpty()) item {
                            EmptyState(
                                Icons.Rounded.DownloadForOffline, stringResource(R.string.transfers_offline_empty_title),
                                stringResource(R.string.transfers_offline_empty_message),
                            )
                        }
                        items(sortedDownloads, key = { it.fileId }) { d ->
                            DownloadRow(
                                d,
                                onOpen = { if (d.state == TransferState.Done) onOpenOffline(d) },
                                onRemove = { container.transferManager.cancelDownload(d.fileId) },
                                modifier = Modifier.animateItem(),
                            )
                        }
                    } else {
                        if (uploads.isEmpty()) item {
                            EmptyState(Icons.Rounded.CloudUpload, stringResource(R.string.transfers_uploads_empty_title), stringResource(R.string.transfers_uploads_empty_message))
                        }
                        sections.forEach { (section, list) ->
                            item(key = "h$section") {
                                SectionHeader(section, list.size, Modifier.animateItem()) { container.transfers.clearUploads(list.map { it.id }.toSet()) }
                            }
                            items(list, key = { it.id }) { u ->
                                UploadRow(
                                    u,
                                    onRetry = { container.transferManager.retryUpload(u.id) },
                                    onCancel = { container.transferManager.cancelUpload(u.id) },
                                    modifier = Modifier.animateItem(),
                                    // Uploaded: tap opens the file on Frame.io.
                                    onOpen = u.takeIf { it.state == TransferState.Done && it.remoteFileId != null }?.let { { onOpenUpload(it) } },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Moving first, then what needs a hand (failed or paused), then what's done. */
private fun section(state: TransferState) = when (state) {
    TransferState.Running, TransferState.Queued -> 0
    TransferState.Failed, TransferState.Cancelled -> 1
    TransferState.Done -> 2
}

@Composable
private fun SectionHeader(section: Int, count: Int, modifier: Modifier, onClear: () -> Unit) {
    Row(
        modifier.fillMaxWidth().padding(start = 8.dp, top = 14.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(listOf(R.string.transfers_section_in_progress, R.string.transfers_section_attention, R.string.transfers_section_completed)[section]).uppercase() + " · $count",
            style = MaterialTheme.typography.labelMedium,
            color = Palette.TextTertiary,
            modifier = Modifier.weight(1f),
        )
        if (section > 0) TextLink(stringResource(R.string.transfers_clear), onClear)
    }
}

/** Which "waiting" line queued transfers show; provided once per screen by [TransfersTab]. */
private val LocalWaitingLabel = androidx.compose.runtime.staticCompositionLocalOf { R.string.transfers_waiting }

/** The total for the uploads in flight; its own scope, so the ticking numbers redraw only this card. */
@Composable
private fun UploadsSummary(uploads: List<UploadItem>) {
    // Imports from a link have no bytes on this phone, so they stay out of the totals.
    val running = remember(uploads) { uploads.filter { (it.state == TransferState.Running || it.state == TransferState.Queued) && it.remoteSource == null } }
    if (running.isNotEmpty()) SummaryCard(running.size, running.sumOf { it.doneBytes }, running.sumOf { it.totalBytes })
}

/** The same total for downloads to the Download folder; a folder counts the files it has left. */
@Composable
private fun DownloadsSummary(list: List<DeviceDownload>) {
    val running = remember(list) { list.filter { it.state == TransferState.Running || it.state == TransferState.Queued } }
    if (running.isNotEmpty()) SummaryCard(running.sumOf { (it.files - it.finished).coerceAtLeast(1) }, running.sumOf { it.doneBytes }, running.sumOf { it.totalBytes })
}

@Composable
private fun SummaryCard(files: Int, done: Long, totalBytes: Long) {
    val total = totalBytes.coerceAtLeast(1)
    val meter = remember { dev.finalish.core.transfer.RateMeter() }
    LaunchedEffect(done) { meter.sample(done) }
    val rate = meter.bytesPerSecond
    Column(
        Modifier.padding(horizontal = 16.dp).padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(Palette.Surface2).padding(16.dp),
    ) {
        Row {
            Text(pluralStringResource(R.plurals.transfers_in_progress_files, files, files), style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary, modifier = Modifier.weight(1f))
            rate?.takeIf { it >= 1 }?.let { Text("${Format.bytes(it.toLong())}/s", style = dev.finalish.ui.theme.TimecodeStyle, color = Palette.TextSecondary) }
        }
        val pct = (done * 100 / total).toInt()
        val eta = rate?.takeIf { it >= 1 }?.let { ((total - done) / it).toLong() }
        Text(
            "$pct%" + (eta?.let { " · " + if (it >= 60) stringResource(R.string.transfers_eta_min, (it / 60).toInt()) else stringResource(R.string.transfers_eta_sec, it.toInt()) } ?: ""),
            style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(vertical = 4.dp),
        )
        dev.finalish.ui.components.TransferProgress(done.toFloat() / total)
    }
}

@Composable
private fun TextLink(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = Palette.Link,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).pressable(onClick).padding(8.dp),
    )
}

@Composable
private fun DownloadRow(d: DownloadItem, onOpen: () -> Unit, onRemove: () -> Unit, modifier: Modifier) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).pressable(onOpen, scaleTo = 0.98f).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(88.dp).aspectRatio(16f / 10f).clip(RoundedCornerShape(10.dp)).background(Palette.Surface2)) {
            // Checked once per path, not on every progress tick.
            val thumb = remember(d.thumbnailPath) { d.thumbnailPath?.let { File(it) }?.takeIf { it.exists() } }
            if (thumb != null) {
                coil3.compose.AsyncImage(thumb, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
            } else KindPlaceholder(d.kind, Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(d.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            when (d.state) {
                TransferState.Done -> StateLine(Icons.Rounded.CloudDone, stringResource(R.string.transfers_available_offline, Format.bytes(d.totalBytes)), Palette.Green)
                TransferState.Failed -> StateLine(Icons.Rounded.ErrorOutline, d.error ?: stringResource(R.string.transfers_failed), Palette.Red)
                TransferState.Cancelled -> StateLine(Icons.Rounded.Close, stringResource(R.string.transfers_cancelled), Palette.TextTertiary)
                else -> {
                    val rate = rememberTransferRate(d.doneBytes, d.state == TransferState.Running)
                    // Before the first byte the size is the original's, not the rendition's that gets saved: leave it out.
                    Progress(d.progress, d.state == TransferState.Queued, if (d.doneBytes > 0) progressLabel(d.doneBytes, d.totalBytes, rate) else "")
                }
            }
        }
        Icon(
            if (d.state == TransferState.Done) Icons.Rounded.DeleteOutline else Icons.Rounded.Close,
            stringResource(R.string.transfers_remove), tint = Palette.TextTertiary,
            modifier = Modifier.padding(8.dp).size(22.dp).pressable(onRemove, scaleTo = 0.85f),
        )
    }
}

@Composable
internal fun UploadRow(u: UploadItem, onRetry: () -> Unit, onCancel: () -> Unit, modifier: Modifier, onOpen: (() -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.Surface1)
            .then(if (onOpen != null) Modifier.pressable(onOpen, scaleTo = 0.98f) else Modifier)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        UploadThumb(u, Modifier.width(72.dp).aspectRatio(16f / 10f).clip(RoundedCornerShape(10.dp)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(u.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(stringResource(R.string.transfers_to_folder, u.folderName.ifBlank { stringResource(R.string.transfers_folder) }), style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary)
            Spacer(Modifier.height(6.dp))
            if (u.remoteSource != null) when (u.state) {
                // Frame.io fetches the file itself: no bytes or speed to show, just where it stands.
                TransferState.Done -> StateLine(Icons.Rounded.CloudDone, stringResource(R.string.transfers_imported), Palette.Green)
                TransferState.Failed -> StateLine(Icons.Rounded.ErrorOutline, u.error ?: stringResource(R.string.transfers_import_failed), Palette.Red)
                TransferState.Cancelled -> StateLine(Icons.Rounded.Close, stringResource(R.string.transfers_stopped_following), Palette.TextTertiary)
                // No percentage from Frame.io while it fetches: an indeterminate bar.
                else -> Column {
                    androidx.compose.material3.LinearProgressIndicator(
                        Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                        color = Palette.Accent, trackColor = Palette.Surface3,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.transfers_importing), style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary)
                }
            } else when (u.state) {
                TransferState.Done -> if (u.error != null) {
                    StateLine(Icons.Rounded.ErrorOutline, u.error, Palette.Amber)
                } else {
                    StateLine(
                        Icons.Rounded.CloudDone,
                        stringResource(if (u.stackOntoId != null) R.string.transfers_uploaded_version else R.string.transfers_uploaded, Format.bytes(u.totalBytes)),
                        Palette.Green,
                    )
                }
                TransferState.Failed -> StateLine(Icons.Rounded.ErrorOutline, u.error ?: stringResource(R.string.transfers_failed), Palette.Red)
                TransferState.Cancelled -> StateLine(
                    Icons.Rounded.Close,
                    if (u.resumable) stringResource(R.string.transfers_paused, Format.bytes(u.doneBytes), Format.bytes(u.totalBytes)) else stringResource(R.string.transfers_cancelled),
                    Palette.TextTertiary,
                )
                else -> {
                    val rate = rememberTransferRate(u.doneBytes, u.state == TransferState.Running)
                    Progress(u.progress, u.state == TransferState.Queued, progressLabel(u.doneBytes, u.totalBytes, rate))
                }
            }
        }
        when (u.state) {
            // Uploaded, but "as new version" didn't go through: retry just that.
            TransferState.Done -> if (u.error != null && u.stackOntoId != null && u.remoteFileId != null) Icon(
                Icons.Rounded.Refresh, stringResource(R.string.transfers_retry), tint = Palette.AccentBright,
                modifier = Modifier.padding(start = 8.dp).size(24.dp).pressable(onRetry, scaleTo = 0.85f),
            )
            // An import can't be resent from here (the link may need checking): no retry.
            TransferState.Failed, TransferState.Cancelled -> if (u.remoteSource == null) Icon(
                Icons.Rounded.Refresh, stringResource(if (u.resumable) R.string.transfers_resume else R.string.transfers_retry), tint = Palette.AccentBright,
                modifier = Modifier.padding(start = 8.dp).size(24.dp).pressable(onRetry, scaleTo = 0.85f),
            )
            TransferState.Running, TransferState.Queued -> Icon(
                Icons.Rounded.Close, stringResource(R.string.transfers_cancel), tint = Palette.TextTertiary,
                modifier = Modifier.padding(start = 8.dp).size(22.dp).pressable(onCancel, scaleTo = 0.85f),
            )
            else -> {}
        }
    }
}

/** A download into the phone's Download folder: one file, or a folder as a single row. */
@Composable
internal fun DeviceDownloadRow(d: DeviceDownload, onRetry: () -> Unit, onCancel: () -> Unit, onOpen: () -> Unit, modifier: Modifier) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.Surface1)
            .then(if (d.state == TransferState.Done) Modifier.pressable(onOpen, scaleTo = 0.98f) else Modifier)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val thumb = Modifier.width(72.dp).aspectRatio(16f / 10f).clip(RoundedCornerShape(10.dp))
        if (d.folder) {
            Box(thumb.background(Palette.Surface2), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Folder, null, tint = Palette.Accent, modifier = Modifier.size(24.dp))
            }
        } else KindPlaceholder(d.kind, thumb.background(Palette.Surface2))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(d.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Download/${d.path}", style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            val counts = if (d.folder) folderCounts(d) else null
            when {
                d.state == TransferState.Running || d.state == TransferState.Queued -> {
                    val rate = rememberTransferRate(d.doneBytes, d.state == TransferState.Running)
                    val bytes = progressLabel(d.doneBytes, d.totalBytes, rate)
                    val label = if (d.folder) pluralStringResource(R.plurals.core_folder_download_progress, d.files, d.finished, d.files) + " · " + bytes else bytes
                    Progress(d.progress, d.state == TransferState.Queued, label)
                }
                d.state == TransferState.Done && d.failed == 0 ->
                    StateLine(Icons.Rounded.CloudDone, counts ?: Format.bytes(d.totalBytes), Palette.Green)
                d.state == TransferState.Done -> StateLine(Icons.Rounded.ErrorOutline, counts.orEmpty(), Palette.Amber)
                d.state == TransferState.Failed -> StateLine(Icons.Rounded.ErrorOutline, d.error ?: stringResource(R.string.transfers_failed), Palette.Red)
                else -> StateLine(Icons.Rounded.Close, stringResource(R.string.transfers_cancelled), Palette.TextTertiary)
            }
        }
        val retryable = (d.folder && d.failedItems.isNotEmpty() && d.state != TransferState.Running && d.state != TransferState.Queued) ||
            (!d.folder && (d.state == TransferState.Failed || d.state == TransferState.Cancelled))
        when {
            d.state == TransferState.Running || d.state == TransferState.Queued -> Icon(
                Icons.Rounded.Close, stringResource(R.string.transfers_cancel), tint = Palette.TextTertiary,
                modifier = Modifier.padding(start = 8.dp).size(22.dp).pressable(onCancel, scaleTo = 0.85f),
            )
            retryable -> Icon(
                Icons.Rounded.Refresh, stringResource(R.string.transfers_retry), tint = Palette.AccentBright,
                modifier = Modifier.padding(start = 8.dp).size(24.dp).pressable(onRetry, scaleTo = 0.85f),
            )
        }
    }
}

/** "38 saved · 1 skipped · 1 failed" for a finished folder. */
@Composable
private fun folderCounts(d: DeviceDownload): String = listOfNotNull(
    pluralStringResource(R.plurals.transfers_saved_count, d.saved, d.saved),
    d.skipped.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.transfers_skipped_count, it, it) },
    d.failed.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.core_folder_download_failed, it, it) },
).joinToString(" · ")

/** Local preview of the file being uploaded (image or first video frame), else a type icon. */
@Composable
private fun UploadThumb(u: UploadItem, modifier: Modifier) {
    if (u.remoteSource != null) {
        Box(modifier.background(Palette.Surface2), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Link, null, tint = Palette.TextSecondary, modifier = Modifier.size(22.dp))
        }
    } else {
        // Shared files upload from a private copy that's gone once done: its saved thumbnail stands in.
        val thumb = remember(u.thumbnailPath) { u.thumbnailPath?.let { File(it) }?.takeIf { it.exists() } }
        if (thumb != null) {
            coil3.compose.AsyncImage(thumb, null, modifier.background(Palette.Surface2), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
        } else LocalFileThumb(u.uri, u.mimeType, modifier)
    }
}

/** Thumbnail of a file on the phone (content URI): its frame for images and videos, else the kind's icon. */
@Composable
internal fun LocalFileThumb(uri: String, mimeType: String, modifier: Modifier) {
    val kind = when {
        mimeType.startsWith("video/") -> dev.finalish.core.model.MediaKind.Video
        mimeType.startsWith("image/") -> dev.finalish.core.model.MediaKind.Image
        mimeType.startsWith("audio/") -> dev.finalish.core.model.MediaKind.Audio
        mimeType == "application/pdf" -> dev.finalish.core.model.MediaKind.Pdf
        else -> dev.finalish.core.model.MediaKind.Other
    }
    Box(modifier.background(Palette.Surface2)) {
        KindPlaceholder(kind, Modifier.fillMaxSize())
        if (kind == dev.finalish.core.model.MediaKind.Video || kind == dev.finalish.core.model.MediaKind.Image) {
            // Drawn over the placeholder; if the content URI is no longer readable it simply stays hidden.
            coil3.compose.AsyncImage(
                model = uri, contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Smoothed transfer speed in bytes/s while [active], from how fast [doneBytes] grows. */
@Composable
private fun rememberTransferRate(doneBytes: Long, active: Boolean): Double? {
    val meter = remember { dev.finalish.core.transfer.RateMeter() }
    var rate by remember { androidx.compose.runtime.mutableStateOf<Double?>(null) }
    androidx.compose.runtime.LaunchedEffect(doneBytes, active) {
        if (!active) meter.reset() else meter.sample(doneBytes)
        rate = meter.bytesPerSecond
    }
    return rate
}

@Composable
private fun progressLabel(done: Long, total: Long, rate: Double?) =
    dev.finalish.core.transfer.transferLabel(androidx.compose.ui.platform.LocalContext.current, done, total, rate)

@Composable
private fun StateLine(icon: ImageVector, text: String, color: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = color, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(5.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Progress(p: Float, queued: Boolean, label: String) {
    // Queued on mobile data with "Use mobile data" off: say what it waits for.
    val waiting = LocalWaitingLabel.current
    Column {
        dev.finalish.ui.components.TransferProgress(p, moving = !queued)
        Spacer(Modifier.height(4.dp))
        Text(if (queued) stringResource(waiting, label).trim() else label, style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary)
    }
}
