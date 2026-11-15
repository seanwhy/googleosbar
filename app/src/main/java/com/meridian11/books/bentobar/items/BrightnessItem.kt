package com.meridian11.books.bentobar.items

import android.content.Intent
import android.provider.Settings
import android.util.Log
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.data.Display
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.ui.MenuCard
import com.meridian11.books.bentobar.ui.MenuDivider
import com.meridian11.books.bentobar.ui.MenuEntry
import com.meridian11.books.bentobar.ui.MenuNote
import com.meridian11.books.bentobar.ui.SwitchRow
import com.meridian11.books.bentobar.ui.rememberTick
import com.meridian11.books.bentobar.util.Fmt
import com.meridian11.books.bentobar.util.Sym

/**
 * Brightness: the built-in screen's brightness, with a slider in the bar (or the icon and the percent, the wheel still
 * setting it). It writes Android's own setting, which needs "Modify system settings"; a slide or a notch switches
 * automatic brightness off first, and the menu switches it back on. Without the access the slider is dimmed and a slide
 * does nothing; the menu says where to allow it. The curve is [BrightnessRules].
 */
object BrightnessItem : ItemType("brightness", R.string.item_brightness_title, Sym.BRIGHTNESS_MEDIUM, R.string.item_brightness_desc) {
    override val group = ItemGroup.SYSTEM
    override val needs = listOf(Access.WRITE_SETTINGS)

    internal val slider = Opt.bool("slider", true)

    /** What was last read from Android's settings, or set from here: the value (1 to 255; null: not read yet) and automatic. */
    private var value: Int? = null
    private var auto = false

    /** Whether the access is given, as last asked (on going live, opening the menu, every few seconds, a slide). */
    @Volatile private var grantedNow: Boolean? = null
    private var grantedAt: Long? = null

    /** Debug builds: a look staged from adb, in place of Android's setting and the access. Nothing real is written while it stands. */
    private data class Staged(val granted: Boolean, val level: Float, val auto: Boolean)
    @Volatile private var staging: Staged? = null

    private fun ask(): Boolean = Accesses.granted(Env.app, Access.WRITE_SETTINGS).also { grantedNow = it }
    internal fun granted(): Boolean = staging?.granted ?: grantedNow ?: ask()

    /** Android's settings, through their own in-process cache: cheap enough for the tick. */
    private fun read() {
        try {
            val cr = Env.app.contentResolver
            value = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, -1).takeIf { it >= 0 }
            auto = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) ==
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        } catch (e: Exception) {
            Log.w(Env.TAG, "can't read the brightness: ${e.javaClass.simpleName}")
        }
    }

    internal fun level(): Float {
        staging?.let { return it.level }
        // Asked in passing (a debug hook, the settings) before any tick read it.
        if (value == null) read()
        return BrightnessRules.level(value ?: BrightnessRules.MIN)
    }
    internal fun auto(): Boolean = staging?.auto ?: auto

    override fun onLive() { grantedNow = null; read() }

    override fun menuOpening(item: ItemConfig) { ask(); read() }

    override fun sample(now: Long) {
        read()
        val at = grantedAt
        if (at == null || now - at >= 5_000L || now < at) { grantedAt = now; ask() }
    }

    override fun state(item: ItemConfig): ItemState {
        val level = level()
        val auto = auto()
        val pct = BrightnessRules.percent(level)
        val on = slider.of(item)
        val bar = if (on) BarSlider(level, dimmed = !granted()) else null
        return ItemState(icon = BrightnessRules.icon(level, auto), text = Fmt.percent(pct), widthKey = "pct",
            desc = when {
                !granted() -> Env.str(R.string.brightness_noaccess_desc, Fmt.percent(pct))
                auto -> Env.str(R.string.brightness_auto_desc, Fmt.percent(pct))
                else -> Env.str(R.string.brightness_desc, Fmt.percent(pct))
            },
            slider = bar,
            // The number the slider stands for; shown as an icon only, the item has no slider and keeps its name as the tooltip.
            tooltip = if (bar == null || item.display == Display.ICON) null else Env.str(R.string.brightness_desc, Fmt.percent(pct)))
    }

    override val usesWheel = true
    override fun onScroll(item: ItemConfig, steps: Int) = set(BrightnessRules.wheel(level(), steps))

    override fun onSlide(item: ItemConfig, level: Float, done: Boolean) = set(level)

    /**
     * Writes [level] as Android's brightness, automatic switched off first (else Android would take it back at once). Only
     * a value that differs is written: a drag sends many. From the strip's pointer handling, where nothing catches.
     */
    private fun set(level: Float) {
        val s = staging
        if (s != null) { staging = s.copy(level = level.coerceIn(0f, 1f), auto = false); return }
        try {
            // Without the access a slide does nothing; Android is asked once more, as it may have just been given.
            if (grantedNow != true && !ask()) return
            val cr = Env.app.contentResolver
            val v = BrightnessRules.value(level)
            if (Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) !=
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            if (v != value) Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, v)
            value = v
            auto = false
        } catch (e: Exception) {
            Log.w(Env.TAG, "can't set the brightness: ${e.javaClass.simpleName}")
        }
    }

    private fun setAuto(on: Boolean) {
        val s = staging
        if (s != null) { staging = s.copy(auto = on); return }
        try {
            Settings.System.putInt(Env.app.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                if (on) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            auto = on
        } catch (e: Exception) {
            Log.w(Env.TAG, "can't switch automatic brightness: ${e.javaClass.simpleName}")
        }
        Ticker.refresh(type)
    }

    override val menu: @Composable (ItemConfig, MenuHost) -> Unit = { _, host ->
        rememberTick()
        val granted = granted()
        val level = level()
        val auto = auto()
        val pct = Fmt.percent(BrightnessRules.percent(level))
        MenuCard(BrightnessRules.icon(level, auto), stringResource(R.string.item_brightness_title),
            if (auto) stringResource(R.string.brightness_auto_subtitle, pct) else pct) {
            if (!granted) {
                MenuNote(stringResource(Accesses.explain(Access.WRITE_SETTINGS)))
                MenuEntry(Sym.TOGGLE_ON, stringResource(Accesses.allow(Access.WRITE_SETTINGS))) {
                    host.close()
                    Accesses.open(Env.app, Access.WRITE_SETTINGS)
                }
            }
            // Follows the system's own slider while the menu is open, except mid-drag.
            var dragging by remember { mutableStateOf(false) }
            var v by remember { mutableFloatStateOf(level) }
            LaunchedEffect(level, dragging) { if (!dragging) v = level }
            val label = stringResource(R.string.item_brightness_title)
            Slider(value = v, onValueChange = { dragging = true; v = it; set(it) }, onValueChangeFinished = { dragging = false; Ticker.refresh(type) },
                enabled = granted, modifier = Modifier.fillMaxWidth().semantics { contentDescription = label })
            MenuEntry(Sym.BRIGHTNESS_AUTO, stringResource(R.string.brightness_auto), enabled = granted, checked = auto) { setAuto(!auto) }
            MenuNote(stringResource(R.string.brightness_note))
            MenuDivider()
            MenuEntry(Sym.SETTINGS, stringResource(R.string.brightness_settings)) { host.close(); Env.launch(Intent(Settings.ACTION_DISPLAY_SETTINGS)) }
        }
    }

    override val options: @Composable (ItemConfig, (ItemConfig) -> Unit) -> Unit = { item, set ->
        SwitchRow(stringResource(R.string.brightness_slider), slider.of(item), help = stringResource(R.string.brightness_slider_help)) {
            set(slider.set(item, it))
        }
    }

    // ---- for tests on a device (debug builds)

    override val stages = listOf("half", "auto", "noaccess").map { "stage $it" }

    /**
     * `brightness`: what it knows. `brightness stage half|auto|noaccess`: that look in the bar and the menu, with nothing
     * written to Android (a slide moves the staged level); `brightness off` or `brightness stage off` ends it.
     */
    override fun debug(args: List<String>): String? = when {
        args.isEmpty() -> said()
        args == listOf("off") || args == listOf("stage", "off") -> { staging = null; Ticker.refresh(type); said() }
        args.size != 2 || args[0] != "stage" -> null
        else -> {
            staging = when (args[1]) {
                "half" -> Staged(granted = true, level = 0.5f, auto = false)
                "auto" -> Staged(granted = true, level = 0.5f, auto = true)
                "noaccess" -> Staged(granted = false, level = 0.5f, auto = false)
                else -> return null
            }
            Ticker.refresh(type)
            said()
        }
    }

    private fun said() = "brightness: value=$value level=${BrightnessRules.percent(level())}% auto=${auto()} granted=${granted()} staged=${staging != null}"
}
