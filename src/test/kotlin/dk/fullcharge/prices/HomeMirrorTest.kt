package dk.fullcharge.prices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class HomeMirrorTest {
    @Test fun `spot window is 8 days back to the day after tomorrow, local midnight`() {
        val now = ZonedDateTime.of(2026, 11, 12, 21, 30, 0, 0, ZoneId.of("Europe/Copenhagen")).toInstant().toEpochMilli()
        val url = HomeMirror.spotUrl("DK1", now)
        assertTrue(url, url.contains("start=2026-11-04T00:00&end=2026-11-14T00:00"))
        assertTrue(url.contains("%22DK1%22"))
    }

    @Test fun `all grid tariffs split per company, companies list`() {
        val all = """{"records":[{"ChargeOwner":"N1 A/S","GLN_Number":"5790001089030","Price1":0.1},{"ChargeOwner":"Radius","GLN_Number":"5790000610099","Price1":0.2},{"ChargeOwner":"N1 A/S","GLN_Number":"5790001089030","Price1":0.3}]}"""
        val split = HomeMirror.splitByGln(all)
        assertEquals(setOf("5790001089030", "5790000610099"), split.keys)
        assertEquals("""{"records":[{"ChargeOwner":"N1 A/S","GLN_Number":"5790001089030","Price1":0.1},{"ChargeOwner":"N1 A/S","GLN_Number":"5790001089030","Price1":0.3}]}""", split["5790001089030"])
        assertEquals(listOf("5790000610099", "5790001089030"), HomeMirror.glns(HomeMirror.companies(all)))
    }

    @Test fun `grid company numbers from a companies answer`() {
        val body = """{"records":[{"ChargeOwner":"N1 A/S","GLN_Number":"5790001089030"},{"ChargeOwner":"N1 A/S","GLN_Number":"5790001089030"},{"ChargeOwner":"Radius","GLN_Number":"5790000610099"},{"ChargeOwner":"x","GLN_Number":"bad"}]}"""
        assertEquals(listOf("5790000610099", "5790001089030"), HomeMirror.glns(body))
        assertTrue(HomeMirror.gridUrl("5790001089030", LocalDate.of(2025, 10, 8)).contains("start=2025-10-08"))
    }
}
