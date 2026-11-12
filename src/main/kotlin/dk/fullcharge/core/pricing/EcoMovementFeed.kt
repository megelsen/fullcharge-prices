package dk.fullcharge.core.pricing

import dk.fullcharge.core.net.HttpTransport
import java.io.IOException
import java.io.InputStream

/**
 * Eco-Movement's Danish AFIR feed (Danish NAP dataset 1170, CC BY 4.0): Clever, IONITY, Circle K,
 * Q8, Lidl, Fastned, Eviny, Milence … with ad-hoc prices. Token from Eco-Movement (owner's, 2026-11-12),
 * sent as `Authorization: Bearer …`.
 *
 * `GET https://nap-dk.eco-movement.com/datex2/v1/locations?limit=1000&offset=N`: one DATEX II
 * document per page with the table publication (prices in `energyProduct[].aegiElectricEnergy`)
 * and a status publication (same prices; not needed). ≈ 9 MB JSON per 1 000 sites (checked
 * 2026-11-12). Pages until one has fewer than [PAGE_SIZE] sites (the caller counts them).
 */
class EcoMovementFeed(private val http: HttpTransport, private val token: String) {
    /** @return pages written. @throws IOException on HTTP errors (401 = token not accepted). */
    suspend fun downloadPages(sink: (page: Int, body: InputStream) -> Int): Int {
        var page = 0
        while (page < MAX_PAGES) {
            var sites = 0
            val code = http.download(pageUrl(page * PAGE_SIZE), mapOf("Authorization" to "Bearer $token", "Accept" to "application/json")) { sites = sink(page + 1, it) }
            if (code == 401 || code == 403) throw IOException("Eco-Movement refused the token (HTTP $code)")
            if (code !in 200..299) throw IOException("Eco-Movement page ${page + 1}: HTTP $code")
            page++
            if (sites < PAGE_SIZE) return page
        }
        return page
    }

    companion object {
        const val SOURCE = "DK · Eco-Movement"
        const val PAGE_SIZE = 1000
        const val MAX_PAGES = 20

        fun pageUrl(offset: Int) = "https://nap-dk.eco-movement.com/datex2/v1/locations?limit=$PAGE_SIZE&offset=$offset"
    }
}
