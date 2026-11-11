package com.meridian11.books.bentobar.bar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One press on the slider in the bar: what it reports and how it ends, for a mouse and for a finger.
 * The slider here has the fifteen steps of a media volume; levels come in as they are under the pointer.
 */
class SliderGestureTest {
    private fun step(n: Int) = n / 15f
    private fun level(n: Int) = SliderGesture.End.Level(step(n))

    @Test fun aClickSetsTheLevelUnderThePointerAtOnce() {
        val g = SliderGesture(15)
        assertEquals(step(8), g.press(0.52f, follows = true)!!, 1e-6f)
        assertTrue(g.down && g.following)
        assertEquals(level(8), g.release(0.52f, inside = true))
        assertFalse(g.down || g.following)
    }

    @Test fun aClickAtTheVeryStartSetsOneStepAlsoWhenThePointerTrembles() {
        val g = SliderGesture(15)
        assertEquals(step(1), g.press(0f, follows = true)!!, 1e-6f)
        // Moves that stay on the same level are no news, and above all they are not a drag to nothing.
        assertNull(g.move(0.01f, pastSlop = false))
        assertNull(g.move(0.02f, pastSlop = true))
        assertEquals(level(1), g.release(0f, inside = true))
    }

    @Test fun aDragCanReachNothing() {
        val g = SliderGesture(15)
        g.press(0.2f, follows = true)
        assertEquals(step(2), g.move(0.13f, pastSlop = true)!!, 1e-6f)
        assertEquals(step(1), g.move(0.07f, pastSlop = true)!!, 1e-6f)
        assertEquals(0f, g.move(0f, pastSlop = true)!!, 0f)
        // Let go past the track's start, outside the item: still nothing, and still the end of the drag.
        assertEquals(level(0), g.release(-0.3f, inside = false))
    }

    @Test fun aDragThatStartsAtTheStartAndComesBackReachesNothingToo() {
        val g = SliderGesture(15)
        assertEquals(step(1), g.press(0f, follows = true)!!, 1e-6f)
        // On to the first step: that is what shows already, so there is nothing to report, but the pointer has moved.
        assertNull(g.move(0.07f, pastSlop = true))
        assertEquals(0f, g.move(0f, pastSlop = true)!!, 0f)
        assertEquals(level(0), g.release(0f, inside = true))
    }

    @Test fun onlyANewLevelIsReported() {
        val g = SliderGesture(15)
        assertEquals(step(8), g.press(0.5f, follows = true)!!, 1e-6f)
        assertNull(g.move(0.51f, pastSlop = true))
        assertEquals(step(9), g.move(0.6f, pastSlop = true)!!, 1e-6f)
        assertNull(g.move(0.61f, pastSlop = true))
    }

    @Test fun aDragEndsWhereItIsLetGoAlsoPastTheLastMove() {
        val g = SliderGesture(15)
        g.press(0.2f, follows = true)
        g.move(0.4f, pastSlop = true)
        assertEquals(level(15), g.release(1.4f, inside = false))
    }

    @Test fun aLongDragIsStillADrag() {
        // How long the pointer was down only matters for a finger that never followed: no hold is waited for here.
        val g = SliderGesture(15)
        g.press(0.2f, follows = true)
        g.move(0.6f, pastSlop = true)
        assertEquals(level(9), g.release(0.6f, inside = false))
        assertEquals(SliderGesture.End.None, g.held())
    }

    @Test fun aFingerSetsNothingUntilItLifts() {
        val g = SliderGesture(15)
        assertNull(g.press(0.5f, follows = false))
        assertTrue(g.down)
        assertFalse(g.following)
        assertNull(g.move(0.52f, pastSlop = false)) // a tap trembles too
        assertEquals(level(8), g.release(0.52f, inside = true))
    }

    @Test fun aTapAtTheVeryStartSetsOneStep() {
        val g = SliderGesture(15)
        g.press(0f, follows = false)
        assertEquals(level(1), g.release(0f, inside = true))
    }

    @Test fun aFingerThatMovesPastTheSlopMakesTheLevelFollow() {
        val g = SliderGesture(15)
        g.press(0.2f, follows = false)
        assertNull(g.move(0.22f, pastSlop = false))
        assertEquals(step(5), g.move(0.33f, pastSlop = true)!!, 1e-6f)
        assertTrue(g.following)
        // Once it follows it follows, however near it comes to where it went down.
        assertEquals(step(3), g.move(0.21f, pastSlop = false)!!, 1e-6f)
        assertEquals(level(3), g.release(0.21f, inside = true))
    }

    @Test fun aSwipeCanReachNothing() {
        val g = SliderGesture(15)
        g.press(0.2f, follows = false)
        assertEquals(0f, g.move(0f, pastSlop = true)!!, 0f)
        assertEquals(level(0), g.release(0f, inside = true))
    }

    @Test fun aLongHoldAsksForTheMenuAndSetsNothing() {
        val g = SliderGesture(15)
        g.press(0.5f, follows = false)
        assertTrue(g.holding)
        assertEquals(SliderGesture.End.Menu, g.held())
        assertFalse(g.down || g.holding)
        assertEquals(SliderGesture.End.None, g.release(0.5f, inside = true))
    }

    @Test fun aFingerThatStraysOffTheSliderIsNoHoldAndSetsNothing() {
        val g = SliderGesture(15)
        g.press(0.5f, follows = false)
        assertNull(g.move(0.5f, pastSlop = false, strayed = true))
        assertFalse(g.holding)
        assertFalse(g.following)
        assertEquals(SliderGesture.End.None, g.held())
        assertEquals(SliderGesture.End.None, g.release(0.5f, inside = false))
    }

    @Test fun aFingerThatLiftsSomewhereElseSetsNothing() {
        val g = SliderGesture(15)
        g.press(0.5f, follows = false)
        assertEquals(SliderGesture.End.None, g.release(0.5f, inside = false))
    }

    @Test fun aPressThatIsTakenAwayKeepsWhatWasSetAndSetsNothingMore() {
        val mouse = SliderGesture(15)
        mouse.press(0.2f, follows = true)
        mouse.move(0.6f, pastSlop = true)
        assertEquals(level(9), mouse.cancel())
        assertFalse(mouse.down)
        assertEquals(SliderGesture.End.None, mouse.cancel()) // the last word is said once
        // A tap that was taken away is no tap.
        val finger = SliderGesture(15)
        finger.press(0.5f, follows = false)
        assertEquals(SliderGesture.End.None, finger.cancel())
        // A swipe that was taken away ends on what it had set.
        val swipe = SliderGesture(15)
        swipe.press(0.2f, follows = false)
        swipe.move(0.4f, pastSlop = true)
        assertEquals(level(6), swipe.cancel())
    }

    @Test fun nothingHappensWithoutAPress() {
        val g = SliderGesture(15)
        assertNull(g.move(0.5f, pastSlop = true))
        assertEquals(SliderGesture.End.None, g.release(0.5f, inside = true))
        assertEquals(SliderGesture.End.None, g.cancel())
    }

    @Test fun everyPressStartsAfresh() {
        val g = SliderGesture(15)
        g.press(0.4f, follows = true)
        g.move(0.6f, pastSlop = true)
        g.release(0.6f, inside = true)
        // The next click is a click again, on its own level.
        assertEquals(step(3), g.press(0.2f, follows = true)!!, 1e-6f)
        assertEquals(level(3), g.release(0.2f, inside = true))
        // And a swipe after it reports its first level, also when that is where the last press ended.
        g.press(0.1f, follows = false)
        assertEquals(step(3), g.move(0.2f, pastSlop = true)!!, 1e-6f)
    }

    @Test fun whateverWasReportedIsFollowedByOneLastWord() {
        // The item is told "done" exactly once for a press that reported anything, however the press ends.
        for (ending in 0..2) {
            val g = SliderGesture(15)
            g.press(0.3f, follows = true)
            g.move(0.5f, pastSlop = true)
            val end = when (ending) {
                0 -> g.release(0.5f, inside = true)
                1 -> g.release(0.5f, inside = false)
                else -> g.cancel()
            }
            assertTrue("ending $ending", end is SliderGesture.End.Level)
            assertEquals(SliderGesture.End.None, g.cancel())
        }
    }

    @Test fun withoutStepsAClickSetsAtLeastASixtyFourth() {
        val g = SliderGesture(0)
        assertEquals(1 / 64f, g.press(0f, follows = true)!!, 1e-6f)
        assertEquals(0.5f, g.move(0.5f, pastSlop = true)!!, 0f)
        assertEquals(SliderGesture.End.Level(0f), g.release(0f, inside = true))
    }
}
