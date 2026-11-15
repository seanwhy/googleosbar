package com.meridian11.books.bentobar.ui

import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalViewConfiguration
import kotlinx.coroutines.delay

/**
 * How long a note with Undo stands: the slip in a removed row's place, the one for a closed or a removed area, the
 * library's "Added", Look's line. One rule for all of them. A note goes [Motion.NOTE] after it appeared. The time waits
 * while the pointer is over the note or the keyboard is in it, and starts again, whole, when they have left: nobody
 * reaches for an Undo that goes under the hand. The pointer is over a note once it has moved there: a note that came
 * under a pointer at rest (the one that clicked an area's trash, where the note's Undo then stands) is not pointed at
 * and goes in its time. A new change replaces the note, as ever. When the time is up only the
 * note goes ([Undo.noteOver]): Ctrl+Z takes the change back until the next one. And in its first moment a note's Undo
 * takes no pointer's press ([deafAtFirst]).
 *
 * While a screen reader's touch exploration is on, a note does not go by itself: it stays until the next change. Someone
 * who finds it by touch has no pointer over it, and a screen reader's focus is not the keyboard's, so nothing would hold
 * it while it is read out and reached for.
 */
internal object NoteClock {
    /**
     * What a note still waits, in milliseconds, counted from now: all of [time] again, or nothing to wait for (null)
     * while it is [held] or the screen is [explored] by touch.
     */
    fun wait(held: Boolean, explored: Boolean, time: Long): Long? = if (held || explored) null else time
}

/** What holds a note: the pointer moving over one of its parts ([holdsNote]: its words, its Undo), or the keyboard in one. */
@Stable
internal class NoteHold {
    internal var parts by mutableIntStateOf(0)
    val held: Boolean get() = parts > 0
}

/**
 * Runs the time of the last change's note while the note is drawn: called by the note itself, so one that is not on
 * screen (the list behind the panel in a narrow window, another tab) has its time when it is. The time is the one a
 * person's accessibility settings ask for a text with a control ("Time to take action").
 */
@Composable
internal fun NoteTime(hold: NoteHold) {
    val kept = Undo.last.collectAsState().value ?: return
    val manager = LocalAccessibilityManager.current
    val time = manager?.calculateRecommendedTimeoutMillis(Motion.NOTE.toLong(), containsText = true, containsControls = true) ?: Motion.NOTE.toLong()
    val held = hold.held
    val explored = exploredByTouch()
    LaunchedEffect(kept, held, explored, time) {
        delay(NoteClock.wait(held, explored, time) ?: return@LaunchedEffect)
        Undo.noteOver(kept)
    }
}

/** Whether a screen reader's touch exploration is on, followed while this is composed: a note that stands when it comes on is held, and has its time, whole, when it goes off. */
@Composable
private fun exploredByTouch(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(AccessibilityManager::class.java) }
    var explored by remember(manager) { mutableStateOf(manager?.isTouchExplorationEnabled == true) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { explored = it }
        manager?.addTouchExplorationStateChangeListener(listener)
        explored = manager?.isTouchExplorationEnabled == true
        onDispose { manager?.removeTouchExplorationStateChangeListener(listener) }
    }
    return explored
}

/**
 * A part of a note: the pointer over it, or the keyboard in it, holds the note ([NoteHold]). The pointer counts from
 * its first move over the part, not from the moment the part is under it: the window tells a part that it came under
 * a pointer at rest as it tells one the pointer came to, so the place is compared, in the window's own measure (a
 * part that travels under a pointer at rest has not been pointed at either). A finger never hovers and holds nothing.
 */
internal fun Modifier.holdsNote(hold: NoteHold): Modifier = composed {
    var pointed by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val place = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val on = pointed || focused
    DisposableEffect(hold, on) {
        if (on) hold.parts++
        onDispose { if (on) hold.parts-- }
    }
    onGloballyPositioned { place[0] = it }.pointerInput(Unit) {
        awaitPointerEventScope {
            var came: Offset? = null
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull() ?: continue
                val at = place[0]?.takeIf { it.isAttached }?.localToRoot(change.position) ?: change.position
                when (event.type) {
                    PointerEventType.Exit -> { pointed = false; came = null }
                    PointerEventType.Enter -> came = at
                    PointerEventType.Move -> if (!change.pressed) { if (came == null) came = at else if (at != came) pointed = true }
                }
            }
        }
    }.onFocusChanged { focused = it.hasFocus }
}

/**
 * On a note's Undo: it takes no pointer's press for the system's double-tap time after the note came. A note comes
 * where the hand already is (a lane's trash stands where the note for the removed area then has its Undo), so the
 * second press of a double click would take back what the first one did. Only a pointer's press, a mouse's and a
 * finger's alike, which is taken on its way down so the button sees none of it: Enter on the focused Undo, Ctrl+Z and
 * a screen reader's click are no second press and work at once. The time runs from the note's change, so a note that
 * follows another in the same place starts it again. Put before what the button does with a press.
 */
internal fun Modifier.deafAtFirst(): Modifier = composed {
    val kept = Undo.last.collectAsState().value
    val time = LocalViewConfiguration.current.doubleTapTimeoutMillis
    val deaf = remember(kept) { mutableStateOf(true) }
    LaunchedEffect(deaf) {
        delay(time)
        deaf.value = false
    }
    pointerInput(deaf) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (deaf.value && event.type == PointerEventType.Press) event.changes.forEach { it.consume() }
            }
        }
    }
}
