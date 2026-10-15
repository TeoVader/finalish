package dev.finalish.ui.browser

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.finalish.R
import dev.finalish.core.data.AppSettings
import dev.finalish.core.data.CardAspect
import dev.finalish.core.data.CardSize
import dev.finalish.ui.components.pressable
import dev.finalish.ui.theme.Palette

private enum class ViewPage { Main, SortField, CardFields }

/**
 * Sort & view for folder grids, mirroring the official Appearance / Fields / Sort popovers:
 * sort by a built-in order or any field, either direction; card look; fields under cards;
 * flatten subfolders.
 */
@Composable
fun AppearanceSheet(
    settings: AppSettings,
    sort: dev.finalish.core.data.SortOrder,
    onSort: (dev.finalish.core.data.SortOrder) -> Unit,
    onChange: ((AppSettings) -> AppSettings) -> Unit,
    onDismiss: () -> Unit,
    fields: List<FieldInfo> = emptyList(),
) {
    var page by remember { mutableStateOf(ViewPage.Main) }
    val fileFields = fields.filter { it.name != NAME_FIELD }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Palette.Surface2,
    ) {
        androidx.activity.compose.BackHandler(page != ViewPage.Main) { page = ViewPage.Main }
        androidx.compose.animation.AnimatedContent(
            page,
            transitionSpec = { dev.finalish.ui.theme.sheetPage(forward = targetState != ViewPage.Main) },
            label = "view",
        ) { p ->
            Column(
                Modifier.navigationBarsPadding().heightIn(max = 640.dp).verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp).padding(bottom = 16.dp),
            ) {
                when (p) {
                    ViewPage.Main -> MainPage(settings, sort, onSort, onChange, fileFields.isNotEmpty()) { page = it }
                    ViewPage.SortField -> {
                        PageHeader(stringResource(R.string.browser_sort_by_field)) { page = ViewPage.Main }
                        fileFields.forEach { f ->
                            CheckLine(f.name, settings.sortField == f.name) {
                                onChange { it.copy(sortField = f.name, sortReversed = false) }
                                page = ViewPage.Main
                            }
                        }
                    }
                    ViewPage.CardFields -> {
                        PageHeader(stringResource(R.string.browser_fields_on_cards)) { page = ViewPage.Main }
                        Text(
                            stringResource(R.string.browser_fields_on_cards_hint),
                            style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary, modifier = Modifier.padding(bottom = 6.dp),
                        )
                        fileFields.forEach { f ->
                            val on = f.name in settings.cardFields
                            OptionRow(f.name) {
                                Switch(
                                    checked = on,
                                    onCheckedChange = { v -> onChange { s -> s.copy(cardFields = if (v) s.cardFields + f.name else s.cardFields - f.name) } },
                                    colors = SwitchDefaults.colors(checkedTrackColor = Palette.Accent, checkedThumbColor = Palette.OnAccent),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MainPage(
    settings: AppSettings,
    sort: dev.finalish.core.data.SortOrder,
    onSort: (dev.finalish.core.data.SortOrder) -> Unit,
    onChange: ((AppSettings) -> AppSettings) -> Unit,
    hasFields: Boolean,
    onPage: (ViewPage) -> Unit,
) {
    Text(stringResource(R.string.browser_sort_and_view), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 12.dp))
    // Six orders don't fit one segmented row: label above, chips that wrap.
    Column(Modifier.padding(vertical = 9.dp)) {
        Text(stringResource(R.string.browser_sort_by), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(bottom = 10.dp))
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            dev.finalish.core.data.SortOrder.entries.forEach { o ->
                Chip(stringResource(o.labelRes), settings.sortField == null && o == sort) {
                    onChange { it.copy(sortField = null, sortReversed = false) }
                    onSort(o)
                }
            }
            if (hasFields || settings.sortField != null) {
                Chip(settings.sortField?.let { "$it ›" } ?: stringResource(R.string.browser_other_field), settings.sortField != null) { onPage(ViewPage.SortField) }
            }
        }
    }
    OptionRow(stringResource(R.string.browser_direction)) {
        val (naturalRes, reverseRes) = directionLabels(settings.sortField, sort)
        val natural = stringResource(naturalRes)
        val reverse = stringResource(reverseRes)
        Segmented(listOf(false, true), settings.sortReversed, { if (it) reverse else natural }) { v -> onChange { it.copy(sortReversed = v) } }
    }
    OptionRow(stringResource(R.string.browser_card_size)) {
        Segmented(CardSize.entries, settings.cardSize, { it.label }) { v -> onChange { it.copy(cardSize = v) } }
    }
    OptionRow(stringResource(R.string.browser_aspect_ratio)) {
        Segmented(CardAspect.entries, settings.cardAspect, { it.label }) { v -> onChange { it.copy(cardAspect = v) } }
    }
    OptionRow(stringResource(R.string.browser_thumbnail_scale)) {
        val fitLabel = stringResource(R.string.browser_thumb_fit)
        val fillLabel = stringResource(R.string.browser_thumb_fill)
        Segmented(listOf(false, true), settings.thumbFit, { if (it) fitLabel else fillLabel }) { v -> onChange { it.copy(thumbFit = v) } }
    }
    OptionRow(stringResource(R.string.browser_titles)) {
        val oneLine = pluralStringResource(R.plurals.browser_title_lines, 1, 1)
        val twoLines = pluralStringResource(R.plurals.browser_title_lines, 2, 2)
        Segmented(listOf(1, 2), settings.titleLines, { if (it > 1) twoLines else oneLine }) { v -> onChange { it.copy(titleLines = v) } }
    }
    OptionRow(stringResource(R.string.browser_show_card_info)) {
        Switch(
            checked = settings.showCardInfo,
            onCheckedChange = { v -> onChange { it.copy(showCardInfo = v) } },
            colors = SwitchDefaults.colors(checkedTrackColor = Palette.Accent, checkedThumbColor = Palette.OnAccent),
        )
    }
    if (hasFields || settings.cardFields.isNotEmpty()) {
        Row(
            Modifier.fillMaxWidth().pressable({ onPage(ViewPage.CardFields) }, scaleTo = 0.98f).padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.browser_fields_on_cards), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                settings.cardFields.size.takeIf { it > 0 }?.let { stringResource(R.string.browser_card_fields_on, it) } ?: stringResource(R.string.browser_none),
                style = MaterialTheme.typography.bodyMedium, color = Palette.TextTertiary,
            )
            androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.ChevronRight, null, tint = Palette.TextTertiary)
        }
    }
    OptionRow(stringResource(R.string.browser_group_sections)) {
        Switch(
            checked = settings.groupSections,
            onCheckedChange = { v -> onChange { it.copy(groupSections = v) } },
            colors = SwitchDefaults.colors(checkedTrackColor = Palette.Accent, checkedThumbColor = Palette.OnAccent),
        )
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.browser_flatten_folders), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.browser_flatten_folders_hint), style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary)
        }
        Switch(
            checked = settings.flattenFolders,
            onCheckedChange = { v -> onChange { it.copy(flattenFolders = v) } },
            colors = SwitchDefaults.colors(checkedTrackColor = Palette.Accent, checkedThumbColor = Palette.OnAccent),
        )
    }
}

/** What the two directions mean for the current order. */
private fun directionLabels(field: String?, sort: dev.finalish.core.data.SortOrder): Pair<Int, Int> = when {
    field != null -> R.string.browser_dir_ascending to R.string.browser_dir_descending
    sort == dev.finalish.core.data.SortOrder.UpdatedDesc || sort == dev.finalish.core.data.SortOrder.CreatedDesc -> R.string.browser_dir_newest to R.string.browser_dir_oldest
    sort == dev.finalish.core.data.SortOrder.SizeDesc -> R.string.browser_dir_largest to R.string.browser_dir_smallest
    else -> R.string.browser_dir_a_z to R.string.browser_dir_z_a
}

@Composable
private fun Chip(label: String, on: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = if (on) Palette.OnAccent else Palette.TextSecondary,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (on) Palette.Accent else Palette.Surface3)
            .pressable(onClick, scaleTo = 0.94f)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun PageHeader(title: String, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().pressable(onBack, scaleTo = 0.98f).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.browser_back), modifier = Modifier.padding(end = 12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun CheckLine(label: String, checked: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().pressable(onClick, scaleTo = 0.985f).padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (checked) androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.Check, null, tint = Palette.Accent)
    }
}

@Composable
private fun OptionRow(label: String, control: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        control()
    }
}

@Composable
private fun <T> Segmented(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Palette.Surface3)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { o ->
            val sel = o == selected
            val bg by animateColorAsState(if (sel) Palette.Accent else Color.Transparent, label = "seg")
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(bg)
                    .pressable({ onSelect(o) }, scaleTo = 0.94f)
                    .padding(horizontal = 11.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label(o), style = MaterialTheme.typography.labelLarge, color = if (sel) Palette.OnAccent else Palette.TextSecondary)
            }
        }
    }
}
