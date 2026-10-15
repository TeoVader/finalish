package dev.finalish.ui.shares

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.ModeComment
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.finalish.R
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.finalish.core.model.Share
import dev.finalish.core.util.Format
import dev.finalish.ui.components.pressable
import dev.finalish.ui.theme.Palette

/** Horizontal strip of the project's share links, shown at the project root. */
@Composable
fun ProjectSharesStrip(
    shares: List<Share>?,
    accountId: String?,
    onOpen: (Share) -> Unit,
    onSeeAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .pressable(onSeeAll, scaleTo = 0.99f)
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (shares.isNullOrEmpty()) stringResource(R.string.shares_share_links) else pluralStringResource(R.plurals.shares_n_share_links, shares.size, shares.size),
                style = MaterialTheme.typography.labelLarge,
                color = Palette.TextSecondary,
                modifier = Modifier.weight(1f),
            )
            Text(stringResource(R.string.shares_see_all), style = MaterialTheme.typography.labelLarge, color = Palette.AccentBright)
            Icon(Icons.Rounded.ChevronRight, null, tint = Palette.AccentBright, modifier = Modifier.size(18.dp))
        }
        if (shares.isNullOrEmpty()) {
            Text(
                if (shares == null) stringResource(R.string.shares_loading) else stringResource(R.string.shares_none_yet),
                style = MaterialTheme.typography.bodySmall,
                color = Palette.TextTertiary,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        } else {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(shares, key = { it.id }) { s -> ShareChipCard(s, accountId) { onOpen(s) } }
            }
        }
    }
}

@Composable
private fun ShareChipCard(s: Share, accountId: String?, onClick: () -> Unit) {
    Column(
        Modifier
            .width(196.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Palette.Surface2)
            .border(1.dp, Palette.Hairline, RoundedCornerShape(16.dp))
            .pressable(onClick, scaleTo = 0.97f),
    ) {
        // What the link shows, like the cards in Frame.io's share list.
        ShareThumb(accountId, s, Modifier.fillMaxWidth().height(96.dp), iconSize = 26.dp)
        Column(Modifier.padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(if (s.enabled) Palette.AccentContainer else Palette.Surface3),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (s.enabled) Icons.Rounded.Link else Icons.Rounded.LinkOff, null,
                    tint = if (s.enabled) Palette.AccentBright else Palette.TextTertiary,
                    modifier = Modifier.size(17.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                s.name ?: stringResource(R.string.shares_untitled),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.size(10.dp))
        Text(
            when {
                !s.enabled -> stringResource(R.string.shares_status_off)
                s.expiration != null -> Format.remaining(s.expiration)?.let { left -> stringResource(R.string.shares_expires_in, left) }
                    ?: stringResource(R.string.shares_status_expired)
                s.lastViewedAt != null -> stringResource(R.string.shares_viewed, Format.relative(s.lastViewedAt))
                else -> stringResource(R.string.shares_not_viewed_yet)
            },
            style = MaterialTheme.typography.bodySmall,
            color = Palette.TextTertiary,
            maxLines = 1,
        )
        Spacer(Modifier.size(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Flag(Icons.Rounded.ModeComment, s.commentingEnabled)
            Flag(Icons.Rounded.Download, s.downloadingEnabled)
            Flag(Icons.Rounded.Lock, !s.passphrase.isNullOrBlank() || s.access == "secure")
        }
        }
    }
}

@Composable
private fun Flag(icon: ImageVector, on: Boolean) {
    Icon(icon, null, tint = if (on) Palette.TextSecondary else Palette.Surface4, modifier = Modifier.size(15.dp))
}
