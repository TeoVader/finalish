package dev.finalish.core.model

import android.content.Context
import androidx.compose.runtime.Immutable
import dev.finalish.R
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// Models mirror the Frame.io V4 REST API (https://next.developer.frame.io/platform/v4).
// Polymorphic payloads are flattened into a single tolerant class keyed by `type`
// so new server-side variants degrade gracefully instead of failing to decode.

@Serializable
data class DataEnvelope<T>(val data: T)

@Serializable
data class PageEnvelope<T>(
    val data: List<T> = emptyList(),
    val links: Links? = null,
    @SerialName("total_count") val totalCount: Int? = null,
)

@Serializable
data class Links(val next: String? = null)

@Immutable
@Serializable
data class User(
    val id: String? = null,
    val name: String? = null,
    val email: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    val active: Boolean? = null,
) {
    val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: email ?: "Unknown"

    /** [displayName] with the fallback in the app's language. */
    fun displayName(context: Context): String = name?.takeIf { it.isNotBlank() } ?: email ?: context.getString(R.string.core_user_unknown)
}

@Immutable
@Serializable
data class Account(
    val id: String,
    @SerialName("display_name") val displayName: String = "",
    val image: String? = null,
    val roles: List<String> = emptyList(),
    @SerialName("storage_limit") val storageLimit: Long? = null,
    @SerialName("storage_usage") val storageUsage: Long = 0,
)

@Immutable
@Serializable
data class Workspace(
    val id: String,
    @SerialName("account_id") val accountId: String = "",
    val name: String = "",
    val image: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Immutable
@Serializable
data class Project(
    val id: String,
    val name: String = "",
    val description: String? = null,
    @SerialName("root_folder_id") val rootFolderId: String = "",
    @SerialName("workspace_id") val workspaceId: String = "",
    val status: String? = null,
    val storage: Long = 0,
    val image: String? = null,
    val restricted: Boolean? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("view_url") val viewUrl: String? = null,
    val owner: User? = null,
)

@Immutable
@Serializable
data class MediaLink(
    val url: String? = null,
    @SerialName("download_url") val downloadUrl: String? = null,
    @SerialName("inline_url") val inlineUrl: String? = null,
    val metadata: ScrubSheetMeta? = null,
) {
    /** Best URL for in-app streaming/display. */
    val streamUrl: String? get() = url ?: inlineUrl ?: downloadUrl
}

@Immutable
@Serializable
data class ScrubSheetMeta(
    @SerialName("tile_x") val tileX: Int = 0,
    @SerialName("tile_y") val tileY: Int = 0,
    @SerialName("thumb_width") val thumbWidth: Int = 0,
    @SerialName("thumb_height") val thumbHeight: Int = 0,
    val padding: Int = 0,
)

@Immutable
@Serializable
data class MediaLinks(
    val original: MediaLink? = null,
    val thumbnail: MediaLink? = null,
    @SerialName("thumbnail_high_quality") val thumbnailHq: MediaLink? = null,
    @SerialName("video_h264_180") val video180: MediaLink? = null,
    @SerialName("high_quality") val highQuality: MediaLink? = null,
    val efficient: MediaLink? = null,
    @SerialName("scrub_sheet") val scrubSheet: MediaLink? = null,
)

@Immutable
@Serializable
data class SelectOption(
    val id: String,
    @SerialName("display_name") val displayName: String = "",
)

@Immutable
@Serializable
data class MetadataField(
    @SerialName("field_type") val fieldType: String = "",
    @SerialName("field_definition_id") val definitionId: String = "",
    @SerialName("field_definition_name") val name: String = "",
    val mutable: Boolean = false,
    val value: JsonElement? = null,
    @SerialName("field_options") val options: List<SelectOption> = emptyList(),
) {
    val selectedOptions: List<SelectOption>
        get() = (value as? JsonArray)?.mapNotNull { el ->
            runCatching {
                val o = el.jsonObject
                SelectOption(
                    o["id"]!!.jsonPrimitive.content,
                    o["display_name"]?.jsonPrimitive?.content ?: "",
                )
            }.getOrNull()
        } ?: emptyList()

    val intValue: Int? get() = (value as? JsonPrimitive)?.intOrNull
    val doubleValue: Double? get() = (value as? JsonPrimitive)?.doubleOrNull
    val boolValue: Boolean? get() = (value as? JsonPrimitive)?.booleanOrNull
    val textValue: String?
        get() = when (val v = value) {
            null, JsonNull -> null
            is JsonPrimitive -> v.content
            is JsonArray -> selectedOptions.joinToString { it.displayName }.ifBlank { null }
            else -> v.toString()
        }

    val isStatus: Boolean get() = fieldType == "select" && name.equals("Status", ignoreCase = true)
    val isRating: Boolean get() = fieldType == "rating"
}

/** A file, folder or version stack. */
@Immutable
@Serializable
data class Asset(
    val id: String,
    val type: String = "file",
    val name: String = "",
    @SerialName("parent_id") val parentId: String? = null,
    @SerialName("project_id") val projectId: String = "",
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("view_url") val viewUrl: String? = null,
    @SerialName("file_size") val fileSize: Long = 0,
    @SerialName("media_type") val mediaType: String? = null,
    val status: String? = null,
    @SerialName("cover_file_id") val coverFileId: String? = null,
    val restricted: Boolean? = null,
    @SerialName("head_version") val headVersion: Asset? = null,
    @SerialName("deleted_at") val deletedAt: String? = null,
    val creator: User? = null,
    @SerialName("media_links") val mediaLinks: MediaLinks? = null,
    val metadata: List<MetadataField> = emptyList(),
) {
    val isFolder: Boolean get() = type == "folder"
    val isVersionStack: Boolean get() = type == "version_stack"
    val isFile: Boolean get() = type == "file"

    /** For version stacks the head version carries the media. */
    val media: Asset get() = headVersion ?: this
    val kind: MediaKind
        get() = if (isFolder) MediaKind.Folder
        else {
            val fileType = media.metadata.firstOrNull { it.name.equals("File Type", ignoreCase = true) }?.textValue
            MediaKind.ofFileType(fileType) ?: MediaKind.of(media.mediaType, media.name).let { byName ->
                // A design file only counts as an image when Frame.io made a picture of it.
                // Links come back as objects with null URLs when there is no picture. SVGs are
                // drawn from the original, so they stay images.
                val pictured = media.mediaLinks?.let { it.thumbnailHq?.streamUrl ?: it.thumbnail?.streamUrl } != null ||
                    media.name.endsWith(".svg", ignoreCase = true)
                if (byName == MediaKind.Image && fileType != null && !pictured) MediaKind.Other else byName
            }
        }
    val thumbnailUrl: String?
        get() = media.mediaLinks?.thumbnailHq?.streamUrl ?: media.mediaLinks?.thumbnail?.streamUrl
    /**
     * Thumbnail URLs in order of preference. Frame.io's image resizer sometimes
     * returns 500 for large stills, so callers fall back through these.
     */
    val thumbnailCandidates: List<String>
        get() {
            val l = media.mediaLinks ?: return emptyList()
            return listOfNotNull(
                l.thumbnailHq?.streamUrl, l.thumbnail?.streamUrl,
                l.efficient?.streamUrl.takeIf { kind == MediaKind.Image },
                l.highQuality?.streamUrl.takeIf { kind == MediaKind.Image },
            ).distinct()
        }

    val isReady: Boolean get() = media.status == "transcoded" || kind == MediaKind.Other || kind == MediaKind.Pdf

    /**
     * Still being uploaded or transcoded. Some files stay "uploaded" for good (Frame.io never
     * transcodes them but already serves a playable link), and anything untouched for two hours
     * is stuck rather than processing: neither counts, so folders don't poll fast forever.
     */
    val isProcessing: Boolean
        get() {
            if (isFolder) return false
            val m = media
            if (m.status == null || m.status == "transcoded" || m.mediaLinks?.efficient != null) return false
            val touched = (m.updatedAt ?: m.createdAt)?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() } ?: return true
            return touched.isAfter(java.time.Instant.now().minus(java.time.Duration.ofHours(2)))
        }

    val statusField: MetadataField? get() = media.metadata.firstOrNull { it.isStatus }
    val ratingField: MetadataField? get() = media.metadata.firstOrNull { it.isRating }
    /** A built-in metadata field by its display name (e.g. "Page Count"). */
    fun meta(name: String): MetadataField? = media.metadata.firstOrNull { it.name.equals(name, ignoreCase = true) }

    /** Frame.io's built-in "Comment Count" field (sent as a decimal, e.g. 7.0). */
    val commentCount: Int? get() = media.metadata.firstOrNull { it.name.equals("Comment Count", ignoreCase = true) }?.doubleValue?.toInt()
}

enum class MediaKind {
    Folder, Video, Image, Audio, Pdf, Other;

    companion object {
        /**
         * Frame.io's own "File Type" field (Video, Image, Audio, PDF, Text, Compressed…). It
         * knows that an AI, EPS or RAW file got an image preview, where the extension doesn't.
         */
        /** Stills Frame.io renders a JPEG preview for (camera RAW and design files included). */
        private val IMAGE_EXT = setOf(
            "jpg", "jpeg", "png", "gif", "webp", "heic", "tif", "tiff", "bmp", "tga", "psd", "exr", "dng",
            "ai", "eps", "indd", "dwg",
            "3fr", "arw", "cr2", "cr3", "crw", "mrw", "nef", "orf", "pef", "raf", "rw2", "sr2", "srf", "srw",
        )

        fun ofFileType(fileType: String?): MediaKind? = when (fileType?.trim()?.lowercase()) {
            "video" -> Video
            "image" -> Image
            "audio" -> Audio
            "pdf" -> Pdf
            else -> null
        }

        fun of(mediaType: String?, name: String): MediaKind {
            val mt = mediaType.orEmpty().lowercase()
            val ext = name.substringAfterLast('.', "").lowercase()
            return when {
                mt.startsWith("video/") || ext in setOf("mp4", "mov", "m4v", "mkv", "mxf", "avi", "webm", "wmv", "flv", "3gp", "3g2", "r3d", "braw") -> Video
                mt.startsWith("image/") || ext in IMAGE_EXT -> Image
                mt.startsWith("audio/") || ext in setOf("mp3", "wav", "aac", "m4a", "flac", "aif", "aiff", "ogg", "wma") -> Audio
                mt == "application/pdf" || ext == "pdf" -> Pdf
                else -> Other
            }
        }
    }
}

@Immutable
@Serializable
data class Anchor(val x: Float, val y: Float)

@Immutable
@Serializable
data class CommentAttachment(
    val id: String,
    val name: String = "",
    @SerialName("media_type") val mediaType: String? = null,
    @SerialName("file_size") val fileSize: Long = 0,
    @SerialName("upload_urls") val uploadUrls: List<String>? = null,
)

@Immutable
@Serializable
data class CommentLink(
    @SerialName("start_index") val start: Int,
    @SerialName("end_index") val end: Int,
    val url: String,
    val text: String? = null,
)

@Immutable
@Serializable
data class CommentMention(
    @SerialName("start_index") val start: Int,
    @SerialName("end_index") val end: Int,
    val text: String = "",
    @SerialName("user_id") val userId: String? = null,
)

@Immutable
@Serializable
data class Comment(
    val id: String,
    val text: String = "",
    @SerialName("file_id") val fileId: String = "",
    /** Frame number (integer) or a timecode string, depending on the request. */
    val timestamp: JsonElement? = null,
    val duration: Int? = null,
    val page: Int? = null,
    val anchor: Anchor? = null,
    val annotation: String? = null,
    @SerialName("completed_at") val completedAt: String? = null,
    @SerialName("completer_id") val completerId: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("text_edited_at") val textEditedAt: String? = null,
    val attachments: List<CommentAttachment>? = null,
    val links: List<CommentLink> = emptyList(),
    val mentions: List<CommentMention> = emptyList(),
    val owner: User? = null,
    val replies: List<Comment> = emptyList(),
    /** Local-only: comment created offline and waiting to sync. */
    val pending: Boolean = false,
    /** Local-only: Frame.io refused it; kept so the user can retry or delete it. */
    val failed: Boolean = false,
) {
    val isCompleted: Boolean get() = completedAt != null
    val frame: Int?
        get() = (timestamp as? JsonPrimitive)?.let { p -> if (p.isString) null else p.intOrNull }
    val timecode: String?
        get() = (timestamp as? JsonPrimitive)?.takeIf { it.isString }?.content
}

@Immutable
@Serializable
data class Share(
    val id: String,
    val name: String? = null,
    val access: String = "public",
    val enabled: Boolean = true,
    @SerialName("short_url") val shortUrl: String? = null,
    @SerialName("downloading_enabled") val downloadingEnabled: Boolean = false,
    @SerialName("commenting_enabled") val commentingEnabled: Boolean = false,
    val expiration: String? = null,
    val passphrase: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("last_viewed_at") val lastViewedAt: String? = null,
    val layout: String? = null,
    @SerialName("show_all_asset_versions") val showAllVersions: Boolean? = null,
    @SerialName("transcripts_enabled") val transcriptsEnabled: Boolean? = null,
    @SerialName("captions_enabled") val captionsEnabled: Boolean? = null,
    @SerialName("watermark_enabled") val watermarkEnabled: Boolean? = null,
    val description: String? = null,
    val theme: String? = null,
    @SerialName("accent_color") val accentColor: String? = null,
)

@Immutable
@Serializable
data class Collection(
    val id: String,
    val name: String? = null,
    val description: String? = null,
    @SerialName("project_id") val projectId: String = "",
    @SerialName("root_folder_id") val rootFolderId: String? = null,
    val private: Boolean = false,
    @SerialName("aggregation_mode") val aggregationMode: String = "static",
    @SerialName("updated_at") val updatedAt: String? = null,
)

/** Mosaic thumbnails and item count shown on folder cards. */
@Immutable
@Serializable
data class FolderPreview(
    val thumbnails: List<String> = emptyList(),
    val count: Int? = null,
)

@Serializable
data class FileMetadata(
    @SerialName("file_id") val fileId: String = "",
    val metadata: List<MetadataField> = emptyList(),
)

/** A member and their role on an account, workspace or project. */
@Immutable
@Serializable
data class UserRole(
    val role: String? = null,
    val user: User = User(),
)

@Immutable
@Serializable
data class ShareActivity(
    val id: String,
    @SerialName("inserted_at") val insertedAt: String? = null,
    val type: String = "",
    @SerialName("asset_id") val assetId: String? = null,
    @SerialName("user_id") val userId: String? = null,
    val user: User? = null,
)

@Serializable
data class UploadUrl(val size: Long, val url: String)

@Serializable
data class FileWithUploadUrls(
    val id: String,
    val name: String = "",
    @SerialName("media_type") val mediaType: String? = null,
    @SerialName("file_size") val fileSize: Long = 0,
    @SerialName("upload_urls") val uploadUrls: List<UploadUrl> = emptyList(),
)

@Immutable
@Serializable
data class SearchHit(
    val type: String = "",
    val result: JsonElement? = null,
)

/** Comments left through a share link by someone without a Frame.io account come back with no owner. */
val GuestReviewer = User(name = "Guest reviewer")

/** Who wrote it: the owner, or a guest reviewer when Frame.io doesn't say. */
val Comment.author: User get() = owner ?: GuestReviewer

@Immutable
@Serializable
data class AuditLog(
    @SerialName("resource_id") val resourceId: String = "",
    @SerialName("event_type") val eventType: String = "",
    @SerialName("user_id") val userId: String? = null,
    val user: User? = null,
    val actor: User? = null,
)

/** `GET files/{id}/status`: where an upload or a remote import stands on Frame.io's side. */
@kotlinx.serialization.Serializable
data class FileUploadStatus(
    val id: String,
    @kotlinx.serialization.SerialName("upload_complete") val uploadComplete: Boolean = false,
    @kotlinx.serialization.SerialName("upload_failed") val uploadFailed: Boolean = false,
)

/** A session watermark template (experimental API): text blocks burned over shared media per viewer. */
@Serializable
data class WatermarkTemplate(
    val id: String,
    val name: String = "",
    @SerialName("watermark_blocks") val blocks: List<WatermarkBlock> = emptyList(),
) {
    /** "Email · Time · bottom right": what it shows and where. */
    val summary: String
        get() {
            val lines = blocks.flatMap { b -> b.dataPoints.sortedBy { it.order ?: 0.0 } }.map { it.label }.distinct()
            val where = blocks.mapNotNull { it.position?.replace('_', ' ') }.distinct().joinToString(", ")
            return listOf(lines.joinToString(" · "), where).filter { it.isNotBlank() }.joinToString(" · ")
        }

    /** [summary] with the data points named in the app's language (positions stay as the API names them). */
    fun summary(context: Context): String {
        val lines = blocks.flatMap { b -> b.dataPoints.sortedBy { it.order ?: 0.0 } }.map { it.label(context) }.distinct()
        val where = blocks.mapNotNull { it.position?.replace('_', ' ') }.distinct().joinToString(", ")
        return listOf(lines.joinToString(" · "), where).filter { it.isNotBlank() }.joinToString(" · ")
    }
}

@Serializable
data class WatermarkBlock(
    val position: String? = null,
    @SerialName("data_points") val dataPoints: List<WatermarkDataPoint> = emptyList(),
)

@Serializable
data class WatermarkDataPoint(
    val type: String = "",
    val value: String? = null,
    val order: Double? = null,
) {
    val label: String
        get() = when (type) {
            "email" -> "Email"
            "username" -> "Name"
            "ip_address" -> "IP address"
            "timestamp" -> "Time"
            "user_input" -> "Viewer's text"
            "custom_text" -> value?.let { "“$it”" } ?: "Text"
            else -> type
        }

    /** [label] in the app's language. */
    fun label(context: Context): String = when (type) {
        "email" -> context.getString(R.string.core_watermark_email)
        "username" -> context.getString(R.string.core_watermark_name)
        "ip_address" -> context.getString(R.string.core_watermark_ip_address)
        "timestamp" -> context.getString(R.string.core_watermark_time)
        "user_input" -> context.getString(R.string.core_watermark_viewer_text)
        "custom_text" -> value?.let { context.getString(R.string.core_watermark_custom_text, it) } ?: context.getString(R.string.core_watermark_text)
        else -> type
    }
}

/** An account-level custom metadata field (Settings › Metadata fields). */
@Immutable
@Serializable
data class FieldDefinition(
    val id: String,
    val name: String = "",
    @SerialName("field_type") val type: String = "text",
    /** False for Frame.io's built-in fields, which can't be renamed or deleted. */
    val mutable: Boolean = true,
    @SerialName("field_configuration") val configuration: JsonObject? = null,
    val creator: User? = null,
) {
    val options: List<String> get() =
        (configuration?.get("options") as? JsonArray).orEmpty()
            .mapNotNull { ((it as? JsonObject)?.get("display_name") as? JsonPrimitive)?.content }

    private fun config(key: String): String? =
        (configuration?.get(key) as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

    /** "Single select · 3 options", "Rating · 5 stars", … */
    val summary: String get() {
        val detail = when (type) {
            "select", "select_multi" -> options.size.takeIf { it > 0 }?.let { "$it option${if (it == 1) "" else "s"}" }
            "rating" -> config("max_value")?.let { n -> "$n ${when (config("style")) { "heart" -> "hearts"; "thumbs_up" -> "thumbs"; else -> "stars" }}" }
            "date" -> if (config("include_time") == "true") "with time" else null
            "number" -> config("number_format")?.replaceFirstChar { it.uppercase() }
            else -> null
        }
        return listOfNotNull(fieldTypeLabel(type), detail).joinToString(" · ")
    }

    /** [summary] in the app's language. */
    fun summary(context: Context): String {
        val res = context.resources
        val detail = when (type) {
            "select", "select_multi" -> options.size.takeIf { it > 0 }?.let { res.getQuantityString(R.plurals.core_field_options, it, it) }
            "rating" -> config("max_value")?.let { n ->
                val plural = when (config("style")) {
                    "heart" -> R.plurals.core_field_rating_hearts
                    "thumbs_up" -> R.plurals.core_field_rating_thumbs
                    else -> R.plurals.core_field_rating_stars
                }
                res.getQuantityString(plural, n.toDoubleOrNull()?.toInt() ?: 2, n)
            }
            "date" -> if (config("include_time") == "true") context.getString(R.string.core_field_with_time) else null
            "number" -> config("number_format")?.replaceFirstChar { it.uppercase() }
            else -> null
        }
        return listOfNotNull(fieldTypeLabel(context, type), detail).joinToString(" · ")
    }
}

/** Field types a new field can have, in the order Frame.io lists them. */
val FIELD_TYPES = listOf("text", "long_text", "select", "select_multi", "number", "date", "rating", "toggle", "user_single", "user_multi")

fun fieldTypeLabel(type: String): String = when (type) {
    "text" -> "Text"
    "long_text" -> "Long text"
    "select" -> "Single select"
    "select_multi" -> "Multi-select"
    "number" -> "Number"
    "date" -> "Date"
    "rating" -> "Rating"
    "toggle" -> "Toggle"
    "user_single" -> "Person"
    "user_multi" -> "People"
    else -> type.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

/** [fieldTypeLabel] in the app's language (unknown types stay as the API names them). */
fun fieldTypeLabel(context: Context, type: String): String = when (type) {
    "text" -> context.getString(R.string.core_field_type_text)
    "long_text" -> context.getString(R.string.core_field_type_long_text)
    "select" -> context.getString(R.string.core_field_type_select)
    "select_multi" -> context.getString(R.string.core_field_type_select_multi)
    "number" -> context.getString(R.string.core_field_type_number)
    "date" -> context.getString(R.string.core_field_type_date)
    "rating" -> context.getString(R.string.core_field_type_rating)
    "toggle" -> context.getString(R.string.core_field_type_toggle)
    "user_single" -> context.getString(R.string.core_field_type_user_single)
    "user_multi" -> context.getString(R.string.core_field_type_user_multi)
    else -> fieldTypeLabel(type)
}

/** An account's user group ("Editors", "Client") — given a role on projects and workspaces as one. */
@Immutable
@Serializable
data class UserGroup(
    val id: String,
    val name: String = "",
    val emoji: String = "",
    @SerialName("member_count") val memberCount: Int = 0,
    /** "frame", or "adobe" / "workfront" for groups synced from elsewhere (their people are managed there). */
    val source: String = "frame",
    @SerialName("sync_status") val syncStatus: String? = null,
) {
    val synced: Boolean get() = source != "frame"
    val sourceLabel: String? get() = when (source) { "adobe" -> "Adobe"; "workfront" -> "Workfront"; else -> null }
    val membersLabel: String get() = "$memberCount ${if (memberCount == 1) "person" else "people"}"

    /** [membersLabel] in the app's language. */
    fun membersLabel(context: Context): String = context.resources.getQuantityString(R.plurals.core_group_members, memberCount, memberCount)
}

@Immutable
@Serializable
data class GroupRole(
    val group: UserGroup,
    val role: String? = null,
)
