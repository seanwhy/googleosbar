package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.items.ScreenTimeRules.Flip
import com.meridian11.books.bentobar.items.ScreenTimeRules.Front
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

class ScreenTimeRulesTest {
    private val min = 60_000L
    private val hour = 60 * min
    /** A day's start: any instant will do for the arithmetic. */
    private val day = 1_800_000_000_000L
    private fun at(minutes: Long) = day + minutes * min

    @Test fun onAllDayIsTheWholeDaySoFar() {
        assertEquals(10 * hour, ScreenTimeRules.screenOn(emptyList(), day, day + 10 * hour, onAtStart = true))
        assertEquals(0L, ScreenTimeRules.screenOn(emptyList(), day, day + 10 * hour, onAtStart = false))
        assertEquals("nothing before the day began", 0L, ScreenTimeRules.screenOn(emptyList(), day, day - hour, onAtStart = true))
    }

    @Test fun offTimeIsNotCounted() {
        // On from 0 to 60 minutes, off from 60 to 90, on since: at 120 minutes that is 90 minutes on.
        val flips = listOf(Flip(at(60), on = false), Flip(at(90), on = true))
        assertEquals(90 * min, ScreenTimeRules.screenOn(flips, day, at(120), onAtStart = true))
        // A flip after now is not yet: at 100 minutes, 60 and 10.
        assertEquals(70 * min, ScreenTimeRules.screenOn(flips + Flip(at(110), false), day, at(100), onAtStart = true))
    }

    @Test fun aDayThatStartsWithTheScreenOnCountsFromMidnight() {
        // The day's first word is "off" at 7:00: so it was on before, since midnight (the night left on).
        val flips = listOf(Flip(at(7 * 60), on = false), Flip(at(8 * 60), on = true))
        assertTrue(ScreenTimeRules.onAtStart(flips, day, onNow = true))
        assertEquals(7 * hour + hour, ScreenTimeRules.screenOn(flips, day, at(9 * 60), ScreenTimeRules.onAtStart(flips, day, onNow = true)))
        // The first word is "on": off before it.
        assertFalse(ScreenTimeRules.onAtStart(listOf(Flip(at(8 * 60), on = true)), day, onNow = true))
        // No word all day: as it is now.
        assertTrue(ScreenTimeRules.onAtStart(emptyList(), day, onNow = true))
        assertFalse(ScreenTimeRules.onAtStart(emptyList(), day, onNow = false))
        // A word from before the day says how the day began.
        assertTrue(ScreenTimeRules.onAtStart(listOf(Flip(day - hour, true), Flip(at(60), true)), day, onNow = false))
    }

    @Test fun flipsOutOfOrderOrDoubledAreHarmless() {
        val tidy = listOf(Flip(at(60), false), Flip(at(90), true))
        val messy = listOf(Flip(at(90), true), Flip(at(60), false), Flip(at(95), true), Flip(at(70), false), Flip(at(90), true))
        assertEquals(ScreenTimeRules.screenOn(tidy, day, at(120), true), ScreenTimeRules.screenOn(messy, day, at(120), true))
        // From before the day: only how it stood at its start counts.
        val early = listOf(Flip(day - 2 * hour, true), Flip(day - hour, false)) + tidy
        assertEquals(30 * min, ScreenTimeRules.screenOn(early, day, at(120), onAtStart = true))
    }

    @Test fun aTwentyThreeHourDayStartsAtItsOwnMidnight() {
        val berlin = ZoneId.of("Europe/Berlin")
        // The clocks went forward an hour at 2:00: at noon, eleven hours of the day have passed.
        val springNoon = ZonedDateTime.of(LocalDateTime.of(2026, 3, 29, 12, 0), berlin)
        assertEquals(11 * hour, springNoon.toInstant().toEpochMilli() - ScreenTimeRules.dayStart(springNoon))
        val autumnNoon = ZonedDateTime.of(LocalDateTime.of(2026, 10, 25, 12, 0), berlin)
        assertEquals(13 * hour, autumnNoon.toInstant().toEpochMilli() - ScreenTimeRules.dayStart(autumnNoon))
        // Where the change skips midnight itself, the day starts at its first hour.
        val havana = ZonedDateTime.of(LocalDateTime.of(2026, 3, 8, 12, 0), ZoneId.of("America/Havana"))
        assertEquals(11 * hour, havana.toInstant().toEpochMilli() - ScreenTimeRules.dayStart(havana))
        // Just before and just after midnight are two days.
        val late = ZonedDateTime.of(LocalDateTime.of(2026, 11, 11, 23, 59), berlin)
        assertEquals(ScreenTimeRules.dayStart(late) + 24 * hour, ScreenTimeRules.dayStart(late.plusMinutes(2)))
    }

    @Test fun text() {
        assertEquals("0 min", ScreenTimeRules.text(59_999))
        assertEquals("47 min", ScreenTimeRules.text(47 * min + 59_000))
        assertEquals("1 h 00", ScreenTimeRules.text(hour))
        assertEquals("3 h 05", ScreenTimeRules.text(3 * hour + 5 * min))
        assertEquals("12 h 40", ScreenTimeRules.text(12 * hour + 40 * min))
        assertEquals("0 min", ScreenTimeRules.text(-5))
    }

    @Test fun overFromTheHourOn() {
        assertFalse(ScreenTimeRules.over(4 * hour - 1, 4))
        assertTrue(ScreenTimeRules.over(4 * hour, 4))
    }

    @Test fun theBarCountsOnFromItsLastReadingWhileItShows() {
        // Read 30 minutes on at 10:00, shown since 9:00: at 10:05 it says 35.
        assertEquals(35 * min, ScreenTimeRules.shown(30 * min, loadedAt = at(600), liveSince = at(540), now = at(605), dayStart = day))
        // Back on screen at 11:00 after a reading from 10:00: the hour between is not counted until the next reading.
        assertEquals(32 * min, ScreenTimeRules.shown(30 * min, loadedAt = at(600), liveSince = at(660), now = at(662), dayStart = day))
        // Never more than the day so far.
        assertEquals(10 * min, ScreenTimeRules.shown(10 * min, loadedAt = at(5), liveSince = at(0), now = at(10), dayStart = day))
    }

    @Test fun anAppIsInFrontFromItsFirstWindowUntilItsLast() {
        val events = listOf(
            Front(at(60), "a", true),
            Front(at(70), "a", true), // a second window of the same app
            Front(at(80), "a", false),
            Front(at(90), "a", false), // now it has left
            Front(at(100), "b", true),
            Front(at(110), "a", true),
        )
        assertEquals(mapOf("a" to 30 * min + 10 * min, "b" to 20 * min), ScreenTimeRules.inFront(events, day, at(120)))
    }

    @Test fun anAppInFrontWhenTheDayBeganCountsFromItsStart() {
        // It leaves at 1:00 with no word of it coming: it was there at midnight.
        assertEquals(mapOf("a" to 60 * min), ScreenTimeRules.inFront(listOf(Front(at(60), "a", false)), day, at(120)))
        // Came before midnight: counted from midnight only.
        assertEquals(mapOf("a" to 30 * min), ScreenTimeRules.inFront(listOf(Front(day - hour, "a", true), Front(at(30), "a", false)), day, at(120)))
        // A stray "left" after it had come and gone is nothing.
        assertEquals(mapOf("a" to 10 * min), ScreenTimeRules.inFront(listOf(Front(at(10), "a", true), Front(at(20), "a", false), Front(at(50), "a", false)), day, at(120)))
    }

    @Test fun aRestartLeavesNoAppInFront() {
        // The device shut down at 0:40 and started again at 1:00: the app that was in front is not counted on through it.
        val events = listOf(Front(at(10), "a", true), Front(at(40), null, false), Front(at(60), null, false), Front(at(70), "b", true))
        assertEquals(mapOf("a" to 30 * min, "b" to 50 * min), ScreenTimeRules.inFront(events, day, at(120)))
    }

    @Test fun theTopAreTheLongestNoneUnderAMinuteNoneLongerThanTheScreenAndOnlyThoseThatCanBeNamed() {
        val times = mapOf("a" to 5 * hour, "b" to 2 * hour, "c" to 3 * hour, "hidden" to 4 * hour, "d" to 30 * min, "e" to 20 * min,
            "f" to 10 * min, "short" to 59_000L)
        val top = ScreenTimeRules.top(times, cap = 4 * hour) { it != "hidden" }
        assertEquals(listOf("a" to 4 * hour, "c" to 3 * hour, "b" to 2 * hour, "d" to 30 * min, "e" to 20 * min), top)
    }

    @Test fun theStagesAreFixedLengthsOfTimeAndOneWithoutAccess() {
        val short = assertNotNullAnd(ScreenTimeRules.staged("short"))
        val long = assertNotNullAnd(ScreenTimeRules.staged("long"))
        val over = assertNotNullAnd(ScreenTimeRules.staged("over"))
        val none = assertNotNullAnd(ScreenTimeRules.staged("noaccess"))
        assertEquals("47 min", ScreenTimeRules.text(short.onMs))
        assertEquals("3 h 05", ScreenTimeRules.text(long.onMs))
        assertFalse(ScreenTimeRules.over(long.onMs, 4))
        assertTrue(ScreenTimeRules.over(over.onMs, 4))
        assertTrue(short.access && long.access && over.access)
        assertFalse(none.access)
        assertNull(ScreenTimeRules.staged("no-such-state"))
    }

    private fun <T> assertNotNullAnd(v: T?): T { assertNotNull(v); return v!! }
}
