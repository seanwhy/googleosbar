package com.meridian11.books.bentobar.bar

import android.app.KeyguardManager
import android.os.Handler
import android.os.PowerManager
import android.util.Log
import android.view.WindowManager
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
 */
internal class KeepAwake(
    private val main: Handler,
    private val pm: PowerManager,
    private val km: KeyguardManager,
    private val scope: CoroutineScope,
    private val strip: Overlay,
    private val stripEmpty: () -> Boolean,
) {
    private val tag = BarService.TAG
    private val expiry = Runnable { Caffeine.check() }
    private var stopped = false

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
        val f = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        val flags = if (on) strip.params.flags or f else strip.params.flags and f.inv()
        if (flags != strip.params.flags) {
            strip.params.flags = flags
            strip.relayout()
            Log.i(tag, if (on) "keep awake on" else "keep awake off")
        }
        // While the strip is hidden (a full-screen app, BentoBar hidden from its tile) or has nothing
        // to draw, no visible window of ours carries the flag: a screen wake lock holds it instead,
        // with no window. Released when the strip is back, keep awake ends, the screen goes off (the
        // power key or the lid) or the lock screen is up.
        val lock = on && (!strip.shown || stripEmpty()) && pm.isInteractive && !km.isKeyguardLocked
        if (lock && !screenLock.isHeld) { screenLock.acquire(); Log.i(tag, "keep awake: wake lock while the bar is hidden") }
        else if (!lock && screenLock.isHeld) screenLock.release()
    }

    /** The service is going: nothing it held may stay held, and from here on nothing is asked for. */
    fun stop() {
        stopped = true
        if (screenLock.isHeld) screenLock.release()
    }
}
