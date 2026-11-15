package com.meridian11.books.bentobar.ui

import com.meridian11.books.bentobar.data.BarConfig
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.data.MapPrefs
import com.meridian11.books.bentobar.data.Position
import com.meridian11.books.bentobar.data.Section
import com.meridian11.books.bentobar.data.Store
import com.meridian11.books.bentobar.data.hide
import com.meridian11.books.bentobar.data.moved
import com.meridian11.books.bentobar.data.oneArea
import com.meridian11.books.bentobar.data.putAllIn
import com.meridian11.books.bentobar.data.show
import com.meridian11.books.bentobar.data.without
import com.meridian11.books.bentobar.data.fresh
import com.meridian11.books.bentobar.items.Items
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The Bar page's one Undo: the last layout change, taken back once, and never over a layout arranged otherwise since. */
class UndoTest {
    private val a = ItemConfig("a", "nope")
    private val b = ItemConfig("b", "nope", options = mapOf("k" to "1"))
    private val c = ItemConfig("c", "nope", Section.HIDDEN)
    private val layout = BarConfig(items = listOf(a, b, c))
    private fun ids() = Store.config.value.items.map { it.id }

    @Before fun fresh() {
        Store.open(MapPrefs()) { }
        Store.update { layout }
        Undo.last.value = null
    }

    @Test fun aChangeIsTakenBackOnceAndASecondUndoDoesNothing() {
        assertTrue(Undo.change { it.copy(items = it.items.moved("a", Section.SHOWN, 1)) })
        assertEquals(listOf("b", "a", "c"), ids())
        assertTrue(Undo.undo())
        assertEquals(layout, Store.config.value)
        assertFalse(Undo.undo())
        assertEquals(layout, Store.config.value)
    }

    /** The settings window closed for good: its Undo ends with it, and a window opened later has nothing to take back. */
    @Test fun whenTheWindowIsClosedTheChangeCanBeTakenBackNoMoreAndItsNoteIsGone() {
        assertTrue(Undo.change(removed = "a") { it.without("a") })
        Undo.noteOver(Undo.last.value!!)
        Undo.end()
        assertNull(Undo.last.value)
        assertNull(Undo.over.value)
        assertEquals(emptySet<String>(), Undo.held("nope"))
        assertFalse(Undo.undo())
        assertEquals(listOf("b", "c"), ids())
        // With nothing kept it does nothing.
        Undo.end()
        assertEquals(listOf("b", "c"), ids())
    }

    @Test fun looksCommandIsOneChangeTakenBackInOneStepWithAreasAndOrderAsTheyWere() {
        val three = BarConfig(items = listOf(a.copy(area = Position.LEFT), b.copy(area = Position.CENTER), c.copy(area = Position.RIGHT)), position = Position.RIGHT)
        Store.update { three }
        assertTrue(Undo.change(allTo = Position.CENTER) { it.putAllIn(Position.CENTER) })
        assertEquals(Position.CENTER, Store.config.value.oneArea())
        assertEquals("the command says where it put them", Position.CENTER, Undo.allTo(Undo.last.value, Store.config.value))
        // A setting changed since is no other arrangement: it stays, and the areas come back.
        Store.update { it.copy(spacing = 14) }
        assertEquals(Position.CENTER, Undo.allTo(Undo.last.value, Store.config.value))
        assertTrue(Undo.undo())
        assertEquals(three.copy(spacing = 14), Store.config.value)
        assertNull(Undo.allTo(Undo.last.value, Store.config.value))
        // Another change is not Look's, and one arranged otherwise since is offered no more.
        assertTrue(Undo.change { it.hide("a") })
        assertNull(Undo.allTo(Undo.last.value, Store.config.value))
        assertTrue(Undo.change(allTo = Position.LEFT) { it.putAllIn(Position.LEFT) })
        Store.update { it.show("c") }
        assertNull(Undo.allTo(Undo.last.value, Store.config.value))
    }

    /** What Undo holds for a type's kept text: the items the last change can bring back, and none once it can't. */
    @Test fun whatUndoHoldsIsLetGoOnceTheChangeCanNoLongerBeOffered() {
        assertTrue(Undo.change(removed = "b") { it.without("b") })
        assertEquals("the removed item can still come back", setOf("a", "b", "c"), Undo.held("nope"))
        assertEquals(emptySet<String>(), Undo.held("clock"))
        // Arranged otherwise since: Undo offers it no more, and holds nothing for it.
        Store.update { it.hide("a") }
        assertFalse(Undo.offered(Undo.last.value!!, Store.config.value))
        assertEquals(emptySet<String>(), Undo.held("nope"))
    }

    /**
     * Between the layout losing an item and the change being kept, another thread's sweep must still be told the item
     * can come back: asked in the middle of the save, which is after the layout changed and before the change is kept.
     */
    @Test fun aRemovedItemIsHeldFromTheMomentItLeavesTheLayoutNotOnlyOnceTheChangeIsKept() {
        val prefs = MapPrefs()
        Store.open(prefs) { }
        Store.update { BarConfig(items = listOf(a, ItemConfig("f", "flight"), ItemConfig("g", "flight"))) }
        Undo.last.value = null
        var during: Set<String>? = null
        prefs.onPut = { if (Store.config.value.items.none { it.id == "f" }) during = Undo.held("flight") }
        assertTrue(Undo.change(removed = "f") { it.without("f") })
        assertTrue("held while the change is being written: $during", "f" in during.orEmpty())
        assertEquals("and once it is kept", setOf("f", "g"), Undo.held("flight"))
        // What is held is that one change's: a change that changed nothing, and one taken back, leave nothing behind.
        assertFalse(Undo.change { it })
        assertTrue(Undo.undo())
        prefs.onPut = null
        assertEquals(emptySet<String>(), Undo.held("flight"))
        assertNull(Undo.pending)
    }

    @Test fun aChangeThatChangesNothingIsNotKeptAndTheOneBeforeItStaysToBeUndone() {
        assertTrue(Undo.change { it.hide("a") })
        assertFalse(Undo.change { it.hide("a") })
        assertTrue(Undo.undo())
        assertEquals(layout, Store.config.value)
    }

    @Test fun aRemovedItemIsGoneFromTheLayoutAndComesBackWhereItWasAsItWas() {
        // As the + made it, with options of its own, of a type this version doesn't know, waiting on its rule: all the same.
        val plain = Items.of("memory")!!.fresh("m", Position.RIGHT)
        val waits = Items.of("timer")!!.fresh("t", Position.LEFT).copy(section = Section.HIDDEN, whenActive = true, options = mapOf("k" to "v"))
        val five = layout.copy(items = listOf(a, plain, b, waits, c))
        for (id in listOf("m", "b", "t", "c")) {
            Store.update { five }
            assertTrue(Undo.change(removed = id) { it.without(id) })
            assertEquals(five.items.map { it.id } - id, ids())
            assertEquals(id, Undo.last.value?.removed)
            assertTrue(Undo.undo())
            assertEquals(five, Store.config.value)
        }
    }

    @Test fun aRemoveWithNoUndoLeavesNothingToTakeBackOnceTheLayoutIsArrangedOtherwise() {
        // The bar's own menu: the item goes, and the change the window kept is over, since it can be offered no more.
        assertTrue(Undo.change { it.hide("a") })
        Undo.removeForGood("b")
        assertEquals(listOf("a", "c"), ids())
        assertNull(Undo.last.value)
        assertFalse(Undo.undo())
        // An item the layout does not have: nothing happens, and a kept change stays.
        assertTrue(Undo.change { it.show("a") })
        Undo.removeForGood("nope")
        assertTrue(Undo.undo())
    }

    @Test fun anOptionChangedSinceIsKeptByTheUndo() {
        Undo.change { it.hide("a") }
        Store.updateItem("b") { it.copy(options = mapOf("k" to "2")) }
        assertTrue(Undo.undo())
        assertEquals(Section.SHOWN, Store.config.value.items.first { it.id == "a" }.section)
        assertEquals("2", Store.config.value.items.first { it.id == "b" }.options["k"])
    }

    @Test fun aLayoutArrangedOtherwiseSinceIsLeftAloneAndTheChangeIsDropped() {
        Undo.change(removed = "b") { it.without("b") }
        // The bar's own menu, with the settings window open.
        Store.update { it.show("c") }
        val now = Store.config.value
        assertFalse(Undo.offered(Undo.last.value!!, now))
        assertFalse(Undo.undo())
        assertEquals(now, Store.config.value)
        assertNull(Undo.last.value)
    }

    @Test fun anItemTheLibraryAddedIsNamedUntilTheNextChange() {
        val before = Store.config.value
        Store.update { it.copy(items = it.items + ItemConfig("d", "nope")) }
        assertTrue(Undo.note(before, added = "d"))
        assertEquals("d", Undo.last.value?.added)
        assertNull(Undo.last.value?.removed)
        assertEquals("d", Undo.added(Undo.last.value, Store.config.value)?.id)
        // Its options changed since: it is still the added one. Arranged otherwise since, it is not.
        Store.update { c -> c.copy(items = c.items.map { if (it.id == "d") it.with("k", "1") else it }) }
        assertEquals("d", Undo.added(Undo.last.value, Store.config.value)?.id)
        val kept = Undo.last.value
        Store.update { it.hide("a") }
        assertNull(Undo.added(kept, Store.config.value))
        assertTrue(Undo.change { it.show("a") })
        assertNull(Undo.last.value?.added)
        assertNull(Undo.added(Undo.last.value, Store.config.value))
    }

    @Test fun whatTheStoreDidItselfIsNotedAndTakenBack() {
        val before = Store.config.value
        Store.update { it.copy(items = it.items + ItemConfig("d", "nope")) }
        assertTrue(Undo.note(before))
        assertTrue(Undo.undo())
        assertEquals(layout, Store.config.value)
        assertFalse("nothing happened since: nothing is noted", Undo.note(Store.config.value))
    }
}
