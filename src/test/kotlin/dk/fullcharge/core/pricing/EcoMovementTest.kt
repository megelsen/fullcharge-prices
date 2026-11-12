package dk.fullcharge.core.pricing

import dk.fullcharge.core.net.HttpResponse
import dk.fullcharge.core.net.HttpTransport
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import java.io.StringReader
import java.time.ZoneId

/** Recorded Eco-Movement response (2026-11-12, page 1, two public sites trimmed to 2 charge points each). */
class EcoMovementTest {
    private val cph = ZoneId.of("Europe/Copenhagen")
    private val text = javaClass.getResource("/afir/eco_movement_fixture.json")!!.readText()

    @Test fun `entrance position, energyDistributor operator, energyProduct prices`() {
        val sites = Datex2AfirParser(cph, EcoMovementFeed.SOURCE).parseStreaming(StringReader(text))
        assertEquals(2, sites.size)
        val ionity = sites.first { it.id == "IONITY-2073" }
        assertEquals("IONITY", ionity.operator)
        assertEquals(55.413867, ionity.latDeg, 1e-9)
        assertEquals(10.053992, ionity.lonDeg, 1e-9)
        val cp = ionity.chargePoints.first()
        assertTrue(cp.isDc)
        assertEquals(400.0, cp.maxPowerKw, 1e-9) // 400 000 W
        // 3.0877 DKK excl. VAT, taxRate 25 (percent) → × 1.25 = 3.859625 incl. VAT
        assertEquals(3.859625, cp.prices.single().valueInclVat, 1e-9)
        val clever = sites.first { it.operator == "Clever" }
        // 3.99 excl. VAT, taxRate 0.25 (fraction) → 4.9875 incl. VAT
        assertEquals(4.9875, clever.chargePoints.first().prices.single().valueInclVat, 1e-9)
        assertEquals(199.8, clever.maxDcPowerKw, 1e-9)
        // 40 kWh at IONITY Aarup: 40 × 3.859625 = 154.385 DKK
        assertEquals(154.385, ChargeCost.estimate(cp, 0L, 20.0, 40.0, cph)!!.totalInclVat, 1e-9)
    }

    private class Fake(val code: Int = 200) : HttpTransport {
        val urls = ArrayList<String>()
        val auth = ArrayList<String?>()
        override suspend fun get(url: String, headers: Map<String, String>) = HttpResponse(500, "")
        override suspend fun download(url: String, headers: Map<String, String>, sink: (InputStream) -> Unit): Int {
            urls += url; auth += headers["Authorization"]
            if (code == 200) sink("{}".byteInputStream())
            return code
        }
    }

    @Test fun `offset paging until a short page`() = runTest {
        val http = Fake()
        val counts = listOf(1000, 1000, 1000, 912)
        var i = 0
        assertEquals(4, EcoMovementFeed(http, "t").downloadPages { _, _ -> counts[i++] })
        assertEquals("https://nap-dk.eco-movement.com/datex2/v1/locations?limit=1000&offset=3000", http.urls[3])
        assertEquals("Bearer t", http.auth[0])
    }

    @Test fun `token refused`() = runTest {
        val e = runCatching { EcoMovementFeed(Fake(401), "t").downloadPages { _, _ -> 0 } }.exceptionOrNull()
        assertTrue(e is IOException && e.message!!.contains("token"))
    }
}
