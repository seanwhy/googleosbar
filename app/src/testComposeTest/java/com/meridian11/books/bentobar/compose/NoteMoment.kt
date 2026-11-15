package com.meridian11.books.bentobar.compose

import androidx.compose.ui.test.junit4.ComposeTestRule

/**
 * A note's Undo takes no pointer's press in its first moment, the system's double-tap time (`deafAtFirst`; 300 ms
 * here). A test that clicks one lets that pass first, as a hand does; the note itself stands three seconds.
 */
internal fun ComposeTestRule.pastANotesFirstMoment() = mainClock.advanceTimeBy(400)
