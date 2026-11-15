package com.meridian11.books.bentobar.bar

import android.app.KeyguardManager
import android.os.Handler
import android.os.PowerManager
import android.util.Log
import android.view.WindowManager
import com.meridian11.books.bentobar.data.Position
import com.meridian11.books.bentobar.items.Caffeine
import com.meridian11.books.bentobar.items.DisplaysItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Keep awake holds the screen on through the strip's own window (FLAG_KEEP_SCREEN_ON on a
 * window that's visible), not a window of its own. It used to be a separate 1×1 see-through
 * overlay: Play Protect's live threat detection flags an accessibility service that keeps
 * "imperceptible content" on screen ("App displays over other apps"). The catch: while the strip
 * is hidden (a full-screen app, the screen locked), keep awake waits; full-screen video players
 * keep the screen on themselves.
 *
 * [windows] are the strips' windows, and [holder] says which of them holds the flag right now: one that is visible (on
 * screen, measured, with something drawn; [KeepAwakeRule.holder]), or none.
 */
internal class KeepAwake(
    private val main: Handler,
    private val pm: PowerManager,
    private val km: KeyguardManager,
    private val scope: CoroutineScope,
    private val windows: List<Overlay>,
    private val holder: () -> Overlay?,
) {
    private val tag = BarService.TAG
    private val expiry = Runnable { Caffeine.check() }
    private var stopped = false
    private var wasOn = false

    /** See [update]. Deprecated in favor of FLAG_KEEP_SCREEN_ON, which needs a visible window. */
    @Suppress("DEPRECATION")
    private val screenLock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "BentoBar:keepAwake").apply { setReferenceCounted(false) }

    fun start() {
        scope.launch {
            Caffeine.until.collect { until ->
                update()
                main.removeCallbacks(expiry)
                // Real time left, as the timers count it: while the bar is hidden no tick is there to notice the clock moved.
                if (until != 0L && until != Caffeine.FOREVER) main.postDelayed(expiry, Caffeine.left(until).coerceAtLeast(0) + 200)
            }
        }
        scope.launch { DisplaysItem.keepingAwake.collect { update() } }
    }

    fun update() {
        if (stopped) return
        val on = Caffeine.active() || DisplaysItem.keepingAwake.value
        if (on != wasOn) { wasOn = on; Log.i(tag, if (on) "keep awake on" else "keep awake off") }
        val c = KeepAwakeRule.choose(on, if (on) holder() else null, pm.isInteractive, km.isKeyguardLocked)
        // What holds the screen now holds it before anything else lets go: the flag on its window, or the wake lock, is
        // taken first, so at no moment does nothing hold it.
        c.flag?.let { flag(it, true) }
        if (c.lock && !screenLock.isHeld) { screenLock.acquire(); Log.i(tag, "keep awake: wake lock while no strip is visible") }
        for (window in windows) if (window !== c.flag) flag(window, false)
        if (!c.lock && screenLock.isHeld) screenLock.release()
    }

    private fun flag(window: Overlay, on: Boolean) {
        val f = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        val flags = if (on) window.params.flags or f else window.params.flags and f.inv()
        if (flags == window.params.flags) return
        window.params.flags = flags
        window.relayout()
    }

    /** The service is going: nothing it held may stay held, and from here on nothing is asked for. */
    fun stop() {
        stopped = true
        if (screenLock.isHeld) screenLock.release()
    }
}

/** What keep awake does with the strips' windows, as plain facts: [KeepAwake.update] carries it out (KeepAwakeTest). */
internal object KeepAwakeRule {
    /** [flag]: the window that holds the screen on, if one does; every other window lets go. [lock]: a wake lock holds it instead. */
    data class Choice<W>(val flag: W?, val lock: Boolean)

    /**
     * The area whose strip's window holds the flag: the first of [placed] (the areas placed, in room order) whose strip is
     * visible: on screen, measured, with something drawn. A strip shown and not measured yet ([BarRules.AreaState.pending])
     * has an invisible root until its first measure, and Android keeps the screen on for a visible window only: until it
     * shows, it is no holder, and the wake lock holds the screen. Null when no placed strip is visible.
     */
    fun holder(placed: List<Position>, strips: Map<Position, BarRules.AreaState>): Position? =
        placed.firstOrNull { area -> strips[area]?.let { it.shown && !it.pending && !it.empty } == true }

    /**
     * [holder] is the window of the area the other `holder` names, or null. The flag is on that window alone, never on
     * one that is invisible or gone.
     *
     * While no strip is visible (the bar is hidden by a full-screen app or from its tile, every area is empty, or the
     * strips that came back are not measured yet) a screen wake lock holds the screen instead, with no window. Released
     * when a strip is visible again, keep awake ends, the screen goes off (the power key or the lid) or the lock screen
     * is up.
     */
    fun <W> choose(on: Boolean, holder: W?, interactive: Boolean, locked: Boolean): Choice<W> {
        val flag = holder.takeIf { on }
        return Choice(flag, lock = on && flag == null && interactive && !locked)
    }
}
