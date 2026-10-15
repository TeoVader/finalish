package dev.finalish.ui.components

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.Flow

/** A snackbar host state that shows each of a ViewModel's [messages] while the screen is up. */
@Composable
fun rememberMessageSnackbar(messages: Flow<String>): SnackbarHostState {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(messages) { messages.collect { snackbar.showSnackbar(it) } }
    return snackbar
}
