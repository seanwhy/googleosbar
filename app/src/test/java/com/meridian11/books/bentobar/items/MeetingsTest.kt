package com.meridian11.books.bentobar.items

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** Next meeting's rules: what counts as a meeting, and the horizon (today, plus 3 hours). */
class MeetingsTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) = LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()
    private val min = 60_000L

    @Test fun aTaskLinkMakesATaskOnlyWithNoCallLinkAndNoOneElse() {
        // A plain task synced in from a task app.
        assertTrue(Meetings.isTask(linksTask = true, hasCallLink = false, hasOthers = false))
        // A real meeting whose notes link a task: it must still show.
        assertFalse(Meetings.isTask(linksTask = true, hasCallLink = true, hasOthers = false))
        assertFalse(Meetings.isTask(linksTask = true, hasCallLink = false, hasOthers = true))
        assertFalse(Meetings.isTask(linksTask = true, hasCallLink = true, hasOthers = true))
        // An event with no task link is never a task.
        assertFalse(Meetings.isTask(linksTask = false, hasCallLink = false, hasOthers = false))
    }

    @Test fun horizonIsThreeTomorrow() {
        assertEquals(at(2026, 10, 1, 3), Meetings.horizon(at(2026, 9, 30, 9, 30), zone))
        assertEquals(at(2026, 9, 30, 3), Meetings.horizon(at(2026, 9, 30, 0, 0), zone))
        assertEquals(at(2026, 10, 1, 3), Meetings.horizon(at(2026, 9, 30, 23, 59), zone))
    }

    @Test fun afterMidnightTheHorizonIsThatSameNight() {
        // Between midnight and 03:00 the evening is still "today": at 01:00 the horizon is 03:00, 2 hours away.
        assertEquals(at(2026, 10, 1, 3), Meetings.horizon(at(2026, 10, 1, 1, 0), zone))
        assertEquals(at(2026, 10, 2, 3), Meetings.horizon(at(2026, 10, 1, 3, 0), zone))
    }

    @Test fun horizonFollowsDaylightSaving() {
        // The night clocks fall back (Nov 1, 2026 in the US): still 03:00 local.
        assertEquals(at(2026, 11, 1, 3), Meetings.horizon(at(2026, 10, 31, 20), zone))
    }

    @Test fun cutoffAtThree() {
        val now = at(2026, 9, 30, 15)
        val horizon = Meetings.horizon(now, zone)
        assertTrue("a 1 a.m. meeting is in", Meetings.inHorizon(at(2026, 10, 1, 1), at(2026, 10, 1, 2), now, horizon))
        assertTrue("02:59 is in", Meetings.inHorizon(at(2026, 10, 1, 2, 59), at(2026, 10, 1, 3, 30), now, horizon))
        assertFalse("03:00 is out", Meetings.inHorizon(at(2026, 10, 1, 3), at(2026, 10, 1, 4), now, horizon))
        assertFalse("tomorrow morning is out", Meetings.inHorizon(at(2026, 10, 1, 9), at(2026, 10, 1, 10), now, horizon))
    }

    @Test fun currentAndEndedMeetings() {
        val now = at(2026, 9, 30, 10)
        val horizon = Meetings.horizon(now, zone)
        assertTrue("on now", Meetings.inHorizon(now - 20 * min, now + 10 * min, now, horizon))
        assertFalse("ended", Meetings.inHorizon(now - 60 * min, now - 30 * min, now, horizon))
        assertFalse("ends right now", Meetings.inHorizon(now - 30 * min, now, now, horizon))
    }

    @Test fun meetingRule() {
        assertTrue("call link", Meetings.isMeeting(allDay = false, editable = true, hasCallLink = true, hasOthers = false))
        assertTrue("someone else invited", Meetings.isMeeting(allDay = false, editable = true, hasCallLink = false, hasOthers = true))
        assertFalse("a flight or solo appointment", Meetings.isMeeting(allDay = false, editable = true, hasCallLink = false, hasOthers = false))
        assertFalse("all-day", Meetings.isMeeting(allDay = true, editable = true, hasCallLink = true, hasOthers = true))
        assertFalse("a calendar you can't edit", Meetings.isMeeting(allDay = false, editable = false, hasCallLink = true, hasOthers = true))
    }

    @Test fun anAllDayOrReadOnlyEventWithATaskLinkIsAskedAboutAttendeesSoItIsNotLostAsATask() {
        assertTrue(Meetings.asksAttendees(hasCallLink = false, linksTask = true, allDay = true, editable = true))
        assertTrue(Meetings.asksAttendees(hasCallLink = false, linksTask = true, allDay = false, editable = false))
        assertTrue(Meetings.asksAttendees(hasCallLink = false, linksTask = false, allDay = false, editable = true))
        // Plain all-day or read-only events stay unasked, and a call link needs no attendees.
        assertFalse(Meetings.asksAttendees(hasCallLink = false, linksTask = false, allDay = true, editable = true))
        assertFalse(Meetings.asksAttendees(hasCallLink = true, linksTask = true, allDay = false, editable = true))
    }

    @Test fun anInvitationsTitleIsCleanedWhereItComesIn() {
        // Written by whoever sent it: a U+202E must not turn the countdown beside it around, a line break must not drop it.
        assertEquals("evil meeting", Meetings.title("\u202Eevil\nmeeting", "No title"))
        assertEquals("No title", Meetings.title(" \n\u202E", "No title"))
        assertEquals("No title", Meetings.title(null, "No title"))
        assertEquals(Meetings.TITLE_CHARS, Meetings.title("x".repeat(100_000), "No title").length)
    }

    @Test fun aLocationIsOneLineAndHasALimit() {
        assertEquals("Room 4, 2nd floor", Meetings.location("Room 4,\n2nd floor"))
        assertEquals("", Meetings.location(null))
        assertEquals(Meetings.LOCATION_CHARS, Meetings.location("y".repeat(100_000)).length)
    }
}
