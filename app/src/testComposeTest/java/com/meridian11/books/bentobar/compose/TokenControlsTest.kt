package com.meridian11.books.bentobar.compose

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import com.meridian11.books.bentobar.items.Env
import com.meridian11.books.bentobar.ui.BentoButton
import com.meridian11.books.bentobar.ui.ButtonKind
import com.meridian11.books.bentobar.ui.Keycap
import com.meridian11.books.bentobar.ui.NotesRow
import com.meridian11.books.bentobar.ui.Segment
import com.meridian11.books.bentobar.ui.SegmentPart
import com.meridian11.books.bentobar.ui.SettingsTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The settings window's own controls: a segment chooses one part and leaves a part that is off alone; a button that is off takes no click. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w1200dp-h800dp-mdpi")
class TokenControlsTest {
    @get:Rule val rule = createComposeRule()

    /** A keycap's arrows are the window's own symbols. */
    @Before fun fonts() = Env.init(ApplicationProvider.getApplicationContext<Application>())

    private fun part(text: String, role: Role) = rule.onNode(hasText(text) and SemanticsMatcher.expectValue(SemanticsProperties.Role, role))

    @Test fun aSegmentChoosesOnePartAndAPartThatIsOffTakesNoClick() {
        var chosen by mutableIntStateOf(0)
        val asked = mutableListOf<Int>()
        rule.setContent {
            SettingsTheme(dark = false) {
                Segment(listOf(SegmentPart("Your bar"), SegmentPart("Add items"), SegmentPart("Select an item", enabled = false)), chosen,
                    onChoose = { asked += it; chosen = it }, role = Role.Tab)
            }
        }
        part("Your bar", Role.Tab).assertIsSelected()
        part("Add items", Role.Tab).assertIsNotSelected().performClick()
        rule.waitForIdle()
        part("Add items", Role.Tab).assertIsSelected()
        part("Your bar", Role.Tab).assertIsNotSelected()
        part("Select an item", Role.Tab).assertIsNotEnabled().performClick()
        rule.waitForIdle()
        assertEquals(listOf(1), asked)
    }

    @Test fun aSegmentOfValuesIsAGroupOfRadioButtons() {
        rule.setContent { SettingsTheme(dark = true) { Segment(listOf(SegmentPart("Left"), SegmentPart("Middle"), SegmentPart("Right")), chosen = 2, onChoose = {}) } }
        part("Right", Role.RadioButton).assertIsSelected()
        part("Left", Role.RadioButton).assertIsNotSelected()
    }

    @Test fun aButtonTakesAClickUnlessItIsOff() {
        var clicks = 0
        var on by mutableStateOf(true)
        rule.setContent {
            SettingsTheme(dark = false) {
                Column { for (kind in ButtonKind.entries) BentoButton(kind.name, { clicks++ }, kind = kind, enabled = on) }
            }
        }
        for (kind in ButtonKind.entries) part(kind.name, Role.Button).assertIsEnabled().performClick()
        assertEquals(ButtonKind.entries.size, clicks)
        on = false
        rule.waitForIdle()
        for (kind in ButtonKind.entries) part(kind.name, Role.Button).assertIsNotEnabled().performClick()
        assertEquals(ButtonKind.entries.size, clicks)
    }

    /** An item's long text of its own (a prompter's script): several lines, line breaks kept, and no more than its most. */
    @Test fun aNotesFieldTakesSeveralLinesUpToItsMost() {
        var kept by mutableStateOf("")
        rule.setContent { SettingsTheme(dark = false) { Column { NotesRow("Your notes", kept, max = 12) { kept = it } } } }
        val field = rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText))
        field.performTextReplacement("one\ntwo")
        rule.waitForIdle()
        assertEquals("one\ntwo", kept)
        field.performTextInput("\nthree four")
        rule.waitForIdle()
        assertEquals("cut at its most", "one\ntwo\nthre", kept)
        val one = rule.onNode(hasText("Your notes") and !SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)).fetchSemanticsNode().boundsInRoot
        val box = field.fetchSemanticsNode().boundsInRoot
        assertTrue("its label stands above it", one.bottom <= box.top + 1f)
        assertTrue("several lines high: ${box.height}", box.height > 3 * one.height)
    }

    /** A cap of arrows is as high as a cap of words and says the same; its arrows keep their sides from right to left. */
    @Test fun aKeycapsArrowsAreInACapOfTheSameHeightAndKeepTheirSides() {
        var direction by mutableStateOf(LayoutDirection.Ltr)
        rule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                SettingsTheme(dark = false) { Row { Keycap("Alt", Modifier.testTag("word")); Keycap("\u2190 \u2192", Modifier.testTag("arrows")) } }
            }
        }
        fun lefts() = rule.onAllNodes(hasAnyAncestor(hasTestTag("arrows")), useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot.left }
        assertEquals(rule.onNodeWithTag("word").fetchSemanticsNode().size.height, rule.onNodeWithTag("arrows").fetchSemanticsNode().size.height)
        rule.onNodeWithTag("arrows").assertTextEquals("\u2190 \u2192")
        assertEquals(2, lefts().size)
        assertTrue("\u2190 stands left of \u2192", lefts()[0] < lefts()[1])
        direction = LayoutDirection.Rtl
        rule.waitForIdle()
        assertTrue("and from right to left too", lefts()[0] < lefts()[1])
    }
}
