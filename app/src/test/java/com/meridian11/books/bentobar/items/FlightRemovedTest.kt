package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.data.BarConfig
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.data.Kept
import com.meridian11.books.bentobar.data.MapPrefs
import com.meridian11.books.bentobar.data.Online
import com.meridian11.books.bentobar.data.Position
import com.meridian11.books.bentobar.data.Store
import com.meridian11.books.bentobar.data.fresh
import com.meridian11.books.bentobar.data.hide
import com.meridian11.books.bentobar.data.show
import com.meridian11.books.bentobar.data.without
import com.meridian11.books.bentobar.data.withoutArea
import com.meridian11.books.bentobar.items.FlightRules.Tracked
import com.meridian11.books.bentobar.ui.Undo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The flight a Flight item follows is kept outside the layout, under the item's id. Remove deletes the item; its flight
 * stays for as long as Undo can bring the item back, and goes when it no longer can.
 */
class FlightRemovedTest {
    private val dir = File(System.getProperty("java.io.tmpdir"), "bentobar-flight-kept-${System.nanoTime()}")
    private val clock = ItemConfig("c", "clock")
    private val flight = FlightItem.fresh("f", Position.RIGHT)
    private fun at(id: String) = Store.config.value.items.single { it.id == id }
    private fun ids() = Store.config.value.items.map { it.id }
    private fun notes() = Kept.own("flight").names()
    private fun follow(id: String) = assertTrue(FlightLoad.take(id, Tracked("LH455", "2026-10-02"), 1_000))

    /** What the Flight item does on its own thread has been done. */
    private fun settled() {
        val done = CountDownLatch(1)
        Background.executor(FlightItem.type).execute { done.countDown() }
        assertTrue(done.await(5, TimeUnit.SECONDS))
    }

    @Before fun fresh() {
        Online.init(dir)
        Store.open(MapPrefs()) { }
        Store.update { BarConfig(items = listOf(clock, flight, flight.copy(id = "g"))) }
        Undo.last.value = null
    }

    @After fun gone() {
        Undo.last.value = null
        settled()
        dir.deleteRecursively()
        Online.init(File(dir, "empty"))
    }

    @Test fun aRemovedItemsFlightStaysWhileUndoCanBringItBackAndGoesWhenItNoLongerCan() {
        follow("f")
        follow("g")
        // The settings' Remove: the item leaves the layout, as one step of Undo.
        assertTrue(Undo.change(removed = "f") { it.without("f") })
        assertEquals(listOf("c", "g"), ids())
        // Another Flight item is read, which lets go of what belongs to no item: the removed one's flight is not that yet.
        FlightLoad.kept("g", 2_000, FlightItem.ids())
        settled()
        assertEquals(setOf("f", "g"), notes())
        assertTrue(Undo.undo())
        settled()
        assertEquals("back as it was", flight, at("f"))
        assertNotNull("with its flight", Kept.own("flight").read("f"))
        // Removed again: still there. Then something else changes: no Undo brings it back, and its flight goes.
        assertTrue(Undo.change(removed = "f") { it.without("f") })
        settled()
        assertEquals(setOf("f", "g"), notes())
        assertTrue(Undo.change { it.hide("c") })
        settled()
        assertEquals(setOf("g"), notes())
        // The last Flight item goes the same way, and nothing of a flight stays.
        assertTrue(Undo.change(removed = "g") { it.without("g") })
        assertTrue(Undo.change { it.show("c") })
        settled()
        assertEquals(emptySet<String>(), notes())
        assertEquals(listOf("c"), ids())
    }

    /** Closing the settings window ends Undo's reach as the next change does: the flight goes then. */
    @Test fun aRemovedItemsFlightGoesWhenTheSettingsWindowIsClosed() {
        follow("f")
        follow("g")
        assertTrue(Undo.change(removed = "f") { it.without("f") })
        settled()
        assertEquals(setOf("f", "g"), notes())
        Undo.end()
        settled()
        assertEquals("the other item's flight stays", setOf("g"), notes())
        assertEquals(false, Undo.undo())
        assertEquals(listOf("c", "g"), ids())
    }

    /** Remove an area takes every item of it in one step: each one's flight stays while that step can be undone, and not longer. */
    @Test fun theFlightsOfARemovedAreasItemsStayWhileUndoCanBringThemBackAndGoWhenItNoLongerCan() {
        Store.update { c -> c.copy(items = c.items.map { if (it.id == "f") it.copy(area = Position.LEFT) else it } + flight.copy(id = "h", area = Position.LEFT)) }
        val before = Store.config.value
        for (id in listOf("f", "g", "h")) follow(id)
        assertTrue(Undo.change(area = Position.LEFT) { it.withoutArea(Position.LEFT) })
        assertEquals(listOf("c", "g"), ids())
        // Another Flight item is read, which lets go of what belongs to no item: the removed ones' flights are not that yet.
        FlightLoad.kept("g", 2_000, FlightItem.ids())
        settled()
        assertEquals(setOf("f", "g", "h"), notes())
        assertTrue(Undo.undo())
        settled()
        assertEquals("every item back where it was", before, Store.config.value)
        assertEquals(setOf("f", "g", "h"), notes())
        // Removed again: still there. Then something else changes: no Undo brings them back, and both flights go.
        assertTrue(Undo.change(area = Position.LEFT) { it.withoutArea(Position.LEFT) })
        settled()
        assertEquals(setOf("f", "g", "h"), notes())
        assertTrue(Undo.change { it.hide("c") })
        settled()
        assertEquals(setOf("g"), notes())
    }

    @Test fun aRemovalThatWasUndoneTakesNothingWithItWhateverChangesAfter() {
        follow("f")
        assertTrue(Undo.change(removed = "f") { it.without("f") })
        assertTrue(Undo.undo())
        assertTrue(Undo.change { it.hide("c") })
        assertTrue(Undo.change { it.show("c") })
        settled()
        assertEquals(setOf("f"), notes())
    }

    @Test fun theBarsOwnMenuHasNoUndoSoItsRemoveTakesTheFlightAtOnce() {
        follow("f")
        follow("g")
        Undo.removeForGood("f")
        settled()
        assertEquals(listOf("c", "g"), ids())
        assertEquals(setOf("g"), notes())
    }

    @Test fun aRemovalTheBarsOwnMenuPutOutOfUndosReachIsLetGoWithIt() {
        follow("f")
        follow("g")
        assertTrue(Undo.change(removed = "f") { it.without("f") })
        // The bar's menu, with the settings window open: the layout is arranged otherwise, and Undo offers "f" no more.
        Undo.removeForGood("g")
        settled()
        assertEquals(null, Undo.last.value)
        assertEquals(emptySet<String>(), notes())
    }

    @Test fun anItemThatUndoTakesOutAgainTakesTheFlightItFollowedSinceWithIt() {
        assertTrue(Undo.change(added = "h") { c -> c.copy(items = c.items + flight.copy(id = "h")) })
        follow("h")
        follow("g")
        assertTrue(Undo.undo())
        settled()
        assertEquals(listOf("c", "f", "g"), ids())
        assertEquals(setOf("g"), notes())
    }
}
