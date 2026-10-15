package dev.finalish.core.notify

import dev.finalish.core.util.runCatchingCancellable
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.finalish.FinalishApp
import dev.finalish.R
import dev.finalish.core.model.Comment
import dev.finalish.core.model.author
import dev.finalish.core.network.AppJson
import dev.finalish.core.network.allPages
import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * Notifications without a server: a periodic background check (Android's minimum is
 * ~15 minutes) for activity on what the user cares about:
 *  - new comments on files they opened or uploaded recently,
 *  - their uploads finishing processing,
 *  - their share links being opened.
 * Frame.io's webhooks would be instant but need a public server; this keeps the app
 * self-contained.
 */
@Serializable
data class WatchedFile(
    val fileId: String,
    val accountId: String,
    val projectId: String,
    val name: String,
    /** Newest comment already seen (ISO time); newer ones from others are notified. */
    val seenUntil: String? = null,
    /** Set for the user's own uploads until Frame.io finishes processing them. */
    val awaitingReady: Boolean = false,
    val touchedAt: Long = System.currentTimeMillis(),
    /** Comments from others since the user last opened the file (project card badge). */
    val unread: Int = 0,
)

@Serializable
data class WatchedShare(
    val shareId: String,
    val accountId: String,
    val projectId: String,
    val name: String,
    val seenUntil: String? = null,
    val touchedAt: Long = System.currentTimeMillis(),
)

/** One line in the Notifications screen (several comments on a file in one check are one event). */
@Serializable
data class ActivityEvent(
    val id: String,
    val kind: Kind,
    val accountId: String,
    val projectId: String,
    /** File or share link the event is about. */
    val title: String,
    val fileId: String? = null,
    val shareId: String? = null,
    /** Who did it, newest last; empty for the app's own events (an upload finished). */
    val actors: List<String> = emptyList(),
    val count: Int = 1,
    /** The latest comment's text, for mentions and replies. */
    val text: String? = null,
    val at: String,
    val read: Boolean = false,
) {
    enum class Kind { Comments, Replies, Mention, UploadReady, ShareViewed }
}

@Serializable
internal data class WatchState(
    val files: List<WatchedFile> = emptyList(),
    val shares: List<WatchedShare> = emptyList(),
    val events: List<ActivityEvent> = emptyList(),
    /** Projects whose activity doesn't post phone notifications (still listed in Notifications). */
    val muted: Set<String> = emptySet(),
)

class ActivityWatch(private val context: Context) {
    private val file = File(context.filesDir, "watch.json")
    private val lock = Any()

    private fun read(): WatchState = synchronized(lock) {
        runCatching { AppJson.decodeFromString(WatchState.serializer(), file.readText()) }.getOrDefault(WatchState())
    }

    /** watch.json as found at start, read once for the three flows below. */
    private val initial = read()

    /** New comments per project, for the "N new comments" badge on project cards. */
    private val _unread = kotlinx.coroutines.flow.MutableStateFlow(unreadOf(initial))
    val unreadByProject: kotlinx.coroutines.flow.StateFlow<Map<String, Int>> = _unread

    private fun unreadOf(s: WatchState): Map<String, Int> =
        s.files.filter { it.unread > 0 }.groupBy { it.projectId }.mapValues { (_, l) -> l.sumOf { it.unread } }

    private val _events = kotlinx.coroutines.flow.MutableStateFlow(initial.events)
    /** Everything the background check found, newest first. */
    val events: kotlinx.coroutines.flow.StateFlow<List<ActivityEvent>> = _events

    private val _muted = kotlinx.coroutines.flow.MutableStateFlow(initial.muted)
    val mutedProjects: kotlinx.coroutines.flow.StateFlow<Set<String>> = _muted

    fun setMuted(projectId: String, muted: Boolean) = update { s -> s.copy(muted = if (muted) s.muted + projectId else s.muted - projectId) }

    fun markAllRead() = update { s -> s.copy(events = s.events.map { it.copy(read = true) }, files = s.files.map { it.copy(unread = 0) }) }

    fun markRead(eventId: String) = update { s -> s.copy(events = s.events.map { if (it.id == eventId) it.copy(read = true) else it }) }

    private fun write(s: WatchState) = synchronized(lock) {
        val now = System.currentTimeMillis()
        val keep = s.copy(
            // Bounded: the most recently touched items, nothing idle for two weeks.
            files = s.files.filter { now - it.touchedAt < MAX_AGE_MS || it.awaitingReady }.sortedByDescending { it.touchedAt }.take(MAX_FILES),
            shares = s.shares.filter { now - it.touchedAt < MAX_AGE_MS }.sortedByDescending { it.touchedAt }.take(MAX_SHARES),
            events = s.events.filter { now - parse(it.at).toEpochMilli() < EVENT_AGE_MS }.sortedByDescending { parse(it.at) }.take(MAX_EVENTS),
        )
        runCatching { file.writeText(AppJson.encodeToString(WatchState.serializer(), keep)) }
        _unread.value = unreadOf(keep)
        _events.value = keep.events
        _muted.value = keep.muted
    }

    /** The user looked at [fileId] and its comments up to [latestComment]. */
    // Each change reads and writes under one lock, so a background check can't lose it (or vice versa).
    fun watchFile(accountId: String, projectId: String, fileId: String, name: String, latestComment: String?) = update { s ->
        val old = s.files.firstOrNull { it.fileId == fileId }
        val seen = listOfNotNull(old?.seenUntil, latestComment).maxByOrNull { parse(it) }
        val item = WatchedFile(fileId, accountId, projectId, name, seen ?: Instant.now().toString(), old?.awaitingReady == true)
        s.copy(
            files = listOf(item) + s.files.filterNot { it.fileId == fileId },
            events = s.events.map { if (it.fileId == fileId) it.copy(read = true) else it },
        )
    }

    /** One of the user's uploads: tell them when it's ready, then watch its comments. */
    fun watchUpload(accountId: String, folderProjectId: String, fileId: String, name: String) = update { s ->
        val item = WatchedFile(fileId, accountId, folderProjectId, name, Instant.now().toString(), awaitingReady = true)
        s.copy(files = listOf(item) + s.files.filterNot { it.fileId == fileId })
    }

    fun watchShare(accountId: String, projectId: String, shareId: String, name: String) = update { s ->
        if (s.shares.any { it.shareId == shareId }) s
        else s.copy(shares = listOf(WatchedShare(shareId, accountId, projectId, name, Instant.now().toString())) + s.shares)
    }

    internal fun snapshot() = read()

    /** Files the user opened (or uploaded) lately, newest first: Search shows them as "Recently opened". */
    fun recentlyOpened(accountId: String, limit: Int = 8): List<WatchedFile> =
        read().files.filter { it.accountId == accountId }.sortedByDescending { it.touchedAt }.take(limit)
    internal fun update(block: (WatchState) -> WatchState) = synchronized(lock) { write(block(read())) }

    companion object {
        const val MAX_FILES = 30
        const val MAX_SHARES = 15
        const val MAX_AGE_MS = 14L * 24 * 60 * 60 * 1000
        const val MAX_EVENTS = 200
        const val EVENT_AGE_MS = 30L * 24 * 60 * 60 * 1000
        internal fun parse(iso: String?): Instant = runCatching { Instant.parse(iso) }.getOrDefault(Instant.EPOCH)

        fun schedule(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!enabled) {
                wm.cancelUniqueWork(WORK)
                return
            }
            val req = PeriodicWorkRequestBuilder<ActivityCheckWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        /** A one-off check shortly after notifications are switched on (periodic runs can't be forced). */
        fun checkSoon(context: Context) {
            val req = androidx.work.OneTimeWorkRequestBuilder<ActivityCheckWorker>()
                .setInitialDelay(30, TimeUnit.SECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("$WORK-now", androidx.work.ExistingWorkPolicy.REPLACE, req)
        }

        /**
         * Checks soon after an upload so "Uploads ready" arrives about when Frame.io finishes processing,
         * not at the next periodic run. Retries at growing intervals because transcoding time varies.
         */
        fun checkAfterUpload(context: Context) {
            val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            fun step(delayS: Long) = androidx.work.OneTimeWorkRequestBuilder<ActivityCheckWorker>()
                .setInitialDelay(delayS, TimeUnit.SECONDS)
                .setConstraints(network)
                .build()
            // About 1, 3 and 8 minutes after the upload finishes.
            WorkManager.getInstance(context)
                .beginUniqueWork("$WORK-upload", androidx.work.ExistingWorkPolicy.REPLACE, step(60))
                .then(step(120))
                .then(step(300))
                .enqueue()
        }

        private const val WORK = "activity-check"
    }
}

/** The periodic check. Sequential and capped to stay well inside API rate limits. */
class ActivityCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        ActivityCheck(applicationContext).run()
        return Result.success()
    }
}

/**
 * One pass over the watched files and links: records what's new for the Notifications
 * screen and, when the app isn't on screen, posts phone notifications. Also run on
 * pull-to-refresh in Notifications.
 */
class ActivityCheck(private val context: Context) {
    private val c = FinalishApp.container(context)

    /** One pass at a time: overlapping passes would count the same new comments twice. */
    suspend fun run() {
        if (!running.tryLock()) return
        try {
            pass()
        } finally {
            running.unlock()
        }
    }

    private suspend fun pass() = withContext(Dispatchers.IO) {
        val settings = c.settings.current()
        if (!settings.notifications || c.auth.state.value !is dev.finalish.core.auth.AuthState.SignedIn) return@withContext
        val me = runCatchingCancellable { c.repository.me() }.getOrNull()
        // While the app is on screen, comments show up live: record them, but don't buzz.
        val foreground = withContext(Dispatchers.Main) {
            ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }
        // Only watch items from the accounts this user can still reach (e.g. after switching Adobe IDs);
        // anything else would just 401 every 15 minutes, so it's dropped.
        val reachable = runCatchingCancellable { c.repository.accounts().map { it.id }.toSet() }.getOrNull()
        if (reachable != null) {
            c.activityWatch.update { s -> s.copy(files = s.files.filter { it.accountId in reachable }, shares = s.shares.filter { it.accountId in reachable }) }
        }
        val state = c.activityWatch.snapshot()
        val quiet = { projectId: String -> foreground || projectId in state.muted }
        // First run (or after an update): fill Notifications with the last two weeks, already read.
        backfill = state.events.isEmpty()

        for (w in state.files) {
            if (settings.notifyComments || w.awaitingReady) runCatchingCancellable { checkFile(w, me, quiet(w.projectId), settings.notifyComments, settings.notifyUploads) }
        }
        if (settings.notifyShareViews) {
            for (s in state.shares) runCatchingCancellable { checkShare(s, me?.id, quiet(s.projectId)) }
        }
    }

    private var backfill = false

    private fun record(events: List<ActivityEvent>) {
        if (events.isEmpty()) return
        c.activityWatch.update { s -> s.copy(events = events + s.events.filterNot { e -> events.any { it.id == e.id } }) }
    }

    private suspend fun checkFile(watched: WatchedFile, me: dev.finalish.core.model.User?, quiet: Boolean, comments: Boolean, uploads: Boolean) {
        var w = watched
        if (w.awaitingReady) {
            val f = try {
                c.api.file(w.accountId, w.fileId).data
            } catch (e: retrofit2.HttpException) {
                // Deleted before it was processed: stop asking about it every 15 minutes.
                if (e.code() == 404) c.activityWatch.update { s -> s.copy(files = s.files.filterNot { it.fileId == w.fileId }) }
                throw e
            }
            val ready = f.media.status == null || f.media.status == "transcoded"
            if (ready) {
                if (uploads) {
                    record(listOf(ActivityEvent("ready:${w.fileId}", ActivityEvent.Kind.UploadReady, w.accountId, f.projectId, w.name, fileId = w.fileId, at = Instant.now().toString())))
                    if (!quiet) {
                        Notifier.post(
                            context, "ready:${w.fileId}",
                            title = w.name, text = context.getString(R.string.core_notify_upload_ready),
                            link = Notifier.viewLink(f.projectId, w.fileId),
                            actions = listOf(
                                Notifier.openAction(context, "ready:${w.fileId}", Notifier.viewLink(f.projectId, w.fileId)),
                                Notifier.shareAction(context, "ready:${w.fileId}", w.name, Notifier.viewLink(f.projectId, w.fileId)),
                            ),
                        )
                    }
                }
                c.activityWatch.update { s -> s.copy(files = s.files.map { if (it.fileId == w.fileId) it.copy(awaitingReady = false, projectId = f.projectId) else it }) }
                // Uploads are watched before their project is known: link this pass's comments to it too.
                w = w.copy(awaitingReady = false, projectId = f.projectId)
            }
        }
        if (!comments) return
        val page = allPages(
            first = { c.api.comments(w.accountId, w.fileId) },
            next = { c.api.comments(w.accountId, w.fileId, after = it) },
        )
        val all = page + page.flatMap { it.replies }
        val since = ActivityWatch.parse(w.seenUntil)
        val fresh = all.filter { ActivityWatch.parse(it.createdAt).isAfter(since) && it.owner?.id != me?.id }
        val newest = all.mapNotNull { it.createdAt }.maxByOrNull { ActivityWatch.parse(it) }
        record(eventsFor(w, page, fresh, me?.id))
        if (backfill) {
            val cutoff = Instant.now().minusMillis(ActivityWatch.MAX_AGE_MS)
            val earlier = all.filter { cm ->
                val t = ActivityWatch.parse(cm.createdAt)
                cm.owner?.id != me?.id && t.isAfter(cutoff) && !t.isAfter(since)
            }
            record(eventsFor(w, page, earlier, me?.id).map { it.copy(read = true) })
        }
        if (fresh.isNotEmpty() && !quiet) Notifier.comments(context, w, fresh)
        if (newest != null && ActivityWatch.parse(newest).isAfter(since)) {
            c.activityWatch.update { s ->
                s.copy(files = s.files.map { if (it.fileId == w.fileId) it.copy(seenUntil = newest, unread = it.unread + fresh.size) else it })
            }
        }
    }

    private suspend fun checkShare(w: WatchedShare, me: String?, quiet: Boolean) {
        val events = c.api.shareActivities(w.accountId, w.shareId).data
        val since = ActivityWatch.parse(w.seenUntil)
        val views = events.filter { it.type == "share_viewed" && ActivityWatch.parse(it.insertedAt).isAfter(since) && it.user?.id != me }
        if (views.isNotEmpty()) {
            val who = views.mapNotNull { it.user?.displayName }.distinct()
            val newestView = views.mapNotNull { it.insertedAt }.maxByOrNull { ActivityWatch.parse(it) } ?: Instant.now().toString()
            record(listOf(ActivityEvent("share:${w.shareId}:$newestView", ActivityEvent.Kind.ShareViewed, w.accountId, w.projectId, w.name, shareId = w.shareId, actors = who, count = views.size, at = newestView)))
            if (!quiet) {
                val res = context.resources
                val text = when {
                    who.isEmpty() && views.size == 1 -> context.getString(R.string.core_notify_share_opened_someone)
                    who.isEmpty() -> res.getQuantityString(R.plurals.core_notify_share_opened_times, views.size, views.size)
                    who.size == 1 -> context.getString(R.string.core_notify_share_opened_by, who[0])
                    else -> res.getQuantityString(R.plurals.core_notify_share_opened_by_others, who.size - 1, who[0], who.size - 1)
                }
                Notifier.post(context, "share:${w.shareId}", title = w.name, text = text, link = null)
            }
        }
        val newest = events.mapNotNull { it.insertedAt }.maxByOrNull { ActivityWatch.parse(it) }
        if (newest != null && ActivityWatch.parse(newest).isAfter(since)) {
            c.activityWatch.update { s -> s.copy(shares = s.shares.map { if (it.shareId == w.shareId) it.copy(seenUntil = newest) else it }) }
        }
    }

    companion object {
        private val running = kotlinx.coroutines.sync.Mutex()

        /**
         * Splits new comments on a file like Frame.io's inbox: mentions of you, replies to
         * your comments, and everything else as "left N comments".
         */
        internal fun eventsFor(w: WatchedFile, page: List<Comment>, fresh: List<Comment>, me: String?): List<ActivityEvent> {
            if (fresh.isEmpty()) return emptyList()
            val mine = page.filter { me != null && it.owner?.id == me }.flatMap { p -> p.replies.map { it.id } }.toSet()
            val mentions = fresh.filter { cm -> me != null && cm.mentions.any { it.userId == me } }
            val replies = fresh.filter { it.id in mine && it !in mentions }
            val rest = fresh - mentions.toSet() - replies.toSet()
            fun event(kind: ActivityEvent.Kind, list: List<Comment>): ActivityEvent? {
                if (list.isEmpty()) return null
                val sorted = list.sortedBy { ActivityWatch.parse(it.createdAt) }
                val last = sorted.last()
                return ActivityEvent(
                    id = "${kind.name}:${w.fileId}:${last.id}",
                    kind = kind,
                    accountId = w.accountId,
                    projectId = w.projectId,
                    title = w.name,
                    fileId = w.fileId,
                    actors = sorted.mapNotNull { it.author.displayName }.distinct(),
                    count = sorted.size,
                    text = last.text.takeIf { it.isNotBlank() && kind != ActivityEvent.Kind.Comments },
                    at = last.createdAt ?: Instant.now().toString(),
                )
            }
            return listOfNotNull(
                event(ActivityEvent.Kind.Mention, mentions),
                event(ActivityEvent.Kind.Replies, replies),
                event(ActivityEvent.Kind.Comments, rest),
            )
        }
    }
}

internal object Notifier {
    private const val CHANNEL = "activity"

    fun viewLink(projectId: String, fileId: String) = "https://next.frame.io/project/$projectId/view/$fileId"

    fun comments(context: Context, w: WatchedFile, fresh: List<Comment>) {
        val sorted = fresh.sortedBy { ActivityWatch.parse(it.createdAt) }
        val authors = sorted.map { it.author.displayName(context) }.distinct()
        val title = if (sorted.size == 1) {
            authors.firstOrNull()?.let { context.getString(R.string.core_notify_commented, it) } ?: context.getString(R.string.core_notify_new_comment)
        } else {
            context.resources.getQuantityString(R.plurals.core_notify_new_comments, sorted.size, sorted.size)
        }
        val style = NotificationCompat.InboxStyle().setBigContentTitle("$title · ${w.name}")
        sorted.takeLast(5).forEach { style.addLine("${it.author.displayName(context)}: ${it.text}") }
        post(
            context, "comments:${w.fileId}",
            title = "$title · ${w.name}",
            text = sorted.last().let { "${it.author.displayName(context)}: ${it.text}" },
            link = viewLink(w.projectId, w.fileId),
            style = if (sorted.size > 1) style else NotificationCompat.BigTextStyle().bigText(sorted.last().text),
            actions = listOf(
                openAction(context, "comments:${w.fileId}", viewLink(w.projectId, w.fileId)),
                markReadAction(context, "comments:${w.fileId}", w.fileId),
            ),
        )
    }

    private fun openIntent(context: Context, link: String?): Intent =
        (link?.let { Intent(Intent.ACTION_VIEW, it.toUri()).setPackage(context.packageName) }
            ?: context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    fun openAction(context: Context, key: String, link: String): NotificationCompat.Action =
        NotificationCompat.Action(
            0, context.getString(R.string.core_notify_open),
            PendingIntent.getActivity(context, ("open" + key).hashCode(), openIntent(context, link), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
        )

    fun markReadAction(context: Context, key: String, fileId: String): NotificationCompat.Action {
        val i = Intent(context, NotificationActionReceiver::class.java)
            .setAction(NotificationActionReceiver.MARK_READ)
            .putExtra(NotificationActionReceiver.EXTRA_KEY, key)
            .putExtra(NotificationActionReceiver.EXTRA_FILE, fileId)
        return NotificationCompat.Action(
            0, context.getString(R.string.core_notify_mark_read),
            PendingIntent.getBroadcast(context, ("read" + key).hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
        )
    }

    /** "Share" on a finished upload: hands its Frame.io link to any app. */
    fun shareAction(context: Context, key: String, title: String, link: String): NotificationCompat.Action {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link).putExtra(Intent.EXTRA_SUBJECT, title)
        val chooser = Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return NotificationCompat.Action(
            0, context.getString(R.string.core_notify_share),
            PendingIntent.getActivity(context, ("share" + key).hashCode(), chooser, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
        )
    }

    fun post(
        context: Context,
        key: String,
        title: String,
        text: String,
        link: String?,
        style: NotificationCompat.Style? = null,
        actions: List<NotificationCompat.Action> = emptyList(),
    ) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        ensureChannel(context)
        // Tapping opens the file in the app (same route as a pasted next.frame.io link).
        val intent = openIntent(context, link)
        val pi = PendingIntent.getActivity(context, key.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_finalish)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(style)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .apply { actions.forEach { addAction(it) } }
            .build()
        runCatching { nm.notify(key, 0, n) }
    }

    private fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.core_notify_channel_activity), NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = context.getString(R.string.core_notify_channel_activity_desc) },
            )
        }
    }
}

/**
 * "Mark as read" on a comments notification: dismiss it and clear the project badge's
 * count for the file. "Cancel all" on the grouped upload notification.
 */
class NotificationActionReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == CANCEL_UPLOADS) {
            val c = dev.finalish.FinalishApp.container(context)
            c.transfers.uploads.value
                .filter { it.state == dev.finalish.core.transfer.TransferState.Queued || it.state == dev.finalish.core.transfer.TransferState.Running }
                .forEach { c.transferManager.cancelUpload(it.id) }
            return
        }
        if (intent.action != MARK_READ) return
        intent.getStringExtra(EXTRA_KEY)?.let { NotificationManagerCompat.from(context).cancel(it, 0) }
        val fileId = intent.getStringExtra(EXTRA_FILE) ?: return
        dev.finalish.FinalishApp.container(context).activityWatch.update { s ->
            s.copy(files = s.files.map { if (it.fileId == fileId) it.copy(unread = 0) else it })
        }
    }

    companion object {
        const val MARK_READ = "dev.finalish.MARK_READ"
        const val CANCEL_UPLOADS = "dev.finalish.CANCEL_UPLOADS"
        const val EXTRA_KEY = "key"
        const val EXTRA_FILE = "file"
    }
}
