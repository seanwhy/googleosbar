package com.meridian11.books.bentobar.bar

import com.meridian11.books.bentobar.data.Position
import com.meridian11.books.bentobar.data.Position.CENTER
import com.meridian11.books.bentobar.data.Position.LEFT
import com.meridian11.books.bentobar.data.Position.RIGHT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keep awake's choice: which strip holds the screen on (`KeepAwakeRule.holder`: the first placed that is visible), and
 * when a wake lock holds it in its place (`KeepAwakeRule.choose`). The windows are named by their areas here, and
 * `KeepAwake.update` carries the choice out.
 */
class KeepAwakeTest {
    private fun choose(on: Boolean, holder: Position?, interactive: Boolean = true, locked: Boolean = false) =
        KeepAwakeRule.choose(on, holder, interactive, locked)

    private fun drawing() = BarRules.AreaState(measured = 300, empty = false, shown = true, pending = false)
    private fun blank() = BarRules.AreaState(measured = 1, empty = true, shown = true, pending = false)
    /** Shown and not measured yet: its root is invisible until its first measure. */
    private fun pending() = BarRules.AreaState(measured = 300, empty = false, shown = true, pending = true)
    private fun gone() = BarRules.AreaState(measured = 300, empty = false, shown = false, pending = false)
    private val both = listOf(RIGHT, LEFT)

    @Test fun theHolderIsTheFirstPlacedStripThatIsVisible() {
        assertEquals(RIGHT, KeepAwakeRule.holder(both, mapOf(RIGHT to drawing(), LEFT to drawing())))
        assertEquals("Right's window is invisible", LEFT, KeepAwakeRule.holder(both, mapOf(RIGHT to blank(), LEFT to drawing())))
        assertEquals("Right has no window", LEFT, KeepAwakeRule.holder(both, mapOf(RIGHT to gone(), LEFT to drawing())))
        assertNull("nothing is visible: the wake lock's turn", KeepAwakeRule.holder(both, mapOf(RIGHT to blank(), LEFT to blank())))
        assertNull("never measured, never shown", KeepAwakeRule.holder(both, emptyMap()))
        // An area that is not placed (it is owed, or its window is about to go) has no window to hold the flag.
        assertNull(KeepAwakeRule.holder(listOf(RIGHT), mapOf(RIGHT to blank(), LEFT to drawing())))
        assertNull(KeepAwakeRule.holder(emptyList(), mapOf(RIGHT to drawing())))
    }

    /**
     * A strip that was shown and is not measured yet has an invisible root, and Android keeps the screen on only for a
     * visible window: it holds nothing until it shows. The bar coming back after an unlock with keep awake on is all such
     * strips: the wake lock holds the screen until the first of them shows, and the flag moves to it then.
     */
    @Test fun aStripNotMeasuredYetIsNoHolderAndTheWakeLockHoldsUntilItShows() {
        assertNull(KeepAwakeRule.holder(both, mapOf(RIGHT to pending(), LEFT to pending())))
        assertTrue(choose(on = true, holder = KeepAwakeRule.holder(both, mapOf(RIGHT to pending(), LEFT to pending()))).lock)
        // A visible strip carries it meanwhile, whatever comes before it in room order: no wake lock while one is visible.
        assertEquals(LEFT, KeepAwakeRule.holder(both, mapOf(RIGHT to pending(), LEFT to drawing())))
        // Measured and drawing: the flag moves to it, and the wake lock is let go.
        val shown = choose(on = true, holder = KeepAwakeRule.holder(both, mapOf(RIGHT to drawing(), LEFT to pending())))
        assertEquals(RIGHT, shown.flag)
        assertFalse(shown.lock)
    }

    @Test fun theFlagIsOnTheWindowOfTheAreaThatKeepsAwake() {
        for (area in listOf(RIGHT, LEFT, CENTER)) {
            val c = choose(on = true, holder = area)
            assertEquals(area, c.flag)
            assertFalse("a window holds the screen: no wake lock beside it", c.lock)
        }
    }

    /** No strip is visible (the bar is hidden, every area is empty, or none is measured yet): today's rule for the one strip. */
    @Test fun withNoStripToCarryTheFlagAWakeLockHoldsTheScreen() {
        val c = choose(on = true, holder = null)
        assertNull(c.flag)
        assertTrue(c.lock)
    }

    /** With no visible strip no window has the flag: a window comes back invisible, and the flag is only ever on a visible one. */
    @Test fun withNoStripToCarryItNoWindowHasTheFlag() {
        for (interactive in listOf(true, false)) for (locked in listOf(true, false))
            assertNull(choose(on = true, holder = null, interactive = interactive, locked = locked).flag)
    }

    @Test fun theWakeLockIsNeverHeldWithTheScreenOffOrOnTheLockScreen() {
        assertFalse(choose(on = true, holder = null, interactive = false).lock)
        assertFalse(choose(on = true, holder = null, locked = true).lock)
        assertFalse(choose(on = true, holder = null, interactive = false, locked = true).lock)
    }

    @Test fun withKeepAwakeOffNoWindowHasTheFlagAndNothingIsHeld() {
        for (holder in listOf(null, RIGHT, LEFT, CENTER)) for (interactive in listOf(true, false)) for (locked in listOf(true, false)) {
            val c = choose(on = false, holder = holder, interactive = interactive, locked = locked)
            assertNull("off: no window has the flag", c.flag)
            assertFalse("off: no wake lock", c.lock)
        }
    }

    /** One window at most: the flag is never asked for on two, whatever the screen and the lock say. */
    @Test fun aStripThatCarriesTheFlagNeedsNoWakeLockWhateverTheScreenSays() {
        for (interactive in listOf(true, false)) for (locked in listOf(true, false)) {
            val c = choose(on = true, holder = LEFT, interactive = interactive, locked = locked)
            assertEquals(LEFT, c.flag)
            assertFalse(c.lock)
        }
    }
}
