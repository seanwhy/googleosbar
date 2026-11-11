package com.meridian11.books.bentobar.bar

/** Which popup is meant: the ‹ menu, an item's popup, or an item's right-click menu. [toString] is what the debug hooks print. */
sealed interface MenuKey {
    /** What the popup belongs to, under the name the strip knows it by: an item's id, or "chevron" for ‹. */
    val owner: String

    data object Bar : MenuKey {
        override val owner = "chevron"
        override fun toString() = "bentobar"
    }

    data class Item(val id: String) : MenuKey {
        override val owner get() = id
        override fun toString() = "item:$id"
    }

    data class ItemContext(val id: String) : MenuKey {
        override val owner get() = id
        override fun toString() = "ctx:$id"
    }
}

/** When pointing at an item opens its popup. Pure, so the rules are tested (`PopupRulesTest`). */
internal object PopupRules {
    /**
     * How tall a tooltip's window is for a line of text [line] pixels high: the pill's 28 dp, the height it has always had, and
     * taller only when a larger text size needs it. The 8 dp is what the words need around them and fits inside the 28 dp at the
     * default text size at any density (a face's line is up to 1.5 em of its 13 sp), so nothing looks different there.
     */
    fun tooltipHeightPx(line: Int, density: Float): Int = maxOf((28 * density).toInt(), line + (8 * density).toInt())

    /**
     * Whether the pointer coming onto item [hovered] opens its popup: only with "Switch popups on hover" [on] (off by
     * default: nothing opens on hover by itself), only while an item's popup or the ‹ menu is [open], never for the
     * item already open, never for a right-click menu, and only for an item that [hasPopup]: one that acts on a click
     * (keep awake, a Shortcut) must not act on a hover.
     */
    fun hoverOpens(open: MenuKey?, hovered: String, hasPopup: Boolean, on: Boolean): Boolean {
        if (!on || open == null || !hasPopup) return false
        if (open is MenuKey.ItemContext) return false
        return open != MenuKey.Item(hovered)
    }

    /**
     * The item a press at [x] on the strip belongs to, of the items [drawn] now: the highlight's own rule, a gap going to
     * the item on its side of the middle. An item that has gone must not be in [drawn]: its old box would take the press.
     */
    fun pressedItem(x: Float, drawn: List<Span>): String? = StripHighlight.target(x, drawn.sortedBy { it.left }, null)
}
