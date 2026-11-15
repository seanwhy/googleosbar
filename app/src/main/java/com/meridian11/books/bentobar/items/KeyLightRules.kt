package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.util.Sym
import kotlin.math.roundToInt

/**
 * The keyboard's light without Android (`KeyLightRulesTest`). Android does not tell an app the level the system set, so
 * the level is BentoBar's own: none until the first slide (the slider dimmed at 0), then what was set, until it is given
 * back. The light's brightness is a colour's alpha, 0 to 255.
 */
object KeyLightRules {
    fun alpha(level: Float): Int = if (level.isNaN()) 0 else (level.coerceIn(0f, 1f) * 255).roundToInt()

    /** A notch of the wheel is a tenth: a keyboard's light has few steps. */
    fun wheel(level: Float, steps: Int): Float = (level + 0.1f * steps).coerceIn(0f, 1f)

    fun icon(level: Float?): String = if (level == null || level <= 0f) Sym.BACKLIGHT_LOW else Sym.BACKLIGHT_HIGH

    /** What the bar draws: the level held, or, while the system has the light, an empty slider, dimmed. */
    fun slider(level: Float?): BarSlider = BarSlider(level ?: 0f, dimmed = level == null)

    /** Whether the light goes back to the system at [why]: when the item leaves and when the service ends, never when the bar hides. */
    fun release(why: Settle): Boolean = when (why) {
        Settle.LEFT_LAYOUT, Settle.SERVICE_ENDING -> true
        Settle.SERVICE_STARTED -> false
    }

    /** What the menu says of the light. */
    enum class Says { NO_KEYBOARD, NOT_ON_THIS_DEVICE, SYSTEM, OURS }

    fun says(has: Boolean, refused: Boolean, level: Float?): Says = when {
        !has -> Says.NO_KEYBOARD
        refused -> Says.NOT_ON_THIS_DEVICE
        level == null -> Says.SYSTEM
        else -> Says.OURS
    }
}

/**
 * The light as the item holds it, over [KeyLights]: [level] is BentoBar's (null while the system has it), [refused] that
 * Android would not take the last one. Main thread; never throws.
 */
class KeyLight(private val lights: KeyLights) {
    var level: Float? = null; private set
    var refused = false; private set

    fun has(): Boolean = try { lights.has() } catch (e: Exception) { false }

    /** Sets the light to [to]; false (and [refused]) when Android would not. */
    fun slide(to: Float): Boolean {
        val l = if (to.isNaN()) 0f else to.coerceIn(0f, 1f)
        val ok = try { lights.set(KeyLightRules.alpha(l)) } catch (e: Exception) { false }
        refused = !ok
        level = if (ok) l else null
        return ok
    }

    /** "Give back to the system", or a [Settle] moment: the light is the system's again. Nothing held, nothing to give. */
    fun giveBack() {
        if (level == null) return
        level = null
        try { lights.release() } catch (e: Exception) { /* nothing more can be done for it */ }
    }

    fun settle(why: Settle) { if (KeyLightRules.release(why)) giveBack() }
}
