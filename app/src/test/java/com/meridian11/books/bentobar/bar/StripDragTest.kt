package com.meridian11.books.bentobar.bar

import org.junit.Assert.assertEquals
import org.junit.Test

/** Where a dragged item lands: the strip's order runs left to right in every language, so this rule is the only one. */
class StripDragTest {
    private val centers = mapOf("a" to 10f, "b" to 30f, "c" to 50f)
    private val others = listOf("a", "b", "c")

    @Test fun anItemDroppedBetweenTwoLandsBetweenThem() {
        assertEquals(1, StripDrag.index(20f, others, emptySet(), centers))
        assertEquals(2, StripDrag.index(40f, others, emptySet(), centers))
    }

    @Test fun anItemDroppedPastTheEndLandsLastAndAtTheFrontFirst() {
        assertEquals(3, StripDrag.index(70f, others, emptySet(), centers))
        assertEquals(0, StripDrag.index(0f, others, emptySet(), centers))
    }

    @Test fun onlyItemsThatWereDrawnCount() {
        // c never got a place in the strip: passed over, and past the last drawn one the item still lands at the very end.
        assertEquals(3, StripDrag.index(40f, others, setOf("c"), centers - "c"))
        assertEquals(2, StripDrag.index(40f, listOf("a", "b", "c"), emptySet(), centers))
        assertEquals(3, StripDrag.index(45f, others, emptySet(), mapOf("a" to 10f)))
    }
}
