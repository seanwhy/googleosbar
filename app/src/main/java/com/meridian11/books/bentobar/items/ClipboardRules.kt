package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.util.Sym
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Pure rules for the Clipboard and scratchpad item: pinned snippets in [ItemConfig.options],
 * session-only recent clips in memory, and what the bar shows. No Android imports.
 */
object ClipboardRules {
    const val MOST_PINS = 8
    const val MOST_RECENT = 8
    const val BAR_CHARS = 20
    const val MENU_CHARS = 80
    /** The longest a pinned snippet is: pins are kept in the layout, and copied with it. */
    const val MAX_CHARS = 500
    /** The longest recent copy kept in memory: eight of these are under 2 MB, and a click puts the whole text back. */
    const val RECENT_CHARS = 100_000

    /** How a BentoBar settings export (Copy settings) begins. */
    private const val EXPORT_START = """{"version":"""

    class Bar(
        val icon: String,
        val filled: Boolean,
        val text: String?,
        val active: Boolean,
        val desc: String,
        val tooltip: String?,
    )

    /** Normalizes a snippet for storage or recent history; null when blank. */
    fun clean(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        return TextRules.first(trimmed, MAX_CHARS)
    }

    /** A single-line preview of [snippet] for the bar or a menu row. */
    fun preview(snippet: String, maxChars: Int = BAR_CHARS): String {
        val line = TextRules.oneLine(snippet, maxChars + 1)
        return if (TextRules.fits(line, maxChars)) line else TextRules.first(line, maxChars - 1).trimEnd() + "…"
    }

    /** Pinned snippets stored on [item], in order, without blanks or duplicates. */
    fun pins(item: ItemConfig): List<String> = try {
        val raw = item.options["pins"].orEmpty()
        if (raw.isEmpty()) emptyList()
        else (Json.parseToJsonElement(raw) as? JsonArray).orEmpty().asSequence().take(64)
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.let(::clean) }
            .distinct()
            .take(MOST_PINS)
            .toList()
    } catch (e: Exception) {
        emptyList()
    }

    fun withPins(item: ItemConfig, pins: List<String>): ItemConfig {
        val cleaned = pins.mapNotNull(::clean).distinct().take(MOST_PINS)
        val encoded = if (cleaned.isEmpty()) null else buildJsonArray { cleaned.forEach { add(JsonPrimitive(it)) } }.toString()
        return item.with("pins", encoded)
    }

    fun addPin(item: ItemConfig, text: String): ItemConfig {
        val c = clean(text) ?: return item
        val current = pins(item)
        if (c in current || current.size >= MOST_PINS) return item
        return withPins(item, current + c)
    }

    fun removePin(item: ItemConfig, text: String): ItemConfig = withPins(item, pins(item).filterNot { it == text })

    fun movePin(item: ItemConfig, text: String, delta: Int): ItemConfig {
        val list = pins(item).toMutableList()
        val i = list.indexOf(text)
        val target = i + delta
        if (i < 0 || target !in list.indices) return item
        list[i] = list[target].also { list[target] = list[i] }
        return withPins(item, list)
    }

    /**
     * What of a clip may become a recent copy: its own text, or nothing. No text ([text] is null: a copied file or
     * link, which nothing opens to find out), a clip its app marked [sensitive] (Android's EXTRA_IS_SENSITIVE: a
     * password manager's) and BentoBar's own settings export are not recorded.
     */
    fun recentText(text: CharSequence?, sensitive: Boolean): String? {
        if (sensitive || text == null) return null
        return text.toString().takeUnless { it.trimStart().startsWith(EXPORT_START) }
    }

    /**
     * Adds [clip] to the front of [recent], deduplicating and keeping at most [MOST_RECENT] entries. As it was copied,
     * ends included, since a click puts exactly this back on the clipboard. One over [RECENT_CHARS] isn't kept: better
     * no row than a row that copies back less than was copied.
     */
    fun recordRecent(recent: List<String>, clip: String?): List<String> {
        if (clip.isNullOrBlank() || clip.length > RECENT_CHARS) return recent
        return (listOf(clip) + recent.filterNot { it == clip }).take(MOST_RECENT)
    }

    /** Whether [text] survives being pinned ([clean] keeps [MAX_CHARS]): a longer recent copy offers no pin. */
    fun pinsWhole(text: String): Boolean = clean(text) == text.trim()

    /** Recent clips that aren't already pinned on [item]. */
    fun unpinnedRecent(recent: List<String>, pins: List<String>): List<String> {
        val pinnedSet = pins.toHashSet()
        return recent.filterNot { clean(it) in pinnedSet }.take(MOST_RECENT)
    }

    /** What the bar shows for [pins] and [recent]. Spoken description never quotes the snippet text. */
    fun bar(item: ItemConfig, pins: List<String>, recent: List<String>, emptyDesc: String, countDesc: (Int) -> String): Bar {
        val unpinned = unpinnedRecent(recent, pins)
        val total = pins.size + unpinned.size
        if (total == 0) {
            return Bar(Sym.CONTENT_PASTE, filled = false, text = null, active = false, desc = emptyDesc, tooltip = emptyDesc)
        }
        val showCount = item.opt("bar", "preview") == "count"
        val top = unpinned.firstOrNull() ?: pins.first()
        val text = if (showCount) total.toString() else preview(top, BAR_CHARS)
        val desc = countDesc(total)
        return Bar(Sym.CONTENT_PASTE, filled = true, text = text, active = true, desc = desc, tooltip = desc)
    }
}
