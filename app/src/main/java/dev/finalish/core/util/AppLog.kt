package dev.finalish.core.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The app's own log: everything it logs goes to a small in-memory ring, so a bug
 * report can include recent activity (reading logcat from inside the app is
 * unreliable on recent Android), and to logcat in debug builds only: release builds
 * keep paths and file names out of the system log. The last crash is kept on disk
 * and attached to the next report.
 */
object AppLog {
    private const val TAG = "Finalish"
    private const val MAX = 800
    private val ring = ArrayDeque<String>()
    private val time = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private var crashFile: File? = null

    private val logcat = dev.finalish.BuildConfig.DEBUG

    fun i(msg: String) = add('I', msg, null).also { if (logcat) Log.i(TAG, msg) }
    fun w(msg: String, t: Throwable? = null) = add('W', msg, t).also { if (logcat) Log.w(TAG, msg, t) }
    fun e(msg: String, t: Throwable? = null) = add('E', msg, t).also { if (logcat) Log.e(TAG, msg, t) }

    /** Empties the ring and the saved crash: on sign-out, so the next user doesn't send the last one's. */
    @Synchronized
    fun clear() {
        ring.clear()
        clearLastCrash()
    }

    @Synchronized
    private fun add(level: Char, msg: String, t: Throwable?) {
        val line = "${time.format(Date())} $level $msg" + (t?.let { "\n  ${it.javaClass.simpleName}: ${it.message}" } ?: "")
        ring.addLast(line)
        while (ring.size > MAX) ring.removeFirst()
    }

    @Synchronized
    fun snapshot(): String = ring.joinToString("\n")

    /** Records uncaught exceptions to disk before the process dies. */
    fun installCrashRecorder(context: Context) {
        crashFile = File(context.filesDir, "last-crash.txt")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                crashFile?.writeText(
                    "Crash at ${time.format(Date())} on thread ${thread.name}\n" +
                        Log.getStackTraceString(e) + "\n--- recent log ---\n" + snapshot(),
                )
            }
            previous?.uncaughtException(thread, e)
        }
    }

    /** The crash from a previous session, if any (cleared once a report includes it). */
    fun lastCrash(): String? = crashFile?.takeIf { it.exists() }?.readText()

    fun clearLastCrash() {
        crashFile?.delete()
    }
}
