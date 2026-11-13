package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.SourceScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

/** Calendar's loading is [Refresher]'s on its own numbers: a change reloads it at once, and otherwise it is good for a while; one more after a burst of changes. */
class CalendarLoaderTest {
    private var clock = 1_000_000L
    private var loads = 0
    private var may = true
    private val waiting = ArrayDeque<Runnable>()
    private var changes = 0
    private val background = Executor { waiting.addLast(it) }
    private fun run() { while (waiting.isNotEmpty()) waiting.removeFirst().run() }

    private val loader = calendarLoader(Refresher.Wiring(background, { it.run() }, { clock }, changed = { changes++ }, minGapMs = 0, mayLoad = { may })) {
        loads++
        listOf(Calendar.Event(loads.toLong(), "e", 0, 1, false, 0, "", true, false, null))
    }

    @Test fun itIsReadAgainOnlyAfterItsFreshnessHasRunOut() {
        loader.want(Unit); run()
        assertEquals(1, loads)
        clock += Calendar.RELOAD_MS - 1_000; loader.want(Unit); run()
        assertEquals(1, loads)
        clock += 1_000; loader.want(Unit); run()
        assertEquals(2, loads)
        assertEquals(2, changes)
    }

    @Test fun aProviderChangeReloadsItAtOnceWhateverItsFreshness() {
        loader.want(Unit); run()
        assertEquals(true, loader.refresh(Unit, afterRunning = true))
        run()
        assertEquals(2, loads)
    }

    @Test fun nobodyUsingItMeansNothingLoadsAndItsReadingIsForgotten() {
        // F28: a provider change or an item's state with no calendar item and the chip off reloaded 41 days.
        loader.want(Unit); run()
        may = false
        loader.forget()
        loader.want(Unit); assertFalse(loader.refresh(Unit, afterRunning = true)); run()
        assertEquals(1, loads)
        assertEquals(null, loader.peek(Unit))
    }

    @Test fun onlyAFirstLoadMeansNothingIsKnownYet() {
        assertFalse(loader.loadingFirst())
        loader.want(Unit)
        assertTrue("the first load is on its way", loader.loadingFirst())
        run()
        assertFalse("something has been read", loader.loadingFirst())
        assertTrue(loader.refresh(Unit, afterRunning = true))
        assertFalse("a reload still has the last reading", loader.loadingFirst())
        run()
    }

    @Test fun aBurstOfProviderChangesQueuesOneMoreLoadNotHundreds() {
        loader.want(Unit) // the first load is running
        repeat(300) { loader.refresh(Unit, afterRunning = true) }
        run()
        assertEquals("the running load and one more", 2, loads)
    }

    @Test fun switchingTheCalendarOffDropsWhatARunningLoadBrings() {
        loader.want(Unit)
        loader.forget()
        run()
        assertEquals(null, loader.peek(Unit))
        assertEquals(0, changes)
    }

    @Test fun whileItMayNotLoadNothingHappens() {
        may = false
        loader.want(Unit); assertFalse(loader.refresh(Unit, afterRunning = true)); run()
        assertEquals(0, loads)
    }

    @Test fun theCalendarKeepsLoadingWhileTheServiceIsOffForTheChip() {
        // Background's wiring only loads while Ticker.running; the Live Update chip needs meetings with the bar gone.
        val code = SourceScan.code(SourceScan.sources.single { it.name == "Calendar.kt" })
        assertFalse(code, code.contains("Ticker"))
        assertTrue(code.contains("Refresher.Wiring("))
        assertFalse(code, code.contains("Background.wiring"))
    }
}
