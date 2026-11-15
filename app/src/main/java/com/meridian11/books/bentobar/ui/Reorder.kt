package com.meridian11.books.bentobar.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.round
import com.meridian11.books.bentobar.bar.StripLook
import com.meridian11.books.bentobar.data.BAR_ORDER
import com.meridian11.books.bentobar.data.BarConfig
import com.meridian11.books.bentobar.data.Part
import com.meridian11.books.bentobar.data.Position
import com.meridian11.books.bentobar.data.Store
import com.meridian11.books.bentobar.data.partOf
import com.meridian11.books.bentobar.data.parts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** One stretch of the bar's list a row can be dropped in: a part of an area. */
data class Zone(val area: Position, val part: Part)

/** Where a dragged row would land: a zone, and a place among that zone's other rows. */
data class DropSpot(val zone: Zone, val index: Int)

/**
 * The drop spot for a dragged row whose middle is at [y]. [zones] are the zones' vertical extents, top to
 * bottom (a zone starts at its heading: the list's head, an area's name, the line between the parts); [rows] the
 * middles of each zone's other rows (not the dragged one), in order. In a gap between two zones the [current] spot
 * stays, so the opening doesn't flicker.
 */
fun dropSpot(
    y: Float,
    zones: List<Pair<Zone, ClosedFloatingPointRange<Float>>>,
    rows: Map<Zone, List<Float>>,
    current: DropSpot,
): DropSpot {
    if (zones.isEmpty()) return current
    val zone = zones.firstOrNull { y in it.second }?.first ?: when {
        y < zones.first().second.start -> zones.first().first
        y > zones.last().second.endInclusive -> zones.last().first
        else -> return current
    }
    return DropSpot(zone, rows[zone].orEmpty().count { it < y })
}

/** What a press picked up: a row of the bar's list ([key] its id) or a type's row in the library (its type). */
class Picked(val from: From, val key: String, val at: LayoutCoordinates)

/**
 * The Bar page's one drag. The gesture is on the page's root, over the preview, the bar's card and the panel (a row
 * that changes part is drawn anew, which would cancel a gesture on the row itself), and everything it asks is asked in
 * the root's coordinates: the page's parts register here where they stand, [measure] makes a [DropMap] of them on every
 * move, and [dropAt] says what letting go would do ([drop]). While something is [carried] the list shows an opening
 * where it would land ([preview]), a copy of it follows the pointer in the root's overlay, and nothing is written: the
 * layout changes once, on the drop, through [onDrop]. Esc, or letting go over nothing, puts everything back.
 */
@Stable
class Reorder {
    /** What is being dragged, or null. It stays until its copy has settled or travelled back. */
    var carried by mutableStateOf<Carried?>(null)
        private set
    /** The carried thing's id: a row's, or the one a type gets if it is dropped in the bar. */
    val dragging: String? get() = carried?.id
    /** What letting go right now would do. */
    var drop by mutableStateOf<Drop>(Drop.None)
        private set
    /** A finger carries it: the line under the preview says how a finger cancels. */
    var touch by mutableStateOf(false)
        private set
    /** The pointer is over the preview's bar: the copy is the item's chip there. */
    var inPreview by mutableStateOf(false)
        private set
    /** The stretch of each area along the preview's bar, in the bar's own x: a strip's, or the zone of an area not in use. */
    var areas by mutableStateOf<Map<Position, ClosedFloatingPointRange<Float>>>(emptyMap())
        private set
    /** The copy's width and height as a row. */
    var copyWidth by mutableFloatStateOf(0f)
        private set
    var rowHeight by mutableFloatStateOf(0f)
        private set
    /** A drop on its way to the Store (whose flow delivers it a frame later), so the row doesn't jump back. */
    private var pending by mutableStateOf<Pending?>(null)
    /** The pointer, in the root's coordinates. */
    private var pointer by mutableStateOf(Offset.Zero)
    /** Counts what a drag has to follow up on (the pointer moved, the target changed): frames run only for a while after. */
    var stirred by mutableIntStateOf(0)
        private set
    private var pointerInWindow = Offset.Zero
    private var grab = Offset.Zero
    /** The copy's corner while it glides by itself: into its place, or back where it came from. */
    private var settle by mutableStateOf<Offset?>(null)
    private var lift: Lift? = null
    private var source: LayoutCoordinates? = null

    /** The layout, as the Store has it (set by the page). */
    var config: BarConfig = BarConfig()
    var density = 1f
    /** The page's root: every rectangle here is in its coordinates. */
    var container: LayoutCoordinates? = null
    /** The list's card as it shows, and its content, which scrolls in it. */
    var viewport: LayoutCoordinates? = null
    var content: LayoutCoordinates? = null
    /** Each row's bounds (the opening registers under the carried id), its draggable area and its handle. */
    val rows = HashMap<String, LayoutCoordinates>()
    val grabAreas = HashMap<String, LayoutCoordinates>()
    val handles = HashMap<String, LayoutCoordinates>()
    /** Each zone's heading: a zone runs from its heading to the next one's, the last to the list's end. */
    val headings = HashMap<Zone, LayoutCoordinates>()
    /** The library's rows that can be carried into the bar, the card they scroll in, and where each row's buttons start. */
    val sources = HashMap<Pair<From, String>, LayoutCoordinates>()
    val stops = HashMap<Pair<From, String>, LayoutCoordinates>()
    var libraryView: LayoutCoordinates? = null
    /** The trash: the strip along the list card's lower edge while a row of the bar is carried. A row dropped on it is removed. */
    var trash: LayoutCoordinates? = null
    /**
     * The trash takes this drag's drop: the row was picked up beside it, or the pointer has been off it since. A row
     * picked up where the trash then comes up is not on it by standing still.
     */
    private var armed = false
    private var pickedAt = Offset.Zero
    /** The preview: its bar, the mock clock and system icons in it, each area's strip, and each item's chip as the strip reports it (in the window). */
    var previewBar: LayoutCoordinates? = null
    var clock: LayoutCoordinates? = null
    var icons: LayoutCoordinates? = null
    val strips = HashMap<Position, LayoutCoordinates>()
    val chips = HashMap<String, android.graphics.Rect>()
    /** Where the preview's insertion bar stands, in the root: a chip let go over the preview glides there. */
    var insertion: Offset? = null
    /** The strip's look in the preview and a plain color of its bar, for the chip. */
    var look: StripLook? = null
    var ground: Color = Color.Unspecified
    var scope: CoroutineScope? = null
    /** The drop: the page writes it, in one step of Undo. True if the layout changed. */
    var onDrop: (Carried, Drop) -> Boolean = { _, _ -> false }

    private class Pending(val carried: Carried, val drop: Drop, val config: BarConfig)

    val busy: Boolean get() = carried != null
    /** Something is carried and still follows the pointer. */
    val live: Boolean get() = carried != null && settle == null

    /** The layout to list: with the carried thing where it would land while dragging, else the Store's. */
    fun preview(config: BarConfig): BarConfig {
        val c = carried
        val p = pending
        return when {
            c != null -> config.showing(c, drop)
            p != null && p.config === config -> config.showing(p.carried, p.drop)
            else -> config
        }
    }

    /** The Store has caught up with the drop: the row is where it landed. */
    fun arrived() { pending = null }

    /**
     * The copy's corner, in the root. As a row it hangs from the pointer where it was grabbed and never leaves the
     * window sideways; as a chip ([chip] 1) the pointer holds it by its lower left, and a finger holds it from below,
     * so that the chip shows above the finger and covers neither the words under the preview nor the insertion bar.
     */
    fun copyAt(chip: Float): Offset {
        settle?.let { return it }
        val at = pointer + lerp(-grab, (if (touch) CHIP_BY_FINGER else CHIP_BY_POINTER) * density, chip)
        val wide = container?.takeIf { it.isAttached }?.size?.width ?: return at
        return Offset(at.x.coerceIn(0f, maxOf(0f, wide - copyWidth * (1 - chip))), at.y)
    }

    private fun bounds(c: LayoutCoordinates?, clip: Boolean = false) =
        container?.takeIf { it.isAttached }?.let { root -> c?.takeIf { it.isAttached }?.let { root.localBoundingBoxOf(it, clipBounds = clip) } }

    /**
     * What is under [pos]. In the bar's list: a row, anywhere on it for a mouse or pen, only its handle for touch (so
     * fingers still scroll). In the library: a type's row, left of its button.
     */
    fun hit(pos: Offset, touch: Boolean): Picked? {
        if (bounds(viewport, clip = true)?.contains(pos) == true) {
            val id = (if (touch) handles else grabAreas).entries.firstOrNull { (_, c) -> bounds(c)?.contains(pos) == true }?.key ?: return null
            return rows[id]?.let { Picked(From.BAR, id, it) }
        }
        if (bounds(libraryView, clip = true)?.contains(pos) != true) return null
        val root = container ?: return null
        return sources.entries.firstOrNull { (key, c) ->
            val b = bounds(c) ?: return@firstOrNull false
            // By its place, not its bounds: a stop may have no height, and the bounds of an empty box are all zero.
            b.contains(pos) && pos.x < (stops[key]?.takeIf { it.isAttached }?.let { root.localPositionOf(it, Offset.Zero).x } ?: b.right)
        }?.let { Picked(it.key.first, it.key.second, it.value) }
    }

    /** Picks [p] up with the pointer at [down]. False for something that is no longer there. */
    fun start(p: Picked, down: Offset, touch: Boolean): Boolean {
        val root = container?.takeIf { it.isAttached } ?: return false
        val b = bounds(p.at) ?: return false
        val item = config.items.firstOrNull { it.id == p.key }
        val c = when (p.from) {
            From.BAR -> item?.takeIf { config.partOf(it) != null }?.let { Carried(it.id, From.BAR, it.type) }
            From.TYPE -> Carried(Store.newId(), From.TYPE, p.key)
        } ?: return false
        // A row of the list is drawn inside its line's insets; a row of the library is as wide as it is.
        val inset = if (p.from == From.BAR) ROW_INSET_DP * density else 0f
        val wide = (b.width - 2 * inset).coerceAtLeast(1f)
        // Under a finger the copy is the short, two-line row, and the finger keeps the place it took (the handle).
        copyWidth = minOf(wide, (if (touch) COPY_TOUCH_DP else COPY_MAX_DP) * density)
        rowHeight = if (p.from == From.BAR) b.height else ROW_DP * density
        val along = down.x - b.left - inset
        grab = Offset((if (touch) along else along * copyWidth / wide).coerceIn(0f, copyWidth), (down.y - b.top).coerceIn(0f, rowHeight))
        pointer = down
        pointerInWindow = root.localToWindow(down)
        source = p.at
        pending = null
        settle = null
        armed = false
        pickedAt = down
        this.touch = touch
        lift = config.lift(c)
        drop = lift?.own?.let { own -> Drop.At(DropSpot(own, config.parts(own.area)[own.part].indexOfFirst { it.id == c.id })) } ?: Drop.None
        carried = c
        sync()
        return true
    }

    fun pointerAt(local: Offset) {
        val root = container?.takeIf { it.isAttached } ?: return
        pointerInWindow = root.localToWindow(local)
        stirred++
        sync()
    }

    /** Follows the pointer, also after the content moved under it (auto-scroll, mouse wheel). */
    fun sync() {
        val c = carried ?: return
        val root = container?.takeIf { it.isAttached } ?: return
        if (settle != null) return
        pointer = root.windowToLocal(pointerInWindow)
        val map = measure(c)
        inPreview = map.preview?.contains(pointer) == true
        val next = dropAt(pointer, pointer.y - grab.y + rowHeight / 2, map, lift ?: config.lift(c), drop)
        if (next != drop) { drop = next; stirred++ }
    }

    private fun heads(space: LayoutCoordinates?): Map<Zone, Rect> {
        val s = space?.takeIf { it.isAttached } ?: return emptyMap()
        val all = buildMap { headings.forEach { (zone, h) -> if (h.isAttached) put(zone, s.localBoundingBoxOf(h, clipBounds = false)) } }
        // A line shared by the lanes may still name a lane that has left.
        return all.filterKeys { Zone(it.area, Part.UPPER) in all }
    }

    /** The page as the drag sees it right now. */
    private fun measure(c: Carried): DropMap {
        val shown = preview(config)
        val zones = zoneRects(heads(container), bounds(content)?.bottom ?: 0f)
        val middles = zones.associate { (zone, _) ->
            zone to shown.parts(zone.area)[zone.part].filter { it.id != c.id }.mapNotNull { bounds(rows[it.id])?.center?.y }
        }
        val bar = bounds(previewBar)
        val along = previewAreas()
        if (along != areas) areas = along
        val bin = bounds(trash, clip = true)?.takeIf { c.from == From.BAR }
        if (bin != null && !(bin.contains(pickedAt) && bin.contains(pointer))) armed = true
        val root = container
        fun chipX(id: String) = chips[id]?.let { r -> root?.takeIf { it.isAttached }?.windowToLocal(Offset(r.exactCenterX(), r.exactCenterY()))?.x }
        val chipsAt = buildMap {
            for (area in along.keys) for (part in Part.entries) put(Zone(area, part), config.parts(area)[part].filter { it.id != c.id }.map { chipX(it.id) })
        }
        return DropMap(
            list = bounds(viewport, clip = true), zones = zones, rows = middles,
            preview = bar, areas = if (bar == null) emptyMap() else along.mapValues { (_, r) -> (bar.left + r.start)..(bar.left + r.endInclusive) }, chips = chipsAt,
            trash = bin?.takeIf { armed },
        )
    }

    /** Each area's stretch along the preview's bar: its strip's, or for an area not in use the zone [previewZones] gives it. */
    private fun previewAreas(): Map<Position, ClosedFloatingPointRange<Float>> {
        val bar = previewBar?.takeIf { it.isAttached } ?: return emptyMap()
        fun within(c: LayoutCoordinates?) = c?.takeIf { it.isAttached }?.let { bar.localBoundingBoxOf(it, clipBounds = false) }
        val inUse = buildMap { BAR_ORDER.forEach { area -> within(strips[area])?.takeIf { it.width > 0f }?.let { put(area, it.left..it.right) } } }
        val (lo, hi) = freeSpace(within(clock)?.let { it.left..it.right }, within(icons)?.let { it.left..it.right }, bar.size.width.toFloat(), beside = 16 * density, before = 12 * density)
        return previewZones(lo, hi, inUse, wide = 96 * density, gap = 8 * density, least = 40 * density)
    }

    /** The stretch of [zone]'s rows in the list's content, under its heading: what is tinted while it is the target. */
    fun rowsOf(zone: Zone): Rect? {
        val heads = heads(content)
        val head = heads[zone] ?: return null
        val all = zoneRects(heads, content?.size?.height?.toFloat() ?: 0f).firstOrNull { it.first == zone }?.second ?: return null
        return Rect(all.left, minOf(head.bottom, all.bottom), all.right, all.bottom)
    }

    /**
     * Pixels to scroll the list this frame: faster the closer the pointer is to its upper or lower edge, while it is over
     * the list. The list as it shows ends above the trash, so its lower edge is the trash's upper one, and over the
     * trash nothing scrolls.
     */
    fun edgeSpeed(edge: Float, max: Float): Float {
        if (!live) return 0f
        val visible = viewport?.takeIf { it.isAttached }?.boundsInWindow() ?: return 0f
        val p = pointerInWindow
        if (!visible.contains(p)) return 0f
        return when {
            p.y < visible.top + edge -> -max * ((visible.top + edge - p.y) / edge).coerceAtMost(1f)
            p.y > visible.bottom - edge -> max * ((p.y - (visible.bottom - edge)) / edge).coerceAtMost(1f)
            else -> 0f
        }
    }

    /**
     * Let go. Into the bar: the copy glides into the opening (over the preview, to the insertion bar), then the page
     * writes the drop. On the trash the row is removed at once. Over nothing, or a part that refuses it, it travels back.
     */
    suspend fun release() {
        val c = carried ?: return
        val d = drop
        if (d !is Drop.At && d != Drop.Off) return back(told = d)
        try {
            if (d is Drop.At) {
                val to = if (inPreview) insertion else bounds(rows[c.id])?.let { Offset(it.left + (if (it.width > copyWidth) ROW_INSET_DP * density else 0f), it.top) }
                if (to != null) glide(to, Motion.SETTLE)
            }
            pending = Pending(c, d, config)
            // Nothing changed (the same place): nothing is on its way.
            if (!onDrop(c, d)) pending = null
        } finally {
            clear()
        }
    }

    /** Esc, or a pointer that was taken away: everything back, nothing written. */
    fun cancel() {
        if (!live) return
        scope?.launch { back(told = null) } ?: clear()
    }

    /** The copy travels back where it came from; the page hears of a part that refused it, to say why a little longer. */
    private suspend fun back(told: Drop?) {
        val c = carried ?: return
        try {
            val from = copyAt(if (inPreview) 1f else 0f)
            inPreview = false
            drop = lift?.own?.let { own -> Drop.At(DropSpot(own, config.parts(own.area)[own.part].indexOfFirst { it.id == c.id })) } ?: Drop.None
            settle = from
            // The opening is back in the row's own place a frame later.
            withFrameNanos { }
            val home = bounds(if (c.from == From.BAR) rows[c.id] else source)
            if (home != null) glide(Offset(home.left + (if (c.from == From.BAR) ROW_INSET_DP * density else 0f), home.top), Motion.BACK)
            if (told is Drop.Refused) onDrop(c, told)
        } finally {
            clear()
        }
    }

    private suspend fun glide(to: Offset, ms: Int) {
        val from = settle ?: copyAt(if (inPreview) 1f else 0f)
        settle = from
        animate(Offset.VectorConverter, from, to, animationSpec = Motion.spec(tween(ms))) { v, _ -> settle = v }
    }

    private fun clear() {
        carried = null
        drop = Drop.None
        settle = null
        inPreview = false
        lift = null
        source = null
    }

    private companion object {
        const val ROW_INSET_DP = 12f
        const val ROW_DP = 48f
        /** A row of a wide list is carried at this width, not across half the window. */
        const val COPY_MAX_DP = 440f
        const val COPY_TOUCH_DP = 300f
        val CHIP_BY_POINTER = Offset(-22f, -24f)
        val CHIP_BY_FINGER = Offset(-24f, -64f)
    }
}

/**
 * The drag gesture, on the page's root. It watches the Initial pass so that, once a press has moved the full touch
 * slop, it can consume the movement before the row's click sees it (no click after a drag). Compose's own mouse slop is
 * about 1 dp, which would turn slightly shaky clicks into drags. A mouse or pen picks a row up as soon as it has moved
 * that far; a finger picks a row of the list up by its handle the same way, and a row of the library by holding it
 * still for a long press (a finger that moves first scrolls, one that lifts first taps).
 */
fun Modifier.drags(r: Reorder): Modifier = pointerInput(r) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (r.busy) return@awaitEachGesture
        // Right and middle presses are for menus; touchpads may report no button at all, so those count.
        val buttons = currentEvent.buttons
        if (down.type == PointerType.Mouse && (buttons.isSecondaryPressed || buttons.isTertiaryPressed)) return@awaitEachGesture
        val touch = down.type == PointerType.Touch
        val picked = r.hit(down.position, touch) ?: return@awaitEachGesture
        val slop = viewConfiguration.touchSlop
        var at = down.position
        if (touch && picked.from != From.BAR) {
            val early = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                var travel = Offset.Zero
                var over = false
                while (!over) {
                    val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
                    if (change == null || !change.pressed || change.isConsumed) over = true
                    else {
                        travel += change.position - change.previousPosition
                        if (travel.getDistance() > slop) over = true
                    }
                }
                true
            }
            if (early != null) return@awaitEachGesture
        } else {
            var travel = Offset.Zero
            while (true) {
                val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                if (!change.pressed || change.isConsumed) return@awaitEachGesture
                travel += change.position - change.previousPosition
                at = change.position
                if (travel.getDistance() > slop) {
                    change.consume()
                    break
                }
            }
        }
        if (!r.start(picked, down.position, touch)) return@awaitEachGesture
        r.pointerAt(at)
        // Only the pointer let go is a drop. A pointer the system took away (its cancel comes as every pointer up, already
        // consumed), one that is gone from the event, or a gesture that was interrupted puts everything back, as Esc does.
        var letGo = false
        try {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type == PointerEventType.Scroll) continue // the wheel still scrolls; sync() follows
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) {
                    letGo = !change.isConsumed
                    // The drop is where the pointer was let go, which the last move need not have named (injected
                    // input, a resampled pointer).
                    if (letGo) r.pointerAt(change.position)
                    change.consume()
                    break
                }
                change.consume()
                r.pointerAt(change.position)
            }
        } finally {
            if (r.live) { if (letGo) r.scope?.launch { r.release() } else r.cancel() }
        }
    }
}

/**
 * What the list tells its rows about travelling. [ms]: how long a row takes to its new place, [Motion.NEIGHBOURS], or
 * [Motion.AREA] while the list is changing between one list and lanes; it is read when a row sets off. [standing]:
 * the list has been drawn, so a row that comes now was added to it and opens, where the rows a page starts with are
 * simply there.
 */
internal class ListTravel { var ms = Motion.NEIGHBOURS; var standing = false }
internal val LocalTravel = staticCompositionLocalOf { ListTravel() }

/** Slides a row to its new place when the rows around it change, instead of jumping there. Its size changes at once. */
fun Modifier.animatePlacement(): Modifier = composed {
    val scope = rememberCoroutineScope()
    val holder = remember { PlacementHolder() }
    holder.travel = LocalTravel.current
    onPlaced { c ->
        val t = c.positionInParent().round()
        holder.target = t
        if (holder.anim == null) holder.anim = Animatable(t, IntOffset.VectorConverter)
    }.then(Modifier.layoutOffset(holder, scope))
}

private class PlacementHolder {
    var travel = ListTravel()
    var target = IntOffset.Zero
    var anim: Animatable<IntOffset, AnimationVector2D>? = null
}

// absoluteOffset: positionInParent is physical, and the delta below is too. `offset` would mirror it in right-to-left, and an
// item that moves right there would start on the far side and slide back.
private fun Modifier.layoutOffset(holder: PlacementHolder, scope: CoroutineScope): Modifier =
    absoluteOffset {
        val a = holder.anim ?: return@absoluteOffset IntOffset.Zero
        if (a.targetValue != holder.target) {
            val t = holder.target
            scope.launch { a.animateTo(t, Motion.spec(tween(holder.travel.ms))) }
        }
        a.value - holder.target
    }
