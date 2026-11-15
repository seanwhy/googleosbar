package com.meridian11.books.bentobar.compose

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.meridian11.books.bentobar.bar.BarOverflow
import com.meridian11.books.bentobar.bar.BarStatus
import com.meridian11.books.bentobar.data.BarConfig
import com.meridian11.books.bentobar.data.Defaults
import com.meridian11.books.bentobar.data.Display
import com.meridian11.books.bentobar.data.HiddenMode
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.data.Position
import com.meridian11.books.bentobar.data.Section
import com.meridian11.books.bentobar.data.Store
import com.meridian11.books.bentobar.items.Env
import com.meridian11.books.bentobar.ui.AboutPage
import com.meridian11.books.bentobar.ui.AccessibilityDisclosure
import com.meridian11.books.bentobar.ui.BarPage
import com.meridian11.books.bentobar.ui.LookPage
import com.meridian11.books.bentobar.ui.Page
import com.meridian11.books.bentobar.ui.SettingsTheme
import com.meridian11.books.bentobar.ui.SetupPage
import com.meridian11.books.bentobar.ui.SetupState
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

/**
 * The whole window as it is drawn where the other picture tests don't go: at twice the text size and from right to
 * left. Each test draws the window from its view and holds what a picture can't be trusted to show by eye: that no
 * text is cut off by a row of a fixed height. With the environment variable `BENTOBAR_PICTURES` naming a directory,
 * each picture is also written there as a PNG, to look at beside the design.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w1180dp-h780dp-mdpi")
class WindowPicturesTest {
    @get:Rule val rule = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var before: BarConfig
    private lateinit var view: View
    private var dark by mutableStateOf(false)
    private var selected by mutableStateOf<String?>(null)
    private var fontScale by mutableFloatStateOf(1f)
    private var direction by mutableStateOf(LayoutDirection.Ltr)

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

    private fun window(page: Page, running: Boolean = true, content: @Composable () -> Unit) {
        rule.setContent {
            view = LocalView.current
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale), LocalLayoutDirection provides direction) {
                SettingsTheme(dark) {
                    Shell(page, {}, running, enabled = true, status = if (running) BarStatus.SHOWN else BarStatus.STOPPED, onEnabled = {}, onTurnOn = {},
                        insets = WindowInsets(top = 36.dp)) { content() }
                }
            }
        }
        rule.waitForIdle()
    }

    /** A bar of seven items and two that wait for their rule, in one area, in two or in three. */
    private fun layout(areas: Int, mode: HiddenMode = HiddenMode.SHOW_ALL): BarConfig {
        fun area(id: String) = when {
            areas > 1 && id in setOf("event", "weather", "clock") -> Position.LEFT
            areas > 2 && id == "timer" -> Position.CENTER
            else -> Position.RIGHT
        }
        fun item(id: String, display: Display = Display.ICON_AND_TEXT) = ItemConfig(id, id, display = display, area = area(id))
        fun waiting(id: String) = ItemConfig(id, id, Section.HIDDEN, whenActive = true, area = Position.RIGHT)
        return BarConfig(items = listOf(item("event"), item("weather"), item("clock"), item("timer"), item("network", Display.TEXT), item("cpu"),
            item("sound", Display.ICON), waiting("media"), waiting("devices")), position = Position.RIGHT, hiddenMode = mode)
    }

    private fun bar(three: Boolean = false, mode: HiddenMode = HiddenMode.SHOW_ALL, select: String? = null) {
        Store.update { layout(if (three) 3 else 1, mode) }
        selected = select
        window(Page.BAR) { BarPage(selected, onSelect = { selected = it }) }
    }

    /**
     * Every text on screen that its place cuts off: a line lost to a height that doesn't grow, a line that ends in an
     * ellipsis (both are "more lines than it may take"), or a line longer than its text is wide. The preview is left
     * out: it draws the bar as the bar draws itself, and what the bar shortens for want of room is the bar's own rule.
     */
    private fun cutOff(): List<String> =
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true).fetchSemanticsNodes().mapNotNull { node ->
            if (generateSequence(node) { it.parent }.any { it.config.getOrNull(SemanticsProperties.TestTag) == "preview" }) return@mapNotNull null
            val results = mutableListOf<TextLayoutResult>()
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
            results.firstOrNull()?.takeIf { r -> r.didOverflowHeight || (0 until r.lineCount).any { r.getLineRight(it) - r.getLineLeft(it) > r.size.width + 1f } }?.layoutInput?.text?.text
        }

    private fun large(name: String) {
        picture(name)
        assertEquals("texts cut off in $name", emptyList<String>(), cutOff())
    }

    /**
     * The pictures to look at beside the design's boards: the Bar page in light and dark with one area, two and three.
     * What each holds besides: every item has its row, and no text is cut off.
     */
    private fun barPictures(width: Int) {
        bar()
        for (areas in 1..3) {
            rule.runOnUiThread { Store.update { layout(areas) } }
            for (night in listOf(false, true)) {
                dark = night
                rule.waitForIdle()
                large("bar-$width-$areas-${if (night) "dark" else "light"}")
                layout(areas).items.forEach { rule.onNodeWithTag("row:${it.id}").assertExists() }
                // A lane's head ends in its trash while there are several, and with one lane there is none.
                for (area in Position.entries) {
                    val trash = rule.onNodeWithTag("remove-area:$area")
                    if (areas > 1 && layout(areas).items.any { it.area == area }) trash.assertExists() else trash.assertDoesNotExist()
                }
            }
        }
        // Remove an area: what went is said above the lanes that stay, with Undo.
        rule.onNodeWithTag("remove-area:LEFT").performScrollTo().performClick()
        for (night in listOf(false, true)) {
            dark = night
            rule.waitForIdle()
            large("bar-$width-removed-${if (night) "dark" else "light"}")
            rule.onNodeWithTag("area-removed").assertExists()
            rule.onNodeWithTag("row:event").assertDoesNotExist()
        }
    }

    @Test fun theBarPageAt1180() = barPictures(1180)

    @Config(qualifiers = "w960dp-h780dp-mdpi")
    @Test fun theBarPageAt960() = barPictures(960)

    @Config(qualifiers = "w700dp-h780dp-mdpi")
    @Test fun theBarPageAt700() = barPictures(700)

    /**
     * Look, Setup and About, and what a first run shows before BentoBar is on: the Bar page, Setup and the disclosure. The
     * first run has the bar a fresh install has ([Defaults.config]): four items in Left, after the clock.
     */
    @Config(qualifiers = "w1180dp-h1100dp-mdpi")
    @Test fun theOtherPagesAndAFirstRun() {
        var shown by mutableStateOf("look")
        val fresh = Defaults.config()
        Store.update { layout(1) }
        rule.setContent {
            view = LocalView.current
            SettingsTheme(dark) {
                val first = shown.startsWith("first")
                val page = when (shown) { "look" -> Page.LOOK; "about" -> Page.ABOUT; "first-run-bar" -> Page.BAR; else -> Page.SETUP }
                Shell(page, {}, running = !first, enabled = true, status = if (first) BarStatus.STOPPED else BarStatus.SHOWN, onEnabled = {}, onTurnOn = {},
                    insets = WindowInsets(top = 36.dp)) {
                    when (shown) {
                        "look" -> LookPage()
                        "about" -> AboutPage()
                        "setup" -> SetupPage(SetupState(serviceOn = true, notifications = true, calendar = true), declined = false, wontAsk = emptySet(), onTurnOn = {})
                        "first-run-bar" -> BarPage(null, onSelect = {}, running = false)
                        "first-run-setup" -> SetupPage(SetupState(), declined = false, wontAsk = emptySet(), onTurnOn = {})
                        else -> AccessibilityDisclosure({}, {}, {})
                    }
                }
            }
        }
        for (name in listOf("look", "setup", "about", "first-run-bar", "first-run-setup", "first-run-disclosure")) for (night in listOf(false, true)) {
            if (name == "first-run-bar" && !night) rule.runOnUiThread { Store.update { fresh } }
            shown = name
            dark = night
            rule.waitForIdle()
            large("$name-${if (night) "dark" else "light"}")
            if (name == "first-run-bar") fresh.items.forEach { rule.onNodeWithTag("row:${it.id}").assertExists() }
        }
        rule.onNodeWithTag("disclosure").assertExists()
    }

    @Test fun atTwiceTheTextSizeNothingOnTheBarPageIsCutOff() {
        fontScale = 2f
        bar()
        large("large-bar")
        rule.onNodeWithTag("row:clock").assertExists()
    }

    @Test fun atTwiceTheTextSizeAnItemsOptionsAreWhole() {
        fontScale = 2f
        bar(select = "cpu")
        large("large-options")
    }

    @Test fun atTwiceTheTextSizeThreeAreasAreWhole() {
        fontScale = 2f
        bar(three = true, mode = HiddenMode.CLICK, select = "weather")
        large("large-areas")
    }

    /** Pick the row [id] up with the mouse and carry it over the trash, without letting go. */
    @OptIn(ExperimentalTestApi::class)
    private fun overTrash(id: String) {
        val from = rule.onNodeWithTag("row:$id").fetchSemanticsNode().boundsInRoot.center
        rule.onRoot().performMouseInput { moveTo(from); press(); for (i in 1..6) moveBy(Offset(0f, 6f)) }
        rule.waitForIdle()
        val to = rule.onNodeWithTag("trash").fetchSemanticsNode().boundsInRoot.center
        rule.onRoot().performMouseInput { moveTo(Offset(to.x, to.y - 40f)); moveTo(to) }
        rule.waitForIdle()
    }
    @OptIn(ExperimentalTestApi::class)
    private fun letGo() { rule.onRoot().performMouseInput { release() }; rule.waitForIdle() }

    @Test fun atTwiceTheTextSizeTheTrashUnderTheListIsWhole() {
        fontScale = 2f
        bar()
        overTrash("weather")
        large("large-drag-trash")
        val trash = rule.onNodeWithTag("trash").fetchSemanticsNode().boundsInRoot
        assertTrue("it grows with its words: ${trash.height}", trash.height >= 60f)
        letGo()
        rule.onNodeWithTag("row:weather").assertDoesNotExist()
    }

    @Test fun fromRightToLeftTheTrashSpansTheListsCard() {
        direction = LayoutDirection.Rtl
        bar(three = true)
        overTrash("timer")
        large("rtl-drag-trash")
        val trash = rule.onNodeWithTag("trash").fetchSemanticsNode().boundsInRoot
        val list = rule.onNodeWithTag("bar-list").fetchSemanticsNode().boundsInRoot
        assertEquals(list.left to list.right, trash.left to trash.right)
        letGo()
        rule.onNodeWithTag("row:timer").assertDoesNotExist()
    }

    @Test fun atTwiceTheTextSizeTheLanesTrashAndWhatItSaysAfterwardsAreWhole() {
        fontScale = 2f
        bar(three = true)
        large("large-lanes")
        rule.onNodeWithTag("remove-area:LEFT").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("area-removed").assertExists()
        large("large-area-removed")
    }

    @Config(qualifiers = "w640dp-h780dp-mdpi")
    @Test fun atTwiceTheTextSizeANarrowWindowIsWhole() {
        fontScale = 2f
        bar()
        large("large-narrow-bar")
    }

    @Config(qualifiers = "w1180dp-h1600dp-mdpi")
    @Test fun atTwiceTheTextSizeLookIsWhole() {
        fontScale = 2f
        Store.update { BarConfig(items = listOf(ItemConfig("clock", "clock", area = Position.RIGHT)), position = Position.RIGHT) }
        window(Page.LOOK) { LookPage() }
        large("large-look")
    }

    @Test fun fromRightToLeftTheBarPageIsMirroredAroundABarThatIsNot() {
        direction = LayoutDirection.Rtl
        bar(three = true, mode = HiddenMode.CLICK, select = "timer")
        large("rtl-bar")
        selected = null
        large("rtl-library")
    }

    @Config(qualifiers = "w1180dp-h1600dp-mdpi")
    @Test fun fromRightToLeftLookIsWhole() {
        direction = LayoutDirection.Rtl
        Store.update { BarConfig(items = listOf(ItemConfig("clock", "clock", area = Position.RIGHT)), position = Position.RIGHT) }
        window(Page.LOOK) { LookPage() }
        large("rtl-look")
    }

    @Config(qualifiers = "w1180dp-h1600dp-mdpi")
    @Test fun atTwiceTheTextSizeSetupAndAboutAreWhole() {
        fontScale = 2f
        var page by mutableStateOf(Page.SETUP)
        rule.setContent {
            view = LocalView.current
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                SettingsTheme(dark) {
                    Shell(page, {}, running = false, enabled = true, status = BarStatus.STOPPED, onEnabled = {}, onTurnOn = {}, insets = WindowInsets(top = 36.dp)) {
                        when (page) {
                            Page.SETUP -> SetupPage(SetupState(notifications = true), declined = false, wontAsk = emptySet(), onTurnOn = {})
                            Page.ABOUT -> AboutPage()
                            else -> AccessibilityDisclosure({}, {}, {})
                        }
                    }
                }
            }
        }
        large("large-setup")
        page = Page.ABOUT
        large("large-about")
        page = Page.LOOK
        large("large-disclosure")
    }
}
