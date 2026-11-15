package com.meridian11.books.bentobar.compose

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.AccessibilityManager
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.withKeyDown
import androidx.test.core.app.ApplicationProvider
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.data.BarConfig
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.data.Position
import com.meridian11.books.bentobar.data.Store
import com.meridian11.books.bentobar.data.oneArea
import com.meridian11.books.bentobar.data.putAllIn
import com.meridian11.books.bentobar.data.without
import com.meridian11.books.bentobar.items.Env
import com.meridian11.books.bentobar.ui.BarKeys
import com.meridian11.books.bentobar.ui.BarPage
import com.meridian11.books.bentobar.ui.LocalWindowLayout
import com.meridian11.books.bentobar.ui.LookPage
import com.meridian11.books.bentobar.ui.Motion
import com.meridian11.books.bentobar.ui.SettingsTheme
import com.meridian11.books.bentobar.ui.Undo
import com.meridian11.books.bentobar.ui.windowLayout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A note with Undo stands three seconds ([Motion.NOTE]) and then goes, with the clock held: the slip in a removed
 * row's place, the one for a closed lane, the library's "Added", Look's line. The pointer over it or the keyboard in
 * it holds it. Only the note goes: Ctrl+Z still takes the change back, and a note that went does not come again.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w1200dp-h800dp-mdpi")
class NoteTimeTest {
    @get:Rule val rule = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var before: BarConfig
    private lateinit var inputMode: InputModeManager
    private var selected by mutableStateOf<String?>(null)
    private var shown by mutableStateOf(true)
    private var look by mutableStateOf(false)

    @Before fun env() { Env.init(app); before = Store.config.value; Undo.last.value = null; Undo.over.value = null }
    @After fun forget() { Store.update { before }; Undo.last.value = null; Undo.over.value = null }

    private fun item(id: String, type: String, area: Position = Position.RIGHT) = ItemConfig(id, type, area = area)
    private val three = listOf(item("a", "clock"), item("b", "memory"), item("c", "battery"))
    private val lanes = listOf(item("a", "clock", Position.LEFT), item("b", "memory"), item("c", "battery"))

    /** The Bar page (or Look) with the clock held. [times]: what a person's settings make of a time to read and act in. */
    private fun page(items: List<ItemConfig> = three, still: Boolean = false, times: Int = 1) {
        Store.update { BarConfig(items = items, position = Position.RIGHT, noAnimations = still) }
        rule.mainClock.autoAdvance = false
        val manager = object : AccessibilityManager {
            override fun calculateRecommendedTimeoutMillis(originalTimeoutMillis: Long, containsIcons: Boolean, containsText: Boolean, containsControls: Boolean): Long {
                assertTrue("a note is words and a control", containsText && containsControls)
                return originalTimeoutMillis * times
            }
        }
        rule.setContent {
            inputMode = LocalInputModeManager.current
            CompositionLocalProvider(LocalWindowLayout provides windowLayout(1200), LocalAccessibilityManager provides manager) {
                SettingsTheme(dark = false) { Box(Modifier.fillMaxSize()) { if (look) LookPage() else if (shown) BarPage(selected, onSelect = { selected = it }) } }
            }
        }
        frames()
    }

    /** Another tab and back, as a window made again (a rotation, a new size) does it: the page is composed anew. */
    private fun show(page: Boolean) { rule.runOnUiThread { shown = page }; rule.waitForIdle(); frames(4) }
    private fun frames(n: Int = 2) = repeat(n) { rule.mainClock.advanceTimeByFrame() }
    private fun pass(ms: Long) { rule.mainClock.advanceTimeBy(ms); frames() }
    private fun keyboard() { rule.runOnUiThread { assertTrue(inputMode.requestInputMode(InputMode.Keyboard)) }; frames() }
    private fun str(id: Int, vararg args: Any) = app.getString(id, *args)
    private fun tag(tag: String) = rule.onNodeWithTag(tag)
    private val places = mutableMapOf<String, Offset>()
    /** Where the middle of [tag] is, or with [gone] where it was when last asked. */
    private fun tag(tag: String, gone: Boolean): Offset = if (gone) places.getValue(tag) else tag(tag).fetchSemanticsNode().boundsInRoot.center.also { places[tag] = it }
    private fun ids() = Store.config.value.items.map { it.id }
    private fun at(id: String): Offset = tag("row:$id").fetchSemanticsNode().boundsInRoot.topLeft
    private fun remove(id: String) { rule.runOnUiThread { assertTrue(Undo.change(removed = id) { it.without(id) }) }; frames() }
    private fun undoIn(tag: String) = rule.onNode(hasText(str(R.string.bar_undo)) and hasClickAction() and hasAnyAncestor(hasTestTag(tag)))
    /**
     * The pointer comes to a note's part as a hand brings it: it moves there, so the part sees it at more than one place.
     * What the part then writes is the window's to pass on, between the frames this test counts by hand.
     */
    private fun point(part: SemanticsNodeInteraction) {
        part.performMouseInput { moveTo(center - Offset(2f, 0f)) }
        part.performMouseInput { moveTo(center) }
        rule.waitForIdle()
        frames()
    }
    private fun ctrlZ() { tag("bar-list").performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Z) } }; frames() }

    /** The note [tag] is there until just before its time and gone when the time is up. */
    private fun standsItsTime(tag: String, time: Long = Motion.NOTE.toLong()) {
        tag(tag).assertExists()
        // Two frames have passed since it came.
        rule.mainClock.advanceTimeBy(time - 100)
        tag(tag).assertExists()
        pass(100)
        tag(tag).assertDoesNotExist()
    }

    @Test fun aRemovedRowsSlipIsGoneAtThreeSecondsAndNotAtTwoPointNine() {
        page()
        remove("b")
        standsItsTime("slip")
        assertEquals("only the note went", listOf("a", "c"), ids())
        assertNotNull("the change is still the one to undo", Undo.last.value)
    }

    @Test fun ctrlZAfterTheNoteWentStillBringsTheItemBackAndTheSystemsListStillShowsUndo() {
        page()
        keyboard()
        tag("row:a").requestFocus()
        frames()
        remove("b")
        pass(Motion.NOTE + 100L)
        tag("slip").assertDoesNotExist()
        assertTrue(BarKeys.UNDO in BarKeys.listed())
        ctrlZ()
        assertEquals(listOf("a", "b", "c"), ids())
        tag("row:b").assertExists()
        tag("slip").assertDoesNotExist()
    }

    @Test fun thePointerOverTheNoteHoldsItAndTheTimeStartsAgainWhenItLeaves() {
        page()
        remove("b")
        pass(2000)
        point(tag("slip"))
        frames()
        pass(10_000)
        tag("slip").assertExists()
        // Over its Undo it is over the note too.
        point(undoIn("slip"))
        pass(10_000)
        tag("slip").assertExists()
        tag("row:a").performMouseInput { moveTo(center) }
        frames()
        standsItsTime("slip")
    }

    @Test fun theKeyboardInTheNoteHoldsItAndTheTimeStartsAgainWhenItLeaves() {
        page()
        keyboard()
        remove("b")
        pass(2000)
        undoIn("slip").requestFocus()
        frames()
        pass(10_000)
        tag("slip").assertExists()
        undoIn("slip").assertIsFocused()
        tag("row:c").requestFocus()
        frames()
        standsItsTime("slip")
        // The slip's neighbour had the keyboard and keeps it.
        tag("row:c").assertIsFocused()
    }

    @Test fun aNewChangeReplacesTheNoteAndHasItsOwnTime() {
        page()
        remove("b")
        pass(2000)
        remove("c")
        assertEquals(1, rule.onAllNodes(hasTestTag("slip")).fetchSemanticsNodes().size)
        rule.onNode(hasText(str(R.string.bar_removed, str(R.string.item_battery_title))) and hasAnyAncestor(hasTestTag("slip"))).assertExists()
        standsItsTime("slip")
    }

    @Test fun theRowsUnderTheSlipTravelUpIntoItsPlace() {
        page()
        remove("b")
        val under = at("c")
        pass(Motion.NOTE - 100L)
        assertEquals(under, at("c"))
        // The note goes, and the row under it sets off.
        rule.mainClock.advanceTimeBy(100)
        var steps = 0
        while (rule.onAllNodes(hasTestTag("slip")).fetchSemanticsNodes().isNotEmpty() && steps++ < 4) rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeBy(Motion.NEIGHBOURS / 2L)
        val half = at("c")
        assertTrue("half way it is between its places: $under, $half", half.y < under.y)
        rule.mainClock.advanceTimeBy(1000)
        val rest = at("c")
        assertTrue("$half, $rest", rest.y < half.y)
        assertEquals("a row up, into the slip's place", at("a").y + (under.y - at("a").y) / 2, rest.y, 1f)
    }

    @Test fun withNoAnimationsTheRowsUnderTheSlipAreThereAtOnce() {
        page(still = true)
        remove("b")
        val under = at("c")
        pass(Motion.NOTE + 100L)
        tag("slip").assertDoesNotExist()
        val then = at("c")
        assertTrue(then.y < under.y)
        rule.mainClock.advanceTimeBy(1000)
        assertEquals(then, at("c"))
    }

    @Test fun aNoteThatWentDoesNotComeAgainWhenThePageIsMadeAgain() {
        page()
        remove("b")
        pass(Motion.NOTE + 100L)
        tag("slip").assertDoesNotExist()
        show(false)
        tag("bar-list").assertDoesNotExist()
        show(true)
        tag("bar-list").assertExists()
        tag("slip").assertDoesNotExist()
        pass(Motion.NOTE + 100L)
        tag("slip").assertDoesNotExist()
        keyboard()
        tag("row:a").requestFocus()
        frames()
        ctrlZ()
        assertEquals(listOf("a", "b", "c"), ids())
    }

    @Test fun aNoteThatWasNotOnScreenHasItsTimeWhenItIs() {
        page()
        remove("b")
        pass(1000)
        show(false)
        pass(10_000)
        show(true)
        standsItsTime("slip")
    }

    @Test fun theTimeIsTheOneAPersonsSettingsAskFor() {
        page(times = 4)
        remove("b")
        standsItsTime("slip", time = 4L * Motion.NOTE)
    }

    private fun exploring(on: Boolean) {
        shadowOf(app.getSystemService(android.view.accessibility.AccessibilityManager::class.java)).setTouchExplorationEnabled(on)
        rule.waitForIdle()
        frames()
    }

    /**
     * Someone who finds the note by touch, with a screen reader, has no pointer over it and no keyboard in it: while
     * touch exploration is on a note stays until the next change, as it did before notes went by themselves.
     */
    @Test fun whileTouchExplorationIsOnANoteStaysUntilTheNextChange() {
        exploring(true)
        try {
            page()
            remove("b")
            pass(10L * Motion.NOTE)
            tag("slip").assertExists()
            rule.onNode(hasText(str(R.string.bar_removed, str(R.string.item_memory_title))) and hasAnyAncestor(hasTestTag("slip"))).assertExists()
            // The next change has the note, and it stays as long.
            remove("c")
            assertEquals(1, rule.onAllNodes(hasTestTag("slip")).fetchSemanticsNodes().size)
            rule.onNode(hasText(str(R.string.bar_removed, str(R.string.item_battery_title))) and hasAnyAncestor(hasTestTag("slip"))).assertExists()
            pass(10L * Motion.NOTE)
            tag("slip").assertExists()
            // Its Undo is still there to be found, and works.
            undoIn("slip").performSemanticsAction(SemanticsActions.OnClick)
            frames()
            assertEquals(listOf("a", "c"), ids())
        } finally {
            exploring(false)
        }
    }

    @Test fun aNoteHeldForTouchExplorationHasItsTimeOnceExplorationIsOffAndOneThatStoodIsHeldWhenItComesOn() {
        try {
            page()
            remove("b")
            pass(2000)
            exploring(true)
            pass(10L * Motion.NOTE)
            tag("slip").assertExists()
            exploring(false)
            standsItsTime("slip")
        } finally {
            exploring(false)
        }
    }

    @Test fun looksLineIsHeldForTouchExplorationToo() {
        exploring(true)
        try {
            look = true
            page(lanes)
            rule.onNode(hasText(str(R.string.areas_middle)) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)).performClick()
            frames()
            pass(10L * Motion.NOTE)
            tag("look-moved").assertExists()
        } finally {
            exploring(false)
        }
    }

    @Test fun aClosedLanesSlipGoesTheSameWayAndTheLaneStaysClosed() {
        page(lanes)
        keyboard()
        tag("row:b").requestFocus()
        frames()
        remove("a")
        tag("slip").assertDoesNotExist()
        rule.onNode(hasText(str(R.string.areas_folded, str(R.string.areas_left)))).assertExists()
        standsItsTime("fold")
        // What went is the note: no slip stands in its stead in a lane that is gone, and the lane is not back.
        tag("slip").assertDoesNotExist()
        rule.onNode(hasText(str(R.string.areas_left_where), substring = true) and hasAnyAncestor(hasTestTag("bar-list"))).assertDoesNotExist()
        ctrlZ()
        assertEquals(lanes, Store.config.value.items)
        tag("row:a").assertExists()
    }

    @Test fun aRemovedAreasNoteGoesTheSameWayAlsoUnderAPointerThatOnlyRestsWhereItCame() {
        page(lanes)
        keyboard()
        tag("row:a").requestFocus()
        frames()
        // The last lane's trash is where the note's Undo comes: the pointer that clicked rests on the note, and has not pointed at it.
        tag("remove-area:RIGHT", gone = false)
        tag("remove-area:RIGHT").performMouseInput { click() }
        frames()
        assertEquals(listOf("a"), ids())
        val pointer = tag("remove-area:RIGHT", gone = true)
        assertTrue("the pointer is where the note is now", tag("area-removed").fetchSemanticsNode().boundsInRoot.contains(pointer))
        tag("row:a").assertIsFocused()
        standsItsTime("area-removed")
        // The area stays removed, nothing says a lane closed, and its items can still be brought back.
        tag("fold").assertDoesNotExist()
        tag("slip").assertDoesNotExist()
        rule.onNode(hasText(str(R.string.areas_right_where), substring = true) and hasAnyAncestor(hasTestTag("bar-list"))).assertDoesNotExist()
        ctrlZ()
        assertEquals(lanes, Store.config.value.items)
        tag("row:b").assertExists()
    }

    /**
     * The last lane's trash is where the note's Undo comes, so the second press of a double click lands on Undo. A
     * note's Undo takes no pointer's press for the system's double-tap time after the note came; then it does.
     */
    @Test fun aDoubleClickOnALanesTrashRemovesTheAreaAndDoesNotTakeItBack() {
        page(lanes)
        val trash = tag("remove-area:RIGHT", gone = false)
        tag("remove-area:RIGHT").performMouseInput { click() }
        frames()
        assertEquals(listOf("a"), ids())
        assertTrue("the note's Undo is where the trash was", undoIn("area-removed").fetchSemanticsNode().boundsInRoot.contains(trash))
        pass(150)
        rule.onRoot().performMouseInput { click(trash) }
        frames()
        assertEquals("the second press of a double click is not an Undo", listOf("a"), ids())
        tag("area-removed").assertExists()
        // Past the double-tap time (300 ms here) the same press is one.
        pass(300)
        rule.onRoot().performMouseInput { click(trash) }
        frames()
        assertEquals(lanes, Store.config.value.items)
    }

    /** One rule for every note with Undo: the slip in a removed row's place, a closed lane's, the library's and Look's. */
    @Test fun noNotesUndoTakesAPointersPressInItsFirstMomentAndEachTakesTheKeyboardsAtOnce() {
        page(lanes)
        remove("b")
        pass(100)
        undoIn("slip").performMouseInput { click() }
        frames()
        assertEquals("the slip's", listOf("a", "c"), ids())
        // The keyboard is not held up: Enter on the focused Undo, and Ctrl+Z, at once.
        keyboard()
        undoIn("slip").requestFocus()
        frames()
        undoIn("slip").performKeyInput { pressKey(Key.Enter) }
        frames()
        assertEquals(lanes, Store.config.value.items)
        // A lane that closed with its last row.
        remove("a")
        pass(100)
        undoIn("fold").performMouseInput { click() }
        frames()
        assertEquals("a closed lane's", listOf("b", "c"), ids())
        ctrlZ()
        assertEquals(lanes, Store.config.value.items)
        // The library's "Added".
        tag("add:cpu").performScrollTo()
        frames()
        tag("add:cpu").performMouseInput { click() }
        frames()
        assertEquals(4, ids().size)
        pass(100)
        tag("library-undo").performMouseInput { click() }
        frames()
        assertEquals("the library's", 4, ids().size)
        pass(300)
        tag("library-undo").performMouseInput { click() }
        frames()
        assertEquals(lanes, Store.config.value.items)
    }

    @Test fun looksLineTakesNoPointersPressInItsFirstMomentEither() {
        look = true
        page(lanes)
        rule.runOnUiThread { assertTrue(Undo.change(allTo = Position.CENTER) { it.putAllIn(Position.CENTER) }) }
        frames()
        pass(100)
        undoIn("look-moved").performMouseInput { click() }
        frames()
        assertEquals(Position.CENTER, Store.config.value.oneArea())
        pass(300)
        undoIn("look-moved").performMouseInput { click() }
        frames()
        assertEquals(lanes, Store.config.value.items)
    }

    @Test fun aPointerThatMovesOnARemovedAreasNoteHoldsItAndSoDoesTheKeyboardInItsUndo() {
        page(lanes)
        keyboard()
        tag("row:a").requestFocus()
        frames()
        tag("remove-area:RIGHT").performMouseInput { click() }
        frames()
        pass(2000)
        // It moves, where it rested: now it points at the note.
        point(tag("area-removed"))
        frames()
        pass(10_000)
        tag("area-removed").assertExists()
        undoIn("area-removed").requestFocus()
        frames()
        tag("row:a").performMouseInput { moveTo(center) }
        frames()
        pass(10_000)
        tag("area-removed").assertExists()
        tag("row:a").requestFocus()
        frames()
        standsItsTime("area-removed")
    }

    @Test fun theLibrarysAddedWithItsUndoGoesAndTheItemStays() {
        page()
        keyboard()
        tag("row:a").requestFocus()
        frames()
        tag("add:cpu").performScrollTo()
        frames()
        tag("add:cpu").performClick()
        frames()
        assertEquals(4, ids().size)
        tag("library-undo").assertExists()
        standsItsTime("added")
        tag("library-undo").assertDoesNotExist()
        assertEquals(4, ids().size)
        ctrlZ()
        assertEquals(listOf("a", "b", "c"), ids())
    }

    @Test fun thePointerOrTheKeyboardOnTheLibrarysNoteHoldsItAndThePlusThatWasPressedDoesNot() {
        page()
        keyboard()
        tag("add:cpu").performScrollTo()
        frames()
        // The pointer is on the + it clicked and stays there, and the keyboard is there too: neither is in the note.
        tag("add:cpu").performMouseInput { moveTo(center) }
        tag("add:cpu").requestFocus()
        frames()
        tag("add:cpu").performMouseInput { click() }
        frames()
        standsItsTime("added")
        tag("add:cpu").performMouseInput { click() }
        frames()
        pass(1000)
        point(tag("added"))
        frames()
        pass(10_000)
        tag("added").assertExists()
        tag("library-undo").requestFocus()
        frames()
        tag("add:cpu").performMouseInput { moveTo(center) }
        frames()
        pass(10_000)
        tag("library-undo").assertIsFocused()
        tag("add:cpu").requestFocus()
        frames()
        standsItsTime("added")
    }

    @Test fun looksLineGoesAndItsChangeCanStillBeTakenBack() {
        look = true
        page(lanes)
        rule.onNode(hasText(str(R.string.areas_middle)) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)).performClick()
        frames()
        assertEquals(Position.CENTER, Store.config.value.oneArea())
        standsItsTime("look-moved")
        // What Ctrl+Z asks on Look, and what it does there.
        assertEquals(Position.CENTER, Undo.allTo(Undo.last.value, Store.config.value))
        rule.runOnUiThread { assertTrue(Undo.undo()) }
        assertEquals(lanes, Store.config.value.items)
    }
}
