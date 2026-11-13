package com.meridian11.books.bentobar.items

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the ticker computes an item again: the arithmetic that decides whether a tick is the item's turn. */
class TickRulesTest {
    /**
     * Seen on a device: the volume slider followed the volume keys after up to two seconds. Ticks aim
     * at the start of each second, so two of them can be 990 ms apart, and "a second has not passed"
     * then cost a one-second item its turn.
     */
    @Test fun aTickThatLandsALittleEarlyIsStillAOneSecondItemsTurn() {
        assertTrue(TickRules.due(now = 10_990, last = 10_000, everyMs = 1000))
        assertTrue(TickRules.due(now = 11_000, last = 10_000, everyMs = 1000))
        assertTrue(TickRules.due(now = 11_040, last = 10_000, everyMs = 1000))
        // Every tick of a minute with jitter either way: none is skipped.
        var last = 0L
        var computed = 0
        for (i in 1..60) {
            val now = i * 1000L + if (i % 2 == 0) -40 else 35
            if (TickRules.due(now, last, 1000)) { computed++; last = now }
        }
        assertEquals(60, computed)
    }

    @Test fun anItemComputedByHandBetweenTwoTicksStillHasItsTurnAtTheNextTick() {
        // Ticks at 10.0 and 11.0 s; a click recomputes the seconds clock at 10.4 s. Stamped 10.4, the tick at 11.0 would
        // find 600 ms and skip it: "10" until 12.0, then "12". Stamped with the tick before it, 11.0 is its turn.
        val byHand = TickRules.stamp(now = 10_400, lastTick = 10_000)
        assertEquals(10_000L, byHand)
        assertTrue(TickRules.due(now = 11_000, last = byHand, everyMs = 1000))
        assertFalse(TickRules.due(now = 11_000, last = 10_400, everyMs = 1000)) // what stamping it 10.4 did
        // A click right after a tick, a click just before the next: either way the next tick is the item's turn.
        for (at in listOf(10_010L, 10_500L, 10_990L)) assertTrue(TickRules.due(11_000, TickRules.stamp(at, 10_000), 1000))
        // A slower item computed by hand keeps its pace from the tick before, not from the click.
        assertFalse(TickRules.due(now = 19_000, last = TickRules.stamp(10_400, 10_000), everyMs = 10_000))
        assertTrue(TickRules.due(now = 20_000, last = TickRules.stamp(10_400, 10_000), everyMs = 10_000))
    }

    @Test fun anItemThatHadSomethingToSayStaysOutForAWhile() {
        // Network on "show when": a stream downloads in bursts every few seconds. Without a hold the item popped in for a
        // second each time and the whole strip shifted; with one it stays out while the bursts go on, and goes after them.
        assertTrue(TickRules.held(active = true, lastActive = null, now = 50_000))
        assertTrue(TickRules.held(active = false, lastActive = 40_000, now = 50_000))
        assertTrue(TickRules.held(active = false, lastActive = 40_000, now = 40_000 + TickRules.HOLD_MS - 1))
        assertFalse(TickRules.held(active = false, lastActive = 40_000, now = 40_000 + TickRules.HOLD_MS))
        assertFalse(TickRules.held(active = false, lastActive = null, now = 50_000))
        // A last moment that lies ahead (the clock was moved in a test) holds nothing.
        assertFalse(TickRules.held(active = false, lastActive = 60_000, now = 50_000))
        assertEquals(20_000L, TickRules.HOLD_MS)
    }

    @Test fun onlyReadingsThatCrossTheirLineNowAndThenAreHeld() {
        // Speed and load go up and down around the line the user set: those items blink without the hold.
        for (type in listOf("network", "cpu", "memory")) assertTrue(type, Items.of(type)!!.holds)
        // Something that has ended is gone at once: a meeting over (its next one must not show while presenting), a timer
        // stopped, Keep awake turned off, a flight no longer shown. Now playing and Heat have holds of their own.
        for (type in listOf("event", "timer", "countdown", "caffeine", "flight", "media", "heat", "weather", "battery", "clock"))
            assertFalse(type, Items.of(type)!!.holds)
    }

    @Test fun beforeTheFirstTickAnItemComputedByHandIsStampedWhenItWas() {
        assertEquals(10_400L, TickRules.stamp(now = 10_400, lastTick = null))
        // A tick time that lies ahead (the clock was moved in a test) is no tick before it.
        assertEquals(10_400L, TickRules.stamp(now = 10_400, lastTick = 11_000))
    }

    @Test fun slowerItemsKeepTheirPace() {
        // Every ten seconds is every tenth tick, also when ticks jitter.
        var last = 0L
        val turns = ArrayList<Int>()
        for (i in 1..40) {
            val now = i * 1000L + if (i % 3 == 0) -30 else 20
            if (TickRules.due(now, last, 10_000)) { turns += i; last = now }
        }
        assertEquals(listOf(10, 20, 30, 40), turns)
        assertFalse(TickRules.due(now = 9_000, last = 0, everyMs = 10_000))
    }

    @Test fun underBatterySaverEveryOtherSecondIsEveryTick() {
        var last = 0L
        var computed = 0
        for (i in 1..30) {
            val now = i * 2000L - 25
            if (TickRules.due(now, last, 1000)) { computed++; last = now }
        }
        assertEquals(30, computed)
    }
}
