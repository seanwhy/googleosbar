package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.util.ClockAnchor
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keep awake is on for a stretch of real time. Its deadline is a wall-clock time, out of date from a clock change until the
 * next [Caffeine.check] follows it, and while the bar is hidden no tick is there to do that.
 */
class CaffeineTest {
    private val start = ClockAnchor(wall = 1_000_000_000L, elapsed = 50_000L, boot = 7)
    private val twoMinutes = start.wall + 120_000

    private fun set(until: Long) { Caffeine.until.value = until; Caffeine.anchor = start }

    /** [passed] ms of real time after the start, with the clock moved by [moved] on top. */
    private fun at(passed: Long, moved: Long = 0, boot: Int = 7) = ClockAnchor(start.wall + passed + moved, start.elapsed + passed, boot)

    @After fun forget() { Caffeine.until.value = 0; Caffeine.anchor = ClockAnchor(0, 0, -1) }

    @Test fun itLastsItsTimeWithTheClockLeftAlone() {
        set(twoMinutes)
        assertTrue(Caffeine.active(at(119_000)))
        assertFalse(Caffeine.active(at(120_000)))
    }

    /** A screen broadcast reads this while the clock change hasn't been followed yet: the wake lock must not let go. */
    @Test fun aClockSetForwardDoesNotEndItEarly() {
        set(twoMinutes)
        assertTrue(Caffeine.active(at(10_000, moved = 3_600_000)))
        assertTrue(Caffeine.active(at(119_000, moved = 3_600_000)))
    }

    @Test fun aClockSetBackDoesNotKeepItOnPastItsTime() {
        set(twoMinutes)
        assertFalse(Caffeine.active(at(120_000, moved = -3_600_000)))
    }

    /** Off and "until I turn it off" have no deadline for a clock change to move. */
    @Test fun offStaysOffAndForeverStaysOnWhateverTheClockDid() {
        set(0)
        assertFalse(Caffeine.active(at(10_000, moved = 3_600_000_000)))
        set(Caffeine.FOREVER)
        assertTrue(Caffeine.active(at(10_000, moved = 3_600_000_000)))
        assertTrue(Caffeine.active(at(10_000, moved = -3_600_000)))
    }

    /** Across a reboot the wall clock is the only reference left. */
    @Test fun acrossARebootItGoesByTheWallClock() {
        set(twoMinutes)
        assertTrue(Caffeine.active(ClockAnchor(twoMinutes - 1_000, 10_000, 8)))
        assertFalse(Caffeine.active(ClockAnchor(twoMinutes + 1_000, 10_000, 8)))
    }
}
