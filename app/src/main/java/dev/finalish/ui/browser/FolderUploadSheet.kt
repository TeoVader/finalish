package dev.finalish.ui.browser

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DriveFolderUpload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.finalish.R
import dev.finalish.core.transfer.FolderUploadPlan
import dev.finalish.core.transfer.LocalFolder
import dev.finalish.core.util.Format
import dev.finalish.ui.components.PrimaryButton
import dev.finalish.ui.components.SecondaryButton
import dev.finalish.ui.theme.Palette

/** "Upload folder": reading the picked folder, then the confirmation with what it holds. */
sealed interface FolderUploadUi {
    val treeUri: Uri

    data class Scanning(override val treeUri: Uri, val found: Int = 0) : FolderUploadUi

    data class Ready(override val treeUri: Uri, val root: LocalFolder) : FolderUploadUi {
        val summary = FolderUploadPlan.summary(root)
    }
}

@Composable
fun FolderUploadSheet(state: FolderUploadUi, destination: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Palette.Surface2,
    ) {
        FolderUploadContent(state, destination, onConfirm, onDismiss)
    }
}

@Composable
fun FolderUploadContent(state: FolderUploadUi, destination: String, onConfirm: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (state) {
            is FolderUploadUi.Scanning -> {
                Text(stringResource(R.string.browser_folder_upload_scanning), style = MaterialTheme.typography.titleMedium, color = Palette.Text)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = Palette.Accent, strokeWidth = 2.dp)
                    Text(
                        pluralStringResource(R.plurals.browser_folder_download_found, state.found, state.found),
                        style = MaterialTheme.typography.bodyMedium, color = Palette.TextSecondary,
                    )
                }
                SecondaryButton(stringResource(R.string.browser_cancel), onCancel, Modifier.fillMaxWidth())
            }
            is FolderUploadUi.Ready -> {
                val s = state.summary
                Text(stringResource(R.string.browser_folder_upload_title, state.root.name), style = MaterialTheme.typography.titleMedium, color = Palette.Text)
                Text(
                    listOfNotNull(
                        pluralStringResource(R.plurals.browser_folder_download_files, s.files, s.files),
                        Format.bytes(s.bytes),
                        s.folders.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.browser_folder_download_subfolders, it, it) },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.titleSmall, color = Palette.Text,
                )
                Text(
                    stringResource(R.string.browser_folder_upload_destination, state.root.name, destination),
                    style = MaterialTheme.typography.bodyMedium, color = Palette.TextSecondary,
                )
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryButton(stringResource(R.string.browser_cancel), onCancel, Modifier.weight(1f))
                    PrimaryButton(stringResource(R.string.browser_upload), onConfirm, Modifier.weight(1f), icon = Icons.Rounded.DriveFolderUpload)
                }
            }
        }
    }
}
