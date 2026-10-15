package dev.finalish.ui.settings

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.finalish.R
import dev.finalish.core.data.AppSettings
import dev.finalish.ui.components.BrandMark
import dev.finalish.ui.components.pressable
import dev.finalish.ui.theme.AccentTheme
import dev.finalish.ui.theme.Palette

/** "Theme & colors": pick the accent, follow the wallpaper (Material You), or go pure black. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeSheet(settings: AppSettings, onChange: ((AppSettings) -> AppSettings) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        modifier = Modifier.offset(x = dev.finalish.ui.components.LocalSideBarWidth.current / 2),
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Palette.Surface1,
    ) {
        Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()).navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.settings_theme_and_colors), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 4.dp))
            Preview()
            Text(
                stringResource(R.string.settings_theme_accent_header), style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp),
            )
            val dynamic = settings.dynamicAccent
            AccentTheme.entries.chunked(4).forEach { rowThemes ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rowThemes.forEach { t ->
                        val selected = !dynamic && settings.accent == t.name
                        Column(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(18.dp))
                                .background(if (selected) Palette.Surface3 else Palette.Surface2)
                                .then(if (selected) Modifier.border(2.dp, t.color, RoundedCornerShape(18.dp)) else Modifier)
                                .pressable({ onChange { it.copy(accent = t.name, dynamicAccent = false) } }, scaleTo = 0.94f)
                                .padding(vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            BrandMark(40.dp, arcColor = t.color)
                            Spacer(Modifier.height(4.dp))
                            Text(accentLabel(t), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            Column(Modifier.clip(RoundedCornerShape(20.dp)).background(Palette.Surface2)) {
                Toggle(stringResource(R.string.settings_theme_wallpaper_colors), stringResource(R.string.settings_theme_wallpaper_colors_desc), settings.dynamicAccent) { v ->
                    onChange { it.copy(dynamicAccent = v) }
                }
                Toggle(
                    stringResource(R.string.settings_theme_matching_icon),
                    stringResource(R.string.settings_theme_matching_icon_desc),
                    settings.matchIcon,
                ) { v -> onChange { it.copy(matchIcon = v) } }
                Toggle(stringResource(R.string.settings_theme_pure_black), stringResource(R.string.settings_theme_pure_black_desc), settings.pureBlack) { v ->
                    onChange { it.copy(pureBlack = v) }
                }
            }
        }
    }
}

@Composable
private fun Preview() {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Palette.Ink)
            .border(1.dp, Palette.Surface4, RoundedCornerShape(18.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BrandMark(26.dp)
            Spacer(Modifier.width(8.dp))
            Text("Final", style = MaterialTheme.typography.titleMedium)
            Text("(ish)", style = MaterialTheme.typography.titleMedium, color = Palette.TextTertiary)
            Spacer(Modifier.weight(1f))
            Text(
                pluralStringResource(R.plurals.settings_theme_preview_new_comments, 3, 3), style = MaterialTheme.typography.labelMedium, color = Palette.AccentBright,
                modifier = Modifier.clip(CircleShape).background(Palette.AccentContainer).padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Palette.Surface4)) {
            Box(Modifier.fillMaxWidth(0.4f).height(4.dp).clip(CircleShape).background(Palette.Accent))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f).height(38.dp).clip(RoundedCornerShape(12.dp)).background(Palette.Accent), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.settings_theme_preview_comment), style = MaterialTheme.typography.labelLarge, color = Palette.OnAccent)
            }
            Box(Modifier.weight(1f).height(38.dp).clip(RoundedCornerShape(12.dp)).background(Palette.Surface3), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.settings_theme_preview_share), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun Toggle(label: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary)
        }
        Switch(checked, onChange, colors = SwitchDefaults.colors(checkedTrackColor = Palette.Accent, checkedThumbColor = Palette.OnAccent))
    }
}
