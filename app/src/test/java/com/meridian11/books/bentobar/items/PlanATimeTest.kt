package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.data.WorldCity
import com.meridian11.books.bentobar.items.PlanATime.Day
import com.meridian11.books.bentobar.items.PlanATime.Plan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Plan a time without a device: the slider's quarter hours, the days, and which moment a chosen time is, clock changes included. */
class PlanATimeTest {
    private val la = ZoneId.of("America/Los_Angeles")
    private val berlin = ZoneId.of("Europe/Berlin")
    /** Monday, October 5, 2026, 2:10 PM in Los Angeles. */
    private val now: ZonedDateTime = LocalDateTime.parse("2026-10-05T14:10").atZone(la)
    private val today: LocalDate = now.toLocalDate()
    private val words = worldClockWords()

    /** The time and offset a plan comes to, as "03:00 -07:00". */
    private fun moment(date: LocalDate, quarter: Int, zone: ZoneId) = PlanATime.moment(Plan(date, quarter), zone).let { "${it.toLocalTime()} ${it.offset}" }

    /** The rows for a plan, with Berlin (or Los Angeles) as the other place: their times and what stands under them. */
    private fun rows(date: LocalDate, quarter: Int, zone: ZoneId, other: String): List<String> {
        val at = PlanATime.moment(Plan(date, quarter), zone).toInstant().toEpochMilli()
        return WorldClock.rows(WorldClock.places(zone, emptyList(), listOf(WorldCity(other)), at), at, true, words).map { "${it.time}, ${it.sub}" }
    }

    @Test fun atRestTheSliderStandsOnThePresentQuarterHour() {
        assertEquals(56, PlanATime.quarter(LocalTime.of(14, 10))) // 2:00 PM
        assertEquals(0, PlanATime.quarter(LocalTime.of(0, 0)))
        assertEquals(0, PlanATime.quarter(LocalTime.of(0, 14, 59)))
        assertEquals(1, PlanATime.quarter(LocalTime.of(0, 15)))
        assertEquals(36, PlanATime.quarter(LocalTime.of(9, 0)))
        assertEquals(95, PlanATime.quarter(LocalTime.of(23, 59, 59)))
        // The day in quarter hours: 00:00 to 23:45.
        assertEquals(95, PlanATime.LAST_QUARTER)
    }

    @Test fun movingTheSliderPlansThatTimeOfToday() {
        assertEquals(Plan(today, 36), PlanATime.slid(null, now, 36))
        // Once a day is chosen the slider moves within that day.
        assertEquals(Plan(today.plusDays(2), 40), PlanATime.slid(Plan(today.plusDays(2), 36), now, 40))
        assertEquals(Plan(today, 95), PlanATime.slid(null, now, 99))
        assertEquals(Plan(today, 0), PlanATime.slid(null, now, -3))
    }

    @Test fun steppingADayPlansThePresentQuarterHourOfThatDay() {
        assertEquals(Plan(today.plusDays(1), 56), PlanATime.stepped(null, now, 1))
        assertEquals(Plan(today.minusDays(1), 56), PlanATime.stepped(null, now, -1))
        // A time that was chosen stays when the day moves.
        assertEquals(Plan(today.plusDays(3), 36), PlanATime.stepped(Plan(today.plusDays(2), 36), now, 1))
        assertEquals(Plan(today, 36), PlanATime.stepped(Plan(today.plusDays(1), 36), now, -1))
    }

    @Test fun theDaysRunFromYesterdayToFourteenDaysAhead() {
        assertTrue(PlanATime.canStep(null, today, -1))
        assertTrue(PlanATime.canStep(null, today, 1))
        assertFalse(PlanATime.canStep(Plan(today.minusDays(1), 36), today, -1))
        assertTrue(PlanATime.canStep(Plan(today.minusDays(1), 36), today, 1))
        assertTrue(PlanATime.canStep(Plan(today.plusDays(13), 36), today, 1))
        assertFalse(PlanATime.canStep(Plan(today.plusDays(14), 36), today, 1))
        // A step past an end stays on it.
        var plan: Plan? = null
        repeat(20) { plan = PlanATime.stepped(plan, now, 1) }
        assertEquals(Plan(today.plusDays(14), 56), plan)
        repeat(20) { plan = PlanATime.stepped(plan, now, -1) }
        assertEquals(Plan(today.minusDays(1), 56), plan)
    }

    @Test fun aPlanLeftOpenPastMidnightCanOnlyComeBackIntoTheDays() {
        // Yesterday was planned, and the menu stayed open into the next day: that day is two back now.
        val stale = Plan(today.minusDays(2), 36)
        assertFalse(PlanATime.canStep(stale, today, -1))
        assertTrue(PlanATime.canStep(stale, today, 1))
        assertEquals(Plan(today.minusDays(1), 36), PlanATime.stepped(stale, now, 1))
    }

    @Test fun aPlanIsThatTimeOfThatDayWhereTheDeviceIs() {
        val nine = PlanATime.moment(Plan(LocalDate.of(2026, 10, 7), 36), la)
        assertEquals(ZonedDateTime.of(2026, 10, 7, 9, 0, 0, 0, la), nine)
        assertEquals("2026-10-07T16:00:00Z", nine.toInstant().toString())
        assertEquals("00:00 -07:00", moment(LocalDate.of(2026, 10, 7), 0, la))
        assertEquals("23:45 -07:00", moment(LocalDate.of(2026, 10, 7), 95, la))
        assertEquals("09:00 +02:00", moment(LocalDate.of(2026, 10, 7), 36, berlin))
    }

    @Test fun aTimeThatDoesNotExistIsTheNextOneThatDoes() {
        // March 14, 2027 in Los Angeles: the clocks go from 2:00 to 3:00.
        val spring = LocalDate.of(2027, 3, 14)
        assertEquals("01:45 -08:00", moment(spring, 7, la))
        for (quarter in 8..11) assertEquals("2:00, 2:15, 2:30 and 2:45 are all 3:00", "03:00 -07:00", moment(spring, quarter, la))
        assertEquals("03:00 -07:00", moment(spring, 12, la))
        assertEquals("03:15 -07:00", moment(spring, 13, la))
        // Europe two weeks later.
        assertEquals("01:45 +01:00", moment(LocalDate.of(2027, 3, 28), 7, berlin))
        assertEquals("03:00 +02:00", moment(LocalDate.of(2027, 3, 28), 10, berlin))
    }

    @Test fun aChangeOfHalfAnHourOrAtMidnightIsTheNextValidTimeToo() {
        // Lord Howe Island's clocks go forward half an hour, from 2:00 to 2:30.
        val lordHowe = ZoneId.of("Australia/Lord_Howe")
        assertEquals("01:45 +10:30", moment(LocalDate.of(2026, 10, 4), 7, lordHowe))
        assertEquals("02:30 +11:00", moment(LocalDate.of(2026, 10, 4), 8, lordHowe))
        assertEquals("02:30 +11:00", moment(LocalDate.of(2026, 10, 4), 9, lordHowe))
        assertEquals("02:30 +11:00", moment(LocalDate.of(2026, 10, 4), 10, lordHowe))
        // Havana's clocks go forward at midnight: the day begins at 1:00, and it is still that day.
        // 2026-03-08, not the next spring: from tzdata 2026f Cuba stays on -04 from 2026-11-01 through 2027-11-07,
        // so 2027-03-14 has a midnight and the gap the rule is about is gone.
        val havana = ZoneId.of("America/Havana")
        val havanaSpring = LocalDate.of(2026, 3, 8)
        val first = PlanATime.moment(Plan(havanaSpring, 0), havana)
        assertEquals("01:00 -04:00", "${first.toLocalTime()} ${first.offset}")
        assertEquals(havanaSpring, first.toLocalDate())
        assertEquals("01:00 -04:00", moment(havanaSpring, 2, havana))
    }

    @Test fun aTimeThatComesTwiceIsItsFirst() {
        // November 1, 2026 in Los Angeles: 1:00 to 2:00 comes twice.
        val autumn = LocalDate.of(2026, 11, 1)
        assertEquals("00:45 -07:00", moment(autumn, 3, la))
        assertEquals("01:30 -07:00", moment(autumn, 6, la))
        assertEquals("02:00 -08:00", moment(autumn, 8, la))
        assertEquals("02:30 +02:00", moment(LocalDate.of(2026, 10, 25), 10, berlin))
        assertEquals("03:00 +01:00", moment(LocalDate.of(2026, 10, 25), 12, berlin))
    }

    @Test fun onTheSpringChangeDayTheRowsShowTheNextValidTimeAndThatMomentsOffsets() {
        val spring = LocalDate.of(2027, 3, 14)
        // Europe changes two weeks later: until 2:00 Berlin is 9 hours ahead, from 3:00 it is 8.
        assertEquals(listOf("1:45 AM, Sun · Local", "10:45 AM, Sun · +9h"), rows(spring, 7, la, "Europe/Berlin"))
        assertEquals(listOf("3:00 AM, Sun · Local", "11:00 AM, Sun · +8h"), rows(spring, 10, la, "Europe/Berlin"))
        assertEquals(listOf("3:00 AM, Sun · Local", "11:00 AM, Sun · +8h"), rows(spring, 12, la, "Europe/Berlin"))
        // Seen from Berlin on its own change day.
        assertEquals(listOf("1:45 AM, Sun · Local", "5:45 PM, Sat · −8h"), rows(LocalDate.of(2027, 3, 28), 7, berlin, "America/Los_Angeles"))
        assertEquals(listOf("3:00 AM, Sun · Local", "6:00 PM, Sat · −9h"), rows(LocalDate.of(2027, 3, 28), 10, berlin, "America/Los_Angeles"))
    }

    @Test fun onTheAutumnChangeDayOffsetsAreThoseOfTheChosenMoment() {
        val autumn = LocalDate.of(2026, 11, 1)
        // Europe's clocks went back a week before: Berlin is 8 hours ahead until Los Angeles's go back too, then 9 again.
        assertEquals(listOf("12:45 AM, Sun · Local", "8:45 AM, Sun · +8h"), rows(autumn, 3, la, "Europe/Berlin"))
        assertEquals(listOf("1:30 AM, Sun · Local", "9:30 AM, Sun · +8h"), rows(autumn, 6, la, "Europe/Berlin"))
        assertEquals(listOf("2:00 AM, Sun · Local", "11:00 AM, Sun · +9h"), rows(autumn, 8, la, "Europe/Berlin"))
        // Seen from Berlin on its own change day.
        assertEquals(listOf("2:30 AM, Sun · Local", "5:30 PM, Sat · −9h"), rows(LocalDate.of(2026, 10, 25), 10, berlin, "America/Los_Angeles"))
        assertEquals(listOf("3:00 AM, Sun · Local", "7:00 PM, Sat · −8h"), rows(LocalDate.of(2026, 10, 25), 12, berlin, "America/Los_Angeles"))
    }

    @Test fun aNewEventLastsAnHour() {
        // Without an end a calendar app picks a length of its own; an hour is what a new event has in the system's calendar.
        val nine = PlanATime.moment(Plan(LocalDate.of(2026, 10, 7), 36), la).toInstant()
        assertEquals("2026-10-07T17:00:00Z", Instant.ofEpochMilli(PlanATime.eventEnd(nine.toEpochMilli())).toString())
        assertEquals(60 * 60_000L, PlanATime.eventEnd(nine.toEpochMilli()) - nine.toEpochMilli())
        // An hour as it passes, also on the night the clocks go back: from the first 1:30 to the second.
        val first = PlanATime.moment(Plan(LocalDate.of(2026, 11, 1), 6), la)
        val end = Instant.ofEpochMilli(PlanATime.eventEnd(first.toInstant().toEpochMilli())).atZone(la)
        assertEquals("01:30 -07:00", "${first.toLocalTime()} ${first.offset}")
        assertEquals("01:30 -08:00", "${end.toLocalTime()} ${end.offset}")
    }

    @Test fun theDayIsCalledTodayTomorrowYesterdayOrByItsDate() {
        assertEquals(Day.TODAY, PlanATime.day(today, today))
        assertEquals(Day.TOMORROW, PlanATime.day(today.plusDays(1), today))
        assertEquals(Day.YESTERDAY, PlanATime.day(today.minusDays(1), today))
        assertEquals(Day.OTHER, PlanATime.day(today.plusDays(2), today))
        assertEquals(Day.OTHER, PlanATime.day(today.minusDays(2), today))
        // Over the end of a month and of a year.
        assertEquals(Day.TOMORROW, PlanATime.day(LocalDate.of(2027, 1, 1), LocalDate.of(2026, 12, 31)))
        assertEquals(Day.YESTERDAY, PlanATime.day(LocalDate.of(2026, 9, 30), LocalDate.of(2026, 10, 1)))
    }
}
