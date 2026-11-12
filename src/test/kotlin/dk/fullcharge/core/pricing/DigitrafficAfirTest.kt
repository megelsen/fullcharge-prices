package dk.fullcharge.core.pricing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Finland: Digitraffic AFIR snapshots of 2026-11-12 (`/tariffs/all` 18:48Z, `/locations/all`
 * 18:47Z), trimmed to 9 locations and the 10 tariffs they reference, minus Wattery's
 * `FI*WTY*E11940*1-tariff` (left out on purpose: unresolved reference). VAT 25.5 %: × 1.255.
 */
class DigitrafficAfirTest {
    private val cph = SpiriiFeed.ZONE // the app's zone
    private val helsinki = ZoneId.of("Europe/Helsinki")
    private fun fi(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) = ZonedDateTime.of(y, mo, d, h, mi, 0, 0, helsinki).toInstant().toEpochMilli()

    private val tariffs = DigitrafficAfir.parseTariffs(javaClass.getResource("/digitraffic/tariffs_trimmed.json")!!.readText())
    private val result: DigitrafficAfir.Sites = javaClass.getResourceAsStream("/digitraffic/locations_trimmed.json")!!.reader().use { DigitrafficAfir.sites(it, tariffs) }
    private val sites: List<AfirSite> = result.sites
    private fun evse(evseId: String): AfirChargePoint = sites.flatMap { it.chargePoints }.single { it.evseId == evseId }

    @Test fun `only source-labelled ad-hoc tariffs, unsupported elements counted`() {
        assertEquals(10, tariffs.known.size)
        // Helen REGULAR and Tesla (no type) are not ad hoc.
        assertEquals(8, tariffs.adHoc.size)
        assertFalse(tariffs.adHoc.containsKey("FI*HLN*FIHLNTTRF639077933404301694"))
        assertFalse(tariffs.adHoc.containsKey("FI*TSL*26994fe9-bb0e-4b39-8e3b-50f0609a52a2"))
        // Neste Hartola: all 4 elements restricted by min/maxPower → dropped, no price.
        assertEquals(4, tariffs.skippedElements)
        assertEquals(0, tariffs.skippedTariffs)
        assertTrue(evse("FI*NES*E8246*4").prices.isEmpty())
        assertTrue(evse("FI*TSL*EA4TUOQ").prices.isEmpty())
        assertTrue(evse("FI*HLN*E18118902").prices.isEmpty())
        assertTrue(evse("FI*001*E271378").prices.isEmpty()) // Virta: connector without tariffIds
        assertEquals(setOf("FI*WTY*FI*WTY*E11940*1-tariff"), result.unresolvedTariffRefs)
    }

    @Test fun `sites, positions, power W to kW, DC filter`() {
        assertEquals(9, sites.size)
        val allego = sites.single { it.operator == "Allego" }
        assertEquals("fi:FI*ALL:e8756dcc-83e7-11f0-a2d0-42010aa400b8", allego.id)
        assertEquals(61.516327, allego.latDeg, 0.0) // GeoJSON [lon, lat]
        assertEquals(23.663525, allego.lonDeg, 0.0)
        assertEquals("FI", allego.countryCode) // address "FIN" → FI
        assertEquals("Taninkatu 2, 33400 Tampere", allego.address)
        assertEquals("FI · Fintraffic Digitraffic", allego.source)
        assertEquals(2, allego.chargePoints.size)
        assertTrue(allego.chargePoints.all { it.isDc })
        assertEquals(150.0, allego.maxDcPowerKw, 0.0) // maxElectricPower 150000 W
        assertEquals(22.0, evse("FI*PLG*E26278").maxPowerKw, 0.0) // 22000 W AC
        assertFalse(evse("FI*PLG*E26278").isDc)
        val dc = javaClass.getResourceAsStream("/digitraffic/locations_trimmed.json")!!.reader().use { DigitrafficAfir.sites(it, tariffs, dcOnly = true) }.sites
        assertEquals(setOf("Allego", "Plugit Finland Oy", "ABC", "K Auto Oy", "Neste MY Uusiutuva Lataus", "Tesla"), dc.mapNotNull { it.operator }.toSet())
        assertTrue(dc.flatMap { it.chargePoints }.all { it.isDc })
    }

    @Test fun `allego excl VAT energy, idle fee dropped, cost golden`() {
        val cp = evse("FI*ALL*EGO0010101")
        // ENERGY 0.476 excl., taxIncluded NO, vat 25.5: 0.476 × 1.255 = 0.59738 (alt text "0.60 EUR/kWh").
        // PARKING_TIME (idle fee 11.9522 /h after 2700 s) is not charging → dropped.
        assertEquals(listOf(PriceType.PER_KWH), cp.prices.map { it.type })
        assertEquals(0.59738, cp.prices.single().valueInclVat, 1e-12)
        assertEquals("EUR", cp.prices.single().currency)
        // 40 kWh in 30 min: 40 × 0.59738 = 23.8952 EUR
        val c = ChargeCost.estimate(cp, fi(2026, 11, 12, 12), 30.0, 40.0, cph)!!
        assertEquals(23.8952, c.totalInclVat, 1e-9)
        assertEquals("EUR", c.currency)
        val m = AfirMatcher.match(sites, 61.516327, 23.663525)!!
        assertEquals("Allego", m.site.operator)
        assertEquals(150.0, m.chargePoint.maxPowerKw, 0.0)
    }

    @Test fun `plugit incl VAT windows shifted to Copenhagen time, cost golden`() {
        val cp = evse("FI*PLG*E25239")
        // taxIncluded YES: 0.35 (07–21 Helsinki) and 0.32 (21–07) as published.
        // Helsinki 07:00 = Copenhagen 06:00 → windows (360, 1200) and (1200, 360).
        val kwh = cp.prices.filter { it.type == PriceType.PER_KWH }
        assertEquals(listOf(360 to 1200, 1200 to 360), kwh.map { it.windowsMinOfDay.single() })
        assertEquals(0.35, ChargeCost.perKwhAt(cp, fi(2026, 11, 12, 7, 30), cph)!!.valueInclVat, 0.0)
        assertEquals(0.32, ChargeCost.perKwhAt(cp, fi(2026, 11, 12, 6, 30), cph)!!.valueInclVat, 0.0)
        assertEquals(0.35, ChargeCost.perKwhAt(cp, fi(2026, 11, 12, 20, 59), cph)!!.valueInclVat, 0.0)
        assertEquals(0.32, ChargeCost.perKwhAt(cp, fi(2026, 11, 12, 21, 0), cph)!!.valueInclVat, 0.0)
        // Summer (EEST/CEST): the same 60 min difference.
        assertEquals(0.35, ChargeCost.perKwhAt(cp, fi(2026, 7, 1, 7, 0), cph)!!.valueInclVat, 0.0)
        assertEquals(0.32, ChargeCost.perKwhAt(cp, fi(2026, 7, 1, 6, 59), cph)!!.valueInclVat, 0.0)
        // TIME 12 /h incl. VAT from 10800 s → 0.20 /min from minute 180, in both windows.
        val time = cp.prices.filter { it.type == PriceType.PER_MINUTE }
        assertEquals(listOf(180, 180), time.map { it.fromMinute })
        assertEquals(0.2, time.first().valueInclVat, 1e-12)
        // 60 kWh in 200 min from 20:30 Helsinki (0.3 kWh/min):
        // 30 min × 0.3 × 0.35 = 3.15; 170 min × 0.3 × 0.32 = 16.32; (200 − 180) × 0.20 = 4.00 → 23.47 EUR
        val c = ChargeCost.estimate(cp, fi(2026, 11, 12, 20, 30), 200.0, 60.0, cph)!!
        assertEquals(23.47, c.totalInclVat, 1e-9)
        assertEquals(4.0, c.timeFeeInclVat, 1e-9)
        assertEquals(0.35, c.startPerKwh, 0.0)
    }

    @Test fun `abc windowed time fee, kesko validity, wattery flat golden`() {
        // ABC DC: 0.247 × 1.255 = 0.309985 (07–20 Helsinki = 06–19 Copenhagen), 0.2231 × 1.255 = 0.2799905;
        // TIME 9.5618 /h × 1.255 / 60 = 0.20000098 /min from 5400 s = minute 90; PARKING_TIME dropped.
        val abc = evse("FI*ABC*E0222*1012*1")
        assertEquals(0.309985, ChargeCost.perKwhAt(abc, fi(2026, 11, 12, 19, 59), cph)!!.valueInclVat, 1e-12)
        assertEquals(0.2799905, ChargeCost.perKwhAt(abc, fi(2026, 11, 12, 20, 0), cph)!!.valueInclVat, 1e-12)
        val t = abc.prices.single { it.type == PriceType.PER_MINUTE }
        assertEquals(9.5618 * 1.255 / 60, t.valueInclVat, 1e-12)
        assertEquals(90, t.fromMinute)
        assertTrue(t.windowsMinOfDay.isEmpty())
        // ABC AC: the TIME element is windowed 07–20 Helsinki → (360, 1140) Copenhagen, from minute 120.
        val abcAc = evse("FI*ABC*E0222*1012*8").prices.single { it.type == PriceType.PER_MINUTE }
        assertEquals(listOf(360 to 1140), abcAc.windowsMinOfDay)
        assertEquals(120, abcAc.fromMinute)

        // K-lataus DC 100 kW: 0.3586 × 1.255 = 0.450043 /kWh; TIME 9.564 × 1.255 / 60 = 0.200047 /min from minute 60.
        // 30 kWh in 70 min: 30 × 0.450043 + 10 × 0.200047 = 13.50129 + 2.00047 = 15.50176 EUR
        val k = evse("FI*KCH*E12237")
        assertEquals(100.0, k.maxPowerKw, 0.0)
        assertEquals(15.50176, ChargeCost.estimate(k, fi(2026, 11, 12, 12), 70.0, 30.0, cph)!!.totalInclVat, 1e-9)
        // startDateTime 2026-06-11T21:00:00Z: no price before.
        assertNull(ChargeCost.perKwhAt(k, fi(2026, 6, 11, 23, 59), cph))
        assertEquals(0.450043, ChargeCost.perKwhAt(k, fi(2026, 6, 12, 0, 1), cph)!!.valueInclVat, 1e-12)

        // Wattery AC: 0.2789 × 1.255 = 0.3500195 /kWh + FLAT 0.1992 × 1.255 = 0.249996 once.
        // 10 kWh in 60 min: 3.500195 + 0.249996 = 3.750191 EUR
        val w = evse("FI*WTY*E11939*1")
        assertEquals(3.750191, ChargeCost.estimate(w, fi(2026, 11, 12, 12), 60.0, 10.0, cph)!!.totalInclVat, 1e-9)
        assertTrue(evse("FI*WTY*E11940*1").prices.isEmpty()) // unresolved tariff
    }

    @Test fun `helsinki to copenhagen windows with weekdays`() {
        // Synthetic (no ad-hoc tariff in the 2026-11-12 data has dayOfWeek).
        // No weekdays: 20:00–07:00 → 19:00–06:00.
        assertEquals(listOf(listOf(1140 to 360) to emptySet<DayOfWeek>()), DigitrafficAfir.toCopenhagen(listOf(1200 to 420), emptySet()))
        // Monday 00:30–06:00 Helsinki = Sunday 23:30–24:00 + Monday 00:00–05:00 Copenhagen.
        assertEquals(
            setOf(listOf(1410 to 0) to setOf(DayOfWeek.SUNDAY), listOf(0 to 300) to setOf(DayOfWeek.MONDAY)),
            DigitrafficAfir.toCopenhagen(listOf(30 to 360), setOf(DayOfWeek.MONDAY)).toSet(),
        )
        // All of Saturday Helsinki = Friday 23:00–24:00 + Saturday 00:00–23:00 Copenhagen.
        assertEquals(
            setOf(listOf(1380 to 0) to setOf(DayOfWeek.FRIDAY), listOf(0 to 1380) to setOf(DayOfWeek.SATURDAY)),
            DigitrafficAfir.toCopenhagen(emptyList(), setOf(DayOfWeek.SATURDAY)).toSet(),
        )
        // Mon and Tue 22:00–02:00 Helsinki: pieces Mon/Tue 22–24 and Tue/Wed 00–02, each −60 min →
        // Mon/Tue 21:00–23:00, Mon/Tue 23:00–24:00 (from Tue/Wed 00–01) and Tue/Wed 00:00–01:00 Copenhagen.
        assertEquals(
            setOf(
                listOf(1260 to 1380) to setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY),
                listOf(1380 to 0) to setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY),
                listOf(0 to 60) to setOf(DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY),
            ),
            DigitrafficAfir.toCopenhagen(listOf(1320 to 120), setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY)).toSet(),
        )
    }
}
