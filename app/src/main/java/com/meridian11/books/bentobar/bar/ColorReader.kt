package com.meridian11.books.bentobar.bar

import android.app.WallpaperManager
import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.meridian11.books.bentobar.data.ColorMode
import com.meridian11.books.bentobar.data.Pill
import com.meridian11.books.bentobar.data.Store
import com.meridian11.books.bentobar.data.TextSize
import com.meridian11.books.bentobar.data.spacingDp

/**
 * The strip's colours: when the status bar is read ([ColorWatch] says), what a reading makes of the strip's look,
 * and the theme and wallpaper hints when there is no reading. The screenshot itself is asked for through [shoot], at
 * the accessibility edge ([StatusBarScan.shoot]): this class names none of that API.
 */
internal class ColorReader(
    private val context: Context,
    private val main: Handler,
    private val facts: Facts,
    private val shoot: (BarSnapshot, (BarColors?, Int?) -> Unit) -> Unit,
) {
    /** What the reader asks of the strip's controller, each time it needs it. */
    interface Facts {
        val started: Boolean
        val onScreen: Boolean
        val empty: Boolean
        val interactive: Boolean
        val snapshot: BarSnapshot?
    }

    private val tag = BarService.TAG
    private val watch = ColorWatch()
    private var sampled: BarColors? = null
    private var lastColor: ColorMode? = null
    private val sample = Runnable { sampleColor() }

    /** Read by the strip's composition. */
    val look = mutableStateOf(StripLook(Color.White, true, TextSize.DEFAULT, 10.dp, Pill.NONE))

    // A live wallpaper can report new colours every second: a quick cause, two readings.
    private val wallpaper = WallpaperManager.OnColorsChangedListener { _, _ -> readSoon(ColorWatch.Cause.QUICK, 300) }

    fun start() { runCatching { WallpaperManager.getInstance(context).addOnColorsChangedListener(wallpaper, main) } }

    fun stop() { runCatching { WallpaperManager.getInstance(context).removeOnColorsChangedListener(wallpaper) } }

    /** The layout changed: colour back to "Match the status bar" means reading the bar again (nothing else would until it changes). */
    fun onConfig() {
        Store.config.value.color.let { if (it != lastColor) { if (it == ColorMode.AUTO && lastColor != null) readSoon(ColorWatch.Cause.QUICK, 100); lastColor = it } }
    }

    /**
     * Asks for colour readings after something that can change the bar's look. A reading is a
     * screenshot of SystemUI's status bar WINDOW only (its own surface, no app content), read at the
     * clock ([BarPixels]). On some devices that surface is glyphs on transparent; on others it's opaque
     * while an app is maximized (white glyphs on black) and see-through over the wallpaper otherwise.
     *
     * The bar gives no sign when it changes, and it changes late, so one cause is followed by a few
     * readings spread over the time the bar may take ([ColorWatch]): two within a second and a half
     * after a change of windows or of the wallpaper, six over ten seconds when the strip comes back on
     * screen or the theme or the display changed. Causes that keep coming are held to a budget there.
     *
     * Readings are taken only while the strip is on screen; a cause while it's hidden waits until the
     * strip shows ([BarController] asks then). The causes are the moments the bar can change: a new status bar
     * window, the strip coming back on screen, a window settling against the bar's lower edge or under
     * it, or leaving ([BarController]'s look at the windows), a theme, display or wallpaper change, and switching back to "Match the
     * status bar". Never on a timer: an accessibility service taking screenshots every 30 s looks like
     * screen capture to Android's threat detection.
     */
    fun readSoon(cause: ColorWatch.Cause, firstMs: Long) {
        if (!facts.started || !facts.onScreen) return
        // Not while the strip has nothing to draw: the reading waits until it has ([ColorWatch.pause]).
        if (facts.empty) return watch.owe(cause)
        watch.cause(cause, SystemClock.uptimeMillis(), firstMs)
        arm()
    }

    /** A window settled against the bar or under it, or left. */
    fun windowsMoved() { watch.windowsMoved(SystemClock.uptimeMillis()); arm() }

    /** The strip left the screen: what was still to be read no longer matters, and its return asks again. */
    fun stripLeft() { watch.clear(); main.removeCallbacks(sample) }

    /** Nothing to draw: no readings; something again: the readings owed meanwhile, or two quick ones. */
    fun contentChanged(empty: Boolean) {
        if (!facts.started || !facts.onScreen) return
        if (empty) watch.pause() else watch.resume(SystemClock.uptimeMillis(), 250)
        arm()
    }

    /** Times the next reading [watch] has to come, if any. */
    private fun arm() {
        main.removeCallbacks(sample)
        watch.due(SystemClock.uptimeMillis())?.let { main.postDelayed(sample, it) }
    }

    /** A reading out of turn, for the test hook. */
    fun sampleNow() = sampleColor()

    private fun sampleColor() {
        if (!facts.started || !facts.onScreen || !facts.interactive) return watch.clear()
        if (facts.empty) return watch.pause()
        val s = facts.snapshot
        if (s == null || Store.config.value.color != ColorMode.AUTO) { watch.clear(); return applyLook() }
        watch.taken(SystemClock.uptimeMillis())
        shoot(s) { colors, failed ->
            // A result can arrive after stop(): it mustn't paint a strip that's gone.
            if (failed == null) main.post { if (facts.started) onSample(colors) }
            else main.post { if (facts.started) { Log.i(tag, "status bar colour sample failed ($failed)"); onSample(null) } }
        }
    }

    /**
     * A reading, or null for none (the screenshot failed, or the bar was caught fading and had nothing
     * opaque to read). No reading keeps the colours the strip has; after three in a row the strip uses
     * the theme and wallpaper hints rather than a reading of how the bar looked before. [watch] says
     * whether another reading follows.
     */
    private fun onSample(c: BarColors?) {
        val result = ColorWatch.result(sampled, c)
        if (result == ColorWatch.Result.CHANGED) { sampled = c; applyLook() }
        // The strip left the screen while the screenshot was on its way: its return asks again.
        if (!facts.onScreen) return
        if (watch.read(SystemClock.uptimeMillis(), result)) {
            Log.i(tag, "no status bar colour reading, using theme and wallpaper hints"); sampled = null; applyLook()
        }
        arm()
        // Every reading is logged: a strip in the wrong colour can then be traced in a device's log.
        Log.i(tag, "read ${c?.let { "text=${it.text?.let(::hex)} bg=${it.background?.let(::hex)}" } ?: "nothing"} (${result.name.lowercase()}), " +
            (watch.due(SystemClock.uptimeMillis())?.let { "next in $it ms" } ?: "no more"))
    }

    /** No sample: a dark system theme means a dark bar on Googlebooks; otherwise the wallpaper decides. */
    private fun fallbackText(): Color {
        val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        if (night) return Color.White
        val hints = runCatching { WallpaperManager.getInstance(context).getWallpaperColors(WallpaperManager.FLAG_SYSTEM)?.colorHints }.getOrNull() ?: 0
        return if (hints and android.app.WallpaperColors.HINT_SUPPORTS_DARK_TEXT != 0) Contrast.DARK_TEXT else Color.White
    }

    fun applyLook() {
        val cfg = Store.config.value
        val bg = if (cfg.color == ColorMode.AUTO) sampled?.background else null
        val text = when (cfg.color) {
            ColorMode.LIGHT -> Color.White
            ColorMode.DARK -> Contrast.DARK_TEXT
            ColorMode.AUTO -> sampled?.text ?: bg?.let { Contrast.readableOn(it) } ?: fallbackText()
        }
        val live = Contrast.resolve(text, bg)
        BarLook.current.value = live
        look.value = StripLook(live.fg, live.barDark, cfg.textSize, cfg.spacingDp.dp, cfg.pill, live.background)
        Log.i(tag, "look fg=${hex(live.fg)} bg=${bg?.let { hex(it) } ?: "transparent"} dark=${live.barDark} " +
            "contrast=${"%.1f".format(java.util.Locale.ROOT, Contrast.ratio(live.fg, live.background))}")
    }

    private fun hex(c: Color) = "#%06X".format(java.util.Locale.ROOT, c.toArgb() and 0xFFFFFF)
}
