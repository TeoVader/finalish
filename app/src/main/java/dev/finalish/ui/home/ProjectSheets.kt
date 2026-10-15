package dev.finalish.ui.home

import dev.finalish.ui.container
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.ViewCarousel
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material.icons.rounded.Workspaces
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.NotificationsNone
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.finalish.R
import dev.finalish.core.data.WorkspaceProjects
import dev.finalish.core.model.Project
import dev.finalish.core.util.Format
import dev.finalish.ui.SessionState
import dev.finalish.ui.components.Avatar
import dev.finalish.ui.components.RemoteImage
import dev.finalish.ui.components.pressable
import dev.finalish.ui.theme.Palette

/** Project "⋯": a sheet with the project's header and its places, with counts (mockup). */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun ProjectMenuSheet(
    project: Project,
    accountId: String?,
    workspaceName: String?,
    onDismiss: () -> Unit,
    onShares: () -> Unit,
    onCollections: () -> Unit,
    onMembers: () -> Unit,
    onTrash: () -> Unit,
    onRename: () -> Unit,
    onRestricted: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val repo = remember { dev.finalish.FinalishApp.container(context).repository }
    // Counts load in the background; rows show them when they arrive.
    val shares by androidx.compose.runtime.produceState<Int?>(null, project.id) {
        if (accountId != null) repo.shares(accountId, project.id).collect { r -> r.data?.let { value = it.size } }
    }
    val collections by androidx.compose.runtime.produceState<Int?>(null, project.id) {
        if (accountId != null) repo.collections(accountId, project.id).collect { r -> r.data?.let { value = it.size } }
    }
    val members by androidx.compose.runtime.produceState<Int?>(null, project.id) {
        if (accountId != null) value = runCatching { repo.members(accountId, "projects", project.id).size }.getOrNull()
    }
    androidx.compose.material3.ModalBottomSheet(
        modifier = Modifier.offset(x = dev.finalish.ui.components.LocalSideBarWidth.current / 2),
        onDismissRequest = onDismiss,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Palette.Surface2,
    ) {
        Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()).navigationBarsPadding().padding(bottom = 12.dp)) {
            // Header like the official app: cover, name, members · size · bell when notifications are on.
            val watch = remember { dev.finalish.FinalishApp.container(context).activityWatch }
            val muted by watch.mutedProjects.collectAsStateWithLifecycle()
            val notifying = project.id !in muted
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(width = 112.dp, height = 70.dp).clip(RoundedCornerShape(14.dp)).background(projectGradient(project.id)),
                    contentAlignment = Alignment.BottomStart,
                ) {
                    // The project's image, else its latest file (same cover as the card).
                    val cover by androidx.compose.runtime.produceState(project.image, project.id) {
                        if (value == null && accountId != null) value = runCatching { repo.projectCover(accountId, project).thumbnails.firstOrNull() }.getOrNull()
                    }
                    cover?.let { RemoteImage(it, Modifier.fillMaxSize(), fallback = {}) }
                        ?: Text(Format.initials(project.name), style = MaterialTheme.typography.titleMedium, color = Color.White, modifier = Modifier.padding(10.dp))
                }
                Spacer(Modifier.height(10.dp))
                Text(project.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        listOfNotNull(
                            workspaceName,
                            members?.let { pluralStringResource(R.plurals.home_member_count, it, it) },
                            Format.bytes(project.storage).takeIf { project.storage > 0 },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary,
                    )
                    if (notifying) Icon(Icons.Rounded.Notifications, stringResource(R.string.home_notifications_on), tint = Palette.TextTertiary, modifier = Modifier.padding(start = 4.dp).size(14.dp))
                }
            }
            Row(
                Modifier.fillMaxWidth().pressable({ watch.setMuted(project.id, notifying) }, scaleTo = 0.99f).padding(horizontal = 24.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.NotificationsNone, null, tint = Palette.TextSecondary, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.home_notifications), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.home_project_notifications_desc), style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary)
                }
                androidx.compose.material3.Switch(
                    checked = notifying, onCheckedChange = { watch.setMuted(project.id, !it) },
                    colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = Palette.Accent, checkedThumbColor = Palette.OnAccent),
                )
            }
            HorizontalDivider(color = Palette.Hairline, modifier = Modifier.padding(vertical = 6.dp, horizontal = 24.dp))
            MenuRow(Icons.Rounded.Group, stringResource(R.string.home_members), members, onMembers)
            MenuRow(Icons.Rounded.Link, stringResource(R.string.home_share_links), shares, onShares)
            MenuRow(Icons.Rounded.ViewCarousel, stringResource(R.string.home_collections), collections, onCollections)
            MenuRow(Icons.Rounded.DeleteSweep, stringResource(R.string.home_recently_deleted), null, onTrash)
            MenuRow(Icons.Rounded.IosShare, stringResource(R.string.home_send_project_link), null, onClick = {
                val link = "https://next.frame.io/project/${project.id}"
                val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(android.content.Intent.EXTRA_TEXT, link).putExtra(android.content.Intent.EXTRA_SUBJECT, project.name)
                context.startActivity(android.content.Intent.createChooser(send, null))
                onDismiss()
            })
            HorizontalDivider(color = Palette.Hairline, modifier = Modifier.padding(vertical = 6.dp, horizontal = 24.dp))
            MenuRow(Icons.Rounded.Edit, stringResource(R.string.home_rename), null, onRename)
            // Free and Pro have no restricted projects: the row would only fail.
            val noRestricted = "restricted_projects" in dev.finalish.core.network.PlanLimits.missing.collectAsStateWithLifecycle().value[accountId].orEmpty() &&
                project.restricted != true
            if (!noRestricted) Row(
                Modifier.fillMaxWidth().pressable(onRestricted, scaleTo = 0.99f).padding(horizontal = 24.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Lock, null, tint = Palette.TextSecondary, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.home_restricted), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.home_restricted_menu_desc), style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary)
                }
                androidx.compose.material3.Switch(
                    checked = project.restricted == true, onCheckedChange = { onRestricted() },
                    colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = Palette.Accent, checkedThumbColor = Palette.OnAccent),
                )
            }
            MenuRow(
                if (project.status == "inactive") Icons.Rounded.Unarchive else Icons.Rounded.Archive,
                if (project.status == "inactive") stringResource(R.string.home_restore) else stringResource(R.string.home_archive), null, onArchive,
            )
            MenuRow(Icons.Rounded.Delete, stringResource(R.string.home_delete), null, onDelete, danger = true)
        }
    }
}

/** Bell next to the avatar; the dot counts unread notifications. */
@Composable
internal fun NotificationsBell(accountId: String?, onClick: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val events by remember { dev.finalish.FinalishApp.container(context).activityWatch }.events.collectAsStateWithLifecycle()
    val unread = events.count { !it.read && it.accountId == accountId }
    val bellDescription = if (unread > 0) pluralStringResource(R.plurals.home_notifications_unread, unread, unread) else stringResource(R.string.home_notifications)
    Box(
        Modifier.size(40.dp).clip(CircleShape).pressable(onClick, scaleTo = 0.88f)
            .semantics { contentDescription = bellDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.NotificationsNone, null, tint = Palette.TextSecondary, modifier = Modifier.size(24.dp))
        if (unread > 0) {
            Text(
                if (unread > 99) "99+" else "$unread",
                style = MaterialTheme.typography.labelSmall,
                color = Palette.OnAccent,
                modifier = Modifier.align(Alignment.TopEnd).offset(x = (-2).dp, y = 4.dp)
                    .clip(RoundedCornerShape(50)).background(Palette.Accent).padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
    }
}

@Composable
private fun MenuRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, count: Int?, onClick: () -> Unit, danger: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().pressable(onClick, scaleTo = 0.99f).padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (danger) Palette.Red else Palette.TextSecondary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = if (danger) Palette.Red else Palette.Text, modifier = Modifier.weight(1f))
        count?.let {
            Text(
                "$it", style = MaterialTheme.typography.labelMedium, color = Palette.TextSecondary,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(Palette.Surface3).padding(horizontal = 9.dp, vertical = 3.dp),
            )
        }
    }
}

@Composable
internal fun AccountSheet(
    session: SessionState,
    workspaces: List<WorkspaceProjects>,
    onWorkspace: (String) -> Unit,
    onSelect: (String) -> Unit,
    onSignOut: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        modifier = Modifier.offset(x = dev.finalish.ui.components.LocalSideBarWidth.current / 2),
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Palette.Surface1,
    ) {
        Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(bottom = 24.dp)) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(session.me, 44.dp)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(session.me?.displayName ?: "", style = MaterialTheme.typography.titleMedium)
                    Text(session.me?.email ?: "", style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Palette.Hairline)
            Text(
                stringResource(R.string.home_accounts_header), style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
            session.accounts.forEach { acc ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .pressable({ onSelect(acc.id) }, scaleTo = 0.98f)
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AccountBadge(acc, 34.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(acc.displayName, style = MaterialTheme.typography.titleSmall)
                        val usage = acc.storageLimit?.let { stringResource(R.string.home_storage_used_of_limit, Format.bytes(acc.storageUsage), Format.bytes(it)) }
                            ?: Format.bytes(acc.storageUsage)
                        Text(
                            "${acc.roles.joinToString { it.replaceFirstChar(Char::uppercase) }} · $usage",
                            style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary,
                        )
                    }
                    AnimatedContent(acc.id == session.accountId, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "sel") { sel ->
                        if (sel) {
                            Box(Modifier.size(24.dp).clip(CircleShape).background(Palette.Accent), contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.Check, null, tint = Palette.OnAccent, modifier = Modifier.size(16.dp))
                            }
                        } else Spacer(Modifier.size(24.dp))
                    }
                }
            }
            // The current account's workspaces (mockup): tap one to show just its projects.
            if (workspaces.isNotEmpty()) {
                Text(
                    stringResource(R.string.home_workspaces_of, (session.account?.displayName ?: stringResource(R.string.home_this_account)).uppercase()),
                    style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp),
                )
                workspaces.forEach { g ->
                    Row(
                        Modifier.fillMaxWidth().pressable({ onWorkspace(g.workspace.id) }, scaleTo = 0.98f).padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Workspaces, null, tint = Palette.TextSecondary, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(14.dp))
                        Text(g.workspace.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            pluralStringResource(R.plurals.home_project_count, g.projects.size, g.projects.size),
                            style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary,
                        )
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Palette.Hairline)
            Row(
                Modifier
                    .fillMaxWidth()
                    .pressable(onSignOut, scaleTo = 0.98f)
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.AutoMirrored.Rounded.Logout, null, tint = Palette.Red)
                Spacer(Modifier.width(14.dp))
                Text(stringResource(R.string.home_sign_out), color = Palette.Red, style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}
