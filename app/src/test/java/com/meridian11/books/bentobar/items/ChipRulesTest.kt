package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.SourceScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The meeting chip: when it needs the calendar and when it changes, which must hold with nothing keeping the process alive. */
class ChipRulesTest {
    private val min = 60_000L
    private fun event(begin: Long, end: Long) = Calendar.Event(1, "t", begin, end, false, 0, "", true, true, null)

    @Test fun theChipNeedsTheCalendarOnlyWhileAMeetingCanBeWhatItShows() {
        assertTrue(ChipRules.needsCalendar(notifies = true, timerRuns = false, meetingItem = true))
        assertFalse("nothing shown", ChipRules.needsCalendar(notifies = false, timerRuns = false, meetingItem = true))
        assertFalse("the chip is the timer's", ChipRules.needsCalendar(notifies = true, timerRuns = true, meetingItem = true))
        assertFalse("no meeting item in the layout", ChipRules.needsCalendar(notifies = true, timerRuns = false, meetingItem = false))
    }

    @Test fun itChangesAtTheLeadTimeTheStartAndTheEnd() {
        val now = 1_000 * min
        val e = event(now + 20 * min, now + 50 * min)
        assertEquals(now + 5 * min, ChipRules.nextChange(listOf(e), now))
        assertEquals(now + 20 * min, ChipRules.nextChange(listOf(e), now + 5 * min))
        assertEquals(now + 50 * min, ChipRules.nextChange(listOf(e), now + 20 * min))
        assertNull(ChipRules.nextChange(listOf(e), now + 50 * min))
    }

    @Test fun theChipGoesAtTheMeetingsEndEvenIfNothingIsLeftToTakeItAway() {
        assertEquals(30 * min, ChipRules.timeoutAfter(end = 1_000 * min, now = 970 * min))
        // A timeout of 0 means none at all: a chip built at the very end still gets one.
        assertEquals(1L, ChipRules.timeoutAfter(end = 1_000 * min, now = 1_000 * min))
        assertEquals(1L, ChipRules.timeoutAfter(end = 1_000 * min, now = 2_000 * min))
    }

    @Test fun aMeetingsChipStaysWhileTheCalendarsFirstLoadIsOnItsWay() {
        // A process started by the alarm has read nothing yet, which says nothing about the meeting the chip is for.
        assertTrue(ChipRules.leaveUp(meetings = true, firstLoadRunning = true) { 30 * min })
    }

    @Test fun oncePassedTheFirstLoadNothingToShowTakesTheChipDown() {
        assertFalse("the load is back and found no meeting", ChipRules.leaveUp(meetings = true, firstLoadRunning = false) { 30 * min })
        assertFalse("the chip can't be a meeting's now", ChipRules.leaveUp(meetings = false, firstLoadRunning = true) { 30 * min })
    }

    @Test fun aTimersChipIsNeverLeftUpForTheCalendar() {
        // A timer that ended while the process was gone ends in Env.init, on this path, and its chip has no timeout: left up, nothing
        // would ever take it down if the process went before the load came back.
        assertFalse(ChipRules.leaveUp(meetings = true, firstLoadRunning = true) { 0L })
    }

    @Test fun noChipUpIsNothingToLeave() {
        assertFalse(ChipRules.leaveUp(meetings = true, firstLoadRunning = true) { null })
    }

    @Test fun theSystemIsAskedWhatIsUpOnlyWhenThatDecidesIt() {
        val asked = { throw AssertionError("asked") }
        assertFalse(ChipRules.leaveUp(meetings = true, firstLoadRunning = false, asked))
        assertFalse(ChipRules.leaveUp(meetings = false, firstLoadRunning = true, asked))
    }

    @Test fun theAlarmHoldsAFreshProcessUntilItHasReadTheCalendar() {
        // Without it the process is cached as onReceive returns, the first load still running and the next alarm not set.
        val notify = SourceScan.code(SourceScan.sources.single { it.name == "Notify.kt" })
        assertTrue("goAsync()" in notify)
        assertTrue(Regex("""Calendar\.loadingFirst""").containsMatchIn(notify))
        assertTrue("ChipRules.leaveUp(" in notify)
    }

    @Test fun theChipsBoundaryIsAnInexactAlarmAndTheCalendarLetsGoOfItsObserver() {
        val notify = SourceScan.code(SourceScan.sources.single { it.name == "Notify.kt" })
        assertTrue(".setTimeoutAfter(" in notify)
        assertTrue("armed with the system's clock, inexact and without waking the device", ".set(AlarmManager.RTC," in notify)
        assertFalse("an exact alarm would need the permission's say-so", "setExact" in notify)
        val calendar = SourceScan.code(SourceScan.sources.single { it.name == "Calendar.kt" })
        assertTrue("unregisterContentObserver" in calendar)
        val items = SourceScan.code(SourceScan.sources.single { it.name == "TimeItems.kt" })
        assertEquals("the Calendar and the Next meeting item", 2, Regex("""override fun onLive\(\) = Calendar\.hold\(type\)""").findAll(items).count())
        assertEquals(2, Regex("""override fun onIdle\(\) = Calendar\.release\(type\)""").findAll(items).count())
    }
}
