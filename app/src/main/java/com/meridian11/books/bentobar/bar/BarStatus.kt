package com.meridian11.books.bentobar.bar

import kotlinx.coroutines.flow.MutableStateFlow

/** What the strip is doing right now, so settings can say so instead of assuming it's live. */
enum class BarStatus {
    /** The accessibility service isn't running. */
    STOPPED,
    /** Drawn in the status bar. */
    SHOWN,
    /** Shown, but the status bar has no free space for any item. */
    NO_ROOM,
    /** On screen with nothing to draw: its window is invisible until an item has something to show. */
    EMPTY,
    /** "Show in status bar" is off. */
    HIDDEN_BY_USER,
    /** No status bar to draw in: an app is full screen, or the bar is hidden. */
    NO_BAR,
    /** A system panel covers the status bar. */
    COVERED,
    /** Screen off or locked. */
    ASLEEP;

    companion object {
        val current = MutableStateFlow(STOPPED)
    }
}

/** Items in the bar that don't fit in the free space right now (they're in the ‹ menu instead). */
object BarOverflow {
    val ids = MutableStateFlow<Set<String>>(emptySet())
}
