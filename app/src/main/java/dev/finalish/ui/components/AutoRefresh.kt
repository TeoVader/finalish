package dev.finalish.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

/**
 * Keeps a screen live: refreshes whenever it becomes visible again and then
 * polls silently while it stays in the foreground. Stops in the background.
 */
@Composable
fun AutoRefresh(key: Any?, intervalMs: () -> Long, refresh: () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentRefresh by rememberUpdatedState(refresh)
    val currentInterval by rememberUpdatedState(intervalMs)
    LaunchedEffect(key, lifecycle) {
        var first = true
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            // The screen already loads on first composition; refresh on later resumes.
            if (!first) currentRefresh()
            first = false
            while (true) {
                delay(currentInterval())
                currentRefresh()
            }
        }
    }
}
