package dev.finalish.ui.onboarding

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.finalish.R
import dev.finalish.ui.components.BrandMark
import dev.finalish.ui.components.TransferProgress
import dev.finalish.ui.components.pressable
import dev.finalish.ui.theme.Palette
import dev.finalish.ui.theme.TimecodeStyle
import kotlinx.coroutines.launch

/** Brand lockup: the mark and "Final(ish)" with the "(ish)" as a muted afterthought. */
@Composable
fun BrandLockup(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(Palette.AccentContainer),
            contentAlignment = Alignment.Center,
        ) { BrandMark(24.dp) }
        Spacer(Modifier.width(8.dp))
        Text("Final", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
        Text("(ish)", style = MaterialTheme.typography.titleMedium, color = Palette.TextTertiary)
    }
}

private val ART_HEIGHT = 340.dp
private val ONBOARDING_WIDTH = 560.dp

private data class IntroPage(@androidx.annotation.StringRes val title: Int, @androidx.annotation.StringRes val body: Int, val art: @Composable () -> Unit)

/** First-run intro: three slides, then [onDone] (which shows sign-in). */
@Composable
fun IntroPager(onDone: () -> Unit, onDemo: () -> Unit) {
    val pages = remember {
        listOf(
            IntroPage(R.string.onboarding_intro_review_title, R.string.onboarding_intro_review_body) { ArtReview() },
            IntroPage(R.string.onboarding_intro_transfers_title, R.string.onboarding_intro_transfers_body) { ArtTransfers() },
            IntroPage(R.string.onboarding_intro_notify_title, R.string.onboarding_intro_notify_body) { ArtNotify() },
        )
    }
    val pager = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    // On tablets the steps stay at phone width, centered, instead of stretching the buttons.
    Box(Modifier.fillMaxSize().background(Palette.Ink), contentAlignment = Alignment.TopCenter) { Column(
        Modifier.fillMaxHeight().widthIn(max = ONBOARDING_WIDTH).statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp),
    ) {
        BrandLockup(Modifier.padding(top = 18.dp))
        HorizontalPager(pager, Modifier.weight(1f)) { i ->
            val p = pages[i]
            // Art and text stay together, centered; tall screens (tablets) don't open a gap between them.
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
                // The art is drawn for 340 dp; on short screens (16:9 phones) it shrinks to fit,
                // so the title and text below always have room.
                BoxWithConstraints(Modifier.padding(top = 16.dp).fillMaxWidth().weight(1f, fill = false), contentAlignment = Alignment.TopCenter) {
                    val h = minOf(maxHeight, ART_HEIGHT)
                    val scale = h / ART_HEIGHT
                    val w = maxWidth
                    Box(Modifier.fillMaxWidth().height(h).clip(RoundedCornerShape(28.dp)).background(Palette.Surface1), contentAlignment = Alignment.Center) {
                        Box(
                            Modifier.requiredSize(w / scale, ART_HEIGHT)
                                .graphicsLayer { scaleX = scale; scaleY = scale },
                        ) { p.art() }
                    }
                }
                Spacer(Modifier.height(20.dp))
                Text(stringResource(p.title), style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(10.dp))
                Text(stringResource(p.body), style = MaterialTheme.typography.bodyLarge, color = Palette.TextSecondary)
            }
        }
        Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(pages.size) { i ->
                val w by animateDpAsState(if (pager.currentPage == i) 20.dp else 6.dp, label = "dot")
                val c by animateColorAsState(if (pager.currentPage == i) Palette.Accent else Palette.Surface4, label = "dotc")
                Box(Modifier.width(w).height(6.dp).clip(CircleShape).background(c))
            }
        }
        val last = pager.currentPage == pages.lastIndex
        Box(
            Modifier.padding(top = 20.dp).fillMaxWidth().height(54.dp).clip(RoundedCornerShape(16.dp)).background(Palette.Accent)
                .pressable({ if (last) onDone() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }, scaleTo = 0.97f),
            contentAlignment = Alignment.Center,
        ) { Text(if (last) stringResource(R.string.onboarding_get_started) else stringResource(R.string.onboarding_next), style = MaterialTheme.typography.titleMedium, color = Palette.OnAccent) }
        Box(
            Modifier.padding(top = 6.dp, bottom = 8.dp).fillMaxWidth().height(46.dp).clip(RoundedCornerShape(16.dp)).pressable(onDemo, scaleTo = 0.97f),
            contentAlignment = Alignment.Center,
        ) { Text(stringResource(R.string.onboarding_explore_demo), style = MaterialTheme.typography.bodyLarge, color = Palette.TextSecondary) }
        Text(
            stringResource(R.string.onboarding_unofficial_disclaimer),
            style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 12.dp),
        )
    } }
}

@Composable
private fun ArtReview() {
    val frame = appear(0)
    // The playhead runs along the timeline; each comment's pin pops as it's passed, and its
    // note appears below at the same moment.
    val play = appear(200, durationMs = PLAY_MS, easing = androidx.compose.animation.core.LinearEasing)
    val notes = listOf(
        ReviewNote(0.22f, Palette.Green, "00:00:28", R.string.onboarding_art_comment),
        ReviewNote(0.5f, Palette.Amber, "00:01:04", R.string.onboarding_art_comment_hold),
        ReviewNote(0.78f, Palette.Red, "00:01:39", R.string.onboarding_art_comment_logo),
    )
    val shown = notes.map { appear(200 + (PLAY_MS * it.at / PLAY_END).toInt()) }
    Box(Modifier.fillMaxSize().padding(22.dp)) {
        Box(Modifier.fillMaxWidth().height(120.dp).rise(frame.value).clip(RoundedCornerShape(18.dp)).background(Color(0xFF1F2A24)))
        BoxWithConstraints(Modifier.fillMaxWidth().offset(y = 128.dp).height(24.dp)) {
            val w = maxWidth
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(6.dp).clip(CircleShape).background(Palette.Surface4))
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(0.001f + PLAY_END * play.value).height(6.dp).clip(CircleShape).background(Palette.Accent))
            notes.forEachIndexed { i, n ->
                Column(Modifier.offset(x = w * n.at - 6.dp).pop(shown[i].value), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(n.color))
                    Box(Modifier.width(1.5.dp).height(6.dp).background(n.color.copy(alpha = 0.7f)))
                }
            }
        }
        notes.forEachIndexed { i, n ->
            Row(
                Modifier.offset(y = 166.dp + 44.dp * i).rise(shown[i].value).fillMaxWidth().height(38.dp)
                    .clip(RoundedCornerShape(14.dp)).background(Palette.Surface3).padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(20.dp).clip(CircleShape).border(2.dp, n.color, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(n.time, style = TimecodeStyle, color = n.color)
                Text(
                    "  " + stringResource(n.text), style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private class ReviewNote(val at: Float, val color: Color, val time: String, @androidx.annotation.StringRes val text: Int)
private const val PLAY_MS = 2400
private const val PLAY_END = 0.92f
private const val UPLOAD_MS = 9000

@Composable
private fun ArtTransfers() {
    val upload = appear(0)
    // The upload creeps along for a while, its wave moving, like a real transfer.
    val progress = appear(150, durationMs = UPLOAD_MS, easing = androidx.compose.animation.core.LinearEasing)
    val offline = appear(250)
    val check = appear(650)
    val plane = appear(500)
    Column(Modifier.fillMaxSize().padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.onboarding_art_transfers_header), style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary)
        Row(Modifier.fillMaxWidth().rise(upload.value).clip(RoundedCornerShape(16.dp)).background(Palette.Surface3).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(56.dp, 38.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF2A3338)))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Spot_v7.mov", style = MaterialTheme.typography.titleSmall)
                val done = 0.18f + 0.7f * progress.value
                TransferProgress(done, Modifier.padding(vertical = 4.dp))
                Text(stringResource(R.string.onboarding_art_upload_progress, "${(done * 100).toInt()}%", "18 MB/s"), style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary)
            }
        }
        Row(Modifier.fillMaxWidth().rise(offline.value).clip(RoundedCornerShape(16.dp)).background(Palette.Surface3).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(56.dp, 38.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF3A2E2E)))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Full Spot - Graded.mp4", style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.onboarding_art_available_offline, "412 MB"), style = MaterialTheme.typography.labelSmall, color = Palette.Green)
            }
            Icon(Icons.Rounded.Check, null, tint = Palette.Green, modifier = Modifier.size(18.dp).pop(check.value))
        }
        Spacer(Modifier.weight(1f))
        Row(
            Modifier.align(Alignment.CenterHorizontally).rise(plane.value).clip(CircleShape).background(Palette.Green.copy(alpha = 0.14f)).padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Wifi, null, tint = Palette.Green, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.onboarding_art_works_on_a_plane), style = MaterialTheme.typography.labelMedium, color = Palette.Green)
        }
    }
}

@Composable
private fun ArtNotify() {
    val first = appear(0)
    val second = appear(250)
    val third = appear(500)
    val card = appear(700)
    Column(Modifier.fillMaxSize().padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Notifications drop in from the top, like they arrive.
        NotifCard(pluralStringResource(R.plurals.onboarding_art_notif_comments_title, 2, 2, "Spot_v7"), stringResource(R.string.onboarding_art_notif_comments_body, "John"), true, Modifier.rise(first.value, (-14).dp))
        NotifCard(stringResource(R.string.onboarding_art_notif_link_title), pluralStringResource(R.plurals.onboarding_art_notif_link_body, 3, 3), false, Modifier.rise(second.value, (-14).dp))
        NotifCard(stringResource(R.string.onboarding_art_notif_upload_title), stringResource(R.string.onboarding_art_notif_upload_body), false, Modifier.rise(third.value, (-14).dp))
        Spacer(Modifier.weight(1f))
        Column(Modifier.fillMaxWidth().rise(card.value).clip(RoundedCornerShape(18.dp)).background(Palette.Surface3).padding(14.dp)) {
            Text("Full Spot - Graded.mp4 · V3", style = MaterialTheme.typography.titleSmall)
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f).height(36.dp).clip(RoundedCornerShape(12.dp)).background(Palette.Green.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                    Text("✓ " + stringResource(R.string.onboarding_art_approved), style = MaterialTheme.typography.labelLarge, color = Palette.Green)
                }
                Box(Modifier.weight(1f).height(36.dp).clip(RoundedCornerShape(12.dp)).background(Palette.Surface4), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.onboarding_art_share_link), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

/**
 * 0 → 1 once, [delayMs] after the art first shows: the intro's light entrance. Starts at 1
 * when the system asks for reduced motion.
 */
@Composable
private fun appear(
    delayMs: Int,
    durationMs: Int = 420,
    easing: androidx.compose.animation.core.Easing = androidx.compose.animation.core.FastOutSlowInEasing,
): androidx.compose.runtime.State<Float> {
    val reduced = dev.finalish.ui.theme.LocalReducedMotion.current
    val a = remember { androidx.compose.animation.core.Animatable(if (reduced) 1f else 0f) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (a.value < 1f) {
            kotlinx.coroutines.delay(delayMs.toLong())
            a.animateTo(1f, androidx.compose.animation.core.tween(durationMs, easing = easing))
        }
    }
    return a.asState()
}

/** Fades in while sliding [from] its offset to place. */
private fun Modifier.rise(p: Float, from: androidx.compose.ui.unit.Dp = 12.dp) = graphicsLayer {
    alpha = p
    translationY = (1f - p) * from.toPx()
}

/** Grows from nothing with a small overshoot. */
private fun Modifier.pop(p: Float) = graphicsLayer {
    val s = if (p < 0.7f) p / 0.7f * 1.15f else 1.15f - (p - 0.7f) / 0.3f * 0.15f
    scaleX = s; scaleY = s; alpha = minOf(1f, p * 2f)
}

/** The art of intro step [index], for screenshot tests. */
@Composable
internal fun IntroArt(index: Int) = when (index) {
    0 -> ArtReview()
    1 -> ArtTransfers()
    else -> ArtNotify()
}

@Composable
private fun NotifCard(title: String, body: String, strong: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(if (strong) Palette.Surface3 else Palette.Surface2).padding(12.dp),
    ) {
        Box(Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(Palette.AccentContainer), contentAlignment = Alignment.Center) { BrandMark(24.dp) }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
        }
    }
}

// ---------------------------------------------------------------- permissions

enum class AppPermission(@androidx.annotation.StringRes val title: Int, @androidx.annotation.StringRes val why: Int, val icon: ImageVector) {
    Notifications(R.string.onboarding_permission_notifications, R.string.onboarding_permission_notifications_why, Icons.Rounded.Notifications),
    Background(R.string.onboarding_permission_background, R.string.onboarding_permission_background_why, Icons.Rounded.BatteryChargingFull),
    Links(R.string.onboarding_permission_links, R.string.onboarding_permission_links_why, Icons.Rounded.Link),
}

fun AppPermission.granted(context: Context): Boolean = when (this) {
    AppPermission.Notifications -> Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    AppPermission.Background -> (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(context.packageName)
    // Before Android 12 the system asks which app opens a link; from 12 on, links to a domain
    // the app can't verify (frame.io isn't ours) only open here once the user adds them.
    AppPermission.Links -> Build.VERSION.SDK_INT < 31 || frameLinksSelected(context)
}

private fun frameLinksSelected(context: Context): Boolean = Build.VERSION.SDK_INT >= 31 && runCatching {
    val dvm = context.getSystemService(android.content.pm.verify.domain.DomainVerificationManager::class.java)
    val states = dvm.getDomainVerificationUserState(context.packageName)?.hostToStateMap.orEmpty()
    dev.finalish.core.data.FrameLinks.HOSTS.all { h ->
        states[h] == android.content.pm.verify.domain.DomainVerificationUserState.DOMAIN_STATE_SELECTED ||
            states[h] == android.content.pm.verify.domain.DomainVerificationUserState.DOMAIN_STATE_VERIFIED
    }
}.getOrDefault(false)

/** Live permission states, re-read whenever the screen comes back (e.g. from system settings). */
@Composable
fun rememberPermissionStates(): Pair<Map<AppPermission, Boolean>, (AppPermission) -> Unit> {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val states = remember(tick) { AppPermission.entries.associateWith { it.granted(context) } }
    val request: (AppPermission) -> Unit = { p ->
        when (p) {
            AppPermission.Notifications -> if (Build.VERSION.SDK_INT >= 33) launcher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            AppPermission.Background -> runCatching {
                // The settings list (not the direct "ignore optimizations" dialog, which Play restricts).
                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
            // Android's "Open by default" page for the app: "Add link" ticks next.frame.io and f.io.
            AppPermission.Links -> if (Build.VERSION.SDK_INT >= 31) runCatching {
                context.startActivity(Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, Uri.fromParts("package", context.packageName, null)))
            }
        }
    }
    return states to request
}

/** Opens this app's system settings page (to change a permission that was already decided). */
fun openAppSettings(context: Context) {
    runCatching {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
    }
}

/** One-time step after sign-in: ask for everything the app needs, with a line on why. */
@Composable
fun PermissionsScreen(onDone: () -> Unit) {
    val (states, request) = rememberPermissionStates()
    // On tablets the steps stay at phone width, centered, instead of stretching the buttons.
    Box(Modifier.fillMaxSize().background(Palette.Ink), contentAlignment = Alignment.TopCenter) { Column(
        Modifier.fillMaxHeight().widthIn(max = ONBOARDING_WIDTH).statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp),
    ) {
        BrandLockup(Modifier.padding(top = 18.dp))
        Spacer(Modifier.height(40.dp))
        Text(stringResource(R.string.onboarding_permissions_title), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(R.string.onboarding_permissions_body),
            style = MaterialTheme.typography.bodyLarge, color = Palette.TextSecondary,
        )
        Spacer(Modifier.height(12.dp))
        AppPermission.entries.forEach { p ->
            PermissionRow(p, states[p] == true, onAllow = { request(p) })
        }
        Spacer(Modifier.weight(1f))
        Box(
            Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(16.dp)).background(Palette.Accent).pressable(onDone, scaleTo = 0.97f),
            contentAlignment = Alignment.Center,
        ) { Text(stringResource(R.string.onboarding_continue), style = MaterialTheme.typography.titleMedium, color = Palette.OnAccent) }
        Box(
            Modifier.padding(top = 6.dp, bottom = 16.dp).fillMaxWidth().height(46.dp).clip(RoundedCornerShape(16.dp)).pressable(onDone, scaleTo = 0.97f),
            contentAlignment = Alignment.Center,
        ) { Text(stringResource(R.string.onboarding_later), style = MaterialTheme.typography.bodyLarge, color = Palette.TextSecondary) }
    } }
}

@Composable
fun PermissionRow(p: AppPermission, granted: Boolean, onAllow: () -> Unit, divider: Boolean = true) {
    Column {
        if (divider) Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Surface3))
        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Palette.AccentContainer), contentAlignment = Alignment.Center) {
                Icon(p.icon, null, tint = Palette.AccentBright, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(p.title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(p.why), style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
            }
            Spacer(Modifier.width(10.dp))
            if (granted) {
                Row(
                    Modifier.clip(CircleShape).background(Palette.Green.copy(alpha = 0.16f)).padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.onboarding_permission_allowed), style = MaterialTheme.typography.labelMedium, color = Palette.Green)
                }
            } else {
                Box(
                    Modifier.clip(RoundedCornerShape(11.dp)).background(Palette.Accent).pressable(onAllow, scaleTo = 0.94f)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) { Text(stringResource(R.string.onboarding_permission_allow), style = MaterialTheme.typography.labelLarge, color = Palette.OnAccent) }
            }
        }
    }
}
