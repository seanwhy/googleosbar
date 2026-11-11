package com.meridian11.books.bentobar.bar

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.meridian11.books.bentobar.data.HiddenMode
import com.meridian11.books.bentobar.data.stripPlan
import com.meridian11.books.bentobar.data.behindChevron
import com.meridian11.books.bentobar.data.moved
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.data.Pill
import com.meridian11.books.bentobar.data.Position
import com.meridian11.books.bentobar.data.Section
import com.meridian11.books.bentobar.data.Store
import com.meridian11.books.bentobar.items.Chips
import com.meridian11.books.bentobar.items.CloudClipboardItem
import com.meridian11.books.bentobar.items.DisplaysItem
import com.meridian11.books.bentobar.items.Env
import com.meridian11.books.bentobar.items.RemoteKeyboardItem
import com.meridian11.books.bentobar.items.Items
import com.meridian11.books.bentobar.items.Ticker
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * BentoBar's accessibility service. It reads SystemUI's status bar window (layout and text colour)
 * and, of other windows, where they are and never what is in them; it draws BentoBar's items in
 * overlay windows on top of the bar, and performs the global actions of the Tools menu when asked.
 * It doesn't observe keys or touches.
 */
class BarService : AccessibilityService() {
    companion object {
        const val TAG = "BentoBar"

        /** The connected service, or null. Only bar/ may hold it; the rest of the app asks [global] or Env.running. */
        @Volatile var current: BarService? = null
            private set

        /** One of the Tools and Shortcut menu's global actions (Back, Home, Recents...), false with no service. */
        fun global(action: Int): Boolean = current?.performGlobalAction(action) ?: false

        fun pasteIntoFocus(text: CharSequence) {
            val node = current?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return
            if (!node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
                val args = android.os.Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            }
        }
    }

    private var bar: BarController? = null

    override fun onServiceConnected() {
        Env.init(this)
        current = this
        runCatching {
            serviceInfo = serviceInfo?.apply {
                flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            }
        }
        bar?.stop() // connected again without an unbind: the old strip's windows mustn't stay up
        bar = BarController(this).also { it.start() }
        Chips.update(this)
        com.meridian11.books.bentobar.ui.Setup.refresh(this)
        Log.i(TAG, "service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) { bar?.onEvent(event) }
    override fun onKeyEvent(event: KeyEvent): Boolean = bar?.onKeyEvent(event) ?: false
    override fun onInterrupt() {}

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        bar?.onConfigChanged()
    }

    override fun onUnbind(intent: Intent?): Boolean { shutdown(); return super.onUnbind(intent) }
    override fun onDestroy() { shutdown(); super.onDestroy() }

    /**
     * Runs on unbind and again on destroy; a rebind makes a new controller in [onServiceConnected].
     * (UiAutomation, e.g. `uiautomator dump`, unbinds every accessibility service while it runs.)
     */
    private fun shutdown() {
        val stopping = bar
        bar = null // first: whatever stop() does, it runs once per controller
        stopping?.stop()
        if (current === this) current = null
        Chips.update(this)
        com.meridian11.books.bentobar.ui.Setup.refresh(this)
    }

    fun controller(): BarController? = bar
}

class BarController(private val service: AccessibilityService) {
    private val tag = BarService.TAG
    private val main = Handler(Looper.getMainLooper())
    private val scope = MainScope()
    private val wm = service.getSystemService(WindowManager::class.java)
    private val pm = service.getSystemService(PowerManager::class.java)
    private val km = service.getSystemService(KeyguardManager::class.java)
    private val density get() = service.resources.displayMetrics.density

    // Read by the strip's composition.
    private val expanded = object {
        private val state = mutableStateOf(false)
        var value: Boolean
            get() = state.value
            set(v) {
                state.value = v; Ticker.revealHidden = v || menus.menuKey == MenuKey.Bar; if (v) Ticker.refresh()
                // Out: a click anywhere else folds them away again (see [outsideClick]).
                strip.watchOutside(v)
            }
    }
    /** The strip's colours and the readings of the status bar behind them. */
    private val colors = ColorReader(service, main, object : ColorReader.Facts {
        override val started get() = this@BarController.started
        override val onScreen get() = strip.shown
        override val empty get() = stripEmpty
        override val interactive get() = pm.isInteractive
        override val snapshot get() = snap
    }) { s, done -> StatusBarScan.shoot(service, s, done) }
    private val look get() = colors.look
    private val maxWidth = mutableIntStateOf(0)
    private val heightDp = mutableStateOf(36.dp)

    private val strip = Overlay(service, "BentoBar", onOutside = { outsideClick() }).apply { params.width = 1; watchOutside(false) }

    /** Keep awake: a flag on the strip's window, a wake lock while the strip has none to carry it. */
    private val awake = KeepAwake(main, pm, km, scope, strip) { stripEmpty }

    /** A click outside the strip while hidden items are out folds them away, unless a menu is open (it closes itself). */
    private fun outsideClick() {
        if (menus.menuKey != null || !expanded.value) return
        fold()
    }

    /** Hidden items go back behind ‹ and stay there: with their pin gone too, or the next start of the service opens them with no click. */
    private fun fold() {
        expanded.value = false; pinned = false
        if (Store.config.value.pinnedOpen) Store.update { it.copy(pinnedOpen = false) }
    }
    private var snap: BarSnapshot? = null
    private var overlayIds = emptySet<Int>()
    private var hovering = false
    private var pinned = false
    private var started = false
    /** Where each item was last drawn (window coordinates), for anchoring menus from tests. */
    val placed = HashMap<String, Rect>()

    /** The popups and the tooltip. */
    private val menus: MenuCoordinator = MenuCoordinator(service, main, wm, strip, placed, object : MenuCoordinator.Facts {
        override val snapshot get() = snap
        override val expanded get() = this@BarController.expanded.value
        override fun popupClosed() { if (!hovering && !pinned) { main.removeCallbacks(collapse); main.postDelayed(collapse, 600) } }
    })

    private val scanNow = Runnable { scan() }
    private val quickCheck = Runnable { check(full = false) }
    /**
     * Status bar icons come and go without window changes. Every 2 s while shown, refresh just the
     * spacer node (one call) and read the whole bar again only if it moved, or every 30 s.
     */
    private val poll = object : Runnable {
        override fun run() {
            if (!pm.isInteractive) return // screen off: nothing to show; SCREEN_ON restarts this
            if (strip.shown) lightCheck() else check(full = false)
            main.postDelayed(this, if (strip.shown) 2_000 else 5_000)
        }
    }
    private var lastFullScan = 0L
    /** The desktop bar's spacer node at the last scan: refreshing just this is a cheap "did anything move?" check. */
    private var spacerNode: AccessibilityNodeInfo? = null
    /** The app windows against the bar's lower edge and under the bar, as [check] last saw them. */
    private var neighbours = BarNeighbours()

    private fun lightCheck() {
        val s = snap ?: return scan()
        val node = spacerNode
        val step = BarRules.lightLook(hasSpacer = node != null, sinceFullMs = SystemClock.uptimeMillis() - lastFullScan)
        if (step == BarRules.LightLook.FULL) return scan()
        if (node == null || step == BarRules.LightLook.WINDOWS) return check(full = false)
        if (!node.refresh()) return scan()
        // Against the spacer's own last bounds: with the spacer squeezed to nothing, the free area is
        // the widest gap instead, and comparing with that would run a full scan every time.
        val r = Rect().also { node.getBoundsInScreen(it) }
        if (r.box() != s.spacer) scan()
    }
    private val expandOnHover = Runnable { if (hovering) expanded.value = true }
    private val collapse: Runnable = object : Runnable {
        override fun run() {
            // A slider held by a pointer that has wandered off the strip: its item stays where it is until it lets go.
            if (sliding) { main.postDelayed(this, 300); return }
            if (!hovering && menus.menuKey == null && expanded.value) {
                Log.i(tag, "collapse (pinned=$pinned autoCollapse=${Store.config.value.autoCollapseSec}s)")
                fold()
            }
        }
    }
    /** A pointer holds an item's slider: between its first report and the one that says `done`. */
    private var sliding = false

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == Intent.ACTION_SCREEN_OFF) menus.closeMenu(now = true)
            awake.update()
            main.post(scanNow)
            if (i.action == Intent.ACTION_SCREEN_ON) {
                main.removeCallbacks(poll); main.postDelayed(poll, 2_000)
                // The chip's next change is timed on a clock that stops while the device sleeps: catch up.
                Chips.update(service)
            }
        }
    }
    fun start() {
        started = true
        // Pinned open with ‹ before a restart: open again.
        Store.config.value.let { if (it.pinnedOpen && it.hiddenMode != HiddenMode.SHOW_ALL) { expanded.value = true; pinned = true } }
        service.registerReceiver(screen, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT)
        })
        colors.start()
        scope.launch { Store.config.collect { onConfig() } }
        awake.start()
        scan()
        main.postDelayed(poll, 2_000)
    }

    fun stop() {
        if (!started) return // stopped already: the status and the ticker may be a newer controller's by now
        started = false
        BarLook.current.value = null
        BarStatus.current.value = BarStatus.STOPPED
        BarOverflow.ids.value = emptySet()
        runCatching { service.unregisterReceiver(screen) }
        colors.stop()
        // Before the strip goes, which would ask keep awake for a wake lock in its place.
        awake.stop()
        DisplaysItem.follow(false)
        leaveScreen()
        // Nothing is revealed once this controller is gone: the ticker outlives it, in the process.
        Ticker.revealHidden = false
        main.removeCallbacksAndMessages(null) // after closeMenu, which posts a collapse
        strip.destroy()
        scope.cancel()
    }

    /**
     * The strip leaves the screen, whatever the reason: the bar it sits in went away, or the service is going. The one
     * way off it: a new reason, or a new overlay that must go with the strip, is one edit here. The order is load-bearing
     * (HidePathTest): the popup and the tooltip go before the window that anchors them, the pointer's holds end before
     * the release or Exit that would have ended them is lost, and the ticker stops last.
     */
    private fun leaveScreen() {
        menus.closeMenu(now = true)
        menus.hideTip()
        cancelDrag() // the strip's window goes, and with it the release that would end a drag
        // and the Exit that would end a hover: what only the pointer held out doesn't come back with the bar.
        hovering = false; main.removeCallbacks(expandOnHover)
        if (!pinned && expanded.value) expanded.value = false
        strip.hide()
        // Readings still to come were for a strip that is gone: its return asks again.
        colors.stripLeft()
        awake.update()
        Ticker.stop("bar")
    }

    /** Window events, logged only where asked for: `adb shell setprop log.tag.BentoBarEvents DEBUG`. */
    private val EVENTS_TAG = "BentoBarEvents"

    fun onEvent(e: AccessibilityEvent) {
        if (tracing) trace("event windows id=${e.windowId} changes=0x${Integer.toHexString(e.windowChanges)} ours=${e.windowId in overlayIds}")
        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                if (Log.isLoggable(EVENTS_TAG, Log.DEBUG)) Log.d(EVENTS_TAG, "windows changed id=${e.windowId} changes=0x${Integer.toHexString(e.windowChanges)} ours=${e.windowId in overlayIds}")
                if (e.windowId in overlayIds) return // our own windows resizing
                // Only windows appearing, going or moving can hide or cover the bar; titles, focus
                // and stacking changes (every page load, every terminal command) can't.
                if (e.windowChanges and RELEVANT == 0) return
                // The status bar animates in and out: look again as the animation settles.
                main.removeCallbacks(quickCheck)
                for (delay in longArrayOf(100, 450, 900)) main.postDelayed(quickCheck, delay)
            }
        }
    }

    /** Rotation, density or resolution: a menu or tooltip placed for the old display would be off, so close them. */
    fun onConfigChanged() {
        menus.closeMenu(now = true); menus.hideTip()
        main.postDelayed(scanNow, 200); colors.readSoon(ColorWatch.Cause.SLOW, 500)
    }

    private var lastGeometry: BarRules.Geometry? = null

    private fun onConfig() {
        if (!started) return
        DisplaysItem.follow(true)
        colors.onConfig()
        // Switched to Show everything (or presenting) with hidden items out: nothing can fold them back.
        Store.config.value.let { if ((it.hiddenMode == HiddenMode.SHOW_ALL || it.presenting) && expanded.value) fold() }
        colors.applyLook()
        if (Ticker.running) Ticker.refresh()
        // Every Store write lands here, and a label saves on every key: only what moves the strip asks the bar again.
        BarRules.geometry(Store.config.value).let { if (it != lastGeometry) { lastGeometry = it; scan() } }
        Chips.update(service)
    }

    // ---- where the status bar is -------------------------------------------------------------

    private fun scan() = check(full = true)

    /**
     * The one window title BentoBar reads (AGENTS.md, Least privilege), and only so that the screenshot preview doesn't
     * count as a shade. [BarRules.decide] asks it of a big system window over the bar's free area and nothing else;
     * the title is compared here, never logged or kept.
     */
    private fun isScreenshotPreview(w: AccessibilityWindowInfo) =
        w.type == AccessibilityWindowInfo.TYPE_SYSTEM && w.title?.contains("Screenshot", ignoreCase = true) == true

    /**
     * Where the status bar is and whether to show the strip. A cheap pass reads just the window list;
     * the status bar's node tree is read only when [full] or the bar window itself changed.
     */
    private fun check(full: Boolean) {
        if (!started) return
        val metrics = wm.currentWindowMetrics
        val screenW = metrics.bounds.width()
        val inset = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top
        val maxH = (if (inset > 0) inset else (48 * density).toInt()) + 4
        val windows = runCatching { service.windows }.getOrDefault(emptyList())
        // Plain facts from here on: what kind each window is and where, and nothing of what is in it.
        val listed = windows.map { StatusBarScan.fact(it) }
        overlayIds = listed.filter { it.kind == WindowFact.Kind.OVERLAY }.map { it.id }.toSet()
        val barFact = BarRules.findBar(listed, screenW, maxH)
        val prev = snap
        val s = when {
            barFact == null -> null
            BarRules.keepsReading(full, prev, barFact) -> prev
            else -> {
                lastFullScan = SystemClock.uptimeMillis()
                // Without content-change events the node cache can go stale: read the status bar fresh.
                service.clearCache()
                val scanned = runCatching { StatusBarScan.scan(windows[listed.indexOf(barFact)]) }.onFailure { Log.w(tag, "scan failed", it) }.getOrNull()
                // A reading kept from before has no node (a scan without a tree brought none).
                spacerNode = scanned?.spacerNode
                BarRules.settled(scanned?.snapshot, prev)
            }
        }
        if (s == null) spacerNode = null
        snap = s
        val cfg = Store.config.value
        val interactive = pm.isInteractive
        val locked = km.isKeyguardLocked
        val d = BarRules.decide(BarFacts(listed, s, prev, cfg.enabled, interactive, locked, neighbours)) { isScreenshotPreview(windows[listed.indexOf(it)]) }
        d.status?.let { BarStatus.current.value = it }
        neighbours = d.neighbours
        if (d.show) {
            place(s!!, screenW)
            // A new status bar window can look different (see ColorReader.readSoon for the other causes).
            if (d.read == BarRules.Read.NEW_BAR_WINDOW) colors.readSoon(ColorWatch.Cause.QUICK, 250)
            else if (d.read == BarRules.Read.WINDOWS_MOVED) colors.windowsMoved()
        } else {
            if (strip.shown) Log.i(tag, "bar hidden (statusBar=${s != null} covered=${d.cover?.box?.toShortString()} " +
                "enabled=${cfg.enabled} interactive=$interactive locked=$locked)")
            leaveScreen()
        }
    }

    /** adb `debug trace on|off`: logs every move and resize with its cause, to chase jitter. */
    private var tracing = false
    private var lastPlace = ""
    private fun trace(what: String) { if (tracing) Log.i(tag, "trace ${SystemClock.uptimeMillis() % 100_000} $what") }

    private fun place(s: BarSnapshot, screenW: Int) {
        val cfg = Store.config.value
        val gap = (6 * density).toInt()
        val p = strip.params
        p.height = s.bar.height()
        p.y = s.bar.top
        // Without a pill the strip's ends are only room for the highlight's round ends: the window reaches that much
        // further out, into the gap, so the items stay 6 dp from the free area's edges as they always were.
        val ends = (STRIP_PILL_PADDING.value * density).toInt()
        val out = if (cfg.pill == Pill.NONE) ends else 0
        when (cfg.position) {
            Position.RIGHT -> { p.gravity = Gravity.TOP or Gravity.RIGHT; p.x = screenW - s.free.right + gap - out }
            Position.LEFT -> { p.gravity = Gravity.TOP or Gravity.LEFT; p.x = s.free.left + gap - out }
            Position.CENTER -> { p.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; p.x = s.free.centerX() - screenW / 2 }
        }
        // The whole strip fits the free area: items, the pill's own padding and (decided by the strip,
        // from what fits) room for ‹.
        val pillPad = if (cfg.pill == Pill.NONE) 0 else 2 * ends
        maxWidth.intValue = (s.free.width() - 2 * gap - pillPad).coerceAtLeast(0)
        heightDp.value = (s.bar.height() / density).dp
        val where = "free=${s.free.toShortString()} x=${p.x} maxW=${maxWidth.intValue} [${s.summary}]"
        if (where != lastPlace) { trace("place $where"); lastPlace = where }
        BarStatus.current.value = status()
        if (!strip.shown) {
            stripEmpty = false // a new window starts visible; its first measure says whether it has content
            strip.show { StripHost() }
            // Android refused the window (the service is going): nothing is on screen, so nothing may run or go online.
            // The next look at the bar tries again.
            if (!strip.shown) { BarStatus.current.value = BarStatus.STOPPED; return }
            awake.update()
            // Back on screen (after a full-screen app, the lock screen, a covering panel): the bar may
            // look different now, or only in a while (after an unlock it kept the lock screen's look for
            // seconds), and a reading owed while hidden is taken here.
            colors.readSoon(ColorWatch.Cause.SLOW, 250)
            Ticker.start("bar")
            Log.i(tag, "bar shown: bar=${s.bar.toShortString()} free=${s.free.toShortString()} [${s.summary}]")
        } else strip.relayout()
    }

    // ---- the strip ---------------------------------------------------------------------------

    fun hiddenItems(): List<ItemConfig> {
        val states = Ticker.states.value
        return Store.config.value.behindChevron { states[it.id]?.active == true }
    }

    @Composable
    private fun StripHost() {
        val cfg by Store.config.collectAsState()
        val states by Ticker.states.collectAsState()
        val entries = { list: List<ItemConfig> -> list.map { StripEntry(it, states[it.id] ?: Ticker.placeholder(it)) } }
        // While dragging, the bar shows the order the item would land in.
        val items = dragPreview.value?.let { (id, at) -> cfg.items.moved(id, Section.SHOWN, at) } ?: cfg.items
        val overflow by BarOverflow.ids.collectAsState()
        val plan = cfg.copy(items = items).stripPlan({ states[it.id]?.active == true }, expanded.value, overflow.isNotEmpty())
        val shownEntries = entries(plan.visible)
        val revealedEntries = entries(plan.revealed)
        // The one rule for an empty strip: what it puts on the screen, never how many entries it has.
        val drawn = StripContent.drawn(shownEntries + revealedEntries, plan.chevron, look.value.pill)
        // While the window is invisible nothing draws, and the remeasure Compose hands to the next draw would wait for it for
        // good. Measuring still runs, if it is asked for: the rule above is heard as soon as the composition changes.
        val view = LocalView.current
        SideEffect { if (stripEmpty) view.requestLayout() }
        MeasuredStrip(bias = when (cfg.position) { Position.RIGHT -> 1f; Position.CENTER -> 0f; Position.LEFT -> -1f },
            onWidth = { w -> val empty = StripContent.empty(w, drawn); if (w != strip.params.width || empty != stripEmpty) main.post { applyWidth(w, empty) } }) {
            Strip(
                visible = shownEntries,
                revealed = revealedEntries,
                showChevron = plan.chevron,
                chevronAlways = plan.chevronAlways,
                chevronReservePx = (30 * density).toInt(),
                expanded = plan.open,
                chevronOnLeft = cfg.position != Position.LEFT,
                look = look.value,
                maxWidthPx = maxWidth.intValue,
                heightDp = heightDp.value,
                events = events,
                onOverflow = { ids -> main.post { if (BarOverflow.ids.value != ids) { BarOverflow.ids.value = ids; main.post(scanNow) } } },
                // The item a popup belongs to: an item's popup and its right-click menu are the item's, the ‹ menu is ‹'s.
                open = menus.menuKey?.owner?.ifEmpty { null },
            )
        }
    }

    /** Sizes the strip window to its content (an exact width, never WRAP_CONTENT). */
    /**
     * Nothing to draw ([StripContent.empty]: every item off, only rule items that don't apply now, only blank items): the
     * strip's window is made invisible rather than left up empty, which is what Android's threat
     * detection looks for ("imperceptible content" from an accessibility service). Its content keeps
     * being measured, so it shows again as soon as there's something to draw ([StripHost] asks for that measure).
     */
    private var stripEmpty = false

    private fun status() = StripContent.status(empty = strip.shown && stripEmpty, noRoom = maxWidth.intValue == 0)

    private fun applyWidth(w: Int, empty: Boolean) {
        if (empty != stripEmpty) {
            stripEmpty = empty; strip.setContentVisible(!empty); trace("strip ${if (empty) "empty" else "has content"}"); awake.update()
            colors.contentChanged(empty)
            if (started && strip.shown) BarStatus.current.value = status()
        }
        val width = w.coerceAtLeast(1)
        if (strip.params.width == width) return
        trace("width ${strip.params.width} -> $width")
        strip.params.width = width
        strip.relayout()
    }

    private var dragFrom: Pair<String, Rect>? = null
    private var dragCenters: Map<String, Float> = emptyMap()
    /** While an item is dragged: its id and the index it would land at, for the strip's live order. */
    private val dragPreview = mutableStateOf<Pair<String, Int>?>(null)

    /** Ends a drag without moving anything: the order drawn is the saved one again. */
    private fun cancelDrag() { dragPreview.value = null; dragFrom = null; dragCenters = emptyMap() }

    private val events = object : StripEvents {
        override fun placed(id: String, at: Rect) { placed[id] = at }

        override fun gone(id: String) { placed -= id }

        override fun click(item: ItemConfig, at: Rect) {
            placed[item.id] = at
            // A click ends the hover's tooltip, whatever it does next (a screenshot must not catch it).
            menus.hideTip()
            val type = Items.of(item.type) ?: return
            val clicked = Ticker.guarded(type, "onClick") { type.onClick(item) } ?: return
            if (clicked) { Ticker.refresh(); return }
            if (type.menu == null) return
            menus.toggleMenu(MenuKey.Item(item.id), at, type.menuWidthDp) { host -> ItemMenu(item.id, host) }
        }

        override fun context(item: ItemConfig, at: Rect) {
            placed[item.id] = at
            menus.toggleMenu(MenuKey.ItemContext(item.id), at, 280) { host ->
                ItemContextMenu(item.id, host) {
                    val type = Items.of(item.type)
                    if (type?.menu != null) { menus.closeMenu(dissolve = true); menus.toggleMenu(MenuKey.Item(item.id), at, type.menuWidthDp) { h -> ItemMenu(item.id, h) } }
                }
            }
        }

        override fun scroll(item: ItemConfig, steps: Int) {
            Items.of(item.type)?.let { t -> Ticker.guarded(t, "onScroll") { t.onScroll(item, steps) } }
            Ticker.refresh()
        }

        /** The slider in the bar: the item sets what it stands for, and only that item is drawn again (no sampler runs for a drag). */
        override fun slide(item: ItemConfig, level: Float, done: Boolean) {
            sliding = !done
            menus.hideTip()
            Items.of(item.type)?.let { t -> Ticker.guarded(t, "onSlide") { t.onSlide(item, level, done) } }
            Ticker.refresh(item)
        }

        override fun chevron(at: Rect) {
            // While presenting, or with nothing behind it (show everything, or ‹ only for items that
            // don't fit), ‹ opens the menu.
            Store.config.value.let { if (it.presenting || it.hiddenMode == HiddenMode.SHOW_ALL || (!expanded.value && hiddenItems().isEmpty())) return chevronContext(at) }
            val next = !expanded.value
            expanded.value = next
            pinned = next
            if (Store.config.value.pinnedOpen != next) Store.update { it.copy(pinnedOpen = next) }
            main.removeCallbacks(collapse)
            val secs = Store.config.value.autoCollapseSec
            if (next && secs > 0) main.postDelayed(collapse, secs * 1000L)
        }

        override fun chevronContext(at: Rect) = menus.barMenu(at, everything = false)

        /**
         * A drag in the bar. While it moves, the strip previews the new order ([dragPreview]), so
         * the other items slide aside; on release that order is saved. The landing place is judged
         * against where the items were when the drag began (a snapshot), not their moving slots,
         * so it can't flip back and forth at a boundary. Only items in the bar move this way;
         * ones out on their own or revealed from ‹ keep their section.
         */
        override fun drag(item: ItemConfig, dx: Float, done: Boolean) {
            menus.hideTip()
            if (dragFrom?.first != item.id) {
                val r = placed[item.id]
                dragFrom = r?.let { item.id to Rect(it) }
                dragCenters = placed.filterKeys { it != item.id }.mapValues { it.value.exactCenterX() }
            }
            val from = dragFrom?.second
            val cfg = Store.config.value
            val index = if (item.section != Section.SHOWN || from == null) null
                else StripDrag.index(from.exactCenterX() + dx, cfg.items.filter { it.section == Section.SHOWN && it.id != item.id }.map { it.id }, BarOverflow.ids.value, dragCenters)
            if (!done) {
                if (index != null && dragPreview.value != (item.id to index)) dragPreview.value = item.id to index
                return
            }
            trace("drag ${item.type} dx=${dx.toInt()} -> index $index")
            if (index != null) Store.move(item.id, Section.SHOWN, index)
            cancelDrag()
        }

        override fun dragTakenAway(item: ItemConfig) { if (dragFrom?.first == item.id) cancelDrag() }

        override fun gapClick(key: String) {
            val at = placed[key] ?: return
            if (key == "chevron") { chevron(at); return }
            click(Store.config.value.items.firstOrNull { it.id == key } ?: return, at)
        }

        override fun wheel(up: Boolean) {
            val cfg = Store.config.value
            if (cfg.presenting || cfg.hiddenMode == HiddenMode.SHOW_ALL || hiddenItems().isEmpty()) return
            if (up == expanded.value) return
            if (up) { expanded.value = true; pinned = false } else fold()
        }

        override fun itemHover(item: ItemConfig, at: Rect, inside: Boolean) = menus.itemHover(item, at, inside, sliding)

        override fun hover(inside: Boolean) {
            trace("hover $inside")
            hovering = inside
            if (!inside) menus.hideTip() // the pointer left the strip: even an item that has gone, and can't say so itself
            main.removeCallbacks(expandOnHover); main.removeCallbacks(collapse)
            if (inside) {
                if (Store.config.value.hiddenMode == HiddenMode.HOVER && !expanded.value && hiddenItems().isNotEmpty()) main.postDelayed(expandOnHover, 350)
            } else if (!pinned) main.postDelayed(collapse, 900)
            else {
                val secs = Store.config.value.autoCollapseSec
                if (secs > 0) main.postDelayed(collapse, secs * 1000L)
            }
        }
    }

    /**
     * The "BentoBar menu" entry (a keyboard shortcut the user binds in the system's shortcut
     * settings): BentoBar's menu listing every item, focused for arrows and Enter. Again closes it.
     * False when the strip isn't showing.
     */
    fun openBarMenu(): Boolean {
        if (!started || !strip.shown) return false
        // Under the strip's end next to the system icons (its start when the strip is on the left).
        val w = strip.params.width
        val h = strip.params.height
        val at = if (Store.config.value.position == Position.LEFT) Rect(0, 0, 1, h) else Rect(w - 1, 0, w, h)
        menus.barMenu(at, everything = true)
        return true
    }

    // ---- remote keyboard mapping & cloud clipboard shortcut ----------------------------------

    fun onKeyEvent(event: KeyEvent): Boolean {
        if (RemoteKeyboardItem.onKeyEvent(event)) return true
        if (!CloudClipboardItem.matchesShortcut(event)) return false
        captureSelectedTextAndPush()
        return true
    }

    private data class SelectionCapture(
        val directText: String?,
        val performedCopy: Boolean,
    )

    private fun captureSelectedTextAndPush() {
        val capture = findSelectedTextOrCopy()
        if (!capture.directText.isNullOrBlank()) {
            CloudClipboardItem.pushCapturedText(capture.directText, fromShortcut = true)
            return
        }
        // Let the target app finish handling ACTION_COPY while it still owns window focus,
        // then briefly make the strip focusable so Android 10+ allows reading ClipboardManager.primaryClip
        // (including copied content:// or file:// URIs).
        val copySettleMs = if (capture.performedCopy) 60L else 10L
        main.postDelayed({
            val originalFlags = strip.params.flags
            val hadNotFocusable = (originalFlags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0
            if (strip.shown && hadNotFocusable) {
                strip.params.flags = (originalFlags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()) or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                strip.relayout()
            }
            main.postDelayed({
                val firstSnapshot = CloudClipboardItem.readLocalClipboardSnapshot()
                if (!firstSnapshot.isEmpty) {
                    if (strip.shown && hadNotFocusable) {
                        strip.params.flags = originalFlags
                        strip.relayout()
                    }
                    CloudClipboardItem.pushCapturedClip(firstSnapshot, fromShortcut = true)
                } else {
                    main.postDelayed({
                        val secondSnapshot = CloudClipboardItem.readLocalClipboardSnapshot()
                        if (strip.shown && hadNotFocusable) {
                            strip.params.flags = originalFlags
                            strip.relayout()
                        }
                        CloudClipboardItem.pushCapturedClip(secondSnapshot, fromShortcut = true)
                    }, 120L)
                }
            }, 100L)
        }, copySettleMs)
    }

    private fun findSelectedTextOrCopy(): SelectionCapture {
        var extracted: String? = null
        var copiedAny = false
        val selectedNodes = ArrayList<AccessibilityNodeInfo>()

        fun extractFromNode(node: AccessibilityNodeInfo?): Boolean {
            if (node == null) return false
            val start = node.textSelectionStart
            val end = node.textSelectionEnd
            val raw = node.text
            if (start >= 0 && end >= 0 && start != end) {
                val lo = minOf(start, end)
                val hi = maxOf(start, end)
                if (raw != null && lo >= 0 && hi <= raw.length) {
                    val sub = raw.subSequence(lo, hi).toString().trim()
                    if (sub.isNotEmpty() && extracted == null) {
                        extracted = sub
                    }
                }
                if (node.performAction(AccessibilityNodeInfo.ACTION_COPY)) {
                    copiedAny = true
                }
                return true
            }
            if ((node.isSelected || node.isChecked) && selectedNodes.size < 8) {
                selectedNodes.add(node)
            }
            return false
        }

        // 1. Check focused nodes first (most accurate for the active editor/browser)
        val inputFocused = runCatching { service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
        val a11yFocused = runCatching { service.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY) }.getOrNull()
        extractFromNode(inputFocused)
        if (extracted == null) extractFromNode(a11yFocused)

        // 2. Walk the accessibility node tree starting with the active/focused window first
        val visited = HashSet<AccessibilityNodeInfo>()
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 28 || !visited.add(node)) return
            extractFromNode(node)
            for (i in 0 until node.childCount) {
                walk(runCatching { node.getChild(i) }.getOrNull(), depth + 1)
            }
        }

        if (extracted == null) {
            val orderedRoots = buildList {
                runCatching { service.rootInActiveWindow }.getOrNull()?.let { add(it) }
                val wins = runCatching { service.windows }.getOrNull().orEmpty()
                wins.filter { it.isFocused || it.isActive }.forEach { w ->
                    runCatching { w.root }.getOrNull()?.let { add(it) }
                }
                wins.filterNot { it.isFocused || it.isActive }.forEach { w ->
                    runCatching { w.root }.getOrNull()?.let { add(it) }
                }
            }
            for (root in orderedRoots) {
                walk(root, 0)
                if (extracted != null) break
            }
        }

        // 3. Even if textSelectionStart/End wasn't exposed (e.g. WebView/Chrome canvas or selected file items), trigger ACTION_COPY
        if (!copiedAny) {
            if (inputFocused?.performAction(AccessibilityNodeInfo.ACTION_COPY) == true) {
                copiedAny = true
            } else if (a11yFocused?.performAction(AccessibilityNodeInfo.ACTION_COPY) == true) {
                copiedAny = true
            } else {
                for (sel in selectedNodes) {
                    if (sel.performAction(AccessibilityNodeInfo.ACTION_COPY)) {
                        copiedAny = true
                        break
                    }
                }
            }
        }
        return SelectionCapture(extracted, copiedAny)
    }
    // ---- tests (adb only) --------------------------------------------------------------------

    fun debug(cmd: List<String>): String {
        fun first(type: String) = Store.config.value.items.firstOrNull { it.type == type || it.id == type }
        return when (cmd.firstOrNull()) {
            "dump" -> "snap=$snap look=${look.value} expanded=${expanded.value} menu=${menus.menuKey} shown=${strip.shown} empty=$stripEmpty placed=$placed"
            "open", "ctx" -> {
                val item = first(cmd.getOrElse(1) { "" }) ?: return "no such item"
                val at = placed[item.id] ?: return "item not drawn yet"
                if (cmd[0] == "open") events.click(item, at) else events.context(item, at); "ok"
            }
            "chevron" -> { events.chevron(Rect()); "expanded=${expanded.value}" }
            "scroll" -> {
                val item = first(cmd.getOrElse(1) { "" }) ?: return "no such item"
                events.scroll(item, cmd.getOrNull(2)?.toIntOrNull() ?: 1); "ok"
            }
            "barmenu" -> { events.chevronContext(placed["chevron"] ?: Rect(0, 0, 40, 40)); "ok" }
            "hover" -> { events.hover(cmd.getOrNull(1) == "on"); "ok" }
            "close" -> { menus.closeMenu(); "ok" }
            "solid" -> { MenuWindow.solid = cmd.getOrNull(1) != "off"; "solid=${MenuWindow.solid}" }
            "scan" -> { scan(); "snap=$snap" }
            "sample" -> { colors.sampleNow(); "sampling" }
            "trace" -> { tracing = cmd.getOrNull(1) != "off"; "trace=$tracing" }
            else -> "unknown"
        }
    }

    companion object {
        private const val RELEVANT = AccessibilityEvent.WINDOWS_CHANGE_ADDED or AccessibilityEvent.WINDOWS_CHANGE_REMOVED or
            AccessibilityEvent.WINDOWS_CHANGE_BOUNDS
    }
}
