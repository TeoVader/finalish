package dev.finalish.ui.shares

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.finalish.R
import dev.finalish.core.model.Share
import dev.finalish.core.util.Format
import dev.finalish.ui.components.pressable
import dev.finalish.ui.theme.Palette

/**
 * Picker for choosing a share link to add assets to.
 * Used inside the viewer's action sheet and the folder browser's [AddToShareSheet].
 */
@Composable
fun SharesPicker(
    shares: List<Share>?,
    onPick: (Share) -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (onBack != null) Modifier.pressable(onBack, scaleTo = 0.985f) else Modifier)
                .padding(horizontal = if (onBack != null) 12.dp else 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.viewer_back), modifier = Modifier.padding(8.dp))
            }
            Text(stringResource(R.string.viewer_add_to_share_link), style = MaterialTheme.typography.titleMedium)
        }
        when {
            shares == null -> Box(Modifier.fillMaxWidth().heightIn(min = 160.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
            }
            shares.isEmpty() -> Text(
                stringResource(R.string.viewer_no_share_links),
                color = Palette.TextTertiary,
                modifier = Modifier.padding(24.dp),
            )
            else -> LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(shares, key = { it.id }) { s -> ShareRow(s) { onPick(s) } }
            }
        }
    }
}

@Composable
fun ShareRow(s: Share, onClick: () -> Unit) {
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

/**
 * Bottom sheet for adding files or folders to an existing share link in the folder browser.
 */
@Composable
fun AddToShareSheet(
    shares: List<Share>?,
    onPick: (Share) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Palette.Surface1,
    ) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            SharesPicker(
                shares = shares,
                onPick = { share ->
                    onPick(share)
                    onDismiss()
                },
            )
        }
    }
}
