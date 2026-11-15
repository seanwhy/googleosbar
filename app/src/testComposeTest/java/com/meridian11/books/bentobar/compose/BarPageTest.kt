package com.meridian11.books.bentobar.compose

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.data.BarConfig
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.data.Position
import com.meridian11.books.bentobar.data.Store
import com.meridian11.books.bentobar.items.Env
import com.meridian11.books.bentobar.ui.BarPage
import com.meridian11.books.bentobar.ui.LocalWindowLayout
import com.meridian11.books.bentobar.ui.SettingsTheme
import com.meridian11.books.bentobar.ui.Undo
import com.meridian11.books.bentobar.ui.windowLayout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Bar page's list and its panel, by pointer: what selects an item, what turns the panel, and the Undo that stands in
 * a removed row's place. Drawn as the window draws it, with the selection held where the window holds it.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w1200dp-h800dp-mdpi")
class BarPageTest {
    @get:Rule val rule = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var before: BarConfig
    private var selected by mutableStateOf<String?>(null)

    @Before fun env() { Env.init(app); before = Store.config.value; Undo.last.value = null }
    @After fun forget() { Store.update { before }; Undo.last.value = null }

    private fun page(select: String?, vararg items: ItemConfig, width: Int = 1180): BarConfig {
        val layout = BarConfig(items = items.toList(), position = Position.RIGHT)
        Store.update { layout }
        selected = select
        rule.setContent {
            CompositionLocalProvider(LocalWindowLayout provides windowLayout(width)) {
                SettingsTheme(dark = false) { BarPage(selected, onSelect = { selected = it }) }
            }
        }
        rule.waitForIdle()
        return layout
    }

    private val three = arrayOf(ItemConfig("a", "clock", area = Position.RIGHT), ItemConfig("b", "memory", area = Position.RIGHT),
        ItemConfig("c", "battery", area = Position.RIGHT))

    private fun row(id: String) = rule.onNodeWithTag("row:$id")
    private fun options() = rule.onNodeWithTag("options")
    private fun order() = Store.config.value.items.map { it.id }
    private fun switchPart(text: Int) = rule.onNode(hasText(app.getString(text)) and hasAnyAncestor(hasTestTag("panel-switch")))

    /** Press on the row, carry it [by] down past the touch slop in steps, and let go. */
    private fun pickUp(id: String, by: Float) {
        row(id).performMouseInput {
            moveTo(center)
            press()
            repeat(4) { moveBy(Offset(0f, by / 4)) }
            release()
        }
        rule.waitForIdle()
    }

    @Test fun aPressAndReleaseSelectsAndTurnsThePanelAndAPickupDoesNeither() {
        page(null, *three)
        options().assertDoesNotExist()

        pickUp("a", by = 64f)
        assertEquals("the row was carried: it stands after the one it passed", listOf("b", "a", "c"), order())
        assertNull("a pickup never selects", selected)
        options().assertDoesNotExist()
        row("a").assertIsNotSelected()

        row("a").performMouseInput { moveTo(center); press(); release() }
        rule.waitForIdle()
        assertEquals("a", selected)
        row("a").assertIsSelected()
        options().assertExists()
        assertEquals("a click moves nothing", listOf("b", "a", "c"), order())

        // With another item selected, a pickup keeps that selection and the panel on its options.
        pickUp("c", by = -64f)
        assertEquals(listOf("b", "c", "a"), order())
        assertEquals("a", selected)
        row("a").assertIsSelected()
        row("c").assertIsNotSelected()
        options().assertExists()
    }

    @Test fun aClickOnTheCardsEmptyPartLetsTheItemGoAndThePanelTurnsBack() {
        page("b", *three)
        options().assertExists()
        rule.onNodeWithTag("bar-list").performTouchInput { click(Offset(width / 2f, height - 6f)) }
        rule.waitForIdle()
        assertNull(selected)
        options().assertDoesNotExist()
        switchPart(R.string.page_add).assertIsSelected()
    }

    @Test fun undoStandsInTheRemovedRowsPlaceAndBringsItBackWhereItWas() {
        val layout = page("b", *three)
        rule.onNode(hasText(app.getString(R.string.detail_remove)) and hasClickAction()).performClick()
        rule.waitForIdle()
        row("b").assertDoesNotExist()
        assertEquals(listOf("a", "c"), order())
        assertNull("what was removed is no longer selected", selected)
        options().assertDoesNotExist()
        val slip = rule.onNodeWithTag("slip")
        slip.assertExists()
        rule.onNode(hasText(app.getString(R.string.bar_removed, app.getString(R.string.item_memory_title)))).assertExists()

        rule.pastANotesFirstMoment()
        rule.onNode(hasText(app.getString(R.string.bar_undo)) and hasClickAction()).performClick()
        rule.waitForIdle()
        assertEquals(layout, Store.config.value)
        row("b").assertExists()
        rule.onNodeWithTag("slip").assertDoesNotExist()
    }

    @Test fun theSlipClosesWithTheNextChangeToTheBar() {
        page("b", *three)
        rule.onNode(hasText(app.getString(R.string.detail_remove)) and hasClickAction()).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("slip").assertExists()
        // Carried past the removed row's place and the row after it.
        pickUp("a", by = 120f)
        assertEquals(listOf("c", "a"), order())
        rule.onNodeWithTag("slip").assertDoesNotExist()
    }

    @Test fun belowTheWidthWithRoomForBothTheListIsThePanelsFirstPart() {
        page(null, *three, width = 960)
        switchPart(R.string.page_bar).assertIsSelected()
        rule.onNodeWithTag("bar-list").assertExists()

        row("b").performClick()
        rule.waitForIdle()
        assertEquals("b", selected)
        options().assertExists()
        rule.onNodeWithTag("bar-list").assertDoesNotExist()

        // Removing it turns to the library, as at every width; the Undo stands in the list, one part to the left.
        rule.onNode(hasText(app.getString(R.string.detail_remove)) and hasClickAction()).performClick()
        rule.waitForIdle()
        switchPart(R.string.page_add).assertIsSelected()
        rule.onNodeWithTag("library").assertExists()
        switchPart(R.string.page_bar).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("slip").assertExists()
    }

    @Test fun withNothingSelectedThePanelsSecondPartSaysSoAndCannotBeChosen() {
        page(null, *three)
        switchPart(R.string.page_add).assertIsSelected()
        switchPart(R.string.panel_select_item).assertIsNotSelected()
        switchPart(R.string.panel_select_item).performClick()
        rule.waitForIdle()
        options().assertDoesNotExist()
    }
}
