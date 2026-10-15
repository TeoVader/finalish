package dev.finalish.ui.members

import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.finalish.ui.application
import dev.finalish.ui.components.rememberMessageSnackbar
import dev.finalish.ui.messageFlow
import dev.finalish.ui.report
import dev.finalish.ui.container
import dev.finalish.core.data.FrameioRepository
import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.PersonRemove
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.finalish.R
import dev.finalish.core.model.User
import dev.finalish.core.model.UserGroup
import dev.finalish.core.model.UserRole
import dev.finalish.core.network.userMessage
import dev.finalish.ui.components.Avatar
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

/** Emoji a new group can wear; Frame.io requires one. */
val GROUP_EMOJI = listOf("👥", "🎬", "✂️", "🎨", "🎧", "📣", "⭐", "🧑‍💼", "🤝", "🔒")

data class GroupsUiState(
    /** Null until the first load. */
    val groups: List<UserGroup>? = null,
    val error: String? = null,
    /** The account's plan has no user groups. */
    val unavailable: Boolean = false,
)

sealed interface GroupsEvent {
    data class Bind(val accountId: String) : GroupsEvent
    data object Load : GroupsEvent
    data class Create(val name: String, val emoji: String) : GroupsEvent
}

class GroupsViewModel(app: Application, private val repo: FrameioRepository) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(GroupsUiState())
    val state: StateFlow<GroupsUiState> = _state.asStateFlow()
    private val _messages = messageFlow()
    val messages = _messages.asSharedFlow()
    private var account = ""

    fun onEvent(e: GroupsEvent) {
        when (e) {
            is GroupsEvent.Bind -> bind(e.accountId)
            GroupsEvent.Load -> load()
            is GroupsEvent.Create -> create(e.name, e.emoji)
        }
    }

    private fun bind(a: String) {
        if (account == a) return
        account = a
        load()
    }

    private fun load() = viewModelScope.launch {
        _state.update { it.copy(error = null) }
        runCatching { repo.groups(account) }
            .onSuccess { list -> _state.update { it.copy(groups = list) } }
            .onFailure { e ->
                val message = e.userMessage()
                _state.update { it.copy(error = message, unavailable = dev.finalish.core.network.isPlanLimitMessage(message)) }
            }
    }

    private fun create(name: String, emoji: String) = viewModelScope.launch {
        _messages.report({ g -> getApplication<Application>().getString(R.string.members_group_created, g.name) }) {
            repo.createGroup(account, name, emoji)
        }.onSuccess { g -> _state.update { it.copy(groups = (it.groups.orEmpty() + g).sortedBy { x -> x.name.lowercase() }) } }
    }

    companion object {
        val Factory = viewModelFactory { initializer { GroupsViewModel(application, container.repository) } }
    }
}

data class GroupUiState(
    val group: UserGroup? = null,
    /** Null until the first load. */
    val people: List<User>? = null,
    val error: String? = null,
    /** People who could be added; null until asked for. */
    val candidates: List<UserRole>? = null,
    /** The group is gone: the screen closes. */
    val deleted: Boolean = false,
)

sealed interface GroupEvent {
    data class Bind(val accountId: String, val group: UserGroup) : GroupEvent
    data object Load : GroupEvent
    data object LoadCandidates : GroupEvent
    data class Add(val user: User) : GroupEvent
    data class Remove(val user: User) : GroupEvent
    data class Update(val name: String, val emoji: String) : GroupEvent
    data object Delete : GroupEvent
}

class GroupViewModel(app: Application, private val repo: FrameioRepository) : AndroidViewModel(app) {
    private val app get() = getApplication<Application>()
    private val _state = MutableStateFlow(GroupUiState())
    val state: StateFlow<GroupUiState> = _state.asStateFlow()
    private val _messages = messageFlow()
    val messages = _messages.asSharedFlow()
    private var account = ""

    fun onEvent(e: GroupEvent) {
        when (e) {
            is GroupEvent.Bind -> bind(e.accountId, e.group)
            GroupEvent.Load -> load()
            GroupEvent.LoadCandidates -> loadCandidates()
            is GroupEvent.Add -> add(e.user)
            is GroupEvent.Remove -> remove(e.user)
            is GroupEvent.Update -> update(e.name, e.emoji)
            GroupEvent.Delete -> delete()
        }
    }

    private fun bind(a: String, g: UserGroup) {
        if (account == a && _state.value.group?.id == g.id) return
        account = a
        _state.update { it.copy(group = g) }
        load()
    }

    private val id get() = _state.value.group?.id.orEmpty()

    private fun load() = viewModelScope.launch {
        _state.update { it.copy(error = null) }
        runCatching { repo.groupUsers(account, id) }
            .onSuccess { list -> _state.update { it.copy(people = list, group = it.group?.copy(memberCount = list.size)) } }
            .onFailure { e -> _state.update { it.copy(error = e.userMessage()) } }
    }

    private fun loadCandidates() = viewModelScope.launch {
        _messages.report { repo.accountUsers(account) }
            .onSuccess { all ->
                val existing = _state.value.people.orEmpty().mapNotNull { it.id }.toSet()
                _state.update { it.copy(candidates = all.filter { u -> u.user.id != null && u.user.id !in existing }) }
            }
            .onFailure { _state.update { it.copy(candidates = emptyList()) } }
    }

    private fun add(u: User) {
        val uid = u.id ?: return
        viewModelScope.launch {
            _messages.report({ app.getString(R.string.members_added_person, u.displayName) }) { repo.addGroupUsers(account, id, listOf(uid)) }
                .onSuccess { load() }
        }
    }

    private fun remove(u: User) {
        val uid = u.id ?: return
        val before = _state.value.people
        _state.update { s -> s.copy(people = s.people?.filterNot { it.id == uid }) }
        viewModelScope.launch {
            _messages.report({ app.getString(R.string.members_removed_person, u.displayName) }) { repo.removeGroupUser(account, id, uid) }
                .onSuccess { _state.update { s -> s.copy(group = s.group?.let { g -> g.copy(memberCount = (g.memberCount - 1).coerceAtLeast(0)) }) } }
                .onFailure {
                    _state.update { s -> s.copy(people = s.people?.let { l -> dev.finalish.core.util.reinsert(l, before.orEmpty(), u) { x -> x.id } }) }
                }
        }
    }

    private fun update(name: String, emoji: String) = viewModelScope.launch {
        _messages.report { repo.updateGroup(account, id, name, emoji) }
            .onSuccess { g -> _state.update { s -> s.copy(group = s.group?.copy(name = g.name, emoji = g.emoji)) } }
    }

    private fun delete() = viewModelScope.launch {
        _messages.report { repo.deleteGroup(account, id) }.onSuccess { _state.update { it.copy(deleted = true) } }
    }

    companion object {
        val Factory = viewModelFactory { initializer { GroupViewModel(application, container.repository) } }
    }
}

@Composable
fun GroupBadge(group: UserGroup, size: Dp = 38.dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.3f)).background(Palette.Surface2),
        contentAlignment = Alignment.Center,
    ) {
        if (group.emoji.isNotBlank()) Text(group.emoji, fontSize = (size.value * 0.48f).sp)
        else Icon(Icons.Rounded.Groups, null, tint = Palette.TextSecondary, modifier = Modifier.size(size * 0.55f))
    }
}

/** Settings › Groups. */
@Composable
fun GroupsScreen(
    accountId: String,
    accountName: String?,
    onBack: () -> Unit,
    onOpenGroup: (UserGroup) -> Unit,
    vm: GroupsViewModel = viewModel(factory = GroupsViewModel.Factory),
) {
    LaunchedEffect(accountId) { vm.onEvent(GroupsEvent.Bind(accountId)) }
    // Back from a group (renamed, deleted, people added): show it as it is now.
    var resumedBefore by remember { mutableStateOf(false) }
    androidx.lifecycle.compose.LifecycleResumeEffect(accountId) {
        if (resumedBefore) vm.onEvent(GroupsEvent.Load) else resumedBefore = true
        onPauseOrDispose { }
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val groups = state.groups
    val error = state.error
    val unavailable = state.unavailable
    val snackbar = rememberMessageSnackbar(vm.messages)
    var creating by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(Palette.Ink)) {
        Column(Modifier.fillMaxSize()) {
            SimpleTopBar(stringResource(R.string.members_groups), accountName, onBack) {
                if (!unavailable) AccentPill(Icons.Rounded.Add, stringResource(R.string.members_new_group)) { creating = true }
            }
            val list0 = groups
            val stage = when {
                unavailable -> 0
                list0 == null && error != null -> 1
                list0 == null -> 2
                list0.isEmpty() -> 3
                else -> 4
            }
            androidx.compose.animation.Crossfade(stage, animationSpec = dev.finalish.ui.theme.Motion.enter(), label = "groups") { st ->
                val list = list0.orEmpty()
                when (st) {
                    0 -> GroupsUnavailable()
                    1 -> ErrorState(error.orEmpty(), onRetry = { vm.onEvent(GroupsEvent.Load) })
                    2 -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        repeat(5) { Box(Modifier.fillMaxWidth().height(52.dp).shimmer(RoundedCornerShape(14.dp))) }
                    }
                    3 -> EmptyState(Icons.Rounded.Groups, stringResource(R.string.members_no_groups_title), stringResource(R.string.members_no_groups_body))
                    else -> GroupList(list, onOpenGroup)
            
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }
    if (creating) GroupNameSheet(
        title = stringResource(R.string.members_new_group), action = stringResource(R.string.members_create_group),
        onDismiss = { creating = false },
        onDone = { name, emoji -> creating = false; vm.onEvent(GroupsEvent.Create(name, emoji)) },
    )
}

/** Shown instead of an error when the plan has no groups: nothing to retry. */
@Composable
internal fun GroupsUnavailable() = EmptyState(
    Icons.Rounded.Groups,
    stringResource(R.string.members_groups_unavailable_title),
    stringResource(R.string.members_groups_unavailable_body),
)

@Composable
internal fun GroupList(groups: List<UserGroup>, onOpen: (UserGroup) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
        item {
            Text(
                pluralStringResource(R.plurals.members_n_groups, groups.size, groups.size),
                style = MaterialTheme.typography.labelLarge, color = Palette.TextSecondary,
                modifier = Modifier.padding(start = 8.dp, bottom = 6.dp),
            )
        }
        items(groups, key = { it.id }) { g ->
            Row(
                Modifier
                    .animateItem()
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .pressable({ onOpen(g) }, scaleTo = 0.98f)
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GroupBadge(g)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(g.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(g.membersLabel, g.sourceLabel?.let { stringResource(R.string.members_synced_from, it) }).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary,
                    )
                }
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Palette.TextTertiary)
            }
        }
        item(key = "hint") {
            Text(
                stringResource(R.string.members_groups_hint),
                style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
            )
        }
    }
}

/** One group: its people, rename, delete. */
@Composable
fun GroupScreen(
    accountId: String,
    group: UserGroup,
    onBack: () -> Unit,
    vm: GroupViewModel = viewModel(factory = GroupViewModel.Factory, key = "group-${group.id}"),
) {
    LaunchedEffect(accountId, group.id) { vm.onEvent(GroupEvent.Bind(accountId, group)) }
    val state by vm.state.collectAsStateWithLifecycle()
    val g = state.group
    val people = state.people
    val error = state.error
    LaunchedEffect(state.deleted) { if (state.deleted) onBack() }
    val snackbar = rememberMessageSnackbar(vm.messages)
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<User?>(null) }
    val current = g ?: group

    Box(Modifier.fillMaxSize().background(Palette.Ink)) {
        Column(Modifier.fillMaxSize()) {
            SimpleTopBar(current.name, current.membersLabel, onBack) {
                if (!current.synced) AccentPill(Icons.Rounded.PersonAdd, stringResource(R.string.members_add)) { adding = true; vm.onEvent(GroupEvent.LoadCandidates) }
                Box {
                    var menu by remember { mutableStateOf(false) }
                    IconButton(onClick = { menu = true }, modifier = Modifier.padding(end = 4.dp)) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.members_more)) }
                    DropdownMenu(menu, onDismissRequest = { menu = false }, shape = dev.finalish.ui.theme.MenuShape, containerColor = Palette.Surface3) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.members_rename)) },
                            leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                            onClick = { menu = false; editing = true },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.members_delete_group), color = Palette.Red) },
                            leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = Palette.Red) },
                            onClick = { menu = false; deleting = true },
                        )
                    }
                }
            }
            val list0 = people
            val stage = when {
                list0 == null && error != null -> 0
                list0 == null -> 1
                else -> 2
            }
            androidx.compose.animation.Crossfade(stage, animationSpec = dev.finalish.ui.theme.Motion.enter(), label = "people") { st ->
                val list = list0.orEmpty()
                when (st) {
                    0 -> ErrorState(error.orEmpty(), onRetry = { vm.onEvent(GroupEvent.Load) })
                    1 -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        repeat(4) { Box(Modifier.fillMaxWidth().height(52.dp).shimmer(RoundedCornerShape(14.dp))) }
                    }
                    else -> GroupPeople(current, list, onRemove = { removing = it })
            
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }

    if (adding) PickPersonSheet(
        title = stringResource(R.string.members_add_to, current.name),
        candidates = state.candidates,
        onPick = { vm.onEvent(GroupEvent.Add(it.user)); adding = false },
        onDismiss = { adding = false },
    )
    if (editing) GroupNameSheet(
        title = stringResource(R.string.members_rename_group), action = stringResource(R.string.members_save),
        initialName = current.name, initialEmoji = current.emoji,
        onDismiss = { editing = false },
        onDone = { name, emoji -> editing = false; vm.onEvent(GroupEvent.Update(name, emoji)) },
    )
    if (deleting) AlertDialog(
        onDismissRequest = { deleting = false },
        containerColor = Palette.Surface2,
        title = { Text(stringResource(R.string.members_delete_group_title, current.name)) },
        text = { Text(stringResource(R.string.members_delete_group_body), color = Palette.TextSecondary) },
        confirmButton = { TextButton(onClick = { deleting = false; vm.onEvent(GroupEvent.Delete) }) { Text(stringResource(R.string.members_delete_group), color = Palette.Red) } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.members_cancel)) } },
    )
    removing?.let { u ->
        AlertDialog(
            onDismissRequest = { removing = null },
            containerColor = Palette.Surface2,
            title = { Text(stringResource(R.string.members_remove_title, u.displayName)) },
            text = { Text(stringResource(R.string.members_remove_from_group_body, current.name), color = Palette.TextSecondary) },
            confirmButton = { TextButton(onClick = { removing = null; vm.onEvent(GroupEvent.Remove(u)) }) { Text(stringResource(R.string.members_remove), color = Palette.Red) } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.members_cancel)) } },
        )
    }
}

@Composable
internal fun GroupPeople(group: UserGroup, people: List<User>, onRemove: (User) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
        group.sourceLabel?.let { src ->
            item(key = "synced") {
                Row(
                    Modifier.padding(horizontal = 4.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .background(Palette.Surface2).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Sync, null, tint = Palette.TextSecondary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.members_synced_from_detail, src), style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
                }
            }
        }
        if (people.isEmpty()) item(key = "empty") {
            EmptyState(Icons.Rounded.PersonAdd, stringResource(R.string.members_group_empty_title), stringResource(R.string.members_group_empty_body))
        }
        items(people, key = { it.id ?: it.email.orEmpty() }) { u ->
            Row(
                Modifier.animateItem().fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(u, 38.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(u.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(u.email.takeIf { it != u.displayName }, if (u.active == false) stringResource(R.string.members_inactive) else null).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!group.synced && u.id != null) IconButton(onClick = { onRemove(u) }) {
                    Icon(Icons.Rounded.PersonRemove, stringResource(R.string.members_remove_person, u.displayName), tint = Palette.TextTertiary)
                }
            }
        }
    }
}

@Composable
internal fun AccentPill(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .padding(end = 8.dp)
            .clip(RoundedCornerShape(50))
            .background(Palette.Accent)
            .pressable(onClick, scaleTo = 0.92f)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Palette.OnAccent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = Palette.OnAccent)
    }
}

@Composable
private fun GroupNameSheet(
    title: String,
    action: String,
    onDismiss: () -> Unit,
    onDone: (name: String, emoji: String) -> Unit,
    initialName: String = "",
    initialEmoji: String = GROUP_EMOJI.first(),
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Palette.Surface1,
    ) {
        GroupNameForm(title, action, onDone, initialName, initialEmoji)
    }
}

/** Name and emoji of a group (New group / Rename group sheets). */
@Composable
internal fun GroupNameForm(
    title: String,
    action: String,
    onDone: (name: String, emoji: String) -> Unit,
    initialName: String = "",
    initialEmoji: String = GROUP_EMOJI.first(),
) {
    var name by remember { mutableStateOf(initialName) }
    var emoji by remember { mutableStateOf(initialEmoji.ifBlank { GROUP_EMOJI.first() }) }
    Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 4.dp, bottom = 12.dp))
        OutlinedTextField(
            value = name, onValueChange = { name = it },
            label = { Text(stringResource(R.string.members_name)) },
            placeholder = { Text(stringResource(R.string.members_group_name_placeholder)) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        LazyRow(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(GROUP_EMOJI + listOfNotNull(emoji.takeIf { it !in GROUP_EMOJI })) { e ->
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(if (e == emoji) Palette.AccentContainer else Palette.Surface2)
                        .pressable({ emoji = e }, scaleTo = 0.9f),
                    contentAlignment = Alignment.Center,
                ) { Text(e, fontSize = 20.sp) }
            }
        }
        Button(
            onClick = { onDone(name.trim(), emoji) },
            enabled = name.isNotBlank(),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent, contentColor = Palette.OnAccent),
            modifier = Modifier.fillMaxWidth().padding(top = 18.dp).height(50.dp),
        ) { Text(action) }
    }
}

/** People in the account who aren't in the group yet. */
@Composable
private fun PickPersonSheet(title: String, candidates: List<UserRole>?, onPick: (UserRole) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Palette.Surface1,
    ) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 4.dp, bottom = 12.dp))
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.members_search_account_people)) },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            Box(Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 160.dp, max = 420.dp)) {
                val list = candidates
                if (list == null) Text(stringResource(R.string.members_loading), color = Palette.TextTertiary, modifier = Modifier.padding(12.dp))
                else {
                    val q = query.trim().lowercase()
                    val filtered = list.filter { q.isEmpty() || it.user.displayName.lowercase().contains(q) || it.user.email.orEmpty().lowercase().contains(q) }
                    if (filtered.isEmpty()) Text(
                        if (list.isEmpty()) stringResource(R.string.members_everyone_in_group) else stringResource(R.string.members_no_matches),
                        color = Palette.TextTertiary, modifier = Modifier.padding(12.dp),
                    )
                    else LazyColumn {
                        items(filtered, key = { it.user.id!! }) { u ->
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).pressable({ onPick(u) }, scaleTo = 0.98f)
                                    .padding(horizontal = 8.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Avatar(u.user, 34.dp)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(u.user.displayName, style = MaterialTheme.typography.titleSmall)
                                    Text(u.user.email.orEmpty(), style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary)
                                }
                                Box(Modifier.size(28.dp).clip(CircleShape).background(Palette.AccentContainer), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Rounded.PersonAdd, null, tint = Palette.AccentBright, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
