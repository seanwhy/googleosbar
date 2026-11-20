package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.data.MeetingChoices
import com.meridian11.books.bentobar.data.MeetingChoices.OnlyMe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The sets a test stages on a device (`./bento debug event stage NAME`): what each shows with the choices as they come, and
 * with the switch it is about flipped, through the real rules.
 */
class EventSamplesTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val now = LocalDateTime.of(2026, 11, 16, 13, 0).atZone(zone).toInstant().toEpochMilli()
    private val defaults = MeetingChoices.State()

    private fun events(name: String, choices: MeetingChoices.State = defaults, at: Long = now) =
        Meetings.events(EventSamples.of(name, at, zone)!!, choices, "No title")

    /** What the bar stands on, as Next meeting's "Today" picks it. */
    private fun picked(name: String, choices: MeetingChoices.State = defaults, at: Long = now): String? {
        val horizon = Meetings.horizon(at, zone)
        val today = Meetings.once(events(name, choices, at).filter { it.meeting && Meetings.inHorizon(it.begin, it.end, at, horizon) })
        return Meetings.pick(today, at, 15 * 60_000L)?.title
    }

    @Test fun everyNameIsASetAndNothingElseIs() {
        for (name in EventSamples.names) assertNotNull(name, EventSamples.of(name, now, zone))
        assertNull(EventSamples.of("no-such-set", now, zone))
    }

    @Test fun demo() {
        assertEquals("Design review", picked("demo"))
        val all = events("demo")
        assertEquals(listOf("Design review", "Planning", "Team lunch"), all.filter { it.meeting }.map { it.title })
        assertTrue("the all-day event and the solo one are in the month view", all.map { it.title }.containsAll(listOf("Release day", "Focus time")))
    }

    @Test fun solo() {
        assertNull(picked("solo"))
        assertEquals("Focus time", picked("solo", defaults.copy(onlyMe = OnlyMe.ALWAYS)))
    }

    @Test fun aRoomIsNoOne() {
        assertNull(picked("room"))
        assertEquals(Meetings.Verdict.ONLY_ME, events("room").single().verdict)
    }

    @Test fun sharedCountsTheUsersMeetingOnceAndNotTheColleagues() {
        assertEquals("Team sync", picked("shared"))
        val all = events("shared")
        assertEquals(Meetings.Verdict.NOT_MINE, all.single { it.title == "Their 1:1" }.verdict)
        assertEquals("on two calendars", 2, all.count { it.title == "Team sync" })
        val horizon = Meetings.horizon(now, zone)
        assertEquals("counted once", 1, Meetings.once(all.filter { it.meeting && Meetings.inHorizon(it.begin, it.end, now, horizon) }).size)
        assertTrue("the own calendar's copy", Meetings.once(all.filter { it.meeting }).single().ownCalendar)
    }

    @Test fun unanswered() {
        assertEquals("Vendor intro", picked("unanswered"))
        assertNull(picked("unanswered", defaults.copy(unanswered = false)))
    }

    @Test fun free() {
        assertNull(picked("free"))
        assertEquals("Office hours", picked("free", defaults.copy(free = true)))
    }

    @Test fun workshopGoesToTheStandUpAndBack() {
        assertEquals("Stand-up", picked("workshop"))
        val min = 60_000L
        // The set's own times, read later: the stand-up ends 25 minutes in, and the workshop is back.
        val reading = EventSamples.of("workshop", now, zone)!!
        val list = Meetings.events(reading, defaults, "").filter { it.meeting }
        assertEquals("Stand-up", Meetings.pick(list, now + 20 * min, 15 * min)?.title)
        assertEquals("Workshop", Meetings.pick(list, now + 25 * min, 15 * min)?.title)
    }

    @Test fun link() {
        assertEquals("Hold", picked("link"))
        assertNull(picked("link", defaults.copy(onlyMe = OnlyMe.NEVER)))
    }

    @Test fun allDayEventsAreNoMeetingsAndAreInTheMonthView() {
        assertNull(picked("allday"))
        assertEquals(listOf("Birthday", "Offsite"), events("allday").map { it.title }.sorted())
        assertTrue(events("allday").all { it.verdict == Meetings.Verdict.ALL_DAY })
        // The birthday's calendar is one the user can only read.
        val reading = EventSamples.of("allday", now, zone)!!
        assertTrue(!reading.calendars.getValue(reading.rows.single { it.title == "Birthday" }.calendarId).editable)
    }

    @Test fun canceledAndDeclinedAreNowhere() {
        assertNull(picked("gone"))
        assertEquals(emptyList<Calendar.Event>(), events("gone"))
    }

    @Test fun words() {
        assertEquals("Lunch", picked("words"))
        assertNull(picked("words", defaults.copy(words = listOf("Lunch"))))
    }

    @Test fun aSetStagedAtTenToThreeKeepsItsMeetingUnderTheHorizon() {
        val late = LocalDateTime.of(2026, 11, 17, 2, 55).atZone(zone).toInstant().toEpochMilli()
        for (name in listOf("demo", "workshop", "words", "unanswered", "link", "shared"))
            assertNotNull(name, picked(name, at = late))
        val horizon = Meetings.horizon(late, zone)
        assertTrue(events("demo", at = late).filter { it.meeting }.all { it.begin < horizon })
    }

    @Test fun noAddressOutsideExampleComButTheOneRoom() {
        val room = "c_4f2a1b@resource.calendar.google.com"
        val addresses = EventSamples.names.flatMap { name ->
            val r = EventSamples.of(name, now, zone)!!
            r.calendars.values.flatMap { listOf(it.accountName, it.owner) } + r.rows.map { it.organizer } + r.guests.values.flatten().map { it.email }
        }.filter { it.isNotEmpty() }.toSet()
        for (a in addresses) assertTrue(a, a.endsWith("@example.com") || a == room)
        assertTrue("the room is there", room in addresses)
    }
}
