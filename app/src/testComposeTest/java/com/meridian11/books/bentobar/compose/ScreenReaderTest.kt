package com.meridian11.books.bentobar.compose

import android.app.Application
import android.graphics.Rect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.meridian11.books.bentobar.bar.Strip
import com.meridian11.books.bentobar.bar.StripEntry
import com.meridian11.books.bentobar.bar.StripEvents
import com.meridian11.books.bentobar.bar.StripLook
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.data.Pill
import com.meridian11.books.bentobar.data.TextSize
import com.meridian11.books.bentobar.items.Env
import com.meridian11.books.bentobar.items.ItemState
import com.meridian11.books.bentobar.ui.ChipRow
import com.meridian11.books.bentobar.ui.MenuEntry
import com.meridian11.books.bentobar.ui.SectionLabel
import com.meridian11.books.bentobar.ui.SliderRow
import com.meridian11.books.bentobar.util.Fmt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** What a screen reader finds on the shared pieces, and that a large text size only grows what is words (findings F37, F86, F36). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37])
class ScreenReaderTest {
    @get:Rule val rule = createComposeRule()

    @Before fun env() = Env.init(ApplicationProvider.getApplicationContext<Application>())

    @Test fun aSectionLabelIsAHeading() {
        rule.setContent { SectionLabel("Options") }
        rule.onNodeWithText("OPTIONS").assert(isHeading())
    }

    @Test fun anOnOffEntrySaysItsStateAndItsMarkIsNotReadOut() {
        rule.setContent { MenuEntry("x", "Show when active", detail = "✓", checked = true) {} }
        rule.onNodeWithText("Show when active", useUnmergedTree = false).assertIsOn()
        rule.onNodeWithText("✓").assertDoesNotExist()
    }

    @Test fun aChosenChipIsSelectedAndItsCheckMarkIsNotReadOut() {
        rule.setContent { ChipRow(listOf("Icon", "Text"), selected = 1) {} }
        rule.onNodeWithText("Text").assertIsSelected()
        rule.onNodeWithText("✓ ").assertDoesNotExist()
        rule.onNodeWithText("✓", substring = true).assertDoesNotExist()
    }

    @Test fun aSliderSaysItsValueInItsOwnUnit() {
        rule.setContent { SliderRow("Spacing", 12, 0..24, { "$it dp" }) {} }
        rule.onNodeWithContentDescription("Spacing").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "12 dp"))
    }

    @Test fun theFormatsTakeTheirWordsFromTheAppsText() {
        // Env.init hands Fmt the words from the text resources: a resource format that doesn't fit its call shows up here.
        assertEquals("85%", Fmt.percent(85))
        assertEquals("840 KB", Fmt.bytes(840_000.0))
        assertEquals("1 hour 5 minutes", Fmt.spoken(65 * 60_000L))
        assertEquals("1h 05m", Fmt.duration(65 * 60_000L))
    }

        private object Events : StripEvents {
        override fun click(item: ItemConfig, at: Rect) {}
        override fun context(item: ItemConfig, at: Rect) {}
        override fun scroll(item: ItemConfig, steps: Int) {}
        override fun chevron(at: Rect) {}
        override fun chevronContext(at: Rect) {}
        override fun hover(inside: Boolean) {}
        override fun placed(id: String, at: Rect) {}
    }

    @Test fun twiceTheTextSizeLeavesADateBadgeAndItsDigitsAsTheyAre() {
        val look = StripLook(Color.White, lightText = true, TextSize.DEFAULT, 8.dp, Pill.NONE)
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                Strip(listOf(StripEntry(ItemConfig("d", "calendar"), ItemState(dayNumber = 9, desc = "Today"))), emptyList(), showChevron = false, chevronAlways = false,
                    chevronReservePx = 0, expanded = false, chevronOnLeft = false, look = look, maxWidthPx = 500, heightDp = 36.dp, events = Events)
            }
        }
        // 15 dp of badge + 1 + the item's 6 dp at each end, whatever the text size.
        val item = rule.onNodeWithContentDescription("Today").getBoundsInRoot()
        assertEquals(28f, (item.right - item.left).value, 1f)
        // The digits fit the 16 dp badge: in sp, at 200% their line would be cut off by it.
        val digits = rule.onNodeWithText("9", useUnmergedTree = true).getBoundsInRoot()
        assertTrue("digits of ${digits.bottom - digits.top} in a 16 dp badge", (digits.bottom - digits.top).value < 14f)
    }
}
