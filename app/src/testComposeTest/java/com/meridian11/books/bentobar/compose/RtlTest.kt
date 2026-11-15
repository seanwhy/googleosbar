package com.meridian11.books.bentobar.compose

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.data.BarConfig
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.data.Position
import com.meridian11.books.bentobar.data.Store
import com.meridian11.books.bentobar.items.Env
import com.meridian11.books.bentobar.ui.BarPage
import com.meridian11.books.bentobar.ui.Bento
import com.meridian11.books.bentobar.ui.LocalWindowLayout
import com.meridian11.books.bentobar.ui.Segment
import com.meridian11.books.bentobar.ui.SegmentPart
import com.meridian11.books.bentobar.ui.SettingsTheme
import com.meridian11.books.bentobar.ui.Undo
import com.meridian11.books.bentobar.ui.windowLayout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The window from right to left. The page is mirrored as every page is; what stands for the bar is not, because Left,
 * Middle and Right are sides of the screen: the lanes, the choices of an area, the preview and its brackets keep Left
 * on the left, so Alt with an arrow moves a row the way the arrow points.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w1180dp-h780dp-mdpi")
class RtlTest {
    @get:Rule val rule = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var before: BarConfig
    private lateinit var inputMode: InputModeManager
    private var selected by mutableStateOf<String?>(null)

    @Before fun env() { Env.init(app); before = Store.config.value; Undo.last.value = null }
    @After fun forget() { Store.update { before }; Undo.last.value = null }

    private fun item(id: String, type: String, area: Position) = ItemConfig(id, type, area = area)
    private val three = listOf(item("w", "weather", Position.LEFT), item("t", "timer", Position.CENTER), item("a", "clock", Position.RIGHT), item("m", "memory", Position.RIGHT))

    private fun page(select: String?, direction: LayoutDirection = LayoutDirection.Rtl, items: List<ItemConfig> = three, home: Position = Position.RIGHT) {
        Store.update { BarConfig(items = items, position = home) }
        selected = select
        rule.setContent {
            inputMode = LocalInputModeManager.current
            CompositionLocalProvider(LocalLayoutDirection provides direction, LocalWindowLayout provides windowLayout(1180)) {
                SettingsTheme(dark = false) { BarPage(selected, onSelect = { selected = it }) }
            }
        }
        rule.runOnIdle { assertTrue(inputMode.requestInputMode(InputMode.Keyboard)) }
        if (select != null) { rule.onNodeWithTag("row:$select").requestFocus(); rule.onNodeWithTag("row:$select").assertIsFocused() }
    }

    private fun str(id: Int) = app.getString(id)
    private fun bounds(tag: String): Rect = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
    private fun now(id: String) = Store.config.value.items.first { it.id == id }
    private fun alt(key: Key) { rule.onNodeWithTag("bar-list").performKeyInput { withKeyDown(Key.AltLeft) { pressKey(key) } }; rule.waitForIdle() }

    @Test fun thePageIsMirroredAndTheLanesKeepTheBarsSides() {
        page(null)
        // The page: the panel is at the window's start, which is now its right.
        assertTrue("the panel stands where the page starts", bounds("panel").left < bounds("bar-list").left)
        // The bar: Left's lane is on the left, as its strip is in the bar.
        assertTrue(bounds("row:w").left < bounds("row:t").left)
        assertTrue(bounds("row:t").left < bounds("row:a").left)
        assertTrue(bounds("bracket:LEFT").left < bounds("bracket:CENTER").left)
        assertTrue(bounds("bracket:CENTER").left < bounds("bracket:RIGHT").left)
    }

    @Test fun aLanesTrashStaysAtItsHeadsEndWhichIsNowItsLeft() {
        page(null)
        for ((area, name, row) in listOf(Triple(Position.LEFT, R.string.areas_left, "w"), Triple(Position.CENTER, R.string.areas_middle, "t"), Triple(Position.RIGHT, R.string.areas_right, "a"))) {
            val head = rule.onNode(isHeading() and hasText(str(name))).fetchSemanticsNode().boundsInRoot
            val trash = bounds("remove-area:$area")
            assertTrue("$area: the trash ends the head, left of its words", trash.right <= head.left)
            // Its glyph stands in line with the rows' ends; the button around it is a target and reaches a little further.
            assertTrue("$area: and it is the lane's own, over its rows", trash.center.x > bounds("row:$row").left && trash.center.x < bounds("row:$row").center.x)
        }
    }

    @Test fun fromLeftToRightALanesTrashEndsItsHeadAtTheRight() {
        page(null, LayoutDirection.Ltr)
        for ((area, name) in listOf(Position.LEFT to R.string.areas_left, Position.CENTER to R.string.areas_middle, Position.RIGHT to R.string.areas_right)) {
            val head = rule.onNode(isHeading() and hasText(str(name))).fetchSemanticsNode().boundsInRoot
            assertTrue("$area", bounds("remove-area:$area").left >= head.right)
        }
    }

    @Test fun thePreviewIsMirroredAroundAreasThatKeepTheirSidesAndTheHeadsSayWhatIsBesideEach() {
        page(null)
        // The picture: the clock at the right, the system's icons at the left, Left's strip still on the left.
        assertTrue("the mock clock is at the right", bounds("preview-clock").left > bounds("preview-icons").right)
        assertTrue(bounds("strip:LEFT").right <= bounds("strip:CENTER").left && bounds("strip:CENTER").right <= bounds("strip:RIGHT").left)
        assertTrue("Left is next to the icons", bounds("strip:LEFT").left - bounds("preview-icons").right < bounds("preview-clock").left - bounds("strip:LEFT").right)
        // The words under it say that: what each area is beside, not what it is beside from left to right.
        fun head(name: Int, where: Int) = rule.onNode(isHeading() and hasText(str(name)) and hasText(str(where), substring = true)).assertExists()
        head(R.string.areas_left, R.string.areas_right_where)
        head(R.string.areas_middle, R.string.areas_middle_where)
        head(R.string.areas_right, R.string.areas_left_where)
        // The clock's own words read from left to right, as a time does.
        val results = mutableListOf<TextLayoutResult>()
        rule.onNodeWithTag("preview-clock").fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        assertEquals(ResolvedTextDirection.Ltr, results.single().getParagraphDirection(0))
    }

    @Test fun fromLeftToRightTheHeadsSayWhatTheyAlwaysDid() {
        page(null, LayoutDirection.Ltr)
        assertTrue(bounds("preview-clock").right < bounds("preview-icons").left)
        fun head(name: Int, where: Int) = rule.onNode(isHeading() and hasText(str(name)) and hasText(str(where), substring = true)).assertExists()
        head(R.string.areas_left, R.string.areas_left_where)
        head(R.string.areas_right, R.string.areas_right_where)
    }

    @Test fun aBarOfOneAreaKeepsItsSideAndAddAnAreaSaysWhatEachAreaIsBeside() {
        val right = listOf(item("a", "clock", Position.RIGHT), item("m", "memory", Position.RIGHT))
        page(null, items = right)
        val bar = bounds("preview")
        assertTrue("Right stands at the right, which is now beside the clock", bounds("strip:RIGHT").center.x > bar.center.x)
        assertTrue(bounds("preview-clock").left - bounds("strip:RIGHT").right < bounds("strip:RIGHT").left - bounds("preview-icons").right)

        rule.onNodeWithTag("add-area").performClick()
        rule.waitForIdle()
        rule.onNode(hasText(str(R.string.areas_right_where)) and hasAnyAncestor(hasTestTag("add-area:LEFT")), useUnmergedTree = true).assertExists()
        rule.onNode(hasText(str(R.string.areas_middle_where)) and hasAnyAncestor(hasTestTag("add-area:CENTER")), useUnmergedTree = true).assertExists()
    }

    @Test fun aBarWithOnlyLeftStandsAtTheLeft() {
        page(null, items = listOf(item("a", "clock", Position.LEFT), item("m", "memory", Position.LEFT)), home = Position.LEFT)
        assertTrue("Left stands at the left, beside the icons", bounds("strip:LEFT").center.x < bounds("preview").center.x)
        assertTrue(bounds("strip:LEFT").left - bounds("preview-icons").right < bounds("preview-clock").left - bounds("strip:LEFT").right)
    }

    @Test fun aRowCarriedOverThePreviewFindsEveryAreaOnItsOwnSide() {
        page(null, items = listOf(item("a", "clock", Position.RIGHT), item("m", "memory", Position.RIGHT)))
        val from = bounds("row:a").center
        val over = bounds("preview").let { Offset(it.center.x, it.top + 26f) }
        rule.onRoot().performMouseInput { moveTo(from); press(); for (i in 1..6) moveTo(from + (over - from) * (i / 6f)) }
        rule.waitForIdle()
        val left = bounds("zone:LEFT")
        assertTrue("Left's zone is at the left, the Middle's between", left.right <= bounds("zone:CENTER").left && bounds("zone:CENTER").right <= bounds("zone:RIGHT").left)
        rule.onRoot().performMouseInput { moveTo((over + left.center) / 2f); moveTo(left.center) }
        rule.waitForIdle()
        rule.onRoot().performMouseInput { release() }
        rule.waitForIdle()
        assertEquals(Position.LEFT, now("a").area)
        assertTrue(bounds("strip:LEFT").left < bounds("strip:RIGHT").left)
    }

    @Test fun aRowCarriedOntoTheTrashIsRemovedAndTheStripLiesUnderTheListBesideThePanel() {
        page(null)
        val layout = Store.config.value
        val card = bounds("bar-list")
        val from = bounds("row:m").center
        rule.onRoot().performMouseInput { moveTo(from); press(); for (i in 1..6) moveBy(Offset(0f, 5f)) }
        rule.waitForIdle()
        // The copy hangs from the pointer, on the pointer's side of the window.
        val pointer = from + Offset(0f, 30f)
        assertTrue("the carried row is under the pointer at $pointer: ${bounds("drag-row")}", bounds("drag-row").contains(pointer))
        val trash = bounds("trash")
        assertEquals("as wide as the list's card", card.left to card.right, trash.left to trash.right)
        assertEquals(card.bottom, trash.bottom)
        assertTrue("the panel, at the page's start, is beside it and no part of it", bounds("panel").right <= trash.left)
        // Every end of the strip takes the row, also the one under Left's lane.
        for (x in listOf(trash.left + 8f, trash.right - 8f, trash.center.x)) {
            rule.onRoot().performMouseInput { moveTo(Offset(x, trash.center.y - 80f)); moveTo(Offset(x, trash.center.y)) }
            rule.waitForIdle()
            rule.onNodeWithTag("preview-line").assert(hasText(app.getString(R.string.bar_drop_off, app.getString(R.string.item_memory_title))))
        }
        // Over the panel nothing would change.
        rule.onRoot().performMouseInput { moveTo(bounds("panel").center) }
        rule.waitForIdle()
        rule.onNodeWithTag("preview-line").assert(hasText(str(R.string.bar_drop_none)))
        rule.onRoot().performMouseInput { moveTo(trash.center + Offset(0f, -80f)); moveTo(trash.center); release() }
        rule.waitForIdle()
        assertTrue(Store.config.value.items.none { it.id == "m" })
        rule.onNodeWithTag("trash").assertDoesNotExist()
        rule.runOnIdle { Undo.undo() }
        rule.waitForIdle()
        assertEquals(layout, Store.config.value)
    }

    @Test fun altWithAnArrowMovesARowTheWayTheArrowPointsOnScreen() {
        page("t")
        val from = bounds("row:t").left
        alt(Key.DirectionRight)
        assertEquals(Position.RIGHT, now("t").area)
        assertTrue("→ moved it to the right on screen", bounds("row:t").left > from)
        alt(Key.DirectionLeft)
        alt(Key.DirectionLeft)
        assertEquals(Position.LEFT, now("t").area)
        assertTrue("← moved it to the left on screen", bounds("row:t").left < from)
        rule.onNodeWithTag("row:t").assertIsFocused()
    }

    @Test fun theChoicesOfAnAreaKeepLeftOnTheLeft() {
        page("t")
        fun part(text: Int) = rule.onNode(hasText(str(text)) and hasAnyAncestor(hasTestTag("area"))).fetchSemanticsNode().boundsInRoot.left
        assertTrue("Area: Left, Middle, Right as they are in the bar", part(R.string.areas_left) < part(R.string.areas_middle) && part(R.string.areas_middle) < part(R.string.areas_right))
    }

    @Test fun theLibrarysAddToKeepsLeftOnTheLeft() {
        page(null)
        fun part(text: Int) = rule.onNode(hasContentDescription("${str(R.string.areas_add_to)} ${str(text)}") and hasAnyAncestor(hasTestTag("add-to"))).fetchSemanticsNode().boundsInRoot.left
        assertTrue(part(R.string.areas_left) < part(R.string.areas_middle) && part(R.string.areas_middle) < part(R.string.areas_right))
    }

    @Test fun englishWordsReadFromLeftToRightAndAreSetToTheRight() {
        rule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                SettingsTheme(dark = false) { Text("Behind ‹.", Modifier.width(300.dp).testTag("words"), style = Bento.type.small) }
            }
        }
        val results = mutableListOf<TextLayoutResult>()
        rule.onNodeWithTag("words").fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        val layout = results.single()
        assertTrue("the full stop ends the sentence on its right", layout.getBoundingBox(8).left > layout.getBoundingBox(0).left)
        assertTrue("the arrow's sign follows its word", layout.getBoundingBox(7).left > layout.getBoundingBox(5).left)
        assertEquals("the line ends at the right edge", layout.size.width.toFloat(), layout.getLineRight(0), 1f)
        assertTrue(layout.getLineLeft(0) > layout.size.width / 2f)
    }

    @Test fun aSegmentsRaisedPartIsUnderTheChosenOneInBothDirections() {
        var direction by mutableStateOf(LayoutDirection.Ltr)
        var raised = 0
        var sunk = 0
        lateinit var view: View
        rule.setContent {
            view = LocalView.current
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                SettingsTheme(dark = false) {
                    raised = Bento.colors.raised.toArgb()
                    sunk = Bento.colors.sunk.toArgb()
                    Box(Modifier.padding(20.dp)) { Segment(listOf(SegmentPart("One"), SegmentPart("Two")), 0, {}, Modifier.width(300.dp).testTag("segment")) }
                }
            }
        }
        // The ground just inside a part's upper corner region, clear of its words.
        fun ground(text: String): Int {
            val at = rule.onNode(hasText(text) and hasAnyAncestor(hasTestTag("segment"))).fetchSemanticsNode().boundsInRoot
            return rule.runOnIdle {
                val picture = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
                picture.getPixel((at.left + 12).toInt(), at.center.y.toInt())
            }
        }
        assertEquals("the first part is raised", raised, ground("One"))
        assertEquals(sunk, ground("Two"))
        direction = LayoutDirection.Rtl
        rule.waitForIdle()
        val one = rule.onNode(hasText("One") and hasAnyAncestor(hasTestTag("segment"))).fetchSemanticsNode().boundsInRoot
        val two = rule.onNode(hasText("Two") and hasAnyAncestor(hasTestTag("segment"))).fetchSemanticsNode().boundsInRoot
        assertTrue("an ordinary choice is mirrored", one.left > two.left)
        assertEquals("the raised part went with it", raised, ground("One"))
        assertEquals(sunk, ground("Two"))
    }
}
