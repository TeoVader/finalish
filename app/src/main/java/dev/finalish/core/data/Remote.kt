package dev.finalish.core.data

import androidx.compose.runtime.Immutable

/** Stale-while-revalidate state for a piece of remote data. */
@Immutable
data class Remote<out T>(
    val data: T? = null,
    val loading: Boolean = true,
    val error: String? = null,
) {
    val isInitialLoading: Boolean get() = data == null && loading
    val isEmptyError: Boolean get() = data == null && !loading && error != null

    companion object {
        fun <T> loading(data: T? = null) = Remote(data = data, loading = true)
        fun <T> ready(data: T) = Remote(data = data, loading = false)
    }
}
