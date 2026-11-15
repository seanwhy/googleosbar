package com.meridian11.books.bentobar.compose

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.bar.BarOverflow
import com.meridian11.books.bentobar.bar.BarStatus
import com.meridian11.books.bentobar.data.BarConfig
import com.meridian11.books.bentobar.data.Display
import com.meridian11.books.bentobar.data.HiddenMode
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.data.Position
import com.meridian11.books.bentobar.data.Section
import com.meridian11.books.bentobar.data.Store
import com.meridian11.books.bentobar.items.Env
import com.meridian11.books.bentobar.ui.BarPage
import com.meridian11.books.bentobar.ui.LocalPreviewClock
import com.meridian11.books.bentobar.ui.Page
import com.meridian11.books.bentobar.ui.Palette
import com.meridian11.books.bentobar.ui.TRASH_VEIL
import com.meridian11.books.bentobar.ui.SettingsTheme
import com.meridian11.books.bentobar.ui.Shell
import com.meridian11.books.bentobar.ui.Undo
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
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The Bar page as it is drawn, in the window, in light and dark: the list's card and the panel's are the palette's panel,
 * the selected row stands on the soft ground, and Undo's slip on the sunk one. With the environment variable
 * `BENTOBAR_PICTURES` naming a directory, each picture is also written there as a PNG, to look at beside the design.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w1180dp-h780dp-mdpi")
class BarPagePicturesTest {
    @get:Rule val rule = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var before: BarConfig
    private lateinit var view: View
    private var dark by mutableStateOf(false)
    private var selected by mutableStateOf<String?>(null)
    /** BentoBar's service is on, as in every picture but the one of the bar arranged before it is. */
    private var running = true

    @Before fun env() { Env.init(app); before = Store.config.value; Undo.last.value = null }
    @After fun forget() { Store.update { before }; Undo.last.value = null; BarOverflow.ids.value = emptySet() }

    private fun picture(name: String): Bitmap {
        val bitmap = rule.runOnIdle { Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) } }
        System.getenv("BENTOBAR_PICTURES")?.takeIf { it.isNotBlank() }?.let { dir ->
            File(dir).mkdirs()
            File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        return bitmap
    }

    private fun assertColor(what: String, want: Color, bitmap: Bitmap, x: Int, y: Int) =
        assertEquals(what, Integer.toHexString(want.toArgb()), Integer.toHexString(bitmap.getPixel(x, y)))

    /** A veil over a ground, which the canvas rounds its own way: each channel within two steps of [want]. */
    private fun assertVeil(what: String, want: Color, bitmap: Bitmap, x: Int, y: Int) {
        val got = bitmap.getPixel(x, y)
        val wanted = want.toArgb()
        assertTrue("$what: ${Integer.toHexString(wanted)} wanted, ${Integer.toHexString(got)} drawn",
            listOf(16, 8, 0).all { kotlin.math.abs((got shr it and 0xFF) - (wanted shr it and 0xFF)) <= 2 })
    }

    private fun palette() = if (dark) Palette.Dark else Palette.Light

    /** A bar of seven items and two that wait for their rule. */
    private fun window(mode: HiddenMode = HiddenMode.SHOW_ALL, select: String? = null) {
        fun item(id: String, type: String, display: Display = Display.ICON_AND_TEXT) = ItemConfig(id, type, display = display, area = Position.RIGHT)
        fun waiting(id: String, type: String) = ItemConfig(id, type, Section.HIDDEN, whenActive = true, area = Position.RIGHT)
        show(BarConfig(items = listOf(item("event", "event"), item("weather", "weather"), item("clock", "clock"), item("timer", "timer"),
            item("network", "network", Display.TEXT), item("cpu", "cpu"), item("sound", "sound", Display.ICON),
            waiting("media", "media"), waiting("devices", "devices")), position = Position.RIGHT, hiddenMode = mode), select)
    }

    /** The same bar in areas: [left] after the clock, [middle] in the middle, the rest and the two that wait at the right. */
    private fun areas(left: Set<String> = setOf("event", "weather", "clock"), middle: Set<String> = setOf("timer", "caffeine"), select: String? = null) {
        fun item(id: String, display: Display = Display.ICON_AND_TEXT) = ItemConfig(id, id, display = display,
            area = if (id in left) Position.LEFT else if (id in middle) Position.CENTER else Position.RIGHT)
        fun waiting(id: String) = ItemConfig(id, id, Section.HIDDEN, whenActive = true, area = Position.RIGHT)
        show(BarConfig(items = listOf(item("event"), item("weather"), item("clock"), item("timer"), item("caffeine", Display.ICON),
            item("network", Display.TEXT), item("cpu"), item("memory"), item("sound", Display.ICON), item("prompter"), item("tools"),
            waiting("media"), waiting("devices")), position = Position.RIGHT), select)
    }

    private fun show(cfg: BarConfig, select: String?) {
        Store.update { cfg }
        selected = select
        rule.setContent {
            view = LocalView.current
            // The mock clock at one time: an hour of two digits is wider, and at 640 dp that is the room Left's strip has.
            CompositionLocalProvider(LocalPreviewClock provides { MORNING }) {
                SettingsTheme(dark) {
                    Shell(Page.BAR, {}, running, enabled = true, status = if (running) BarStatus.SHOWN else BarStatus.STOPPED, onEnabled = {}, onTurnOn = {},
                        insets = WindowInsets(top = 36.dp)) {
                        BarPage(selected, onSelect = { selected = it }, running = running)
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun bounds(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private companion object {
        val MORNING: ZonedDateTime = ZonedDateTime.of(2026, 11, 10, 9, 41, 0, 0, ZoneId.systemDefault())
    }

    @Test fun theListAndThePanelAreCardsOnThePagesGround() {
        window()
        for (isDark in listOf(false, true)) {
            dark = isDark
            val bitmap = picture("main-${if (isDark) "dark" else "light"}")
            val list = bounds("bar-list")
            assertColor("the page's ground beside the list", palette().surface, bitmap, list.left.toInt() - 6, list.center.y.toInt())
            assertColor("the list's card", palette().panel, bitmap, list.left.toInt() + 6, list.bottom.toInt() - 24)
        }
    }

    @Test fun beforeBentoBarIsOnThePreviewIsSolidTealAndSaysTheBarCanBeArrangedNow() {
        running = false
        window()
        val hint = app.getString(R.string.bar_preview_hint_off)
        both("bar-serviceoff") { bitmap ->
            rule.onNodeWithTag("preview-line").assertTextEquals(hint)
            val bar = bounds("preview")
            // Solid: the same teal near both ends, in light and in dark.
            for (x in listOf(bar.left.toInt() + 8, bar.right.toInt() - 8))
                assertColor("the preview's ground at $x", palette().stripOff, bitmap, x, bar.top.toInt() + 26)
        }
        // The list is there to arrange, and the header offers Turn on.
        rule.onNodeWithTag("bar-list").assertExists()
        rule.onNode(hasText(app.getString(R.string.setup_turn_on))).assertExists()
    }

    @Test fun thePreviewHasTheWindowsOneSoftShadowUnderIt() { window(); shadowed("preview-shadow") }
    @Test fun thePreviewHasItsShadowBeforeBentoBarIsOnToo() { running = false; window(); shadowed("preview-shadow-off") }

    /** Under the preview the page's ground is darker, most right under it; above the bar it is next to nothing. */
    private fun shadowed(name: String) = both(name) { bitmap ->
        fun light(argb: Int) = (argb shr 16 and 0xFF) + (argb shr 8 and 0xFF) + (argb and 0xFF)
        val bar = bounds("preview")
        val x = bar.right.toInt() - 60
        val foot = bar.top.toInt() + 52
        val ground = light(palette().surface.toArgb())
        val near = light(bitmap.getPixel(x, foot + 3)); val far = light(bitmap.getPixel(x, foot + 26)); val above = light(bitmap.getPixel(x, bar.top.toInt() - 4))
        assertTrue("a shadow right under the bar: $near against $ground", near <= ground - 6)
        assertTrue("which fades downwards: $near, then $far", far > near)
        assertTrue("and is under the bar far more than over it: $above above, $near below", above >= ground - 15 && above - near >= 6)
    }

    @Test fun onceBentoBarIsOnThePreviewSaysHowToEditAnItem() {
        window()
        rule.onNodeWithTag("preview-line").assertTextEquals(app.getString(R.string.bar_preview_hint))
        val bitmap = picture("firstrun-3-live")
        val bar = bounds("preview")
        assertEquals("the bar's own look, not the teal", false,
            Integer.toHexString(bitmap.getPixel(bar.left.toInt() + 8, bar.top.toInt() + 26)) == Integer.toHexString(palette().stripOff.toArgb()))
    }

    @Test fun theSelectedRowStandsOnTheSoftGround() {
        window(select = "network")
        for (isDark in listOf(false, true)) {
            dark = isDark
            val bitmap = picture("selected-${if (isDark) "dark" else "light"}")
            val row = bounds("row:network")
            assertColor("the selected row", palette().soft, bitmap, row.left.toInt() + 8, row.center.y.toInt())
            val other = bounds("row:cpu")
            assertColor("a row that is not selected", palette().panel, bitmap, other.left.toInt() + 8, other.center.y.toInt())
            // Between a row's label and its control.
            val showAs = bounds("show-as")
            assertColor("the panel's card", palette().panel, bitmap, showAs.left.toInt() - 4, showAs.center.y.toInt())
        }
        // The other states of the options, to look at.
        dark = false
        selected = "sound"
        picture("options-no-rule")
        selected = "event"
        picture("options-permission")
        selected = "devices"
        picture("options-when-active")
    }

    @Test fun undoStandsOnTheSunkGroundInTheRemovedRowsPlace() {
        window(select = "timer")
        val place = bounds("row:timer")
        rule.onNode(hasText(app.getString(R.string.detail_remove)) and hasClickAction()).performClick()
        rule.waitForIdle()
        for (isDark in listOf(false, true)) {
            dark = isDark
            val bitmap = picture("removed-${if (isDark) "dark" else "light"}")
            val slip = bounds("slip")
            assertEquals("where the row was", place.top, slip.top)
            // Before its words begin.
            assertColor("the slip", palette().sunk, bitmap, slip.left.toInt() - 4, slip.center.y.toInt())
        }
    }

    @Test fun withTheArrowTheLowerPartIsWhatIsBehindIt() {
        window(HiddenMode.CLICK)
        picture("chevron-light")
        rule.onNodeWithTag("row:media").performClick()
        rule.waitForIdle()
        val bitmap = picture("chevron-selected-light")
        val row = bounds("row:media")
        assertColor("the selected row under the line", palette().soft, bitmap, row.left.toInt() + 8, row.center.y.toInt())
    }

    @Config(qualifiers = "w960dp-h780dp-mdpi")
    @Test fun at960TheListIsThePanelsFirstPart() {
        window()
        picture("mid-list")
        rule.onNodeWithTag("row:network").performClick()
        rule.waitForIdle()
        val bitmap = picture("mid-options")
        val showAs = bounds("show-as")
        assertColor("the panel's card", palette().panel, bitmap, showAs.left.toInt() - 4, showAs.center.y.toInt())
    }

    private fun click(tag: String) { rule.onNodeWithTag(tag).performScrollTo(); rule.onNodeWithTag(tag).performClick(); rule.waitForIdle() }

    @Test fun theLibraryIsOneCardWithTheFilterAndTheTypesInTheirGroups() {
        window()
        picture("library-top")
        rule.onNodeWithTag("type:timer").performScrollTo()
        rule.waitForIdle()
        for (isDark in listOf(false, true)) {
            dark = isDark
            val bitmap = picture("library-${if (isDark) "dark" else "light"}")
            val library = bounds("library")
            assertColor("the library's card", palette().panel, bitmap, library.left.toInt() + 6, library.center.y.toInt())
        }
        dark = false
        // A type added: its row says so, with the Undo, and the bar's new row is marked.
        click("add:countdown")
        picture("library-added")
        // A quiet type: under the line, and its row says when it shows.
        click("add:heat")
        picture("library-added-quiet")
    }

    @Test fun theFilterShowsWhatMatchesAndCountsIt() {
        window()
        rule.onNodeWithTag("filter").performTextInput("clo")
        rule.waitForIdle()
        val bitmap = picture("library-filter")
        val count = bounds("filter-count")
        assertColor("the count stands on the card", palette().panel, bitmap, count.left.toInt() - 4, count.center.y.toInt())
        rule.onNodeWithTag("filter").performTextInput("zzz")
        rule.waitForIdle()
        picture("library-filter-nothing")
    }

    @Test fun afterRemoveThePanelIsTheLibraryAtTheItemsType() {
        window(select = "clock")
        rule.onNodeWithTag("row:clock").assertExists()
        Store.updateItem("clock") { it.with("zone", "Asia/Tokyo") }
        rule.waitForIdle()
        rule.onNode(hasText(app.getString(R.string.detail_remove)) and hasClickAction()).performClick()
        rule.waitForIdle()
        picture("library-after-remove")
        rule.onNodeWithTag("type:clock").assertIsDisplayed()
        assertEquals("deleted, with what it was set to", null, Store.config.value.items.firstOrNull { it.id == "clock" })
    }

    @Config(qualifiers = "w960dp-h780dp-mdpi")
    @Test fun at960AddItemsIsTheSwitchsSecondPart() {
        window()
        rule.onNode(hasText(app.getString(R.string.page_add)) and hasAnyAncestor(hasTestTag("panel-switch"))).performClick()
        rule.waitForIdle()
        val bitmap = picture("mid-library")
        val library = bounds("library")
        assertColor("the library's card", palette().panel, bitmap, library.left.toInt() + 6, library.center.y.toInt())
    }

    @Config(qualifiers = "w640dp-h780dp-mdpi")
    @Test fun at640TheLibraryTakesTheWindow() {
        window()
        rule.onNode(hasText(app.getString(R.string.page_add)) and hasAnyAncestor(hasTestTag("panel-switch"))).performClick()
        rule.waitForIdle()
        picture("narrow-library")
        click("add:countdown")
        picture("narrow-library-added")
    }

    // ---- Areas ----

    private fun top(tag: String) = bounds(tag).top
    private fun both(name: String, check: (Bitmap) -> Unit = {}) {
        for (isDark in listOf(false, true)) {
            dark = isDark
            check(picture("$name-${if (isDark) "dark" else "light"}"))
        }
        dark = false
    }

    @Test fun threeAreasAreThreeLanesWithTheLowerPartsAtOneHeight() {
        areas()
        both("areas-three") { bitmap ->
            val list = bounds("bar-list")
            assertColor("the lanes stand on the list's card", palette().panel, bitmap, list.center.x.toInt(), list.bottom.toInt() - 24)
        }
        assertEquals("a lower part without rows starts where its neighbour's rows do", top("row:media"), top("blank:LEFT"))
        assertEquals(top("blank:LEFT"), top("blank:CENTER"))
        assertEquals("the lanes' first rows stand at one height", top("row:event"), top("row:timer"))
        assertEquals(top("row:event"), top("row:network"))
        assert(bounds("row:event").right <= bounds("row:timer").left) { "Left's rows end before the Middle's begin" }
        assert(bounds("row:timer").right <= bounds("row:network").left) { "the Middle's rows end before Right's begin" }
        assert(bounds("bracket:LEFT").right <= bounds("bracket:CENTER").left && bounds("bracket:CENTER").right <= bounds("bracket:RIGHT").left) { "one bracket for each strip, in the bar's order" }
        // An item of one area selected: its bracket takes the accent, and Area is the first of its options.
        rule.onNodeWithTag("row:timer").performClick()
        rule.waitForIdle()
        picture("areas-three-selected")
    }

    @Test fun twoAreasAreTwoLanesAndTheLastHeadEndsInAddAnArea() {
        areas(middle = emptySet())
        both("areas-two")
        rule.onNodeWithTag("add-area").assertIsDisplayed()
        assert(bounds("add-area").left > bounds("row:timer").left) { "\"Add an area\" stands at the end of the last lane's head" }
    }

    @Test fun anOpenedLaneIsEmptyBesideTheBarsRowsAndTheLibraryAddsToIt() {
        window()
        rule.onNodeWithTag("add-area").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("add-area:LEFT").performClick()
        rule.waitForIdle()
        both("areas-new")
        assertEquals("the empty lane starts where the rows beside it do", top("row:event"), top("lane:LEFT"))
        assertEquals("and is as tall as they are together", bounds("row:sound").bottom, bounds("lane:LEFT").bottom)
        rule.onNodeWithTag("add-to").assertIsDisplayed()
    }

    @Test fun aLaneThatClosesIsSaidAcrossTheTopAndItsRowIsMarked() {
        areas(middle = setOf("timer"), select = "timer")
        rule.onNode(hasText(app.getString(R.string.areas_right)) and hasAnyAncestor(hasTestTag("area"))).performClick()
        rule.waitForIdle()
        both("areas-fold") { bitmap ->
            val fold = bounds("fold")
            assertColor("the slip stands on the sunk ground", palette().sunk, bitmap, fold.left.toInt() - 4, fold.center.y.toInt())
        }
        assert(bounds("fold").bottom <= top("row:event")) { "the slip stands above the lanes" }
        rule.onNodeWithTag("moved-mark", useUnmergedTree = true).assertExists()
    }

    @Test fun aRemovedAreaIsSaidAcrossTheTopWithUndoAndTheLanesThatStayKeepTheirTrash() {
        areas()
        for (area in Position.entries) rule.onNodeWithTag("remove-area:$area").assertIsDisplayed()
        click("remove-area:CENTER")
        both("areas-removed") { bitmap ->
            val note = bounds("area-removed")
            assertColor("the note stands on the sunk ground", palette().sunk, bitmap, note.left.toInt() - 4, note.center.y.toInt())
        }
        assert(bounds("area-removed").bottom <= top("row:event")) { "the note stands above the lanes" }
        rule.onNodeWithTag("row:timer").assertDoesNotExist()
        rule.onNodeWithTag("remove-area:LEFT").assertIsDisplayed()
        rule.onNodeWithTag("remove-area:RIGHT").assertIsDisplayed()
    }

    @Test fun anAreaWithoutRoomSaysSoOnceAndTheBarsPictureShowsWhoHasItsPlace() {
        areas(select = "clock")
        BarOverflow.ids.value = setOf("timer", "caffeine", "clock")
        rule.waitForIdle()
        both("areas-no-room") { bitmap ->
            val notice = bounds("no-room")
            assertColor("the notice stands on the warning ground", palette().warnGround, bitmap, notice.left.toInt() + 4, notice.center.y.toInt())
        }
        assertEquals("the notice stands above the Middle's rows", true, bounds("no-room").bottom <= top("row:timer"))
    }

    @Config(qualifiers = "w960dp-h780dp-mdpi")
    @Test fun at960TheLanesStandInThePanelsFirstPart() {
        areas()
        picture("mid-areas")
        assertEquals(top("row:event"), top("row:timer"))
    }

    @Config(qualifiers = "w640dp-h780dp-mdpi")
    @Test fun at640TheAreasAreOneListWithAPlusOnEachHead() {
        areas()
        both("narrow-areas")
        assert(bounds("row:event").bottom <= top("row:timer")) { "one column: the Middle's rows stand under Left's" }
        rule.onNodeWithTag("add-to:LEFT").assertIsDisplayed()
        assert(bounds("remove-area:LEFT").left >= bounds("add-to:LEFT").right) { "a head's trash comes after its +" }
        rule.onNodeWithTag("bracket:LEFT").assertIsDisplayed()
    }

    @Config(qualifiers = "w640dp-h780dp-mdpi")
    @Test fun at640TheRowsHaveTwoLines() {
        window()
        val bitmap = picture("narrow-list")
        val list = bounds("bar-list")
        assertColor("the list's card", palette().panel, bitmap, list.left.toInt() + 6, list.bottom.toInt() - 24)
    }

    // ---- A drag, in mid-flight ----

    private fun middle(tag: String) = bounds(tag).center
    /** Press at [from] and carry to [to] without letting go. */
    @OptIn(ExperimentalTestApi::class)
    private fun carry(from: Offset, to: Offset) {
        rule.onRoot().performMouseInput {
            moveTo(from)
            press()
            for (i in 1..6) moveTo(from + (to - from) * (i / 6f))
        }
        rule.waitForIdle()
    }
    @OptIn(ExperimentalTestApi::class)
    private fun letGo() { rule.onRoot().performMouseInput { release() }; rule.waitForIdle() }

    /** The trash's own ground: a point inside the strip, clear of its rounded ends, its glyph and its words. */
    private fun inTrash() = bounds("trash").let { Offset(it.left + 40f, it.top + 24f) }

    @Test fun aRowCarriedInTheListLeavesAnOpeningAndTheTrashStandsAlongTheCardsLowerEdge() {
        window()
        val card = bounds("bar-list")
        val filter = bounds("filter")
        val weather = bounds("row:weather")
        carry(middle("row:cpu"), Offset(weather.center.x + 60f, weather.bottom + 14f))
        both("drag-bar") { bitmap ->
            val trash = bounds("trash")
            assertEquals("the trash is the card's lower edge, as wide as the card", listOf(card.left, card.right, card.bottom), listOf(trash.left, trash.right, trash.bottom))
            assertEquals("the library is as it was", filter, bounds("filter"))
            assertColor("at rest the trash is a sunk strip", palette().sunk, bitmap, inTrash().x.toInt(), inTrash().y.toInt())
            assertColor("on the list's card", palette().panel, bitmap, trash.center.x.toInt(), trash.bottom.toInt() - 4)
            assertColor("the page's ground beside the list", palette().surface, bitmap, card.left.toInt() - 6, trash.center.y.toInt())
        }
        rule.onNodeWithTag("drag-row").assertIsDisplayed()
        letGo()
    }

    @Test fun aRowOverTheTrashTurnsItToTheDangerColoursAndTheLineSaysWhatADropDoes() {
        window()
        carry(middle("row:cpu"), middle("row:cpu") + Offset(0f, 30f))
        val to = middle("trash")
        @OptIn(ExperimentalTestApi::class)
        rule.onRoot().performMouseInput { moveTo(Offset(to.x, to.y - 40f)); moveTo(to + Offset(90f, 0f)) }
        rule.waitForIdle()
        both("drag-trash") { bitmap ->
            val trash = bounds("trash")
            assertVeil("the red lies over the card as under a pressed red button", palette().danger.copy(alpha = TRASH_VEIL).compositeOver(palette().panel),
                bitmap, inTrash().x.toInt(), inTrash().y.toInt())
            assertColor("inside a border of the same red", palette().danger, bitmap, trash.center.x.toInt(), trash.top.toInt())
        }
        rule.onNodeWithTag("preview-line").assertTextEquals(app.getString(R.string.bar_drop_off, app.getString(R.string.item_cpu_title)))
        rule.onNodeWithTag("row:cpu").assertDoesNotExist()
        letGo()
        rule.onNodeWithTag("trash").assertDoesNotExist()
        rule.onNodeWithTag("slip").assertIsDisplayed()
    }

    @Config(qualifiers = "w960dp-h780dp-mdpi")
    @Test fun at960TheTrashStandsUnderTheLanes() {
        areas()
        carry(middle("row:weather"), middle("row:weather") + Offset(0f, 30f))
        both("mid-drag-trash")
        val trash = bounds("trash")
        assertEquals(bounds("bar-list").bottom, trash.top)
        assert(trash.height >= 48f) { "tall enough for a finger: ${trash.height}" }
        letGo()
    }

    @Config(qualifiers = "w640dp-h780dp-mdpi")
    @Test fun at640TheTrashStandsUnderTheOneColumnAndTheListScrollsAboveIt() {
        areas()
        carry(middle("row:weather"), middle("row:weather") + Offset(0f, 30f))
        both("narrow-drag-trash")
        val trash = bounds("trash")
        assertEquals(bounds("bar-list").bottom, trash.top)
        assertEquals("as wide as the list", bounds("bar-list").left to bounds("bar-list").right, trash.left to trash.right)
        val to = middle("trash")
        @OptIn(ExperimentalTestApi::class)
        rule.onRoot().performMouseInput { moveTo(Offset(to.x, to.y - 40f)); moveTo(to) }
        rule.waitForIdle()
        both("narrow-drag-trash-over")
        letGo()
        rule.onNodeWithTag("row:weather").assertDoesNotExist()
    }

    @Test fun aTypeCarriedOutOfTheLibraryIsDimmedThereAndTheListMakesRoom() {
        window()
        val weather = bounds("row:weather")
        val storage = bounds("type:storage")
        carry(Offset(storage.left + 80f, storage.center.y), Offset(weather.center.x + 120f, weather.bottom + 10f))
        both("drag-add")
        rule.onNodeWithTag("trash").assertDoesNotExist()
        rule.onNodeWithTag("drag-row").assertIsDisplayed()
        letGo()
    }

    @Test fun aRowCarriedOverThePreviewIsItsChipAndEveryAreaIsAZone() {
        window()
        carry(middle("row:timer"), bounds("preview").let { Offset(it.center.x, it.top + 26f) })
        val left = bounds("zone:LEFT")
        @OptIn(ExperimentalTestApi::class)
        rule.onRoot().performMouseInput { moveTo(Offset(left.center.x + 40f, left.center.y)); moveTo(Offset(left.center.x + 6f, left.center.y + 4f)) }
        rule.waitForIdle()
        both("drag-areas")
        rule.onNodeWithTag("drag-chip").assertIsDisplayed()
        rule.onNodeWithTag("bracket:LEFT").assertIsDisplayed()
        letGo()
    }

    @Test fun aFingerCarriesARowByItsHandleOntoTheTrash() {
        window()
        val sound = bounds("row:sound")
        val grip = Offset(sound.left + 32f, sound.center.y)
        rule.onRoot().performTouchInput { down(grip); for (i in 1..4) moveBy(Offset(0f, -10f)) }
        rule.waitForIdle()
        val to = middle("trash") + Offset(-120f, 0f)
        rule.onRoot().performTouchInput { for (i in 1..8) moveTo(grip + Offset(0f, -40f) + (to - grip - Offset(0f, -40f)) * (i / 8f)) }
        rule.waitForIdle()
        both("drag-touch") { bitmap ->
            assertVeil("under the finger the trash is red", palette().danger.copy(alpha = TRASH_VEIL).compositeOver(palette().panel),
                bitmap, inTrash().x.toInt(), inTrash().y.toInt())
        }
        rule.onNodeWithTag("preview-line").assertTextEquals(app.getString(R.string.bar_drop_off_touch, app.getString(R.string.item_sound_title)))
        rule.onRoot().performTouchInput { up() }
        rule.waitForIdle()
        rule.onNodeWithTag("row:sound").assertDoesNotExist()
    }
}
