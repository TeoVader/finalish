package dev.finalish.ui.home

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.finalish.FinalishApp
import dev.finalish.R
import dev.finalish.core.data.UploadDestination
import dev.finalish.core.model.Asset
import dev.finalish.core.model.Project
import dev.finalish.core.util.Format
import dev.finalish.ui.browser.AssetPickerSheet
import dev.finalish.ui.browser.FolderPickerSheet
import dev.finalish.ui.components.BrandMark
import dev.finalish.ui.components.PrimaryButton
import dev.finalish.ui.components.pressable
import dev.finalish.ui.theme.Palette
import dev.finalish.ui.transfers.LocalFileThumb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A file another app shared with us. */
data class SharedFile(val uri: String, val mimeType: String, val size: Long)

private sealed interface Step {
    data object Main : Step
    data object Project : Step
    data class Folder(val project: dev.finalish.core.model.Project) : Step
    data object Version : Step
}

/**
 * "Share to Final(ish)": one sheet with what's being uploaded, where to (last time's
 * folder by default), optionally as a new version of an existing file, and Upload.
 */
@Composable
internal fun ShareInFlow(accountId: String, uris: List<Uri>, source: String?, projects: List<Project>, onDone: () -> Unit) {
    val context = LocalContext.current
    val container = remember { FinalishApp.container(context) }
    val last = remember { container.settings.settings.value.lastUpload?.takeIf { it.accountId == accountId } }
    var destination by remember { mutableStateOf(last) }
    var version by remember { mutableStateOf<Asset?>(null) }
    var step by remember { mutableStateOf<Step>(Step.Main) }
    var files by remember { mutableStateOf(uris.map { SharedFile(it.toString(), "", -1) }) }
    LaunchedEffect(uris) {
        files = withContext(Dispatchers.IO) {
            uris.map { u ->
                SharedFile(
                    u.toString(),
                    runCatching { context.contentResolver.getType(u) }.getOrNull().orEmpty(),
                    runCatching { container.transferManager.describe(u).second }.getOrDefault(-1),
                )
            }
        }
    }

    when (val s = step) {
        Step.Main -> ModalBottomSheet(
            onDismissRequest = onDone,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Palette.Surface1,
        ) {
            ShareInContent(
                files = files,
                source = source,
                destination = destination?.label,
                version = version?.name,
                onDestination = { step = Step.Project },
                onVersion = { if (destination != null) step = Step.Version },
                onClearVersion = { version = null },
                onUpload = {
                    val d = destination ?: return@ShareInContent
                    container.transferManager.upload(accountId, d.folderId, d.folderName, uris, version)
                    container.settings.update { it.copy(lastUpload = d) }
                    onDone()
                },
            )
        }
        Step.Project -> ModalBottomSheet(
            onDismissRequest = { step = Step.Main },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Palette.Surface1,
        ) {
            Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
                Text(stringResource(R.string.home_share_in_upload_to), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                LazyColumn(Modifier.heightIn(max = 480.dp)) {
                    items(projects, key = { it.id }) { pr ->
                        Row(
                            Modifier.fillMaxWidth().pressable({ step = Step.Folder(pr) }, scaleTo = 0.98f).padding(horizontal = 20.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(projectGradient(pr.id)))
                            Spacer(Modifier.width(14.dp))
                            Text(pr.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            if (pr.id == destination?.projectId) Text(stringResource(R.string.home_share_in_last_used), style = MaterialTheme.typography.labelMedium, color = Palette.TextTertiary)
                        }
                    }
                }
            }
        }
        is Step.Folder -> FolderPickerSheet(
            accountId = accountId,
            rootFolderId = s.project.rootFolderId,
            rootName = s.project.name,
            actionLabel = stringResource(R.string.home_share_in_switch),
            onPick = { folderId, name ->
                destination = UploadDestination(accountId, s.project.id, s.project.name, s.project.rootFolderId, folderId, name)
                version = null // it belonged to the old folder
                step = Step.Main
            },
            onDismiss = { step = Step.Main },
        )
        Step.Version -> destination?.let { d ->
            val stackLabel = stringResource(R.string.home_share_in_stack_on_top)
            AssetPickerSheet(
                accountId = accountId,
                projectId = d.projectId,
                startFolder = d.folderId to (if (d.folderId == d.rootFolderId) d.projectName else d.folderName),
                single = true,
                actionLabel = { stackLabel },
                onPick = { picked -> version = picked.firstOrNull(); step = Step.Main },
                onDismiss = { step = Step.Main },
            )
        }
    }
}

@Composable
internal fun ShareInContent(
    files: List<SharedFile>,
    source: String?,
    destination: String?,
    version: String?,
    onDestination: () -> Unit,
    onVersion: () -> Unit,
    onClearVersion: () -> Unit,
    onUpload: () -> Unit,
) {
    val count = files.size
    val bytes = files.filter { it.size >= 0 }.sumOf { it.size }
    val items = pluralStringResource(R.plurals.home_item_count, count, count)
    Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).background(Palette.Ink), contentAlignment = Alignment.Center) {
                BrandMark(26.dp)
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(stringResource(R.string.home_share_in_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    listOfNotNull(if (source != null) stringResource(R.string.home_share_in_items_from, items, source) else items, bytes.takeIf { it > 0 }?.let(Format::bytes)).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.TextTertiary,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val shown = if (count > 4) files.take(3) else files.take(4)
            shown.forEach { f -> LocalFileThumb(f.uri, f.mimeType, Modifier.size(72.dp).clip(RoundedCornerShape(12.dp))) }
            if (count > 4) {
                Box(Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)).background(Palette.Surface3), contentAlignment = Alignment.Center) {
                    Text("+${count - 3}", style = MaterialTheme.typography.titleMedium, color = Palette.TextSecondary)
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.Surface2)) {
            Row(
                Modifier.fillMaxWidth().pressable(onDestination, scaleTo = 0.99f).padding(start = 16.dp, end = 10.dp, top = 14.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.FolderOpen, null, tint = Palette.TextSecondary, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(14.dp))
                Text(stringResource(R.string.home_share_in_destination), style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.width(12.dp))
                Text(
                    destination ?: stringResource(R.string.home_share_in_choose),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (destination == null) Palette.Accent else Palette.TextSecondary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f),
                )
                Icon(Icons.Rounded.ChevronRight, null, tint = Palette.TextTertiary)
            }
            HorizontalDivider(color = Palette.Hairline, modifier = Modifier.padding(start = 52.dp))
            // Stacking one upload on one file keeps the version order unambiguous.
            val versionEnabled = count == 1 && destination != null
            Row(
                Modifier.fillMaxWidth()
                    .alpha(if (versionEnabled) 1f else 0.5f)
                    .pressable(onVersion, enabled = versionEnabled, scaleTo = 0.99f)
                    .padding(start = 16.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Layers, null, tint = if (version != null) Palette.Accent else Palette.TextSecondary, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (version != null) stringResource(R.string.home_share_in_new_version_of, version) else stringResource(R.string.home_share_in_as_new_version), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        when {
                            version != null -> stringResource(R.string.home_share_in_version_stacked)
                            count > 1 -> stringResource(R.string.home_share_in_version_one_file)
                            destination == null -> stringResource(R.string.home_share_in_version_choose_destination)
                            else -> stringResource(R.string.home_share_in_version_optional)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.TextTertiary,
                    )
                }
                if (version != null) {
                    IconButton(onClick = onClearVersion) { Icon(Icons.Rounded.Close, stringResource(R.string.home_share_in_dont_stack), tint = Palette.TextTertiary) }
                } else {
                    Icon(Icons.Rounded.ChevronRight, null, tint = Palette.TextTertiary)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        PrimaryButton(
            if (version != null) stringResource(R.string.home_share_in_upload_as_version) else stringResource(R.string.home_share_in_upload_items, items),
            enabled = destination != null,
            onClick = onUpload,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
