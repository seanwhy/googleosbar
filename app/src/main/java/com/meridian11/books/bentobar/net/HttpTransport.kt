package com.meridian11.books.bentobar.net

import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.zip.GZIPInputStream
import javax.net.ssl.SSLException

/**
 * A request over HTTPS with the platform's own HttpURLConnection. The address is put together here
 * and nowhere else: `https://`, the [Host]'s domain, a path of plain characters, the encoded query.
 * A redirect is an answer (a status), never followed, so nothing can lead to a fourth host.
 *
 * Whatever goes wrong becomes a [Reply.Failed]. No exception leaves this class and none is logged:
 * the platform puts the address into exception messages, and an address can hold a key.
 */
class HttpTransport(
    /** Opens the connection for an address. Only tests pass one (to reach a server on this machine). */
    private val open: (String) -> HttpURLConnection = ::openChecked,
    private val connectMs: Int = Http.CONNECT_MS,
    private val readMs: Int = Http.READ_MS,
    private val maxBytes: Int = Http.MAX_BYTES,
    private val totalMs: Int = Http.TOTAL_MS,
) : Transport {

    override fun get(request: Request): Reply {
        val address = address(request, Http.key(request.host)) ?: return Reply.Failed(Why.REFUSED, sent = false)
        val deadline = System.nanoTime() + totalMs * 1_000_000L
        var connection: HttpURLConnection? = null
        return try {
            val c = open(address).also { connection = it }
            c.connectTimeout = minOf(connectMs, totalMs)
            c.readTimeout = minOf(readMs, totalMs)
            c.instanceFollowRedirects = false
            c.useCaches = false
            c.requestMethod = "GET"
            // Not the platform's default, which names the device model and the Android build.
            c.setRequestProperty("User-Agent", Http.USER_AGENT)
            c.setRequestProperty("Accept", "application/json")
            // Asked for by name and unpacked below, so Android and the JVM (the unit tests) behave alike.
            c.setRequestProperty("Accept-Encoding", "gzip")
            val status = c.responseCode
            if (status !in 200..299) return Reply.Failed(Why.STATUS, status, retryAfter(c))
            // Connecting and the headers can use up the whole allowance: an answer that took that long is given up too, even an empty one.
            if (System.nanoTime() - deadline > 0) throw SocketTimeoutException()
            val packed = c.contentEncoding.orEmpty().trim().equals("gzip", ignoreCase = true)
            val bytes = c.inputStream.let { if (packed) GZIPInputStream(it) else it }.use { read(it, deadline) }
                ?: return Reply.Failed(Why.TOO_LARGE, status)
            Reply.Ok(String(bytes, Charsets.UTF_8))
        } catch (t: Throwable) {
            Reply.Failed(why(t))
        } finally {
            try { connection?.disconnect() } catch (_: Throwable) {}
        }
    }

    /**
     * At most [maxBytes], or null if there is more: a reply that large is nobody's forecast. Each
     * read has its own time limit ([readMs]), and so has the whole: past [deadline] (System.nanoTime)
     * an answer that still trickles in is given up as a timeout, looked at before each read and after it.
     */
    private fun read(stream: InputStream, deadline: Long): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            if (System.nanoTime() - deadline > 0) throw SocketTimeoutException()
            val n = stream.read(buffer)
            if (n < 0) return out.toByteArray()
            if (out.size() + n > maxBytes) return null
            out.write(buffer, 0, n)
            if (System.nanoTime() - deadline > 0) throw SocketTimeoutException()
        }
    }

    private fun retryAfter(c: HttpURLConnection): Long? =
        c.getHeaderField("Retry-After")?.trim()?.toLongOrNull()?.takeIf { it in 0..86_400 }

    companion object {
        private val PATH = Regex("(/[A-Za-z0-9._~-]+)+")

        /**
         * The address of [request], or null if its path isn't a plain one: it must start with "/"
         * and hold only letters, digits and `. _ ~ -` between slashes (so no `//`, `@`, `?`, `#`,
         * backslash or space), and no segment that is exactly `.` or `..`. Dots anywhere else are
         * nothing: a date range like `/v1/2026-10-05..` is a plain segment too.
         * [key]: the user's own key, added under the name its host takes one by ([Host.keyName]), after the request's own query.
         */
        fun address(request: Request, key: String? = null): String? {
            if (!PATH.matches(request.path) || request.path.split('/').any { it == "." || it == ".." }) return null
            val own = request.query + listOfNotNull(request.host.keyName?.takeIf { !key.isNullOrEmpty() }?.let { it to key!! })
            val query = own.joinToString("&") { (name, value) -> encode(name) + "=" + encode(value) }
            return "https://" + request.host.domain + request.path + if (query.isEmpty()) "" else "?$query"
        }

        private fun encode(text: String): String = URLEncoder.encode(text, "UTF-8")

        /** The real opener: once more, only HTTPS and only BentoBar's hosts. Nothing is connected yet when it returns. */
        internal fun openChecked(address: String): HttpURLConnection {
            val url = URL(address)
            check(url.protocol == "https" && Host.entries.any { it.domain == url.host }) { "not one of BentoBar's hosts" }
            return url.openConnection() as HttpURLConnection
        }

        /** What went wrong, as one of a few kinds. The exception itself is dropped here. */
        internal fun why(t: Throwable): Why = when (t) {
            is SocketTimeoutException -> Why.TIMEOUT
            is UnknownHostException, is ConnectException, is NoRouteToHostException, is SocketException, is SSLException -> Why.OFFLINE
            else -> Why.UNREADABLE
        }
    }
}
