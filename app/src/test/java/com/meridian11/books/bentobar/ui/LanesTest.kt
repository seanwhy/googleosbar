package com.meridian11.books.bentobar.ui

import com.meridian11.books.bentobar.data.BarConfig
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.data.Position
import com.meridian11.books.bentobar.data.Section
import com.meridian11.books.bentobar.data.landedIn
import com.meridian11.books.bentobar.data.putAllIn
import com.meridian11.books.bentobar.data.rowAreas
import com.meridian11.books.bentobar.data.without
import com.meridian11.books.bentobar.data.withoutArea
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which areas the settings window has on screen, where a key leads from one, and when one closes. */
class LanesTest {
    private val left = Position.LEFT
    private val middle = Position.CENTER
    private val right = Position.RIGHT
    private fun item(id: String, area: Position, section: Section = Section.SHOWN) = ItemConfig(id, "cpu", section, area = area)
    private fun layout(vararg items: ItemConfig) = BarConfig(items = items.toList(), position = Position.RIGHT)

    @Test fun theLanesOnScreenAreTheAreasWithRowsAndTheOnesOpenedInBarOrder() {
        val one = layout(item("a", right))
        assertEquals(listOf(right), lanesOnScreen(one, emptySet()))
        assertEquals(listOf(left, right), lanesOnScreen(one, setOf(left)))
        assertEquals(listOf(left, middle, right), lanesOnScreen(one, setOf(middle, left)))
        assertEquals(listOf(left, right), lanesOnScreen(layout(item("a", right), item("b", left, Section.HIDDEN)), emptySet()))
        assertEquals("an opened area that has rows is counted once", listOf(left, right), lanesOnScreen(layout(item("a", right), item("b", left)), setOf(left)))
        // With nothing in the bar the list is where a new item goes, and an opened area stands beside it.
        assertEquals(listOf(right), lanesOnScreen(layout(), emptySet()))
        assertEquals(listOf(middle, right), lanesOnScreen(layout(), setOf(middle)))
    }

    @Test fun aKeyLeadsToTheNextLaneOnThatSideAndNowhereAtTheEnd() {
        val all = listOf(left, middle, right)
        assertEquals(middle, laneBeside(all, left, right = true))
        assertEquals(right, laneBeside(all, middle, right = true))
        assertNull(laneBeside(all, right, right = true))
        assertEquals(middle, laneBeside(all, right, right = false))
        assertNull(laneBeside(all, left, right = false))
        // An area with no lane is passed over: it is reached with Area or with Add an area.
        assertEquals(right, laneBeside(listOf(left, right), left, right = true))
        assertEquals(left, laneBeside(listOf(left, right), right, right = false))
        assertNull("one lane leads nowhere", laneBeside(listOf(right), right, right = false))
        assertNull(laneBeside(listOf(left, right), middle, right = true))
    }

    @Test fun anEmptyLaneSaysTheKeyThatReachesItFromWhereTheItemsAre() {
        assertEquals("Left is reached going left", false, leadsRight(listOf(left, right), left))
        assertEquals(true, leadsRight(listOf(left, right), right))
        assertEquals("the Middle from Right", false, leadsRight(listOf(middle, right), middle))
        assertEquals("the Middle from Left", true, leadsRight(listOf(left, middle), middle))
        assertEquals("between two, from the side the bar fills first", true, leadsRight(listOf(left, middle, right), middle))
        assertEquals("one lane leads nowhere", false, leadsRight(listOf(middle), middle))
    }

    @Test fun looksCommandClosesLanesWithoutASlipAndMarksNoRow() {
        // Left's one item goes to Right with it: a lane closed and one row moved, which a move on the Bar page would say.
        val two = layout(item("l", left), item("r1", right), item("r2", right))
        val all = two.putAllIn(right)
        assertEquals(left, foldOf(Undo.Kept(two, all), all, several = true))
        assertNull("Look says it itself", foldOf(Undo.Kept(two, all, allTo = right), all, several = true))
        assertNull(movedBy(Undo.Kept(two, all, allTo = right)))
    }

    @Test fun aLaneClosesWhenItsLastItemMovesAwayOrIsRemovedAndOnlyThen() {
        val two = layout(item("l", left), item("r1", right), item("r2", right))
        fun kept(after: BarConfig, removed: String? = null) = Undo.Kept(two, after, removed)
        val moved = two.landedIn("l", right)
        assertEquals(left, foldOf(kept(moved), moved, several = true))
        assertEquals("l", movedBy(kept(moved)))
        val gone = two.without("l")
        assertEquals(left, foldOf(kept(gone, "l"), gone, several = true))
        assertNull("a removed item moved nowhere", movedBy(kept(gone, "l")))
        // An item that leaves a lane with others in it closes nothing.
        val still = two.landedIn("r1", left)
        assertNull(foldOf(kept(still), still, several = true))
        assertNull(foldOf(null, two, several = true))
        // Arranged otherwise since: the change can no longer be taken back, and nothing is said about it.
        assertNull(foldOf(kept(moved), moved.landedIn("r1", middle), several = true))
    }

    @Test fun removeAnAreaSaysWhatWentItselfAndNoLaneIsSaidToHaveClosed() {
        val two = layout(item("l1", left), item("r1", right), item("l2", left, Section.HIDDEN), item("l3", left))
        val rest = two.withoutArea(left)
        val kept = Undo.Kept(two, rest, area = left)
        assertEquals(Gone(left, 3), goneOf(kept, rest))
        assertNull("not also \"Left is empty now, so it closed\"", foldOf(kept, rest, several = true))
        assertNull(movedBy(kept))
        assertNull("nor a removed row's slip: no one item was removed", slipOf(kept, rest))
        // The same two layouts from the one row's Remove are a lane that closed, and no area removed.
        val one = layout(item("l", left), item("r", right))
        val last = Undo.Kept(one, one.without("l"), removed = "l")
        assertNull(goneOf(last, last.after))
        assertEquals(left, foldOf(last, last.after, several = true))
        // Arranged otherwise since, or no change at all: nothing is said.
        assertNull(goneOf(kept, rest.landedIn("r1", middle)))
        assertNull(goneOf(null, rest))
        // The bar's last items went with it: it is said all the same (a closed lane would not be).
        val all = layout(item("a", middle), item("b", middle))
        val none = all.withoutArea(middle)
        assertEquals(Gone(middle, 2), goneOf(Undo.Kept(all, none, area = middle), none))
    }

    @Test fun withOneLaneOnScreenNothingClosesAndAnOpenedLaneCountsAsOne() {
        // The bar's last row keeps its place with Undo in it: the list is not a lane that closed.
        val one = layout(item("a", right))
        val gone = one.without("a")
        assertNull(foldOf(Undo.Kept(one, gone, "a"), gone, several = false))
        assertNull("nor beside an opened lane: nothing is left in the bar", foldOf(Undo.Kept(one, gone, "a"), gone, several = true))
        // An item that takes its area with it to another closes none: there was one lane and there is one.
        val over = one.landedIn("a", left)
        assertNull(foldOf(Undo.Kept(one, over), over, several = false))
        // Into a lane that was opened and still empty, the one it left closes.
        assertEquals(right, foldOf(Undo.Kept(one, over), over, several = true))
    }

    @Test fun anAreaHasNoRoomWhenAllItsStripWouldDrawDoesNotFitAndItIsNotTheFirstToTakeTheSpace() {
        val cfg = layout(item("r", right), item("l", left), item("m1", middle), item("m2", middle))
        val none: (ItemConfig) -> Boolean = { false }
        assertEquals(setOf(middle), noRoomAreas(cfg, setOf("m1", "m2"), none))
        assertEquals("one of its items is in the bar: its rows say which are not", emptySet<Position>(), noRoomAreas(cfg, setOf("m1"), none))
        assertEquals(setOf(right, middle), noRoomAreas(cfg, setOf("r", "m1", "m2"), none))
        assertEquals("Left takes the space first: what does not fit there is behind its arrow", emptySet<Position>(), noRoomAreas(cfg, setOf("l"), none))
        assertEquals(emptySet<Position>(), noRoomAreas(cfg, emptySet(), none))
        assertEquals("with one area in use there is no lane to say it in", emptySet<Position>(), noRoomAreas(layout(item("m1", middle)), setOf("m1"), none))
        // The areas its notice names: the ones in use that take the space before it.
        assertEquals(listOf(left, right), roomAhead(cfg, middle))
        assertEquals(listOf(left), roomAhead(cfg, right))
        assertEquals(listOf(right), roomAhead(layout(item("r", right), item("m", middle)), middle))
        assertEquals(emptyList<Position>(), roomAhead(cfg, left))
    }

    /**
     * An item of a type this version doesn't know has a row and a lane, but takes no room in the bar ([areasInUse]): with
     * one alone in Left the bar's first strip is Right's, so what Right can't fit is behind Right's own ‹, and the page
     * never says Right has no room; nor does Middle's notice name Left.
     */
    @Test fun anItemOfATypeThisVersionDoesntKnowHasALaneButTakesNoRoom() {
        val newer = ItemConfig("u", "from-a-newer-version", area = left)
        val cfg = layout(newer, item("r", right), item("m1", middle), item("m2", middle))
        val none: (ItemConfig) -> Boolean = { false }
        assertEquals(listOf(left, middle, right), lanesOnScreen(cfg, emptySet()))
        assertEquals(listOf(left, middle, right), cfg.rowAreas())
        assertEquals("Right is the bar's first strip", setOf(middle), noRoomAreas(cfg, setOf("r", "m1", "m2"), none))
        assertEquals(listOf(right), roomAhead(cfg, middle))
        assertEquals(emptyList<Position>(), roomAhead(cfg, right))
        // With nothing else in use there is one area, and no lane to say it in.
        assertEquals(emptySet<Position>(), noRoomAreas(layout(newer, item("m1", middle)), setOf("m1"), none))
    }
}
