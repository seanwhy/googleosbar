package com.meridian11.books.bentobar.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.meridian11.books.bentobar.data.BAR_ORDER
import com.meridian11.books.bentobar.data.BarConfig
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.data.Part
import com.meridian11.books.bentobar.data.Position
import com.meridian11.books.bentobar.data.Store
import com.meridian11.books.bentobar.data.addedAt
import com.meridian11.books.bentobar.data.canGoBelow
import com.meridian11.books.bentobar.data.fresh
import com.meridian11.books.bentobar.data.homeArea
import com.meridian11.books.bentobar.data.partOf
import com.meridian11.books.bentobar.data.parts
import com.meridian11.books.bentobar.data.placed
import com.meridian11.books.bentobar.data.unplaced
import com.meridian11.books.bentobar.data.without
import com.meridian11.books.bentobar.items.Items
import kotlin.math.abs

// What a drag on the Bar page comes to, as rules over rectangles and a point: which place the pointer is over, what the
// list shows meanwhile, what a drop writes and what the line under the preview says. Pure, so it is tested without a
// pointer; `Reorder` measures the page into a [DropMap] and asks here on every move.

/** Where a carried thing came from: a row of the bar's list, a type's row in the library. */
enum class From { BAR, TYPE }

/** What is carried. A type has no item yet: [id] is the one it gets if it is dropped in the bar. */
data class Carried(val id: String, val from: From, val type: String)

/** What letting go right now would do. */
sealed interface Drop {
    /** Into the bar at [spot]; [starts] when its area has no other item, so the drop starts that area. */
    data class At(val spot: DropSpot, val starts: Boolean = false) : Drop
    /** On the trash: a row of the bar is removed. */
    data object Off : Drop
    /** Over a lower part the item can't wait in (its type has no rule): no place, and the line says why. */
    data class Refused(val zone: Zone) : Drop
    /** Outside the list, the preview and the trash: nothing changes. */
    data object None : Drop
}

/**
 * The page as a drag sees it, in one coordinate space. [list] is the list's card and [zones] the stretch of each part
 * in it, [rows] the middles of each zone's rows without the carried one; [preview] is the bar in the preview, [areas]
 * the stretch of each area along it (also one that is not in use) and [chips] the middles of each zone's rows there,
 * null for a row the bar isn't drawing right now; [trash] is the strip along the card's lower edge that takes a row of
 * the bar, null while there is none to drop on (a type of the library is carried, or the row was picked up where the
 * trash stands and has not left it yet).
 */
data class DropMap(
    val list: Rect? = null,
    val zones: List<Pair<Zone, Rect>> = emptyList(),
    val rows: Map<Zone, List<Float>> = emptyMap(),
    val preview: Rect? = null,
    val areas: Map<Position, ClosedFloatingPointRange<Float>> = emptyMap(),
    val chips: Map<Zone, List<Float?>> = emptyMap(),
    val trash: Rect? = null,
)

/**
 * What the layout says of the carried thing: the zone a row came from ([own]), the [part] it takes on the preview (its
 * own; what the + would give a type), whether it may wait [below] a line, the areas that have a
 * row besides it ([inUse]) and the zones whose first row is Next meeting ([pinned]).
 */
data class Lift(val from: From, val own: Zone?, val part: Part, val below: Boolean, val inUse: Set<Position>, val pinned: Set<Zone>)

fun BarConfig.lift(c: Carried): Lift {
    val item = items.firstOrNull { it.id == c.id }
    val type = Items.of(c.type)
    val own = item?.takeIf { c.from == From.BAR }?.let { row -> partOf(row)?.let { Zone(row.area, it) } }
    // A type carried is the item the + would make of it.
    val made = if (c.from == From.TYPE) type?.fresh(c.id, homeArea()) else null
    val part = when (c.from) {
        From.BAR -> own?.part
        From.TYPE -> made?.let(::partOf)
    } ?: Part.UPPER
    // The one rule of the lower part, canGoBelow: the preview and the drop ask the same.
    val below = when (c.from) {
        From.TYPE -> made != null && canGoBelow(made, type)
        From.BAR -> item != null && canGoBelow(item)
    }
    val others = items.filter { it.id != c.id }
    val pinned = others.filter { it.type == Store.PINNED_LEFT }.mapNotNull { row -> partOf(row)?.let { Zone(row.area, it) } }
    return Lift(c.from, own, part, below, others.map { it.area }.toSet(), pinned.toSet())
}

/**
 * The stretch of each part in the list, from where its heading stands ([heads]: the list's head or an area's name for
 * an upper part, the line between the parts for a lower one). A zone is as wide as its area's lane (the upper heading:
 * a line shared by the lanes side by side is wider than any of them) and runs down to the next heading in its lane, the
 * last one to [bottom].
 */
fun zoneRects(heads: Map<Zone, Rect>, bottom: Float): List<Pair<Zone, Rect>> = heads.map { (zone, head) ->
    val lane = heads[Zone(zone.area, Part.UPPER)] ?: head
    val next = heads.values.filter { it.top > head.top && it.left < lane.right && it.right > lane.left }.minOfOrNull { it.top }
    zone to Rect(lane.left, head.top, lane.right, maxOf(head.top, next ?: bottom))
}.sortedWith(compareBy({ it.second.left }, { it.second.top }))

/**
 * The place in the list for a row whose middle is at [y] while the pointer is at [x]: the lane is told by [x] (the
 * nearest one between two), the part and the place in it by [y] as [dropSpot] tells them. Null while nothing is measured.
 */
fun listSpot(x: Float, y: Float, zones: List<Pair<Zone, Rect>>, rows: Map<Zone, List<Float>>, current: DropSpot?): DropSpot? {
    if (zones.isEmpty()) return null
    fun away(r: Rect) = when {
        x < r.left -> r.left - x
        x > r.right -> x - r.right
        else -> 0f
    }
    val near = zones.minOf { away(it.second) }
    val lane = zones.filter { away(it.second) == near }.sortedBy { it.second.top }
    // Between two zones of a lane the place stays; with none yet, the nearer zone's.
    val fallback = current?.takeIf { c -> lane.any { it.first == c.zone } } ?: lane.minBy { (_, r) -> minOf(abs(y - r.top), abs(y - r.bottom)) }.let { (zone, r) ->
        DropSpot(zone, if (y < r.top) 0 else rows[zone].orEmpty().size)
    }
    return dropSpot(y, lane.map { (zone, r) -> zone to r.top..r.bottom }, rows, fallback)
}

/**
 * The free space along the preview's bar, from its left end to its right: between the mock [clock] and the system's
 * [icons] as they were measured, whichever side each is drawn on (mirrored, the icons are at the left). [beside] stays
 * free at the clock and [before] at the icons; one not measured yet is taken to be at its end of a bar [width] wide.
 */
fun freeSpace(clock: ClosedFloatingPointRange<Float>?, icons: ClosedFloatingPointRange<Float>?, width: Float, beside: Float, before: Float): Pair<Float, Float> =
    if (clock != null && icons != null && icons.start < clock.start) icons.endInclusive + before to clock.start - beside
    else (clock?.endInclusive ?: 0f) + beside to (icons?.start ?: width) - before

/**
 * The stretch of each area along the preview's bar between [lo] and [hi], the ends of its [free space][freeSpace] (the
 * clock's end and where the system's icons start, or mirrored the other way round). An area [inUse] has the stretch its
 * strip takes. One that is not gets a zone [wide]: Left from the left end, Right up to the right end, the Middle
 * centered in the free space and moved aside only as far as a neighbour needs.
 * A zone is at least [gap] from its neighbours, narrower where the room is, and not offered with less than [least].
 */
fun previewZones(
    lo: Float,
    hi: Float,
    inUse: Map<Position, ClosedFloatingPointRange<Float>>,
    wide: Float,
    gap: Float,
    least: Float,
): Map<Position, ClosedFloatingPointRange<Float>> {
    val out = LinkedHashMap(inUse)
    fun fit(from: Float, to: Float, at: (Float) -> Float): ClosedFloatingPointRange<Float>? {
        val room = to - from
        if (room < least) return null
        val w = minOf(wide, room)
        val start = at(w).coerceIn(from, to - w)
        return start..start + w
    }
    if (Position.LEFT !in out) {
        val to = listOfNotNull(out[Position.CENTER]?.start, out[Position.RIGHT]?.start).minOrNull()?.minus(gap) ?: hi
        fit(lo, to) { lo }?.let { out[Position.LEFT] = it }
    }
    if (Position.RIGHT !in out) {
        val from = listOfNotNull(out[Position.CENTER]?.endInclusive, out[Position.LEFT]?.endInclusive).maxOrNull()?.plus(gap) ?: lo
        fit(from, hi) { hi - it }?.let { out[Position.RIGHT] = it }
    }
    if (Position.CENTER !in out) {
        val from = out[Position.LEFT]?.endInclusive?.plus(gap) ?: lo
        val to = out[Position.RIGHT]?.start?.minus(gap) ?: hi
        fit(from, to) { (lo + hi) / 2 - it / 2 }?.let { out[Position.CENTER] = it }
    }
    return out
}

/** The area at [x] along the preview's bar: the one whose stretch holds it, else the nearest. Null with none. */
fun areaAt(x: Float, areas: Map<Position, ClosedFloatingPointRange<Float>>): Position? = areas.entries.minByOrNull { (_, r) ->
    when {
        x < r.start -> r.start - x
        x > r.endInclusive -> x - r.endInclusive
        else -> 0f
    }
}?.key

/**
 * What a drop would do with the pointer at [p] and the carried row's middle at height [mid] (the row hangs from the
 * pointer where it was grabbed, so its middle tells the place in the list, the pointer the lane and everything else).
 * The trash is asked first: it takes a row of the bar and nothing that came from the library, and the panel takes no
 * drop at all. The preview takes a drop by area: the item keeps
 * its part and stands after the last chip of its part that is left of the pointer. In the list a part under a line
 * refuses what can't wait there. A place before Next meeting is the one after it. Anywhere else nothing would change.
 */
fun dropAt(p: Offset, mid: Float, map: DropMap, lift: Lift, current: Drop): Drop {
    fun at(zone: Zone, index: Int): Drop {
        if (zone.part == Part.LOWER && !lift.below) return Drop.Refused(zone)
        val starts = zone.area !in lift.inUse && zone.area != lift.own?.area
        return Drop.At(DropSpot(zone, if (zone in lift.pinned) index.coerceAtLeast(1) else index), starts)
    }
    if (map.trash?.contains(p) == true) return if (lift.from == From.BAR) Drop.Off else Drop.None
    if (map.preview?.contains(p) == true) {
        val zone = Zone(areaAt(p.x, map.areas) ?: return Drop.None, lift.part)
        return at(zone, map.chips[zone].orEmpty().indexOfLast { it != null && it < p.x } + 1)
    }
    if (map.list?.contains(p) == true) {
        val spot = listSpot(p.x, mid, map.zones, map.rows, (current as? Drop.At)?.spot) ?: return Drop.None
        return at(spot.zone, spot.index)
    }
    return Drop.None
}

/** The layout a drop writes: the one step it is ([placed], [addedAt], [without]), or the layout as it is. */
fun BarConfig.dropped(c: Carried, drop: Drop): BarConfig = when (drop) {
    is Drop.At -> {
        val (zone, index) = drop.spot
        when (c.from) {
            From.BAR -> placed(c.id, zone.area, zone.part, index)
            From.TYPE -> Items.of(c.type)?.let { addedAt(it, c.id, zone.area, zone.part, index) } ?: this
        }
    }
    Drop.Off -> if (c.from == From.BAR) without(c.id) else this
    is Drop.Refused, Drop.None -> this
}

/**
 * The layout the list shows while [c] is carried: with it where it would land, without it over the trash, and as the
 * Store has it over nothing (a row's opening stays where the row came from).
 */
fun BarConfig.showing(c: Carried, drop: Drop): BarConfig = when (drop) {
    is Drop.At -> dropped(c, drop)
    Drop.Off -> if (c.from == From.BAR) without(c.id) else this
    is Drop.Refused, Drop.None -> this
}

/**
 * The item the copy under the pointer draws while [c] is carried: as it would be if it were let go now, so the copy
 * never says of a place what is not so there (a type that waits for its rule is always in the bar above the line).
 * Over no place a row of the bar is what it is, and a type what the + would make of
 * it without its rule: it is in no part of the bar, so it says nothing about when it shows. Null for what the layout
 * does not have.
 */
fun BarConfig.carriedAs(c: Carried, drop: Drop): ItemConfig? {
    if (drop is Drop.At) dropped(c, drop).items.firstOrNull { it.id == c.id }?.let { return it }
    if (c.from == From.TYPE) return Items.of(c.type)?.let { unplaced(it, c.id) }
    return items.firstOrNull { it.id == c.id }
}

/** What the line under the preview says while dragging. */
enum class Says { MOVE_AFTER, MOVE_BEFORE, MOVE_TO, ADD_AFTER, ADD_BEFORE, ADD_TO, STARTS, OFF, NO_RULE, NO_RULE_ADD, NONE }

/**
 * The line under the preview: what it [says], the row it names [beside] the place (an id), the [area] it names (with
 * several lanes on screen, and always for a part with no rows or an area that starts) and, for a part with no rows, the [part].
 */
data class DropLine(val says: Says, val beside: String? = null, val area: Position? = null, val part: Part? = null)

fun BarConfig.dropLine(c: Carried, drop: Drop, several: Boolean): DropLine {
    val add = c.from != From.BAR
    return when (drop) {
        Drop.None -> DropLine(Says.NONE)
        Drop.Off -> DropLine(if (add) Says.NONE else Says.OFF)
        is Drop.Refused -> DropLine(if (add) Says.NO_RULE_ADD else Says.NO_RULE)
        is Drop.At -> {
            val zone = drop.spot.zone
            val rows = parts(zone.area)[zone.part].filter { it.id != c.id }
            val at = drop.spot.index.coerceIn(0, rows.size)
            val area = zone.area.takeIf { several }
            when {
                drop.starts -> DropLine(Says.STARTS, area = zone.area)
                rows.isEmpty() -> DropLine(if (add) Says.ADD_TO else Says.MOVE_TO, area = zone.area, part = zone.part)
                at == 0 -> DropLine(if (add) Says.ADD_BEFORE else Says.MOVE_BEFORE, rows.first().id, area)
                else -> DropLine(if (add) Says.ADD_AFTER else Says.MOVE_AFTER, rows[at - 1].id, area)
            }
        }
    }
}

/**
 * Where the insertion bar stands along the preview: before the chip of the next item of the area that is drawn, else
 * after the last one before it, half a [gap] away; with no chip in the area, where the area's first item would start
 * ([zone]: its start for Left, its middle for the Middle, its end for Right). [order] is the area's ids as the bar
 * draws them with the carried one ([id]) at its new place, [chips] the stretch of each chip that is drawn.
 */
fun insertionX(order: List<String>, id: String, chips: Map<String, ClosedFloatingPointRange<Float>>, zone: ClosedFloatingPointRange<Float>, area: Position, gap: Float): Float {
    val at = order.indexOf(id)
    val after = order.drop(at + 1).firstNotNullOfOrNull { chips[it] }
    val before = order.take(at.coerceAtLeast(0)).reversed().firstNotNullOfOrNull { chips[it] }
    return when {
        at >= 0 && after != null -> after.start - gap / 2
        at >= 0 && before != null -> before.endInclusive + gap / 2
        area == BAR_ORDER.first() -> zone.start
        area == BAR_ORDER.last() -> zone.endInclusive
        else -> (zone.start + zone.endInclusive) / 2
    }
}
