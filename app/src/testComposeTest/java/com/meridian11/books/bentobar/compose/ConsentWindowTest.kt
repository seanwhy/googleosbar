package com.meridian11.books.bentobar.compose

import android.app.Application
import android.app.Instrumentation
import android.content.ContextWrapper
import android.content.Intent
import android.provider.Settings
import android.view.KeyEvent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.data.Store
import com.meridian11.books.bentobar.data.Store.Consent
import com.meridian11.books.bentobar.items.Env
import com.meridian11.books.bentobar.ui.MainActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

/**
 * The disclosure in the settings window itself, from AGENTS.md's finding about Google Play's disclosure: it comes up
 * on first opening, only Agree records consent and opens the system's Accessibility settings, No thanks records a
 * decline and opens nothing, and leaving it any other way (Back, another tab) is no answer.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w1180dp-h780dp-mdpi")
class ConsentWindowTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var window: ActivityScenario<MainActivity>

    @Before fun firstOpening() {
        Env.init(app)
        Store.setConsent(Consent.NOT_ASKED)
        while (opened() != null) Unit
        window = ActivityScenario.launch(MainActivity::class.java)
        rule.waitForIdle()
    }

    @After fun close() {
        window.close()
        Store.setConsent(Consent.NOT_ASKED)
    }

    private fun str(id: Int) = app.getString(id)
    private val button = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)
    private fun tab(id: Int) = hasText(str(id)) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
    /**
     * What the app opened, oldest first. It opens through the application it first met (`Env.init` is once per process),
     * which under Robolectric outlives one test and keeps that test's instrumentation: the starts are recorded there.
     */
    private fun opened(): Intent? {
        val thread = ReflectionHelpers.getField<Any>((Env.app as ContextWrapper).baseContext, "mMainThread")
        val record = shadowOf(ReflectionHelpers.callInstanceMethod<Instrumentation>(thread, "getInstrumentation"))
        return ReflectionHelpers.callInstanceMethod<Intent?>(record, "getNextStartedActivity")
    }

    @Test fun googlePlayWantsAProminentDisclosureWithConsent_itComesUpOnFirstOpeningUnderSetup() {
        rule.onNode(hasTestTag("disclosure")).assertExists()
        rule.onNode(tab(R.string.nav_setup)).assertIsSelected()
        assertEquals("showing it answers nothing", Consent.NOT_ASKED, Store.consent.value)
        assertNull("and opens nothing", opened())
    }

    @Test fun googlePlayWantsAProminentDisclosureWithConsent_agreeRecordsItAndOnlyThenOpensAccessibilitySettings() {
        rule.onNode(hasTestTag("disclosure-agree")).performClick()
        rule.waitForIdle()
        assertEquals(Consent.AGREED, Store.consent.value)
        assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, opened()?.action)
        // Setup is what is under it, with Turn on.
        rule.onNode(hasTestTag("disclosure")).assertDoesNotExist()
        rule.onNode(hasTestTag("setup-turn-on")).assertExists()
        rule.onNode(hasTestTag("declined")).assertDoesNotExist()
    }

    @Test fun googlePlayWantsAProminentDisclosureWithConsent_noThanksRecordsADeclineOpensNothingAndSetupSaysSo() {
        rule.onNode(hasTestTag("disclosure-decline")).performClick()
        rule.waitForIdle()
        assertEquals(Consent.DECLINED, Store.consent.value)
        assertNull(opened())
        rule.onNode(hasTestTag("disclosure")).assertDoesNotExist()
        rule.onNode(hasText(str(R.string.setup_declined))).assertExists()
        // Turn on asks again, and still opens nothing by itself.
        rule.onNode(hasTestTag("setup-turn-on")).performClick()
        rule.waitForIdle()
        rule.onNode(hasTestTag("disclosure")).assertExists()
        assertNull(opened())
        assertEquals(Consent.DECLINED, Store.consent.value)
    }

    @Test fun googlePlayWantsAProminentDisclosureWithConsent_backIsNoAnswer() {
        window.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        rule.onNode(hasTestTag("disclosure")).assertDoesNotExist()
        assertEquals(Consent.NOT_ASKED, Store.consent.value)
        assertNull(opened())
        // Nothing says No thanks: there was none.
        rule.onNode(hasTestTag("setup-turn-on")).assertExists()
        rule.onNode(hasTestTag("declined")).assertDoesNotExist()
    }

    /** A platform's key map need not turn Escape into Back, so the window takes the key itself. */
    @Test fun googlePlayWantsAProminentDisclosureWithConsent_escapeIsNoAnswer() {
        window.onActivity {
            it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE))
            it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ESCAPE))
        }
        rule.waitForIdle()
        rule.onNode(hasTestTag("disclosure")).assertDoesNotExist()
        assertEquals(Consent.NOT_ASKED, Store.consent.value)
        assertNull(opened())
        rule.onNode(hasTestTag("setup-turn-on")).assertExists()
        rule.onNode(hasTestTag("declined")).assertDoesNotExist()
    }

    @Test fun googlePlayWantsAProminentDisclosureWithConsent_anotherTabIsNoAnswerAndTheHeadersTurnOnAsksAgain() {
        rule.onNode(tab(R.string.nav_about)).performClick()
        rule.waitForIdle()
        rule.onNode(hasTestTag("disclosure")).assertDoesNotExist()
        rule.onNode(hasTestTag("about")).assertExists()
        assertEquals(Consent.NOT_ASKED, Store.consent.value)
        assertNull(opened())
        // Off Setup the header has Turn on: it goes through the disclosure, on the Setup tab.
        rule.onNode(hasText(str(R.string.setup_turn_on)) and button).performClick()
        rule.waitForIdle()
        rule.onNode(hasTestTag("disclosure")).assertExists()
        rule.onNode(tab(R.string.nav_setup)).assertIsSelected()
        assertNull(opened())
    }

    /**
     * The window is asked to come up again, as the bar's menu or a long press on the tile ask it. The scenario knows its
     * window by the intent it was started with, so that one is put back: with another it would wait for ever to close it.
     */
    private fun asked(intent: Intent) {
        window.onActivity {
            val started = it.intent
            ReflectionHelpers.callInstanceMethod<Unit>(it, "onNewIntent", ReflectionHelpers.ClassParameter.from(Intent::class.java, intent))
            it.intent = started
        }
        rule.waitForIdle()
    }

    @Test fun googlePlayWantsAProminentDisclosureWithConsent_anIntentThatNamesAnotherPageLeavesItUpUnderSetupWithItsTwoButtons() {
        val buttons = rule.onAllNodes(button).fetchSemanticsNodes().size
        val asks = listOf(
            Intent(app, MainActivity::class.java).putExtra(MainActivity.EXTRA_EDIT, true),
            Intent(app, MainActivity::class.java).putExtra(MainActivity.EXTRA_ITEM, "someone"),
            Intent(app, MainActivity::class.java).setAction("android.service.quicksettings.action.QS_TILE_PREFERENCES"),
        )
        for (ask in asks) {
            asked(ask)
            rule.onNode(hasTestTag("disclosure")).assertExists()
            rule.onNode(tab(R.string.nav_setup)).assertIsSelected()
            // Agree and No thanks, and no Turn on beside them in the header.
            rule.onNode(hasTestTag("disclosure-agree")).assertExists()
            rule.onNode(hasTestTag("disclosure-decline")).assertExists()
            rule.onNode(hasText(str(R.string.setup_turn_on)) and button).assertDoesNotExist()
            assertEquals(buttons, rule.onAllNodes(button).fetchSemanticsNodes().size)
            assertEquals(Consent.NOT_ASKED, Store.consent.value)
            assertNull(opened())
        }
        // The answer leaves the window where the disclosure stood: on Setup, which says so.
        rule.onNode(hasTestTag("disclosure-decline")).performClick()
        rule.waitForIdle()
        assertEquals(Consent.DECLINED, Store.consent.value)
        rule.onNode(tab(R.string.nav_setup)).assertIsSelected()
        rule.onNode(hasText(str(R.string.setup_declined))).assertExists()
        assertNull(opened())
        // With no disclosure up, the same intent turns the page as it always did.
        asked(asks[0])
        rule.onNode(tab(R.string.nav_bar)).assertIsSelected()
    }

    @Test fun onceAgreedTurnOnOpensAccessibilitySettingsWithoutAskingAgain() {
        rule.onNode(hasTestTag("disclosure-agree")).performClick()
        rule.waitForIdle()
        opened()
        rule.onNode(hasTestTag("setup-turn-on")).performClick()
        rule.waitForIdle()
        rule.onNode(hasTestTag("disclosure")).assertDoesNotExist()
        assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, opened()?.action)
    }
}
