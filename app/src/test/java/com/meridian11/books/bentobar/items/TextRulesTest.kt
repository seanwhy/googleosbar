package com.meridian11.books.bentobar.items

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Text from outside as one line, and its length as a reader counts it. Weather's tests show it in the bar; these, the rules themselves. */
class TextRulesTest {
    @Test fun oneLineIsOneLineOfAtMostSoManyCharacters() {
        assertEquals("A B C", TextRules.oneLine(" A\nB\t\u0007 C ", 80))
        assertEquals("Lake", TextRules.oneLine("Lake Tahoe", 5))
        assertEquals("", TextRules.oneLine(" \n ", 80))
    }

    @Test fun noCharacterIsCutInHalf() {
        assertEquals("é".repeat(2), TextRules.first("é".repeat(3), 2))
        assertEquals("🌧", TextRules.oneLine("🌧🌧", 1))
    }

    @Test fun whatTurnsTextAroundIsTakenOutAndWhatJoinsLettersStays() {
        assertEquals("SF", TextRules.oneLine("‮SF⁩", 80))
        assertEquals("a‌b", TextRules.oneLine("a‌b", 80))
    }

    @Test fun aTitleLongerThanItsRoomEndsInAnEllipsisAndNoCharacterIsCutInHalf() {
        assertEquals("Standup", TextRules.short("Standup", 7))
        assertEquals("Stand…", TextRules.short("Standup!", 6))
        // An emoji at the cut: a cut by UTF-16 units leaves half of it, which draws as a broken glyph.
        assertEquals("a🌧…", TextRules.short("a🌧🌧b", 3))
        assertEquals("…", TextRules.short("Standup", 1))
    }

    @Test fun aReaderCountsALetterWithItsAccentAsOne() {
        assertEquals(6, TextRules.count("Zürich"))
        assertTrue(TextRules.fits("Zürich", 6))
        assertFalse(TextRules.fits("Zürich", 5))
    }
}
