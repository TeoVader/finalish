package dev.finalish.core.auth

import android.net.Uri
import androidx.core.net.toUri
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Minimal one-shot HTTP listener for loopback redirect URIs
 * (e.g. `http://127.0.0.1:8765/callback`). Lets people use their own Adobe
 * credential at runtime without rebuilding the app with a custom URI scheme.
 */
object LoopbackReceiver {

    suspend fun awaitRedirect(redirectUri: String, timeoutMs: Long = 5 * 60_000L): Uri =
        withContext(Dispatchers.IO) {
            val port = redirectUri.toUri().port.takeIf { it > 0 } ?: 80
            // Only the registered redirect path counts: a request to any other path on the port
            // (another app probing it, a stray browser tab) is answered 404 and ignored.
            val expectedPath = redirectUri.toUri().path.orEmpty().ifEmpty { "/" }
            ServerSocket(port, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                server.soTimeout = 1_000
                withTimeout(timeoutMs) {
                    while (true) {
                        coroutineContext.ensureActive()
                        val socket = try {
                            server.accept()
                        } catch (_: java.net.SocketTimeoutException) {
                            continue
                        }
                        socket.use { s ->
                            // Browsers open speculative connections that never send a request:
                            // don't let one block the real redirect.
                            s.soTimeout = 5_000
                            val requestLine = try {
                                s.getInputStream().bufferedReader().readLine().orEmpty()
                            } catch (_: java.net.SocketTimeoutException) {
                                ""
                            }
                            val path = requestLine.split(" ").getOrNull(1).orEmpty()
                            val uri = "http://127.0.0.1:$port$path".toUri()
                            val ok = requestLine.startsWith("GET ") &&
                                uri.path.orEmpty().ifEmpty { "/" } == expectedPath &&
                                (uri.getQueryParameter("code") != null || uri.getQueryParameter("error") != null)
                            val body = if (ok) SUCCESS_HTML else "Not found"
                            val status = if (ok) "200 OK" else "404 Not Found"
                            s.getOutputStream().apply {
                                write(
                                    ("HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\n" +
                                        "Content-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body")
                                        .toByteArray(),
                                )
                                flush()
                            }
                            if (ok) return@withTimeout uri
                        }
                    }
                    @Suppress("UNREACHABLE_CODE")
                    error("unreachable")
                }
            }
        }

    private val SUCCESS_HTML = """
        <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
        <style>body{background:#0A0A0E;color:#F4F4F8;font-family:sans-serif;display:flex;min-height:100vh;
        align-items:center;justify-content:center;margin:0;text-align:center}
        a{display:inline-block;margin-top:24px;padding:14px 28px;border-radius:14px;background:#7C6CFF;
        color:#fff;text-decoration:none;font-weight:600}</style></head>
        <body><div><h2>Signed in</h2><p>You can return to Final(ish).</p>
        <a href="finalish://auth-done">Back to Final(ish)</a></div>
        <script>setTimeout(function(){location.href='finalish://auth-done'},300)</script></body></html>
    """.trimIndent()
}
