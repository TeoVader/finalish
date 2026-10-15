package dev.finalish.ui.settings

import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.finalish.ui.application
import dev.finalish.ui.components.rememberMessageSnackbar
import dev.finalish.ui.messageFlow
import dev.finalish.ui.report
import dev.finalish.ui.container
import dev.finalish.core.data.FrameioRepository
import androidx.compose.foundation.verticalScroll
import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.automirrored.rounded.ShortText
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDropDownCircle
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Numbers
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material.icons.rounded.ToggleOn
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.finalish.R
import dev.finalish.core.model.FIELD_TYPES
import dev.finalish.core.model.FieldDefinition
import dev.finalish.core.model.fieldTypeLabel
import dev.finalish.core.network.userMessage
import dev.finalish.ui.components.EmptyState
import dev.finalish.ui.components.ErrorState
import dev.finalish.ui.components.pressable
import dev.finalish.ui.components.shimmer
import dev.finalish.ui.shares.SimpleTopBar
import dev.finalish.ui.theme.Palette
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MetadataFieldsUiState(
    /** Null until the first load. */
    val fields: List<FieldDefinition>? = null,
    val error: String? = null,
)

sealed interface MetadataFieldsEvent {
    data class Bind(val accountId: String) : MetadataFieldsEvent
    data object Load : MetadataFieldsEvent
    data class Create(val type: String, val name: String, val options: List<String>) : MetadataFieldsEvent
    data class Rename(val field: FieldDefinition, val name: String) : MetadataFieldsEvent
    data class Delete(val field: FieldDefinition) : MetadataFieldsEvent
}

class MetadataFieldsViewModel(app: Application, private val repo: FrameioRepository) : AndroidViewModel(app) {
    private val app get() = getApplication<Application>()
    private val _state = MutableStateFlow(MetadataFieldsUiState())
    val state: StateFlow<MetadataFieldsUiState> = _state.asStateFlow()
    private val _messages = messageFlow()
    val messages = _messages.asSharedFlow()
    private var account = ""

    fun onEvent(e: MetadataFieldsEvent) {
        when (e) {
            is MetadataFieldsEvent.Bind -> bind(e.accountId)
            MetadataFieldsEvent.Load -> load()
            is MetadataFieldsEvent.Create -> create(e.type, e.name, e.options)
            is MetadataFieldsEvent.Rename -> rename(e.field, e.name)
            is MetadataFieldsEvent.Delete -> delete(e.field)
        }
    }

    private fun bind(a: String) {
        if (account == a) return
        account = a
        load()
    }

    private fun updateFields(block: (List<FieldDefinition>) -> List<FieldDefinition>) = _state.update { it.copy(fields = it.fields?.let(block)) }

    private fun load() = viewModelScope.launch {
        _state.update { it.copy(error = null) }
        runCatching { repo.fieldDefinitions(account) }
            .onSuccess { list -> _state.update { it.copy(fields = list) } }
            .onFailure { e -> _state.update { it.copy(error = e.userMessage(app)) } }
    }

    private fun create(type: String, name: String, options: List<String>) = viewModelScope.launch {
        _messages.report({ f -> app.getString(R.string.settings_metadata_field_added, f.name) }, { it.userMessage(app) }) {
            repo.createFieldDefinition(account, type, name, options)
        }.onSuccess { f -> _state.update { it.copy(fields = it.fields.orEmpty() + f) } }
    }

    private fun rename(field: FieldDefinition, name: String) = viewModelScope.launch {
        _messages.report(error = { it.userMessage(app) }) { repo.renameFieldDefinition(account, field, name) }
            .onSuccess { f -> updateFields { list -> list.map { if (it.id == f.id) f.copy(creator = it.creator) else it } } }
    }

    private fun delete(field: FieldDefinition) {
        val before = _state.value.fields
        updateFields { list -> list.filterNot { it.id == field.id } }
        viewModelScope.launch {
            _messages.report({ app.getString(R.string.settings_metadata_field_deleted, field.name) }, { it.userMessage(app) }) {
                repo.deleteFieldDefinition(account, field.id)
            }.onFailure { updateFields { l -> dev.finalish.core.util.reinsert(l, before.orEmpty(), field) { x -> x.id } } }
        }
    }

    companion object {
        val Factory = viewModelFactory { initializer { MetadataFieldsViewModel(application, container.repository) } }
    }
}

fun fieldTypeIcon(type: String): ImageVector = when (type) {
    "text" -> Icons.AutoMirrored.Rounded.ShortText
    "long_text" -> Icons.AutoMirrored.Rounded.Notes
    "select" -> Icons.Rounded.ArrowDropDownCircle
    "select_multi" -> Icons.Rounded.Checklist
    "number" -> Icons.Rounded.Numbers
    "date" -> Icons.Rounded.CalendarToday
    "rating" -> Icons.Rounded.StarOutline
    "toggle" -> Icons.Rounded.ToggleOn
    "user_single" -> Icons.Rounded.Person
    "user_multi" -> Icons.Rounded.Group
    else -> Icons.Rounded.Tune
}

@Composable
fun MetadataFieldsScreen(
    accountId: String,
    accountName: String?,
    onBack: () -> Unit,
    vm: MetadataFieldsViewModel = viewModel(factory = MetadataFieldsViewModel.Factory),
) {
    LaunchedEffect(accountId) { vm.onEvent(MetadataFieldsEvent.Bind(accountId)) }
    val state by vm.state.collectAsStateWithLifecycle()
    val fields = state.fields
    val error = state.error
    val snackbar = rememberMessageSnackbar(vm.messages)
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<FieldDefinition?>(null) }
    var deleting by remember { mutableStateOf<FieldDefinition?>(null) }

    Box(Modifier.fillMaxSize().background(Palette.Ink)) {
        Column(Modifier.fillMaxSize()) {
            SimpleTopBar(stringResource(R.string.settings_metadata_fields), accountName, onBack) {
                dev.finalish.ui.members.AccentPill(Icons.Rounded.Add, stringResource(R.string.settings_metadata_new_field)) { creating = true }
            }
            val list0 = fields
            val stage = when {
                list0 == null && error != null -> 0
                list0 == null -> 1
                else -> 2
            }
            androidx.compose.animation.Crossfade(stage, animationSpec = dev.finalish.ui.theme.Motion.enter(), label = "fields") { st ->
                val list = list0.orEmpty()
                when (st) {
                    0 -> ErrorState(error.orEmpty(), onRetry = { vm.onEvent(MetadataFieldsEvent.Load) })
                    1 -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        repeat(6) { Box(Modifier.fillMaxWidth().height(52.dp).shimmer(RoundedCornerShape(14.dp))) }
                    }
                    else -> MetadataFieldList(list, onOpen = { editing = it })
            
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }

    if (creating) NewFieldSheet(
        onDismiss = { creating = false },
        onCreate = { type, name, options -> creating = false; vm.onEvent(MetadataFieldsEvent.Create(type, name, options)) },
    )
    editing?.let { f ->
        EditFieldSheet(
            f,
            onDismiss = { editing = null },
            onRename = { editing = null; vm.onEvent(MetadataFieldsEvent.Rename(f, it)) },
            onDelete = { editing = null; deleting = f },
        )
    }
    deleting?.let { f ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            containerColor = Palette.Surface2,
            title = { Text(stringResource(R.string.settings_metadata_delete_title, f.name)) },
            text = {
                Text(
                    stringResource(R.string.settings_metadata_delete_body),
                    color = Palette.TextSecondary,
                )
            },
            confirmButton = { TextButton(onClick = { deleting = null; vm.onEvent(MetadataFieldsEvent.Delete(f)) }) { Text(stringResource(R.string.settings_metadata_delete_field), color = Palette.Red) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.settings_cancel)) } },
        )
    }
}

/** Custom fields first, then Frame.io's built-in ones (read-only). */
@Composable
internal fun MetadataFieldList(fields: List<FieldDefinition>, onOpen: (FieldDefinition) -> Unit) {
    val custom = fields.filter { it.mutable }
    val builtIn = fields.filterNot { it.mutable }
    var showBuiltIn by remember { mutableStateOf(builtIn.size <= 8) }
    LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
        item(key = "h-custom") { ListHeader(stringResource(R.string.settings_metadata_custom_fields)) }
        if (custom.isEmpty()) item(key = "empty") {
            EmptyState(Icons.Rounded.Tune, stringResource(R.string.settings_metadata_empty_title), stringResource(R.string.settings_metadata_empty_body))
        }
        items(custom, key = { it.id }) { f -> FieldRow(f, onClick = { onOpen(f) }, modifier = Modifier.animateItem()) }
        if (builtIn.isNotEmpty()) {
            // Frame.io has dozens (codec, bit depth…): folded away unless asked for.
            item(key = "h-builtin") {
                Row(
                    Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .pressable({ showBuiltIn = !showBuiltIn }, scaleTo = 0.98f).padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f)) { ListHeader(stringResource(R.string.settings_metadata_built_in_count, builtIn.size), top = 6.dp) }
                    Text(if (showBuiltIn) stringResource(R.string.settings_metadata_hide) else stringResource(R.string.settings_metadata_show), style = MaterialTheme.typography.labelLarge, color = Palette.AccentBright)
                }
            }
            if (showBuiltIn) items(builtIn, key = { it.id }) { f -> FieldRow(f, onClick = null, modifier = Modifier.animateItem()) }
        }
        item(key = "hint") {
            Text(
                stringResource(R.string.settings_metadata_fields_hint),
                style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
            )
        }
    }
}

@Composable
private fun ListHeader(text: String, top: androidx.compose.ui.unit.Dp = 0.dp) = Text(
    text,
    style = MaterialTheme.typography.labelLarge,
    color = Palette.TextSecondary,
    modifier = Modifier.padding(start = 8.dp, top = top, bottom = 6.dp),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FieldRow(f: FieldDefinition, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .then(if (onClick != null) Modifier.pressable(onClick, scaleTo = 0.98f) else Modifier)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(Palette.Surface2), contentAlignment = Alignment.Center) {
            Icon(fieldTypeIcon(f.type), null, tint = if (f.mutable) Palette.AccentBright else Palette.TextTertiary, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(f.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(f.summary(androidx.compose.ui.platform.LocalContext.current), style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (f.options.isNotEmpty()) {
                FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    f.options.take(5).forEach { OptionChip(it) }
                    if (f.options.size > 5) OptionChip("+${f.options.size - 5}")
                }
            }
        }
        if (!f.mutable) Icon(Icons.Rounded.Lock, stringResource(R.string.settings_metadata_built_in), tint = Palette.TextTertiary, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun OptionChip(text: String, onRemove: (() -> Unit)? = null) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(Palette.Surface3)
            .padding(start = 10.dp, end = if (onRemove != null) 4.dp else 10.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = Palette.TextSecondary)
        if (onRemove != null) Icon(
            Icons.Rounded.Close, stringResource(R.string.settings_metadata_remove_option, text), tint = Palette.TextTertiary,
            modifier = Modifier.padding(start = 2.dp).size(18.dp).clip(RoundedCornerShape(50)).pressable(onRemove),
        )
    }
}

@Composable
private fun NewFieldSheet(onDismiss: () -> Unit, onCreate: (type: String, name: String, options: List<String>) -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Palette.Surface1,
    ) {
        NewFieldForm(onCreate)
    }
}

/** The body of the New field sheet (also used by the screenshot test). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NewFieldForm(
    onCreate: (type: String, name: String, options: List<String>) -> Unit,
    initialType: String = "text",
    initialName: String = "",
    initialOptions: List<String> = emptyList(),
) {
    var type by remember { mutableStateOf(initialType) }
    var name by remember { mutableStateOf(initialName) }
    val options = remember { mutableStateListOf<String>().apply { addAll(initialOptions) } }
    var option by remember { mutableStateOf("") }
    val isSelect = type == "select" || type == "select_multi"
    val addOption = {
        val o = option.trim()
        if (o.isNotEmpty() && o !in options) options += o
        option = ""
    }
    Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
        Text(stringResource(R.string.settings_metadata_new_field), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 4.dp, bottom = 12.dp))
        OutlinedTextField(
            value = name, onValueChange = { name = it },
            label = { Text(stringResource(R.string.settings_metadata_name)) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(stringResource(R.string.settings_metadata_type), style = MaterialTheme.typography.labelLarge, color = Palette.TextSecondary, modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FIELD_TYPES.forEach { t ->
                val on = t == type
                Row(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (on) Palette.AccentContainer else Palette.Surface2)
                        .pressable({ type = t }, scaleTo = 0.94f)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(fieldTypeIcon(t), null, tint = if (on) Palette.AccentBright else Palette.TextTertiary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(fieldTypeLabel(androidx.compose.ui.platform.LocalContext.current, t), style = MaterialTheme.typography.labelLarge, color = if (on) Palette.AccentBright else Palette.TextSecondary)
                }
            }
        }
        if (isSelect) {
            Text(stringResource(R.string.settings_metadata_options), style = MaterialTheme.typography.labelLarge, color = Palette.TextSecondary, modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 8.dp))
            if (options.isNotEmpty()) FlowRow(
                Modifier.padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                options.forEach { o -> OptionChip(o, onRemove = { options.remove(o) }) }
            }
            OutlinedTextField(
                value = option, onValueChange = { option = it },
                placeholder = { Text(stringResource(R.string.settings_metadata_add_an_option)) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { addOption() }),
                trailingIcon = {
                    if (option.isNotBlank()) Icon(Icons.Rounded.Add, stringResource(R.string.settings_metadata_add_option), modifier = Modifier.clip(RoundedCornerShape(50)).pressable(addOption))
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(
            when (type) {
                "rating" -> stringResource(R.string.settings_metadata_hint_rating)
                "user_single", "user_multi" -> stringResource(R.string.settings_metadata_hint_user)
                else -> stringResource(R.string.settings_metadata_hint_default)
            },
            style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary,
            modifier = Modifier.padding(start = 4.dp, top = 12.dp),
        )
        val ready = name.isNotBlank() && (!isSelect || options.isNotEmpty() || option.isNotBlank())
        Button(
            onClick = {
                val all = if (isSelect && option.isNotBlank()) options + option.trim() else options.toList()
                onCreate(type, name.trim(), if (isSelect) all.distinct() else emptyList())
            },
            enabled = ready,
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent, contentColor = Palette.OnAccent),
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp).height(50.dp),
        ) { Text(stringResource(R.string.settings_metadata_create_field)) }
    }
}

@Composable
private fun EditFieldSheet(field: FieldDefinition, onDismiss: () -> Unit, onRename: (String) -> Unit, onDelete: () -> Unit) {
    var name by remember { mutableStateOf(field.name) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Palette.Surface1,
    ) {
        Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()).navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            Row(Modifier.padding(start = 4.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(fieldTypeIcon(field.type), null, tint = Palette.AccentBright, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text(field.summary(androidx.compose.ui.platform.LocalContext.current), style = MaterialTheme.typography.titleMedium, color = Palette.TextSecondary)
            }
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text(stringResource(R.string.settings_metadata_name)) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            if (field.options.isNotEmpty()) {
                Text(
                    stringResource(R.string.settings_metadata_edit_options_hint, field.options.joinToString(", ")),
                    style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary,
                    modifier = Modifier.padding(start = 4.dp, top = 10.dp),
                )
            }
            Button(
                onClick = { onRename(name.trim()) },
                enabled = name.isNotBlank() && name.trim() != field.name,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent, contentColor = Palette.OnAccent),
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp).height(50.dp),
            ) { Text(stringResource(R.string.settings_save)) }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .pressable(onDelete, scaleTo = 0.98f)
                    .padding(vertical = 14.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.DeleteOutline, null, tint = Palette.Red, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.settings_metadata_delete_field), style = MaterialTheme.typography.labelLarge, color = Palette.Red)
            }
        }
    }
}
