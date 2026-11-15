package com.meridian11.books.bentobar.compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.hasClickAction
import com.meridian11.books.bentobar.ui.BarKeys
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.bar.BarStatus
import com.meridian11.books.bentobar.data.BarConfig
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.data.Position
import com.meridian11.books.bentobar.data.Store
import com.meridian11.books.bentobar.items.Env
import com.meridian11.books.bentobar.items.WeatherItem
import com.meridian11.books.bentobar.ui.BarPage
import com.meridian11.books.bentobar.ui.Page
import com.meridian11.books.bentobar.ui.DANGER_VEIL
import com.meridian11.books.bentobar.ui.Palette
import com.meridian11.books.bentobar.ui.Setup
import com.meridian11.books.bentobar.ui.SettingsTheme
import com.meridian11.books.bentobar.ui.Shell
import com.meridian11.books.bentobar.ui.Undo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
 * An item's own options in the panel, on the window's controls: no heading in capitals between Show as and the type's
 * settings, a short choice as a segment and a longer one as radio buttons, a sunk text field. With the environment
 * variable `BENTOBAR_PICTURES` naming a directory, each panel is also written there as a PNG, to look at beside the design.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w1180dp-h1000dp-mdpi")
class OptionsPicturesTest {
    @get:Rule val rule = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var before: BarConfig
    private lateinit var view: View
    private var dark by mutableStateOf(false)
    private var selected by mutableStateOf<String?>(null)

    @Before fun env() { Env.init(app); before = Store.config.value; Undo.last.value = null }
    @After fun forget() { Store.update { before }; Undo.last.value = null }

    private fun str(id: Int, vararg args: Any) = app.getString(id, *args)
    private fun inOptions() = hasAnyAncestor(hasTestTag("options"))
    private fun role(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

    private fun picture(name: String): Bitmap {
        val bitmap = rule.runOnIdle { Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) } }
        System.getenv("BENTOBAR_PICTURES")?.takeIf { it.isNotBlank() }?.let { dir ->
            File(dir).mkdirs()
            File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        return bitmap
    }

    private fun show(select: String, vararg options: Pair<String, String>) {
        fun item(id: String) = ItemConfig(id, id, area = Position.RIGHT, options = if (id == select) options.toMap() else emptyMap())
        Store.update { BarConfig(items = listOf("event", "weather", "clock", "flight", "network", "cpu", "sound").map(::item), position = Position.RIGHT) }
        selected = select
        if (::view.isInitialized) { rule.waitForIdle(); return }
        rule.setContent {
            view = LocalView.current
            SettingsTheme(dark) {
                Shell(Page.BAR, {}, running = true, enabled = true, status = BarStatus.SHOWN, onEnabled = {}, onTurnOn = {}, insets = WindowInsets(top = 36.dp)) {
                    BarPage(selected, onSelect = { selected = it })
                }
            }
        }
        rule.waitForIdle()
    }

    @Test fun weathersOptionsFollowShowAsWithNoHeadingAShortChoiceIsASegmentAndALongOneRadioButtons() {
        show("weather", "city" to "San Francisco", "lat" to "37.77", "lon" to "-122.42")
        // The heading in capitals is gone: nothing in the panel says "Options" over the type's settings.
        assertTrue(rule.onAllNodes(hasText("OPTIONS") and inOptions()).fetchSemanticsNodes().isEmpty())
        // Show: four values, so radio buttons under one another, the chosen one selected.
        rule.onNode(hasText(str(R.string.weather_show_temp)) and inOptions() and role(Role.RadioButton)).assertIsSelected()
        val temp = rule.onNode(hasText(str(R.string.weather_show_temp)) and inOptions()).fetchSemanticsNode().boundsInRoot
        val feels = rule.onNode(hasText(str(R.string.weather_show_feels)) and inOptions()).fetchSemanticsNode().boundsInRoot
        assertTrue("the values of Show stand under one another", feels.top >= temp.bottom - 1f && feels.left == temp.left)
        // Temperature unit: three short values in one line.
        val system = rule.onNode(hasText(str(R.string.option_like_system)) and inOptions()).fetchSemanticsNode().boundsInRoot
        val celsius = rule.onNode(hasText(str(R.string.option_celsius)) and inOptions()).fetchSemanticsNode().boundsInRoot
        assertTrue("the units stand beside one another", celsius.left >= system.right - 1f && celsius.top < system.bottom)
        rule.onNode(hasText(str(R.string.option_celsius)) and inOptions()).performClick()
        rule.runOnIdle { assertEquals("c", Store.config.value.items.first { it.id == "weather" }.options["unit"]) }
        // The label's field stands on the sunk ground, with no outline.
        for (isDark in listOf(false, true)) {
            dark = isDark
            picture("options-weather-${if (isDark) "dark" else "light"}")
            val field = rule.onNode(hasSetTextAction() and inOptions()).performScrollTo().fetchSemanticsNode().boundsInRoot
            val bitmap = picture("options-weather-${if (isDark) "dark" else "light"}-end")
            val palette = if (isDark) Palette.Dark else Palette.Light
            assertEquals("the field's ground", Integer.toHexString(palette.sunk.toArgb()), Integer.toHexString(bitmap.getPixel(field.right.toInt() - 8, field.center.y.toInt())))
        }
    }

    @Test fun removeIsTheRedButtonOnItsRedGroundWithATrashBeforeItsWordsAndNoKeycapBesideIt() {
        show("clock")
        val words = str(R.string.detail_remove)
        assertEquals("Remove from the bar", words)
        for (isDark in listOf(false, true)) {
            dark = isDark
            val bitmap = picture("options-remove-${if (isDark) "dark" else "light"}")
            val palette = if (isDark) Palette.Dark else Palette.Light
            val button = rule.onNode(hasText(words) and hasClickAction() and inOptions()).fetchSemanticsNode().boundsInRoot
            val text = rule.onNode(hasText(words) and inOptions(), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            fun count(color: Color, from: Float, to: Float): Int {
                var n = 0
                for (y in button.top.toInt() until button.bottom.toInt()) for (x in from.toInt() until to.toInt()) if (bitmap.getPixel(x, y) == color.toArgb()) n++
                return n
            }
            val theme = if (isDark) "dark" else "light"
            assertTrue("the words are in the danger colour, $theme", count(palette.danger, text.left, text.right) > 0)
            assertEquals("and not in the ink, $theme", 0, count(palette.ink, button.left, button.right))
            assertTrue("a trash stands before them, $theme", text.left - button.left >= 18f && count(palette.danger, button.left, text.left) > 0)
            // A button at rest: a ground of its own, the danger colour over the card, in Duplicate's shape and height.
            val ground = palette.danger.copy(alpha = DANGER_VEIL).compositeOver(palette.panel)
            for (x in listOf(button.left + 8f, button.right - 8f))
                assertTrue("its ground, $theme: ${Integer.toHexString(bitmap.getPixel(x.toInt(), button.center.y.toInt()))}, not ${Integer.toHexString(ground.toArgb())}",
                    near(bitmap.getPixel(x.toInt(), button.center.y.toInt()), ground))
            assertEquals("the card's corner outside its rounded one, $theme", Integer.toHexString(palette.panel.toArgb()),
                Integer.toHexString(bitmap.getPixel(button.left.toInt(), button.top.toInt())))
            val duplicate = rule.onNode(hasText(str(R.string.detail_duplicate)) and hasClickAction() and inOptions()).fetchSemanticsNode().boundsInRoot
            assertEquals("as tall as Duplicate, $theme", duplicate.height, button.height)
            assertEquals("and on its line, $theme", duplicate.top, button.top)
            assertEquals("Duplicate's ground, $theme", Integer.toHexString(palette.sunk.toArgb()), Integer.toHexString(bitmap.getPixel(duplicate.left.toInt() + 8, duplicate.center.y.toInt())))
        }
        // No keycap beside it: the keys still remove, and the system's list of shortcuts says which.
        rule.onNode(hasText("Del") and inOptions()).assertDoesNotExist()
        assertTrue(BarKeys.REMOVE in BarKeys.listed())
        val foot = rule.onNode(hasText(words) and hasClickAction() and inOptions()).fetchSemanticsNode().boundsInRoot
        val edge = rule.onNodeWithTag("options-edge").fetchSemanticsNode().boundsInRoot
        assertTrue("the button is the last thing in the foot", rule.onAllNodes(inOptions()).fetchSemanticsNodes().none { it.boundsInRoot.top >= edge.bottom && it.boundsInRoot.left >= foot.right })
    }

    @Test fun aServiceThatGaveNoAnswerSaysSoOverTheOptionsWithTryAgainAndTheRowSaysWhenItWasLastUpdated() {
        try {
            show("weather", "city" to "San Francisco", "lat" to "37.77", "lon" to "-122.42")
            rule.onNodeWithTag("trouble").assertDoesNotExist()
            // An older reading still shows and the last try got no answer.
            rule.runOnIdle { assertNotNull(WeatherItem.debug(listOf("stage", "no-answer"))) }
            rule.waitForIdle()
            rule.onNode(hasText(str(R.string.weather_no_answer_15)) and inOptions()).assertExists()
            rule.onNode(hasText(str(R.string.common_try_again)) and inOptions()).assertHasClickAction()
            val row = rule.onNode(hasText(str(R.string.row_last_updated, "").trim(), substring = true) and hasTestTag("row:weather"))
            row.assertExists()
            picture("options-online-error")
            // Nothing to show at all: the notice stands, the row has no time to name.
            rule.runOnIdle { assertNotNull(WeatherItem.debug(listOf("stage", "error"))) }
            rule.waitForIdle()
            rule.onNode(hasText(str(R.string.weather_no_answer_15)) and inOptions()).assertExists()
            assertTrue(rule.onAllNodes(hasText(str(R.string.row_last_updated, "").trim(), substring = true)).fetchSemanticsNodes().isEmpty())
            // No connection is the device's: no notice of the service's.
            rule.runOnIdle { assertNotNull(WeatherItem.debug(listOf("stage", "offline"))) }
            rule.waitForIdle()
            rule.onNodeWithTag("trouble").assertDoesNotExist()
        } finally {
            WeatherItem.debug(listOf("off"))
        }
    }

    @Test fun anItemThatWaitsForCalendarAccessSaysSoWithAllow() {
        val setup = Setup.state.value
        try {
            Setup.state.value = setup.copy(calendar = false)
            show("event")
            rule.onNode(hasText(str(R.string.detail_needs_calendar)) and inOptions()).assertExists()
            rule.onNode(hasText(str(R.string.common_allow)) and inOptions()).assertHasClickAction()
            picture("options-permission")
        } finally {
            Setup.state.value = setup
        }
    }

    @Test fun theOtherTypesOptionsAreDrawnOnTheSameControls() {
        for (type in listOf("flight", "clock", "sound", "network", "event")) {
            show(type)
            rule.onNodeWithTag("options").assertExists()
            picture("options-$type")
        }
    }
}
