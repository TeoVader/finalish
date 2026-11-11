package dev.finalish.ui.shares

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.finalish.R
import dev.finalish.ui.theme.Palette

/**
 * A share link of the user's own account opened in the app as the plain file or folder, with the
 * member's own access: the link's expiry, passphrase, download setting and watermark don't apply.
 * Says so, and offers the link itself in a browser (what a reviewer would see).
 */
@Composable
fun ShareAccessNotice(onOpenInBrowser: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .widthIn(max = 520.dp)
            .fillMaxWidth()
            .shadow(24.dp, RoundedCornerShape(20.dp))
            .clip(RoundedCornerShape(20.dp))
            .background(Palette.Surface3)
            .border(1.dp, Palette.Hairline, RoundedCornerShape(20.dp))
            .padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(Icons.Rounded.Info, null, tint = Palette.AccentBright, modifier = Modifier.padding(top = 2.dp).size(18.dp))
            Spacer(Modifier.size(10.dp))
            Column(Modifier.padding(end = 8.dp)) {
                Text(stringResource(R.string.shares_opened_with_access_title), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.shares_opened_with_access_body),
                    style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.shares_done)) }
            TextButton(onClick = onOpenInBrowser) { Text(stringResource(R.string.shares_open_link_in_browser)) }
        }
    }
}
