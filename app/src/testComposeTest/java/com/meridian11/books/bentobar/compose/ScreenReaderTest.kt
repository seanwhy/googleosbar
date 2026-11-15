package com.meridian11.books.bentobar.compose

import android.app.Application
import android.graphics.Rect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import com.meridian11.books.bentobar.bar.BarOverflow
import com.meridian11.books.bentobar.ui.LookPage
import com.meridian11.books.bentobar.ui.SettingsTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.bar.Strip
import com.meridian11.books.bentobar.bar.StripEntry
import com.meridian11.books.bentobar.bar.StripEvents
import com.meridian11.books.bentobar.bar.StripLook
import com.meridian11.books.bentobar.data.BarConfig
import com.meridian11.books.bentobar.data.HiddenMode
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.data.Pill
import com.meridian11.books.bentobar.data.Position
import com.meridian11.books.bentobar.data.Section
import com.meridian11.books.bentobar.data.Store
import com.meridian11.books.bentobar.data.TextSize
import com.meridian11.books.bentobar.items.Env
import com.meridian11.books.bentobar.items.ItemState
import com.meridian11.books.bentobar.items.Items
import com.meridian11.books.bentobar.ui.BarPage
import com.meridian11.books.bentobar.ui.ChipRow
import com.meridian11.books.bentobar.ui.LocalWindowLayout
import com.meridian11.books.bentobar.ui.MenuEntry
import com.meridian11.books.bentobar.ui.SectionLabel
import com.meridian11.books.bentobar.ui.SliderRow
import com.meridian11.books.bentobar.ui.Undo
import com.meridian11.books.bentobar.ui.windowLayout
import com.meridian11.books.bentobar.util.Fmt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** What a screen reader finds on the shared pieces, and that a large text size only grows what is words (findings F37, F86, F36). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37])
class ScreenReaderTest {
    @get:Rule val rule = createComposeRule()

    @Before fun env() = Env.init(ApplicationProvider.getApplicationContext<Application>())

    @Test fun aSectionLabelIsAHeading() {
        rule.setContent { SectionLabel("Options") }
        rule.onNodeWithText("OPTIONS").assert(isHeading())
    }

    @Test fun anOnOffEntrySaysItsStateAndItsMarkIsNotReadOut() {
        rule.setContent { MenuEntry(null, "Show when active", checked = true) {} }
        rule.onNodeWithText("Show when active", useUnmergedTree = false).assertIsOn()
        // The tick is the entry's own, a symbol at its end, and says nothing.
        rule.onNodeWithText(com.meridian11.books.bentobar.util.Sym.CHECK).assertDoesNotExist()
        rule.onNodeWithText(com.meridian11.books.bentobar.util.Sym.CHECK, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun aChosenChipIsSelectedAndItsCheckMarkIsNotReadOut() {
        rule.setContent { ChipRow(listOf("Icon", "Text"), selected = 1) {} }
        rule.onNodeWithText("Text").assertIsSelected()
        rule.onNodeWithText("✓ ").assertDoesNotExist()
        rule.onNodeWithText("✓", substring = true).assertDoesNotExist()
    }

    @Test fun aSliderSaysItsValueInItsOwnUnit() {
        rule.setContent { SliderRow("Spacing", 12, 0..24, { "$it dp" }) {} }
        rule.onNodeWithContentDescription("Spacing").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "12 dp"))
    }

    @Test fun theFormatsTakeTheirWordsFromTheAppsText() {
        // Env.init hands Fmt the words from the text resources: a resource format that doesn't fit its call shows up here.
        assertEquals("85%", Fmt.percent(85))
        assertEquals("840 KB", Fmt.bytes(840_000.0))
        assertEquals("1 hour 5 minutes", Fmt.spoken(65 * 60_000L))
        assertEquals("1h 05m", Fmt.duration(65 * 60_000L))
    }

        private object Events : StripEvents {
        override fun click(item: ItemConfig, at: Rect) {}
        override fun context(item: ItemConfig, at: Rect) {}
        override fun scroll(item: ItemConfig, steps: Int) {}
        override fun chevron(at: Rect) {}
        override fun chevronContext(at: Rect) {}
        override fun hover(inside: Boolean) {}
        override fun placed(id: String, at: Rect) {}
    }

    @Test fun twiceTheTextSizeLeavesADateBadgeAndItsDigitsAsTheyAre() {
        val look = StripLook(Color.White, lightText = true, TextSize.DEFAULT, 8.dp, Pill.NONE)
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                Strip(listOf(StripEntry(ItemConfig("d", "calendar"), ItemState(dayNumber = 9, desc = "Today"))), emptyList(), showChevron = false, chevronAlways = false,
                    chevronReservePx = 0, expanded = false, chevronOnLeft = false, look = look, maxWidthPx = 500, heightDp = 36.dp, events = Events)
            }
        }
        // 15 dp of badge + 1 + the item's 6 dp at each end, whatever the text size.
        val item = rule.onNodeWithContentDescription("Today").getBoundsInRoot()
        assertEquals(28f, (item.right - item.left).value, 1f)
        // The digits fit the 16 dp badge: in sp, at 200% their line would be cut off by it.
        val digits = rule.onNodeWithText("9", useUnmergedTree = true).getBoundsInRoot()
        assertTrue("digits of ${digits.bottom - digits.top} in a 16 dp badge", (digits.bottom - digits.top).value < 14f)
    }

    // The bar's rows: every command a row has by pointer or key is an action of the row, and only where it would do something.

    private fun str(id: Int) = ApplicationProvider.getApplicationContext<Application>().getString(id)
    private fun actions(id: String) = rule.onNodeWithTag("row:$id").fetchSemanticsNode().config[SemanticsActions.CustomActions]
    private fun labels(id: String) = actions(id).map { it.label }
    private fun act(id: String, label: Int) {
        val action = actions(id).first { it.label == str(label) }
        rule.runOnUiThread { assertTrue(action.action()) }
        rule.waitForIdle()
    }

    /** The Bar page as one pane, so the list is on screen at the test's small size, with the layout put back after [check]. */
    private fun barRows(mode: HiddenMode, vararg items: ItemConfig, check: () -> Unit) {
        val before = Store.config.value
        Undo.last.value = null
        try {
            Store.update { BarConfig(items = items.toList(), position = Position.RIGHT, hiddenMode = mode) }
            rule.setContent {
                CompositionLocalProvider(LocalWindowLayout provides windowLayout(700)) { BarPage(null, onSelect = {}) }
            }
            rule.waitForIdle()
            check()
        } finally {
            Store.update { before }
            Undo.last.value = null
        }
    }

    @Test fun aRowsActionsAreTheCommandsItCanTakeAndNoOthers() {
        val up = str(R.string.row_move_up); val down = str(R.string.row_move_down); val remove = str(R.string.detail_remove)
        val active = str(R.string.row_show_when_active)
        barRows(HiddenMode.SHOW_ALL, ItemConfig("e", "event"), ItemConfig("a", "clock"), ItemConfig("b", "memory"), ItemConfig("s", "sound")) {
            assertEquals("the first row that moves has no way up", listOf(down, active, remove), labels("a"))
            assertEquals(listOf(up, down, active, remove), labels("b"))
            assertEquals("a type with no rule can't go under the line, by a step or by name", listOf(up, remove), labels("s"))
            assertEquals("Next meeting is always first: it has no order to change", listOf(active, remove), labels("e"))
        }
    }

    @Test fun aRowsActionsDoWhatTheControlsDo() {
        barRows(HiddenMode.SHOW_ALL, ItemConfig("a", "clock"), ItemConfig("b", "memory"), ItemConfig("c", "battery")) {
            fun item(id: String) = Store.config.value.items.first { it.id == id }
            act("b", R.string.row_move_up)
            assertEquals(listOf("b", "a", "c"), Store.config.value.items.map { it.id })

            act("b", R.string.row_show_when_active)
            assertTrue(item("b").whenActive)
            assertEquals(Section.HIDDEN, item("b").section)
            // Under the line the same place in the list of actions offers the way back.
            assertTrue(str(R.string.row_show_always) in labels("b"))
            assertTrue(str(R.string.row_show_when_active) !in labels("b"))
            act("b", R.string.row_show_always)
            assertEquals(Section.SHOWN, item("b").section)

            act("c", R.string.detail_remove)
            assertEquals(listOf("b", "a"), Store.config.value.items.map { it.id })
            rule.onNodeWithTag("row:c").assertDoesNotExist()
            rule.onNodeWithTag("slip").assertExists()
        }
    }

    @Test fun withTheArrowTheActionPutsARowBehindItWhateverItsType() {
        barRows(HiddenMode.CLICK, ItemConfig("a", "clock"), ItemConfig("s", "sound")) {
            assertTrue(str(R.string.row_put_behind) in labels("s"))
            assertTrue(str(R.string.row_show_when_active) !in labels("s"))
            act("s", R.string.row_put_behind)
            assertEquals(Section.HIDDEN, Store.config.value.items.first { it.id == "s" }.section)
            assertTrue(str(R.string.row_show_always) in labels("s"))
        }
    }

    // The whole window: what stages 2 to 6a put on it, as a screen reader finds it.

    private val clock get() = str(Items.of("clock")!!.titleRes)
    private fun str(id: Int, vararg args: Any) = ApplicationProvider.getApplicationContext<Application>().getString(id, *args)
    private fun said(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
    private val isTab = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)

    /** The Bar page in a window with room for the list and the panel, the layout put back after [check]. */
    private fun window(mode: HiddenMode, items: List<ItemConfig>, select: String? = null, overflow: Set<String> = emptySet(), check: () -> Unit) {
        val before = Store.config.value
        Undo.last.value = null
        var selected by mutableStateOf(select)
        try {
            Store.update { BarConfig(items = items, position = Position.RIGHT, hiddenMode = mode) }
            BarOverflow.ids.value = overflow
            rule.setContent {
                SettingsTheme(false) { CompositionLocalProvider(LocalWindowLayout provides windowLayout(1180)) { BarPage(selected, onSelect = { selected = it }) } }
            }
            rule.waitForIdle()
            check()
        } finally {
            Store.update { before }
            BarOverflow.ids.value = emptySet()
            Undo.last.value = null
        }
    }

    private fun inArea(id: String, type: String, area: Position, section: Section = Section.SHOWN) = ItemConfig(id, type, section, area = area)
    private val three = listOf(inArea("w", "weather", Position.LEFT), inArea("t", "timer", Position.CENTER), inArea("a", "clock", Position.RIGHT),
        inArea("m", "memory", Position.RIGHT), inArea("h", "battery", Position.RIGHT, Section.HIDDEN), inArea("lh", "sound", Position.LEFT, Section.HIDDEN))

    /**
     * What on screen still says the arrow as a sign: a label with ‹ or › in it, or a text with one that has no label
     * of its own to say it in words.
     */
    private fun arrowSigns(): List<String> = rule.onAllNodes(SemanticsMatcher("anything") { true }, useUnmergedTree = true).fetchSemanticsNodes().flatMap { node ->
        fun sign(s: String) = "‹" in s || "›" in s
        // What stands under a node that speaks for everything in it is not read.
        if (generateSequence(node.parent) { it.parent }.any { it.config.isClearingSemantics }) return@flatMap emptyList()
        val labels = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        val texts = node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
        labels.filter(::sign).map { "label: $it" } + if (labels.isEmpty()) texts.filter(::sign).map { "text: $it" } else emptyList()
    }

    @Config(qualifiers = "w1180dp-h780dp-mdpi")
    @Test fun nothingOnTheBarPageSaysTheArrowAsASign() {
        // With the arrow, in three areas, one row of Right and one of Left with no room: every place the sign is written.
        window(HiddenMode.CLICK, three, overflow = setOf("m", "w")) {
            rule.onNodeWithTag("library").assertExists()
            assertEquals("the list and the library", emptyList<String>(), arrowSigns())
            assertTrue("the rows that don't fit say behind which arrow", rule.onAllNodes(hasText("‹", substring = true) or hasText("›", substring = true), useUnmergedTree = true)
                .fetchSemanticsNodes().size >= 3)
        }
    }

    @Config(qualifiers = "w1180dp-h780dp-mdpi")
    @Test fun nothingInAnItemsOptionsSaysTheArrowAsASign() {
        window(HiddenMode.HOVER, three, select = "a") {
            rule.onNodeWithTag("options").assertExists()
            assertEquals(emptyList<String>(), arrowSigns())
        }
    }

    @Config(qualifiers = "w1180dp-h780dp-mdpi")
    @Test fun nothingInOneAreaSaysTheArrowAsASign() {
        // One area: the row that doesn't fit names the arrow's menu, and the library says where a waiting type goes.
        window(HiddenMode.CLICK, listOf(ItemConfig("a", "clock"), ItemConfig("m", "memory"), ItemConfig("h", "battery", Section.HIDDEN)), overflow = setOf("m")) {
            assertEquals(emptyList<String>(), arrowSigns())
        }
    }

    @Config(qualifiers = "w1180dp-h1600dp-mdpi")
    @Test fun nothingOnLookSaysTheArrowAsASign() {
        val before = Store.config.value
        try {
            Store.update { BarConfig(items = listOf(ItemConfig("a", "clock")), position = Position.RIGHT, hiddenMode = HiddenMode.CLICK) }
            rule.setContent { SettingsTheme(false) { CompositionLocalProvider(LocalWindowLayout provides windowLayout(1180)) { LookPage() } } }
            rule.waitForIdle()
            rule.onNodeWithText(str(R.string.look_hidden_click), useUnmergedTree = true).assertExists()
            assertEquals(emptyList<String>(), arrowSigns())
        } finally {
            Store.update { before }
        }
    }

    @Config(qualifiers = "w1180dp-h780dp-mdpi")
    @Test fun thePanelsSwitchIsATabListAndItsDisabledPartIsSaidWithItsText() {
        window(HiddenMode.SHOW_ALL, listOf(ItemConfig("a", "clock"))) {
            assertTrue("the parts are one group", rule.onNodeWithTag("panel-switch").fetchSemanticsNode().config.contains(SemanticsProperties.SelectableGroup))
            rule.onNode(hasText(str(R.string.page_add)) and isTab).assertIsSelected()
            // With nothing selected the last part can't be chosen, and says so with its own words.
            rule.onNode(hasText(str(R.string.panel_select_item)) and isTab).assertIsNotEnabled().assertIsNotSelected()
            // A click on a row selects it, and the part is the item's by name.
            rule.onNodeWithTag("row:a").performClick()
            rule.waitForIdle()
            rule.onNode(hasText(clock) and isTab).assertIsEnabled().assertIsSelected()
            rule.onNode(hasText(str(R.string.page_add)) and isTab).assertIsNotSelected()
        }
    }

    @Config(qualifiers = "w1180dp-h780dp-mdpi")
    @Test fun aPartSaidInOtherWordsIsStillAChoiceWithItsState() {
        // "Behind ‹" is said in words, and is still one of two, chosen or not, and disabled where the type can't go there.
        window(HiddenMode.CLICK, three, select = "h") {
            val behind = rule.onNode(hasContentDescription(str(R.string.visibility_behind).replace("‹", str(R.string.chevron_spoken))) and hasAnyAncestor(hasTestTag("visibility")))
            behind.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)).assertIsSelected().assertIsEnabled()
        }
    }

    @Config(qualifiers = "w1180dp-h780dp-mdpi")
    @Test fun theLibrarysAddToSaysEachAreaWithWhatItIsFor() {
        window(HiddenMode.SHOW_ALL, three) {
            // What is chosen as the window opens is the home area: Left, where a new item goes.
            val to = rule.onNode(hasContentDescription("${str(R.string.areas_add_to)} ${str(R.string.areas_left)}") and hasAnyAncestor(hasTestTag("add-to")))
            to.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)).assertIsSelected()
            rule.onNode(hasContentDescription("${str(R.string.areas_add_to)} ${str(R.string.areas_right)}") and hasAnyAncestor(hasTestTag("add-to"))).assertIsNotSelected()
        }
    }

    @Config(qualifiers = "w1180dp-h780dp-mdpi")
    @Test fun theLanesAreHeadingsAndTheirPlusesAndBracketsSayWhichArea() {
        window(HiddenMode.SHOW_ALL, three) {
            for ((area, name, where) in listOf(Triple(Position.LEFT, R.string.areas_left, R.string.areas_left_where),
                Triple(Position.CENTER, R.string.areas_middle, R.string.areas_middle_where), Triple(Position.RIGHT, R.string.areas_right, R.string.areas_right_where))) {
                // The lane's head is one heading: the area and where it is in the bar.
                rule.onNode(isHeading() and hasText(str(name)) and hasText(" · ${str(where)}")).assertExists()
                // Under the preview the name alone would be a word with nothing around it.
                assertEquals(str(R.string.areas_bracket_spoken, str(name)), said("bracket:$area"))
            }
        }
    }

    @Config(qualifiers = "w1180dp-h780dp-mdpi")
    @Test fun aLanesTrashSaysWhatItTakesAndItsHeadHasTheSameAsAnActionThatSaysWhatWent() {
        window(HiddenMode.SHOW_ALL, three) {
            fun plural(id: Int, count: Int, name: Int) = ApplicationProvider.getApplicationContext<Application>().resources.getQuantityString(id, count, str(name), count)
            fun head(name: Int) = rule.onNode(isHeading() and hasText(str(name)))
            fun headSays(name: Int) = head(name).fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).orEmpty().map { it.label }
            for ((area, name, count) in listOf(Triple(Position.LEFT, R.string.areas_left, 2), Triple(Position.CENTER, R.string.areas_middle, 1), Triple(Position.RIGHT, R.string.areas_right, 3))) {
                val words = plural(R.plurals.areas_remove, count, name)
                assertEquals(words, said("remove-area:$area"))
                assertEquals("the head's one action", listOf(words), headSays(name))
            }
            assertEquals("Remove Middle and its item", said("remove-area:CENTER"))

            // The action is the button's command: the area goes with its items, and the note says so by itself.
            val act = head(R.string.areas_left).fetchSemanticsNode().config[SemanticsActions.CustomActions].single()
            rule.runOnUiThread { assertTrue(act.action()) }
            rule.waitForIdle()
            assertEquals(listOf("t", "a", "m", "h"), Store.config.value.items.map { it.id })
            val note = rule.onNode(hasText(plural(R.plurals.areas_removed, 2, R.string.areas_left)))
            assertEquals("Removed Left and its 2 items", plural(R.plurals.areas_removed, 2, R.string.areas_left))
            assertEquals(LiveRegionMode.Polite, note.fetchSemanticsNode().config.getOrNull(SemanticsProperties.LiveRegion))
            rule.onNodeWithTag("remove-area:LEFT").assertDoesNotExist()
        }
    }

    @Test fun inOneColumnEachLanesPlusSaysWhichAreaItAddsTo() {
        barRows(HiddenMode.SHOW_ALL, *three.toTypedArray()) {
            for ((area, name) in listOf(Position.LEFT to R.string.areas_left, Position.CENTER to R.string.areas_middle, Position.RIGHT to R.string.areas_right))
                assertEquals(str(R.string.areas_add_item, str(name)), said("add-to:$area"))
        }
    }

    @Config(qualifiers = "w1180dp-h780dp-mdpi")
    @Test fun whatADragDoesARowsActionsDoInWords() {
        window(HiddenMode.SHOW_ALL, three) {
            // To another area: one action for each of the other lanes, by name.
            assertEquals(listOf(str(R.string.row_move_down), str(R.string.row_show_when_active), str(R.string.areas_move_to, str(R.string.areas_left)),
                str(R.string.areas_move_to, str(R.string.areas_middle)), str(R.string.detail_remove)), labels("a"))
            val move = actions("a").first { it.label == str(R.string.areas_move_to, str(R.string.areas_middle)) }
            rule.runOnUiThread { assertTrue(move.action()) }
            rule.waitForIdle()
            assertEquals(Position.CENTER, Store.config.value.items.first { it.id == "a" }.area)
            // And what it did is said: the row's new place, in the line a screen reader hears.
            assertTrue(rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)).fetchSemanticsNodes()
                .any { n -> n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { str(R.string.areas_middle) in it } })
            // Off the bar (the drop on the trash): Remove.
            val remove = actions("m").first { it.label == str(R.string.detail_remove) }
            rule.runOnUiThread { assertTrue(remove.action()) }
            rule.waitForIdle()
            assertTrue(Store.config.value.items.none { it.id == "m" })
        }
    }

    @Config(qualifiers = "w1180dp-h780dp-mdpi")
    @OptIn(ExperimentalTestApi::class)
    @Test fun theTrashUnderTheListSaysNothingAndTakesNoFocusAndRemoveStaysARowsAction() {
        window(HiddenMode.SHOW_ALL, three) {
            val from = rule.onNodeWithTag("row:a").fetchSemanticsNode().boundsInRoot.center
            rule.onRoot().performMouseInput { moveTo(from); press(); for (i in 1..6) moveBy(Offset(0f, 5f)) }
            rule.waitForIdle()
            // The strip and whatever is in it, as a screen reader gets it: no words, no name, nothing to focus or to do.
            val nodes = rule.onAllNodes(hasTestTag("trash") or hasAnyAncestor(hasTestTag("trash"))).fetchSemanticsNodes()
            assertTrue("the trash is on screen", nodes.isNotEmpty())
            for (node in nodes) for (key in listOf(SemanticsProperties.Text, SemanticsProperties.ContentDescription, SemanticsProperties.Focused,
                SemanticsProperties.Role, SemanticsActions.OnClick, SemanticsActions.RequestFocus, SemanticsProperties.LiveRegion))
                assertTrue("the trash has no ${key.name}", key !in node.config)
            assertTrue("its words are drawn and not read", rule.onAllNodes(hasText(str(R.string.bar_trash))).fetchSemanticsNodes().isEmpty())
            assertEquals("and are there to see", 1, rule.onAllNodes(hasText(str(R.string.bar_trash)), useUnmergedTree = true).fetchSemanticsNodes().size)
            // What it does is every row's action still.
            assertTrue(str(R.string.detail_remove) in labels("m"))
            rule.onRoot().performMouseInput { release() }
            rule.waitForIdle()
        }
    }

    @Config(qualifiers = "w1180dp-h780dp-mdpi")
    @Test fun anEmptyLaneSaysWhatItIsAndWhichKeyFillsIt() {
        window(HiddenMode.SHOW_ALL, listOf(ItemConfig("a", "clock"), ItemConfig("m", "memory"))) {
            rule.onNodeWithTag("add-area").performClick()
            rule.onNodeWithTag("add-area:LEFT").performClick()
            rule.waitForIdle()
            assertEquals(str(R.string.areas_empty_new_spoken, str(R.string.areas_left), "Alt+←"), said("lane:LEFT"))
        }
    }
}
