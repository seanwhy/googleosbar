package com.meridian11.books.bentobar.bar

import com.meridian11.books.bentobar.bar.BarRules.AreaState
import com.meridian11.books.bentobar.bar.BarRules.Lane
import com.meridian11.books.bentobar.bar.BarRules.Spot
import com.meridian11.books.bentobar.data.Position
import com.meridian11.books.bentobar.data.Position.CENTER
import com.meridian11.books.bentobar.data.Position.LEFT
import com.meridian11.books.bentobar.data.Position.RIGHT
import com.meridian11.books.bentobar.data.ROOM_ORDER
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.abs

/**
 * Room for the three areas (`BarRules.arrange`): which of them get a window, where and how wide, whose window goes, whose
 * items are owed and which strip carries the bar's one-off work. One area alone is the strip as it was before there were
 * areas, to the pixel: what is expected of it here is that placing's own lines, written out ([today]).
 *
 * The bars are `BarRulesTest`'s: the 14-inch one (1920 wide, free from 168 to 1681, so its middle falls on half a pixel)
 * and the captured one (2560 wide, free from 242 to 2243).
 */
class ArrangeTest {
    /** A status bar as one scan reads it: how wide the screen is and where the free area begins and ends. */
    private data class Bar(val screenW: Int, val freeLeft: Int, val freeRight: Int)

    /** The caller's numbers at one density: the gap (6 dp), the strip's end padding (6 dp) and the ‹'s reserve (30 dp). */
    private data class Scale(val gap: Int, val ends: Int, val reserve: Int) {
        val least get() = reserve + 2 * ends
    }

    private val small = Bar(1920, 168, 1681)
    private val large = Bar(2560, 242, 2243)
    private val one = Scale(6, 6, 30)

    /** Density 2.25, each number rounded down as the controller rounds it. */
    private val dense = Scale(13, 13, 67)
    private val all = Position.entries.toSet()

    private fun out(pill: Boolean, k: Scale) = if (pill) 0 else k.ends
    private fun lo(bar: Bar, pill: Boolean, k: Scale = one) = bar.freeLeft + k.gap - out(pill, k)
    private fun hi(bar: Bar, pill: Boolean, k: Scale = one) = bar.freeRight - k.gap + out(pill, k)

    private fun arrange(bar: Bar, home: Position, inUse: Set<Position>, strips: Map<Position, AreaState>, pill: Boolean = false, k: Scale = one) =
        BarRules.arrange(bar.freeLeft, bar.freeRight, bar.screenW, k.gap, out(pill, k), k.ends, between = k.gap, least = k.least,
            home = home, inUse = inUse, strips = strips)

    /** A strip on screen that draws something [measured] wide. */
    private fun drawing(measured: Int) = AreaState(measured, empty = false, shown = true, pending = false)

    /** A strip on screen with nothing to draw right now: its window is there and invisible. */
    private fun blank(measured: Int = 1) = AreaState(measured, empty = true, shown = true, pending = false)

    /** A strip with no window yet. */
    private fun waiting(measured: Int = 0) = AreaState(measured, empty = false, shown = false, pending = false)

    /**
     * What `BarController.place` set for the one strip before there were areas: its three lines for x, by gravity, and its
     * budget; the width is the one `applyWidth` set.
     */
    private fun today(area: Position, bar: Bar, measured: Int, pill: Boolean, k: Scale = one): Spot {
        val gap = k.gap
        val ends = k.ends
        val out = if (pill) 0 else ends
        val x = when (area) {
            RIGHT -> bar.screenW - bar.freeRight + gap - out
            LEFT -> bar.freeLeft + gap - out
            CENTER -> Box(bar.freeLeft, 0, bar.freeRight, 41).centerX() - bar.screenW / 2
        }
        val pillPad = if (pill) 2 * ends else 0
        return Spot(area, x, measured.coerceAtLeast(1), (bar.freeRight - bar.freeLeft - 2 * gap - pillPad).coerceAtLeast(0))
    }

    /** Where the window manager puts a spot's window: from the right edge, from the left edge, or centred and moved by x. */
    private fun Spot.left(screenW: Int) = when (area) {
        RIGHT -> screenW - x - width
        LEFT -> x
        CENTER -> (screenW - width) / 2 + x
    }

    /** For the sweeps: the message is put together only when the rule is broken. */
    private inline fun hold(ok: Boolean, say: () -> String) {
        if (!ok) fail(say())
    }

    // ---- one area: the strip as it was -------------------------------------------------------------------

    @Test fun oneAreaIsPlacedAsTheOneStripWasBeforeThereWereAreas() {
        // Wide bars with even and odd screens and free areas, and bars with less room than a ‹ needs, down to none.
        val bars = listOf(small, large, Bar(1921, 168, 1681), Bar(1920, 168, 1680), Bar(1920, 900, 930), Bar(1920, 900, 905), Bar(1920, 900, 900))
        for (bar in bars) for (k in listOf(one, dense)) for (pill in listOf(false, true)) for (area in Position.entries) {
            // Every width up to more than the free area holds: the one strip was never cut.
            for (measured in 0..bar.freeRight - bar.freeLeft + 60) {
                val expected = listOf(today(area, bar, measured, pill, k))
                // In use, or the home of a layout with no item; drawing, blank or not on screen yet.
                for (inUse in listOf(setOf(area), emptySet())) for (state in listOf(drawing(measured), blank(measured), waiting(measured))) {
                    val a = arrange(bar, area, inUse, mapOf(area to state), pill, k)
                    hold(a.spots == expected) { "$area on $bar, $measured wide, pill=$pill, $k, $state: ${a.spots} for $expected" }
                    hold(a.owed.isEmpty() && a.leave.isEmpty() && a.carrier == area) { "$area on $bar, $measured wide: $a" }
                }
            }
        }
    }

    /**
     * A centred strip that filled its room on a bar whose free middle is on half a pixel stood a pixel left of that room:
     * the rounding of a centred window. A Middle with nothing beside it has nothing to clear, so it stays there.
     */
    @Test fun aLoneMiddleThatFillsItsRoomStaysWhereRoundingPutTheOneStrip() {
        val spot = arrange(small, CENTER, setOf(CENTER), mapOf(CENTER to drawing(1513))).spots.single()
        assertEquals(today(CENTER, small, 1513, pill = false), spot)
        assertEquals("its room begins at 168", 167, spot.left(small.screenW))
    }

    // ---- no room for any area ----------------------------------------------------------------------------

    @Test fun withNoRoomForAnyAreaTheHomeAreaStillShowsItsChevronAsToday() {
        // 41 px of room without a pill, 29 with one: a ‹ and its two ends need 42.
        val narrow = Bar(1920, 900, 941)
        val strips = mapOf(RIGHT to drawing(300), LEFT to drawing(200), CENTER to drawing(250))
        for (pill in listOf(false, true)) for (home in Position.entries) {
            val a = arrange(narrow, home, all, strips, pill)
            // Not cut: its ‹ lies over its neighbours, as the one strip's did.
            assertEquals("home $home, pill=$pill", listOf(today(home, narrow, strips.getValue(home).measured, pill)), a.spots)
            assertEquals(all - home, a.owed)
            assertEquals(ROOM_ORDER - home, a.leave)
            assertEquals(home, a.carrier)
        }
        // A pixel more is room for a ‹, and room goes in room order: Left has the strip, whichever area is home.
        val a = arrange(Bar(1920, 900, 942), CENTER, all, strips)
        assertEquals(listOf(Spot(LEFT, 900, 200, 30)), a.spots)
        assertEquals(setOf(RIGHT, CENTER), a.owed)
    }

    // ---- Left, then Right, then Middle -------------------------------------------------------------------

    @Test fun rightGetsTheRoomLeftLeavesAndTheMiddleWhatBothLeave() {
        // Whichever area is home: who has the room first is not the home area's to say.
        for (home in Position.entries) {
            val a = arrange(small, home, all, mapOf(RIGHT to drawing(300), LEFT to drawing(200), CENTER to drawing(250)))
            assertEquals(listOf(LEFT, RIGHT, CENTER), a.spots.map { it.area })
            assertEquals(listOf(200, 300, 250), a.spots.map { it.width })
            // 1513 px in all; each strip's own ends (2 x 6) come off what it may fill, and a strip beside it costs its width and the gap.
            assertEquals(listOf(1513 - 12, 1513 - 206 - 12, 1513 - 206 - 306 - 12), a.spots.map { it.budget })
            // Left against the left end, Right against the right, the Middle where the one centred strip stood.
            assertEquals(listOf(168, 1381, 799), a.spots.map { it.left(small.screenW) })
            assertEquals(today(CENTER, small, 250, pill = false).x, a.spots[2].x)
            assertTrue(a.owed.isEmpty() && a.leave.isEmpty())
        }

        // An area's room is what the areas asked before it leave: nothing has been used when Left is asked, Left's when Right is.
        assertEquals(Lane(168, 1681), BarRules.lane(LEFT, 168, 1681, between = 6, usedRight = 0, usedLeft = 0))
        assertEquals(Lane(374, 1681), BarRules.lane(RIGHT, 168, 1681, between = 6, usedRight = 0, usedLeft = 200))
        assertEquals(Lane(374, 1375), BarRules.lane(CENTER, 168, 1681, between = 6, usedRight = 300, usedLeft = 200))
        assertEquals(1001, Lane(374, 1375).max)
        // A neighbour that takes no room asks for no gap either.
        assertEquals(Lane(168, 1681), BarRules.lane(CENTER, 168, 1681, between = 6, usedRight = 0, usedLeft = 0))
        assertEquals(Lane(168, 1681), BarRules.lane(RIGHT, 168, 1681, between = 6, usedRight = 0, usedLeft = 0))
        // What an area uses itself is not taken from its own room.
        assertEquals(Lane(168, 1681), BarRules.lane(LEFT, 168, 1681, between = 6, usedRight = 0, usedLeft = 200))
        assertEquals(Lane(374, 1681), BarRules.lane(RIGHT, 168, 1681, between = 6, usedRight = 300, usedLeft = 200))
    }

    @Test fun withoutLeftTheRightIsFirstAndHasTheWholeRoom() {
        val a = arrange(small, CENTER, setOf(RIGHT, CENTER), mapOf(RIGHT to drawing(300), CENTER to drawing(250)))
        assertEquals(listOf(today(RIGHT, small, 300, pill = false), Spot(CENTER, today(CENTER, small, 250, pill = false).x, 250, 1513 - 306 - 12)), a.spots)
        assertEquals(RIGHT, a.carrier)
    }

    @Test fun anAreaBesideOthersIsCutToItsRoomAndTheFirstNeverIs() {
        val cut = arrange(small, LEFT, setOf(RIGHT, LEFT), mapOf(LEFT to drawing(300), RIGHT to drawing(2000)))
        assertEquals(listOf(Spot(LEFT, 168, 300, 1501), Spot(RIGHT, 239, 1207, 1195)), cut.spots)
        // The first is as wide as it was measured, as the one strip is: the others then have no room left.
        val over = arrange(small, LEFT, setOf(RIGHT, LEFT), mapOf(LEFT to drawing(2000), RIGHT to drawing(200)))
        assertEquals(listOf(Spot(LEFT, 168, 2000, 1501)), over.spots)
        assertEquals(setOf(RIGHT), over.owed)
    }

    @Test fun anAreaWithLessRoomThanAChevronNeedsBesideOthersIsOwedAndItsWindowGoes() {
        // Of 1513 px, Left's 1465 and the gap leave Right exactly a ‹ and its ends (42); a pixel more leaves it too little.
        val enough = arrange(small, LEFT, setOf(RIGHT, LEFT), mapOf(LEFT to drawing(1465), RIGHT to drawing(200)))
        assertEquals(listOf(Spot(LEFT, 168, 1465, 1501), Spot(RIGHT, 239, 42, 30)), enough.spots)
        assertTrue(enough.owed.isEmpty() && enough.leave.isEmpty())

        val short = arrange(small, LEFT, setOf(RIGHT, LEFT), mapOf(LEFT to drawing(1466), RIGHT to drawing(200)))
        assertEquals(listOf(Spot(LEFT, 168, 1466, 1501)), short.spots)
        assertEquals(setOf(RIGHT), short.owed)
        assertEquals(listOf(RIGHT), short.leave)

        // Owed all the same while it has no window, and then no window has to go.
        val notUp = arrange(small, LEFT, setOf(RIGHT, LEFT), mapOf(LEFT to drawing(1466), RIGHT to waiting(200)))
        assertEquals(setOf(RIGHT), notUp.owed)
        assertTrue(notUp.leave.isEmpty())
    }

    @Test fun anAreaNoLongerInUseLosesItsWindowAndIsOwedNothing() {
        val a = arrange(small, RIGHT, setOf(RIGHT), mapOf(RIGHT to drawing(300), LEFT to drawing(200), CENTER to blank()))
        assertEquals(listOf(RIGHT), a.spots.map { it.area })
        assertEquals(listOf(LEFT, CENTER), a.leave)
        assertTrue(a.owed.isEmpty())
    }

    @Test fun anAreaInUseThatDrawsNothingKeepsItsSpotAndTakesNoRoom() {
        val a = arrange(small, LEFT, all, mapOf(LEFT to blank(300), RIGHT to drawing(200), CENTER to drawing(250)))
        assertEquals(Spot(LEFT, 168, 300, 1501), a.spots[0])
        assertEquals("the whole room, as if Left were not there", Spot(RIGHT, 239, 200, 1501), a.spots[1])
        assertEquals(1513 - 206 - 12, a.spots[2].budget)
        assertTrue(a.owed.isEmpty() && a.leave.isEmpty())
    }

    @Test fun widthsAreNeverBelowOneAndAStripNeverMeasuredTakesNoRoom() {
        // Its window is up and waits, invisible, for its first measure.
        val fresh = AreaState(0, empty = false, shown = true, pending = true)
        val a = arrange(small, LEFT, setOf(RIGHT, LEFT), mapOf(LEFT to fresh, RIGHT to drawing(200)))
        assertEquals(listOf(Spot(LEFT, 168, 1, 1501), Spot(RIGHT, 239, 200, 1501)), a.spots)
        // An area the controller has no state for yet is one never measured.
        assertEquals(listOf(Spot(LEFT, 168, 1, 1501), Spot(RIGHT, 239, 1, 1501)), arrange(small, LEFT, setOf(RIGHT, LEFT), emptyMap()).spots)
        // With no room for any area too.
        assertEquals(1, arrange(Bar(1920, 900, 930), LEFT, setOf(LEFT), emptyMap()).spots.single().width)
    }

    // ---- the first strip's reach -----------------------------------------------------------------------------

    /** A first strip that keeps [kept] px of items with a ‹ [chevron] px wide beside them (0: it draws none), and its reach. */
    private fun fitted(kept: Int, chevron: Int, k: Scale = one) =
        AreaState(kept + chevron + 2 * k.ends, empty = false, shown = true, pending = false, reach = kept + k.reserve + 2 * k.ends)

    /**
     * The first strip's ‹ can come and go for another area's sake: an area with no room owes it its items. If the others
     * stood behind what the first strip measures, that ‹ would move them, and an area could get room because it had none
     * and lose it because it had some. So they stand behind its reach: its width with a ‹'s whole room beside what it keeps,
     * which is the same number whether the ‹ is drawn or not.
     */
    @Test fun whatStandsBehindTheFirstStripIsTheSameWhetherItDrawsAChevronOrNot() {
        val narrow = Bar(1920, 600, 1200)
        var behindIt = 0
        for ((bar, inUse) in listOf(small to all, small to setOf(RIGHT, LEFT), narrow to all, narrow to setOf(RIGHT, CENTER)))
            for (kept in listOf(0, 1, 150, 188, 400, 560, 1400, 1471)) for (r in listOf(40, 250, 380, 2000)) for (m in listOf(40, 250)) {
                val first = ROOM_ORDER.first { it in inUse }
                val home = if (LEFT in inUse) LEFT else CENTER
                fun placed(state: AreaState) = arrange(bar, home, inUse, mapOf(RIGHT to drawing(r), CENTER to drawing(m)) + (first to state))
                val none = placed(fitted(kept, chevron = 0))
                val say = { "$first first on $bar keeping $kept, right $r, middle $m" }
                for (chevron in 1..one.reserve) {
                    val state = fitted(kept, chevron)
                    val a = placed(state)
                    hold(a.spots.drop(1) == none.spots.drop(1) && a.owed == none.owed) { "${say()}, a ‹ of $chevron: ${a.spots} ${a.owed} for ${none.spots} ${none.owed}" }
                    hold(a.spots.first().width == state.measured) { "${say()}: the first is as wide as it was measured, ${a.spots.first()}" }
                    // And nothing behind it comes nearer than the gap to what it really draws.
                    for (s in a.spots.drop(1)) {
                        behindIt++
                        val clear = if (first == RIGHT) hi(bar, pill = false) - state.measured - (s.left(bar.screenW) + s.width)
                        else s.left(bar.screenW) - (lo(bar, pill = false) + state.measured)
                        hold(clear >= one.gap) { "${say()}, a ‹ of $chevron: $s is $clear px from the first strip" }
                    }
                }
                // It is the room of a first strip exactly as wide as its reach.
                val wide = placed(drawing(fitted(kept, 0).reach))
                hold(wide.spots.drop(1) == none.spots.drop(1) && wide.owed == none.owed) { "${say()}: ${none.spots} for ${wide.spots}" }
                // A first strip with nothing to draw has no ends either, and its ‹ would bring them: the same reach.
                if (kept == 0) {
                    val quiet = placed(blank().copy(reach = fitted(0, 0).reach))
                    hold(quiet.spots.drop(1) == none.spots.drop(1) && quiet.owed == none.owed) { "${say()}, drawing nothing: ${quiet.spots} for ${none.spots}" }
                }
            }
        assertTrue("strips stood behind the first in the sweep", behindIt > 1_000)
    }

    @Test fun aFirstStripThatDrawsNothingKeepsItsReachClearOnceItIsKnown() {
        val strips = mapOf(LEFT to blank(), RIGHT to drawing(2000), CENTER to drawing(250))
        val a = arrange(small, LEFT, all, strips + (LEFT to blank().copy(reach = 42)))
        assertEquals(Spot(LEFT, 168, 1, 1501), a.spots[0])
        // Of 1513 px, a ‹ with its ends (42) and the gap are Left's.
        assertEquals(Spot(RIGHT, 239, 1465, 1453), a.spots[1])
        assertEquals("with no reach known it takes no room, as before", Spot(RIGHT, 239, 1513, 1501), arrange(small, LEFT, all, strips).spots[1])
    }

    @Test fun onlyTheFirstStripHasAReachAndWithNoRoomForAChevronNoStripHas() {
        val strips = mapOf(RIGHT to drawing(300), LEFT to drawing(200), CENTER to drawing(250))
        val plain = arrange(small, LEFT, all, strips)
        assertEquals("a strip behind the first takes what it measures", plain, arrange(small, LEFT, all, strips + (RIGHT to drawing(300).copy(reach = 900))))
        assertEquals(plain.spots.drop(1), arrange(small, LEFT, all, strips + (LEFT to drawing(170).copy(reach = 200))).spots.drop(1))
        val none = Bar(1920, 900, 930)
        assertEquals(arrange(none, LEFT, all, strips), arrange(none, LEFT, all, strips.mapValues { it.value.copy(reach = 400) }))
    }

    @Test fun theAreasThatWantAStripAreThoseInUseAndTheHomeInTheOrderRoomIsGiven() {
        for (area in Position.entries) assertEquals("one area: that one", listOf(area), BarRules.wantedAreas(area, setOf(area)))
        for (home in Position.entries) {
            assertEquals("whichever is home, room goes Left, Right, Middle", listOf(LEFT, RIGHT, CENTER), BarRules.wantedAreas(home, all))
            assertEquals("no item: the home area alone, so there is always one", listOf(home), BarRules.wantedAreas(home, emptySet()))
        }
        assertEquals(listOf(LEFT, CENTER), BarRules.wantedAreas(CENTER, setOf(CENTER, LEFT)))
        assertEquals("the home counts once", listOf(RIGHT, CENTER), BarRules.wantedAreas(RIGHT, setOf(CENTER, RIGHT)))
        assertEquals("a home that is not in use is wanted as well", listOf(LEFT, RIGHT), BarRules.wantedAreas(RIGHT, setOf(LEFT)))
    }

    /** The bar and the settings preview ask the one list, so the preview has a strip exactly where the bar wants one. */
    @Test fun whatIsPlacedOrOwedIsExactlyWhatIsWanted() {
        val strips = mapOf(RIGHT to drawing(300), LEFT to drawing(2000), CENTER to drawing(250))
        for (home in Position.entries) for (inUse in listOf(emptySet(), setOf(LEFT), setOf(RIGHT, LEFT), setOf(LEFT, CENTER), all)) {
            val a = arrange(small, home, inUse, strips)
            assertEquals("$home $inUse", BarRules.wantedAreas(home, inUse), ROOM_ORDER.filter { area -> a.spots.any { it.area == area } || area in a.owed })
        }
    }

    /** With every item in one area nothing stands behind the strip: whatever its reach, it is the strip as it was. */
    @Test fun oneAreaIsPlacedTheSameWhateverItsReach() {
        for (bar in listOf(small, large, Bar(1920, 900, 930))) for (area in Position.entries) for (measured in listOf(0, 1, 300, 1513, 2100))
            for (state in listOf(drawing(measured), blank(measured), waiting(measured))) for (reach in listOf(1, measured + 30, 5000)) {
                val a = arrange(bar, area, setOf(area), mapOf(area to state.copy(reach = reach)))
                hold(a == arrange(bar, area, setOf(area), mapOf(area to state))) { "$area on $bar, $state, reach $reach: $a" }
                hold(a.spots == listOf(today(area, bar, measured, pill = false))) { "$area on $bar, $state, reach $reach: ${a.spots}" }
            }
    }

    // ---- the Middle --------------------------------------------------------------------------------------

    @Test fun theMiddleStaysCentredUntilRightReachesItAndThenMovesAPixelForEachOfRights() {
        val lo = lo(small, pill = false)
        val hi = hi(small, pill = false)
        val centred = today(CENTER, small, 400, pill = false).x
        val naturalRight = (small.screenW - 400) / 2 + centred + 400
        var last: Int? = null
        var pushed = 0
        var gone = false
        for (r in 0..1513) {
            val a = arrange(small, RIGHT, setOf(RIGHT, CENTER), mapOf(RIGHT to drawing(r), CENTER to drawing(400)))
            // Where the Middle's room ends: Right's left edge and the gap, or the free area's end while Right is not measured.
            val room = hi - (if (r > 0) r + one.gap else 0)
            val mid = a.spots.firstOrNull { it.area == CENTER }
            if (mid == null) {
                hold(room - lo < one.least && CENTER in a.owed) { "Right $r wide: the Middle went with ${room - lo} px of room: $a" }
                gone = true
                continue
            }
            hold(!gone) { "Right $r wide: the Middle came back" }
            val left = mid.left(small.screenW)
            hold(left >= lo && left + mid.width <= room) { "Right $r wide: the Middle at $left, ${mid.width} wide, leaves $lo..$room" }
            if (naturalRight <= room) hold(mid.x == centred && mid.width == 400) { "Right $r wide: the Middle touches nothing and moved: $mid" }
            // Against Right's gap and no further: not centred again in what is left.
            else { pushed++; hold(left + mid.width == room) { "Right $r wide: the Middle ends at ${left + mid.width}, its room at $room" } }
            last?.let { hold(abs(mid.x - it) <= 1) { "Right $r wide: the Middle's x jumped from $it to ${mid.x}" } }
            last = mid.x
        }
        assertTrue("Right reached the Middle", pushed > 0)
        assertTrue("and then left it no room", gone)
    }

    @Test fun theMiddleMovesOnlyAsFarAsItMustToClearLeft() {
        val lo = lo(small, pill = false)
        val hi = hi(small, pill = false)
        val centred = today(CENTER, small, 400, pill = false).x
        val naturalLeft = (small.screenW - 400) / 2 + centred
        var last: Int? = null
        var pushed = 0
        for (l in 0..1513) {
            val a = arrange(small, CENTER, setOf(LEFT, CENTER), mapOf(LEFT to drawing(l), CENTER to drawing(400)))
            val room = lo + (if (l > 0) l + one.gap else 0)
            val mid = a.spots.firstOrNull { it.area == CENTER }
            if (mid == null) { hold(hi - room < one.least && CENTER in a.owed) { "Left $l wide: the Middle went with ${hi - room} px of room" }; continue }
            val left = mid.left(small.screenW)
            hold(left >= room && left + mid.width <= hi) { "Left $l wide: the Middle at $left, ${mid.width} wide, leaves $room..$hi" }
            if (naturalLeft >= room) hold(mid.x == centred && mid.width == 400) { "Left $l wide: the Middle touches nothing and moved: $mid" }
            else { pushed++; hold(left == room) { "Left $l wide: the Middle begins at $left, its room at $room" } }
            last?.let { hold(abs(mid.x - it) <= 1) { "Left $l wide: the Middle's x jumped from $it to ${mid.x}" } }
            last = mid.x
        }
        assertTrue("Left reached the Middle", pushed > 0)
    }

    @Test fun aMiddleBetweenTwoWideNeighboursIsCutToTheRoomBetweenThem() {
        val a = arrange(small, RIGHT, all, mapOf(RIGHT to drawing(500), LEFT to drawing(700), CENTER to drawing(400)))
        // Its room is 874..1175: 301 px, where centred it would begin at 773.
        assertEquals(Spot(CENTER, 65, 301, 289), a.spots[2])
        assertEquals(874, a.spots[2].left(small.screenW))
    }

    @Test fun middleLeftKeepsTheCentredPlaceWithinItsRoomAndLeavesAStripTooWideForItAlone() {
        val lane = Lane(100, 500)
        assertEquals(250, BarRules.middleLeft(lane, width = 100, natural = 250))
        assertEquals(100, BarRules.middleLeft(lane, width = 100, natural = 60))
        assertEquals(400, BarRules.middleLeft(lane, width = 100, natural = 470))
        assertEquals("it fills its room", 100, BarRules.middleLeft(lane, width = 400, natural = 99))
        assertEquals("wider than its room: nowhere to keep it", 60, BarRules.middleLeft(lane, width = 401, natural = 60))
    }

    // ---- who carries, the first placing ----------------------------------------------

    @Test fun theCarrierIsTheFirstSpotInRoomOrder() {
        val strips = mapOf(RIGHT to drawing(300), LEFT to drawing(200), CENTER to drawing(250))
        assertEquals(LEFT, arrange(small, LEFT, all, strips).carrier)
        assertEquals(RIGHT, arrange(small, CENTER, setOf(RIGHT, CENTER), strips).carrier)
        assertEquals(CENTER, arrange(small, CENTER, setOf(CENTER), strips).carrier)
        // Left holds nothing right now and carries all the same: it has a window.
        assertEquals(LEFT, arrange(small, LEFT, all, strips + (LEFT to blank())).carrier)
        // With no room for any area the one strip is the home area's.
        assertEquals(CENTER, arrange(Bar(1920, 900, 930), CENTER, setOf(LEFT, CENTER), strips).carrier)
    }

    @Test fun firstIsTrueExactlyWhenNoStripWasShownBefore() {
        assertTrue(arrange(small, RIGHT, all, emptyMap()).first)
        assertTrue(arrange(small, RIGHT, all, mapOf(RIGHT to waiting(300), LEFT to waiting(200), CENTER to waiting())).first)
        assertFalse(arrange(small, RIGHT, all, mapOf(RIGHT to waiting(300), LEFT to drawing(200))).first)
        assertFalse("an invisible window is a window", arrange(small, RIGHT, all, mapOf(RIGHT to blank())).first)
        // The one shown is of an area that is leaving: the bar was on screen all the same.
        assertFalse(arrange(small, RIGHT, setOf(RIGHT), mapOf(LEFT to drawing(200))).first)
    }

    // ---- every input -------------------------------------------------------------------------------------

    private val subsets = (0 until 8).map { bits -> Position.entries.filterIndexed { i, _ -> bits shr i and 1 == 1 }.toSet() }

    @Test fun thereIsAStripForEveryFreeWidthAndEverySetOfAreas() {
        val strips = mapOf(RIGHT to drawing(120), LEFT to drawing(80), CENTER to drawing(200))
        for (w in 0..600) for (inUse in subsets) for (home in Position.entries) for (pill in listOf(false, true)) {
            for (known in listOf(strips, emptyMap())) {
                val bar = Bar(1920, 700, 700 + w)
                val a = arrange(bar, home, inUse, known, pill)
                val say = { "free $w wide, in use $inUse, home $home, pill=$pill: $a" }
                val wanted = inUse + home
                val placed = a.spots.map { it.area }
                hold(placed.isNotEmpty(), say)
                hold(placed == ROOM_ORDER.filter { it in placed } && placed.all { it in wanted }, say)
                hold(a.carrier == placed.first(), say)
                hold(a.owed == wanted - placed.toSet(), say)
                hold(a.leave == ROOM_ORDER.filter { known[it]?.shown == true && it !in placed }, say)
                hold(a.spots.all { it.width >= 1 && it.budget >= 0 }, say)
                // No room for a ‹: the home area alone. Room for one: the first wanted area in room order is always there.
                val total = maxOf(0, hi(bar, pill) - lo(bar, pill))
                hold(placed.first() == if (total < one.least) home else ROOM_ORDER.first { it in wanted }, say)
                hold(total >= one.least || placed.size == 1, say)
            }
        }
    }

    // ---- the order in which the spots are carried out ------------------------------------------------------

    /** Right with a timer just started: 46 px wider, and the Middle that stood against it 46 px further left. */
    private val grown = listOf(Spot(LEFT, 82, 131, 733), Spot(RIGHT, 112, 942, 1714), Spot(CENTER, -224, 182, 596))

    @Test fun aStripThatGetsWiderTakesItsRoomAfterTheStripsThatMoveOrNarrowForIt() {
        val order = BarRules.givingWayFirst(grown, mapOf(RIGHT to 896, LEFT to 131, CENTER to 182))
        assertEquals(listOf(LEFT, CENTER, RIGHT), order.map { it.area })
        assertEquals("the same spots", grown.toSet(), order.toSet())
        // Left grows and the Middle narrows for it: the Middle first, though Left comes before it in room order.
        val left = listOf(Spot(LEFT, 82, 700, 1400), Spot(RIGHT, 112, 300, 1714), Spot(CENTER, 40, 120, 108))
        assertEquals(listOf(RIGHT, CENTER, LEFT), BarRules.givingWayFirst(left, mapOf(RIGHT to 300, LEFT to 400, CENTER to 250)).map { it.area })
    }

    @Test fun aStripThatComesTakesItsRoomAfterTheStripsThatAreThere() {
        val order = BarRules.givingWayFirst(grown, mapOf(RIGHT to 942, CENTER to 250))
        assertEquals(listOf(RIGHT, CENTER, LEFT), order.map { it.area })
    }

    @Test fun stripsThatGetNoWiderAndStripsThatAllGetWiderArePlacedInRoomOrder() {
        // Right narrows again and the Middle moves back: Right has left the room before the Middle stands in it.
        assertEquals(grown, BarRules.givingWayFirst(grown, mapOf(RIGHT to 988, LEFT to 131, CENTER to 182)))
        assertEquals(grown, BarRules.givingWayFirst(grown, mapOf(RIGHT to 942, LEFT to 131, CENTER to 182)))
        // The bar comes back on screen: no window yet, and the first strip's is the first to come.
        assertEquals(grown, BarRules.givingWayFirst(grown, emptyMap()))
        assertEquals(grown, BarRules.givingWayFirst(grown, mapOf(RIGHT to 1, LEFT to 1, CENTER to 1)))
    }

    @Test fun noTwoStripsThatTakeRoomOverlapAndNoneLeavesTheFreeSpace() {
        val widths = listOf(0, 1, 40, 150, 400)
        var pairs = 0
        for (w in 42..600 step 9) for (inUse in subsets.filter { it.isNotEmpty() }) for (pill in listOf(false, true))
            for (r in widths) for (l in widths) for (m in widths) for (blankLeft in listOf(false, true)) {
                val bar = Bar(1921, 700, 700 + w)
                val lo = lo(bar, pill)
                val hi = hi(bar, pill)
                if (hi - lo < one.least) continue
                val strips = mapOf(RIGHT to drawing(r), LEFT to if (blankLeft) blank(l) else drawing(l), CENTER to drawing(m))
                val a = arrange(bar, ROOM_ORDER.first { it in inUse }, inUse, strips, pill)
                val say = { "free $w wide, in use $inUse, pill=$pill, right $r, left $l${if (blankLeft) " (blank)" else ""}, middle $m: $a" }
                a.spots.forEachIndexed { i, s ->
                    val left = s.left(bar.screenW)
                    // The first is never cut, as the one strip never was: wider than the room, it lies over its neighbours.
                    val uncut = i == 0 && strips.getValue(s.area).measured > hi - lo
                    // And a lone Middle keeps the pixel rounding gave the one centred strip.
                    val slack = if (i == 0 && s.area == CENTER) 1 else 0
                    hold(uncut || (left >= lo - slack && left + s.width <= hi), say)
                }
                // Strips that take room keep the gap between them.
                val solid = a.spots.filter { strips.getValue(it.area).let { st -> !st.empty && st.measured > 0 } }.sortedBy { it.left(bar.screenW) }
                solid.zipWithNext { x, y -> pairs++; hold(y.left(bar.screenW) - (x.left(bar.screenW) + x.width) >= one.gap, say) }
            }
        assertTrue("strips stood side by side in the sweep", pairs > 10_000)
    }
}
