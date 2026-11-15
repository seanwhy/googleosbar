package com.meridian11.books.bentobar.compose

import android.app.Application
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.KeyboardShortcutInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.data.BarConfig
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.data.Position
import com.meridian11.books.bentobar.data.oneArea
import com.meridian11.books.bentobar.data.Store
import com.meridian11.books.bentobar.data.Store.Consent
import com.meridian11.books.bentobar.items.Env
import com.meridian11.books.bentobar.ui.AreaKeys
import com.meridian11.books.bentobar.ui.BarKeys
import com.meridian11.books.bentobar.ui.Binding
import com.meridian11.books.bentobar.ui.MainActivity
import com.meridian11.books.bentobar.ui.Undo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The system's list of shortcuts and the window, which cannot disagree: what the settings window gives the system is
 * the Bar page's bindings themselves ([BarKeys], [AreaKeys]), only while that page is shown, the areas' keys only while
 * there are lanes to move between, and on Look its Undo while that has something to take back; and every press it lists
 * is one the window takes.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w1180dp-h780dp-mdpi")
class ShortcutListTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var before: BarConfig
    private var window: ActivityScenario<MainActivity>? = null

    @Before fun env() { Env.init(app); before = Store.config.value; Store.setConsent(Consent.AGREED); Undo.last.value = null }
    @After fun close() { window?.close(); Store.update { before }; Store.setConsent(Consent.NOT_ASKED); Undo.last.value = null }

    private fun item(id: String, type: String, area: Position = Position.RIGHT) = ItemConfig(id, type, area = area)
    private val one = listOf(item("a", "clock"), item("b", "memory"), item("c", "battery"))
    private val lanes = listOf(item("a", "clock", Position.LEFT), item("b", "memory", Position.LEFT), item("c", "battery"))

    private fun open(items: List<ItemConfig>, select: String? = null) {
        window?.close()
        Store.update { BarConfig(items = items, position = Position.RIGHT) }
        Undo.last.value = null
        window = ActivityScenario.launch(MainActivity::class.java)
        rule.waitForIdle()
        if (select != null) { rule.onNode(hasTestTag("row:$select")).performClick(); rule.waitForIdle() }
    }

    private fun listed(): List<KeyboardShortcutGroup> {
        val groups = mutableListOf<KeyboardShortcutGroup>()
        window!!.onActivity { it.onProvideKeyboardShortcuts(groups, null, 0) }
        return groups
    }

    private fun press(keycode: Int, modifiers: Int): Boolean {
        var taken = false
        window!!.onActivity { taken = it.onKeyDown(keycode, KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keycode, 0, modifiers)) }
        rule.waitForIdle()
        return taken
    }

    private fun presses(keys: List<KeyboardShortcutInfo>) = keys.map { Triple(it.label.toString(), it.keycode, it.modifiers) }
    private fun presses(keys: Iterable<Binding>) = keys.map { Triple(app.getString(it.does), it.keycode, it.modifiers) }

    @Test fun theBarPageListsItsOwnBindingsUnderItsName() {
        open(one)
        val group = listed().single()
        assertEquals(app.getString(R.string.page_bar), group.label.toString())
        assertEquals(presses(BarKeys.all), presses(group.items))
    }

    @Test fun theAreasKeysAreListedWhileThereAreLanesToMoveBetween() {
        open(lanes)
        assertEquals(presses(BarKeys.all + AreaKeys.all), presses(listed().single().items))
    }

    @Test fun anotherPageListsNothing() {
        open(one)
        rule.onNode(hasText(app.getString(R.string.nav_look)) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).performClick()
        rule.waitForIdle()
        assertEquals(emptyList<KeyboardShortcutGroup>(), listed())
    }

    @Test fun onLookCtrlZTakesItsOwnCommandBackAndTheBarPagesCtrlZDoesTooWithNoSlipForTheLaneThatClosed() {
        fun tab(name: Int) { rule.onNode(hasText(app.getString(name)) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).performClick(); rule.waitForIdle() }
        fun all(area: Int) { rule.onNode(hasText(app.getString(area)) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)).performClick(); rule.waitForIdle() }
        open(lanes, select = "b")
        // A change of the Bar page's is not Look's to take back: there Ctrl+Z is no key of the window's.
        assertTrue(press(BarKeys.UP.keycode, BarKeys.UP.modifiers))
        tab(R.string.nav_look)
        val arranged = Store.config.value
        assertEquals(listOf("b", "a", "c"), arranged.items.map { it.id })
        assertFalse(press(BarKeys.UNDO.keycode, BarKeys.UNDO.modifiers))
        assertEquals(arranged, Store.config.value)
        // Look's own command: listed while it can be taken back, and taken back by the key in one step.
        all(R.string.areas_middle)
        assertEquals(Position.CENTER, Store.config.value.oneArea())
        assertEquals(presses(listOf(BarKeys.UNDO)), presses(listed().single().items))
        assertTrue(press(BarKeys.UNDO.keycode, BarKeys.UNDO.modifiers))
        assertEquals(arranged, Store.config.value)
        assertEquals(emptyList<KeyboardShortcutGroup>(), listed())
        // And from the Bar page: no slip says a lane closed, and its Ctrl+Z brings lanes and order back.
        all(R.string.areas_right)
        tab(R.string.nav_bar)
        rule.onNode(hasTestTag("fold")).assertDoesNotExist()
        assertTrue(press(BarKeys.UNDO.keycode, BarKeys.UNDO.modifiers))
        assertEquals(arranged, Store.config.value)
    }

    @Test fun everyPressTheListShowsIsOneTheWindowTakes() {
        open(lanes, select = "b")
        val keys = listed().single().items
        assertTrue(keys.size >= 9)
        keys.forEach { key ->
            // Each from the same start: a key that takes the selection or the keyboard away would answer for the next.
            open(lanes, select = "b")
            if (key.keycode == KeyEvent.KEYCODE_Z) assertTrue(press(KeyEvent.KEYCODE_FORWARD_DEL, 0))
            assertTrue("${key.label} is listed and not taken", press(key.keycode, key.modifiers))
        }
    }

    @Test fun whatAListedPressDoesIsWhatItsLabelSays() {
        open(lanes, select = "b")
        fun ids() = Store.config.value.items.map { it.id }
        assertTrue(press(BarKeys.UP.keycode, BarKeys.UP.modifiers))
        assertEquals(listOf("b", "a", "c"), ids())
        assertTrue(press(AreaKeys.RIGHT.keycode, AreaKeys.RIGHT.modifiers))
        assertEquals(Position.RIGHT, Store.config.value.items.first { it.id == "b" }.area)
        assertTrue(press(BarKeys.REMOVE.keycode, BarKeys.REMOVE.modifiers))
        assertEquals(listOf("a", "c"), ids())
        assertTrue(press(BarKeys.UNDO.keycode, BarKeys.UNDO.modifiers))
        assertTrue("b" in ids())
    }

    @Test fun shiftHeldTooStillMovesARow_theWayRoundASystemThatKeepsAltWithAnArrow() {
        open(lanes, select = "b")
        assertTrue(press(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.META_ALT_ON or KeyEvent.META_SHIFT_ON))
        assertEquals(Position.RIGHT, Store.config.value.items.first { it.id == "b" }.area)
    }
}
