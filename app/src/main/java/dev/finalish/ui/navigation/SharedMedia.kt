package dev.finalish.ui.navigation

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import dev.finalish.ui.theme.LocalReducedMotion

/** The app-wide shared transition (around the NavHost); null in previews, sheets' own windows and tests. */
@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }

/** The navigation transition of the screen being composed. */
val LocalNavAnimatedScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * A file's picture as one element across screens: the card thumbnail grows into the viewer's stage
 * on open and shrinks back into its card on back. No-op where there is no navigation transition.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedMedia(assetId: String, enabled: Boolean = true): Modifier {
    val shared = LocalSharedTransitionScope.current ?: return this
    val nav = LocalNavAnimatedScope.current ?: return this
    if (!enabled || LocalReducedMotion.current) return this
    return with(shared) {
        this@sharedMedia.sharedBounds(
            rememberSharedContentState("media-$assetId"),
            nav,
            boundsTransform = { _, _ -> androidx.compose.animation.core.spring(dampingRatio = 0.9f, stiffness = 380f) },
        )
    }
}
