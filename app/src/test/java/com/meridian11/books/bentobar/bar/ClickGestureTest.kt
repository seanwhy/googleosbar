package com.meridian11.books.bentobar.bar

import com.meridian11.books.bentobar.bar.ClickGesture.Did
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** One press on an item of the strip: what it comes to, for a mouse and for a finger. The rules behind the strip's pointer loop. */
class ClickGestureTest {
    private fun gesture() = ClickGesture(slop = 8f)

    @Test fun aPressThatLetsGoInsideIsAClick() {
        val g = gesture()
        g.press(taken = false, secondary = false, touch = false, button = true, x = 10f, y = 5f, at = 0, longPressMs = 400)
        assertTrue(g.armed)
        assertEquals(Did.Click, g.release(x = 10f, inside = true, taken = false))
        assertFalse(g.armed)
    }

    @Test fun aPressThatLetsGoOutsideIsNothing() {
        val g = gesture()
        g.press(taken = false, secondary = false, touch = false, button = true, x = 10f, y = 5f, at = 0, longPressMs = 400)
        assertEquals(Did.Nothing, g.release(x = 90f, inside = false, taken = false))
    }

    @Test fun aSecondaryPressIsTheMenuAndNeverDrags() {
        val g = gesture()
        g.press(taken = false, secondary = true, touch = false, button = true, x = 10f, y = 5f, at = 0, longPressMs = 400)
        assertEquals(Did.Nothing, g.move(x = 90f, y = 5f, inside = true, pressed = true, taken = false, draggable = true, at = 50))
        assertFalse(g.dragging)
        assertEquals(Did.Menu, g.release(x = 90f, inside = true, taken = false))
    }

    @Test fun aPressTakenByTheSliderInsideIsNoneOfTheItems() {
        val g = gesture()
        g.press(taken = true, secondary = false, touch = false, button = true, x = 10f, y = 5f, at = 0, longPressMs = 400)
        assertFalse(g.armed)
        assertEquals(Did.Nothing, g.move(x = 90f, y = 5f, inside = true, pressed = true, taken = false, draggable = true, at = 50))
        assertEquals(Did.Nothing, g.release(x = 90f, inside = true, taken = false))
    }

    @Test fun aMoveTheSliderHasTakenNeverStartsADrag() {
        val g = gesture()
        g.press(taken = false, secondary = false, touch = false, button = true, x = 10f, y = 5f, at = 0, longPressMs = 400)
        assertEquals(Did.Nothing, g.move(x = 90f, y = 5f, inside = true, pressed = true, taken = true, draggable = true, at = 50))
        assertFalse(g.dragging)
    }

    @Test fun aMovePastTheSlopBecomesADragThatDropsWhereItIsLetGo() {
        val g = gesture()
        g.press(taken = false, secondary = false, touch = false, button = true, x = 10f, y = 5f, at = 0, longPressMs = 400)
        assertEquals(Did.Nothing, g.move(x = 15f, y = 5f, inside = true, pressed = true, taken = false, draggable = true, at = 10))
        assertEquals(Did.Drag(30f), g.move(x = 30f, y = 5f, inside = true, pressed = true, taken = false, draggable = true, at = 20))
        assertTrue(g.dragging)
        assertEquals(Did.Drag(40f), g.move(x = 40f, y = 5f, inside = true, pressed = true, taken = false, draggable = true, at = 30))
        assertEquals(Did.Drop(50f), g.release(x = 50f, inside = false, taken = false))
        assertFalse(g.dragging)
    }

    @Test fun anItemThatCannotBeDraggedStaysAClick() {
        val g = gesture()
        g.press(taken = false, secondary = false, touch = false, button = true, x = 10f, y = 5f, at = 0, longPressMs = 400)
        assertEquals(Did.Nothing, g.move(x = 90f, y = 5f, inside = true, pressed = true, taken = false, draggable = false, at = 20))
        assertEquals(Did.Click, g.release(x = 90f, inside = true, taken = false))
    }

    // A press the system cancels arrives as a Release already consumed.

    @Test fun aPressTheSystemTakesAwayIsNeverAClick() {
        val g = gesture()
        g.press(taken = false, secondary = false, touch = true, button = false, x = 10f, y = 5f, at = 0, longPressMs = 400)
        assertEquals(Did.Nothing, g.release(x = 10f, inside = true, taken = true))
        assertFalse(g.armed)
    }

    @Test fun aDragTheSystemTakesAwayIsNeverADrop() {
        val g = gesture()
        g.press(taken = false, secondary = false, touch = true, button = false, x = 10f, y = 5f, at = 0, longPressMs = 400)
        g.move(x = 40f, y = 5f, inside = true, pressed = true, taken = false, draggable = true, at = 20)
        assertEquals(Did.DragTakenAway, g.release(x = 40f, inside = true, taken = true))
    }

    @Test fun aDragCutShortDoesNotStayBehind() {
        val g = gesture()
        g.press(taken = false, secondary = false, touch = true, button = false, x = 10f, y = 5f, at = 0, longPressMs = 400)
        g.move(x = 40f, y = 5f, inside = true, pressed = true, taken = false, draggable = true, at = 20)
        assertEquals(Did.DragTakenAway, g.cancel())
        assertEquals(Did.Nothing, gesture().cancel())
    }

    // A finger held still.

    @Test fun aFingerHeldForTheSystemsTimeIsTheMenuThenAndLiftingItDoesNothingMore() {
        val g = gesture()
        g.press(taken = false, secondary = false, touch = true, button = false, x = 10f, y = 5f, at = 1000, longPressMs = 400)
        assertEquals(400L, g.holdMs)
        assertEquals(Did.Menu, g.held())
        assertFalse(g.armed)
        assertEquals(Did.Nothing, g.release(x = 10f, inside = true, taken = false))
    }

    @Test fun aFingerThatLiftsBeforeTheTimeIsAClick() {
        val g = gesture()
        g.press(taken = false, secondary = false, touch = true, button = false, x = 10f, y = 5f, at = 0, longPressMs = 400)
        assertEquals(Did.Click, g.release(x = 11f, inside = true, taken = false))
    }

    @Test fun theHoldCountsFromThePressHoweverMuchTheFingerTrembles() {
        val g = gesture()
        g.press(taken = false, secondary = false, touch = true, button = false, x = 10f, y = 5f, at = 1000, longPressMs = 400)
        g.move(x = 12f, y = 5f, inside = true, pressed = true, taken = false, draggable = true, at = 1300)
        assertEquals(100L, g.holdMs)
        g.move(x = 12f, y = 5f, inside = true, pressed = true, taken = false, draggable = true, at = 1500)
        assertEquals(0L, g.holdMs)
    }

    @Test fun aFingerThatMovedIsNoHoldAndAMouseNeverHolds() {
        val moved = gesture()
        moved.press(taken = false, secondary = false, touch = true, button = false, x = 10f, y = 5f, at = 0, longPressMs = 400)
        moved.move(x = 40f, y = 5f, inside = true, pressed = true, taken = false, draggable = false, at = 10)
        assertNull(moved.holdMs)
        assertEquals(Did.Nothing, moved.held())
        assertEquals(Did.Click, moved.release(x = 40f, inside = true, taken = false))

        val mouse = gesture()
        mouse.press(taken = false, secondary = false, touch = false, button = true, x = 10f, y = 5f, at = 0, longPressMs = 400)
        assertNull(mouse.holdMs)
        val right = gesture()
        right.press(taken = false, secondary = true, touch = true, button = false, x = 10f, y = 5f, at = 0, longPressMs = 400)
        assertNull(right.holdMs)
    }

    // What is held still, and for how long, is the press's own (F90).

    @Test fun onlyAPressWithNoButtonIsAFingerWhateverThePointerClaims() {
        // A finger on a touch screen has no button. A touchpad's press has one, and it can claim to be a touch.
        assertTrue(ClickGesture.isFinger(touch = true, button = false))
        assertFalse(ClickGesture.isFinger(touch = true, button = true))
        assertFalse(ClickGesture.isFinger(touch = false, button = true))
        assertFalse(ClickGesture.isFinger(touch = false, button = false))
    }

    @Test fun aTouchPressWithAButtonIsNoHoldAndLetsGoAsAClick() {
        val g = gesture()
        g.press(taken = false, secondary = false, touch = true, button = true, x = 10f, y = 5f, at = 0, longPressMs = 400)
        assertNull(g.holdMs)
        assertEquals(Did.Nothing, g.held())
        assertEquals(Did.Click, g.release(x = 10f, inside = true, taken = false))
    }

    @Test fun theHoldIsTheTimeInForceWhenThePressWentDown() {
        val g = gesture()
        g.press(taken = false, secondary = false, touch = true, button = false, x = 10f, y = 5f, at = 0, longPressMs = 1500)
        assertEquals(1500L, g.holdMs)
    }

    @Test fun aFingerPulledOffTheItemIsNoHold() {
        val g = gesture()
        g.press(taken = false, secondary = false, touch = true, button = false, x = 10f, y = 5f, at = 0, longPressMs = 400)
        assertEquals(Did.Nothing, g.move(x = 10f, y = 40f, inside = false, pressed = true, taken = false, draggable = true, at = 50))
        assertNull(g.holdMs)
        assertEquals(Did.Nothing, g.held())
        assertFalse(g.dragging)
        assertEquals(Did.Nothing, g.release(x = 10f, inside = false, taken = false))
    }

    @Test fun aVerticalMovePastTheSlopInsideTheItemIsNoHoldNoDragAndStillAClick() {
        val g = gesture()
        g.press(taken = false, secondary = false, touch = true, button = false, x = 10f, y = 5f, at = 0, longPressMs = 400)
        assertEquals(Did.Nothing, g.move(x = 10f, y = 20f, inside = true, pressed = true, taken = false, draggable = true, at = 50))
        assertNull(g.holdMs)
        assertFalse(g.dragging)
        assertEquals(Did.Click, g.release(x = 10f, inside = true, taken = false))
    }

    @Test fun aFingerThatLeavesTheItemIsNoHoldEvenWithinTheSlop() {
        val g = gesture()
        g.press(taken = false, secondary = false, touch = true, button = false, x = 2f, y = 5f, at = 0, longPressMs = 400)
        g.move(x = -1f, y = 5f, inside = false, pressed = true, taken = false, draggable = true, at = 50)
        assertNull(g.holdMs)
    }

    @Test fun aDraggingFingerIsNoHold() {
        val g = gesture()
        g.press(taken = false, secondary = false, touch = true, button = false, x = 10f, y = 5f, at = 0, longPressMs = 400)
        g.move(x = 40f, y = 5f, inside = true, pressed = true, taken = false, draggable = true, at = 10)
        assertNull(g.holdMs)
        assertEquals(Did.Nothing, g.held())
    }
}
