package com.meridian11.books.bentobar.data

import com.meridian11.books.bentobar.SourceScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** One command places an item one way, whichever surface asks. */
class LayoutIntentsTest {
    private val shown = ItemConfig("s", "clock", Section.SHOWN)
    private val shown2 = ItemConfig("s2", "clock", Section.SHOWN)
    private val hidden = ItemConfig("h", "clock", Section.HIDDEN)
    private val layout = BarConfig(items = listOf(shown, shown2, hidden))
    private fun BarConfig.ids() = items.map { it.id }
    private fun BarConfig.at(id: String) = items.single { it.id == id }

    @Test fun aCommandChangesAnItemsSectionNeverItsPlaceInTheList() {
        val hid = layout.hide("s")
        assertEquals(Section.HIDDEN, hid.at("s").section)
        assertEquals(layout.ids(), hid.ids())
        val back = layout.show("h")
        assertEquals(Section.SHOWN, back.at("h").section)
        assertEquals(layout.ids(), back.ids())
        val chosen = layout.putIn("s2", Section.HIDDEN)
        assertEquals(Section.HIDDEN, chosen.at("s2").section)
        assertEquals(layout.ids(), chosen.ids())
    }

    @Test fun inShowEverythingAHideThatHidesNothingMovesNothingAlongTheStrip() {
        // The default mode draws Hidden items, so an item that jumped to the section's end would jump along the strip.
        val c = BarConfig(items = listOf(shown, hidden.copy(section = Section.SHOWN), shown2))
        assertEquals(HiddenMode.SHOW_ALL, c.hiddenMode)
        val before = c.stripPlan(c.homeArea(), { false }, expanded = false, overflow = false).visible.map { it.id }
        assertEquals(before, c.hide("h").stripPlan(c.homeArea(), { false }, expanded = false, overflow = false).visible.map { it.id })
    }

    @Test fun hideThenShowLeavesAnItemWhereItWas() {
        assertEquals(layout.ids(), layout.hide("s").show("s").ids())
    }

    @Test fun onlyAnExplicitMoveChangesAnItemsPlace() {
        assertEquals(listOf("s2", "s", "h"), layout.copy(items = layout.items.moved("s", Section.HIDDEN, 0)).ids())
    }

    @Test fun aCommandForTheSectionAnItemIsAlreadyInMovesNothing() {
        // The settings' Where chooser asks for the section whichever chip is clicked: the selected one must not reorder.
        assertSame(layout, layout.show("s"))
        assertSame(layout, layout.hide("h"))
    }

    @Test fun anIdThatIsNotInTheLayoutChangesNothing() {
        assertSame(layout, layout.hide("nope"))
        assertEquals(layout, layout.setWhenActive("nope", true))
    }

    @Test fun theRuleBringsAShownItemIntoHiddenWhereItIs() {
        // Both the bar's menu and settings do this: where it is, because it pops out at the place it had in the bar.
        val fromShown = layout.setWhenActive("s", true)
        assertEquals(Section.HIDDEN, fromShown.at("s").section)
        assertTrue(fromShown.at("s").whenActive)
        assertEquals(layout.ids(), fromShown.ids())
    }

    @Test fun turningTheRuleOffLeavesAnItemWhereItIs() {
        val c = layout.setWhenActive("h", true).setWhenActive("h", false)
        assertEquals(Section.HIDDEN, c.at("h").section)
        assertEquals(false, c.at("h").whenActive)
        val stayed = layout.copy(items = layout.items.map { if (it.id == "s") it.copy(whenActive = true) else it }).setWhenActive("s", false)
        assertEquals(Section.SHOWN, stayed.at("s").section)
    }

    @Test fun anItemsRuleStaysOnThroughACommandThatChangesItsSection() {
        val rule = layout.setWhenActive("h", true)
        assertTrue(rule.show("h").at("h").whenActive)
    }

    @Test fun puttingAnItemInAnAreaChangesOnlyThatItemsAreaAndNeverItsPlaceInTheList() {
        val moved = layout.putInArea("s2", Position.LEFT)
        assertEquals(Position.LEFT, moved.at("s2").area)
        assertEquals(layout.ids(), moved.ids())
        // Everything else of the layout, and of the item, is as it was.
        assertEquals(layout, moved.copy(items = moved.items.map { if (it.id == "s2") it.copy(area = Position.RIGHT) else it }))
        // A Hidden item has an area as any other: it is where it pops out.
        assertEquals(Position.CENTER, layout.putInArea("h", Position.CENTER).at("h").area)
    }

    @Test fun puttingAnItemInTheAreaItIsInOrAnIdThatIsNotThereChangesNothing() {
        assertSame(layout, layout.putInArea("s", Position.RIGHT))
        assertSame(layout, layout.putInArea("nope", Position.LEFT))
    }

    @Test fun puttingAllInOneAreaChangesEveryItemsAreaHiddenOnesTooAndNothingElse() {
        val spread = layout.putInArea("s", Position.LEFT).putInArea("h", Position.CENTER)
        val all = spread.putAllIn(Position.LEFT)
        assertEquals(listOf(Position.LEFT, Position.LEFT, Position.LEFT), all.items.map { it.area })
        assertEquals(layout.ids(), all.ids())
        assertEquals(layout, all.putAllIn(Position.RIGHT))
        assertSame(all, all.putAllIn(Position.LEFT))
    }

    @Test fun withItemsNeitherAreaCommandTouchesTheOldPosition() {
        // The migration reads it, and a layout with no items: with items, the areas are theirs and it says nothing.
        val old = layout.copy(position = Position.CENTER)
        assertEquals(Position.CENTER, old.putInArea("s", Position.LEFT).position)
        assertEquals(Position.CENTER, old.putAllIn(Position.LEFT).position)
    }

    /** With no items there is no area to change: the place is kept in `position`, which says where an empty bar is. */
    @Test fun puttingAllInAnAreaOnALayoutWithNoItemsSaysWhereTheEmptyBarIs() {
        for (area in Position.entries) {
            val moved = BarConfig().putAllIn(area)
            assertEquals(BarConfig(position = area), moved)
            assertEquals(area, moved.oneArea())
            assertEquals(area, moved.homeArea())
            assertSame("already there: nothing changes", moved, moved.putAllIn(area))
        }
    }

    /**
     * With items, `position` says nothing and no area command writes it, so it can be out of date. Taking the last item
     * away must not send the empty bar, and the next item, back to where it last said: the bar stays where it was.
     */
    @Test fun takingTheLastItemAwayLeavesTheEmptyBarWhereTheBarWas() {
        val one = BarConfig(items = listOf(shown))
        for (area in Position.entries) {
            for (moved in listOf(one.putAllIn(area), one.putInArea("s", area))) {
                assertEquals("no area command writes it while there are items", Position.RIGHT, moved.position)
                val empty = moved.without("s")
                assertEquals(BarConfig(position = area), empty)
                assertEquals(area, empty.homeArea())
                assertEquals(area, empty.oneArea())
            }
        }
        // The last one being Hidden changes nothing.
        assertEquals(Position.LEFT, BarConfig(items = listOf(hidden.copy(area = Position.LEFT))).without("h").position)
    }

    @Test fun takingAnItemAwayWhileOthersStayChangesNothingElse() {
        val spread = layout.copy(position = Position.CENTER).putInArea("s", Position.LEFT)
        val rest = spread.without("s")
        assertEquals(spread.copy(items = spread.items.filterNot { it.id == "s" }), rest)
        assertEquals(Position.CENTER, rest.position)
        assertSame("an id that isn't in the layout changes nothing", spread, spread.without("nope"))
    }

    // ---- Remove an area: every item of it, in one step ----

    @Test fun removingAnAreaTakesEveryItemOfItAndLeavesTheOthersAsTheyWere() {
        val unknown = ItemConfig("u", "a-type-from-later", Section.SHOWN, area = Position.LEFT)
        val spread = BarConfig(position = Position.CENTER, items = listOf(
            shown.copy(area = Position.LEFT), shown2, hidden.copy(area = Position.LEFT, whenActive = true), unknown,
            ItemConfig("m", "clock", area = Position.CENTER)))
        val rest = spread.withoutArea(Position.LEFT)
        assertEquals("an item of a type this version doesn't know has its row there, and goes too", listOf("s2", "m"), rest.ids())
        assertEquals(spread.copy(items = spread.items.filter { it.area != Position.LEFT }), rest)
        assertEquals(listOf(Position.CENTER, Position.RIGHT), rest.rowAreas())
        assertEquals("with items left, nothing else is written", Position.CENTER, rest.position)
        // The step is taken back as any other: every item where it was, in its part and order.
        assertEquals(spread, rest.undone(spread, rest))
    }

    @Test fun removingAnAreaWithNothingInItChangesNothing() {
        assertSame(layout, layout.withoutArea(Position.LEFT))
        val empty = BarConfig(position = Position.LEFT)
        assertSame(empty, empty.withoutArea(Position.LEFT))
    }

    /** As [without] leaves it when the last item goes: the empty bar stands where the bar was, whatever `position` last said. */
    @Test fun removingTheAreaThatHoldsTheBarsLastItemsLeavesTheEmptyBarWhereTheBarWas() {
        for (area in Position.entries) {
            val all = layout.putAllIn(area)
            assertEquals(Position.RIGHT, all.position)
            val empty = all.withoutArea(area)
            assertEquals(BarConfig(position = area), empty)
            assertEquals(area, empty.homeArea())
            assertEquals(all, empty.undone(all, empty))
        }
    }

    // ---- Order and Visibility, as the bar's list asks: a place in a part of an area ----

    private val a = ItemConfig("a", "clock")
    private val b = ItemConfig("b", "cpu")
    private val cal = ItemConfig("cal", "calendar")
    private val w = ItemConfig("w", "timer", Section.HIDDEN, whenActive = true)
    private val w2 = ItemConfig("w2", "memory", Section.HIDDEN, whenActive = true)
    private val meeting = ItemConfig("e", "event", Section.HIDDEN, whenActive = true)
    private val bar = BarConfig(items = listOf(meeting, a, b, cal, w, w2))
    private fun BarConfig.rows() = parts(Position.RIGHT).let { p -> p.upper.map { it.id } to p.lower.map { it.id } }
    private fun BarConfig.step(id: String, down: Boolean) = (stepped(id, down) as Step.Moved).config

    @Test fun aPlaceIsAnIndexAmongThePartsOtherRows() {
        assertEquals(listOf("a", "b", "cal") to listOf("e", "w", "w2"), bar.rows())
        assertEquals(listOf("cal", "a", "b"), bar.placed("cal", Position.RIGHT, Part.UPPER, 0).rows().first)
        assertEquals(listOf("b", "a", "cal"), bar.placed("a", Position.RIGHT, Part.UPPER, 1).rows().first)
        assertEquals(listOf("b", "cal", "a"), bar.placed("a", Position.RIGHT, Part.UPPER, 99).rows().first)
        assertSame(bar, bar.placed("a", Position.RIGHT, Part.UPPER, 0))
        assertSame(bar, bar.placed("nobody", Position.RIGHT, Part.UPPER, 0))
        // Only the moved item changes its place in the list.
        assertEquals(bar.items.filter { it.id != "a" }, bar.placed("a", Position.RIGHT, Part.UPPER, 2).items.filter { it.id != "a" })
    }

    @Test fun underTheLineIsTheRuleInTheDefaultModeAndHiddenWithTheChevron() {
        val below = bar.placed("a", Position.RIGHT, Part.LOWER, 2)
        assertEquals(listOf("b", "cal") to listOf("e", "w", "a", "w2"), below.rows())
        assertEquals(Section.HIDDEN, below.at("a").section)
        assertTrue(below.at("a").whenActive)
        val click = bar.copy(hiddenMode = HiddenMode.CLICK).placed("a", Position.RIGHT, Part.LOWER, 99)
        assertEquals(listOf("b", "cal") to listOf("e", "w", "w2", "a"), click.rows())
        assertEquals(Section.HIDDEN, click.at("a").section)
        assertFalse("behind ‹ it only waits; popping out is its own switch", click.at("a").whenActive)
        // Back above the line it is Shown.
        assertEquals(Section.SHOWN, below.placed("a", Position.RIGHT, Part.UPPER, 0).at("a").section)
    }

    @Test fun aTypeWithNoRuleIsNotPutUnderTheLineInTheDefaultModeAndIsWithTheChevron() {
        assertSame(bar, bar.placed("cal", Position.RIGHT, Part.LOWER, 0))
        val click = bar.copy(hiddenMode = HiddenMode.HOVER)
        assertEquals(Section.HIDDEN, click.placed("cal", Position.RIGHT, Part.LOWER, 0).at("cal").section)
    }

    @Test fun aHiddenItemWithoutARuleIsListedAboveInTheDefaultModeAndAnyMoveOfItWritesShown() {
        val c = BarConfig(items = listOf(a, hidden, b))
        assertEquals(listOf("a", "h", "b") to emptyList<String>(), c.rows())
        val moved = c.step("h", down = true)
        assertEquals(listOf("a", "b", "h"), moved.rows().first)
        assertEquals(Section.SHOWN, moved.at("h").section)
        assertEquals(Section.SHOWN, c.placed("h", Position.RIGHT, Part.UPPER, 0).at("h").section)
        // Visibility's Always on it is show(), which writes Shown and moves nothing.
        assertEquals(Section.SHOWN, c.show("h").at("h").section)
    }

    @Test fun aPlaceInAnotherAreaTakesTheItemThere() {
        val left = ItemConfig("l", "clock", area = Position.LEFT)
        val c = bar.copy(items = bar.items + left)
        val moved = c.placed("a", Position.LEFT, Part.UPPER, 0)
        assertEquals(Position.LEFT, moved.at("a").area)
        assertEquals(listOf("a", "l"), moved.parts(Position.LEFT).upper.map { it.id })
        assertEquals(listOf("b", "cal"), moved.rows().first)
        // Into an area with nothing in that part, it keeps its place in the list.
        val alone = c.placed("b", Position.CENTER, Part.UPPER, 0)
        assertEquals(c.ids(), alone.ids())
        assertEquals(listOf("b"), alone.parts(Position.CENTER).upper.map { it.id })
    }

    @Test fun aStepMovesOneRowWithinThePart() {
        assertEquals(listOf("b", "a", "cal"), bar.step("a", down = true).rows().first)
        assertEquals(listOf("a", "cal", "b"), bar.step("cal", down = false).rows().first)
        assertEquals(listOf("e", "w2", "w"), bar.step("w", down = true).rows().second)
        assertEquals(Step.End, bar.stepped("a", down = false))
        assertEquals(Step.End, bar.stepped("w2", down = true))
        assertEquals(Step.End, bar.stepped("nobody", down = true))
    }

    @Test fun downAtTheEndOfTheUpperPartCrossesTheLineByTheRuleOrByHiding() {
        val c = bar.copy(items = listOf(meeting, a, cal, b, w, w2))
        val below = c.step("b", down = true)
        assertEquals(listOf("a", "cal") to listOf("e", "b", "w", "w2"), below.rows())
        assertEquals(c.setWhenActive("b", true).at("b"), below.at("b"))
        for (mode in listOf(HiddenMode.CLICK, HiddenMode.HOVER)) {
            val behind = c.copy(hiddenMode = mode).step("b", down = true)
            assertEquals(listOf("a", "cal") to listOf("e", "b", "w", "w2"), behind.rows())
            assertEquals(c.hide("b").at("b"), behind.at("b"))
        }
    }

    @Test fun upAtTheTopOfTheLowerPartCrossesTheLineAndShows() {
        val up = bar.step("w", down = false)
        assertEquals(listOf("a", "b", "cal", "w") to listOf("e", "w2"), up.rows())
        assertEquals(Section.SHOWN, up.at("w").section)
        // And down again it is the first under the line, as it was.
        assertEquals(bar.rows(), up.step("w", down = true).rows())
    }

    @Test fun aTypeWithNoRuleStaysAboveTheLineInTheDefaultModeAndTheStepSaysWhy() {
        assertEquals(Step.Stays(Step.Why.NO_RULE), bar.stepped("cal", down = true))
        val behind = bar.copy(hiddenMode = HiddenMode.CLICK).step("cal", down = true)
        assertEquals(Section.HIDDEN, behind.at("cal").section)
        // An item of a type this version doesn't know has nothing to say about a rule: the end of the part is its end.
        val unknown = ItemConfig("u", "from-a-newer-version")
        for (mode in HiddenMode.entries) assertEquals(Step.End, BarConfig(items = listOf(a, unknown), hiddenMode = mode).stepped("u", down = true))
    }

    @Test fun nextMeetingIsNeverMovedAndNothingMovesAboveIt() {
        assertEquals(Step.Stays(Step.Why.PINNED), bar.stepped("e", down = true))
        assertEquals(Step.Stays(Step.Why.PINNED), bar.stepped("e", down = false))
        // The first row under it goes up across the line, not above it.
        assertEquals(listOf("e", "w2"), bar.step("w", down = false).rows().second)
        // Its part and area follow Visibility and Area; its place in the list is left alone.
        val shown = bar.placed("e", Position.RIGHT, Part.UPPER, 2)
        assertEquals(bar.ids(), shown.ids())
        assertEquals(listOf("e", "a", "b", "cal"), shown.rows().first)
        assertEquals(Step.End, shown.stepped("a", down = false))
    }

    // ---- An area by a click or a key ----

    private fun inArea(id: String, area: Position, section: Section = Section.SHOWN, rule: Boolean = false, type: String = "cpu") =
        ItemConfig(id, type, section, whenActive = rule, area = area)
    private fun BarConfig.rows(area: Position, part: Part) = parts(area)[part].map { it.id }

    @Test fun movingRightAnItemLandsFirstInItsPartThereAndMovingLeftLast() {
        val c = BarConfig(items = listOf(inArea("l1", Position.LEFT), inArea("l2", Position.LEFT), inArea("m1", Position.CENTER), inArea("m2", Position.CENTER),
            inArea("r1", Position.RIGHT), inArea("r2", Position.RIGHT)))
        assertEquals("from Left into Middle: at the top", listOf("l2", "m1", "m2"), c.landedIn("l2", Position.CENTER).rows(Position.CENTER, Part.UPPER))
        assertEquals("from Right into Middle: at the bottom", listOf("m1", "m2", "r1"), c.landedIn("r1", Position.CENTER).rows(Position.CENTER, Part.UPPER))
        assertEquals("past the Middle too", listOf("l1", "r1", "r2"), c.landedIn("l1", Position.RIGHT).rows(Position.RIGHT, Part.UPPER))
        assertEquals(listOf("l1", "l2", "r2"), c.landedIn("r2", Position.LEFT).rows(Position.LEFT, Part.UPPER))
        assertEquals("the lane it left keeps its order", listOf("r2"), c.landedIn("r1", Position.CENTER).rows(Position.RIGHT, Part.UPPER))
    }

    @Test fun anItemKeepsItsPartAndItsSectionWhenItChangesArea() {
        val waits = inArea("w", Position.RIGHT, Section.HIDDEN, rule = true)
        val quiet = inArea("q", Position.RIGHT, Section.HIDDEN)
        val c = BarConfig(items = listOf(inArea("l", Position.LEFT), inArea("lw", Position.LEFT, Section.HIDDEN, rule = true), inArea("r", Position.RIGHT), waits, quiet))
        val moved = c.landedIn("w", Position.LEFT)
        assertEquals("under the line there, after what waits there", listOf("lw", "w"), moved.rows(Position.LEFT, Part.LOWER))
        assertEquals(waits.copy(area = Position.LEFT), moved.at("w"))
        // A Hidden item without a rule is listed above in the default mode, and a change of area does not make it Shown.
        val above = c.landedIn("q", Position.LEFT)
        assertEquals(listOf("l", "q"), above.rows(Position.LEFT, Part.UPPER))
        assertEquals(quiet.copy(area = Position.LEFT), above.at("q"))
    }

    @Test fun intoAnAreaWithNothingInThatPartOnlyTheAreaChangesAndTheSameAreaChangesNothing() {
        val c = BarConfig(items = listOf(inArea("a", Position.RIGHT), inArea("b", Position.RIGHT)))
        assertEquals(c.putInArea("a", Position.LEFT), c.landedIn("a", Position.LEFT))
        assertSame(c, c.landedIn("a", Position.RIGHT))
        assertSame(c, c.landedIn("nobody", Position.LEFT))
    }

    @Test fun nextMeetingTakesTheAreaAndIsFirstThereWhereverTheListHasIt() {
        val c = BarConfig(items = listOf(inArea("m", Position.CENTER), inArea("e", Position.LEFT, type = Store.PINNED_LEFT), inArea("r", Position.RIGHT)))
        assertEquals(c.putInArea("e", Position.CENTER), c.landedIn("e", Position.CENTER))
        assertEquals(listOf("e", "m"), c.landedIn("e", Position.CENTER).rows(Position.CENTER, Part.UPPER))
        // And nothing lands before it.
        val pinned = BarConfig(items = listOf(inArea("e", Position.RIGHT, type = Store.PINNED_LEFT), inArea("r", Position.RIGHT), inArea("l", Position.LEFT)))
        assertEquals(listOf("e", "l", "r"), pinned.landedIn("l", Position.RIGHT).rows(Position.RIGHT, Part.UPPER))
    }

    @Test fun noScreenWritesASectionARuleOrAnAreaOfItsOwn() {
        val handlers = SourceScan.sources.filter { val p = it.path.replace('\\', '/'); "/bentobar/ui/" in p || "/bentobar/bar/" in p }
        assertTrue(handlers.size > 10)
        // `position` with them: it is the area the whole strip had before version 4, and a screen that wrote it would change nothing.
        val byHand = Regex("""\.copy\([^)]*\b(section|whenActive|area|position)\s*=(?!=)|,\s*999\s*\)""")
        assertTrue("the scan sees a write", byHand.containsMatchIn("c.copy(items = c.items.map { it.copy(area = Position.LEFT) })"))
        assertFalse("and not a comparison", byHand.containsMatchIn("it.copy(display = if (it.area == Position.LEFT) a else b)"))
        val found = handlers.filter { byHand.containsMatchIn(SourceScan.code(it)) }.map { it.name }
        assertEquals("a section, a Show when rule or an area is written in a screen: add a Layout intent instead", emptyList<String>(), found)
    }
}
