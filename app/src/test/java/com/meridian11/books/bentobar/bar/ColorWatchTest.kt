package com.meridian11.books.bentobar.bar

import com.meridian11.books.bentobar.bar.ColorWatch.Cause
import com.meridian11.books.bentobar.bar.ColorWatch.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the status bar's colours are read after something that can change them, when the readings stop, and how many there can be. */
class ColorWatchTest {
    /** A strip that follows a bar: [look] is what the strip shows, [at] when it read the bar. */
    private class Strip(var look: String?, val watch: ColorWatch = ColorWatch()) {
        val at = ArrayList<Long>()
        var now = 0L

        /** Reads whenever a reading is due, up to [until]; [bar] is the bar's look at a moment, null for a reading that gives nothing. */
        fun follow(until: Long = 60_000, bar: (Long) -> String?): Strip {
            while (true) {
                val wait = watch.due(now) ?: break
                if (now + wait > until) break
                now += wait
                at += now
                watch.taken(now)
                val seen = bar(now)
                val result = ColorWatch.result(look, seen)
                if (seen != null) look = seen
                if (watch.read(now, result)) look = HINTS
            }
            return this
        }
    }

    /** A screenshot asked for and answered at [t]. */
    private fun ColorWatch.shot(t: Long, result: Result): Boolean { taken(t); return read(t, result) }

    /** The most screenshots asked for within any one minute. */
    private fun mostInAMinute(asked: List<Long>): Int {
        var most = 0
        var from = 0
        for (to in asked.indices) {
            while (asked[to] - asked[from] >= 60_000) from++
            most = maxOf(most, to - from + 1)
        }
        return most
    }

    // ---- one cause ---------------------------------------------------------------------------

    /**
     * Seen on a device: after an unlock the strip stayed white. The bar keeps the lock screen's light icons for
     * a while after the strip is back, then turns dark with no event. 0.9 read the bar 250 ms after the strip
     * came back (white, not the dark it had: a change) and once more 800 ms later (white again: settled, it
     * thought), and never again.
     */
    @Test fun afterAnUnlockTheBarIsReadUntilItHasSettled() {
        val strip = Strip("dark")
        strip.watch.cause(Cause.SLOW, now = 0, first = 250)
        strip.follow { t -> if (t < 3_000) "white" else "dark" }
        assertEquals(listOf(250L, 1_000L, 2_000L, 3_500L, 6_000L, 10_000L), strip.at)
        assertEquals("dark", strip.look)
    }

    /** Whenever the bar turns within those ten seconds, the strip follows, at the latest four seconds behind. */
    @Test fun whereverTheChangeFallsAfterAReturnItIsRead() {
        for (turn in 0L..9_750L step 250) {
            val strip = Strip("dark")
            strip.watch.cause(Cause.SLOW, now = 0, first = 250)
            strip.follow { t -> if (t < turn) "white" else "dark" }
            assertEquals("the bar turned at $turn", "dark", strip.look)
            val seen = strip.at.first { it >= turn }
            assertTrue("the bar turned at $turn and was read at $seen", seen - turn <= 4_000)
        }
    }

    /** The same when the bar takes eight seconds (a wake from a long sleep): the last reading finds the change, so one more confirms it. */
    @Test fun aReturnIsWatchedForTenSeconds() {
        val strip = Strip("dark")
        strip.watch.cause(Cause.SLOW, now = 0, first = 250)
        strip.follow { t -> if (t < 8_000) "white" else "dark" }
        assertEquals(listOf(250L, 1_000L, 2_000L, 3_500L, 6_000L, 10_000L, 10_800L), strip.at)
        assertEquals("dark", strip.look)
        assertNull(strip.watch.due(strip.now))
    }

    /** And not for longer: a bar that turns after that is read at the next cause, as before. Only a timer would catch it. */
    @Test fun aBarThatTurnsAfterTheWatchIsNotNoticed() {
        val strip = Strip("dark")
        strip.watch.cause(Cause.SLOW, now = 0, first = 250)
        strip.follow { t -> if (t < 12_000) "white" else "dark" }
        assertEquals(10_000L, strip.at.last())
        assertEquals("white", strip.look)
    }

    /**
     * A window is maximized: its bounds say so at once, the bar turns black when the window has landed. A
     * reading before that shows the old look, which is no proof that nothing will change: the second
     * reading is taken whatever the first one showed (and, having found the change, is confirmed).
     */
    @Test fun aReadingThatMatchesTheLastOneDoesNotEndTheWatch() {
        val strip = Strip("see-through")
        strip.watch.cause(Cause.QUICK, now = 0, first = 300)
        strip.follow { t -> if (t < 600) "see-through" else "black" }
        assertEquals(listOf(300L, 1_300L, 2_100L), strip.at)
        assertEquals("black", strip.look)
    }

    @Test fun aBarThatDidNotChangeCostsTwoReadings() {
        val strip = Strip("black")
        strip.watch.cause(Cause.QUICK, now = 0, first = 300)
        strip.follow { "black" }
        assertEquals(listOf(300L, 1_300L), strip.at)
    }

    /** The bar fades: a look that still differed at the last reading may have been caught halfway. */
    @Test fun aLookStillMovingAtTheLastReadingIsReadOnceMore() {
        val strip = Strip("see-through")
        strip.watch.cause(Cause.QUICK, now = 0, first = 300)
        strip.follow { t -> when { t < 1_000 -> "see-through"; t < 1_500 -> "halfway"; else -> "black" } }
        assertEquals(listOf(300L, 1_300L, 2_100L, 2_900L), strip.at)
        assertEquals("black", strip.look)
    }

    @Test fun aBarThatNeverSettlesIsNotReadForever() {
        val strip = Strip("a")
        strip.watch.cause(Cause.QUICK, now = 0, first = 300)
        strip.follow { t -> "look at $t" }
        assertEquals(listOf(300L, 1_300L, 2_100L, 2_900L, 3_700L), strip.at)
        assertNull(strip.watch.due(strip.now))
        // The next cause gets its own readings again, and its own three more.
        strip.watch.cause(Cause.QUICK, now = 5_000, first = 300)
        strip.follow { t -> "look at $t" }
        assertEquals(listOf(5_300L, 6_300L, 7_100L, 7_900L, 8_700L), strip.at.drop(5))
    }

    // ---- causes that meet ---------------------------------------------------------------------

    /**
     * A window is maximized right after an unlock. Its readings come at once (it is the first window change
     * in a while) and stand in for the unlock's readings that fall beside them; the late ones stay.
     */
    @Test fun aWindowChangeDuringAReturnKeepsTheLateReadings() {
        val strip = Strip("dark")
        strip.watch.cause(Cause.SLOW, now = 0, first = 250)
        strip.follow(until = 400) { "white" }
        assertEquals(listOf(250L), strip.at)
        strip.watch.windowsMoved(now = 400)
        strip.follow { t -> if (t < 4_000) "white" else "dark" }
        // 700 serves the return's 1 000 too, and 1 700 its 2 000.
        assertEquals(listOf(250L, 700L, 1_700L, 3_500L, 6_000L, 10_000L), strip.at)
        assertEquals("dark", strip.look)
    }

    /** The strip is back again before the first return was through (a panel opened and closed twice). */
    @Test fun aNewReturnStartsOver() {
        val strip = Strip("dark")
        strip.watch.cause(Cause.SLOW, now = 0, first = 250)
        strip.follow(until = 1_200) { "dark" }
        strip.watch.cause(Cause.SLOW, now = 1_200, first = 250)
        strip.follow { "dark" }
        assertEquals(listOf(250L, 1_000L, 1_450L, 2_200L, 3_200L, 4_700L, 7_200L, 11_200L), strip.at)
    }

    /** A second window change before the first one's look was confirmed: its readings take the place of the confirming one. */
    @Test fun aQuickCauseTakesThePlaceOfAConfirmStillToCome() {
        val strip = Strip("a")
        strip.watch.cause(Cause.QUICK, now = 0, first = 300)
        strip.follow(until = 1_400) { t -> if (t < 1_000) "a" else "b" }
        assertEquals(2_100L - 1_300L, strip.watch.due(1_300))
        strip.watch.cause(Cause.QUICK, now = 1_500, first = 300)
        strip.follow { "b" }
        assertEquals(listOf(300L, 1_300L, 1_800L, 2_800L), strip.at)
    }

    // ---- screenshots on their way --------------------------------------------------------------

    /** Android refuses a screenshot within a third of a second of the last one: a cause right after one waits that out. */
    @Test fun aCauseRightAfterAScreenshotKeepsItsDistance() {
        val watch = ColorWatch()
        watch.shot(1_000, Result.SAME)
        watch.cause(Cause.QUICK, now = 1_010, first = 300)
        assertEquals(390L, watch.due(1_010))
        // Its second reading keeps its second.
        watch.taken(1_400)
        assertEquals(1_000L, watch.due(1_400))
    }

    /**
     * The answer takes a moment. A window change in that moment is not served by a screenshot asked for
     * before it, and the reading that screenshot is for is not asked for a second time.
     */
    @Test fun aCauseWhileAScreenshotIsOnItsWayGetsItsOwn() {
        val watch = ColorWatch()
        watch.cause(Cause.SLOW, now = 0, first = 250)
        watch.shot(250, Result.SAME)
        watch.taken(1_000)
        watch.windowsMoved(now = 1_020)
        assertEquals(380L, watch.due(1_020))
        assertFalse(watch.read(1_050, Result.SAME))
        assertEquals(350L, watch.due(1_050))
    }

    /** The main thread was busy and two readings fell due: one screenshot is taken for both. */
    @Test fun oneScreenshotServesAllThatAreOverdue() {
        val watch = ColorWatch()
        watch.cause(Cause.SLOW, now = 0, first = 250)
        watch.taken(1_100)
        assertEquals(900L, watch.due(1_100))
    }

    // ---- readings that give nothing -------------------------------------------------------------

    /** A reading can give nothing (the bar caught fading, a screenshot refused): tried twice more, then the hints. */
    @Test fun readingsThatGiveNothingAreTriedAgainAndThenTheHintsAreUsed() {
        val strip = Strip("dark")
        strip.watch.cause(Cause.QUICK, now = 0, first = 300)
        strip.follow { null }
        assertEquals(listOf(300L, 1_300L, 2_800L), strip.at)
        assertEquals(HINTS, strip.look)
        assertNull(strip.watch.due(strip.now))
    }

    @Test fun aReadingAfterTwoThatGaveNothingKeepsTheColours() {
        val strip = Strip("dark")
        strip.watch.cause(Cause.QUICK, now = 0, first = 300)
        strip.follow { t -> if (t < 2_000) null else "black" }
        assertEquals("black", strip.look)
        // 2 800: the look differs from the one before, so one more.
        assertEquals(listOf(300L, 1_300L, 2_800L, 3_600L), strip.at)
    }

    /** With late readings still to come the hints are used after three that gave nothing, and a later reading replaces them. */
    @Test fun theHintsAreUsedOnceAndAReadingReplacesThem() {
        val watch = ColorWatch()
        watch.cause(Cause.SLOW, now = 0, first = 250)
        assertFalse(watch.shot(250, Result.NOTHING))
        assertFalse(watch.shot(1_000, Result.NOTHING))
        assertTrue(watch.shot(2_000, Result.NOTHING))
        assertEquals(1_500L, watch.due(2_000))
        assertFalse(watch.shot(3_500, Result.NOTHING))
        assertFalse(watch.shot(6_000, Result.NOTHING))
        assertFalse(watch.shot(10_000, Result.CHANGED))
        // The look changed at the last reading: one more.
        assertEquals(800L, watch.due(10_000))
    }

    /** What a reading is, against what the strip had; after the hints (the strip has no reading) any reading is a change. */
    @Test fun whatAReadingShows() {
        assertEquals(Result.SAME, ColorWatch.result("white", "white"))
        assertEquals(Result.CHANGED, ColorWatch.result("white", "dark"))
        assertEquals(Result.NOTHING, ColorWatch.result("white", null))
        assertEquals(Result.NOTHING, ColorWatch.result(null, null))
        assertEquals(Result.CHANGED, ColorWatch.result(null, "white"))
    }

    // ---- nothing to read -----------------------------------------------------------------------

    @Test fun nothingIsDueWithoutACauseOrAfterClear() {
        val watch = ColorWatch()
        assertNull(watch.due(0))
        watch.cause(Cause.SLOW, now = 0, first = 250)
        assertEquals(250L, watch.due(0))
        assertEquals(0L, watch.due(900))
        watch.clear()
        assertNull(watch.due(900))
    }

    /** A reading nobody asked for (the test hook) that finds another look is confirmed like any other. */
    @Test fun aReadingOutOfTurnThatFindsAChangeIsConfirmed() {
        val watch = ColorWatch()
        assertFalse(watch.shot(5_000, Result.SAME))
        assertNull(watch.due(5_000))
        watch.shot(6_000, Result.CHANGED)
        assertEquals(800L, watch.due(6_000))
    }

    // ---- causes that keep coming: the budget -----------------------------------------------------

    /**
     * Under a full-screen app every screen it opens is another window under the bar. The first readings keep
     * two seconds apart as before ([BarNeighbours.wait]): a new change takes the place of the second reading
     * of the one before, and only the last change gets both.
     */
    @Test fun windowChangesInAStreamKeepTheirReadingsApart() {
        val watch = ColorWatch()
        val at = ArrayList<Long>()
        for (t in 0L..24_000L step 100) {
            if (watch.due(t) == 0L) { at += t; watch.shot(t, Result.SAME) }
            if (t <= 20_000 && t % 400 == 0L) watch.windowsMoved(t)
        }
        val during = at.filter { it <= 20_000 }
        assertTrue("$during", during.size in 9..11)
        assertTrue("$during", during.zipWithNext().all { (a, b) -> b - a >= BarNeighbours.APART_MS })
        // After the last change: its reading, and the one a second later.
        assertEquals("$at", 2, at.size - during.size)
    }

    /**
     * A window change every two seconds, for minutes (an app that cycles its screens). Each gets both readings
     * until a dozen were taken; from then on one reading every two seconds, as 0.9 had it, and the last
     * change still gets its second one.
     */
    @Test fun aWindowChangeEveryTwoSecondsIsReadOnceEach() {
        val watch = ColorWatch()
        val at = ArrayList<Long>()
        for (t in 0L..200_000L step 100) {
            if (watch.due(t) == 0L) { at += t; watch.shot(t, Result.SAME) }
            if (t <= 180_000 && t % 2_000 == 0L) watch.windowsMoved(t)
        }
        assertTrue("${at.take(40)}", mostInAMinute(at) <= 36)
        assertTrue(mostInAMinute(at.filter { it > 60_000 }) <= 31)
        assertEquals(2, at.count { it > 180_000 })
    }

    /** A live wallpaper can report new colours every second. 0.9 read the bar every second for that; now every two. */
    @Test fun aWallpaperThatChangesEverySecondIsReadEveryTwoSeconds() {
        val watch = ColorWatch()
        val at = ArrayList<Long>()
        for (t in 0L..180_000L step 100) {
            if (watch.due(t) == 0L) { at += t; watch.shot(t, Result.SAME) }
            if (t % 1_000 == 0L) watch.cause(Cause.QUICK, t, 300)
        }
        assertTrue(mostInAMinute(at.filter { it > 60_000 }) <= 31)
        assertTrue(at.filter { it > 60_000 }.zipWithNext().all { (a, b) -> b - a >= BarNeighbours.APART_MS })
    }

    /** Something that hides and shows the strip every nine seconds must not cost five screenshots each time for good. */
    @Test fun aReturnEveryNineSecondsStaysWithinTheBudget() {
        val watch = ColorWatch()
        val at = ArrayList<Long>()
        for (t in 0L..300_000L step 50) {
            if (watch.due(t) == 0L) { at += t; watch.shot(t, Result.SAME) }
            if (t % 9_000 == 0L) watch.cause(Cause.SLOW, t, 250)
        }
        assertTrue("${mostInAMinute(at.filter { it > 60_000 })}", mostInAMinute(at.filter { it > 60_000 }) <= 20)
    }

    /**
     * Whatever happens, in whatever order, with answers that take up to 150 ms: two screenshots are never
     * asked for within 400 ms of each other, and never more than 45 in any minute.
     */
    @Test fun screenshotsKeepTheirDistanceAndTheirBudgetWhateverHappens() {
        for (seed in 1L..20L) {
            val random = java.util.Random(seed)
            val watch = ColorWatch()
            val asked = ArrayList<Long>()
            val answers = ArrayList<Long>()
            for (t in 0L..300_000L step 10) {
                while (answers.isNotEmpty() && answers.first() <= t) { answers.removeAt(0); watch.read(t, Result.values()[random.nextInt(3)]) }
                if (watch.due(t) == 0L) { watch.taken(t); asked += t; answers += t + random.nextInt(16) * 10; answers.sort() }
                when (random.nextInt(60)) { // something about every 200 ms
                    0 -> watch.windowsMoved(t)
                    1 -> watch.cause(Cause.QUICK, t, 100L + random.nextInt(5) * 100)
                    2 -> if (random.nextInt(10) == 0) watch.cause(Cause.SLOW, t, 250)
                    3 -> if (random.nextInt(20) == 0) watch.clear()
                }
            }
            assertTrue("seed $seed", asked.zipWithNext().all { (a, b) -> b - a >= 400 })
            assertTrue("seed $seed: ${mostInAMinute(asked)} in a minute", mostInAMinute(asked) <= 45)
        }
    }

    // ---- a stream that goes on: the second tier ---------------------------------------------------------

    /** A cause every [every] ms until [until]; each reading due is asked for and answered at once, through [end]. */
    private fun stream(until: Long, every: Long, end: Long = until, cause: ColorWatch.(Long) -> Unit): List<Long> {
        val watch = ColorWatch()
        val at = ArrayList<Long>()
        for (t in 0L..end step 100) {
            if (watch.due(t) == 0L) { at += t; watch.shot(t, Result.SAME) }
            if (t <= until && t % every == 0L) watch.cause(t)
        }
        return at
    }

    private fun wallpaper(until: Long, end: Long = until) = stream(until, every = 1_000, end) { cause(Cause.QUICK, it, 300) }

    /** Finding F26: a wallpaper that reports its colours every second, for an hour, must not cost 30 screenshots a minute for an hour. */
    @Test fun aStreamThatGoesOnIsReadLessAndLess() {
        val at = wallpaper(until = 3_600_000)
        // The first minute of the stream is the budget as it always was: every two seconds, once a dozen were taken.
        val first = at.filter { it in 20_000L..60_000L }
        assertTrue("$first", first.isNotEmpty() && first.zipWithNext().all { (a, b) -> b - a >= BarNeighbours.APART_MS })
        // A minute in, every ten seconds; five minutes in, once a minute.
        val tenSeconds = at.filter { it > 90_000 }
        assertTrue("${tenSeconds.take(20)}", tenSeconds.zipWithNext().all { (a, b) -> b - a >= 10_000 })
        val minutes = at.filter { it > 330_000 }
        assertTrue("${minutes.take(20)}", minutes.zipWithNext().all { (a, b) -> b - a >= 60_000 })
        assertTrue("${minutes.size} readings after the first five minutes", minutes.size <= 56)
        assertTrue("${at.size} readings in the hour", at.size <= 140)
    }

    /** "Reading on a change" is the rule: the tier narrows the rate, it never drops the last cause. */
    @Test fun theLastCauseOfALongStreamIsStillRead() {
        val at = wallpaper(until = 1_200_000, end = 1_400_000)
        val after = at.filter { it > 1_200_000 }
        assertTrue("$after", after.size in 1..2)
        assertTrue("the last cause is read within the minute's gap: $after", after.first() <= 1_260_000)
        assertTrue("and its readings keep their distance: $after", after.zipWithNext().all { (a, b) -> b - a >= 60_000 })
    }

    /**
     * The stream's age goes on counting after its last cause, and the gap went with it: a stream that stopped at ten seconds
     * apart held its last follow-up back a minute once its age passed five minutes. It keeps the gap it stopped with.
     */
    @Test fun theLastFollowUpOfAStreamKeepsTheGapTheStreamStoppedWith() {
        val after = wallpaper(until = 305_000, end = 400_000).filter { it > 305_000 }
        assertEquals("$after", 2, after.size)
        assertEquals("$after", 10_000L, after[1] - after[0])
    }

    /** Readings aren't budgeted away from a change of windows or a return either, once a stream ends. */
    @Test fun aStreamThatStopsIsForgotten() {
        val watch = ColorWatch()
        for (t in 0L..400_000L step 1_000) { if (watch.due(t) == 0L) watch.shot(t, Result.SAME); watch.cause(Cause.QUICK, t, 300) }
        // Readings owed by the stream's last cause are taken within its gap.
        var t = 400_000L
        while (watch.due(t) != null) { t += watch.due(t)!!; watch.shot(t, Result.SAME) }
        // A minute with no cause: the next one is read like any other.
        val next = t + 61_000
        watch.cause(Cause.QUICK, next, 300)
        assertEquals(300L, watch.due(next))
        assertEquals(listOf(next + 300, next + 1_300), Strip("a", watch).also { it.now = next }.follow(until = next + 5_000) { "a" }.at)
    }

    /** A stream's window changes keep the two-second rule until it has lasted; only then does the gap widen. */
    @Test fun aWindowStreamWidensLikeAnyOther() {
        val at = stream(until = 600_000, every = 2_000, end = 700_000) { windowsMoved(it) }
        assertTrue("${at.filter { it > 90_000 }.take(20)}", at.filter { it > 90_000 }.zipWithNext().all { (a, b) -> b - a >= 10_000 })
        assertTrue("the last change is read after it stopped", at.any { it > 600_000 })
    }

    /** The gap holds for every reading a quick cause asks for in a stream that goes on: its later reading and a change's follow-ups too. */
    @Test fun inALongStreamEveryReadingOfAQuickCauseKeepsTheGap() {
        val watch = ColorWatch()
        val at = ArrayList<Long>()
        for (t in 0L..1_000_000L step 100) {
            // The bar changes at every reading, so each one asks for its follow-up; and a QUICK cause every nine seconds.
            if (watch.due(t) == 0L) { at += t; watch.shot(t, Result.CHANGED) }
            if (t % 9_000 == 0L) watch.cause(Cause.QUICK, t, 250)
        }
        assertTrue("${at.filter { it in 90_000L..300_000L }.take(20)}", at.filter { it in 90_000L..300_000L }.zipWithNext().all { (a, b) -> b - a >= 10_000 })
        assertTrue("${at.filter { it > 400_000 }.take(20)}", at.filter { it > 400_000 }.zipWithNext().all { (a, b) -> b - a >= 60_000 })
    }

    /** A wallpaper's stream that has lasted [until] ms, every reading due asked for and answered at once, the last one at [until]. */
    private fun ColorWatch.wallpaperStream(until: Long): ColorWatch {
        for (t in 0L..until step 100) {
            if (due(t) == 0L) shot(t, Result.SAME)
            if (t % 1_000 == 0L) cause(Cause.QUICK, t, 300)
        }
        shot(until, Result.SAME)
        return this
    }

    /**
     * A live wallpaper has been reporting for ten minutes, and the screen is locked and unlocked twenty seconds later. The
     * stream didn't cause that, and the bar has just kept the lock screen's look: the return's first reading comes when it
     * always came, not when the stream's minute is up. What follows it keeps the gap.
     */
    @Test fun aLongStreamDoesNotHoldBackAReturnsFirstReading() {
        val watch = ColorWatch().wallpaperStream(until = 600_000)
        watch.clear() // the strip left the screen
        watch.cause(Cause.SLOW, 620_000, 250)
        assertEquals(250L, watch.due(620_000))
        watch.shot(620_250, Result.SAME)
        assertEquals(60_000L, watch.due(620_250))
    }

    /** A wallpaper's quick causes coming in between take nothing from it. */
    @Test fun quickCausesOfALongStreamLeaveAReturnsFirstReadingAlone() {
        val watch = ColorWatch().wallpaperStream(until = 600_000)
        watch.clear()
        watch.cause(Cause.SLOW, 620_000, 250)
        watch.cause(Cause.QUICK, 620_100, 100)
        assertEquals(150L, watch.due(620_100))
    }

    /** It is held to what the budget's first tier asks of a reading, two seconds behind the last one, and Android's distance with it. */
    @Test fun aReturnsFirstReadingStaysTwoSecondsBehindTheLastOne() {
        val watch = ColorWatch().wallpaperStream(until = 600_000)
        watch.clear()
        watch.shot(620_000, Result.SAME)
        watch.cause(Cause.SLOW, 620_500, 250)
        assertEquals(1_500L, watch.due(620_500))
    }

    /** What that costs: something that hides and shows the strip for good gets one reading for each return and no follow-ups. */
    @Test fun inALongStreamAReturnCostsItsFirstReadingAndNoMore() {
        val watch = ColorWatch()
        val at = ArrayList<Long>()
        for (t in 0L..1_000_000L step 100) {
            // The bar changes at every reading, so each one would ask for its follow-up.
            if (watch.due(t) == 0L) { at += t; watch.shot(t, Result.CHANGED) }
            if (t % 9_000 == 0L) watch.cause(Cause.SLOW, t, 250)
        }
        val late = at.filter { it > 400_000 }
        // 300: the first reading 250 ms after the cause, on this loop's tenths of a second.
        assertTrue("${late.take(20)}", late.all { it % 9_000 == 300L })
        assertTrue("${late.take(20)}", late.zipWithNext().all { (a, b) -> b - a == 9_000L })
    }

    /** Whatever the tier: two screenshots are never asked for within 400 ms. */
    @Test fun theLongStreamKeepsTheDistanceWhateverHappens() {
        for (seed in 1L..5L) {
            val random = java.util.Random(seed)
            val watch = ColorWatch()
            val asked = ArrayList<Long>()
            for (t in 0L..1_500_000L step 20) {
                if (watch.due(t) == 0L) { watch.taken(t); asked += t; watch.read(t, Result.values()[random.nextInt(3)]) }
                when (random.nextInt(50)) { // something about every second
                    0 -> watch.windowsMoved(t)
                    1 -> watch.cause(Cause.QUICK, t, 100L + random.nextInt(5) * 100)
                    2 -> if (random.nextInt(10) == 0) watch.cause(Cause.SLOW, t, 250)
                    3 -> if (random.nextInt(40) == 0) watch.clear()
                }
            }
            assertTrue("seed $seed", asked.zipWithNext().all { (a, b) -> b - a >= 400 })
        }
    }

    private companion object { const val HINTS = "hints" }

    @Test fun aStripWithNothingToDrawReadsNothingAndCatchesUpWhenItHasSomething() {
        // Back from the lock screen: the slow series starts, then the strip turns out empty (CPU and Network wait for a
        // second reading). An invisible window of ours reading the screen is what Play Protect flags: no readings then.
        val w = ColorWatch()
        w.cause(Cause.SLOW, 0, 250)
        w.pause()
        assertNull(w.due(0))
        // Something to draw a second later: the series the unlock asked for, not just two quick readings that can still
        // catch the lock screen's look.
        val s = Strip("lock", w).also { w.resume(1_000, 250) }.follow { "lock" }
        assertEquals(listOf(1_250L, 2_000L, 3_000L, 4_500L, 7_000L, 11_000L), s.at)
    }

    @Test fun aCauseWhileTheStripIsEmptyWaitsForIt() {
        val w = ColorWatch()
        w.pause()
        w.owe(Cause.QUICK)
        assertNull(w.due(0))
        w.owe(Cause.SLOW); w.owe(Cause.QUICK)                // a quick one doesn't take a slow one's place
        val s = Strip("a", w).also { w.resume(5_000, 250) }.follow { "a" }
        assertEquals(6, s.at.size)
        // Nothing owed: content appearing gets its two quick readings.
        val q = ColorWatch().also { it.pause(); it.resume(0, 250) }
        assertEquals(listOf(250L, 1_250L), Strip("a", q).follow { "a" }.at)
        // The strip left the screen meanwhile: what was owed no longer matters (its return asks again).
        val gone = ColorWatch().also { it.pause(); it.owe(Cause.SLOW); it.clear(); it.resume(0, 250) }
        assertEquals(listOf(250L, 1_250L), Strip("a", gone).follow { "a" }.at)
    }
}
