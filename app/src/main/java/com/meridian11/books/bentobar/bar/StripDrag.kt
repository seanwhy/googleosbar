package com.meridian11.books.bentobar.bar

/** Where a dragged item lands in the strip, apart from the service, so the rule is unit-tested. */
internal object StripDrag {
    /**
     * The place in the list for a dragged item whose centre is at [center]: before the first other item, of those drawn when
     * the drag began, whose centre is to the right of it; after all of them if none is. [others] are the other shown items in
     * list order, which is the strip's own left-to-right order in every language ([Strip] forces it); [overflow]: those not
     * drawn, which are passed over; [centers]: where each stood when the drag began, so a landing place can't flip at a boundary.
     */
    fun index(center: Float, others: List<String>, overflow: Set<String>, centers: Map<String, Float>): Int {
        val next = others.firstOrNull { it !in overflow && centers.getOrElse(it) { Float.NEGATIVE_INFINITY } > center } ?: return others.size
        return others.indexOf(next)
    }
}
