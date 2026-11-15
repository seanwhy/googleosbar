package com.meridian11.books.bentobar.compose

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.data.BarConfig
import com.meridian11.books.bentobar.data.HiddenMode
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.data.Pill
import com.meridian11.books.bentobar.data.Position
import com.meridian11.books.bentobar.data.Section
import com.meridian11.books.bentobar.data.Store
import com.meridian11.books.bentobar.data.putInArea
import com.meridian11.books.bentobar.items.Env
import com.meridian11.books.bentobar.items.ItemState
import com.meridian11.books.bentobar.ui.BarPreview
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
 * The picture of the bar on the Bar page: where its areas' strips stand between the mock clock and the mock system icons,
 * and what it says about the items it has no room for. Measured from the clock and the icons, which the picture draws
 * itself, so the numbers hold whatever the time is.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w1200dp-h800dp-mdpi")
class BarPreviewTest {
    @get:Rule val rule = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var before: BarConfig

    @Before fun env() { Env.init(app); before = Store.config.value }
    @After fun forget() = Store.update { before }

    /** What an item says, by id; one without words here says its own id. */
    private var words by mutableStateOf(emptyMap<String, String>())
    /** Items that draw nothing: no icon and no words, only a name for a screen reader. */
    private var quiet = emptySet<String>()

    private fun item(id: String, area: Position, section: Section = Section.SHOWN) = ItemConfig(id, "cpu", section, area = area)
    private fun wide(vararg ids: String) = ids.associateWith { "Some more words $it" }

    /** How wide the picture is. */
    private var width by mutableStateOf(1000.dp)

    private fun show(items: List<ItemConfig>, width: Dp = 1000.dp, direction: LayoutDirection = LayoutDirection.Ltr, change: BarConfig.() -> BarConfig = { this }) {
        this.width = width
        Store.update { BarConfig(items = items).change() }
        rule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                Box(Modifier.requiredWidth(this.width)) {
                    BarPreview(items.associate { it.id to if (it.id in quiet) ItemState(desc = "${it.id} desc") else ItemState(icon = "X", text = words[it.id] ?: it.id, desc = "${it.id} desc") }, null) {}
                }
            }
        }
        rule.waitForIdle()
    }

    private fun at(id: String): DpRect = rule.onNodeWithContentDescription("$id desc").getBoundsInRoot()
    /** An item the strip has no room for keeps its node and is not placed: only this tells the two apart. */
    private fun drawn(id: String) = rule.onNodeWithContentDescription("$id desc").isDisplayed()
    private fun clock() = rule.onNode(hasText("   ", substring = true)).getBoundsInRoot()
    /** The first of the mock system icons: the end of their row that is nearest the strip, left to right. */
    private fun icons() = rule.onNodeWithText("US").getBoundsInRoot()
    private fun more(n: Int) = app.resources.getQuantityString(R.plurals.bar_preview_more, n, n)
    private fun says(text: String) = rule.onNodeWithText(text, substring = true).assertExists()

    // The strip's own ends are 6 dp each (its pill's padding): an item is that far inside its strip.
    private val end = 6.dp
    /** The room on the two sides of a strip from [first] to [last] between the clock and the 12 dp before the icons. */
    private fun sides(first: String, last: String) = (at(first).left - end - clock().right) to (icons().left - 12.dp - (at(last).right + end))

    // ---- The first frame. ----

    /** What [view] draws right now. */
    private fun drawn(view: android.view.View): android.graphics.Bitmap = rule.runOnUiThread<android.graphics.Bitmap> {
        android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888).also { view.draw(android.graphics.Canvas(it)) }
    }
    /** The pixels of [box] in [picture]; mdpi, so a dp is a pixel. */
    private fun pixels(picture: android.graphics.Bitmap, box: DpRect): List<Int> {
        val l = box.left.value.toInt(); val t = box.top.value.toInt()
        val w = box.right.value.toInt() - l; val h = box.bottom.value.toInt() - t
        return IntArray(w * h).also { picture.getPixels(it, 0, w, l, t, w, h) }.toList()
    }

    @Test fun theItemsStandInThePicturesFirstFrameAndNeverFadeIn() {
        // The window's second frame can be seconds behind its first while the page is still being put together: an item
        // that comes in with the strip's fade is not in the picture until then. So no frame passes here before the first look.
        rule.mainClock.autoAdvance = false
        lateinit var view: android.view.View
        val items = listOf(item("a", Position.LEFT), item("b", Position.CENTER), item("c", Position.RIGHT))
        Store.update { BarConfig(items = items) }
        rule.setContent {
            view = androidx.compose.ui.platform.LocalView.current
            Box(Modifier.requiredWidth(1000.dp)) { BarPreview(items.associate { it.id to ItemState(icon = "X", text = it.id, desc = "${it.id} desc") }, null) {} }
        }
        val first = drawn(view)
        val boxes = items.map { at(it.id) }
        rule.mainClock.advanceTimeBy(1000)
        val later = drawn(view)
        boxes.forEachIndexed { i, box ->
            assertTrue("${items[i].id} is in the first frame as it is a second later", pixels(first, box) == pixels(later, box))
        }
    }

    // ---- One area: where the one strip always stood. These numbers were taken from the preview before it knew areas. ----

    @Test fun aLayoutOnTheRightEndsTwelveDpBeforeTheSystemIconsAsItAlwaysDid() {
        show(listOf(item("a", Position.RIGHT), item("b", Position.RIGHT))) { copy(position = Position.RIGHT) }
        assertEquals(icons().left - 12.dp - end, at("b").right)
        assertEquals("the strip is as high as the picture, less 3 dp above and below", 3.dp to 49.dp, at("b").top to at("b").bottom)
        assertTrue("a before b: ${at("a")}, ${at("b")}", at("a").right < at("b").left)
        says(app.getString(R.string.bar_preview_hint))
    }

    @Test fun aLayoutOnTheLeftBeginsSixteenDpAfterTheClockAsItAlwaysDid() {
        show(listOf(item("a", Position.LEFT), item("b", Position.LEFT))) { copy(position = Position.LEFT) }
        assertEquals(clock().right + 16.dp + end, at("a").left)
        assertEquals(3.dp to 49.dp, at("a").top to at("a").bottom)
    }

    @Test fun aLayoutInTheMiddleIsCentredBetweenTheClockAndTheSystemIconsAsItAlwaysWas() {
        show(listOf(item("a", Position.CENTER), item("b", Position.CENTER))) { copy(position = Position.CENTER) }
        val (left, right) = sides("a", "b")
        // The two spacers share the room, and an odd pixel goes to the second.
        assertTrue("$left, $right", (right - left).value in 0f..1f && left.value > 100f)
    }

    // Right and Left are sides of the screen, as the bar's own windows are: the picture is mirrored around the strip.

    @Test fun inARightToLeftLanguageAOneAreaLayoutOnTheRightKeepsItsSideAndStandsBesideTheClock() {
        show(listOf(item("a", Position.RIGHT), item("b", Position.RIGHT)), direction = LayoutDirection.Rtl) { copy(position = Position.RIGHT) }
        // The clock is on the right there.
        assertEquals(clock().left - 16.dp - end, at("b").right)
        assertTrue("the strip's own order is not mirrored: ${at("a")}, ${at("b")}", at("a").right < at("b").left)
    }

    @Test fun inARightToLeftLanguageAOneAreaLayoutOnTheLeftKeepsItsSideAndStandsBesideTheIcons() {
        show(listOf(item("a", Position.LEFT), item("b", Position.LEFT)), direction = LayoutDirection.Rtl) { copy(position = Position.LEFT) }
        // The icons are on the left there, "US" at their right end.
        assertEquals(icons().right + 12.dp + end, at("a").left)
        assertTrue("the strip's own order is not mirrored: ${at("a")}, ${at("b")}", at("a").right < at("b").left)
    }

    @Test fun aOneAreaLayoutWithNoRoomForAllKeepsItsLastItemsAndCountsTheRestAsItAlwaysDid() {
        // 600 dp wide, the strip's items may take 220 dp: one of these, and two would need about 280.
        words = wide("a", "b", "c")
        show(listOf(item("a", Position.RIGHT), item("b", Position.RIGHT), item("c", Position.RIGHT)), width = 600.dp) { copy(position = Position.RIGHT) }
        assertEquals(listOf(false, false, true), listOf("a", "b", "c").map { drawn(it) })
        assertEquals(icons().left - 12.dp - end, at("c").right)
        says(app.getString(R.string.bar_preview_hint) + " " + more(2))
        // ‹ is there for what has no room, at the strip's left.
        val chevron = rule.onNodeWithContentDescription(app.getString(R.string.chevron_show)).getBoundsInRoot()
        assertTrue("$chevron, ${at("c")}", chevron.right <= at("c").left)
    }

    @Test fun aOneAreaLayoutOnTheLeftKeepsItsFirstItemsAndHasItsChevronAtItsRight() {
        words = wide("a", "b", "c")
        show(listOf(item("a", Position.LEFT), item("b", Position.LEFT), item("c", Position.LEFT)), width = 600.dp) { copy(position = Position.LEFT) }
        assertEquals(listOf(true, false, false), listOf("a", "b", "c").map { drawn(it) })
        assertEquals(clock().right + 16.dp + end, at("a").left)
        val chevron = rule.onNodeWithContentDescription(app.getString(R.string.chevron_show)).getBoundsInRoot()
        assertTrue("$chevron, ${at("a")}", chevron.left >= at("a").right)
        says(more(2))
    }

    @Test fun hiddenItemsComeOutAtAPressOnTheChevronAndTheHintSaysSo() {
        show(listOf(item("h", Position.RIGHT, Section.HIDDEN), item("a", Position.RIGHT))) { copy(position = Position.RIGHT, hiddenMode = HiddenMode.CLICK) }
        says(app.getString(R.string.bar_preview_hint_hidden))
        assertEquals(listOf(false, true), listOf("h", "a").map { drawn(it) })
        rule.onNodeWithContentDescription(app.getString(R.string.chevron_show)).performClick()
        rule.waitForIdle()
        assertTrue("the hidden item is out, on the far side of ‹: ${at("h")}, ${at("a")}", at("h").right < at("a").left)
        assertEquals(icons().left - 12.dp - end, at("a").right)
        rule.onNodeWithContentDescription(app.getString(R.string.chevron_hide)).performClick()
        rule.waitForIdle()
        assertEquals(listOf(false, true), listOf("h", "a").map { drawn(it) })
    }

    // ---- Several areas: each where the bar has it, by the bar's rule of room. ----

    private val three = listOf(item("l", Position.LEFT), item("m", Position.CENTER), item("r", Position.RIGHT))
    private fun chevrons() = rule.onAllNodesWithContentDescription(app.getString(R.string.chevron_show)).let { all ->
        all.fetchSemanticsNodes().indices.filter { all[it].isDisplayed() }.map { all[it].getBoundsInRoot() }.sortedBy { it.left }
    }
    /** The room between the clock and the 12 dp before the icons, which the areas share. */
    private fun room() = icons().left - 12.dp - clock().right

    @Test fun theItemsAreasSayWhereTheStripStandsAndTheOldPositionSettingSaysNothing() {
        show(listOf(item("a", Position.LEFT), item("b", Position.LEFT))) { copy(position = Position.RIGHT) }
        assertEquals(clock().right + 16.dp + end, at("a").left)
    }

    @Test fun threeAreasStandAtTheLeftEdgeInTheMiddleAndAtTheRightEdge() {
        show(three)
        assertEquals("Left begins where a layout on the left begins", clock().right + 16.dp + end, at("l").left)
        assertEquals("Right ends where a layout on the right ends", icons().left - 12.dp - end, at("r").right)
        val (left, right) = sides("m", "m")
        assertTrue("the Middle is centred as a layout in the middle is: $left, $right", (right - left).value in 0f..1f)
        assertTrue("no two overlap: ${at("l")}, ${at("m")}, ${at("r")}", at("l").right < at("m").left && at("m").right < at("r").left)
        assertEquals(3.dp to 49.dp, at("m").top to at("m").bottom)
        rule.onNodeWithText(app.getString(R.string.bar_preview_hint)).assertExists()
    }

    // An area that goes out of use while the picture stands: its strip is still composed until the next measure, with a
    // layout that no longer has it.

    @Test fun anAreasLastItemMovedToAnotherAreaLeavesAPictureOfTheAreasThatAreLeft() {
        show(three)
        rule.runOnUiThread { Store.update { it.putInArea("m", Position.RIGHT) } }
        rule.waitForIdle()
        assertEquals("Right ends where it did", icons().left - 12.dp - end, at("r").right)
        assertTrue("the moved item stands in Right, before the one that was there: ${at("m")}, ${at("r")}",
            at("m").right < at("r").left && at("r").left - at("m").right < 40.dp)
        assertEquals("Left stays", clock().right + 16.dp + end, at("l").left)
        // And back: the Middle has its strip again.
        rule.runOnUiThread { Store.update { it.putInArea("m", Position.CENTER) } }
        rule.waitForIdle()
        val (left, right) = sides("m", "m")
        assertTrue("the Middle is centred again: $left, $right", (right - left).value in 0f..1f)
    }

    @Test fun aLayoutWithOneAreaFewerTakesTheOldOnesPlaceInThePicture() {
        show(three)
        for (gone in listOf(Position.CENTER, Position.LEFT)) {
            rule.runOnUiThread { Store.update { BarConfig(items = three.filter { item -> item.area != gone }) } }
            rule.waitForIdle()
            assertEquals("without $gone", three.map { it.area != gone }, three.map { rule.onAllNodesWithContentDescription("${it.id} desc").fetchSemanticsNodes().isNotEmpty() })
            assertEquals(icons().left - 12.dp - end, at("r").right)
            rule.runOnUiThread { Store.update { BarConfig(items = three) } }
            rule.waitForIdle()
            assertTrue("three again: ${at("l")}, ${at("m")}, ${at("r")}", at("l").right < at("m").left && at("m").right < at("r").left)
        }
    }

    @Test fun aWideRightMovesTheMiddleLeftOnlyAsFarAsItMustAndNeverMakesItJump() {
        words = mapOf("r1" to "W")
        show(listOf(item("m", Position.CENTER), item("r1", Position.RIGHT), item("r2", Position.RIGHT)))
        val centred = at("m").left
        var last = centred
        var edge = at("r1").left
        var moved = false
        for (n in 2..60) {
            rule.runOnUiThread { words = mapOf("r1" to "W".repeat(n)) }
            rule.waitForIdle()
            if (!drawn("r1") || !drawn("m")) break // the Right is full, or the Middle has no room left
            val now = at("m").left
            val grown = edge - at("r1").left
            assertTrue("with $n letters the Middle went from $last to $now while the Right grew by $grown", now <= last && last - now <= grown)
            if (now < centred) {
                moved = true
                assertEquals("6 dp between the two strips", at("r1").left - end - 6.dp - end, at("m").right)
            }
            last = now
            edge = at("r1").left
        }
        assertTrue("the Right got wide enough to move the Middle", moved)
    }

    @Test fun whatTheAreasHaveNoRoomForIsCountedOnceUnderThePicture() {
        // 600 dp wide: the Left keeps one of its two, and what it leaves is too little for any of the others' items.
        words = wide("l1", "l2", "m", "r1", "r2")
        show(listOf(item("l1", Position.LEFT), item("l2", Position.LEFT), item("m", Position.CENTER), item("r1", Position.RIGHT), item("r2", Position.RIGHT)), width = 600.dp)
        assertEquals(listOf(true, false, false, false, false), listOf("l1", "l2", "m", "r1", "r2").map { drawn(it) })
        rule.onNodeWithText(app.getString(R.string.bar_preview_hint) + " " + more(4)).assertExists()
    }

    @Test fun anAreaWithNoRoomForAChevronIsNotDrawnAndItsItemsAreCounted() {
        show(listOf(item("l", Position.LEFT), item("r", Position.RIGHT)))
        // The Left's one item with its strip's ends after the 16 dp beside the clock, and 6 dp to the Right, leave the Right
        // 33 dp: a ‹ needs 30 and its strip's ends 12.
        width += (at("l").right - at("l").left) + end * 2 + 6.dp + 16.dp + 33.dp - room()
        rule.waitForIdle()
        assertEquals(listOf(true, false), listOf("l", "r").map { drawn(it) })
        assertEquals("no ‹ where there is no room for one", 0, chevrons().size)
        rule.onNodeWithText(app.getString(R.string.bar_preview_hint) + " " + more(1)).assertExists()
        // With room for a ‹ and not for the item, the Right has its ‹ for it.
        width += 20.dp
        rule.waitForIdle()
        assertEquals(listOf(true, false), listOf("l", "r").map { drawn(it) })
        assertEquals(1, chevrons().size)
        assertEquals("at the Right's place", icons().left - 12.dp - end, chevrons().single().right)
        rule.onNodeWithText(app.getString(R.string.bar_preview_hint) + " " + more(1)).assertExists()
    }

    @Test fun inARightToLeftLanguageTheAreasKeepTheirSides() {
        show(three, direction = LayoutDirection.Rtl)
        // The picture is mirrored around them: the clock is on the right, the icons on the left with "US" at their right end.
        assertEquals("Right ends 16 dp before the clock", clock().left - 16.dp - end, at("r").right)
        assertEquals("Left begins 12 dp after the icons", icons().right + 12.dp + end, at("l").left)
        val left = at("m").left - end - (icons().right + 12.dp)
        val right = clock().left - (at("m").right + end)
        assertTrue("the Middle is centred: $left, $right", (left - right).value in 0f..1f)
        assertTrue("${at("l")}, ${at("m")}, ${at("r")}", at("l").right < at("m").left && at("m").right < at("r").left)
    }

    @Test fun eachAreaHasItsChevronOnItsOwnSideAndAPressOnOneBringsOutEveryAreasHiddenItems() {
        show(listOf(item("a", Position.LEFT), item("h1", Position.LEFT, Section.HIDDEN), item("h2", Position.RIGHT, Section.HIDDEN), item("b", Position.RIGHT))) {
            copy(hiddenMode = HiddenMode.CLICK)
        }
        val (ofLeft, ofRight) = chevrons()
        assertTrue("the Left's ‹ is at its right end, the Right's at its left end", ofLeft.left >= at("a").right && ofRight.right <= at("b").left)
        assertEquals(listOf(false, false), listOf("h1", "h2").map { drawn(it) })
        rule.onAllNodesWithContentDescription(app.getString(R.string.chevron_show))[0].performClick()
        rule.waitForIdle()
        assertTrue("each area's hidden items are out beside its own ‹: ${at("h1")}, ${at("h2")}", at("h1").left > at("a").right && at("h2").right < at("b").left)
        assertEquals("the areas stay where they are", (clock().right + 16.dp + end) to (icons().left - 12.dp - end), at("a").left to at("b").right)
    }

    @Test fun theHintTellsOfHiddenItemsInAnyArea() {
        show(listOf(item("a", Position.LEFT), item("h", Position.LEFT, Section.HIDDEN), item("b", Position.RIGHT))) { copy(hiddenMode = HiddenMode.CLICK) }
        says(app.getString(R.string.bar_preview_hint_hidden))
    }

    @Test fun anAreaThatDrawsNothingHasNoCapsuleBesideAnothersItems() {
        quiet = setOf("l")
        show(listOf(item("l", Position.LEFT), item("r", Position.RIGHT))) { copy(pill = Pill.SUBTLE) }
        assertEquals(listOf(false, true), listOf("l", "r").map { drawn(it) })
    }

    @Test fun theEmptyCapsuleIsTheHomeAreasWhileNoAreaDraws() {
        quiet = setOf("l", "r")
        show(listOf(item("l", Position.LEFT), item("r", Position.RIGHT))) { copy(pill = Pill.SUBTLE) }
        assertEquals(listOf(true, false), listOf("l", "r").map { drawn(it) })
        // Without a pill there is no capsule to show, in any area.
        rule.runOnUiThread { Store.update { it.copy(pill = Pill.NONE) } }
        rule.waitForIdle()
        assertEquals(listOf(false, false), listOf("l", "r").map { drawn(it) })
    }
}
