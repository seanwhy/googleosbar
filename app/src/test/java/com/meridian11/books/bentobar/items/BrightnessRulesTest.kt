package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.util.Sym
import org.junit.Assert.assertEquals
import org.junit.Test

/** Brightness without a device: the slider's curve over Android's 1 to 255, the wheel, and the icon. */
class BrightnessRulesTest {
    @Test fun theEndsAreTheEnds() {
        assertEquals(1, BrightnessRules.value(0f))
        assertEquals(255, BrightnessRules.value(1f))
        assertEquals(0f, BrightnessRules.level(1), 0f)
        assertEquals(1f, BrightnessRules.level(255), 0f)
        // What Android should never say is still drawn within the track, and what a slide says still a value Android takes.
        assertEquals(0f, BrightnessRules.level(0), 0f)
        assertEquals(1f, BrightnessRules.level(4000), 0f)
        assertEquals(1, BrightnessRules.value(-0.5f))
        assertEquals(255, BrightnessRules.value(2f))
        assertEquals(1, BrightnessRules.value(Float.NaN))
    }

    @Test fun halfWayIsAQuarterOfTheLight() {
        // The squared curve: the slider's middle is a quarter of the range, so the dim half of it is not crowded at the start.
        assertEquals(65, BrightnessRules.value(0.5f))
        assertEquals(0.5f, BrightnessRules.level(65), 0.01f)
    }

    @Test fun thereAndBackIsTheSameValue() {
        for (v in BrightnessRules.MIN..BrightnessRules.MAX) assertEquals("value $v", v, BrightnessRules.value(BrightnessRules.level(v)))
    }

    @Test fun aNotchIsFivePercentAndStopsAtTheEnds() {
        assertEquals(0.55f, BrightnessRules.wheel(0.5f, 1), 1e-6f)
        assertEquals(0.4f, BrightnessRules.wheel(0.5f, -2), 1e-6f)
        assertEquals(1f, BrightnessRules.wheel(0.98f, 1), 0f)
        assertEquals(0f, BrightnessRules.wheel(0.02f, -1), 0f)
        assertEquals(1f, BrightnessRules.wheel(0.5f, 40), 0f)
    }

    @Test fun theIconSaysAutomaticOrHowBright() {
        assertEquals(Sym.BRIGHTNESS_AUTO, BrightnessRules.icon(0.9f, auto = true))
        assertEquals(Sym.BRIGHTNESS_LOW, BrightnessRules.icon(0f, auto = false))
        assertEquals(Sym.BRIGHTNESS_LOW, BrightnessRules.icon(0.33f, auto = false))
        assertEquals(Sym.BRIGHTNESS_MEDIUM, BrightnessRules.icon(0.34f, auto = false))
        assertEquals(Sym.BRIGHTNESS_MEDIUM, BrightnessRules.icon(0.66f, auto = false))
        assertEquals(Sym.BRIGHTNESS_HIGH, BrightnessRules.icon(0.67f, auto = false))
    }

    @Test fun thePercentIsTheSlidersPlace() {
        assertEquals(0, BrightnessRules.percent(0f))
        assertEquals(50, BrightnessRules.percent(0.5f))
        assertEquals(100, BrightnessRules.percent(1f))
    }
}
