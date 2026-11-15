package com.meridian11.books.bentobar.items

import android.hardware.input.InputManager
import android.hardware.lights.Light
import android.hardware.lights.LightState
import android.hardware.lights.LightsManager
import android.hardware.lights.LightsRequest

/** The keyboard's backlight, as the Keyboard light item uses it ([KeyLight]). [KeyLightsOnDevice] is Android's; a test gives a fake. */
interface KeyLights {
    /** A keyboard whose light an app may set is connected. */
    fun has(): Boolean
    /** Sets the light to [alpha] (0 off to 255 full), holding it from the first call on; false: refused. */
    fun set(alpha: Int): Boolean
    /** Gives the light back to the system. */
    fun release()
}

/**
 * Holding a light, without Android (`KeyLightRulesTest`): the session is opened at the first [set] and kept for the
 * ones after it, until [release]. A set that fails lets the session go, so the next one opens a new one (a keyboard
 * unplugged and plugged in again). Never throws.
 */
class HeldLight<S : Any>(
    private val find: () -> Boolean,
    private val open: () -> S?,
    private val apply: (S, Int) -> Unit,
    private val close: (S) -> Unit,
) : KeyLights {
    private var session: S? = null

    override fun has(): Boolean = try { find() } catch (e: Exception) { false }

    override fun set(alpha: Int): Boolean = try {
        val s = session ?: open()?.also { session = it }
        if (s == null) false else { apply(s, alpha.coerceIn(0, 255)); true }
    } catch (e: Exception) {
        release()
        false
    }

    override fun release() {
        val s = session ?: return
        session = null
        try { close(s) } catch (e: Exception) { /* the light is the system's again either way once the session is gone */ }
    }
}

/**
 * On Android: the first input device whose lights include a keyboard backlight. A session of its lights manager holds the
 * light while it is open (Android gives it back when the session closes, or when the process dies); a single-colour
 * light takes its brightness from the colour's alpha, as the system's own keyboard backlight does.
 */
internal object KeyLightsOnDevice : KeyLights by HeldLight(
    find = { backlight() != null },
    open = { backlight()?.let { (lights, light) -> Held(lights.openSession(), light) } },
    apply = { held, alpha -> held.session.requestLights(LightsRequest.Builder().addLight(held.light, LightState.Builder().setColor(alpha shl 24).build()).build()) },
    close = { it.session.close() },
)

private class Held(val session: LightsManager.LightsSession, val light: Light)

private fun backlight(): Pair<LightsManager, Light>? {
    val im = Env.app.getSystemService(InputManager::class.java) ?: return null
    for (id in im.inputDeviceIds) {
        val lights = im.getInputDevice(id)?.lightsManager ?: continue
        val light = lights.lights.firstOrNull { it.type == Light.LIGHT_TYPE_KEYBOARD_BACKLIGHT } ?: continue
        return lights to light
    }
    return null
}
