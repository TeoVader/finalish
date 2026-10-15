package dev.finalish.ui.viewer

import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import dev.finalish.ui.navigation.sharedMedia
import dev.finalish.ui.theme.Motion
import androidx.compose.ui.layout.layout
import androidx.compose.ui.draw.clipToBounds
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.finalish.ui.browser.AssetPickerSheet
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.res.stringResource
import dev.finalish.R
import dev.finalish.core.model.Asset
import dev.finalish.core.model.MediaKind
import dev.finalish.ui.components.AutoRefresh
import dev.finalish.ui.components.ErrorState
import dev.finalish.ui.components.GlassIconButton
import dev.finalish.ui.shares.CreateShareSheet
import dev.finalish.ui.theme.Palette
import kotlinx.coroutines.launch

@Composable
fun ViewerScreen(
    accountId: String,
    assetId: String,
    type: String,
    onBack: () -> Unit,
    onCompare: (Asset, Asset) -> Unit,
    onOpenFolder: (projectId: String, rootFolderId: String, folderId: String, title: String, focusId: String?) -> Unit = { _, _, _, _, _ -> },
    /** The folder's images, to swipe through in place: only the picture slides, the rest stays. */
    siblings: List<Asset>? = null,
    vm: ViewerViewModel = viewModel(factory = ViewerViewModel.Factory, key = "viewer-$assetId"),
) {
    // Bound during composition (idempotent) so a file opened from a card has its stage on the first frame.
    remember(vm) { vm.bind(accountId, assetId, type); true }
    val state by vm.state.collectAsStateWithLifecycle()
    val composer by vm.composer.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val view by vm.view.collectAsStateWithLifecycle()
    val controller = vm.player
    AutoRefresh(assetId, intervalMs = { 10_000 }) { vm.refreshComments() }
    LaunchedEffect(controller.failureCount) { if (controller.failureCount > 0) vm.onPlaybackFailure(controller.failureCount) }
    val snackbar = dev.finalish.ui.components.rememberMessageSnackbar(vm.messages)
    LaunchedEffect(Unit) { vm.closed.collect { onBack() } }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    // Full screen: comments beside the picture (mockup), toggled from the top bar.
    var fsComments by rememberSaveable { mutableStateOf(false) }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var share by remember { mutableStateOf(false) }
    var actions by remember { mutableStateOf(false) }
    val newVersionPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) vm.uploadNewVersion(uri) }
    val pickNewVersion = { newVersionPicker.launch(arrayOf("*/*")) }
    // "Compare with another take": the folder this file is in, to pick the other take.
    var takeFolder by remember { mutableStateOf<Pair<String, String>?>(null) }
    var moveRoot by remember { mutableStateOf<String?>(null) }
    var copying by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    // Turning a video sideways goes fullscreen; leaving fullscreen there keeps the landscape
    // split view (bar, ⋯ menu, comments beside) until the phone is upright again.
    var landscapeSplit by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(landscape) { if (!landscape) landscapeSplit = false }
    // Tablets have room for the comments beside the video, like images: no automatic fullscreen.
    val roomy = with(androidx.compose.ui.platform.LocalDensity.current) {
        androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.width.toDp() >= 840.dp
    }
    val immersive = fullscreen || (landscape && state.kind == MediaKind.Video && !landscapeSplit && !roomy)
    val toggleFullscreen = {
        if (immersive) { fullscreen = false; landscapeSplit = true } else { fullscreen = true; landscapeSplit = false }
    }

    // A horizontal video goes landscape in fullscreen; vertical ones, images and PDFs keep the
    // device orientation (they turn only when the phone does).
    // Only the current pager page owns the system bars: a page left behind by a swipe would
    // otherwise show them again when it's disposed, after the new page hid them.
    FullscreenEffect(fullscreen, immersive, lockLandscape = state.kind == MediaKind.Video && controller.videoAspect > 1f)
    BackHandler(fullscreen) { fullscreen = false }
    BackHandler(composer.drawing) { vm.toggleDrawing() }

    // Pause when the app goes to the background.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) controller.pause() }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    // Smooth playhead: sample the player every frame while playing.
    LaunchedEffect(controller.isPlaying) {
        if (controller.isPlaying) while (true) withFrameMillis { controller.syncPosition() }
        else controller.syncPosition()
    }

    val fps = vm.fps()
    val timeLabel: (Long) -> String = { formatTime(it, fps, settings.timeFormat) }

    // Annotations visible on the frame: the selected comment, or any comment at the paused playhead.
    val frameComments by remember(state.comments, state.selectedCommentId, controller.isPlaying, state.kind, view.showAnnotations, fps) {
        derivedStateOf {
            val selected = state.comments.firstOrNull { it.id == state.selectedCommentId }
            when {
                composer.drawing || !view.showAnnotations -> emptyList()
                selected != null -> listOf(selected)
                controller.isPlaying || state.kind !in setOf(MediaKind.Video) -> emptyList()
                else -> state.comments.filter { c ->
                    val start = vm.commentMs(c) ?: return@filter false
                    val end = start + ((c.duration ?: 0) * 1000.0 / fps).toLong().coerceAtLeast((1000 / fps).toLong())
                    controller.positionMs in start..end && (c.annotation != null || c.anchor != null)
                }
            }
        }
    }

    val file = state.file
    val inPip = rememberInPictureInPicture()
    PictureInPictureEffect(
        enabled = settings.pictureInPicture && state.kind == MediaKind.Video && controller.isPlaying,
        aspect = controller.videoAspect,
        playing = controller.isPlaying,
        sourceRect = controller.surfaceBounds,
        onTogglePlay = controller::toggle,
    )
    if (inPip) {
        // Floating window: just the picture.
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            VideoSurface(controller, Modifier.fillMaxSize())
        }
        return
    }
    // Hardware keyboard (tablets, Chromebooks): Space, J/K/L, arrows, F. A focused text field
    // keeps its keys, since these only see what nothing below handled.
    val keyFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val shuttle = remember { Shuttle() }
    LaunchedEffect(Unit) { runCatching { keyFocus.requestFocus() } }
    val media = state.kind == MediaKind.Video || state.kind == MediaKind.Audio
    val onKey: (androidx.compose.ui.input.key.KeyEvent) -> Boolean = onKey@{ e ->
        if (e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return@onKey false
        val action = playerKeyFor(e.key, e.isShiftPressed) ?: return@onKey false
        val jumpMs = if (state.kind == MediaKind.Audio) 5_000L else 1_000L
        when (action) {
            PlayerKey.Fullscreen -> fullscreen = !fullscreen
            PlayerKey.ExitFullscreen -> if (fullscreen) fullscreen = false else return@onKey false
            else -> {
                if (!media) return@onKey false
                when (action) {
                    PlayerKey.TogglePlay -> {
                        if (controller.player.playWhenReady) shuttle.stop()?.let(controller::setPlaybackSpeed)
                        controller.toggle()
                    }
                    PlayerKey.Pause -> {
                        controller.pause()
                        shuttle.stop()?.let(controller::setPlaybackSpeed)
                    }
                    PlayerKey.Forward -> {
                        // playWhenReady, not isPlaying: a speed change can rebuffer, and that is still playing.
                        controller.setPlaybackSpeed(shuttle.forward(controller.player.playWhenReady, controller.speed))
                        controller.play()
                    }
                    PlayerKey.Back, PlayerKey.JumpBack -> controller.seekTo(controller.positionMs - jumpMs)
                    PlayerKey.JumpForward -> controller.seekTo(controller.positionMs + jumpMs)
                    PlayerKey.StepBack -> if (state.kind == MediaKind.Audio) controller.seekTo(controller.positionMs - jumpMs) else controller.stepFrames(-1, fps)
                    PlayerKey.StepForward -> if (state.kind == MediaKind.Audio) controller.seekTo(controller.positionMs + jumpMs) else controller.stepFrames(1, fps)
                    else -> {}
                }
            }
        }
        true
    }
    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color.Black)
            .focusRequester(keyFocus)
            .focusable()
            .onKeyEvent(onKey),
    ) {
        val boxW = maxWidth
        val boxH = maxHeight
        val wide = boxW >= 840.dp && !fullscreen

        if (file == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (state.loading) CircularProgressIndicator(color = Palette.AccentBright, strokeWidth = 2.dp)
                else ErrorState(state.error ?: stringResource(R.string.viewer_error_open_file), onRetry = onBack)
            }
            GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.viewer_back), onBack, Modifier.statusBarsPadding().padding(12.dp))
            return@BoxWithConstraints
        }

        val stage: @Composable (Modifier) -> Unit = { m ->
            MediaArea(
                file = file, state = state, composer = composer, controller = controller, vm = vm,
                frameComments = frameComments, immersive = immersive, fullscreen = fullscreen,
                timeLabel = timeLabel, fps = fps, settingsLoop = settings.loopPlayback,
                onToggleFullscreen = toggleFullscreen,
                onMore = { actions = true },
                // The image shown now (after swipes), so going back lands on its card.
                onBack = onBack, modifier = m.sharedMedia(file.id.takeIf { siblings != null } ?: assetId, enabled = !fullscreen),
                siblings = siblings,
                commentsOpen = fsComments,
                onToggleComments = { fsComments = !fsComments },
                onCommentHere = { fsComments = true; vm.requestComposerFocus() },
            )
        }

        when {
            immersive -> {
                val panelVisible = fsComments && (file.kind == MediaKind.Video || file.kind == MediaKind.Image)
                val panel: @Composable (Modifier) -> Unit = { m ->
                    SidePanel(state, composer, vm, fps, timeLabel, onShare = { share = true }, onCompare = onCompare, onNewVersion = pickNewVersion, modifier = m.imePadding())
                }
                if (boxW > boxH) {
                    // Landscape: comments beside the picture (mockup).
                    Row(Modifier.fillMaxSize()) {
                        stage(Modifier.weight(1f).fillMaxHeight())
                        AnimatedVisibility(
                            panelVisible,
                            enter = androidx.compose.animation.expandHorizontally() + fadeIn(),
                            exit = androidx.compose.animation.shrinkHorizontally() + fadeOut(),
                        ) { panel(Modifier.width(340.dp).fillMaxHeight()) }
                    }
                } else {
                    // Portrait (images keep the device orientation): comments below.
                    Column(Modifier.fillMaxSize()) {
                        stage(Modifier.weight(1f).fillMaxWidth())
                        AnimatedVisibility(
                            panelVisible,
                            enter = androidx.compose.animation.expandVertically() + fadeIn(),
                            exit = androidx.compose.animation.shrinkVertically() + fadeOut(),
                        ) { panel(Modifier.fillMaxWidth().height(boxH * 0.5f).navigationBarsPadding()) }
                    }
                }
            }
            wide -> Row(Modifier.fillMaxSize()) {
                // Landscape phones: keep the transport bar above the gesture bar.
                Column(Modifier.weight(1f).fillMaxHeight().navigationBarsPadding()) {
                    ViewerTopBar(file, state, onBack, onMore = { actions = true }, onVersions = { vm.setTab(PanelTab.Versions) }, onStatus = { opt -> file.statusField?.let { vm.setSelect(it, opt) } })
                    stage(Modifier.weight(1f).fillMaxWidth())
                    if (file.kind == MediaKind.Video || file.kind == MediaKind.Audio) {
                        TransportBar(controller, state, vm, timeLabel, fps, onToggleFullscreen = toggleFullscreen)
                    }
                }
                SidePanel(state, composer, vm, fps, timeLabel, onShare = { share = true }, onCompare = onCompare, onNewVersion = pickNewVersion, modifier = Modifier.width(400.dp).fillMaxHeight().statusBarsPadding())
            }
            else -> Column(Modifier.fillMaxSize().background(Palette.Ink)) {
                ViewerTopBar(file, state, onBack, onMore = { actions = true }, onVersions = { vm.setTab(PanelTab.Versions) }, onStatus = { opt -> file.statusField?.let { vm.setSelect(it, opt) } })
                // While typing, the media shrinks so the composer (emoji, tools, field) fits above the keyboard.
                @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                val typing = androidx.compose.foundation.layout.WindowInsets.isImeVisible
                val fullHeight = if (state.kind == MediaKind.Other) boxH * 0.6f
                    else if (state.kind == MediaKind.Pdf || state.kind == MediaKind.Image) boxH * 0.42f
                    else minOf(boxW / controller.videoAspect.coerceIn(0.5f, 3f), boxH * 0.45f)
                val mediaHeight by animateDpAsState(if (typing) minOf(fullHeight, boxH * 0.15f) else fullHeight, Motion.snappy(), label = "mediaH")
                // The stage is laid out at its full height and scaled down, so the video surface keeps
                // one size while the keyboard comes up (re-sizing a TextureView every frame stutters).
                stage(
                    Modifier
                        .fillMaxWidth()
                        .height(mediaHeight)
                        .clipToBounds()
                        .layout { measurable, c ->
                            val full = fullHeight.roundToPx().coerceAtLeast(1)
                            val placeable = measurable.measure(c.copy(minHeight = full, maxHeight = full))
                            layout(c.maxWidth, c.maxHeight) {
                                placeable.placeWithLayer(0, 0) {
                                    val k = (c.maxHeight.toFloat() / full).coerceAtMost(1f)
                                    scaleX = k; scaleY = k
                                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f)
                                }
                            }
                        },
                )
                // Short screens: while typing, the transport bar steps aside too, so the composer fits.
                if ((file.kind == MediaKind.Video || file.kind == MediaKind.Audio) && !(typing && boxH < 760.dp)) {
                    TransportBar(controller, state, vm, timeLabel, fps, onToggleFullscreen = toggleFullscreen)
                }
                SidePanel(state, composer, vm, fps, timeLabel, onShare = { share = true }, onCompare = onCompare, onNewVersion = pickNewVersion, modifier = Modifier.weight(1f))
            }
        }

        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 120.dp))
    }

    takeFolder?.let { folder ->
        val compareLabel = stringResource(R.string.viewer_compare)
        if (file != null) AssetPickerSheet(
            accountId = accountId,
            projectId = file.projectId,
            startFolder = folder,
            single = true,
            excludeIds = setOf(file.id, file.parentId.orEmpty()),
            actionLabel = { compareLabel },
            accept = { it.kind == MediaKind.Audio || it.kind == MediaKind.Video },
            onPick = { picked -> takeFolder = null; picked.firstOrNull()?.let { onCompare(file, it.media) } },
            onDismiss = { takeFolder = null },
        )
    }

    if (actions && file != null) {
        AssetActionsSheet(
            file = file,
            state = state,
            vm = vm,
            onNewShare = { share = true },
            onVersions = { vm.setTab(PanelTab.Versions) },
            onMove = { scope.launch { copying = false; moveRoot = vm.projectRoot() } },
            onCopy = { scope.launch { copying = true; moveRoot = vm.projectRoot() } },
            onNewVersion = pickNewVersion,
            onCompareTake = if (file.kind == MediaKind.Audio) ({
                scope.launch { vm.projectLocation()?.let { (_, folderId, name) -> takeFolder = folderId to name } }
            }) else null,
            onViewInProject = {
                scope.launch {
                    vm.projectLocation()?.let { (rootId, folderId, name) -> onOpenFolder(file.projectId, rootId, folderId, name, vm.boundAssetId) }
                }
            },
            onRename = { renaming = true },
            onDelete = { deleting = true },
            onDismiss = { actions = false },
        )
    }
    if (renaming && file != null) {
        dev.finalish.ui.browser.TextPromptDialog(stringResource(R.string.viewer_action_rename), stringResource(R.string.viewer_detail_name), file.name, stringResource(R.string.viewer_action_rename), onDismiss = { renaming = false }) {
            renaming = false
            vm.rename(it)
        }
    }
    if (deleting && file != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { deleting = false },
            containerColor = Palette.Surface2,
            title = { Text(stringResource(R.string.viewer_delete_title, file.name)) },
            text = { Text(stringResource(R.string.viewer_delete_body), color = Palette.TextSecondary) },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { deleting = false; vm.delete() }) { Text(stringResource(R.string.viewer_action_delete), color = Palette.Red) } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.viewer_cancel)) } },
        )
    }
    moveRoot?.let { rootId ->
        dev.finalish.ui.browser.FolderPickerSheet(
            accountId = vm.accountId,
            rootFolderId = rootId,
            rootName = stringResource(R.string.viewer_project),
            projectId = file?.projectId,
            actionLabel = if (copying) stringResource(R.string.viewer_copy) else stringResource(R.string.viewer_move),
            onPick = { id, name -> moveRoot = null; if (copying) vm.copyTo(id, name) else vm.moveTo(id, name) },
            onDismiss = { moveRoot = null },
        )
    }

    if (share && file != null) {
        val target = if (type == "version_stack") file.copy(id = assetId, type = "version_stack") else file
        CreateShareSheet(vm.accountId, file.projectId, listOf(target), onDismiss = { share = false })
    }
}
