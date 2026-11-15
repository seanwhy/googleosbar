package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.data.BarConfig
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.data.Section
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The ticker on a test's clock: which types are told what, and what a failing or discreet type costs the others. */
class TickLoopTest {
    private var elapsed = 1_000_000L
    private var wall = 7_000_000L
    private var cfg = BarConfig()
    private val posted = ArrayDeque<Runnable>()
    private val scheduled = ArrayDeque<Pair<Long, Runnable>>()
    private var onMain = true
    private val calls = ArrayList<String>()
    private val samplerRuns = ArrayList<Set<String>>()
    private var clockChecks = 0
    private val types = HashMap<String, ItemType>()
    private val logs = ArrayList<Pair<String, Throwable?>>()

    private inner class FakeType(id: String, private val active: Boolean = false, override val discreet: Boolean = false,
                                 override val holds: Boolean = false, private val fails: Set<String> = emptySet(),
                                 private val wakesTypes: Set<String> = emptySet()) : ItemType(id, 0, "sym", 0) {
        var isActive = active
        override val group = ItemGroup.TOOLS
        override val samples = setOf<String>()
        override fun state(item: ItemConfig): ItemState { calls += "state:$type"; maybeFail("state"); return ItemState(text = type, active = isActive) }
        override fun onLive() { calls += "live:$type"; maybeFail("live") }
        override fun onIdle() { calls += "idle:$type"; maybeFail("idle") }
        override fun sample(now: Long) { calls += "sample:$type"; maybeFail("sample") }
        override fun settle(why: Settle) { calls += "settle:$type:$why"; maybeFail("settle") }
        override fun wakes(item: ItemConfig): Set<String> { maybeFail("wakes"); return wakesTypes }
        private fun maybeFail(what: String) { if (what in fails) error("boom in $what") }
    }

    private fun type(id: String, active: Boolean = false, discreet: Boolean = false, holds: Boolean = false, fails: Set<String> = emptySet(),
                     wakes: Set<String> = emptySet()) =
        FakeType(id, active, discreet, holds, fails, wakes).also { types[id] = it }

    private fun item(id: String, type: String, section: Section = Section.SHOWN) = ItemConfig(id, type, section)
    private fun layout(vararg items: ItemConfig) { cfg = BarConfig(items = items.toList()) }

    private val loop = TickLoop(TickLoop.Wiring(
        post = { posted.addLast(it) }, onMain = { onMain },
        schedule = { ms, r -> scheduled.addLast(ms to r) }, cancel = { scheduled.clear() },
        elapsed = { elapsed }, wall = { wall }, sampleClock = { elapsed }, powerSave = { false },
        config = { cfg }, type = { types[it] },
        sampleEnv = { _, t -> samplerRuns += t.toSet() },
        clocks = { clockChecks++ },
        failedText = { "failed ${it.type}" },
        log = { what, e -> logs += what to e },
        location = { items, sampled -> located += items.map { it.id } to sampled.map { it.id } },
    ))

    private val located = ArrayList<Pair<List<String>, List<String>>>()

    /** Moves time on and runs the tick that was scheduled. */
    private fun tick(afterMs: Long = 1000) {
        elapsed += afterMs; wall += afterMs
        val (_, r) = scheduled.removeFirst()
        r.run()
    }

    @Test fun aTickIsScheduledOnTheSecondAndNothingAfterTheLastUserLeaves() {
        layout(item("a", type("one").type))
        loop.start("bar")
        assertTrue(loop.running)
        assertEquals(1, scheduled.size)
        assertEquals(wall.let { 1000 - it % 1000 + 5 }, scheduled.first().first)
        loop.stop("bar")
        assertFalse(loop.running)
        assertEquals(0, scheduled.size)
    }

    @Test fun whereTheDeviceIsIsFollowedOncePerTickWithWhatIsSampledAndLetGoWhenNothingShows() {
        type("weather"); type("air")
        layout(item("w", "weather"), item("a", "air"))
        loop.start("bar")
        tick()
        assertEquals("one call for both types' items", 2, located.size)
        assertEquals(listOf("w", "a") to listOf("w", "a"), located.last())
        // The last Weather item stops being sampled: what the Air item still wants goes on.
        layout(item("a", "air"))
        tick()
        assertEquals(listOf("a") to listOf("a"), located.last())
        loop.stop("bar")
        assertEquals(listOf("a") to emptyList<String>(), located.last())
    }

    @Test fun eachTypeIsToldWhenItStartsAndStopsBeingSampledOncePerChange() {
        type("one"); type("two")
        layout(item("a", "one"), item("b", "two"))
        loop.start("bar")
        assertEquals(1, calls.count { it == "live:one" })
        assertEquals(1, calls.count { it == "live:two" })
        tick(); tick()
        assertEquals("told once, however many ticks", 1, calls.count { it == "live:one" })
        // Only the second type's item is removed: only it is told to let go.
        cfg = cfg.copy(items = listOf(item("a", "one")))
        tick()
        assertEquals(1, calls.count { it == "idle:two" })
        assertEquals(0, calls.count { it == "idle:one" })
        // The bar hides: the rest let go too, and come back when it returns.
        loop.stop("bar")
        assertEquals(1, calls.count { it == "idle:one" })
        loop.start("bar")
        assertEquals(2, calls.count { it == "live:one" })
    }

    @Test fun aRefreshWithNothingOnScreenWakesNoType() {
        type("one"); layout(item("a", "one"))
        loop.refresh()
        assertEquals(0, calls.count { it.startsWith("live") })
        assertEquals("nothing sampled either", 0, calls.count { it.startsWith("sample") })
    }

    @Test fun aRefreshOfOneItemDoesNothingWhileNothingIsSampled() {
        // F82: asked with nothing on screen it ran state() for a type nobody had told to go live.
        type("one"); layout(item("a", "one"))
        loop.refresh(item("a", "one"))
        assertEquals(0, calls.size)
        assertNull(loop.states.value["a"])
        loop.start("bar")
        calls.clear(); loop.refresh(item("a", "one"))
        assertEquals(listOf("state:one"), calls)
    }

    @Test fun aRefreshOfOneItemRunsNoSampler() {
        type("one"); layout(item("a", "one"))
        loop.start("bar")
        val runsBefore = samplerRuns.size
        loop.refresh(item("a", "one"))
        assertEquals(runsBefore, samplerRuns.size)
    }

    @Test fun aRefreshByHandFeedsNoSamplerButStillLooksAtTheClocks() {
        // F19: a click or a wheel notch put a reading milliseconds after the last one into the network sampler: a false spike.
        // The clocks must still run: a click relies on refresh() to show a timer or keep awake it has just changed.
        type("one"); layout(item("a", "one"))
        loop.start("bar")
        val runs = samplerRuns.size; val checks = clockChecks
        loop.refresh()
        assertEquals(runs, samplerRuns.size)
        assertEquals(checks + 1, clockChecks)
        tick()
        assertEquals("the next scheduled tick samples as usual", runs + 1, samplerRuns.size)
    }

    @Test fun aSamplerThatHasNoReadingYetTakesItsFirstOneOnARefreshAndOnlyThat() {
        // A hidden battery item, revealed by a click, would otherwise read 0% until the next tick.
        type("one"); type("two")
        layout(item("a", "one"))
        loop.start("bar")
        samplerRuns.clear()
        layout(item("a", "one"), item("b", "two"))
        loop.refresh()
        assertEquals(listOf(setOf("two")), samplerRuns)
        loop.refresh()
        assertEquals("the first one only", 1, samplerRuns.size)
    }

    @Test fun aSamplerThatWasDroppedTakesAFreshReadingWhenItIsNeededAgain() {
        // Hours behind ‹, then revealed: what it last read is old by then.
        type("one"); type("two")
        layout(item("a", "one"), item("b", "two"))
        loop.start("bar")
        layout(item("a", "one"))
        tick()
        samplerRuns.clear()
        layout(item("a", "one"), item("b", "two"))
        loop.refresh()
        assertEquals(listOf(setOf("two")), samplerRuns)
    }

    @Test fun aTypeThatFailsCostsTheOthersNothing() {
        type("bad", fails = setOf("state", "sample", "live", "idle")); type("good")
        layout(item("a", "bad"), item("b", "good"))
        loop.start("bar") // onLive of bad throws; good still goes live and is computed
        tick()
        assertEquals("good", loop.states.value["b"]?.text)
        assertEquals("!", loop.states.value["a"]?.text) // the item says it failed
        assertEquals("failed bad", loop.states.value["a"]?.desc)
        assertTrue(calls.contains("sample:good"))
        assertTrue(logs.any { it.first.contains("onLive of bad failed") })
        assertEquals(2, clockChecks)
        loop.stop("bar") // onIdle of bad throws: the rest still let go
        assertEquals(1, calls.count { it == "idle:good" })
    }

    @Test fun whatFailedInATypeThatGoesOnlineIsLoggedOnlyByKind() {
        val quiet = object : ItemType("q", 0, "sym", 0) {
            override val group = ItemGroup.ONLINE
            override val online = com.meridian11.books.bentobar.data.Online.Service.OPEN_METEO
            override fun state(item: ItemConfig): ItemState = error("a reply with a key in it")
        }
        types["q"] = quiet
        layout(item("a", "q"))
        loop.start("bar")
        val (what, error) = logs.single { it.first.contains("failed") }
        assertTrue(what, what.contains("IllegalStateException"))
        assertFalse(what, what.contains("key"))
        assertNull(error)
    }

    @Test fun whatADiscreetTypeLastShowedIsForgottenWhenItGoesIdle() {
        type("private", discreet = true); type("plain")
        layout(item("p", "private"), item("n", "plain"))
        loop.start("bar")
        assertEquals("private", loop.states.value["p"]?.text)
        cfg = cfg.copy(items = listOf(item("n", "plain")))
        tick()
        assertNull(loop.states.value["p"])
        assertEquals("plain", loop.states.value["n"]?.text)
    }

    @Test fun aPlainTypeKeepsWhatItLastShowedAcrossAnIdle() {
        type("plain"); layout(item("n", "plain"))
        loop.start("bar")
        loop.stop("bar")
        assertEquals("plain", loop.states.value["n"]?.text)
    }

    @Test fun anOnlySampledTypeHoldsItsActiveStateForAWhileThenLetsGo() {
        val busy = type("busy", active = true, holds = true); val quick = type("quick", active = true)
        layout(item("h", "busy"), item("q", "quick"))
        loop.start("bar")
        busy.isActive = false; quick.isActive = false
        tick()
        assertTrue("held", loop.states.value["h"]!!.active)
        assertFalse("not held", loop.states.value["q"]!!.active)
        tick(TickRules.HOLD_MS)
        assertFalse("let go", loop.states.value["h"]!!.active)
    }

    @Test fun aHiddenItemIsNotSampledUntilItIsRevealedOrItsMenuOpens() {
        type("one"); layout(item("a", "one", Section.HIDDEN).copy(whenActive = false))
        cfg = cfg.copy(hiddenMode = com.meridian11.books.bentobar.data.HiddenMode.CLICK)
        loop.start("bar")
        assertEquals(emptyList<ItemConfig>(), loop.sampled)
        loop.revealHidden = true
        tick()
        assertEquals(listOf("a"), loop.sampled.map { it.id })
        loop.revealHidden = false; loop.focusItem = "a"
        tick()
        assertEquals(listOf("a"), loop.sampled.map { it.id })
    }

    @Test fun whatAnotherThreadAsksForIsPostedToTheMainThreadInOrder() {
        type("one"); layout(item("a", "one"))
        onMain = false
        loop.start("bar")
        assertTrue(posted.isNotEmpty()); assertFalse(loop.running)
        onMain = true; posted.removeFirst().run()
        assertTrue(loop.running)
    }

    @Test fun aFailingSamplerDoesNotCostTheClockCheck() {
        type("one"); layout(item("a", "one"))
        val loud = TickLoop(TickLoop.Wiring(post = { it.run() }, onMain = { true }, schedule = { _, _ -> }, cancel = {}, elapsed = { elapsed }, wall = { wall },
            sampleClock = { elapsed }, powerSave = { false }, config = { cfg }, type = { types[it] },
            sampleEnv = { _, _ -> error("sampler") }, clocks = { clockChecks++ }, failedText = { "" }, log = { w, e -> logs += w to e }))
        loud.start("bar")
        assertEquals(1, clockChecks)
        assertEquals("one", loud.states.value["a"]?.text)
    }

    // ---- settle(LEFT_LAYOUT): what outlives the bar (a mute, a Do Not Disturb rule) ends with the type's last item, not with the bar ----

    private fun settled(type: String) = calls.count { it == "settle:$type:${Settle.LEFT_LAYOUT}" }

    @Test fun aTypeWhoseLastItemLeavesTheLayoutIsSettledOnceAndNotWhenTheBarHides() {
        type("mic"); type("plain")
        layout(item("m1", "mic"), item("m2", "mic"), item("p", "plain"))
        loop.start("bar")
        // The bar hides and comes back with the items still placed: nothing is settled.
        loop.stop("bar")
        loop.start("bar")
        tick()
        assertEquals(0, calls.count { it.startsWith("settle") })
        // One of two goes: the type still has an item in the layout.
        layout(item("m2", "mic"), item("p", "plain"))
        tick()
        assertEquals(0, settled("mic"))
        // The last one is removed: settled once, and not again on the ticks that follow.
        layout(item("p", "plain"))
        tick(); tick()
        assertEquals(1, settled("mic"))
        assertEquals("only the type that left", 0, settled("plain"))
        // Back (an Undo), and removed outright: settled again, once.
        layout(item("m2", "mic"), item("p", "plain"))
        tick()
        layout(item("p", "plain"))
        tick(); tick()
        assertEquals(2, settled("mic"))
        // Behind ‹ an item is still placed, though nothing samples it: its type is not settled.
        layout(item("m3", "mic"), item("p", "plain"))
        tick()
        cfg = BarConfig(items = listOf(item("m3", "mic", Section.HIDDEN).copy(whenActive = false), item("p", "plain")),
            hiddenMode = com.meridian11.books.bentobar.data.HiddenMode.CLICK)
        tick()
        assertTrue("not sampled", "m3" !in loop.sampled.map { it.id })
        assertEquals(2, settled("mic"))
    }

    @Test fun aRemovalWhileNothingWasOnScreenIsSeenAtTheNextRun() {
        type("mic")
        layout(item("m", "mic"))
        loop.start("bar")
        loop.stop("bar")
        // Removed while the bar was hidden: nothing runs then, and nothing is settled.
        layout()
        assertEquals(0, settled("mic"))
        loop.start("bar")
        assertEquals(1, settled("mic"))
        tick()
        assertEquals(1, settled("mic"))
    }

    @Test fun aTypeThatFailsToSettleCostsTheOthersNothing() {
        type("bad", fails = setOf("settle")); type("good")
        layout(item("a", "bad"), item("b", "good"))
        loop.start("bar")
        layout()
        tick()
        assertEquals(1, settled("good"))
        assertTrue(logs.any { it.first.contains("settle of bad failed") })
    }

    // ---- wakes: a type kept live for another's item (Live activity's sources), with no item of its own ----

    @Test fun aWokenTypeIsLiveAndSampledWhileTheItemThatWakesItIsAndIdleWhenItGoes() {
        type("live", wakes = setOf("timer")); type("timer")
        layout(item("l", "live"))
        loop.start("bar")
        assertEquals(1, calls.count { it == "live:timer" })
        tick()
        assertTrue("sampled on the tick", calls.contains("sample:timer"))
        layout()
        tick()
        assertEquals(1, calls.count { it == "idle:timer" })
        calls.clear(); tick()
        assertFalse("nothing of it runs any more", calls.any { it.endsWith(":timer") })
    }

    @Test fun aTypeWokenAndLiveItselfIsStartedOnce() {
        type("live", wakes = setOf("timer")); type("timer")
        layout(item("l", "live"), item("t", "timer"))
        loop.start("bar")
        tick()
        assertEquals(1, calls.count { it == "live:timer" })
        // Its own item goes: still woken, so still live.
        layout(item("l", "live"))
        tick()
        assertEquals(0, calls.count { it == "idle:timer" })
        assertEquals(1, calls.count { it == "live:timer" })
    }

    @Test fun aWokenTypesStateIsNotRun() {
        type("live", wakes = setOf("timer")); type("timer")
        layout(item("l", "live"))
        loop.start("bar")
        tick()
        assertFalse(calls.contains("state:timer"))
        assertEquals(setOf("l"), loop.states.value.keys)
    }

    @Test fun whatAWokenTypeNamesThatIsNotRegisteredOrFailsCostsNothing() {
        type("live", wakes = setOf("gone")); type("bad", fails = setOf("wakes")); type("good")
        layout(item("l", "live"), item("b", "bad"), item("g", "good"))
        loop.start("bar")
        tick()
        assertEquals("good", loop.states.value["g"]?.text)
        assertTrue(logs.any { it.first.contains("wakes of bad failed") })
    }

    @Test fun aTypeNoLongerInTheRegistryIsSkippedAndStaysInTheLayout() {
        layout(item("a", "gone"), item("b", type("here").type))
        loop.start("bar")
        assertEquals(setOf("b"), loop.states.value.keys)
    }
}
