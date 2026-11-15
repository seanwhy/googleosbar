package com.meridian11.books.bentobar.bar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    // ---- a layout in more than one area: a drag stays in its own -------------------------------------------

    // Left's items stand at the left of the screen and Right's at the right, and the list mixes them as it likes.
    private val places = mapOf("l1" to 10f, "l2" to 30f, "l3" to 50f, "r1" to 510f, "r2" to 530f)
    private val rights = setOf("r1", "r2")

    @Test fun aDropPastTheAreasLastItemLandsAfterThatItemAndNotAtTheListsEnd() {
        // The list: (dragged) l1 r1 l2 r2 l3.
        val list = listOf("l1", "r1", "l2", "r2", "l3")
        assertEquals(4, StripDrag.index(600f, list, emptySet(), places, mine = rights, now = 0))
        // Its area's last item never got a place in the strip: still after that one, as the end is in one area.
        assertEquals(4, StripDrag.index(600f, list, setOf("r2"), places - "r2", mine = rights, now = 0))
        assertEquals(4, StripDrag.index(600f, list, emptySet(), places - "r2", mine = rights, now = 0))
    }

    @Test fun aDropThatPassesNoItemOfTheAreaLeavesTheListAsItIs() {
        // l1 r1 (dragged) l2 r2 l3, dropped between r1 and r2 where it stands: before r2 is one place on, past l2 only.
        val list = listOf("l1", "r1", "l2", "r2", "l3")
        assertEquals(2, StripDrag.index(520f, list, emptySet(), places, mine = rights, now = 2))
        // l1 r1 l2 (dragged) r2 l3: the same drop, from the other side of l2.
        assertEquals(3, StripDrag.index(520f, list, emptySet(), places, mine = rights, now = 3))
        // l1 r1 r2 l2 (dragged) l3, dropped past its area's end, where it stands: after r2 is one place back, past l2 only.
        assertEquals(4, StripDrag.index(600f, listOf("l1", "r1", "r2", "l2", "l3"), emptySet(), places, mine = rights, now = 4))
        // The only item of its area has nowhere to go.
        for (at in listOf(0f, 40f, 600f)) assertEquals(1, StripDrag.index(at, listOf("l1", "l2", "l3"), emptySet(), places, mine = emptySet(), now = 1))
    }

    @Test fun itemsOfOtherAreasBetweenTwoOfTheAreasOwnAreSteppedOver() {
        val list = listOf("l1", "r1", "l2", "r2", "l3")
        // (dragged) l1 r1 l2 r2 l3, dropped between r1 and r2: before r2, over l1 and l2 as well as r1.
        assertEquals(3, StripDrag.index(520f, list, emptySet(), places, mine = rights, now = 0))
        // l1 r1 l2 r2 l3 (dragged), dropped left of r1: before r1, over l3 and l2 as well as r2.
        assertEquals(1, StripDrag.index(500f, list, emptySet(), places, mine = rights, now = 5))
        // Another area's item is never the one to land before, wherever it stands: (dragged) r1 l1 r2 l2, a Left item
        // dropped between l1 and l2, lands before l2 and not before r1, which is the first in the list right of it.
        assertEquals(3, StripDrag.index(20f, listOf("r1", "l1", "r2", "l2"), emptySet(), places, mine = setOf("l1", "l2"), now = 0))
    }

    /** The rule as it was before items had areas, word for word. */
    private fun asBeforeAreas(center: Float, others: List<String>, overflow: Set<String>, centers: Map<String, Float>): Int {
        val next = others.firstOrNull { it !in overflow && centers.getOrElse(it) { Float.NEGATIVE_INFINITY } > center } ?: return others.size
        return others.indexOf(next)
    }

    /** Every list of up to four others, drawn or not, measured or not, wherever the dragged item stood and is dropped. */
    @Test fun inOneAreaADragLandsExactlyWhereItAlwaysDid() {
        val ids = listOf("a", "b", "c", "d")
        val xs = listOf(null, 10f, 30f, 50f)
        var drops = 0
        for (n in 0..ids.size) {
            val list = ids.take(n)
            for (code in 0 until (1 shl 2 * n)) for (out in 0 until (1 shl n)) {
                val at = list.withIndex().mapNotNull { (i, id) -> xs[(code shr 2 * i) and 3]?.let { id to it } }.toMap()
                val overflow = list.filterIndexed { i, _ -> (out shr i) and 1 == 1 }.toSet()
                for (center in listOf(0f, 20f, 40f, 60f)) {
                    val before = asBeforeAreas(center, list, overflow, at)
                    assertEquals("$list $at $overflow $center", before, StripDrag.index(center, list, overflow, at))
                    for (now in 0..n) assertEquals("$list $at $overflow $center now=$now", before, StripDrag.index(center, list, overflow, at, mine = list.toSet(), now = now))
                    drops++
                }
            }
        }
        assertTrue("$drops drops compared", drops > 10_000)
    }
}
