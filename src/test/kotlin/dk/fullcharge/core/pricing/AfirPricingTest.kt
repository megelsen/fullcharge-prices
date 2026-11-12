package dk.fullcharge.core.pricing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Golden tests on a recorded Spirii AFIR response (public chargers, 2026-11-11 15:13 UTC):
 * site-1052 (AC 22 kW, dynamic prices + parking fee) and site-1075 (DC 50 kW, dynamic prices).
 * Expected values read from the JSON by hand / an independent script.
 */
class AfirPricingTest {
    private val cph = ZoneId.of("Europe/Copenhagen")
    private fun res(name: String) = javaClass.getResource("/afir/$name")!!.readText()
    private val sites = Datex2AfirParser(cph, "DK · Spirii").parse(res("spirii_tables_fixture.json"), res("spirii_statuses_fixture.json"))
    private val ac = sites.first { it.id == "site-1052" }.chargePoints.single()
    private val dc = sites.first { it.id == "site-1075" }.chargePoints.single()
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) = LocalDateTime.of(y, mo, d, h, mi).atZone(cph).toInstant().toEpochMilli()

    @Test fun `sites and charge points`() {
        assertEquals(2, sites.size)
        val s = sites.first { it.id == "site-1075" }
        assertEquals("Spirii ApS", s.operator)
        assertEquals(55.276207, s.latDeg, 1e-6)
        assertEquals(14.804908, s.lonDeg, 1e-6)
        assertEquals("DK", s.countryCode)
        assertTrue(dc.isDc)
        assertEquals(50.0, dc.maxPowerKw, 1e-9) // maxPowerAtSocket 50 000 W
        assertEquals("DK.SPI.H050*1", dc.evseId)
        assertEquals(22.0, ac.maxPowerKw, 1e-9)
        assertEquals(50.0, s.maxDcPowerKw, 1e-9)
    }

    @Test fun `dynamic slot prices in local time`() {
        assertEquals(6.66, ChargeCost.perKwhAt(dc, at(2026, 11, 11, 17, 5), cph)!!.valueInclVat, 1e-9)
        assertEquals(6.97, ChargeCost.perKwhAt(dc, at(2026, 11, 11, 17, 20), cph)!!.valueInclVat, 1e-9)
        // Next day's slots (period starting 2026-11-12T00:00Z).
        assertEquals(4.74, ChargeCost.perKwhAt(dc, at(2026, 11, 12, 0, 5), cph)!!.valueInclVat, 1e-9)
        // No slot published for that day: the all-day ad-hoc price.
        assertEquals(2.68, ChargeCost.perKwhAt(dc, at(2026, 11, 20, 12, 0), cph)!!.valueInclVat, 1e-9)
    }

    @Test fun `golden DC charge across two slots`() {
        // 30 kWh in 30 min from 17:00: 15 kWh × 6.66 + 15 kWh × 6.97 = 99.90 + 104.55 = 204.45 DKK.
        val c = ChargeCost.estimate(dc, at(2026, 11, 11, 17, 0), 30.0, 30.0, cph)!!
        assertEquals(204.45, c.totalInclVat, 1e-9)
        assertEquals("DKK", c.currency)
        assertEquals(6.66, c.startPerKwh, 1e-9)
        assertEquals(6.815, c.meanPerKwh, 1e-9)
        assertEquals(0.0, c.timeFeeInclVat, 1e-9)
        assertEquals(0, c.minutesWithoutPrice)
    }

    @Test fun `golden charge across midnight`() {
        // 10 kWh in 30 min from 23:45: 5 × 4.60 + 5 × 4.74 = 46.70 DKK.
        val c = ChargeCost.estimate(dc, at(2026, 11, 11, 23, 45), 30.0, 10.0, cph)!!
        assertEquals(46.70, c.totalInclVat, 1e-9)
    }

    @Test fun `golden AC parking fee after 240 minutes on a weekday`() {
        // Friday 2026-11-13 from 10:00 for 300 min, 30 kWh: no dynamic slot that day → 2.00 DKK/kWh = 60.00;
        // parking 0.25 DKK/min from minute 240 (14:00–15:00, inside 06:00–17:00) = 60 × 0.25 = 15.00. Total 75.00.
        val c = ChargeCost.estimate(ac, at(2026, 11, 13, 10, 0), 300.0, 30.0, cph)!!
        assertEquals(60.0 + 15.0, c.totalInclVat, 1e-9)
        assertEquals(15.0, c.timeFeeInclVat, 1e-9)
    }

    @Test fun `no parking fee on a Saturday`() {
        val c = ChargeCost.estimate(ac, at(2026, 11, 14, 10, 0), 300.0, 30.0, cph)!!
        assertEquals(60.0, c.totalInclVat, 1e-9)
    }

    @Test fun `price bars every 15 minutes`() {
        val bars = ChargeCost.slots(dc, at(2026, 11, 11, 17, 0), at(2026, 11, 11, 18, 0), cph)
        assertEquals(4, bars.size)
        assertEquals(6.66, bars[0].second!!, 1e-9)
        assertEquals(6.97, bars[1].second!!, 1e-9)
    }

    @Test fun `vat added when not included`() {
        val json = """{"aegiEnergyInfrastructureTablePublication":{"energyInfrastructureTable":[{"energyInfrastructureSite":[{"idG":"s","locationReference":{"locAreaLocation":{"coordinatesForDisplay":{"latitude":56.0,"longitude":10.0}}},"energyInfrastructureStation":[{"refillPoint":[{"aegiElectricChargingPoint":{"idG":"cp","currentType":{"value":"dc"},"connector":[{"maxPowerAtSocket":150000}],"electricEnergy":[{"energyRate":[{"idG":"r","ratePolicy":{"value":"adHoc"},"applicableCurrency":["DKK"],"energyPrice":[{"priceType":{"value":"pricePerKWh"},"value":3.0,"taxIncluded":false,"taxRate":0.25}]},{"idG":"r2","ratePolicy":{"value":"adHoc"},"applicableCurrency":["DKK"],"energyPrice":[{"priceType":{"value":"flatRate"},"value":4.0,"taxIncluded":false,"taxRate":25}]}]}]}}]}]}]}]}}"""
        val cp = Datex2AfirParser(cph, "test").parse(json, null).single().chargePoints.single()
        assertEquals(150.0, cp.maxPowerKw, 1e-9)
        assertEquals(3.75, cp.prices.first { it.type == PriceType.PER_KWH }.valueInclVat, 1e-9) // 3.00 × 1.25 (fraction)
        assertEquals(5.0, cp.prices.first { it.type == PriceType.FLAT }.valueInclVat, 1e-9) // 4.00 × 1.25 (percent)
    }

    @Test fun `zero price counts as no price`() {
        val cp = AfirChargePoint("cp", null, true, 150.0, listOf(PriceElement(PriceType.PER_KWH, 0.0, "DKK")))
        assertNull(ChargeCost.perKwhAt(cp, 0L, cph))
        assertNull(ChargeCost.estimate(cp, 0L, 20.0, 30.0, cph))
        assertNull(AfirMatcher.match(listOf(AfirSite("s", null, 56.0, 10.0, null, "DK", listOf(cp), "x")), 56.0, 10.0))
    }

    @Test fun `matching a planner charger`() {
        // 100 m north of site-1075: matched; 400 m away: not.
        val m = AfirMatcher.match(sites, 55.276207 + 100.0 / 111_195.0, 14.804908)
        assertNotNull(m)
        assertEquals("site-1075", m!!.site.id)
        assertEquals(100.0, m.distanceM, 1.0)
        assertNull(AfirMatcher.match(sites, 55.276207 + 400.0 / 111_195.0, 14.804908))
        // AC only at site-1052: no DC match there.
        assertNull(AfirMatcher.match(sites, 55.715244, 12.425135))
    }
}

class AfirFilterAndFeedTest {
    private val cph = ZoneId.of("Europe/Copenhagen")
    private fun res(name: String) = javaClass.getResource("/afir/$name")!!.readText()

    @Test fun `dc only drops AC sites and AC points`() {
        val s = Datex2AfirParser(cph, "x").parse(res("spirii_tables_fixture.json"), null, dcOnly = true)
        assertEquals(listOf("site-1075"), s.map { it.id })
        assertTrue(s.single().chargePoints.all { it.isDc })
    }

    @Test fun `position filter`() {
        val s = Datex2AfirParser(cph, "x").parse(res("spirii_tables_fixture.json"), null) { lat, _ -> lat > 55.5 }
        assertEquals(listOf("site-1052"), s.map { it.id })
    }

    @Test fun `feed downloads one operator at a time and skips failures`() = kotlinx.coroutines.test.runTest {
        val urls = ArrayList<String>()
        val http = object : dk.fullcharge.core.net.HttpTransport {
            override suspend fun get(url: String, headers: Map<String, String>) = dk.fullcharge.core.net.HttpResponse(500, "")
            override suspend fun download(url: String, headers: Map<String, String>, sink: (java.io.InputStream) -> Unit): Int {
                urls += url
                if (url.endsWith("cpoId=4")) return 500
                sink("{}".byteInputStream())
                return 200
            }
        }
        val errors = ArrayList<Int>()
        val got = ArrayList<Int>()
        val ok = SpiriiFeed(http, listOf(1, 4, 7)).downloadTables({ id, _ -> got += id }) { id, _ -> errors += id }
        assertEquals(listOf(1, 7), ok)
        assertEquals(listOf(1, 7), got)
        assertEquals(listOf(4), errors)
        assertEquals("https://api.spirii.com/v2/afir/energy-infrastructure-tables?countryIsos=DK&cpoId=1", urls[0])
    }

    @Test fun `streaming parse equals the in-memory parse`() {
        val text = res("spirii_tables_fixture.json")
        val a = Datex2AfirParser(cph, "x").parse(text, null)
        val b = Datex2AfirParser(cph, "x").parseStreaming(java.io.StringReader(text))
        assertEquals(a, b)
        assertEquals(listOf("site-1075"), Datex2AfirParser(cph, "x").parseStreaming(java.io.StringReader(text), dcOnly = true).map { it.id })
    }

    @Test fun `array streamer handles strings with brackets and escapes`() {
        val json = """{"x":{"energyInfrastructureSite":[{"a":"]}[{\"q"},{"b":[1,2,{"c":"}"}]}]},"energyInfrastructureSite":[{"d":1}]}"""
        val got = ArrayList<String>()
        JsonArrayStreamer.forEachElement(java.io.StringReader(json), "energyInfrastructureSite") { got += it }
        assertEquals(listOf("""{"a":"]}[{\"q"}""", """{"b":[1,2,{"c":"}"}]}""", """{"d":1}"""), got)
    }
}
