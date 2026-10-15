package dev.finalish.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.finalish.R
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.request.ImageRequest
import dev.finalish.core.model.MediaKind
import dev.finalish.core.model.User
import dev.finalish.core.util.Format
import dev.finalish.ui.theme.Motion
import dev.finalish.ui.theme.Palette
import dev.finalish.ui.theme.statusColor

/** Subtle press-down scale for a tactile feel on every tappable card. */
fun Modifier.pressable(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    scaleTo: Float = 0.965f,
    enabled: Boolean = true,
    /** What accessibility services announce; most pressables are buttons. */
    role: Role? = Role.Button,
): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) scaleTo else 1f, Motion.snappy(), label = "press")
    val haptics = LocalHapticFeedback.current
    this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .combinedClickable(
            interactionSource = source,
            indication = null,
            enabled = enabled,
            role = role,
            onClick = onClick,
            onLongClick = onLongClick?.let { lc -> { haptics.performHapticFeedback(HapticFeedbackType.LongPress); lc() } },
        )
}

/**
 * Animated skeleton placeholder. The sweep is read only while drawing (no recomposition per frame),
 * is sized to the element, and starts in step with the clock so every skeleton on screen moves together.
 */
fun Modifier.shimmer(shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(10.dp)): Modifier = composed {
    val base = clip(shape).background(Palette.Surface2)
    if (dev.finalish.ui.theme.LocalReducedMotion.current) return@composed base
    val start = remember { (android.os.SystemClock.uptimeMillis() % SHIMMER_MS) / SHIMMER_MS.toFloat() }
    val t = rememberInfiniteTransition(label = "shimmer")
    val p = t.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(SHIMMER_MS.toInt(), easing = LinearEasing), RepeatMode.Restart),
        label = "shimmerX",
    )
    val hi = Palette.Surface3
    base.drawBehind {
        val band = maxOf(size.width * 0.6f, 120.dp.toPx())
        val x = ((p.value + start) % 1f) * (size.width + 2 * band) - band
        drawRect(
            Brush.linearGradient(
                colors = listOf(Color.Transparent, hi, Color.Transparent),
                start = Offset(x, 0f),
                end = Offset(x + band, size.height * 0.4f),
            ),
        )
    }
}

private const val SHIMMER_MS = 1400L

/** Signed media URLs rotate their query string; cache by path instead. */
fun stableImageKey(url: String): String = url.substringBefore('?')

@Composable
fun RemoteImage(
    url: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    contentDescription: String? = null,
    fallback: @Composable () -> Unit = { Box(Modifier.fillMaxSize().background(Palette.Surface2)) },
) {
    if (url == null) {
        Box(modifier) { fallback() }
        return
    }
    val context = LocalContext.current
    // Keyed on the stable part: a refresh that only rotated the signature keeps the image (and its
    // loading state) instead of starting over.
    val key = stableImageKey(url)
    val request = remember(key) {
        ImageRequest.Builder(context).data(url).memoryCacheKey(key).diskCacheKey(key).build()
    }
    LoadingImage(request, modifier, contentScale, contentDescription, onError = { fallback() })
}

/** Tries each URL in turn until one loads (signed renditions can fail server-side). */
@Composable
fun RemoteImageChain(
    urls: List<String>,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    fallback: @Composable () -> Unit = { Box(Modifier.fillMaxSize().background(Palette.Surface2)) },
) {
    // Keyed on the stable parts of the URLs, so rotated signatures don't restart the chain.
    val keys = remember(urls) { urls.map(::stableImageKey) }
    var index by remember(keys) { androidx.compose.runtime.mutableIntStateOf(0) }
    val url = urls.getOrNull(index)
    if (url == null) {
        Box(modifier) { fallback() }
        return
    }
    val context = LocalContext.current
    val key = stableImageKey(url)
    val request = remember(key) {
        ImageRequest.Builder(context).data(url).memoryCacheKey(key).diskCacheKey(key).build()
    }
    LoadingImage(request, modifier, contentScale, contentDescription = null, onError = {
        if (index < urls.lastIndex) androidx.compose.runtime.SideEffect { index++ } else fallback()
    })
}

/**
 * The image over a shimmer while it loads, or [onError] if it fails. A plain AsyncImage with the
 * placeholder beside it: SubcomposeAsyncImage subcomposes every cell, which made long grids stutter.
 */
@Composable
private fun LoadingImage(
    request: ImageRequest,
    modifier: Modifier,
    contentScale: ContentScale,
    contentDescription: String?,
    onError: @Composable () -> Unit,
) {
    var state by remember(request) { androidx.compose.runtime.mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty) }
    Box(modifier) {
        when (state) {
            is AsyncImagePainter.State.Success -> Unit
            is AsyncImagePainter.State.Error -> onError()
            else -> Box(Modifier.matchParentSize().shimmer(RoundedCornerShape(0.dp)))
        }
        AsyncImage(
            model = request,
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = Modifier.matchParentSize(),
            onState = { state = it },
        )
    }
}

@Composable
@Suppress("ModifierParameter") // size reads naturally as the second positional argument
fun Avatar(user: User?, size: Dp = 28.dp, modifier: Modifier = Modifier, ring: Color? = null) {
    val name = user?.displayName ?: "?"
    val hue = (name.hashCode() and 0xFFFFFF) % 360
    val bg = Color.hsl(hue.toFloat(), 0.45f, 0.38f)
    Box(
        modifier
            .size(size)
            // Optional colour ring (the user's colour), with a thin gap like Frame.io.
            .then(if (ring != null) Modifier.border(1.5.dp, ring, CircleShape).padding(2.5.dp) else Modifier)
            .clip(CircleShape)
            .background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            Format.initials(name),
            color = Color.White,
            fontSize = (size.value * 0.38f).sp,
            fontWeight = FontWeight.SemiBold,
        )
        user?.avatarUrl?.let { RemoteImage(it, Modifier.fillMaxSize(), fallback = {}) }
    }
}

/**
 * A stable colour per person, used for avatar rings, timeline markers and ranges.
 * Frame.io doesn't expose its own assignment through the API, so this derives one
 * from the user's id from a similar palette.
 */
fun userColor(user: User?): Color {
    val key = user?.id ?: user?.email ?: user?.name ?: return Palette.AccentBright
    return UserPalette[(key.hashCode() and 0x7FFFFFFF) % UserPalette.size]
}

private val UserPalette = listOf(
    Color(0xFFE8508F), // pink
    Color(0xFF7ED321), // green
    Color(0xFF2FA6F7), // blue
    Color(0xFFD63BD6), // magenta
    Color(0xFFF5A623), // orange
    Color(0xFF9B6BFF), // violet
    Color(0xFF2BD9C2), // teal
    Color(0xFFFF5A5A), // red
    Color(0xFFF8D51C), // yellow
)

@Composable
fun StatusPill(label: String?, modifier: Modifier = Modifier, compact: Boolean = false) {
    if (label.isNullOrBlank()) return
    val color = statusColor(label)
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = if (compact) 6.dp else 9.dp, vertical = if (compact) 2.dp else 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Spacer(Modifier.size(5.dp))
        Text(
            label,
            color = color,
            style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
            maxLines = 1,
        )
    }
}

@Composable
fun RatingStars(
    value: Int,
    max: Int = 5,
    size: Dp = 16.dp,
    onChange: ((Int) -> Unit)? = null,
) {
    val haptics = LocalHapticFeedback.current
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (i in 1..max) {
            val filled = i <= value
            Icon(
                if (filled) Icons.Rounded.Star else Icons.Rounded.StarOutline,
                contentDescription = null,
                tint = if (filled) Palette.Amber else Palette.TextTertiary,
                modifier = Modifier
                    .size(size)
                    .then(
                        if (onChange != null) Modifier.clickable {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onChange(if (value == i) 0 else i)
                        } else Modifier,
                    ),
            )
        }
    }
}

fun MediaKind.icon(): ImageVector = when (this) {
    MediaKind.Folder -> Icons.Rounded.Folder
    MediaKind.Video -> Icons.Rounded.Movie
    MediaKind.Image -> Icons.Rounded.Image
    MediaKind.Audio -> Icons.Rounded.AudioFile
    MediaKind.Pdf -> Icons.AutoMirrored.Rounded.Article
    MediaKind.Other -> Icons.AutoMirrored.Rounded.InsertDriveFile
}

@Composable
fun KindPlaceholder(kind: MediaKind, modifier: Modifier = Modifier) {
    Box(
        modifier.background(
            Brush.linearGradient(listOf(Palette.Surface3, Palette.Surface2)),
        ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(kind.icon(), null, tint = Palette.TextTertiary, modifier = Modifier.size(30.dp))
    }
}

@Suppress("ModifierParameter") // the message reads naturally as the third positional argument
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String? = null,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    /** Show the Final(ish) mark instead of [icon] (empty folders, first-run states). */
    brand: Boolean = false,
    /** Tint for problem states (errors use red, waiting uses amber). */
    tone: Color? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 36.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(if (brand) 96.dp else 72.dp)
                .clip(if (tone != null) androidx.compose.foundation.shape.CircleShape else RoundedCornerShape(if (brand) 28.dp else 22.dp))
                .background(
                    when {
                        brand -> Palette.AccentContainer
                        tone != null -> tone.copy(alpha = 0.16f)
                        else -> Palette.Surface2
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (brand) BrandMark(64.dp, pairs = 2)
            else Icon(icon, null, tint = tone ?: Palette.AccentBright, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        if (message != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 320.dp),
            )
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(22.dp))
            PrimaryButton(actionLabel, onAction)
        }
    }
}

/** [retrying]: the screen keeps refreshing by itself (AutoRefresh), so say so and offer "Retry now". */
@Composable
fun ErrorState(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier, retrying: Boolean = false) =
    EmptyState(
        Icons.Rounded.CloudOff, stringResource(R.string.common_couldnt_load),
        if (retrying) stringResource(R.string.common_retrying_shortly, message.trimEnd('.', ' ')) else message,
        modifier, if (retrying) stringResource(R.string.common_retry_now) else stringResource(R.string.common_try_again), onRetry, tone = Palette.Red,
    )

/** The default network as the transfers see it; updates live. */
@androidx.compose.runtime.Immutable
data class NetworkStatus(val online: Boolean, val metered: Boolean)

@Composable
fun rememberNetworkStatus(): NetworkStatus {
    val context = androidx.compose.ui.platform.LocalContext.current
    val cm = remember { context.getSystemService(android.net.ConnectivityManager::class.java) }
    fun statusOf(caps: android.net.NetworkCapabilities?) = NetworkStatus(
        online = caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
        metered = caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true,
    )
    var status by remember { mutableStateOf(statusOf(cm.getNetworkCapabilities(cm.activeNetwork))) }
    androidx.compose.runtime.DisposableEffect(cm) {
        val cb = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: android.net.Network, caps: android.net.NetworkCapabilities) {
                status = statusOf(caps)
            }
            override fun onLost(network: android.net.Network) {
                // The default network went away; a new one (if any) reports itself via onCapabilitiesChanged.
                status = NetworkStatus(online = false, metered = true)
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb) }
        onDispose { runCatching { cm.unregisterNetworkCallback(cb) } }
    }
    return status
}

/** True while the device has a validated internet connection; updates live. */
@Composable
fun rememberIsOnline(): Boolean = rememberNetworkStatus().online

/** Amber banner shown while offline: cached copies stay usable, writes are queued. */
@Composable
fun OfflineBanner(modifier: Modifier = Modifier) {
    val online = rememberIsOnline()
    androidx.compose.animation.AnimatedVisibility(!online, modifier = modifier) { OfflineBannerContent() }
}

@Composable
fun OfflineBannerContent() {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.Amber.copy(alpha = 0.16f)).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.CloudOff, null, tint = Palette.Amber, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        val offlineTitle = stringResource(R.string.common_offline_title)
        val offlineBody = stringResource(R.string.common_offline_body)
        Text(
            androidx.compose.ui.text.buildAnnotatedString {
                withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)) { append(offlineTitle) }
                append(" ")
                append(offlineBody)
            },
            style = MaterialTheme.typography.bodySmall, color = Palette.Amber,
        )
    }
}

@Composable
fun PrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(50.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Palette.Accent,
            contentColor = Palette.OnAccent,
            disabledContainerColor = Palette.Surface3,
            disabledContentColor = Palette.TextTertiary,
        ),
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
        }
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun SecondaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(50.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Palette.Surface3, contentColor = Palette.Text),
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
        }
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 22.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = Palette.TextTertiary,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

/** Translucent round button for overlays on top of media. */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    tint: Color = Color.White,
    active: Boolean = false,
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(if (active) Palette.Accent else Color(0x66000000))
            .border(1.dp, Color(0x1FFFFFFF), CircleShape)
            .pressable(onClick, scaleTo = 0.9f),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

/**
 * Progress for something that is moving (uploads, downloads): Material 3's wavy bar.
 * Paused or waiting work uses a plain straight bar instead, so the two read differently at a glance.
 */
@Composable
fun TransferProgress(progress: Float, modifier: Modifier = Modifier, moving: Boolean = true, color: Color = Palette.Accent) {
    // Workers report in steps; ease between them so the bar glides instead of jumping. The animated
    // values are read only while drawing, so each frame redraws the bar without recomposing it.
    val eased = androidx.compose.animation.core.animateFloatAsState(progress.coerceIn(0f, 1f), Motion.gentle(), label = "progress")
    if (moving && !dev.finalish.ui.theme.LocalReducedMotion.current) {
        // Our own wave (the Material 3 expressive one isn't public in this version): the filled part
        // is a gentle sine that slowly travels, the rest is a flat track.
        val phase = androidx.compose.animation.core.rememberInfiniteTransition(label = "wave").animateFloat(
            0f, (2 * Math.PI).toFloat(),
            androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1400, easing = androidx.compose.animation.core.LinearEasing)),
            label = "phase",
        )
        val track = Palette.Surface4
        val path = remember { androidx.compose.ui.graphics.Path() }
        androidx.compose.foundation.Canvas(modifier.fillMaxWidth().height(10.dp)) {
            val w = size.width
            val cy = size.height / 2
            val stroke = 3.dp.toPx()
            val amp = 1.8.dp.toPx()
            val wave = 14.dp.toPx()
            val end = (w * eased.value.coerceIn(0f, 1f))
            val gap = 4.dp.toPx()
            val shift = phase.value
            if (end + gap < w) drawLine(track, androidx.compose.ui.geometry.Offset(end + gap, cy), androidx.compose.ui.geometry.Offset(w, cy), stroke, androidx.compose.ui.graphics.StrokeCap.Round)
            if (end > 1f) {
                path.rewind()
                var x = 0f
                path.moveTo(0f, cy + amp * kotlin.math.sin(shift))
                while (x < end) {
                    x = (x + 4f).coerceAtMost(end)
                    path.lineTo(x, cy + amp * kotlin.math.sin(x / wave * 2 * Math.PI.toFloat() + shift))
                }
                drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round))
            }
        }
    } else {
        androidx.compose.material3.LinearProgressIndicator(
            progress = { eased.value },
            modifier = modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
            color = if (moving) color else Palette.Amber,
            trackColor = Palette.Surface4,
            drawStopIndicator = {},
        )
    }
}

/** From this width on (Material's "expanded" class: tablets, unfolded phones) layouts go wide. */
internal val WIDE_LAYOUT = 840.dp
private val READABLE_WIDTH = 720.dp

/**
 * Centers [content] at a readable width when [wide]; otherwise it fills the space as before. The
 * content stays in the same place in the tree either way, so crossing the threshold (rotating a
 * foldable) keeps its state.
 */
@Composable
fun ReadableWidth(wide: Boolean, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(if (wide) Modifier.fillMaxHeight().widthIn(max = READABLE_WIDTH) else Modifier.fillMaxSize()) { content() }
    }
}

/**
 * Width taken by the side bar on the left of the content (Home on wide screens). Bottom sheets
 * opened from that content shift right by half of it, so they sit centred on the content.
 */
val LocalSideBarWidth = androidx.compose.runtime.staticCompositionLocalOf { 0.dp }

/** True on tablets and unfolded phones: the window is at least [WIDE_LAYOUT] wide. */
@Composable
fun isWideWindow(): Boolean {
    val width = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.width
    return with(androidx.compose.ui.platform.LocalDensity.current) { width.toDp() } >= WIDE_LAYOUT
}

/** A whole secondary screen (lists, forms) at a readable width on wide screens, over the app background. */
@Composable
fun ReadableScreen(content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Palette.Ink)) {
        ReadableWidth(maxWidth >= WIDE_LAYOUT, content)
    }
}
