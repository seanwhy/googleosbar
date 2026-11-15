package com.meridian11.books.bentobar.compose

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.withKeyDown
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.data.BarConfig
import com.meridian11.books.bentobar.data.Display
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
 * Areas on the Bar page, there only when they are used: with one lane on screen nothing of them but "Add an area";
 * with a second, opened or in the layout, the lanes, Alt with ← or →, Area as the three-way choice, the library's
 * "Add to" and the brackets under the bar's picture. A lane whose last item leaves closes, and the page says so with Undo.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w1200dp-h800dp-mdpi")
class AreasTest {
    @get:Rule val rule = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var before: BarConfig
    private lateinit var inputMode: InputModeManager
    private var selected by mutableStateOf<String?>(null)
    private var onBar by mutableStateOf(true)

    @Before fun env() { Env.init(app); before = Store.config.value; Undo.last.value = null }
    @After fun forget() { Store.update { before }; Undo.last.value = null }

    private fun page(select: String?, vararg items: ItemConfig): BarConfig {
        val layout = BarConfig(items = items.toList(), position = Position.RIGHT)
        Store.update { layout }
        selected = select
        rule.setContent {
            inputMode = LocalInputModeManager.current
            SettingsTheme(dark = false) { if (onBar) BarPage(selected, onSelect = { selected = it }) }
        }
        rule.runOnIdle { assertTrue(inputMode.requestInputMode(InputMode.Keyboard)) }
        if (select != null) { row(select).requestFocus(); row(select).assertIsFocused() }
        return layout
    }

    private fun item(id: String, type: String, area: Position = Position.RIGHT) = ItemConfig(id, type, area = area)
    private val three = arrayOf(item("a", "clock"), item("b", "memory"), item("c", "battery"))

    private fun row(id: String) = rule.onNodeWithTag("row:$id")
    private fun left(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.left
    private fun now(id: String) = Store.config.value.items.first { it.id == id }
    private fun str(id: Int, vararg args: Any) = app.getString(id, *args)
    private fun press(key: Key, on: String = "bar-list") { rule.onNodeWithTag(on).performKeyInput { pressKey(key) }; rule.waitForIdle() }
    private fun press(with: Key, key: Key) { rule.onNodeWithTag("bar-list").performKeyInput { withKeyDown(with) { pressKey(key) } }; rule.waitForIdle() }
    private fun click(tag: String) { rule.onNodeWithTag(tag).performClick(); rule.waitForIdle() }
    private fun open(area: Position) { click("add-area"); click("add-area:$area") }
    private fun areaParts() = listOf(R.string.areas_left, R.string.areas_middle, R.string.areas_right)
        .map { rule.onAllNodes(hasText(str(it)) and hasAnyAncestor(hasTestTag("area"))).fetchSemanticsNodes().size }
    private fun toLibrary() {
        rule.onNode(hasText(str(R.string.page_add)) and hasAnyAncestor(hasTestTag("panel-switch"))).performClick()
        rule.waitForIdle()
    }
    private fun addTo(area: Int) = rule.onNode(hasContentDescription("${str(R.string.areas_add_to)} ${str(area)}") and hasAnyAncestor(hasTestTag("add-to")))
    private fun add(type: String) { rule.onNodeWithTag("add:$type").performScrollTo(); rule.onNodeWithTag("add:$type").performClick(); rule.waitForIdle() }
    private fun heads() = listOf(R.string.areas_left_where, R.string.areas_middle_where, R.string.areas_right_where)
        .map { rule.onAllNodes(hasText(str(it), substring = true) and hasAnyAncestor(hasTestTag("bar-list"))).fetchSemanticsNodes().size }

    @Test fun withOneLaneThereIsNothingOfAreasButAddAnAreaAndTheKeysChangeNothing() {
        val layout = page("b", *three)
        rule.onNodeWithTag("add-area").assertExists()
        assertEquals("no lane has a head", listOf(0, 0, 0), heads())
        for (area in Position.entries) {
            rule.onNodeWithTag("bracket:$area").assertDoesNotExist()
            rule.onNodeWithTag("lane:$area").assertDoesNotExist()
            rule.onNodeWithTag("add-to:$area").assertDoesNotExist()
        }
        assertEquals("Area is the menu at the end of the options, with the one area in use", listOf(0, 0, 1), areaParts())
        press(Key.AltLeft, Key.DirectionLeft)
        press(Key.AltLeft, Key.DirectionRight)
        assertEquals(layout, Store.config.value)
        assertNull("and nothing was kept to undo", Undo.last.value)
        row("b").assertIsFocused()
        toLibrary()
        rule.onNodeWithTag("library").assertExists()
        rule.onNodeWithTag("add-to").assertDoesNotExist()
    }

    @Test fun addAnAreaByKeyOpensTheEmptyLaneWithTheFocusAndAltWithTheArrowMovesARowThere() {
        val layout = page("b", *three)
        // A keyboard is in use: the menu's window is not in touch mode either.
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        rule.onNodeWithTag("add-area").requestFocus()
        press(Key.Enter, on = "add-area")
        // The menu is a window of its own, and its keys go to it. Opened by key, its first entry has the focus.
        rule.onNodeWithTag("add-area:LEFT").assertIsFocused()
        rule.onNodeWithTag("add-area:RIGHT").assertDoesNotExist()
        rule.onNodeWithTag("add-area:CENTER").performKeyInput { pressKey(Key.DirectionDown) }
        rule.onNodeWithTag("add-area:CENTER").assertIsFocused()
        rule.onNodeWithTag("add-area:CENTER").performKeyInput { pressKey(Key.DirectionUp); pressKey(Key.Enter) }
        rule.waitForIdle()

        assertEquals("opening a lane changes nothing in the bar", layout, Store.config.value)
        val lane = rule.onNodeWithTag("lane:LEFT")
        lane.assertIsFocused()
        assertEquals(str(R.string.areas_empty_new_spoken, str(R.string.areas_left), "Alt+←"),
            lane.fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString())
        assertEquals("both lanes have their heads", listOf(1, 0, 1), heads())
        // Area is the three-way choice as soon as the empty lane is on screen.
        assertEquals(listOf(1, 1, 1), areaParts())
        rule.onNode(hasText(str(R.string.areas_right)) and hasAnyAncestor(hasTestTag("area"))).assertIsSelected()

        row("b").requestFocus()
        press(Key.AltLeft, Key.DirectionLeft)
        assertEquals(Position.LEFT, now("b").area)
        row("b").assertIsFocused()
        row("b").assertIsSelected()
        rule.onNodeWithTag("lane:LEFT").assertDoesNotExist()
        assertTrue("the row stands in Left's lane, left of Right's rows", left("row:b") < left("row:a"))
        // At the last lane on that side the key has nowhere to go.
        val there = Store.config.value
        press(Key.AltLeft, Key.DirectionLeft)
        assertEquals(there, Store.config.value)
        // And back: the top of Right, where the bar's picture has it beside Left.
        press(Key.AltLeft, Key.DirectionRight)
        assertEquals(Position.RIGHT, now("b").area)
        assertEquals(listOf("b", "a", "c"), Store.config.value.items.map { it.id })
        row("b").assertIsFocused()
    }

    @Test fun withAnOpenedLaneTheLibraryAddsToItAndTheChoiceCanBeChanged() {
        page(null, *three)
        open(Position.LEFT)
        toLibrary()
        rule.onNodeWithTag("add-to").assertExists()
        addTo(R.string.areas_left).assertIsSelected()
        add("countdown")
        assertEquals(Position.LEFT, Store.config.value.items.single { it.type == "countdown" }.area)
        rule.onNodeWithTag("lane:LEFT").assertDoesNotExist()

        addTo(R.string.areas_right).performClick()
        rule.waitForIdle()
        add("storage")
        assertEquals(Position.RIGHT, Store.config.value.items.single { it.type == "storage" }.area)
    }

    @Test fun aBracketIsNeverNarrowerThanItsWordAndTheCaptionStandsClearOfTheBrackets() {
        page(null, item("a", "clock", Position.LEFT), ItemConfig("b", "caffeine", display = Display.ICON, area = Position.CENTER), item("c", "battery"))
        fun box(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        val strip = box("strip:CENTER")
        val bracket = box("bracket:CENTER")
        // One icon's strip is narrower than "Middle": the bracket is the word's, with a short line on each side, around the strip's middle.
        assertTrue("wider than the strip and its reach: ${bracket.width} for ${strip.width}", bracket.width > strip.width + 12f + 8f)
        assertEquals(strip.center.x, bracket.center.x, 1f)
        // A wide strip's bracket stays the strip's.
        assertEquals(box("strip:LEFT").width + 12f, box("bracket:LEFT").width, 1f)
        // The caption is a line of its own under them, not a second line of theirs.
        for (area in Position.entries) assertTrue("$area", box("preview-line").top >= box("bracket:$area").bottom + 8f)
    }

    @Test fun theLibraryScrollsUnderAHairlineBelowAddToAndWithOneLaneThereIsNeither() {
        page(null, *three)
        toLibrary()
        rule.onNodeWithTag("library-edge").assertDoesNotExist()
        rule.runOnIdle { onBar = false }
        rule.runOnIdle { onBar = true }
        open(Position.LEFT)
        toLibrary()
        fun box(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        val edge = box("library-edge")
        assertEquals("a hairline", 1f, edge.height, 0.01f)
        assertTrue("under Add to, with air", edge.top >= box("add-to").bottom + 8f)
        // The list begins at the line: a row scrolled up is cut there, under an edge.
        val list = rule.onNode(androidx.compose.ui.test.hasScrollAction() and hasAnyAncestor(hasTestTag("library"))).fetchSemanticsNode().boundsInRoot
        assertEquals(edge.bottom, list.top, 0.5f)
        assertEquals("as wide as the filter above it", box("filter").left, edge.left, 0.5f)
        assertEquals(box("filter").right, edge.right, 0.5f)
    }

    @Test fun aLaneClosesWhenItsLastItemMovesAwayAndUndoRestoresTheItemAndTheLane() {
        val layout = page("a", item("a", "clock", Position.LEFT), item("b", "memory"), item("c", "battery"))
        assertEquals(listOf(1, 0, 1), heads())
        rule.onNodeWithTag("bracket:LEFT").assertExists()
        rule.onNodeWithTag("bracket:RIGHT").assertExists()
        press(Key.AltLeft, Key.DirectionRight)
        assertEquals(Position.RIGHT, now("a").area)
        rule.onNode(hasText(str(R.string.areas_folded, str(R.string.areas_left)))).assertExists()
        rule.onNodeWithTag("fold").assertExists()
        rule.onNodeWithTag("moved-mark", useUnmergedTree = true).assertExists()
        assertEquals("one lane is left, and nothing of areas with it", listOf(0, 0, 0), heads())
        rule.onNodeWithTag("bracket:RIGHT").assertDoesNotExist()
        rule.onNodeWithTag("lane:LEFT").assertDoesNotExist()
        row("a").assertIsFocused()

        rule.pastANotesFirstMoment()
        rule.onNode(hasText(str(R.string.bar_undo)) and hasClickAction() and hasAnyAncestor(hasTestTag("fold"))).performClick()
        rule.waitForIdle()
        assertEquals(layout, Store.config.value)
        rule.onNodeWithTag("fold").assertDoesNotExist()
        assertEquals(listOf(1, 0, 1), heads())
        assertTrue(left("row:a") < left("row:b"))
        row("a").assertIsFocused()
    }

    @Test fun aLaneClosesWhenItsLastItemIsRemovedAndCtrlZBringsBothBack() {
        val layout = page("a", item("a", "clock", Position.LEFT), item("b", "memory"), item("c", "battery"))
        press(Key.Delete)
        assertEquals(listOf("b", "c"), Store.config.value.items.map { it.id })
        rule.onNode(hasText(str(R.string.areas_folded, str(R.string.areas_left)))).assertExists()
        rule.onNodeWithTag("slip").assertDoesNotExist()
        assertEquals(listOf(0, 0, 0), heads())

        press(Key.CtrlLeft, Key.Z)
        assertEquals(layout, Store.config.value)
        rule.onNodeWithTag("fold").assertDoesNotExist()
        assertEquals(listOf(1, 0, 1), heads())
        row("a").assertIsFocused()
    }

    @Test fun anItemLeavingALaneThatKeepsOthersClosesNothing() {
        page("a", item("a", "clock", Position.LEFT), item("d", "timer", Position.LEFT), item("b", "memory"))
        press(Key.AltLeft, Key.DirectionRight)
        assertEquals(Position.RIGHT, now("a").area)
        rule.onNodeWithTag("fold").assertDoesNotExist()
        assertEquals(listOf(1, 0, 1), heads())
    }

    // ---- Remove an area ----

    private fun trash(area: Position) = rule.onNodeWithTag("remove-area:$area")
    private fun plural(id: Int, count: Int, vararg args: Any) = app.resources.getQuantityString(id, count, *args)
    private fun noteUndo() = rule.onNode(hasText(str(R.string.bar_undo)) and hasClickAction() and hasAnyAncestor(hasTestTag("area-removed")))
    private val twoLanes = arrayOf(item("a", "clock", Position.LEFT), item("b", "memory"), item("d", "timer", Position.LEFT).copy(section = Section.HIDDEN, whenActive = true),
        item("c", "battery"), item("e", "cpu", Position.LEFT))

    @Test fun withSeveralLanesEachHeadEndsInATrashThatSaysWhatItTakesAndWithOneLaneThereIsNone() {
        page("b", *twoLanes)
        trash(Position.LEFT).assert(hasContentDescription(plural(R.plurals.areas_remove, 3, str(R.string.areas_left), 3)) and hasClickAction())
        assertEquals("Remove Left and its 3 items", plural(R.plurals.areas_remove, 3, str(R.string.areas_left), 3))
        trash(Position.RIGHT).assert(hasContentDescription("Remove Right and its 2 items"))
        trash(Position.CENTER).assertDoesNotExist()
        // The head's own end: after the name, and with one item it says so.
        assertTrue(left("remove-area:LEFT") >= rule.onNode(hasText(str(R.string.areas_left)) and hasAnyAncestor(hasTestTag("bar-list"))).fetchSemanticsNode().boundsInRoot.right)
        press(Key.Delete)
        trash(Position.RIGHT).assert(hasContentDescription("Remove Right and its item"))
        press(Key.CtrlLeft, Key.Z)
        // One lane: nothing of areas, so no trash either.
        rule.runOnUiThread { Store.update { c -> c.copy(items = c.items.map { it.copy(area = Position.RIGHT) }) } }
        rule.waitForIdle()
        assertEquals(listOf(0, 0, 0), heads())
        for (area in Position.entries) trash(area).assertDoesNotExist()
    }

    @Test fun removeAnAreaTakesEveryItemOfItInOneStepSaysSoAboveTheListAndCtrlZBringsThemAllBackWithTheLane() {
        val layout = page("b", *twoLanes)
        trash(Position.LEFT).performClick()
        rule.waitForIdle()
        assertEquals(listOf("b", "c"), Store.config.value.items.map { it.id })
        assertEquals("one step, which says what it was", Undo.Kept(layout, Store.config.value, area = Position.LEFT), Undo.last.value)
        rule.onNodeWithTag("area-removed").assertExists()
        rule.onNode(hasText("Removed Left and its 3 items") and hasAnyAncestor(hasTestTag("area-removed"))).assertExists()
        assertEquals("a polite live region", LiveRegionMode.Polite, rule.onNode(hasText("Removed Left and its 3 items")).fetchSemanticsNode().config.getOrNull(SemanticsProperties.LiveRegion))
        // Not also "Left is empty now, so it closed", nor a slip for one of its rows.
        rule.onNodeWithTag("fold").assertDoesNotExist()
        rule.onNode(hasText(str(R.string.areas_folded, str(R.string.areas_left)))).assertDoesNotExist()
        rule.onNodeWithTag("slip").assertDoesNotExist()
        assertEquals("one lane is left, and nothing of areas with it", listOf(0, 0, 0), heads())
        for (area in Position.entries) trash(area).assertDoesNotExist()
        rule.onNodeWithTag("bracket:RIGHT").assertDoesNotExist()
        // The note is where a closed lane's slip stands: across the top, over the list's head.
        val note = rule.onNodeWithTag("area-removed").fetchSemanticsNode().boundsInRoot
        assertTrue(note.bottom <= rule.onNodeWithTag("row:b").fetchSemanticsNode().boundsInRoot.top)
        assertTrue(note.bottom <= rule.onNodeWithTag("add-area").fetchSemanticsNode().boundsInRoot.top + 1f)

        row("b").assertIsFocused()
        press(Key.CtrlLeft, Key.Z)
        assertEquals("every item back where it was, in its part and order", layout, Store.config.value)
        rule.onNodeWithTag("area-removed").assertDoesNotExist()
        assertEquals(listOf(1, 0, 1), heads())
        for (id in listOf("a", "d", "e")) assertTrue(left("row:$id") < left("row:b"))
        assertTrue("under the line again", rule.onNodeWithTag("row:d").fetchSemanticsNode().boundsInRoot.top > rule.onNodeWithTag("row:e").fetchSemanticsNode().boundsInRoot.top)
        assertNull(Undo.last.value)
    }

    @Test fun theNotesUndoBringsTheAreaBackTooAndASelectedItemThatWasInItIsLetGo() {
        val layout = page("a", *twoLanes)
        rule.onNodeWithTag("options", useUnmergedTree = true).assertExists()
        trash(Position.LEFT).performClick()
        rule.waitForIdle()
        assertNull("the selected item went with its area", selected)
        rule.onNodeWithTag("options", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag("library", useUnmergedTree = true).assertExists()
        rule.pastANotesFirstMoment()
        noteUndo().performClick()
        rule.waitForIdle()
        assertEquals(layout, Store.config.value)
        assertEquals(listOf(1, 0, 1), heads())
        assertNull("and it is not selected again by itself", selected)
        // An item of another area stays selected.
        click("row:b")
        trash(Position.LEFT).performClick()
        rule.waitForIdle()
        assertEquals("b", selected)
        rule.onNodeWithTag("options", useUnmergedTree = true).assertExists()
    }

    @Test fun anOpenedLaneThatIsStillEmptySimplyClosesWithNoNoteAndNoStepOfUndo() {
        val layout = page(null, *three)
        open(Position.CENTER)
        trash(Position.CENTER).assert(hasContentDescription("Remove Middle"))
        trash(Position.RIGHT).assert(hasContentDescription("Remove Right and its 3 items"))
        trash(Position.CENTER).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("lane:CENTER").assertDoesNotExist()
        assertEquals(listOf(0, 0, 0), heads())
        rule.onNodeWithTag("area-removed").assertDoesNotExist()
        rule.onNodeWithTag("fold").assertDoesNotExist()
        assertNull(Undo.last.value)
        assertEquals(layout, Store.config.value)
        // The library adds to the area in use again.
        toLibrary()
        rule.onNodeWithTag("add-to").assertDoesNotExist()
    }

    @Test fun byKeyTheKeyboardGoesToTheNotesUndoAndFromAnEmptyLaneBackToAddAnArea() {
        val layout = page("b", *twoLanes)
        trash(Position.LEFT).requestFocus()
        press(Key.Enter, on = "remove-area:LEFT")
        assertEquals(listOf("b", "c"), Store.config.value.items.map { it.id })
        noteUndo().assertIsFocused()
        press(Key.Enter, on = "area-removed")
        assertEquals(layout, Store.config.value)
        row("a").assertIsFocused()
        // An empty lane: it closes, and the keyboard is on what opened it.
        open(Position.CENTER)
        trash(Position.CENTER).requestFocus()
        press(Key.Enter, on = "remove-area:CENTER")
        rule.onNodeWithTag("lane:CENTER").assertDoesNotExist()
        rule.onNodeWithTag("add-area").assertIsFocused()
        assertEquals(layout, Store.config.value)
    }

    @Test fun theAreaWithTheBarsLastItemsLeavesTheEmptyBarWithItsNoteAndUndoBringsThemBack() {
        val layout = page(null, item("a", "clock", Position.LEFT), item("b", "memory", Position.LEFT))
        open(Position.CENTER)
        trash(Position.LEFT).performClick()
        rule.waitForIdle()
        assertEquals(BarConfig(position = Position.LEFT), Store.config.value)
        rule.onNode(hasText("Removed Left and its 2 items")).assertExists()
        rule.onNode(hasText(str(R.string.bar_empty))).assertExists()
        // The empty bar has one lane: the one opened beside it closed too, and nothing of areas is left.
        rule.onNodeWithTag("lane:CENTER").assertDoesNotExist()
        assertEquals(listOf(0, 0, 0), heads())
        for (area in Position.entries) trash(area).assertDoesNotExist()
        rule.pastANotesFirstMoment()
        noteUndo().performClick()
        rule.waitForIdle()
        assertEquals(layout, Store.config.value)
        rule.onNodeWithTag("area-removed").assertDoesNotExist()
    }

    @Test fun theEmptyBarsOwnLaneHasNoTrashBesideAnOpenedOne() {
        page(null)
        open(Position.LEFT)
        trash(Position.LEFT).assertExists()
        trash(Position.RIGHT).assertDoesNotExist()
        // Nor a lane that only a removed row's slip keeps on screen.
        trash(Position.LEFT).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("lane:LEFT").assertDoesNotExist()
    }

    @Test fun anOpenedLaneThatWasNeverUsedIsGoneAfterLeavingTheBarTab() {
        val layout = page(null, *three)
        open(Position.CENTER)
        rule.onNodeWithTag("lane:CENTER").assertExists()
        onBar = false
        rule.waitForIdle()
        onBar = true
        rule.waitForIdle()
        rule.onNodeWithTag("lane:CENTER").assertDoesNotExist()
        assertEquals(listOf(0, 0, 0), heads())
        assertEquals(0, rule.onAllNodesWithTag("add-to").fetchSemanticsNodes().size)
        assertEquals(layout, Store.config.value)
    }
}
