package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.items.ProgressRules.Of
import com.meridian11.books.bentobar.util.Sym
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

class ProgressRulesTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private fun at(text: String, z: ZoneId = zone): ZonedDateTime = LocalDateTime.parse(text).atZone(z)
    private fun fraction(of: Of, text: String, z: ZoneId = zone, from: Int = 9, to: Int = 17, weekStart: DayOfWeek = DayOfWeek.MONDAY) =
        ProgressRules.fraction(of, at(text, z), from, to, weekStart)
    private val close = 1e-6f

    @Test fun noonIsHalfTheDay() {
        assertEquals(0.5f, fraction(Of.DAY, "2027-03-10T12:00"), close)
        assertEquals(0f, fraction(Of.DAY, "2027-03-10T00:00"), close)
        assertEquals(0.25f, fraction(Of.DAY, "2027-03-10T06:00"), close)
    }

    @Test fun theWorkdayIsZeroBeforeAndFullAfter() {
        assertEquals(0f, fraction(Of.WORKDAY, "2027-03-10T08:59"), close)
        assertEquals(0f, fraction(Of.WORKDAY, "2027-03-10T09:00"), close)
        assertEquals(0.5f, fraction(Of.WORKDAY, "2027-03-10T13:00"), close)
        assertEquals(1f, fraction(Of.WORKDAY, "2027-03-10T17:00"), close)
        assertEquals(1f, fraction(Of.WORKDAY, "2027-03-10T23:30"), close)
        // To 24: the workday ends at midnight.
        assertEquals(0.5f, fraction(Of.WORKDAY, "2027-03-10T16:00", from = 8, to = 24), close)
        // Before it starts all of it is left; after it ends, nothing.
        assertEquals("8 h 00 left", ProgressRules.left(Of.WORKDAY, at("2027-03-10T07:00"), 9, 17, DayOfWeek.MONDAY))
        assertEquals("Over", ProgressRules.left(Of.WORKDAY, at("2027-03-10T18:00"), 9, 17, DayOfWeek.MONDAY))
    }

    @Test fun aWorkdayThatEndsBeforeItStartsRunsPastMidnight() {
        // A night's work, 22 to 6: at 2:00 half of it is done, whichever date that is on; at noon it is over (the last one ended at 6).
        assertEquals(0.5f, fraction(Of.WORKDAY, "2027-03-11T02:00", from = 22, to = 6), close)
        assertEquals(0.25f, fraction(Of.WORKDAY, "2027-03-10T00:00", from = 22, to = 6), close)
        assertEquals(1f, fraction(Of.WORKDAY, "2027-03-10T12:00", from = 22, to = 6), close)
        assertEquals(0f, fraction(Of.WORKDAY, "2027-03-10T22:00", from = 22, to = 6), close)
        // The same hour twice is a day's work.
        assertEquals(0.5f, fraction(Of.WORKDAY, "2027-03-10T21:00", from = 9, to = 9), close)
    }

    @Test fun aLeapYearHas366Days() {
        // 2028-07-02T00:00: 183 days of 366 are gone, so exactly half. In UTC: Berlin's summer time takes an hour of them,
        // since the year is counted in real time.
        val utc = ZoneId.of("UTC")
        assertEquals(0.5f, fraction(Of.YEAR, "2028-07-02T00:00", utc), close)
        assertEquals(182f / 365f, fraction(Of.YEAR, "2027-07-02T00:00", utc), close)
        assertEquals((183f * 24 - 1) / (366 * 24), fraction(Of.YEAR, "2028-07-02T00:00"), close)
        // February of a leap year has 29 days.
        assertEquals(14f / 29f, fraction(Of.MONTH, "2028-02-15T00:00"), close)
    }

    @Test fun aDayWithAClockChangeIsStillOneDay() {
        // 2027-03-28 in Berlin has 23 hours (2:00 becomes 3:00): at its 11.5th hour it is half gone.
        val start = at("2027-03-28T00:00")
        assertEquals(0.5f, ProgressRules.fraction(Of.DAY, start.plusSeconds(11 * 3600L + 1800), 9, 17, DayOfWeek.MONDAY), close)
        // And 2027-10-31 has 25: at its 12.5th hour.
        val autumn = at("2027-10-31T00:00")
        assertEquals(0.5f, ProgressRules.fraction(Of.DAY, autumn.plusSeconds(12 * 3600L + 1800), 9, 17, DayOfWeek.MONDAY), close)
        // A zone whose clock change skips midnight: the day starts at its first moment (Santiago, 2027-09-05 starts at 1:00).
        val santiago = ZoneId.of("America/Santiago")
        val f = ProgressRules.fraction(Of.DAY, at("2027-09-05T12:00", santiago), 9, 17, DayOfWeek.MONDAY)
        assertEquals(11f / 23f, f, close)
    }

    @Test fun theWeekStartsWhereTheLocaleSays() {
        // Wednesday 2027-03-10 at noon: two and a half days of the week are gone from Monday, three and a half from Sunday.
        assertEquals(2.5f / 7, fraction(Of.WEEK, "2027-03-10T12:00", weekStart = DayOfWeek.MONDAY), close)
        assertEquals(3.5f / 7, fraction(Of.WEEK, "2027-03-10T12:00", weekStart = DayOfWeek.SUNDAY), close)
        assertEquals(4.5f / 7, fraction(Of.WEEK, "2027-03-10T12:00", weekStart = DayOfWeek.SATURDAY), close)
        // On the week's first day, at its start, nothing is gone.
        assertEquals(0f, fraction(Of.WEEK, "2027-03-07T00:00", weekStart = DayOfWeek.SUNDAY), close)
        // Where the locale's week starts, as the Calendar item reads it.
        assertEquals(DayOfWeek.SUNDAY, ProgressRules.weekStart(java.util.Locale.US))
        assertEquals(DayOfWeek.MONDAY, ProgressRules.weekStart(java.util.Locale.GERMANY))
        assertEquals(DayOfWeek.MONDAY, ProgressRules.weekStart(java.util.Locale.forLanguageTag("en-US-u-fw-mon")))
    }

    @Test fun ninetyNinePointNineIsNotAHundred() {
        assertEquals("99%", ProgressRules.percent(0.999f))
        assertEquals("99%", ProgressRules.percent(0.9999999f))
        assertEquals("100%", ProgressRules.percent(1f))
        assertEquals("62%", ProgressRules.percent(0.629f))
        assertEquals("0%", ProgressRules.percent(0f))
        // The last tenth of a second of the year is not the year's end, though a Float can't tell it from 1.
        val last = at("2027-12-31T23:59:59.9")
        assertTrue(ProgressRules.fraction(Of.YEAR, last, 9, 17, DayOfWeek.MONDAY) < 1f)
        assertEquals("99%", ProgressRules.percent(ProgressRules.fraction(Of.YEAR, last, 9, 17, DayOfWeek.MONDAY)))
        assertEquals("99%", ProgressRules.percent(ProgressRules.fraction(Of.DAY, last, 9, 17, DayOfWeek.MONDAY)))
    }

    @Test fun theGlyphFillsInSteps() {
        assertEquals(Sym.CLOCK_LOADER_10, ProgressRules.icon(0f))
        assertEquals(Sym.CLOCK_LOADER_10, ProgressRules.icon(0.149f))
        assertEquals(Sym.CLOCK_LOADER_20, ProgressRules.icon(0.15f))
        assertEquals(Sym.CLOCK_LOADER_40, ProgressRules.icon(0.3f))
        assertEquals(Sym.CLOCK_LOADER_60, ProgressRules.icon(0.5f))
        assertEquals(Sym.CLOCK_LOADER_80, ProgressRules.icon(0.7f))
        assertEquals(Sym.CLOCK_LOADER_90, ProgressRules.icon(0.85f))
        assertEquals(Sym.CLOCK_LOADER_90, ProgressRules.icon(1f))
    }

    @Test fun whatIsLeftIsHoursOnItsLastDayAndDaysBefore() {
        val wednesday = at("2027-03-10T09:00")
        assertEquals("15 h 00 left", ProgressRules.left(Of.DAY, wednesday, 9, 17, DayOfWeek.MONDAY))
        assertEquals("48 min left", ProgressRules.left(Of.DAY, at("2027-03-10T23:12"), 9, 17, DayOfWeek.MONDAY))
        // Whole days after today: Thursday to Sunday.
        assertEquals("4 days left", ProgressRules.left(Of.WEEK, wednesday, 9, 17, DayOfWeek.MONDAY))
        assertEquals("21 days left", ProgressRules.left(Of.MONTH, wednesday, 9, 17, DayOfWeek.MONDAY))
        assertEquals("296 days left", ProgressRules.left(Of.YEAR, wednesday, 9, 17, DayOfWeek.MONDAY))
        assertEquals("1 day left", ProgressRules.left(Of.YEAR, at("2027-12-30T09:00"), 9, 17, DayOfWeek.MONDAY))
        // On the last day of the year, its hours.
        assertEquals("1 h 00 left", ProgressRules.left(Of.YEAR, at("2027-12-31T23:00"), 9, 17, DayOfWeek.MONDAY))
        // Never "0 min" while some is left.
        assertEquals("1 min left", ProgressRules.left(Of.DAY, at("2027-03-10T23:59:30"), 9, 17, DayOfWeek.MONDAY))
    }

    @Test fun theChoiceOfWhatToCountIsReadFromTheLayout() {
        assertEquals(Of.DAY, ProgressRules.of("nonsense"))
        assertEquals(Of.DAY, ProgressRules.of(null))
        for (of in Of.entries) assertEquals(of, ProgressRules.of(ProgressRules.key(of)))
    }
}
