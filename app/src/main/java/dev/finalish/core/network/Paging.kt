package dev.finalish.core.network

import dev.finalish.core.model.PageEnvelope
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Fetches every page of a cursor-paginated listing (up to [limit] items), calling [partial] with
 * the accumulated list before each further page. A cursor that repeats or an empty page that still
 * has a next link ends the listing, so a misbehaving server can't keep it going forever.
 */
suspend fun <T> allPages(
    first: suspend () -> PageEnvelope<T>,
    next: suspend (after: String) -> PageEnvelope<T>,
    partial: suspend (List<T>) -> Unit = {},
    limit: Int = 2_000,
): List<T> {
    var page = first()
    val items = page.data.toMutableList()
    val cursors = mutableSetOf<String>()
    while (items.size < limit && page.data.isNotEmpty()) {
        val after = page.links?.next?.let(::afterCursor) ?: break
        if (!cursors.add(after)) break
        partial(items.toList())
        page = next(after)
        items += page.data
    }
    return items
}

/** The `after` cursor of a `links.next` value, which Frame.io gives as a path or a full URL. */
fun afterCursor(next: String): String? =
    (if (next.startsWith("http")) next else "https://x$next").toHttpUrlOrNull()?.queryParameter("after")
