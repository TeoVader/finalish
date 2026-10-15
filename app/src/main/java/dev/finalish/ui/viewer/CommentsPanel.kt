package dev.finalish.ui.viewer

import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Attachment
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.CropSquare
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Draw
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Gesture
import androidx.compose.material.icons.rounded.HorizontalRule
import androidx.compose.material.icons.rounded.NorthEast
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SpaceBar
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.finalish.core.model.MediaKind
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.compose.ui.res.stringResource
import dev.finalish.R
import dev.finalish.core.model.Comment
import dev.finalish.core.model.User
import dev.finalish.core.model.author
import dev.finalish.core.util.Format
import dev.finalish.ui.components.Avatar
import dev.finalish.ui.components.EmptyState
import dev.finalish.ui.components.pressable
import dev.finalish.ui.components.shimmer
import dev.finalish.ui.home.Chip
import dev.finalish.ui.shares.copyToClipboard
import dev.finalish.ui.theme.Palette
import dev.finalish.ui.theme.TimecodeStyle

@Composable
fun CommentsList(
    state: ViewerState,
    listState: LazyListState,
    timeOf: (Comment) -> String?,
    modifier: Modifier = Modifier,
    rangeOf: (Comment) -> String? = { null },
    onSelect: (Comment) -> Unit,
    onToggleComplete: (Comment) -> Unit,
    onEdit: (Comment) -> Unit,
    onDelete: (Comment) -> Unit,
    onFilter: (CommentFilter) -> Unit,
    onRetry: (Comment) -> Unit = {},
    pinNumberOf: (Comment) -> Int? = { null },
    onDeleteAttachment: (Comment, String) -> Unit = { _, _ -> },
) {
    val comments = state.visibleComments
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 12.dp),
    ) {
        item(key = "filters") {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CommentFilter.entries.forEach { f ->
                    val count = when (f) {
                        CommentFilter.All -> state.comments.size
                        CommentFilter.Open -> state.comments.count { !it.isCompleted }
                        CommentFilter.Completed -> state.comments.count { it.isCompleted }
                    }
                    Chip("${stringResource(f.labelRes)} $count", state.filter == f) { onFilter(f) }
                }
            }
        }
        when {
            state.commentsLoading && state.comments.isEmpty() -> items(4, key = { "sk$it" }) { CommentSkeleton() }
            comments.isEmpty() -> item(key = "empty") {
                EmptyState(
                    Icons.Rounded.ChatBubbleOutline,
                    if (state.filter == CommentFilter.All) stringResource(R.string.viewer_comments_empty_title) else stringResource(R.string.viewer_comments_empty_filtered),
                    if (state.filter == CommentFilter.All) {
                        stringResource(
                            when (state.kind) {
                                MediaKind.Video -> R.string.viewer_comments_empty_body_video
                                MediaKind.Audio -> R.string.viewer_comments_empty_body_audio
                                MediaKind.Pdf -> R.string.viewer_comments_empty_body_pdf
                                MediaKind.Image -> R.string.viewer_comments_empty_body_image
                                else -> R.string.viewer_comments_empty_body
                            },
                        )
                    } else null,
                )
            }
            else -> items(comments, key = { it.id }) { c ->
                CommentCard(
                    comment = c,
                    me = state.me,
                    time = timeOf(c),
                    range = rangeOf(c),
                    selected = c.id == state.selectedCommentId,
                    onClick = { onSelect(c) },
                    onToggleComplete = { onToggleComplete(c) },
                    onEdit = { onEdit(c) },
                    onDelete = { onDelete(c) },
                    onRetry = { onRetry(c) },
                    pinNumber = pinNumberOf(c),
                    modifier = Modifier.animateItem(),
                    onDeleteAttachment = { onDeleteAttachment(c, it) },
                )
            }
        }
    }
}

@Composable
private fun CommentCard(
    comment: Comment,
    me: User?,
    time: String?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    range: String? = null,
    onToggleComplete: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onRetry: () -> Unit = {},
    onDeleteAttachment: (String) -> Unit = {},
    pinNumber: Int? = null,
) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val bg by animateColorAsState(if (selected) Palette.Surface2 else Color.Transparent, label = "bg")
    val mine = me?.id != null && comment.owner?.id == me.id
    val dim by animateFloatAsState(if (comment.isCompleted && !selected) 0.55f else 1f, label = "dim")

    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .pressable(onClick, onLongClick = { menu = true }, scaleTo = 0.985f)
            .padding(horizontal = 10.dp, vertical = 12.dp)
            .graphicsLayer { alpha = dim },
    ) {
        Avatar(comment.author, 34.dp, ring = dev.finalish.ui.components.userColor(comment.author))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    comment.author.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        comment.failed -> stringResource(R.string.viewer_comment_not_sent)
                        comment.pending -> stringResource(R.string.viewer_comment_sending)
                        else -> Format.relative(comment.createdAt)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = when {
                        comment.failed -> Palette.Red
                        comment.pending -> Palette.Amber
                        else -> Palette.TextTertiary
                    },
                )
                if (comment.failed) {
                    Text(
                        stringResource(R.string.viewer_retry),
                        style = MaterialTheme.typography.labelMedium,
                        color = Palette.Link,
                        modifier = Modifier
                            .padding(start = 6.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .pressable(onRetry)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                if (comment.textEditedAt != null) {
                    Text(stringResource(R.string.viewer_comment_edited_suffix), style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary)
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.Top) {
                pinNumber?.let {
                    Text(
                        "● $it",
                        style = TimecodeStyle,
                        color = Palette.AccentBright,
                        modifier = Modifier
                            .padding(end = 8.dp, top = 1.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Palette.AccentContainer)
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
                if (time != null) {
                    Text(
                        time,
                        style = TimecodeStyle,
                        color = Palette.AccentBright,
                        modifier = Modifier
                            .padding(end = 8.dp, top = 1.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Palette.AccentContainer)
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
                comment.page?.let {
                    Text(
                        stringResource(R.string.viewer_page_short, it),
                        style = TimecodeStyle,
                        color = Palette.AccentBright,
                        modifier = Modifier
                            .padding(end = 8.dp, top = 1.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Palette.AccentContainer)
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
                LinkifiedText(comment.text, comment.mentions, onLink = { url ->
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
                })
            }
            val drawingLabel = stringResource(R.string.viewer_comment_drawing)
            val pinnedLabel = stringResource(R.string.viewer_pinned)
            val rangeLabel = range?.let { stringResource(R.string.viewer_comment_range_with, it) } ?: stringResource(R.string.viewer_range)
            val queuedLabel = stringResource(R.string.viewer_comment_queued)
            val extras = buildList {
                if (!comment.annotation.isNullOrBlank()) add(Icons.Rounded.Draw to drawingLabel)
                if (comment.anchor != null) add(Icons.Rounded.Place to pinnedLabel)
                if ((comment.duration ?: 0) > 0) add(Icons.Rounded.SpaceBar to rangeLabel)
                if (comment.pending) add(Icons.Rounded.CloudUpload to queuedLabel)
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                extras.forEach { (icon, label) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(icon, null, tint = Palette.TextTertiary, modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(3.dp))
                        Text(label, style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary)
                    }
                }
                Text(
                    stringResource(R.string.viewer_more),
                    style = MaterialTheme.typography.labelMedium,
                    color = Palette.TextSecondary,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).pressable({ menu = true }).padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
            comment.attachments?.takeIf { it.isNotEmpty() }?.let { list ->
                Spacer(Modifier.height(8.dp))
                // Removing a published file is for everyone: ask first.
                var removing by remember { mutableStateOf<dev.finalish.core.model.CommentAttachment?>(null) }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    list.forEach { a -> AttachmentChip(a, removable = mine, onRemove = { removing = a }) }
                }
                removing?.let { a ->
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { removing = null },
                        containerColor = Palette.Surface2,
                        title = { Text(stringResource(R.string.viewer_remove_attachment_title, a.name)) },
                        text = { Text(stringResource(R.string.viewer_remove_attachment_body), color = Palette.TextSecondary) },
                        confirmButton = {
                            androidx.compose.material3.TextButton(onClick = { removing = null; onDeleteAttachment(a.id) }) {
                                Text(stringResource(R.string.viewer_remove), color = Palette.Red)
                            }
                        },
                        dismissButton = {
                            androidx.compose.material3.TextButton(onClick = { removing = null }) { Text(stringResource(R.string.browser_cancel)) }
                        },
                    )
                }
            }
            if (comment.replies.isNotEmpty()) {
                Column(
                    Modifier
                        .padding(top = 10.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Palette.Surface1)
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    comment.replies.forEach { r ->
                        Row {
                            Avatar(r.author, 22.dp)
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Row {
                                    Text(r.author.displayName, style = MaterialTheme.typography.labelLarge)
                                    Spacer(Modifier.width(6.dp))
                                    Text(Format.relative(r.createdAt), style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary)
                                }
                                Text(r.text, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.width(6.dp))
        AnimatedContent(comment.isCompleted, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "done") { done ->
            Icon(
                if (done) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                if (done) stringResource(R.string.viewer_mark_open) else stringResource(R.string.viewer_mark_done),
                tint = if (done) Palette.Green else Palette.TextTertiary,
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .pressable(onToggleComplete, scaleTo = 0.8f, enabled = !comment.pending && !comment.failed),
            )
        }
        if (menu) {
            CommentActionsSheet(
                comment = comment,
                time = time,
                canEdit = mine && !comment.pending && !comment.failed,
                canDelete = mine || comment.pending || comment.failed,
                onDismiss = { menu = false },
                onGoTo = if (time != null || comment.page != null) ({ menu = false; onClick() }) else null,
                onToggleComplete = if (!comment.pending && !comment.failed) ({ menu = false; onToggleComplete() }) else null,
                onCopy = { menu = false; copyToClipboard(context, comment.text) },
                onEdit = { menu = false; onEdit() },
                onDelete = { menu = false; onDelete() },
                onRetry = if (comment.failed) ({ menu = false; onRetry() }) else null,
            )
        }
    }
}

/** Long-press / "More" on a comment: a preview of it and what you can do with it. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun CommentActionsSheet(
    comment: Comment,
    time: String?,
    canEdit: Boolean,
    canDelete: Boolean,
    onDismiss: () -> Unit,
    onGoTo: (() -> Unit)?,
    onToggleComplete: (() -> Unit)?,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onRetry: (() -> Unit)?,
) {
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Palette.Surface2,
    ) {
        Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()).navigationBarsPadding().padding(bottom = 12.dp)) {
            Row(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.Surface1).padding(12.dp),
            ) {
                Avatar(comment.author, 30.dp)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(comment.author.displayName, style = MaterialTheme.typography.titleSmall)
                    Text(
                        listOfNotNull(time ?: comment.page?.let { stringResource(R.string.viewer_page_short, it) }, comment.text).joinToString("  "),
                        style = MaterialTheme.typography.bodyMedium, color = Palette.TextSecondary,
                        maxLines = 3, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            onRetry?.let { SheetAction(Icons.Rounded.Refresh, stringResource(R.string.viewer_retry_sending), it) }
            onGoTo?.let { SheetAction(Icons.Rounded.Schedule, if (time != null) stringResource(R.string.viewer_go_to_time, time) else stringResource(R.string.viewer_go_to_page), it) }
            onToggleComplete?.let {
                SheetAction(
                    if (comment.isCompleted) Icons.Rounded.RadioButtonUnchecked else Icons.Rounded.CheckCircle,
                    if (comment.isCompleted) stringResource(R.string.viewer_mark_open) else stringResource(R.string.viewer_mark_done), it,
                )
            }
            SheetAction(Icons.Rounded.ContentCopy, stringResource(R.string.viewer_copy_text), onCopy)
            if (canEdit) SheetAction(Icons.Rounded.Edit, stringResource(R.string.viewer_edit), onEdit)
            if (canDelete) SheetAction(Icons.Rounded.Delete, stringResource(R.string.viewer_action_delete), onDelete, tint = Palette.Red)
        }
    }
}

@Composable
private fun SheetAction(icon: ImageVector, label: String, onClick: () -> Unit, tint: Color = Palette.Text) {
    Row(
        Modifier.fillMaxWidth().pressable(onClick, scaleTo = 0.99f).padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (tint == Palette.Text) Palette.TextSecondary else tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = tint)
    }
}

@Composable
private fun LinkifiedText(
    text: String,
    mentions: List<dev.finalish.core.model.CommentMention>,
    onLink: (String) -> Unit,
) {
    val annotated = remember(text, mentions) {
        buildAnnotatedString {
            append(text)
            dev.finalish.core.data.CommentDraft.URL_REGEX.findAll(text).forEach { m ->
                // Only links are tappable; taps/long-presses elsewhere reach the comment row.
                addLink(
                    androidx.compose.ui.text.LinkAnnotation.Clickable(
                        m.value,
                        androidx.compose.ui.text.TextLinkStyles(SpanStyle(color = Palette.AccentBright, textDecoration = TextDecoration.Underline)),
                    ) { onLink(m.value) },
                    m.range.first, m.range.last + 1,
                )
            }
            // @mentions from the API (read-only: the public API can't create them).
            mentions.forEach { m ->
                val start = m.start.coerceIn(0, text.length)
                val end = m.end.coerceIn(start, text.length)
                if (end > start) addStyle(
                    SpanStyle(color = Palette.AccentBright, background = Palette.AccentContainer, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                    start, end,
                )
            }
        }
    }
    Text(annotated, style = MaterialTheme.typography.bodyMedium.copy(color = Palette.Text))
}

/** Comment attachment: name, type and size (the public API offers no download link). */
@Composable
private fun AttachmentChip(a: dev.finalish.core.model.CommentAttachment, removable: Boolean, onRemove: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Palette.Surface1)
            .padding(start = 10.dp, end = 6.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Attachment, null, tint = Palette.AccentBright, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f, fill = false)) {
            Text(a.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(a.mediaType?.substringAfter('/')?.uppercase(), dev.finalish.core.util.Format.bytes(a.fileSize).takeIf { a.fileSize > 0 }).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary,
            )
        }
        if (removable) {
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Rounded.Close, stringResource(R.string.viewer_remove_attachment), tint = Palette.TextTertiary, modifier = Modifier.size(18.dp).pressable(onRemove))
        }
    }
}

@Composable
private fun CommentSkeleton() {
    Row(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
        Box(Modifier.size(32.dp).shimmer(CircleShape))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Box(Modifier.fillMaxWidth(0.35f).height(12.dp).shimmer())
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth(0.9f).height(10.dp).shimmer())
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth(0.6f).height(10.dp).shimmer())
        }
    }
}

/** Its own recomposition scope, so a value that changes every frame redraws only [content]. */
@Composable
private fun Live(content: @Composable () -> Unit) = content()

private val QuickEmoji = listOf("👍", "🔥", "❤️", "👀", "✅", "😂", "🎬", "🙌")

@Composable
fun CommentComposer(
    composer: ComposerState,
    timed: Boolean,
    /** Read only inside small scopes: during playback they change every frame. */
    currentTime: () -> String,
    rangeLabel: () -> String?,
    onText: (String) -> Unit,
    onToggleTime: () -> Unit,
    onToggleRange: () -> Unit,
    onToggleDraw: () -> Unit,
    onTogglePin: () -> Unit,
    onClearPin: () -> Unit,
    onTool: (DrawTool) -> Unit,
    onColor: (Color) -> Unit,
    onUndo: () -> Unit,
    onCancelEdit: () -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    /** Drawing and pins: only on a picture (video frame or image). */
    allowMarkup: Boolean = true,
    onAttach: () -> Unit = {},
    onRemoveAttachment: (PendingAttachment) -> Unit = {},
    onRetime: () -> Unit = {},
    onFocusChange: (Boolean) -> Unit = {},
    /** False when the plan has no comment attachments (Free): no File chip. */
    allowAttachments: Boolean = true,
) {
    var focused by remember { mutableStateOf(false) }
    // On short screens (16:9 phones, landscape) the emoji row would squeeze the field to a sliver.
    val window = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize
    val roomy = with(androidx.compose.ui.platform.LocalDensity.current) { window.height.toDp() } >= 760.dp
    val focus = remember { FocusRequester() }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    // The field can stay focused after the keyboard is dismissed, so taps count too.
    val fieldInteractions = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    LaunchedEffect(fieldInteractions) {
        fieldInteractions.interactions.collect { if (it is androidx.compose.foundation.interaction.PressInteraction.Release) onFocusChange(true) }
    }
    // Closing the keyboard with nothing typed leaves the field: the composer folds back up.
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    val imeOpen = WindowInsets.isImeVisible
    var imeWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(imeOpen) {
        if (imeWasOpen && !imeOpen && focused && composer.text.isBlank() && composer.editing == null && !composer.drawing) focusManager.clearFocus()
        imeWasOpen = imeOpen
    }
    // Drawing needs the picture, not the keyboard: hide it (the typed text stays).
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(composer.drawing) { if (composer.drawing) keyboard?.hide() }
    LaunchedEffect(composer.editing) { if (composer.editing != null) focus.requestFocus() }
    LaunchedEffect(composer.focusRequest) { if (composer.focusRequest > 0) runCatching { focus.requestFocus() } }

    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxWidth()) {
    // In a side panel (landscape, tablets) the timecode inside the field left the placeholder a sliver.
    val narrow = maxWidth < 440.dp
    Column(
        Modifier
            .fillMaxWidth()
            .background(Palette.Surface1)
            .padding(top = 8.dp, bottom = 10.dp),
    ) {
        AnimatedVisibility(composer.drawing, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            DrawToolbar(composer, onTool, onColor, onUndo, onDone = onToggleDraw)
        }
        AnimatedVisibility(composer.editing != null) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Edit, null, tint = Palette.AccentBright, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.viewer_editing_comment), style = MaterialTheme.typography.labelLarge, color = Palette.AccentBright, modifier = Modifier.weight(1f))
                if (timed && composer.editing?.timestamp != null) {
                    Live { ToolChip(Icons.Rounded.Schedule, if (composer.retime) currentTime() else stringResource(R.string.viewer_move_to_playhead), composer.retime, onRetime, mono = composer.retime) }
                    Spacer(Modifier.width(8.dp))
                }
                Icon(Icons.Rounded.Close, stringResource(R.string.viewer_cancel), modifier = Modifier.size(20.dp).pressable(onCancelEdit))
            }
        }
        AnimatedVisibility(roomy && focused && composer.editing == null) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(bottom = 6.dp),
            ) {
                items(QuickEmoji) { e ->
                    Text(
                        e,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .pressable({ onText(composer.text + e) }, scaleTo = 0.8f)
                            .padding(6.dp),
                    )
                }
            }
        }
        // Compact by default (mockup): the tool chips only show while writing or when something is set.
        val expanded = focused || composer.drawing || composer.shapes.isNotEmpty() || composer.anchor != null ||
            composer.placingAnchor || composer.rangeStartMs != null || composer.attachments.isNotEmpty() || composer.editing != null
        AnimatedVisibility(expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            // Scrolls sideways: in a narrow side panel the last chips would be cut off.
            Row(
                Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 12.dp).padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (timed && composer.editing == null) {
                    Live { ToolChip(Icons.Rounded.Schedule, if (composer.attachTime) currentTime() else stringResource(R.string.viewer_no_time), composer.attachTime, onToggleTime, mono = composer.attachTime) }
                    Live {
                        val range = rangeLabel()
                        ToolChip(Icons.Rounded.SpaceBar, range ?: stringResource(R.string.viewer_range), composer.rangeStartMs != null, onToggleRange, mono = range != null)
                    }
                }
                if (allowMarkup) {
                    ToolChip(Icons.Rounded.Draw, if (composer.shapes.isEmpty()) stringResource(R.string.viewer_draw) else "${composer.shapes.size}", composer.drawing || composer.shapes.isNotEmpty(), onToggleDraw)
                    ToolChip(
                        Icons.Rounded.Place, if (composer.anchor != null) stringResource(R.string.viewer_pinned) else stringResource(R.string.viewer_pin),
                        composer.placingAnchor || composer.anchor != null,
                        if (composer.anchor != null) onClearPin else onTogglePin,
                    )
                }
                if (composer.editing == null && (allowAttachments || composer.attachments.isNotEmpty())) {
                    ToolChip(
                        Icons.Rounded.Attachment,
                        if (composer.attachments.isEmpty()) stringResource(R.string.viewer_file) else "${composer.attachments.size}",
                        composer.attachments.isNotEmpty(), onAttach,
                    )
                }
            }
        }
        if (composer.attachments.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(bottom = 8.dp),
            ) {
                itemsIndexed(composer.attachments, key = { i, a -> "${a.uri}#$i" }) { _, a ->
                    Row(
                        Modifier.clip(RoundedCornerShape(50)).background(Palette.Surface3).padding(start = 10.dp, end = 4.dp, top = 5.dp, bottom = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Attachment, null, tint = Palette.TextSecondary, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(a.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 140.dp))
                        Icon(Icons.Rounded.Close, stringResource(R.string.viewer_remove), tint = Palette.TextTertiary, modifier = Modifier.padding(start = 2.dp).size(18.dp).pressable({ onRemoveAttachment(a) }))
                    }
                }
            }
        }
        val timeChip = !expanded && timed && composer.attachTime && composer.text.isEmpty()
        if (timeChip && narrow) {
            Live { TimeTag(currentTime(), Modifier.padding(start = 16.dp, bottom = 6.dp)) }
        }
        Row(
            Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 46.dp)
                    .clip(RoundedCornerShape(23.dp))
                    .background(Palette.Surface3)
                    .border(1.dp, if (focused) Palette.Accent.copy(alpha = 0.6f) else Palette.Hairline, RoundedCornerShape(23.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (composer.text.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (timeChip && !narrow) {
                            Live { TimeTag(currentTime()) }
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(stringResource(R.string.viewer_comment_placeholder), color = Palette.TextTertiary, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                }
                BasicTextField(
                    value = composer.text,
                    onValueChange = onText,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = Palette.Text),
                    cursorBrush = SolidColor(Palette.Accent),
                    maxLines = 5,
                    interactionSource = fieldInteractions,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus)
                        .onFocusChanged { if (it.isFocused != focused) { focused = it.isFocused; onFocusChange(it.isFocused) } },
                )
            }
            Spacer(Modifier.width(8.dp))
            // Collapsed (mockup): Draw stays one tap away next to Send.
            if (allowMarkup && !expanded) {
                Box(
                    Modifier.size(46.dp).clip(CircleShape).background(Palette.Surface3).pressable(onToggleDraw, scaleTo = 0.85f),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Draw, stringResource(R.string.viewer_draw), tint = Palette.TextSecondary, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(8.dp))
            }
            if (focused && composer.editing == null && composer.text.isNotEmpty()) {
                Text(
                    stringResource(R.string.viewer_cancel),
                    style = MaterialTheme.typography.labelLarge,
                    color = Palette.TextSecondary,
                    modifier = Modifier
                        .align(Alignment.CenterVertically)
                        .clip(RoundedCornerShape(12.dp))
                        .pressable({ onText(""); focusManager.clearFocus() })
                        .padding(horizontal = 8.dp, vertical = 12.dp),
                )
                Spacer(Modifier.width(4.dp))
            }
            val canSend = composer.text.isNotBlank() && !composer.sending
            val sendScale by animateFloatAsState(if (canSend) 1f else 0.86f, label = "send")
            Box(
                Modifier
                    .size(46.dp)
                    .graphicsLayer { scaleX = sendScale; scaleY = sendScale }
                    .clip(CircleShape)
                    .background(if (canSend) Palette.Accent else Palette.Surface3)
                    .pressable(onSend, enabled = canSend, scaleTo = 0.85f),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (composer.editing != null) Icons.Rounded.Check else Icons.AutoMirrored.Rounded.Send,
                    if (composer.editing != null) stringResource(R.string.viewer_save) else stringResource(R.string.viewer_send),
                    tint = if (canSend) Palette.OnAccent else Palette.TextTertiary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
    }
}

@Composable
private fun ToolChip(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit, mono: Boolean = false) {
    val bg by animateColorAsState(if (active) Palette.AccentContainer else Palette.Surface2, label = "chipbg")
    val fg by animateColorAsState(if (active) Palette.AccentBright else Palette.TextSecondary, label = "chipfg")
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .pressable(onClick, scaleTo = 0.92f)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(5.dp))
        Text(label, style = if (mono) TimecodeStyle.copy(fontSize = MaterialTheme.typography.labelMedium.fontSize) else MaterialTheme.typography.labelMedium, color = fg, maxLines = 1)
    }
}

@Composable
private fun DrawToolbar(
    composer: ComposerState,
    onTool: (DrawTool) -> Unit,
    onColor: (Color) -> Unit,
    onUndo: () -> Unit,
    onDone: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Palette.Surface2)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf(
            DrawTool.Pen to Icons.Rounded.Gesture,
            DrawTool.Arrow to Icons.Rounded.NorthEast,
            DrawTool.Line to Icons.Rounded.HorizontalRule,
            DrawTool.Rect to Icons.Rounded.CropSquare,
        ).forEach { (tool, icon) ->
            val sel = composer.tool == tool
            Box(
                Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (sel) Palette.Surface4 else Color.Transparent)
                    .pressable({ onTool(tool) }, scaleTo = 0.88f),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, stringResource(tool.labelRes), tint = if (sel) Palette.Text else Palette.TextSecondary, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.width(6.dp))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Palette.Annotation.forEach { c ->
                val sel = composer.color == c
                Box(
                    Modifier
                        .size(if (sel) 24.dp else 20.dp)
                        .clip(CircleShape)
                        .background(c)
                        .border(if (sel) 2.dp else 0.dp, Color.White, CircleShape)
                        .pressable({ onColor(c) }, scaleTo = 0.8f),
                )
            }
        }
        Icon(
            Icons.AutoMirrored.Rounded.Undo, stringResource(R.string.viewer_undo),
            tint = if (composer.shapes.isEmpty()) Palette.TextTertiary else Palette.Text,
            modifier = Modifier.padding(horizontal = 6.dp).size(22.dp).pressable(onUndo, enabled = composer.shapes.isNotEmpty()),
        )
        Text(
            stringResource(R.string.viewer_done),
            style = MaterialTheme.typography.labelLarge,
            color = Palette.AccentBright,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).pressable(onDone).padding(horizontal = 8.dp, vertical = 6.dp),
        )
    }
}

/** The playhead time a new comment will be attached to. */
@Composable
private fun TimeTag(time: String, modifier: Modifier = Modifier) {
    Text(
        time, style = dev.finalish.ui.theme.TimecodeStyle, color = Palette.AccentBright,
        modifier = modifier.clip(RoundedCornerShape(9.dp)).background(Palette.AccentContainer).padding(horizontal = 7.dp, vertical = 3.dp),
    )
}
