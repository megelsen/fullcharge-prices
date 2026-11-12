package dk.fullcharge.core.pricing

import dk.fullcharge.core.net.HttpResponse
import dk.fullcharge.core.net.HttpTransport
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Monta AFIR format. The fixture is SYNTHETIC, written from Monta's published OpenAPI schema
 * (docs.public-api.monta.com, 2026-11-12); replace it with a recorded response once available.
 */
class MontaFeedTest {
    private val cph = ZoneId.of("Europe/Copenhagen")
    private fun res(name: String) = javaClass.getResource("/afir/$name")!!.readText()

    @Test fun `monta layout parses (point location, charge point without wrapper, base price)`() {
        val sites = Datex2AfirParser(cph, MontaFeed.SOURCE).parseStreaming(java.io.StringReader(res("monta_schema_example.json")))
        val s = sites.single()
        assertEquals("Norlys", s.operator)
        assertEquals(56.1, s.latDeg, 1e-9)
        assertEquals("Hasselager Allé 1, Viby J", s.address)
        assertEquals(2, s.chargePoints.size)
        val dc = s.chargePoints.first { it.isDc }
        assertEquals(150.0, dc.maxPowerKw, 1e-9)
        assertEquals("DK*MON*E100001", dc.evseId)
        // 3.16 excl. VAT × 1.25 = 3.95; base price 4.00 flat; idle fee 1.00/min from minute 60.
        assertEquals(3.95, dc.prices.first { it.type == PriceType.PER_KWH }.valueInclVat, 1e-9)
        val t = LocalDateTime.of(2026, 11, 12, 12, 0).atZone(cph).toInstant().toEpochMilli()
        // 30 kWh in 70 min: 30 × 3.95 = 118.50 + 4.00 + 10 min idle × 1.00 = 132.50
        assertEquals(132.5, ChargeCost.estimate(dc, t, 70.0, 30.0, cph)!!.totalInclVat, 1e-9)
        assertEquals(listOf("site-77"), Datex2AfirParser(cph, "x").parseStreaming(java.io.StringReader(res("monta_schema_example.json")), dcOnly = true).map { it.id })
    }

    private class Fake(val pages: List<Int>, val loginCode: Int = 200) : HttpTransport {
        val calls = ArrayList<String>()
        var postBody = ""
        override suspend fun get(url: String, headers: Map<String, String>) = HttpResponse(500, "")
        override suspend fun post(url: String, body: String, headers: Map<String, String>): HttpResponse {
            calls += "POST $url"
            postBody = body
            return if (loginCode == 200) HttpResponse(200, """{"accessToken":"tok-1","refreshToken":"r"}""")
            else HttpResponse(loginCode, """{"status":"UNAUTHORIZED","message":"Application with given `clientId` and `clientSecret` not found"}""")
        }
        override suspend fun download(url: String, headers: Map<String, String>, sink: (InputStream) -> Unit): Int {
            calls += "GET $url ${headers["Authorization"]}"
            sink("{}".byteInputStream())
            return 200
        }
    }

    @Test fun `login then pages until a short page`() = runTest {
        val http = Fake(listOf(1000, 1000, 37))
        var n = 0
        val pages = MontaFeed(http, "id-1", "sec\"ret").downloadPages { _, _ -> listOf(1000, 1000, 37)[n++] }
        assertEquals(3, pages)
        assertEquals("POST https://public-api.monta.com/api/v1/auth/token", http.calls[0])
        assertEquals("""{"clientId":"id-1","clientSecret":"sec\"ret"}""", http.postBody) // JSON-escaped
        assertEquals("GET https://public-api.monta.com/api/v1/afir/charge-points?country=DK&page=1&perPage=1000 Bearer tok-1", http.calls[1])
        assertTrue(http.calls[3].contains("page=3"))
    }

    @Test fun `bad credentials give Monta's message`() = runTest {
        val e = runCatching { MontaFeed(Fake(emptyList(), 401), "x", "y").accessToken() }.exceptionOrNull()
        assertTrue(e is IOException)
        assertTrue(e!!.message!!.contains("not found"))
    }
}
