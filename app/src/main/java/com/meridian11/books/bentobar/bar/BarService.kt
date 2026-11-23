package com.meridian11.books.bentobar.bar

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.meridian11.books.bentobar.data.HiddenMode
import com.meridian11.books.bentobar.data.areasInUse
import com.meridian11.books.bentobar.data.carrierOverflow
import com.meridian11.books.bentobar.data.chevronOpensMenu
import com.meridian11.books.bentobar.data.stripPlan
import com.meridian11.books.bentobar.data.behindChevron
import com.meridian11.books.bentobar.data.homeArea
import com.meridian11.books.bentobar.data.moved
import com.meridian11.books.bentobar.data.owedIds
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.data.Pill
import com.meridian11.books.bentobar.data.Position
import com.meridian11.books.bentobar.data.ROOM_ORDER
import com.meridian11.books.bentobar.data.Section
import com.meridian11.books.bentobar.data.Store
import com.meridian11.books.bentobar.items.AiUsageTerminal
import com.meridian11.books.bentobar.items.ChargeOptimization
import com.meridian11.books.bentobar.items.ChargeOptimizationRules
import com.meridian11.books.bentobar.items.TerminalMinimizeRules
import com.meridian11.books.bentobar.items.Chips
import com.meridian11.books.bentobar.items.CloudClipboardItem
import com.meridian11.books.bentobar.items.DisplaysItem
import com.meridian11.books.bentobar.items.Env
import com.meridian11.books.bentobar.items.RemoteKeyboardItem
import com.meridian11.books.bentobar.items.Items
import com.meridian11.books.bentobar.items.Settle
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

        fun toggleChargeOptimization(): Boolean =
            current?.controller()?.toggleChargeOptimization() ?: false

        fun minimizeTerminalWindow(): Boolean =
            current?.controller()?.scheduleTerminalMinimize() ?: false

        fun launchActivity(intent: Intent): Boolean {
            val svc = current ?: return false
            return runCatching {
                svc.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            }.getOrDefault(false)
        }

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
        // While the controller is still there, once for it: what a type holds beyond the bar (a mute, an endless Focus) ends
        // with the service. A rebind ends it too: it can't be told from an ending.
        if (stopping != null) for (t in Items.all) Ticker.guarded(t, "settle") { t.settle(Settle.SERVICE_ENDING) }
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
                watchOutside()
            }
    }
    /** The strip's colours and the readings of the status bar behind them. */
    private val colors = ColorReader(service, main, object : ColorReader.Facts {
        override val started get() = this@BarController.started
        override val onScreen get() = strips.any { it.shown }
        override val empty get() = stripEmpty
        override val interactive get() = pm.isInteractive
        override val snapshot get() = snap
    }) { s, done -> StatusBarScan.shoot(service, s, done) }
    private val look get() = colors.look
    /** The room the first strip has: none (0) is what the status calls no room. Each strip's own room is its [AreaStrip.maxWidth]. */
    private val firstBudget = mutableIntStateOf(0)
    private val heightDp = mutableStateOf(36.dp)

    /** A strip for each area, in room order. On screen are those of the areas in use that have room. */
    private val strips = ROOM_ORDER.map { area ->
        val title = when (area) { Position.RIGHT -> "BentoBar"; Position.LEFT -> "BentoBar left"; Position.CENTER -> "BentoBar middle" }
        AreaStrip(area, Overlay(service, title, onOutside = { outsideClick(it) }).apply { params.width = 1; watchOutside(false) })
    }
    /** The area whose strip does what the bar does once: the first one placed. State: a strip that becomes it is composed again. */
    private var carrier by mutableStateOf(ROOM_ORDER.first())
    private fun carrierStrip() = strips.first { it.area == carrier }
    /** The areas in use that have no room right now: their items are on no strip. */
    private val owed = mutableStateOf(emptySet<Position>())
    /** What those areas would draw now, as the first strip last said it: its ‹ is for them, and its menu lists them. */
    private var owedItems = emptySet<String>()
    /**
     * The areas whose strip is on view: on screen, measured and with something to draw. Not [AreaStrip.draws], which also
     * counts a strip that has not been measured yet. The empty capsule is the home area's only while no other is among them.
     */
    private val onView = mutableStateOf(emptySet<Position>())
    /** The areas placed, in room order: the strips whose window may keep the screen on ([KeepAwakeRule.holder]). */
    private var placedAreas: List<Position> = emptyList()

    /** The strip's window that keeps the screen on now: the first placed that is visible. Null when none is. */
    private fun awakeWindow(): Overlay? =
        KeepAwakeRule.holder(placedAreas, strips.associate { it.area to it.state() })?.let { area -> strips.first { it.area == area }.window }

    /** Keep awake: a flag on the visible strip's window, a wake lock while no strip is visible to carry it. */
    private val awake = KeepAwake(main, pm, km, scope, strips.map { it.window }) { awakeWindow() }

    /**
     * Presses outside the bar are heard by one strip's window alone (the first on view: [PopupRules.watchingArea]), and
     * only while hidden items are out: one press is heard once, in however many windows the bar is. Asked again when
     * hidden items come out or go, when the first strip is another and when what is on view changes.
     */
    private fun watchOutside() {
        val watching = PopupRules.watchingArea(onView.value, carrier)
        for (strip in strips) strip.watchOutside(expanded.value && strip.area == watching)
    }

    /**
     * The strip a press at [x], [y] on the screen landed on, of those on view (a press where an invisible window lies goes
     * to what is below it, and is not on the bar); null for a press on none of them.
     */
    private fun stripAt(x: Float, y: Float): AreaStrip? {
        val height = snap?.bar?.height() ?: 0
        val seen = strips.filter { it.area in onView.value }
        val area = PopupRules.pressedArea(x, y, seen.map { s -> s.locationOnScreen().let { StripFrame(s.area, it[0], it[1], s.params.width, height) } })
        return seen.firstOrNull { it.area == area }
    }

    /**
     * A click outside the bar while hidden items are out folds them away, unless a menu is open (it closes itself). To
     * the window that hears it a press on another strip is outside too: that one is on the bar, and is that strip's own.
     */
    private fun outsideClick(ev: MotionEvent) {
        if (menus.menuKey != null || !expanded.value) return
        if (stripAt(ev.rawX, ev.rawY) != null) return
        fold()
    }

    /** Hidden items go back behind ‹ and stay there: with their pin gone too, or the next start of the service opens them with no click. */
    private fun fold() {
        expanded.value = false; pinned = false
        if (Store.config.value.pinnedOpen) Store.update { it.copy(pinnedOpen = false) }
    }
    private var snap: BarSnapshot? = null
    private var overlayIds = emptySet<Int>()
    /** The areas whose strip the pointer is over: an Exit from one strip can arrive after the Enter of the next. */
    private var hoverAreas = emptySet<Position>()
    /** The pointer is over the bar. */
    private val hovering get() = hoverAreas.isNotEmpty()
    private var pinned = false
    private var started = false
    /** The bar is on screen as far as the last look at it said. A strip's measure can arrive after the bar has left. */
    private var showing = false

    /** The popups and the tooltip. */
    private val menus: MenuCoordinator = MenuCoordinator(service, main, wm, object : MenuCoordinator.Facts {
        override val snapshot get() = snap
        override val expanded get() = this@BarController.expanded.value
        override fun stripAt(x: Float, y: Float) = this@BarController.stripAt(x, y)
        override fun stripOf(id: String) = strips.firstOrNull { id in it.placed }
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
            val shown = strips.any { it.shown }
            if (shown) lightCheck() else check(full = false)
            main.postDelayed(this, if (shown) 2_000 else 5_000)
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
    /**
     * The pointer came onto the bar or left it ([inside]), whichever strip said so: what a hover brings out, and what
     * its end lets fold away. A hover ends here only, for the pointer going and for the last strip under it going.
     */
    private fun barHover(inside: Boolean) {
        main.removeCallbacks(expandOnHover); main.removeCallbacks(collapse)
        if (inside) {
            if (Store.config.value.hiddenMode == HiddenMode.HOVER && !expanded.value && hiddenItems().isNotEmpty()) main.postDelayed(expandOnHover, 350)
        } else if (!pinned) main.postDelayed(collapse, 900)
        else {
            val secs = Store.config.value.autoCollapseSec
            if (secs > 0) main.postDelayed(collapse, secs * 1000L)
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
            if (i.action == Intent.ACTION_USER_PRESENT) {
                if (com.meridian11.books.bentobar.items.AiUsageTerminal.hasAiUsageItems(Store.config.value.items)) {
                    main.postDelayed({
                        if (started) {
                            com.meridian11.books.bentobar.items.AiUsageReader.ensureDaemonReady(
                                com.meridian11.books.bentobar.items.TerminalWakeReason.BACKGROUND_POLL
                            )
                        }
                    }, 800L)
                }
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
        // Last: what a type left behind when the service last ended, or the process with it (a mute of its own with no such
        // item placed any more, a Focus session past its end), it gives back now.
        for (t in Items.all) Ticker.guarded(t, "settle") { t.settle(Settle.SERVICE_STARTED) }
        if (com.meridian11.books.bentobar.items.AiUsageTerminal.hasAiUsageItems(Store.config.value.items)) {
            // Wait briefly for scan() to place the visible strip overlay window (so Android BAL allows startActivity).
            main.postDelayed({
                if (started) {
                    com.meridian11.books.bentobar.items.AiUsageReader.ensureDaemonReady(
                        com.meridian11.books.bentobar.items.TerminalWakeReason.BACKGROUND_POLL
                    )
                }
            }, 800L)
        }
    }

    fun stop() {
        if (!started) return // stopped already: the status and the ticker may be a newer controller's by now
        started = false
        chargeOptSession = null
        chargeOptDueAt = 0L
        terminalMinSession = null
        terminalMinDueAt = 0L
        BarLook.current.value = null
        BarStatus.current.value = BarStatus.STOPPED
        BarOverflow.ids.value = emptySet()
        owedItems = emptySet()
        runCatching { service.unregisterReceiver(screen) }
        colors.stop()
        // Before the strip goes, which would ask keep awake for a wake lock in its place.
        awake.stop()
        DisplaysItem.follow(false)
        leaveScreen()
        // Nothing is revealed once this controller is gone: the ticker outlives it, in the process.
        Ticker.revealHidden = false
        main.removeCallbacksAndMessages(null) // after closeMenu, which posts a collapse
        for (strip in strips) strip.window.destroy()
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
        hoverAreas = emptySet(); main.removeCallbacks(expandOnHover)
        if (!pinned && expanded.value) expanded.value = false
        showing = false // before the windows go: a measure on its way finds the bar gone
        for (strip in strips) strip.hide()
        noteOnView()
        // Readings still to come were for a strip that is gone: its return asks again.
        colors.stripLeft()
        awake.update()
        Ticker.stop("bar")
    }

    /**
     * One strip's window goes while the bar stays: its area went out of use, or has no room. What hangs from it goes
     * before the window that anchors it, and what the pointer held in it ends, as when the bar leaves; what it held in
     * another strip stays. The bar's own ending is [leaveScreen]'s alone.
     */
    private fun leave(strip: AreaStrip) {
        if (!strip.shown) return
        menus.closeFor(strip)
        if (dragFrom?.strip === strip) cancelDrag() // its window takes the release with it
        if (strip.area in hoverAreas) events.getValue(strip).hover(false) // and the Exit
        strip.hide()
        strip.dropped = emptySet() // what it had no room for goes with it
        noteOnView()
        publishOverflow()
    }

    /** Which strips are on view is asked again: a strip was measured, or went. The first of them hears a press outside the bar. */
    private fun noteOnView() {
        onView.value = strips.filter { it.shown && !it.pending && !it.empty }.mapTo(mutableSetOf()) { it.area }
        watchOutside()
    }

    /** The first strip says what the areas with no window would draw now, each time it is composed. */
    private fun owe(ids: Set<String>) {
        if (ids != owedItems) { owedItems = ids; publishOverflow() }
    }

    /**
     * What has no room right now, for Settings' rows and the ‹ menu: every strip's own and what the areas with no window
     * are owed, as one set. A ‹ coming or going changes a strip's width, so the bar is looked at again, and only when the
     * set changed.
     */
    private fun publishOverflow() {
        val ids = strips.flatMapTo(mutableSetOf()) { it.dropped } + owedItems
        if (BarOverflow.ids.value != ids) { BarOverflow.ids.value = ids; main.post(scanNow) }
    }

    /** Window events, logged only where asked for: `adb shell setprop log.tag.BentoBarEvents DEBUG`. */
    private val EVENTS_TAG = "BentoBarEvents"

    fun onEvent(e: AccessibilityEvent) {
        if (tracing) trace("event windows id=${e.windowId} changes=0x${Integer.toHexString(e.windowChanges)} ours=${e.windowId in overlayIds}")
        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                if (Log.isLoggable(EVENTS_TAG, Log.DEBUG)) Log.d(EVENTS_TAG, "windows changed id=${e.windowId} changes=0x${Integer.toHexString(e.windowChanges)} ours=${e.windowId in overlayIds}")
                if (e.windowId in overlayIds) return // our own windows resizing
                chargeOptSession?.let { cs ->
                    if (!cs.inFlightGesture) {
                        val nowUp = SystemClock.uptimeMillis()
                        if (chargeOptDueAt == 0L || chargeOptDueAt - nowUp > 30L) {
                            scheduleChargeOptTick(25L)
                        }
                    }
                }
                terminalMinSession?.let { ts ->
                    if (!ts.inFlightGesture) {
                        val nowUp = SystemClock.uptimeMillis()
                        if (terminalMinDueAt == 0L || terminalMinDueAt - nowUp > 60L) {
                            scheduleTerminalMinTick(45L)
                        }
                    }
                }
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
            showing = true
            place(s!!, screenW)
            // A new status bar window can look different (see ColorReader.readSoon for the other causes).
            if (d.read == BarRules.Read.NEW_BAR_WINDOW) colors.readSoon(ColorWatch.Cause.QUICK, 250)
            else if (d.read == BarRules.Read.WINDOWS_MOVED) colors.windowsMoved()
        } else {
            if (strips.any { it.shown }) Log.i(tag, "bar hidden (statusBar=${s != null} covered=${d.cover?.box?.toShortString()} " +
                "enabled=${cfg.enabled} interactive=$interactive locked=$locked)")
            leaveScreen()
        }
    }

    /** adb `debug trace on|off`: logs every move and resize with its cause, to chase jitter. */
    private var tracing = false
    private var lastWhere = ""
    /** What the strip was last placed by: a new width is placed by the same. */
    private var lastPlace: Pair<BarSnapshot, Int>? = null
    private fun trace(what: String) { if (tracing) Log.i(tag, "trace ${SystemClock.uptimeMillis() % 100_000} $what") }

    private fun place(s: BarSnapshot, screenW: Int) {
        lastPlace = s to screenW
        val cfg = Store.config.value
        val gap = (6 * density).toInt()
        // Without a pill the strip's ends are only room for the highlight's round ends: the window reaches that much
        // further out, into the gap, so the items stay 6 dp from the free area's edges as they always were.
        val ends = (STRIP_PILL_PADDING.value * density).toInt()
        // Which strips there are, where and how wide is one rule's answer, carried out here: a window for each area in
        // use that has room. Each fits its part of the free area: items, the pill's own padding and (decided by the
        // strip, from what fits) room for ‹.
        val a = BarRules.arrange(s.free.left, s.free.right, screenW, gap, out = if (cfg.pill == Pill.NONE) ends else 0, ends = ends,
            between = gap, least = (30 * density).toInt() + 2 * ends, home = cfg.homeArea(), inUse = cfg.areasInUse(),
            strips = strips.associate { it.area to it.state() })
        firstBudget.intValue = a.spots.first().budget
        heightDp.value = (s.bar.height() / density).dp
        val where = "free=${s.free.toShortString()} ${a.spots.joinToString(" ") { "${word(it.area)} x=${it.x} maxW=${it.budget}" }} [${s.summary}]"
        if (where != lastWhere) { trace("place $where"); lastWhere = where }
        val was = stripEmpty
        // Which strip is the first and which areas are owed only name areas, and are said before any window goes or comes:
        // a window that is added has the watch for presses outside the bar in its params already, as the one strip's had.
        val before = carrier
        carrier = a.carrier
        // Hidden items are out and the first strip is another now: that one hears a press outside the bar.
        if (carrier != before) watchOutside()
        owed.value = a.owed
        val held = awakeWindow()
        placedAreas = a.spots.map { it.area }
        // The window that keeps the screen on is about to go: the flag is on the next one first, or the wake lock holds.
        if (held != null && strips.any { it.window === held && it.area in a.leave }) awake.update()
        for (strip in strips) if (strip.area in a.leave) leave(strip)
        // A strip keeps what it had no room for while the bar is off the screen. An area that went out of use or lost its
        // room meanwhile has no window to say otherwise: nothing of it is left to publish.
        val stale = strips.filter { s -> s.dropped.isNotEmpty() && a.spots.none { it.area == s.area } }
        if (stale.isNotEmpty()) { for (strip in stale) strip.dropped = emptySet(); publishOverflow() }
        // A strip that gets wider, or comes, takes its room after the others have moved or narrowed: asked for the other way
        // round, it stood for a frame or two in room its neighbour had not left yet.
        for (spot in BarRules.givingWayFirst(a.spots, strips.filter { it.shown }.associate { it.area to it.params.width })) {
            val strip = strips.first { it.area == spot.area }
            val p = strip.params
            p.height = s.bar.height()
            p.y = s.bar.top
            p.gravity = Gravity.TOP or when (spot.area) { Position.RIGHT -> Gravity.RIGHT; Position.LEFT -> Gravity.LEFT; Position.CENTER -> Gravity.CENTER_HORIZONTAL }
            p.x = spot.x
            // Exactly as wide as its content last measured (never WRAP_CONTENT), set with where it stands: one move.
            if (p.width != spot.width) { trace("${word(spot.area)} width ${p.width} -> ${spot.width}"); p.width = spot.width }
            strip.maxWidth.intValue = spot.budget
            if (!strip.shown) strip.show { StripHost(strip) } else strip.relayout()
        }
        // Android refused an area's window while the others have theirs: the status says so, and the next placing tries again.
        refused = a.spots.any { spot -> strips.none { it.area == spot.area && it.shown } }
        // Strips came and went: whether the bar draws anything is asked again. A new window counts as drawing until its
        // first measure says whether it has content.
        stripEmpty = strips.none { it.draws }
        BarStatus.current.value = status()
        // The strips that were up have left and Android refused the ones in their place: the bar is off the screen.
        if (!a.first && strips.none { it.shown }) leaveScreen()
        // Android refused the window (the service is going): nothing is on screen, so nothing may run or go online.
        // The next look at the bar tries again.
        if (strips.none { it.shown }) { BarStatus.current.value = BarStatus.STOPPED; return }
        // Once for each placing, after every window it shows or moves is up: which strip is visible may have changed with
        // it, or with the measure that asked for it.
        awake.update()
        if (a.first) {
            // Back on screen (after a full-screen app, the lock screen, a covering panel): the bar may
            // look different now, or only in a while (after an unlock it kept the lock screen's look for
            // seconds), and a reading owed while hidden is taken here.
            colors.readSoon(ColorWatch.Cause.SLOW, 250)
            Ticker.start("bar")
            Log.i(tag, "bar shown: bar=${s.bar.toShortString()} free=${s.free.toShortString()} [${s.summary}]")
        } else if (stripEmpty != was) {
            // An area came or went and with it the last thing drawn, or the first: no reading of its own, only what an
            // empty bar owes or is owed.
            trace("strip ${if (stripEmpty) "empty" else "has content"}")
            colors.contentChanged(stripEmpty)
        }
    }

    /** An area as the user's word for it, for the trace and the dump. */
    private fun word(area: Position) = when (area) { Position.RIGHT -> "right"; Position.LEFT -> "left"; Position.CENTER -> "middle" }

    // ---- the strip ---------------------------------------------------------------------------

    fun hiddenItems(): List<ItemConfig> {
        val states = Ticker.states.value
        return Store.config.value.behindChevron { states[it.id]?.active == true }
    }

    @Composable
    private fun StripHost(strip: AreaStrip) {
        val cfg by Store.config.collectAsState()
        val states by Ticker.states.collectAsState()
        val entries = { list: List<ItemConfig> -> list.map { StripEntry(it, states[it.id] ?: Ticker.placeholder(it)) } }
        // While dragging, the bar shows the order the item would land in.
        val items = dragPreview.value?.let { (id, at) -> cfg.items.moved(id, Section.SHOWN, at) } ?: cfg.items
        val active = { item: ItemConfig -> states[item.id]?.active == true }
        // An area with no room has no window, and what it would draw now must not be out of reach: it is owed to the first
        // strip. Decided here, so a rule that starts to hold in such an area is heard at once; told once it is applied.
        val owes = if (strip.area == carrier) cfg.owedIds(owed.value, active, expanded.value) else emptySet()
        SideEffect { if (strip.area == carrier) owe(owes) }
        // Its own area's items, and a ‹ for what it has no room for itself: a placed strip's lack of room is not another's
        // business. The first strip's ‹ is also there for what is owed, for what waits behind the ‹ of an area with no window,
        // and for presenting's ‹ when the home area has no window.
        val plan = cfg.copy(items = items).stripPlan(strip.area, active, expanded.value,
            strip.dropped.isNotEmpty() || (strip.area == carrier && cfg.carrierOverflow(owed.value, owes, active)))
        val shownEntries = entries(plan.visible)
        val revealedEntries = entries(plan.revealed)
        // The one rule for an empty strip: what it puts on the screen, never how many entries it has.
        // The empty capsule shows once: in the home area, or the first strip's while the home area has no window, and not
        // beside another area's items.
        val drawn = StripContent.drawn(shownEntries + revealedEntries, plan.chevron, look.value.pill,
            home = strip.area == StripContent.capsuleArea(cfg.homeArea(), carrier, owed.value) && (onView.value - strip.area).isEmpty())
        // While the window is invisible nothing draws, and the remeasure Compose hands to the next draw would wait for it for
        // good. Measuring still runs, if it is asked for: the rule above is heard as soon as the composition changes.
        val view = LocalView.current
        SideEffect { if (strip.empty) view.requestLayout() }
        // Its reach is known when its items are laid out, before its width is. It is read as state where the width is
        // reported, so a fit that changed under an unchanged width (an item gone, a ‹ come) is still heard.
        val reach = remember { mutableIntStateOf(0) }
        MeasuredStrip(bias = when (strip.area) { Position.RIGHT -> 1f; Position.CENTER -> 0f; Position.LEFT -> -1f },
            onWidth = { w ->
                val empty = StripContent.empty(w, drawn)
                val r = reach.intValue
                val window = strip.generation
                if (w != strip.measured || empty != strip.empty || strip.pending || r != strip.reach) main.post { applyWidth(strip, window, w, empty, r) }
            }) {
            Strip(
                visible = shownEntries,
                revealed = revealedEntries,
                showChevron = plan.chevron,
                // The first strip's ‹ also comes and goes for the areas with no room. With several areas in use it keeps
                // a ‹'s room whatever it draws, so that what it keeps, and with it the room behind it, never follows that ‹.
                chevronAlways = plan.chevronAlways || (strip.area == carrier && cfg.areasInUse().size > 1),
                chevronReservePx = (30 * density).toInt(),
                expanded = plan.open,
                chevronOnLeft = strip.area != Position.LEFT,
                look = look.value,
                maxWidthPx = strip.maxWidth.intValue,
                heightDp = heightDp.value,
                events = events.getValue(strip),
                onOverflow = { ids ->
                    val window = strip.generation
                    main.post { if (strip.shown && window == strip.generation) { strip.dropped = ids; publishOverflow() } }
                },
                // The item a popup belongs to: an item's popup and its right-click menu are the item's, the ‹ menu is ‹'s.
                open = menus.menuKey?.owner?.ifEmpty { null }.takeIf { menus.menuStrip === strip },
                onReach = { reach.intValue = it },
            )
        }
    }

    /**
     * No strip draws anything ([AreaStrip.draws]: a strip shown and not measured yet counts as drawing, until its first
     * measure says). A strip with nothing to draw ([StripContent.empty]: every item off, only rule items that don't apply
     * now, only blank items) has its window made invisible rather than left up empty, which is what Android's threat
     * detection looks for ("imperceptible content" from an accessibility service). Its content keeps being measured, so
     * it shows again as soon as there's something to draw ([StripHost] asks for that measure). Keep awake and the colours
     * are told when this changes.
     */
    private var stripEmpty = false
    /** An area with a place has no window: Android refused it at the last placing. */
    private var refused = false

    private fun status() = StripContent.status(refused, empty = strips.any { it.shown } && stripEmpty, noRoom = firstBudget.intValue == 0)

    /**
     * A strip measured its content: what it measured is kept on the strip, and the bar is placed again by it. [window] is
     * the strip's [AreaStrip.generation] when it was measured.
     */
    private fun applyWidth(strip: AreaStrip, window: Int, w: Int, empty: Boolean, reach: Int) {
        if (!strip.shown || window != strip.generation) return // it left meanwhile, or left and came back: another window's measure
        val was = stripEmpty
        strip.measured = w; strip.empty = empty; strip.reach = reach; strip.pending = false
        strip.setContentVisible(!empty) // the one place a strip's window becomes visible, or invisible again
        noteOnView()
        stripEmpty = strips.none { it.draws }
        if (stripEmpty != was) {
            trace("strip ${if (stripEmpty) "empty" else "has content"}")
            colors.contentChanged(stripEmpty)
        }
        // Placed again first, then keep awake is told once, by the placing: told before, it would choose by the placing
        // that is about to change, and take a wake lock and let it go within one measure.
        val placing = if (showing) lastPlace else null // never puts back a bar that has left
        if (placing != null) place(placing.first, placing.second) else awake.update()
        if (started && strips.any { it.shown }) BarStatus.current.value = status()
    }

    private var dragFrom: Drag? = null
    private var dragCenters: Map<String, Float> = emptyMap()
    /** While an item is dragged: its id and the index it would land at, for the strip's live order. */
    private val dragPreview = mutableStateOf<Pair<String, Int>?>(null)

    /** Ends a drag without moving anything: the order drawn is the saved one again. */
    private fun cancelDrag() { dragPreview.value = null; dragFrom = null; dragCenters = emptyMap() }

    /**
     * The one drag: its item, where that stood when the drag began, and the strip it is in. [from] and the others' centres
     * are places in that strip's window, so a drag is never carried on from another strip.
     */
    private class Drag(val id: String, val from: Rect, val strip: AreaStrip)

    /** What the pointer does on one strip: the same for each, but for the window its popups hang from and where its items stand. */
    private inner class AreaEvents(val strip: AreaStrip) : StripEvents {
        private val placed = strip.placed

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
            menus.toggleMenu(strip, MenuKey.Item(item.id), at, type.menuWidthDp) { host -> ItemMenu(item.id, host) }
        }

        override fun context(item: ItemConfig, at: Rect) {
            placed[item.id] = at
            menus.toggleMenu(strip, MenuKey.ItemContext(item.id), at, 280) { host ->
                ItemContextMenu(item.id, host) {
                    val type = Items.of(item.type)
                    if (type?.menu != null) { menus.closeMenu(dissolve = true); menus.toggleMenu(strip, MenuKey.Item(item.id), at, type.menuWidthDp) { h -> ItemMenu(item.id, h) } }
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
            // don't fit, in this area or owed to it), ‹ opens the menu.
            val hidden = hiddenItems()
            Store.config.value.let { if (chevronOpensMenu(it.presenting, it.hiddenMode, expanded.value, hidden.isNotEmpty(), hidden.any { h -> h.area == strip.area })) return chevronContext(at) }
            val next = !expanded.value
            expanded.value = next
            pinned = next
            if (Store.config.value.pinnedOpen != next) Store.update { it.copy(pinnedOpen = next) }
            main.removeCallbacks(collapse)
            val secs = Store.config.value.autoCollapseSec
            if (next && secs > 0) main.postDelayed(collapse, secs * 1000L)
        }

        override fun chevronContext(at: Rect) = menus.barMenu(strip, at, everything = false)

        /**
         * A drag in the bar. While it moves, the strip previews the new order ([dragPreview]), so
         * the other items slide aside; on release that order is saved. The landing place is judged
         * against where the items were when the drag began (a snapshot), not their moving slots,
         * so it can't flip back and forth at a boundary. Only items in the bar move this way;
         * ones out on their own or revealed from ‹ keep their section.
         */
        override fun drag(item: ItemConfig, dx: Float, done: Boolean) {
            menus.hideTip()
            if (dragFrom?.id != item.id || dragFrom?.strip !== strip) {
                val r = placed[item.id]
                dragFrom = r?.let { Drag(item.id, Rect(it), strip) }
                dragCenters = placed.filterKeys { it != item.id }.mapValues { it.value.exactCenterX() }
            }
            val from = dragFrom?.from
            val cfg = Store.config.value
            // It lands among its own area's items; with every item in one area those are all the others, as they always were.
            val others = cfg.items.filter { it.section == Section.SHOWN && it.id != item.id }
            val index = if (item.section != Section.SHOWN || from == null) null
                else StripDrag.index(from.exactCenterX() + dx, others.map { it.id }, BarOverflow.ids.value, dragCenters,
                    mine = others.filter { it.area == item.area }.map { it.id }.toSet(),
                    now = cfg.items.takeWhile { it.id != item.id }.count { it.section == Section.SHOWN })
            if (!done) {
                if (index != null && dragPreview.value != (item.id to index)) dragPreview.value = item.id to index
                return
            }
            trace("drag ${item.type} dx=${dx.toInt()} -> index $index")
            if (index != null) Store.move(item.id, Section.SHOWN, index)
            cancelDrag()
        }

        override fun dragTakenAway(item: ItemConfig) { if (dragFrom?.id == item.id && dragFrom?.strip === strip) cancelDrag() }

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

        override fun itemHover(item: ItemConfig, at: Rect, inside: Boolean) = menus.itemHover(strip, item, at, inside, sliding)

        override fun hover(inside: Boolean) {
            trace("hover $inside ${word(strip.area)}")
            val (areas, bar) = PopupRules.hoverChanged(hoverAreas, strip.area, inside)
            hoverAreas = areas
            // Onto this strip or off it with the pointer over another one as well: to the bar it is where it was. The
            // tooltip of the strip it left ends all the same; one the other strip is waiting to show is not this one's.
            if (bar == null && !inside) menus.hideTip(strip)
            if (bar == null) return
            if (!inside) menus.hideTip() // the pointer left the bar: even an item that has gone, and can't say so itself
            barHover(bar)
        }
    }

    private val events = strips.associateWith { AreaEvents(it) }

    /**
     * The "BentoBar menu" entry (a keyboard shortcut the user binds in the system's shortcut
     * settings): BentoBar's menu listing every item, focused for arrows and Enter. Again closes it.
     * False when the strip isn't showing.
     */
    fun openBarMenu(): Boolean {
        val strip = carrierStrip()
        if (!started || !strip.shown) return false
        // Under the strip's end next to the system icons (its start when the strip is on the left).
        val w = strip.params.width
        val h = strip.params.height
        val at = if (strip.area == Position.LEFT) Rect(0, 0, 1, h) else Rect(w - 1, 0, w, h)
        menus.barMenu(strip, at, everything = true)
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
            val targetStrip = carrierStrip().takeIf { it.shown } ?: strips.firstOrNull { it.shown } ?: carrierStrip()
            val originalFlags = targetStrip.params.flags
            val hadNotFocusable = (originalFlags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0
            if (targetStrip.shown && hadNotFocusable) {
                targetStrip.params.flags = (originalFlags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()) or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                targetStrip.relayout()
            }
            main.postDelayed({
                val firstSnapshot = CloudClipboardItem.readLocalClipboardSnapshot()
                if (!firstSnapshot.isEmpty) {
                    if (targetStrip.shown && hadNotFocusable) {
                        targetStrip.params.flags = originalFlags
                        targetStrip.relayout()
                    }
                    CloudClipboardItem.pushCapturedClip(firstSnapshot, fromShortcut = true)
                } else {
                    main.postDelayed({
                        val secondSnapshot = CloudClipboardItem.readLocalClipboardSnapshot()
                        if (targetStrip.shown && hadNotFocusable) {
                            targetStrip.params.flags = originalFlags
                            targetStrip.relayout()
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

    // ---- direct charging optimization navigation ---------------------------------------------

    private class ChargeOptSession(
        val startedAt: Long,
        val preExistingWindowIds: Set<Int>,
        var inFlightGesture: Boolean = false,
        var firstScreenSeenAt: Long = 0L,
        var lastNavKey: String? = null,
        var navAttempts: Int = 0,
        var navUsedGesture: Boolean = false,
        var lastNavAt: Long = 0L,
        var scrollCount: Int = 0,
        var triedBatteryHealth: Boolean = false,
    )

    private var chargeOptSession: ChargeOptSession? = null
    private var chargeOptDueAt: Long = 0L
    private val chargeOptTick = Runnable {
        chargeOptDueAt = 0L
        stepChargeOptimization()
    }

    private fun scheduleChargeOptTick(delayMs: Long) {
        main.removeCallbacks(chargeOptTick)
        chargeOptDueAt = SystemClock.uptimeMillis() + delayMs
        if (delayMs <= 0L) main.post(chargeOptTick) else main.postDelayed(chargeOptTick, delayMs)
    }

    private fun flattenWindows(topWindows: List<AccessibilityWindowInfo>): List<AccessibilityWindowInfo> {
        val out = ArrayList<AccessibilityWindowInfo>(topWindows.size + 4)
        val seen = HashSet<Int>(topWindows.size * 2)
        fun visit(w: AccessibilityWindowInfo) {
            if (!seen.add(w.id)) return
            // Visit attached child popups/dialogs before their parent window so modals win first
            val count = runCatching { w.childCount }.getOrDefault(0)
            for (i in 0 until count) {
                val child = runCatching { w.getChild(i) }.getOrNull() ?: continue
                visit(child)
            }
            out += w
        }
        for (w in topWindows) visit(w)
        return out
    }

    fun toggleChargeOptimization(): Boolean {
        if (!started) return false
        val existing = chargeOptSession
        if (existing != null) {
            if (!existing.inFlightGesture) scheduleChargeOptTick(0L)
            return true
        }

        if (menus.menuKey != null) menus.closeMenu(now = true)

        service.clearCache()
        val now = SystemClock.uptimeMillis()
        val ownPkg = service.packageName
        val wins = flattenWindows(runCatching { service.windows }.getOrDefault(emptyList()))
        val preWinIds = wins.mapTo(HashSet()) { it.id }
        val probeNodes = ArrayList<AccessibilityNodeInfo>(256)
        val initialScreens = ArrayList<ChargeOptimizationRules.SettingScreen>()
        for (w in wins) {
            if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val rootNode = runCatching { w.root }.getOrNull() ?: continue
            val pkg = rootNode.packageName?.toString().orEmpty()
            if (ChargeOptimizationRules.isSettingsPackage(pkg, ownPkg)) {
                val wb = Rect().also { w.getBoundsInScreen(it) }
                initialScreens += ChargeOptimizationRules.SettingScreen(
                    pkg = pkg,
                    windowId = w.id,
                    tree = toUiTree(rootNode, probeNodes, maxWindowNodes = 3_500),
                    isNewWindow = false,
                    windowLeft = wb.left,
                    windowTop = wb.top,
                    windowRight = wb.right,
                    windowBottom = wb.bottom,
                    displayId = w.displayId,
                )
            }
        }
        ChargeOptimizationRules.observeBatteryRowState(initialScreens)?.let { ChargeOptimization.recordConfirmed(it) }

        val topAppWin = wins
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .maxByOrNull { it.layer }
        val topAppIsSettings = topAppWin?.let { w ->
            val pkg = runCatching { w.root?.packageName?.toString() }.getOrNull().orEmpty()
            ChargeOptimizationRules.isSettingsPackage(pkg, ownPkg)
        } ?: false

        // Already looking at the Charging Optimization page on top: nothing to open or flicker.
        if (topAppIsSettings && ChargeOptimizationRules.isSubpageVisible(initialScreens)) return true

        val launched = Env.launch(ChargeOptimization.buildBatteryIntent(), quiet = true) ||
            Env.launch(Intent(android.provider.Settings.ACTION_BATTERY_SAVER_SETTINGS), quiet = true)
        if (!launched) return false

        chargeOptSession = ChargeOptSession(
            startedAt = now,
            preExistingWindowIds = preWinIds,
        )
        scheduleChargeOptTick(90L)
        return true
    }

    private fun toUiTree(
        node: AccessibilityNodeInfo,
        liveNodes: MutableList<AccessibilityNodeInfo>,
        maxWindowNodes: Int = 3_500,
        windowStartIdx: Int = liveNodes.size,
        depth: Int = 0,
    ): ChargeOptimizationRules.UiNode {
        val ref = liveNodes.size
        liveNodes += node
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        val checkedInt = if (android.os.Build.VERSION.SDK_INT >= 36) node.checked else -1
        val rawText = node.text?.toString().orEmpty()
        val suppText = if (android.os.Build.VERSION.SDK_INT >= 36) {
            node.supplementalDescription?.toString().orEmpty()
        } else {
            ""
        }
        val combinedText = when {
            rawText.isEmpty() -> suppText
            suppText.isEmpty() || suppText == rawText -> rawText
            else -> "$rawText\n$suppText"
        }
        val hasClick = node.isClickable ||
            node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }
        val childCount = node.childCount
        val withinCap = depth < 48 &&
            (liveNodes.size - windowStartIdx) < maxWindowNodes &&
            liveNodes.size < 12_000
        val children = if (withinCap && childCount > 0) {
            buildList {
                for (i in 0 until childCount) {
                    if ((liveNodes.size - windowStartIdx) >= maxWindowNodes || liveNodes.size >= 12_000) break
                    val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                    if (!child.isVisibleToUser && child.childCount == 0) {
                        val cb = Rect().also { child.getBoundsInScreen(it) }
                        if (cb.width() <= 0 || cb.height() <= 0) continue
                    }
                    add(toUiTree(child, liveNodes, maxWindowNodes, windowStartIdx, depth + 1))
                }
            }
        } else {
            emptyList()
        }
        return ChargeOptimizationRules.UiNode(
            ref = ref,
            id = node.viewIdResourceName.orEmpty(),
            cls = node.className?.toString().orEmpty(),
            text = combinedText,
            desc = node.contentDescription?.toString().orEmpty(),
            stateDesc = node.stateDescription?.toString().orEmpty(),
            paneTitle = node.paneTitle?.toString().orEmpty(),
            checkable = node.isCheckable,
            checked = node.isChecked,
            checkedState = checkedInt,
            selected = node.isSelected,
            clickable = hasClick,
            scrollable = node.isScrollable,
            enabled = node.isEnabled,
            visible = node.isVisibleToUser,
            left = bounds.left,
            top = bounds.top,
            right = bounds.right,
            bottom = bounds.bottom,
            children = children,
        )
    }

    private fun collectSettingScreens(
        wins: List<AccessibilityWindowInfo>,
        sess: ChargeOptSession,
        liveNodes: MutableList<AccessibilityNodeInfo>,
        ownPkg: String,
    ): List<ChargeOptimizationRules.SettingScreen> {
        val screens = ArrayList<ChargeOptimizationRules.SettingScreen>()
        val seenScreenWinIds = HashSet<Int>()

        for (w in wins) {
            val rootNode = when (w.type) {
                AccessibilityWindowInfo.TYPE_APPLICATION,
                AccessibilityWindowInfo.TYPE_SYSTEM -> runCatching { w.root }.getOrNull()
                else -> null
            } ?: continue
            runCatching { rootNode.refresh() }
            val pkg = rootNode.packageName?.toString().orEmpty()
            val isNew = w.id !in sess.preExistingWindowIds
            val eligible = if (w.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                ChargeOptimizationRules.isCandidateWindow(pkg, ownPkg, isNew)
            } else {
                ChargeOptimizationRules.isSettingsPackage(pkg, ownPkg)
            }
            if (!eligible) continue
            val wb = Rect().also { w.getBoundsInScreen(it) }
            seenScreenWinIds += w.id
            screens += ChargeOptimizationRules.SettingScreen(
                pkg = pkg,
                windowId = w.id,
                tree = toUiTree(rootNode, liveNodes, maxWindowNodes = 3_500),
                isNewWindow = isNew,
                windowLeft = wb.left,
                windowTop = wb.top,
                windowRight = wb.right,
                windowBottom = wb.bottom,
                displayId = w.displayId,
            )
        }

        // Fallback: include rootInActiveWindow if it belongs to Settings and wasn't in getWindows()
        runCatching { service.rootInActiveWindow }.getOrNull()?.let { activeRoot ->
            val winId = activeRoot.windowId
            if (winId !in seenScreenWinIds) {
                runCatching { activeRoot.refresh() }
                val pkg = activeRoot.packageName?.toString().orEmpty()
                val isNew = winId !in sess.preExistingWindowIds
                if (ChargeOptimizationRules.isCandidateWindow(pkg, ownPkg, isNew)) {
                    val rb = Rect().also { activeRoot.getBoundsInScreen(it) }
                    screens.add(
                        0,
                        ChargeOptimizationRules.SettingScreen(
                            pkg = pkg,
                            windowId = winId,
                            tree = toUiTree(activeRoot, liveNodes, maxWindowNodes = 3_500),
                            isNewWindow = isNew,
                            windowLeft = rb.left,
                            windowTop = rb.top,
                            windowRight = rb.right,
                            windowBottom = rb.bottom,
                            displayId = 0,
                        ),
                    )
                }
            }
        }

        return screens
    }

    private fun isPointCovered(
        wins: List<AccessibilityWindowInfo>,
        targetWinId: Int,
        x: Float,
        y: Float,
        displayId: Int,
    ): Boolean {
        if (x < 0f || y < 0f) return true
        val targetWin = wins.firstOrNull { it.id == targetWinId } ?: return false
        val targetLayer = targetWin.layer
        val ix = x.toInt()
        val iy = y.toInt()
        val rect = Rect()
        for (w in wins) {
            if (w.id == targetWinId || w.id in overlayIds || w.type == WINDOW_TYPE_CONTROL) continue
            if (w.displayId != displayId || w.layer <= targetLayer) continue
            w.getBoundsInScreen(rect)
            if (rect.contains(ix, iy)) return true
        }
        return false
    }

    private fun uncoveredCenterOf(
        wins: List<AccessibilityWindowInfo>,
        node: AccessibilityNodeInfo?,
    ): Triple<Float, Float, Int>? {
        node ?: return null
        val rect = Rect().also { node.getBoundsInScreen(it) }
        if (rect.width() <= 0 || rect.height() <= 0) return null
        val x = rect.exactCenterX()
        val y = rect.exactCenterY()
        val winId = node.windowId
        val displayId = wins.firstOrNull { it.id == winId }?.displayId ?: 0
        if (isPointCovered(wins, winId, x, y, displayId)) return null
        return Triple(x, y, displayId)
    }

    private fun stepChargeOptimization() {
        val sess = chargeOptSession ?: return
        val now = SystemClock.uptimeMillis()
        val limitMs = if (sess.inFlightGesture) 5_200L else 4_500L
        if (!started || now - sess.startedAt > limitMs) {
            chargeOptSession = null
            chargeOptDueAt = 0L
            return
        }
        if (sess.inFlightGesture) return

        service.clearCache()
        val wins = flattenWindows(runCatching { service.windows }.getOrDefault(emptyList()))
        val liveNodes = ArrayList<AccessibilityNodeInfo>(512)
        val screens = collectSettingScreens(wins, sess, liveNodes, service.packageName)
        if (screens.isNotEmpty() && sess.firstScreenSeenAt == 0L) sess.firstScreenSeenAt = now
        ChargeOptimizationRules.observeBatteryRowState(screens)?.let { ChargeOptimization.recordConfirmed(it) }

        val step = ChargeOptimizationRules.nextStep(
            screens = screens,
            lastNavKey = sess.lastNavKey,
            navAttempts = sess.navAttempts,
            scrollCount = sess.scrollCount,
            triedBatteryHealth = sess.triedBatteryHealth,
            sinceNavMs = if (sess.lastNavAt == 0L) Long.MAX_VALUE else now - sess.lastNavAt,
        )

        when (step) {
            ChargeOptimizationRules.Step.Arrived -> {
                chargeOptSession = null
                chargeOptDueAt = 0L
            }
            is ChargeOptimizationRules.Step.ClickNav -> {
                val settleRemaining = ChargeOptimizationRules.PRE_NAV_SETTLE_MS - (now - sess.firstScreenSeenAt)
                if (sess.firstScreenSeenAt != 0L && settleRemaining > 0L) {
                    scheduleChargeOptTick(settleRemaining + 15L)
                    return
                }
                val targetNode = liveNodes.getOrNull(step.ref)
                val isSameNav = sess.lastNavKey == step.navKey
                val coords = if (hasGestureCapability()) uncoveredCenterOf(wins, targetNode) else null
                fun recordNav(usedGesture: Boolean) {
                    if (isSameNav) sess.navAttempts++ else sess.navAttempts = 1
                    sess.lastNavKey = step.navKey
                    sess.navUsedGesture = usedGesture
                    sess.lastNavAt = now
                    if (step.navKey == "battery_health") {
                        sess.triedBatteryHealth = true
                        sess.scrollCount = 0
                    }
                }
                if (coords != null && (!isSameNav || !sess.navUsedGesture)) {
                    val (nx, ny, displayId) = coords
                    sess.inFlightGesture = true
                    recordNav(usedGesture = true)
                    val dispatched = tapAt(nx, ny, displayId) {
                        if (chargeOptSession !== sess) return@tapAt
                        sess.inFlightGesture = false
                        sess.lastNavAt = SystemClock.uptimeMillis()
                        scheduleChargeOptTick(120L)
                    }
                    if (dispatched) return
                    sess.inFlightGesture = false
                }
                targetNode?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                recordNav(usedGesture = false)
                scheduleChargeOptTick(120L)
            }
            is ChargeOptimizationRules.Step.Scroll -> {
                liveNodes.getOrNull(step.ref)?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                sess.scrollCount++
                sess.lastNavKey = "scroll"
                sess.lastNavAt = now
                scheduleChargeOptTick(120L)
            }
            ChargeOptimizationRules.Step.Wait -> scheduleChargeOptTick(80L)
        }
    }

    private fun hasGestureCapability(): Boolean = runCatching {
        val info = service.serviceInfo ?: return@runCatching false
        (info.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES) != 0
    }.getOrDefault(false)

    private fun tapAt(x: Float, y: Float, displayId: Int, onDone: (Boolean) -> Unit): Boolean {
        if (!hasGestureCapability()) return false
        val path = Path().apply { moveTo(x.coerceAtLeast(1f), y.coerceAtLeast(1f)) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 45L)
        val builder = GestureDescription.Builder()
        if (displayId >= 0) runCatching { builder.setDisplayId(displayId) }
        builder.addStroke(stroke)
        return runCatching {
            service.dispatchGesture(
                builder.build(),
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        main.post { onDone(true) }
                    }
                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        main.post { onDone(false) }
                    }
                },
                main,
            )
        }.getOrDefault(false)
    }

    // ---- auto-minimize Linux Terminal window after AI usage wake -----------------------------

    private class TerminalMinimizeSession(
        val startedAt: Long,
        var terminalSeenAt: Long = 0L,
        var attempts: Int = 0,
        var lastActionAt: Long = 0L,
        var usedGesture: Boolean = false,
        var inFlightGesture: Boolean = false,
    )

    private var terminalMinSession: TerminalMinimizeSession? = null
    private var terminalMinDueAt: Long = 0L
    private val terminalMinTick = Runnable {
        terminalMinDueAt = 0L
        stepTerminalMinimize()
    }

    private fun scheduleTerminalMinTick(delayMs: Long) {
        main.removeCallbacks(terminalMinTick)
        terminalMinDueAt = SystemClock.uptimeMillis() + delayMs
        if (delayMs <= 0L) main.post(terminalMinTick) else main.postDelayed(terminalMinTick, delayMs)
    }

    fun scheduleTerminalMinimize(): Boolean {
        if (!started) return false
        main.post {
            if (!started) return@post
            terminalMinSession = TerminalMinimizeSession(startedAt = SystemClock.uptimeMillis())
            scheduleTerminalMinTick(180L)
        }
        return true
    }

    private fun toCaptionTree(
        node: AccessibilityNodeInfo,
        liveNodes: MutableList<AccessibilityNodeInfo>,
        maxNodes: Int = 256,
        startIdx: Int = liveNodes.size,
        depth: Int = 0,
        maxBottom: Int = Int.MAX_VALUE,
    ): TerminalMinimizeRules.CaptionNode {
        val ref = liveNodes.size
        liveNodes += node
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        val hasClick = node.isClickable ||
            node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }
        val childCount = node.childCount
        val withinCap = depth < 24 && (liveNodes.size - startIdx) < maxNodes && liveNodes.size < 4_000
        val children = if (withinCap && childCount > 0 && bounds.top <= maxBottom) {
            buildList {
                for (i in 0 until childCount) {
                    if ((liveNodes.size - startIdx) >= maxNodes || liveNodes.size >= 4_000) break
                    val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                    val cb = Rect().also { child.getBoundsInScreen(it) }
                    if (cb.top > maxBottom && child.childCount == 0) continue
                    add(toCaptionTree(child, liveNodes, maxNodes, startIdx, depth + 1, maxBottom))
                }
            }
        } else {
            emptyList()
        }
        return TerminalMinimizeRules.CaptionNode(
            ref = ref,
            id = node.viewIdResourceName.orEmpty(),
            desc = node.contentDescription?.toString().orEmpty(),
            text = node.text?.toString().orEmpty(),
            clickable = hasClick,
            enabled = node.isEnabled,
            visible = node.isVisibleToUser,
            left = bounds.left,
            top = bounds.top,
            right = bounds.right,
            bottom = bounds.bottom,
            children = children,
        )
    }

    private fun stepTerminalMinimize() {
        val sess = terminalMinSession ?: return
        val now = SystemClock.uptimeMillis()
        if (!started || now - sess.startedAt > TerminalMinimizeRules.MAX_SESSION_MS + 1_000L) {
            terminalMinSession = null
            terminalMinDueAt = 0L
            return
        }
        if (sess.inFlightGesture) return

        service.clearCache()
        val wins = flattenWindows(runCatching { service.windows }.getOrDefault(emptyList()))
        val liveNodes = ArrayList<AccessibilityNodeInfo>(128)
        val snapshots = ArrayList<TerminalMinimizeRules.WindowSnapshot>(wins.size)

        // First locate any visible Terminal application window so we only inspect aligned caption bars.
        var terminalWin: AccessibilityWindowInfo? = null
        val terminalRect = Rect()
        for (w in wins) {
            if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val rootNode = runCatching { w.root }.getOrNull() ?: continue
            val pkg = rootNode.packageName?.toString().orEmpty()
            val wb = Rect().also { w.getBoundsInScreen(it) }
            val isTerm = pkg == AiUsageTerminal.TERMINAL_PACKAGE
            if (isTerm && wb.width() > 0 && wb.height() > 0) {
                if (terminalWin == null || w.layer > terminalWin.layer) {
                    terminalWin = w
                    terminalRect.set(wb)
                }
            }
            val tree = if (isTerm && wb.width() > 0 && wb.height() > 0) {
                toCaptionTree(rootNode, liveNodes, maxNodes = 160, maxBottom = wb.top + 180)
            } else {
                null
            }
            snapshots += TerminalMinimizeRules.WindowSnapshot(
                windowId = w.id,
                type = TerminalMinimizeRules.WindowKind.APPLICATION,
                packageName = pkg,
                layer = w.layer,
                displayId = w.displayId,
                left = wb.left,
                top = wb.top,
                right = wb.right,
                bottom = wb.bottom,
                tree = tree,
            )
        }

        if (terminalWin != null && sess.terminalSeenAt == 0L) {
            sess.terminalSeenAt = now
        }

        if (terminalWin != null) {
            for (w in wins) {
                val kind = when (w.type) {
                    WINDOW_TYPE_CONTROL -> TerminalMinimizeRules.WindowKind.WINDOW_CONTROL
                    AccessibilityWindowInfo.TYPE_SYSTEM -> TerminalMinimizeRules.WindowKind.SYSTEM
                    else -> continue
                }
                if (w.displayId != terminalWin.displayId || w.layer < terminalWin.layer) continue
                val wb = Rect().also { w.getBoundsInScreen(it) }
                if (wb.height() !in 16..180) continue
                if (kotlin.math.abs(wb.left - terminalRect.left) > 32 || kotlin.math.abs(wb.right - terminalRect.right) > 32) continue
                val rootNode = runCatching { w.root }.getOrNull()
                val pkg = rootNode?.packageName?.toString().orEmpty()
                val tree = rootNode?.let {
                    runCatching { it.refresh() }
                    toCaptionTree(it, liveNodes, maxNodes = 256)
                }
                snapshots += TerminalMinimizeRules.WindowSnapshot(
                    windowId = w.id,
                    type = kind,
                    packageName = pkg,
                    layer = w.layer,
                    displayId = w.displayId,
                    left = wb.left,
                    top = wb.top,
                    right = wb.right,
                    bottom = wb.bottom,
                    tree = tree,
                )
            }
        }

        when (
            val step = TerminalMinimizeRules.nextStep(
                windows = snapshots,
                terminalSeenAtMs = sess.terminalSeenAt,
                nowMs = now,
                attempts = sess.attempts,
                lastActionAtMs = sess.lastActionAt,
                usedGesture = sess.usedGesture,
                startedAtMs = sess.startedAt,
            )
        ) {
            TerminalMinimizeRules.Step.Done -> {
                Log.i(tag, "Linux Terminal minimized automatically (attempts=${sess.attempts})")
                terminalMinSession = null
                terminalMinDueAt = 0L
            }
            TerminalMinimizeRules.Step.GiveUp -> {
                Log.w(tag, "Linux Terminal auto-minimize timed out (seen=${sess.terminalSeenAt > 0L}, attempts=${sess.attempts})")
                terminalMinSession = null
                terminalMinDueAt = 0L
            }
            TerminalMinimizeRules.Step.Wait -> {
                val delay = when {
                    sess.terminalSeenAt > 0L && (now - sess.terminalSeenAt) < TerminalMinimizeRules.SETTLE_MS ->
                        (TerminalMinimizeRules.SETTLE_MS - (now - sess.terminalSeenAt)) + 25L
                    sess.attempts > 0 && (now - sess.lastActionAt) < TerminalMinimizeRules.RETRY_INTERVAL_MS ->
                        (TerminalMinimizeRules.RETRY_INTERVAL_MS - (now - sess.lastActionAt)) + 25L
                    else -> 140L
                }
                scheduleTerminalMinTick(delay.coerceIn(60L, 450L))
            }
            is TerminalMinimizeRules.Step.ClickMinimize -> {
                val targetNode = liveNodes.getOrNull(step.nodeRef)
                val canTap = hasGestureCapability() &&
                    !isPointCovered(wins, step.captionWindowId, step.centerX, step.centerY, step.displayId)

                fun dispatchMinimizeTap(): Boolean {
                    if (!canTap) return false
                    sess.inFlightGesture = true
                    sess.attempts++
                    sess.usedGesture = true
                    sess.lastActionAt = now
                    val dispatched = tapAt(step.centerX, step.centerY, step.displayId) {
                        if (terminalMinSession !== sess) return@tapAt
                        sess.inFlightGesture = false
                        sess.lastActionAt = SystemClock.uptimeMillis()
                        scheduleTerminalMinTick(180L)
                    }
                    if (!dispatched) {
                        sess.inFlightGesture = false
                    }
                    return dispatched
                }

                if (step.preferGesture && dispatchMinimizeTap()) return

                val clicked = targetNode?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
                if (!clicked && dispatchMinimizeTap()) return

                sess.attempts++
                sess.lastActionAt = now
                scheduleTerminalMinTick(220L)
            }
        }
    }

    // ---- tests (adb only) --------------------------------------------------------------------

    fun debug(cmd: List<String>): String {
        fun first(type: String) = Store.config.value.items.firstOrNull { it.type == type || it.id == type }
        // What names no item goes to the carrier's strip; what names one, to the strip that drew it.
        val strip = carrierStrip()
        val events = events.getValue(strip)
        // A hook for one strip can name its area (left, middle, right), whose strip must be on screen; with no word it is the
        // carrier's, as it was with one strip.
        fun stripOf(area: String?) = if (area == null) strip else strips.firstOrNull { it.shown && word(it.area) == area }
        return when (cmd.firstOrNull()) {
            "dump" -> "snap=$snap look=${look.value} expanded=${expanded.value} menu=${menus.menuKey} shown=${strips.any { it.shown }} empty=$stripEmpty " +
                "carrier=${word(carrier)} owed=${owed.value.map { word(it) }} " + strips.joinToString(" ") {
                    "${word(it.area)}[shown=${it.shown} empty=${it.empty} pending=${it.pending} width=${it.params.width} x=${it.params.x} " +
                        "budget=${it.maxWidth.intValue} placed=${it.placed}]"
                }
            "open", "ctx" -> {
                val item = first(cmd.getOrElse(1) { "" }) ?: return "no such item"
                val drew = strips.firstOrNull { item.id in it.placed } ?: return "item not drawn yet"
                val at = drew.placed.getValue(item.id)
                this.events.getValue(drew).let { if (cmd[0] == "open") it.click(item, at) else it.context(item, at) }; "ok"
            }
            "chevron" -> { val on = stripOf(cmd.getOrNull(1)) ?: return "no such strip"; this.events.getValue(on).chevron(Rect()); "expanded=${expanded.value}" }
            "scroll" -> {
                val item = first(cmd.getOrElse(1) { "" }) ?: return "no such item"
                events.scroll(item, cmd.getOrNull(2)?.toIntOrNull() ?: 1); "ok"
            }
            "barmenu" -> { events.chevronContext(strip.placed["chevron"] ?: Rect(0, 0, 40, 40)); "ok" }
            "hover" -> { val on = stripOf(cmd.getOrNull(2)) ?: return "no such strip"; this.events.getValue(on).hover(cmd.getOrNull(1) == "on"); "ok" }
            "close" -> { menus.closeMenu(); "ok" }
            "solid" -> { MenuWindow.solid = cmd.getOrNull(1) != "off"; "solid=${MenuWindow.solid}" }
            "scan" -> { scan(); "snap=$snap" }
            "sample" -> { colors.sampleNow(); "sampling" }
            "trace" -> { tracing = cmd.getOrNull(1) != "off"; "trace=$tracing" }
            else -> "unknown"
        }
    }

    companion object {
        private const val WINDOW_TYPE_CONTROL = 7
        private const val RELEVANT = AccessibilityEvent.WINDOWS_CHANGE_ADDED or AccessibilityEvent.WINDOWS_CHANGE_REMOVED or
            AccessibilityEvent.WINDOWS_CHANGE_BOUNDS
    }
}
