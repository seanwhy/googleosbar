package com.meridian11.books.bentobar.ui

import com.meridian11.books.bentobar.data.BarConfig
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.data.MapPrefs
import com.meridian11.books.bentobar.data.Store
import com.meridian11.books.bentobar.data.hide
import com.meridian11.books.bentobar.data.without
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A note with Undo stands three seconds, and what "the note is over" means to the one Undo: only the note goes. The
 * change is still the last one, it can still be taken back, and nothing of a removed item is let go for it.
 */
class NoteClockTest {
    private val a = ItemConfig("a", "nope")
    private val b = ItemConfig("b", "nope")
    private val layout = BarConfig(items = listOf(a, b))
    private fun ids() = Store.config.value.items.map { it.id }
    private fun noted() = Undo.noted(Undo.last.value, Undo.over.value)

    @Before fun fresh() {
        Store.open(MapPrefs()) { }
        Store.update { layout }
        Undo.last.value = null
        Undo.over.value = null
    }

    @After fun forget() { Undo.last.value = null; Undo.over.value = null }

    @Test fun aNoteStandsThreeSeconds() = assertEquals(3000, Motion.NOTE)

    @Test fun theTimeIsWholeAgainWheneverNothingHoldsTheNoteAndThereIsNoneToWaitForWhileSomethingDoes() {
        assertEquals(3000L, NoteClock.wait(held = false, explored = false, time = 3000))
        assertNull(NoteClock.wait(held = true, explored = false, time = 3000))
        // What a person's settings make of the three seconds is the time, whole, too.
        assertEquals(10_000L, NoteClock.wait(held = false, explored = false, time = 10_000))
    }

    /** Found by touch, with a screen reader, a note has no pointer over it and no keyboard in it: it waits for the next change. */
    @Test fun whileTheScreenIsExploredByTouchThereIsNoTimeToWaitForWhateverTheSettingsMakeOfIt() {
        assertNull(NoteClock.wait(held = false, explored = true, time = 3000))
        assertNull(NoteClock.wait(held = false, explored = true, time = 10_000))
        assertNull(NoteClock.wait(held = true, explored = true, time = 3000))
    }

    @Test fun whenTheNoteIsOverTheChangeIsStillTheLastOneAndUndoStillTakesItBack() {
        assertTrue(Undo.change(removed = "a") { it.without("a") })
        val kept = Undo.last.value!!
        assertSame("said, with its Undo", kept, noted())
        Undo.noteOver(kept)
        assertNull("no page says it any more", noted())
        assertSame("and it is still the change to undo", kept, Undo.last.value)
        assertTrue(Undo.offered(kept, Store.config.value))
        assertTrue(Undo.undo())
        assertEquals(listOf("a", "b"), ids())
        assertNull(Undo.over.value)
    }

    @Test fun theNextChangeHasItsOwnNoteAlsoWhenItIsEqualToTheOneThatWent() {
        assertTrue(Undo.change(removed = "a") { it.without("a") })
        Undo.noteOver(Undo.last.value!!)
        assertTrue(Undo.undo())
        // The same removal again: an equal change, and a new one. It is said.
        assertTrue(Undo.change(removed = "a") { it.without("a") })
        assertNotNull(noted())
        Undo.noteOver(Undo.last.value!!)
        assertNull(noted())
        assertTrue(Undo.change { it.hide("b") })
        assertNotNull("another change is said in its turn", noted())
    }

    @Test fun aNoteWhoseChangeIsNoLongerTheLastOneCannotEndTheNextOnesNote() {
        assertTrue(Undo.change(removed = "a") { it.without("a") })
        val first = Undo.last.value!!
        assertTrue(Undo.change(removed = "b") { it.without("b") })
        Undo.noteOver(first)
        assertSame(Undo.last.value, noted())
    }
}
