package com.meridian11.books.bentobar.compose

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.bar.MenuSurface
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.data.Section
import com.meridian11.books.bentobar.items.AiUsageReader
import com.meridian11.books.bentobar.items.AiUsageTerminal
import com.meridian11.books.bentobar.items.AntigravityItem
import com.meridian11.books.bentobar.items.Env
import com.meridian11.books.bentobar.items.MenuHost
import com.meridian11.books.bentobar.items.TerminalWakeReason
import com.meridian11.books.bentobar.ui.SettingsTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37])
class AiUsageTerminalComposeTest {
    @get:Rule val rule = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    private object DummyHost : MenuHost {
        override fun close() {}
        override fun openItemSettings(id: String) {}
        override fun afterClose(action: () -> Unit) { action() }
    }

    @Before
    fun setUp() {
        Env.init(app)
        AiUsageReader.clearCacheForTesting()
    }

    @After
    fun tearDown() {
        AiUsageReader.clearCacheForTesting()
    }

    @Test
    fun launchesTerminalIntentWhenTerminalPackageIsInstalled() {
        val cn = ComponentName(AiUsageTerminal.TERMINAL_PACKAGE, AiUsageTerminal.TERMINAL_MAIN_ACTIVITY)
        val spm = shadowOf(app.packageManager)
        spm.addActivityIfNotPresent(cn)
        spm.addIntentFilterForActivity(
            cn,
            IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) },
        )

        val intents = AiUsageTerminal.resolveTerminalIntents(app)
        assertTrue(intents.isNotEmpty())
        assertEquals(AiUsageTerminal.TERMINAL_PACKAGE, intents.first().component?.packageName ?: intents.first().`package`)
        assertTrue((intents.first().flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)

        AiUsageTerminal.clockElapsedMs = { 10_000L }
        AiUsageTerminal.isDeviceUnlocked = { true }
        AiUsageReader.pollRunner = { it.run() }
        AiUsageReader.wallClockMs = { 2_000_000L }
        AiUsageReader.daemonFetcher = { null }
        AiUsageReader.fileReader = { null }

        AiUsageReader.ensureDaemonReady(TerminalWakeReason.APP_OPEN)

        val started = shadowOf(app).nextStartedActivity
        assertNotNull("Expected Terminal activity to be launched", started)
        assertEquals(AiUsageTerminal.TERMINAL_PACKAGE, started.component?.packageName ?: started.`package`)
        assertTrue(AiUsageReader.isStartingTerminal())
    }

    @Test
    fun staysQuietWhenTerminalPackageIsNotInstalled() {
        assertTrue(AiUsageTerminal.resolveTerminalIntents(app).isEmpty())

        AiUsageTerminal.clockElapsedMs = { 10_000L }
        AiUsageTerminal.isDeviceUnlocked = { true }
        AiUsageReader.pollRunner = { it.run() }
        AiUsageReader.wallClockMs = { 2_000_000L }
        AiUsageReader.daemonFetcher = { null }
        AiUsageReader.fileReader = { null }

        AiUsageReader.ensureDaemonReady(TerminalWakeReason.APP_OPEN)

        assertNull(shadowOf(app).nextStartedActivity)
        assertFalse(AiUsageReader.isStartingTerminal())
    }

    @Test
    fun menuDisplaysStartingTerminalStateAndManualLaunchAction() {
        var launches = 0
        AiUsageTerminal.clockElapsedMs = { 10_000L }
        AiUsageTerminal.isDeviceUnlocked = { true }
        AiUsageTerminal.launchImpl = { launches++; true }
        AiUsageReader.pollRunner = { it.run() }
        AiUsageReader.wallClockMs = { 2_000_000L }
        AiUsageReader.daemonFetcher = { null }
        AiUsageReader.fileReader = { null }

        // Trigger initial auto-wake so boot-watch is active
        AiUsageReader.ensureDaemonReady(TerminalWakeReason.APP_OPEN)
        assertEquals(1, launches)

        val item = ItemConfig("ag-1", "ai_antigravity", Section.SHOWN)
        rule.setContent {
            SettingsTheme(dark = false) {
                MenuSurface(AntigravityItem.menuWidthDp.dp, 2000.dp, null) {
                    AntigravityItem.menu(item, DummyHost)
                }
            }
        }
        rule.waitForIdle()

        rule.onNode(hasText("正在启动终端守护进程…")).assertIsDisplayed()
        rule.onNode(hasText("启动终端")).assertIsDisplayed().performClick()
        rule.waitForIdle()

        assertEquals(2, launches)
    }

    @Test
    fun optionsToggleControlsAutoStartTerminalPreference() {
        AiUsageTerminal.setAutoStartEnabledForTesting(true)
        AiUsageTerminal.setAutoMinimizeEnabledForTesting(true)
        val item = ItemConfig("ag-1", "ai_antigravity", Section.SHOWN)
        rule.setContent {
            SettingsTheme(dark = false) {
                androidx.compose.foundation.layout.Column {
                    AntigravityItem.options?.invoke(item) {}
                }
            }
        }
        rule.waitForIdle()

        val label = app.getString(R.string.ai_usage_options_auto_terminal)
        val toggle = rule.onNode(isToggleable() and hasText(label, substring = true))
        toggle.assertIsOn()
        toggle.performClick()
        rule.waitForIdle()

        assertFalse(AiUsageTerminal.autoStartEnabled.value)
        toggle.assertIsOff()

        toggle.performClick()
        rule.waitForIdle()

        assertTrue(AiUsageTerminal.autoStartEnabled.value)
        toggle.assertIsOn()

        val minLabel = app.getString(R.string.ai_usage_options_auto_minimize_terminal)
        val minToggle = rule.onNode(isToggleable() and hasText(minLabel, substring = true))
        minToggle.assertIsOn()
        minToggle.performClick()
        rule.waitForIdle()

        assertFalse(AiUsageTerminal.autoMinimizeEnabled.value)
        minToggle.assertIsOff()
    }
}
