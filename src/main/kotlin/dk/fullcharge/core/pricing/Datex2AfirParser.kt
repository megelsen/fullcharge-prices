package dk.fullcharge.core.pricing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.DayOfWeek
import java.time.Instant
import java.io.Reader
import java.io.StringReader
import java.time.ZoneId

/**
 * DATEX II v3 "AFIR Energy Infrastructure" profile (the format every EU national access point
 * uses since 14 Apr 2026), JSON encoding. Reads the table publication (sites, charge points,
 * rates) and the status publication (dynamic price updates per charge point, linked by the rate's
 * `idG`). Tolerant to the wrappers seen in feeds: `payload` object, `messageContainer.payload[]`,
 * or the publication at the top level.
 *
 * Units: power W → kW (÷ 1000). Prices are turned into "including VAT":
 * `taxIncluded = false` → value × (1 + rate), where a rate ≤ 1 is a fraction (0.25) and > 1 a
 * percentage (25) — both occur in Danish feeds.
 *
 * Dynamic 15-minute slots: `recurringTimePeriodOfDay` is read as local time in [zone] and the slot
 * belongs to the local date of `overallStartTime` (inferred from the Spirii feed: the slots of the
 * period starting 2026-11-11T00:00Z run 16:00–23:45 and the next period's 00:00–16:00, published
 * at 16:13 local).
 */
class Datex2AfirParser(private val zone: ZoneId, private val source: String) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Sites with the table's prices, then dynamic prices from [statusJson] (if given) replacing them
     * per rate. [keep] filters sites by position before their charge points are read (memory on the
     * phone); [dcOnly] keeps only DC charge points and drops sites without one.
     */
    fun parse(
        tableJson: String,
        statusJson: String?,
        dcOnly: Boolean = false,
        keep: (latDeg: Double, lonDeg: Double) -> Boolean = { _, _ -> true },
    ): List<AfirSite> {
        val dynamic = statusJson?.let { dynamicPrices(json.parseToJsonElement(it)) } ?: emptyMap()
        val out = ArrayList<AfirSite>()
        JsonArrayStreamer.forEachElement(StringReader(tableJson), "energyInfrastructureSite") { text ->
            site(json.parseToJsonElement(text).obj(), dynamic, dcOnly, keep)?.let(out::add)
        }
        return out
    }

    /**
     * Streaming variant for whole feeds (tens of MB per operator): reads the table publication
     * site by site from [table] and keeps only what [dcOnly] / [keep] let through. Prices from the
     * table (which carries the dynamic slots too in the Spirii feed).
     */
    fun parseStreaming(table: Reader, dcOnly: Boolean = false, keep: (latDeg: Double, lonDeg: Double) -> Boolean = { _, _ -> true }): List<AfirSite> {
        val out = ArrayList<AfirSite>()
        JsonArrayStreamer.forEachElement(table, "energyInfrastructureSite") { text ->
            // Cheap pre-check before building the tree: DC-only feeds skip AC sites without parsing them.
            if (dcOnly && !text.contains("\"dc\"")) return@forEachElement
            site(json.parseToJsonElement(text).obj(), emptyMap(), dcOnly, keep)?.let(out::add)
        }
        return out
    }

    private fun site(s: JsonObject, dynamic: Map<String, List<JsonObject>>, dcOnly: Boolean, keep: (Double, Double) -> Boolean): AfirSite? {
        // Spirii: locationReference.locAreaLocation.coordinatesForDisplay; Monta: locationReference.
        // locPointLocation.pointByCoordinates.pointCoordinates; Eco-Movement: entrance[0].locAreaLocation.
        val ref = s.o("locationReference") ?: s.arr("entrance").firstOrNull()?.obj()
        val loc = ref?.o("locAreaLocation") ?: ref?.o("locPointLocation")
        val coords = loc?.o("coordinatesForDisplay") ?: loc?.o("pointByCoordinates")?.o("pointCoordinates") ?: return null
        val lat = coords.d("latitude") ?: return null
        val lon = coords.d("longitude") ?: return null
        if (!keep(lat, lon)) return null
        if (dcOnly && s.arr("energyInfrastructureStation").none { st ->
                st.obj().arr("refillPoint").any { chargePointObj(it.obj())?.o("currentType")?.str("value") == "dc" }
            }
        ) return null
        val addr = loc?.o("locLocationExtensionG")?.o("FacilityLocation")?.o("address")
        val street = addr?.arr("addressLine")?.firstOrNull()?.obj()?.o("text")?.let(::text)
        val city = addr?.o("city")?.let(::text)
        val cps = s.arr("energyInfrastructureStation").flatMap { st ->
            st.obj().arr("refillPoint").mapNotNull { rp -> chargePointObj(rp.obj())?.let { cp -> chargePoint(cp, dynamic) } }
                .filter { !dcOnly || it.isDc }
        }
        return AfirSite(
            id = s.str("idG") ?: return null,
            // Eco-Movement names the operator "energyDistributor".
            operator = (s.o("operator") ?: s.o("energyDistributor"))?.o("afacAnOrganisation")?.o("name")?.let(::text),
            latDeg = lat,
            lonDeg = lon,
            address = listOfNotNull(street, city).joinToString(", ").ifEmpty { null },
            countryCode = addr?.str("countryCode"),
            chargePoints = cps,
            source = source,
        )
    }

    /** Spirii: `refillPoint[].aegiElectricChargingPoint`; Monta: the charge point is `refillPoint[]` itself. */
    private fun chargePointObj(rp: JsonObject): JsonObject? = rp.o("aegiElectricChargingPoint") ?: rp.takeIf { it.containsKey("currentType") || it.containsKey("idG") }

    private fun chargePoint(cp: JsonObject, dynamic: Map<String, List<JsonObject>>): AfirChargePoint? {
        val id = cp.str("idG") ?: return null
        val evse = cp.arr("externalIdentifier").firstOrNull()?.obj()?.str("identifier")
        val isDc = cp.o("currentType")?.str("value") == "dc"
        val powerW = cp.arr("connector").mapNotNull { it.obj().d("maxPowerAtSocket") }.maxOrNull()
            ?: cp.arr("availableChargingPower").mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.doubleOrNull }.maxOrNull() ?: 0.0
        // Rates: Spirii / Monta `electricEnergy[].energyRate[]`; Eco-Movement `energyProduct[].aegiElectricEnergy.energyRate[]`.
        val rates = cp.arr("electricEnergy").flatMap { it.obj().arr("energyRate") } +
            cp.arr("energyProduct").flatMap { it.obj().o("aegiElectricEnergy")?.arr("energyRate") ?: emptyList() }
        val prices = rates.flatMap { r ->
            val rate = r.obj()
            if (rate.o("ratePolicy")?.str("value")?.let { it != "adHoc" } == true) return@flatMap emptyList()
            val currency = rate.arr("applicableCurrency").firstOrNull()?.jsonPrimitive?.content ?: "?"
            val elements = dynamic[rate.str("idG")] ?: rate.arr("energyPrice").map { it.obj() }
            elements.flatMap { priceElements(it, currency) }
        }
        return AfirChargePoint(id, evse, isDc, powerW / 1000.0, prices)
    }

    private fun priceElements(p: JsonObject, currency: String): List<PriceElement> {
        val type = when (p.o("priceType")?.str("value")) {
            "pricePerKWh" -> PriceType.PER_KWH
            "pricePerMinute" -> PriceType.PER_MINUTE
            "flatRate", "basePrice" -> PriceType.FLAT // basePrice (Monta): one-off start fee per session
            else -> return emptyList()
        }
        val raw = p.d("value") ?: return emptyList()
        val included = p.b("taxIncluded") ?: true
        val rate = p.d("taxRate") ?: 0.0
        val value = if (included) raw else raw * (1.0 + if (rate > 1.0) rate / 100.0 else rate)
        val from = p.o("timeBasedApplicability")?.i("fromMinute") ?: 0
        val ov = p.o("overallPeriod")
        val start = ov?.str("overallStartTime")?.let(::epochMs)
        val end = ov?.str("overallEndTime")?.let(::epochMs)
        val periods = ov?.arr("validPeriod")?.map { it.obj() } ?: emptyList()
        if (periods.isEmpty()) {
            return listOf(PriceElement(type, value, currency, fromMinute = from, validFromEpochMs = start, validToEpochMs = end))
        }
        return periods.map { vp ->
            val windows = vp.arr("recurringTimePeriodOfDay").mapNotNull { w ->
                val s = w.obj().str("startTimeOfPeriod")?.let(::minutesOfDay)
                val e = w.obj().str("endTimeOfPeriod")?.let(::minutesOfDay)
                if (s != null && e != null) s to e else null
            }
            val days = vp.arr("recurringDayWeekMonthPeriod").flatMap { d ->
                d.obj().o("comDayWeekMonth")?.arr("applicableDay")?.mapNotNull { day(it.obj().str("value")) } ?: emptyList()
            }.toSet()
            // A 15-min slot (dynamic price) belongs to the local date its period starts on.
            val slot = windows.size == 1 && start != null && windows[0].let { (s, e) -> (e - s + 1440) % 1440 in 1..60 }
            PriceElement(
                type, value, currency,
                dayLocal = if (slot) Instant.ofEpochMilli(start!!).atZone(zone).toLocalDate() else null,
                windowsMinOfDay = windows, days = days, fromMinute = from,
                validFromEpochMs = if (slot) null else start, validToEpochMs = if (slot) null else end,
            )
        }
    }

    /** Status publication: rate idG → its current `energyPrice` elements. */
    private fun dynamicPrices(root: JsonElement): Map<String, List<JsonObject>> {
        val out = HashMap<String, List<JsonObject>>()
        publications(root, "aegiEnergyInfrastructureStatusPublication").forEach { pub ->
            pub.arr("energyInfrastructureSiteStatus").forEach { s ->
                s.obj().arr("energyInfrastructureStationStatus").forEach { st ->
                    st.obj().arr("refillPointStatus").forEach { rp ->
                        rp.obj().o("aegiElectricChargingPointStatus")?.arr("energyRateUpdate")?.forEach { u ->
                            val id = u.obj().o("energyRateReference")?.str("idG") ?: return@forEach
                            out[id] = u.obj().arr("energyPrice").map { it.obj() }
                        }
                    }
                }
            }
        }
        return out
    }

    /** Finds publication objects named [key] under the known wrappers. */
    private fun publications(root: JsonElement, key: String): List<JsonObject> {
        val o = root as? JsonObject ?: return emptyList()
        o.o(key)?.let { return listOf(it) }
        val payload = o["payload"] ?: o.o("messageContainer")?.get("payload")
        return when (payload) {
            is JsonObject -> listOfNotNull(payload.o(key))
            is JsonArray -> payload.mapNotNull { (it as? JsonObject)?.o(key) }
            else -> emptyList()
        }
    }

    private fun day(v: String?): DayOfWeek? = v?.let { runCatching { DayOfWeek.valueOf(it.uppercase()) }.getOrNull() }
    private fun epochMs(s: String): Long? = runCatching { Instant.parse(s).toEpochMilli() }.getOrNull()
    private fun text(o: JsonObject): String? = o.arr("values").firstOrNull()?.obj()?.str("value")

    private fun JsonElement.obj(): JsonObject = jsonObject
    private fun JsonObject.o(k: String): JsonObject? = this[k] as? JsonObject
    private fun JsonObject.arr(k: String): List<JsonElement> = (this[k] as? JsonArray)?.toList() ?: emptyList()
    private fun JsonObject.str(k: String): String? = (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.content
    private fun JsonObject.d(k: String): Double? = (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.doubleOrNull
    private fun JsonObject.i(k: String): Int? = (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull
    private fun JsonObject.b(k: String): Boolean? = (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull
}
