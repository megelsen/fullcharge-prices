package dk.fullcharge.core.pricing

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** DATEX II `priceType`. */
enum class PriceType { PER_KWH, PER_MINUTE, FLAT }

/**
 * One AFIR ad-hoc price element of a charge point (DATEX II `energyPrice`).
 *
 * @property valueInclVat price including VAT, in [currency]: per kWh, per minute, or once (flat).
 * @property dayLocal the day a dynamic (15-min) slot belongs to, local date of the feed's zone
 *   (from `overallStartTime`); null = every day.
 * @property windowsMinOfDay local times of day it applies, [start, end) in minutes after
 *   midnight (end may be ≤ start = past midnight); empty = all day.
 * @property days weekdays it applies; empty = every day.
 * @property fromMinute per-minute fee only from this minute of the session on (e.g. parking after
 *   240 min); 0 = from the start.
 * @property validFromEpochMs, validToEpochMs `overallPeriod`, ms since epoch; null = open.
 */
data class PriceElement(
    val type: PriceType,
    val valueInclVat: Double,
    val currency: String,
    val dayLocal: LocalDate? = null,
    val windowsMinOfDay: List<Pair<Int, Int>> = emptyList(),
    val days: Set<DayOfWeek> = emptySet(),
    val fromMinute: Int = 0,
    val validFromEpochMs: Long? = null,
    val validToEpochMs: Long? = null,
) {
    /** True if this element applies at [tsEpochMs] in [zone]. */
    fun appliesAt(tsEpochMs: Long, zone: ZoneId): Boolean {
        if (validFromEpochMs != null && tsEpochMs < validFromEpochMs) return false
        if (validToEpochMs != null && tsEpochMs > validToEpochMs) return false
        val local = Instant.ofEpochMilli(tsEpochMs).atZone(zone)
        if (dayLocal != null && local.toLocalDate() != dayLocal) return false
        if (days.isNotEmpty() && local.dayOfWeek !in days) return false
        if (windowsMinOfDay.isEmpty()) return true
        val m = local.hour * 60 + local.minute
        return windowsMinOfDay.any { (s, e) -> if (e > s) m in s until e else m >= s || m < e }
    }

    /** How specific the element is: dynamic slot > time window > all day (used to pick between matches). */
    val specificity: Int get() = (if (dayLocal != null) 4 else 0) + (if (windowsMinOfDay.isNotEmpty()) 2 else 0) + (if (days.isNotEmpty()) 1 else 0)
}

/**
 * One charge point (EVSE) with its ad-hoc prices.
 * @property maxPowerKw highest connector power, kW (DATEX II gives W).
 */
data class AfirChargePoint(
    val id: String,
    val evseId: String?,
    val isDc: Boolean,
    val maxPowerKw: Double,
    val prices: List<PriceElement>,
)

/** One charging site from an AFIR feed. Degrees WGS84. */
data class AfirSite(
    val id: String,
    val operator: String?,
    val latDeg: Double,
    val lonDeg: Double,
    val address: String?,
    val countryCode: String?,
    val chargePoints: List<AfirChargePoint>,
    /** Feed it came from, e.g. "DK · Spirii". */
    val source: String,
) {
    val maxDcPowerKw: Double get() = chargePoints.filter { it.isDc }.maxOfOrNull { it.maxPowerKw } ?: 0.0
}

/** Parses "16:15" / "24:00" to minutes after midnight. */
internal fun minutesOfDay(hhmm: String): Int? {
    val p = hhmm.split(':')
    val h = p.getOrNull(0)?.toIntOrNull() ?: return null
    val m = p.getOrNull(1)?.toIntOrNull() ?: 0
    return (h * 60 + m).takeIf { it in 0..24 * 60 }?.let { if (it == 24 * 60) 0 else it }
}
