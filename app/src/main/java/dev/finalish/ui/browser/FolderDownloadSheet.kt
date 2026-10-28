package dev.finalish.ui.browser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.finalish.R
import dev.finalish.core.transfer.FolderDownloadPlan
import dev.finalish.core.transfer.FolderTree
import dev.finalish.core.util.Format
import dev.finalish.ui.components.PrimaryButton
import dev.finalish.ui.components.SecondaryButton
import dev.finalish.ui.theme.Palette

/** "Download" on a folder: walking it, then the confirmation with what it holds. */
sealed interface FolderDownloadUi {
    val folderName: String

    data class Scanning(override val folderName: String, val found: Int = 0) : FolderDownloadUi

    data class Ready(override val folderName: String, val tree: FolderTree, val allVersions: Boolean = false) : FolderDownloadUi {
        val summary = FolderDownloadPlan.summary(tree, allVersions)
    }
}

@Composable
fun FolderDownloadSheet(state: FolderDownloadUi, onAllVersions: (Boolean) -> Unit, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Palette.Surface2,
    ) {
        FolderDownloadContent(state, onAllVersions, onConfirm, onDismiss)
    }
}

@Composable
fun FolderDownloadContent(
    state: FolderDownloadUi,
    onAllVersions: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (state) {
            is FolderDownloadUi.Scanning -> {
                Text(stringResource(R.string.browser_folder_download_scanning, state.folderName), style = MaterialTheme.typography.titleMedium, color = Palette.Text)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = Palette.Accent, strokeWidth = 2.dp)
                    Text(
                        pluralStringResource(R.plurals.browser_folder_download_found, state.found, state.found),
                        style = MaterialTheme.typography.bodyMedium, color = Palette.TextSecondary,
                    )
                }
                SecondaryButton(stringResource(R.string.browser_cancel), onCancel, Modifier.fillMaxWidth())
            }
            is FolderDownloadUi.Ready -> {
                val s = state.summary
                Text(stringResource(R.string.browser_folder_download_title, state.folderName), style = MaterialTheme.typography.titleMedium, color = Palette.Text)
                Text(
                    listOfNotNull(
                        pluralStringResource(R.plurals.browser_folder_download_files, s.files, s.files),
                        Format.bytes(s.bytes),
                        s.folders.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.browser_folder_download_subfolders, it, it) },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.titleSmall, color = Palette.Text,
                )
                Text(
                    stringResource(R.string.browser_folder_download_destination, Format.safeFileName(state.folderName, "folder")),
                    style = MaterialTheme.typography.bodyMedium, color = Palette.TextSecondary,
                )
                if (s.stacks > 0) {
                    Text(
                        pluralStringResource(R.plurals.browser_folder_download_stacks, s.stacks, s.stacks).uppercase(),
                        style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Column {
                        ChoiceRow(stringResource(R.string.browser_folder_download_latest), null, !state.allVersions) { onAllVersions(false) }
                        ChoiceRow(stringResource(R.string.browser_folder_download_all), stringResource(R.string.browser_folder_download_all_hint), state.allVersions) {
                            onAllVersions(true)
                        }
                    }
                }
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryButton(stringResource(R.string.browser_cancel), onCancel, Modifier.weight(1f))
                    PrimaryButton(stringResource(R.string.viewer_action_download), onConfirm, Modifier.weight(1f), icon = Icons.Rounded.FileDownload)
                }
            }
        }
    }
}

@Composable
internal fun ChoiceRow(title: String, hint: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(role = Role.RadioButton, onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected, onClick = null, Modifier.padding(horizontal = 8.dp), colors = RadioButtonDefaults.colors(selectedColor = Palette.Accent))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = Palette.Text)
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary)
        }
    }
}
