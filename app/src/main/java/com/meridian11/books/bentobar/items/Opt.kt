package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.data.ItemConfig

/**
 * One option of an item type, declared once: its key in [ItemConfig.options], its default, and how it is read and written.
 * The bar and the settings read the same declaration, so a default or a key can't come apart between what the bar does and
 * what settings show. [parse] says what a layout's value means, and null for what can't be used: a layout can be pasted in and
 * can say anything, so a range or a limit is checked on the way in, here, once. [encode] says what is stored, null for
 * no key at all. A type that gets options, or is touched for another reason, declares them like this ([EventItem.chars]);
 * the older types still read by key and move over as they are touched. Pure.
 */
open class Opt<T : Any>(
    val key: String,
    val default: T,
    private val parse: (String) -> T?,
    private val encode: (T) -> String? = { it.toString() },
) {
    /** What [item] says, or the default where it says nothing that can be used. */
    fun of(item: ItemConfig): T = stored(item) ?: default

    /** What [item] says, or null: for a default that isn't a constant (a word in the app's language). */
    fun stored(item: ItemConfig): T? = item.options[key]?.let(parse)

    /** [item] with this option set to [value]. */
    fun set(item: ItemConfig, value: T): ItemConfig = item.with(key, encode(value))

    companion object {
        fun bool(key: String, default: Boolean) = Opt(key, default, { it.toBooleanStrictOrNull() })

        /** One of [allowed], as the layout stores it; [default] for anything else. */
        fun oneOf(key: String, default: String, vararg allowed: String) = Opt(key, default, { it.takeIf { v -> v in allowed } })

        /**
         * Text that came from a layout, cleaned once on its way in ([TextRules.oneLine]): at most [max] characters on one line,
         * with nothing that turns the text around. Saved cut at a character, not inside one. A blank one removes the key
         * unless [clearedIsUnset] is false (a Text item that is meant to be empty).
         */
        fun text(key: String, max: Int, default: String = "", clearedIsUnset: Boolean = true) = Opt(key, default,
            { TextRules.oneLine(it, max) },
            { v -> TextRules.first(v, max).let { if (clearedIsUnset && it.isBlank()) null else it } })
    }
}

/** A whole number held in [range] when it is read: the settings' own limits, whatever a layout says. */
open class IntOpt(key: String, default: Int, val range: IntRange) : Opt<Int>(key, default, { it.toIntOrNull()?.coerceIn(range) })
