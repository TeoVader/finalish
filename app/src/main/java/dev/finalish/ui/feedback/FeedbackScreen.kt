package dev.finalish.ui.feedback

import dev.finalish.core.network.userMessage
import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Draw
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import dev.finalish.core.feedback.FeedbackStore
import androidx.core.net.toUri
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.finalish.FinalishApp
import dev.finalish.R
import dev.finalish.core.feedback.DeviceInfo
import dev.finalish.core.feedback.FeedbackKind
import dev.finalish.core.feedback.FeedbackReport
import dev.finalish.core.feedback.FeedbackStatus
import dev.finalish.core.util.Format
import dev.finalish.ui.components.PrimaryButton
import dev.finalish.ui.components.pressable
import dev.finalish.ui.shares.SimpleTopBar
import dev.finalish.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant

/**
 * Bug report / feature request composer. Opened from Settings, the viewer's menu or
 * by shaking the phone (which also grabs a screenshot of where you were).
 */
@Composable
fun FeedbackScreen(initialKind: FeedbackKind, accountId: String?, onBack: () -> Unit) {
    val context = LocalContext.current
    val res = androidx.compose.ui.platform.LocalResources.current
    val container = remember { FinalishApp.container(context) }
    val store = container.feedback
    val reports by store.reports.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var kind by rememberSaveable { mutableStateOf(initialKind) }
    var viewLogs by remember { mutableStateOf(false) }
    if (viewLogs) {
        val text = remember { dev.finalish.core.feedback.AppLogs.recent() }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { viewLogs = false },
            title = { Text(stringResource(R.string.feedback_logs_dialog_title)) },
            text = {
                Text(
                    text,
                    style = dev.finalish.ui.theme.TimecodeStyle.copy(fontSize = 11.sp),
                    color = Palette.TextSecondary,
                    modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { viewLogs = false }) { Text(stringResource(R.string.feedback_close)) } },
            containerColor = Palette.Surface2,
        )
    }
    var title by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    val shotFile = store.draftScreenshot
    val screenshot = remember { if (shotFile.exists()) BitmapFactory.decodeFile(shotFile.absolutePath) else null }
    // Off until the user opts in: the screen may show clients' footage, names and comments.
    var includeShot by rememberSaveable { mutableStateOf(false) }
    var includeLogs by rememberSaveable { mutableStateOf(initialKind == FeedbackKind.Bug) }
    var showDetails by remember { mutableStateOf(false) }
    var marking by remember { mutableStateOf(false) }
    val strokes = remember { mutableStateListOf<List<Offset>>() } // normalised 0..1
    var sending by remember { mutableStateOf(false) }
    val media = remember { mutableStateListOf<android.net.Uri>() }
    val pickMedia = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia(6),
    ) { uris -> media.addAll(uris.filterNot { it in media }) }
    var plan by rememberSaveable { mutableStateOf(dev.finalish.core.feedback.FramePlan.remembered(context, accountId)) }
    val device = remember(plan) { DeviceInfo.collect(context, accountId, plan.label) }
    LaunchedEffect(kind) { if (kind == FeedbackKind.Bug) includeLogs = true }

    val canSend = title.isNotBlank() && !sending

    val relay = container.reportRelay
    // Fetch and solve the relay's ticket while the user writes, so Send goes straight out.
    LaunchedEffect(Unit) { relay.prefetch() }
    fun openIssue(r: FeedbackReport) {
        r.issueUrl?.let { url -> runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, url.toUri())) } }
    }

    fun submit(share: Boolean) {
        sending = true
        scope.launch {
            try {
                val report = withContext(Dispatchers.IO) {
                    // Burn the markup into the screenshot before it's attached.
                    if (includeShot && screenshot != null && strokes.isNotEmpty()) flatten(screenshot, strokes, shotFile)
                    store.submit(
                        context, kind, title, description, includeShot, includeLogs, accountId,
                        send = !share, media = media.toList(), ticket = if (share) null else relay.takePrefetched(),
                        plan = plan.label,
                    )
                }
                if (share) store.share(context, report)
                withContext(Dispatchers.IO) { shotFile.delete() }
                // The screenshot went with this report; the next one starts without it.
                title = ""; description = ""; strokes.clear(); media.clear(); includeShot = false
                if (!share) scope.launch { relay.prefetch() } // ready for another report
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // Saving failed (e.g. storage full): keep what was written and say so.
                android.widget.Toast.makeText(context, res.getString(R.string.feedback_save_failed, e.userMessage(context)), android.widget.Toast.LENGTH_LONG).show()
            } finally {
                sending = false
            }
        }
    }

    if (marking && screenshot != null) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { marking = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            Column(Modifier.fillMaxSize().background(Color.Black)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp).statusBarsPadding(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.feedback_mark_up), style = MaterialTheme.typography.titleMedium, color = Color.White, modifier = Modifier.weight(1f).padding(start = 12.dp))
                    SmallAction(Icons.AutoMirrored.Rounded.Undo, stringResource(R.string.feedback_undo), false) { if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex) }
                    SmallAction(Icons.Rounded.CheckCircle, stringResource(R.string.feedback_done), true) { marking = false }
                }
                Box(Modifier.weight(1f).fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.aspectRatio(screenshot.width.toFloat() / screenshot.height, matchHeightConstraintsFirst = true)) {
                        Image(screenshot.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                        MarkupLayer(strokes, enabled = true)
                    }
                }
                Text(
                    stringResource(R.string.feedback_markup_hint),
                    style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary,
                    modifier = Modifier.align(Alignment.CenterHorizontally).navigationBarsPadding().padding(bottom = 12.dp),
                )
            }
        }
    }

    Column(Modifier.fillMaxSize().background(Palette.Ink)) {
        val viaShake = remember { dev.finalish.core.feedback.FeedbackLauncher.viaShake }
        SimpleTopBar(stringResource(R.string.feedback_title), if (viaShake) stringResource(R.string.feedback_subtitle_shake) else stringResource(R.string.feedback_subtitle), onBack)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Kind
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KindCard(FeedbackKind.Bug, Icons.Rounded.BugReport, stringResource(R.string.feedback_kind_bug_hint), kind == FeedbackKind.Bug, Modifier.weight(1f)) { kind = it }
                KindCard(FeedbackKind.Feature, Icons.Rounded.AutoAwesome, stringResource(R.string.feedback_kind_feature_hint), kind == FeedbackKind.Feature, Modifier.weight(1f)) { kind = it }
                KindCard(FeedbackKind.Change, Icons.Rounded.Tune, stringResource(R.string.feedback_kind_change_hint), kind == FeedbackKind.Change, Modifier.weight(1f)) { kind = it }
            }

            Field(
                value = title, onValue = { title = it },
                placeholder = when (kind) {
                    FeedbackKind.Bug -> stringResource(R.string.feedback_title_placeholder_bug)
                    FeedbackKind.Feature -> stringResource(R.string.feedback_title_placeholder_feature)
                    FeedbackKind.Change -> stringResource(R.string.feedback_title_placeholder_change)
                },
                maxLines = 3,
            )
            Field(
                value = description, onValue = { description = it },
                placeholder = when (kind) {
                    FeedbackKind.Bug -> stringResource(R.string.feedback_description_placeholder_bug)
                    else -> stringResource(R.string.feedback_description_placeholder)
                },
                minHeight = 120,
            )

            // Screenshot with quick markup
            if (screenshot != null) {
                Card {
                    Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.feedback_screenshot), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        if (includeShot) SmallAction(Icons.Rounded.Draw, if (strokes.isEmpty()) stringResource(R.string.feedback_mark_up) else stringResource(R.string.feedback_edit_markup), false) { marking = true }
                        Toggle(includeShot) { includeShot = it }
                    }
                    AnimatedVisibility(includeShot) {
                        Box(
                            Modifier
                                .padding(12.dp)
                                .fillMaxWidth()
                                .heightIn(max = 460.dp)
                                .aspectRatio(screenshot.width.toFloat() / screenshot.height, matchHeightConstraintsFirst = true)
                                .clip(RoundedCornerShape(12.dp))
                                .border(1.dp, Palette.Hairline, RoundedCornerShape(12.dp))
                                .pressable({ marking = true }, scaleTo = 0.98f)
                                .align(Alignment.CenterHorizontally),
                        ) {
                            Image(screenshot.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                            MarkupLayer(strokes, enabled = false)
                        }
                    }
                    if (includeShot) {
                        Text(
                            stringResource(R.string.feedback_screenshot_hint),
                            style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                        )
                    }
                }
            }

            // Screenshots and screen recordings from the gallery
            Card {
                Row(
                    Modifier.fillMaxWidth().pressable({
                        pickMedia.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                    }, scaleTo = 0.99f).padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Videocam, null, tint = if (media.isNotEmpty()) Palette.AccentBright else Palette.TextSecondary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (media.isNotEmpty()) pluralStringResource(R.plurals.feedback_media_attached, media.size, media.size) else stringResource(R.string.feedback_attach_media),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            if (media.isNotEmpty()) stringResource(R.string.feedback_tap_to_add_more)
                            else stringResource(R.string.feedback_attach_media_hint),
                            style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary,
                        )
                    }
                    if (media.isNotEmpty()) {
                        Icon(
                            Icons.Rounded.Close, stringResource(R.string.feedback_remove_attachments), tint = Palette.TextTertiary,
                            modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).pressable({ media.clear() }).padding(6.dp),
                        )
                    }
                }
            }

            // What's attached
            Card {
                PlanLine(plan) { plan = it; dev.finalish.core.feedback.FramePlan.remember(context, it) }
                ToggleLine(stringResource(R.string.feedback_include_logs), stringResource(R.string.feedback_include_logs_hint), includeLogs, onView = { viewLogs = true }) { includeLogs = it }
                Row(
                    Modifier.fillMaxWidth().pressable({ showDetails = !showDetails }, scaleTo = 0.99f).padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.feedback_device_info), style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(R.string.feedback_device_info_hint), style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary)
                    }
                    Icon(if (showDetails) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown, null, tint = Palette.TextTertiary)
                }
                AnimatedVisibility(showDetails) {
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                        device.forEach { (k, v) ->
                            Row {
                                Text(k, style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary, modifier = Modifier.width(120.dp))
                                Text(v, style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
                            }
                        }
                    }
                }
            }

            // Actions (mockup: sending to the developer first, sharing second).
            PrimaryButton(
                if (sending) stringResource(R.string.feedback_preparing) else stringResource(R.string.feedback_send_report),
                onClick = { submit(share = false) },
                enabled = canSend && relay.enabled,
                icon = Icons.AutoMirrored.Rounded.Send,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                if (relay.enabled) stringResource(R.string.feedback_send_hint)
                else stringResource(R.string.feedback_send_unavailable),
                style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            Text(
                stringResource(R.string.feedback_privacy_policy),
                style = MaterialTheme.typography.bodySmall, color = Palette.AccentBright,
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .pressable({ dev.finalish.ui.settings.openUrl(context, dev.finalish.ui.settings.PRIVACY_URL) }, scaleTo = 0.97f),
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .border(1.dp, Palette.Hairline, RoundedCornerShape(14.dp))
                    .pressable({ if (canSend) submit(share = true) }, enabled = canSend, scaleTo = 0.98f)
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Share, null, tint = if (canSend) Palette.AccentBright else Palette.TextTertiary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.feedback_share_report), style = MaterialTheme.typography.bodyLarge, color = if (canSend) Palette.Text else Palette.TextTertiary)
                    Text(stringResource(R.string.feedback_share_report_hint), style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary)
                }
            }

            if (reports.isNotEmpty()) {
                Text(stringResource(R.string.feedback_your_reports), style = MaterialTheme.typography.titleSmall, color = Palette.TextSecondary, modifier = Modifier.padding(top = 8.dp, start = 4.dp))
                Card {
                    reports.take(20).forEachIndexed { i, r ->
                        if (i > 0) androidx.compose.material3.HorizontalDivider(Modifier.padding(start = 16.dp), color = Palette.Hairline)
                        ReportRow(
                            r, onShare = { store.share(context, r) }, onRetry = { store.retry(context, r.id) },
                            onOpenIssue = { openIssue(r) }, canSend = relay.enabled, onDelete = { store.delete(r.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun KindCard(kind: FeedbackKind, icon: ImageVector, hint: String, selected: Boolean, modifier: Modifier, onSelect: (FeedbackKind) -> Unit) {
    val bg by animateColorAsState(if (selected) Palette.AccentContainer else Palette.Surface2, label = "kind")
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .border(1.dp, if (selected) Palette.Accent else Palette.Hairline, RoundedCornerShape(16.dp))
            .pressable({ onSelect(kind) }, scaleTo = 0.96f)
            .padding(12.dp),
    ) {
        Icon(icon, null, tint = if (selected) Palette.AccentBright else Palette.TextSecondary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.size(8.dp))
        Text(stringResource(kind.labelRes), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(hint, style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary, maxLines = 2)
    }
}

@Composable
private fun Field(value: String, onValue: (String) -> Unit, placeholder: String, maxLines: Int = Int.MAX_VALUE, minHeight: Int = 0) {
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = minHeight.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Palette.Surface2)
            .border(1.dp, Palette.Hairline, RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        if (value.isEmpty()) Text(placeholder, color = Palette.TextTertiary, style = MaterialTheme.typography.bodyLarge)
        val short = maxLines <= 3
        BasicTextField(
            // Short fields wrap but stay one paragraph; their Enter key moves on to the next field.
            value = value, onValueChange = { onValue(if (short) it.replace('\n', ' ') else it) }, maxLines = maxLines,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Palette.Text),
            cursorBrush = SolidColor(Palette.Accent),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences,
                imeAction = if (short) androidx.compose.ui.text.input.ImeAction.Next else androidx.compose.ui.text.input.ImeAction.Default,
            ),
            // The whole box is the field: a tap anywhere in it (not just on the first line) starts typing.
            modifier = Modifier.fillMaxWidth().heightIn(min = (minHeight - 28).coerceAtLeast(0).dp),
        )
    }
}

@Composable
private fun MarkupLayer(strokes: MutableList<List<Offset>>, enabled: Boolean) {
    var current by remember { mutableStateOf<List<Offset>>(emptyList()) }
    Canvas(
        Modifier
            .fillMaxSize()
            .then(
                if (enabled) Modifier.pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { current = listOf(Offset(it.x / size.width, it.y / size.height)) },
                        onDragEnd = { if (current.size > 1) strokes.add(current); current = emptyList() },
                        onDragCancel = { current = emptyList() },
                    ) { change, _ ->
                        change.consume()
                        current = current + Offset(change.position.x / size.width, change.position.y / size.height)
                    }
                } else Modifier,
            ),
    ) {
        (strokes + listOf(current)).forEach { s ->
            if (s.size < 2) return@forEach
            val path = Path().apply {
                moveTo(s[0].x * size.width, s[0].y * size.height)
                s.drop(1).forEach { lineTo(it.x * size.width, it.y * size.height) }
            }
            drawPath(path, Palette.Red, style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/** Draws the markup strokes onto the full-resolution screenshot file. */
private fun flatten(src: android.graphics.Bitmap, strokes: List<List<Offset>>, out: File) {
    val bmp = src.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
    val canvas = android.graphics.Canvas(bmp)
    val paint = android.graphics.Paint().apply {
        color = android.graphics.Color.rgb(0xFF, 0x5A, 0x6A)
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = bmp.width / 90f
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
        isAntiAlias = true
    }
    strokes.forEach { s ->
        val p = android.graphics.Path()
        p.moveTo(s[0].x * bmp.width, s[0].y * bmp.height)
        s.drop(1).forEach { p.lineTo(it.x * bmp.width, it.y * bmp.height) }
        canvas.drawPath(p, paint)
    }
    out.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
}

@Composable
private fun ReportRow(
    r: FeedbackReport,
    onShare: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    onOpenIssue: () -> Unit = {},
    canSend: Boolean = true,
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            // A sent report opens its issue; anything else offers its actions.
            Modifier.fillMaxWidth().pressable({ if (r.issueUrl != null) onOpenIssue() else menu = true }, onLongClick = { menu = true }, scaleTo = 0.99f)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.feedback_report_row_title, stringResource(r.kind.labelRes), r.title), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    Format.relative(Instant.ofEpochMilli(r.createdAt).toString()) + (r.error?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            // Status in words (mockup): Sent / Queued / Failed…
            val (label, tint) = when (r.status) {
                FeedbackStatus.Sent -> (r.issueNumber?.let { "#$it" } ?: stringResource(R.string.feedback_status_sent)) to Palette.Green
                FeedbackStatus.Shared -> stringResource(R.string.feedback_status_shared) to Palette.TextSecondary
                FeedbackStatus.Failed -> stringResource(R.string.feedback_status_failed) to Palette.Red
                FeedbackStatus.Sending -> stringResource(R.string.feedback_status_sending) to Palette.Amber
                FeedbackStatus.Queued -> stringResource(R.string.feedback_status_queued) to Palette.Amber
            }
            Text(
                label, style = MaterialTheme.typography.labelMedium, color = tint,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(tint.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        androidx.compose.material3.DropdownMenu(menu, onDismissRequest = { menu = false }, shape = dev.finalish.ui.theme.MenuShape) {
            if (r.issueUrl != null) {
                androidx.compose.material3.DropdownMenuItem(text = { Text(stringResource(R.string.feedback_open_issue, "${r.issueNumber}")) }, onClick = { menu = false; onOpenIssue() })
            }
            androidx.compose.material3.DropdownMenuItem(text = { Text(stringResource(R.string.feedback_share)) }, onClick = { menu = false; onShare() })
            if (canSend && (r.status == FeedbackStatus.Failed || r.status == FeedbackStatus.Shared)) {
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(if (r.status == FeedbackStatus.Failed) stringResource(R.string.feedback_try_again) else stringResource(R.string.feedback_send)) },
                    onClick = { menu = false; onRetry() },
                )
            }
            androidx.compose.material3.DropdownMenuItem(text = { Text(stringResource(R.string.feedback_delete), color = Palette.Red) }, onClick = { menu = false; onDelete() })
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.Surface1).border(1.dp, Palette.Hairline, RoundedCornerShape(16.dp))) { content() }
}

@Composable
private fun ToggleLine(label: String, hint: String, checked: Boolean, onView: (() -> Unit)? = null, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary)
            onView?.let {
                Text(
                    stringResource(R.string.feedback_view_logs), style = MaterialTheme.typography.labelLarge, color = Palette.Link,
                    modifier = Modifier.padding(top = 2.dp).clip(RoundedCornerShape(6.dp)).pressable(it).padding(vertical = 4.dp),
                )
            }
        }
        Toggle(checked, onChange)
    }
}

/** Which Frame.io plan the reporter is on: it decides what the API allows, so it helps debugging. */
@Composable
internal fun PlanLine(plan: dev.finalish.core.feedback.FramePlan, onChange: (dev.finalish.core.feedback.FramePlan) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val unknown = stringResource(R.string.feedback_plan_unknown)
    fun label(p: dev.finalish.core.feedback.FramePlan) = if (p == dev.finalish.core.feedback.FramePlan.Unknown) unknown else p.label
    Row(
        Modifier.fillMaxWidth().pressable({ menu = true }, scaleTo = 0.99f).padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.feedback_plan), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.feedback_plan_hint), style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary)
        }
        Box {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label(plan), style = MaterialTheme.typography.bodyMedium, color = Palette.TextSecondary)
                Icon(Icons.Rounded.KeyboardArrowDown, null, tint = Palette.TextTertiary)
            }
            androidx.compose.material3.DropdownMenu(menu, onDismissRequest = { menu = false }, shape = dev.finalish.ui.theme.MenuShape, containerColor = Palette.Surface3) {
                dev.finalish.core.feedback.FramePlan.entries.forEach { p ->
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(label(p)) },
                        trailingIcon = { if (p == plan) Icon(Icons.Rounded.Check, null, tint = Palette.Accent) },
                        onClick = { menu = false; onChange(p) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Toggle(checked: Boolean, onChange: (Boolean) -> Unit) {
    Switch(
        checked = checked, onCheckedChange = onChange,
        colors = SwitchDefaults.colors(checkedTrackColor = Palette.Accent, checkedThumbColor = Palette.OnAccent),
    )
}

@Composable
private fun SmallAction(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .padding(end = 6.dp)
            .clip(RoundedCornerShape(50))
            .background(if (active) Palette.AccentContainer else Palette.Surface3)
            .pressable(onClick, scaleTo = 0.92f)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (active) Palette.AccentBright else Palette.TextSecondary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = if (active) Palette.AccentBright else Palette.TextSecondary)
    }
}

