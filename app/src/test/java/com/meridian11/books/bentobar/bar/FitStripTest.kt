package com.meridian11.books.bentobar.bar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FitStripTest {
    private val widths = listOf(40, 40, 40) // 120 + 2 gaps of 10 = 140

    @Test fun everythingThatFitsShowsWithoutRoomForTheChevron() {
        // 140 fits a 150 budget; with 30 kept for ‹ it wouldn't. Nothing is dropped, so no ‹ is needed.
        val (keep, used) = fitStrip(widths, 10, 150, chevronAlways = false, chevronPx = 30, keepEnd = true)
        assertTrue(keep.all { it })
        assertEquals(140, used)
    }

    @Test fun overflowLeavesRoomForTheChevron() {
        // 140 doesn't fit 130: drop from the start and keep 30 for ‹, so 100 is left: two items (90).
        val (keep, used) = fitStrip(widths, 10, 130, chevronAlways = false, chevronPx = 30, keepEnd = true)
        assertEquals(listOf(false, true, true), keep.toList())
        assertEquals(90, used)
    }

    @Test fun aChevronThatShowsAnywayAlwaysHasRoom() {
        val (keep, _) = fitStrip(widths, 10, 150, chevronAlways = true, chevronPx = 30, keepEnd = true)
        assertEquals(listOf(false, true, true), keep.toList())
    }

    @Test fun aPictureIsAsHighAsAnIconAndAsWideAsItsShape() {
        assertEquals("a square one is an icon, as every picture was", 15f, imageWidthDp(15f, 96, 96), 0f)
        assertEquals("a row of three icons in one picture", 45f, imageWidthDp(15f, 288, 96), 0f)
        assertEquals("nothing to go by: an icon's room", 15f, imageWidthDp(15f, 0, 0), 0f)
    }

    @Test fun keepEndFalseDropsFromTheEnd() {
        val (keep, _) = fitStrip(widths, 10, 130, chevronAlways = false, chevronPx = 30, keepEnd = false)
        assertEquals(listOf(true, true, false), keep.toList())
    }
}
