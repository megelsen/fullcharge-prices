package dk.fullcharge.core.pricing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.floor

/**
 * FullCharge price tiles, format **v1** (published by github.com/megelsen/fullcharge-prices, read by
 * the app). One JSON file per [TILE_DEG]° square, key `floor(lat/0.25)_floor(lon/0.25)`; `meta.json`
 * lists the tiles and the sources. A 1:1 serialisation of [AfirSite] / [AfirChargePoint] /
 * [PriceElement], so decoding gives exactly what the parsers produced.
 *
 * Tile: `{"v":1,"tile":"214_40","generated":"<ISO>","sites":[site…]}`
 * - site: `id`, `src` (e.g. "DK · Spirii"), `op` operator?, `la`/`lo` degrees WGS84, `a` address?,
 *   `cc` country?, `cp` [charge point…]
 * - charge point: `id`, `evse`?, `dc` boolean, `kw` max power **kW**, `p` [price…]
 * - price: `t` "kwh" | "min" | "flat", `v` value **incl. VAT** in `cur` (ISO 4217) per kWh / per
 *   minute / once; `from` minute of the session a per-minute fee starts (0 if absent); `day` local
 *   date of a dynamic slot ("YYYY-MM-DD"); `win` [[start, end]] minutes after local midnight (end ≤
 *   start = past midnight); `days` ISO weekdays 1 = Monday … 7; `vf` / `vt` validity, ms since epoch.
 *   Local = the operator's zone; all sources so far are CET/CEST (DK, DE, CH, SE, NO).
 */
object PriceTiles {
    const val VERSION = 1
    const val TILE_DEG = 0.25
    /** Published by the GitHub Action of github.com/megelsen/fullcharge-prices. */
    const val BASE_URL = "https://megelsen.github.io/fullcharge-prices/v1/"
    const val META_URL = BASE_URL + "meta.json"
    fun tileUrl(key: String) = "${BASE_URL}tiles/$key.json"

    /** Zone in which tile windows / slot days are read (all sources so far keep CET/CEST; Finland is shifted). */
    val ZONE: java.time.ZoneId = java.time.ZoneId.of("Europe/Copenhagen")

    private val json = Json { ignoreUnknownKeys = true }

    fun tileKey(latDeg: Double, lonDeg: Double): String = "${floor(latDeg / TILE_DEG).toInt()}_${floor(lonDeg / TILE_DEG).toInt()}"

    /** Keys of the tiles within [radiusM] of the point (the point's own tile first). */
    fun tilesNear(latDeg: Double, lonDeg: Double, radiusM: Double): List<String> {
        val dLat = radiusM / 111_320.0
        val dLon = radiusM / (111_320.0 * kotlin.math.cos(Math.toRadians(latDeg)).coerceAtLeast(0.01))
        val keys = LinkedHashSet<String>()
        keys += tileKey(latDeg, lonDeg)
        for (la in listOf(latDeg - dLat, latDeg + dLat)) for (lo in listOf(lonDeg - dLon, lonDeg + dLon)) keys += tileKey(la, lo)
        return keys.toList()
    }

    // ------------------------------------------------------------------ encode

    fun encodeTile(key: String, generatedIso: String, sites: List<AfirSite>): String = buildJsonObject {
        put("v", VERSION)
        put("tile", key)
        put("generated", generatedIso)
        put("sites", buildJsonArray { sites.forEach { add(encodeSite(it)) } })
    }.toString()

    fun encodeSite(s: AfirSite): JsonObject = buildJsonObject {
        put("id", s.id)
        put("src", s.source)
        s.operator?.let { put("op", it) }
        put("la", s.latDeg)
        put("lo", s.lonDeg)
        s.address?.let { put("a", it) }
        s.countryCode?.let { put("cc", it) }
        put("cp", buildJsonArray {
            s.chargePoints.forEach { cp ->
                add(buildJsonObject {
                    put("id", cp.id)
                    cp.evseId?.let { put("evse", it) }
                    put("dc", cp.isDc)
                    put("kw", cp.maxPowerKw)
                    put("p", buildJsonArray { cp.prices.forEach { add(encodePrice(it)) } })
                })
            }
        })
    }

    private fun encodePrice(e: PriceElement): JsonObject = buildJsonObject {
        put("t", when (e.type) { PriceType.PER_KWH -> "kwh"; PriceType.PER_MINUTE -> "min"; PriceType.FLAT -> "flat" })
        put("v", e.valueInclVat)
        put("cur", e.currency.uppercase()) // some feeds write "dkk"
        if (e.fromMinute != 0) put("from", e.fromMinute)
        e.dayLocal?.let { put("day", it.toString()) }
        if (e.windowsMinOfDay.isNotEmpty()) put("win", buildJsonArray {
            e.windowsMinOfDay.forEach { (a, b) -> add(buildJsonArray { add(JsonPrimitive(a)); add(JsonPrimitive(b)) }) }
        })
        if (e.days.isNotEmpty()) put("days", buildJsonArray { e.days.sorted().forEach { add(JsonPrimitive(it.value)) } })
        e.validFromEpochMs?.let { put("vf", it) }
        e.validToEpochMs?.let { put("vt", it) }
    }

    // ------------------------------------------------------------------ decode

    /** Sites of a tile; null when the text is not a v1 tile. Unreadable sites are skipped. */
    fun decodeTile(text: String): List<AfirSite>? {
        val o = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        if ((o["v"] as? JsonPrimitive)?.intOrNull != VERSION) return null
        return o["sites"]?.jsonArray?.mapNotNull { runCatching { decodeSite(it.jsonObject) }.getOrNull() }.orEmpty()
    }

    fun decodeSite(s: JsonObject): AfirSite? {
        val cps = (s["cp"] as? JsonArray).orEmpty().map { ce ->
            val c = ce.jsonObject
            AfirChargePoint(
                id = c.str("id") ?: "",
                evseId = c.str("evse"),
                isDc = (c["dc"] as? JsonPrimitive)?.booleanOrNull ?: false,
                maxPowerKw = c.dbl("kw") ?: 0.0,
                prices = (c["p"] as? JsonArray).orEmpty().mapNotNull { decodePrice(it.jsonObject) },
            )
        }
        return AfirSite(
            id = s.str("id") ?: return null, operator = s.str("op"),
            latDeg = s.dbl("la") ?: return null, lonDeg = s.dbl("lo") ?: return null,
            address = s.str("a"), countryCode = s.str("cc"), chargePoints = cps, source = s.str("src") ?: "?",
        )
    }

    private fun decodePrice(p: JsonObject): PriceElement? {
        val type = when (p.str("t")) { "kwh" -> PriceType.PER_KWH; "min" -> PriceType.PER_MINUTE; "flat" -> PriceType.FLAT; else -> return null }
        return PriceElement(
            type = type,
            valueInclVat = p.dbl("v") ?: return null,
            currency = p.str("cur")?.uppercase() ?: return null,
            dayLocal = p.str("day")?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            windowsMinOfDay = (p["win"] as? JsonArray).orEmpty().mapNotNull { w ->
                val a = w as? JsonArray ?: return@mapNotNull null
                val s = (a.getOrNull(0) as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
                val e = (a.getOrNull(1) as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
                s to e
            },
            days = (p["days"] as? JsonArray).orEmpty().mapNotNull { d -> (d as? JsonPrimitive)?.intOrNull?.takeIf { it in 1..7 }?.let(DayOfWeek::of) }.toSet(),
            fromMinute = (p["from"] as? JsonPrimitive)?.intOrNull ?: 0,
            validFromEpochMs = (p["vf"] as? JsonPrimitive)?.longOrNull,
            validToEpochMs = (p["vt"] as? JsonPrimitive)?.longOrNull,
        )
    }

    /** meta.json → (version, generated ISO, tile keys); null when unreadable or another version. */
    fun decodeMeta(text: String): Meta? = runCatching {
        val o = json.parseToJsonElement(text).jsonObject
        val v = (o["v"] as? JsonPrimitive)?.intOrNull
        if (v != VERSION) return null
        Meta(o.str("generated"), (o["tiles"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }.toSet(),
            (o["sources"] as? JsonArray).orEmpty().map { it.jsonObject }.map { s ->
                SourceInfo(s.str("key") ?: "", s.str("name") ?: "", s.str("status") ?: "", s.str("fetched"), (s["dc_priced"] as? JsonPrimitive)?.intOrNull ?: 0, s.str("attribution") ?: "")
            })
    }.getOrNull()

    data class Meta(val generatedIso: String?, val tiles: Set<String>, val sources: List<SourceInfo>)
    data class SourceInfo(val key: String, val name: String, val status: String, val fetchedIso: String?, val dcPriced: Int, val attribution: String)

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.dbl(k: String): Double? = (this[k] as? JsonPrimitive)?.doubleOrNull
}
