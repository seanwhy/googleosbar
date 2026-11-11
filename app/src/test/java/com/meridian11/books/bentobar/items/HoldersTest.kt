package com.meridian11.books.bentobar.items

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A shared listener starts with its first user and stops after the last, with room for one to come back. */
class HoldersTest {
    private var starts = 0
    private var stops = 0
    private val scheduled = ArrayDeque<Pair<Long, Runnable>>()
    private fun holders(graceMs: Long) = Holders(graceMs, { ms, r -> scheduled.addLast(ms to r) }, { r -> scheduled.removeAll { it.second === r } }, { starts++ }, { stops++ })

    /** Runs what is still scheduled. */
    private fun runLater() {
        while (scheduled.isNotEmpty()) scheduled.removeFirst().second.run()
    }

    @Test fun itStartsWithTheFirstHolderOnceAndStopsAfterTheLast() {
        val h = holders(graceMs = 0)
        h.hold("a"); h.hold("b"); h.hold("a")
        assertEquals(1, starts)
        assertTrue(h.active)
        h.release("a")
        assertEquals(0, stops)
        assertTrue(h.active)
        h.release("b")
        assertEquals(1, stops)
        assertFalse(h.active)
    }

    @Test fun whatWasNotHeldCannotBeReleased() {
        val h = holders(graceMs = 0)
        h.release("nobody")
        assertEquals(0, stops)
        h.hold("a"); h.release("b")
        assertEquals(0, stops)
        assertTrue(h.active)
    }

    @Test fun aShortAbsenceCostsNothing() {
        // The strip hides for every panel and full-screen flip: the calendar must not be read again for that.
        val h = holders(graceMs = 60_000)
        h.hold("calendar")
        h.release("calendar")
        assertTrue(h.active)
        assertEquals(60_000L, scheduled.single().first)
        h.hold("calendar")
        runLater()
        assertEquals("not started again", 1, starts)
        assertEquals("never stopped", 0, stops)
    }

    @Test fun aLongAbsenceStopsOnceAndThenItCanStartAgain() {
        val h = holders(graceMs = 60_000)
        h.hold("calendar")
        h.release("calendar")
        runLater()
        assertEquals(1, stops)
        assertFalse(h.active)
        h.hold("chip")
        assertEquals(2, starts)
    }

    @Test fun aHolderThatArrivesInTheGraceKeepsItAfterTheFirstOneLeaves() {
        val h = holders(graceMs = 60_000)
        h.hold("event")
        h.release("event")
        h.hold("chip")
        runLater()
        assertTrue(h.active)
        assertEquals(0, stops)
        h.release("chip")
        runLater()
        assertEquals(1, stops)
    }
}
