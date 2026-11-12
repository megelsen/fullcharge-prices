package dk.fullcharge.core.pricing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Switzerland (0.15.0): ad-hoc ("direct payment") prices from the daily bundle of the open-source
 * project **ev-money-saver-data** (github.com/platinumvortex/ev-money-saver-data), built from the
 * Swiss eMobility Charging Price Map (Chargeprice open data, licence O-By-Ask: non-commercial use,
 * attribution required) plus eCarUp, Fastned and Migrol published tariffs; positions from the
 * federal station list (SFOE, OICP `EVSEData`). Switzerland is not in the EU: no AFIR feed.
 * 2026-11-12: 8 843 of 21 035 Swiss charge points have a direct-payment price.
 *
 * `prices-v2.json` (schemaVersion 2, currency CHF): tariffs keyed by `evseId` with `source`,
 * `payment` ("direct" | "app"; only direct is used: no app account, owner 2026-11-11) and
 * OCPI-like `components` (`type`, `price`, `step`, `from` in **seconds**). Units checked 2026-11-12:
 * - ENERGY: CHF/kWh incl. VAT (Chargeprice open data: "B2C price including VAT").
 * - TIME, source **chargeprice**: CHF **per minute** — GOFAST in the bundle: 0.25 from 3600 s;
 *   Swisscharge's price page: "GOFAST 0.65 CHF/kWh + 0.28 CHF/min from 61 min" (same scale; the
 *   docs' table says per hour, their example per minute).
 * - TIME, source **ecarup**: CHF **per hour** — the bundle's `scripts/sources/ecarup.mjs` stores the
 *   hourly parking fee as is and converts the per-minute penalty with × 60.
 * - TIME from any other source: unit unknown → dropped. FLAT: CHF once. PARKING_TIME: dropped
 *   (parking after charging).
 * `stations.json` (gzip on the wire): `EVSEData[]` { `OperatorName`, `EVSEDataRecord[]` { `EvseID`,
 * `GeoCoordinates.Google` "lat lon", `ChargingFacilities[]` { `power` kW (number or string),
 * `powertype` "DC" | "AC_3_PHASE" | "AC_1_PHASE" }, `Address` } }.
 * One site per operator and position (rounded to 1e-4°, ≈ 10 m).
 */
object SwissPriceBundle {
    const val PRICES_URL = "https://cdn.jsdelivr.net/gh/platinumvortex/ev-money-saver-data@feed/prices-v2.json"
    const val STATIONS_URL = "https://platinumvortex.github.io/ev-money-saver-data/stations.json"
    const val SOURCE = "CH · Swiss eMobility"
    const val ATTRIBUTION = "Data: Charging Price Map Swiss eMobility (opendata.swiss, O-By-Ask), eCarUp, Fastned, Migrol; " +
        "stations: SFOE ich-tanke-strom.ch; via ev-money-saver-data (github.com/platinumvortex/ev-money-saver-data)"

    /** Rough bounding box of Switzerland and Liechtenstein, degrees (decides when to load the bundle). */
    fun covers(latDeg: Double, lonDeg: Double): Boolean = latDeg in 45.75..47.85 && lonDeg in 5.9..10.55

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Body bytes → text. `stations.json` is stored on GitHub Pages as a gzip **file** (served as
     * `.json`; seen 2026-11-12), so after the transfer encoding is decoded it can still start with
     * the gzip magic 1F 8B: then it is unzipped once more.
     */
    fun text(bytes: ByteArray): String {
        val gz = bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()
        val plain = if (gz) java.util.zip.GZIPInputStream(bytes.inputStream()).readBytes() else bytes
        return String(plain, Charsets.UTF_8)
    }

    /** EVSE id → price elements (CHF incl. VAT); direct-payment tariffs only, the cheapest kWh price per EVSE. */
    fun parsePrices(text: String): Map<String, List<PriceElement>> {
        val root = json.parseToJsonElement(text).jsonObject
        val currency = root.str("currency") ?: "CHF"
        val best = HashMap<String, Pair<Double, List<PriceElement>>>()
        for (te in root["tariffs"]?.jsonArray.orEmpty()) {
            val t = te.jsonObject
            if (t.str("payment") != "direct") continue
            val evse = t.str("evseId") ?: continue
            val source = t.str("source")
            val els = t["components"]?.jsonArray.orEmpty().mapNotNull { ce ->
                val c = ce.jsonObject
                val price = c.dbl("price") ?: return@mapNotNull null
                val fromMin = (c.dbl("from") ?: 0.0).div(60.0).toInt() // seconds → whole minutes
                when (c.str("type")) {
                    "ENERGY" -> PriceElement(PriceType.PER_KWH, price, currency)
                    "FLAT" -> PriceElement(PriceType.FLAT, price, currency)
                    "TIME" -> when (source) {
                        "chargeprice" -> PriceElement(PriceType.PER_MINUTE, price, currency, fromMinute = fromMin)
                        "ecarup" -> PriceElement(PriceType.PER_MINUTE, price / 60.0, currency, fromMinute = fromMin)
                        else -> null
                    }
                    else -> null
                }
            }
            val kwh = els.filter { it.type == PriceType.PER_KWH }.minOfOrNull { it.valueInclVat } ?: continue
            val cur = best[evse]
            if (cur == null || kwh < cur.first) best[evse] = kwh to els
        }
        return best.mapValues { it.value.second }
    }

    /**
     * Sites from the federal station list with [prices] attached by EVSE id (unpriced charge points
     * kept, with no prices). [dcOnly] keeps only DC charge points.
     */
    fun sites(stationsText: String, prices: Map<String, List<PriceElement>>, dcOnly: Boolean = false): List<AfirSite> {
        val root = json.parseToJsonElement(stationsText).jsonObject
        data class Key(val operator: String?, val lat: Long, val lon: Long)
        val groups = LinkedHashMap<Key, MutableList<Triple<Double, Double, Pair<AfirChargePoint, String?>>>>()
        for (oe in root["EVSEData"]?.jsonArray.orEmpty()) {
            val o = oe.jsonObject
            val operator = o.str("OperatorName")
            for (re in o["EVSEDataRecord"]?.jsonArray.orEmpty()) {
                val r = re.jsonObject
                val evse = r.str("EvseID") ?: continue
                val geo = (r["GeoCoordinates"] as? JsonObject)?.str("Google")?.trim()?.split(Regex("\\s+")) ?: continue
                val lat = geo.getOrNull(0)?.toDoubleOrNull() ?: continue
                val lon = geo.getOrNull(1)?.toDoubleOrNull() ?: continue
                val facilities = (r["ChargingFacilities"] as? JsonArray).orEmpty().map { it.jsonObject }
                val isDc = facilities.any { it.str("powertype") == "DC" }
                if (dcOnly && !isDc) continue
                val kw = facilities.mapNotNull { f -> (f["power"] as? JsonPrimitive)?.let { p -> p.doubleOrNull ?: p.content.toDoubleOrNull() } }.maxOrNull() ?: 0.0
                val addr = (r["Address"] as? JsonObject)?.let { a -> listOfNotNull(a.str("Street"), listOfNotNull(a.str("PostalCode"), a.str("City")).joinToString(" ").ifBlank { null }).joinToString(", ") }
                val cp = AfirChargePoint(evse, evse, isDc, kw, prices[evse].orEmpty())
                groups.getOrPut(Key(operator, Math.round(lat * 1e4), Math.round(lon * 1e4))) { ArrayList() } += Triple(lat, lon, cp to addr)
            }
        }
        return groups.map { (k, list) ->
            AfirSite(
                id = "ch:${k.operator}:${k.lat}:${k.lon}", operator = k.operator,
                latDeg = list.map { it.first }.average(), lonDeg = list.map { it.second }.average(),
                address = list.firstNotNullOfOrNull { it.third.second }, countryCode = "CH",
                chargePoints = list.map { it.third.first }, source = SOURCE,
            )
        }
    }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.dbl(k: String): Double? = (this[k] as? JsonPrimitive)?.doubleOrNull
}
