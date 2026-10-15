package dev.finalish.core.data

import android.content.Context
import androidx.annotation.StringRes
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.finalish.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private val Context.dataStore by preferencesDataStore("settings")

/**
 * Playback quality, named like the official apps: Auto, the source file, or a resolution.
 * A resolution plays the closest rendition at or below it (the smallest one if none is).
 */
enum class PlaybackQuality(val label: String, @StringRes val labelRes: Int, val height: Int? = null) {
    Auto("Auto", R.string.core_quality_auto),
    P2160("2160p (4K)", R.string.core_quality_2160, 2160),
    P1080("1080p (HD)", R.string.core_quality_1080, 1080),
    P720("720p (HD)", R.string.core_quality_720, 720),
    P540("540p", R.string.core_quality_540, 540),
    P360("360p", R.string.core_quality_360, 360),
    P180("180p", R.string.core_quality_180, 180),
    ;

    companion object {
        /** Settings saved before resolutions replaced High / Data saver / Lowest (and "Original", now Auto). */
        fun parse(name: String?): PlaybackQuality = when (name) {
            "High" -> P1080
            "Efficient" -> P360
            "Lowest" -> P180
            else -> entries.firstOrNull { it.name == name } ?: Auto
        }

        /** Label for a rendition's nominal height, e.g. 1080 → "1080p (HD)", 1440 → "1440p". */
        fun labelFor(height: Int): String = entries.firstOrNull { it.height == height }?.label ?: "${height}p"

        /** [labelFor] in the app's language. */
        fun labelFor(context: Context, height: Int): String =
            entries.firstOrNull { it.height == height }?.let { context.getString(it.labelRes) }
                ?: context.getString(R.string.core_quality_height, height)
    }
}
enum class BrowseLayout { Grid, List }
enum class CardSize(val label: String, val minWidthDp: Int) { S("S", 112), M("M", 158), L("L", 236) }
/** Card proportions in the folder grid; the names are stored, so new ones go at the end. */
enum class CardAspect(val label: String, val ratio: Float) {
    Landscape("16:9", 16f / 9f), Square("1:1", 1f), Portrait("4:5", 4f / 5f), Vertical("9:16", 9f / 16f),
}
enum class TimeFormat(val label: String, @StringRes val labelRes: Int) {
    Timecode("Timecode", R.string.core_time_format_timecode),
    Standard("Standard", R.string.core_time_format_standard),
    Frames("Frames", R.string.core_time_format_frames),
}
/** [local] orders are applied on the phone to the name-sorted list (the API can't sort by them). */
enum class SortOrder(val api: String, val label: String, @StringRes val labelRes: Int, val local: Boolean = false) {
    NameAsc("name_asc", "Name", R.string.core_sort_name),
    UpdatedDesc("updated_at_desc", "Last modified", R.string.core_sort_last_modified),
    CreatedDesc("created_at_desc", "Date uploaded", R.string.core_sort_date_uploaded),
    Type("name_asc", "Type", R.string.core_sort_type, local = true),
    SizeDesc("name_asc", "Size", R.string.core_sort_size, local = true),
    Status("name_asc", "Status", R.string.core_sort_status, local = true),
}

data class AppSettings(
    val accountId: String? = null,
    val quality: PlaybackQuality = PlaybackQuality.Auto,
    val layout: BrowseLayout = BrowseLayout.Grid,
    val sort: SortOrder = SortOrder.NameAsc,
    val cellularTransfers: Boolean = true,
    val loopPlayback: Boolean = false,
    val timeFormat: TimeFormat = TimeFormat.Timecode,
    val autoplay: Boolean = true,
    /** Pause playback when you start writing a comment (otherwise the time is frozen at that moment). */
    val pauseOnComment: Boolean = true,
    /** Keep a playing video in a floating window when leaving the app. */
    val pictureInPicture: Boolean = true,
    /** Download the small scrub-preview rendition on mobile data too (off: Wi-Fi only). */
    val scrubOnMobileData: Boolean = true,
    /** Background checks for activity (see ActivityWatch); per-category switches below. */
    val notifications: Boolean = true,
    /** Shake the phone to open the bug reporter. */
    val shakeToReport: Boolean = true,
    val notifyComments: Boolean = true,
    val notifyUploads: Boolean = true,
    val notifyShareViews: Boolean = true,
    val cardSize: CardSize = CardSize.M,
    val cardAspect: CardAspect = CardAspect.Landscape,
    val thumbFit: Boolean = false,
    val titleLines: Int = 1,
    val showCardInfo: Boolean = true,
    /** Projects as one column of large cards (mockup) instead of the two-column grid. */
    val projectsLarge: Boolean = false,
    val groupSections: Boolean = true,
    /** Sort folders by this metadata field instead of [sort] (null: use [sort]). */
    val sortField: String? = null,
    /** Reverse the order's natural direction (A→Z, newest first, biggest first…). */
    val sortReversed: Boolean = false,
    /** Metadata fields shown under each file card, in this order. */
    val cardFields: List<String> = emptyList(),
    /** Show the files of every subfolder in one list, without the folders. */
    val flattenFolders: Boolean = false,
    /** Accent palette name (see AccentTheme); Glicine is the brand default. */
    val accent: String = "Glicine",
    /** The launcher icon takes the accent colour (one activity-alias per accent). */
    val matchIcon: Boolean = false,
    /** Follow the wallpaper colours (Material You) instead of [accent]. */
    val dynamicAccent: Boolean = false,
    /** Pure #000 background for OLED screens. */
    val pureBlack: Boolean = false,
    /** Where "Share to Final(ish)" uploaded last time; offered first next time. */
    val lastUpload: UploadDestination? = null,
    /** First-run intro slides were shown. */
    val introSeen: Boolean = false,
    /** The post-sign-in permissions step was shown (answered or skipped). */
    val permissionsAsked: Boolean = false,
)

/** A folder to upload into, with the names needed to show "Project › Folder" without a lookup. */
@kotlinx.serialization.Serializable
data class UploadDestination(
    val accountId: String,
    val projectId: String,
    val projectName: String,
    val rootFolderId: String,
    val folderId: String,
    val folderName: String,
) {
    val label: String get() = if (folderId == rootFolderId) projectName else "$projectName › $folderName"
}

private val settingsJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

class SettingsStore(private val context: Context, scope: CoroutineScope) {

    val settings: StateFlow<AppSettings> = context.dataStore.data
        .map { it.toSettings() }
        .stateIn(scope, SharingStarted.Eagerly, AppSettings())

    private val launchScope = scope

    /**
     * The saved settings, waiting for them to load. [settings] starts from the defaults, which
     * a background worker in a freshly started process would otherwise read.
     */
    suspend fun current(): AppSettings = context.dataStore.data.first().toSettings()

    fun update(block: (AppSettings) -> AppSettings) {
        launchScope.launch {
            context.dataStore.edit { prefs ->
                val next = block(prefs.toSettings())
                next.accountId?.let { prefs[K_ACCOUNT] = it } ?: prefs.remove(K_ACCOUNT)
                prefs[K_QUALITY] = next.quality.name
                prefs[K_LAYOUT] = next.layout.name
                prefs[K_SORT] = next.sort.name
                prefs[K_CELL] = next.cellularTransfers
                prefs[K_LOOP] = next.loopPlayback
                prefs[K_TIME] = next.timeFormat.name
                prefs[K_AUTOPLAY] = next.autoplay
                prefs[K_PAUSE_ON_COMMENT] = next.pauseOnComment
                prefs[K_PIP] = next.pictureInPicture
                prefs[K_SCRUB_CELLULAR] = next.scrubOnMobileData
                prefs[K_NOTIFY] = next.notifications
                prefs[K_SHAKE] = next.shakeToReport
                prefs[K_NOTIFY_COMMENTS] = next.notifyComments
                prefs[K_NOTIFY_UPLOADS] = next.notifyUploads
                prefs[K_NOTIFY_SHARES] = next.notifyShareViews
                prefs[K_CARD_SIZE] = next.cardSize.name
                prefs[K_CARD_ASPECT] = next.cardAspect.name
                prefs[K_THUMB_FIT] = next.thumbFit
                prefs[K_TITLE_LINES] = next.titleLines.toString()
                prefs[K_CARD_INFO] = next.showCardInfo
                prefs[K_PROJECTS_LARGE] = next.projectsLarge
                prefs[K_GROUP] = next.groupSections
                next.sortField?.let { prefs[K_SORT_FIELD] = it } ?: prefs.remove(K_SORT_FIELD)
                prefs[K_SORT_REVERSED] = next.sortReversed
                prefs[K_CARD_FIELDS] = next.cardFields.joinToString("\n")
                prefs[K_FLATTEN] = next.flattenFolders
                prefs[K_ACCENT] = next.accent
                prefs[K_MATCH_ICON] = next.matchIcon
                prefs[K_DYNAMIC] = next.dynamicAccent
                prefs[K_BLACK] = next.pureBlack
                prefs[K_INTRO] = next.introSeen
                prefs[K_PERMS] = next.permissionsAsked
                next.lastUpload?.let { prefs[K_LAST_UPLOAD] = settingsJson.encodeToString(UploadDestination.serializer(), it) }
                    ?: prefs.remove(K_LAST_UPLOAD)
            }
        }
    }

    private fun Preferences.toSettings() = AppSettings(
        accountId = this[K_ACCOUNT],
        quality = PlaybackQuality.parse(this[K_QUALITY]),
        layout = enumOr(this[K_LAYOUT], BrowseLayout.Grid),
        sort = enumOr(this[K_SORT], SortOrder.NameAsc),
        cellularTransfers = this[K_CELL] ?: true,
        loopPlayback = this[K_LOOP] ?: false,
        timeFormat = enumOr(this[K_TIME], TimeFormat.Timecode),
        autoplay = this[K_AUTOPLAY] ?: true,
        pauseOnComment = this[K_PAUSE_ON_COMMENT] ?: true,
        pictureInPicture = this[K_PIP] ?: true,
        scrubOnMobileData = this[K_SCRUB_CELLULAR] ?: true,
        notifications = this[K_NOTIFY] ?: true,
        shakeToReport = this[K_SHAKE] ?: true,
        notifyComments = this[K_NOTIFY_COMMENTS] ?: true,
        notifyUploads = this[K_NOTIFY_UPLOADS] ?: true,
        notifyShareViews = this[K_NOTIFY_SHARES] ?: true,
        cardSize = enumOr(this[K_CARD_SIZE], CardSize.M),
        cardAspect = enumOr(this[K_CARD_ASPECT], CardAspect.Landscape),
        thumbFit = this[K_THUMB_FIT] ?: false,
        titleLines = this[K_TITLE_LINES]?.toIntOrNull() ?: 1,
        showCardInfo = this[K_CARD_INFO] ?: true,
        projectsLarge = this[K_PROJECTS_LARGE] ?: false,
        groupSections = this[K_GROUP] ?: true,
        sortField = this[K_SORT_FIELD],
        sortReversed = this[K_SORT_REVERSED] ?: false,
        cardFields = this[K_CARD_FIELDS]?.split('\n')?.filter { it.isNotBlank() }.orEmpty(),
        flattenFolders = this[K_FLATTEN] ?: false,
        accent = this[K_ACCENT] ?: "Glicine",
        matchIcon = this[K_MATCH_ICON] ?: false,
        dynamicAccent = this[K_DYNAMIC] ?: false,
        pureBlack = this[K_BLACK] ?: false,
        introSeen = this[K_INTRO] ?: false,
        permissionsAsked = this[K_PERMS] ?: false,
        lastUpload = this[K_LAST_UPLOAD]?.let { runCatching { settingsJson.decodeFromString(UploadDestination.serializer(), it) }.getOrNull() },
    )

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default

    private companion object {
        val K_ACCOUNT = stringPreferencesKey("account")
        val K_QUALITY = stringPreferencesKey("quality")
        val K_LAYOUT = stringPreferencesKey("layout")
        val K_SORT = stringPreferencesKey("sort")
        val K_CELL = booleanPreferencesKey("cellular")
        val K_LOOP = booleanPreferencesKey("loop")
        val K_TIME = stringPreferencesKey("time_format")
        val K_AUTOPLAY = booleanPreferencesKey("autoplay")
        val K_PAUSE_ON_COMMENT = booleanPreferencesKey("pause_on_comment")
        val K_PIP = booleanPreferencesKey("picture_in_picture")
        val K_SCRUB_CELLULAR = booleanPreferencesKey("scrub_on_mobile_data")
        val K_NOTIFY = booleanPreferencesKey("notifications")
        val K_SHAKE = booleanPreferencesKey("shake_to_report")
        val K_NOTIFY_COMMENTS = booleanPreferencesKey("notify_comments")
        val K_NOTIFY_UPLOADS = booleanPreferencesKey("notify_uploads")
        val K_NOTIFY_SHARES = booleanPreferencesKey("notify_share_views")
        val K_CARD_SIZE = stringPreferencesKey("card_size")
        val K_CARD_ASPECT = stringPreferencesKey("card_aspect")
        val K_THUMB_FIT = booleanPreferencesKey("thumb_fit")
        val K_ACCENT = stringPreferencesKey("accent")
        val K_MATCH_ICON = booleanPreferencesKey("match_icon")
        val K_LAST_UPLOAD = stringPreferencesKey("last_upload")
        val K_DYNAMIC = booleanPreferencesKey("dynamic_accent")
        val K_BLACK = booleanPreferencesKey("pure_black")
        val K_INTRO = booleanPreferencesKey("intro_seen")
        val K_PERMS = booleanPreferencesKey("permissions_asked")
        val K_TITLE_LINES = stringPreferencesKey("title_lines")
        val K_CARD_INFO = booleanPreferencesKey("card_info")
        val K_PROJECTS_LARGE = booleanPreferencesKey("projects_large")
        val K_GROUP = booleanPreferencesKey("group_sections")
        val K_SORT_FIELD = stringPreferencesKey("sort_field")
        val K_SORT_REVERSED = booleanPreferencesKey("sort_reversed")
        val K_CARD_FIELDS = stringPreferencesKey("card_fields")
        val K_FLATTEN = booleanPreferencesKey("flatten_folders")
    }
}
