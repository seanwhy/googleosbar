package com.meridian11.books.bentobar.items

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Pure rules for the Sunset item: when the sun rises and sets, worked out on the device from the date and a place, with
 * nothing asked of anyone. The arithmetic is the standard sunrise equation (the sun's centre 0.833° below the horizon, for
 * refraction and the sun's half width) with the sun's place from NOAA's solar calculator, good to about a minute below
 * the polar circles. The place is the time zone's main city ([SunZones]), so the times are "around" it. The item is [SunItem].
 */
object SunRules {
    /** A day with a [rise] and a [set] (UTC ms), or one when the sun stays up, or stays down. */
    sealed interface Day {
        data class Normal(val rise: Long, val set: Long) : Day
        data object AlwaysUp : Day
        data object NeverUp : Day
    }

    /** The next sunrise ([rises]) or sunset, [at] UTC ms. */
    data class Next(val rises: Boolean, val at: Long)

    /** The words of [text]; English here, the app's from its text resources. */
    class Words(
        val minutes: (Long) -> String = { "$it min" },
        val hoursMinutes: (Long, Long) -> String = { h, m -> String.format(Locale.ROOT, "%d h %02d", h, m) },
    )

    private const val RAD = Math.PI / 180
    private const val DAY_MS = 86_400_000L
    private const val MINUTE_MS = 60_000.0
    /** The sun's centre this far from overhead at sunrise and sunset: 90° plus 34' of refraction and 16' of its half width. */
    private const val ZENITH = 90.833

    /** The sun's declination (degrees) and the equation of time (minutes) at [ms], from NOAA's solar calculator. */
    private fun sun(ms: Double): Pair<Double, Double> {
        val t = (ms / DAY_MS + 2440587.5 - 2451545.0) / 36525.0
        val l0 = (280.46646 + t * (36000.76983 + t * 0.0003032)) % 360
        val m = 357.52911 + t * (35999.05029 - 0.0001537 * t)
        val e = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)
        val c = sin(m * RAD) * (1.914602 - t * (0.004817 + 0.000014 * t)) + sin(2 * m * RAD) * (0.019993 - 0.000101 * t) +
            sin(3 * m * RAD) * 0.000289
        val omega = 125.04 - 1934.136 * t
        val lambda = l0 + c - 0.00569 - 0.00478 * sin(omega * RAD)
        val eps0 = 23 + (26 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60) / 60
        val eps = eps0 + 0.00256 * cos(omega * RAD)
        val decl = asin(sin(eps * RAD) * sin(lambda * RAD)) / RAD
        val y = tan(eps * RAD / 2).let { it * it }
        val eot = 4 * (y * sin(2 * l0 * RAD) - 2 * e * sin(m * RAD) + 4 * e * y * sin(m * RAD) * cos(2 * l0 * RAD) -
            0.5 * y * y * sin(4 * l0 * RAD) - 1.25 * e * e * sin(2 * m * RAD)) / RAD
        return decl to eot
    }

    /** The cosine of the sun's hour angle at sunrise at [lat] with the sun at [decl]: above 1 it never rises, below -1 it never sets. */
    private fun cosHour(lat: Double, decl: Double): Double =
        (cos(ZENITH * RAD) - sin(lat * RAD) * sin(decl * RAD)) / (cos(lat * RAD) * cos(decl * RAD))

    /**
     * The sunrise and sunset of [date] at [lat], [lon] (degrees, east positive): those of the solar day around that date's
     * local noon, so far west the sunset falls on the next day in UTC and far east the sunrise on the day before. Whether
     * the sun rises at all is judged at that noon; each time is then worked out again with the sun where it stands then.
     */
    fun day(date: LocalDate, lat: Double, lon: Double): Day {
        val midday = date.toEpochDay() * DAY_MS + DAY_MS / 2.0
        // Local noon by the sun: 4 minutes a degree west of Greenwich, less the equation of time.
        fun noonWith(eot: Double) = midday - (4 * lon + eot) * MINUTE_MS
        var noon = midday
        repeat(3) { noon = noonWith(sun(noon).second) }
        val c = cosHour(lat, sun(noon).first)
        if (c > 1) return Day.NeverUp
        if (c < -1) return Day.AlwaysUp
        fun event(sign: Int): Long {
            var at = noon + sign * acos(c) / RAD * 4 * MINUTE_MS
            repeat(4) {
                val (decl, eot) = sun(at)
                val ci = cosHour(lat, decl)
                // At the polar edge the sun may no longer reach the horizon at that hour: keep the last time that did.
                if (ci > 1 || ci < -1) return at.toLong()
                at = noonWith(eot) + sign * acos(ci) / RAD * 4 * MINUTE_MS
            }
            return at.toLong()
        }
        return Day.Normal(event(-1), event(1))
    }

    /**
     * The next sunrise or sunset after [now] at the place (only sunrises with [rises] true, only sunsets with false), from the
     * days around [now]'s date in [zone]; null when there is none within them: a polar day or night.
     */
    fun next(now: Long, lat: Double, lon: Double, zone: ZoneId, rises: Boolean? = null): Next? {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        var best: Next? = null
        for (offset in -1L..2L) {
            val d = day(today.plusDays(offset), lat, lon) as? Day.Normal ?: continue
            for (n in listOf(Next(true, d.rise), Next(false, d.set))) {
                if (n.at <= now || (rises != null && n.rises != rises)) continue
                if (best == null || n.at < best.at) best = n
            }
        }
        return best
    }

    /** The time until [next]: "48 min", "1 h 12", rounded up, so it says "1 min" until the moment itself. */
    fun text(next: Next, now: Long, words: Words = Words()): String {
        val minutes = ((next.at - now).coerceAtLeast(0) + 59_999) / 60_000
        return if (minutes < 60) words.minutes(minutes) else words.hoursMinutes(minutes / 60, minutes % 60)
    }

    /**
     * The main city of the time zone [zone]: as the table holds it, else under the name [canonical] gives it (Android may name
     * a zone by an older alias, `Asia/Calcutta` for `Asia/Kolkata`). Null when neither is there: a zone with no city, `UTC`.
     */
    fun place(zone: String, canonical: (String) -> String = { it }): SunZones.Place? =
        SunZones.TABLE[zone] ?: runCatching { canonical(zone) }.getOrNull()?.let { SunZones.TABLE[it] }
}
