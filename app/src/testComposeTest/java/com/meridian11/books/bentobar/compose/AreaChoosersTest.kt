package com.meridian11.books.bentobar.compose

import android.app.Application
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.data.BarConfig
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.data.Position
import com.meridian11.books.bentobar.data.Section
import com.meridian11.books.bentobar.data.Store
import com.meridian11.books.bentobar.items.Env
import com.meridian11.books.bentobar.ui.BarPage
import com.meridian11.books.bentobar.ui.LookPage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Where Settings says and changes an item's area: Area in the item's options, the bar list's head for each area in use,
 * and Look's Position row, which puts every item in one area. Drawn through the Bar page and the Look page as the app
 * draws them, wide enough for the list and the panel side by side.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w1200dp-h800dp-mdpi")
class AreaChoosersTest {
    @get:Rule val rule = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var before: BarConfig

    @Before fun env() { Env.init(app); before = Store.config.value }
    @After fun forget() = Store.update { before }

    private val left get() = app.getString(R.string.areas_left)
    private val middle get() = app.getString(R.string.areas_middle)
    private val right get() = app.getString(R.string.areas_right)

    // An item that is not in the bar is kept only with a setup of its own: the one that is Off here shows as an icon.
    private fun item(id: String, area: Position, section: Section = Section.SHOWN) =
        ItemConfig(id, "cpu", section, area = area)

    private fun barPage(selected: String?, vararg items: ItemConfig): BarConfig {
        // The old Position says Right throughout: nothing here may read it.
        val layout = BarConfig(items = items.toList(), position = Position.RIGHT)
        Store.update { layout }
        rule.setContent { BarPage(selected, onSelect = {}) }
        rule.waitForIdle()
        return layout
    }

    /** Area's control in the options: one area in use has a menu, several have the three-way choice. */
    private val inArea = hasAnyAncestor(hasTestTag("area"))
    private fun areaChoice(word: String) = rule.onNode(hasText(word) and inArea)
    /** A choice on the Look page, which has no list. */
    private fun choice(word: String) = rule.onNode(hasText(word))
    private fun heads(word: String) = rule.onAllNodes(hasText(word) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))

    @Test fun areaShowsTheItemsAreaAndAChoiceChangesOnlyThatItemsArea() {
        val layout = barPage("a", item("a", Position.RIGHT), item("b", Position.RIGHT, Section.HIDDEN))
        rule.onNode(hasText(app.getString(R.string.areas_label))).assertExists()
        // One area in use: a menu that shows it, last in the options.
        rule.onNode(hasText(right) and hasClickAction() and inArea).performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNode(hasText(left) and hasClickAction() and !inArea).performClick()
        rule.waitForIdle()
        assertEquals("only a's area, and its place in the list as it was",
            layout.copy(items = layout.items.map { if (it.id == "a") it.copy(area = Position.LEFT) else it }), Store.config.value)

        // Two in use now: the three of them side by side, the item's own chosen.
        areaChoice(left).assertIsSelected()
        areaChoice(right).assertIsNotSelected()
        areaChoice(middle).performClick()
        rule.waitForIdle()
        assertEquals(layout.copy(items = layout.items.map { if (it.id == "a") it.copy(area = Position.CENTER) else it }), Store.config.value)
        areaChoice(middle).assertIsSelected()
        areaChoice(left).assertIsNotSelected()
    }

    @Test fun theListNamesItsAreasOnlyWhileMoreThanOneIsInUse() {
        barPage(null, item("a", Position.RIGHT), item("b", Position.LEFT, Section.HIDDEN))
        heads(right).assertCountEquals(1)
        heads(left).assertCountEquals(1)
        heads(middle).assertCountEquals(0)

        // One area again: the list reads as it did before there were areas.
        Store.update { it.copy(items = it.items.map { i -> if (i.id == "b") i.copy(area = Position.RIGHT) else i }) }
        rule.waitForIdle()
        for (word in listOf(left, middle, right)) heads(word).assertCountEquals(0)
    }

    @Test fun anItemOfATypeThisVersionDoesNotKnowStandsUnderItsAreasHeadLikeAnyOther() {
        // It counts as an area in use for Settings (Look shows no choice), so the list has to say where it is.
        barPage(null, item("a", Position.RIGHT), ItemConfig("n", "from-a-newer-version", area = Position.LEFT))
        heads(left).assertCountEquals(1)
        heads(right).assertCountEquals(1)
        rule.onNodeWithTag("row:n").assertExists()
    }

    private fun lookPage(vararg items: ItemConfig): BarConfig {
        val layout = BarConfig(items = items.toList(), position = Position.RIGHT)
        Store.update { layout }
        rule.setContent { LookPage() }
        rule.waitForIdle()
        return layout
    }

    @Test fun lookShowsNoAreaAsChosenWhileTwoAreInUseAndAPressPutsEveryItemInThatOne() {
        val layout = lookPage(item("a", Position.RIGHT), item("b", Position.LEFT, Section.HIDDEN), item("c", Position.LEFT))
        for (word in listOf(left, middle, right)) choice(word).assertIsNotSelected()

        choice(middle).performClick()
        rule.waitForIdle()
        assertEquals("every item's area, and nothing else",
            layout.copy(items = layout.items.map { it.copy(area = Position.CENTER) }), Store.config.value)
        choice(middle).assertIsSelected()
        choice(left).assertIsNotSelected()
        choice(right).assertIsNotSelected()
    }

    @Test fun lookSaysThatAChoiceMovesEveryItemOnlyWhileTheItemsAreInSeveralAreas() {
        val several = hasText(app.getString(R.string.areas_look_several))
        lookPage(item("a", Position.RIGHT), item("b", Position.LEFT, Section.HIDDEN))
        rule.onNode(several).assertExists()

        // One area: the row reads as it did before there were areas.
        choice(middle).performClick()
        rule.waitForIdle()
        rule.onNode(several).assertDoesNotExist()

        // No items: a place is chosen, and nothing is said.
        Store.update { it.copy(items = emptyList()) }
        rule.waitForIdle()
        rule.onNode(several).assertDoesNotExist()
        choice(right).assertIsSelected()
    }

    @Test fun lookShowsTheOneAreaEveryItemIsInWhateverTheOldPositionSays() {
        lookPage(item("a", Position.LEFT), item("b", Position.LEFT, Section.HIDDEN))
        choice(left).assertIsSelected()
        choice(middle).assertIsNotSelected()
        choice(right).assertIsNotSelected()
    }
}
