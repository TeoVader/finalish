package dev.finalish.ui.viewer

import androidx.lifecycle.lifecycleScope
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CompareArrows
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.ContentCopy
import kotlinx.coroutines.launch
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AddLink
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.stringResource
import dev.finalish.R
import dev.finalish.core.model.Asset
import dev.finalish.core.model.MediaKind
import dev.finalish.core.model.Share
import dev.finalish.core.util.Format
import dev.finalish.ui.components.RemoteImage
import dev.finalish.ui.components.pressable
import dev.finalish.ui.shares.shareText
import dev.finalish.ui.theme.Palette

private enum class ActionsPage { Main, Shares }

/** The "…" menu of the official player: share, versions, downloads, URL, project. */
@Composable
fun AssetActionsSheet(
    file: Asset,
    state: ViewerState,
    vm: ViewerViewModel,
    onNewShare: () -> Unit,
    onVersions: () -> Unit,
    onMove: () -> Unit,
    onCopy: () -> Unit = {},
    onNewVersion: () -> Unit = {},
    onCompareTake: (() -> Unit)? = null,
    onViewInProject: () -> Unit,
    onRename: () -> Unit = {},
    onDelete: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var page by remember { mutableStateOf(ActionsPage.Main) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Palette.Surface2,
    ) {
        // System back steps out of a sub-page before it closes the sheet.
        androidx.activity.compose.BackHandler(page != ActionsPage.Main) { page = ActionsPage.Main }
        AnimatedContent(
            page,
            transitionSpec = { dev.finalish.ui.theme.sheetPage(forward = targetState != ActionsPage.Main) },
            label = "actions",
        ) { p ->
            // The main page scrolls so Rename and Delete stay reachable on short screens.
            val scroll = if (p == ActionsPage.Main) Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()) else Modifier
            Column(scroll.navigationBarsPadding().padding(bottom = 12.dp)) {
                when (p) {
                    ActionsPage.Main -> {
                        Header(file, state)
                        // Mockup: three quick actions as tiles, everything else in grouped rows.
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                            QuickTile(Icons.Rounded.Link, stringResource(R.string.viewer_action_share), Modifier.weight(1f)) { onDismiss(); onNewShare() }
                            QuickTile(Icons.Rounded.FileDownload, stringResource(R.string.viewer_action_download), Modifier.weight(1f)) { vm.saveToDevice(); onDismiss() }
                            QuickTile(
                                Icons.Rounded.DownloadForOffline, if (state.offlinePath != null) stringResource(R.string.viewer_action_offline_done) else stringResource(R.string.viewer_action_offline), Modifier.weight(1f),
                            ) {
                                if (state.offlinePath != null) vm.removeOffline() else vm.downloadForOffline()
                                onDismiss()
                            }
                        }
                        Group {
                            ActionRow(Icons.Rounded.AddLink, stringResource(R.string.viewer_action_add_to_existing_share), chevron = true) { page = ActionsPage.Shares }
                        }
                        Group {
                            if (state.versions.size > 1) {
                                ActionRow(Icons.Rounded.Layers, stringResource(R.string.viewer_action_versions), value = "${state.versions.size}", chevron = true) {
                                    onDismiss(); onVersions()
                                }
                            }
                            ActionRow(Icons.Rounded.Upload, stringResource(R.string.viewer_action_upload_new_version)) { onDismiss(); onNewVersion() }
                            onCompareTake?.let { ActionRow(Icons.AutoMirrored.Rounded.CompareArrows, stringResource(R.string.viewer_action_compare_take)) { onDismiss(); it() } }
                        }
                        Group {
                            if (file.kind == MediaKind.Video) {
                                ActionRow(Icons.Rounded.Image, stringResource(R.string.viewer_action_save_frame)) { vm.downloadStill(); onDismiss() }
                            }
                            ActionRow(Icons.AutoMirrored.Rounded.DriveFileMove, stringResource(R.string.viewer_action_move_to)) { onDismiss(); onMove() }
                            ActionRow(Icons.Rounded.ContentCopy, stringResource(R.string.viewer_action_copy_to)) { onDismiss(); onCopy() }
                            ActionRow(Icons.Rounded.LibraryAdd, stringResource(R.string.viewer_action_duplicate)) { vm.duplicate(); onDismiss() }
                            ActionRow(Icons.Rounded.FolderOpen, stringResource(R.string.viewer_action_show_in_project)) { onDismiss(); onViewInProject() }
                            ActionRow(Icons.AutoMirrored.Rounded.Send, stringResource(R.string.viewer_action_send_link)) {
                                file.viewUrl?.let { shareText(context, it, file.name) }
                                onDismiss()
                            }
                        }
                        Group {
                            ActionRow(Icons.Rounded.DriveFileRenameOutline, stringResource(R.string.viewer_action_rename)) { onDismiss(); onRename() }
                        }
                        Group {
                            ActionRow(Icons.Rounded.DeleteOutline, stringResource(R.string.viewer_action_delete), danger = true) { onDismiss(); onDelete() }
                        }
                        Group {
                            val activity = androidx.activity.compose.LocalActivity.current
                            ActionRow(Icons.Rounded.BugReport, stringResource(R.string.viewer_action_report_problem)) {
                                onDismiss()
                                // Outlives this sheet: closing it would cancel a sheet-scoped launch.
                                (activity as? androidx.activity.ComponentActivity)?.lifecycleScope?.launch {
                                    dev.finalish.core.feedback.FeedbackLauncher.launch(activity, dev.finalish.core.feedback.FeedbackKind.Bug, capture = true)
                                }
                            }
                        }
                    }
                    ActionsPage.Shares -> SharesPicker(vm, onBack = { page = ActionsPage.Main }, onPicked = onDismiss)
                }
            }
        }
    }
}

@Composable
private fun Header(file: Asset, state: ViewerState) {
    // Like the official sheet: the thumbnail with its duration, the name, who uploaded it.
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.width(150.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(Palette.Surface3)) {
            RemoteImage(file.thumbnailUrl, Modifier.fillMaxSize(), fallback = {})
            file.meta("Duration")?.doubleValue?.takeIf { it > 0 }?.let { d ->
                Text(
                    Format.clock((d * 1000).toLong()),
                    style = MaterialTheme.typography.labelSmall, color = Color.White,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp).clip(RoundedCornerShape(5.dp))
                        .background(Color(0xB30A0A0C)).padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(file.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Text(
            listOfNotNull(
                stringResource(R.string.viewer_version_short, state.versionNumber).takeIf { state.versions.size > 1 },
                file.creator?.displayName?.let { stringResource(R.string.viewer_uploaded_by, it) },
                Format.bytes(file.media.fileSize).takeIf { file.media.fileSize > 0 },
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary, maxLines = 1,
        )
    }
}

@Composable
private fun QuickTile(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.height(76.dp).clip(RoundedCornerShape(18.dp)).background(Palette.Surface3).pressable(onClick, scaleTo = 0.95f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        androidx.compose.material3.Icon(icon, null, tint = Palette.Text, modifier = Modifier.size(22.dp))
        Spacer(Modifier.size(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun Group(content: @Composable () -> Unit) {
    Column(
        Modifier
            .padding(horizontal = 14.dp, vertical = 5.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Palette.Surface3),
    ) { content() }
}

@Composable
private fun ActionRow(
    icon: ImageVector,
    label: String,
    value: String? = null,
    chevron: Boolean = false,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(onClick, scaleTo = 0.985f)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (danger) Palette.Red else Palette.TextSecondary, modifier = Modifier.size(21.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = if (danger) Palette.Red else Palette.Text, modifier = Modifier.weight(1f))
        value?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Palette.TextTertiary) }
        if (chevron) Icon(Icons.Rounded.ChevronRight, null, tint = Palette.TextTertiary)
    }
}

@Composable
private fun SharesPicker(vm: ViewerViewModel, onBack: () -> Unit, onPicked: () -> Unit) {
    val shares by vm.projectShares.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.loadProjectShares() }
    Row(
        Modifier.fillMaxWidth().pressable(onBack, scaleTo = 0.985f).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.viewer_back), modifier = Modifier.padding(8.dp))
        Text(stringResource(R.string.viewer_add_to_share_link), style = MaterialTheme.typography.titleMedium)
    }
    val list = shares
    when {
        list == null -> Box(Modifier.fillMaxWidth().heightIn(min = 160.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
        }
        list.isEmpty() -> Text(
            stringResource(R.string.viewer_no_share_links),
            color = Palette.TextTertiary,
            modifier = Modifier.padding(24.dp),
        )
        else -> LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(list, key = { it.id }) { s -> ShareRow(s) { vm.addToShare(s); onPicked() } }
        }
    }
}

@Composable
private fun ShareRow(s: Share, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().pressable(onClick, scaleTo = 0.985f).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Link, null, tint = if (s.enabled) Palette.AccentBright else Palette.TextTertiary)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(s.name ?: stringResource(R.string.viewer_untitled_share), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(s.access.replaceFirstChar { it.uppercase() }, Format.relative(s.createdAt).ifBlank { null }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary,
            )
        }
    }
    HorizontalDivider(color = Palette.Hairline, modifier = Modifier.padding(start = 58.dp))
}
