package com.meridian11.books.bentobar.items

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which meeting the bar stands on ([Meetings.pick]): the one on now, else the next, and one inside a long one at its lead. */
class MeetingsPickTest {
    private val min = 60_000L
    private val lead = 15 * min
    private val t = 1_000_000 * min
    private fun meeting(title: String, begin: Long, end: Long) = Calendar.Event(begin, title, begin, end, false, 0, "", true, true, null)

    @Test fun theWorkshopThroughItsWholeAfternoon() {
        // 13:00 to 17:00, and a stand-up 14:00 to 14:15 inside it.
        val workshop = meeting("Workshop", t, t + 240 * min)
        val standup = meeting("Stand-up", t + 60 * min, t + 75 * min)
        val all = listOf(workshop, standup)
        assertEquals("before the lead", workshop, Meetings.pick(all, t + 30 * min, lead))
        assertEquals("a minute before it", workshop, Meetings.pick(all, t + 45 * min - 1, lead))
        assertEquals("at the lead", standup, Meetings.pick(all, t + 45 * min, lead))
        assertEquals("the inner one on", standup, Meetings.pick(all, t + 60 * min, lead))
        assertEquals(standup, Meetings.pick(all, t + 75 * min - 1, lead))
        assertEquals("after it, the workshop again", workshop, Meetings.pick(all, t + 75 * min, lead))
        assertNull("and nothing after the workshop", Meetings.pick(all, t + 240 * min, lead))
    }

    @Test fun backToBackTheOneOnStaysUntilItEnds() {
        val a = meeting("A", t, t + 30 * min)
        val b = meeting("B", t + 30 * min, t + 60 * min)
        assertEquals(a, Meetings.pick(listOf(a, b), t + 20 * min, lead))
        assertEquals(a, Meetings.pick(listOf(a, b), t + 30 * min - 1, lead))
        assertEquals(b, Meetings.pick(listOf(a, b), t + 30 * min, lead))
    }

    @Test fun withAGapTheOneOnStaysThenTheNext() {
        val a = meeting("A", t, t + 30 * min)
        val b = meeting("B", t + 40 * min, t + 60 * min)
        assertEquals(a, Meetings.pick(listOf(a, b), t + 29 * min, lead))
        assertEquals("the gap", b, Meetings.pick(listOf(a, b), t + 30 * min, lead))
        assertEquals("nothing on: the next, however far", b, Meetings.pick(listOf(b), t - 600 * min, lead))
    }

    @Test fun aPlainOverlapTakesOverAtTheLead() {
        val a = meeting("A", t, t + 60 * min)
        val b = meeting("B", t + 45 * min, t + 90 * min)
        assertEquals(a, Meetings.pick(listOf(a, b), t + 29 * min, lead))
        assertEquals(b, Meetings.pick(listOf(a, b), t + 30 * min, lead))
        assertEquals("both on: the one that began last", b, Meetings.pick(listOf(a, b), t + 50 * min, lead))
    }

    @Test fun ofTwoThatBeganTogetherTheOneThatEndsFirst() {
        val long = meeting("Long", t, t + 120 * min)
        val short = meeting("Short", t, t + 30 * min)
        assertEquals(short, Meetings.pick(listOf(long, short), t + 5 * min, lead))
        assertEquals(long, Meetings.pick(listOf(long, short), t + 30 * min, lead))
        assertEquals("and of two to come", short, Meetings.pick(listOf(long, short), t - 5 * min, lead))
    }

    @Test fun theLeadIsTheArgument() {
        val workshop = meeting("Workshop", t, t + 240 * min)
        val standup = meeting("Stand-up", t + 60 * min, t + 75 * min)
        assertEquals(workshop, Meetings.pick(listOf(workshop, standup), t + 55 * min - 1, 5 * min))
        assertEquals(standup, Meetings.pick(listOf(workshop, standup), t + 55 * min, 5 * min))
        assertEquals(standup, Meetings.pick(listOf(workshop, standup), t + 1, 60 * min))
    }

    @Test fun nothingToPick() {
        assertNull(Meetings.pick(emptyList(), t, lead))
        assertNull("ended", Meetings.pick(listOf(meeting("A", t - 60 * min, t)), t, lead))
    }
}
