package com.meridian11.books.bentobar.compose

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.withKeyDown
import androidx.test.core.app.ApplicationProvider
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.data.BarConfig
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.data.Position
import com.meridian11.books.bentobar.data.Section
import com.meridian11.books.bentobar.data.Store
import com.meridian11.books.bentobar.items.Env
import com.meridian11.books.bentobar.ui.BarPage
import com.meridian11.books.bentobar.ui.SettingsTheme
import com.meridian11.books.bentobar.ui.Undo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Bar page by key: Alt with the arrows moves a row, Delete removes it, Ctrl+Z brings it back, Ctrl+D copies it and
 * Esc lets it go. The focus is an item's, by its id: wherever a key or a choice puts the row, the focus is on it there.
 * While text is being typed the keys are the field's.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w1200dp-h800dp-mdpi")
class BarKeysTest {
    @get:Rule val rule = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var before: BarConfig
    private lateinit var inputMode: InputModeManager
    private var selected by mutableStateOf<String?>(null)

    @Before fun env() { Env.init(app); before = Store.config.value; Undo.last.value = null }
    @After fun forget() { Store.update { before }; Undo.last.value = null }

    /** The page with [select] selected and, as someone working by keyboard has it, the focus on its row. */
    private fun page(select: String, vararg items: ItemConfig): BarConfig {
        val layout = BarConfig(items = items.toList(), position = Position.RIGHT)
        Store.update { layout }
        selected = select
        rule.setContent {
            inputMode = LocalInputModeManager.current
            SettingsTheme(dark = false) { BarPage(selected, onSelect = { selected = it }) }
        }
        // In touch mode nothing that takes a click takes the focus.
        rule.runOnIdle { assertTrue(inputMode.requestInputMode(InputMode.Keyboard)) }
        row(select).requestFocus()
        row(select).assertIsFocused()
        return layout
    }

    private fun item(id: String, type: String, area: Position = Position.RIGHT) = ItemConfig(id, type, area = area)
    private val three = arrayOf(item("a", "clock"), item("b", "memory"), item("c", "battery"))

    private fun row(id: String) = rule.onNodeWithTag("row:$id")
    private fun now(id: String) = Store.config.value.items.first { it.id == id }
    private fun ids() = Store.config.value.items.map { it.id }
    private fun str(id: Int, vararg args: Any) = app.getString(id, *args)
    private fun press(key: Key) { rule.onNodeWithTag("bar-list").performKeyInput { pressKey(key) }; rule.waitForIdle() }
    private fun press(with: Key, key: Key) { rule.onNodeWithTag("bar-list").performKeyInput { withKeyDown(with) { pressKey(key) } }; rule.waitForIdle() }

    @Test fun altWithAnArrowMovesTheRowAndAcrossTheLineItKeepsTheFocusAndTheSelection() {
        page("b", *three)
        press(Key.AltLeft, Key.DirectionUp)
        assertEquals(listOf("b", "a", "c"), ids())
        row("b").assertIsFocused()
        press(Key.AltLeft, Key.DirectionUp)
        assertEquals("the first row has nowhere to go", listOf("b", "a", "c"), ids())

        press(Key.AltLeft, Key.DirectionDown)
        press(Key.AltLeft, Key.DirectionDown)
        assertEquals(listOf("a", "c", "b"), ids())
        assertEquals(false, now("b").whenActive)
        // The next step is across the line: the row is the same item under it, with the focus and the selection.
        press(Key.AltLeft, Key.DirectionDown)
        assertTrue("under the line it waits for its rule", now("b").whenActive)
        assertEquals(Section.HIDDEN, now("b").section)
        row("b").assertIsFocused()
        row("b").assertIsSelected()
        assertEquals("b", selected)
        rule.onNodeWithTag("options").assertExists()

        press(Key.AltLeft, Key.DirectionUp)
        assertEquals(Section.SHOWN, now("b").section)
        row("b").assertIsFocused()
        row("b").assertIsSelected()
    }

    @Test fun afterAnAreaChangeThatPutsTheRowUnderAnotherHeadTheSameItemHasTheFocusAndIsSelected() {
        page("b", item("a", "clock", Position.LEFT), item("b", "memory"), item("c", "battery"))
        // The choice is made without taking the focus from the row, as a screen reader's action does.
        rule.onNode(hasText(str(R.string.areas_left)) and hasAnyAncestor(hasTestTag("area"))).performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        assertEquals(Position.LEFT, now("b").area)
        row("b").assertIsFocused()
        row("b").assertIsSelected()
        assertEquals("b", selected)
        // And the keys still act on it there.
        press(Key.AltLeft, Key.DirectionUp)
        assertEquals(listOf("b", "a", "c"), ids())
        row("b").assertIsFocused()
    }

    @Test fun aRowThatMayNotGoWhereTheKeyAsksStaysAndThePageSaysWhy() {
        val layout = page("s", item("a", "clock"), item("s", "sound"))
        press(Key.AltLeft, Key.DirectionDown)
        assertEquals(layout, Store.config.value)
        rule.onNode(hasText(str(R.string.visibility_no_rule_drop, str(R.string.item_sound_title)))).assertExists()
        row("s").assertIsFocused()
        // The reason goes with the next thing done.
        press(Key.AltLeft, Key.DirectionUp)
        assertEquals(listOf("s", "a"), ids())
        rule.onNode(hasText(str(R.string.visibility_no_rule_drop, str(R.string.item_sound_title)))).assertDoesNotExist()
    }

    @Test fun nextMeetingStaysFirstAndThePageSaysSo() {
        val layout = page("e", item("e", "event"), item("a", "clock"))
        press(Key.AltLeft, Key.DirectionDown)
        assertEquals(layout, Store.config.value)
        rule.onNode(hasText(str(R.string.order_pinned_said, str(R.string.areas_right)))).assertExists()
    }

    @Test fun deleteRemovesTheRowAndCtrlZBringsItBackWithTheFocus() {
        val layout = page("b", *three)
        press(Key.Delete)
        assertEquals(listOf("a", "c"), ids())
        assertNull(selected)
        rule.onNodeWithTag("slip").assertExists()
        // The focus is on the row that took its place.
        row("c").assertIsFocused()

        press(Key.CtrlLeft, Key.Z)
        assertEquals(layout, Store.config.value)
        rule.onNodeWithTag("slip").assertDoesNotExist()
        row("b").assertIsFocused()

        press(Key.Backspace)
        assertEquals(listOf("a", "c"), ids())
        press(Key.CtrlLeft, Key.Z)
        assertEquals(layout, Store.config.value)
    }

    @Test fun ctrlDCopiesTheItemAndEscLetsItGo() {
        page("a", *three)
        press(Key.CtrlLeft, Key.D)
        val ids = ids()
        assertEquals(4, ids.size)
        assertEquals("a", ids[0])
        assertEquals("clock", now(ids[1]).type)
        assertEquals("the copy is selected", ids[1], selected)
        row(ids[1]).assertIsFocused()

        press(Key.Escape)
        assertNull(selected)
        rule.onNodeWithTag("options").assertDoesNotExist()
        assertEquals("Esc changes nothing in the bar", ids, ids())
        // With nothing selected Esc is not the page's.
        press(Key.Escape)
        assertEquals(ids, ids())
    }

    @Test fun whileTextIsBeingTypedInOptionsTheKeysAreTheFields() {
        val layout = page("t", item("t", "text"), item("c", "clock"))
        val field = rule.onAllNodes(hasSetTextAction() and hasAnyAncestor(hasTestTag("options")))[0]
        field.requestFocus()
        field.assertIsFocused()
        rule.waitForIdle()

        press(Key.Delete)
        press(Key.Backspace)
        press(Key.CtrlLeft, Key.D)
        press(Key.AltLeft, Key.DirectionDown)
        press(Key.Escape)
        // The field may have taken a key as an edit of its text; the bar has what it had, where it had it.
        assertEquals(layout, Store.config.value.let { c -> c.copy(items = c.items.map { it.copy(options = emptyMap()) }) })
        assertEquals("t", selected)
        rule.onNodeWithTag("options").assertExists()
        rule.onNodeWithTag("slip").assertDoesNotExist()
        field.assertIsFocused()
    }

    @Test fun ctrlZDoesNothingToTheBarWhileTextIsBeingTyped() {
        page("c", item("t", "text"), item("c", "clock"))
        press(Key.Delete)
        assertEquals(listOf("t"), ids())
        row("t").performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        val field = rule.onAllNodes(hasSetTextAction() and hasAnyAncestor(hasTestTag("options")))[0]
        field.requestFocus()
        field.assertIsFocused()
        press(Key.CtrlLeft, Key.Z)
        assertEquals("what was removed stays removed", listOf("t"), ids())
        rule.onNodeWithTag("slip").assertExists()
    }
}
