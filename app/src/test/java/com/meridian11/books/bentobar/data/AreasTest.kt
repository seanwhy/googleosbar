package com.meridian11.books.bentobar.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a layout says about the bar's three areas: which are in use, and which one is home to the bar's one-off things. */
class AreasTest {
    private fun item(id: String, area: Position, section: Section = Section.SHOWN, type: String = "clock") = ItemConfig(id, type, section, area = area)
    private fun layout(vararg items: ItemConfig) = BarConfig(items = items.toList())
    /** A type from a later version: in the layout, drawn nowhere. */
    private val later = "a-type-of-a-later-version"

    @Test fun anAreaIsInUseWhenItHoldsAnItemOfATypeThisVersionKnows() {
        val c = layout(item("l", Position.LEFT), item("m", Position.CENTER, Section.HIDDEN))
        assertEquals(setOf(Position.LEFT, Position.CENTER), c.areasInUse()) // a Hidden item counts
        assertEquals(setOf(Position.LEFT), layout(item("l", Position.LEFT), item("x", Position.RIGHT, type = later)).areasInUse())
        assertEquals(emptySet<Position>(), BarConfig().areasInUse())
    }

    @Test fun theHomeAreaIsLeftWhileLeftIsInUse() {
        assertEquals(Position.LEFT, layout(item("r", Position.RIGHT), item("m", Position.CENTER), item("l", Position.LEFT, Section.HIDDEN)).homeArea())
    }

    @Test fun withoutLeftTheHomeAreaIsMiddleAndOnlyThenRight() {
        assertEquals(Position.CENTER, layout(item("r", Position.RIGHT), item("m", Position.CENTER)).homeArea())
        assertEquals(Position.CENTER, layout(item("m", Position.CENTER)).homeArea())
        assertEquals(Position.RIGHT, layout(item("r", Position.RIGHT)).homeArea())
    }

    @Test fun withOnlyItemsOfTypesThisVersionDoesntKnowTheHomeAreaIsTheFirstItemsArea() {
        assertEquals(Position.LEFT, layout(item("l", Position.LEFT, type = later), item("r", Position.RIGHT, type = later)).homeArea())
        assertEquals(Position.CENTER, layout(item("m", Position.CENTER, type = later), item("l", Position.LEFT, type = later)).homeArea())
    }

    /** A layout with no items has no area to speak for it: the place the whole bar was last given does, as it always did. */
    @Test fun withNoItemsTheHomeAreaIsWherePositionSays() {
        assertEquals(Position.RIGHT, BarConfig().homeArea())
        for (position in Position.entries) assertEquals(position, BarConfig(position = position).homeArea())
    }

    @Test fun withAnItemPositionSaysNothingAboutTheHomeArea() {
        assertEquals(Position.RIGHT, layout(item("r", Position.RIGHT)).copy(position = Position.LEFT).homeArea())
        assertEquals(Position.CENTER, layout(item("m", Position.CENTER, type = later)).copy(position = Position.LEFT).homeArea())
    }

    @Test fun anItemOfATypeThisVersionDoesntKnowDoesNotMakeItsAreaHome() {
        assertEquals(Position.LEFT, layout(item("x", Position.RIGHT, type = later), item("l", Position.LEFT)).homeArea())
        // Alone it is still the first item, and so says where the home area is.
        assertEquals(Position.CENTER, layout(item("x", Position.CENTER, type = later)).homeArea())
    }

    @Test fun aLayoutUsesAreasWhenMoreThanOneHoldsAnItem() {
        assertTrue(layout(item("l", Position.LEFT), item("r", Position.RIGHT, Section.HIDDEN)).usesAreas())
        assertFalse(layout(item("l", Position.LEFT), item("l2", Position.LEFT, Section.HIDDEN)).usesAreas())
        assertFalse(BarConfig().usesAreas())
    }

    @Test fun theOneAreaIsWhereEveryItemIsAndNullWhenTheyAreInTwo() {
        assertEquals(Position.LEFT, layout(item("l", Position.LEFT), item("l2", Position.LEFT, Section.HIDDEN)).oneArea())
        assertNull(layout(item("l", Position.LEFT), item("m", Position.CENTER)).oneArea())
    }

    @Test fun withNoItemsTheOneAreaIsTheHomeArea() {
        assertEquals(BarConfig().homeArea(), BarConfig().oneArea())
        assertEquals(Position.RIGHT, BarConfig().oneArea())
    }

    /** Look's row shows a place as chosen on a layout with no items too: the one Position says. */
    @Test fun withNoItemsTheOneAreaIsWherePositionSays() {
        for (position in Position.entries) assertEquals(position, BarConfig(position = position).oneArea())
    }

    /** The home area is looked for in the bar's own order, so the two are one list. */
    @Test fun roomGoesLeftRightMiddleAndHomeGoesLeftMiddleRightAsTheBarStands() {
        assertEquals(listOf(Position.LEFT, Position.RIGHT, Position.CENTER), ROOM_ORDER)
        assertEquals(listOf(Position.LEFT, Position.CENTER, Position.RIGHT), BAR_ORDER)
    }

    /** The fields' own defaults are what a stored layout that names no area is read by: they stay Right, whatever a new bar starts with. */
    @Test fun anItemAndALayoutThatNameNoAreaAreInRight() {
        assertEquals(Position.RIGHT, ItemConfig("a", "clock", Section.SHOWN).area)
        assertEquals(Position.RIGHT, BarConfig().position)
    }
}
