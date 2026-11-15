package com.meridian11.books.bentobar.compose

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.bar.MenuSurface
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.items.Access
import com.meridian11.books.bentobar.items.Accesses
import com.meridian11.books.bentobar.items.Env
import com.meridian11.books.bentobar.items.EventItem
import com.meridian11.books.bentobar.items.FocusItem
import com.meridian11.books.bentobar.items.LiveItem
import com.meridian11.books.bentobar.items.LiveNow
import com.meridian11.books.bentobar.items.LiveRank
import com.meridian11.books.bentobar.items.LiveRules
import com.meridian11.books.bentobar.items.MenuHost
import com.meridian11.books.bentobar.items.TimerItem
import com.meridian11.books.bentobar.ui.Setup
import com.meridian11.books.bentobar.ui.SetupState
import com.meridian11.books.bentobar.ui.SettingsTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Live activity's options and popup as drawn: a switch for every registered source by its name, on as the + makes
 * it but for other apps' progress, the access a source lacks under its switch; and a row that has somewhere to go
 * closes the popup and goes there.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37])
class LiveOptionsTest {
    @get:Rule val rule = createComposeRule()
    private var item by mutableStateOf(ItemConfig("l", LiveItem.type))
    private lateinit var setupBefore: SetupState
    private lateinit var wiringBefore: LiveItem.Wiring

    @Before fun env() {
        Env.init(ApplicationProvider.getApplicationContext<Application>())
        setupBefore = Setup.state.value
        wiringBefore = LiveItem.wiring
        Setup.state.value = SetupState()
    }

    @After fun back() {
        Setup.state.value = setupBefore
        LiveItem.onIdle()
        LiveItem.wiring = wiringBefore
    }

    private fun str(id: Int, vararg args: Any) = Env.str(id, *args)
    private fun switch(title: String) = rule.onNode(hasText(title) and isToggleable())

    @Test fun everySourceHasASwitchByItsNameAndSaysWhatAccessItLacks() {
        rule.setContent { SettingsTheme(dark = false) { Column { LiveItem.options!!(item) { item = it } } } }
        rule.waitForIdle()
        val sources = LiveItem.sources()
        rule.onAllNodes(isToggleable()).assertCountEquals(sources.size)
        for (t in sources) if (LiveRules.onAtFirst(t.type)) switch(t.title).assertIsOn() else switch(t.title).assertIsOff()
        // Nothing given: each source that needs something says what, under its switch; the timer needs nothing.
        rule.onNode(hasText(EventItem.title) and hasText(str(R.string.live_needs, str(R.string.setup_calendar_title)))).assertExists()
        rule.onNode(hasText(FocusItem.title) and hasText(str(R.string.live_needs, str(Accesses.title(Access.DO_NOT_DISTURB))))).assertExists()
        rule.onNode(hasText(TimerItem.title) and hasText(str(R.string.live_needs, ""), substring = true)).assertDoesNotExist()
        // Given, the line goes.
        rule.runOnUiThread { Setup.state.value = SetupState(calendar = true, access = setOf(Access.DO_NOT_DISTURB)) }
        rule.waitForIdle()
        rule.onNode(hasText(EventItem.title) and hasText(str(R.string.live_needs, ""), substring = true)).assertDoesNotExist()
        rule.onNode(hasText(FocusItem.title) and hasText(str(R.string.live_needs, ""), substring = true)).assertDoesNotExist()
        // A click switches the source off, and the item no longer wakes it.
        switch(TimerItem.title).performClick()
        rule.waitForIdle()
        switch(TimerItem.title).assertIsOff()
        assertEquals("false", item.options[LiveRules.key(TimerItem.type)])
        assertFalse(TimerItem.type in LiveItem.wakes(item))
    }

    @Test fun aRowWithSomewhereToGoClosesThePopupAndGoesThere() {
        val calls = ArrayList<String>()
        val meeting = LiveNow(LiveRank.MEETING, "sym", "Standup in 5m", "Next: Standup in 5 minutes", open = { calls += "open" })
        val timer = LiveNow(LiveRank.TIMER, "sym", "12:34", "Timer 12 minutes 34 seconds left")
        val sources = listOf(EventItem, TimerItem)
        LiveItem.wiring = LiveItem.Wiring(types = { sources }, sampled = { listOf(item) }, layout = { listOf(item) },
            guarded = { t, _ -> if (t == EventItem) meeting else timer }, idle = { "" }, redraw = {})
        LiveItem.sample(1_000)
        val host = object : MenuHost {
            override fun close() { calls += "close" }
            override fun openItemSettings(id: String) { calls += "settings $id" }
            override fun afterClose(action: () -> Unit) {}
        }
        rule.setContent { MenuSurface(LiveItem.menuWidthDp.dp, 2000.dp, null) { LiveItem.menu!!(item, host) } }
        rule.waitForIdle()
        rule.onNode(hasText("12:34") and hasClickAction()).assertDoesNotExist()
        rule.onNode(hasText("Standup in 5m") and hasClickAction()).performClick()
        rule.waitForIdle()
        assertEquals(listOf("close", "open"), calls)
        rule.onNode(hasText(str(R.string.live_choose)) and hasClickAction()).performClick()
        assertEquals("settings l", calls.last())
    }
}
