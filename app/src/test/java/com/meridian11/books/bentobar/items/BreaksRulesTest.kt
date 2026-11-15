package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.items.BreaksRules.Watch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Work is time the screen is on and the bar sampled, a break that long without it; nothing else is watched (ruling R9). */
class BreaksRulesTest {
    private val sec = 1000L
    private val min = 60 * sec
    private val every = 50 * min
    private val rest = 5 * min
    private val t0 = 1_000_000_000L

    /** [w] sampled once a second for [ms], the screen on or off as [interactive] says. */
    private fun run(w: Watch, ms: Long, interactive: Boolean = true): Watch {
        var watch = w
        val end = w.lastAt + ms
        var t = w.lastAt
        while (t < end) { t += sec; watch = BreaksRules.step(watch, t, interactive, rest) }
        return watch
    }

    @Test fun fiftyMinutesOfSamplesAreDue() {
        val almost = run(BreaksRules.tookOne(t0), 50 * min - sec)
        assertFalse(BreaksRules.due(almost, every))
        assertEquals("1 min", BreaksRules.text(almost, every))
        val due = run(almost, sec)
        assertEquals(50 * min, due.workedMs)
        assertTrue(BreaksRules.due(due, every))
        assertEquals("Break", BreaksRules.text(due, every))
    }

    @Test fun fiveMinutesAwayStartAgain() {
        val worked = run(BreaksRules.tookOne(t0), 30 * min)
        val away = run(worked, 5 * min, interactive = false)
        assertEquals(0L, away.workedMs)
        assertEquals(1 * min, run(away, 1 * min).workedMs)
        // A break taken while one was due ends it.
        val due = run(BreaksRules.tookOne(t0), 55 * min)
        assertFalse(BreaksRules.due(run(due, 5 * min, interactive = false), every))
    }

    @Test fun aShortAwayDoesNot() {
        // Two minutes away of the five needed: what was worked stays, and keeps counting after.
        val worked = run(BreaksRules.tookOne(t0), 30 * min)
        val away = run(worked, 2 * min, interactive = false)
        assertEquals(30 * min, away.workedMs)
        assertEquals(40 * min, run(away, 10 * min).workedMs)
        // Short aways don't add up to a break: three of three minutes, with work between them.
        var w = worked
        repeat(3) { w = run(run(w, 3 * min, interactive = false), 1 * min) }
        assertEquals(33 * min, w.workedMs)
    }

    @Test fun aSilenceLongerThanTheGapIsAway() {
        val worked = run(BreaksRules.tookOne(t0), 30 * min)
        // No sample for 3 minutes (the bar hidden), then the screen is on: those minutes were not work.
        val back = BreaksRules.step(worked, worked.lastAt + 3 * min, true, rest)
        assertEquals(30 * min, back.workedMs)
        assertEquals(back.lastAt, worked.lastAt + 3 * min)
        // Six minutes of it are a break.
        assertEquals(0L, BreaksRules.step(worked, worked.lastAt + 6 * min, true, rest).workedMs)
        // A pause within the gap (a slow tick, battery saver) is still work.
        val slow = BreaksRules.step(worked, worked.lastAt + BreaksRules.GAP_MS, true, rest)
        assertEquals(30 * min + BreaksRules.GAP_MS, slow.workedMs)
        // A clock that went back counts nothing either way.
        assertEquals(worked.workedMs, BreaksRules.step(worked, worked.lastAt - min, true, rest).workedMs)
    }

    @Test fun aClickStartsAgain() {
        val due = run(BreaksRules.tookOne(t0), 60 * min)
        assertTrue(BreaksRules.due(due, every))
        val now = due.lastAt + 500
        val again = BreaksRules.tookOne(now)
        assertEquals(Watch(0L, 0L, now), again)
        assertFalse(BreaksRules.due(again, every))
        assertEquals("50 min", BreaksRules.text(again, every))
    }

    @Test fun theFirstSampleOnlyStartsTheCount() {
        // Nothing sampled before: no time before it is work or a break.
        val first = BreaksRules.step(Watch(), t0, true, rest)
        assertEquals(Watch(0L, 0L, t0), first)
    }

    @Test fun theItemCountsWhatTheScreenSaysAtEachSample() {
        // The seam: the ticker's sample feeds the rule with whether the screen is on, and a break as long as the item's.
        val before = BreaksItem.interactive
        try {
            BreaksItem.watch = Watch()
            var on = true
            BreaksItem.interactive = { on }
            var t = t0
            repeat(10 * 60) { t += sec; BreaksItem.sample(t) }
            assertEquals("the first sample only starts it", 10 * min - sec, BreaksItem.watch.workedMs)
            on = false
            repeat(4 * 60) { t += sec; BreaksItem.sample(t) }
            assertEquals("four minutes off are not yet a break", 10 * min - sec, BreaksItem.watch.workedMs)
            repeat(60) { t += sec; BreaksItem.sample(t) }
            assertEquals("five are", 0L, BreaksItem.watch.workedMs)
        } finally {
            BreaksItem.interactive = before
            BreaksItem.watch = Watch()
        }
    }

    @Test fun theTimeToTheNextIsInMinutesThenHours() {
        assertEquals("38 min", BreaksRules.text(Watch(12 * min, 0, t0), every))
        assertEquals("rounded up: a part of a minute is a minute", "39 min", BreaksRules.text(Watch(12 * min - 30 * sec, 0, t0), every))
        assertEquals("1 h 20", BreaksRules.text(Watch(10 * min, 0, t0), 90 * min))
        assertEquals("Break", BreaksRules.text(Watch(120 * min, 0, t0), 90 * min))
    }
}
