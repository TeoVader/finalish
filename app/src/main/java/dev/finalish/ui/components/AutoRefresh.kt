package dev.finalish.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/**
 * Keeps a screen live: refreshes whenever it becomes visible again and then polls silently while
 * it stays in the foreground. Stops while the screen is paused or the app isn't visible.
 *
 * [content] is what the refreshes show (a list, a hash): while it stays the same the wait grows
 * ([AutoRefreshBackoff]) up to [maxIntervalMs], and it drops back to [intervalMs] as soon as
 * something changes or the screen comes back. Failed refreshes change nothing, so they slow down too.
 */
@Composable
fun AutoRefresh(
    key: Any?,
    intervalMs: () -> Long,
    content: () -> Any? = { null },
    maxIntervalMs: Long = 120_000,
    refresh: () -> Unit,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentRefresh by rememberUpdatedState(refresh)
    val currentInterval by rememberUpdatedState(intervalMs)
    val currentContent by rememberUpdatedState(content)
    LaunchedEffect(key, lifecycle) {
        var first = true
        val app = ProcessLifecycleOwner.get().lifecycle
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            // The screen already loads on first composition; refresh on later resumes.
            if (!first) currentRefresh()
            first = false
            val backoff = AutoRefreshBackoff(maxIntervalMs)
            var last = currentContent()
            while (true) {
                delay(backoff.next(currentInterval()))
                // A screen can stay resumed with the app out of sight (split screen with another app
                // in focus on some phones): no polling until it's back.
                app.currentStateFlow.first { it.isAtLeast(Lifecycle.State.STARTED) }
                val now = currentContent()
                backoff.record(changed = now != last)
                last = now
                currentRefresh()
            }
        }
    }
}

/**
 * The wait between two automatic refreshes: the base interval, doubled for every refresh in a row
 * that brought nothing new, up to [maxMs] (but never below the base interval).
 */
class AutoRefreshBackoff(private val maxMs: Long) {
    private var quiet = 0

    fun record(changed: Boolean) {
        quiet = if (changed) 0 else (quiet + 1).coerceAtMost(16)
    }

    fun next(baseMs: Long): Long = (baseMs shl quiet.coerceAtMost(6)).coerceAtMost(maxOf(baseMs, maxMs))
}
