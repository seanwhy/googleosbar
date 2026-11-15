package com.meridian11.books.bentobar.compose

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.bar.ItemMenu
import com.meridian11.books.bentobar.bar.MenuSurface
import com.meridian11.books.bentobar.data.BarConfig
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.data.Store
import com.meridian11.books.bentobar.items.Env
import com.meridian11.books.bentobar.items.MenuHost
import com.meridian11.books.bentobar.items.PrompterItem
import com.meridian11.books.bentobar.items.PrompterRules
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
 * The prompter's popup as the bar opens it: Space plays and pauses while the popup has the keys (the focus is its own as
 * it opens), its buttons change the speed in the item's options, and the script stops where it is when the popup goes.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37])
class PrompterMenuTest {
    @get:Rule val rule = createComposeRule()

    private object Host : MenuHost {
        override fun close() {}
        override fun openItemSettings(id: String) {}
        override fun afterClose(action: () -> Unit) {}
    }

    private var open by mutableStateOf(true)
    private fun str(id: Int) = Env.str(id)
    private fun playing() = PrompterItem.going("p").run.playing

    @Before fun popup() {
        Env.init(ApplicationProvider.getApplicationContext<Application>())
        Store.update { BarConfig(items = listOf(ItemConfig("p", PrompterItem.type))) }
        PrompterItem.debug(listOf("stage", "paused"))
        rule.setContent { if (open) MenuSurface(PrompterItem.menuWidthDp.dp, 2000.dp, null) { ItemMenu("p", Host) } }
        rule.waitForIdle()
    }

    @After fun gone() {
        PrompterItem.debug(listOf("off"))
        Store.update { BarConfig() }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun spacePlaysAndPausesWhileThePopupHasTheKeys() {
        rule.onNodeWithContentDescription(str(R.string.prompter_play)).assertExists()
        rule.onRoot().performKeyInput { pressKey(Key.Spacebar) }
        rule.waitForIdle()
        assertTrue(playing())
        rule.onNodeWithContentDescription(str(R.string.prompter_pause)).assertExists()
        rule.onRoot().performKeyInput { pressKey(Key.Spacebar) }
        rule.waitForIdle()
        assertFalse(playing())
        rule.onNodeWithContentDescription(str(R.string.prompter_play)).assertExists()
    }

    @Test fun itsButtonsChangeTheSpeedInTheItemsOptions() {
        fun speed() = PrompterRules.speed.of(Store.config.value.items.single())
        assertEquals(4, speed())
        rule.onNodeWithContentDescription(str(R.string.prompter_faster)).performClick()
        rule.waitForIdle()
        assertEquals(5, speed())
        assertEquals("the place moves on at the new speed", 5, PrompterItem.going("p").speed)
        rule.onNodeWithContentDescription(str(R.string.prompter_slower)).performClick()
        rule.onNodeWithContentDescription(str(R.string.prompter_slower)).performClick()
        rule.waitForIdle()
        assertEquals(3, speed())
    }

    @Test fun theScriptStopsWhereItIsWhenThePopupGoes() {
        rule.onNodeWithContentDescription(str(R.string.prompter_play)).performClick()
        rule.waitForIdle()
        assertTrue(playing())
        open = false
        rule.waitForIdle()
        assertFalse("nothing moves where nobody reads it", playing())
    }
}
