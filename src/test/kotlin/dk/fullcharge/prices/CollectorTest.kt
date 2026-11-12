package dk.fullcharge.prices

import dk.fullcharge.core.pricing.AfirChargePoint
import dk.fullcharge.core.pricing.AfirSite
import dk.fullcharge.core.pricing.Datex2AfirParser
import dk.fullcharge.core.pricing.LadekroneTiles
import dk.fullcharge.core.pricing.PriceElement
import dk.fullcharge.core.pricing.PriceTiles
import dk.fullcharge.core.pricing.PriceType
import dk.fullcharge.core.pricing.SpiriiFeed
import dk.fullcharge.core.pricing.SwissPriceBundle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.DayOfWeek
import java.time.LocalDate

class CollectorTest {
    private fun res(path: String) = javaClass.getResource(path)!!.readText()

    private val spirii = Datex2AfirParser(SpiriiFeed.ZONE, SpiriiFeed.SOURCE).parse(res("/afir/spirii_tables_fixture.json"), null)
    private val germany = LadekroneTiles.parseTile(res("/ladekrone/tile_214_40_trimmed.json"))
    private val swiss = SwissPriceBundle.sites(res("/swiss/stations_trimmed.json"), SwissPriceBundle.parsePrices(res("/swiss/prices-v2_trimmed.json")))

    @Test fun `tile format round trip is lossless for every parser`() {
        for (sites in listOf(spirii, germany, swiss)) {
            assertTrue(sites.isNotEmpty())
            val decoded = PriceTiles.decodeTile(PriceTiles.encodeTile("x", "2026-11-12T00:00:00Z", sites))
            assertEquals(sites, decoded)
        }
        // A dynamic Spirii slot (day + window) survives too.
        assertTrue(spirii.flatMap { it.chargePoints }.flatMap { it.prices }.any { it.dayLocal != null && it.windowsMinOfDay.isNotEmpty() })
    }

    @Test fun `every element field round trips golden`() {
        val e = PriceElement(
            PriceType.PER_MINUTE, 0.24, "EUR", dayLocal = LocalDate.of(2026, 11, 12), windowsMinOfDay = listOf(1140 to 0),
            days = setOf(DayOfWeek.MONDAY, DayOfWeek.SUNDAY), fromMinute = 30, validFromEpochMs = 1L, validToEpochMs = 2L,
        )
        val site = AfirSite("s", "Op", 55.0, 9.0, "Street 1", "DK", listOf(AfirChargePoint("c", "DK*X*E1", true, 150.0, listOf(e))), "DK · test")
        val text = PriceTiles.encodeTile("220_36", "g", listOf(site))
        assertTrue(text.contains("\"days\":[1,7]")) // ISO weekdays
        assertTrue(text.contains("\"win\":[[1140,0]]"))
        assertEquals(listOf(site), PriceTiles.decodeTile(text))
        assertNull(PriceTiles.decodeTile("""{"v":2,"sites":[]}"""))
        assertEquals("220_36", PriceTiles.tileKey(55.0, 9.0))
    }

    @Test fun `pruning drops past dynamic days and ended periods`() {
        val now = java.time.ZonedDateTime.of(2026, 11, 12, 10, 0, 0, 0, SpiriiFeed.ZONE).toInstant().toEpochMilli()
        val keep = PriceElement(PriceType.PER_KWH, 3.0, "DKK")
        val today = keep.copy(dayLocal = LocalDate.of(2026, 11, 12))
        val yesterday = keep.copy(dayLocal = LocalDate.of(2026, 11, 11))
        val ended = keep.copy(validToEpochMs = now - 1)
        val site = AfirSite("s", null, 55.0, 9.0, null, null, listOf(AfirChargePoint("c", null, true, 50.0, listOf(keep, today, yesterday, ended))), "x")
        assertEquals(listOf(keep, today), Pruning.prune(site, now).chargePoints.single().prices)
    }

    private fun source(refreshMs: Long = 0, needs: List<String> = emptyList(), fetch: suspend (Map<String, String>) -> List<AfirSite>) =
        Source("t", "Test", "DK", "lic", "attr", refreshMs, needs, fetch)

    @Test fun `snapshot fallback, cache, missing key`() = runTest {
        val dir = Files.createTempDirectory("state").toFile()
        val t0 = 1_000_000_000_000L
        // 1st run ok → snapshot written.
        val ok = collect(source { germany }, dir, emptyMap(), t0)
        assertEquals("ok", ok.status)
        assertTrue(File(dir, "t.json.gz").exists())
        // Fails 1 h later → last good data, marked stale.
        val stale = collect(source { throw IOException("down") }, dir, emptyMap(), t0 + 3_600_000L)
        assertEquals("stale", stale.status)
        assertEquals(germany, stale.sites)
        assertTrue(stale.error!!.contains("down"))
        // Fails after 48 h → nothing.
        assertEquals("failed", collect(source { throw IOException("down") }, dir, emptyMap(), t0 + STALE_MS + 1).status)
        // Within refreshMs → reused without fetching.
        val cached = collect(source(refreshMs = 3 * 3_600_000L) { throw AssertionError("must not fetch") }, dir, emptyMap(), t0 + 3_600_000L)
        assertEquals("cached", cached.status)
        // Missing secret → no-key, no fetch.
        val noKey = collect(source(needs = listOf("ECO_MOVEMENT_TOKEN")) { throw AssertionError("must not fetch") }, dir, emptyMap(), t0)
        assertEquals("no-key", noKey.status)
        dir.deleteRecursively()
    }

    @Test fun `publisher writes tiles and meta`() {
        val dir = Files.createTempDirectory("site").toFile()
        val now = 1_794_000_000_000L
        Publisher.write(dir, listOf(SourceReport(source { germany }, "ok", null, now, germany), SourceReport(source { swiss }, "ok", null, now, swiss)), now)
        val meta = PriceTiles.decodeMeta(File(dir, "v1/meta.json").readText())!!
        assertTrue("214_40" in meta.tiles)
        val tile = PriceTiles.decodeTile(File(dir, "v1/tiles/214_40.json").readText())!!
        assertEquals(germany.map { it.id }.toSet(), tile.map { it.id }.toSet())
        assertEquals(meta.tiles, File(dir, "v1/tiles").list()!!.map { it.removeSuffix(".json") }.toSet())
        dir.deleteRecursively()
    }
}
