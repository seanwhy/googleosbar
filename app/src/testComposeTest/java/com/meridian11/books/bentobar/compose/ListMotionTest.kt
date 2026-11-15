package com.meridian11.books.bentobar.compose

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import com.meridian11.books.bentobar.data.BarConfig
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.data.Position
import com.meridian11.books.bentobar.data.Store
import com.meridian11.books.bentobar.items.Env
import com.meridian11.books.bentobar.ui.BarPage
import com.meridian11.books.bentobar.ui.LocalWindowLayout
import com.meridian11.books.bentobar.ui.Motion
import com.meridian11.books.bentobar.ui.SettingsTheme
import com.meridian11.books.bentobar.ui.Undo
import com.meridian11.books.bentobar.ui.windowLayout
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
 * Things travel: a row whose place changes goes there over the design's time, the rows around a move in
 * [Motion.NEIGHBOURS], every row to its lane in [Motion.AREA] when an area starts or closes. A row's shape changes at
 * once (one line, two lines); only its place travels. With "No animations" it is there at once.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w1200dp-h800dp-mdpi")
class ListMotionTest {
    @get:Rule val rule = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var before: BarConfig
    private var selected by mutableStateOf<String?>(null)
    private lateinit var view: View

    @Before fun env() { Env.init(app); before = Store.config.value; Undo.last.value = null }
    @After fun forget() { Store.update { before }; Undo.last.value = null }

    private fun item(id: String, type: String, area: Position = Position.RIGHT) = ItemConfig(id, type, area = area)

    private fun page(still: Boolean = false, added: Boolean = false) {
        Store.update { BarConfig(items = listOf(item("a", "clock"), item("b", "memory"), item("c", "battery")), position = Position.RIGHT, noAnimations = still) }
        if (added) Undo.change(added = "d") { it.copy(items = it.items + item("d", "cpu")) }
        rule.mainClock.autoAdvance = false
        rule.setContent {
            view = LocalView.current
            CompositionLocalProvider(LocalWindowLayout provides windowLayout(1200)) { SettingsTheme(dark = false) { BarPage(selected, onSelect = { selected = it }) } }
        }
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeByFrame()
    }

    private fun at(id: String): Offset = rule.onNodeWithTag("row:$id").fetchSemanticsNode().boundsInRoot.topLeft
    private fun change(to: (BarConfig) -> BarConfig) { rule.runOnUiThread { Store.update(to) }; rule.mainClock.advanceTimeByFrame(); rule.mainClock.advanceTimeByFrame() }
    private fun swapped(c: BarConfig) = c.copy(items = listOf(c.items[1], c.items[0], c.items[2]))
    private fun leftArea(c: BarConfig) = c.copy(items = c.items.map { if (it.id == "c") it.copy(area = Position.LEFT) else it })
    /** Where everything comes to rest after [change], seen in a page without animations. */
    private fun rest(id: String): Offset {
        rule.mainClock.advanceTimeBy(1000)
        return at(id)
    }

    @Test fun aRowWhosePlaceChangesTravelsThere() {
        page()
        val from = at("a")
        change(::swapped)
        rule.mainClock.advanceTimeBy(Motion.NEIGHBOURS / 2L)
        val half = at("a")
        assertTrue("half way it is between its places: $from, $half", half.y > from.y)
        val to = rest("a")
        assertTrue(half.y < to.y)
        assertEquals("it went down by a row and not sideways", from.x, to.x, 0.5f)
    }

    @Test fun theRowsAroundAMoveArriveInTheNeighboursTime() {
        page()
        change(::swapped)
        rule.mainClock.advanceTimeBy(Motion.NEIGHBOURS + 16L)
        val then = at("a")
        assertEquals(rest("a"), then)
    }

    @Test fun whenAnAreaStartsTheRowsTravelToTheirLanesAndTakeAnAreasTime() {
        page()
        val from = at("a")
        change(::leftArea)
        rule.mainClock.advanceTimeBy(Motion.NEIGHBOURS + 16L)
        val then = at("a")
        val to = rest("a")
        assertNotEquals("Right's lane is the right half now: its rows went there", from.x, to.x, 0.5f)
        assertTrue("and are still on their way after a neighbour's time: $from, $then, $to", then.x > from.x && then.x < to.x)
        // And back: the area closes, and the rows travel into the one list.
        change { c -> c.copy(items = c.items.map { it.copy(area = Position.RIGHT) }) }
        rule.mainClock.advanceTimeBy(Motion.NEIGHBOURS / 2L)
        assertTrue(at("a").x < to.x && at("a").x > from.x)
        assertEquals(from.x, rest("a").x, 0.5f)
    }

    @Test fun withNoAnimationsARowIsThereAtOnce() {
        page(still = true)
        val from = at("a")
        change(::swapped)
        val then = at("a")
        assertTrue(then.y > from.y)
        assertEquals("nothing is left to travel", then, rest("a"))
        change(::leftArea)
        val lanes = at("a")
        assertEquals(lanes, rest("a"))
    }

    /** How much of the lower half of a row's place is drawn on: pixels that differ from its ground. */
    private fun drawn(id: String): Int {
        val at = rule.onNodeWithTag("row:$id").fetchSemanticsNode().boundsInRoot
        val picture = rule.runOnUiThread<Bitmap> { Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) } }
        val ground = picture.getPixel(at.left.toInt() + 2, at.bottom.toInt() - 2)
        var n = 0
        for (y in (at.center.y.toInt() + 2) until at.bottom.toInt() - 2) for (x in at.left.toInt() + 2 until at.right.toInt() - 2) if (picture.getPixel(x, y) != ground) n++
        return n
    }
    private fun add() = change { c -> Undo.change(added = "d") { it.copy(items = it.items + item("d", "cpu")) }; Store.config.value }

    @Test fun aRowTheLibraryAddedOpensDownwardsWhileTheOthersMakeRoom() {
        page()
        add()
        assertEquals("at first only its upper edge shows", 0, drawn("d"))
        rule.mainClock.advanceTimeBy(Motion.NEIGHBOURS + 32L)
        assertTrue("then it is whole", drawn("d") > 0)
    }

    @Test fun theRowsAPageStartsWithAreSimplyThere_alsoOneThatWasJustAdded() {
        page(added = true)
        assertTrue(drawn("a") > 0)
        assertTrue("a page that opens while the last + can still be undone has that row whole", drawn("d") > 0)
    }

    @Test fun withNoAnimationsAnAddedRowIsWholeAtOnce() {
        page(still = true)
        add()
        assertTrue(drawn("d") > 0)
    }

    @Test fun theDurationsAreTheDesigns() {
        assertEquals(listOf(120, 160, 180, 200, 200), listOf(Motion.LIFT, Motion.NEIGHBOURS, Motion.SETTLE, Motion.BACK, Motion.AREA))
    }
}

/** The system's own "Remove animations": Compose is given a scale of zero for every duration, and nothing travels. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w1200dp-h800dp-mdpi")
class SystemMotionTest {
    @get:Rule val rule = createComposeRule(effectContext = object : MotionDurationScale { override val scaleFactor = 0f })
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var before: BarConfig

    @Before fun env() { Env.init(app); before = Store.config.value; Undo.last.value = null }
    @After fun forget() { Store.update { before }; Undo.last.value = null }

    @Test fun withTheSystemsAnimationsRemovedARowIsThereAtOnce() {
        Store.update { BarConfig(items = listOf(ItemConfig("a", "clock", area = Position.RIGHT), ItemConfig("b", "memory", area = Position.RIGHT)), position = Position.RIGHT) }
        rule.setContent { CompositionLocalProvider(LocalWindowLayout provides windowLayout(1200)) { SettingsTheme(dark = false) { BarPage(null, onSelect = {}) } } }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        fun at(id: String) = rule.onNodeWithTag("row:$id").fetchSemanticsNode().boundsInRoot.topLeft
        val from = at("a")
        rule.runOnUiThread { Store.update { it.copy(items = it.items.reversed()) } }
        rule.mainClock.advanceTimeByFrame(); rule.mainClock.advanceTimeByFrame(); rule.mainClock.advanceTimeByFrame()
        val then = at("a")
        assertTrue(then.y > from.y)
        rule.mainClock.advanceTimeBy(1000)
        assertEquals("nothing was left to travel", then, at("a"))
        // An area starts: the rows are in their lanes at once too.
        rule.runOnUiThread { Store.update { c -> c.copy(items = c.items.map { if (it.id == "b") it.copy(area = Position.LEFT) else it }) } }
        rule.mainClock.advanceTimeByFrame(); rule.mainClock.advanceTimeByFrame(); rule.mainClock.advanceTimeByFrame()
        val lanes = at("a")
        rule.mainClock.advanceTimeBy(1000)
        assertEquals(lanes, at("a"))
    }
}
