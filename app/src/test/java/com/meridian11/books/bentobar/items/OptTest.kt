package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.SourceScan
import com.meridian11.books.bentobar.data.ItemConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** An option is declared once: its key, its default, and that what a layout says is checked on the way in. */
class OptTest {
    private fun item(vararg options: Pair<String, String>) = ItemConfig("a", "x", options = mapOf(*options))

    @Test fun aMissingKeyGivesTheDefault() {
        assertEquals(20, IntOpt("chars", 20, 8..40).of(item()))
        assertTrue(Opt.bool("mono", true).of(item()))
        assertEquals("", Opt.text("label", 12).of(item()))
        assertEquals("system", Opt.oneOf("hours", "system", "system", "24", "12").of(item()))
    }

    @Test fun aNumberFromALayoutIsHeldInItsRange() {
        val chars = IntOpt("chars", 20, 8..40)
        assertEquals(8, chars.of(item("chars" to "0")))
        assertEquals(40, chars.of(item("chars" to "99999")))
        assertEquals(33, chars.of(item("chars" to "33")))
    }

    @Test fun whatCannotBeReadGivesTheDefault() {
        assertEquals(20, IntOpt("chars", 20, 8..40).of(item("chars" to "lots")))
        assertTrue(Opt.bool("mono", true).of(item("mono" to "yes")))
        assertEquals("system", Opt.oneOf("hours", "system", "system", "24", "12").of(item("hours" to "13")))
        assertEquals("24", Opt.oneOf("hours", "system", "system", "24", "12").of(item("hours" to "24")))
    }

    @Test fun whatIsSetReadsBackAndBooleansAreWrittenAsTheOldCodeWroteThem() {
        val b = Opt.bool("mono", true)
        assertEquals("false", b.set(item(), false).options["mono"])
        assertFalse(b.of(b.set(item(), false)))
    }

    @Test fun aLabelFromALayoutIsOneLineWithNothingThatTurnsTextAroundAndNoLongerThanItsLimit() {
        val label = Opt.text("label", 12)
        assertEquals("ab cd", label.of(item("label" to "‮ab\ncd")))
        assertEquals("a".repeat(12), label.of(item("label" to "a".repeat(5000))))
    }

    @Test fun aClearedLabelTakesItsKeyOutOfTheLayoutButATextThatMayBeEmptyKeepsIt() {
        assertNull(Opt.text("label", 12).set(item("label" to "x"), "  ").options["label"])
        val text = Opt.text("text", 40, clearedIsUnset = false)
        assertEquals("", text.set(item("text" to "x"), "").options["text"])
        assertEquals("", text.stored(text.set(item(), "")))
        assertNull(text.stored(item()))
    }

    @Test fun aSavedLabelIsCutAtACharacterNotInsideOne() {
        val label = Opt.text("label", 2)
        assertEquals("a🌧", label.set(item(), "a🌧b").options["label"])
    }

    @Test fun aThresholdIsAnOptionThatKeepsWhatAnOlderLayoutStored() {
        // ThresholdTest pins the numbers; this, that it is the same kind of thing.
        val t: Opt<Int> = Threshold("activeKBs", 500, 50..5000, step = 50) { "$it" }
        assertEquals(9000, t.of(item("activeKBs" to "9000")))
    }

    @Test fun theMeetingTitlesLengthFromALayoutCannotBreakTheItem() {
        // 'chars':'0' made the bar call take(-1), which threw, and the item said "!".
        assertEquals(8, EventItem.chars.of(ItemConfig("e", "event", options = mapOf("chars" to "0"))))
        assertEquals("…", TextRules.short("Standup", 0))
    }

    @Test fun theLabelsTheBarShowsAreDeclaredWithTheirLimits() {
        assertEquals("Home", ClockItem.label.of(ItemConfig("c", "clock", options = mapOf("label" to "⁦Home⁩"))))
        assertEquals("0123456789abcdef", CountdownItem.label.of(ItemConfig("c", "countdown", options = mapOf("label" to "0123456789abcdefXYZ"))))
    }

    @Test fun theStripReadsAnAppsThemedIconOptionThroughItsDeclaration() {
        // A default changed in one place would split the icon from its tint.
        val byKey = SourceScan.sources.filter { """optBool("mono"""" in SourceScan.code(it) }.map { it.name }
        assertEquals(emptyList<String>(), byKey)
    }
}
