package dev.finalish.ui

import android.app.Application
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.CreationExtras
import dev.finalish.AppContainer
import dev.finalish.FinalishApp
import dev.finalish.core.network.userMessage
import dev.finalish.core.util.runCatchingCancellable
import kotlinx.coroutines.flow.MutableSharedFlow

/** The application, inside a ViewModel factory's `initializer { }`. */
val CreationExtras.application: Application get() = checkNotNull(this[APPLICATION_KEY])

/** The app's dependencies, inside a ViewModel factory's `initializer { }`. */
val CreationExtras.container: AppContainer get() = FinalishApp.container(application)

/** A ViewModel's one-off snackbar messages. */
fun messageFlow(capacity: Int = 4) = MutableSharedFlow<String>(extraBufferCapacity = capacity)

/**
 * Runs [block] and tells the user how it went: the text [success] gives for the result (nothing
 * when null), or the error's message. The result is returned for whatever else follows, such as
 * undoing an optimistic change.
 */
suspend inline fun <T> MutableSharedFlow<String>.report(
    success: (T) -> String? = { null },
    error: (Throwable) -> String = { it.userMessage() },
    block: () -> T,
): Result<T> = runCatchingCancellable { block() }
    .onSuccess { value -> success(value)?.let { emit(it) } }
    .onFailure { emit(error(it)) }
