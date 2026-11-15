package com.meridian11.books.bentobar.items

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
import com.meridian11.books.bentobar.items.KeyLightRules.Says
import com.meridian11.books.bentobar.ui.MenuCard
import com.meridian11.books.bentobar.ui.MenuDivider
import com.meridian11.books.bentobar.ui.MenuEntry
import com.meridian11.books.bentobar.ui.MenuNote
import com.meridian11.books.bentobar.ui.rememberTick
import com.meridian11.books.bentobar.util.Fmt
import com.meridian11.books.bentobar.util.Sym
import kotlin.math.roundToInt

/**
 * Keyboard light: the keyboard's backlight from a slider in the bar. Android doesn't tell an app the level the system
 * set, so until the first slide the slider is dimmed at nothing, and the light is the system's. From the first slide
 * BentoBar holds it, also while the bar is hidden, until "Give back to the system", the item leaving the layout or the
 * service ending ([settle]); never on [onIdle]. The level is in memory only. Its rules are [KeyLightRules].
 */
object KeyLightItem : ItemType("keylight", R.string.item_keylight_title, Sym.BACKLIGHT_HIGH, R.string.item_keylight_desc) {
    override val group = ItemGroup.SYSTEM

    /** What BentoBar holds of the light. A test gives it a fake. */
    internal var light = KeyLight(KeyLightsOnDevice)

    /** Whether a keyboard with such a light is there, as last asked (on going live, opening the menu, every few seconds). */
    @Volatile private var hasNow: Boolean? = null
    private var hasAt: Long? = null

    /** Debug builds: a look staged from adb, in place of the keyboard. Nothing real is set while it stands. */
    private data class Staged(val has: Boolean, val level: Float?)
    @Volatile private var staging: Staged? = null

    internal fun has(): Boolean = staging?.has ?: hasNow ?: light.has().also { hasNow = it }
    internal fun level(): Float? = staging.let { if (it != null) it.level else light.level }
    private fun says(): Says = KeyLightRules.says(has(), staging == null && light.refused, level())

    override fun onLive() { hasNow = null }

    override fun menuOpening(item: ItemConfig) { hasNow = light.has() }

    override fun sample(now: Long) {
        val at = hasAt
        if (at == null || now - at >= 5_000L || now < at) { hasAt = now; hasNow = light.has() }
    }

    override fun settle(why: Settle) = light.settle(why)

    private fun percent(level: Float) = Fmt.percent((level * 100).roundToInt())

    override fun state(item: ItemConfig): ItemState {
        val level = level()
        val words = level?.let(::percent)
        val desc = when (says()) {
            // No keyboard with such a light: nothing to slide.
            Says.NO_KEYBOARD -> return ItemState(icon = Sym.BACKLIGHT_LOW, filled = false, desc = Env.str(R.string.keylight_none_desc))
            // Refused: the slider stays, empty, so a later slide can try again; the menu says why.
            Says.NOT_ON_THIS_DEVICE -> Env.str(R.string.keylight_refused_desc)
            Says.SYSTEM -> Env.str(R.string.keylight_system_desc)
            Says.OURS -> Env.str(R.string.keylight_desc, words.orEmpty())
        }
        return ItemState(icon = KeyLightRules.icon(level), filled = level != null, text = words ?: Env.str(R.string.keylight_system), desc = desc,
            slider = KeyLightRules.slider(level), tooltip = if (item.display == Display.ICON) null else desc)
    }

    override val usesWheel = true
    override fun onScroll(item: ItemConfig, steps: Int) = slide(KeyLightRules.wheel(level() ?: 0f, steps))

    override fun onSlide(item: ItemConfig, level: Float, done: Boolean) = slide(level)

    /** From the strip's pointer handling and the menu, where nothing catches: [KeyLight] never throws. */
    private fun slide(to: Float) {
        val s = staging
        if (s != null) staging = s.copy(level = to.coerceIn(0f, 1f)) else light.slide(to)
    }

    private fun giveBack() {
        val s = staging
        if (s != null) staging = s.copy(level = null) else light.giveBack()
        Ticker.refresh(type)
    }

    override val menu: @Composable (ItemConfig, MenuHost) -> Unit = { _, _ ->
        rememberTick()
        val level = level()
        val says = says()
        val title = stringResource(R.string.item_keylight_title)
        MenuCard(KeyLightRules.icon(level), title, when (says) {
            Says.NO_KEYBOARD -> stringResource(R.string.keylight_none)
            Says.NOT_ON_THIS_DEVICE -> stringResource(R.string.keylight_not_here)
            Says.SYSTEM -> stringResource(R.string.keylight_system_subtitle)
            Says.OURS -> percent(level ?: 0f)
        }) {
            if (says != Says.NO_KEYBOARD) {
                // Follows the wheel and the bar's slider while the menu is open, except mid-drag.
                var dragging by remember { mutableStateOf(false) }
                var v by remember { mutableFloatStateOf(level ?: 0f) }
                LaunchedEffect(level, dragging) { if (!dragging) v = level ?: 0f }
                Slider(value = v, onValueChange = { dragging = true; v = it; slide(it) }, onValueChangeFinished = { dragging = false; Ticker.refresh(type) },
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = title })
            }
            MenuNote(stringResource(when (says) {
                Says.NO_KEYBOARD -> R.string.keylight_none_note
                Says.NOT_ON_THIS_DEVICE -> R.string.keylight_refused_note
                Says.SYSTEM -> R.string.keylight_system_note
                Says.OURS -> R.string.keylight_ours_note
            }))
            MenuDivider()
            MenuEntry(Sym.RESTART_ALT, stringResource(R.string.keylight_give_back), enabled = level != null) { giveBack() }
        }
    }

    // ---- for tests on a device (debug builds)

    override val stages = listOf("half", "unset", "none").map { "stage $it" }

    /**
     * `keylight`: what it knows. `keylight stage half|unset|none`: that look in the bar and the menu, with nothing set on
     * the keyboard (a slide moves the staged level); `keylight off` or `keylight stage off` ends it.
     */
    override fun debug(args: List<String>): String? = when {
        args.isEmpty() -> said()
        args == listOf("off") || args == listOf("stage", "off") -> { staging = null; Ticker.refresh(type); said() }
        args.size != 2 || args[0] != "stage" -> null
        else -> {
            staging = when (args[1]) {
                "half" -> Staged(has = true, level = 0.5f)
                "unset" -> Staged(has = true, level = null)
                "none" -> Staged(has = false, level = null)
                else -> return null
            }
            Ticker.refresh(type)
            said()
        }
    }

    private fun said() = "keylight: has=${has()} level=${level()?.let { KeyLightRules.alpha(it) }} refused=${light.refused} staged=${staging != null}"
}
