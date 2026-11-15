package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.items.SunRules.Day
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.abs

/**
 * The sun's times, against what the standard sunrise equation gives for well-known places (each within three minutes),
 * and the table of places the item works them out for.
 */
class SunRulesTest {
    private val min = 60_000L
    private fun utc(text: String): Long = LocalDateTime.parse(text).toInstant(ZoneOffset.UTC).toEpochMilli()
    private fun near(what: String, expected: Long, actual: Long) =
        assertTrue("$what: ${Instant.ofEpochMilli(actual)}, not within 3 minutes of ${Instant.ofEpochMilli(expected)}", abs(expected - actual) <= 3 * min)
    private fun normal(d: Day): Day.Normal = d as? Day.Normal ?: throw AssertionError("not a day with a sunrise and a sunset: $d")

    @Test fun greenwichAtMidsummer() {
        val d = normal(SunRules.day(LocalDate.of(2026, 6, 21), 51.48, 0.0))
        near("rise", utc("2026-06-21T03:43"), d.rise)
        near("set", utc("2026-06-21T20:21"), d.set)
    }

    @Test fun theEquatorAtTheEquinox() {
        val d = normal(SunRules.day(LocalDate.of(2026, 3, 20), 0.0, 0.0))
        near("rise", utc("2026-03-20T06:04"), d.rise)
        near("set", utc("2026-03-20T18:11"), d.set)
    }

    @Test fun tromsoHasNoSunsetInJuneAndNoSunriseInDecember() {
        assertEquals(Day.AlwaysUp, SunRules.day(LocalDate.of(2026, 6, 21), 69.65, 18.96))
        assertEquals(Day.NeverUp, SunRules.day(LocalDate.of(2026, 12, 21), 69.65, 18.96))
        val oslo = ZoneId.of("Europe/Oslo")
        assertNull("no sunset to count down to", SunRules.next(utc("2026-06-21T10:00"), 69.65, 18.96, oslo))
        assertNull("no sunrise to count down to", SunRules.next(utc("2026-12-21T10:00"), 69.65, 18.96, oslo))
    }

    @Test fun afterSunsetTheNextIsTomorrowsSunrise() {
        val london = ZoneId.of("Europe/London")
        // London, 2027-03-10: sunrise about 06:26 UTC, sunset about 17:56.
        val evening = SunRules.next(utc("2027-03-10T16:00"), 51.5, -0.1, london)!!
        assertFalse(evening.rises)
        near("this evening's sunset", utc("2027-03-10T17:56"), evening.at)
        val night = SunRules.next(utc("2027-03-10T22:00"), 51.5, -0.1, london)!!
        assertTrue(night.rises)
        near("tomorrow's sunrise", utc("2027-03-11T06:24"), night.at)
        // In the small hours it is still that same sunrise, now today's.
        assertEquals(night, SunRules.next(utc("2027-03-11T01:00"), 51.5, -0.1, london))
        // Asked for one kind, the next of that kind.
        assertEquals(night.at, SunRules.next(utc("2027-03-10T16:00"), 51.5, -0.1, london, rises = true)!!.at)
        assertEquals(evening.at, SunRules.next(utc("2027-03-10T16:00"), 51.5, -0.1, london, rises = false)!!.at)
    }

    @Test fun farWestTheSunsetIsOnTheNextUtcDay() {
        // Honolulu at midsummer: the sun sets at 19:16 in Hawaii, 05:16 UTC on the 22nd.
        val d = normal(SunRules.day(LocalDate.of(2026, 6, 21), 21.31, -157.86))
        near("set", utc("2026-06-22T05:16"), d.set)
        assertTrue(d.rise < d.set)
        val honolulu = ZoneId.of("Pacific/Honolulu")
        val noon = LocalDateTime.parse("2026-06-21T12:00").atZone(honolulu).toInstant().toEpochMilli()
        val next = SunRules.next(noon, 21.31, -157.86, honolulu)!!
        assertFalse(next.rises)
        near("from local noon", utc("2026-06-22T05:16"), next.at)
    }

    @Test fun farEastTheSunriseIsOnTheUtcDayBefore() {
        // Auckland in midwinter: the sun rises at about 07:33 NZST on 2026-06-21, which is 19:33 UTC on the 20th.
        val d = normal(SunRules.day(LocalDate.of(2026, 6, 21), -36.85, 174.76))
        near("rise", utc("2026-06-20T19:33"), d.rise)
        val auckland = ZoneId.of("Pacific/Auckland")
        val dawn = LocalDateTime.parse("2026-06-21T06:00").atZone(auckland).toInstant().toEpochMilli()
        val next = SunRules.next(dawn, -36.85, 174.76, auckland)!!
        assertTrue(next.rises)
        near("this morning's", d.rise, next.at)
    }

    @Test fun atThePolarEdgeRiseAndSetStraddleMidnight() {
        // Tromsø around the start of the midnight sun: each day has a sunrise and a sunset (one either side of local midnight),
        // or none; whatever the hour, the next is never before now, and it comes within a day while there is one.
        val oslo = ZoneId.of("Europe/Oslo")
        for (date in (5..25).map { LocalDate.of(2026, 5, it) }) {
            val d = SunRules.day(date, 69.65, 18.96)
            assertTrue("$date: $d", d is Day.Normal || d == Day.AlwaysUp)
            if (d is Day.Normal) assertTrue("$date: rises before it sets", d.rise < d.set)
            var t = date.atStartOfDay(oslo).toInstant().toEpochMilli()
            val end = t + 24 * 60 * min
            while (t < end) {
                val n = SunRules.next(t, 69.65, 18.96, oslo)
                if (n != null) assertTrue("$date ${Instant.ofEpochMilli(t)}: ${Instant.ofEpochMilli(n.at)}", n.at > t && n.at - t <= 2 * 24 * 60 * min)
                t += 10 * min
            }
        }
        assertEquals("the 18th of May is already under the midnight sun, by the standard 0.833°", Day.AlwaysUp,
            SunRules.day(LocalDate.of(2026, 5, 18), 69.65, 18.96))
        assertTrue("the 10th still has a night", SunRules.day(LocalDate.of(2026, 5, 10), 69.65, 18.96) is Day.Normal)
    }

    @Test fun theTimeLeftIsInHoursAndMinutes() {
        val now = utc("2027-03-10T16:00")
        assertEquals("48 min", SunRules.text(SunRules.Next(false, now + 48 * min), now))
        assertEquals("1 h 12", SunRules.text(SunRules.Next(false, now + 72 * min), now))
        assertEquals("rounded up: never 0 while it is to come", "1 min", SunRules.text(SunRules.Next(true, now + 20_000), now))
        assertEquals("1 h 00", SunRules.text(SunRules.Next(true, now + 59 * min + 30_000), now))
        assertEquals("1 h 01", SunRules.text(SunRules.Next(true, now + 60 * min + 1), now))
    }

    @Test fun theTableHoldsThePlatformsZones() {
        val table = SunZones.TABLE
        assertTrue("${table.size} places", table.size >= 400)
        val known = ZoneId.getAvailableZoneIds()
        for (zone in table.keys) assertTrue("$zone is not a zone this platform knows", zone in known)
        assertEquals(SunZones.Place("Berlin", 52.5, 13.4), SunRules.place("Europe/Berlin"))
        for (zone in listOf("America/Los_Angeles", "Asia/Kolkata", "Australia/Sydney")) assertNotNull(zone, SunRules.place(zone))
        assertEquals("Buenos Aires", SunRules.place("America/Argentina/Buenos_Aires")?.city)
        for (p in table.values) assertTrue("$p", p.lat in -90.0..90.0 && p.lon in -180.0..180.0 && p.city.isNotBlank())
        assertNull("no city", SunRules.place("UTC"))
        assertNull(SunRules.place("Etc/GMT+5"))
    }

    @Test fun anAliasIsLookedUpByItsCanonicalName() {
        val canonical = mapOf("Asia/Calcutta" to "Asia/Kolkata", "US/Pacific" to "America/Los_Angeles")
        val lookup = { id: String -> canonical[id] ?: id }
        assertNull("not in the table as it is", SunRules.place("Asia/Calcutta"))
        assertEquals("Kolkata", SunRules.place("Asia/Calcutta", lookup)?.city)
        assertEquals("Los Angeles", SunRules.place("US/Pacific", lookup)?.city)
        // A zone in the table is found without asking.
        assertEquals("Berlin", SunRules.place("Europe/Berlin") { error("not asked") }?.city)
        assertNull("still unknown", SunRules.place("Etc/UTC", lookup))
        // A canonical name that throws is no answer.
        assertNull(SunRules.place("Nowhere/Else") { throw IllegalArgumentException() })
    }
}
