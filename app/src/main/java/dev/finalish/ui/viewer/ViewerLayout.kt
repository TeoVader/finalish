package dev.finalish.ui.viewer

import dev.finalish.ui.theme.Motion
import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.stringResource
import dev.finalish.core.model.Asset
import dev.finalish.core.model.Comment
import dev.finalish.core.model.MediaKind
import dev.finalish.ui.components.pressable
import dev.finalish.ui.theme.Palette
import kotlinx.coroutines.launch

@Composable
internal fun SidePanel(
    state: ViewerState,
    composer: ComposerState,
    vm: ViewerViewModel,
    fps: Double,
    timeLabel: (Long) -> String,
    onShare: () -> Unit,
    onCompare: (Asset, Asset) -> Unit,
    onNewVersion: () -> Unit,
    modifier: Modifier,
) {
    val file = state.file ?: return
    val listState = rememberLazyListState()
    val attachPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> if (uris.isNotEmpty()) vm.addAttachments(uris) }
    val tabs = buildList {
        add(PanelTab.Comments)
        add(PanelTab.Info)
        if (state.versions.size > 1) add(PanelTab.Versions)
    }
    // Keep the selected comment in view (e.g. after tapping a timeline marker).
    LaunchedEffect(state.selectedCommentId) {
        val idx = state.visibleComments.indexOfFirst { it.id == state.selectedCommentId }
        if (idx >= 0) listState.animateScrollToItem(idx + 1)
    }

    Column(modifier.background(Palette.Ink)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            tabs.forEach { t ->
                val sel = state.tab == t
                Column(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .pressable({ vm.setTab(t) }, scaleTo = 0.95f)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        if (t == PanelTab.Comments && state.comments.isNotEmpty()) "${stringResource(t.labelRes)} ${state.comments.size}" else stringResource(t.labelRes),
                        style = MaterialTheme.typography.titleSmall,
                        color = if (sel) Palette.Text else Palette.TextTertiary,
                    )
                    Spacer(Modifier.height(5.dp))
                    val underline by androidx.compose.animation.core.animateDpAsState(if (sel) 22.dp else 0.dp, dev.finalish.ui.theme.Motion.snappy(), label = "tabLine")
                    Box(
                        Modifier
                            .height(2.5.dp)
                            .width(underline)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Palette.Accent),
                    )
                }
            }
        }
        Box(Modifier.weight(1f).then(if (state.tab != PanelTab.Comments) Modifier.navigationBarsPadding() else Modifier)) {
            AnimatedContent(state.tab, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "tab") { tab ->
                when (tab) {
                    PanelTab.Comments -> CommentsList(
                        state = state,
                        listState = listState,
                        timeOf = { c -> vm.commentMs(c)?.let(timeLabel) },
                        rangeOf = { c -> c.duration?.takeIf { it > 0 }?.let { d -> String.format(java.util.Locale.US, "%.1fs", d / fps) } },
                        onSelect = vm::selectComment,
                        onToggleComplete = vm::toggleComplete,
                        onEdit = vm::startEdit,
                        onDelete = vm::deleteComment,
                        onFilter = vm::setFilter,
                        onRetry = vm::retryComment,
                        pinNumberOf = if (file.kind == MediaKind.Image) { pinNumbers(state.comments).let { m -> { c: Comment -> m[c.id] } } } else { { null } },
                        onDeleteAttachment = vm::deleteAttachment,
                    )
                    PanelTab.Info -> InfoPanel(file, state.offlinePath != null, vm, onShare)
                    PanelTab.Versions -> VersionsPanel(state.versions, file.id, vm::selectVersion, onCompare, onReorder = vm::reorderVersion, onNewVersion = onNewVersion)
                }
            }
        }
        AnimatedVisibility(
            state.tab == PanelTab.Comments,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            val timed = file.kind == MediaKind.Video || file.kind == MediaKind.Audio
            CommentComposer(
                composer = composer,
                timed = timed,
                currentTime = { timeLabel(composer.capturedMs ?: vm.player.positionMs) },
                rangeLabel = { composer.rangeStartMs?.let { "${timeLabel(minOf(it, vm.player.positionMs))}–${timeLabel(maxOf(it, vm.player.positionMs))}" } },
                onText = vm::setText,
                onToggleTime = vm::toggleAttachTime,
                onToggleRange = vm::toggleRange,
                onToggleDraw = vm::toggleDrawing,
                onTogglePin = vm::togglePlacingAnchor,
                onClearPin = vm::clearAnchor,
                onTool = vm::setTool,
                onColor = vm::setColor,
                onUndo = vm::undoShape,
                onCancelEdit = vm::cancelEdit,
                onAttach = { attachPicker.launch(arrayOf("*/*")) },
                onRemoveAttachment = vm::removeAttachment,
                onRetime = vm::retimeEditing,
                onFocusChange = vm::onComposerFocus,
                onSend = vm::send,
                allowMarkup = file.kind == MediaKind.Video || file.kind == MediaKind.Image,
                allowAttachments = !dev.finalish.core.network.PlanLimits.missing.collectAsStateWithLifecycle().value[vm.accountId].orEmpty().contains("comment_attachments"),
                modifier = Modifier.navigationBarsPadding().imePadding(),
            )
        }
    }
}

/** Landscape lock + immersive system bars while in fullscreen. */
// Portrait is only held after leaving fullscreen with auto-rotate off, and released when the viewer closes.
@android.annotation.SuppressLint("SourceLockedOrientationActivity")
@Composable
internal fun FullscreenEffect(fullscreen: Boolean, immersive: Boolean, lockLandscape: Boolean) {
    val context = LocalContext.current
    val view = LocalView.current
    val activity = context as? Activity
    // Set when fullscreen was entered upright with auto-rotate off: Android would otherwise
    // keep the forced landscape after leaving it, so portrait is held until the viewer closes.
    val heldPortrait = remember { mutableStateOf(false) }
    val orientation = LocalConfiguration.current.orientation
    DisposableEffect(fullscreen) {
        val lock = fullscreen && lockLandscape
        val upright = orientation == Configuration.ORIENTATION_PORTRAIT
        if (lock) activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            if (lock) {
                val autoRotate = android.provider.Settings.System.getInt(context.contentResolver, android.provider.Settings.System.ACCELEROMETER_ROTATION, 1) == 1
                if (upright && !autoRotate) {
                    activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    heldPortrait.value = true
                } else {
                    activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose { if (heldPortrait.value) activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }
    DisposableEffect(immersive) {
        val window = (context as? Activity)?.window
        val ctl = window?.let { WindowCompat.getInsetsController(it, view) }
        if (immersive) {
            ctl?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctl?.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { ctl?.show(WindowInsetsCompat.Type.systemBars()) }
    }
}
