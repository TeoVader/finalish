package dev.finalish.ui.shares

import androidx.compose.foundation.verticalScroll
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.finalish.FinalishApp
import dev.finalish.R
import dev.finalish.core.model.Asset
import dev.finalish.core.model.Share
import dev.finalish.core.network.userMessage
import dev.finalish.core.network.PlanLimits
import dev.finalish.ui.components.PrimaryButton
import dev.finalish.ui.components.SecondaryButton
import dev.finalish.ui.home.Chip
import dev.finalish.ui.theme.Motion
import dev.finalish.ui.theme.Palette
import kotlinx.coroutines.launch
import androidx.annotation.StringRes
import java.time.Instant
import java.time.temporal.ChronoUnit

private enum class Expiry(@StringRes val labelRes: Int, val days: Long?) {
    Never(R.string.shares_expiry_never, null), Day(R.string.shares_expiry_1_day, 1), Week(R.string.shares_expiry_7_days, 7), Month(R.string.shares_expiry_30_days, 30),
}

@Composable
fun CreateShareSheet(
    accountId: String,
    projectId: String,
    assets: List<Asset>,
    onDismiss: () -> Unit,
    onChangeAssets: (() -> Unit)? = null,
    onCreated: (Share) -> Unit = {},
) {
    val context = LocalContext.current
    val repo = remember { FinalishApp.container(context).repository }
    val scope = rememberCoroutineScope()
    val itemsName = pluralStringResource(R.plurals.shares_n_items, assets.size, assets.size)
    var name by remember { mutableStateOf(if (assets.size == 1) assets.first().name else itemsName) }
    var downloads by remember { mutableStateOf(false) }
    var comments by remember { mutableStateOf(true) }
    var secure by remember { mutableStateOf(false) }
    var allVersions by remember { mutableStateOf(false) }
    var usePass by remember { mutableStateOf(false) }
    var pass by remember { mutableStateOf("") }
    var expiry by remember { mutableStateOf(Expiry.Never) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var created by remember { mutableStateOf<Share?>(null) }
    // What this account's plan turned out to lack: greyed out instead of failing again.
    val missing = PlanLimits.missing.collectAsStateWithLifecycle().value[accountId].orEmpty()
    val noSecure = "secure_sharing" in missing
    val noPass = "password_protected_shares" in missing
    val noExpiry = "share_link_expiration" in missing
    LaunchedEffect(missing) {
        if (noSecure) secure = false
        if (noPass) usePass = false
        if (noExpiry) expiry = Expiry.Never
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Palette.Surface1,
    ) {
        AnimatedContent(created, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "share") { share ->
            Column(
                Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()).navigationBarsPadding().imePadding().padding(horizontal = 20.dp).padding(bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (share == null) {
                    Text(stringResource(R.string.shares_new_link_title), style = MaterialTheme.typography.headlineSmall)
                    SelectionSummary(assets, onChangeAssets)
                    OutlinedTextField(
                        value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.shares_name)) }, singleLine = true,
                        shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Palette.Accent, unfocusedBorderColor = Palette.Outline),
                    )
                    Text(stringResource(R.string.shares_who_can_open), style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip(stringResource(R.string.shares_anyone_with_link), !secure) { secure = false }
                        Chip(stringResource(R.string.shares_only_invited), secure, enabled = !noSecure) { secure = true }
                    }
                    Column(
                        Modifier.clip(RoundedCornerShape(16.dp)).background(Palette.Surface2),
                    ) {
                        ToggleRow(stringResource(R.string.shares_allow_comments), comments) { comments = it }
                        ToggleRow(stringResource(R.string.shares_allow_downloads), downloads) { downloads = it }
                        ToggleRow(stringResource(R.string.shares_show_all_versions), allVersions) { allVersions = it }
                        ToggleRow(stringResource(R.string.shares_require_passphrase), usePass, enabled = !noPass) { usePass = it }
                    }
                    AnimatedVisibility(usePass) {
                        OutlinedTextField(
                            value = pass, onValueChange = { pass = it },
                            label = { Text(stringResource(R.string.shares_passphrase)) }, singleLine = true,
                            shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Text(stringResource(R.string.shares_expires), style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Expiry.entries.forEach { e -> Chip(stringResource(e.labelRes), expiry == e, enabled = e == Expiry.Never || !noExpiry) { expiry = e } }
                    }
                    if (noSecure || noPass || noExpiry) {
                        val inviteOnly = stringResource(R.string.shares_plan_invite_only_links)
                        val passphrases = stringResource(R.string.shares_plan_passphrases)
                        val expiryDates = stringResource(R.string.shares_plan_expiry_dates)
                        Text(
                            stringResource(
                                R.string.shares_not_on_plan,
                                listOfNotNull(
                                    inviteOnly.takeIf { noSecure }, passphrases.takeIf { noPass }, expiryDates.takeIf { noExpiry },
                                ).joinToString(", "),
                            ),
                            style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary,
                        )
                    }
                    error?.let { Text(it, color = Palette.Red, style = MaterialTheme.typography.bodySmall) }
                    PrimaryButton(
                        if (busy) stringResource(R.string.shares_creating) else stringResource(R.string.shares_create_and_copy),
                        enabled = !busy && name.isNotBlank(),
                        icon = Icons.Rounded.Link,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            busy = true
                            error = null
                            scope.launch {
                                runCatching {
                                    repo.createShare(
                                        accountId, projectId, name.trim(), assets.map { it.id },
                                        downloads = downloads, comments = comments,
                                        passphrase = if (usePass) pass else null,
                                        expiration = expiry.days?.let { Instant.now().plus(it, ChronoUnit.DAYS).toString() },
                                        access = if (secure) "secure" else "public",
                                        showAllVersions = allVersions,
                                    )
                                }.onSuccess {
                                    it.shortUrl?.let { url -> copyToClipboard(context, url) }
                                    created = it
                                    onCreated(it)
                                    dev.finalish.FinalishApp.container(context).activityWatch
                                        .watchShare(accountId, projectId, it.id, it.name ?: name.trim())
                                }.onFailure { error = it.userMessage() }
                                busy = false
                            }
                        },
                    )
                } else {
                    ShareResult(share, context)
                }
            }
        }
    }
}

@Composable
private fun ShareResult(share: Share, context: Context) {
    // The link is copied as soon as it's created.
    var copied by remember { mutableStateOf(share.shortUrl != null) }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(
            Modifier.size(64.dp).clip(CircleShape).background(Palette.Green.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            // Starts hidden so the check actually pops in (a visibility that starts true never animates).
            val shown = remember { androidx.compose.animation.core.MutableTransitionState(false).apply { targetState = true } }
            androidx.compose.animation.AnimatedVisibility(shown, enter = scaleIn(Motion.snappy()) + androidx.compose.animation.fadeIn()) {
                Icon(Icons.Rounded.Check, null, tint = Palette.Green, modifier = Modifier.size(32.dp))
            }
        }
        Text(if (copied) stringResource(R.string.shares_link_ready_copied) else stringResource(R.string.shares_link_ready), style = MaterialTheme.typography.headlineSmall)
        val url = share.shortUrl ?: ""
        Text(
            url,
            style = MaterialTheme.typography.bodyLarge,
            color = Palette.AccentBright,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Palette.Surface2)
                .border(1.dp, Palette.Hairline, RoundedCornerShape(12.dp))
                .padding(14.dp),
        )
        share.passphrase?.takeIf { it.isNotBlank() }?.let {
            Text(stringResource(R.string.shares_passphrase_value, it), style = MaterialTheme.typography.bodyMedium, color = Palette.TextSecondary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton(
                if (copied) stringResource(R.string.shares_copied) else stringResource(R.string.shares_copy),
                icon = if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                modifier = Modifier.weight(1f),
                onClick = {
                    copyToClipboard(context, url)
                    copied = true
                },
            )
            PrimaryButton(
                stringResource(R.string.shares_share), icon = Icons.Rounded.IosShare, modifier = Modifier.weight(1f),
                onClick = { shareText(context, url, share.name) },
            )
        }
        Spacer(Modifier.height(4.dp))
    }
}

/** "3 files · 1 folder", with Change when the caller can re-pick. */
@Composable
private fun SelectionSummary(assets: List<Asset>, onChange: (() -> Unit)?) {
    val folders = assets.count { it.isFolder }
    val files = assets.size - folders
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.Surface2).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val filesText = pluralStringResource(R.plurals.shares_n_files, files, files)
        val foldersText = pluralStringResource(R.plurals.shares_n_folders, folders, folders)
        Text(
            stringResource(
                R.string.shares_selected,
                listOfNotNull(
                    filesText.takeIf { files > 0 },
                    foldersText.takeIf { folders > 0 },
                ).joinToString(" · "),
            ),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        if (onChange != null) {
            androidx.compose.material3.TextButton(onClick = onChange) { Text(stringResource(R.string.shares_change), color = Palette.AccentBright) }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = if (enabled) Palette.Text else Palette.TextTertiary, modifier = Modifier.weight(1f))
        Switch(
            checked = checked, onCheckedChange = onChange, enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = Palette.Accent, checkedThumbColor = Palette.OnAccent),
        )
    }
}

fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(ClipboardManager::class.java)
    cm.setPrimaryClip(ClipData.newPlainText("Link", text))
}

fun shareText(context: Context, text: String, subject: String? = null) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
        subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
    }
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
