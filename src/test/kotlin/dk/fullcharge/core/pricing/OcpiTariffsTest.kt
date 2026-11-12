package dk.fullcharge.core.pricing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader
import java.time.LocalDate
import java.time.ZonedDateTime

/**
 * NDW DOT-NL OCPI 2.2.1 files of 2026-11-12 (locations 18:45 UTC, tariffs 18:30 UTC), trimmed:
 * 9 locations (≤ 2 EVSEs each, bulky fields removed), the 8 tariffs they reference that exist
 * (PowerGo cut to its first 5 slots, E-Flux to 4 hourly slots + its default).
 */
class OcpiTariffsTest {
    private val zone = OcpiTariffs.NDW_ZONE
    private fun local(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) = ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone).toInstant().toEpochMilli()

    private val tariffs = OcpiTariffs.parseTariffs(javaClass.getResource("/ndw/tariffs_trimmed.json")!!.readText(), zone)
    private val locationsText = javaClass.getResource("/ndw/locations_trimmed.json")!!.readText()
    private val nl = OcpiTariffs.sites(locationsText, tariffs, OcpiTariffs.NDW_SOURCE, "NL")
    private fun site(id: String) = nl.single { it.id.endsWith(":$id") }
    private fun kwh(cp: AfirChargePoint, ts: Long) = ChargeCost.perKwhAt(cp, ts, zone)?.valueInclVat

    @Test fun `sites, positions, power and ids`() {
        assertEquals(9, nl.size)
        assertEquals(8, tariffs.size)
        val electra = site("NL*ELD*PMAADH")
        assertEquals("ocpi:NL:ELD:NL*ELD*PMAADH", electra.id)
        assertEquals("Electra", electra.operator)
        assertEquals(50.8165440, electra.latDeg, 0.0) // "50.8165440" (string) → degrees
        assertEquals(5.7261640, electra.lonDeg, 0.0)
        assertEquals("Slakweg 5, 6228 Maastricht", electra.address)
        assertEquals("NL", electra.countryCode) // country "NLD" → ISO alpha-2
        assertEquals("NL · NDW DOT-NL", electra.source)
        assertEquals(listOf("NL*ELD*E33JB", "NL*ELD*ESTV3"), electra.chargePoints.map { it.id })
        assertTrue(electra.chargePoints.all { it.isDc })
        assertEquals(200.0, electra.maxDcPowerKw, 0.0) // max_electric_power 200 000 W
        // Qwello AC_3_PHASE: max_electric_power 17 250 W = 230 V × 25 A × 3 phases.
        val qwello = site("02181eed-b7fe-4cc7-b454-6cd3f2464500").chargePoints.first()
        assertEquals(false, qwello.isDc)
        assertEquals(17.25, qwello.maxPowerKw, 0.0)
        // Tesla: tariff and location carry country_code "US" (party TSL); the site's country is "NLD".
        assertEquals("NL", site("7e5c7726-97b4-4584-b993-fba4a27258c8").countryCode)
    }

    @Test fun `DC energy plus time fee from minute 60 golden (Electra)`() {
        val cp = site("NL*ELD*PMAADH").chargePoints.single { it.id == "NL*ELD*ESTV3" }
        // ENERGY 0.5702 excl. @ 21 % → 0.5702 × 1.21 = 0.689942 EUR/kWh
        assertEquals(0.689942, cp.prices.single { it.type == PriceType.PER_KWH }.valueInclVat, 1e-9)
        // TIME 19.8347 EUR/h excl. @ 21 % → 23.999987 EUR/h → / 60 = 0.39999978 EUR/min, min_duration 3600 s → minute 60.
        val time = cp.prices.single { it.type == PriceType.PER_MINUTE }
        assertEquals(19.8347 * 1.21 / 60.0, time.valueInclVat, 1e-12)
        assertEquals(0.4, time.valueInclVat, 1e-6)
        assertEquals(60, time.fromMinute)
        assertEquals(2, cp.prices.size) // PARKING_TIME dropped
        // 40 kWh in 75 min: 40 × 0.689942 + (75 − 60) × 0.39999978 = 27.59768 + 5.99999675 = 33.59767675 EUR
        val c = ChargeCost.estimate(cp, local(2026, 11, 12, 10), 75.0, 40.0, zone)!!
        assertEquals(33.59767675, c.totalInclVat, 1e-8)
        assertEquals("EUR", c.currency)
        // Same site, other EVSE: its tariff 58d22428-… is not in the tariffs file → kept, unpriced.
        assertTrue(site("NL*ELD*PMAADH").chargePoints.single { it.id == "NL*ELD*E33JB" }.prices.isEmpty())
        assertEquals("NL*ELD*ESTV3", AfirMatcher.match(nl, 50.8165440, 5.7261640)!!.chargePoint.id)
    }

    @Test fun `AC ad-hoc tariff with explicit 0 % VAT golden (Qwello)`() {
        val cp = site("02181eed-b7fe-4cc7-b454-6cd3f2464500").chargePoints.first()
        // type AD_HOC_PAYMENT; ENERGY 0.55 @ vat 0.0 → 0.55; TIME 0.25 EUR/h → 0.25 / 60 EUR/min.
        assertEquals(0.55, kwh(cp, local(2026, 11, 12, 10))!!, 0.0)
        assertEquals(0.25 / 60.0, cp.prices.single { it.type == PriceType.PER_MINUTE }.valueInclVat, 1e-15)
        // start_date_time 2020-01-01T00:00:00Z
        assertEquals(1_577_836_800_000L, cp.prices.first().validFromEpochMs)
        // 10 kWh in 60 min: 10 × 0.55 + 60 × 0.25 / 60 = 5.50 + 0.25 = 5.75 EUR
        assertEquals(5.75, ChargeCost.estimate(cp, local(2026, 11, 12, 10), 60.0, 10.0, zone)!!.totalInclVat, 1e-9)
    }

    @Test fun `time-of-day prices with default and tariff start golden (Tesla)`() {
        val cp = site("7e5c7726-97b4-4584-b993-fba4a27258c8").chargePoints.first()
        assertTrue(cp.isDc)
        assertEquals(250.0, cp.maxPowerKw, 0.0)
        // vat null → no VAT applicable: prices as published. All seven day_of_week → no day restriction.
        assertTrue(cp.prices.all { it.days.isEmpty() })
        assertEquals(0.56, kwh(cp, local(2026, 11, 12, 10))!!, 0.0) // 09:00–22:00
        assertEquals(0.41, kwh(cp, local(2026, 11, 12, 3))!!, 0.0) // 00:00–04:00
        assertEquals(0.41, kwh(cp, local(2026, 11, 12, 23, 30))!!, 0.0) // 22:00–00:00 (end 00:00 = midnight)
        // Tariff start_date_time 2026-11-05T23:00:00Z: nothing before.
        assertNull(kwh(cp, local(2026, 11, 1, 10)))
        // 30 kWh 21:30–22:30: 15 kWh × 0.56 + 15 kWh × 0.41 = 8.40 + 6.15 = 14.55 EUR
        assertEquals(14.55, ChargeCost.estimate(cp, local(2026, 11, 12, 21, 30), 60.0, 30.0, zone)!!.totalInclVat, 1e-9)
    }

    @Test fun `first unrestricted element closes its dimension (E-ON tariff 431)`() {
        val cp = site("2665").chargePoints.first()
        // Element 1: TIME 0.00 with min_duration 0 (= no restriction) → TIME fixed at 0;
        // element 2's TIME 1.4876 EUR/h is unreachable under OCPI first-match. ENERGY 0.5372 × 1.21 = 0.650012.
        assertEquals(listOf(PriceType.PER_KWH), cp.prices.map { it.type })
        assertEquals(0.650012, cp.prices.single().valueInclVat, 1e-9)
        assertEquals(50.0, cp.maxPowerKw, 0.0)
    }

    @Test fun `vat given as fraction and start_date validity (TotalEnergies)`() {
        val p = site("3813bf42-4dbe-4743-96d6-560e053b8b6e").chargePoints.first().prices.single()
        // 0.5868 with vat 0.21 (read as 21 %): 0.5868 × 1.21 = 0.710028 EUR/kWh
        assertEquals(0.710028, p.valueInclVat, 1e-9)
        // start_date 2026-08-10 local → 2026-08-10T00:00+02:00 = 2026-08-09T22:00:00Z
        assertEquals(ZonedDateTime.of(2026, 8, 10, 0, 0, 0, 0, zone).toInstant().toEpochMilli(), p.validFromEpochMs)
        assertNull(p.validToEpochMs)
        assertEquals(300.0, site("3813bf42-4dbe-4743-96d6-560e053b8b6e").maxDcPowerKw, 0.0)
    }

    @Test fun `dynamic slots golden (PowerGo 15 min, E-Flux hourly)`() {
        val pg = site("15041").chargePoints.first()
        // 0.79 @ 21 % = 0.9559, 01:15–20:30 on 2026-11-11 (end_date 11-12 exclusive) → dayLocal 11-11.
        assertEquals(LocalDate.of(2026, 11, 11), pg.prices.first().dayLocal)
        assertEquals(0.79 * 1.21, kwh(pg, local(2026, 11, 11, 12))!!, 1e-12)
        // 05:30–05:45 slot 0.86 on 11-11 overlaps the earlier 0.79 element: OCPI first match → 0.9559.
        assertEquals(0.79 * 1.21, kwh(pg, local(2026, 11, 11, 5, 35))!!, 1e-12)
        // 11-12 06:00–06:15 slot (start_date = end_date, read as that day): 0.82 × 1.21 = 0.9922
        assertEquals(0.9922, kwh(pg, local(2026, 11, 12, 6, 5))!!, 1e-9)
        assertNull(kwh(pg, local(2026, 11, 12, 12)))
        assertNull(kwh(pg, local(2026, 11, 11, 0, 30)))

        val ef = site("60b4d35c2caf1afda5c45a65").chargePoints.first()
        assertEquals(11.04, ef.maxPowerKw, 0.0) // 11 040 W
        assertEquals(0.4953, kwh(ef, local(2026, 11, 11, 0, 30))!!, 0.0)
        assertEquals(0.4947, kwh(ef, local(2026, 11, 12, 0, 30))!!, 0.0)
        assertEquals(0.7923, kwh(ef, local(2026, 11, 12, 12))!!, 0.0) // unrestricted default
        // 10 kWh 00:30–01:30 on 11-12: 5 × 0.4947 + 5 × 0.4896 = 2.4735 + 2.4480 = 4.9215 EUR (FLAT/TIME 0 dropped)
        assertEquals(4.9215, ChargeCost.estimate(ef, local(2026, 11, 12, 0, 30), 60.0, 10.0, zone)!!.totalInclVat, 1e-9)
    }

    @Test fun `missing tariff and REGULAR-only connectors`() {
        val missing = site("60dd93856c690668cabe9a68")
        assertTrue(missing.chargePoints.single().prices.isEmpty())
        assertNull(AfirMatcher.match(listOf(missing), missing.latDeg, missing.lonDeg, dc = false))
        // Vattenfall tags its only tariff REGULAR (RFID per OCPI): not ad hoc by default.
        assertTrue(site("114817b0b0bc15b09028d4f020d87e86").chargePoints.all { it.prices.isEmpty() })
        val withRegular = OcpiTariffs.sites(locationsText, tariffs, OcpiTariffs.NDW_SOURCE, "NL", acceptRegular = true)
            .single { it.id.endsWith(":114817b0b0bc15b09028d4f020d87e86") }
        // 0.64 @ 21 % = 0.7744 EUR/kWh from 2025-02-01
        assertEquals(0.7744, kwh(withRegular.chargePoints.first(), local(2026, 11, 12, 10))!!, 1e-9)
        assertEquals(120.0, withRegular.maxDcPowerKw, 0.0)
    }

    @Test fun `dcOnly keeps DC sites`() {
        val dc = OcpiTariffs.sites(locationsText, tariffs, OcpiTariffs.NDW_SOURCE, "NL", dcOnly = true)
        assertEquals(setOf("Electra", "Tesla", "E.ON Drive", "TotalEnergies", "PowerGo", "Vattenfall InCharge"), dc.mapNotNull { it.operator }.toSet())
        assertEquals(6, dc.size)
    }

    @Test fun `connector power fallbacks`() {
        fun w(s: String) = OcpiTariffs.powerW(Json.parseToJsonElement(s).jsonObject)
        assertEquals(11_040.0, w("""{"power_type":"AC_3_PHASE","max_voltage":230,"max_amperage":16,"max_electric_power":null}"""), 0.0) // 230 × 16 × 3
        assertEquals(400.0 * 32 * Math.sqrt(3.0), w("""{"power_type":"AC_3_PHASE","max_voltage":400,"max_amperage":32}"""), 1e-9) // line-to-line
        assertEquals(300_000.0, w("""{"power_type":"DC","max_voltage":1000,"max_amperage":300}"""), 0.0)
        assertEquals(3_680.0, w("""{"power_type":"AC_1_PHASE","max_voltage":230,"max_amperage":16,"max_electric_power":0}"""), 0.0)
        assertEquals(150_000.0, w("""{"power_type":"DC","max_voltage":920,"max_amperage":400,"max_electric_power":150000}"""), 0.0)
    }

    @Test fun `top-level streaming and OCPI DateTime`() {
        val got = ArrayList<String>()
        OcpiTariffs.forEachTopLevelElement(StringReader(""" [ {"a":"x]}\"[{","b":[1,{"c":2}]} , {"d":[]} ] """)) { got += it }
        assertEquals(listOf("""{"a":"x]}\"[{","b":[1,{"c":2}]}""", """{"d":[]}"""), got)
        // No zone designator = UTC (OCPI types).
        assertEquals(OcpiTariffs.instantMs("2026-11-05T23:00:00Z"), OcpiTariffs.instantMs("2026-11-05T23:00:00"))
        assertEquals(1_793_919_600_000L, OcpiTariffs.instantMs("2026-11-05T23:00:00Z"))
    }
}
