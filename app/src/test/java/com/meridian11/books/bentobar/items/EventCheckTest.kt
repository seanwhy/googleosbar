package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.data.MeetingChoices
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * `./bento debug event check`: the real calendar in counts. Run here over made-up sets, it says what the rules made of them
 * and nothing of what was read: no title, no address, no calendar's name.
 */
class EventCheckTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val now = LocalDateTime.of(2026, 11, 16, 13, 0).atZone(zone).toInstant().toEpochMilli()
    private val choices = MeetingChoices.State()

    private fun report(name: String): String = Meetings.report(EventSamples.of(name, now, zone)!!, choices, now, zone)

    /** Every title, address and calendar name of a set: none may be in the report. */
    private fun secrets(name: String): Set<String> {
        val r = EventSamples.of(name, now, zone)!!
        return (r.rows.flatMap { listOf(it.title, it.organizer, it.location) } + r.guests.values.flatten().map { it.email } +
            r.calendars.values.flatMap { listOf(it.name, it.accountName, it.owner) }).filter { it.isNotBlank() }.toSet()
    }

    @Test fun theReportSaysNothingOfWhatItRead() {
        for (name in listOf("demo", "shared", "room", "gone")) {
            val out = report(name)
            for (s in secrets(name)) assertFalse("$name: \"$s\" is in the report", s in out)
            assertFalse("no address at all", '@' in out)
        }
    }

    @Test fun demo() {
        val out = report("demo")
        assertTrue(out, "today: events=5 meetings=3 [with-others=3 only-me-call=0 only-me-any=0]" in out)
        assertTrue(out, "only-me=1" in out)
        assertTrue(out, "all-day=1" in out)
        assertTrue(out, "calendars=4 accounts=2 own=2 editable-not-own=1 read-only=1 primary-by-flag=2 primary-by-owner-only=0" in out)
        assertTrue(out, "picked=with-others in 12m" in out)
    }

    @Test fun shared() {
        val out = report("shared")
        assertTrue(out, "not-mine=1" in out)
        assertTrue(out, "twice=1" in out)
        assertTrue(out, "organizer: mine=0 the-calendar=0 another=3 none=0" in out)
        assertTrue(out, "picked=with-others on now" in out)
    }

    @Test fun rooms() {
        val out = report("room")
        assertTrue(out, "rooms-by-type=1 rooms-by-address-only=1 calendars-as-guests=0" in out)
        assertTrue(out, "you: found-by-account=1 found-by-owner-only=0" in out)
    }

    @Test fun gone() {
        val out = report("gone")
        assertTrue(out, "gone: canceled=1 declined=1 task=0 hidden-calendar=0" in out)
        assertTrue(out, "picked=none" in out)
    }
}
