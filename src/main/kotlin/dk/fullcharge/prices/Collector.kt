package dk.fullcharge.prices

import dk.fullcharge.core.net.HttpTransport
import dk.fullcharge.core.net.UrlConnectionTransport
import dk.fullcharge.core.pricing.AfirSite
import dk.fullcharge.core.pricing.Datex2AfirParser
import dk.fullcharge.core.pricing.DigitrafficAfir
import dk.fullcharge.core.pricing.DigitrafficAfirFeed
import dk.fullcharge.core.pricing.EcoMovementFeed
import dk.fullcharge.core.pricing.JsonArrayStreamer
import dk.fullcharge.core.pricing.LadekroneTiles
import dk.fullcharge.core.pricing.MontaFeed
import dk.fullcharge.core.pricing.OcpiTariffs
import dk.fullcharge.core.pricing.PriceTiles
import dk.fullcharge.core.pricing.SpiriiFeed
import dk.fullcharge.core.pricing.SwissPriceBundle
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * One source of charging prices.
 * @property refreshMs fetch again only when the last good snapshot is older (0 = every run).
 * @property needs environment variables (secrets) the source needs; missing → status "no-key".
 */
class Source(
    val key: String,
    val name: String,
    val country: String,
    val license: String,
    val attribution: String,
    val refreshMs: Long,
    val needs: List<String> = emptyList(),
    val fetch: suspend (env: Map<String, String>) -> List<AfirSite>,
)

/** What happened to a source in this run (published in meta.json). */
data class SourceReport(
    val source: Source,
    val status: String,
    val error: String?,
    val fetchedAtMs: Long?,
    val sites: List<AfirSite>,
)

/**
 * Collects all sources, keeps the last good snapshot per source in [stateDir] (restored by the
 * workflow's cache) and writes v1 tiles + meta.json to `<siteDir>/v1/`.
 *
 * Usage: `collector <siteDir> <stateDir>`. Secrets come from the environment.
 */
fun main(args: Array<String>) = runBlocking {
    val siteDir = File(args.getOrElse(0) { "site" })
    val stateDir = File(args.getOrElse(1) { "state" }).apply { mkdirs() }
    val http = UrlConnectionTransport(connectTimeoutMs = 20_000, readTimeoutMs = 120_000)
    val now = System.currentTimeMillis()
    val reports = Sources.all(http).map { collect(it, stateDir, System.getenv(), now) }
    reports.forEach { r ->
        println("%-14s %-8s sites=%6d dcPriced=%6d %s".format(r.source.key, r.status, r.sites.size, dcPriced(r.sites), r.error ?: ""))
    }
    Publisher.write(siteDir, reports, now)
    // Home charging (0.17.2): Energi Data Service mirrored to v1/home/ (phones that reject its TLS chain).
    runCatching { HomeMirror.write(siteDir, stateDir, http, now) }
        .onSuccess { lines -> lines.forEach(::println) }
        .onFailure { println("home: ${it.javaClass.simpleName}: ${it.message}") }
    if (reports.none { it.status == "ok" || it.status == "cached" }) throw IllegalStateException("no source delivered data")
}

const val STALE_MS = 48 * 3_600_000L

suspend fun collect(source: Source, stateDir: File, env: Map<String, String>, nowMs: Long): SourceReport {
    val snapshot = File(stateDir, "${source.key}.json.gz")
    val previous = Snapshots.read(snapshot)
    if (previous != null && nowMs - previous.first < source.refreshMs) {
        return SourceReport(source, "cached", null, previous.first, previous.second)
    }
    val missing = source.needs.filter { env[it].isNullOrBlank() }
    if (missing.isNotEmpty()) return SourceReport(source, "no-key", "missing ${missing.joinToString()}", null, emptyList())
    return try {
        val sites = source.fetch(env)
        if (sites.isEmpty()) throw IOException("no sites")
        Snapshots.write(snapshot, nowMs, sites)
        SourceReport(source, "ok", null, nowMs, sites)
    } catch (e: Exception) {
        val msg = "${e.javaClass.simpleName}: ${e.message}".take(300)
        if (previous != null && nowMs - previous.first < STALE_MS) SourceReport(source, "stale", msg, previous.first, previous.second)
        else SourceReport(source, "failed", msg, previous?.first, emptyList())
    }
}

fun dcPriced(sites: List<AfirSite>): Int = sites.sumOf { s -> s.chargePoints.count { cp -> cp.isDc && cp.prices.any { it.valueInclVat > 0 } } }

/** Per-source snapshot: `{"fetched": ms, "sites": [v1 site…]}`, gzip. */
object Snapshots {
    private val json = Json { ignoreUnknownKeys = true }

    fun read(f: File): Pair<Long, List<AfirSite>>? = runCatching {
        if (!f.exists()) return null
        val o = json.parseToJsonElement(GZIPInputStream(f.inputStream()).reader().readText()).jsonObject
        val at = (o["fetched"] as? JsonPrimitive)?.longOrNull ?: return null
        at to (o["sites"] as JsonArray).mapNotNull { PriceTiles.decodeSite(it.jsonObject) }
    }.getOrNull()

    fun write(f: File, atMs: Long, sites: List<AfirSite>) {
        val text = buildJsonObject {
            put("fetched", atMs)
            put("sites", buildJsonArray { sites.forEach { add(PriceTiles.encodeSite(it)) } })
        }.toString()
        val tmp = File(f.parentFile, f.name + ".tmp")
        GZIPOutputStream(tmp.outputStream()).writer().use { it.write(text) }
        tmp.renameTo(f)
    }
}

/** The sources, Denmark first. Units and formats: see each parser's KDoc. */
object Sources {
    fun all(http: HttpTransport): List<Source> = listOf(
        Source(
            "dk-spirii", "Denmark · Spirii AFIR feed", "DK",
            "AFIR Art. 20 open data (Danish NAP datasets 1174/1175); no licence text published",
            "Spirii ApS (api.spirii.com), Danish national access point", 0,
        ) { _ ->
            val parser = Datex2AfirParser(SpiriiFeed.ZONE, SpiriiFeed.SOURCE)
            val out = ArrayList<AfirSite>()
            val errors = ArrayList<String>()
            val ok = SpiriiFeed(http).downloadTables({ _, body -> out += parser.parseStreaming(body.reader(Charsets.UTF_8)) }) { id, e -> errors += "operator $id: $e" }
            if (ok.isEmpty()) throw IOException(errors.firstOrNull() ?: "no data")
            out
        },
        Source(
            "dk-eco", "Denmark · Eco-Movement NAP feed", "DK", "CC BY 4.0 (Danish NAP dataset 1170)",
            "Eco-Movement B.V. (nap-dk.eco-movement.com)", 0, listOf("ECO_MOVEMENT_TOKEN"),
        ) { env ->
            val parser = Datex2AfirParser(SpiriiFeed.ZONE, EcoMovementFeed.SOURCE)
            val out = ArrayList<AfirSite>()
            EcoMovementFeed(http, env.getValue("ECO_MOVEMENT_TOKEN")).downloadPages { _, body -> countAndParse(body, parser, out) }
            out
        },
        Source(
            "dk-monta", "Denmark · Monta AFIR", "DK", "CC BY (Danish NAP dataset 1092)",
            "Monta ApS (public-api.monta.com)", 0, listOf("MONTA_CLIENT_ID", "MONTA_CLIENT_SECRET"),
        ) { env ->
            val parser = Datex2AfirParser(SpiriiFeed.ZONE, MontaFeed.SOURCE)
            val out = ArrayList<AfirSite>()
            MontaFeed(http, env.getValue("MONTA_CLIENT_ID"), env.getValue("MONTA_CLIENT_SECRET")).downloadPages("DK") { _, body -> countAndParse(body, parser, out) }
            out
        },
        Source(
            "de-ladekrone", "Germany · Mobilithek AFIR offers via ladekrone", "DE",
            "CC0 / CC BY 4.0 / DL-DE offers only (bundle code MIT)", LadekroneTiles.ATTRIBUTION,
            3 * 3_600_000L, // ladekrone publishes every 6 h
        ) { _ ->
            val metaText = http.get(LadekroneTiles.META_URL, mapOf("Accept" to "application/json"))
            if (!metaText.isSuccess) throw IOException("meta HTTP ${metaText.statusCode}")
            val meta = LadekroneTiles.parseMeta(metaText.body) ?: throw IOException("meta unreadable")
            if (meta.version != 1) throw IOException("ladekrone format v${meta.version}")
            val out = ArrayList<AfirSite>()
            var failed = 0
            for (k in meta.tiles.sorted()) {
                var text = ""
                val code = runCatching { http.download(LadekroneTiles.tileUrl(k), mapOf("Accept" to "application/json")) { text = it.reader(Charsets.UTF_8).readText() } }.getOrNull()
                if (code in 200..299) out += LadekroneTiles.parseTile(text) else failed++
            }
            if (failed > meta.tiles.size / 10) throw IOException("$failed of ${meta.tiles.size} tiles failed")
            out
        },
        Source(
            "ch-swiss", "Switzerland · Swiss eMobility price map (direct payment)", "CH",
            "O-By-Ask (opendata.swiss terms_by_ask): attribution; commercial use needs permission", SwissPriceBundle.ATTRIBUTION,
            12 * 3_600_000L, // bundle built daily 03:17 UTC
        ) { _ ->
            val stations = downloadText(http, SwissPriceBundle.STATIONS_URL)
            val prices = downloadText(http, SwissPriceBundle.PRICES_URL)
            SwissPriceBundle.sites(stations, SwissPriceBundle.parsePrices(prices))
        },
        Source(
            "fi-digitraffic", "Finland · Fintraffic Digitraffic AFIR", "FI", "CC BY 4.0", DigitrafficAfir.ATTRIBUTION, 0,
        ) { _ -> DigitrafficAfirFeed(http).sites().sites },
        Source(
            "nl-ndw", "Netherlands · NDW DOT-NL (OCPI 2.2.1)", "NL", "CC0 1.0", OcpiTariffs.NDW_ATTRIBUTION,
            3 * 3_600_000L, // two files of ≈ 19 + 5 MB gzip (≈ 290 MB JSON)
        ) { _ ->
            var tariffs: Map<OcpiTariffs.TariffKey, OcpiTariffs.Tariff>? = null
            val tc = http.download(OcpiTariffs.NDW_TARIFFS_URL, emptyMap()) { tariffs = OcpiTariffs.parseTariffs(ungzip(it).reader(Charsets.UTF_8), OcpiTariffs.NDW_ZONE) }
            if (tc !in 200..299 || tariffs == null) throw IOException("NDW tariffs HTTP $tc")
            val out = ArrayList<AfirSite>()
            val lc = http.download(OcpiTariffs.NDW_LOCATIONS_URL, emptyMap()) {
                // acceptRegular (owner, 2026-11-12): Vattenfall InCharge and six smaller CPOs publish their only
                // tariff as REGULAR; NDW maps the AFIR ad-hoc price to the Tariffs module without a type filter.
                // Used only where a connector has no AD_HOC_PAYMENT or untyped tariff.
                OcpiTariffs.forEachSite(ungzip(it).reader(Charsets.UTF_8), tariffs!!, OcpiTariffs.NDW_SOURCE, "NL", acceptRegular = true) { s -> out += s }
            }
            if (lc !in 200..299) throw IOException("NDW locations HTTP $lc")
            out
        },
        Source(
            "be-road", "Belgium · Road open OCPI files", "BE",
            "Operator's open download (AFIR Art. 20); no licence text published", "Road B.V. (roaming.road.io)",
            3 * 3_600_000L,
        ) { _ ->
            val base = "https://roaming.road.io/files/9ef09c78-2666-418a-aa45-4f2261e2e305"
            val tariffs = OcpiTariffs.parseTariffs(downloadText(http, "$base/tariffs.json"), BE_ZONE)
            OcpiTariffs.sites(downloadText(http, "$base/locations.json"), tariffs, "BE · Road", "BE")
        },
    )

    /** Belgium keeps the same time as Copenhagen (CET/CEST). */
    private val BE_ZONE = java.time.ZoneId.of("Europe/Brussels")

    /** A body that is itself a gzip file (e.g. `.json.gz`, also after transfer decoding) → unzipped stream. */
    internal fun ungzip(input: java.io.InputStream): java.io.InputStream {
        val b = java.io.BufferedInputStream(input)
        b.mark(2)
        val gz = b.read() == 0x1f && b.read() == 0x8b
        b.reset()
        return if (gz) java.util.zip.GZIPInputStream(b) else b
    }

    /** Whole body as text (gzip transfer encoding decoded by the transport; a gzip file body unzipped too). */
    private suspend fun downloadText(http: HttpTransport, url: String): String {
        var t = ""
        val code = http.download(url, mapOf("Accept" to "application/json")) { t = SwissPriceBundle.text(it.readBytes()) }
        if (code !in 200..299) throw IOException("$url HTTP $code")
        return t
    }

    /** Paged DATEX feeds: the caller needs the page's site count (sites without charge points are dropped by the parser). */
    private fun countAndParse(body: java.io.InputStream, parser: Datex2AfirParser, out: MutableList<AfirSite>): Int {
        val tmp = File.createTempFile("page", ".json")
        try {
            tmp.outputStream().use { body.copyTo(it) }
            var n = 0
            tmp.reader(Charsets.UTF_8).use { r -> JsonArrayStreamer.forEachElement(r, "energyInfrastructureSite") { n++ } }
            tmp.reader(Charsets.UTF_8).use { r -> out += parser.parseStreaming(r) }
            return n
        } finally {
            tmp.delete()
        }
    }
}

/** Writes `<siteDir>/v1/tiles/<key>.json`, `<siteDir>/v1/meta.json` and a small index page. */
object Publisher {
    fun write(siteDir: File, reports: List<SourceReport>, nowMs: Long) {
        val generated = Instant.ofEpochMilli(nowMs).toString()
        val tilesDir = File(siteDir, "v1/tiles").apply { deleteRecursively(); mkdirs() }
        val byTile = reports.flatMap { it.sites }.map { Pruning.prune(it, nowMs) }.filter { it.chargePoints.isNotEmpty() }
            .groupBy { PriceTiles.tileKey(it.latDeg, it.lonDeg) }
        for ((key, sites) in byTile) File(tilesDir, "$key.json").writeText(PriceTiles.encodeTile(key, generated, sites))
        val meta = buildJsonObject {
            put("v", PriceTiles.VERSION)
            put("generated", generated)
            put("tile_deg", PriceTiles.TILE_DEG)
            put("tiles", buildJsonArray { byTile.keys.sorted().forEach { add(JsonPrimitive(it)) } })
            put("sources", buildJsonArray {
                reports.forEach { r ->
                    add(buildJsonObject {
                        put("key", r.source.key)
                        put("name", r.source.name)
                        put("country", r.source.country)
                        put("license", r.source.license)
                        put("attribution", r.source.attribution)
                        put("status", r.status)
                        r.error?.let { put("error", it) }
                        r.fetchedAtMs?.let { put("fetched", Instant.ofEpochMilli(it).toString()) }
                        put("sites", r.sites.size)
                        put("dc_priced", dcPriced(r.sites))
                    })
                }
            })
        }
        File(siteDir, "v1/meta.json").writeText(meta.toString())
        File(siteDir, ".nojekyll").writeText("")
        File(siteDir, "index.html").writeText(
            "<!doctype html><meta charset=utf-8><title>FullCharge prices</title>" +
                "<p>Charging-price tiles for the FullCharge app, format v1. Sources, licences and status: " +
                "<a href=v1/meta.json>v1/meta.json</a>. Code: <a href=https://github.com/megelsen/fullcharge-prices>" +
                "github.com/megelsen/fullcharge-prices</a>. Generated $generated.</p>",
        )
    }
}

/** Drops price elements that can no longer apply: dynamic slots of past days and periods that ended. */
object Pruning {
    fun prune(s: AfirSite, nowMs: Long): AfirSite {
        val today = Instant.ofEpochMilli(nowMs).atZone(SpiriiFeed.ZONE).toLocalDate()
        return s.copy(chargePoints = s.chargePoints.map { cp ->
            cp.copy(prices = cp.prices.filter { e ->
                (e.dayLocal == null || !e.dayLocal.isBefore(today)) && (e.validToEpochMs == null || e.validToEpochMs >= nowMs)
            })
        })
    }
}
