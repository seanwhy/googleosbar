package com.meridian11.books.bentobar.data

/**
 * What BentoBar had and has no more: the item types it took out, the online services only they asked and what they kept
 * on this install outside the layout. The one list, read by [readLayout] and [Online.init]. Pure. The Off section went
 * the same way ([withoutOff]).
 *
 * An item of a type this version doesn't know otherwise stays in the layout (it may be from a newer version) and has a
 * row in Settings that says so. An item of a retired type is from an older one and will never be known again, so a
 * layout that is read drops it, with its options, and the next save writes the layout without it. That needs no new
 * layout version: what is left is a layout of the same schema, and an older version reads it as it reads any other.
 */
object Retired {
    /**
     * Clipboard (1.6 to 2.0: its pinned snippets were its options, in the layout, and its recent copies in memory only)
     * Crypto (only in 2.0's test builds; its prices were in memory only), and Shelf and Value from a URL (2.1 only; what
     * they held was on this install, see [kept]).
     */
    val types: Set<String> = setOf("clipboard", "crypto", "shelf", "webvalue")

    /** The ids in `Online`'s file of the services only a retired type asked: Crypto's Kraken, and Value from a URL's "Your own addresses". */
    val services: Set<String> = setOf("kraken", "own")

    /**
     * The folders under `kept/` in the no-backup directory (see [Kept.own]) that only a retired type wrote: the texts and
     * links a Shelf held and the address of each Value from a URL, a file per item. [Online.init] deletes them at each
     * start, which is the first start of a version without those types and nothing after that.
     */
    val kept: Set<String> = setOf("shelf", "webvalue")
}

/** This layout without the items of a [Retired] type (each with its options), or this very layout when it has none. */
fun BarConfig.withoutRetired(): BarConfig =
    if (items.none { it.type in Retired.types }) this else copy(items = items.filterNot { it.type in Retired.types })

/**
 * This layout without its Off items, or this very layout when it has none. Up to 2.1 an item taken out of the bar could
 * wait in [Section.OFF], to be brought back as it had been set up. Remove now deletes the item, so nothing is put there
 * and nothing looks there: a layout that is read drops what an older version left in Off, as it drops a [Retired] type's
 * items, and the next save writes it without them. No new layout version, for the same reason. [Store.update] asks too,
 * so no layout the app holds has one, whatever a change made.
 */
fun BarConfig.withoutOff(): BarConfig =
    if (items.none { it.section == Section.OFF }) this else copy(items = items.filterNot { it.section == Section.OFF })
