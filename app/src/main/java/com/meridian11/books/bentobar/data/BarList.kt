package com.meridian11.books.bentobar.data

import com.meridian11.books.bentobar.items.ItemType
import com.meridian11.books.bentobar.items.Items

// How the settings read a layout: what a new item is, and the two parts of the bar's list. Pure, and nothing here is stored.

/**
 * What the library's + makes of a type: in the bar, or for a type that speaks up now and then ([ItemType.addsWhenActive])
 * waiting with its rule on. The one definition of a fresh item.
 */
fun ItemType.fresh(id: String, area: Position): ItemConfig =
    ItemConfig(id, type, if (addsWhenActive) Section.HIDDEN else Section.SHOWN, whenActive = addsWhenActive, options = defaultOptions(), area = area)

/** The two parts of the bar's list: what is always in the bar, and under the line what waits (for its rule, or behind ‹). */
enum class Part { UPPER, LOWER }

/** One area's rows, in the order they are listed. */
data class Parts(val upper: List<ItemConfig>, val lower: List<ItemConfig>) {
    operator fun get(part: Part): List<ItemConfig> = if (part == Part.UPPER) upper else lower
    val size: Int get() = upper.size + lower.size
}

/**
 * The part [item] is listed in, or null for one that is not in the bar (an Off item, which no layout the app holds has:
 * [withoutOff]). In Show everything, the default mode, the lower
 * part is "When active": exactly the Hidden items with their rule on. A Hidden item without one is drawn all the time
 * there, so it is listed above. With ‹ the lower part is "Hidden": every Hidden item.
 */
fun BarConfig.partOf(item: ItemConfig): Part? = when (item.section) {
    Section.OFF -> null
    Section.SHOWN -> Part.UPPER
    Section.HIDDEN -> if (hiddenMode == HiddenMode.SHOW_ALL && !item.whenActive) Part.UPPER else Part.LOWER
}

/**
 * The rows of [area], each part in the list's order, which is the strip's left to right; Next meeting first in its part,
 * as the strip draws it ([stripPlan]). Items of a type this version doesn't know are listed too: they are in the layout.
 */
fun BarConfig.parts(area: Position): Parts {
    val (upper, lower) = items.filter { it.area == area }
        .sortedBy { if (it.type == Store.PINNED_LEFT) 0 else 1 }
        .partition { partOf(it) == Part.UPPER }
    return Parts(upper, lower)
}

/**
 * The areas that hold a row of the bar's list, in the bar's order ([BAR_ORDER]): each with an item, of any
 * type. An item of a type this version doesn't know has its row, so it counts here, where the bar's [areasInUse] leaves
 * it out: a question about the bar's room asks that one. None with nothing in the bar. The one count of the list's areas:
 * [listAreas], [usesAreas], [oneArea] and the Bar page's lanes read it.
 */
fun BarConfig.rowAreas(): List<Position> = BAR_ORDER.filter { a -> items.any { it.area == a } }

/**
 * The areas the bar's list has rows for, Left before Middle before Right; with no item in the bar, the one a new item
 * goes to. More than one is [usesAreas].
 */
fun BarConfig.listAreas(): List<Position> = rowAreas().ifEmpty { listOf(homeArea()) }

/**
 * Whether [item] can be put in the lower part. In the default mode that takes a "Show when…" rule, which not every type
 * has; behind ‹ anything can wait. An item of a type this version doesn't know stays where it is.
 */
fun BarConfig.canGoBelow(item: ItemConfig, type: ItemType? = Items.of(item.type)): Boolean =
    type != null && (hiddenMode != HiddenMode.SHOW_ALL || type.canBeActive)

/** How an item stands in the bar, as Visibility words it in settings and in the bar's own menu. */
enum class Standing {
    /** Always in the bar. */
    ALWAYS,
    /** The default mode's lower part: in the bar only while its "Show when…" rule applies. */
    WHEN_ACTIVE,
    /** Behind ‹ (and popping out by its rule, if that is on). */
    BEHIND,
}

/** How [item] stands, or null for one that is not in the bar: its [part][partOf], in the words of the hidden mode. */
fun BarConfig.standing(item: ItemConfig): Standing? = when (partOf(item)) {
    null -> null
    Part.UPPER -> Standing.ALWAYS
    Part.LOWER -> if (hiddenMode == HiddenMode.SHOW_ALL) Standing.WHEN_ACTIVE else Standing.BEHIND
}
