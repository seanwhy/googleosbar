package com.meridian11.books.bentobar.bar

import androidx.compose.ui.graphics.Color
import com.meridian11.books.bentobar.items.Stands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContrastTest {
    private val black = Color.Black
    private val white = Color.White
    private val grey = Color(0xFF474747) // what the old sampler produced on the Acer's black bar

    @Test fun ratiosMatchWcag() {
        assertEquals(21f, Contrast.ratio(white, black), 0.01f)
        assertEquals(2.25f, Contrast.ratio(grey, black), 0.05f)
    }

    @Test fun greyOnABlackBarBecomesWhite() {
        val look = Contrast.resolve(grey, black)
        assertEquals(white, look.fg)
        assertTrue(look.barDark)
        assertTrue(look.opaque)
    }

    @Test fun readableSampleIsKept() {
        val pale = Color(0xFFE8EAED)
        assertEquals(pale, Contrast.resolve(pale, black).fg)
    }

    @Test fun lightOpaqueBarGetsDarkText() {
        val look = Contrast.resolve(Color(0xFFBDBDBD), Color(0xFFF1F3F4))
        assertFalse(look.barDark)
        assertTrue(Contrast.ratio(look.fg, look.background) >= Contrast.MIN)
    }

    @Test fun transparentBarJudgedByTheText() {
        assertTrue(Contrast.resolve(white, null).barDark)
        assertFalse(Contrast.resolve(Contrast.DARK_TEXT, null).barDark)
    }

    @Test fun midToneBarStillReaches45() {
        val teal = Color(0xFF3F6F73)
        val look = Contrast.resolve(Color(0xFF6A8E91), teal)
        assertTrue(Contrast.ratio(look.fg, teal) >= Contrast.MIN)
    }

    @Test fun accentFallsBackWhenItWouldNotRead() {
        val teal = Color(0xFF3F6F73)
        val look = Contrast.resolve(white, teal)
        val strip = StripLook(look.fg, look.barDark, com.meridian11.books.bentobar.data.TextSize.DEFAULT,
            androidx.compose.ui.unit.Dp(12f), com.meridian11.books.bentobar.data.Pill.NONE, look.background)
        assertTrue(Contrast.ratio(strip.accent, teal) >= Contrast.MIN)
        assertTrue(Contrast.ratio(strip.warn, teal) >= Contrast.MIN)
    }

    @Test fun everyOpaqueBarReaches45() {
        // Every grey, and a coarse sweep of colours, with an unreadable sample (the bar's own colour):
        // the strip's text, accent and warning colours still reach 4.5:1. #808080 failed before.
        val bars = (0..255).map { Color(0xFF000000.toInt() or (it * 0x010101)) } +
            (0..255 step 51).flatMap { r -> (0..255 step 51).flatMap { g -> (0..255 step 51).map { b -> Color(r, g, b) } } }
        for (bg in bars) {
            val look = Contrast.resolve(bg, bg)
            assertTrue("fg on $bg", Contrast.ratio(look.fg, bg) >= Contrast.MIN)
            val strip = StripLook(look.fg, look.barDark, com.meridian11.books.bentobar.data.TextSize.DEFAULT,
                androidx.compose.ui.unit.Dp(12f), com.meridian11.books.bentobar.data.Pill.NONE, look.background)
            assertTrue("accent on $bg", Contrast.ratio(strip.accent, bg) >= Contrast.MIN)
            assertTrue("warn on $bg", Contrast.ratio(strip.warn, bg) >= Contrast.MIN)
            assertTrue("quotaTimeout on $bg", Contrast.ratio(strip.quotaTimeout, bg) >= Contrast.MIN)
        }
    }

    // ---- the route line: its plane and the part flown take the color of how the flight stands

    /** The strip as it is on a bar with this text color and, where the bar is opaque, this color of its own. */
    private fun strip(text: Color, bg: Color?): StripLook {
        val look = Contrast.resolve(text, bg)
        return StripLook(look.fg, look.barDark, com.meridian11.books.bentobar.data.TextSize.DEFAULT, androidx.compose.ui.unit.Dp(12f),
            com.meridian11.books.bentobar.data.Pill.NONE, look.background)
    }
    private val onDark = strip(white, null)
    private val onLight = strip(Contrast.DARK_TEXT, null)
    private fun colors(look: StripLook) = listOf(look.routeGood, look.routeLate, look.routeVeryLate)

    @Test fun theRouteLinesColorsAreTheDesignsOnDarkBarsAndOnLightOnes() {
        // Measured against black and white, the way the strip takes a see-through bar. A line is a graphic and needs 3:1.
        assertEquals(listOf(Color(0xFF6DD58C), Color(0xFFFFD27A), Color(0xFFFF897D)), colors(onDark))
        assertEquals(11.5f, Contrast.ratio(onDark.routeGood, black), 0.1f)
        assertEquals(14.7f, Contrast.ratio(onDark.routeLate, black), 0.1f)
        assertEquals(9.1f, Contrast.ratio(onDark.routeVeryLate, black), 0.1f)
        assertEquals(listOf(Color(0xFF146C2E), Color(0xFF8A5100), Color(0xFFB3261E)), colors(onLight))
        assertEquals(6.5f, Contrast.ratio(onLight.routeGood, white), 0.1f)
        assertEquals(6.4f, Contrast.ratio(onLight.routeLate, white), 0.1f)
        assertEquals(6.5f, Contrast.ratio(onLight.routeVeryLate, white), 0.1f)
        // The same on the black bar a maximized window gives, and on a light bar of an app's own.
        assertEquals(colors(onDark), colors(strip(white, black)))
        assertEquals(colors(onLight), colors(strip(Color(0xFFBDBDBD), Color(0xFFF1F3F4))))
        // Late is the warning color as it is, and very late on a light bar the alert pill's red.
        assertEquals(onDark.warn, onDark.routeLate)
        assertEquals(onLight.warn, onLight.routeLate)
        assertEquals(onLight.alertBg, onLight.routeVeryLate)
    }

    @Test fun howAFlightStandsIsTheColorOfItsLine() {
        for (look in listOf(onDark, onLight)) {
            // Nothing claimed: the text's own color.
            assertEquals(look.fg, look.route(Stands.NO_CLAIM))
            assertEquals(look.routeGood, look.route(Stands.GOOD))
            assertEquals(look.routeLate, look.route(Stands.LATE))
            assertEquals(look.routeVeryLate, look.route(Stands.VERY_LATE))
            // What will not arrive is as red as what is very late: that its line is one color from end to end tells them apart.
            assertEquals(look.routeVeryLate, look.route(Stands.WILL_NOT_ARRIVE))
        }
    }

    @Test fun inTheAlertPillTheLineIsThePillsInkWhateverTheFlightDoes() {
        for (look in listOf(onDark, onLight)) {
            for (stands in Stands.entries) assertEquals("$stands", look.alertFg, look.route(stands, alert = true))
            assertTrue(Contrast.ratio(look.alertFg, look.alertBg) >= Contrast.MIN)
            // The colors themselves would be lost on it: the pill is as red as the reds, and about as light as the others.
            for (color in colors(look)) assertTrue("$color", Contrast.ratio(color, look.alertBg) < 1.5f)
        }
    }

    @Test fun onABarOfAnotherColorALineColorUnderThreeToOneGivesWayToTheTextColor() {
        // A teal bar with white text: green (3.1:1) and yellow (3.9:1) still stand out from it; the red (2.4:1) does not.
        val teal = Color(0xFF3F6F73)
        val onTeal = strip(white, teal)
        assertEquals(white, onTeal.fg)
        assertEquals(listOf(Color(0xFF6DD58C), Color(0xFFFFD27A), white), colors(onTeal))
        // A mid gray, where the text is black: only the yellow (3.2:1) is left.
        val gray = Color(0xFF777777)
        val onGray = strip(gray, gray)
        assertEquals(black, onGray.fg)
        assertEquals(listOf(black, Color(0xFFFFD27A), black), colors(onGray))
        for ((look, bar) in listOf(onTeal to teal, onGray to gray)) for (color in colors(look)) assertTrue("$color on $bar", Contrast.ratio(color, bar) >= 3f)
        // The warning color is asked less of on a line than in words, where it needs 4.5:1 and is given up on both bars.
        assertEquals(white, onTeal.warn)
        assertEquals(black, onGray.warn)
    }

    @Test fun onEveryOpaqueBarTheRouteLineStandsOutAtThreeToOne() {
        // Every gray and a coarse sweep of colors, as for the text: whatever the flight does, the plane and the part flown
        // can be told from the bar, in their own color or else in the text's.
        val bars = (0..255).map { Color(0xFF000000.toInt() or (it * 0x010101)) } +
            (0..255 step 51).flatMap { r -> (0..255 step 51).flatMap { g -> (0..255 step 51).map { b -> Color(r, g, b) } } }
        for (bg in bars) {
            val look = strip(bg, bg)
            for (stands in Stands.entries) assertTrue("$stands on $bg", Contrast.ratio(look.route(stands), bg) >= 3f)
        }
    }
}
