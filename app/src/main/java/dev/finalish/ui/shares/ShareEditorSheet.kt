package dev.finalish.ui.shares

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.ModeComment
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.core.graphics.set
import androidx.core.net.toUri
import androidx.core.graphics.toColorInt
import dev.finalish.FinalishApp
import dev.finalish.R
import dev.finalish.core.model.Asset
import dev.finalish.core.model.Share
import dev.finalish.core.network.userMessage
import dev.finalish.core.util.Format
import dev.finalish.ui.browser.AssetThumbnail
import dev.finalish.ui.browser.TextPromptDialog
import dev.finalish.ui.components.pressable
import dev.finalish.ui.home.Chip
import dev.finalish.ui.theme.Palette
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/** Share link settings, mirroring the official share panel. */
@Composable
fun ShareEditorSheet(
    accountId: String,
    initial: Share,
    onChanged: (Share) -> Unit,
    onMessage: (String) -> Unit,
    onDismiss: () -> Unit,
    projectId: String? = null,
    onDelete: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val res = androidx.compose.ui.platform.LocalResources.current
    val container = remember { FinalishApp.container(context) }
    val repo = container.repository
    val scope = rememberCoroutineScope()
    var share by remember { mutableStateOf(initial) }
    val patchSeq = remember { java.util.concurrent.atomic.AtomicInteger() }
    val patchesInFlight = remember { java.util.concurrent.atomic.AtomicInteger() }
    var assets by remember { mutableStateOf<List<Asset>?>(null) }
    var reviewers by remember { mutableStateOf<List<dev.finalish.core.model.User>?>(null) }
    var activity by remember { mutableStateOf<List<dev.finalish.core.model.ShareActivity>?>(null) }
    var inviting by remember { mutableStateOf(false) }
    var describing by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var passphrase by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var qr by remember { mutableStateOf(false) }
    var pickingTemplate by remember { mutableStateOf(false) }
    var templates by remember { mutableStateOf<List<dev.finalish.core.model.WatermarkTemplate>?>(null) }
    var templateId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(initial.id) { templateId = repo.shareWatermarkTemplate(initial.id) }
    // The list endpoint omits layout/watermark/etc., so load the full share first.
    var loaded by remember { mutableStateOf(false) }
    val snack = remember { androidx.compose.material3.SnackbarHostState() }
    val notify: (String) -> Unit = { m -> scope.launch { snack.currentSnackbarData?.dismiss(); snack.showSnackbar(m) } }

    LaunchedEffect(initial.id) {
        // GET omits layout/watermark/versions/etc.; a no-op PATCH (same name) returns the full share.
        runCatching {
            val current = repo.share(accountId, initial.id)
            repo.updateShare(accountId, initial.id, mapOf("name" to JsonPrimitive(current.name ?: initial.name.orEmpty())))
        }.onSuccess { share = it.copy(shortUrl = it.shortUrl ?: initial.shortUrl); onChanged(share) }
        loaded = true
        // Opening a link's settings means you care about it: notify when it's viewed.
        container.activityWatch.watchShare(accountId, projectId.orEmpty(), share.id, share.name ?: res.getString(R.string.shares_share_link))
        assets = runCatching { repo.shareAssets(accountId, initial.id) }.getOrDefault(emptyList())
        // Only secure (invite-only) shares have reviewers; public ones answer 400.
        reviewers = if (share.access == "secure") runCatching { repo.shareReviewers(accountId, initial.id) }.getOrDefault(emptyList()) else emptyList()
        activity = runCatching { repo.shareActivities(accountId, initial.id) }.getOrDefault(emptyList())
    }

    /**
     * Optimistic update of one field, reverted (with the API's reason) on failure. With quick
     * toggles several patches overlap: only the last one back is applied, so an earlier answer
     * (which lacks the later change) can't overwrite the screen.
     */
    fun patch(local: Share, field: String, value: JsonElement) {
        if (!loaded) return
        val previous = share
        share = local
        val seq = patchSeq.incrementAndGet()
        patchesInFlight.incrementAndGet()
        scope.launch {
            // The API rejects theme/accent changes unless the layout is sent alongside.
            val fields = buildMap {
                put(field, value)
                if (field in setOf("theme", "accent_color")) put("layout", JsonPrimitive(local.layout ?: "grid"))
            }
            val result = runCatching { repo.updateShare(accountId, share.id, fields) }
            val last = patchesInFlight.decrementAndGet() == 0
            result
                .onSuccess { updated ->
                    if (last) {
                        share = updated.copy(shortUrl = updated.shortUrl ?: share.shortUrl)
                        onChanged(share)
                    }
                }
                .onFailure {
                    // Undo only when nothing newer was changed since; otherwise a later answer settles it.
                    if (last && seq == patchSeq.get()) share = previous
                    notify(it.userMessage())
                }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Palette.Surface1,
    ) {
        Column(
            Modifier
                .weight(1f, fill = false)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // What the link shows: its first file or folder cover, or a mosaic of the first few. Landscape phones put it
            // beside the name, or the cover alone would fill the little height there is.
            val compact = with(androidx.compose.ui.platform.LocalDensity.current) {
                androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.height.toDp() < 480.dp
            }
            val title: @Composable () -> Unit = {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            share.name ?: stringResource(R.string.shares_untitled),
                            style = MaterialTheme.typography.headlineSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            Icons.Rounded.Edit, stringResource(R.string.shares_rename), tint = Palette.TextSecondary,
                            modifier = Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).pressable({ rename = true }).padding(8.dp),
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        val (label, fg) = if (share.enabled) stringResource(R.string.shares_status_active) to Palette.Green else stringResource(R.string.shares_status_turned_off) to Palette.TextSecondary
                        Text(
                            label, style = MaterialTheme.typography.labelMedium, color = fg,
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(fg.copy(alpha = 0.16f)).padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                        Text(
                            listOf(
                                layoutLabelRes(share.layout ?: "grid")?.let { stringResource(it) }
                                    ?: (share.layout ?: "grid").replaceFirstChar { it.uppercase() },
                                if (share.access == "public") stringResource(R.string.shares_access_public) else stringResource(R.string.shares_only_invited),
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary,
                        )
                    }
                }
            }
            if (compact) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    ShareMosaic(accountId, share, assets.orEmpty(), Modifier.width(176.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)), iconSize = 28.dp)
                    Box(Modifier.weight(1f)) { title() }
                }
            } else {
                // A link with nothing in it gets a short band, not a half-sheet of empty colour.
                val header = if (assets?.isEmpty() == true) Modifier.fillMaxWidth().height(88.dp) else Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                ShareMosaic(accountId, share, assets.orEmpty(), header.clip(RoundedCornerShape(16.dp)), iconSize = 34.dp)
                title()
            }

            // Link
            Card {
                Row(Modifier.padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.shares_link_visibility), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (share.enabled) stringResource(R.string.shares_anyone_can_view) else stringResource(R.string.shares_link_turned_off),
                            style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary,
                        )
                    }
                    AppSwitch(share.enabled) { patch(share.copy(enabled = it), "enabled", JsonPrimitive(it)) }
                }
                share.shortUrl?.let { url ->
                    HorizontalDivider(color = Palette.Hairline)
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(url, color = Palette.AccentBright, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1)
                        val linkCopied = stringResource(R.string.shares_link_copied)
                        Icon(
                            Icons.Rounded.ContentCopy, stringResource(R.string.shares_copy), tint = Palette.TextSecondary,
                            modifier = Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)).pressable({ copyToClipboard(context, url); notify(linkCopied) }).padding(7.dp),
                        )
                        Icon(
                            Icons.Rounded.IosShare, stringResource(R.string.shares_share), tint = Palette.TextSecondary,
                            modifier = Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)).pressable({ shareText(context, url, share.name) }).padding(7.dp),
                        )
                        Icon(
                            Icons.Rounded.QrCode2, stringResource(R.string.shares_qr_code), tint = Palette.TextSecondary,
                            modifier = Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)).pressable({ qr = true }).padding(7.dp),
                        )
                        Icon(
                            Icons.AutoMirrored.Rounded.OpenInNew, stringResource(R.string.shares_open), tint = Palette.TextSecondary,
                            modifier = Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)).pressable({
                                runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, url.toUri())) }
                            }).padding(7.dp),
                        )
                    }
                }
            }

            activity?.let { list -> ShareStats(list) }

            // What this account's plan turned out to lack (learned from earlier refusals).
            val missing = dev.finalish.core.network.PlanLimits.missing.collectAsStateWithLifecycle().value[accountId].orEmpty()
            Label(stringResource(R.string.shares_label_access))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(stringResource(R.string.shares_anyone_with_link), share.access == "public") { patch(share.copy(access = "public"), "access", JsonPrimitive("public")) }
                Chip(stringResource(R.string.shares_only_invited), share.access == "secure", enabled = "secure_sharing" !in missing || share.access == "secure") {
                    patch(share.copy(access = "secure"), "access", JsonPrimitive("secure"))
                }
            }
            if (share.access == "secure") Card {
                Row(
                    Modifier.fillMaxWidth().pressable({ inviting = true }, scaleTo = 0.99f).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.PersonAdd, null, tint = Palette.AccentBright, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.shares_invite_reviewers), style = MaterialTheme.typography.bodyLarge, color = Palette.AccentBright, modifier = Modifier.weight(1f))
                    Text("${reviewers?.size ?: 0}", color = Palette.TextTertiary)
                }
                reviewers.orEmpty().forEach { u ->
                    Divider()
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        dev.finalish.ui.components.Avatar(u, 30.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(u.displayName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (u.email != u.displayName) Text(u.email.orEmpty(), style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary)
                        }
                        Icon(
                            Icons.Rounded.Close, stringResource(R.string.shares_remove_reviewer), tint = Palette.TextTertiary,
                            modifier = Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).pressable({
                                val before = reviewers
                                reviewers = reviewers?.filterNot { it == u }
                                scope.launch {
                                    runCatching { repo.removeShareReviewer(accountId, share.id, u) }
                                        .onFailure { reviewers = before; notify(it.userMessage()) }
                                }
                            }).padding(6.dp),
                        )
                    }
                }
            }

            Label(stringResource(R.string.shares_label_permissions))
            Card {
                ToggleLine(stringResource(R.string.shares_allow_comments), share.commentingEnabled) { patch(share.copy(commentingEnabled = it), "commenting_enabled", JsonPrimitive(it)) }
                Divider()
                ToggleLine(stringResource(R.string.shares_allow_downloads), share.downloadingEnabled) { patch(share.copy(downloadingEnabled = it), "downloading_enabled", JsonPrimitive(it)) }
                Divider()
                ToggleLine(stringResource(R.string.shares_show_all_versions), share.showAllVersions == true) { patch(share.copy(showAllVersions = it), "show_all_asset_versions", JsonPrimitive(it)) }
                Divider()
                ToggleLine(stringResource(R.string.shares_show_transcripts), share.transcriptsEnabled == true) { patch(share.copy(transcriptsEnabled = it), "transcripts_enabled", JsonPrimitive(it)) }
                Divider()
                ToggleLine(stringResource(R.string.shares_show_captions), share.captionsEnabled == true) { patch(share.copy(captionsEnabled = it), "captions_enabled", JsonPrimitive(it)) }
            }

            Label(stringResource(R.string.shares_label_layout))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("grid" to R.string.shares_layout_grid, "list" to R.string.shares_layout_list, "reel" to R.string.shares_layout_reel).forEach { (key, label) ->
                    Chip(stringResource(label), (share.layout ?: "grid") == key) { patch(share.copy(layout = key), "layout", JsonPrimitive(key)) }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("dark" to R.string.shares_theme_dark, "light" to R.string.shares_theme_light, "neutral" to R.string.shares_theme_neutral).forEach { (key, label) ->
                    Chip(stringResource(label), (share.theme ?: "dark") == key) { patch(share.copy(theme = key), "theme", JsonPrimitive(key)) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.shares_accent), style = MaterialTheme.typography.bodyMedium, color = Palette.TextSecondary)
                listOf("#7C6CFF", "#4DA3FF", "#3DD68C", "#F5B83D", "#FF5A6A", "#FF6BC1", "#FFFFFF").forEach { hex ->
                    val c = Color(hex.toColorInt())
                    val sel = share.accentColor.equals(hex, ignoreCase = true)
                    Box(
                        Modifier
                            .size(if (sel) 28.dp else 24.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(c)
                            .then(if (sel) Modifier.border(2.dp, Palette.Text, androidx.compose.foundation.shape.CircleShape) else Modifier)
                            .pressable({ patch(share.copy(accentColor = hex), "accent_color", JsonPrimitive(hex)) }, scaleTo = 0.85f),
                    )
                }
            }
            Card {
                Row(
                    Modifier.fillMaxWidth().pressable({ describing = true }, scaleTo = 0.99f).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.shares_description), style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.width(16.dp))
                    Text(
                        share.description?.takeIf { it.isNotBlank() } ?: stringResource(R.string.shares_add),
                        color = if (share.description.isNullOrBlank()) Palette.AccentBright else Palette.TextTertiary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        textAlign = androidx.compose.ui.text.style.TextAlign.End, modifier = Modifier.weight(1f),
                    )
                }
            }

            Label(stringResource(R.string.shares_label_security))
            Card {
                val noPass = "password_protected_shares" in missing && share.passphrase.isNullOrBlank()
                Row(
                    Modifier.fillMaxWidth().then(if (noPass) Modifier else Modifier.pressable({ passphrase = true }, scaleTo = 0.99f)).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.shares_passphrase), style = MaterialTheme.typography.bodyLarge, color = if (noPass) Palette.TextTertiary else Palette.Text, modifier = Modifier.weight(1f))
                    Text(
                        when { noPass -> stringResource(R.string.shares_not_on_your_plan); share.passphrase.isNullOrBlank() -> stringResource(R.string.shares_set); else -> "••••••" },
                        color = if (noPass) Palette.TextTertiary else Palette.AccentBright,
                    )
                }
                Divider()
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.shares_expiration), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Text(
                            share.expiration?.let { Format.dateTime(it) } ?: if ("share_link_expiration" in missing) stringResource(R.string.shares_not_on_your_plan) else stringResource(R.string.shares_expiry_never),
                            color = Palette.TextTertiary,
                        )
                    }
                    Spacer(Modifier.size(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf<Pair<Int, Long?>>(
                            R.string.shares_expiry_never to null, R.string.shares_expiry_1_day to 1L,
                            R.string.shares_expiry_7_days to 7L, R.string.shares_expiry_30_days to 30L,
                        ).forEach { (label, days) ->
                            // The chip matching the time left (rounded up) stays highlighted.
                            val selected = if (days == null) share.expiration == null else Format.daysLeft(share.expiration) == days
                            Chip(stringResource(label), selected, enabled = days == null || "share_link_expiration" !in missing) {
                                val iso = days?.let { Instant.now().plus(it, ChronoUnit.DAYS).toString() }
                                patch(share.copy(expiration = iso), "expiration", iso?.let { JsonPrimitive(it) } ?: JsonNull)
                            }
                        }
                    }
                }
                Divider()
                ToggleLine(
                    stringResource(R.string.shares_watermark), share.watermarkEnabled == true,
                    note = stringResource(R.string.shares_not_on_your_plan).takeIf { "watermark" in missing && share.watermarkEnabled != true },
                ) { patch(share.copy(watermarkEnabled = it), "watermark_enabled", JsonPrimitive(it)) }
                if (share.watermarkEnabled == true) {
                    // Which session watermark template (experimental API); default = the workspace's or account's.
                    LaunchedEffect(Unit) { if (templates == null) templates = runCatching { repo.watermarkTemplates(accountId) }.getOrDefault(emptyList()) }
                    val current = templates?.firstOrNull { it.id == templateId }
                    Divider()
                    Row(
                        Modifier.fillMaxWidth().pressable({ pickingTemplate = true }, scaleTo = 0.99f).padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.shares_watermark_template), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                current?.summary?.ifBlank { null } ?: stringResource(R.string.shares_watermark_default_summary),
                                style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary, maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                        }
                        Text(current?.name ?: stringResource(R.string.shares_default), style = MaterialTheme.typography.bodyMedium, color = Palette.TextSecondary)
                        Icon(Icons.Rounded.ChevronRight, null, tint = Palette.TextTertiary)
                    }
                }
            }

            Label(stringResource(R.string.shares_label_activity))
            Card {
                val list = activity
                when {
                    list == null -> Text(stringResource(R.string.shares_loading), color = Palette.TextTertiary, modifier = Modifier.padding(16.dp))
                    list.isEmpty() -> Text(stringResource(R.string.shares_no_activity), color = Palette.TextTertiary, modifier = Modifier.padding(16.dp))
                    else -> list.take(25).forEachIndexed { i, ev ->
                        if (i > 0) Divider()
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(activityIcon(ev.type), null, tint = Palette.TextSecondary, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                val who = ev.user?.displayName ?: stringResource(R.string.shares_activity_someone)
                                val verb = activityVerbRes(ev.type)?.let { stringResource(it) } ?: ev.type.replace('_', ' ')
                                val assetName = ev.assetId?.let { id -> assets?.firstOrNull { it.id == id }?.name }
                                Text(
                                    if (assetName != null) stringResource(R.string.shares_activity_on_asset, who, verb, assetName)
                                    else stringResource(R.string.shares_activity, who, verb),
                                    style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                )
                                Text(Format.relative(ev.insertedAt), style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary)
                            }
                        }
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Label(stringResource(R.string.shares_files_in_link) + (assets?.let { " · ${it.size}" } ?: ""))
                Spacer(Modifier.weight(1f))
                if (projectId != null) {
                    androidx.compose.material3.TextButton(onClick = { adding = true }) {
                        Icon(Icons.Rounded.Add, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.shares_add))
                    }
                }
            }
            Card {
                val list = assets
                if (list == null) {
                    Text(stringResource(R.string.shares_loading), color = Palette.TextTertiary, modifier = Modifier.padding(16.dp))
                } else if (list.isEmpty()) {
                    Text(stringResource(R.string.shares_no_assets), color = Palette.TextTertiary, modifier = Modifier.padding(16.dp))
                } else list.forEachIndexed { i, a ->
                    if (i > 0) Divider()
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(64.dp).aspectRatio(16f / 10f).clip(RoundedCornerShape(8.dp)).background(Palette.Surface3)) {
                            AssetThumbnail(a, Modifier.fillMaxSize(), scrubbable = false)
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(a.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Icon(
                            Icons.Rounded.Close, stringResource(R.string.shares_remove), tint = Palette.TextTertiary,
                            modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).pressable({
                                assets = list.filterNot { it.id == a.id }
                                scope.launch {
                                    runCatching { repo.removeFromShare(accountId, share.id, a.id) }
                                        .onFailure { assets = list; notify(it.userMessage()) }
                                }
                            }).padding(6.dp),
                        )
                    }
                }
            }
            if (onDelete != null) {
                Card {
                    Row(
                        Modifier.fillMaxWidth().pressable(onDelete, scaleTo = 0.99f).padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.DeleteOutline, null, tint = Palette.Red, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(stringResource(R.string.shares_delete_link), style = MaterialTheme.typography.bodyLarge, color = Palette.Red)
                            Text(stringResource(R.string.shares_delete_link_detail), style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary)
                        }
                    }
                }
            }
        }
        // The sheet covers the screen's snackbar, so show messages inside it.
        androidx.compose.material3.SnackbarHost(snack, Modifier.navigationBarsPadding())
    }

    if (qr) share.shortUrl?.let { url -> QrDialog(url, share.name, onDismiss = { qr = false }) }

    if (adding && projectId != null) {
        dev.finalish.ui.browser.AssetPickerSheet(
            accountId = accountId,
            projectId = projectId,
            excludeIds = assets.orEmpty().map { it.id }.toSet(),
            actionLabel = { n -> if (n == 0) res.getString(R.string.shares_choose_files_to_add) else res.getString(R.string.shares_add_n_to_link, n) },
            onDismiss = { adding = false },
            onPick = { picked ->
                adding = false
                assets = assets.orEmpty() + picked
                scope.launch {
                    val failed = picked.filter { a -> runCatching { repo.addToShare(accountId, share.id, a.id) }.isFailure }
                    if (failed.isNotEmpty()) {
                        assets = assets.orEmpty().filterNot { a -> failed.any { it.id == a.id } }
                        notify(res.getQuantityString(R.plurals.shares_couldnt_add_items, failed.size, failed.size))
                    } else {
                        notify(res.getString(R.string.shares_added_n_to_link, picked.size))
                    }
                }
            },
        )
    }

    if (pickingTemplate) WatermarkTemplateSheet(templates, templateId, onPick = { id ->
        pickingTemplate = false
        val before = templateId
        templateId = id
        scope.launch {
            runCatching { repo.setShareWatermarkTemplate(accountId, share.id, id) }
                .onFailure { templateId = before; notify(it.userMessage()) }
        }
    }, onDismiss = { pickingTemplate = false })

    if (inviting) {
        InviteReviewersDialog(
            onDismiss = { inviting = false },
            onInvite = { emails, message, notify ->
                inviting = false
                scope.launch {
                    runCatching { repo.addShareReviewers(accountId, share.id, emails, message, notify) }
                        .onSuccess {
                            notify(res.getQuantityString(R.plurals.shares_invited_reviewers, emails.size, emails.size))
                            reviewers = runCatching { repo.shareReviewers(accountId, share.id) }.getOrDefault(reviewers.orEmpty())
                        }
                        .onFailure { notify(it.userMessage()) }
                }
            },
        )
    }
    if (describing) {
        TextPromptDialog(stringResource(R.string.shares_description), stringResource(R.string.shares_description_hint), share.description.orEmpty(), stringResource(R.string.shares_save), onDismiss = { describing = false }) { d ->
            describing = false
            patch(share.copy(description = d), "description", JsonPrimitive(d))
        }
    }

    if (rename) {
        TextPromptDialog(stringResource(R.string.shares_rename_title), stringResource(R.string.shares_name), share.name.orEmpty(), stringResource(R.string.shares_save), onDismiss = { rename = false }) { name ->
            rename = false
            patch(share.copy(name = name), "name", JsonPrimitive(name))
        }
    }
    if (passphrase) {
        TextPromptDialog(stringResource(R.string.shares_passphrase), stringResource(R.string.shares_passphrase_hint), share.passphrase.orEmpty(), stringResource(R.string.shares_save), onDismiss = { passphrase = false }) { p ->
            passphrase = false
            patch(share.copy(passphrase = p.ifBlank { null }), "passphrase", p.ifBlank { null }?.let { JsonPrimitive(it) } ?: JsonNull)
        }
    }
}

/** The link as a QR code, for showing it on screen to someone next to you. */
/** Pick the session watermark template for a share: the default, or one of the account's. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun WatermarkTemplateSheet(
    templates: List<dev.finalish.core.model.WatermarkTemplate>?,
    selectedId: String?,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Palette.Surface1) {
        WatermarkTemplateList(templates, selectedId, onPick)
    }
}

@Composable
internal fun WatermarkTemplateList(
    templates: List<dev.finalish.core.model.WatermarkTemplate>?,
    selectedId: String?,
    onPick: (String?) -> Unit,
) {
    Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
        Text(stringResource(R.string.shares_watermark_template), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        Text(
            stringResource(R.string.shares_watermark_template_intro),
            style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary,
            modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp),
        )
        @Composable
        fun row(name: String, detail: String, selected: Boolean, onClick: () -> Unit) = Row(
            Modifier.fillMaxWidth().pressable(onClick, scaleTo = 0.99f).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyLarge)
                if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary)
            }
            if (selected) Icon(Icons.Rounded.Check, stringResource(R.string.shares_selected_cd), tint = Palette.AccentBright)
        }
        val untitled = stringResource(R.string.shares_untitled_template)
        row(stringResource(R.string.shares_default), stringResource(R.string.shares_watermark_default_template), selectedId == null) { onPick(null) }
        when {
            templates == null -> androidx.compose.material3.CircularProgressIndicator(Modifier.padding(20.dp).size(24.dp), strokeWidth = 2.dp)
            templates.isEmpty() -> Text(
                stringResource(R.string.shares_no_templates),
                style = MaterialTheme.typography.bodyMedium, color = Palette.TextTertiary, modifier = Modifier.padding(20.dp),
            )
            else -> templates.forEach { t -> row(t.name.ifBlank { untitled }, t.summary, t.id == selectedId) { onPick(t.id) } }
        }
    }
}

@Composable
private fun QrDialog(url: String, name: String?, onDismiss: () -> Unit) {
    val bitmap = remember(url) {
        val m = com.google.zxing.qrcode.QRCodeWriter().encode(url, com.google.zxing.BarcodeFormat.QR_CODE, 512, 512, mapOf(com.google.zxing.EncodeHintType.MARGIN to 1))
        androidx.core.graphics.createBitmap(m.width, m.height).apply {
            for (x in 0 until m.width) for (y in 0 until m.height) this[x, y] = if (m[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        }.asImageBitmap()
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.Surface2,
        title = { Text(name ?: stringResource(R.string.shares_share_link), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                androidx.compose.foundation.Image(
                    bitmap, stringResource(R.string.shares_qr_code_for, url),
                    modifier = Modifier.size(240.dp).clip(RoundedCornerShape(12.dp)),
                    filterQuality = androidx.compose.ui.graphics.FilterQuality.None,
                )
                Spacer(Modifier.size(10.dp))
                Text(url, style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary)
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text(stringResource(R.string.shares_done)) } },
    )
}

/** Views, comments and downloads counted from the link's activity. */
@Composable
private fun ShareStats(activity: List<dev.finalish.core.model.ShareActivity>) {
    val stats = listOf(
        activity.count { it.type == "share_viewed" || it.type == "asset_viewed" } to stringResource(R.string.shares_stat_views),
        activity.count { it.type == "comment_created" } to stringResource(R.string.shares_stat_comments),
        activity.count { it.type == "asset_downloaded" } to stringResource(R.string.shares_stat_downloads),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        stats.forEach { (n, label) ->
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(Palette.Surface2).padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("$n", style = MaterialTheme.typography.titleLarge)
                Text(label, style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary)
            }
        }
    }
}

private fun activityVerbRes(type: String): Int? = when (type) {
    "share_viewed" -> R.string.shares_activity_opened_link
    "asset_viewed" -> R.string.shares_activity_viewed
    "asset_downloaded" -> R.string.shares_activity_downloaded
    "comment_created" -> R.string.shares_activity_commented_on
    else -> null
}

internal fun layoutLabelRes(layout: String): Int? = when (layout) {
    "grid" -> R.string.shares_layout_grid
    "list" -> R.string.shares_layout_list
    "reel" -> R.string.shares_layout_reel
    else -> null
}

private fun activityIcon(type: String) = when (type) {
    "asset_downloaded" -> Icons.Rounded.Download
    "comment_created" -> Icons.Rounded.ModeComment
    else -> Icons.Rounded.Visibility
}

@Composable
private fun InviteReviewersDialog(onDismiss: () -> Unit, onInvite: (List<String>, String, Boolean) -> Unit) {
    var emails by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var notify by remember { mutableStateOf(true) }
    val list = emails.split(',', ' ', ';', '\n').map { it.trim() }.filter { "@" in it && "." in it.substringAfter("@") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.Surface2,
        icon = { Icon(Icons.Rounded.PersonAdd, null) },
        title = { Text(stringResource(R.string.shares_invite_reviewers)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                androidx.compose.material3.OutlinedTextField(
                    value = emails, onValueChange = { emails = it },
                    label = { Text(stringResource(R.string.shares_emails_label)) }, shape = RoundedCornerShape(12.dp),
                )
                androidx.compose.material3.OutlinedTextField(
                    value = message, onValueChange = { message = it },
                    label = { Text(stringResource(R.string.shares_message)) }, shape = RoundedCornerShape(12.dp), minLines = 2,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.shares_send_email), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    AppSwitch(notify) { notify = it }
                }
            }
        },
        confirmButton = {
            val pleaseReview = stringResource(R.string.shares_please_review)
            androidx.compose.material3.TextButton(enabled = list.isNotEmpty(), onClick = {
                onInvite(list, message.ifBlank { pleaseReview }, notify)
            }) { Text(if (list.isNotEmpty()) stringResource(R.string.shares_invite_n, list.size) else stringResource(R.string.shares_invite)) }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text(stringResource(R.string.shares_cancel)) } },
    )
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Palette.Surface2),
    ) { content() }
}

@Composable
private fun Label(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
}

@Composable
private fun Divider() = HorizontalDivider(color = Palette.Hairline, modifier = Modifier.padding(start = 16.dp))

@Composable
private fun ToggleLine(label: String, checked: Boolean, note: String? = null, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = if (note != null) Palette.TextTertiary else Palette.Text, modifier = Modifier.weight(1f))
        // A note (e.g. "Not on your plan") replaces the switch: nothing to toggle.
        if (note != null) Text(note, color = Palette.TextTertiary, modifier = Modifier.padding(end = 10.dp, top = 12.dp, bottom = 12.dp))
        else AppSwitch(checked, onChange)
    }
}

@Composable
private fun AppSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    val track by animateColorAsState(if (checked) Palette.Accent else Palette.Surface4, label = "track")
    Switch(
        checked = checked, onCheckedChange = onChange,
        colors = SwitchDefaults.colors(checkedTrackColor = track, checkedThumbColor = Color.White),
        modifier = Modifier.padding(end = 6.dp),
    )
}
