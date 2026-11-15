package com.meridian11.books.bentobar.bar

import com.meridian11.books.bentobar.data.BarConfig
import com.meridian11.books.bentobar.data.Pill
import com.meridian11.books.bentobar.data.Position
import com.meridian11.books.bentobar.data.ROOM_ORDER
import com.meridian11.books.bentobar.data.Section

/**
 * A rectangle on the screen, in plain numbers (`android.graphics.Rect` is a stub in unit tests). It answers with the
 * names `Rect` does, so the code that places the strip reads the same either way.
 */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    fun width() = right - left
    fun height() = bottom - top
    fun centerX() = (left + right) shr 1

    /** As `Rect.intersects`: strict on every side, so a box with no width still counts when it lies inside another. */
    fun intersects(other: Box) = left < other.right && other.left < right && top < other.bottom && other.top < bottom

    /** As `Rect.toShortString`, for the logs. */
    fun toShortString() = "[$left,$top][$right,$bottom]"
}

/**
 * A window as the window list gives it: what kind it is and where. Nothing of what is in it, so a list of these is all
 * [BarRules] ever sees of the windows, and a recorded dump replays through them.
 */
data class WindowFact(val id: Int, val kind: Kind, val box: Box) {
    enum class Kind { SYSTEM, APPLICATION, OVERLAY, OTHER }
}

/** One node of the status bar's tree, in the order the walk met it: the root first, with its depth below the root. */
data class BarNode(
    val id: String,
    val box: Box,
    val depth: Int,
    /** What it says. Looked at for the clock and dropped when the scan ends. */
    val text: String = "",
    /** Whether it has a content description. */
    val described: Boolean = false,
    val clickable: Boolean = false,
    val children: Int = 0,
)

/** Where the status bar is and which part of it is free: what one scan of its tree found. */
data class BarSnapshot(
    /** The status bar window's id, for window screenshots (colour sampling). */
    val windowId: Int,
    val bar: Box,
    /** Empty space BentoBar may use: the desktop bar's spacer, or the widest gap between items. */
    val free: Box,
    /** The clock text, whose colour BentoBar copies. */
    val clock: Box?,
    /** A short fingerprint of the bar's items, for logs. */
    val summary: String,
    /** Where the spacer node was at the scan. It is [free] unless the spacer had no width left. */
    val spacer: Box? = null,
)

/** What one look at the bar knows. The last look's reading and neighbours are facts too: nothing is remembered in here. */
internal data class BarFacts(
    /** The windows the list gave, in its order. It can end early: a window missing from it has not left. */
    val listed: List<WindowFact>,
    /** This look's reading of the bar; null with no bar to read. */
    val reading: BarSnapshot?,
    /** The last look's. */
    val last: BarSnapshot?,
    val enabled: Boolean,
    val interactive: Boolean,
    val locked: Boolean,
    val neighbours: BarNeighbours,
)

/** What one look comes to. */
internal data class BarDecision(
    val show: Boolean,
    /** Why the strip is not shown. Null while it is: placing it says how that went. */
    val status: BarStatus?,
    val cover: WindowFact?,
    /** What to remember for the next look. */
    val neighbours: BarNeighbours,
    /** A reading of the bar's colors this look asks for, if any. */
    val read: BarRules.Read?,
)

/**
 * What the window list and the status bar's tree mean, as plain rules over plain facts. No Android types: the facts are
 * collected in `StatusBarScan` and `BarController.check`, and the cases the devices showed are unit-tested here.
 *
 * There is no state in here and no state machine for the bar: each rule is a function of one look. The controller still
 * does everything that touches Android, and in the order that matters.
 */
internal object BarRules {
    /**
     * What a change of the layout has to do with the bar: which area each item is in and what the areas hold. Everything
     * else (a label typed letter by letter, a colour) the strip draws by itself, and the bar needs no second look for it.
     */
    data class Geometry(val enabled: Boolean, val pill: Pill, val spacing: Int, val items: List<Triple<String, Section, Position>>)

    fun geometry(cfg: BarConfig) = Geometry(cfg.enabled, cfg.pill, cfg.spacing, cfg.items.map { Triple(it.id, it.section, it.area) })

    /** What asks [decide] for a reading of the bar's colors. */
    enum class Read { NEW_BAR_WINDOW, WINDOWS_MOVED }

    /** What the two-second look does. */
    enum class LightLook { FULL, WINDOWS, SPACER }

    /** [BarSnapshot.summary] when the window had no node tree to read: its free area is unknown. */
    const val NO_TREE = "no-tree"
    private val timeRe = Regex("""\d{1,2}[:.]\d{2}""")

    /** The status bar window: a system window along the top edge, shorter than [maxHeight]. */
    fun findBar(listed: List<WindowFact>, screenWidth: Int, maxHeight: Int): WindowFact? =
        listed.firstOrNull { w -> w.kind == WindowFact.Kind.SYSTEM && w.box.top == 0 && w.box.height() in 1..maxHeight && w.box.width() >= screenWidth / 2 }

    /** Whether the last reading still stands: a cheap look at the same bar window in the same place reads no node. */
    fun keepsReading(full: Boolean, last: BarSnapshot?, bar: WindowFact) = !full && last != null && last.windowId == bar.id && last.bar == bar.box

    /**
     * The reading a scan comes to: what it [read], or null if it threw. No node tree (SystemUI mid-change): the whole bar
     * would cover its icons, so the last reading of this same bar stands; with none, stay hidden until a scan can read it.
     */
    fun settled(read: BarSnapshot?, last: BarSnapshot?): BarSnapshot? = when {
        read == null -> null
        read.summary != NO_TREE -> read
        else -> last?.takeIf { it.windowId == read.windowId && it.bar == read.bar }
    }

    /**
     * What the look every two seconds does, [sinceFullMs] after the last full scan. Without content events the node cache
     * goes stale, so a full scan comes every 30 s whatever else happens. A bar without the spacer node has nothing cheap
     * to re-check: a full scan (with clearCache) every 2 s is costly, so between full scans only the window list is read.
     */
    fun lightLook(hasSpacer: Boolean, sinceFullMs: Long): LightLook = when {
        !hasSpacer -> if (sinceFullMs > 10_000) LightLook.FULL else LightLook.WINDOWS
        sinceFullMs > 30_000 -> LightLook.FULL
        else -> LightLook.SPACER
    }

    /** Whether the node with this resource id is the desktop bar's spacer. */
    fun isSpacer(id: String) = id.endsWith("DesktopStatusBarSpacer")

    /**
     * What the tree of the status bar window [windowId], at [bar], says about the bar. [nodes] are in the walk's order.
     * No nodes, or a root with no children (SystemUI mid-change), is no tree: its free area is unknown. A root with children
     * and nothing labelled under it is a tree, or a bar with no spacer would have no widest gap to fall back on.
     */
    fun parse(windowId: Int, bar: Box, nodes: List<BarNode>): BarSnapshot {
        if (nodes.isEmpty() || nodes.first().children == 0) return BarSnapshot(windowId, bar, bar, null, NO_TREE)
        var spacer: Box? = null
        var clock: Box? = null
        val parts = ArrayList<Box>()
        val names = StringBuilder()
        for (n in nodes) {
            if (isSpacer(n.id)) spacer = n.box
            if (clock == null && n.text.isNotEmpty() && timeRe.containsMatchIn(n.text)) clock = n.box
            // Leaf-ish pieces that take room: anything clickable or labelled that isn't the whole bar.
            val labelled = n.clickable || n.described || n.text.isNotEmpty()
            if (labelled && n.box.width() in 1 until bar.width() * 6 / 10 && n.box.height() > 0) {
                parts += n.box
                if (n.depth <= 6 && n.id.isNotEmpty()) names.append(n.id.substringAfterLast('/')).append(' ')
            }
        }
        val free = spacer?.takeIf { it.width() > 0 } ?: widestGap(bar, parts)
        return BarSnapshot(windowId, bar, free, clock, names.toString().trim(), spacer)
    }

    /**
     * Whether the strip shows, and what follows from this look at the windows. [titleSaysScreenshot] is the one question
     * about a title, asked of a window only once everything else says it covers the bar, and of no window after the first
     * that does.
     */
    fun decide(f: BarFacts, titleSaysScreenshot: (WindowFact) -> Boolean): BarDecision {
        val s = f.reading
        // On tablets the shade and Quick Settings slide over the status bar; our overlay would sit
        // on top of them. Only a big system window counts: on the desktop bar the panels open as
        // popups below the bar, and small system UI near the top shouldn't blink BentoBar away.
        // The screenshot UI is a big, mostly see-through system window too ("Screenshot preview",
        // full screen while it animates), but it doesn't cover the bar: hiding for it made the strip
        // vanish for the seconds the preview shows.
        val cover = if (s == null) null else f.listed.firstOrNull { w ->
            w.kind == WindowFact.Kind.SYSTEM && w.id != s.windowId && w.box.intersects(s.free) &&
                w.box.height() >= s.bar.height() * 4 && !titleSaysScreenshot(w)
        }
        val show = s != null && cover == null && f.enabled && f.interactive && !f.locked
        val status = if (show) null else when {
            !f.interactive || f.locked -> BarStatus.ASLEEP
            !f.enabled -> BarStatus.HIDDEN_BY_USER
            s == null -> BarStatus.NO_BAR
            else -> BarStatus.COVERED
        }
        // An app window settled against the bar's lower edge (maximized, or snapped to a side), or a
        // full-screen one lies under the bar: SystemUI then gives the bar a background of its own, or that
        // app's light or dark icons, and takes them away when the window leaves, without an event of the
        // bar's own. Only the windows' ids and bounds are looked at, from the list at hand.
        val (neighbours, moved) = if (s == null) f.neighbours to false else f.neighbours.next(s.bar.bottom, s.bar.width(),
            f.listed.filter { it.kind == WindowFact.Kind.APPLICATION }.map { w -> AppWindow(w.id, w.box.left, w.box.top, w.box.right, w.box.bottom) })
        val read = when {
            !show -> null
            // A new status bar window can look different.
            s?.windowId != f.last?.windowId -> Read.NEW_BAR_WINDOW
            moved -> Read.WINDOWS_MOVED
            else -> null
        }
        return BarDecision(show, status, cover, neighbours, read)
    }

    // ---- room: which areas have a strip, where and how wide ------------------------------------------------

    /**
     * One area's strip as the controller knows it. [measured] and [empty] are the last known, kept while it has no window.
     * [reach] is how wide it is with a ‹'s whole room beside the items it keeps, whether it draws a ‹ or not; 0 while that
     * is not known.
     */
    data class AreaState(val measured: Int, val empty: Boolean, val shown: Boolean, val pending: Boolean, val reach: Int = 0)

    /**
     * Where one strip goes. The gravity is the area's own (Right: top and right; Left: top and left; Middle: top and centred),
     * and [x] is the window's x under it. [budget] is what the strip may fill: its room less its own two ends.
     */
    data class Spot(val area: Position, val x: Int, val width: Int, val budget: Int)

    /** What [arrange] comes to. */
    data class Arranged(
        /** The strips to have on screen, in room order. Never empty. */
        val spots: List<Spot>,
        /** Areas whose strip is shown now and has no spot: their windows go. */
        val leave: List<Position>,
        /** Areas in use with no spot: their items are shown as not fitting. */
        val owed: Set<Position>,
        /** The first spot's area: the strip that does what the bar does once. */
        val carrier: Position,
        /** No strip was shown before this placing. */
        val first: Boolean,
    )

    /**
     * The order in which [spots] are carried out. [widths] is how wide each area's window is on screen now; an area with
     * no window is not in it. A strip that gets wider, or comes, takes room, and takes it last: by then whatever it pushes
     * has moved or narrowed, so it never stands in room a neighbour has not left yet. Room order otherwise.
     */
    fun givingWayFirst(spots: List<Spot>, widths: Map<Position, Int>): List<Spot> = spots.sortedBy { it.width > (widths[it.area] ?: 0) }

    /** The room an area may use, from [left] to [right] on the screen: for [arrange], and for the settings preview. */
    data class Lane(val left: Int, val right: Int) {
        val max get() = right - left
    }

    /**
     * An area's room in the free space from [lo] to [hi]: what the areas asked before it leave. Asked in room order
     * ([ROOM_ORDER]), Left has all of it, Right what Left leaves and the Middle what both leave. A neighbour costs what it
     * uses ([usedRight], [usedLeft]; 0 for one not asked yet) and the gap [between]; one that uses nothing costs nothing.
     */
    fun lane(area: Position, lo: Int, hi: Int, between: Int, usedRight: Int, usedLeft: Int): Lane {
        val left = lo + if (usedLeft > 0) usedLeft + between else 0
        val right = hi - if (usedRight > 0) usedRight + between else 0
        return when (area) {
            Position.LEFT -> Lane(lo, right)
            Position.RIGHT -> Lane(left, hi)
            Position.CENTER -> Lane(left, right)
        }
    }

    /**
     * Where a Middle strip [width] wide begins, [natural] being where it begins when centred: there, or as near to there as
     * its [lane] lets it be. It is moved only as far as it must, never centred again in the room that is left (which would
     * make it jump by tens of pixels when a neighbour grows by one). A strip wider than its lane stays where it is.
     */
    fun middleLeft(lane: Lane, width: Int, natural: Int): Int =
        if (width > lane.max) natural else natural.coerceIn(lane.left, lane.right - width)

    /** An area the controller has no strip for yet: never measured, not on screen. */
    private val unknown = AreaState(measured = 0, empty = false, shown = false, pending = false)

    /**
     * The areas that want a strip, in the order they are given room: those in use, and the [home] area as well, so there is
     * always one. The bar places them ([arrange]) and the settings preview draws one strip for each.
     */
    fun wantedAreas(home: Position, inUse: Set<Position>): List<Position> = ROOM_ORDER.filter { it in inUse || it == home }

    /**
     * Which areas have a strip, where and how wide: the one rule of room, which the controller only carries out. In pixels.
     * The free area is [freeLeft] to [freeRight] on a screen [screenW] wide; [gap] is kept to its edges, less [out] (what a
     * strip's own ends may reach into the gap: all of [ends] with no pill, nothing with one); [between] is kept between two
     * strips; [least] is the room a ‹ needs with its ends. [inUse] are the areas with an item; the [home] area
     * is wanted as well, so there is always one. [strips] is what is known of each area's strip; an area missing from it
     * was never measured.
     *
     * Room goes to Left first, then Right, then the Middle. The first area there is has the whole free space and is as wide
     * as it was measured, which is the one strip there was before areas, to the pixel. An area after it is cut to its room,
     * and with less room than [least] it has no strip: it is owed. A strip that draws nothing, or was never measured, has
     * its spot and takes no room from the others. With no room for a ‹ at all the home area alone is placed, as the one
     * strip was: uncut, its ‹ over its neighbours.
     *
     * One exception, for the first strip once its reach is known ([AreaState.reach]): the others stand behind its reach,
     * not its width, and do so when it draws nothing too. Its ‹ comes and goes for the areas that are owed, so room that
     * followed its width would follow that ‹: an area would get room because it had none and lose it again because it
     * had some. The reach is the same with and without the ‹, so nothing behind the first strip can tell. With one area
     * nothing is behind it.
     */
    fun arrange(
        freeLeft: Int, freeRight: Int, screenW: Int, gap: Int, out: Int, ends: Int, between: Int, least: Int,
        home: Position, inUse: Set<Position>, strips: Map<Position, AreaState>,
    ): Arranged {
        val lo = freeLeft + gap - out
        val hi = freeRight - gap + out
        val total = maxOf(0, hi - lo)
        // A centred window's x counts from the middle of the screen: the free area's middle against it.
        val x0 = ((freeLeft + freeRight) shr 1) - screenW / 2
        val wanted = wantedAreas(home, inUse)
        fun state(area: Position) = strips[area] ?: unknown
        fun budget(max: Int) = maxOf(0, max - 2 * ends)
        fun x(area: Position, lane: Lane, width: Int, first: Boolean) = when (area) {
            Position.RIGHT -> screenW - hi
            Position.LEFT -> lo
            // A Middle with nothing before it has nothing to clear, and stays where the one centred strip stood: when it
            // fills its room, rounding can put that a pixel outside the room, and holding it to the room would move it.
            Position.CENTER -> if (first) x0 else {
                // Where the window manager puts a centred window's left edge.
                val natural = (screenW - width) / 2 + x0
                x0 + (middleLeft(lane, width, natural) - natural)
            }
        }

        val spots = ArrayList<Spot>()
        if (total < least) {
            val width = maxOf(1, state(home).measured)
            spots += Spot(home, x(home, Lane(lo, hi), width, first = true), width, budget(total))
        } else {
            var usedRight = 0
            var usedLeft = 0
            for (area in wanted) {
                val first = spots.isEmpty()
                val lane = lane(area, lo, hi, between, usedRight, usedLeft)
                if (!first && lane.max < least) continue
                val known = state(area)
                val width = maxOf(1, if (first) known.measured else minOf(known.measured, lane.max))
                spots += Spot(area, x(area, lane, width, first), width, budget(lane.max))
                // Never measured, its width is the one pixel a window must have: no room to keep clear, and no gap.
                val used = if (first && known.reach > 0) known.reach else if (known.empty || known.measured <= 0) 0 else width
                if (area == Position.RIGHT) usedRight = used else if (area == Position.LEFT) usedLeft = used
            }
        }
        val placed = spots.map { it.area }
        return Arranged(
            spots = spots,
            leave = ROOM_ORDER.filter { strips[it]?.shown == true && it !in placed },
            owed = wanted.filter { it !in placed }.toSet(),
            carrier = placed.first(),
            first = strips.values.none { it.shown },
        )
    }

    /** The widest horizontal stretch of the bar that no item covers. */
    private fun widestGap(bar: Box, parts: List<Box>): Box {
        val spans = parts.map { it.left to it.right }.sortedBy { it.first }
        var best = Box(bar.left, bar.top, bar.left, bar.bottom)
        var cursor = bar.left
        for ((l, r) in spans) {
            if (l > cursor && l - cursor > best.width()) best = Box(cursor, bar.top, l, bar.bottom)
            cursor = maxOf(cursor, r)
        }
        if (bar.right - cursor > best.width()) best = Box(cursor, bar.top, bar.right, bar.bottom)
        return best
    }
}
