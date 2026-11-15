package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.items.KeyLightRules.Says
import com.meridian11.books.bentobar.util.Sym
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The keyboard's light without a device: the level as a light's brightness, the session held from the first slide until it
 * is given back, and the moments it goes back by itself (the item leaving, the service ending), never the bar hiding.
 */
class KeyLightRulesTest {
    /** A keyboard light as a test sees it. */
    private class FakeLights(var has: Boolean = true, var accepts: Boolean = true, var throws: Boolean = false) : KeyLights {
        val set = ArrayList<Int>()
        var releases = 0
        override fun has() = has
        override fun set(alpha: Int): Boolean { if (throws) error("refused"); set += alpha; return accepts }
        override fun release() { releases++ }
    }

    private lateinit var before: KeyLight
    @Before fun keep() { before = KeyLightItem.light }
    @After fun back() { KeyLightItem.light = before }

    @Test fun alphaIsTheLevel() {
        assertEquals(0, KeyLightRules.alpha(0f))
        assertEquals(128, KeyLightRules.alpha(0.5f))
        assertEquals(255, KeyLightRules.alpha(1f))
        assertEquals(0, KeyLightRules.alpha(-1f))
        assertEquals(255, KeyLightRules.alpha(3f))
        assertEquals(0, KeyLightRules.alpha(Float.NaN))
    }

    @Test fun aNotchIsTenPercentAndStopsAtTheEnds() {
        assertEquals(0.6f, KeyLightRules.wheel(0.5f, 1), 1e-6f)
        assertEquals(0.3f, KeyLightRules.wheel(0.5f, -2), 1e-6f)
        assertEquals(1f, KeyLightRules.wheel(0.95f, 1), 0f)
        assertEquals(0f, KeyLightRules.wheel(0.05f, -1), 0f)
    }

    @Test fun theIconIsLowUntilThereIsLight() {
        assertEquals(Sym.BACKLIGHT_LOW, KeyLightRules.icon(null))
        assertEquals(Sym.BACKLIGHT_LOW, KeyLightRules.icon(0f))
        assertEquals(Sym.BACKLIGHT_HIGH, KeyLightRules.icon(0.1f))
    }

    @Test fun onlyTheItemLeavingAndTheServiceEndingGiveItBack() {
        assertTrue(KeyLightRules.release(Settle.LEFT_LAYOUT))
        assertTrue(KeyLightRules.release(Settle.SERVICE_ENDING))
        assertFalse(KeyLightRules.release(Settle.SERVICE_STARTED))
    }

    @Test fun aSlideOpensTheSessionOnce() {
        var opens = 0; var closes = 0
        val applied = ArrayList<Int>()
        val held = HeldLight(find = { true }, open = { opens++; "session $opens" }, apply = { _, alpha -> applied += alpha }, close = { closes++ })
        val light = KeyLight(held)
        assertTrue(light.slide(0.2f)); assertTrue(light.slide(0.5f)); assertTrue(light.slide(1f))
        assertEquals("one session for every slide", 1, opens)
        assertEquals(listOf(51, 128, 255), applied)
        assertEquals(1f, light.level)
        light.giveBack()
        assertEquals(1, closes)
        // The next slide holds it again, in a new session.
        assertTrue(light.slide(0.3f))
        assertEquals(2, opens)
    }

    @Test fun givingBackReleasesAndDimsTheSlider() {
        val lights = FakeLights()
        val light = KeyLight(lights)
        assertNull("before the first slide the level is the system's", light.level)
        assertEquals(BarSlider(0f, dimmed = true), KeyLightRules.slider(light.level))
        assertTrue(light.slide(0.5f))
        assertEquals(BarSlider(0.5f, dimmed = false), KeyLightRules.slider(light.level))
        light.giveBack()
        assertEquals(1, lights.releases)
        assertNull(light.level)
        assertEquals(BarSlider(0f, dimmed = true), KeyLightRules.slider(light.level))
        light.giveBack()
        assertEquals("nothing held, nothing given back", 1, lights.releases)
    }

    @Test fun theLightGoesBackWhenTheItemLeavesOrTheServiceEnds() {
        val lights = FakeLights()
        KeyLightItem.light = KeyLight(lights)
        val item = ItemConfig("k", KeyLightItem.type)
        KeyLightItem.onSlide(item, 0.5f, done = true)
        assertEquals(listOf(128), lights.set)
        KeyLightItem.onIdle()
        assertEquals("the bar hiding keeps it", 0, lights.releases)
        KeyLightItem.settle(Settle.SERVICE_STARTED)
        assertEquals("a start (a rebind) keeps it", 0, lights.releases)
        KeyLightItem.settle(Settle.LEFT_LAYOUT)
        assertEquals(1, lights.releases)
        KeyLightItem.settle(Settle.SERVICE_ENDING)
        assertEquals("given back once", 1, lights.releases)
        KeyLightItem.onScroll(item, 2)
        assertEquals("the wheel holds it again, from nothing", listOf(128, 51), lights.set)
        KeyLightItem.settle(Settle.SERVICE_ENDING)
        assertEquals(2, lights.releases)
    }

    @Test fun aRefusedSetNeverThrowsAndSaysNotOnThisDevice() {
        val lights = FakeLights(throws = true)
        val light = KeyLight(lights)
        assertFalse(light.slide(0.5f))
        assertNull(light.level)
        assertTrue(light.refused)
        assertEquals(Says.NOT_ON_THIS_DEVICE, KeyLightRules.says(has = true, refused = light.refused, level = light.level))
        // A light that answers no, the same.
        val no = KeyLight(FakeLights(accepts = false))
        assertFalse(no.slide(0.5f))
        assertTrue(no.refused)
        // And through the session holder: what Android throws lets the session go, and the next slide tries a new one.
        var opens = 0; var closes = 0
        val held = HeldLight(find = { true }, open = { opens++; Unit }, apply = { _, _ -> error("refused") }, close = { closes++ })
        assertFalse(held.set(10))
        assertEquals(1, closes)
        assertFalse(held.set(10))
        assertEquals(2, opens)
        // Nothing found, nothing opened.
        assertFalse(HeldLight(find = { error("gone") }, open = { null }, apply = { _: Unit, _ -> }, close = {}).has())
        assertFalse(HeldLight(find = { false }, open = { null }, apply = { _: Unit, _ -> }, close = {}).set(10))
        // Through the item's own hook too.
        KeyLightItem.light = KeyLight(FakeLights(throws = true))
        KeyLightItem.onSlide(ItemConfig("k", KeyLightItem.type), 0.5f, done = true)
        assertTrue(KeyLightItem.light.refused)
    }

    @Test fun whatTheMenuSays() {
        assertEquals(Says.NO_KEYBOARD, KeyLightRules.says(has = false, refused = false, level = null))
        assertEquals(Says.NO_KEYBOARD, KeyLightRules.says(has = false, refused = true, level = 0.5f))
        assertEquals(Says.SYSTEM, KeyLightRules.says(has = true, refused = false, level = null))
        assertEquals(Says.OURS, KeyLightRules.says(has = true, refused = false, level = 0.5f))
    }
}
