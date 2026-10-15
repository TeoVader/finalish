package dev.finalish.ui.shares

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.finalish.core.model.Share
import dev.finalish.ui.components.RemoteImage
import dev.finalish.ui.theme.Palette

/** What a share link shows: its first file (or folder cover), else the link icon. */
@Composable
fun ShareThumb(accountId: String?, share: Share, modifier: Modifier = Modifier, iconSize: Dp = 20.dp) {
    val context = LocalContext.current
    val preview = LocalInspectionMode.current
    val cover by produceState<String?>(null, accountId, share.id) {
        if (accountId != null && !preview) {
            value = dev.finalish.FinalishApp.container(context).repository.shareCover(accountId, share.id)
        }
    }
    val icon: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize().background(Palette.AccentContainer), contentAlignment = Alignment.Center) {
            Icon(
                if (share.enabled) Icons.Rounded.Link else Icons.Rounded.LinkOff, null,
                tint = Palette.AccentBright, modifier = Modifier.size(iconSize),
            )
        }
    }
    Box(modifier) {
        if (cover != null) RemoteImage(cover, Modifier.fillMaxSize(), fallback = icon) else icon()
    }
}

/**
 * A link with several items: the first few as a mosaic (columns of one or two tiles), with
 * "+N" on the last tile when there are more. One item falls back to [ShareThumb].
 */
@Composable
fun ShareMosaic(accountId: String?, share: Share, assets: List<dev.finalish.core.model.Asset>, modifier: Modifier = Modifier, iconSize: Dp = 20.dp) {
    if (assets.size < 2) return ShareThumb(accountId, share, modifier, iconSize)
    val shown = assets.take(MOSAIC_MAX)
    val columns = mosaicColumns(shown.size)
    val more = assets.size - shown.size
    androidx.compose.foundation.layout.Row(modifier, horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(MOSAIC_GAP)) {
        var i = 0
        columns.forEach { count ->
            androidx.compose.foundation.layout.Column(
                Modifier.weight(1f).fillMaxSize(),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(MOSAIC_GAP),
            ) {
                repeat(count) {
                    val a = shown[i++]
                    val last = i == shown.size
                    Box(Modifier.weight(1f).fillMaxSize().background(Palette.Surface3)) {
                        dev.finalish.ui.browser.AssetThumbnail(a, Modifier.fillMaxSize(), scrubbable = false)
                        if (last && more > 0) {
                            Box(Modifier.fillMaxSize().background(Palette.Ink.copy(alpha = 0.72f)), contentAlignment = Alignment.Center) {
                                androidx.compose.material3.Text(
                                    "+$more", style = androidx.compose.material3.MaterialTheme.typography.titleMedium, color = Palette.Text,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private const val MOSAIC_MAX = 5
private val MOSAIC_GAP = 3.dp

/** Tiles per column: 2 → 1·1, 3 → 1·2, 4 → 2·2, 5 → 1·2·2. */
internal fun mosaicColumns(n: Int): List<Int> = when (n) {
    0, 1 -> listOf(n)
    2 -> listOf(1, 1)
    3 -> listOf(1, 2)
    4 -> listOf(2, 2)
    else -> listOf(1, 2, 2)
}
