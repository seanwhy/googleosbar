package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.data.MeetingChoices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** Next meeting's rules: what counts as a meeting, one event on two calendars, the menu's list, and the horizon (today, plus 3 hours). */
class MeetingsTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) = LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()
    private val min = 60_000L

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

    private val choices = MeetingChoices.State()
    private val work = Meetings.Cal(1, "you@example.com", "com.google", "you@example.com", "Work", 0, 700, visible = true, flagPrimary = true)
    private val holidays = Meetings.Cal(2, "you@example.com", "com.google", "holidays@example.com", "Holidays", 0, 200, visible = true, flagPrimary = false)
    private fun row(allDay: Boolean = false, link: String? = null, linksTask: Boolean = false, status: Int = 1, self: Int = 0) =
        Meetings.Row(1, 1, "t", 0, 1, allDay = allDay, link = link, linksTask = linksTask, status = status, selfStatus = self)
    private fun judge(r: Meetings.Row, cal: Meetings.Cal = work, guests: List<Meetings.Guest> = emptyList(), c: MeetingChoices.State = choices) =
        Meetings.judge(r, cal, guests, setOf("you@example.com"), c)

    @Test fun meetingRule() {
        val other = listOf(Meetings.Guest("you@example.com"), Meetings.Guest("ana@example.com"))
        assertTrue("call link", judge(row(link = "https://meet.example.com/x")).meeting)
        assertTrue("someone else invited", judge(row(), guests = other).meeting)
        assertFalse("a flight or solo appointment", judge(row()).meeting)
        assertFalse("all-day", judge(row(allDay = true, link = "https://meet.example.com/x"), guests = other).meeting)
        assertFalse("a calendar you can't edit", judge(row(link = "https://meet.example.com/x"), cal = holidays, guests = other).meeting)
        assertEquals(Meetings.Verdict.ALL_DAY, judge(row(allDay = true)))
        assertTrue("all-day and read-only events are shown", judge(row(), cal = holidays).event)
    }

    @Test fun anAllDayOrReadOnlyEventWithATaskLinkIsAskedAboutAttendeesSoItIsNotLostAsATask() {
        assertTrue(Meetings.asksAttendees(row(allDay = true, linksTask = true), work, choices))
        assertTrue(Meetings.asksAttendees(row(linksTask = true), holidays, choices))
        assertTrue(Meetings.asksAttendees(row(), work, choices))
        // Plain all-day or read-only events stay unasked.
        assertFalse(Meetings.asksAttendees(row(allDay = true), work, choices))
        assertFalse(Meetings.asksAttendees(row(), holidays, choices))
        // A call link no longer spares the read: whose meeting it is decides first.
        assertTrue(Meetings.asksAttendees(row(link = "https://meet.example.com/x"), work, choices))
        // What shows nowhere, and a calendar the user unticked, are not asked about.
        assertFalse(Meetings.asksAttendees(row(status = Meetings.STATUS_CANCELED), work, choices))
        assertFalse(Meetings.asksAttendees(row(self = Meetings.SELF_DECLINED), work, choices))
        assertFalse(Meetings.asksAttendees(row(), null, choices))
        assertFalse(Meetings.asksAttendees(row(), work, choices.copy(calendarsOff = setOf(work.ident))))
    }

    private fun meeting(title: String, begin: Long, end: Long, own: Boolean = false, cal: Long = 1) =
        Calendar.Event(begin, title, begin, end, false, 0, "", true, true, null, calendarId = cal, ownCalendar = own)

    @Test fun oneMeetingOnTwoCalendarsCountsOnceAndTheOwnCopyIsKept() {
        val team = meeting("Team sync", 100, 200, own = false, cal = 3)
        val mine = meeting("Team sync", 100, 200, own = true, cal = 1)
        assertEquals(listOf(mine), Meetings.once(listOf(team, mine)))
        assertEquals(listOf(mine), Meetings.once(listOf(mine, team)))
    }

    @Test fun meetingsThatDifferInTimeOrTitleAreTwo() {
        val a = meeting("Sync", 100, 200)
        val b = meeting("Sync", 100, 250)
        val c = meeting("Other", 100, 200)
        assertEquals(listOf(a, b, c), Meetings.once(listOf(a, b, c)))
    }

    @Test fun onceKeepsTheOrderAndTheFirstOfTwoOnCalendarsNeitherOwn() {
        val a = meeting("Sync", 100, 200, cal = 3)
        val b = meeting("Sync", 100, 200, cal = 4)
        val later = meeting("Later", 50, 300)
        assertEquals(listOf(later, a), Meetings.once(listOf(later, a, b)))
        assertEquals(emptyList<Calendar.Event>(), Meetings.once(emptyList()))
    }

    @Test fun theMenuListsTodayAndWithAnyDayTheFirstLaterDayThatHasOne() {
        val now = at(2026, 9, 30, 20)
        val horizon = Meetings.horizon(now, zone)
        val tonight = meeting("Tonight", at(2026, 9, 30, 21), at(2026, 9, 30, 22))
        val friday = meeting("Friday", at(2026, 10, 2, 9), at(2026, 10, 2, 10))
        val friday2 = meeting("Friday later", at(2026, 10, 2, 15), at(2026, 10, 2, 16))
        val monday = meeting("Monday", at(2026, 10, 5, 9), at(2026, 10, 5, 10))
        val all = listOf(tonight, friday, friday2, monday)
        assertEquals(listOf(tonight), Meetings.listed(all, now, horizon, zone, any = true))
        assertEquals(listOf(tonight), Meetings.listed(all, now, horizon, zone, any = false))
        val later = listOf(friday, friday2, monday)
        assertEquals("nothing left today: Friday's", listOf(friday, friday2), Meetings.listed(later, now, horizon, zone, any = true))
        assertEquals("today only", emptyList<Calendar.Event>(), Meetings.listed(later, now, horizon, zone, any = false))
        assertEquals(emptyList<Calendar.Event>(), Meetings.listed(emptyList(), now, horizon, zone, any = true))
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
