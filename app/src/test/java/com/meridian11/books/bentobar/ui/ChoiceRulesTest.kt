package com.meridian11.books.bentobar.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** How an item's own choice is set in the panel: a segment where two or three short values fit it, else radio buttons. */
class ChoiceRulesTest {
    @Test fun twoOrThreeShortValuesAreASegment() {
        assertTrue(ChoiceRules.asSegment(listOf("Like the system", "°C", "°F")))
        assertTrue(ChoiceRules.asSegment(listOf("US AQI", "European")))
        assertTrue(ChoiceRules.asSegment(listOf("3 s", "5 s", "10 s")))
    }

    @Test fun moreThanThreeValuesAreAListHoweverShort() {
        assertFalse(ChoiceRules.asSegment(listOf("3 s", "5 s", "10 s", "30 s")))
        assertFalse(ChoiceRules.asSegment(listOf("a", "b", "c", "d")))
    }

    @Test fun wordsTooLongForThePanelAreAList() {
        // Weather's Show: no part of a segment 212 dp wide holds "High and low" beside two others.
        assertFalse(ChoiceRules.asSegment(listOf("Temperature", "High and low", "Feels like")))
        assertFalse(ChoiceRules.asSegment(listOf("Title and artist", "Title")))
    }

    @Test fun oneValueOrNoneIsNeverASegment() {
        assertFalse(ChoiceRules.asSegment(listOf("Only")))
        assertFalse(ChoiceRules.asSegment(emptyList()))
    }

    @Test fun theLimitItselfStillFits() {
        assertTrue(ChoiceRules.asSegment(listOf("a".repeat(ChoiceRules.SEGMENT_CHARS - 2), "b", "c")))
        assertFalse(ChoiceRules.asSegment(listOf("a".repeat(ChoiceRules.SEGMENT_CHARS - 1), "b", "c")))
    }
}
