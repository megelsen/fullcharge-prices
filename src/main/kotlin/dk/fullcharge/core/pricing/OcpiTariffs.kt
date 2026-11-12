package dk.fullcharge.core.pricing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.io.Reader
import java.io.StringReader
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.sqrt

/**
 * Generic **OCPI 2.2.1** Locations + Tariffs → [AfirSite] converter (0.16.0), first used for the
 * Netherlands: NDW **DOT-NL**, the Dutch AFIR national access point, publishes every CPO's OCPI
 * objects as two gzip files ([NDW_LOCATIONS_URL], [NDW_TARIFFS_URL]; each a top-level JSON array,
 * ~200 MB and ~90 MB unzipped on 2026-11-12, hence the streaming readers). Licence: NDW open data
 * is **CC0 1.0** unless a dataset says otherwise (docs.ndw.nu/algemeen/data-gebruiksvoorwaarden,
 * 2026-10-23); the DOT-NL pages state nothing else. Attribution is optional; shown anyway.
 *
 * Units, from the OCPI 2.2.1 text (`mod_tariffs.asciidoc`, tag 2.2.1) and checked on the NDW
 * files of 2026-11-12:
 * - `PriceComponent.price` is "Price per unit (**excl. VAT**)"; `vat` is a **percentage**; "If
 *   omitted, no VAT is applicable" → incl. VAT = `price × (1 + vat / 100)`, vat null → price as is.
 *   Data: Fastned ENERGY 0.6364 @ 21 → 0.7700; Allego 0.655 @ 21 → 0.7926; Lidl 0.4959 @ 21 →
 *   0.6000; Electra 0.5702 @ 21 → 0.6899 — round incl.-VAT prices, so the scale is right. CPOs
 *   with `vat` null publish round figures (E-Flux 0.63, Valk 0.79, Tesla 0.41/0.56): read as
 *   final prices, as the spec says. A `vat` in (0, 1) is read as a **fraction** (TotalEnergies
 *   `NL*TCB_NL_059`: 0.5868 with vat 0.21; × 1.21 = 0.7100, its other tariffs use 21).
 * - ENERGY: currency per **kWh** (`step_size` multiplier 1 Wh; billing steps ignored).
 * - TIME: "Time charging: defined in **hours**, `step_size` multiplier: 1 second" → per minute =
 *   per hour / 60. Data: Electra 19.8347 @ 21 → 24.00 /h = 0.40 /min from minute 60; E.ON Drive
 *   1.4876 @ 21 → 1.80 /h = 0.03 /min (both round per minute only when read as per hour).
 * - FLAT: once per session. PARKING_TIME (not charging) is dropped.
 * - `currency`: ISO 4217 per tariff (NDW: EUR, a few DKK/SEK/USD; "XXX" = none → tariff dropped).
 * - `max_electric_power`: **W** (spec "in Watts"; NDW: Qwello AC_3_PHASE 230 V × 25 A × 3 =
 *   17 250 = max_electric_power). When null or 0: V × A for DC/AC_1_PHASE, V × A × 3 for
 *   AC_3_PHASE with V line-to-neutral (spec), V × A × √3 when V > 250 (line-to-line; NDW connectors
 *   that fill both give max_electric_power / (V·A·3) ≈ 0.577 = 1/√3 at 400 V).
 * - `coordinates.latitude/longitude`: decimal-degree **strings** (WGS84).
 *
 * Restrictions (`TariffRestrictions`): `start_time`/`end_time` local "HH:MM" → window (end ≤ start
 * wraps midnight; a missing side is 00:00; equal = all day; "23:59" as end is read as end of day:
 * Shell Recharge publishes 00:00–23:59 for all-day prices); `day_of_week` → days (all seven = no
 * restriction); `start_date` (incl.) / `end_date` (excl.) local dates → validity in [zone]; a
 * one-day range becomes [PriceElement.dayLocal] (dynamic slots: E-Flux hourly, PowerGo 15-min).
 * PowerGo publishes `end_date` = `start_date` for its slots (by the spec: never valid); read as
 * that one day. `min_duration` (s, inclusive) → [PriceElement.fromMinute] = ⌈s / 60⌉ for TIME; 0 =
 * no restriction. Not modelled → the element is dropped: `max_duration` (> 0) on a priced TIME,
 * any duration on ENERGY/FLAT, `min/max_kwh`, `min/max_current`, `min/max_power`, `reservation`.
 * Tariff `start_date_time`/`end_date_time` (UTC) intersect each element's validity.
 * Zero-priced components are left out (they cost nothing; [ChargeCost] treats 0 kWh as no price).
 *
 * Precedence: OCPI uses, per dimension, the **first** element whose restrictions match;
 * [ChargeCost] uses the most specific. Kept faithful where it matters: an element with no
 * restrictions closes its dimensions (later elements of that dimension are unreachable and
 * dropped — e.g. E.ON tariff 431 starts with TIME 0.00 / `min_duration` 0, so its later 1.4876 /h
 * never applies), and elements keep their order so equally specific matches resolve first-wins.
 * Not exact: an earlier, broader restricted element followed by a more specific overlapping one
 * (OCPI: earlier wins; here: the more specific one).
 *
 * Ad hoc: per connector, its `AD_HOC_PAYMENT` tariffs; else those without `type` ("When omitted,
 * this tariff is valid for all sessions"); `REGULAR` (spec: "valid when using an RFID") and
 * `PROFILE_*` only with [acceptRegular]. NDW 2026-11-12: every connector lists at most one tariff;
 * types: none 104 473, REGULAR 2 482, AD_HOC_PAYMENT 33. NDW maps AFIR "ad hoc price" to the whole
 * Tariffs module without a type, so CPOs such as Vattenfall (NUO) that tag their only tariff
 * REGULAR may mean it as the ad-hoc price; the caller decides ([acceptRegular]).
 *
 * Tariffs are looked up by (`country_code`, `party_id`, `id`) of the location (OCPI ids are unique
 * per party only). Unpublished locations (`publish` false) and REMOVED EVSEs are skipped; EVSEs
 * whose tariffs are missing are kept without prices. One [AfirSite] per OCPI location.
 */
object OcpiTariffs {
    const val NDW_LOCATIONS_URL = "https://opendata.ndw.nu/charging_point_locations_ocpi.json.gz"
    const val NDW_TARIFFS_URL = "https://opendata.ndw.nu/charging_point_tariffs_ocpi.json.gz"
    const val NDW_SOURCE = "NL · NDW DOT-NL"
    const val NDW_ATTRIBUTION = "Data: NDW DOT-NL (opendata.ndw.nu), CC0 1.0"
    val NDW_ZONE: ZoneId = ZoneId.of("Europe/Amsterdam")

    private const val AD_HOC = "AD_HOC_PAYMENT"
    private val json = Json { ignoreUnknownKeys = true }

    data class TariffKey(val countryCode: String, val partyId: String, val id: String)

    /** One OCPI tariff converted to price elements (incl. VAT, per kWh / per minute / once). */
    data class Tariff(val key: TariffKey, val type: String?, val currency: String, val prices: List<PriceElement>)

    // ---------------------------------------------------------------- tariffs

    /** Tariffs file (top-level array) → tariffs by key; unreadable entries are skipped. */
    fun parseTariffs(reader: Reader, zone: ZoneId): Map<TariffKey, Tariff> {
        val out = HashMap<TariffKey, Tariff>()
        forEachTopLevelElement(reader) { text ->
            runCatching { tariff(json.parseToJsonElement(text).jsonObject, zone) }.getOrNull()?.let { out[it.key] = it }
        }
        return out
    }

    fun parseTariffs(text: String, zone: ZoneId): Map<TariffKey, Tariff> = parseTariffs(StringReader(text), zone)

    /** One OCPI Tariff object; null without id/party/currency. */
    fun tariff(t: JsonObject, zone: ZoneId): Tariff? {
        val key = TariffKey(t.str("country_code") ?: return null, t.str("party_id") ?: return null, t.str("id") ?: return null)
        val currency = t.str("currency")?.takeIf { it.length == 3 && it != "XXX" } ?: return null
        val tariffFrom = instantMs(t.str("start_date_time"))
        val tariffTo = instantMs(t.str("end_date_time"))
        val closed = HashSet<String>() // dimensions an unrestricted element already covers
        val prices = ArrayList<PriceElement>()
        for (ee in t.arr("elements")) {
            val el = ee as? JsonObject ?: continue
            val r = el["restrictions"] as? JsonObject
            val dims = el.arr("price_components").mapNotNull { (it as? JsonObject)?.str("type") }.toSet()
            val res = restriction(r, zone)
            if (res != null) {
                val validFrom = listOfNotNull(tariffFrom, res.validFrom).maxOrNull()
                val validTo = listOfNotNull(tariffTo, res.validTo).minOrNull()
                if (validFrom == null || validTo == null || validFrom <= validTo) {
                    for (ce in el.arr("price_components")) {
                        val c = ce as? JsonObject ?: continue
                        val dim = c.str("type") ?: continue
                        if (dim in closed) continue
                        val price = c.dbl("price")?.takeIf { it > 0.0 } ?: continue
                        val vatPct = c.dbl("vat")?.let { if (it > 0.0 && it < 1.0) it * 100.0 else it } ?: 0.0
                        val incl = price * (1.0 + vatPct / 100.0)
                        val noDuration = res.minDurationS == null && res.maxDurationS == null
                        val base = PriceElement(
                            PriceType.PER_KWH, incl, currency, dayLocal = res.dayLocal, windowsMinOfDay = res.windows,
                            days = res.days, validFromEpochMs = validFrom, validToEpochMs = validTo,
                        )
                        prices += when (dim) {
                            "ENERGY" -> if (res.modelable && noDuration) base else continue
                            "FLAT" -> if (res.modelable && noDuration) base.copy(type = PriceType.FLAT) else continue
                            "TIME" -> if (res.modelable && res.maxDurationS == null) {
                                // per hour → per minute; active from second min_duration → first whole minute.
                                base.copy(type = PriceType.PER_MINUTE, valueInclVat = incl / 60.0, fromMinute = (((res.minDurationS ?: 0L) + 59L) / 60L).toInt())
                            } else continue
                            else -> continue // PARKING_TIME and unknown dimensions
                        }
                    }
                }
            }
            if (res != null && !res.restricted) closed += dims
        }
        return Tariff(key, t.str("type"), currency, prices)
    }

    private class Restriction(
        val windows: List<Pair<Int, Int>>,
        val days: Set<DayOfWeek>,
        val dayLocal: LocalDate?,
        val validFrom: Long?,
        val validTo: Long?,
        val minDurationS: Long?,
        val maxDurationS: Long?,
        /** False when a restriction we cannot model is present (kWh, current, power, reservation). */
        val modelable: Boolean,
        /** True when any restriction is present (the element then does not close its dimensions). */
        val restricted: Boolean,
    )

    /** Null when the restrictions can never match or cannot be read (the element is dropped). */
    private fun restriction(r: JsonObject?, zone: ZoneId): Restriction? {
        if (r == null) return Restriction(emptyList(), emptySet(), null, null, null, null, null, modelable = true, restricted = false)
        val unsupported = listOf("min_kwh", "max_kwh", "min_current", "max_current", "min_power", "max_power", "reservation")
            .any { r.filled(it) }
        val minDur = r.lng("min_duration")?.takeIf { it > 0 }
        val maxDur = r.lng("max_duration")
        if (maxDur != null && maxDur <= 0) return null // shorter than 0 s: never
        val st = r.str("start_time")
        val et = r.str("end_time")
        val windows = if (st == null && et == null) emptyList() else {
            val s = minutesOfDay(st ?: "00:00") ?: return null
            val e = if (et == "23:59") 0 else minutesOfDay(et ?: "00:00") ?: return null
            if (s == e) emptyList() else listOf(s to e)
        }
        val dayArr = r["day_of_week"] as? JsonArray
        val days = dayArr.orEmpty().mapNotNull { d -> (d as? JsonPrimitive)?.content?.let { n -> runCatching { DayOfWeek.valueOf(n) }.getOrNull() } }.toSet()
        if (dayArr != null && dayArr.isNotEmpty() && days.isEmpty()) return null
        val sd = r.str("start_date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() ?: return null }
        val ed = r.str("end_date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() ?: return null }
        var dayLocal: LocalDate? = null
        var from: Long? = null
        var to: Long? = null
        if (sd != null && ed != null && (ed == sd || ed == sd.plusDays(1))) dayLocal = sd
        else {
            if (sd != null && ed != null && ed < sd) return null
            from = sd?.atStartOfDay(zone)?.toInstant()?.toEpochMilli()
            to = ed?.atStartOfDay(zone)?.toInstant()?.toEpochMilli()?.minus(1)
        }
        val normDays = if (days.size == 7) emptySet() else days
        val restricted = unsupported || minDur != null || maxDur != null || windows.isNotEmpty() || normDays.isNotEmpty() || sd != null || ed != null
        return Restriction(windows, normDays, dayLocal, from, to, minDur, maxDur, modelable = !unsupported, restricted = restricted)
    }

    // ---------------------------------------------------------------- locations

    /**
     * Streams the locations file (top-level array) and calls [action] for every site; locations that
     * cannot be read are skipped. [dcOnly] keeps only DC charge points (sites without any dropped).
     */
    fun forEachSite(
        reader: Reader,
        tariffs: Map<TariffKey, Tariff>,
        source: String,
        defaultCountryCode: String?,
        acceptRegular: Boolean = false,
        dcOnly: Boolean = false,
        action: (AfirSite) -> Unit,
    ) {
        forEachTopLevelElement(reader) { text ->
            runCatching { site(json.parseToJsonElement(text).jsonObject, tariffs, source, defaultCountryCode, acceptRegular, dcOnly) }
                .getOrNull()?.let(action)
        }
    }

    fun sites(
        locationsText: String,
        tariffs: Map<TariffKey, Tariff>,
        source: String,
        defaultCountryCode: String?,
        acceptRegular: Boolean = false,
        dcOnly: Boolean = false,
    ): List<AfirSite> = ArrayList<AfirSite>().also { out ->
        forEachSite(StringReader(locationsText), tariffs, source, defaultCountryCode, acceptRegular, dcOnly) { out += it }
    }

    /** One OCPI Location → site; null when unpublished, without position or without charge points. */
    fun site(
        l: JsonObject,
        tariffs: Map<TariffKey, Tariff>,
        source: String,
        defaultCountryCode: String?,
        acceptRegular: Boolean = false,
        dcOnly: Boolean = false,
    ): AfirSite? {
        if ((l["publish"] as? JsonPrimitive)?.content == "false") return null
        val id = l.str("id") ?: return null
        val cc = l.str("country_code") ?: return null
        val party = l.str("party_id") ?: return null
        val coord = l["coordinates"] as? JsonObject ?: return null
        val lat = coord.num("latitude") ?: return null
        val lon = coord.num("longitude") ?: return null
        val points = l.arr("evses").mapNotNull { ee ->
            val e = ee as? JsonObject ?: return@mapNotNull null
            if (e.str("status") == "REMOVED") return@mapNotNull null
            val conns = e.arr("connectors").mapNotNull { it as? JsonObject }
            if (conns.isEmpty()) return@mapNotNull null
            val isDc = conns.any { it.str("power_type") == "DC" }
            if (dcOnly && !isDc) return@mapNotNull null
            val best = conns.filter { !isDc || it.str("power_type") == "DC" }.maxByOrNull { powerW(it) } ?: return@mapNotNull null
            val evseId = e.str("evse_id")
            AfirChargePoint(
                id = evseId ?: e.str("uid") ?: return@mapNotNull null,
                evseId = evseId,
                isDc = isDc,
                maxPowerKw = conns.maxOf { powerW(it) } / 1000.0,
                prices = adHoc(best, cc, party, tariffs, acceptRegular),
            )
        }
        if (points.isEmpty()) return null
        val city = listOfNotNull(l.str("postal_code"), l.str("city")).joinToString(" ").ifBlank { null }
        return AfirSite(
            id = "ocpi:$cc:$party:$id",
            operator = (l["operator"] as? JsonObject)?.str("name") ?: (l["owner"] as? JsonObject)?.str("name"),
            latDeg = lat, lonDeg = lon,
            address = listOfNotNull(l.str("address"), city).joinToString(", ").ifBlank { null },
            countryCode = iso2(l.str("country")) ?: defaultCountryCode,
            chargePoints = points,
            source = source,
        )
    }

    /** The connector's ad-hoc tariffs (see class doc), merged in listed order. */
    private fun adHoc(c: JsonObject, cc: String, party: String, tariffs: Map<TariffKey, Tariff>, acceptRegular: Boolean): List<PriceElement> {
        val ts = c.arr("tariff_ids").mapNotNull { (it as? JsonPrimitive)?.content?.let { id -> tariffs[TariffKey(cc, party, id)] } }
        val chosen = ts.filter { it.type == AD_HOC }.ifEmpty { ts.filter { it.type == null } }
            .ifEmpty { if (acceptRegular) ts else emptyList() }
        return chosen.flatMap { it.prices }
    }

    /** Connector power, W (see class doc). */
    internal fun powerW(c: JsonObject): Double {
        c.num("max_electric_power")?.takeIf { it > 0 }?.let { return it }
        val v = c.num("max_voltage") ?: return 0.0
        val a = c.num("max_amperage") ?: return 0.0
        return when (c.str("power_type")) {
            "AC_3_PHASE" -> if (v > 250) v * a * sqrt(3.0) else v * a * 3
            "AC_2_PHASE", "AC_2_PHASE_SPLIT" -> v * a * 2
            else -> v * a
        }
    }

    private val iso3to2: Map<String, String> by lazy {
        Locale.getISOCountries().mapNotNull { c -> runCatching { Locale.Builder().setRegion(c).build().isO3Country to c }.getOrNull() }.toMap()
    }

    private fun iso2(iso3: String?): String? = iso3?.let { if (it.length == 2) it else iso3to2[it] }

    /** OCPI DateTime (UTC; "the absence of the timezone designator implies a UTC timestamp"). */
    internal fun instantMs(iso: String?): Long? {
        if (iso.isNullOrBlank()) return null
        return runCatching { OffsetDateTime.parse(iso).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(iso).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
    }

    // ---------------------------------------------------------------- streaming

    /**
     * Raw text of every element of a top-level JSON array, one at a time (the NDW files are one
     * array of hundreds of MB; one location or tariff is a few kB). Assumes valid JSON.
     */
    internal fun forEachTopLevelElement(reader: Reader, action: (String) -> Unit) {
        val buf = CharArray(64 * 1024)
        var depth = 0
        var inString = false
        var escape = false
        var element: StringBuilder? = null
        while (true) {
            val n = reader.read(buf)
            if (n < 0) break
            for (i in 0 until n) {
                val ch = buf[i]
                if (inString) {
                    element?.append(ch)
                    if (escape) escape = false
                    else if (ch == '\\') escape = true
                    else if (ch == '"') inString = false
                    continue
                }
                when (ch) {
                    '{', '[' -> {
                        depth++
                        if (depth == 2) element = StringBuilder()
                        element?.append(ch)
                    }
                    '}', ']' -> {
                        element?.append(ch)
                        if (depth == 2) { element?.let { action(it.toString()) }; element = null }
                        depth--
                    }
                    '"' -> { inString = true; element?.append(ch) }
                    else -> element?.append(ch)
                }
            }
        }
    }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.dbl(k: String): Double? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.doubleOrNull
    private fun JsonObject.lng(k: String): Long? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }
    private fun JsonObject.num(k: String): Double? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { it.doubleOrNull ?: it.content.toDoubleOrNull() }
    private fun JsonObject.arr(k: String): List<JsonElement> = (this[k] as? JsonArray).orEmpty()
    private fun JsonObject.filled(k: String): Boolean = when (val v = this[k]) {
        null, JsonNull -> false
        is JsonArray -> v.isNotEmpty()
        else -> true
    }
}
