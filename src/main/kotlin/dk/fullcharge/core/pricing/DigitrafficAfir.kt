package dk.fullcharge.core.pricing

import dk.fullcharge.core.net.HttpTransport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.io.Reader
import java.time.DayOfWeek
import java.time.Instant

/**
 * Finland: Fintraffic **Digitraffic AFIR** (afir.digitraffic.fi, licence CC BY 4.0, attribution
 * "Source: Fintraffic / digitraffic.fi, license CC 4.0 BY"). Spec: `https://afir.digitraffic.fi/swagger/openapi.json`
 * (version 2026-11-10T12:20:48Z); it gives field names and enums but **no units**. The tariff
 * objects are OCPI 2.2.1/2.3-shaped (`elements[].priceComponents[]`, `restrictions`, `taxIncluded`);
 * units follow OCPI and were confirmed with the real snapshot of 2026-11-12 (1 752 tariffs, 3 809
 * locations):
 *
 * - **Ad hoc** = tariff `type` == `AD_HOC_PAYMENT` (OCPI TariffType; e.g. Porsche's alt text
 *   "Online Adhoc Tariff …"). `REGULAR` (RFID/app) and tariffs without `type` are not used.
 * - **Join**: connector `tariffIds[]` → tariff `id`, within the operator (`countryCode`*`partyId`*`id`).
 *   References that are missing from the snapshot are returned as [Sites.unresolvedTariffRefs].
 * - **VAT**: `taxIncluded` "NO" → `price × (1 + vat/100)` with the component's `vat` (percent; 25.5
 *   in the data, 0 on a few Wattery sites); "YES" → `price` as is; "N/A" → tariff skipped. Evidence:
 *   Allego's alt text "Energy: 0.60 EUR/kWh" with ENERGY 0.476, taxIncluded NO (0.476 × 1.255 =
 *   0.597); Plugit's NO tariffs 0.1594 / 0.2231 / 0.3108 → 0.20 / 0.28 / 0.39, its YES tariffs
 *   0.19 / 0.28 / 0.35 / 0.38 round as published. A missing `vat` with "NO" uses the Finnish
 *   general rate [FI_VAT_PCT] (not seen in ad-hoc tariffs).
 * - **ENERGY**: EUR per kWh (ad-hoc 0.12–1.00 excl. VAT). An ENERGY component with `minDuration`
 *   > 0 is skipped: the price at the start of the session is kept (in the data those repeat the
 *   same price, Neste).
 * - **TIME**: EUR **per hour** of charging (OCPI) → / 60 per minute. Allego "0.25 EUR/min after
 *   45 min" = 11.9522 /h × 1.255 / 60; K-lataus and ABC 9.564 / 9.5618 /h → 12.00 /h = 0.20 /min.
 *   `stepSize` (billing granularity, s) is ignored. `minDuration` (seconds) → [PriceElement.fromMinute];
 *   TIME with `maxDuration` is dropped.
 * - **FLAT**: EUR once (Wattery 0.1992 → 0.25). Dropped when duration-restricted.
 * - **PARKING_TIME**, **CONGESTION_TIME**: dropped (idle/blocking fees, not charging).
 * - Elements with restrictions the model cannot express (`minPower`/`maxPower`, kWh, current,
 *   date, `reservation`) are dropped: 1 Neste tariff prices ≤ 50 kW / ≥ 50 kW differently.
 * - Elements are evaluated OCPI-style (first matching element per component wins): once an element
 *   without time/day/duration restriction has given a component type, later elements of that type
 *   are ignored.
 * - **Time zone**: `startTime`/`endTime` are local times of the location (Finland, Europe/Helsinki);
 *   the app evaluates windows in Europe/Copenhagen. Helsinki is always 60 min ahead (both switch
 *   DST at 01:00 UTC on the same EU dates), so windows are shifted by [COPENHAGEN_SHIFT_MIN];
 *   with `dayOfWeek`, a piece moved past midnight moves to the previous weekday.
 * - Locations (GeoJSON): position `geometry.coordinates` [lon, lat] degrees; `maxElectricPower` in
 *   **W** (22000 on 22 kW AC, 400000 on 400 kW DC; `maxVoltage` V, `maxAmperage` A) → kW.
 *   One [AfirSite] per location, one [AfirChargePoint] per EVSE.
 * - Validity: tariff `startDateTime`/`endDateTime` (UTC) → validFrom/To.
 */
object DigitrafficAfir {
    const val SOURCE = "FI · Fintraffic Digitraffic"
    const val ATTRIBUTION = "Source: Fintraffic / digitraffic.fi, license CC 4.0 BY"
    const val BASE = "https://afir.digitraffic.fi/api/charging-network/v1"
    /** Snapshots (rebuilt every minute; `?limit=ALL` only redirects here). */
    const val TARIFFS_URL = "$BASE/tariffs/all"
    const val LOCATIONS_URL = "$BASE/locations/all"
    /** Digitraffic asks clients to identify themselves; generic value, no personal data. */
    val HEADERS = mapOf("Digitraffic-User" to "fullcharge-prices", "Accept" to "application/json")

    /** Finnish general VAT rate, % (since 2024-09-01); only used when a component has no `vat`. */
    const val FI_VAT_PCT = 25.5
    /** Europe/Helsinki local time − 60 min = Europe/Copenhagen local time, all year. */
    const val COPENHAGEN_SHIFT_MIN = -60

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * @property adHoc tariff key (`FI*PLG*<id>`) → price elements incl. VAT (Copenhagen windows)
     * @property known keys of every tariff in the snapshot (ad hoc or not)
     * @property skippedElements elements dropped for restrictions the model cannot express
     */
    data class Tariffs(val adHoc: Map<String, List<PriceElement>>, val known: Set<String>, val skippedElements: Int, val skippedTariffs: Int)

    data class Sites(val sites: List<AfirSite>, val unresolvedTariffRefs: Set<String>)

    fun key(countryCode: String?, partyId: String?, tariffId: String) = "$countryCode*$partyId*$tariffId"

    fun parseTariffs(text: String): Tariffs {
        val root = json.parseToJsonElement(text).jsonObject
        val adHoc = HashMap<String, List<PriceElement>>()
        val known = HashSet<String>()
        var skippedEl = 0
        var skippedT = 0
        for (te in root["tariffs"]?.jsonArray.orEmpty()) {
            val t = te.jsonObject
            val id = t.str("id") ?: continue
            val k = key(t.str("countryCode"), t.str("partyId"), id)
            known += k
            if (t.str("type") != "AD_HOC_PAYMENT") continue
            val taxIncluded = when (t.str("taxIncluded")) { "YES" -> true; "NO" -> false; else -> null }
            if (taxIncluded == null) { skippedT++; continue }
            val currency = t.str("currency") ?: continue
            val from = t.str("startDateTime")?.let(::epochMs)
            val to = t.str("endDateTime")?.let(::epochMs)
            val out = ArrayList<PriceElement>()
            val done = HashSet<PriceType>() // component types already given by an unrestricted element
            for (ee in t["elements"]?.jsonArray.orEmpty()) {
                val e = ee.jsonObject
                val r = e["restrictions"] as? JsonObject ?: JsonObject(emptyMap())
                if (UNSUPPORTED.any { r[it] != null && r[it] !is kotlinx.serialization.json.JsonNull }) { skippedEl++; continue }
                val start = r.str("startTime")?.let(::minutesOfDay)
                val end = r.str("endTime")?.let(::minutesOfDay)
                val windows = if (start != null && end != null && start != end) listOf(start to end) else emptyList()
                val days = (r["dayOfWeek"] as? JsonArray).orEmpty().mapNotNull { d -> (d as? JsonPrimitive)?.content?.let { runCatching { DayOfWeek.valueOf(it) }.getOrNull() } }.toSet()
                val minDurS = r.dbl("minDuration") ?: 0.0
                val maxDurS = r.dbl("maxDuration")
                for (pe in e["priceComponents"]?.jsonArray.orEmpty()) {
                    val p = pe.jsonObject
                    val price = p.dbl("price") ?: continue
                    val incl = if (taxIncluded) price else price * (1 + (p.dbl("vat") ?: FI_VAT_PCT) / 100.0)
                    val (type, value, fromMinute) = when (p.str("type")) {
                        "ENERGY" -> if (minDurS > 0) continue else Triple(PriceType.PER_KWH, incl, 0)
                        "TIME" -> if (maxDurS != null) continue else Triple(PriceType.PER_MINUTE, incl / 60.0, (minDurS / 60.0).toInt())
                        "FLAT" -> if (minDurS > 0 || maxDurS != null) continue else Triple(PriceType.FLAT, incl, 0)
                        else -> continue // PARKING_TIME, CONGESTION_TIME
                    }
                    if (type in done) continue
                    for ((w, d) in toCopenhagen(windows, days)) {
                        out += PriceElement(type, value, currency, windowsMinOfDay = w, days = d, fromMinute = fromMinute, validFromEpochMs = from, validToEpochMs = to)
                    }
                    if (windows.isEmpty() && days.isEmpty() && fromMinute == 0) done += type
                }
            }
            adHoc[k] = out
        }
        return Tariffs(adHoc, known, skippedEl, skippedT)
    }

    /**
     * Helsinki window(s) and weekdays → Copenhagen ones (−60 min). Without weekdays every window just
     * shifts (wrapping past midnight). With weekdays each day's piece is shifted; the part that
     * falls before midnight belongs to the previous weekday. End 0 = midnight (end ≤ start).
     */
    internal fun toCopenhagen(windows: List<Pair<Int, Int>>, days: Set<DayOfWeek>): List<Pair<List<Pair<Int, Int>>, Set<DayOfWeek>>> {
        val day = 24 * 60
        fun m(x: Int) = ((x % day) + day) % day
        if (days.isEmpty()) return listOf(windows.map { (s, e) -> m(s + COPENHAGEN_SHIFT_MIN) to m(e + COPENHAGEN_SHIFT_MIN) } to emptySet())
        val pieces = ArrayList<Triple<DayOfWeek, Int, Int>>() // day, start, end (end exclusive, ≤ 1440)
        for (d in days) for ((s, e) in windows.ifEmpty { listOf(0 to 0) }) {
            if (e > s) pieces += Triple(d, s, e) else { pieces += Triple(d, s, day); if (e > 0) pieces += Triple(d.plus(1), 0, e) }
        }
        val shifted = ArrayList<Triple<DayOfWeek, Int, Int>>()
        for ((d, s, e) in pieces) {
            val a = s + COPENHAGEN_SHIFT_MIN
            val b = e + COPENHAGEN_SHIFT_MIN
            when {
                a >= 0 -> shifted += Triple(d, a, b)
                b <= 0 -> shifted += Triple(d.minus(1), a + day, b + day)
                else -> { shifted += Triple(d.minus(1), a + day, day); shifted += Triple(d, 0, b) }
            }
        }
        return shifted.groupBy({ it.second to m(it.third) }, { it.first })
            .map { (w, ds) -> (if (w.first == 0 && w.second == 0) emptyList() else listOf(w)) to ds.toSet() }
    }

    /**
     * Locations snapshot (GeoJSON FeatureCollection, ≈ 24 MB, streamed per feature) joined with
     * [tariffs]. Charge points without an ad-hoc tariff are kept without prices. [dcOnly] keeps
     * only DC charge points (sites without any are dropped).
     */
    fun sites(locations: Reader, tariffs: Tariffs, dcOnly: Boolean = false): Sites {
        val out = ArrayList<AfirSite>()
        val unresolved = HashSet<String>()
        JsonArrayStreamer.forEachElement(locations, "features") { text ->
            val f = json.parseToJsonElement(text).jsonObject
            val coords = (f["geometry"] as? JsonObject)?.get("coordinates") as? JsonArray ?: return@forEachElement
            val lonDeg = (coords.getOrNull(0) as? JsonPrimitive)?.doubleOrNull ?: return@forEachElement
            val latDeg = (coords.getOrNull(1) as? JsonPrimitive)?.doubleOrNull ?: return@forEachElement
            val p = f["properties"] as? JsonObject ?: return@forEachElement
            val op = p["operator"] as? JsonObject
            val cc = op?.str("countryCode")
            val party = op?.str("partyId")
            val cps = (p["evses"] as? JsonArray).orEmpty().mapNotNull { ee ->
                val e = ee as? JsonObject ?: return@mapNotNull null
                val conns = (e["connectors"] as? JsonArray).orEmpty().map { it.jsonObject }
                if (conns.isEmpty()) return@mapNotNull null
                val isDc = conns.any { it.str("powerType") == "DC" }
                if (dcOnly && !isDc) return@mapNotNull null
                val kind = conns.filter { (it.str("powerType") == "DC") == isDc }
                val powerKw = (kind.mapNotNull { it.dbl("maxElectricPower") }.maxOrNull() ?: 0.0) / 1000.0 // W → kW
                var prices: List<PriceElement> = emptyList()
                for (c in kind) for (tid in (c["tariffIds"] as? JsonArray).orEmpty()) {
                    val id = (tid as? JsonPrimitive)?.content ?: continue
                    val k = key(cc, party, id)
                    if (k !in tariffs.known) unresolved += k
                    if (prices.isEmpty()) tariffs.adHoc[k]?.let { prices = it }
                }
                val evse = e.str("id")
                AfirChargePoint(evse ?: "", evse, isDc, powerKw, prices)
            }
            if (cps.isEmpty()) return@forEachElement
            val a = p["address"] as? JsonObject
            val address = a?.let { listOfNotNull(it.str("street"), listOfNotNull(it.str("postalCode"), it.str("city")).joinToString(" ").ifBlank { null }).joinToString(", ").ifBlank { null } }
            out += AfirSite(
                id = "fi:$cc*$party:${p.str("id")}",
                operator = (op?.get("details") as? JsonObject)?.str("name") ?: party,
                latDeg = latDeg, lonDeg = lonDeg, address = address,
                countryCode = a?.str("countryCode")?.let(::alpha2), chargePoints = cps, source = SOURCE,
            )
        }
        return Sites(out, unresolved)
    }

    private val UNSUPPORTED = listOf("minPower", "maxPower", "minKwh", "maxKwh", "minCurrent", "maxCurrent", "startDate", "endDate", "reservation")

    /** Address `countryCode` is ISO 3166-1 alpha-3 ("FIN") → alpha-2. */
    @Suppress("DEPRECATION")
    private fun alpha2(a3: String): String? =
        if (a3.length == 2) a3 else java.util.Locale.getISOCountries().firstOrNull { java.util.Locale("", it).isO3Country.equals(a3, ignoreCase = true) }

    private fun epochMs(s: String): Long? = runCatching { Instant.parse(s).toEpochMilli() }.getOrNull()
    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.dbl(k: String): Double? = (this[k] as? JsonPrimitive)?.doubleOrNull
}

/** Downloads the two Digitraffic AFIR snapshots the parser needs (gzip handled by the transport). */
class DigitrafficAfirFeed(private val http: HttpTransport) {
    /** Tariffs snapshot as text (≈ 1.5 MB). @throws IOException on HTTP errors. */
    suspend fun tariffsText(): String {
        var t = ""
        val code = http.download(DigitrafficAfir.TARIFFS_URL, DigitrafficAfir.HEADERS) { t = it.reader(Charsets.UTF_8).readText() }
        if (code !in 200..299) throw IOException("Digitraffic tariffs: HTTP $code")
        return t
    }

    /** Downloads both snapshots and joins them (locations streamed, ≈ 24 MB). @throws IOException on HTTP errors. */
    suspend fun sites(dcOnly: Boolean = false): DigitrafficAfir.Sites {
        val tariffs = DigitrafficAfir.parseTariffs(tariffsText())
        var result: DigitrafficAfir.Sites? = null
        val code = http.download(DigitrafficAfir.LOCATIONS_URL, DigitrafficAfir.HEADERS) { result = DigitrafficAfir.sites(it.reader(Charsets.UTF_8), tariffs, dcOnly) }
        if (code !in 200..299) throw IOException("Digitraffic locations: HTTP $code")
        return result ?: throw IOException("Digitraffic locations: empty")
    }
}
