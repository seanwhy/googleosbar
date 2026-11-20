package com.meridian11.books.bentobar.items

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What Next meeting says in the bar: every cell of the plan's table, with the words the app shows. */
class EventTextTest {
    private val texts = StringFiles.read(listOf("strings.xml", "strings_events.xml")).strings
    private val words = EventText.Words { texts.getValue(it.resource) }
    private val zone = ZoneId.of("America/Los_Angeles")
    private fun fmt(p: String) = { ms: Long -> Instant.ofEpochMilli(ms).atZone(zone).format(DateTimeFormatter.ofPattern(p, Locale.US)) }
    private val clock = EventText.Clock(fmt("HH:mm"), fmt("EEE HH:mm"), fmt("MMM d"), fmt("EEEE HH:mm"), fmt("MMMM d"))
    private val min = 60_000L
    /** Monday, 16 November 2026, 13:00. */
    private val now = LocalDateTime.of(2026, 11, 16, 13, 0).atZone(zone).toInstant().toEpochMilli()
    private val horizon = Meetings.horizon(now, zone)
    private fun at(d: Int, h: Int, m: Int = 0) = LocalDateTime.of(2026, 11, d, h, m).atZone(zone).toInstant().toEpochMilli()
    private fun meeting(title: String, begin: Long, end: Long) = Calendar.Event(1, title, begin, end, false, 0, "", true, true, null)

    private fun bar(e: Calendar.Event, timeOnly: Boolean, chars: Int = 20, soon: Long = 15 * min) =
        EventText.bar(e, now, horizon, zone, timeOnly, chars, soon, words, clock)

    @Test fun everyWordIsAString() {
        for (w in EventText.Word.entries) assertTrue(w.resource, w.resource in texts)
    }

    @Test fun everyStringOfCalendarEventsIsShown() {
        assertEquals("a string in strings_events.xml that no code shows", emptyList<String>(), StringFiles.unused("src/main/res/values/strings_events.xml"))
        assertTrue(StringFiles.names("src/main/res/values/strings_events.xml").all { it.startsWith("events_") })
    }

    @Test fun onNow() {
        val e = meeting("Standup", now - 6 * min, now + 24 * min)
        assertEquals("Standup · 24m left", bar(e, false).text)
        assertEquals("24m left", bar(e, true).text)
        assertTrue(bar(e, true).active)
    }

    @Test fun withinAnHour() {
        val e = meeting("Standup", now + 12 * min, now + 30 * min)
        assertEquals("Standup in 12m", bar(e, false).text)
        assertEquals("in 12m", bar(e, true).text)
        assertTrue("within Show when", bar(e, false).active)
        assertFalse(bar(e, false, soon = 10 * min).active)
    }

    @Test fun laterBeforeTheHorizon() {
        val e = meeting("Standup", at(16, 14, 30), at(16, 15))
        assertEquals("Standup 14:30", bar(e, false).text)
        assertEquals("14:30", bar(e, true).text)
        assertFalse(bar(e, false).active)
    }

    @Test fun afterTheHorizonWithinSixDays() {
        val e = meeting("Standup", at(17, 10), at(17, 11))
        assertEquals("Standup Tue 10:00", bar(e, false).text)
        assertEquals("Tue 10:00", bar(e, true).text)
        assertEquals("Sun 10:00", bar(meeting("Standup", at(22, 10), at(22, 11)), true).text)
    }

    @Test fun furtherOut() {
        val e = meeting("Standup", at(24, 10), at(24, 11))
        assertEquals("Standup Nov 24", bar(e, false).text)
        assertEquals("Nov 24", bar(e, true).text)
    }

    @Test fun aMeetingOnALaterDayIsNeverActive() {
        // At 02:30 a 02:59 meeting is today's; 03:10 is past the horizon, inside "Show when" and still not active.
        val night = LocalDateTime.of(2026, 11, 17, 2, 30).atZone(zone).toInstant().toEpochMilli()
        val h = Meetings.horizon(night, zone)
        val inside = EventText.bar(meeting("A", night + 29 * min, night + 60 * min), night, h, zone, false, 20, 60 * min, words, clock)
        val past = EventText.bar(meeting("B", night + 40 * min, night + 60 * min), night, h, zone, false, 20, 60 * min, words, clock)
        assertTrue(inside.active)
        assertFalse(past.active)
        assertEquals("B in 40m", past.text)
    }

    @Test fun theTitleIsCutToLongestTitle() {
        val e = meeting("Quarterly planning review", now + 12 * min, now + 30 * min)
        assertEquals("Quarterl… in 12m", bar(e, false, chars = 9).text)
        assertEquals("Quarterly planning review in 12m", bar(e, false, chars = 40).text)
        assertEquals("the label is the whole title", "Quarterly planning review", bar(e, false, chars = 9).label)
    }

    @Test fun theSpokenTextAlwaysNamesTheTitle() {
        assertEquals("Now: Standup, ends in 24 minutes", bar(meeting("Standup", now - 6 * min, now + 24 * min), true).desc)
        assertEquals("Next: Standup in 12 minutes", bar(meeting("Standup", now + 12 * min, now + 30 * min), true).desc)
        assertEquals("Next: Standup 14:30", bar(meeting("Standup", at(16, 14, 30), at(16, 15)), true).desc)
        assertEquals("Next: Standup Tuesday 10:00", bar(meeting("Standup", at(17, 10), at(17, 11)), true).desc)
        assertEquals("Next: Standup November 24", bar(meeting("Standup", at(24, 10), at(24, 11)), true).desc)
    }

    @Test fun aStartWithinAMinuteSaysAMinute() {
        assertEquals("in 1m", bar(meeting("A", now + 10_000, now + 30 * min), true).text)
    }

    // ---- Live activity: the bar's words, as the first Next meeting item has them

    private fun live(e: Calendar.Event, timeOnly: Boolean, chars: Int = 20) = EventText.live(e, now, timeOnly, chars, words)

    @Test fun liveActivitySaysTheTitleAndTheCountdown() {
        assertEquals("Standup in 5m", live(meeting("Standup", now + 5 * min, now + 30 * min), false).text)
        assertEquals("Standup · 24m left", live(meeting("Standup", now - 6 * min, now + 24 * min), false).text)
        // Cut so that the countdown stays whole in Live activity's room.
        assertEquals("Quarterly plannin… in 5m", live(meeting("Quarterly planning review", now + 5 * min, now + 30 * min), false, chars = 40).text)
        assertEquals("Quarterl… in 5m", live(meeting("Quarterly planning review", now + 5 * min, now + 30 * min), false, chars = 9).text)
    }

    @Test fun withTheTimeAloneInTheBarLiveActivityShowsNoTitle() {
        // "In the bar": Time keeps the title off the bar: Live activity, beside it, doesn't show it either.
        val soon = meeting("Therapy", now + 5 * min, now + 55 * min)
        val on = meeting("Therapy", now - 6 * min, now + 24 * min)
        assertEquals("in 5m", live(soon, true).text)
        assertEquals("24m left", live(on, true).text)
        assertEquals(bar(soon, true).text, live(soon, true).text)
        assertEquals(bar(on, true).text, live(on, true).text)
        // Spoken, the title is there as in the bar.
        assertEquals("Next: Therapy in 5 minutes", live(soon, true).desc)
        assertEquals("Now: Therapy, ends in 24 minutes", live(on, true).desc)
    }
}
