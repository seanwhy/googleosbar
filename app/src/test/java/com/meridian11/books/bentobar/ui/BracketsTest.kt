package com.meridian11.books.bentobar.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where the areas' names stand under the preview: on their strips, never narrower than their words, never on each other, never off the bar. */
class BracketsTest {
    private fun places(vararg spans: IntRange, need: Int = 60, apart: Int = 2, room: Int = 1000) =
        bracketPlaces(IntArray(spans.size) { spans[it].first }, IntArray(spans.size) { spans[it].last }, IntArray(spans.size) { need }, apart, room)

    @Test fun aBracketWiderThanItsWordStandsOnItsStrip() {
        assertEquals(listOf(100..300, 400..700), places(100..300, 400..700))
    }

    @Test fun aStripNarrowerThanTheWordGetsABracketAsWideAsTheWordAroundTheStripsMiddle() {
        val (short) = places(490..510)
        assertEquals(60, short.last - short.first)
        assertEquals(500, (short.first + short.last) / 2)
    }

    @Test fun twoThatWouldMeetMoveApartAndKeepTheirWidths() {
        val (a, b) = places(100..120, 130..150)
        assertEquals(60, a.last - a.first); assertEquals(60, b.last - b.first)
        assertTrue("$a then $b", a.last + 2 <= b.first)
        // What they give way by is shared: each stands as far from its strip as the other.
        assertEquals(110 - (a.first + a.last) / 2, (b.first + b.last) / 2 - 140)
    }

    @Test fun noneLeavesTheBarAndANeighbourMakesRoom() {
        val (first) = places(0..10)
        assertEquals(0, first.first)
        val (a, b) = places(900..920, 980..1000)
        assertEquals(1000, b.last)
        assertTrue("$a then $b", a.last + 2 <= b.first)
        assertEquals(60, a.last - a.first)
    }
}
