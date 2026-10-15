package dev.finalish.testing

import dev.finalish.core.data.FrameioRepository
import dev.finalish.core.data.JsonCache
import dev.finalish.core.network.Network
import java.io.File
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * A Frame.io API answered from canned JSON, for ViewModel tests: [on] sets the answer to
 * "METHOD path" (path after /v4/, no query), anything else is a 404. Calls run on the calling
 * thread, so a ViewModel on an unconfined dispatcher finishes its work before the call returns.
 */
class FakeFrameio(cacheDir: File) {
    private val routes = HashMap<String, Pair<Int, String>>()
    /** Every request made, as "METHOD path". */
    val calls = mutableListOf<String>()

    fun on(route: String, json: String, code: Int = 200) {
        routes[route] = code to json
    }

    private val client = OkHttpClient.Builder()
        .dispatcher(Dispatcher(DirectExecutor()))
        .addInterceptor { chain ->
            val req = chain.request()
            val key = "${req.method} ${req.url.encodedPath.removePrefix("/v4/")}"
            synchronized(calls) { calls += key }
            val (code, body) = routes[key] ?: (404 to """{"errors":[{"detail":"Not found"}]}""")
            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("fake")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }
        .build()

    val repository = FrameioRepository(Network.api(client), JsonCache(cacheDir))

    companion object {
        /** A list response: `{"data": [...], "links": {"next": null}}`. */
        fun page(vararg items: String) = """{"data":[${items.joinToString(",")}],"links":{"next":null}}"""
        fun data(item: String) = """{"data":$item}"""
    }
}

/** Runs each task right away on the submitting thread. */
private class DirectExecutor : AbstractExecutorService() {
    @Volatile private var shut = false
    override fun execute(command: Runnable) = command.run()
    override fun shutdown() { shut = true }
    override fun shutdownNow(): MutableList<Runnable> { shut = true; return mutableListOf() }
    override fun isShutdown() = shut
    override fun isTerminated() = shut
    override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
}

/** The first value of [flow] that matches, waiting at most a few seconds (some work runs on other dispatchers). */
fun <T> awaitValue(flow: Flow<T>, predicate: (T) -> Boolean): T =
    runBlocking { withTimeout(5_000) { flow.first(predicate) } }
