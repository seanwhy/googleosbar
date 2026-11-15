package com.meridian11.books.bentobar.ui

import android.view.KeyEvent
import androidx.compose.ui.input.key.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Bar page's keys as they are declared: each binding once, in [BarKeys] or [AreaKeys], with the words the system's
 * list of shortcuts shows for it. No two are the same press, so the list can't show one key twice.
 */
class KeyBindingsTest {
    private val all = BarKeys.all + AreaKeys.all

    @Test fun noTwoBindingsAreTheSamePress() {
        val presses = all.map { it.keycode to it.modifiers }
        assertEquals(presses.size, presses.toSet().size)
    }

    @Test fun eachBindingSaysWhatItDoesInItsOwnWords() {
        assertTrue(all.all { it.does != 0 })
        assertEquals(all.size, all.map { it.does }.toSet().size)
    }

    @Test fun aBindingIsListedAsTheSystemCountsKeys() {
        assertEquals(KeyEvent.KEYCODE_DPAD_UP to KeyEvent.META_ALT_ON, BarKeys.UP.keycode to BarKeys.UP.modifiers)
        assertEquals(KeyEvent.KEYCODE_F to KeyEvent.META_CTRL_ON, BarKeys.FIND.keycode to BarKeys.FIND.modifiers)
        assertEquals(KeyEvent.KEYCODE_FORWARD_DEL to 0, BarKeys.REMOVE.keycode to BarKeys.REMOVE.modifiers)
        assertEquals(KeyEvent.KEYCODE_DPAD_RIGHT to KeyEvent.META_ALT_ON, AreaKeys.RIGHT.keycode to AreaKeys.RIGHT.modifiers)
    }

    @Test fun theAreasKeysAreListedOnlyWhileTheyDoSomething() {
        val before = BarKeys.handler to BarKeys.several
        try {
            BarKeys.handler = null
            assertEquals("no Bar page on screen, nothing listed", emptyList<Binding>(), BarKeys.listed())
            BarKeys.handler = { false }
            BarKeys.several = false
            assertEquals(BarKeys.all, BarKeys.listed())
            BarKeys.several = true
            assertEquals(BarKeys.all + AreaKeys.all, BarKeys.listed())
        } finally {
            BarKeys.handler = before.first
            BarKeys.several = before.second
        }
    }
}
