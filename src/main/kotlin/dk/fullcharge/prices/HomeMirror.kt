package dk.fullcharge.prices

import dk.fullcharge.core.net.HttpTransport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Home-charging data for FullCharge (0.17.2), mirrored from Energi Data Service (Energinet) to
 * `<siteDir>/v1/home/` **in Energi Data Service's own response format** (`{"records":[…]}`), so the
 * app keeps its parsers and only reads from GitHub Pages. Reason: some phones reject the TLS chain of
 * api.energidataservice.dk ("Trust anchor for certification path not found", owner, 2026-11-12).
 *
 * Files:
 * - `spot-DK1.json`, `spot-DK2.json`: DayAheadPrices (TimeUTC, DayAheadPriceDKK = DKK/MWh excl. VAT),
 *   from 8 days ago 00:00 to the day after tomorrow 00:00 local time — every run.
 * - `tariffs-energinet.json`: DatahubPricelist lines of Energinet (40000, 41000, EA-001) from 400 days ago.
 * - `companies.json`: grid companies with a private-home tariff ("Nettarif C"), columns ChargeOwner, GLN_Number.
 * - `tariffs-grid-<GLN>.json`: each company's "Nettarif C" lines from 400 days ago.
 * Tariff files are fetched again after [TARIFF_REFRESH_MS]; any file that cannot be fetched keeps
 * its last good copy from [stateDir] (spot up to 48 h, tariffs up to 14 days).
 */
object HomeMirror {
    private const val EDS = "https://api.energidataservice.dk/dataset/"
    private val DK: ZoneId = ZoneId.of("Europe/Copenhagen")
    private const val ENERGINET_GLN = "5790000432752"
    private val ENERGINET_CODES = listOf("40000", "41000", "EA-001")
    const val TARIFF_REFRESH_MS = 24 * 3_600_000L
    private const val SPOT_STALE_MS = 48 * 3_600_000L
    private const val TARIFF_STALE_MS = 14 * 24 * 3_600_000L
    private const val GAP_MS = 1_500L
    private const val RETRY_MS = 10_000L
    private val json = Json { ignoreUnknownKeys = true }

    fun spotUrl(area: String, nowMs: Long): String {
        val f = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
        val today = Instant.ofEpochMilli(nowMs).atZone(DK).toLocalDate()
        val from = today.minusDays(8).atStartOfDay().format(f)
        val to = today.plusDays(2).atStartOfDay().format(f)
        return "${EDS}DayAheadPrices?start=$from&end=$to&filter=%7B%22PriceArea%22:%5B%22$area%22%5D%7D&columns=TimeUTC,DayAheadPriceDKK&sort=TimeUTC%20asc"
    }

    fun energinetUrl(fromDate: LocalDate) =
        "${EDS}DatahubPricelist?filter=%7B%22GLN_Number%22:%5B%22$ENERGINET_GLN%22%5D,%22ChargeTypeCode%22:%5B" +
            ENERGINET_CODES.joinToString(",") { "%22$it%22" } + "%5D%7D&start=$fromDate&sort=ValidFrom%20asc"

    fun companiesUrl(fromDate: LocalDate) =
        "${EDS}DatahubPricelist?filter=%7B%22Note%22:%5B%22Nettarif%20C%22%5D%7D&start=$fromDate&columns=ChargeOwner,GLN_Number&limit=5000"

    /** Every company's "Nettarif C" lines from [fromDate] (all columns, no row limit). */
    fun allGridUrl(fromDate: LocalDate) =
        "${EDS}DatahubPricelist?filter=%7B%22Note%22:%5B%22Nettarif%20C%22%5D%7D&start=$fromDate&sort=ValidFrom%20asc&limit=0"

    /** Records of [allBody] grouped by GLN_Number → `{"records":[…]}` per company, in source order. */
    fun splitByGln(allBody: String): Map<String, String> {
        val records = (json.parseToJsonElement(allBody) as? JsonObject)?.get("records") as? JsonArray ?: return emptyMap()
        return records.filterIsInstance<JsonObject>()
            .groupBy { (it["GLN_Number"] as? JsonPrimitive)?.content.orEmpty() }
            .filterKeys { it.matches(Regex("\\d{13}")) }
            .mapValues { (_, rs) -> JsonObject(mapOf("records" to JsonArray(rs))).toString() }
    }

    /** `{"records":[{"ChargeOwner","GLN_Number"}…]}` — one per company, as the app's companies query returns. */
    fun companies(allBody: String): String {
        val records = (json.parseToJsonElement(allBody) as? JsonObject)?.get("records") as? JsonArray ?: return """{"records":[]}"""
        val rows = records.filterIsInstance<JsonObject>().mapNotNull { r ->
            val gln = (r["GLN_Number"] as? JsonPrimitive)?.content?.takeIf { it.matches(Regex("\\d{13}")) } ?: return@mapNotNull null
            gln to ((r["ChargeOwner"] as? JsonPrimitive)?.content ?: gln)
        }.distinctBy { it.first }
        return JsonObject(mapOf("records" to JsonArray(rows.map { (g, n) -> JsonObject(mapOf("ChargeOwner" to JsonPrimitive(n), "GLN_Number" to JsonPrimitive(g))) }))).toString()
    }

    fun gridUrl(gln: String, fromDate: LocalDate) =
        "${EDS}DatahubPricelist?filter=%7B%22GLN_Number%22:%5B%22$gln%22%5D,%22Note%22:%5B%22Nettarif%20C%22%5D%7D&start=$fromDate&sort=ValidFrom%20asc"

    /** GLN numbers in a companies response (13 digits), unique, sorted. */
    fun glns(companiesBody: String): List<String> {
        val records = (json.parseToJsonElement(companiesBody) as? JsonObject)?.get("records") as? JsonArray ?: return emptyList()
        return records.mapNotNull { (it as? JsonObject)?.get("GLN_Number") as? JsonPrimitive }
            .map { it.content }.filter { it.matches(Regex("\\d{13}")) }.distinct().sorted()
    }

    /** A body with at least one record (an empty answer counts as a failure). */
    private fun hasRecords(body: String): Boolean =
        runCatching { ((json.parseToJsonElement(body) as JsonObject)["records"] as JsonArray).isNotEmpty() }.getOrDefault(false)

    /** @return one status line per file group, for the log. */
    suspend fun write(siteDir: File, stateDir: File, http: HttpTransport, nowMs: Long): List<String> {
        val out = File(siteDir, "v1/home").apply { deleteRecursively(); mkdirs() }
        val state = File(stateDir, "home").apply { mkdirs() }
        val log = ArrayList<String>()
        val from = Instant.ofEpochMilli(nowMs).atZone(DK).toLocalDate().minusDays(400)

        suspend fun fetch(name: String, url: String, refreshMs: Long, staleMs: Long): String? {
            val cached = File(state, name)
            val fresh = cached.exists() && nowMs - cached.lastModified() < refreshMs
            if (!fresh) {
                // Energi Data Service answers 429 to bursts: a pause before each request, retries with growing waits.
                var r: dk.fullcharge.core.net.HttpResponse? = null
                for (attempt in 0..3) {
                    kotlinx.coroutines.delay(if (attempt == 0) GAP_MS else RETRY_MS * attempt)
                    r = runCatching { http.get(url, mapOf("Accept" to "application/json")) }.getOrNull()
                    if (r?.statusCode != 429) break
                }
                if (r != null && r.isSuccess && hasRecords(r.body)) {
                    cached.writeText(r.body)
                } else if (!cached.exists() || nowMs - cached.lastModified() >= staleMs) {
                    log += "$name: " + (r?.let { "HTTP ${it.statusCode}" } ?: "not reachable") + ", no copy"
                    return null
                } else {
                    log += "$name: " + (r?.let { "HTTP ${it.statusCode}" } ?: "not reachable") + ", last good copy"
                }
            }
            val text = cached.readText()
            File(out, name).writeText(text)
            return text
        }

        for (area in listOf("DK1", "DK2")) fetch("spot-$area.json", spotUrl(area, nowMs), 0, SPOT_STALE_MS)
        fetch("tariffs-energinet.json", energinetUrl(from), TARIFF_REFRESH_MS, TARIFF_STALE_MS)
        // All private-home grid tariffs in ONE request (EDS rate-limits bursts), split per company here.
        val all = fetch("tariffs-grid-all.json", allGridUrl(from), TARIFF_REFRESH_MS, TARIFF_STALE_MS)
        File(out, "tariffs-grid-all.json").delete() // only the split files are published
        val byGln = all?.let(::splitByGln).orEmpty()
        byGln.forEach { (gln, body) -> File(out, "tariffs-grid-$gln.json").writeText(body) }
        File(out, "companies.json").writeText(all?.let(::companies) ?: """{"records":[]}""")
        val list = byGln.keys.toList()
        val grids = list.size
        log += "home: spot DK1/DK2, ${list.size} grid companies, $grids tariff files"
        if (list.isNotEmpty() && grids == 0) throw IOException("no grid tariffs")
        return log
    }
}
