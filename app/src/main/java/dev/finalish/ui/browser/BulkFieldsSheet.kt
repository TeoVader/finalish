package dev.finalish.ui.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import dev.finalish.ui.components.RatingStars
import dev.finalish.ui.components.pressable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.finalish.R
import dev.finalish.core.data.Remote
import dev.finalish.core.model.MetadataField
import dev.finalish.core.model.SelectOption
import dev.finalish.ui.theme.Palette
import dev.finalish.ui.viewer.DateFieldDialog
import dev.finalish.ui.viewer.MetadataFieldRow
import dev.finalish.ui.viewer.MultiSelectSheet
import dev.finalish.ui.viewer.UserPickerSheet
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** What the API takes to set a field ([wire]) and what the row shows afterwards ([shown]). */
internal data class FieldValue(val wire: JsonElement, val shown: JsonElement?)

/** An empty list clears a field of any type. */
private val CLEAR = JsonArray(emptyList())

internal object FieldValues {
    fun select(o: SelectOption?) = FieldValue(
        JsonArray(listOfNotNull(o?.let { JsonPrimitive(it.id) })),
        JsonArray(listOfNotNull(o?.let { buildJsonObject { put("id", it.id); put("display_name", it.displayName) } })),
    )
    fun multi(options: List<SelectOption>) = FieldValue(
        JsonArray(options.map { JsonPrimitive(it.id) }),
        JsonArray(options.map { o -> buildJsonObject { put("id", o.id); put("display_name", o.displayName) } }),
    )
    fun users(ids: List<String>) = JsonArray(ids.map { id -> buildJsonObject { put("id", id); put("type", "user") } }).let { FieldValue(it, it) }
    fun rating(n: Int) = if (n == 0) FieldValue(CLEAR, null) else FieldValue(JsonPrimitive(n), JsonPrimitive(n))
    fun toggle(b: Boolean) = FieldValue(JsonPrimitive(b), JsonPrimitive(b))
    fun text(s: String) = if (s.isBlank()) FieldValue(CLEAR, null) else FieldValue(JsonPrimitive(s), JsonPrimitive(s))
    fun number(n: Double?) = n?.let { FieldValue(JsonPrimitive(it), JsonPrimitive(it)) } ?: FieldValue(CLEAR, null)
    fun date(iso: String?) = iso?.let { FieldValue(JsonPrimitive(it), JsonPrimitive(it)) } ?: FieldValue(CLEAR, null)
}

/**
 * The selection's other metadata fields. Rows start empty (each file may have its own value); a
 * change goes on every selected file at once and the row then shows it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BulkFieldsSheet(
    count: Int,
    skippedFolders: Boolean,
    fields: Remote<FolderViewModel.BulkFields>,
    onSet: (MetadataField, JsonElement) -> Unit,
    onDismiss: () -> Unit,
) {
    // What was set from this sheet, by field, so each row shows its new value.
    var shown by remember { mutableStateOf(emptyMap<String, JsonElement?>()) }
    var editing by remember { mutableStateOf<MetadataField?>(null) }
    var multiEditing by remember { mutableStateOf<MetadataField?>(null) }
    var userEditing by remember { mutableStateOf<MetadataField?>(null) }
    var dateEditing by remember { mutableStateOf<MetadataField?>(null) }
    val people = fields.data?.people.orEmpty()
    val peopleById = remember(people) { people.associateBy { it.id.orEmpty() } }
    fun set(f: MetadataField, v: FieldValue) {
        shown = shown + (f.definitionId to v.shown)
        onSet(f, v.wire)
    }
    fun MetadataField.current() = if (definitionId in shown) copy(value = shown[definitionId]) else this

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Palette.Surface1,
    ) {
        BulkFieldsContent(count, skippedFolders, fields, onDismiss) { base ->
            val f = base.current()
            MetadataFieldRow(
                f, peopleById,
                onText = { editing = f },
                onToggle = { set(f, FieldValues.toggle(it)) },
                onSelect = { set(f, FieldValues.select(it)) },
                onMultiSelect = { multiEditing = f },
                onUsers = { userEditing = f },
                onDate = { dateEditing = f },
                onRating = { set(f, FieldValues.rating(it)) },
            )
        }
    }

    multiEditing?.let { f -> MultiSelectSheet(f, onDone = { set(f, FieldValues.multi(it)) }, onDismiss = { multiEditing = null }) }
    userEditing?.let { f -> UserPickerSheet(f, people, onDone = { set(f, FieldValues.users(it)) }, onDismiss = { userEditing = null }) }
    dateEditing?.let { f -> DateFieldDialog(f, onPick = { set(f, FieldValues.date(it)) }, onDismiss = { dateEditing = null }) }
    editing?.let { f ->
        TextPromptDialog(f.name, f.name, f.textValue.orEmpty(), stringResource(R.string.viewer_save), onDismiss = { editing = null }, allowBlank = true) { v ->
            editing = null
            set(f, if (f.fieldType == "number") FieldValues.number(v.trim().replace(',', '.').toDoubleOrNull()) else FieldValues.text(v))
        }
    }
}

/** Status or Rating for several files at once; the options come from one of the selected files. */
@Composable
internal fun BulkFieldDialog(
    count: Int,
    skippedFolders: Boolean,
    isStatus: Boolean,
    fields: dev.finalish.core.data.Remote<FolderViewModel.BulkFields>,
    onStatus: (dev.finalish.core.model.MetadataField, dev.finalish.core.model.SelectOption?) -> Unit,
    onRating: (dev.finalish.core.model.MetadataField, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.Surface2,
        title = {
            Text(pluralStringResource(if (isStatus) R.plurals.browser_bulk_status_title else R.plurals.browser_bulk_rating_title, count, count))
        },
        text = { BulkFieldDialogContent(skippedFolders, isStatus, fields, onStatus, onRating) },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.browser_cancel)) } },
    )
}

@Composable
private fun BulkStatusRow(label: String, color: Color?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).pressable(onClick, scaleTo = 0.98f).padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(color ?: Palette.TextTertiary))
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = if (color == null) Palette.TextSecondary else Palette.Text)
    }
}

/** The body of [BulkFieldDialog]: the statuses to pick from, or the stars. */
@Composable
internal fun BulkFieldDialogContent(
    skippedFolders: Boolean,
    isStatus: Boolean,
    fields: dev.finalish.core.data.Remote<FolderViewModel.BulkFields>,
    onStatus: (dev.finalish.core.model.MetadataField, dev.finalish.core.model.SelectOption?) -> Unit,
    onRating: (dev.finalish.core.model.MetadataField, Int) -> Unit,
) {
    val field = fields.data?.let { if (isStatus) it.status else it.rating }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when {
            fields.loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                androidx.compose.material3.CircularProgressIndicator(Modifier.size(28.dp), color = Palette.Accent, strokeWidth = 3.dp)
            }
            fields.error != null -> Text(fields.error, color = Palette.TextSecondary)
            field == null -> Text(stringResource(R.string.browser_bulk_field_missing), color = Palette.TextSecondary)
            isStatus -> {
                BulkStatusRow(stringResource(R.string.viewer_no_status), null) { onStatus(field, null) }
                field.options.forEach { opt ->
                    BulkStatusRow(opt.displayName, dev.finalish.ui.theme.statusColor(opt.displayName)) { onStatus(field, opt) }
                }
            }
            else -> {
                Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                    RatingStars(0, size = 36.dp, onChange = { n -> onRating(field, n) })
                }
                TextButton(onClick = { onRating(field, 0) }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text(stringResource(R.string.browser_bulk_clear_rating), color = Palette.TextSecondary)
                }
            }
        }
        if (skippedFolders) Text(
            stringResource(R.string.browser_bulk_folders_skipped),
            style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary, modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** The body of [BulkFieldsSheet]: title, hint, and one [row] per field. */
@Composable
internal fun BulkFieldsContent(
    count: Int,
    skippedFolders: Boolean,
    fields: Remote<FolderViewModel.BulkFields>,
    onDone: () -> Unit,
    row: @Composable (MetadataField) -> Unit,
) {
    Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(pluralStringResource(R.plurals.browser_bulk_fields_title, count, count), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = onDone) { Text(stringResource(R.string.browser_done), color = Palette.AccentBright) }
        }
        Text(
            stringResource(R.string.browser_bulk_fields_hint) + if (skippedFolders) " " + stringResource(R.string.browser_bulk_folders_skipped) else "",
            style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        val others = fields.data?.others.orEmpty()
        when {
            fields.loading -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(28.dp), color = Palette.Accent, strokeWidth = 3.dp)
            }
            fields.error != null -> Text(fields.error, color = Palette.TextSecondary, modifier = Modifier.padding(20.dp))
            others.isEmpty() -> Text(stringResource(R.string.browser_bulk_no_other_fields), color = Palette.TextSecondary, modifier = Modifier.padding(20.dp))
            else -> LazyColumn(
                Modifier.heightIn(max = 520.dp).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(others, key = { it.definitionId }) { row(it) }
            }
        }
    }
}
