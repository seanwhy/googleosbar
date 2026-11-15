package com.meridian11.books.bentobar.compose

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.bar.BarStatus
import com.meridian11.books.bentobar.ui.LocalWindowLayout
import com.meridian11.books.bentobar.ui.Page
import com.meridian11.books.bentobar.ui.SettingsTheme
import com.meridian11.books.bentobar.ui.Shell
import com.meridian11.books.bentobar.ui.tabs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The settings window's header: the tabs by click and by keys, the status line in each of its states, the one control
 * (the switch, or Turn on while BentoBar is off, or none on Setup), one row or two by the window's width, and nothing
 * in the system's caption. The page under it is a line of text that says which page and layout it was given.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w1200dp-h800dp-mdpi")
class ShellTest {
    @get:Rule val rule = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private fun str(id: Int) = app.getString(id)

    private var page by mutableStateOf(Page.BAR)
    private var running by mutableStateOf(true)
    private var enabled by mutableStateOf(true)
    private var status by mutableStateOf(BarStatus.SHOWN)
    private var presenting by mutableStateOf(false)
    private var turnOns = 0
    private val switched = mutableListOf<Boolean>()

    private lateinit var inputMode: InputModeManager

    private fun shell(width: Int = 1180, caption: Int = 0) {
        rule.setContent {
            inputMode = LocalInputModeManager.current
            SettingsTheme(dark = false) {
                Box(Modifier.requiredSize(width.dp, 600.dp)) {
                    Shell(page, { page = it }, running, enabled, status, onEnabled = { switched += it; enabled = it }, onTurnOn = { turnOns++ },
                        presenting = presenting, insets = WindowInsets(top = caption.dp)) {
                        val layout = LocalWindowLayout.current
                        Text("page ${page.name} ${layout.kind} ${layout.panelDp}")
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private val isTab = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
    private val isSwitch = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)
    private val isButton = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)
    private fun tab(p: Page) = rule.onNode(hasText(str(p.label)) and isTab)
    private fun theSwitch() = rule.onNode(hasText(str(R.string.show_in_status_bar)) and isSwitch)
    private fun turnOn() = rule.onNode(hasText(str(R.string.setup_turn_on)) and isButton)
    private fun shows(p: Page) = rule.onNode(hasText("page ${p.name}", substring = true)).assertExists()
    private fun bounds(text: String): Rect = rule.onNode(hasText(text)).fetchSemanticsNode().boundsInRoot

    @Test fun aClickOnATabOpensItsPageAndEveryPageHasATab() {
        shell()
        assertEquals(Page.entries.toSet(), tabs.toSet())
        shows(Page.BAR)
        tab(Page.BAR).assertIsSelected()
        for (p in tabs.reversed()) {
            tab(p).performClick()
            rule.waitForIdle()
            assertEquals(p, page)
            shows(p)
            tab(p).assertIsSelected()
            for (other in tabs - p) tab(other).assertIsNotSelected()
        }
    }

    @Test fun theArrowKeysCarryTheKeyboardFromTabToTabAndEnterOpensTheTabThatHasIt() {
        shell()
        // A keyboard is in use: in touch mode nothing that takes a click takes the focus.
        rule.runOnIdle { assertTrue(inputMode.requestInputMode(InputMode.Keyboard)) }
        tab(Page.BAR).requestFocus()
        tab(Page.BAR).assertIsFocused()
        tab(Page.BAR).performKeyInput { pressKey(Key.DirectionRight) }
        tab(Page.LOOK).assertIsFocused()
        // The keyboard moved; the page did not.
        assertEquals(Page.BAR, page)
        tab(Page.LOOK).performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
        assertEquals(Page.LOOK, page)
        shows(Page.LOOK)
        tab(Page.LOOK).performKeyInput { pressKey(Key.MoveEnd) }
        tab(tabs.last()).assertIsFocused()
        // At the end it stays.
        tab(tabs.last()).performKeyInput { pressKey(Key.DirectionRight) }
        tab(tabs.last()).assertIsFocused()
        tab(tabs.last()).performKeyInput { pressKey(Key.DirectionLeft) }
        tab(tabs[tabs.lastIndex - 1]).assertIsFocused()
        tab(tabs[tabs.lastIndex - 1]).performKeyInput { pressKey(Key.MoveHome) }
        tab(tabs.first()).assertIsFocused()
        tab(tabs.first()).performKeyInput { pressKey(Key.DirectionLeft) }
        tab(tabs.first()).assertIsFocused()
        assertEquals(Page.LOOK, page)
        tab(tabs.first()).performKeyInput { pressKey(Key.Spacebar) }
        rule.waitForIdle()
        assertEquals(Page.BAR, page)
    }

    @Test fun whilePresentingModeIsOnTheStatusLineSaysSoOnEveryTab() {
        shell()
        rule.onNode(hasText(str(R.string.status_live))).assertExists()
        presenting = true
        rule.waitForIdle()
        rule.onNode(hasText(str(R.string.status_presenting))).assertExists()
        rule.onNode(hasText(str(R.string.status_live))).assertDoesNotExist()
        page = Page.LOOK
        rule.waitForIdle()
        rule.onNode(hasText(str(R.string.status_presenting))).assertExists()
        // A full-screen app is said first: then there is no bar at all.
        status = BarStatus.NO_BAR
        rule.waitForIdle()
        rule.onNode(hasText(str(R.string.status_no_bar))).assertExists()
        status = BarStatus.SHOWN
        presenting = false
        rule.waitForIdle()
        rule.onNode(hasText(str(R.string.status_live))).assertExists()
    }

    @Test fun theStatusLineShowsEachOfItsStates() {
        shell()
        fun says(id: Int) { rule.waitForIdle(); rule.onNode(hasText(str(id))).assertExists() }
        says(R.string.status_live)
        for ((s, id) in listOf(BarStatus.STOPPED to R.string.status_starting, BarStatus.NO_ROOM to R.string.status_no_room, BarStatus.EMPTY to R.string.status_empty,
            BarStatus.NO_BAR to R.string.status_no_bar, BarStatus.COVERED to R.string.status_covered, BarStatus.HIDDEN_BY_USER to R.string.status_live,
            BarStatus.ASLEEP to R.string.status_live)) {
            status = s
            says(id)
        }
        status = BarStatus.NO_ROOM
        enabled = false
        says(R.string.status_hidden)
        running = false
        says(R.string.bar_banner_title)
        // The same on Setup, where the step is.
        page = Page.SETUP
        says(R.string.bar_banner_title)
    }

    @Test fun whileBentoBarRunsTheControlIsTheSwitch() {
        shell()
        turnOn().assertDoesNotExist()
        theSwitch().assertIsOn()
        theSwitch().performClick()
        rule.waitForIdle()
        assertEquals(listOf(false), switched)
        theSwitch().assertIsOff()
        // On Setup too.
        page = Page.SETUP
        rule.waitForIdle()
        theSwitch().assertExists()
    }

    @Test fun whileBentoBarIsOffTurnOnTakesTheSwitchsPlaceAndSetupHasNeither() {
        running = false
        shell()
        for (p in tabs - Page.SETUP) {
            page = p
            rule.waitForIdle()
            theSwitch().assertDoesNotExist()
            turnOn().assertExists()
        }
        turnOn().performClick()
        assertEquals(1, turnOns)
        // Setup's own Turn on is then the one on the screen: the header has the status only.
        page = Page.SETUP
        rule.waitForIdle()
        theSwitch().assertDoesNotExist()
        turnOn().assertDoesNotExist()
        rule.onNode(hasText(str(R.string.bar_banner_title))).assertExists()
    }

    @Test fun nothingIsPlacedInTheCaption() {
        shell(caption = 36)
        for (on in listOf(true, false)) {
            running = on
            rule.waitForIdle()
            val caption = with(rule.density) { 36.dp.toPx() }
            // Everything that says or does something. A painted ground has a shape and nothing else: the header's is under the caption too.
            val nodes = rule.onAllNodes(!isRoot(), useUnmergedTree = true).fetchSemanticsNodes().filter { n -> n.config.any { it.key != SemanticsProperties.Shape } }
            assertTrue("the header has its parts", nodes.size > tabs.size)
            for (n in nodes) assertTrue("${n.config} at ${n.boundsInRoot} is in the caption", n.boundsInRoot.top >= caption)
            // The header starts right under it.
            assertEquals(caption, nodes.minOf { it.boundsInRoot.top }, 0.5f)
        }
    }

    @Test fun at1180TheHeaderIsOneRowAndThePageHasAPanel() {
        shell(1180)
        val status = bounds(str(R.string.status_live))
        val tabRow = tab(Page.BAR).fetchSemanticsNode().boundsInRoot
        assertTrue("the status line is in the tabs' row", status.top >= tabRow.top && status.bottom <= tabRow.bottom)
        // Mark, name, tabs, status, switch, in that order.
        val name = bounds(str(R.string.app_name))
        val control = theSwitch().fetchSemanticsNode().boundsInRoot
        assertTrue(name.right <= tabRow.left && tab(tabs.last()).fetchSemanticsNode().boundsInRoot.right <= status.left && status.right <= control.left)
        rule.onNode(hasText("WIDE 348", substring = true)).assertExists()
    }

    @Test fun at960TheHeaderIsStillOneRowAndThePageHasNoPanel() {
        shell(960)
        val status = bounds(str(R.string.status_live))
        val tabRow = tab(Page.BAR).fetchSemanticsNode().boundsInRoot
        assertTrue("the status line is in the tabs' row", status.top >= tabRow.top && status.bottom <= tabRow.bottom)
        assertTrue(tab(tabs.last()).fetchSemanticsNode().boundsInRoot.right <= status.left)
        theSwitch().assertExists()
        rule.onNode(hasText("MID 0", substring = true)).assertExists()
    }

    @Test fun below760TheHeaderTakesTwoRows() {
        shell(700)
        val status = bounds(str(R.string.status_live))
        val tabRow = tab(Page.BAR).fetchSemanticsNode().boundsInRoot
        assertTrue("the status line is under the tabs", status.top >= tabRow.bottom)
        val control = theSwitch().fetchSemanticsNode().boundsInRoot
        assertTrue("the switch is in the status line's row", control.top < status.bottom && control.bottom > status.top && control.left >= status.right)
        // The mark alone, which then carries the name.
        rule.onNode(hasText(str(R.string.app_name))).assertDoesNotExist()
        rule.onNode(hasContentDescription(str(R.string.app_name))).assertExists()
        for (p in tabs) tab(p).assertExists()
        rule.onNode(hasText("NARROW 0", substring = true)).assertExists()
    }
}
