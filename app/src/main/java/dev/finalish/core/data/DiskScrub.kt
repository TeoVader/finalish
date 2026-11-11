package dev.finalish.core.data

import dev.finalish.core.model.linkImageKey
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What [JsonCache] writes to disk instead of the response itself: Frame.io's signed links must not
 * be stored ("Do not store or cache these URLs. Always request a fresh set of media_links each
 * time", developer.frame.io/platform/v4/docs/guides/media-links). They stay in memory only.
 *
 * - In every `media_links` object, each link loses its `url`, `download_url` and `inline_url`; the
 *   names of those that had a value go in `stripped`, so the app still knows a picture or a
 *   download exists (`MediaLink.hasStream`, `hasDownload`) and shows cached images by their stable
 *   key (`ImageRef`).
 * - Every `signed_url` field (an `ImageRef`'s link) is dropped.
 * - Bare signed image links (a project's, account's or workspace's `image`, a user's `avatar_url`)
 *   are dropped too, and replaced by their [linkImageKey] in `image_key` / `avatar_key`: a copy
 *   read back from disk shows the cached picture by that key, or the initials, until a refresh
 *   brings a fresh link.
 */
internal object DiskScrub {
    private val LINK_FIELDS = listOf("url", "download_url", "inline_url")

    /** Bare image links and the field that keeps each one's image cache key on disk. */
    private val IMAGE_FIELDS = mapOf("image" to "image_key", "avatar_url" to "avatar_key")

    fun scrub(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(
            element.flatMap { (name, value) ->
                when {
                    name == "signed_url" -> emptyList()
                    name == "media_links" && value is JsonObject -> listOf(name to scrubMediaLinks(value))
                    name in IMAGE_FIELDS && value is JsonPrimitive && value.isString -> imageKey(IMAGE_FIELDS.getValue(name), value.content)
                    else -> listOf(name to scrub(value))
                }
            }.toMap(),
        )
        is JsonArray -> JsonArray(element.map(::scrub))
        else -> element
    }

    private fun imageKey(keyField: String, url: String): List<Pair<String, JsonElement>> =
        if (url.isBlank()) emptyList() else listOf(keyField to JsonPrimitive(linkImageKey(url)))

    private fun scrubMediaLinks(links: JsonObject) = JsonObject(
        links.mapValues { (_, link) -> if (link is JsonObject) scrubLink(link) else link },
    )

    private fun scrubLink(link: JsonObject): JsonObject {
        val had = LINK_FIELDS.filter { link[it].let { v -> v != null && v != JsonNull } }
        if (had.isEmpty()) return link
        val earlier = (link["stripped"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
        return JsonObject(
            link.filterKeys { it !in LINK_FIELDS } + ("stripped" to JsonArray((earlier + had).distinct().map(::JsonPrimitive))),
        )
    }

    /**
     * A folder preview or project cover from an earlier version, whose `thumbnails` were bare signed URLs:
     * there's nothing to keep once the links go, so the entry is dropped instead.
     */
    fun isLegacyPreview(element: JsonElement): Boolean =
        ((element as? JsonObject)?.get("thumbnails") as? JsonArray)?.any { it is JsonPrimitive && it.isString } == true
}
