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
 * [Overlay]; this says when they come and go.
 */
internal class MenuCoordinator(
    private val service: Context,
    private val main: Handler,
    private val wm: WindowManager,
    private val strip: Overlay,
    private val placed: MutableMap<String, Rect>,
    private val facts: Facts,
) {
    /** What the coordinator asks of the strip's controller, each time it needs it. */
    interface Facts {
        val snapshot: BarSnapshot?
        /** Hidden items are out. */
        val expanded: Boolean
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

    fun barMenu(at: Rect, everything: Boolean) = toggleMenu(MenuKey.Bar, at, 290) { host ->
        BentoBarMenu(host, openItem = { item ->
            val type = Items.of(item.type)
            closeMenu(dissolve = true)
            // From the list of every item, an item's menu opens under the item itself.
            val anchor = placed[item.id]?.takeIf { everything } ?: at
            if (type != null && Ticker.guarded(type, "onClick") { type.onClick(item) } == true) Ticker.refresh()
            else if (type?.menu != null) toggleMenu(MenuKey.Item(item.id), anchor, type.menuWidthDp) { h -> ItemMenu(item.id, h) }
        }, hideBar = { Store.update { it.copy(enabled = false) } }, everything = everything)
    }

    private val host = object : MenuHost {
        override fun close() = closeMenu()
        override fun openItemSettings(id: String) { closeMenu(); MainActivity.open(service, id.ifEmpty { null }) }
        // Once the popup is gone from the screen (a screenshot must not show it): a frame after its window is removed.
        override fun afterClose(action: () -> Unit) { closeMenu(then = { main.postDelayed(action, 50) }) }
    }

    /**
     * Opens the popup [key] below [anchor] (strip window coordinates), or closes it if it's open. A popup
     * open for another item dissolves while this one unfolds; one still folding away turns round.
     */
    fun toggleMenu(key: MenuKey, anchor: Rect, widthDp: Int, content: @Composable (MenuHost) -> Unit) {
        val now = SystemClock.uptimeMillis()
        hideTip()
        when (val step = popups.toggle(key, now)) {
            PopupCommand.Fold -> { closeMenu(); return }
            PopupCommand.Spend -> return
            is PopupCommand.Reopen -> if (step.popup.reopen()) {
                popups.reopened(step.popup, key)
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
        val o = MenuWindow(service, "BentoBar menu", onOutside = { outside(it) }, onEscape = { closeMenu() })
        if (!o.show(x, y, w, shadow = { MenuShadow(widthDp.dp, o.glass) }) { MenuSurface(widthDp.dp, maxH.dp, o.glass) { content(host) } }) return
        popups.opened(o, key)
        menuKey = key
        focusMenu(key)
    }

    /**
     * A press outside the open popup. On the popup's own item it folds the popup away and that press's click is
     * spent; on another item the popup dissolves, and the click opens that item's popup as it unfolds; anywhere
     * else it folds. (A press on another app's window comes without coordinates: it is "anywhere else".)
     */
    private fun outside(ev: MotionEvent) {
        if (menuKey == null) return
        val loc = strip.locationOnScreen()
        val x = ev.rawX - loc[0]
        val y = ev.rawY - loc[1]
        val onStrip = (ev.rawX != 0f || ev.rawY != 0f) && strip.shown &&
            x >= 0 && x < strip.params.width && y >= 0 && y < (facts.snapshot?.bar?.height() ?: 0)
        // The same rule as the highlight's: a press in a gap belongs to the item on its side of the gap's middle.
        val hit = if (onStrip) PopupRules.pressedItem(x, placed.map { (k, r) -> Span(k, r.left.toFloat(), r.right.toFloat()) }) else null
        when (popups.press(hit, SystemClock.uptimeMillis())) {
            PopupCommand.Fold -> closeMenu()
            PopupCommand.Dissolve -> closeMenu(dissolve = true)
            else -> Unit
        }
    }

    private fun focusMenu(key: MenuKey) {
        Ticker.focusItem = (key as? MenuKey.Item)?.id
        Ticker.revealHidden = facts.expanded || key == MenuKey.Bar
        Ticker.start("menu")
    }

    private var tip: Overlay? = null
    private var tipFor: Pair<ItemConfig, Rect>? = null
    private val showTip = Runnable { tipFor?.let { (item, at) -> showTooltip(item, at) } }

    /**
     * The item's name below it, after a short hover: icon-only items otherwise give a mouse user
     * nothing to go on. An item whose state has a tooltip of its own (a track's whole title and
     * artist, which flight this is) shows that instead. A no-touch window, so it never takes a click.
     */
    private fun showTooltip(item: ItemConfig, anchor: Rect) {
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

    /** The pointer is over [item] or has left it: a tooltip after a moment, or the item's own popup with one already open. */
    fun itemHover(item: ItemConfig, at: Rect, inside: Boolean, sliding: Boolean) {
        main.removeCallbacks(showTip)
        // "Switch popups on hover": with a popup open, the item pointed at opens its own, as in a menu bar.
        val type = Items.of(item.type)
        if (inside && !sliding && type != null &&
            PopupRules.hoverOpens(menuKey, item.id, hasPopup = type.menu != null, on = Store.config.value.hoverSwitchesPopups)) {
            placed[item.id] = at
            toggleMenu(MenuKey.Item(item.id), at, type.menuWidthDp) { host -> ItemMenu(item.id, host) }
            return
        }
        if (inside && menu == null && !sliding) { tipFor = item to Rect(at); main.postDelayed(showTip, 600) } else hideTip()
    }

    private companion object {
        const val TOOLTIP_MAX_DP = 360
    }
}
