package dev.finalish.core.feedback

import android.content.Context
import androidx.core.content.edit
import dev.finalish.R
import dev.finalish.core.network.AppJson
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Client of the report relay (relay/ in the repository): the app never holds a GitHub credential.
 *
 * A ticket is fetched when the report form opens and its proof of work is solved while the user
 * types, so tapping Send is instant. The relay then needs the report no earlier than the ticket's
 * not-before time (a few seconds for a first report, longer for each further one).
 */
class ReportRelay(context: Context, private val http: OkHttpClient, private val baseUrl: String) {
    /** For the reasons shown in the report list. */
    private val appContext = context.applicationContext
    private val prefs = context.getSharedPreferences("report-relay", Context.MODE_PRIVATE)

    val enabled: Boolean get() = baseUrl.isNotBlank()

    /** Random, per installation; the relay only ever sees an HMAC of it. Used to meter reports. */
    val installId: String
        get() = prefs.getString("installId", null) ?: UUID.randomUUID().toString().also { id -> prefs.edit { putString("installId", id) } }

    @Serializable
    data class Ticket(val ticket: String, val seed: String, val bits: Int, val nbf: Long, val exp: Long, val nonce: String? = null) {
        val expired: Boolean get() = System.currentTimeMillis() > exp - 30_000
        val waitMs: Long get() = (nbf - System.currentTimeMillis()).coerceAtLeast(0)
    }

    sealed interface Outcome {
        data class Created(val url: String, val number: Int) : Outcome
        /** Too early, or the relay is busy: try again after [ms]. */
        data class Later(val ms: Long, val reason: String) : Outcome
        /** This report already went through. */
        data object Duplicate : Outcome
        /** The relay refused the report itself; retrying won't help. */
        data class Rejected(val reason: String) : Outcome
    }

    /** The relay answered with an error status: it's up, but couldn't take the report (e.g. GitHub refused it). */
    class RelayException(val code: Int) : IOException("Relay error ($code)")

    @Volatile private var prefetched: Ticket? = null

    /** Fetches and solves a ticket in the background, ready for the next Send. */
    suspend fun prefetch() {
        if (!enabled || prefetched?.expired == false) return
        runCatching { solve(challenge()) }.onSuccess { prefetched = it }
    }

    /** The prefetched ticket, if it's still good; the sender fetches its own otherwise. */
    fun takePrefetched(): Ticket? = prefetched?.takeUnless { it.expired }.also { prefetched = null }

    suspend fun challenge(): Ticket = withContext(Dispatchers.IO) {
        val body = buildJsonObject { put("installId", installId) }.toString().toRequestBody(JSON)
        http.newCall(Request.Builder().url("$baseUrl/challenge").post(body).build()).execute().use { res ->
            if (!res.isSuccessful) throw RelayException(res.code)
            val o = AppJson.parseToJsonElement(res.body.string()).jsonObject
            Ticket(
                ticket = o.getValue("ticket").jsonPrimitive.content,
                seed = o.getValue("seed").jsonPrimitive.content,
                bits = o.getValue("bits").jsonPrimitive.int,
                nbf = o.getValue("nbf").jsonPrimitive.long,
                exp = o.getValue("exp").jsonPrimitive.long,
            )
        }
    }

    /** About a second of SHA-256 off the main thread; the relay checks the same rule. */
    suspend fun solve(t: Ticket): Ticket = if (t.nonce != null) t else withContext(Dispatchers.Default) {
        t.copy(nonce = solvePow(t.seed, t.bits))
    }

    data class Part(val field: String, val file: File, val mime: String)

    suspend fun send(ticket: Ticket, report: JsonObject, parts: List<Part>): Outcome = withContext(Dispatchers.IO) {
        val solved = solve(ticket)
        val meta = buildJsonObject {
            report.forEach { (k, v) -> put(k, v) }
            put("installId", installId)
            put("ticket", solved.ticket)
            put("powNonce", solved.nonce)
        }
        val form = MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("report", meta.toString())
        parts.forEach { p -> form.addFormDataPart(p.field, p.file.name, p.file.asRequestBody(p.mime.toMediaType())) }
        http.newCall(Request.Builder().url("$baseUrl/report").post(form.build()).build()).execute().use { res ->
            val retryAfter = res.header("retry-after")?.toLongOrNull()?.times(1000)
            when (res.code) {
                201 -> AppJson.parseToJsonElement(res.body.string()).jsonObject.let {
                    Outcome.Created(it.getValue("url").jsonPrimitive.content, it.getValue("number").jsonPrimitive.int)
                }
                403 -> Outcome.Later(retryAfter ?: 0, appContext.getString(R.string.core_feedback_waiting))
                409 -> Outcome.Duplicate
                429 -> Outcome.Later(retryAfter ?: 60_000, appContext.getString(R.string.core_feedback_waiting))
                503 -> Outcome.Later(retryAfter ?: 6 * 3_600_000L, appContext.getString(R.string.core_feedback_paused))
                413 -> Outcome.Rejected(appContext.getString(R.string.core_feedback_too_large))
                in 500..599 -> throw RelayException(res.code)
                else -> Outcome.Rejected(appContext.getString(R.string.core_feedback_refused, res.code))
            }
        }
    }

    companion object {
        private val JSON = "application/json".toMediaType()

        /** A nonce such that sha256("seed:nonce") starts with [bits] zero bits. */
        fun solvePow(seed: String, bits: Int): String {
            if (bits <= 0) return "0"
            val md = MessageDigest.getInstance("SHA-256")
            var n = 0L
            while (true) {
                val d = md.digest("$seed:$n".toByteArray())
                if (leadingZeroBits(d) >= bits) return n.toString()
                n++
            }
        }

        fun leadingZeroBits(d: ByteArray): Int {
            var bits = 0
            for (b in d) {
                val v = b.toInt() and 0xff
                if (v == 0) { bits += 8; continue }
                return bits + Integer.numberOfLeadingZeros(v) - 24
            }
            return bits
        }

        /** The report fields the relay renders into the issue. */
        fun reportJson(r: FeedbackReport, logs: String?): JsonObject = buildJsonObject {
            put("kind", when (r.kind) { FeedbackKind.Bug -> "bug"; FeedbackKind.Feature -> "feature"; FeedbackKind.Change -> "change" })
            put("title", r.title)
            put("description", r.description)
            putJsonObject("device") { r.device.forEach { (k, v) -> put(k, v) } }
            putJsonArray("screens") { r.screens.takeLast(12).forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
            logs?.let { put("logExcerpt", FeedbackStore.logExcerpt(it)) }
        }
    }
}
