package com.meridian11.books.bentobar.compose

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.Density
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.data.BarConfig
import com.meridian11.books.bentobar.data.Display
import com.meridian11.books.bentobar.data.HiddenMode
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.data.Position
import com.meridian11.books.bentobar.data.Section
import com.meridian11.books.bentobar.data.Store
import com.meridian11.books.bentobar.items.Env
import com.meridian11.books.bentobar.items.Items
import com.meridian11.books.bentobar.ui.BarPage
import com.meridian11.books.bentobar.ui.SettingsTheme
import com.meridian11.books.bentobar.ui.Undo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The selected item's options: Visibility with only what the chosen part needs, Order, Area in its place, and what an item
 * that waits for a permission or comes from a newer version shows. Drawn through the Bar page, as the window draws them.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w1200dp-h800dp-mdpi")
class ItemOptionsTest {
    @get:Rule val rule = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var before: BarConfig
    private var selected by mutableStateOf<String?>(null)

    @Before fun env() { Env.init(app); before = Store.config.value; Undo.last.value = null }
    @After fun forget() { Store.update { before }; Undo.last.value = null }

    private fun page(select: String, mode: HiddenMode, vararg items: ItemConfig): BarConfig {
        val layout = BarConfig(items = items.toList(), position = Position.RIGHT, hiddenMode = mode)
        Store.update { layout }
        selected = select
        rule.setContent { SettingsTheme(dark = false) { BarPage(selected, onSelect = { selected = it }) } }
        rule.waitForIdle()
        return layout
    }

    private fun item(id: String, type: String, section: Section = Section.SHOWN, area: Position = Position.RIGHT) = ItemConfig(id, type, section, area = area)
    private fun now(id: String) = Store.config.value.items.first { it.id == id }
    private fun str(id: Int, vararg args: Any) = app.getString(id, *args)

    private fun inTag(tag: String) = hasAnyAncestor(hasTestTag(tag))
    private fun visibility(text: Int) = rule.onNode(hasText(str(text)) and inTag("visibility"))
    private val behind get() = rule.onNode(hasContentDescription(str(R.string.visibility_behind).replace("‹", str(R.string.chevron_spoken))) and inTag("visibility"))
    private val sliders get() = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo) and inTag("options"))
    private fun button(description: Int) = rule.onNode(hasContentDescription(str(description)) and hasClickAction() and inTag("options"))
    private fun top(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top

    /** The lines of the text tagged [tag], each line's width, and how its style breaks them. */
    private fun lines(tag: String): Pair<List<Float>, LineBreak> {
        val results = mutableListOf<TextLayoutResult>()
        val node = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult) and (hasTestTag(tag) or inTag(tag)), useUnmergedTree = true).fetchSemanticsNodes().first()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        val layout = results.single()
        return (0 until layout.lineCount).map { layout.getLineRight(it) - layout.getLineLeft(it) } to layout.layoutInput.style.lineBreak
    }

    @Test fun theRulesSentenceBreaksIntoLinesOfLikeLength() {
        val layout = BarConfig(items = listOf(item("m", "memory", Section.HIDDEN).copy(whenActive = true), item("c", "clock")), position = Position.RIGHT, hiddenMode = HiddenMode.SHOW_ALL)
        Store.update { layout }
        selected = "m"
        rule.setContent {
            // Large text, so that the sentence takes two lines in the panel.
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.6f)) {
                SettingsTheme(dark = false) { BarPage(selected, onSelect = { selected = it }) }
            }
        }
        rule.waitForIdle()
        val (widths, how) = lines("rule")
        assertEquals(LineBreak.Heading, how)
        assertTrue("it takes more than one line here: $widths", widths.size > 1)
        assertTrue("no line is left with a word or two: $widths", widths.min() > widths.max() * 0.6f)
        // Behind ‹ the same sentence is the switch's label, broken the same way.
        rule.runOnUiThread { Store.update { it.copy(hiddenMode = HiddenMode.CLICK) } }
        rule.waitForIdle()
        assertEquals(LineBreak.Heading, lines("pops-out").second)
    }

    @Test fun underAlwaysThereIsNoRuleAndUnderOnlyWhenActiveItsSentenceWithItsValue() {
        page("m", HiddenMode.SHOW_ALL, item("m", "memory"), item("c", "clock"))
        visibility(R.string.visibility_always).assertIsSelected()
        visibility(R.string.visibility_when_active).assertIsNotSelected()
        rule.onNodeWithTag("rule").assertDoesNotExist()
        rule.onNodeWithTag("pops-out").assertDoesNotExist()
        sliders.assertCountEquals(0)

        visibility(R.string.visibility_when_active).performClick()
        rule.waitForIdle()
        assertTrue("the item waits for its rule", now("m").whenActive)
        visibility(R.string.visibility_when_active).assertIsSelected()
        val sentence = str(R.string.rule_shows, Items.of("memory")!!.trigger!!.sentence(now("m")))
        assertTrue(sentence, sentence.startsWith("Shows while memory use is above ") && "%" in sentence)
        rule.onNodeWithTag("rule").assertTextEquals(sentence)
        sliders.assertCountEquals(1)
        rule.onNodeWithTag("pops-out").assertDoesNotExist()

        visibility(R.string.visibility_always).performClick()
        rule.waitForIdle()
        assertEquals(Section.SHOWN, now("m").section)
        visibility(R.string.visibility_always).assertIsSelected()
        rule.onNodeWithTag("rule").assertDoesNotExist()
        sliders.assertCountEquals(0)
    }

    @Test fun withTheArrowTheSecondPartIsBehindItAndTheRuleIsASwitchThatSaysPopsOut() {
        page("m", HiddenMode.CLICK, item("m", "memory"), item("c", "clock"))
        visibility(R.string.visibility_always).assertIsSelected()
        rule.onNode(hasText(str(R.string.visibility_when_active))).assertDoesNotExist()
        rule.onNodeWithTag("pops-out").assertDoesNotExist()

        behind.performClick()
        rule.waitForIdle()
        assertEquals(Section.HIDDEN, now("m").section)
        behind.assertIsSelected()
        rule.onNodeWithTag("rule").assertDoesNotExist()
        val sentence = str(R.string.rule_pops_out, Items.of("memory")!!.trigger!!.sentence(now("m")))
        assertTrue(sentence, sentence.startsWith("Pops out while memory use is above "))
        rule.onNode(hasText(sentence) and (hasTestTag("pops-out") or inTag("pops-out"))).assertExists()
        rule.onNodeWithTag("pops-out").assertIsOff()
        sliders.assertCountEquals(0)

        rule.onNodeWithTag("pops-out").performClick()
        rule.waitForIdle()
        assertTrue(now("m").whenActive)
        assertEquals("it stays behind the arrow", Section.HIDDEN, now("m").section)
        rule.onNodeWithTag("pops-out").assertIsOn()
        sliders.assertCountEquals(1)
    }

    @Test fun aTypeWithNoRuleHasTheSecondPartDimmedWithItsReasonAndBehindTheArrowNoSwitch() {
        page("s", HiddenMode.SHOW_ALL, item("s", "sound"), item("c", "clock"))
        visibility(R.string.visibility_when_active).assertIsNotEnabled()
        rule.onNode(hasText(str(R.string.visibility_no_rule, str(R.string.item_sound_title)))).assertExists()
        visibility(R.string.visibility_when_active).performClick()
        rule.waitForIdle()
        assertEquals(item("s", "sound"), now("s"))

        // With the arrow anything can go behind it; what has no rule has no switch there.
        Store.update { it.copy(hiddenMode = HiddenMode.CLICK) }
        rule.waitForIdle()
        behind.assertIsEnabled()
        rule.onNode(hasText(str(R.string.visibility_no_rule, str(R.string.item_sound_title)))).assertDoesNotExist()
        behind.performClick()
        rule.waitForIdle()
        assertEquals(Section.HIDDEN, now("s").section)
        rule.onNodeWithTag("pops-out").assertDoesNotExist()
    }

    @Test fun areaIsTheLastRowWithOneAreaInUseAndTheFirstWithSeveral() {
        page("m", HiddenMode.SHOW_ALL, item("m", "memory"), item("c", "clock"))
        assertTrue("a menu at the foot of the options", top("area") > top("show-as") && top("area") > top("order"))

        Store.update { it.copy(items = it.items.map { i -> if (i.id == "c") i.copy(area = Position.LEFT) else i }) }
        rule.waitForIdle()
        assertTrue("the three-way choice above Visibility", top("area") < top("visibility"))
        rule.onNode(hasText(str(R.string.areas_right)) and inTag("area")).assertIsSelected()
    }

    @Test fun orderSaysWhereTheItemStandsAndItsArrowsMoveItUntilTheEnd() {
        page("b", HiddenMode.SHOW_ALL, item("a", "clock"), item("b", "memory"), item("c", "battery"))
        rule.onNodeWithTag("order").assertTextEquals("2nd of 3 in the bar")
        button(R.string.row_move_down).performClick()
        rule.waitForIdle()
        assertEquals(listOf("a", "c", "b"), Store.config.value.items.map { it.id })
        rule.onNodeWithTag("order").assertTextEquals("3rd of 3 in the bar")
        // Below it is the line, and under the line only what waits for its rule: one more step puts it there.
        button(R.string.row_move_down).assertIsEnabled().performClick()
        rule.waitForIdle()
        assertTrue(now("b").whenActive)
        rule.onNodeWithTag("order").assertTextEquals("1st of 1 when active")
        button(R.string.row_move_down).assertIsNotEnabled()
        button(R.string.row_move_up).assertIsEnabled()
    }

    @Test fun nextMeetingHasNoGripAndNoArrowsAndSaysItIsAlwaysFirst() {
        page("e", HiddenMode.SHOW_ALL, item("e", "event"), item("c", "clock"))
        rule.onNodeWithTag("order").assertTextEquals(str(R.string.order_pinned, str(R.string.areas_right)))
        rule.onAllNodes(hasContentDescription(str(R.string.row_move_up)) and inTag("options")).assertCountEquals(0)
        rule.onAllNodes(hasContentDescription(str(R.string.row_move_down)) and inTag("options")).assertCountEquals(0)
        rule.onAllNodes(hasText(str(R.string.key_alt)) and inTag("options")).assertCountEquals(0)
        rule.onAllNodes(hasTestTag("grip") and inTag("row:e"), useUnmergedTree = true).assertCountEquals(0)
        rule.onAllNodes(hasTestTag("grip") and inTag("row:c"), useUnmergedTree = true).assertCountEquals(1)
    }

    @Test fun anItemThatWaitsForCalendarAccessSaysSoOnItsRowAndOffersTheOneButton() {
        page("e", HiddenMode.SHOW_ALL, item("e", "event"), item("c", "clock"))
        rule.onNode(hasText(str(R.string.detail_needs_calendar)) and inTag("options")).assertExists()
        rule.onNode(hasText(str(R.string.common_allow)) and hasClickAction() and inTag("options")).assertExists()
        rule.onNode(hasText(str(R.string.row_needs_calendar), substring = true) and hasTestTag("row:e")).assertExists()
        rule.onNode(hasText(str(R.string.row_needs_calendar), substring = true) and hasTestTag("row:c")).assertDoesNotExist()
    }

    @Test fun showAsChoosesWhatTheItemDrawsAndATypeThatIsAnIconHasNoSuchRow() {
        page("m", HiddenMode.SHOW_ALL, item("m", "memory"), item("t", "tools"))
        rule.onNode(hasText(str(R.string.display_icon_and_text)) and inTag("show-as")).assertIsSelected()
        rule.onNode(hasText(str(R.string.display_icon)) and inTag("show-as")).performClick()
        rule.waitForIdle()
        assertEquals(item("m", "memory").copy(display = Display.ICON), now("m"))
        rule.onNode(hasText(str(R.string.display_icon)) and inTag("show-as")).assertIsSelected()

        assertTrue(Items.of("tools")!!.iconOnly)
        selected = "t"
        rule.waitForIdle()
        rule.onNodeWithTag("show-as").assertDoesNotExist()
    }

    @Test fun theDatesChoicesShowTheDayTheWindowsClockSaysInEachFormat() {
        Store.update { BarConfig(items = listOf(item("a", "calendar")), position = Position.RIGHT) }
        selected = "a"
        var day by mutableStateOf(java.time.ZonedDateTime.of(2026, 11, 10, 9, 41, 0, 0, java.time.ZoneId.systemDefault()))
        rule.setContent {
            CompositionLocalProvider(com.meridian11.books.bentobar.ui.LocalPreviewClock provides { day }) {
                SettingsTheme(dark = false) { BarPage(selected, onSelect = { selected = it }) }
            }
        }
        rule.waitForIdle()
        fun choice(text: String) = rule.onNode(hasText(text) and inTag("options"))
        for (text in listOf("Tue 10 Nov", "10 Nov", "Tuesday", "10.11.")) choice(text).assertExists()
        choice("Sun 28 Sep").assertDoesNotExist()

        rule.runOnIdle { day = day.plusDays(1) }
        rule.waitForIdle()
        for (text in listOf("Wed 11 Nov", "11 Nov", "Wednesday", "11.11.")) choice(text).assertExists()
    }

    @Test fun theFootHasAHairlineAcrossThePanelAndTheOptionsScrollUnderIt() {
        page("a", HiddenMode.SHOW_ALL, item("a", "clock"), item("c", "memory"))
        fun box(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        val panel = box("options")
        val edge = box("options-edge")
        assertEquals("a hairline", 1f, edge.height, 0.01f)
        assertEquals("from the panel's one side", panel.left, edge.left, 0.5f)
        assertEquals("to the other", panel.right, edge.right, 0.5f)
        // What scrolls ends at the line, and the buttons stand under it with air.
        val scrolls = rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and inTag("options")).fetchSemanticsNode().boundsInRoot
        assertEquals(edge.top, scrolls.bottom, 0.5f)
        val remove = rule.onNode(hasText(str(R.string.detail_remove)) and hasClickAction() and inTag("options")).fetchSemanticsNode().boundsInRoot
        assertTrue(remove.top >= edge.bottom + 8f)
    }

    @Test fun duplicatePutsACopyRightAfterTheItemAndSelectsIt() {
        page("a", HiddenMode.SHOW_ALL, item("a", "memory"), item("c", "clock"))
        rule.onNode(hasText(str(R.string.detail_duplicate)) and hasClickAction()).performClick()
        rule.waitForIdle()
        val ids = Store.config.value.items.map { it.id }
        assertEquals(3, ids.size)
        assertEquals("a", ids[0]); assertEquals("c", ids[2])
        assertEquals("memory", now(ids[1]).type)
        assertEquals(ids[1], selected)
        rule.onNodeWithTag("row:${ids[1]}").assertIsSelected()
        rule.onNodeWithTag("order").assertTextEquals("2nd of 3 in the bar")
    }

    @Test fun anItemFromANewerVersionCanBeMovedAndRemovedAndNothingElse() {
        val layout = page("n", HiddenMode.SHOW_ALL, item("n", "from-a-newer-version"), item("c", "clock"))
        rule.onNode(hasText(str(R.string.detail_unknown_help))).assertExists()
        rule.onNodeWithTag("show-as").assertDoesNotExist()
        rule.onNode(hasText(str(R.string.detail_duplicate))).assertDoesNotExist()
        visibility(R.string.visibility_when_active).assertIsNotEnabled()
        button(R.string.row_move_down).assertIsEnabled()

        rule.onNode(hasText(str(R.string.detail_remove)) and hasClickAction()).performClick()
        rule.waitForIdle()
        assertNotEquals(layout, Store.config.value)
        rule.onNodeWithTag("row:n").assertDoesNotExist()
        rule.pastANotesFirstMoment()
        rule.onNode(hasText(str(R.string.bar_undo)) and hasClickAction()).performClick()
        rule.waitForIdle()
        assertEquals(layout, Store.config.value)
    }
}
