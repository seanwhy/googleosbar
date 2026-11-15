package com.meridian11.books.bentobar.bar

import android.graphics.Rect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.meridian11.books.bentobar.data.Position

/**
 * One area's strip: its window and what belongs to that window alone. The controller reaches a strip's window through
 * this, and the popups and the tooltip are told which strip they hang from.
 *
 * What it last measured, its reach and whether it drew anything are kept while it has no window: they say how much room
 * it wants when it comes back.
 */
internal class AreaStrip(val area: Position, val window: Overlay) {
    /** The width its content last measured. */
    var measured = 0
    /** How wide it last was with a ‹'s whole room beside the items it kept, with a ‹ or without: what the strips behind the first keep clear. */
    var reach = 0
    /** Whether it last had nothing to draw ([StripContent.empty]); its window's root is invisible while so. */
    var empty by mutableStateOf(false)
    /** Shown and not measured since: its root is still invisible, and it counts as drawing until the measure says. */
    var pending = false
    /** The most its content may take, in px. */
    val maxWidth = mutableIntStateOf(0)
    /** The items it had no room for. Kept while the bar is off the screen, so that it comes back with its ‹ in its first
     *  frame; emptied when the area's own window goes while the bar stays, or the area has no window when the bar is back. */
    var dropped by mutableStateOf(emptySet<String>())
    /** Where each item was last drawn (window coordinates): a popup opens under its item, and a press is told to the item it is on. */
    val placed = mutableMapOf<String, Rect>()
    /**
     * Which of its windows this is, counted up with each [show]: what a window's content measured reaches the controller
     * a moment later, and by then the strip can have left and come back. A measure of an earlier window says nothing of
     * this one.
     */
    var generation = 0
        private set
    val shown get() = window.shown
    val params get() = window.params
    /** On screen with something to see, or about to say whether it has. */
    val draws get() = shown && (pending || !empty)

    fun state() = BarRules.AreaState(measured, empty, shown, pending, reach)

    /**
     * A window starts invisible: nothing has been measured, so nothing says yet whether it has anything to draw, and a
     * window that is up with nothing in it is what must never be on screen. Its first measure makes it visible, or not.
     */
    fun show(content: @Composable () -> Unit) {
        generation++
        window.show(content)
        if (!window.shown) return // Android refused the window
        window.setContentVisible(false)
        pending = true
    }

    fun hide() { window.hide(); pending = false; placed.clear() }
    fun setContentVisible(visible: Boolean) = window.setContentVisible(visible)
    fun relayout() = window.relayout()
    fun locationOnScreen() = window.locationOnScreen()
    fun watchOutside(on: Boolean) = window.watchOutside(on)
}
