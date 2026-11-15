package com.meridian11.books.bentobar.bar

/** Where a dragged item lands in the strip, apart from the service, so the rule is unit-tested. */
internal object StripDrag {
    /**
     * The place in the list for a dragged item whose centre is at [center]: before the first other item, of those drawn when
     * the drag began, whose centre is to the right of it; after all of them if none is. [others] are the other shown items in
     * list order, which is the strip's own left-to-right order in every language ([Strip] forces it); [overflow]: those not
     * drawn, which are passed over; [centers]: where each stood when the drag began, so a landing place can't flip at a boundary.
     *
     * A drag stays inside its area. [mine] are those of [others] in the dragged item's area, the only ones it lands before or
     * after: past its area's end it lands after that area's last item in the list (drawn or not), stepping over other areas'
     * items on the way and never past the ones behind. [now] is where it stands, as how many of [others] are before it: a drop
     * that passes none of [mine] changes nothing in its area, so it changes nothing in the list either (which is also the answer
     * for an item alone in its area). With the defaults every other item is the area's own, and the rule is the one above.
     */
    fun index(center: Float, others: List<String>, overflow: Set<String>, centers: Map<String, Float>,
              mine: Set<String> = others.toSet(), now: Int = others.size): Int {
        val next = others.firstOrNull { it in mine && it !in overflow && centers.getOrElse(it) { Float.NEGATIVE_INFINITY } > center }
        val target = if (next != null) others.indexOf(next) else others.indexOfLast { it in mine } + 1
        val stands = now.coerceIn(0, others.size)
        val passed = if (target > stands) others.subList(stands, target) else others.subList(target, stands)
        return if (passed.none { it in mine }) stands else target
    }
}
