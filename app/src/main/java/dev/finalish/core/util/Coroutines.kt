package dev.finalish.core.util

import kotlinx.coroutines.CancellationException

/**
 * [runCatching] for suspending code: a cancelled coroutine stays cancelled instead of
 * carrying on with a null or empty result.
 */
inline fun <R> runCatchingCancellable(block: () -> R): Result<R> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
