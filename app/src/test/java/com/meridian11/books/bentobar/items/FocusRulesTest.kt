package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.items.FocusRules.Click
import com.meridian11.books.bentobar.items.FocusRules.Session
import com.meridian11.books.bentobar.items.FocusRules.Then
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Focus without a device: how long a session runs, what each road to its end does with it, and what a click does. */
class FocusRulesTest {
    private val t0 = 1_800_000_000_000L
    private val min = 60_000L

    @Test fun fiftyMinutesEndFiftyMinutesOn() {
        val s = FocusRules.start(t0, 50, dark = false)
        assertEquals(Session(t0, t0 + 50 * min, false), s)
        assertEquals(50 * min, FocusRules.left(s, t0))
        assertFalse(FocusRules.over(s, t0 + 50 * min - 1))
        assertTrue(FocusRules.over(s, t0 + 50 * min))
        assertEquals(0L, FocusRules.left(s, t0 + 51 * min))
        // The bar's face: time left, rounded up as the timer's is, with hours when there are any.
        assertEquals("24:10", FocusRules.text(s, t0 + 50 * min - (24 * min + 10_000), "On"))
        assertEquals("1:02:00", FocusRules.text(FocusRules.start(t0, 90, false), t0 + 28 * min, "On"))
    }

    @Test fun withoutAnEndItNeverEndsByItself() {
        val s = FocusRules.start(t0, 0, dark = true)
        assertEquals(Session(t0, 0L, true), s)
        assertNull(FocusRules.left(s, t0 + 1000 * min))
        assertFalse(FocusRules.over(s, Long.MAX_VALUE))
        assertFalse(FocusRules.over(null, t0))
        assertEquals("On", FocusRules.text(s, t0 + 5 * min, "On"))
        // Nothing to add to a session that has no end.
        assertSame(s, FocusRules.extend(s, 10))
    }

    @Test fun tenMoreMinutesMoveTheEnd() {
        val s = FocusRules.start(t0, 25, dark = false)
        val more = FocusRules.extend(s, FocusRules.MORE)
        assertEquals(t0 + 35 * min, more.endsAt)
        assertEquals(t0, more.startedAt)
        assertEquals(35 * min, FocusRules.left(more, t0))
    }

    @Test fun aSessionPastItsEndIsEndedWhoeverNotices() {
        val s = FocusRules.start(t0, 25, dark = false)
        val after = t0 + 26 * min
        assertTrue(FocusRules.over(s, after))
        // The service starting (a restart, a reboot), the service ending and the item leaving: each ends it.
        for (placed in listOf(true, false)) {
            assertEquals(Then.END, FocusRules.settle(s, Settle.SERVICE_STARTED, after, placed))
            assertEquals(Then.END, FocusRules.settle(s, Settle.SERVICE_ENDING, after, placed))
            assertEquals(Then.END, FocusRules.settle(s, Settle.LEFT_LAYOUT, after, placed))
        }
        // With no session nobody does anything.
        for (why in Settle.entries) assertEquals(Then.KEEP, FocusRules.settle(null, why, after, true))
    }

    @Test fun anEndlessSessionEndsWithItsItemAndWithTheService() {
        val s = FocusRules.start(t0, 0, dark = false)
        val later = t0 + 600 * min
        assertEquals(Then.END, FocusRules.settle(s, Settle.LEFT_LAYOUT, later, placed = false))
        assertEquals(Then.END, FocusRules.settle(s, Settle.SERVICE_ENDING, later, placed = true))
        // Started again with its item gone (removed while nothing could tell it): nothing is left to end it.
        assertEquals(Then.END, FocusRules.settle(s, Settle.SERVICE_STARTED, later, placed = false))
        // Started again with its item still placed (a rebind with no ending before it): it goes on.
        assertEquals(Then.REARM, FocusRules.settle(s, Settle.SERVICE_STARTED, later, placed = true))
    }

    @Test fun aTimedSessionOutlivesARebindAndIsArmedAgain() {
        val s = FocusRules.start(t0, 50, dark = false)
        val during = t0 + 10 * min
        // Its alarm ends it whether or not the service is there.
        assertEquals(Then.KEEP, FocusRules.settle(s, Settle.SERVICE_ENDING, during, placed = true))
        // A reboot lost the alarm: it is set again.
        assertEquals(Then.REARM, FocusRules.settle(s, Settle.SERVICE_STARTED, during, placed = true))
        assertEquals(Then.REARM, FocusRules.settle(s, Settle.SERVICE_STARTED, during, placed = false))
        // The item leaving ends it all the same.
        assertEquals(Then.END, FocusRules.settle(s, Settle.LEFT_LAYOUT, during, placed = false))
    }

    @Test fun aRuleSwitchedOffElsewhereIsForgotten() {
        val s = FocusRules.start(t0, 50, dark = false)
        assertNull("switched off in Android's own settings: forgotten, nothing switched", FocusRules.seen(s, ruleOn = false))
        assertSame(s, FocusRules.seen(s, ruleOn = true))
        assertSame("Android can't say (below Android 15): kept", s, FocusRules.seen(s, ruleOn = null))
        assertNull(FocusRules.seen(null, ruleOn = true))
    }

    @Test fun withoutAccessAClickAsksAndStartsNothing() {
        assertEquals(Click.ASK, FocusRules.click(null, granted = false))
        assertEquals(Click.START, FocusRules.click(null, granted = true))
        // While one runs a click opens the menu, access or not.
        val s = FocusRules.start(t0, 50, dark = false)
        assertEquals(Click.MENU, FocusRules.click(s, granted = true))
        assertEquals(Click.MENU, FocusRules.click(s, granted = false))
        // And the keeper asks Android nothing beyond whether the access is there.
        val quiet = FakeQuiet(granted = false)
        val keeper = FocusKeeper(FocusKeeper.Wiring(quiet, alarm = { error("no alarm") }, kept = { error("nothing kept") }, wall = { t0 },
            placed = { true }, background = { it() }, main = { it() }))
        assertFalse(keeper.start(50, dark = false))
        assertEquals(0, quiet.ons)
        assertNull(keeper.session)
    }

    @Test fun whatIsKeptReadsBackAsTheSameSession() {
        for (s in listOf(Session(t0, t0 + 50 * min, false), Session(t0, 0L, true)))
            assertEquals(s, FocusRules.read(FocusRules.write(s)))
        for (bad in listOf(null, "", "x", "1 2", "1 2 3 4", "a b c", "1 2 7")) assertNull(bad, FocusRules.read(bad))
    }

    @Test fun theLengthIsTheOptionsWord() {
        assertEquals(25, FocusRules.minutes("25"))
        assertEquals(0, FocusRules.minutes("0"))
        assertEquals(50, FocusRules.minutes("nonsense"))
    }
}

/** Do Not Disturb as a test sees it: what was asked of it, and what it answers. */
internal class FakeQuiet(var granted: Boolean = true, var accepts: Boolean = true, var ruleOn: Boolean? = null) : FocusQuiet {
    var ons = 0; var offs = 0; var removes = 0; var asked = 0
    override fun granted() = granted
    override fun on(dark: Boolean): Boolean { ons++; return accepts }
    override fun off() { offs++ }
    override fun isOn(): Boolean? { asked++; return ruleOn }
    override fun remove() { removes++ }
}
