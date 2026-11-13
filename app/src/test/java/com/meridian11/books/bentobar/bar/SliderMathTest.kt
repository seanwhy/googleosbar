package com.meridian11.books.bentobar.bar

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.dp
import com.meridian11.books.bentobar.data.Pill
import com.meridian11.books.bentobar.data.TextSize
import com.meridian11.books.bentobar.items.Stands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The slider in the bar: which level a pointer stands for, and colors that can be told from the bar.
 * And the route line, which is the slider's line with a plane on it: where the plane and the two parts
 * of the line stand.
 */
class SliderMathTest {
    @Test fun aStopThatComesInAHairUnderItsNumberIsThatNumber() {
        assertEquals(7, SliderMath.stop(6.9999995f))
        assertEquals(10, SliderMath.stop(10.00001f))
        assertEquals(4, SliderMath.stop(4f))
        for (n in 4..64) assertEquals(n, SliderMath.stop(n - 0.00001f))
    }

    @Test fun everyWholeNumberInARangeIsAStopHoweverLongTheRange() {
        // Spacer's 4..64 used to be capped at 40 steps, which snapped 1.46 apart: 7, 10, 13 and so on could not be set.
        assertEquals(59, SliderMath.stops(4..64))
        assertEquals(23, SliderMath.stops(0..24))
        assertEquals(31, SliderMath.stops(8..40))
        assertEquals(0, SliderMath.stops(4..4))
        assertEquals(0, SliderMath.stops(4..5))
    }

    // A track 64 px wide that starts 10 px in.
    private fun at(x: Float, steps: Int = 0, rtl: Boolean = false) = SliderMath.level(x, left = 10f, width = 64f, steps = steps, rtl = rtl)

    @Test fun theLevelIsHowFarAlongTheTrackThePointerIs() {
        assertEquals(0f, at(10f), 0f)
        assertEquals(0.5f, at(42f), 0f)
        assertEquals(1f, at(74f), 0f)
        assertEquals(0.25f, at(26f), 0f)
    }

    @Test fun aPointerPastEitherEndStaysAtThatEnd() {
        // A drag that leaves the track (or the bar) keeps setting the nearest end.
        assertEquals(0f, at(-500f), 0f)
        assertEquals(0f, at(9f), 0f)
        assertEquals(1f, at(75f), 0f)
        assertEquals(1f, at(5_000f), 0f)
    }

    @Test fun withStepsTheLevelIsTheNearestStep() {
        // Media volume has 15 steps: a level is k / 15.
        assertEquals(0f, at(10f, steps = 15), 0f)
        assertEquals(8 / 15f, at(42f, steps = 15), 1e-6f)
        assertEquals(1f, at(74f, steps = 15), 0f)
        assertEquals(1 / 15f, at(10f + 64f / 15f, steps = 15), 1e-6f)
        // Just under halfway between two steps goes down, just over goes up.
        assertEquals(0f, at(10f + 64f / 30f - 0.1f, steps = 15), 0f)
        assertEquals(1 / 15f, at(10f + 64f / 30f + 0.1f, steps = 15), 1e-6f)
    }

    @Test fun aDragAcrossTheTrackVisitsEveryStepOnce() {
        val seen = (0..640).map { at(10f + it / 10f, steps = 15) }.distinct()
        assertEquals(16, seen.size) // 0 and the 15 steps
        assertEquals(seen.sorted(), seen)
    }

    @Test fun rightToLeftCountsFromTheOtherEnd() {
        assertEquals(1f, at(10f, rtl = true), 0f)
        assertEquals(0f, at(74f, rtl = true), 0f)
        assertEquals(0.75f, at(26f, rtl = true), 0f)
        assertEquals(14 / 15f, at(10f + 64f / 15f, steps = 15, rtl = true), 1e-6f)
    }

    @Test fun aTrackWithoutWidthIsEmpty() {
        assertEquals(0f, SliderMath.level(5f, 0f, 0f, 15), 0f)
        assertEquals(0f, SliderMath.level(5f, 0f, -3f, 0), 0f)
    }

    @Test fun snapAndStepAgree() {
        assertEquals(0.6f, SliderMath.snap(0.62f, 5), 1e-6f)
        assertEquals(3, SliderMath.step(0.62f, 5))
        assertEquals(15, SliderMath.step(1f, 15))
        assertEquals(0, SliderMath.step(0f, 15))
        assertEquals(9, SliderMath.step(0.62f, 15)) // 62% of 15 is 9.3
        assertEquals(0.62f, SliderMath.snap(0.62f, 0), 0f) // no steps: any level
        assertEquals(0, SliderMath.step(0.62f, 0))
        // Out of range, or not a number: within the track all the same.
        assertEquals(1f, SliderMath.snap(7f, 15), 0f)
        assertEquals(0f, SliderMath.snap(-1f, 15), 0f)
        assertEquals(0f, SliderMath.snap(Float.NaN, 15), 0f)
    }

    @Test fun aClickNeverSetsNothingButADragCan() {
        // The level under a click is one step at the least; what a drag reports is the level as it is.
        assertEquals(1 / 15f, SliderMath.atLeastOneStep(at(10f, steps = 15), 15), 1e-6f)
        assertEquals(1 / 15f, SliderMath.atLeastOneStep(0f, 15), 1e-6f)
        assertEquals(8 / 15f, SliderMath.atLeastOneStep(at(42f, steps = 15), 15), 1e-6f)
        assertEquals(1f, SliderMath.atLeastOneStep(1f, 15), 0f)
        assertEquals(0f, at(10f, steps = 15), 0f)
        // Without steps the least is one dp of the 64 dp track.
        assertEquals(1 / 64f, SliderMath.atLeastOneStep(0f, 0), 1e-6f)
        assertEquals(0.5f, SliderMath.atLeastOneStep(0.5f, 0), 0f)
    }

    @Test fun theTrackZoneRunsToTheItemsEndAndThatMeansFull() {
        // The zone is the 64 px track and 6 px after it: a press in those last 6 is the highest step.
        assertEquals(1f, SliderMath.level(66f, left = 0f, width = 64f, steps = 15), 0f)
        assertEquals(1f, SliderMath.level(70f, left = 0f, width = 64f, steps = 15), 0f)
        // Right to left the zone starts with those 6 px, and the track's own start is its right end.
        assertEquals(1f, SliderMath.level(2f, left = 6f, width = 64f, steps = 15, rtl = true), 0f)
        assertEquals(0f, SliderMath.level(70f, left = 6f, width = 64f, steps = 15, rtl = true), 0f)
    }

    @Test fun anyLevelAboveNothingShowsAtLeastADot() {
        assertEquals(0f, SliderMath.fillWidth(0f, 64f, min = 4f), 0f)
        assertEquals(4f, SliderMath.fillWidth(1 / 100f, 64f, min = 4f), 0f)
        assertEquals(4.2666f, SliderMath.fillWidth(1 / 15f, 64f, min = 4f), 1e-3f)
        assertEquals(39.68f, SliderMath.fillWidth(0.62f, 64f, min = 4f), 1e-4f)
        assertEquals(64f, SliderMath.fillWidth(1.5f, 64f, min = 4f), 0f)
        assertEquals(0f, SliderMath.fillWidth(Float.NaN, 64f, min = 4f), 0f)
        assertEquals(0f, SliderMath.fillWidth(0.5f, 0f, min = 4f), 0f)
    }

    @Test fun theHandleStandsOnTheLevelButNeverHangsOverAnEnd() {
        assertEquals(2f, SliderMath.handleCenter(0f, 64f, edge = 2f), 0f)
        assertEquals(32f, SliderMath.handleCenter(0.5f, 64f, edge = 2f), 0f)
        assertEquals(62f, SliderMath.handleCenter(1f, 64f, edge = 2f), 0f)
        assertEquals(1.5f, SliderMath.handleCenter(0.9f, 3f, edge = 2f), 0f) // a track too short for it: the middle
    }

    @Test fun theTrackIsDrawnAsTheDesignSaysOnBlackAndOnWhite() {
        val dark = StripLook(Color.White, true, TextSize.DEFAULT, 12.dp, Pill.NONE)
        assertEquals(0.38f, dark.sliderTrackAlpha, 0f)
        assertEquals(3.4f, Contrast.ratio(dark.sliderTrack.compositeOver(Color.Black), Color.Black), 0.1f)
        val light = StripLook(Contrast.DARK_TEXT, false, TextSize.DEFAULT, 12.dp, Pill.NONE)
        assertEquals(0.50f, light.sliderTrackAlpha, 0f)
        assertEquals(3.2f, Contrast.ratio(light.sliderTrack.compositeOver(Color.White), Color.White), 0.1f)
        // Muted, the kept level is between the two: told from the track and from a full fill.
        assertEquals(0.62f, dark.sliderMuted.alpha, 0.01f)
        assertEquals(0.74f, light.sliderMuted.alpha, 0.01f)
    }

    @Test fun trackAndFillStandOutFromEveryBar() {
        // The 3:1 the parts of a control need, on every gray an opaque bar can be and on a grid of colors: where the
        // usual strength is too faint on a bar of another color, the track is drawn stronger, up to 0.7 of the text
        // color. Past that it would no longer be told from the fill, so the few mid-tone bars where the text itself
        // only just reaches 4.5:1 get a track a little under 3:1, and never under 2.75:1.
        val bars = (0..255 step 3).map { Color(it, it, it) } +
            (0..255 step 51).flatMap { r -> (0..255 step 51).flatMap { g -> (0..255 step 51).map { b -> Color(r, g, b) } } }
        var short = 0
        for (bar in bars) {
            val live = Contrast.resolve(Contrast.readableOn(bar), bar)
            val look = StripLook(live.fg, live.barDark, TextSize.DEFAULT, 12.dp, Pill.NONE, live.background)
            val alpha = look.sliderTrackAlpha
            val track = Contrast.ratio(look.sliderTrack.compositeOver(bar), bar)
            assertTrue("the track's strength on $bar", alpha >= (if (look.lightText) 0.38f else 0.50f) && alpha <= 0.7f + 1e-6f)
            if (alpha < 0.7f - 1e-6f) assertTrue("track on $bar", track >= 3f)
            assertTrue("track at its strongest on $bar: $track", track >= 2.75f)
            if (track < 3f) short++
            assertTrue("fill on $bar", Contrast.ratio(look.fg, bar) >= 4.5f)
            assertTrue("muted fill on $bar", Contrast.ratio(look.sliderMuted.compositeOver(bar), bar) >= 3f)
            assertTrue("muted fill and track differ on $bar", look.sliderMuted.alpha >= alpha + 0.2f)
        }
        // Four of the 302 today (a mid gray, a green, a magenta, a pink). More would mean the rule changed.
        assertTrue("$short bars with a track under 3:1", short <= 4)
    }

    // ---- the route line: the slider's line with a plane on it

    /** The line as it is designed, in dp (the canvas gives pixels; the rules are the same): 64 long, a plane of 15 with 2 clear on each side of it. */
    private fun route(share: Float, track: Float = 64f, rtl: Boolean = false) = SliderMath.route(track, share, plane = 15f, gap = 2f, rtl = rtl)!!

    @Test fun beforeItLeavesThePlaneStandsAtTheLinesStartAndAllTheRestIsAhead() {
        val r = route(0f)
        assertEquals(7.5f, r.center, 0f)
        assertNull(r.flown)
        assertEquals(17f..64f, r.ahead)
    }

    @Test fun rightAfterItHasLeftThePlaneIsALittleWayInAndNothingIsFlownYet() {
        val r = route(0.02f)
        assertEquals(8.48f, r.center, 1e-4f)
        // The clearing behind the plane still reaches past the line's start: there is no room for a part there.
        assertNull(r.flown)
        assertEquals(17.98f, r.ahead!!.start, 1e-4f)
        assertEquals(64f, r.ahead.endInclusive, 0f)
        // A part appears behind it once the clearing has left the start: 2 along the 49 that the plane travels.
        assertNull(route(1.9f / 49f).flown)
        assertEquals(0.49f, route(2.49f / 49f).flown!!.endInclusive, 1e-4f)
    }

    @Test fun halfWayThePlaneIsInTheMiddleWithAsMuchFlownAsAhead() {
        val r = route(0.5f)
        assertEquals(32f, r.center, 0f)
        assertEquals(0f..22.5f, r.flown)
        assertEquals(41.5f..64f, r.ahead)
    }

    @Test fun nearlyThereThePlaneDoesNotTouchTheFarEnd() {
        val r = route(0.98f)
        assertEquals(55.52f, r.center, 1e-4f)
        assertEquals(0f, r.flown!!.start, 0f)
        assertEquals(46.02f, r.flown.endInclusive, 1e-4f)
        // Its nose is short of the end by less than the clearing: nothing of the line is left ahead of it.
        assertTrue(r.center + 7.5f < 64f)
        assertNull(r.ahead)
        // 45 minutes out of a flight of 645: a last bit of the line is still ahead.
        val out = route(600f / 645f).ahead!!
        assertEquals(64f, out.endInclusive, 0f)
        assertEquals(1.42f, out.endInclusive - out.start, 0.01f)
    }

    @Test fun landedThePlaneStandsAtTheFarEndAndTheWholeLineIsFlown() {
        val r = route(1f)
        assertEquals(56.5f, r.center, 0f)
        assertEquals(0f..47f, r.flown)
        assertNull(r.ahead)
    }

    @Test fun thePlaneMovesEvenlyWithTheShareAndKeepsItsClearing() {
        var last = -1f
        for (i in 0..1000) {
            val share = i / 1000f
            val r = route(share)
            // 49 of the 64 are the plane's way: its middle is 7.5 in at the start and as far from the end when it has landed.
            assertEquals("$share", 7.5f + share * 49f, r.center, 1e-3f)
            assertTrue("$share", r.center > last)
            last = r.center
            r.flown?.let { assertTrue("$share", it.start == 0f && it.endInclusive > 0f && it.endInclusive <= r.center - 9.5f + 1e-3f) }
            r.ahead?.let { assertTrue("$share", it.endInclusive == 64f && it.start < 64f && it.start >= r.center + 9.5f - 1e-3f) }
        }
    }

    @Test fun aShareThatIsNoNumberOrOutOfRangeIsTheNearestEnd() {
        assertEquals(7.5f, route(Float.NaN).center, 0f)
        assertNull(route(Float.NaN).flown)
        assertEquals(17f..64f, route(Float.NaN).ahead)
        assertEquals(7.5f, route(-3f).center, 0f)
        assertEquals(56.5f, route(7f).center, 0f)
        assertEquals(0f..47f, route(Float.POSITIVE_INFINITY).flown)
    }

    @Test fun onALineShorterThanThePlaneItStandsInTheMiddleAndNothingElseIsDrawn() {
        for (share in listOf(0f, 0.5f, 1f)) for (track in listOf(4f, 10f, 15f)) {
            val r = route(share, track)
            assertEquals("$track", track / 2, r.center, 0f)
            assertNull("$track", r.flown)
            assertNull("$track", r.ahead)
        }
        // With a little more room than the plane takes, it has a way to go, and what is left of the line is drawn.
        assertEquals(17f..20f, route(0f, track = 20f).ahead)
        assertEquals(0f..3f, route(1f, track = 20f).flown)
        assertEquals(10f, route(0.5f, track = 20f).center, 0f)
        assertEquals(0f..0.5f, route(0.5f, track = 20f).flown)
        assertEquals(19.5f..20f, route(0.5f, track = 20f).ahead)
        // No width at all, less than none, not a number: nothing to draw on, and nothing fails.
        for (track in listOf(0f, -5f, Float.NaN)) assertNull("$track", SliderMath.route(track, 0.5f, plane = 15f, gap = 2f))
    }

    @Test fun rightToLeftTheLineStartsAtTheRightAndThePlaneFliesToTheLeft() {
        val start = route(0f, rtl = true)
        assertEquals(56.5f, start.center, 0f)
        assertNull(start.flown)
        assertEquals(0f..47f, start.ahead)
        val half = route(0.5f, rtl = true)
        assertEquals(32f, half.center, 0f)
        assertEquals(41.5f..64f, half.flown)
        assertEquals(0f..22.5f, half.ahead)
        val landed = route(1f, rtl = true)
        assertEquals(7.5f, landed.center, 0f)
        assertEquals(17f..64f, landed.flown)
        assertNull(landed.ahead)
        // It is the mirror image all the way, with each part still given from its left edge to its right.
        for (i in 0..100) {
            val ltr = route(i / 100f)
            val mirrored = route(i / 100f, rtl = true)
            assertEquals("$i", 64f - ltr.center, mirrored.center, 1e-4f)
            assertEquals("$i", ltr.flown?.let { 64f - it.endInclusive }, mirrored.flown?.start)
            assertEquals("$i", ltr.flown?.let { 64f - it.start }, mirrored.flown?.endInclusive)
            assertEquals("$i", ltr.ahead?.let { 64f - it.endInclusive }, mirrored.ahead?.start)
            assertEquals("$i", ltr.ahead?.let { 64f - it.start }, mirrored.ahead?.endInclusive)
        }
        // On a line shorter than the plane the middle is the middle from either side.
        assertEquals(5f, route(0f, track = 10f, rtl = true).center, 0f)
    }

    @Test fun thePlanesPlaceIsMarkedByItsClearingNotByTheStepInColor() {
        // The part ahead is the slider's quiet track. A colored part flown stands out from it far less than the slider's
        // fill does (5:1 and more): by 2.0:1 at the least and 4.3:1 at the most. So the plane and the nothing on each side
        // of it say where it is, and the color only says how the flight stands.
        val dark = StripLook(Color.White, true, TextSize.DEFAULT, 12.dp, Pill.NONE)
        val light = StripLook(Contrast.DARK_TEXT, false, TextSize.DEFAULT, 12.dp, Pill.NONE)
        fun against(look: StripLook, stands: Stands) = Contrast.ratio(look.route(stands), look.sliderTrack.compositeOver(look.background))
        val colored = listOf(dark, light).flatMap { look -> listOf(Stands.GOOD, Stands.LATE, Stands.VERY_LATE).map { against(look, it) } }
        assertEquals(2.0f, colored.min(), 0.05f)
        assertEquals(4.3f, colored.max(), 0.1f)
        for (look in listOf(dark, light)) assertTrue(against(look, Stands.NO_CLAIM) >= 5f)
    }
}
