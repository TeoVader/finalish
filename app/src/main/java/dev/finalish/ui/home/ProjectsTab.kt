package dev.finalish.ui.home

import dev.finalish.ui.container
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.FolderSpecial
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.finalish.R
import dev.finalish.core.data.WorkspaceProjects
import dev.finalish.core.model.Account
import dev.finalish.core.model.Project
import dev.finalish.core.util.Format
import dev.finalish.ui.SessionState
import dev.finalish.ui.components.AutoRefresh
import dev.finalish.ui.components.Avatar
import dev.finalish.ui.components.EmptyState
import dev.finalish.ui.components.ErrorState
import dev.finalish.ui.components.RemoteImage
import dev.finalish.ui.components.pressable
import dev.finalish.ui.theme.Palette

@Composable
fun ProjectsTab(
    session: SessionState,
    onSelectAccount: (String) -> Unit,
    onOpenProject: (Project) -> Unit,
    onOpenShares: (Project) -> Unit,
    onOpenCollections: (Project) -> Unit,
    onSignOut: () -> Unit,
    contentPadding: PaddingValues,
    vm: ProjectsViewModel = viewModel(factory = ProjectsViewModel.Factory),
    onRetrySession: () -> Unit = {},
    onOpenMembers: (scope: String, id: String, title: String) -> Unit = { _, _, _ -> },
    onOpenTrash: (Project) -> Unit = {},
    onOpenNotifications: () -> Unit = {},
) {
    val accountId = session.accountId
    val context = androidx.compose.ui.platform.LocalContext.current
    val container = remember { dev.finalish.FinalishApp.container(context) }
    val appSettings by container.settings.settings.collectAsStateWithLifecycle()
    val large = appSettings.projectsLarge
    val newComments by container.activityWatch.unreadByProject.collectAsStateWithLifecycle()
    var recent by rememberSaveable(accountId) { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<Project?>(null) }
    LaunchedEffect(accountId) { accountId?.let { vm.onEvent(ProjectsEvent.Load(it)) } }
    val state by vm.state.collectAsStateWithLifecycle()
    val remote = state.remote
    val userRefreshing = state.userRefreshing
    AutoRefresh(accountId, intervalMs = { 30_000 }) { vm.onEvent(ProjectsEvent.Refresh(user = false)) }
    var workspaceFilter by rememberSaveable(accountId) { mutableStateOf<String?>(null) }
    var showAccounts by remember { mutableStateOf(false) }
    var showArchived by rememberSaveable(accountId) { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var linkHelp by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Project?>(null) }
    var deleting by remember { mutableStateOf<Project?>(null) }
    var renamingWs by remember { mutableStateOf<dev.finalish.core.model.Workspace?>(null) }
    var deletingWs by remember { mutableStateOf<WorkspaceProjects?>(null) }
    val busy = state.busy
    val snackbar = dev.finalish.ui.components.rememberMessageSnackbar(vm.messages)

    val allGroups = remote.data.orEmpty()
    val archivedCount = allGroups.sumOf { g -> g.projects.count { it.status == "inactive" } }
    LaunchedEffect(archivedCount, remote.loading) { if (showArchived && archivedCount == 0 && !remote.loading) showArchived = false }
    // Filtered and sorted once per data change, not on every recomposition.
    val groups = remember(allGroups, showArchived) {
        allGroups
            .map { g -> g.copy(projects = g.projects.filter { (it.status == "inactive") == showArchived }) }
            .filter { it.projects.isNotEmpty() }
    }
    val visible = if (workspaceFilter == null) groups else groups.filter { it.workspace.id == workspaceFilter }
    // "Recent": the most recently updated projects across workspaces, no grouping.
    val recentProjects = remember(groups) {
        groups.flatMap { it.projects }.distinctBy { it.id }
            .sortedByDescending { Format.parseInstant(it.updatedAt) ?: java.time.Instant.EPOCH }.take(12)
    }

    PullToRefreshBox(
        isRefreshing = userRefreshing,
        onRefresh = { vm.onEvent(ProjectsEvent.Refresh(user = true)) },
        modifier = Modifier.fillMaxSize(),
    ) {
        // Tablets get bigger cards, so a workspace's few projects fill the row instead of a corner.
        val wide = dev.finalish.ui.components.isWideWindow()
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = if (large) (if (wide) 400.dp else 320.dp) else (if (wide) 260.dp else 160.dp)),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                bottom = contentPadding.calculateBottomPadding() + 24.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "header") {
                Column(Modifier.statusBarsPadding().padding(top = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AccountSwitcher(session.account, onClick = { showAccounts = true }, modifier = Modifier.weight(1f))
                        NotificationsBell(accountId, onOpenNotifications)
                        Spacer(Modifier.width(6.dp))
                        Avatar(session.me, size = 36.dp, modifier = Modifier.pressable({ showAccounts = true }))
                    }
                    Spacer(Modifier.height(18.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (showArchived) stringResource(R.string.home_archived_title) else stringResource(R.string.home_tab_projects),
                            style = MaterialTheme.typography.headlineMedium,
                            modifier = Modifier.weight(1f),
                        )
                        // Grid (two columns) or one column of large cards.
                        Icon(
                            if (large) Icons.Rounded.GridView else Icons.Rounded.ViewAgenda,
                            if (large) stringResource(R.string.home_show_as_grid) else stringResource(R.string.home_show_large_cards),
                            tint = Palette.TextSecondary,
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .pressable({ container.settings.update { it.copy(projectsLarge = !large) } }, scaleTo = 0.88f)
                                .padding(9.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        if (accountId != null && !showArchived) {
                            Box(
                                Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(Palette.AccentGradient)
                                    .pressable({ creating = true }, scaleTo = 0.88f),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Rounded.Add, stringResource(R.string.home_new_project), tint = Palette.OnAccent, modifier = Modifier.size(22.dp))
                            }
                        }
                    }
                    dev.finalish.ui.components.OfflineBanner(Modifier.padding(top = 12.dp))
                    if (groups.isNotEmpty() || archivedCount > 0 || showArchived) {
                        Spacer(Modifier.height(14.dp))
                        WorkspaceChips(
                            groups, workspaceFilter, { workspaceFilter = it; recent = false },
                            archived = showArchived, archivedCount = archivedCount,
                            onArchived = { showArchived = !showArchived; workspaceFilter = null; recent = false },
                            recent = recent, onRecent = { recent = true; workspaceFilter = null },
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }

            // Frame.io answers 401 "Frame user is not linked to an Adobe ID" when a Frame-managed account
            // still signs in with its own password: show how to link it, not a bare error.
            val notLinked = session.error?.contains("linked to an Adobe ID", ignoreCase = true) == true
            when {
                accountId == null && session.error != null && !notLinked -> item(span = { GridItemSpan(maxLineSpan) }, key = "serr") {
                    ErrorState(session.error, onRetry = onRetrySession)
                }
                accountId == null && (notLinked || session.accounts.isEmpty() && session.me != null) -> item(span = { GridItemSpan(maxLineSpan) }, key = "noacc") {
                    EmptyState(
                        Icons.Rounded.FolderSpecial, stringResource(R.string.home_no_accounts_title),
                        stringResource(R.string.home_no_accounts_body, session.me?.email ?: stringResource(R.string.home_this_adobe_id)),
                        actionLabel = stringResource(R.string.home_how_to_link), onAction = { linkHelp = true },
                    )
                }
                remote.isInitialLoading -> items(6, key = { "sk$it" }) { ProjectCardSkeleton() }
                remote.isEmptyError -> item(span = { GridItemSpan(maxLineSpan) }, key = "err") {
                    ErrorState(remote.error.orEmpty(), onRetry = { accountId?.let { vm.onEvent(ProjectsEvent.Load(it, force = true)) } }, retrying = true)
                }
                groups.isEmpty() && !remote.loading -> item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
                    if (showArchived) EmptyState(Icons.Rounded.FolderSpecial, stringResource(R.string.home_no_archived_projects))
                    else EmptyState(
                        Icons.Rounded.FolderSpecial, stringResource(R.string.home_no_projects_title),
                        stringResource(R.string.home_no_projects_body),
                        actionLabel = stringResource(R.string.home_new_project), onAction = { creating = true },
                    )
                }
                recent && !showArchived -> items(recentProjects, key = { "r-" + it.id }) { project ->
                    ProjectCard(
                        project = project, accountId = accountId, large = large,
                        newComments = newComments[project.id] ?: 0,
                        onClick = { onOpenProject(project) },
                        onMenu = { menuFor = project },
                        modifier = Modifier.animateItem(),
                    )
                }
                else -> visible.forEach { group ->
                    if (!showArchived && (workspaceFilter == null || groups.size == 1)) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "ws-${group.workspace.id}") {
                            WorkspaceHeader(
                                group,
                                onRename = { renamingWs = group.workspace },
                                onMembers = { onOpenMembers("workspaces", group.workspace.id, group.workspace.name) },
                                onDelete = { deletingWs = group },
                            )
                        }
                    }
                    items(group.projects, key = { it.id }) { project ->
                        ProjectCard(
                            project = project, accountId = accountId, large = large,
                            newComments = newComments[project.id] ?: 0,
                            onClick = { onOpenProject(project) },
                            onMenu = { menuFor = project },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
    }

    menuFor?.let { project ->
        val workspace = allGroups.firstOrNull { g -> g.projects.any { it.id == project.id } }?.workspace?.name
        ProjectMenuSheet(
            project = project, accountId = accountId, workspaceName = workspace,
            onDismiss = { menuFor = null },
            onShares = { menuFor = null; onOpenShares(project) },
            onCollections = { menuFor = null; onOpenCollections(project) },
            onMembers = { menuFor = null; onOpenMembers("projects", project.id, project.name) },
            onTrash = { menuFor = null; onOpenTrash(project) },
            onRename = { menuFor = null; renaming = project },
            onRestricted = { vm.onEvent(ProjectsEvent.SetRestricted(project, project.restricted != true)); menuFor = project.copy(restricted = project.restricted != true) },
            onArchive = { menuFor = null; vm.onEvent(ProjectsEvent.SetArchived(project, project.status != "inactive")) },
            onDelete = { menuFor = null; deleting = project },
        )
    }

    if (linkHelp) {
        dev.finalish.ui.login.AdobeLinkHelpSheet(
            onDismiss = { linkHelp = false },
            email = session.me?.email,
            onSignInAgain = { linkHelp = false; onSignOut() },
        )
    }

    if (creating) {
        NewProjectSheet(
            workspaces = allGroups.map { it.workspace }.filter { it.id != dev.finalish.core.data.INVITED_WORKSPACE },
            preselected = workspaceFilter?.takeIf { it != dev.finalish.core.data.INVITED_WORKSPACE },
            busy = busy,
            missing = dev.finalish.core.network.PlanLimits.missing.collectAsStateWithLifecycle().value[accountId].orEmpty(),
            limitNote = dev.finalish.core.network.PlanLimits.projectLimit(accountId)?.let { max ->
                val owned = allGroups.filter { it.workspace.id != dev.finalish.core.data.INVITED_WORKSPACE }.sumOf { it.projects.size }
                pluralStringResource(R.plurals.home_project_limit_note, max, max, owned)
                    .takeIf { owned >= max }
            },
            onCreate = { target, name, restricted ->
                vm.onEvent(ProjectsEvent.Create(target, name, restricted) { p -> creating = false; onOpenProject(p) })
            },
            onDismiss = { creating = false },
        )
    }
    renaming?.let { p ->
        dev.finalish.ui.browser.TextPromptDialog(stringResource(R.string.home_rename_project), stringResource(R.string.home_name), p.name, stringResource(R.string.home_save), onDismiss = { renaming = null }) {
            renaming = null
            vm.onEvent(ProjectsEvent.Rename(p, it))
        }
    }
    renamingWs?.let { ws ->
        dev.finalish.ui.browser.TextPromptDialog(stringResource(R.string.home_rename_workspace), stringResource(R.string.home_name), ws.name, stringResource(R.string.home_save), onDismiss = { renamingWs = null }) {
            renamingWs = null
            vm.onEvent(ProjectsEvent.RenameWorkspace(ws.id, it))
        }
    }
    deletingWs?.let { g ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { deletingWs = null },
            containerColor = Palette.Surface2,
            title = { Text(stringResource(R.string.home_delete_named, g.workspace.name)) },
            text = {
                Text(
                    if (g.projects.isEmpty()) stringResource(R.string.home_delete_empty_workspace_body)
                    else pluralStringResource(R.plurals.home_delete_workspace_body, g.projects.size, g.projects.size),
                    color = Palette.TextSecondary,
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { deletingWs = null; vm.onEvent(ProjectsEvent.DeleteWorkspace(g.workspace.id)) }) {
                    Text(stringResource(R.string.home_delete), color = Palette.Red)
                }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { deletingWs = null }) { Text(stringResource(R.string.home_cancel)) } },
        )
    }
    deleting?.let { p ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { deleting = null },
            containerColor = Palette.Surface2,
            title = { Text(stringResource(R.string.home_delete_named, p.name)) },
            text = {
                Text(
                    stringResource(R.string.home_delete_project_body),
                    color = Palette.TextSecondary,
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { deleting = null; vm.onEvent(ProjectsEvent.Delete(p)) }) {
                    Text(stringResource(R.string.home_delete), color = Palette.Red)
                }
            },
            dismissButton = {
                if (p.status == "inactive") {
                    androidx.compose.material3.TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.home_cancel)) }
                } else {
                    androidx.compose.material3.TextButton(onClick = { deleting = null; vm.onEvent(ProjectsEvent.SetArchived(p, true)) }) { Text(stringResource(R.string.home_archive)) }
                }
            },
        )
    }
    Box(Modifier.fillMaxSize()) {
        androidx.compose.material3.SnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter).padding(bottom = contentPadding.calculateBottomPadding() + 8.dp),
        )
    }

    if (showAccounts) {
        AccountSheet(
            session = session,
            workspaces = allGroups.filter { it.workspace.id != dev.finalish.core.data.INVITED_WORKSPACE },
            onWorkspace = { id -> showAccounts = false; showArchived = false; recent = false; workspaceFilter = id },
            onSelect = { onSelectAccount(it); showAccounts = false },
            onSignOut = { showAccounts = false; onSignOut() },
            onDismiss = { showAccounts = false },
        )
    }
}

@Composable
private fun WorkspaceHeader(group: WorkspaceProjects, onRename: () -> Unit, onMembers: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val invited = group.workspace.id == dev.finalish.core.data.INVITED_WORKSPACE
    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(group.workspace.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Text("${group.projects.size}", style = MaterialTheme.typography.labelMedium, color = Palette.TextTertiary)
        if (!invited) Box {
            Icon(
                Icons.Rounded.MoreHoriz, stringResource(R.string.home_workspace_options), tint = Palette.TextTertiary,
                modifier = Modifier.padding(start = 8.dp).size(26.dp).clip(CircleShape).pressable({ menu = true }, scaleTo = 0.85f),
            )
            DropdownMenu(menu, onDismissRequest = { menu = false }, shape = dev.finalish.ui.theme.MenuShape) {
                DropdownMenuItem(text = { Text(stringResource(R.string.home_members)) }, leadingIcon = { Icon(Icons.Rounded.Group, null) }, onClick = { menu = false; onMembers() })
                DropdownMenuItem(text = { Text(stringResource(R.string.home_rename)) }, leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = { menu = false; onRename() })
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.home_delete_workspace), color = Palette.Red) },
                    leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = Palette.Red) },
                    onClick = { menu = false; onDelete() },
                )
            }
        }
    }
}

@Composable
private fun AccountSwitcher(account: Account?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .clip(RoundedCornerShape(12.dp))
                .pressable(onClick)
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AccountBadge(account, 28.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                account?.displayName ?: stringResource(R.string.home_loading),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(Icons.Rounded.UnfoldMore, null, tint = Palette.TextTertiary, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
internal fun AccountBadge(account: Account?, size: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(8.dp)).background(Palette.AccentGradient),
        contentAlignment = Alignment.Center,
    ) {
        Text(Format.initials(account?.displayName ?: "·"), style = MaterialTheme.typography.labelSmall, color = Color.White)
        account?.image?.let { RemoteImage(it, Modifier.matchParentSizeCompat(), fallback = {}) }
    }
}

private fun Modifier.matchParentSizeCompat() = this.fillMaxSize()

@Composable
private fun WorkspaceChips(
    groups: List<WorkspaceProjects>,
    selected: String?,
    onSelect: (String?) -> Unit,
    archived: Boolean = false,
    archivedCount: Int = 0,
    onArchived: () -> Unit = {},
    recent: Boolean = false,
    onRecent: () -> Unit = {},
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!archived) {
            item { Chip(stringResource(R.string.home_chip_all), selected == null && !recent) { onSelect(null) } }
            item(key = "recent") { Chip(stringResource(R.string.home_chip_recent), recent, onClick = onRecent) }
            if (groups.size > 1) items(groups, key = { it.workspace.id }) { g ->
                Chip(g.workspace.name, selected == g.workspace.id && !recent) { onSelect(g.workspace.id) }
            }
        }
        if (archivedCount > 0 || archived) {
            item(key = "archived") { Chip(if (archived) stringResource(R.string.home_chip_active_projects) else stringResource(R.string.home_chip_archived, archivedCount), archived, onClick = onArchived) }
        }
    }
}

@Composable
fun Chip(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val bg by androidx.compose.animation.animateColorAsState(if (selected) Palette.Text else Palette.Surface2, dev.finalish.ui.theme.Motion.snappy(), label = "chipBg")
    val fg by androidx.compose.animation.animateColorAsState(if (selected) Palette.Ink else Palette.TextSecondary, dev.finalish.ui.theme.Motion.snappy(), label = "chipFg")
    val edge by androidx.compose.animation.animateColorAsState(if (selected) Color.Transparent else Palette.Hairline, dev.finalish.ui.theme.Motion.snappy(), label = "chipEdge")
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .border(1.dp, edge, RoundedCornerShape(50))
            .then(if (enabled) Modifier.pressable(onClick) else Modifier.graphicsLayer { alpha = 0.38f })
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = fg,
            maxLines = 1,
        )
    }
}
