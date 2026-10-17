package dev.finalish.core.feedback

import android.app.Activity
import android.content.Context
import androidx.core.content.edit
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.annotation.StringRes
import androidx.core.graphics.createBitmap
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.finalish.BuildConfig
import dev.finalish.FinalishApp
import dev.finalish.R
import dev.finalish.core.network.AppJson
import dev.finalish.core.network.userMessage
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import okhttp3.Request

/**
 * In-app bug reports and feature requests.
 *
 * A report bundles what the user wrote with what a developer needs to act on it:
 * a screenshot of the screen they were on, the app's recent (sanitised) logs, the
 * screens they visited, and device/app info. Reports are queued on disk and sent in
 * the background, so they also work offline.
 *
 * Destination: an issue on the app's GitHub repository, opened by the report relay (relay/,
 * a Cloudflare Worker) so no GitHub credential ships in the app. Screenshots, recordings and
 * logs go to the repository's `feedback-assets` branch and show inside the issue.
 */
/** [label] goes into the report (English); [labelRes] is what the app shows. */
enum class FeedbackKind(val label: String, @StringRes val labelRes: Int, val tag: String) {
    Bug("Bug", R.string.core_feedback_kind_bug, "BUG"),
    Feature("Feature request", R.string.core_feedback_kind_feature, "FEATURE"),
    Change("Improvement", R.string.core_feedback_kind_change, "IMPROVE"),
}

/** Shared = handed to another app through the share sheet (no issue opened). */
enum class FeedbackStatus { Queued, Sending, Sent, Failed, Shared }

@Serializable
data class FeedbackReport(
    val id: String,
    val kind: FeedbackKind,
    val title: String,
    val description: String,
    val createdAt: Long,
    val includeScreenshot: Boolean,
    val includeLogs: Boolean,
    /** A screen recording attached by the user (copied to recording.<ext>). Older reports only. */
    val recordingName: String? = null,
    /** Screenshots/recordings the user attached (file names inside the report folder). */
    val attachments: List<String> = emptyList(),
    val device: Map<String, String>,
    val screens: List<String>,
    val status: FeedbackStatus = FeedbackStatus.Queued,
    val error: String? = null,
    /** Reports sent to Frame.io by older versions. */
    val remoteFolderId: String? = null,
    val remoteProjectId: String? = null,
    val issueUrl: String? = null,
    val issueNumber: Int? = null,
    /** The relay ticket this report was prepared with (solved while the user typed). */
    val ticket: ReportRelay.Ticket? = null,
)

/** Where the user was, for "steps to reproduce": the last screens visited. */
object Breadcrumbs {
    private val trail = ArrayDeque<String>()
    private val time = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun add(screen: String) {
        if (trail.lastOrNull()?.substringAfter(' ') == screen) return
        trail.addLast("${time.format(Date())} $screen")
        dev.finalish.core.util.AppLog.i("Screen: $screen")
        while (trail.size > 25) trail.removeFirst()
    }

    @Synchronized
    fun snapshot(): List<String> = trail.toList()

    @Synchronized
    fun clear() = trail.clear()

    fun current(): String = snapshot().lastOrNull()?.substringAfter(' ')?.substringBefore(" (") ?: "unknown"
}

/** Removes secrets and personal data from logs before they leave the device. */
object Sanitizer {
    private val rules = listOf(
        Regex("""(?i)(bearer\s+)[A-Za-z0-9._\-]+""") to "$1[token]",
        Regex("""(?i)((?:access|refresh|id)_token["'=:\s]+)[A-Za-z0-9._\-]+""") to "$1[token]",
        Regex("""(?i)(code=)[A-Za-z0-9._\-]+""") to "$1[code]",
        // Bare JWTs (id tokens, signed claims) wherever they show up.
        Regex("""eyJ[\w-]+\.[\w-]+\.[\w-]+""") to "[jwt]",
        // Presigned/signed URLs: keep the path, drop the query (signatures, keys).
        Regex("""(https?://[^\s?"']+)\?[^\s"']+""") to "$1?[…]",
        Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""") to "[email]",
    )

    fun clean(text: String): String = rules.fold(text) { acc, (re, rep) -> re.replace(acc, rep) }
}

/** Reads this app's own recent log lines (no permission needed for the app's process). */
object AppLogs {
    /** The app's own recent log (plus a crash from the previous session), sanitised. */
    fun recent(): String {
        val crash = dev.finalish.core.util.AppLog.lastCrash()?.let { "=== Previous crash ===\n$it\n\n" }.orEmpty()
        val log = dev.finalish.core.util.AppLog.snapshot().ifBlank { "No app log lines this session." }
        return Sanitizer.clean(crash + "=== Recent app log ===\n" + log)
    }
}

object DeviceInfo {
    fun collect(context: Context, accountId: String?, plan: String? = null): Map<String, String> {
        val dm = context.resources.displayMetrics
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        val network = when {
            caps == null -> "offline"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            else -> "other"
        }
        return linkedMapOf(
            "App" to "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}, ${BuildConfig.BUILD_TYPE})",
            "Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "Device" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "Screen" to "${dm.widthPixels}×${dm.heightPixels} @${dm.densityDpi}dpi",
            "Locale" to Locale.getDefault().toLanguageTag(),
            "Network" to network,
            "Screen at report" to Breadcrumbs.current(),
        ).apply {
            accountId?.let { put("Account", it) }
            plan?.let { put("Frame.io plan", it) }
        }
    }
}

/** Grabs the current window (including video surfaces) before the report screen opens. */
object ScreenCapture {
    suspend fun capture(activity: Activity): Bitmap? = suspendCoroutine { cont ->
        val window = activity.window
        val view = window.decorView
        if (view.width == 0 || view.height == 0) return@suspendCoroutine cont.resume(null)
        val bmp = createBitmap(view.width, view.height)
        runCatching {
            PixelCopy.request(window, bmp, { result ->
                cont.resume(if (result == PixelCopy.SUCCESS) bmp else null)
            }, Handler(Looper.getMainLooper()))
        }.onFailure { cont.resume(null) }
    }
}

/** Reports on disk: feedback/<id>/{report.json, screenshot.png, logs.txt}. */
class FeedbackStore(context: Context) {
    private val root = File(context.filesDir, "feedback").apply { mkdirs() }
    private val _reports = MutableStateFlow(load())
    val reports: StateFlow<List<FeedbackReport>> = _reports.asStateFlow()

    /** Screenshot taken when the reporter was opened, before it's attached to a report. */
    val draftScreenshot: File get() = File(root, "draft-screenshot.png")

    fun dir(id: String) = File(root, id)

    private fun load(): List<FeedbackReport> =
        root.listFiles()?.filter { it.isDirectory }?.mapNotNull { d ->
            runCatching { AppJson.decodeFromString(FeedbackReport.serializer(), File(d, "report.json").readText()) }.getOrNull()
        }?.sortedByDescending { it.createdAt }.orEmpty()

    @Synchronized
    fun save(r: FeedbackReport) {
        val d = dir(r.id).apply { mkdirs() }
        File(d, "report.json").writeText(AppJson.encodeToString(FeedbackReport.serializer(), r))
        _reports.value = (listOf(r) + _reports.value.filterNot { it.id == r.id }).sortedByDescending { it.createdAt }
    }

    fun update(id: String, block: (FeedbackReport) -> FeedbackReport) {
        _reports.value.firstOrNull { it.id == id }?.let { save(block(it)) }
    }

    fun get(id: String) = _reports.value.firstOrNull { it.id == id }

    @Synchronized
    fun delete(id: String) {
        dir(id).deleteRecursively()
        _reports.value = _reports.value.filterNot { it.id == id }
    }

    /** Sign-out: every saved report (screenshots included) goes, so the next user can't see them. */
    @Synchronized
    fun clearAll() {
        root.listFiles()?.forEach { it.deleteRecursively() }
        _reports.value = emptyList()
    }

    /** Creates the report on disk (with screenshot/logs) and queues it for sending. */
    fun submit(
        context: Context,
        kind: FeedbackKind,
        title: String,
        description: String,
        includeScreenshot: Boolean,
        includeLogs: Boolean,
        accountId: String?,
        send: Boolean,
        media: List<android.net.Uri> = emptyList(),
        ticket: ReportRelay.Ticket? = null,
        plan: String? = null,
    ): FeedbackReport {
        val id = UUID.randomUUID().toString()
        val d = dir(id).apply { mkdirs() }
        val attached = media.mapIndexedNotNull { i, uri ->
            val type = context.contentResolver.getType(uri).orEmpty()
            val ext = type.substringAfter('/').takeIf { it.length in 2..5 } ?: if (type.startsWith("image")) "png" else "mp4"
            val name = "${if (type.startsWith("image")) "image" else "recording"}-${i + 1}.$ext"
            runCatching {
                context.contentResolver.openInputStream(uri)!!.use { input -> File(d, name).outputStream().use { input.copyTo(it) } }
                name
            }.getOrNull()
        }
        if (includeScreenshot && draftScreenshot.exists()) draftScreenshot.copyTo(File(d, "screenshot.png"), overwrite = true)
        if (includeLogs) {
            File(d, "logs.txt").writeText(AppLogs.recent())
            dev.finalish.core.util.AppLog.clearLastCrash()
        }
        val r = FeedbackReport(
            id = id, kind = kind, title = title.trim(), description = description.trim(),
            createdAt = System.currentTimeMillis(),
            includeScreenshot = includeScreenshot && File(d, "screenshot.png").exists(),
            includeLogs = includeLogs,
            attachments = attached,
            device = DeviceInfo.collect(context, accountId, plan),
            screens = Breadcrumbs.snapshot(),
            ticket = ticket,
        )
        save(r)
        if (send) FeedbackWorker.enqueue(context, id)
        return r
    }

    /**
     * Queues again the reports left waiting or sending whose work is gone (e.g. the app was force-stopped
     * mid-send); KEEP leaves the ones WorkManager still holds alone.
     */
    fun resumePending(context: Context) {
        _reports.value.filter { it.status == FeedbackStatus.Queued || it.status == FeedbackStatus.Sending }
            .forEach { FeedbackWorker.enqueue(context, it.id) }
    }

    /** Sends (again) a report that failed, was only shared or is stuck. */
    fun retry(context: Context, id: String) {
        update(id) { it.copy(status = FeedbackStatus.Queued, error = null) }
        FeedbackWorker.enqueue(context, id, replace = true)
    }

    /**
     * Hands the report to another app (chat, email…) through Android's share sheet:
     * formatted text, plus the screenshot and full logs as attachments.
     */
    fun share(context: Context, r: FeedbackReport) {
        val d = dir(r.id)
        val authority = "${context.packageName}.files"
        val uris = arrayListOf<android.net.Uri>()
        File(d, "screenshot.png").takeIf { r.includeScreenshot && it.exists() }
            ?.let { uris += androidx.core.content.FileProvider.getUriForFile(context, authority, it) }
        File(d, "logs.txt").takeIf { r.includeLogs && it.exists() }
            ?.let { uris += androidx.core.content.FileProvider.getUriForFile(context, authority, it) }
        (listOfNotNull(r.recordingName) + r.attachments).map { File(d, it) }.filter { it.exists() }
            .forEach { uris += androidx.core.content.FileProvider.getUriForFile(context, authority, it) }
        val text = shareText(r, File(d, "logs.txt").takeIf { r.includeLogs && it.exists() }?.readText())
        val intent = if (uris.size > 1) {
            android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, uris).setType("*/*")
        } else {
            android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                if (uris.isNotEmpty()) { putExtra(android.content.Intent.EXTRA_STREAM, uris[0]); type = context.contentResolver.getType(uris[0]) ?: "*/*" } else type = "text/plain"
            }
        }
        intent.putExtra(android.content.Intent.EXTRA_SUBJECT, "Final(ish) ${r.kind.label}: ${r.title}")
            .putExtra(android.content.Intent.EXTRA_TEXT, text)
            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(
            android.content.Intent.createChooser(intent, context.getString(R.string.core_feedback_send_report)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        if (r.status != FeedbackStatus.Sent) update(r.id) { it.copy(status = FeedbackStatus.Shared, error = null) }
    }

    companion object {
        /** Compact report for chat: description, device, screens and the recent warnings/errors. */
        fun shareText(r: FeedbackReport, logs: String?): String = buildString {
            appendLine("[Final(ish) ${r.kind.label}] ${r.title}")
            appendLine()
            if (r.description.isNotBlank()) { appendLine(r.description); appendLine() }
            appendLine(r.device.entries.joinToString(" · ") { (k, v) -> "$k: $v" })
            if (r.screens.isNotEmpty()) {
                appendLine()
                appendLine("Screens: " + r.screens.takeLast(8).joinToString(" → ") { it.substringAfter(' ') })
            }
            val problems = logs?.lineSequence()
                ?.filter { Regex(""" [WE] """).containsMatchIn(it) || it.startsWith("Crash") }
                ?.toList()?.takeLast(25).orEmpty()
            if (problems.isNotEmpty()) {
                appendLine()
                appendLine("Recent warnings/errors:")
                problems.forEach { appendLine(it.take(300)) }
            }
        }

        /**
         * The part of the log an issue needs: warnings, errors and crashes (plain activity only when
         * there are none), last lines first to go when it's too long. The full log is attached.
         */
        fun logExcerpt(logs: String, maxChars: Int = 5000): String {
            val lines = logs.lines().filter { it.isNotBlank() && !it.startsWith("===") }
            val problems = lines.filter { Regex(""" [WE] """).containsMatchIn(it) || it.startsWith("Crash") }
            var out = (problems.ifEmpty { lines }).takeLast(40).map { it.take(300) }
            while (out.sumOf { it.length + 1 } > maxChars && out.isNotEmpty()) out = out.drop(1)
            return out.joinToString("\n")
        }
    }
}

/** Sends one queued report through the report relay, which opens the GitHub issue. */
class FeedbackWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val c = FinalishApp.container(context)

    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY) ?: return Result.failure()
        val store = c.feedback
        val relay = c.reportRelay
        val r = store.get(id) ?: return Result.failure()
        if (r.status == FeedbackStatus.Sent) return Result.success()
        if (!relay.enabled) {
            store.update(id) { it.copy(status = FeedbackStatus.Failed, error = applicationContext.getString(R.string.core_feedback_not_set_up)) }
            return Result.failure()
        }
        store.update(id) { it.copy(status = FeedbackStatus.Sending, error = null) }
        return try {
            val ticket = r.ticket?.takeUnless { it.expired } ?: relay.challenge().also { t -> store.update(id) { it.copy(ticket = t) } }
            // A first report waits a few seconds, a later one longer: wait here when it's short.
            if (ticket.waitMs > 0) {
                if (ticket.waitMs > 90_000) return later(id, ticket.waitMs, applicationContext.getString(R.string.core_feedback_waiting))
                kotlinx.coroutines.delay(ticket.waitMs + 500)
            }
            val d = store.dir(id)
            val logs = File(d, "logs.txt").takeIf { r.includeLogs && it.exists() }
            val parts = buildList {
                File(d, "screenshot.png").takeIf { r.includeScreenshot && it.exists() }?.let { add(ReportRelay.Part("screenshot", it, "image/png")) }
                logs?.let { add(ReportRelay.Part("logs", it, "text/plain")) }
                // Within the relay's limits: per file, and in total (the first ones win).
                var total = sumOf { it.file.length() }
                (listOfNotNull(r.recordingName) + r.attachments).map { File(d, it) }.filter { it.exists() && it.length() in 1..MAX_ATTACHMENT }
                    .filter { f -> (total + f.length() <= MAX_TOTAL).also { if (it) total += f.length() } }
                    .forEachIndexed { i, f -> add(ReportRelay.Part("attachment-$i", f, mimeOf(f))) }
            }
            when (val out = relay.send(ticket, ReportRelay.reportJson(r, logs?.readText()), parts)) {
                is ReportRelay.Outcome.Created -> {
                    store.update(id) { it.copy(status = FeedbackStatus.Sent, error = null, issueUrl = out.url, issueNumber = out.number, ticket = null) }
                    Result.success()
                }
                ReportRelay.Outcome.Duplicate -> {
                    store.update(id) { it.copy(status = FeedbackStatus.Sent, error = null, ticket = null) }
                    Result.success()
                }
                is ReportRelay.Outcome.Later -> {
                    store.update(id) { it.copy(ticket = null) }
                    later(id, out.ms.coerceAtLeast(5_000), out.reason)
                }
                is ReportRelay.Outcome.Rejected -> {
                    store.update(id) { it.copy(status = FeedbackStatus.Failed, error = out.reason, ticket = null) }
                    Result.failure()
                }
            }
        } catch (e: CancellationException) {
            // WorkManager stopped this run (constraints lost, Retry tapped, app killed): it runs it again
            // later, so the report goes back to the queue instead of showing "Sending" forever.
            store.update(id) { if (it.status == FeedbackStatus.Sending) it.copy(status = FeedbackStatus.Queued, error = null) else it }
            throw e
        } catch (t: Throwable) {
            // The relay may have used up the ticket before failing: a retry starts from a fresh one,
            // else it would answer "duplicate" and the report would pass for sent.
            val message = when (t) {
                is ReportRelay.RelayException ->
                    if (t.code == 503) applicationContext.getString(R.string.core_feedback_paused)
                    else applicationContext.getString(R.string.core_feedback_relay_error, t.code)
                is IOException -> applicationContext.getString(R.string.core_feedback_unreachable)
                else -> t.userMessage(applicationContext)
            }
            store.update(id) { it.copy(status = FeedbackStatus.Failed, error = message, ticket = null) }
            if (runAttemptCount < 5 && t is IOException) Result.retry() else Result.failure()
        }
    }

    /** Hands the report back to WorkManager to try again after [ms]. */
    private fun later(id: String, ms: Long, reason: String): Result {
        // "Waiting to send · around 09:47": a clock time stays true while the list sits on screen.
        val at = java.time.LocalTime.now().plusSeconds(ms / 1000 + 30)
            .format(java.time.format.DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT))
        val note = if (ms >= 60_000) applicationContext.getString(R.string.core_feedback_around, reason, at) else reason
        c.feedback.update(id) { it.copy(status = FeedbackStatus.Queued, error = note) }
        // Appended to this run, which is still finishing (REPLACE would cancel it mid-way).
        enqueue(applicationContext, id, delayMs = ms, policy = ExistingWorkPolicy.APPEND_OR_REPLACE)
        return Result.success()
    }

    private fun mimeOf(f: File): String = when (f.extension.lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "webm" -> "video/webm"
        "3gp", "3gpp" -> "video/3gpp"
        "mov" -> "video/quicktime"
        else -> "video/mp4"
    }

    companion object {
        private const val KEY = "feedback"
        /** The relay's limits, per file and per report. */
        const val MAX_ATTACHMENT = 10L * 1024 * 1024
        const val MAX_TOTAL = 20L * 1024 * 1024

        fun enqueue(
            context: Context,
            id: String,
            replace: Boolean = false,
            delayMs: Long = 0,
            policy: ExistingWorkPolicy = if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
        ) {
            val req = OneTimeWorkRequestBuilder<FeedbackWorker>()
                .setInputData(androidx.work.workDataOf(KEY to id))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("feedback:$id", policy, req)
        }
    }
}

/**
 * The Frame.io plan the reporter says they're on: the API has no plan endpoint, and many bugs
 * depend on it (features missing on Free, limits on Pro). Remembered for the next report.
 */
enum class FramePlan(val label: String) {
    Unknown("Not sure"), Free("Free"), Pro("Pro"), Team("Team"), Business("Business"), Enterprise("Enterprise");

    companion object {
        private const val PREFS = "feedback"
        private const val KEY = "frameio_plan"

        /** The last answer, else Free when the app already saw Free's limits on this account. */
        fun remembered(context: Context, accountId: String?): FramePlan {
            val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            return entries.firstOrNull { it.name == saved }
                ?: if (dev.finalish.core.network.PlanLimits.isFreeAccount(accountId)) Free else Unknown
        }

        fun remember(context: Context, plan: FramePlan) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(KEY, plan.name) }
        }
    }
}
