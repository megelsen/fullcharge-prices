package dk.fullcharge.core.pricing

import dk.fullcharge.core.net.HttpTransport
import kotlinx.coroutines.delay
import java.io.IOException
import java.io.InputStream
import java.time.ZoneId

/**
 * Denmark's Spirii AFIR feed (DATEX II, public, no key), one of the Danish national access point
 * datasets. The table publication carries sites, charge points and the ad-hoc prices (incl. the
 * 15-min dynamic slots for today and, from ≈ 13:00, tomorrow), so the status publication is not
 * needed for prices.
 *
 * The feed stops at 1 000 sites per request and ignores paging, so it is read one operator
 * (`cpoId`) at a time, 0.25 s apart. [CPO_IDS_DK]: from a scan of ids 1–200 on 2026-11-11
 * (22 operators, 2 305 sites; id 17 "Test CPO" left out). New operators need a new scan.
 */
class SpiriiFeed(private val http: HttpTransport, private val cpoIds: List<Int> = CPO_IDS_DK) {
    /**
     * Downloads each operator's table publication into [sink] (streamed: up to ≈ 50 MB of JSON per
     * operator, ≈ 1 MB gzip). @return ids that succeeded; failures go to [onError].
     */
    suspend fun downloadTables(sink: (cpoId: Int, body: InputStream) -> Unit, onError: (Int, String) -> Unit = { _, _ -> }): List<Int> {
        val ok = ArrayList<Int>()
        for ((i, id) in cpoIds.withIndex()) {
            if (i > 0) delay(REQUEST_GAP_MS)
            try {
                val code = http.download(tableUrl(id), mapOf("Accept" to "application/json")) { sink(id, it) }
                if (code in 200..299) ok += id else onError(id, "HTTP $code")
            } catch (e: IOException) {
                onError(id, e.message ?: "network error")
            }
        }
        return ok
    }

    companion object {
        const val SOURCE = "DK · Spirii"
        val ZONE: ZoneId = ZoneId.of("Europe/Copenhagen")
        const val REQUEST_GAP_MS = 250L
        val CPO_IDS_DK = listOf(1, 4, 7, 8, 10, 16, 20, 25, 36, 37, 44, 47, 50, 56, 57, 59, 83, 84, 90, 99, 106, 140)

        fun tableUrl(cpoId: Int) = "https://api.spirii.com/v2/afir/energy-infrastructure-tables?countryIsos=DK&cpoId=$cpoId"
    }
}
