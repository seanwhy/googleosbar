package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.SourceScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** An item type's own code runs behind one guard: a type's failure ends at its item, not in the process that hosts the strip. */
class ItemGuardTest {
    private val logged = ArrayList<Pair<String, Throwable?>>()
    private val log: (String, Throwable?) -> Unit = { m, t -> logged += m to t }

    @Test fun aFailureEndsHereAndTheCallerGetsNothing() {
        assertNull(ItemGuard.run(kindOnly = false, what = "onClick of x failed", log = log) { error("wrong") })
        assertEquals("onClick of x failed", logged.single().first)
        assertTrue(logged.single().second is IllegalStateException)
    }

    @Test fun whatWorksIsReturned() {
        assertEquals(7, ItemGuard.run(false, "x", log) { 7 })
        assertTrue(logged.isEmpty())
    }

    /** A method this device's Android doesn't have is no Exception, and it is the likeliest thing to go wrong in a click. */
    @Test fun aMethodThisDeviceLacksIsAFailureToo() {
        assertNull(ItemGuard.run<Unit>(false, "onScroll of x failed", log) { throw NoSuchMethodError("nope") })
        assertEquals(1, logged.size)
    }

    /** A type that goes online or shows the user's own things logs only the kind: an exception's message can quote a reply or a title. */
    @Test fun aDiscreetTypesFailureLogsOnlyItsKind() {
        assertNull(ItemGuard.run<Unit>(kindOnly = true, what = "onClick of x failed", log = log) { throw IllegalStateException("the user's title") })
        val (message, thrown) = logged.single()
        assertEquals("onClick of x failed: IllegalStateException", message)
        assertNull(thrown)
        assertFalse(message, "title" in message)
    }

    /** What the guard cannot save the process from is not its to swallow. */
    @Test(expected = OutOfMemoryError::class) fun outOfMemoryIsNotAnItemsFailure() {
        ItemGuard.run<Unit>(false, "x", log) { throw OutOfMemoryError() }
    }

    @Test fun theStripsClicksScrollsAndSlidesGoThroughTheGuard() {
        val bar = "src/main/java/com/meridian11/books/bentobar/bar"
        for (name in listOf("BarService.kt", "MenuCoordinator.kt")) {
            val code = SourceScan.code(File("$bar/$name"))
            for (call in listOf(".onClick(", ".onScroll(", ".onSlide(", ".menuOpening(", ".settle(")) {
                for (line in code.lines().filter { call in it }) assertTrue("$name calls $call bare: $line", "Ticker.guarded(" in line)
            }
        }
    }

    @Test fun aFailingSamplerDoesNotCostTheTimersTheirTick() {
        val items = SourceScan.code(File("src/main/java/com/meridian11/books/bentobar/items/TickLoop.kt"))
        assertFalse("samplers are guarded against any Throwable, not only Exception", "catch (e: Exception)" in items)
        // The clock checks in a guard of their own, after the samplers'.
        val once = items.substringAfter("private fun runOnce(").substringBefore("val next = HashMap(_states.value)")
        val samplers = once.indexOf("wiring.sampleEnv(")
        val clocks = once.indexOf("wiring.clocks()")
        assertTrue(once.substring(samplers, clocks), samplers in 0 until clocks && "ItemGuard.run(" in once.substring(samplers, clocks))
    }
}
