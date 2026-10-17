@file:OptIn(UnstableApi::class)

package dev.finalish.ui.settings

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.NetworkCell
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import dev.finalish.BuildConfig
import dev.finalish.FinalishApp
import dev.finalish.R
import dev.finalish.core.data.PlaybackQuality
import dev.finalish.core.util.Format
import dev.finalish.ui.SessionState
import dev.finalish.ui.components.Avatar
import dev.finalish.ui.components.SectionHeader
import dev.finalish.ui.components.pressable
import dev.finalish.ui.login.LogoMark
import dev.finalish.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val SOURCE_URL = "https://github.com/TeoVader/finalish"

/** Published with the store listing (GitHub Pages); also linked from the Feedback screen. */
const val PRIVACY_URL = "https://teovader.github.io/finalish/privacy-policy"
const val TERMS_URL = "https://teovader.github.io/finalish/terms"

/** Credits for the sample media that Demo mode streams (shown here and in [LicensesScreen]). */
val DEMO_MEDIA_CREDITS = R.string.settings_demo_media_credits

fun openUrl(context: android.content.Context, url: String) {
    runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, url.toUri())) }
}

private fun dirBytes(dir: java.io.File): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

@Composable
fun SettingsTab(
    session: SessionState,
    onSignOut: () -> Unit,
    contentPadding: PaddingValues,
    onSelectAccount: (String) -> Unit = {},
    onOpenMetadataFields: () -> Unit = {},
    onOpenGroups: () -> Unit = {},
    onOpenLicenses: () -> Unit = {},
) {
    val context = LocalContext.current
    val container = remember { FinalishApp.container(context) }
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    // Measured off the main thread (cacheDir also holds the API cache, "api/").
    var cacheSize by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { cacheSize = withContext(Dispatchers.IO) { dirBytes(context.cacheDir) } }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var themeSheet by remember { mutableStateOf(false) }

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp),
    ) {
        item {
            Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 14.dp))
        }
        item {
            Row(
                Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(Palette.Surface2)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(session.me, 52.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(session.me?.displayName ?: "", style = MaterialTheme.typography.titleMedium)
                    Text(session.me?.email ?: "", style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
                    session.account?.let {
                        Text(it.displayName, style = MaterialTheme.typography.labelMedium, color = Palette.AccentBright, modifier = Modifier.padding(top = 4.dp))
                    }
                }
                // "Change" (mockup): another Frame.io account, or another Adobe ID.
                Box {
                    var menu by remember { mutableStateOf(false) }
                    Text(
                        stringResource(R.string.settings_change_account), style = MaterialTheme.typography.labelLarge, color = Palette.Text,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(Palette.Surface3)
                            .pressable({ menu = true }, scaleTo = 0.94f).padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                    androidx.compose.material3.DropdownMenu(menu, onDismissRequest = { menu = false }, shape = dev.finalish.ui.theme.MenuShape, containerColor = Palette.Surface3) {
                        session.accounts.forEach { a ->
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(a.displayName) },
                                trailingIcon = { if (a.id == session.accountId) Icon(Icons.Rounded.Check, stringResource(R.string.settings_current_account), tint = Palette.AccentBright) },
                                onClick = { menu = false; if (a.id != session.accountId) onSelectAccount(a.id) },
                            )
                        }
                        androidx.compose.material3.HorizontalDivider(color = Palette.Hairline)
                        androidx.compose.material3.DropdownMenuItem(text = { Text(stringResource(R.string.settings_use_another_adobe_id)) }, onClick = { menu = false; onSignOut() })
                    }
                }
            }
        }

        item { SectionHeader(stringResource(R.string.settings_section_appearance)) }
        item {
            Group {
                val accentName = if (settings.dynamicAccent) stringResource(R.string.settings_accent_wallpaper)
                    else accentLabel(dev.finalish.ui.theme.AccentTheme.entries.firstOrNull { it.name == settings.accent } ?: dev.finalish.ui.theme.AccentTheme.Glicine)
                Row0(Icons.Rounded.Palette, stringResource(R.string.settings_theme_and_colors), trailing = accentName) { themeSheet = true }
                Divider()
                val activity = context.findActivity()
                // Read once per composition: the screen is recreated when the language changes.
                val language = remember { dev.finalish.core.util.AppLocale.current(context) }
                ChoiceRow(
                    Icons.Rounded.Language, stringResource(R.string.settings_language), language,
                    listOf<String?>(null) + dev.finalish.core.util.AppLocale.supported,
                    labelOf = { tag -> if (tag == null) stringResource(R.string.settings_language_device) else languageName(tag) },
                ) { tag -> if (tag != language && activity != null) dev.finalish.core.util.AppLocale.set(activity, tag) }
            }
        }

        item { SectionHeader(stringResource(R.string.settings_section_playback)) }
        item {
            Group {
                ChoiceRow(
                    Icons.Rounded.Tune, stringResource(R.string.settings_default_quality), settings.quality, PlaybackQuality.entries, { stringResource(it.labelRes) },
                ) { q -> container.settings.update { it.copy(quality = q) } }
                Divider()
                ToggleRow(Icons.Rounded.PlayCircle, stringResource(R.string.settings_autoplay_videos), settings.autoplay) { v -> container.settings.update { it.copy(autoplay = v) } }
                Divider()
                ToggleRow(Icons.Rounded.Repeat, stringResource(R.string.settings_loop_playback), settings.loopPlayback) { v -> container.settings.update { it.copy(loopPlayback = v) } }
                Divider()
                ToggleRow(Icons.Rounded.Pause, stringResource(R.string.settings_pause_while_commenting), settings.pauseOnComment) { v -> container.settings.update { it.copy(pauseOnComment = v) } }
                Divider()
                ToggleRow(Icons.Rounded.PictureInPictureAlt, stringResource(R.string.settings_picture_in_picture), settings.pictureInPicture) { v -> container.settings.update { it.copy(pictureInPicture = v) } }
                Divider()
                ToggleRow(Icons.Rounded.NetworkCell, stringResource(R.string.settings_scrub_on_mobile_data), settings.scrubOnMobileData) { v -> container.settings.update { it.copy(scrubOnMobileData = v) } }
                Divider()
                ChoiceRow(
                    Icons.Rounded.Timer, stringResource(R.string.settings_time_display), settings.timeFormat, dev.finalish.core.data.TimeFormat.entries, { stringResource(it.labelRes) },
                ) { f -> container.settings.update { it.copy(timeFormat = f) } }
            }
        }

        item { SectionHeader(stringResource(R.string.settings_section_notifications)) }
        item {
            Group {
                ToggleRow(Icons.Rounded.Notifications, stringResource(R.string.settings_activity_notifications), settings.notifications) { v ->
                    container.settings.update { it.copy(notifications = v) }
                    if (v) dev.finalish.core.notify.ActivityWatch.checkSoon(context)
                }
                if (settings.notifications) {
                    Divider()
                    ToggleRow(Icons.Rounded.ChatBubbleOutline, stringResource(R.string.settings_notify_new_comments), settings.notifyComments) { v -> container.settings.update { it.copy(notifyComments = v) } }
                    Divider()
                    ToggleRow(Icons.Rounded.CloudDone, stringResource(R.string.settings_notify_uploads_ready), settings.notifyUploads) { v -> container.settings.update { it.copy(notifyUploads = v) } }
                    Divider()
                    ToggleRow(Icons.Rounded.Visibility, stringResource(R.string.settings_notify_share_link_views), settings.notifyShareViews) { v -> container.settings.update { it.copy(notifyShareViews = v) } }
                }
            }
        }
        item {
            Text(
                stringResource(R.string.settings_notifications_hint),
                style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }

        item { SectionHeader(stringResource(R.string.settings_section_transfers)) }
        item {
            Group {
                ToggleRow(Icons.Rounded.NetworkCell, stringResource(R.string.settings_use_mobile_data), settings.cellularTransfers) { v ->
                    container.settings.update { it.copy(cellularTransfers = v) }
                }
            }
        }

        item { SectionHeader(stringResource(R.string.settings_section_account)) }
        item {
            Group {
                Row0(Icons.Rounded.Groups, stringResource(R.string.settings_groups), onClick = onOpenGroups)
                Divider()
                Row0(Icons.Rounded.Tune, stringResource(R.string.settings_metadata_fields), onClick = onOpenMetadataFields)
            }
        }

        item { SectionHeader(stringResource(R.string.settings_section_storage)) }
        item {
            val offline by androidx.compose.runtime.produceState(0L) {
                value = withContext(Dispatchers.IO) { container.transfers.offlineBytes() }
            }
            val projects by androidx.compose.runtime.produceState(emptyList<dev.finalish.core.model.Project>(), session.accountId) {
                val acc = session.accountId ?: return@produceState
                runCatching {
                    container.repository.workspacesWithProjects(acc).first { r -> r.data != null }.data.orEmpty().flatMap { it.projects }
                }.onSuccess { value = it.distinctBy { p -> p.id } }
            }
            StorageCard(
                account = session.account,
                projects = projects,
                offlineBytes = offline,
                cacheBytes = cacheSize,
                onClearCache = {
                    scope.launch {
                        cacheSize = withContext(Dispatchers.IO) {
                            container.clearCaches()
                            dirBytes(context.cacheDir)
                        }
                    }
                },
            )
        }

        item { SectionHeader(stringResource(R.string.settings_section_feedback)) }
        item {
            val activity = androidx.activity.compose.LocalActivity.current
            Group {
                Row0(Icons.Rounded.BugReport, stringResource(R.string.settings_report_bug)) {
                    scope.launch { dev.finalish.core.feedback.FeedbackLauncher.launch(activity, dev.finalish.core.feedback.FeedbackKind.Bug, capture = false) }
                }
                Divider()
                Row0(Icons.Rounded.AutoAwesome, stringResource(R.string.settings_suggest_feature)) {
                    scope.launch { dev.finalish.core.feedback.FeedbackLauncher.launch(activity, dev.finalish.core.feedback.FeedbackKind.Feature, capture = false) }
                }
                Divider()
                ToggleRow(Icons.Rounded.Vibration, stringResource(R.string.settings_shake_to_report), settings.shakeToReport) { v -> container.settings.update { it.copy(shakeToReport = v) } }
            }
        }
        item {
            Text(
                stringResource(R.string.settings_shake_to_report_hint),
                style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }

        item { SectionHeader(stringResource(R.string.settings_section_permissions)) }
        item {
            val (perms, request) = dev.finalish.ui.onboarding.rememberPermissionStates()
            Group {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    dev.finalish.ui.onboarding.AppPermission.entries.forEachIndexed { i, p ->
                        val granted = perms[p] == true
                        dev.finalish.ui.onboarding.PermissionRow(
                            p, granted, divider = i > 0,
                            onAllow = { request(p) },
                        )
                    }
                }
            }
            Text(
                stringResource(R.string.settings_revoke_permission_hint),
                style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                    .pressable({ dev.finalish.ui.onboarding.openAppSettings(context) }, scaleTo = 0.98f),
            )
        }

        item { SectionHeader(stringResource(R.string.settings_section_about)) }
        item {
            Group {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    LogoMark(Modifier.size(40.dp))
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("Final(ish) ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(R.string.settings_about_disclaimer),
                            style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary,
                        )
                    }
                }
                Divider()
                Row0(Icons.Rounded.Code, stringResource(R.string.settings_source_code), trailing = "GPL-3.0") { openUrl(context, SOURCE_URL) }
                Divider()
                Row0(Icons.Rounded.PrivacyTip, stringResource(R.string.settings_privacy_policy)) { openUrl(context, PRIVACY_URL) }
                Divider()
                Row0(Icons.Rounded.Description, stringResource(R.string.settings_terms_of_use)) { openUrl(context, TERMS_URL) }
                Divider()
                Row0(Icons.Rounded.Gavel, stringResource(R.string.settings_open_source_licenses), onClick = onOpenLicenses)
                Divider()
                Row0(Icons.Rounded.Info, stringResource(R.string.settings_sign_in_method), trailing = if (container.auth.isDemo) stringResource(R.string.settings_sign_in_demo) else "Adobe ID")
            }
        }
        item {
            Text(
                stringResource(R.string.settings_trademarks_notice, stringResource(DEMO_MEDIA_CREDITS)),
                style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
        }
        item {
            Group(Modifier.padding(top = 20.dp)) {
                Row0(Icons.AutoMirrored.Rounded.Logout, stringResource(R.string.settings_sign_out), tint = Palette.Red, onClick = onSignOut)
            }
        }
    }
    if (themeSheet) {
        ThemeSheet(settings, onChange = { f -> container.settings.update(f) }, onDismiss = { themeSheet = false })
    }
}

@Composable
private fun Group(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Palette.Surface2),
    ) { content() }
}

@Composable
private fun Divider() = HorizontalDivider(Modifier.padding(start = 54.dp), color = Palette.Hairline)

@Composable
private fun Box(content: @Composable () -> Unit) = androidx.compose.foundation.layout.Box { content() }

@Composable
private fun Row0(icon: ImageVector, label: String, trailing: String? = null, tint: Color = Palette.Text, onClick: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.pressable(onClick, scaleTo = 0.98f) else Modifier)
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (tint == Palette.Text) Palette.TextSecondary else tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = tint, modifier = Modifier.weight(1f))
        trailing?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Palette.TextTertiary) }
    }
}

/** A row whose value opens a small menu right at the value, not at the row's start. */
@Composable
private fun <T> ChoiceRow(icon: ImageVector, label: String, value: T, options: List<T>, labelOf: @Composable (T) -> String, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .pressable({ open = true }, scaleTo = 0.98f)
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Palette.TextSecondary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Box {
            Text(labelOf(value), style = MaterialTheme.typography.bodyMedium, color = Palette.TextTertiary)
            DropdownMenu(open, onDismissRequest = { open = false }, shape = dev.finalish.ui.theme.MenuShape, containerColor = Palette.Surface3) {
                options.forEach { o ->
                    DropdownMenuItem(
                        text = { Text(labelOf(o)) },
                        trailingIcon = { if (o == value) Icon(Icons.Rounded.Check, null, tint = Palette.Accent) },
                        onClick = { open = false; onSelect(o) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(icon: ImageVector, label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Palette.TextSecondary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Palette.Accent, checkedThumbColor = Palette.OnAccent),
        )
    }
}

/** Space used on Frame.io (split by the biggest projects) and on this phone (offline files + cache). */
@Composable
private fun StorageCard(
    account: dev.finalish.core.model.Account?,
    projects: List<dev.finalish.core.model.Project>,
    offlineBytes: Long,
    cacheBytes: Long,
    onClearCache: () -> Unit,
) {
    val segmentColors = listOf(Palette.Green, Palette.Amber, Palette.Blue, Palette.Accent, Palette.Red, Color(0xFF2BD9C2), Color(0xFFE8508F), Color(0xFFF8D51C))
    var showAll by remember { mutableStateOf(false) }
    Column(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Palette.Surface2).padding(16.dp),
    ) {
        if (account != null && account.storageUsage > 0) {
            val limit = account.storageLimit?.takeIf { it > 0 }
            val total = (limit ?: account.storageUsage).toFloat()
            Row {
                Text("Frame.io", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(
                    if (limit != null) stringResource(R.string.settings_storage_used_of_limit, Format.bytes(account.storageUsage), Format.bytes(limit))
                    else stringResource(R.string.settings_storage_used, Format.bytes(account.storageUsage)),
                    style = MaterialTheme.typography.bodyMedium, color = Palette.TextTertiary,
                )
            }
            // Every project, largest first; whatever isn't in a project (versions, trash…) is "Other".
            val all = projects.filter { it.storage > 0 }.sortedByDescending { it.storage }
            val colorOf = { i: Int -> segmentColors[i % segmentColors.size] }
            val other = (account.storageUsage - all.sumOf { it.storage }).coerceAtLeast(0)
            val otherColor = Palette.TextTertiary.copy(alpha = 0.5f)
            StorageBar(all.mapIndexed { i, p -> p.storage / total to colorOf(i) } + listOf(other / total to otherColor))
            val otherLabel = stringResource(R.string.settings_storage_other)
            val rows = all.mapIndexed { i, p -> Triple(p.name, p.storage, colorOf(i)) } +
                listOfNotNull(Triple(otherLabel, other, otherColor).takeIf { other > 0 })
            val visible = if (showAll) rows else rows.take(4)
            Column(Modifier.padding(top = 10.dp)) {
                visible.forEach { (name, bytes, c) ->
                    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.foundation.layout.Box(Modifier.size(8.dp).clip(androidx.compose.foundation.shape.CircleShape).background(c))
                        Spacer(Modifier.width(10.dp))
                        Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(Format.bytes(bytes), style = MaterialTheme.typography.bodyMedium, color = Palette.TextTertiary)
                    }
                }
                if (rows.size > 4) {
                    Text(
                        if (showAll) stringResource(R.string.settings_show_less) else stringResource(R.string.settings_show_all, rows.size),
                        style = MaterialTheme.typography.labelLarge, color = Palette.Link,
                        modifier = Modifier.padding(top = 4.dp).clip(RoundedCornerShape(8.dp)).pressable({ showAll = !showAll }, scaleTo = 0.94f).padding(vertical = 4.dp),
                    )
                }
            }
            androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 14.dp), color = Palette.Surface3)
        }
        Row {
            Text(stringResource(R.string.settings_storage_on_this_phone), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(Format.bytes(offlineBytes + cacheBytes), style = MaterialTheme.typography.bodyMedium, color = Palette.TextTertiary)
        }
        val phoneTotal = (offlineBytes + cacheBytes).coerceAtLeast(1).toFloat()
        StorageBar(listOf(offlineBytes / phoneTotal to Palette.Accent, cacheBytes / phoneTotal to Palette.Blue))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Legend(listOf(stringResource(R.string.settings_storage_offline, Format.bytes(offlineBytes)) to Palette.Accent, stringResource(R.string.settings_storage_cache, Format.bytes(cacheBytes)) to Palette.Blue), Modifier.weight(1f))
            Text(
                stringResource(R.string.settings_clear_cache), style = MaterialTheme.typography.labelLarge, color = Palette.Link,
                modifier = Modifier.padding(top = 8.dp).clip(RoundedCornerShape(8.dp)).pressable(onClearCache, scaleTo = 0.94f).padding(4.dp),
            )
        }
    }
}

@Composable
private fun StorageBar(parts: List<Pair<Float, Color>>) {
    Row(Modifier.padding(top = 10.dp).fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Palette.Surface3)) {
        parts.forEach { (f, c) ->
            val w = f.coerceIn(0f, 1f)
            if (w > 0.002f) androidx.compose.foundation.layout.Box(Modifier.weight(w).height(8.dp).background(c))
        }
        val used = parts.sumOf { it.first.coerceIn(0f, 1f).toDouble() }.toFloat()
        if (used < 0.998f) Spacer(Modifier.weight(1f - used))
    }
}

@Composable
private fun Legend(items: List<Pair<String, Color>>, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.FlowRow(
        modifier.padding(top = 8.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(14.dp),
    ) {
        items.forEach { (label, c) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.foundation.layout.Box(Modifier.size(7.dp).clip(androidx.compose.foundation.shape.CircleShape).background(c))
                Spacer(Modifier.width(5.dp))
                Text(label, style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary, maxLines = 1)
            }
        }
    }
}

/** Accent names are shown in the UI; [dev.finalish.ui.theme.AccentTheme.label] stays the English name. */
@Composable
internal fun accentLabel(t: dev.finalish.ui.theme.AccentTheme): String = stringResource(
    when (t) {
        dev.finalish.ui.theme.AccentTheme.Glicine -> R.string.settings_accent_wisteria
        dev.finalish.ui.theme.AccentTheme.Lavanda -> R.string.settings_accent_lavender
        dev.finalish.ui.theme.AccentTheme.Ambra -> R.string.settings_accent_amber
        dev.finalish.ui.theme.AccentTheme.Lime -> R.string.settings_accent_lime
        dev.finalish.ui.theme.AccentTheme.Menta -> R.string.settings_accent_mint
        dev.finalish.ui.theme.AccentTheme.Corallo -> R.string.settings_accent_coral
        dev.finalish.ui.theme.AccentTheme.Ciano -> R.string.settings_accent_cyan
        dev.finalish.ui.theme.AccentTheme.Mono -> R.string.settings_accent_black_and_white
    },
)


/** A language's own name ("Italiano", "Deutsch"), so people find theirs whatever the app's language. */
private fun languageName(tag: String): String {
    val locale = java.util.Locale.forLanguageTag(tag)
    return locale.getDisplayName(locale).replaceFirstChar { it.titlecase(locale) }
}

private fun android.content.Context.findActivity(): android.app.Activity? {
    var c: android.content.Context? = this
    while (c is android.content.ContextWrapper) {
        if (c is android.app.Activity) return c
        c = c.baseContext
    }
    return null
}
