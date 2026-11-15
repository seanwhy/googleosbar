package com.meridian11.books.bentobar.bar

import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.data.Store
import com.meridian11.books.bentobar.items.Items
import com.meridian11.books.bentobar.items.MenuHost
import com.meridian11.books.bentobar.items.Ticker
import com.meridian11.books.bentobar.ui.MainActivity
import com.meridian11.books.bentobar.ui.MenuRoom

/**
 * The strip's popups and its tooltip: which popup is up ([PopupState] decides), opening one under its item, a press
 * outside it, and the small window that names an item under the pointer. The windows themselves are [MenuWindow] and
 * [Overlay]; this says when they come and go. Every call names the strip it comes from: a popup and a tooltip hang from
 * that strip, and go with it ([closeFor]).
 */
internal class MenuCoordinator(
    private val service: Context,
    private val main: Handler,
    private val wm: WindowManager,
    private val facts: Facts,
) {
    /** What the coordinator asks of the strip's controller, each time it needs it. */
    interface Facts {
        val snapshot: BarSnapshot?
        /** Hidden items are out. */
        val expanded: Boolean
        /** The strip a press at [x], [y] on the screen landed on, of those on view; null for a press on none of them. */
        fun stripAt(x: Float, y: Float): AreaStrip?
        /** The strip that drew the item [id], if one did. */
        fun stripOf(id: String): AreaStrip?
        /** A popup was closed: hidden items the pointer no longer holds out may fold away. */
        fun popupClosed()
    }

    private val density get() = service.resources.displayMetrics.density

    /** Which popup is up, which is still folding away, and whose click a press has spent. */
    private val popups = PopupState<MenuWindow>()
    private val menu get() = popups.open
    // Observable, so the strip's highlight can stay on the item whose popup is open (StripHost).
    var menuKey: MenuKey? by mutableStateOf(null)
        private set
    /** The strip the open popup hangs from: the highlight stays on the popup's item there, and nowhere else. */
    var menuStrip: AreaStrip? by mutableStateOf(null)
        private set
    /** The strip of the popup that last began to leave: one still folding away stands under it. */
    private var leavingStrip: AreaStrip? = null

    fun barMenu(strip: AreaStrip, at: Rect, everything: Boolean) = toggleMenu(strip, MenuKey.Bar, at, 290) { host ->
        BentoBarMenu(host, openItem = { item ->
            val type = Items.of(item.type)
            closeMenu(dissolve = true)
            // From the list of every item, an item's menu opens under the item itself, in whichever strip drew it.
            val owner = PopupRules.itemMenuStrip(strip, everything, facts.stripOf(item.id))
            val anchor = owner.placed[item.id]?.takeIf { everything } ?: at
            if (type != null && Ticker.guarded(type, "onClick") { type.onClick(item) } == true) Ticker.refresh()
            else if (type?.menu != null) toggleMenu(owner, MenuKey.Item(item.id), anchor, type.menuWidthDp) { h -> ItemMenu(item.id, h) }
        }, hideBar = { Store.update { it.copy(enabled = false) } }, everything = everything)
    }

    /** What a popup's content can ask of the strip: one for each popup, since what runs as it starts to close is that popup's own. */
    private fun hostFor(window: MenuWindow) = object : MenuHost {
        override fun close() = closeMenu()
        override fun openItemSettings(id: String) { closeMenu(); MainActivity.open(service, id.ifEmpty { null }) }
        // Once the popup is gone from the screen (a screenshot must not show it): a frame after its window is removed.
        override fun afterClose(action: () -> Unit) { closeMenu(then = { main.postDelayed(action, 50) }) }
        override fun onClosing(action: () -> Unit) = window.onClosing(action)
    }

    /** Whether the popup [key] stays when pressed beside: an item's own popup, whose type says so (a mirror, a prompter). */
    private fun staysOpen(key: MenuKey?): Boolean {
        val id = (key as? MenuKey.Item)?.id ?: return false
        val item = Store.config.value.items.firstOrNull { it.id == id } ?: return false
        return Items.of(item.type)?.staysOpen == true
    }

    /**
     * Opens the popup [key] below [anchor] (coordinates in [strip]'s window), or closes it if it's open. A popup
     * open for another item dissolves while this one unfolds, under whichever strip each stands; [key]'s own, still
     * folding away under the same strip, turns round.
     */
    fun toggleMenu(strip: AreaStrip, key: MenuKey, anchor: Rect, widthDp: Int, content: @Composable (MenuHost) -> Unit) {
        val now = SystemClock.uptimeMillis()
        hideTip()
        // This key's own popup, still folding away under another strip, would turn round there, not under what was
        // clicked (the ‹ menu has one key in every area, and an item put in another area keeps its key): it goes at once,
        // and this one opens anew. Another key's goes on leaving where it stands while this one unfolds.
        if (leavingStrip != null && leavingStrip !== strip) popups.dropLeaving(key)?.let { it.close(now = true); leavingStrip = null }
        when (val step = popups.toggle(key, now)) {
            PopupCommand.Fold -> { closeMenu(); return }
            PopupCommand.Spend -> return
            PopupCommand.LetKeysGo, PopupCommand.TakeKeys -> return // a click's answer is never one of these
            is PopupCommand.Reopen -> if (step.popup.reopen()) {
                popups.reopened(step.popup, key)
                menuStrip = strip
                menuKey = key
                focusMenu(key)
                return
            }
            PopupCommand.Open, PopupCommand.Dissolve -> Unit
        }
        closeMenu(dissolve = true) // resets the sampling demand, so the new popup's is set after it
        val s = facts.snapshot ?: return
        // Before the popup's first frame reads anything (an item's own LaunchedEffect would run after it).
        (key as? MenuKey.Item)?.id?.let { id ->
            Store.config.value.items.firstOrNull { it.id == id }?.let { i -> Items.of(i.type)?.let { t -> Ticker.guarded(t, "menuOpening") { t.menuOpening(i) } } }
        }
        val loc = strip.locationOnScreen()
        val a = Rect(anchor).apply { offset(loc[0], loc[1]) }
        val bounds = wm.currentWindowMetrics.bounds
        val w = (widthDp * density).toInt()
        val side = (MenuRoom.side.value * density).toInt()
        // Under an item in the right half the card's right edge lines up with the item's, in the left half its left edge.
        val left = if (a.centerX() > bounds.width() / 2) a.right - w else a.left
        val x = left.coerceIn(side, (bounds.width() - w - side).coerceAtLeast(side))
        val y = s.bar.bottom + (MenuRoom.top.value * density).toInt()
        val maxH = ((bounds.height() - s.bar.bottom) / density - 170).toInt().coerceAtLeast(200)
        val o = MenuWindow(service, "BentoBar menu", onOutside = { outside(it) }, onEscape = { closeMenu() }, onInside = { inside() })
        val host = hostFor(o)
        if (!o.show(x, y, w, shadow = { MenuShadow(widthDp.dp, o.glass) }) { MenuSurface(widthDp.dp, maxH.dp, o.glass) { content(host) } }) return
        popups.opened(o, key)
        menuStrip = strip
        menuKey = key
        focusMenu(key)
    }

    /**
     * A press outside the open popup. On the popup's own item it folds the popup away and that press's click is
     * spent; on another item, of any strip, the popup dissolves, and the click opens that item's popup as it unfolds;
     * anywhere else it folds, or, for a popup that stays (a mirror, a prompter), gives the keys back to the app below.
     * (A press on another app's window comes without coordinates: it is "anywhere else".)
     */
    private fun outside(ev: MotionEvent) {
        if (menuKey == null) return
        val strip = menuStrip ?: return
        // On whichever strip it landed: the bar is one, in however many windows.
        val on = facts.stripAt(ev.rawX, ev.rawY)
        // The same rule as the highlight's: a press in a gap belongs to the item on its side of the gap's middle.
        val item = on?.let { PopupRules.pressedItem(ev.rawX - it.locationOnScreen()[0], it.placed.map { (k, r) -> Span(k, r.left.toFloat(), r.right.toFloat()) }) }
        when (popups.press(PopupRules.pressedOwner(item, on?.area ?: strip.area, strip.area), SystemClock.uptimeMillis(), stays = staysOpen(menuKey))) {
            PopupCommand.Fold -> closeMenu()
            PopupCommand.Dissolve -> closeMenu(dissolve = true)
            PopupCommand.LetKeysGo -> menu?.keys(take = false)
            else -> Unit
        }
    }

    /** A press inside the open popup: one that gave the keys back takes them again. */
    private fun inside() {
        if (popups.pressInside() == PopupCommand.TakeKeys) menu?.keys(take = true)
    }

    private fun focusMenu(key: MenuKey) {
        Ticker.focusItem = (key as? MenuKey.Item)?.id
        Ticker.revealHidden = facts.expanded || key == MenuKey.Bar
        Ticker.start("menu")
    }

    private var tip: Overlay? = null
    /** The item the tooltip is for, in its strip: the one shown, or the one the pointer is waiting for. */
    private var tipFor: Triple<AreaStrip, ItemConfig, Rect>? = null
    private val showTip = Runnable { tipFor?.let { (strip, item, at) -> showTooltip(strip, item, at) } }

    /**
     * The item's name below it, after a short hover: icon-only items otherwise give a mouse user
     * nothing to go on. An item whose state has a tooltip of its own (a track's whole title and
     * artist, which flight this is) shows that instead. A no-touch window, so it never takes a click.
     */
    private fun showTooltip(strip: AreaStrip, item: ItemConfig, anchor: Rect) {
        hideTip()
        val s = facts.snapshot ?: return
        if (menu != null || !strip.shown) return
        val label = Ticker.states.value[item.id]?.tooltip?.takeIf { it.isNotBlank() } ?: Items.of(item.type)?.title ?: return
        val loc = strip.locationOnScreen()
        val a = Rect(anchor).apply { offset(loc[0], loc[1]) }
        val paint = android.text.TextPaint().apply { textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 13f, service.resources.displayMetrics) }
        val line = paint.fontMetricsInt.let { it.bottom - it.top }
        // A long tip ends in an ellipsis: the window is never wider than this.
        val w = ((paint.measureText(label) + 2 * 10 * density).toInt() + 2).coerceAtMost((TOOLTIP_MAX_DP * density).toInt())
        val bounds = wm.currentWindowMetrics.bounds
        val o = Overlay(service, "BentoBar tooltip", touchable = false)
        o.params.gravity = Gravity.TOP or Gravity.LEFT
        o.params.width = w // exact, as for menus
        // As tall as its text wants, at the user's text size; at the usual size that is the pill's 28 dp.
        o.params.height = PopupRules.tooltipHeightPx(line, density)
        o.params.x = (a.centerX() - w / 2).coerceIn(0, (bounds.width() - w).coerceAtLeast(0))
        o.params.y = s.bar.bottom + (4 * density).toInt()
        tip = o
        o.show { Tooltip(label) }
    }

    fun hideTip() {
        main.removeCallbacks(showTip)
        tip?.destroy(); tip = null
    }

    /**
     * Closes the open popup: it folds back into its item ([dissolve]: it dissolves in place, left for
     * another), or goes at once ([now]: the service stopping, the screen going off, the display
     * changing). [then] runs once it is off the screen.
     */
    fun closeMenu(now: Boolean = false, dissolve: Boolean = false, then: (() -> Unit)? = null) {
        val o = menu
        if (o == null) {
            if (now) popups.dropLeaving()?.close(now = true)
            then?.invoke()
            return
        }
        // An earlier one still leaving goes at once: only one popup is ever folding away.
        popups.leave(then = then != null)?.close(now = true)
        leavingStrip = menuStrip
        menuStrip = null
        menuKey = null
        o.close(dissolve = dissolve, now = now) {
            popups.gone(o)
            then?.invoke()
        }
        Ticker.focusItem = null
        Ticker.revealHidden = facts.expanded
        Ticker.stop("menu")
        facts.popupClosed()
    }

    /** The pointer left [strip] for another strip: a tooltip of an item of [strip], shown or waited for, ends with that. */
    fun hideTip(strip: AreaStrip) { if (tipFor?.first === strip) hideTip() }

    /** [strip] is leaving the screen: a popup that hangs from it, open or still folding away, goes at once, and so does its tooltip. */
    fun closeFor(strip: AreaStrip) {
        if (menuStrip === strip) closeMenu(now = true)
        if (leavingStrip === strip) { popups.dropLeaving()?.close(now = true); leavingStrip = null }
        if (tipFor?.first === strip) hideTip()
    }

    /** The pointer is over [item] of [strip] or has left it: a tooltip after a moment, or the item's own popup with one already open. */
    fun itemHover(strip: AreaStrip, item: ItemConfig, at: Rect, inside: Boolean, sliding: Boolean) {
        // Each strip's window has its own frames: the item the pointer left can say so after the one it came to, in
        // another strip, said it is under it. That is not this tooltip's end (the last one went as the pointer came, below).
        if (!inside && tipFor?.first.let { it != null && it !== strip }) return
        main.removeCallbacks(showTip)
        // "Switch popups on hover": with a popup open, the item pointed at opens its own, as in a menu bar.
        val type = Items.of(item.type)
        if (inside && !sliding && type != null &&
            PopupRules.hoverOpens(menuKey, item.id, hasPopup = type.menu != null, on = Store.config.value.hoverSwitchesPopups, stays = staysOpen(menuKey))) {
            strip.placed[item.id] = at
            toggleMenu(strip, MenuKey.Item(item.id), at, type.menuWidthDp) { host -> ItemMenu(item.id, host) }
            return
        }
        if (inside && menu == null && !sliding) {
            if (tipFor?.first !== strip) hideTip() // another strip's, whose item may not have said its end yet
            tipFor = Triple(strip, item, Rect(at)); main.postDelayed(showTip, 600)
        } else hideTip()
    }

    private companion object {
        const val TOOLTIP_MAX_DP = 360
    }
}
