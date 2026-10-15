package dev.finalish.ui.viewer

import androidx.compose.ui.layout.layout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.ui.unit.sp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.material.icons.rounded.Forward5
import androidx.compose.material.icons.rounded.Replay5
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.finalish.core.model.author
import dev.finalish.core.data.PlaybackQuality
import androidx.compose.ui.res.stringResource
import dev.finalish.R
import dev.finalish.core.model.Asset
import dev.finalish.core.model.MediaKind
import dev.finalish.ui.components.pressable
import dev.finalish.ui.theme.Palette
import dev.finalish.ui.theme.TimecodeStyle

@Composable
internal fun ViewerTopBar(
    file: Asset,
    state: ViewerState,
    onBack: () -> Unit,
    onMore: () -> Unit,
    onVersions: () -> Unit,
    onStatus: (dev.finalish.core.model.SelectOption?) -> Unit,
) {
    var statusMenu by remember { mutableStateOf(false) }
    val status = file.statusField
    Row(
        Modifier
            .fillMaxWidth()
            .background(Palette.Ink)
            .statusBarsPadding()
            .height(60.dp)
            .padding(start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.viewer_back)) }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    file.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (state.versions.size > 1) {
                    Spacer(Modifier.width(6.dp))
                    VersionPill(state.versionNumber, onVersions)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 3.dp)) {
                if (status != null) {
                    Box {
                        val label = status.textValue
                        Row(
                            Modifier
                                .clip(RoundedCornerShape(50))
                                .background(dev.finalish.ui.theme.statusColor(label).copy(alpha = if (label == null) 0.12f else 0.16f))
                                .pressable({ statusMenu = true }, enabled = status.mutable, scaleTo = 0.95f)
                                .padding(start = 8.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(6.dp).clip(RoundedCornerShape(50)).background(dev.finalish.ui.theme.statusColor(label)))
                            Spacer(Modifier.width(5.dp))
                            Text(
                                label ?: stringResource(R.string.viewer_no_status),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (label == null) Palette.TextSecondary else dev.finalish.ui.theme.statusColor(label),
                            )
                            Icon(Icons.Rounded.KeyboardArrowDown, null, tint = Palette.TextTertiary, modifier = Modifier.size(14.dp))
                        }
                        DropdownMenu(statusMenu, onDismissRequest = { statusMenu = false }, shape = dev.finalish.ui.theme.MenuShape, containerColor = Palette.Surface3) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.viewer_no_status), color = Palette.TextSecondary) },
                                trailingIcon = { if (label == null) Icon(Icons.Rounded.Check, null, tint = Palette.Accent) },
                                onClick = { statusMenu = false; onStatus(null) },
                            )
                            status.options.forEach { opt ->
                                val c = dev.finalish.ui.theme.statusColor(opt.displayName)
                                DropdownMenuItem(
                                    text = { Text(opt.displayName) },
                                    leadingIcon = { Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(c)) },
                                    trailingIcon = { if (status.selectedOptions.any { it.id == opt.id }) Icon(Icons.Rounded.Check, null, tint = Palette.Accent) },
                                    onClick = { statusMenu = false; onStatus(opt) },
                                )
                            }
                        }
                    }
                }
                formatLine(file, androidx.compose.ui.platform.LocalResources.current)?.let {
                    Text(
                        "  $it", style = MaterialTheme.typography.labelMedium, color = Palette.TextTertiary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                if (state.offlinePath != null) {
                    Text("  " + stringResource(R.string.viewer_action_offline), style = MaterialTheme.typography.labelMedium, color = Palette.Green)
                }
            }
        }
        IconButton(onClick = onMore) { Icon(Icons.Rounded.MoreHoriz, stringResource(R.string.viewer_more)) }
    }
}

@Composable
fun TransportBar(
    controller: PlayerController,
    state: ViewerState,
    vm: ViewerViewModel,
    timeLabel: (Long) -> String,
    fps: Double,
    onToggleFullscreen: () -> Unit,
    overlay: Boolean = false,
) {
    val view by vm.view.collectAsStateWithLifecycle()
    val markers = remember(state.comments, fps, view.showMarkers) {
        if (!view.showMarkers) emptyList() else state.comments.mapNotNull { c ->
            val start = vm.commentMs(c) ?: return@mapNotNull null
            val end = c.duration?.takeIf { it > 0 }?.let { start + (it * 1000.0 / fps).toLong() }
            TimelineMarker(
                c.id, start, end, dev.finalish.ui.components.userColor(c.author),
                user = c.author, completed = c.isCompleted,
            )
        }
    }
    val composer by vm.composer.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var speedMenu by remember { mutableStateOf(false) }
    var timeMenu by remember { mutableStateOf(false) }
    var qualityMenu by remember { mutableStateOf(false) }
    var optionsSheet by remember { mutableStateOf(false) }
    val pipActivity = androidx.activity.compose.LocalActivity.current
    var muted by remember { mutableStateOf(false) }
    val renditions by vm.renditions.collectAsStateWithLifecycle()
    LaunchedEffect(controller.videoHeight) { vm.onPlayingHeight(controller.videoHeight) }

    Column(
        Modifier
            .fillMaxWidth()
            .then(if (overlay) Modifier else Modifier.background(Palette.Ink))
            .padding(horizontal = 12.dp)
            .padding(top = 6.dp, bottom = 4.dp),
    ) {
        Timeline(
            positionMs = { controller.positionMs },
            durationMs = controller.durationMs,
            bufferedMs = { controller.bufferedMs },
            markers = markers,
            selectedMarker = state.selectedCommentId,
            scrubSheet = state.file?.mediaLinks?.scrubSheet,
            timeLabel = timeLabel,
            onScrubStart = { controller.pause() },
            onScrub = { ms, exact -> controller.scrubTo(ms, exact) },
            onMarkerTap = { m -> state.comments.firstOrNull { it.id == m.id }?.let(vm::selectComment) },
            rangeStartMs = composer.rangeStartMs,
            previewAspect = controller.videoAspect,
            previewFrame = vm.scrub::frameAt,
            previewNearest = vm.scrub::nearest,
        )
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
            // Narrow phones: loop & volume move into the settings sheet to keep the bar uncluttered.
            val roomy = maxWidth >= 400.dp
            // Below this the quality chip would press against the timecode; Tools still has quality.
            val qualityChip = maxWidth >= 420.dp
            Column {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                // Left cluster: play, loop, speed, volume (same order as the web player).
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    SmallIcon(
                        if (controller.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        stringResource(R.string.viewer_play_pause), tint = Palette.Text, onClick = controller::toggle,
                    )
                    if (roomy) {
                        SmallIcon(Icons.Rounded.Repeat, stringResource(R.string.viewer_opt_loop), active = controller.looping) {
                            controller.setLoop(!controller.looping)
                            vm.updateSettings { it.copy(loopPlayback = controller.looping) }
                        }
                    }
                    // Speed lives in Tools; the bar only shows it while it isn't 1×.
                    if (controller.speed != 1f) Box {
                        Text(
                            speedLabel(controller.speed),
                            style = MaterialTheme.typography.labelLarge,
                            color = if (controller.speed != 1f) Palette.AccentBright else Palette.TextSecondary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .pressable({ speedMenu = true }, scaleTo = 0.9f)
                                .padding(horizontal = 6.dp, vertical = 8.dp),
                        )
                        DropdownMenu(speedMenu, onDismissRequest = { speedMenu = false }, shape = dev.finalish.ui.theme.MenuShape, containerColor = Palette.Surface3) {
                            listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f).forEach { sp ->
                                DropdownMenuItem(
                                    text = { Text(speedLabel(sp)) },
                                    trailingIcon = { if (controller.speed == sp) Icon(Icons.Rounded.Check, null, tint = Palette.Accent) },
                                    onClick = { speedMenu = false; controller.setPlaybackSpeed(sp) },
                                )
                            }
                        }
                    }
                    if (roomy) {
                        SmallIcon(if (muted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp, stringResource(R.string.viewer_opt_mute)) {
                            muted = !muted
                            controller.setVolume(muted)
                        }
                    }
                }

                // Center: timecode box with frame stepping and the time-format menu.
                Row(
                    Modifier
                        .padding(horizontal = 4.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Palette.Surface2),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Audio has no frames: skip 5 s instead (mockup).
                    val audio = state.kind == MediaKind.Audio
                    Icon(
                        if (audio) Icons.Rounded.Replay5 else Icons.Rounded.ChevronLeft,
                        if (audio) stringResource(R.string.viewer_back_5_seconds) else stringResource(R.string.viewer_previous_frame), tint = Palette.TextSecondary,
                        modifier = Modifier.size(32.dp).pressable({
                            if (audio) controller.seekTo(controller.positionMs - 5_000) else controller.stepFrames(-1, fps)
                        }, scaleTo = 0.85f).padding(6.dp),
                    )
                    Box {
                        Row(
                            Modifier.pressable({ timeMenu = true }, scaleTo = 0.96f).padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            LiveText({ timeLabel(controller.positionMs) }, style = TimecodeStyle, color = Palette.Text)
                            Icon(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.viewer_time_format), tint = Palette.TextTertiary, modifier = Modifier.size(16.dp))
                        }
                        DropdownMenu(timeMenu, onDismissRequest = { timeMenu = false }, shape = dev.finalish.ui.theme.MenuShape, containerColor = Palette.Surface3) {
                            dev.finalish.core.data.TimeFormat.entries.forEach { f ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(stringResource(f.labelRes))
                                            Text(
                                                formatTime(controller.positionMs, fps, f),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = Palette.TextTertiary,
                                            )
                                        }
                                    },
                                    trailingIcon = { if (settings.timeFormat == f) Icon(Icons.Rounded.Check, null, tint = Palette.Accent) },
                                    onClick = { timeMenu = false; vm.updateSettings { it.copy(timeFormat = f) } },
                                )
                            }
                        }
                    }
                    Icon(
                        if (audio) Icons.Rounded.Forward5 else Icons.Rounded.ChevronRight,
                        if (audio) stringResource(R.string.viewer_forward_5_seconds) else stringResource(R.string.viewer_next_frame), tint = Palette.TextSecondary,
                        modifier = Modifier.size(32.dp).pressable({
                            if (audio) controller.seekTo(controller.positionMs + 5_000) else controller.stepFrames(1, fps)
                        }, scaleTo = 0.85f).padding(6.dp),
                    )
                }

                // Right cluster: settings gear with resolution badge, fullscreen.
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    if (state.kind == MediaKind.Video && qualityChip) {
                        val res = if (state.offlinePath == null) resolutionLabel(controller.videoHeight) else stringResource(R.string.viewer_action_offline)
                        res?.let {
                            val offline = state.offlinePath != null
                            val qualityDescription = stringResource(R.string.viewer_quality_description, it)
                            Box {
                                Text(
                                    it, style = MaterialTheme.typography.labelMedium, color = Palette.Text,
                                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Palette.Surface3)
                                        .pressable({ if (offline) optionsSheet = true else qualityMenu = true }, scaleTo = 0.92f)
                                        .semantics { contentDescription = qualityDescription }
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                                // A small menu at the chip, like the official apps: Auto, Original, then each resolution.
                                DropdownMenu(qualityMenu, onDismissRequest = { qualityMenu = false }, shape = dev.finalish.ui.theme.MenuShape, containerColor = Palette.Surface3) {
                                    val active = vm.activeRendition()
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(stringResource(R.string.viewer_quality_auto))
                                                controller.videoHeight.takeIf { h -> h > 0 }?.let { h ->
                                                    Text(stringResource(R.string.viewer_quality_currently, PlaybackQuality.labelFor(h)), style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary)
                                                }
                                            }
                                        },
                                        trailingIcon = { if (settings.quality == PlaybackQuality.Auto) Icon(Icons.Rounded.Check, null, tint = Palette.Accent) },
                                        onClick = { qualityMenu = false; vm.setQuality(PlaybackQuality.Auto) },
                                    )
                                    qualityLadder(renditions).forEach { r ->
                                        DropdownMenuItem(
                                            text = { Text(r.displayLabel()) },
                                            trailingIcon = {
                                                if (settings.quality != PlaybackQuality.Auto && r == active) {
                                                    Icon(Icons.Rounded.Check, null, tint = Palette.Accent)
                                                }
                                            },
                                            onClick = { qualityMenu = false; vm.setQuality(r.quality) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                    SmallIcon(Icons.Rounded.Tune, stringResource(R.string.viewer_player_tools)) { optionsSheet = true }
                    if (state.kind == MediaKind.Video) {
                        SmallIcon(if (overlay) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen, stringResource(R.string.viewer_fullscreen), onClick = onToggleFullscreen)
                    }
                }
            }
            }
            if (optionsSheet) {
                state.file?.let { f ->
                    PlaybackOptionsSheet(
                        file = f,
                        quality = settings.quality,
                        active = vm.activeRendition(),
                        renditions = renditions,
                        playingHeight = controller.videoHeight,
                        offline = state.offlinePath != null,
                        options = view,
                        onQuality = vm::setQuality,
                        onOptions = vm::setView,
                        onDownloadStill = vm::downloadStill,
                        onDismiss = { optionsSheet = false },
                        extraToggles = ExtraToggles(
                            loop = controller.looping,
                            muted = muted,
                            onLoop = {
                                controller.setLoop(it)
                                vm.updateSettings { s -> s.copy(loopPlayback = it) }
                            },
                            onMute = { muted = it; controller.setVolume(it) },
                            speed = controller.speed,
                            onSpeed = controller::setPlaybackSpeed,
                            onCompare = if (state.versions.size > 1) ({ optionsSheet = false; vm.setTab(PanelTab.Versions) }) else null,
                            onPip = pipActivity?.takeIf { it.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE) }?.let { a ->
                                {
                                    optionsSheet = false
                                    val r = controller.videoAspect.coerceIn(0.42f, 2.39f)
                                    runCatching {
                                        a.enterPictureInPictureMode(
                                            android.app.PictureInPictureParams.Builder().setAspectRatio(android.util.Rational((r * 1000).toInt(), 1000)).build(),
                                        )
                                    }
                                }
                            },
                        ),
                    )
                }
            }
        }
    }
}

/** "V2 ▾" next to the file name: the current version, tap to switch. */
@Composable
fun VersionPill(number: Int, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(Palette.AccentContainer)
            .pressable(onClick, scaleTo = 0.92f)
            .padding(start = 7.dp, end = 3.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.viewer_version_short, number), style = TimecodeStyle.copy(fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = Palette.AccentBright)
        Icon(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.viewer_change_version), tint = Palette.AccentBright, modifier = Modifier.size(14.dp))
    }
}

private fun speedLabel(s: Float): String =
    if ((s * 10) % 1f == 0f) String.format(java.util.Locale.US, "%.1fx", s)
    else String.format(java.util.Locale.US, "%.2fx", s)

@Composable
private fun SmallIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean = false,
    tint: Color? = null,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .pressable(onClick, scaleTo = 0.85f),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = tint ?: if (active) Palette.AccentBright else Palette.TextSecondary, modifier = Modifier.size(22.dp))
    }
}
