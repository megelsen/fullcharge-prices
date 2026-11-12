package dk.fullcharge.core.pricing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Germany: ladekrone tile 214_40 (Hamburg, generated 2026-11-12T09:00Z), 5 stations kept.
 * Switzerland: ev-money-saver-data prices-v2 / stations (2026-11-12), 6 EVSEs kept.
 */
class AbroadFeedsTest {
    private val zone = SpiriiFeed.ZONE // the planner's zone; Berlin and Zürich keep the same time
    private fun local(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) = ZonedDateTime.of(y, mo, d, h, mi, 0, 0, ZoneId.of("Europe/Berlin")).toInstant().toEpochMilli()

    private val de = LadekroneTiles.parseTile(javaClass.getResource("/ladekrone/tile_214_40_trimmed.json")!!.readText())
    private val chPrices = SwissPriceBundle.parsePrices(javaClass.getResource("/swiss/prices-v2_trimmed.json")!!.readText())
    private val ch = SwissPriceBundle.sites(javaClass.getResource("/swiss/stations_trimmed.json")!!.readText(), chPrices)

    @Test fun `tile keys`() {
        assertEquals("214_40", LadekroneTiles.tileKey(53.61827, 10.2391))
        assertEquals("-1_-1", LadekroneTiles.tileKey(-0.1, -0.1))
        // 150 m from a tile corner (53.5, 10.0) touches all four tiles.
        assertEquals(setOf("213_39", "213_40", "214_39", "214_40"), LadekroneTiles.tilesNear(53.5001, 10.0001, 150.0).toSet())
        assertEquals(listOf("214_40"), LadekroneTiles.tilesNear(53.62, 10.12, 150.0))
        val meta = LadekroneTiles.parseMeta("""{"v":1,"generated":"2026-11-12T09:00:42+00:00","tile_deg":0.25,"tiles":["214_40","190_35"]}""")!!
        assertEquals(1, meta.version)
        assertEquals(setOf("214_40", "190_35"), meta.tiles)
    }

    @Test fun `tesla time-of-day prices golden`() {
        val tesla = de.first { it.operator == "Tesla, Inc." }
        assertEquals(53.61827, tesla.latDeg, 0.0)
        assertEquals("DE · ladekrone (tesla)", tesla.source)
        val cp = tesla.chargePoints.single()
        assertTrue(cp.isDc)
        assertEquals(250.0, cp.maxPowerKw, 0.0)
        // From 2026-11-05T23:00Z: 0.54 00–04, 0.54 04–09, 0.62 09–19, 0.54 19–24 local; before: 0.52 all day.
        assertEquals(0.62, ChargeCost.perKwhAt(cp, local(2026, 11, 12, 10), zone)!!.valueInclVat, 0.0)
        assertEquals(0.54, ChargeCost.perKwhAt(cp, local(2026, 11, 12, 2), zone)!!.valueInclVat, 0.0)
        assertEquals(0.54, ChargeCost.perKwhAt(cp, local(2026, 11, 12, 23, 30), zone)!!.valueInclVat, 0.0)
        assertEquals(0.52, ChargeCost.perKwhAt(cp, local(2026, 11, 1, 10), zone)!!.valueInclVat, 0.0)
        assertEquals("EUR", ChargeCost.perKwhAt(cp, local(2026, 11, 12, 10), zone)!!.currency)
    }

    @Test fun `enbw per-minute fee from minute 30 golden`() {
        val cp = de.first { it.operator == "ENBW" }.chargePoints.single()
        // 40 kWh in 40 min: 40 × 0.79 + (40 − 30) × 0.24 = 31.60 + 2.40 = 34.00 EUR
        val c = ChargeCost.estimate(cp, local(2026, 11, 12, 10), 40.0, 40.0, zone)!!
        assertEquals(34.0, c.totalInclVat, 1e-9)
        assertEquals("EUR", c.currency)
    }

    @Test fun `zero fees, 'other' dropped, one rate per group, unpriced kept`() {
        val ladenetz = de.first { it.operator == "Ladenetz" }.chargePoints.single()
        assertEquals(listOf(PriceType.PER_KWH, PriceType.PER_MINUTE, PriceType.FLAT), ladenetz.prices.map { it.type })
        assertEquals(0.8568, ChargeCost.perKwhAt(ladenetz, local(2026, 11, 12, 10), zone)!!.valueInclVat, 0.0)
        // eRound AC group with two identical rates: one rate only → one 1.99 EUR base fee.
        val eround = de.first { it.source.endsWith("(eround)") }
        assertEquals(2, eround.chargePoints.size)
        assertFalse(eround.chargePoints.first().isDc)
        val c = ChargeCost.estimate(eround.chargePoints.first(), local(2026, 11, 12, 10), 60.0, 10.0, zone)!!
        assertEquals(10 * 0.5 + 1.99, c.totalInclVat, 1e-9)
        // Elli: no ad-hoc rate, no power → kept without prices; the matcher skips it.
        val elli = de.first { it.source.endsWith("(vw_group_charging)") }
        assertTrue(elli.chargePoints.single().prices.isEmpty())
        assertNull(AfirMatcher.match(listOf(elli), elli.latDeg, elli.lonDeg))
        assertEquals(5, de.size)
    }

    @Test fun `swiss prices per source and units golden`() {
        // GOFAST (Chargeprice): 0.70 CHF/kWh + 0.25 CHF/min from 3600 s = minute 60.
        assertEquals(
            listOf(PriceElement(PriceType.PER_KWH, 0.70, "CHF"), PriceElement(PriceType.PER_MINUTE, 0.25, "CHF", fromMinute = 60)),
            chPrices["CH*GFT*E*Aaron*1"],
        )
        // eCarUp: 3 CHF per hour from 14400 s → 0.05 CHF/min from minute 240.
        val ecu = chPrices.getValue("CH*ECUE22BXYH5N8ANK24P3TAUUWXZ2MW")
        assertEquals(0.05, ecu.single { it.type == PriceType.PER_MINUTE }.valueInclVat, 1e-12)
        assertEquals(240, ecu.single { it.type == PriceType.PER_MINUTE }.fromMinute)
        // AVIA VOLT: start fee 1.50 CHF.
        assertEquals(1.5, chPrices.getValue("CH*AVI*E10817").single { it.type == PriceType.FLAT }.valueInclVat, 0.0)
        // Electra: app tariff only → no direct price.
        assertNull(chPrices["CH*ELC*E23YQ"])
        assertEquals(4, chPrices.size)
    }

    @Test fun `swiss text accepts plain and gzip bodies`() {
        val json = "{\"EVSEData\":[]}"
        val bos = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(bos).use { it.write(json.toByteArray()) }
        assertEquals(json, SwissPriceBundle.text(bos.toByteArray()))
        assertEquals(json, SwissPriceBundle.text(json.toByteArray()))
    }

    @Test fun `swiss sites, positions, DC filter and cost golden`() {
        assertEquals(6, ch.size)
        val gofast = ch.first { it.operator == "GoFast" }
        assertEquals(47.13154924, gofast.latDeg, 1e-9)
        assertEquals(8.40392241, gofast.lonDeg, 1e-9)
        assertEquals("CH", gofast.countryCode)
        val cp = gofast.chargePoints.single()
        assertTrue(cp.isDc)
        assertEquals(300.0, cp.maxPowerKw, 0.0)
        // 30 kWh in 75 min: 30 × 0.70 + (75 − 60) × 0.25 = 21.00 + 3.75 = 24.75 CHF
        val c = ChargeCost.estimate(cp, local(2026, 11, 12, 10), 75.0, 30.0, zone)!!
        assertEquals(24.75, c.totalInclVat, 1e-9)
        assertEquals("CHF", c.currency)
        // IONITY is in the federal list but unpriced; matcher finds nothing there.
        val ionity = ch.first { it.operator == "IONITY" }
        assertNull(AfirMatcher.match(ch, ionity.latDeg, ionity.lonDeg))
        assertEquals(setOf("Electra", "GoFast", "IONITY", "M-Charge"), SwissPriceBundle.sites(
            javaClass.getResource("/swiss/stations_trimmed.json")!!.readText(), chPrices, dcOnly = true,
        ).mapNotNull { it.operator }.toSet())
        assertTrue(SwissPriceBundle.covers(47.13, 8.40))
        assertFalse(SwissPriceBundle.covers(53.6, 10.2))
    }
}
