package dev.finalish.ui.browser

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.finalish.R
import dev.finalish.core.model.Asset
import dev.finalish.core.model.FolderPreview
import dev.finalish.core.util.Format
import dev.finalish.ui.components.RemoteImage
import dev.finalish.ui.components.pressable
import dev.finalish.ui.theme.Motion
import dev.finalish.ui.theme.Palette

/**
 * Folder card: a folder silhouette (tab + body) holding a mosaic of the
 * contents, so folders read differently from single media thumbnails.
 */
@Composable
fun FolderGridCard(
    folder: Asset,
    preview: FolderPreview?,
    selected: Boolean,
    selecting: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    aspect: Float = 16f / 10f,
    titleLines: Int = 1,
    showInfo: Boolean = true,
    onAction: ((FileAction) -> Unit)? = null,
) {
    val inset by animateDpAsState(if (selected) 5.dp else 0.dp, Motion.snappy(), label = "inset")
    val safeInset = inset.coerceAtLeast(0.dp) // the spring overshoots below zero
    val tabColor = if (selected) Palette.Accent else Palette.Surface3
    Column(modifier.pressable(onClick, onLongClick)) {
        // Folder tab
        Box(
            Modifier
                .padding(start = 0.dp)
                .fillMaxWidth(0.42f)
                .height(9.dp)
                .clip(RoundedCornerShape(topStart = 9.dp, topEnd = 9.dp))
                .background(tabColor),
        )
        // Body
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(aspect)
                .clip(RoundedCornerShape(topStart = 0.dp, topEnd = 14.dp, bottomStart = 14.dp, bottomEnd = 14.dp))
                .background(tabColor)
                .padding(3.dp)
                .padding(safeInset),
        ) {
            Mosaic(
                preview?.thumbnails.orEmpty(),
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(topStart = 0.dp, topEnd = 11.dp, bottomStart = 11.dp, bottomEnd = 11.dp)),
            )
            // Small folder glyph so the type is obvious even on busy images.
            Row(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(7.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(Color(0xB3000000))
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Folder, null, tint = Color.White, modifier = Modifier.size(13.dp))
                if (folder.restricted == true) {
                    Spacer(Modifier.size(4.dp))
                    Icon(Icons.Rounded.Lock, stringResource(R.string.browser_restricted), tint = Color.White, modifier = Modifier.size(11.dp))
                }
            }
            if (selecting) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(9.dp)
                        .size(22.dp)
                        .clip(RoundedCornerShape(50))
                        .background(if (selected) Palette.Accent else Color(0x66000000))
                        .border(1.5.dp, Color.White, RoundedCornerShape(50)),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.Top) {
            Text(
                folder.name, style = MaterialTheme.typography.titleSmall, maxLines = titleLines,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            if (onAction != null && !selecting) FileMenuButton(folder, onAction, compact = true)
        }
        if (showInfo) {
            Text(
                listOfNotNull(
                    preview?.count?.let { pluralStringResource(R.plurals.browser_item_count, it, it) },
                    Format.relative(folder.updatedAt).ifBlank { null },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = Palette.TextTertiary,
                maxLines = 1,
            )
        }
    }
}

/** 0 → folder icon, 1 → full, 2 → halves, 3 → one large + two stacked (like the iPad app). */
@Composable
private fun Mosaic(urls: List<String>, modifier: Modifier) {
    val gap = 2.dp
    Box(modifier.background(Brush.linearGradient(listOf(Palette.Surface2, Palette.Ink)))) {
        when (urls.size) {
            0 -> Icon(
                Icons.Rounded.Folder, null,
                tint = Palette.AccentBright.copy(alpha = 0.85f),
                modifier = Modifier.align(Alignment.Center).size(38.dp),
            )
            1 -> Tile(urls[0], Modifier.fillMaxSize())
            2 -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                Tile(urls[0], Modifier.weight(1f).fillMaxHeight())
                Tile(urls[1], Modifier.weight(1f).fillMaxHeight())
            }
            else -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                Tile(urls[0], Modifier.weight(2f).fillMaxHeight())
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(gap)) {
                    Tile(urls[1], Modifier.weight(1f).fillMaxWidth())
                    Tile(urls[2], Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun Tile(url: String, modifier: Modifier) {
    RemoteImage(url, modifier.background(Palette.Surface3), fallback = {
        Box(Modifier.fillMaxSize().background(Palette.Surface3), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Image, null, tint = Palette.TextTertiary.copy(alpha = 0.6f), modifier = Modifier.size(18.dp))
        }
    })
}
