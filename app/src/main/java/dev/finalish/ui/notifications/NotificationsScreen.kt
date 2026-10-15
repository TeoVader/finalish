package dev.finalish.ui.notifications

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AlternateEmail
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.NotificationsNone
import androidx.compose.material.icons.rounded.Reply
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.content.res.Resources
import dev.finalish.FinalishApp
import dev.finalish.R
import dev.finalish.core.model.User
import dev.finalish.core.notify.ActivityCheck
import dev.finalish.core.notify.ActivityEvent
import dev.finalish.core.notify.ActivityEvent.Kind
import dev.finalish.ui.components.Avatar
import dev.finalish.ui.components.EmptyState
import dev.finalish.ui.shares.SimpleTopBar
import dev.finalish.ui.theme.Palette
import dev.finalish.ui.components.pressable
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * Notifications, like the official app's inbox: what happened on the files and links the
 * app follows (files you opened or uploaded, your share links), grouped by day.
 * Frame.io has no public inbox API, so this is built from the background check.
 */
@Composable
fun NotificationsScreen(
    accountId: String,
    onBack: () -> Unit,
    onOpenFile: (String) -> Unit,
    onOpenShares: (projectId: String, projectName: String) -> Unit,
) {
    val context = LocalContext.current
    val container = remember { FinalishApp.container(context) }
    val all by container.activityWatch.events.collectAsStateWithLifecycle()
    val events = remember(all, accountId) { all.filter { it.accountId == accountId } }
    val projects by produceState(emptyMap<String, String>(), accountId) { value = container.repository.cachedProjectNames(accountId) }
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    // Nothing yet (first visit): look now instead of waiting for the 15-minute check.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (events.isEmpty()) {
            refreshing = true
            runCatching { ActivityCheck(context).run() }
            refreshing = false
        }
    }

    Column(Modifier.fillMaxSize().background(Palette.Ink)) {
        SimpleTopBar(
            stringResource(R.string.notifications_title),
            events.count { !it.read }.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.notifications_unread, it, it) },
            onBack,
        ) {
            if (events.any { !it.read }) {
                IconButton(onClick = { container.activityWatch.markAllRead() }) { Icon(Icons.Rounded.DoneAll, stringResource(R.string.notifications_mark_all_read)) }
            }
            IconButton(onClick = { settingsOpen = true }) { Icon(Icons.Rounded.Settings, stringResource(R.string.notifications_settings)) }
        }
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                scope.launch {
                    refreshing = true
                    runCatching { ActivityCheck(context).run() }
                    refreshing = false
                }
            },
            modifier = Modifier.fillMaxSize(),
        ) {
            if (events.isEmpty()) {
                // In a list so pull-to-refresh works on the empty state too.
                LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        EmptyState(
                            Icons.Rounded.NotificationsNone,
                            stringResource(R.string.notifications_empty_title),
                            stringResource(R.string.notifications_empty_message),
                            modifier = Modifier.fillMaxWidth().padding(top = 80.dp),
                        )
                    }
                }
            } else {
                NotificationList(events, projects) { e ->
                    container.activityWatch.markRead(e.id)
                    when {
                        e.fileId != null -> onOpenFile(e.fileId)
                        e.shareId != null -> onOpenShares(e.projectId, projects[e.projectId].orEmpty())
                    }
                }
            }
        }
    }
    if (settingsOpen) NotificationSettingsSheet { settingsOpen = false }
}

@Composable
internal fun NotificationList(events: List<ActivityEvent>, projects: Map<String, String>, onOpen: (ActivityEvent) -> Unit) {
    val zone = ZoneId.systemDefault()
    val byDay = remember(events) { events.groupBy { java.time.Instant.parse(it.at).atZone(zone).toLocalDate() } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        byDay.forEach { (day, list) ->
            item(key = "day:$day") {
                Text(
                    dayLabel(day),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
                )
                HorizontalDivider(color = Palette.Hairline)
            }
            items(list, key = { it.id }) { e -> NotificationRow(e, projects[e.projectId], onOpen) }
        }
    }
}

@Composable
private fun NotificationRow(e: ActivityEvent, project: String?, onOpen: (ActivityEvent) -> Unit) {
    Row(
        Modifier.fillMaxWidth().pressable({ onOpen(e) }, scaleTo = 0.99f).padding(start = 8.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // Unread dot, like the official inbox.
        Box(Modifier.padding(top = 4.dp).size(8.dp).clip(CircleShape).background(if (e.read) androidx.compose.ui.graphics.Color.Transparent else Palette.Accent))
        Spacer(Modifier.width(6.dp))
        Actors(e)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(headline(e, LocalResources.current), style = MaterialTheme.typography.bodyMedium, color = if (e.read) Palette.TextSecondary else Palette.Text)
            e.text?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
            Text(
                // The day is in the section header; the row says when in the day.
                listOfNotNull(project, timeOfDay(e.at)).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary, modifier = Modifier.padding(top = 2.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier.size(width = 52.dp, height = 36.dp).clip(RoundedCornerShape(8.dp)).background(Palette.Surface2),
            contentAlignment = Alignment.Center,
        ) { Icon(kindIcon(e.kind), null, tint = Palette.TextTertiary, modifier = Modifier.size(18.dp)) }
    }
}

/** One avatar, or two overlapping when several people are involved. */
@Composable
private fun Actors(e: ActivityEvent) {
    val names = e.actors.takeLast(2).reversed()
    Box(Modifier.size(36.dp)) {
        when {
            names.isEmpty() -> Box(
                Modifier.size(36.dp).clip(CircleShape).background(Palette.AccentContainer),
                contentAlignment = Alignment.Center,
            ) { Icon(kindIcon(e.kind), null, tint = Palette.AccentBright, modifier = Modifier.size(18.dp)) }
            names.size == 1 -> Avatar(User(name = names[0]), size = 36.dp)
            else -> {
                Avatar(User(name = names[1]), size = 26.dp)
                Avatar(User(name = names[0]), size = 26.dp, modifier = Modifier.offset(x = 10.dp, y = 10.dp).border(2.dp, Palette.Ink, CircleShape))
            }
        }
    }
}

private fun kindIcon(k: Kind): ImageVector = when (k) {
    Kind.Comments -> Icons.Rounded.ChatBubbleOutline
    Kind.Replies -> Icons.Rounded.Reply
    Kind.Mention -> Icons.Rounded.AlternateEmail
    Kind.UploadReady -> Icons.Rounded.CloudDone
    Kind.ShareViewed -> Icons.Rounded.Visibility
}

/** "Jordan and 1 other left 8 comments on Cut_v2.mp4", in the official inbox's words, from the string resources. */
internal fun headline(e: ActivityEvent, res: Resources): String {
    val who = when (e.actors.size) {
        0 -> res.getString(R.string.notifications_someone)
        1 -> e.actors[0]
        else -> res.getQuantityString(R.plurals.notifications_actors_others, e.actors.size - 1, e.actors.last(), e.actors.size - 1)
    }
    return when (e.kind) {
        Kind.Comments -> if (e.count == 1) res.getString(R.string.notifications_comment_one, who, e.title)
            else res.getQuantityString(R.plurals.notifications_comments_many, e.count, who, e.count, e.title)
        Kind.Replies -> if (e.count == 1) res.getString(R.string.notifications_reply_one, who, e.title)
            else res.getQuantityString(R.plurals.notifications_replies_many, e.count, who, e.count, e.title)
        Kind.Mention -> res.getString(R.string.notifications_mention, who, e.title)
        Kind.UploadReady -> res.getString(R.string.notifications_upload_ready, e.title)
        Kind.ShareViewed -> if (e.actors.isEmpty()) {
            if (e.count == 1) res.getString(R.string.notifications_link_opened_once, e.title)
            else res.getQuantityString(R.plurals.notifications_link_opened_times, e.count, e.title, e.count)
        } else res.getString(R.string.notifications_link_opened_by, who, e.title)
    }
}

/** The English wording of [headline] without resources; kept for the plain JUnit ActivityEventsTest. */
internal fun headline(e: ActivityEvent): String {
    val who = when (e.actors.size) {
        0 -> "Someone"
        1 -> e.actors[0]
        else -> "${e.actors.last()} and ${e.actors.size - 1} other${if (e.actors.size > 2) "s" else ""}"
    }
    return when (e.kind) {
        Kind.Comments -> "$who left ${if (e.count == 1) "a comment" else "${e.count} comments"} on ${e.title}"
        Kind.Replies -> "$who left ${if (e.count == 1) "a reply" else "${e.count} replies"} to you on ${e.title}"
        Kind.Mention -> "$who mentioned you on ${e.title}"
        Kind.UploadReady -> "Your upload ${e.title} is ready to review"
        Kind.ShareViewed -> if (e.actors.isEmpty()) {
            if (e.count == 1) "Someone opened your link ${e.title}" else "Your link ${e.title} was opened ${e.count} times"
        } else "$who opened your link ${e.title}"
    }
}

private fun timeOfDay(iso: String): String? = runCatching {
    DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT).withZone(ZoneId.systemDefault()).format(java.time.Instant.parse(iso))
}.getOrNull()

@Composable
private fun dayLabel(day: LocalDate): String {
    val today = LocalDate.now()
    return when (day) {
        today -> stringResource(R.string.notifications_today)
        today.minusDays(1) -> stringResource(R.string.notifications_yesterday)
        else -> day.format(DateTimeFormatter.ofPattern(if (day.year == today.year) "EEE d MMM" else "d MMM yyyy", androidx.compose.ui.platform.LocalLocale.current.platformLocale))
    }
}

/** The gear in Notifications: what the background check looks for. Email settings live on frame.io. */
@Composable
private fun NotificationSettingsSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val container = remember { FinalishApp.container(context) }
    val s by container.settings.settings.collectAsStateWithLifecycle()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Palette.Surface2,
    ) {
        Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(stringResource(R.string.notifications_settings), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 8.dp))
            SettingSwitch(Icons.Rounded.NotificationsNone, stringResource(R.string.notifications_activity), stringResource(R.string.notifications_activity_detail), s.notifications) { v ->
                container.settings.update { it.copy(notifications = v) }
                if (v) dev.finalish.core.notify.ActivityWatch.checkSoon(context)
            }
            if (s.notifications) {
                SettingSwitch(Icons.Rounded.ChatBubbleOutline, stringResource(R.string.notifications_comments), null, s.notifyComments) { v -> container.settings.update { it.copy(notifyComments = v) } }
                SettingSwitch(Icons.Rounded.CloudDone, stringResource(R.string.notifications_uploads_ready), null, s.notifyUploads) { v -> container.settings.update { it.copy(notifyUploads = v) } }
                SettingSwitch(Icons.Rounded.Visibility, stringResource(R.string.notifications_share_views), null, s.notifyShareViews) { v -> container.settings.update { it.copy(notifyShareViews = v) } }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.notifications_settings_footer),
                style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }
    }
}

@Composable
private fun SettingSwitch(icon: ImageVector, label: String, detail: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, null, tint = Palette.TextSecondary, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary) }
        }
        Switch(checked, onChange, colors = SwitchDefaults.colors(checkedTrackColor = Palette.Accent, checkedThumbColor = Palette.OnAccent))
    }
}
