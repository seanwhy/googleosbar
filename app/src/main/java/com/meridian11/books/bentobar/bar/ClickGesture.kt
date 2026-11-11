package com.meridian11.books.bentobar.bar

/**
 * One press on an item of the strip, or on ‹: from the pointer going down to its letting go, what comes of it. Apart from
 * the pointer events, so that the rules are unit-tested (`ClickGestureTest`), as [SliderGesture]'s are.
 *
 * A primary press is a click when it lets go inside the item, and moved past the touch slop along the strip it becomes a drag, if
 * the item can be dragged. A secondary press is the item's menu. A finger held still for the system's long-press time is the menu
 * too, at that moment and not when it lifts, so the hold says what it did; what the finger does after that is nothing to this press.
 * Held still means past the slop on neither axis and still on the item: a pull down off the bar must not open a menu.
 *
 * Only a press with no button pressed can be a hold ([isFinger]): a finger on a touch screen has no button, and a touchpad's press
 * has one even when its pointer claims to be a touch, which would otherwise turn a slow click or a drag into the menu.
 *
 * A press that something inside the item took for itself (the slider's track) is none of the item's: no click and no drag
 * come of it. A press the system takes away arrives as a Release already consumed: never a click and never a drop.
 */
internal class ClickGesture(private val slop: Float) {
    /** Whether a press is a finger's, one that can hold: a touch, with no button pressed. */
    companion object { fun isFinger(touch: Boolean, button: Boolean) = touch && !button }

    /** What the strip does about what just happened. */
    sealed interface Did {
        data object Nothing : Did
        data object Click : Did
        data object Menu : Did
        /** The drag follows the pointer, which is at [x]. The first of these is its beginning. */
        data class Drag(val x: Float) : Did
        /** The pointer let go of the drag at [x]: where the item lands. */
        data class Drop(val x: Float) : Did
        /** The drag was taken away: nothing lands. */
        data object DragTakenAway : Did
    }

    /** A press is under way that is this item's. */
    var armed = false; private set
    /** It became a drag. */
    var dragging = false; private set
    /** Where the press went down. */
    var downX = 0f; private set
    private var downY = 0f
    private var secondary = false
    private var finger = false
    /** Gone past the slop along the strip: it can become a drag. */
    private var across = false
    /** No longer held still: past the slop on either axis, or off the item. */
    private var moved = false
    private var downAt = 0L
    private var lastAt = 0L
    /** The system's long-press time when this press went down: a person can change it while the service runs. */
    private var longPressMs = 0L

    /**
     * How long the finger may stay as it is before it counts as a hold, from the last event seen; null when no hold is being
     * waited for (nothing pressed, a mouse or a touchpad, a finger that has moved).
     */
    val holdMs: Long? get() = if (!armed || !finger || secondary || moved) null else (longPressMs - (lastAt - downAt)).coerceAtLeast(0)

    /** The pointer went down at [x], [y]. [taken]: something inside the item has it already. [button]: a button is pressed. */
    fun press(taken: Boolean, secondary: Boolean, touch: Boolean, button: Boolean, x: Float, y: Float, at: Long, longPressMs: Long) {
        reset()
        if (taken) return
        armed = true
        this.secondary = secondary
        finger = isFinger(touch, button)
        downX = x
        downY = y
        downAt = at
        lastAt = at
        this.longPressMs = longPressMs
    }

    /** The pointer moved. Only a primary press that has gone past the slop along the strip drags, and only an item that [draggable] says can. */
    fun move(x: Float, y: Float, inside: Boolean, pressed: Boolean, taken: Boolean, draggable: Boolean, at: Long): Did {
        if (!armed) return Did.Nothing
        lastAt = at
        if (!pressed || taken) return Did.Nothing
        if (kotlin.math.abs(x - downX) > slop) across = true
        if (across || kotlin.math.abs(y - downY) > slop || !inside) moved = true
        if (secondary || !draggable) return Did.Nothing
        if (across) dragging = true
        return if (dragging) Did.Drag(x) else Did.Nothing
    }

    /** The pointer let go at [x]. [taken]: the system took the press away, which arrives as a Release already consumed. */
    fun release(x: Float, inside: Boolean, taken: Boolean): Did {
        if (!armed) return Did.Nothing
        val drag = dragging
        val menu = secondary
        reset()
        return when {
            taken -> if (drag) Did.DragTakenAway else Did.Nothing
            drag -> Did.Drop(x)
            !inside -> Did.Nothing
            menu -> Did.Menu
            else -> Did.Click
        }
    }

    /** The finger stayed for [holdMs]: the item's menu, now. Lifting it afterwards does nothing more. */
    fun held(): Did {
        if (holdMs == null) return Did.Nothing
        reset()
        return Did.Menu
    }

    /** The gesture ended with a press under way (the item left the strip): a drag in progress must not stay behind. */
    fun cancel(): Did {
        val drag = dragging
        reset()
        return if (drag) Did.DragTakenAway else Did.Nothing
    }

    private fun reset() { armed = false; dragging = false; secondary = false; finger = false; across = false; moved = false }
}
