package com.meridian11.books.bentobar.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The hidden-items arrow in a text, as a screen reader says it. */
class ArrowWordsTest {
    private val arrow = "the hidden-items arrow"

    @Test fun theSignIsSaidInWords() {
        assertEquals("Behind the hidden-items arrow", ArrowWords.spoken("Behind ‹", arrow))
        assertEquals("Click the hidden-items arrow to see them.", ArrowWords.spoken("Click ‹ to see them.", arrow))
    }

    @Test fun theArrowOfTheLeftAreaPointsTheOtherWayAndIsTheSameArrow() {
        assertEquals("doesn't fit right now, it's behind the hidden-items arrow", ArrowWords.spoken("doesn't fit right now, it's behind ›", arrow, both = true))
    }

    @Test fun aStepInAPathIsNotTheArrow() {
        assertNull(ArrowWords.spoken("Weather's switch in Setup › Online services covers it too.", arrow))
        assertEquals("Setup › the hidden-items arrow", ArrowWords.spoken("Setup › ‹", arrow))
    }

    @Test fun anArticleInFrontOfTheSignIsNotSaidTwice() {
        assertEquals("doesn't fit right now (in the hidden-items arrow menu)", ArrowWords.spoken("doesn't fit right now (in the ‹ menu)", arrow))
        assertEquals("Turn it off here or from the hidden-items arrow menu.", ArrowWords.spoken("Turn it off here or from the ‹ menu.", arrow))
    }

    @Test fun aTextWithNoSignKeepsItsOwnWords() {
        assertNull(ArrowWords.spoken("Left to right, as they appear", arrow))
        assertNull(ArrowWords.spoken("", arrow))
    }
}
