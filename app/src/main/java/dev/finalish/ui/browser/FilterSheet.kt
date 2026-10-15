package dev.finalish.ui.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.finalish.R
import dev.finalish.ui.components.pressable
import dev.finalish.ui.theme.Palette

/**
 * Filters, like the official "Filter by": the active filters, "Add filter" → pick a field →
 * pick how to compare and the value.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FilterSheet(
    fields: List<FieldInfo>,
    active: List<FieldFilter>,
    onAdd: (FieldFilter) -> Unit,
    onRemove: (FieldFilter) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    // null: the list; a field without op: choose how; both: done.
    var field by remember { mutableStateOf<FieldInfo?>(null) }
    var picking by remember { mutableStateOf(active.isEmpty()) }
    var query by remember { mutableStateOf("") }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Palette.Surface2,
    ) {
        androidx.activity.compose.BackHandler(picking || field != null) {
            if (field != null) field = null else picking = false
        }
        Column(
            Modifier.navigationBarsPadding().heightIn(max = 620.dp).verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp).padding(bottom = 16.dp),
        ) {
            val f = field
            when {
                f != null -> ConditionPage(f, onBack = { field = null }) { onAdd(it); field = null; picking = false }
                picking -> {
                    Header(stringResource(R.string.browser_filter_by_field), back = active.isNotEmpty()) { picking = false }
                    SearchBox(query) { query = it }
                    fields.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }.forEach { fi ->
                        Row(
                            Modifier.fillMaxWidth().pressable({ field = fi }, scaleTo = 0.985f).padding(vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(fi.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            Text(stringResource(typeLabel(fi.type)), style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary)
                        }
                    }
                }
                else -> {
                    Header(stringResource(R.string.browser_filters), back = false) {}
                    active.forEach { a ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(14.dp)).background(Palette.Surface3)
                                .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.FilterList, null, tint = Palette.TextSecondary, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(a.label(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Icon(
                                Icons.Rounded.Close, stringResource(R.string.browser_remove_filter), tint = Palette.TextSecondary,
                                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(50)).pressable({ onRemove(a) }, scaleTo = 0.88f).padding(10.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { picking = true },
                            colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent, contentColor = Palette.OnAccent),
                        ) {
                            Icon(Icons.Rounded.Add, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.browser_add_filter))
                        }
                        if (active.isNotEmpty()) {
                            androidx.compose.material3.TextButton(onClick = onClear) { Text(stringResource(R.string.browser_clear_all), color = Palette.TextSecondary) }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConditionPage(field: FieldInfo, onBack: () -> Unit, onDone: (FieldFilter) -> Unit) {
    val ops = opsFor(field.type)
    var op by remember { mutableStateOf(ops.first()) }
    var value by remember { mutableStateOf("") }
    Header(field.name, back = true, onBack = onBack)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ops.forEach { o -> Pill(stringResource(o.labelRes), o == op) { op = o } }
    }
    if (op.needsValue) {
        Spacer(Modifier.height(14.dp))
        if (field.options.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                field.options.forEach { o -> Pill(o, o == value) { value = o } }
            }
        } else {
            val numeric = field.type == "number" || field.type == "rating"
            BasicTextField(
                value, { value = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Palette.Text),
                cursorBrush = SolidColor(Palette.Accent),
                keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text),
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.Surface3).padding(14.dp),
                decorationBox = { inner ->
                    Box {
                        if (value.isEmpty()) Text(stringResource(if (numeric) R.string.browser_enter_number else R.string.browser_enter_value), style = MaterialTheme.typography.bodyLarge, color = Palette.TextTertiary)
                        inner()
                    }
                },
            )
        }
    }
    Spacer(Modifier.height(16.dp))
    Button(
        onClick = { onDone(FieldFilter(field.name, op, value.trim())) },
        enabled = !op.needsValue || value.isNotBlank(),
        colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent, contentColor = Palette.OnAccent),
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.browser_apply_filter)) }
}

@Composable
private fun Header(title: String, back: Boolean, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().then(if (back) Modifier.pressable(onBack, scaleTo = 0.98f) else Modifier).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (back) Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.browser_back), modifier = Modifier.padding(end = 12.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun SearchBox(query: String, onChange: (String) -> Unit) {
    BasicTextField(
        query, onChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = Palette.Text),
        cursorBrush = SolidColor(Palette.Accent),
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp).clip(RoundedCornerShape(12.dp)).background(Palette.Surface3).padding(horizontal = 12.dp, vertical = 10.dp),
        decorationBox = { inner ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Search, null, tint = Palette.TextTertiary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Box { if (query.isEmpty()) Text(stringResource(R.string.browser_search_fields), style = MaterialTheme.typography.bodyMedium, color = Palette.TextTertiary); inner() }
            }
        },
    )
}

@Composable
private fun Pill(label: String, on: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = if (on) Palette.OnAccent else Palette.TextSecondary,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) Palette.Accent else Palette.Surface3)
            .pressable(onClick, scaleTo = 0.94f).padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@androidx.annotation.StringRes
private fun typeLabel(type: String): Int = when (type) {
    "number" -> R.string.browser_type_number
    "rating" -> R.string.browser_type_rating
    "select", "select_multi" -> R.string.browser_type_choice
    "toggle" -> R.string.browser_type_toggle
    "date" -> R.string.browser_type_date
    "user_single", "user_multi", "users" -> R.string.browser_type_people
    else -> R.string.browser_type_text
}
