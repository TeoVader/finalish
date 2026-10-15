package dev.finalish.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.finalish.core.model.MetadataField
import dev.finalish.core.model.SelectOption
import dev.finalish.core.model.User
import dev.finalish.core.util.Format
import dev.finalish.ui.components.Avatar
import androidx.compose.ui.res.stringResource
import dev.finalish.R
import dev.finalish.ui.components.RatingStars
import dev.finalish.ui.components.pressable
import dev.finalish.ui.theme.Palette
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

private val USER_TYPES = setOf("user_single", "user_multi", "users")

/** User ids referenced by a user field. */
fun MetadataField.userIds(): List<String> =
    (value as? JsonArray)?.mapNotNull { runCatching { it.jsonObject["id"]?.jsonPrimitive?.content }.getOrNull() }.orEmpty()

/** True when a field has nothing worth showing. */
fun MetadataField.isEmpty(): Boolean = when {
    fieldType in USER_TYPES -> userIds().isEmpty()
    fieldType == "select" || fieldType == "select_multi" -> selectedOptions.isEmpty()
    else -> textValue.isNullOrBlank()
}

/** Human formatting for technical numeric fields (by field name). */
fun MetadataField.prettyValue(): String? {
    val n = name.lowercase()
    val d = doubleValue
    if (fieldType == "number" && d != null) return when {
        "duration" in n -> Format.clock((d * 1000).toLong())
        "file size" in n -> Format.bytes(d.toLong())
        // Audio bit rates are in the hundreds of kbps; "0.3 Mbps" reads badly.
        "bit rate" in n -> if (d < 1_000_000) "${(d / 1000).toLong()} kbps" else String.format(Locale.US, "%.1f Mbps", d / 1_000_000)
        "sample rate" in n -> String.format(Locale.US, "%.1f kHz", d / 1000)
        "frame rate" in n -> String.format(Locale.US, "%.3f fps", d).replace(Regex("\\.?0+ fps$"), " fps")
        // Lossy formats (MP3, AAC) have no bit depth: Frame.io sends 0.
        "bit depth" in n -> if (d > 0) "${d.toLong()}-bit" else "—"
        "start time" in n || "end time" in n -> Format.clock((d * 1000).toLong())
        "count" in n || "channels" in n || "width" in n || "height" in n -> d.toLong().toString()
        d % 1.0 == 0.0 -> d.toLong().toString()
        else -> d.toString()
    }
    if (fieldType == "date") return textValue?.let { formatDate(it) }
    if (fieldType == "toggle") return when (boolValue) { true -> "Yes"; false -> "No"; null -> null }
    return textValue
}

private fun formatDate(iso: String): String = runCatching {
    val instant = runCatching { Instant.parse(iso) }.getOrElse { LocalDate.parse(iso.take(10)).atStartOfDay().toInstant(ZoneOffset.UTC) }
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withZone(ZoneOffset.UTC).format(instant)
}.getOrDefault(iso)

/** One metadata row; picks the right editor for the field type. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MetadataFieldRow(
    f: MetadataField,
    people: Map<String, User>,
    onText: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onSelect: (SelectOption?) -> Unit,
    onMultiSelect: () -> Unit,
    onUsers: () -> Unit,
    onDate: () -> Unit,
    onRating: (Int) -> Unit,
) {
    val editable = f.mutable && when (f.fieldType) {
        "select", "select_multi" -> f.options.isNotEmpty()
        else -> true
    }
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .pressable(
                {
                    when (f.fieldType) {
                        "text", "long_text", "number" -> onText()
                        "select" -> menu = true
                        "select_multi" -> onMultiSelect()
                        "date" -> onDate()
                        in USER_TYPES -> onUsers()
                    }
                },
                enabled = editable && f.fieldType !in setOf("toggle", "rating"),
                scaleTo = 0.99f,
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            f.name, style = MaterialTheme.typography.bodyMedium, color = Palette.TextTertiary,
            modifier = Modifier.width(120.dp), maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        Box(Modifier.weight(1f)) {
            when (f.fieldType) {
                "toggle" -> if (!f.mutable) Text(when (f.boolValue) { true -> stringResource(R.string.viewer_yes); false -> stringResource(R.string.viewer_no); null -> "—" }, style = MaterialTheme.typography.bodyMedium) else Switch(
                    checked = f.boolValue == true, onCheckedChange = onToggle, enabled = f.mutable,
                    colors = SwitchDefaults.colors(checkedTrackColor = Palette.Accent, checkedThumbColor = Palette.OnAccent),
                )
                "rating" -> RatingStars(f.intValue ?: 0, onChange = if (f.mutable) onRating else null)
                in USER_TYPES -> {
                    val ids = f.userIds()
                    if (ids.isEmpty()) Placeholder(editable, stringResource(R.string.viewer_field_assign))
                    else FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ids.forEach { id -> PersonChip(people[id] ?: User(id = id, name = stringResource(R.string.viewer_unknown_user))) }
                    }
                }
                "select_multi" -> {
                    val sel = f.selectedOptions
                    if (sel.isEmpty()) Placeholder(editable, stringResource(R.string.viewer_field_choose))
                    else FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        sel.forEach { Tag(it.displayName) }
                    }
                }
                else -> {
                    val v = f.prettyValue()
                    if (v.isNullOrBlank()) Placeholder(
                        editable,
                        when (f.fieldType) { "date" -> stringResource(R.string.viewer_field_set_date); "select" -> stringResource(R.string.viewer_field_choose); else -> stringResource(R.string.viewer_field_add) },
                    )
                    else Row(verticalAlignment = Alignment.CenterVertically) {
                        if (f.fieldType == "date") {
                            Icon(Icons.Rounded.CalendarMonth, null, tint = Palette.TextTertiary, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(v, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            androidx.compose.material3.DropdownMenu(menu, onDismissRequest = { menu = false }, shape = dev.finalish.ui.theme.MenuShape) {
                f.options.forEach { o ->
                    androidx.compose.material3.DropdownMenuItem(text = { Text(o.displayName) }, onClick = { menu = false; onSelect(o) })
                }
                androidx.compose.material3.DropdownMenuItem(text = { Text(stringResource(R.string.viewer_clear), color = Palette.TextTertiary) }, onClick = { menu = false; onSelect(null) })
            }
        }
        if (editable && f.fieldType in setOf("select_multi", "date") + USER_TYPES) {
            Icon(Icons.Rounded.ChevronRight, null, tint = Palette.TextTertiary, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun Placeholder(editable: Boolean, action: String) {
    Text(
        if (editable) action else "—",
        style = MaterialTheme.typography.bodyMedium,
        color = if (editable) Palette.AccentBright else Palette.TextTertiary,
    )
}

@Composable
private fun PersonChip(u: User) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Palette.Surface3).padding(start = 3.dp, end = 10.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(u, 20.dp)
        Spacer(Modifier.width(6.dp))
        Text(u.displayName, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

@Composable
private fun Tag(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(Palette.AccentContainer).padding(horizontal = 10.dp, vertical = 4.dp),
        color = Palette.AccentBright,
    )
}

/** Multi-choice picker for select_multi fields. */
@Composable
fun MultiSelectSheet(f: MetadataField, onDone: (List<SelectOption>) -> Unit, onDismiss: () -> Unit) {
    var chosen by remember { mutableStateOf(f.selectedOptions.map { it.id }.toSet()) }
    PickerSheet(f.name, onDismiss, onDone = { onDone(f.options.filter { it.id in chosen }) }) {
        items(f.options, key = { it.id }) { o ->
            val on = o.id in chosen
            CheckLine(o.displayName, on, multi = true) { chosen = if (on) chosen - o.id else chosen + o.id }
        }
    }
}

/** People picker for user fields (single or multiple). */
@Composable
fun UserPickerSheet(f: MetadataField, people: List<User>, onDone: (List<String>) -> Unit, onDismiss: () -> Unit) {
    val multi = f.fieldType != "user_single"
    var chosen by remember { mutableStateOf(f.userIds().toSet()) }
    PickerSheet(f.name, onDismiss, onDone = { onDone(chosen.toList()) }) {
        if (people.isEmpty()) item { Text(stringResource(R.string.viewer_loading_people), color = Palette.TextTertiary, modifier = Modifier.padding(16.dp)) }
        items(people.filter { it.id != null }, key = { it.id!! }) { u ->
            val on = u.id in chosen
            Row(
                Modifier
                    .fillMaxWidth()
                    .pressable({ chosen = if (multi) (if (on) chosen - u.id!! else chosen + u.id!!) else (if (on) emptySet() else setOf(u.id!!)) }, scaleTo = 0.98f)
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(u, 32.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(u.displayName, style = MaterialTheme.typography.bodyLarge)
                    Text(u.email.orEmpty(), style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary)
                }
                SelectMark(on, multi)
            }
        }
    }
}

@Composable
private fun PickerSheet(
    title: String,
    onDismiss: () -> Unit,
    onDone: () -> Unit,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Palette.Surface1,
    ) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { onDone(); onDismiss() }) { Text(stringResource(R.string.viewer_done), color = Palette.AccentBright) }
            }
            LazyColumn(Modifier.heightIn(max = 480.dp), content = content)
        }
    }
}

@Composable
private fun CheckLine(label: String, on: Boolean, multi: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().pressable(onClick, scaleTo = 0.98f).padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        SelectMark(on, multi)
    }
}

@Composable
private fun SelectMark(on: Boolean, multi: Boolean) {
    Icon(
        when {
            multi && on -> Icons.Rounded.CheckBox
            multi -> Icons.Rounded.CheckBoxOutlineBlank
            on -> Icons.Rounded.RadioButtonChecked
            else -> Icons.Rounded.RadioButtonUnchecked
        },
        null,
        tint = if (on) Palette.Accent else Palette.TextTertiary,
    )
}

/** Calendar for date fields; returns an ISO-8601 instant at midnight UTC, or null to clear. */
@Composable
fun DateFieldDialog(f: MetadataField, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    val initial = f.textValue?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
    val state = rememberDatePickerState(initialSelectedDateMillis = initial)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                onPick(state.selectedDateMillis?.let { Instant.ofEpochMilli(it).toString() })
                onDismiss()
            }) { Text(stringResource(R.string.viewer_save)) }
        },
        dismissButton = {
            Row {
                if (f.textValue != null) TextButton(onClick = { onPick(null); onDismiss() }) { Text(stringResource(R.string.viewer_clear), color = Palette.Red) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.viewer_cancel)) }
            }
        },
        colors = DatePickerDefaults.colors(containerColor = Palette.Surface2),
    ) {
        DatePicker(
            state = state,
            colors = DatePickerDefaults.colors(
                containerColor = Palette.Surface2,
                selectedDayContainerColor = Palette.Accent,
                todayDateBorderColor = Palette.Accent,
                todayContentColor = Palette.AccentBright,
            ),
        )
    }
}
