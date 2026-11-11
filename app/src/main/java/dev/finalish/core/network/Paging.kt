package dev.finalish.core.network

import dev.finalish.core.model.PageEnvelope
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Fetches every page of a cursor-paginated listing (up to [limit] items), calling [partial] with
 * the accumulated list before each further page. A cursor that repeats ends the listing, so a
 * misbehaving server can't keep it going forever. An empty page with a new cursor doesn't: more
 * items can follow it, and stopping there would leave files out of a folder download. Still, one
 * pass reads at most [maxPages] pages: a server handing out ever-new cursors (with empty pages)
 * can't keep it calling forever.
 */
suspend fun <T> allPages(
    first: suspend () -> PageEnvelope<T>,
    next: suspend (after: String) -> PageEnvelope<T>,
    partial: suspend (List<T>) -> Unit = {},
    limit: Int = 2_000,
    maxPages: Int = MAX_PAGES,
): List<T> {
    var page = first()
    val items = page.data.toMutableList()
    val cursors = mutableSetOf<String>()
    var pages = 1
    while (items.size < limit) {
        val after = page.links?.next?.let(::afterCursor) ?: break
        if (!cursors.add(after)) break
        if (pages >= maxPages) break
        pages++
        partial(items.toList())
        page = next(after)
        items += page.data
    }
    return items
}

/** Pages one [allPages] pass reads at most (60-100 items each, so well above its item limits). */
const val MAX_PAGES = 100

/** The `after` cursor of a `links.next` value, which Frame.io gives as a path or a full URL. */
fun afterCursor(next: String): String? =
    (if (next.startsWith("http")) next else "https://x$next").toHttpUrlOrNull()?.queryParameter("after")
