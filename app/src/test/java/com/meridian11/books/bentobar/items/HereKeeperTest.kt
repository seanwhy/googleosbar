package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.data.BarConfig
import com.meridian11.books.bentobar.data.HiddenMode
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.data.Section
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What is done with Android's answers for My location ([HereKeeper], which [WeatherHere] is on
 * Android): asking, taking a fix, calling an ask off, and forgetting. Android is a fake whose answers
 * come when a test gives them; the clock is the time since boot.
 */
class HereKeeperTest {
    private val sec = 1_000L
    private val min = 60 * sec
    private var now = 5_000_000L
    private val zurich = Fix(47.376_887, 8.541_694)
    private var changes = 0

    /** Android as a test sets it: the permission, the location switch, a last known location (taken at [lastAt]), and the asks it was given. */
    private class FakeAndroid(private val clock: () -> Long) : Locator {
        var allowed = true
        var on = true
        var lastFix: Fix? = null
        var lastAt = 0L
        var provider = true
        val asks = ArrayList<(Located?) -> Unit>()
        var calledOff = 0
        override fun allowed() = allowed
        override fun on() = on
        override fun last() = lastFix?.let { Located(it, age = clock() - lastAt) }
        override fun current(answer: (Located?) -> Unit): Locator.Cancel? {
            if (!provider) return null
            asks += answer
            return Locator.Cancel { calledOff++ }
        }

        /** Android answers the latest ask. */
        fun answer(located: Located?) = asks.last()(located)
    }

    private val android = FakeAndroid { now }
    private val keeper = HereKeeper(android, { now }) { changes++ }
    private val here = WeatherRules.useHere(ItemConfig("w1", "weather"))
    private val city = ItemConfig("w2", "weather", options = mapOf("city" to "Zurich", "lat" to "47.37", "lon" to "8.55"))

    /** The tick of a live item of My location, [after] this long. */
    private fun tick(after: Long = 0) {
        now += after
        keeper.keepUp()
    }

    @Test fun aLastKnownLocationUnderHalfAnHourOldIsTakenWithoutANewAsk() {
        android.lastFix = zurich
        android.lastAt = now - 10 * min
        tick()
        assertEquals(zurich, keeper.fix)
        assertTrue(android.asks.isEmpty())
        assertEquals(1, changes)
    }

    @Test fun aNewFixIsAskedForAndItsAnswerIsTaken() {
        tick()
        assertTrue(keeper.asking)
        assertNull(keeper.fix)
        assertEquals(Locate.FINDING, keeper.why)
        android.answer(Located(zurich, age = 0))
        assertEquals(zurich, keeper.fix)
        assertFalse(keeper.asking)
        // Asked again when the fix is half an hour old, not before.
        tick(29 * min)
        assertEquals(1, android.asks.size)
        tick(1 * min)
        assertEquals(2, android.asks.size)
    }

    @Test fun withNoProviderThereIsNoLocation() {
        android.provider = false
        tick()
        assertEquals(Locate.NONE, keeper.why)
        assertFalse(keeper.asking)
    }

    @Test fun switchingLocationOffCallsTheAskOffAndDropsItsAnswer() {
        tick()
        android.on = false
        tick(5 * sec)
        assertEquals(1, android.calledOff)
        assertFalse(keeper.asking)
        android.answer(Located(zurich, age = 0))
        assertNull(keeper.held.fix)
        assertEquals(Locate.OFF, keeper.why)
    }

    @Test fun takingThePermissionBackForgetsTheFixAndCallsTheAskOff() {
        tick()
        android.allowed = false
        tick(5 * sec)
        assertEquals(1, android.calledOff)
        assertEquals(Locate.NOT_ALLOWED, keeper.why)
        android.answer(Located(zurich, age = 0))
        assertNull(keeper.held.fix)
    }

    @Test fun forgettingCallsTheAskOffAndDropsItsAnswer() {
        tick()
        keeper.forget()
        assertEquals(1, android.calledOff)
        android.answer(Located(zurich, age = 0))
        assertEquals(Here(), keeper.held)
    }

    @Test fun theLocationGoesWithTheLastItemOfMyLocationAndWithTheSwitch() {
        tick()
        android.answer(Located(zurich, age = 0))
        assertTrue(keeper.keepFor(listOf(here, city), on = true))
        assertEquals(zurich, keeper.fix)
        assertFalse(keeper.keepFor(listOf(city), on = true))
        assertEquals(Here(), keeper.held)

        tick(5 * sec)
        android.answer(Located(zurich, age = 0))
        assertFalse(keeper.keepFor(listOf(here, city), on = false))
        assertEquals(Here(), keeper.held)
    }

    @Test fun anItemOfMyLocationThatIsRemovedForgetsThePlace() {
        tick()
        android.answer(Located(zurich, age = 0))
        // With a city still in the bar the type's next tick applies this; with no Weather item left, its onIdle.
        keeper.keepFor(listOf(city), on = true)
        assertEquals(Here(), keeper.held)
    }

    @Test fun anItemDeletedAfterItsFixWentPastItsTimeLeavesNothingHeld() {
        tick()
        android.answer(Located(zurich, age = 0))
        // The bar was hidden for an hour: no tick, and the fix is past its time but still held.
        now += HereRules.FIX_MS + 1
        assertNull(keeper.fix)
        keeper.keepFor(emptyList(), on = true)
        assertEquals(Here(), keeper.held)
    }

    @Test fun anItemDeletedWhileAndroidIsAskedCallsTheAskOffAndDropsItsAnswer() {
        tick()
        assertTrue(keeper.asking)
        keeper.keepFor(emptyList(), on = true)
        assertEquals(1, android.calledOff)
        android.answer(Located(zurich, age = 0))
        assertEquals(Here(), keeper.held)
    }

    @Test fun whileAndroidHasNoLocationEveryAskAfterTheFirstKeepsSayingSo() {
        // A Googlebook on Ethernet, where Android never knows where it is.
        tick()
        assertEquals(Locate.FINDING, keeper.why) // the first ask: "Loading…"
        android.answer(null)
        assertEquals(Locate.NONE, keeper.why)
        val drawn = changes
        for (wait in listOf(1L, 2L, 5L, 15L, 30L, 30L).map { it * min }) {
            tick(wait)
            assertTrue(keeper.asking)
            // Not "Loading…" again: the bar's crossed-out cloud and an open menu's "Location not found" stay.
            assertEquals(Locate.NONE, keeper.why)
            android.answer(null)
        }
        assertEquals(7, android.asks.size)
        assertEquals(drawn, changes)
    }

    /** What the ticker samples of [items] in click to reveal, by its own rule: ‹ [open] or not, the settings window open or not. */
    private fun sampled(items: List<ItemConfig>, open: Boolean = false, settings: Boolean = false, mode: HiddenMode = HiddenMode.CLICK) =
        TickRules.needed(BarConfig(hiddenMode = mode, items = items), settings, revealHidden = open, focusItem = null)

    @Test fun anItemOfMyLocationBehindTheChevronIsNotAskedForWhileAnotherWeatherItemShows() {
        // Click to reveal: the item of My location waits behind ‹, so the ticker samples only the city.
        val hidden = here.copy(section = Section.HIDDEN)
        val layout = listOf(hidden, city)
        assertEquals(listOf(city), sampled(layout))
        assertTrue(keeper.follow(layout, sampled(layout), on = true))
        assertTrue(android.asks.isEmpty())
        // ‹ is opened: now it is on screen, and Android is asked.
        keeper.follow(layout, sampled(layout, open = true), on = true)
        assertEquals(1, android.asks.size)
    }

    @Test fun anItemOfMyLocationWaitingInHiddenForItsShowWhenRuleIsAskedForInEveryHiddenMode() {
        // Its rule (rain or snow falling or coming) needs the forecast where the device is, so the ticker samples it while it waits:
        // behind a closed ‹, and in Show everything, where there is no ‹ and it isn't drawn.
        val waiting = here.copy(section = Section.HIDDEN, whenActive = true)
        for (mode in HiddenMode.entries) {
            val k = HereKeeper(FakeAndroid { now }.also { android -> asked[mode] = android }, { now }) {}
            k.follow(listOf(waiting, city), sampled(listOf(waiting, city), mode = mode), on = true)
            assertEquals("$mode", 1, asked.getValue(mode).asks.size)
        }
    }

    private val asked = HashMap<HiddenMode, FakeAndroid>()

    @Test fun theSettingsWindowOpenHasAnItemOfMyLocationBehindTheChevronAskedFor() {
        val hidden = here.copy(section = Section.HIDDEN)
        keeper.follow(listOf(hidden, city), sampled(listOf(hidden, city), settings = true), on = true)
        assertEquals(1, android.asks.size)
    }

    @Test fun anAskOnItsWayIsCalledOffWhenNoItemOfMyLocationIsOnScreenAndTheFixStays() {
        keeper.follow(listOf(here, city), sampled = listOf(here, city), on = true)
        android.answer(Located(zurich, age = 0))
        now += 30 * min
        keeper.follow(listOf(here, city), sampled = listOf(here, city), on = true)
        assertTrue(keeper.asking)
        // ‹ is closed on it, or the bar goes away (the type's onIdle samples nothing).
        assertTrue(keeper.follow(listOf(here, city), sampled = listOf(city), on = true))
        assertEquals(1, android.calledOff)
        assertFalse(keeper.asking)
        assertEquals(zurich, keeper.fix)
        assertTrue(keeper.follow(listOf(here, city), sampled = emptyList(), on = true))
        assertEquals(zurich, keeper.fix)
    }

    @Test fun followForgetsAsKeepForDoes() {
        keeper.follow(listOf(here), sampled = listOf(here), on = true)
        android.answer(Located(zurich, age = 0))
        assertFalse(keeper.follow(emptyList(), sampled = emptyList(), on = true))
        assertEquals(Here(), keeper.held)
    }

    @Test fun asksThatBringBackOnlyAndroidsOldFixBackOff() {
        val start = now
        tick()
        android.answer(Located(zurich, age = 0))
        // From now on Android has no new fix: its last known location stays this one.
        android.lastFix = zurich
        android.lastAt = start
        val asked = ArrayList<Long>()
        while (now < start + 70 * min) {
            val before = android.asks.size
            tick(5 * sec)
            if (android.asks.size > before) {
                asked += (now - start) / min
                android.answer(null) // no new fix: the keeper falls back to the old one, which is nothing new
            }
        }
        assertEquals(listOf(30L, 31L, 33L, 38L, 53L), asked)
        assertNull(keeper.fix)
        assertEquals(Locate.NONE, keeper.why)
    }
}
