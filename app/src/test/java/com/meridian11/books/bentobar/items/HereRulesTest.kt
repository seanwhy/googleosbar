package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.data.Section
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * My location's timing, as WeatherHere follows it: when it looks, when it asks Android, how long a fix
 * is good for, and when where the device is is forgotten. The clock is the time since boot.
 */
class HereRulesTest {
    private val sec = 1_000L
    private val min = 60 * sec
    private val start = 5_000_000L
    private val zurich = Fix(47.376_887, 8.541_694)

    /** A look with the permission and location on, nothing on its way. */
    private fun look(h: Here, now: Long, allowed: Boolean = true, on: Boolean = true, asking: Boolean = false) = HereRules.look(h, now, allowed, on, asking)

    /** Asked at [at], and Android said [zurich] then. */
    private fun foundAt(at: Long) = HereRules.found(HereRules.asked(Here(), at), zurich, at = at, now = at)

    @Test fun thePermissionAndTheSwitchAreLookedAtEveryFiveSeconds() {
        assertTrue(HereRules.looks(start, null))
        assertFalse(HereRules.looks(start + 4_999, start))
        assertTrue(HereRules.looks(start + 5 * sec, start))
    }

    @Test fun androidIsAskedAtOnceAndThenWhenTheFixIsHalfAnHourOld() {
        assertTrue(look(Here(), start).ask)
        val h = foundAt(start)
        assertEquals(zurich, h.fix)
        assertFalse(look(h, start + 29 * min).ask)
        assertTrue(look(h, start + 30 * min).ask)
        // A fix another app asked for 20 minutes ago is half an hour old 10 minutes from now.
        val older = HereRules.found(HereRules.asked(Here(), start), zurich, at = start - 20 * min, now = start)
        assertFalse(look(older, start + 9 * min).ask)
        assertTrue(look(older, start + 10 * min).ask)
        // An ask on its way is not asked twice.
        assertFalse(look(Here(), start, asking = true).ask)
    }

    @Test fun anAskThatBringsTheSameOldFixAgainWaitsAMinute() {
        val h = HereRules.found(HereRules.asked(Here(), start), zurich, at = start - 31 * min, now = start)
        assertEquals(zurich, h.fix)
        assertFalse(look(h, start + 59 * sec).ask)
        assertTrue(look(h, start + 1 * min).ask)
    }

    @Test fun withoutALocationAndroidIsAskedLessAndLessOftenUpToEveryHalfHour() {
        // A Googlebook on Ethernet, where Android never knows where it is: 1, 2, 5 and 15 minutes, then every 30.
        var h = Here()
        var now = start
        val waits = ArrayList<Long>()
        repeat(7) {
            assertTrue(look(h, now).ask)
            h = HereRules.none(HereRules.asked(h, now))
            assertEquals(Locate.NONE, h.why)
            var next = now + 5 * sec
            while (!look(h, next).ask) next += 5 * sec
            waits += next - now
            now = next
        }
        assertEquals(listOf(1, 2, 5, 15, 30, 30, 30).map { it * min }, waits)
        // A fix found ends the back-off; the next time Android has none, it starts again at a minute.
        h = HereRules.found(HereRules.asked(h, now), zurich, at = now, now = now)
        assertEquals(0, h.misses)
        assertEquals(1 * min, HereRules.backOff(HereRules.none(Here()).misses))
    }

    @Test fun onlyAFirstAskIsFindingAndAnAskAfterNoneKeepsNone() {
        assertEquals(Locate.FINDING, HereRules.asked(Here(), start).why)
        val none = HereRules.none(HereRules.asked(Here(), start))
        assertEquals(Locate.NONE, HereRules.asked(none, start + 1 * min).why)
        // Location back on, or the permission given: that ask is a first one again.
        assertEquals(Locate.FINDING, HereRules.asked(Here(why = Locate.OFF), start).why)
        assertEquals(Locate.FINDING, HereRules.asked(Here(why = Locate.NOT_ALLOWED), start).why)
    }

    @Test fun anAskThatFindsNothingWhileAFixIsStillGoodBacksOffToo() {
        // The fix is half an hour old and Android has nothing new: asked after 1 and 2 minutes, not every
        // minute; when the fix's 35 minutes are up the back-off goes on, and the item says Location not found.
        var h = foundAt(start)
        var now = start + 30 * min
        val asked = ArrayList<Long>()
        while (now <= start + 70 * min) {
            val l = look(h, now)
            h = l.here
            if (l.ask) {
                asked += (now - start) / min
                h = HereRules.none(HereRules.asked(h, now))
            }
            if (now == start + 36 * min) assertEquals(Locate.NONE, HereRules.fresh(h, now).why)
            now += 5 * sec
        }
        assertEquals(listOf(30L, 31L, 33L, 38L, 53L), asked)
        // A fix found ends it.
        h = HereRules.found(HereRules.asked(h, now), zurich, at = now, now = now)
        assertEquals(0, h.misses)
        // The same old fix again counts as nothing new: the waits grow as well.
        val old = HereRules.found(HereRules.asked(Here(), start), zurich, at = start - 31 * min, now = start)
        val again = HereRules.found(HereRules.asked(old, start + 1 * min), zurich, at = start - 31 * min, now = start + 1 * min)
        assertFalse(look(again, start + 2 * min).ask)
        assertTrue(look(again, start + 3 * min).ask)
    }

    @Test fun aWakeAsksAtOnceWhereThereIsNoFix() {
        var h = HereRules.asked(Here(), start)
        repeat(3) { h = HereRules.none(HereRules.asked(h, start)) }
        assertFalse(look(h, start + 1 * min).ask)
        assertTrue(look(HereRules.woken(h), start + 1 * min).ask)
        // With a fix, a wake changes nothing.
        val found = foundAt(start)
        assertEquals(found, HereRules.woken(found))
    }

    @Test fun withLocationOffAFixStaysForItsTimeAndWithoutOneThatIsWhy() {
        val h = foundAt(start)
        val off = look(h, start + 20 * min, on = false)
        assertEquals(zurich, off.here.fix)
        assertFalse(off.ask)
        val later = look(h, start + HereRules.FIX_MS + 1, on = false)
        assertNull(later.here.fix)
        assertEquals(Locate.OFF, later.here.why)
        assertEquals(Locate.OFF, look(Here(), start, on = false).here.why)
        // Location back on: asked at once.
        assertTrue(look(later.here, start + HereRules.FIX_MS + 2).ask)
    }

    @Test fun takingThePermissionBackForgetsTheFix() {
        val gone = look(foundAt(start), start + 1 * min, allowed = false)
        assertNull(gone.here.fix)
        assertEquals(Locate.NOT_ALLOWED, gone.here.why)
        assertFalse(gone.ask)
        // Allowed again: asked at once.
        assertTrue(look(gone.here, start + 2 * min).ask)
    }

    @Test fun aFixPastItsTimeIsDroppedBeforeAnythingIsAskedForIt() {
        // A night with the screen off: in the morning, yesterday's fix is none, and a new one is asked for.
        val h = foundAt(start)
        assertEquals(zurich, HereRules.fresh(h, start + HereRules.FIX_MS).fix)
        val morning = start + 9 * 60 * min
        assertNull(HereRules.fresh(h, morning).fix)
        val look = look(h, morning)
        assertNull(look.here.fix)
        assertTrue(look.ask)
        // A last known location that is older than that already counts as none.
        val stale = HereRules.found(HereRules.asked(Here(), morning), zurich, at = start, now = morning)
        assertNull(stale.fix)
        assertEquals(Locate.NONE, stale.why)
        // And a good fix is good for its half hour and the few minutes the next may take.
        assertEquals(35 * min, HereRules.FIX_MS)
    }

    @Test fun whereTheDeviceIsIsWantedForAnItemOfMyLocationWhileTheSwitchIsOn() {
        val here = WeatherRules.useHere(ItemConfig("w1", "weather"))
        val city = ItemConfig("w2", "weather", options = mapOf("city" to "Zurich", "lat" to "47.37", "lon" to "8.55"))
        assertTrue(HereRules.wanted(listOf(here, city), on = true))
        // The Weather switch goes off.
        assertFalse(HereRules.wanted(listOf(here, city), on = false))
        // The last item of My location goes, or turns back to its city.
        assertFalse(HereRules.wanted(listOf(city), on = true))
        assertFalse(HereRules.wanted(listOf(WeatherRules.useCity(here), city), on = true))
        // Hidden, it is wanted all the same: its rule may bring it out.
        assertTrue(HereRules.wanted(listOf(here.copy(section = Section.HIDDEN), city), on = true))
    }

    @Test fun nothingPrintsWhereTheDeviceIs() {
        assertEquals("a fix", zurich.toString())
        assertFalse(foundAt(start).toString().contains("47"))
    }
}
