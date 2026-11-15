package com.meridian11.books.bentobar.data

import com.meridian11.books.bentobar.items.ItemType
import com.meridian11.books.bentobar.items.Items

// What the user can ask of an item's section or area, as steps from one layout to the next: the one rule for each command, whichever
// surface asks (the rows, their keys and accessibility actions, Options in settings, the bar's right-click menu). A
// command changes an item's section or its area, never its place in the list: in Show everything, the default mode, Hidden items are
// drawn, so a Hide that hides nothing must not make the item jump along the strip. Only a Move, a drag or a drop changes the
// place ([placed], with an index). A screen calls `Store.update { it.hide(id) }` and writes no section or Show when rule of its
// own, so one command can't place an item one way here and another way there (`LayoutIntentsTest` holds the screens to it).
// Pure; an id that isn't in the layout changes nothing.

/**
 * Into [section], where it stands in the list; in a settings card that is its list order, not last. Already in [section]:
 * nothing changes, so the settings' chooser can ask for whichever chip was clicked.
 */
fun BarConfig.putIn(id: String, section: Section): BarConfig =
    if (items.none { it.id == id && it.section != section }) this else copy(items = items.map { if (it.id == id) it.copy(section = section) else it })

fun BarConfig.show(id: String): BarConfig = putIn(id, Section.SHOWN)

fun BarConfig.hide(id: String): BarConfig = putIn(id, Section.HIDDEN)

/**
 * Into [area], where it stands in the list: among that area's items it is where the list has it, not last. Already in
 * [area]: nothing changes.
 */
fun BarConfig.putInArea(id: String, area: Position): BarConfig =
    if (items.none { it.id == id && it.area != area }) this else copy(items = items.map { if (it.id == id) it.copy(area = area) else it })

/**
 * Into [area] by a click or a key (Area in the options, Alt with an arrow): the item keeps its part and its section, and
 * lands where it came in from. Moving right it is the first of its part there, moving left the last, so a row that
 * crosses to the lane beside it stands next to where it stood. Next meeting takes the area and is drawn first there as
 * everywhere.
 */
fun BarConfig.landedIn(id: String, area: Position): BarConfig {
    val item = items.firstOrNull { it.id == id } ?: return this
    if (item.area == area) return this
    val there = putInArea(id, area)
    val part = partOf(item) ?: return there
    if (item.type == Store.PINNED_LEFT) return there
    val others = there.parts(area)[part].filter { it.id != id && it.type != Store.PINNED_LEFT }
    if (others.isEmpty()) return there
    val moved = there.items.first { it.id == id }
    val rest = there.items.filterNot { it.id == id }
    val right = BAR_ORDER.indexOf(area) > BAR_ORDER.indexOf(item.area)
    val before = if (right) rest.indexOfFirst { it.id == others.first().id } else rest.indexOfFirst { it.id == others.last().id } + 1
    return there.copy(items = rest.toMutableList().apply { add(before, moved) })
}

/**
 * Every item into [area], so the layout is a one-area layout again.
 * A layout with no items has no area to change: there the place is kept in [BarConfig.position], which says where the
 * empty bar is and where its first item goes ([homeArea]). With items, `position` is left as it was ([without] writes
 * it when the last one goes).
 */
fun BarConfig.putAllIn(area: Position): BarConfig = when {
    items.isEmpty() -> if (position == area) this else copy(position = area)
    items.all { it.area == area } -> this
    else -> copy(items = items.map { it.copy(area = area) })
}

/**
 * The layout without the item [id]: Remove from the bar, whichever surface asks (the options' button, Delete, a drag
 * out of the bar, the bar's own menu). Nothing of the item is remembered: the library's + makes a new one, and Undo is
 * the way back to this one ([undone]). When the last item goes, the bar stays where it was: `position` takes the
 * [home area][homeArea] the layout had with it, so the empty bar stands there and the next item goes there, whichever
 * command had put the items in that area.
 */
fun BarConfig.without(id: String): BarConfig {
    val rest = items.filterNot { it.id == id }
    return when {
        rest.size == items.size -> this
        rest.isEmpty() -> copy(items = rest, position = homeArea())
        else -> copy(items = rest)
    }
}

/**
 * The layout without [area]: Remove an area on the Bar page. Every item of it goes, as [without] takes one: the ones
 * under the line and one of a type this version doesn't know too (each has its row there, [rowAreas]); the other areas
 * stay as they are. One step, so one Undo brings them all back, each in its part and place ([undone]). When they were
 * the bar's last, the empty bar stays where the bar was, as [without] leaves it.
 */
fun BarConfig.withoutArea(area: Position): BarConfig {
    val rest = items.filterNot { it.area == area }
    return when {
        rest.size == items.size -> this
        rest.isEmpty() -> copy(items = rest, position = homeArea())
        else -> copy(items = rest)
    }
}

/**
 * The "Show when…" rule on or off. Only hidden items pop into the bar, so turning it on brings a shown item
 * into Hidden, and like every command it keeps the item's place in the list: it pops out where it stood in the bar. Turning
 * it off changes no section.
 */
fun BarConfig.setWhenActive(id: String, on: Boolean): BarConfig = copy(items = items.map {
    if (it.id == id) it.copy(whenActive = on, section = if (on) Section.HIDDEN else it.section) else it
})

/**
 * [id] in [area] and [part] at [index] among that part's rows there, as [parts] lists them without it (the end if out of
 * range): a drag's drop, and each step of Move up and Move down. Above the line is [show]. Under it is the rule
 * ([setWhenActive]) in the default mode and [hide] with ‹; an item that can't wait there ([canGoBelow]) changes nothing.
 * So a Hidden item without a rule, which the default mode lists above, is Shown after any move. Next meeting takes the
 * part and the area and keeps its place: it is drawn first in its area wherever the list has it.
 */
fun BarConfig.placed(id: String, area: Position, part: Part, index: Int): BarConfig {
    val item = items.firstOrNull { it.id == id } ?: return this
    val there = when {
        part == Part.UPPER -> show(id)
        !canGoBelow(item) -> return this
        hiddenMode == HiddenMode.SHOW_ALL -> setWhenActive(id, true)
        else -> hide(id)
    }.putInArea(id, area)
    if (item.type == Store.PINNED_LEFT) return there
    val rows = there.parts(area)[part].filter { it.id != id }
    val others = rows.filter { it.type != Store.PINNED_LEFT }
    if (others.isEmpty()) return there // nothing to stand before or after: where the list has it is as good as any place
    val at = (index - (rows.size - others.size)).coerceIn(0, others.size)
    val moved = there.items.first { it.id == id }
    val rest = there.items.filterNot { it.id == id }
    val before = if (at < others.size) rest.indexOfFirst { it.id == others[at].id } else rest.indexOfFirst { it.id == others.last().id } + 1
    val list = rest.toMutableList().apply { add(before, moved) }
    return if (list == there.items) there else there.copy(items = list)
}

/** What one step of Move up or Move down comes to. */
sealed interface Step {
    /** The item moved: the layout after it. */
    data class Moved(val config: BarConfig) : Step
    /** Nothing moved, and the user is told why. */
    data class Stays(val why: Why) : Step
    /** The first row going up or the last going down: nothing to do and nothing to say. */
    data object End : Step

    enum class Why {
        /** The default mode's lower part takes a "Show when…" rule, and this type has none. */
        NO_RULE,
        /** Next meeting is always first in its area. */
        PINNED,
    }
}

/**
 * One step up or [down] in the bar's list, within the item's area. At a part's end the step crosses the line: down from
 * the last row above it puts the item under it (its rule on in the default mode, behind ‹ otherwise) as the first there,
 * up from the first row under it puts it above as the last.
 */
fun BarConfig.stepped(id: String, down: Boolean): Step {
    val item = items.firstOrNull { it.id == id } ?: return Step.End
    val part = partOf(item) ?: return Step.End
    if (item.type == Store.PINNED_LEFT) return Step.Stays(Step.Why.PINNED)
    val all = parts(item.area)
    val rows = all[part]
    val at = rows.indexOfFirst { it.id == id }
    val next = rows.getOrNull(if (down) at + 1 else at - 1)?.takeIf { it.type != Store.PINNED_LEFT }
    return when {
        next != null -> Step.Moved(placed(id, item.area, part, if (down) at + 1 else at - 1))
        down && part == Part.UPPER -> when {
            canGoBelow(item) -> Step.Moved(placed(id, item.area, Part.LOWER, 0))
            Items.of(item.type) != null -> Step.Stays(Step.Why.NO_RULE)
            else -> Step.End
        }
        !down && part == Part.LOWER -> Step.Moved(placed(id, item.area, Part.UPPER, all.upper.size))
        else -> Step.End
    }
}

/**
 * A new item of [type], with [id], dropped at a place in the bar: what the + makes ([fresh]), then [placed]. Under a line
 * it can't wait under, nothing is added.
 */
fun BarConfig.addedAt(type: ItemType, id: String, area: Position, part: Part, index: Int): BarConfig {
    if (items.any { it.id == id }) return this
    val new = type.fresh(id, area)
    if (part == Part.LOWER && !canGoBelow(new, type)) return this
    return copy(items = with(Store) { items.added(new) }).placed(id, area, part, index)
}

/**
 * A new item of [type] while it is in no part of the bar yet (carried from the library, over no place): what the +
 * makes ([fresh]) without its rule, so nothing about it says when it would show. It is drawn, never stored.
 */
fun BarConfig.unplaced(type: ItemType, id: String): ItemConfig = type.fresh(id, homeArea()).copy(whenActive = false)

/** What a layout change changes: which items there are and in which order, each one's section, rule and area, and where an empty bar stands. */
private fun BarConfig.arrangement(): Any = items.map { listOf(it.id, it.section, it.whenActive, it.area) } to position

/**
 * Undo of one layout change that led from [before] to [after]: the items [before] had, in its order, each in its section
 * and area and with its rule as it was; an item the change took out comes back as it was, with every option. One it
 * brought in (the +, a drop from the library, Duplicate) goes, whatever was done to it since: taking the change back
 * leaves nothing behind. What is not layout stays as it is now (an option changed since). Null when the layout was
 * arranged otherwise since (the bar's own menu, a pasted layout): there is nothing left to take back.
 */
fun BarConfig.undone(before: BarConfig, after: BarConfig): BarConfig? {
    if (arrangement() != after.arrangement()) return null
    val now = items.associateBy { it.id }
    return copy(position = before.position, items = before.items.map { was ->
        now[was.id]?.copy(section = was.section, whenActive = was.whenActive, area = was.area) ?: was
    })
}
