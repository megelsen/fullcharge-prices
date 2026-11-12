package dk.fullcharge.core.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/**
 * HTTP response; [body] is also filled for non-2xx status codes (APIs put error details there).
 * [headers] keys are lower-case.
 */
data class HttpResponse(
    val statusCode: Int,
    val body: String,
    val headers: Map<String, List<String>> = emptyMap(),
) {
    val isSuccess: Boolean get() = statusCode in 200..299
    val isRedirect: Boolean get() = statusCode in 300..399

    /** First value of a header, case-insensitive. */
    fun header(name: String): String? = headers[name.lowercase()]?.firstOrNull()
}

/**
 * Minimal HTTP seam so API clients stay pure JVM and are tested with fakes.
 * Implementations throw [IOException] on network failure; callers map that to "unavailable".
 */
interface HttpTransport {
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): HttpResponse

    /** POST with a UTF-8 body (e.g. JSON; set Content-Type in [headers]). */
    suspend fun post(url: String, body: String, headers: Map<String, String> = emptyMap()): HttpResponse =
        throw UnsupportedOperationException("POST not supported by this transport")

    /**
     * GET with the body streamed to [sink] (only for 2xx; large responses such as AFIR feeds that
     * must not be held as one String). Returns the status code. A gzip response is decoded.
     */
    suspend fun download(url: String, headers: Map<String, String> = emptyMap(), sink: (java.io.InputStream) -> Unit): Int =
        throw UnsupportedOperationException("download not supported by this transport")
}

/**
 * [HttpTransport] on the platform `HttpURLConnection` (decision D1: no HTTP library dependency).
 * Works on Android and the JVM. Redirects are not followed (the share-link resolver needs the
 * `Location` header itself; API calls do not redirect).
 */
class UrlConnectionTransport(
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 15_000,
) : HttpTransport {

    override suspend fun get(url: String, headers: Map<String, String>): HttpResponse = request("GET", url, null, headers)

    override suspend fun post(url: String, body: String, headers: Map<String, String>): HttpResponse =
        request("POST", url, body, headers)

    override suspend fun download(url: String, headers: Map<String, String>, sink: (java.io.InputStream) -> Unit): Int = withContext(Dispatchers.IO) {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            conn.instanceFollowRedirects = false
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.setRequestProperty("Accept-Encoding", "gzip")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            val code = conn.responseCode
            if (code in 200..299) {
                // Android's HttpURLConnection decodes gzip itself only when it added the header; we set it, so decode here.
                val raw = conn.inputStream
                val gz = conn.contentEncoding?.equals("gzip", ignoreCase = true) == true
                (if (gz) java.util.zip.GZIPInputStream(raw) else raw).use(sink)
            } else {
                conn.errorStream?.close()
            }
            code
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun request(method: String, url: String, body: String?, headers: Map<String, String>): HttpResponse = withContext(Dispatchers.IO) {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.instanceFollowRedirects = false
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            val hdrs = conn.headerFields
                .filterKeys { it != null }
                .mapKeys { it.key.lowercase() }
            HttpResponse(code, body, hdrs)
        } finally {
            conn.disconnect()
        }
    }
}
