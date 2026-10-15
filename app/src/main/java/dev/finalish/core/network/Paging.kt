package dev.finalish.core.network

import dev.finalish.core.model.PageEnvelope
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Fetches every page of a cursor-paginated listing (up to [limit] items), calling [partial] with
 * the accumulated list before each further page.
 */
suspend fun <T> allPages(
    first: suspend () -> PageEnvelope<T>,
    next: suspend (after: String) -> PageEnvelope<T>,
    partial: suspend (List<T>) -> Unit = {},
    limit: Int = 2_000,
): List<T> {
    var page = first()
    val items = page.data.toMutableList()
    while (items.size < limit) {
        val after = page.links?.next?.let(::afterCursor) ?: break
        partial(items.toList())
        page = next(after)
        items += page.data
    }
    return items
}

/** The `after` cursor of a `links.next` value, which Frame.io gives as a path or a full URL. */
fun afterCursor(next: String): String? =
    (if (next.startsWith("http")) next else "https://x$next").toHttpUrlOrNull()?.queryParameter("after")
