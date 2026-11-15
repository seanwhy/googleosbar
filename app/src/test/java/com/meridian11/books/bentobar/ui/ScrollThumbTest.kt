package com.meridian11.books.bentobar.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Where the thumb of something that scrolls stands: only while there is more than fits, as long as the share that shows, and at the end when the end shows. */
class ScrollThumbTest {
    @Test fun whereEverythingFitsThereIsNoThumb() {
        assertNull(thumbOf(view = 400f, more = 0, at = 0, least = 24f))
        // A view not measured yet reports no end at all.
        assertNull(thumbOf(view = 400f, more = Int.MAX_VALUE, at = 0, least = 24f))
        assertNull(thumbOf(view = 0f, more = 100, at = 0, least = 24f))
    }

    @Test fun theThumbIsAsLongAsTheShareThatShows() {
        val thumb = thumbOf(view = 400f, more = 400, at = 0, least = 24f)!!
        assertEquals(0f, thumb.start, 0.01f)
        assertEquals(200f, thumb.endInclusive, 0.01f)
    }

    @Test fun itStandsAtTheEndWhenTheEndShowsAndHalfWayInBetween() {
        assertEquals(400f, thumbOf(400f, 400, 400, 24f)!!.endInclusive, 0.01f)
        assertEquals(100f, thumbOf(400f, 400, 200, 24f)!!.start, 0.01f)
        // Scrolled past an end, as a fling can report: it stays inside.
        assertEquals(0f, thumbOf(400f, 400, -30, 24f)!!.start, 0.01f)
        assertEquals(400f, thumbOf(400f, 400, 900, 24f)!!.endInclusive, 0.01f)
    }

    @Test fun aLongListsThumbIsNeverShorterThanCanBeSeen() {
        val thumb = thumbOf(view = 400f, more = 40_000, at = 40_000, least = 24f)!!
        assertEquals(24f, thumb.endInclusive - thumb.start, 0.01f)
        assertEquals(400f, thumb.endInclusive, 0.01f)
    }
}
