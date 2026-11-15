package com.meridian11.books.bentobar.bar

import com.meridian11.books.bentobar.data.Position
import com.meridian11.books.bentobar.data.ROOM_ORDER

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

/** Where one area's strip is on the screen, in pixels: what [PopupRules.pressedArea] needs of a strip's window. */
data class StripFrame(val area: Position, val left: Int, val top: Int, val width: Int, val height: Int)

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
     * (keep awake, a Shortcut) must not act on a hover. Never over a popup that [stays] (a mirror, a prompter): the
     * pointer crossing the bar must not take it away.
     */
    fun hoverOpens(open: MenuKey?, hovered: String, hasPopup: Boolean, on: Boolean, stays: Boolean = false): Boolean {
        if (!on || open == null || !hasPopup || stays) return false
        if (open is MenuKey.ItemContext) return false
        return open != MenuKey.Item(hovered)
    }

    /**
     * The item a press at [x] on the strip belongs to, of the items [drawn] now: the highlight's own rule, a gap going to
     * the item on its side of the middle. An item that has gone must not be in [drawn]: its old box would take the press.
     */
    fun pressedItem(x: Float, drawn: List<Span>): String? = StripHighlight.target(x, drawn.sortedBy { it.left }, null)

    /**
     * The area whose strip a press at [x], [y] on the screen landed on, of the strips on view now ([frames]); null for a
     * press on none of them. A press on another app's window comes without coordinates, as 0, 0: that is on no strip, even
     * one that begins in the screen's corner.
     */
    fun pressedArea(x: Float, y: Float, frames: List<StripFrame>): Position? {
        if (x == 0f && y == 0f) return null
        return frames.firstOrNull { f -> x >= f.left && x < f.left + f.width && y >= f.top && y < f.top + f.height }?.area
    }

    /**
     * The area whose strip's window hears a press outside the bar while hidden items are out: one window, so one press is
     * heard once. It is the first in room order of the strips [onView] (on screen, measured and drawing something), since a
     * window that draws nothing is invisible and may be told of no press; while none is on view, the [carrier]'s, the first
     * strip placed. With one strip that strip is both, on view or not: it watches as it always did.
     */
    fun watchingArea(onView: Set<Position>, carrier: Position): Position = ROOM_ORDER.firstOrNull { it in onView } ?: carrier

    /**
     * The strip an item's menu opens under when the item is chosen in the ‹ menu that stood under [menu]. From the list of
     * every item ([everything]: the keyboard's menu, which hangs from the first strip) that is the strip that [drew] the
     * item, so the menu opens under the item itself and the highlight is on it. An item no strip drew, and one chosen in a
     * ‹'s own menu, opens where the ‹ menu stood. With one strip it is always that one.
     */
    fun <S : Any> itemMenuStrip(menu: S, everything: Boolean, drew: S?): S = drew?.takeIf { everything } ?: menu

    /**
     * The pointer came onto the strip of [area] or left it ([inside]), having been over the strips of [areas]: the areas it
     * is over now, and what that is to the bar, which is one in however many windows. True: it came onto the bar; false: it
     * left it; null: it is over another strip as well, and to the bar nothing happened. So a strip's Exit that arrives after
     * the next strip's Enter ends nothing, and with one strip every Enter and Exit is the bar's, as it always was.
     */
    fun hoverChanged(areas: Set<Position>, area: Position, inside: Boolean): Pair<Set<Position>, Boolean?> =
        (if (inside) areas + area else areas - area) to inside.takeIf { (areas - area).isEmpty() }

    /**
     * What a press on [item] of the strip in [on] is called to the open popup, which hangs from the strip in [popup]
     * ([PopupState.press] knows a popup's own item by that name). An item is in one strip and is itself everywhere. Every
     * area has a ‹ of its own, and only the popup's strip's is the ‹ menu's own: another strip's is another item, so its
     * click opens that strip's menu and is not spent. With one strip a press is always on the popup's.
     */
    fun pressedOwner(item: String?, on: Position, popup: Position): String? =
        if (item == MenuKey.Bar.owner && on != popup) "$item of ${on.name}" else item
}
