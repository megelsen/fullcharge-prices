package dk.fullcharge.core.pricing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.math.floor

/**
 * Germany (0.15.0): AFIR ad-hoc prices bundled by the open-source project **ladekrone**
 * (github.com/uekala/ladekrone, MIT): a GitHub Action reads the operators' DATEX II v3 offers from
 * the Mobilithek (Germany's national access point) every 6 h with its own certificate and publishes
 * compact JSON tiles of [TILE_DEG]° on GitHub Pages. Only openly licensed offers (CC0, CC BY 4.0,
 * DL-DE); attribution in Settings. Format "v1", read from the project's collector
 * (`collector/ladekrone_collector/datex.py`) and web app (`app/pricing.js`), 2026-11-12:
 *
 * - tile key `floor(lat/0.25)_floor(lon/0.25)`; `meta.json` lists the tiles that exist.
 * - station: `id`, `s` source key, `la`/`lo` degrees, `o` operator, `a` address, `g` groups of
 *   identical charge points (`n` count, `c` "dc"/"ac", `kw` kW, `r` indexes into the station's
 *   ad-hoc rates `r`; `x` = contract rates, not used: owner pays ad hoc).
 * - rate `p` components: `t` kwh / min / base / flat / free / other, `v` value **incl. VAT in EUR**
 *   (the collector converts net → gross, always reading `taxRate` as a percent), `f` = DATEX II
 *   `fromMinute` (minutes, passed through), `w` validity: `s`/`e` ISO instants, `in` periods with
 *   `t` [["HH:MM","HH:MM"]] local time (Europe/Berlin), `d` weekdays (0 = Monday).
 *   Rate-level `cur` (default EUR) and `w` (s/e).
 *
 * Mapping: kwh → [PriceType.PER_KWH] (a kWh price with `f` > 0 is dropped), min →
 * [PriceType.PER_MINUTE] from minute `f`, base / flat → [PriceType.FLAT]; free / other dropped.
 * Periods with month (`m`), absolute (`a`) or special-day (`sp`) restrictions, and components with
 * exception periods (`ex`), are dropped (rare; cannot be modelled). A group with several rates uses
 * the one with the cheapest all-day kWh price (ladekrone shows the cheapest; merging them would
 * count each rate's start fee). Local times are read in [ZONE]; Copenhagen and Berlin share CET/CEST.
 */
object LadekroneTiles {
    const val TILE_DEG = 0.25
    const val BASE_URL = "https://uekala.github.io/ladekrone/v1/"
    const val META_URL = BASE_URL + "meta.json"
    const val SOURCE = "DE · ladekrone"
    val ZONE: ZoneId = ZoneId.of("Europe/Berlin")

    /** CC BY 4.0 sources need naming; the rest are CC0 (shown anyway). */
    const val ATTRIBUTION = "Data: Mobilithek AFIR offers (EnBW mobility+, Eco-Movement B.V., GLS Mobility: CC BY 4.0; others CC0) " +
        "via ladekrone (github.com/uekala/ladekrone)"

    private val json = Json { ignoreUnknownKeys = true }

    fun tileKey(latDeg: Double, lonDeg: Double): String = "${floor(latDeg / TILE_DEG).toInt()}_${floor(lonDeg / TILE_DEG).toInt()}"

    fun tileUrl(key: String) = "${BASE_URL}tiles/$key.json"

    /** Tile keys whose square lies within [radiusM] of the point (the point's own tile first). */
    fun tilesNear(latDeg: Double, lonDeg: Double, radiusM: Double): List<String> {
        val dLat = radiusM / 111_320.0
        val dLon = radiusM / (111_320.0 * kotlin.math.cos(Math.toRadians(latDeg)).coerceAtLeast(0.01))
        val keys = LinkedHashSet<String>()
        keys += tileKey(latDeg, lonDeg)
        for (la in listOf(latDeg - dLat, latDeg + dLat)) for (lo in listOf(lonDeg - dLon, lonDeg + dLon)) keys += tileKey(la, lo)
        return keys.toList()
    }

    /** meta.json → (format version, generated ISO, tile keys); null when unreadable. */
    fun parseMeta(text: String): Meta? = runCatching {
        val o = json.parseToJsonElement(text).jsonObject
        Meta(o.int("v") ?: return null, o.str("generated"), o["tiles"]?.jsonArray?.mapNotNull { (it as? JsonPrimitive)?.content }?.toSet() ?: emptySet())
    }.getOrNull()

    data class Meta(val version: Int, val generatedIso: String?, val tiles: Set<String>)

    /** All stations of a tile (AC and DC); sites without any charge point are dropped. */
    fun parseTile(text: String): List<AfirSite> {
        val root = json.parseToJsonElement(text).jsonObject
        return root["stations"]?.jsonArray?.mapNotNull { runCatching { station(it.jsonObject) }.getOrNull() }.orEmpty()
    }

    private fun station(s: JsonObject): AfirSite? {
        val lat = s.dbl("la") ?: return null
        val lon = s.dbl("lo") ?: return null
        val id = s.str("id") ?: return null
        val rates = s["r"]?.jsonArray?.map { rate(it.jsonObject) }.orEmpty()
        val points = s["g"]?.jsonArray?.mapIndexedNotNull { gi, ge ->
            val g = ge.jsonObject
            val kind = g.str("c") ?: return@mapIndexedNotNull null
            val idx = g["r"]?.jsonArray?.mapNotNull { it.jsonPrimitive.intOrNull }.orEmpty()
            val prices = idx.mapNotNull { rates.getOrNull(it) }
                .minByOrNull { r -> r.filter { it.type == PriceType.PER_KWH && it.windowsMinOfDay.isEmpty() }.minOfOrNull { it.valueInclVat } ?: Double.MAX_VALUE }
                .orEmpty()
            AfirChargePoint("$id#$gi", null, kind == "dc", g.dbl("kw") ?: 0.0, prices)
        }.orEmpty()
        if (points.isEmpty()) return null
        return AfirSite(id, s.str("o"), lat, lon, s.str("a"), "DE", points, "$SOURCE (${s.str("s") ?: "?"})")
    }

    private fun rate(r: JsonObject): List<PriceElement> {
        val currency = r.str("cur") ?: "EUR"
        val rateW = r["w"] as? JsonObject
        val out = ArrayList<PriceElement>()
        for (ce in r["p"]?.jsonArray.orEmpty()) {
            val c = ce.jsonObject
            val value = c.dbl("v") ?: continue
            val from = c.int("f") ?: 0
            val type = when (c.str("t")) {
                "kwh" -> if (from == 0) PriceType.PER_KWH else continue
                "min" -> PriceType.PER_MINUTE
                "base", "flat" -> PriceType.FLAT
                else -> continue
            }
            val w = c["w"] as? JsonObject
            if (w?.get("ex").isFilled()) continue
            val validFrom = instantMs(w?.str("s")) ?: instantMs(rateW?.str("s"))
            val validTo = instantMs(w?.str("e")) ?: instantMs(rateW?.str("e"))
            val base = PriceElement(type, value, currency, fromMinute = if (type == PriceType.PER_MINUTE) from else 0,
                validFromEpochMs = validFrom, validToEpochMs = validTo)
            val periods = (w?.get("in") as? JsonArray).orEmpty().map { it.jsonObject }
            if (periods.isEmpty()) { out += base; continue }
            for (p in periods) {
                if (p["m"].isFilled() || p["a"].isFilled() || p["sp"].isFilled()) continue
                val windows = (p["t"] as? JsonArray).orEmpty().mapNotNull { pair ->
                    val a = (pair as? JsonArray) ?: return@mapNotNull null
                    val s = minutesOfDay(a.getOrNull(0)?.jsonPrimitive?.content ?: return@mapNotNull null) ?: return@mapNotNull null
                    val e = minutesOfDay(a.getOrNull(1)?.jsonPrimitive?.content ?: return@mapNotNull null) ?: return@mapNotNull null
                    s to e
                }
                val days = (p["d"] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.intOrNull?.takeIf { d -> d in 0..6 }?.let { d -> DayOfWeek.of(d + 1) } }.toSet()
                out += base.copy(windowsMinOfDay = windows, days = days)
            }
        }
        return out
    }

    /** ISO instant; without zone = local time in [ZONE] (as the collector does). */
    internal fun instantMs(iso: String?): Long? {
        if (iso.isNullOrBlank()) return null
        return runCatching { OffsetDateTime.parse(iso.replace(" ", "T")).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(iso.replace(" ", "T")).atZone(ZONE).toInstant().toEpochMilli() }.getOrNull()
    }

    private fun JsonElement?.isFilled(): Boolean = (this as? JsonArray)?.isNotEmpty() == true
    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.dbl(k: String): Double? = (this[k] as? JsonPrimitive)?.doubleOrNull
    private fun JsonObject.int(k: String): Int? = (this[k] as? JsonPrimitive)?.intOrNull
}
