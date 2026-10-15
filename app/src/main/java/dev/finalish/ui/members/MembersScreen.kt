package dev.finalish.ui.members

import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.finalish.ui.application
import dev.finalish.ui.components.rememberMessageSnackbar
import dev.finalish.ui.messageFlow
import dev.finalish.ui.report
import dev.finalish.ui.container
import dev.finalish.core.data.FrameioRepository
import dev.finalish.core.util.reinsert
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.PersonRemove
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.finalish.R
import dev.finalish.core.model.GroupRole
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


/** Roles that can be assigned through the API, per scope. */
fun assignableRoles(scope: String): List<String> =
    if (scope == "workspaces") listOf("workspace_admin", "full_access", "editor", "edit_only", "commenter", "viewer")
    else listOf("full_access", "editor", "edit_only", "commenter", "viewer")

private fun roleLabelRes(role: String): Int? = when (role) {
    "owner" -> R.string.members_role_owner
    "admin" -> R.string.members_role_account_admin
    "workspace_admin" -> R.string.members_role_workspace_admin
    "full_access" -> R.string.members_role_full_access
    "editor" -> R.string.members_role_editor
    "edit_only" -> R.string.members_role_edit_only
    "commenter" -> R.string.members_role_commenter
    "viewer" -> R.string.members_role_viewer
    "member" -> R.string.members_role_member
    "reviewer" -> R.string.members_role_reviewer
    else -> null
}

fun roleLabel(context: android.content.Context, role: String?): String = when (role) {
    null -> "—"
    else -> roleLabelRes(role)?.let { context.getString(it) } ?: role.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

@Composable
fun roleLabel(role: String?): String = roleLabel(LocalContext.current, role)

private fun roleDescriptionRes(role: String): Int? = when (role) {
    "workspace_admin" -> R.string.members_role_workspace_admin_desc
    "full_access" -> R.string.members_role_full_access_desc
    "editor" -> R.string.members_role_editor_desc
    "edit_only" -> R.string.members_role_edit_only_desc
    "commenter" -> R.string.members_role_commenter_desc
    "viewer" -> R.string.members_role_viewer_desc
    else -> null
}

data class MembersUiState(
    /** Null until the first load. */
    val members: List<UserRole>? = null,
    val error: String? = null,
    /** People who could be added; null until asked for. */
    val candidates: List<UserRole>? = null,
    /** Groups with a role here (projects and workspaces only). */
    val groups: List<GroupRole> = emptyList(),
    val groupCandidates: List<UserGroup> = emptyList(),
)

sealed interface MembersEvent {
    data class Bind(val accountId: String, val scope: String, val id: String) : MembersEvent
    data object Load : MembersEvent
    data object LoadCandidates : MembersEvent
    data class SetRole(val member: UserRole, val role: String) : MembersEvent
    data class Add(val user: UserRole, val role: String) : MembersEvent
    data class Remove(val member: UserRole) : MembersEvent
    data class SetGroupRole(val group: GroupRole, val role: String) : MembersEvent
    data class AddGroup(val group: UserGroup, val role: String) : MembersEvent
    data class RemoveGroup(val group: GroupRole) : MembersEvent
}

class MembersViewModel(app: Application, private val repo: FrameioRepository) : AndroidViewModel(app) {
    private val app get() = getApplication<Application>()
    private val _state = MutableStateFlow(MembersUiState())
    val state: StateFlow<MembersUiState> = _state.asStateFlow()
    private val _messages = messageFlow()
    val messages = _messages.asSharedFlow()
    private var account = ""
    private var scope = ""
    private var id = ""

    fun onEvent(e: MembersEvent) {
        when (e) {
            is MembersEvent.Bind -> bind(e.accountId, e.scope, e.id)
            MembersEvent.Load -> load()
            MembersEvent.LoadCandidates -> loadCandidates()
            is MembersEvent.SetRole -> setRole(e.member, e.role)
            is MembersEvent.Add -> add(e.user, e.role)
            is MembersEvent.Remove -> remove(e.member)
            is MembersEvent.SetGroupRole -> setGroupRole(e.group, e.role)
            is MembersEvent.AddGroup -> addGroup(e.group, e.role)
            is MembersEvent.RemoveGroup -> removeGroup(e.group)
        }
    }

    private fun bind(a: String, s: String, i: String) {
        if (account == a && scope == s && id == i) return
        account = a; scope = s; id = i
        load()
    }

    private fun updateMembers(block: (List<UserRole>) -> List<UserRole>) = _state.update { it.copy(members = it.members?.let(block)) }
    private fun updateGroups(block: (List<GroupRole>) -> List<GroupRole>) = _state.update { it.copy(groups = block(it.groups)) }

    private fun load() = viewModelScope.launch {
        _state.update { it.copy(error = null) }
        runCatching { repo.members(account, scope, id) }
            .onSuccess { list -> _state.update { it.copy(members = list) } }
            .onFailure { e -> _state.update { it.copy(error = e.userMessage()) } }
        // Older accounts or plans without groups: just no section.
        runCatching { repo.scopeGroups(account, scope, id) }.onSuccess { list -> _state.update { it.copy(groups = list) } }
    }

    private fun loadCandidates() = viewModelScope.launch {
        if (scope != "folders") launch {
            runCatching { repo.groups(account) }.onSuccess { all ->
                val existing = _state.value.groups.map { it.group.id }.toSet()
                _state.update { it.copy(groupCandidates = all.filter { g -> g.id !in existing }) }
            }
        }
        _messages.report { repo.accountUsers(account) }
            .onSuccess { all ->
                val existing = _state.value.members.orEmpty().mapNotNull { it.user.id }.toSet()
                _state.update { it.copy(candidates = all.filter { u -> u.user.id != null && u.user.id !in existing }) }
            }
            .onFailure { _state.update { it.copy(candidates = emptyList()) } }
    }

    private fun setRole(m: UserRole, role: String) {
        val uid = m.user.id ?: return
        updateMembers { list -> list.map { if (it.user.id == uid) it.copy(role = role) else it } }
        viewModelScope.launch {
            _messages.report({ app.getString(R.string.members_is_now_role, m.user.displayName, roleLabel(app, role)) }) {
                repo.setMemberRole(account, scope, id, uid, role)
            }
                // Undo only this change; others made meanwhile stay.
                .onFailure { updateMembers { list -> list.map { x -> if (x.user.id == uid) m else x } } }
        }
    }

    private fun add(user: UserRole, role: String) {
        val uid = user.user.id ?: return
        viewModelScope.launch {
            _messages.report({ app.getString(R.string.members_added_person, user.user.displayName) }) {
                repo.setMemberRole(account, scope, id, uid, role)
            }.onSuccess { load() }
        }
    }

    private fun setGroupRole(g: GroupRole, role: String) {
        updateGroups { list -> list.map { if (it.group.id == g.group.id) it.copy(role = role) else it } }
        viewModelScope.launch {
            _messages.report({ app.getString(R.string.members_is_now_role, g.group.name, roleLabel(app, role)) }) {
                repo.setGroupRole(account, scope, id, g.group.id, role)
            }.onFailure { updateGroups { list -> list.map { x -> if (x.group.id == g.group.id) g else x } } }
        }
    }

    private fun addGroup(g: UserGroup, role: String) = viewModelScope.launch {
        _messages.report({ app.getString(R.string.members_added_person, g.name) }) { repo.setGroupRole(account, scope, id, g.id, role) }
            .onSuccess { load() }
    }

    private fun removeGroup(g: GroupRole) {
        val before = _state.value.groups
        updateGroups { list -> list.filterNot { it.group.id == g.group.id } }
        viewModelScope.launch {
            _messages.report({ app.getString(R.string.members_removed_person, g.group.name) }) {
                repo.removeGroupRole(account, scope, id, g.group.id)
            }.onFailure { updateGroups { list -> reinsert(list, before, g) { x -> x.group.id } } }
        }
    }

    private fun remove(m: UserRole) {
        val uid = m.user.id ?: return
        val before = _state.value.members
        updateMembers { list -> list.filterNot { it.user.id == uid } }
        viewModelScope.launch {
            _messages.report({ app.getString(R.string.members_removed_person, m.user.displayName) }) {
                repo.removeMember(account, scope, id, uid)
            }.onFailure { updateMembers { l -> reinsert(l, before.orEmpty(), m) { x -> x.user.id } } }
        }
    }

    companion object {
        val Factory = viewModelFactory { initializer { MembersViewModel(application, container.repository) } }
    }
}

@Composable
fun MembersScreen(
    accountId: String,
    scope: String,
    id: String,
    title: String,
    onBack: () -> Unit,
    vm: MembersViewModel = viewModel(factory = MembersViewModel.Factory, key = "members-$scope-$id"),
) {
    LaunchedEffect(accountId, scope, id) { vm.onEvent(MembersEvent.Bind(accountId, scope, id)) }
    val state by vm.state.collectAsStateWithLifecycle()
    val members = state.members
    val error = state.error
    val groups = state.groups
    val snackbar = rememberMessageSnackbar(vm.messages)
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<UserRole?>(null) }
    var removingGroup by remember { mutableStateOf<GroupRole?>(null) }

    Box(Modifier.fillMaxSize().background(Palette.Ink)) {
        Column(Modifier.fillMaxSize()) {
            SimpleTopBar(stringResource(R.string.members_members), title, onBack) {
                AccentPill(Icons.Rounded.PersonAdd, stringResource(R.string.members_add)) { adding = true; vm.onEvent(MembersEvent.LoadCandidates) }
            }
            val list0 = members
            val stage = when {
                list0 == null && error != null -> 0
                list0 == null -> 1
                list0.isEmpty() -> 2
                else -> 3
            }
            androidx.compose.animation.Crossfade(stage, animationSpec = dev.finalish.ui.theme.Motion.enter(), label = "state") { st ->
                val list = list0.orEmpty()
                when (st) {
                    0 -> ErrorState(error.orEmpty(), onRetry = { vm.onEvent(MembersEvent.Load) })
                    1 -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        repeat(5) { Box(Modifier.fillMaxWidth().height(52.dp).shimmer(RoundedCornerShape(14.dp))) }
                    }
                    2 -> EmptyState(Icons.Rounded.PersonAdd, stringResource(R.string.members_no_members_title), stringResource(R.string.members_group_empty_body))
                    else -> LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                        if (groups.isNotEmpty()) {
                            item(key = "groups-h") {
                                Text(
                                    pluralStringResource(R.plurals.members_n_groups, groups.size, groups.size),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = Palette.TextSecondary,
                                    modifier = Modifier.padding(start = 8.dp, bottom = 6.dp),
                                )
                            }
                            items(groups, key = { "g-" + it.group.id }) { g ->
                                GroupRoleRow(g, scope, onRole = { vm.onEvent(MembersEvent.SetGroupRole(g, it)) }, onRemove = { removingGroup = g }, modifier = Modifier.animateItem())
                            }
                            item(key = "groups-gap") { Spacer(Modifier.height(14.dp)) }
                        }
                        item {
                            Text(
                                pluralStringResource(R.plurals.members_n_members, list.size, list.size),
                                style = MaterialTheme.typography.labelLarge,
                                color = Palette.TextSecondary,
                                modifier = Modifier.padding(start = 8.dp, bottom = 6.dp),
                            )
                        }
                        items(list, key = { it.user.id ?: it.user.email.orEmpty() }) { m ->
                            MemberRow(m, scope, onRole = { vm.onEvent(MembersEvent.SetRole(m, it)) }, onRemove = { removing = m }, modifier = Modifier.animateItem())
                        }
                        item(key = "roles-hint") {
                            Text(
                                stringResource(R.string.members_roles_hint),
                                style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
                            )
                        }
                    }
            
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }

    if (adding) AddMemberSheet(scope, state, vm::onEvent, onDismiss = { adding = false })
    removingGroup?.let { g ->
        AlertDialog(
            onDismissRequest = { removingGroup = null },
            containerColor = Palette.Surface2,
            title = { Text(stringResource(R.string.members_remove_title, g.group.name)) },
            text = {
                val body = when (scope) {
                    "workspaces" -> R.string.members_remove_group_body_workspace
                    "folders" -> R.string.members_remove_group_body_folder
                    else -> R.string.members_remove_group_body_project
                }
                Text(stringResource(body), color = Palette.TextSecondary) },
            confirmButton = { TextButton(onClick = { removingGroup = null; vm.onEvent(MembersEvent.RemoveGroup(g)) }) { Text(stringResource(R.string.members_remove), color = Palette.Red) } },
            dismissButton = { TextButton(onClick = { removingGroup = null }) { Text(stringResource(R.string.members_cancel)) } },
        )
    }
    removing?.let { m ->
        AlertDialog(
            onDismissRequest = { removing = null },
            containerColor = Palette.Surface2,
            title = { Text(stringResource(R.string.members_remove_title, m.user.displayName)) },
            text = {
                val body = when (scope) {
                    "workspaces" -> R.string.members_remove_member_body_workspace
                    "folders" -> R.string.members_remove_member_body_folder
                    else -> R.string.members_remove_member_body_project
                }
                Text(stringResource(body), color = Palette.TextSecondary) },
            confirmButton = { TextButton(onClick = { removing = null; vm.onEvent(MembersEvent.Remove(m)) }) { Text(stringResource(R.string.members_remove), color = Palette.Red) } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.members_cancel)) } },
        )
    }
}

@Composable
private fun MemberRow(m: UserRole, scope: String, onRole: (String) -> Unit, onRemove: () -> Unit, modifier: Modifier) = RoleRow(
    leading = { Avatar(m.user, 38.dp) },
    title = m.user.displayName,
    subtitle = listOfNotNull(m.user.email.takeIf { it != m.user.displayName }, if (m.user.active == false) stringResource(R.string.members_inactive) else null).joinToString(" · "),
    role = m.role,
    editable = m.user.id != null && m.role !in setOf("owner", "admin"),
    scope = scope, onRole = onRole, onRemove = onRemove, modifier = modifier,
)

@Composable
internal fun GroupRoleRow(g: GroupRole, scope: String, onRole: (String) -> Unit, onRemove: () -> Unit, modifier: Modifier = Modifier) = RoleRow(
    leading = { GroupBadge(g.group) },
    title = g.group.name,
    subtitle = stringResource(R.string.members_group_subtitle, g.group.membersLabel),
    role = g.role,
    editable = g.role !in setOf("owner", "admin"),
    scope = scope, onRole = onRole, onRemove = onRemove, modifier = modifier,
)

@Composable
private fun RoleRow(
    leading: @Composable () -> Unit,
    title: String,
    subtitle: String,
    role: String?,
    editable: Boolean,
    scope: String,
    onRole: (String) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box {
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Palette.Surface2)
                    .pressable({ menu = true }, enabled = editable, scaleTo = 0.94f)
                    .padding(start = 12.dp, end = if (editable) 6.dp else 12.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(roleLabel(role), style = MaterialTheme.typography.labelLarge, color = Palette.TextSecondary)
                if (editable) Icon(Icons.Rounded.KeyboardArrowDown, null, tint = Palette.TextTertiary, modifier = Modifier.size(18.dp))
            }
            DropdownMenu(menu, onDismissRequest = { menu = false }, shape = dev.finalish.ui.theme.MenuShape, containerColor = Palette.Surface3) {
                assignableRoles(scope).forEach { r ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(roleLabel(r))
                                Text(roleDescriptionRes(r)?.let { stringResource(it) }.orEmpty(), style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary)
                            }
                        },
                        trailingIcon = { if (role == r) Icon(Icons.Rounded.Check, null, tint = Palette.Accent) },
                        onClick = { menu = false; if (role != r) onRole(r) },
                    )
                }
                HorizontalDivider(color = Palette.Hairline)
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.members_remove), color = Palette.Red) },
                    leadingIcon = { Icon(Icons.Rounded.PersonRemove, null, tint = Palette.Red) },
                    onClick = { menu = false; onRemove() },
                )
            }
        }
    }
}

@Composable
private fun AddMemberSheet(scope: String, state: MembersUiState, onEvent: (MembersEvent) -> Unit, onDismiss: () -> Unit) {
    val candidates = state.candidates
    val groupCandidates = state.groupCandidates
    var query by remember { mutableStateOf("") }
    var role by remember { mutableStateOf(if (scope == "workspaces") "editor" else "commenter") }
    var roleMenu by remember { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Palette.Surface1,
    ) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            Text(stringResource(R.string.members_add_member), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 4.dp, bottom = 12.dp))
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                placeholder = { Text(if (scope == "folders") stringResource(R.string.members_search_account_people) else stringResource(R.string.members_search_people_groups)) },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.padding(vertical = 10.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.members_role), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Box {
                    Row(
                        Modifier.clip(RoundedCornerShape(50)).background(Palette.Surface2).pressable({ roleMenu = true })
                            .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(roleLabel(role), style = MaterialTheme.typography.labelLarge)
                        Icon(Icons.Rounded.KeyboardArrowDown, null, modifier = Modifier.size(18.dp))
                    }
                    DropdownMenu(roleMenu, onDismissRequest = { roleMenu = false }, shape = dev.finalish.ui.theme.MenuShape, containerColor = Palette.Surface3) {
                        assignableRoles(scope).forEach { r ->
                            DropdownMenuItem(text = { Text(roleLabel(r)) }, onClick = { roleMenu = false; role = r })
                        }
                    }
                }
            }
            val list = candidates
            Box(Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 420.dp)) {
                when {
                    list == null -> Text(stringResource(R.string.members_loading), color = Palette.TextTertiary, modifier = Modifier.padding(12.dp))
                    else -> {
                        val q = query.trim().lowercase()
                        val filtered = list.filter { q.isEmpty() || it.user.displayName.lowercase().contains(q) || it.user.email.orEmpty().lowercase().contains(q) }
                        val groupsFiltered = groupCandidates.filter { q.isEmpty() || it.name.lowercase().contains(q) }
                        if (filtered.isEmpty() && groupsFiltered.isEmpty()) Text(
                            if (list.isEmpty()) stringResource(R.string.members_everyone_member) else stringResource(R.string.members_no_matches),
                            color = Palette.TextTertiary, modifier = Modifier.padding(12.dp),
                        )
                        else LazyColumn {
                            if (groupsFiltered.isNotEmpty()) {
                                item(key = "gh") { SheetLabel(stringResource(R.string.members_groups)) }
                                items(groupsFiltered, key = { "g-" + it.id }) { g ->
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .pressable({ onEvent(MembersEvent.AddGroup(g, role)); onDismiss() }, scaleTo = 0.98f)
                                            .padding(horizontal = 8.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        GroupBadge(g, 34.dp)
                                        Spacer(Modifier.width(12.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(g.name, style = MaterialTheme.typography.titleSmall)
                                            Text(g.membersLabel, style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary)
                                        }
                                        Box(Modifier.size(28.dp).clip(CircleShape).background(Palette.AccentContainer), contentAlignment = Alignment.Center) {
                                            Icon(Icons.Rounded.Add, null, tint = Palette.AccentBright, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                                if (filtered.isNotEmpty()) item(key = "ph") { SheetLabel(stringResource(R.string.members_people)) }
                            }
                            items(filtered, key = { it.user.id!! }) { u ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .pressable({ onEvent(MembersEvent.Add(u, role)); onDismiss() }, scaleTo = 0.98f)
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
}

@Composable
private fun SheetLabel(text: String) = Text(
    text, style = MaterialTheme.typography.labelLarge, color = Palette.TextSecondary,
    modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 4.dp),
)
