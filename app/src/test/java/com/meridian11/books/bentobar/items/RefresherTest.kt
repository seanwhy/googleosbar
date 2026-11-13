package com.meridian11.books.bentobar.items

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

/** The loader behind everything that is loaded: when it asks, when it doesn't, and what it drops. */
class RefresherTest {
    private val min = 60_000L
    private var clock = 1_000_000L
    private var changes = 0
    private val loads = ArrayList<String>()
    /** Background work waits here until [run] when [held]; otherwise it runs at once, on the calling thread. */
    private val waiting = ArrayDeque<Runnable>()
    private var held = false
    private val background = Executor { r -> if (held) waiting.addLast(r) else r.run() }
    private fun run() { while (waiting.isNotEmpty()) waiting.removeFirst().run() }

    private var may = true
    private fun wiring(minGapMs: Long = 0) = Refresher.Wiring(background, { it.run() }, { clock }, { changes++ }, minGapMs = minGapMs, afterThrowMs = min,
        mayLoad = { may })

    /** Loads "key#n", counting per test; fresh for half an hour, a value starting with "failed" for 15 minutes, "once" forever. */
    private fun refresher(minGapMs: Long = 0, restore: ((String) -> Refresher.Restored<String>?)? = null, load: ((String, String?) -> String)? = null) =
        Refresher<String, String>(wiring(minGapMs),
            every = { _, v -> when { v.startsWith("failed") -> 15 * min; v.startsWith("once") -> null; else -> 30 * min } },
            restore = restore,
            load = load ?: { key, _ -> loads += key; "$key#${loads.size}" })

    @Test fun aLoadThatThrowsIsReportedByItsKindAndNeverItsMessage() {
        val reported = ArrayList<String>()
        val r = Refresher<String, String>(Refresher.Wiring(background, { it.run() }, { clock }, report = { reported += it }), every = { _, _ -> null }) { _, _ ->
            throw IllegalStateException("https://airlabs.co/x?api_key=a-key-nobody-may-see")
        }
        r.want("x")
        assertEquals(listOf("IllegalStateException"), reported)
        assertNull(r.peek("x"))
    }

    @Test fun nothingOfAForgottenKeyRemains() {
        val r = refresher()
        r.want("zurich"); r.want("oslo")
        r.forget("zurich")
        assertEquals(setOf("oslo"), r.heldKeys())
        r.forget()
        assertEquals(emptySet<String>(), r.heldKeys())
    }

    @Test fun nothingOfAKeyForgottenWhileItLoadsRemainsOnceTheLoadIsBack() {
        held = true
        val r = refresher()
        r.want("zurich")
        r.forget("zurich")
        assertEquals("held only until the load comes back", setOf("zurich"), r.heldKeys())
        held = false
        run()
        assertNull(r.peek("zurich"))
        assertEquals(emptySet<String>(), r.heldKeys())
    }

    @Test fun aKeyForgottenTwiceWhileItLoadsAndThenWantedAgainStillLoads() {
        held = true
        val r = refresher()
        r.want("zurich")
        r.forget("zurich"); r.forget("zurich")
        run()
        held = false
        r.want("zurich")
        assertEquals("zurich#2", r.peek("zurich"))
    }

    @Test fun theFirstWantLoads() {
        val r = refresher()
        assertNull(r.peek("zurich")); assertNull(r.age("zurich"))
        r.want("zurich")
        assertEquals("zurich#1", r.peek("zurich"))
        assertEquals(0L, r.age("zurich"))
        assertEquals(1, changes)
    }

    @Test fun aFreshValueIsLeftAloneAndAnOldOneIsLoadedAgain() {
        val r = refresher()
        r.want("zurich")
        clock += 29 * min; r.want("zurich"); r.want("zurich")
        assertEquals(listOf("zurich"), loads)
        assertEquals(29 * min, r.age("zurich"))
        clock += 1 * min; r.want("zurich")
        assertEquals("zurich#2", r.peek("zurich"))
        assertEquals(0L, r.age("zurich"))
    }

    @Test fun howLongAValueStaysFreshDependsOnTheValue() {
        var answer = "failed: no answer"
        val r = refresher(load = { key, _ -> loads += key; answer })
        r.want("zurich")
        clock += 14 * min; r.want("zurich")
        assertEquals(1, loads.size)
        answer = "15.7"
        clock += 1 * min; r.want("zurich") // a failure is asked again after 15 minutes
        assertEquals(2, loads.size)
        clock += 20 * min; r.want("zurich") // a reading only after 30
        assertEquals(2, loads.size)
    }

    @Test fun aValueThatNeverGrowsOldIsLoadedOnce() {
        val r = refresher(load = { key, _ -> loads += key; "once and for all" })
        r.want("x"); clock += 10_000 * min; r.want("x")
        assertEquals(1, loads.size)
    }

    @Test fun theLoadGetsTheLastValue() {
        val seen = ArrayList<String?>()
        val r = refresher(load = { _, last -> seen += last; "v${seen.size}" })
        r.want("x"); clock += 31 * min; r.want("x")
        assertEquals(listOf(null, "v1"), seen)
    }

    @Test fun neverAgainWithinTheGapWhateverTheRuleSays() {
        // A rule gone wrong (a value that is never fresh) must not turn into a request every second.
        val r = Refresher<String, String>(wiring(minGapMs = 10_000), every = { _, _ -> 0L }, load = { key, _ -> loads += key; "v" })
        r.want("x")
        repeat(9) { clock += 1_000; r.want("x") }
        assertEquals(1, loads.size)
        clock += 1_000; r.want("x")
        assertEquals(2, loads.size)
    }

    @Test fun refreshLoadsNowButNotUnderItsFloor() {
        val r = refresher()
        r.want("zurich")
        clock += 30_000
        assertFalse(r.refresh("zurich", floorMs = min)) // "once a minute at most"
        assertEquals(1, loads.size)
        clock += 30_000
        assertTrue(r.refresh("zurich", floorMs = min))
        assertEquals(2, loads.size)
        assertTrue(r.refresh("zurich")) // no floor: whenever asked
        assertEquals(3, loads.size)
    }

    @Test fun oneLoadPerKeyAtATime() {
        held = true
        val r = refresher()
        r.want("zurich"); r.want("zurich"); r.refresh("zurich")
        assertTrue(r.loading("zurich"))
        assertEquals(1, waiting.size)
        r.want("oslo") // another key doesn't wait for it to be asked
        assertEquals(2, waiting.size)
        run()
        assertFalse(r.loading("zurich"))
        assertEquals(listOf("zurich", "oslo"), loads)
    }

    @Test fun somethingChangedWhileItLoadedSoOneMoreFollows() {
        held = true
        val r = refresher()
        r.want("devices")
        r.refresh("devices", afterRunning = true); r.refresh("devices", afterRunning = true)
        run()
        assertEquals(2, loads.size) // exactly one more, however often it was asked
        assertEquals("devices#2", r.peek("devices"))
    }

    @Test fun anAnswerThatArrivesAfterForgetIsDropped() {
        held = true
        val r = refresher()
        r.want("zurich")
        r.forget()
        run()
        assertNull(r.peek("zurich"))
        assertEquals(0, changes)
        held = false
        r.want("zurich") // and it can be loaded again afterwards
        assertEquals("zurich#2", r.peek("zurich"))
    }

    @Test fun aKeyForgottenWhileItsFirstLoadRunsLooksAtWhatIsKeptAgainNextTime() {
        val asked = ArrayList<String>()
        held = true
        val r = refresher(restore = { key -> asked += key; null })
        r.want("zurich")
        r.forget("zurich")
        run()
        held = false
        r.want("zurich")
        // The answer that was dropped must not count as "what is kept was looked at".
        assertEquals(listOf("zurich", "zurich"), asked)
    }

    @Test fun forgettingOneKeyLeavesTheOthers() {
        val r = refresher()
        r.want("zurich"); r.want("oslo")
        held = true
        clock += 31 * min
        r.want("zurich"); r.want("oslo")
        r.forget("zurich")
        run()
        assertNull(r.peek("zurich"))
        assertEquals("oslo#4", r.peek("oslo"))
    }

    @Test fun keepOnlyDropsWhatTheLayoutNoLongerHas() {
        val r = refresher()
        r.want("zurich"); r.want("oslo"); r.want("lima")
        r.keepOnly(setOf("oslo"))
        assertNull(r.peek("zurich")); assertNull(r.peek("lima"))
        assertEquals("oslo#2", r.peek("oslo"))
    }

    @Test fun keepOnlyAlsoDropsAFirstLoadOnItsWay() {
        val r = refresher()
        held = true
        r.want("lima")
        r.keepOnly(setOf("oslo"))
        run()
        // Its key went while it ran, before it had a snapshot: what it brings counts for nothing.
        assertNull(r.peek("lima"))
        assertEquals(0, changes)
    }

    @Test fun aLoadThatThrowsIsLeftAloneForAWhile() {
        var broken = true
        val r = refresher(load = { key, _ -> loads += key; if (broken) error("a bug") else "fine" })
        r.want("x")
        assertNull(r.peek("x"))
        repeat(30) { clock += 1_000; r.want("x") } // not once a second
        assertEquals(1, loads.size)
        clock += 31_000; broken = false; r.want("x")
        assertEquals("fine", r.peek("x"))
        // A press of Refresh doesn't wait for that.
        broken = true; clock += 31 * min; r.want("x"); assertEquals(3, loads.size)
        broken = false; assertTrue(r.refresh("x")); assertEquals(4, loads.size)
    }

    @Test fun whatWasKeptShowsFirstAndIsAsOldAsItWasWhenSaved() {
        val asked = ArrayList<String>()
        val r = refresher(restore = { key -> asked += key; if (key == "zurich") Refresher.Restored("kept reading", 25 * min) else null })
        r.want("zurich")
        assertEquals("kept reading", r.peek("zurich")) // a restart shows the last reading without asking the service
        assertEquals(emptyList<String>(), loads)
        assertEquals(25 * min, r.age("zurich"))
        clock += 4 * min; r.want("zurich")
        assertEquals(emptyList<String>(), loads)
        clock += 1 * min; r.want("zurich") // now it is half an hour old
        assertEquals("zurich#1", r.peek("zurich"))
        // Nothing was kept for this one: it is loaded at once, and nobody looks for a kept one twice.
        r.want("oslo")
        assertEquals("oslo#2", r.peek("oslo"))
        clock += 31 * min; r.want("oslo")
        assertEquals(listOf("zurich", "oslo"), asked)
    }

    @Test fun nothingLoadsWhileItMayNot() {
        // Nothing shows items, or the service is switched off: asking for a state then loads nothing.
        val r = refresher()
        may = false
        r.want("zurich")
        assertFalse(r.refresh("zurich"))
        assertEquals(emptyList<String>(), loads)
        assertNull(r.peek("zurich"))
        may = true
        r.want("zurich")
        assertEquals("zurich#1", r.peek("zurich"))
        // What there is stays while it may not, however old it grows; then it is loaded once.
        may = false
        clock += 5 * 60 * min; r.want("zurich")
        assertEquals("zurich#1", r.peek("zurich"))
        may = true
        r.want("zurich")
        assertEquals("zurich#2", r.peek("zurich"))
    }

    @Test fun aLoadWithNothingToSayLeavesWhatThereIs() {
        // The request was refused under the load (the switch went off, the bar hid): that is no answer and no failure.
        var refused = false
        val r = Refresher<String, String>(wiring(minGapMs = 10_000), every = { _, _ -> 30 * min },
            load = { key, _ -> loads += key; if (refused) null else "$key#${loads.size}" })
        r.want("zurich")
        refused = true
        clock += 31 * min; r.want("zurich")
        assertEquals(2, loads.size)
        assertEquals("zurich#1", r.peek("zurich"))
        assertEquals(31 * min, r.age("zurich"))
        assertEquals(1, changes)
        // It is asked again, but not every second.
        repeat(9) { clock += 1_000; r.want("zurich") }
        assertEquals(2, loads.size)
        refused = false
        clock += 1_000; r.want("zurich")
        assertEquals("zurich#3", r.peek("zurich"))
        // With nothing there yet it is the same: no snapshot, and another try after the gap.
        refused = true
        r.want("oslo")
        assertNull(r.peek("oslo"))
        clock += 5_000; r.want("oslo")
        assertEquals(4, loads.size)
        refused = false
        clock += 5_000; r.want("oslo")
        assertEquals("oslo#5", r.peek("oslo"))
    }

    @Test fun everyNewSnapshotIsToldOnce() {
        val r = refresher()
        r.want("a"); r.want("a"); r.want("b")
        assertEquals(2, changes)
    }
}
