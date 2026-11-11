package com.meridian11.books.bentobar.data

import com.meridian11.books.bentobar.SourceScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** One command places an item one way, whichever surface asks. */
class LayoutIntentsTest {
    private val shown = ItemConfig("s", "clock", Section.SHOWN)
    private val shown2 = ItemConfig("s2", "clock", Section.SHOWN)
    private val hidden = ItemConfig("h", "clock", Section.HIDDEN)
    private val off = ItemConfig("o", "clock", Section.OFF)
    private val layout = BarConfig(items = listOf(shown, shown2, hidden, off))
    private fun BarConfig.ids() = items.map { it.id }
    private fun BarConfig.at(id: String) = items.single { it.id == id }

    @Test fun aCommandChangesAnItemsSectionNeverItsPlaceInTheList() {
        val hid = layout.hide("s")
        assertEquals(Section.HIDDEN, hid.at("s").section)
        assertEquals(layout.ids(), hid.ids())
        val back = layout.show("h")
        assertEquals(Section.SHOWN, back.at("h").section)
        assertEquals(layout.ids(), back.ids())
        val gone = layout.turnOff("s2")
        assertEquals(Section.OFF, gone.at("s2").section)
        assertEquals(layout.ids(), gone.ids())
        val chosen = layout.putIn("o", Section.SHOWN)
        assertEquals(Section.SHOWN, chosen.at("o").section)
        assertEquals(layout.ids(), chosen.ids())
    }

    @Test fun inShowEverythingAHideThatHidesNothingMovesNothingAlongTheStrip() {
        // The default mode draws Hidden items, so an item that jumped to the section's end would jump along the strip.
        val c = BarConfig(items = listOf(shown, hidden.copy(section = Section.SHOWN), shown2))
        assertEquals(HiddenMode.SHOW_ALL, c.hiddenMode)
        val before = c.stripPlan({ false }, expanded = false, overflow = false).visible.map { it.id }
        assertEquals(before, c.hide("h").stripPlan({ false }, expanded = false, overflow = false).visible.map { it.id })
    }

    @Test fun hideThenShowLeavesAnItemWhereItWas() {
        assertEquals(layout.ids(), layout.hide("s").show("s").ids())
    }

    @Test fun onlyAnExplicitMoveChangesAnItemsPlace() {
        assertEquals(listOf("s2", "s", "h", "o"), layout.copy(items = layout.items.moved("s", Section.HIDDEN, 0)).ids())
    }

    @Test fun aCommandForTheSectionAnItemIsAlreadyInMovesNothing() {
        // The settings' Where chooser asks for the section whichever chip is clicked: the selected one must not reorder.
        assertSame(layout, layout.show("s"))
        assertSame(layout, layout.hide("h"))
        assertSame(layout, layout.turnOff("o"))
    }

    @Test fun anIdThatIsNotInTheLayoutChangesNothing() {
        assertSame(layout, layout.hide("nope"))
        assertEquals(layout, layout.setWhenActive("nope", true))
    }

    @Test fun theRuleBringsAShownOrTurnedOffItemIntoHiddenWhereItIs() {
        // Both the bar's menu and settings do this: where it is, because it pops out at the place it had in the bar.
        val fromShown = layout.setWhenActive("s", true)
        assertEquals(Section.HIDDEN, fromShown.at("s").section)
        assertTrue(fromShown.at("s").whenActive)
        assertEquals(layout.ids(), fromShown.ids())
        // Only hidden items pop out, so a turned-off one comes too (the bar's menu used to leave it Off).
        val fromOff = layout.setWhenActive("o", true)
        assertEquals(Section.HIDDEN, fromOff.at("o").section)
        assertTrue(fromOff.at("o").whenActive)
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
        assertTrue(rule.turnOff("h").at("h").whenActive)
        assertTrue(rule.show("h").at("h").whenActive)
    }

    @Test fun noScreenWritesASectionOrARuleOfItsOwn() {
        val handlers = SourceScan.sources.filter { val p = it.path.replace('\\', '/'); "/bentobar/ui/" in p || "/bentobar/bar/" in p }
        assertTrue(handlers.size > 10)
        val byHand = Regex("""\.copy\([^)]*\b(section|whenActive)\s*=|,\s*999\s*\)""")
        val found = handlers.filter { byHand.containsMatchIn(SourceScan.code(it)) }.map { it.name }
        assertEquals("a section or a Show when rule is written in a screen: add a Layout intent instead", emptyList<String>(), found)
    }
}
