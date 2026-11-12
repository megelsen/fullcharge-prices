package dk.fullcharge.core.pricing

import java.time.ZoneId
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Cost of one charge at AFIR ad-hoc prices (incl. VAT).
 * @property totalInclVat currency units (DKK in Denmark)
 * @property startPerKwh price per kWh when charging starts
 * @property meanPerKwh energy-weighted price per kWh over the charge
 * @property minutesWithoutPrice minutes for which no per-kWh price applied (cost counted as 0 there)
 */
data class CostEstimate(
    val totalInclVat: Double,
    val currency: String,
    val energyKwh: Double,
    val startPerKwh: Double,
    val meanPerKwh: Double,
    val timeFeeInclVat: Double,
    val minutesWithoutPrice: Int,
)

object ChargeCost {
    /**
     * Per-kWh price at [tsEpochMs]: the most specific element that applies (dynamic slot > window >
     * all day). A price of 0 counts as "no price": the Spirii feed publishes 0 DKK on test and
     * placeholder sites (seen 2026-11-11), and free charge points need not publish prices under AFIR.
     */
    fun perKwhAt(cp: AfirChargePoint, tsEpochMs: Long, zone: ZoneId): PriceElement? =
        cp.prices.filter { it.type == PriceType.PER_KWH && it.valueInclVat > 0.0 && it.appliesAt(tsEpochMs, zone) }.maxByOrNull { it.specificity }

    /**
     * Cost of charging [energyKwh] kWh in [durationMin] minutes from [startEpochMs]. Energy is
     * spread evenly over the minutes (constant power); each minute pays the per-kWh price of its
     * slot, plus a per-minute fee from its `fromMinute` on, plus flat fees once.
     *
     *     total = Σ_minutes (E / D) · p_kWh(t) + Σ_minutes fee_min(t, minute) + Σ flat
     *
     * Null when no per-kWh price applies at any minute.
     */
    fun estimate(cp: AfirChargePoint, startEpochMs: Long, durationMin: Double, energyKwh: Double, zone: ZoneId): CostEstimate? {
        val minutes = maxOf(1, Math.round(durationMin).toInt())
        val kwhPerMin = energyKwh / minutes
        var energyCost = 0.0
        var timeFee = 0.0
        var missing = 0
        var currency: String? = null
        for (k in 0 until minutes) {
            val t = startEpochMs + k * 60_000L + 30_000L // middle of the minute
            val p = perKwhAt(cp, t, zone)
            if (p == null) missing++ else {
                energyCost += kwhPerMin * p.valueInclVat
                currency = currency ?: p.currency
            }
            timeFee += cp.prices.filter { it.type == PriceType.PER_MINUTE && k >= it.fromMinute && it.appliesAt(t, zone) }
                .maxByOrNull { it.specificity }?.valueInclVat ?: 0.0
        }
        if (missing == minutes) return null
        val flat = cp.prices.filter { it.type == PriceType.FLAT && it.appliesAt(startEpochMs, zone) }.sumOf { it.valueInclVat }
        val start = perKwhAt(cp, startEpochMs + 30_000L, zone) ?: cp.prices.firstOrNull { it.type == PriceType.PER_KWH && it.valueInclVat > 0.0 }
        val pricedKwh = kwhPerMin * (minutes - missing)
        return CostEstimate(
            totalInclVat = energyCost + timeFee + flat,
            currency = currency ?: "?",
            energyKwh = energyKwh,
            startPerKwh = start?.valueInclVat ?: 0.0,
            meanPerKwh = if (pricedKwh > 0) energyCost / pricedKwh else 0.0,
            timeFeeInclVat = timeFee + flat,
            minutesWithoutPrice = missing,
        )
    }

    /** Per-kWh price every 15 min from [fromEpochMs] to [toEpochMs] (price bars); null = no price in that slot. */
    fun slots(cp: AfirChargePoint, fromEpochMs: Long, toEpochMs: Long, zone: ZoneId): List<Pair<Long, Double?>> {
        val step = 15 * 60_000L
        val first = fromEpochMs / step * step
        return generateSequence(first) { it + step }.takeWhile { it < toEpochMs }
            .map { it to perKwhAt(cp, it + step / 2, zone)?.valueInclVat }.toList()
    }
}

/** A planner charger matched to an AFIR charge point. */
data class AfirMatch(val site: AfirSite, val chargePoint: AfirChargePoint, val distanceM: Double)

object AfirMatcher {
    const val MAX_DISTANCE_M = 150.0

    /**
     * The nearest site within [maxDistanceM] of the charger that has a priced charge point of the
     * wanted kind (DC for fast charging); its most powerful such charge point.
     */
    fun match(sites: List<AfirSite>, latDeg: Double, lonDeg: Double, dc: Boolean = true, maxDistanceM: Double = MAX_DISTANCE_M): AfirMatch? =
        sites.asSequence()
            .map { it to distanceM(latDeg, lonDeg, it.latDeg, it.lonDeg) }
            .filter { it.second <= maxDistanceM }
            .sortedBy { it.second }
            .firstNotNullOfOrNull { (s, d) ->
                s.chargePoints.filter { it.isDc == dc && it.prices.any { p -> p.type == PriceType.PER_KWH && p.valueInclVat > 0.0 } }
                    .maxByOrNull { it.maxPowerKw }?.let { AfirMatch(s, it, d) }
            }

    /** Great-circle distance, m. */
    fun distanceM(lat1Deg: Double, lon1Deg: Double, lat2Deg: Double, lon2Deg: Double): Double {
        val p1 = Math.toRadians(lat1Deg)
        val p2 = Math.toRadians(lat2Deg)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2Deg - lon1Deg)
        val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * 6_371_008.8 * asin(min(1.0, sqrt(h)))
    }
}
