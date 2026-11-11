package com.meridian11.books.bentobar.util

import org.junit.Assert.assertEquals
import org.junit.Test

class ClockJumpTest {
    private val start = ClockAnchor(wall = 1_000_000_000L, elapsed = 50_000L, boot = 7)

    @Test fun timePassingIsNotAJump() {
        assertEquals(0L, start.jump(ClockAnchor(start.wall + 600_000, start.elapsed + 600_000, 7)))
    }

    @Test fun settingTheClockAheadIsMeasured() {
        // Ten minutes passed, and the clock was set an hour ahead.
        assertEquals(3_600_000L, start.jump(ClockAnchor(start.wall + 600_000 + 3_600_000, start.elapsed + 600_000, 7)))
    }

    @Test fun settingTheClockBackIsMeasured() {
        assertEquals(-3_600_000L, start.jump(ClockAnchor(start.wall + 60_000 - 3_600_000, start.elapsed + 60_000, 7)))
    }

    @Test fun smallCorrectionsAreIgnored() {
        assertEquals(0L, start.jump(ClockAnchor(start.wall + 61_500, start.elapsed + 60_000, 7)))
    }

    @Test fun aRebootOrAnUnknownAnchorIsNotAJump() {
        assertEquals(0L, start.jump(ClockAnchor(start.wall + 3_600_000, 10_000, 8)))
        assertEquals(0L, ClockAnchor(0, 0, -1).jump(ClockAnchor(start.wall, start.elapsed, -1)))
    }

    /** Keep awake's release is timed on this: real time, so a clock change while the bar is hidden can't move it. */
    @Test fun aDeadlineIsARealTimeAwayWhateverTheClockDid() {
        val deadline = start.wall + 3_600_000
        // Ten minutes later, nothing changed: fifty left.
        assertEquals(3_000_000L, start.left(deadline, ClockAnchor(start.wall + 600_000, start.elapsed + 600_000, 7)))
        // The clock put an hour forward, then back an hour and a half: still fifty left.
        assertEquals(3_000_000L, start.left(deadline, ClockAnchor(start.wall + 600_000 + 3_600_000, start.elapsed + 600_000, 7)))
        assertEquals(3_000_000L, start.left(deadline, ClockAnchor(start.wall + 600_000 - 5_400_000, start.elapsed + 600_000, 7)))
    }

    /** Across a reboot only the wall clock is left to go by. */
    @Test fun aDeadlineAcrossARebootGoesByTheWallClock() {
        val deadline = start.wall + 3_600_000
        assertEquals(1_000_000L, start.left(deadline, ClockAnchor(deadline - 1_000_000, 10_000, 8)))
    }
}
