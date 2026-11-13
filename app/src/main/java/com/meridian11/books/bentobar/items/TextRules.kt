package com.meridian11.books.bentobar.items

import java.text.BreakIterator
import java.util.Locale

/**
 * Text that comes from outside (a layout, a service's reply) as the bar and the menus may show it,
 * and its length as a reader counts it: names, labels and the text of a search. Pure Kotlin.
 */
object TextRules {
    /**
     * [text] as one line of at most [max] characters: line breaks, tabs and other control characters
     * become single spaces, and the characters that override the direction of the text after them
     * are taken out (a label must not turn the bar's own numbers around). What a script needs to
     * join or part its letters stays. Never more work than a few times what can stay, whatever comes in.
     */
    fun oneLine(text: String, max: Int): String {
        var head = if (text.length > max * 32) text.substring(0, max * 32) else text
        if (head.isNotEmpty() && head.last().isHighSurrogate()) head = head.dropLast(1)
        val flat = StringBuilder(head.length)
        var gap = false
        for (c in head) {
            when {
                // Embeddings, overrides and isolates, with their ends.
                c.code in 0x202A..0x202E || c.code in 0x2066..0x2069 -> {}
                c.isWhitespace() || c.isISOControl() || c.code == 0x2028 || c.code == 0x2029 -> gap = flat.isNotEmpty()
                else -> { if (gap) flat.append(' '); flat.append(c); gap = false }
            }
        }
        return first(flat.toString(), max).trimEnd()
    }

    /** The first [max] characters of [text] as a reader counts them: a letter with its accent is one, and none is cut in half. */
    fun first(text: String, max: Int): String {
        if (text.length <= max) return text
        val breaks = BreakIterator.getCharacterInstance(Locale.ROOT)
        breaks.setText(text)
        var end = 0
        repeat(max) { val next = breaks.next(); if (next == BreakIterator.DONE) return text; end = next }
        return text.substring(0, end)
    }

    /** [text] as a bar or a menu has room for it: whole, or cut to [max] characters as a reader counts them, the last of them "…". */
    fun short(text: String, max: Int): String = if (fits(text, max)) text else first(text, (max - 1).coerceAtLeast(0)).trimEnd() + "…"

    /** How many characters [text] has, as a reader counts them. */
    fun count(text: String): Int {
        val breaks = BreakIterator.getCharacterInstance(Locale.ROOT)
        breaks.setText(text)
        var n = 0
        while (breaks.next() != BreakIterator.DONE) n++
        return n
    }

    /** Whether [text] is at most [max] characters as a reader counts them. A character is at least one unit long, so a short text needs no counting. */
    fun fits(text: String, max: Int) = text.length <= max || count(text) <= max
}
