package com.meridian11.books.bentobar.bar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Switch popups on hover" (off by default): with a popup open, pointing at another item opens its popup, as in a menu bar. */
class PopupRulesTest {
    @Test fun aPressOnTheOpenPopupsItemIsThatItemsOnceTheGoneItemsAreForgotten() {
        // A right-anchored strip after a meeting's item has gone: the others moved left, and "own" holds the open popup.
        val drawn = listOf(Span("clock", 0f, 100f), Span("own", 110f, 210f))
        assertEquals("own", PopupRules.pressedItem(150f, drawn))
        // What a box left behind would do: the press goes to nothing that is there, and the popup turns round instead of closing.
        assertEquals("meeting", PopupRules.pressedItem(150f, drawn + Span("meeting", 100f, 250f)))
    }

    @Test fun aTooltipKeepsThePillsHeightAtTheDefaultTextSizeAndGrowsWithALargerOne() {
        // 13 sp of Roboto is 1.327 em from top to bottom: 18 px at density 1, 20 at 1.125, 36 at 2.
        assertEquals(28, PopupRules.tooltipHeightPx(line = 18, density = 1f))
        assertEquals(31, PopupRules.tooltipHeightPx(line = 20, density = 1.125f))
        assertEquals(56, PopupRules.tooltipHeightPx(line = 36, density = 2f))
        // Twice the text at density 2: 72 px of it, and no clipping.
        assertTrue(PopupRules.tooltipHeightPx(line = 72, density = 2f) >= 72)
    }

    @Test fun aPressBetweenItemsGoesToTheNearerOne() {
        val drawn = listOf(Span("own", 100f, 200f), Span("next", 260f, 300f))
        assertEquals("own", PopupRules.pressedItem(220f, drawn))
        assertEquals("next", PopupRules.pressedItem(240f, drawn))
        assertEquals(null, PopupRules.pressedItem(5f, emptyList()))
    }

    @Test fun withTheSettingOnPointingAtAnotherItemOpensItsPopup() {
        assertTrue(PopupRules.hoverOpens(open = MenuKey.Item("weather1"), hovered = "calendar1", hasPopup = true, on = true))
        // From the ‹ menu too: it is the bar's own popup.
        assertTrue(PopupRules.hoverOpens(open = MenuKey.Bar, hovered = "calendar1", hasPopup = true, on = true))
    }

    @Test fun offByDefaultAndNeverWithoutAnOpenPopup() {
        assertFalse(PopupRules.hoverOpens(open = MenuKey.Item("weather1"), hovered = "calendar1", hasPopup = true, on = false))
        // Nothing open: hovering never opens anything (Alex: no hover surprises).
        assertFalse(PopupRules.hoverOpens(open = null, hovered = "calendar1", hasPopup = true, on = true))
    }

    @Test fun itNeverActsForAClickAndLeavesARightClickMenuAlone() {
        // An item that acts on a click (keep awake, a Shortcut) has no popup: hovering it must not act.
        assertFalse(PopupRules.hoverOpens(open = MenuKey.Item("weather1"), hovered = "caffeine1", hasPopup = false, on = true))
        // The item whose popup is open: nothing to switch.
        assertFalse(PopupRules.hoverOpens(open = MenuKey.Item("weather1"), hovered = "weather1", hasPopup = true, on = true))
        // A right-click menu belongs to its item: pointing elsewhere doesn't swap it for a popup.
        assertFalse(PopupRules.hoverOpens(open = MenuKey.ItemContext("weather1"), hovered = "calendar1", hasPopup = true, on = true))
    }
}
