package dev.finalish.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The user-selectable accent ("Tema e colori"). Glicine is the brand default. */
enum class AccentTheme(val label: String, val color: Color, val light: Color, val tint: Color) {
    Glicine("Wisteria", Color(0xFFAE95F4), Color(0xFFDACDFB), Color(0xFF2B2342)),
    Lavanda("Lavender", Color(0xFF8B7CFF), Color(0xFFC7BEFF), Color(0xFF2A2448)),
    Ambra("Amber", Color(0xFFFFB547), Color(0xFFFFD79A), Color(0xFF3A2A12)),
    Lime("Lime", Color(0xFFC6F432), Color(0xFFE3FA98), Color(0xFF2A3314)),
    Menta("Mint", Color(0xFF4BE0BC), Color(0xFFA3F0DD), Color(0xFF123029)),
    Corallo("Coral", Color(0xFFFF7A66), Color(0xFFFFB0A6), Color(0xFF3A1C19)),
    Ciano("Cyan", Color(0xFF5CD3FF), Color(0xFFB3E9FF), Color(0xFF12303A)),
    Mono("Black & white", Color(0xFFF5F5F5), Color(0xFFFFFFFF), Color(0xFF2A2A2A)),
}

/** Live theme values; changing them recomposes everything that reads [Palette]. */
object ThemeState {
    var accent by mutableStateOf(AccentTheme.Glicine.color)
    var accentLight by mutableStateOf(AccentTheme.Glicine.light)
    var accentTint by mutableStateOf(AccentTheme.Glicine.tint)
    var pureBlack by mutableStateOf(false)
}

/** Final(ish) palette — a quiet, near-black canvas so the media is the hero. */
object Palette {
    val Ink: Color get() = if (ThemeState.pureBlack) Color.Black else Color(0xFF0A0A0C)
    val Surface1 = Color(0xFF121216)
    val Surface2 = Color(0xFF16161B)
    val Surface3 = Color(0xFF1C1C22)
    val Surface4 = Color(0xFF26262E)
    val Hairline = Color(0x14FFFFFF)
    val Outline = Color(0xFF33333C)

    val Text = Color(0xFFF4F4F6)
    val TextSecondary = Color(0xFFA1A1AA)
    val TextTertiary = Color(0xFF8A8A96)

    val Accent: Color get() = ThemeState.accent
    val AccentBright: Color get() = ThemeState.accentLight
    val AccentDeep: Color get() = lerp(ThemeState.accent, Color.Black, 0.25f)
    val AccentContainer: Color get() = ThemeState.accentTint
    /** Text and icons drawn on top of the accent (every accent is light, so dark ink). */
    val OnAccent = Color(0xFF0B0A0E)
    val Link: Color get() = lerp(ThemeState.accent, Color.White, 0.3f)

    val Green = Color(0xFF43D29A)
    val Amber = Color(0xFFF2B544)
    val Blue = Color(0xFF5AA9FF)
    val Red = Color(0xFFFF6B7D)
    val Pink = Color(0xFFFF6BC1)

    /** Annotation pen colors, matching common review tooling. */
    val Annotation = listOf(
        Color(0xFFF22237), Color(0xFFFFB800), Color(0xFF3DD68C),
        Color(0xFF4DA3FF), Color(0xFF9C90FF), Color(0xFFFFFFFF),
    )

    /** Kept for call sites that want a flat fill; the refresh uses a solid accent. */
    val AccentGradient: Brush get() = Brush.linearGradient(listOf(Accent, Accent))
    val ScrimTop = Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent))
    val ScrimBottom = Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000)))
}

/** Maps a Frame.io status label to a color. */
fun statusColor(label: String?): Color {
    val l = label.orEmpty().lowercase()
    return when {
        "await" in l || "pending" in l || "review" in l -> Palette.Amber
        "revision" in l || "reject" in l || "change" in l || "fix" in l -> Palette.Red
        "approv" in l -> Palette.Green
        "progress" in l || "working" in l -> Palette.Blue
        l.isBlank() -> Palette.TextTertiary
        else -> Palette.AccentBright
    }
}

private val Sans = FontFamily.Default

private val AppTypography = Typography(
    displaySmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-0.8).sp),
    headlineMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.6).sp),
    headlineSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.4).sp),
    titleLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, lineHeight = 24.sp, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = (-0.1).sp),
    titleSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.2.sp),
    labelSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 10.5.sp, lineHeight = 14.sp, letterSpacing = 0.6.sp),
)

/** Monospaced digits for timecodes so the readout never jitters. */
val TimecodeStyle = TextStyle(
    fontFamily = Sans,
    fontWeight = FontWeight.Medium,
    fontSize = 13.sp,
    letterSpacing = 0.2.sp,
    fontFeatureSettings = "tnum",
)

/** Floating menus (quality, sort, ⋯): rounder than Material's 4 dp, like the app's cards and sheets. */
val MenuShape = RoundedCornerShape(18.dp)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private fun scheme() = darkColorScheme(
    primary = Palette.Accent,
    onPrimary = Palette.OnAccent,
    primaryContainer = Palette.AccentContainer,
    onPrimaryContainer = Palette.AccentBright,
    secondary = Palette.AccentBright,
    onSecondary = Palette.OnAccent,
    secondaryContainer = Palette.Surface3,
    onSecondaryContainer = Palette.Text,
    tertiary = Palette.Green,
    background = Palette.Ink,
    onBackground = Palette.Text,
    surface = Palette.Ink,
    onSurface = Palette.Text,
    surfaceVariant = Palette.Surface2,
    onSurfaceVariant = Palette.TextSecondary,
    surfaceContainerLowest = Palette.Ink,
    surfaceContainerLow = Palette.Surface1,
    surfaceContainer = Palette.Surface2,
    surfaceContainerHigh = Palette.Surface3,
    surfaceContainerHighest = Palette.Surface4,
    surfaceBright = Palette.Surface4,
    outline = Palette.Outline,
    outlineVariant = Palette.Hairline,
    error = Palette.Red,
    onError = Color.White,
    scrim = Color(0xCC000000),
    inverseSurface = Palette.Text,
    inverseOnSurface = Palette.Ink,
)

@Immutable
object Motion {
    val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    fun <T> enter() = tween<T>(durationMillis = 380, easing = EmphasizedDecelerate)
    fun <T> exit() = tween<T>(durationMillis = 220, easing = Emphasized)
    fun <T> snappy() = spring<T>(dampingRatio = 0.82f, stiffness = 700f)
    fun <T> gentle() = spring<T>(dampingRatio = 0.9f, stiffness = 300f)
}

/** True when Android's "Remove animations" is on: loops (shimmer, waves, aurora) hold still. */
/**
 * Paging inside a sheet (menu → sub-page, folder → subfolder): a quarter-width slide in the
 * direction of travel, the outgoing page fading fast so the two never read on top of each other.
 */
fun sheetPage(forward: Boolean): androidx.compose.animation.ContentTransform {
    val dir = if (forward) 1 else -1
    val move = tween<androidx.compose.ui.unit.IntOffset>(300, easing = Motion.Emphasized)
    return (androidx.compose.animation.slideInHorizontally(move) { dir * it / 4 } +
        androidx.compose.animation.fadeIn(tween(200, delayMillis = 80))) togetherWith
        (androidx.compose.animation.slideOutHorizontally(move) { -dir * it / 4 } + androidx.compose.animation.fadeOut(tween(110)))
}

val LocalReducedMotion = androidx.compose.runtime.compositionLocalOf { false }

private fun animationsOff(context: android.content.Context): Boolean =
    android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

@Composable
private fun rememberReducedMotion(): Boolean {
    val context = androidx.compose.ui.platform.LocalContext.current
    var off by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(animationsOff(context)) }
    androidx.compose.runtime.DisposableEffect(context) {
        val observer = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { off = animationsOff(context) }
        }
        context.contentResolver.registerContentObserver(
            android.provider.Settings.Global.getUriFor(android.provider.Settings.Global.ANIMATOR_DURATION_SCALE), false, observer,
        )
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    return off
}

/** Pushes the saved theme choice into [ThemeState] (Material You uses the wallpaper's primary). */
@Composable
fun ApplyThemeSettings(accent: String, dynamic: Boolean, pureBlack: Boolean) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val dyn = if (dynamic && android.os.Build.VERSION.SDK_INT >= 31) androidx.compose.material3.dynamicDarkColorScheme(context) else null
    val theme = AccentTheme.entries.firstOrNull { it.name == accent } ?: AccentTheme.Glicine
    androidx.compose.runtime.SideEffect {
        if (dyn != null) {
            ThemeState.accent = dyn.primary
            ThemeState.accentLight = lerp(dyn.primary, Color.White, 0.5f)
            ThemeState.accentTint = dyn.primaryContainer.copy(alpha = 1f).let { lerp(it, Color.Black, 0.55f) }
        } else {
            ThemeState.accent = theme.color
            ThemeState.accentLight = theme.light
            ThemeState.accentTint = theme.tint
        }
        ThemeState.pureBlack = pureBlack
    }
}

@Composable
fun FinalishTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = scheme(),
        typography = AppTypography,
        shapes = AppShapes,
    ) {
        // Text outside a Surface would otherwise default to black.
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides Palette.Text,
            LocalReducedMotion provides rememberReducedMotion(),
            content = content,
        )
    }
}
