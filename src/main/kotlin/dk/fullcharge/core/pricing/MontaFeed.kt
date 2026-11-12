package dk.fullcharge.core.pricing

import dk.fullcharge.core.net.HttpTransport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.io.InputStream

/**
 * Denmark's Monta AFIR dataset (Danish NAP dataset 1092: Monta platform operators such as Norlys,
 * Monta, Uno-X, NRGi; CC BY 4.0), read through the Monta Public API with the owner's own free
 * application (hub.monta.app → Profile → Applications → clientId + clientSecret).
 *
 * - `POST /api/v1/auth/token` {clientId, clientSecret} → `accessToken` (valid ≈ 1 h), sent as
 *   `Authorization: Bearer …`.
 * - `GET /api/v1/afir/charge-points?country=DK&page=N&perPage=1000`: DATEX II table publication
 *   (sites, charge points, ad-hoc `electricEnergy.energyRate` prices), 1-based pages. Read until a
 *   page has fewer than [PER_PAGE] sites (the caller counts them) or [MAX_PAGES].
 * Source: docs.public-api.monta.com (OpenAPI, read 2026-11-12). Not yet checked with a real
 * response: the parser follows the published schema.
 */
class MontaFeed(private val http: HttpTransport, private val clientId: String, private val clientSecret: String) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Exchanges the client credentials for an access token. @throws IOException with Monta's message. */
    suspend fun accessToken(): String {
        val body = """{"clientId":${JsonPrimitive(clientId)},"clientSecret":${JsonPrimitive(clientSecret)}}"""
        val r = http.post("$BASE/api/v1/auth/token", body, mapOf("Content-Type" to "application/json", "Accept" to "application/json"))
        if (!r.isSuccess) {
            val msg = runCatching { (json.parseToJsonElement(r.body) as JsonObject)["message"]?.let { (it as JsonPrimitive).content } }.getOrNull()
            throw IOException("Monta login failed (HTTP ${r.statusCode}${msg?.let { ": $it" } ?: ""})")
        }
        return ((json.parseToJsonElement(r.body) as? JsonObject)?.get("accessToken") as? JsonPrimitive)?.content
            ?: throw IOException("Monta login: no accessToken in the response")
    }

    /**
     * Downloads pages into [sink] (streamed: a page of 1 000 sites with prices can be several MB).
     * [sink] returns how many sites the page held; reading stops after a short page.
     * @return pages written.
     */
    suspend fun downloadPages(country: String = "DK", sink: (page: Int, body: InputStream) -> Int): Int {
        val token = accessToken()
        var page = 1
        while (page <= MAX_PAGES) {
            var sites = 0
            val code = http.download(pageUrl(country, page), mapOf("Authorization" to "Bearer $token", "Accept" to "application/json")) { sites = sink(page, it) }
            if (code !in 200..299) throw IOException("Monta charge points page $page: HTTP $code")
            if (sites < PER_PAGE) return page
            page++
        }
        return MAX_PAGES
    }

    companion object {
        const val SOURCE = "DK · Monta"
        const val BASE = "https://public-api.monta.com"
        const val PER_PAGE = 1000
        const val MAX_PAGES = 30

        fun pageUrl(country: String, page: Int) = "$BASE/api/v1/afir/charge-points?country=$country&page=$page&perPage=$PER_PAGE"
    }
}
