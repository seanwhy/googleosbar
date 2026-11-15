package com.meridian11.books.bentobar.compose

import android.Manifest
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.bar.BarStatus
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.data.Online
import com.meridian11.books.bentobar.items.Access
import com.meridian11.books.bentobar.items.Env
import com.meridian11.books.bentobar.items.ItemGroup
import com.meridian11.books.bentobar.items.ItemState
import com.meridian11.books.bentobar.items.ItemType
import com.meridian11.books.bentobar.ui.AboutPage
import com.meridian11.books.bentobar.ui.AccessibilityDisclosure
import com.meridian11.books.bentobar.ui.DANGER_VEIL
import com.meridian11.books.bentobar.ui.Page
import com.meridian11.books.bentobar.ui.Palette
import com.meridian11.books.bentobar.ui.SettingsTheme
import com.meridian11.books.bentobar.ui.SetupPage
import com.meridian11.books.bentobar.ui.SetupState
import com.meridian11.books.bentobar.ui.Shell
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
import java.io.File

/**
 * Setup, the disclosure as a page, and About: what each state shows and which buttons it has. With the environment
 * variable `BENTOBAR_PICTURES` naming a directory, the pages are also written there as PNGs, to look at beside the design.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w1180dp-h1500dp-mdpi")
class SetupPageTest {
    @get:Rule val rule = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var view: View
    private lateinit var activity: ComponentActivity
    private var dark by mutableStateOf(false)
    private var turnOns = 0
    private val answers = mutableListOf<String>()

    @Before fun env() = Env.init(app)

    private fun str(id: Int, vararg args: Any) = app.getString(id, *args)
    private fun role(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)
    private fun within(tag: String) = hasAnyAncestor(hasTestTag(tag))
    private val heading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
    private val button = role(Role.Button)

    private fun picture(name: String): Bitmap {
        val bitmap = rule.runOnIdle { Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) } }
        System.getenv("BENTOBAR_PICTURES")?.takeIf { it.isNotBlank() }?.let { dir ->
            File(dir).mkdirs()
            File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        return bitmap
    }

    private fun window(page: Page, running: Boolean, content: @androidx.compose.runtime.Composable () -> Unit) {
        rule.setContent {
            view = LocalView.current
            activity = LocalActivity.current as ComponentActivity
            SettingsTheme(dark) {
                Shell(page, {}, running = running, enabled = true, status = BarStatus.SHOWN, onEnabled = {}, onTurnOn = { turnOns++ }, insets = WindowInsets(top = 36.dp)) {
                    content()
                }
            }
        }
        rule.waitForIdle()
    }

    private fun setup(state: SetupState, declined: Boolean = false, wontAsk: Set<String> = emptySet()) =
        window(Page.SETUP, state.serviceOn) { SetupPage(state, declined, wontAsk, onTurnOn = { turnOns++ }) }

    private fun disclosure() = window(Page.SETUP, running = false) {
        AccessibilityDisclosure(onAgree = { answers += "agree" }, onDecline = { answers += "decline" }, onDismiss = { answers += "dismiss" })
    }

    /** One kind, one look: a service's "remove the key" is the red button, under that service's switch and in line with its name. */
    @Test fun aServicesKeyIsTakenOffTheDeviceByTheRedButtonUnderItsSwitch() {
        val service = Online.Service.AIRLABS
        try {
            assertTrue(Online.saveKey(service, "a key a test typed"))
            setup(SetupState(serviceOn = true))
            val words = str(R.string.flight_remove_key)
            val remove = hasText(words) and hasClickAction()
            rule.onNode(remove).performScrollTo()
            for (isDark in listOf(false, true)) {
                dark = isDark
                rule.waitForIdle()
                val theme = if (isDark) "dark" else "light"
                val bitmap = picture("setup-key-$theme")
                val palette = if (isDark) Palette.Dark else Palette.Light
                val button = rule.onNode(remove).fetchSemanticsNode().boundsInRoot
                val ground = palette.danger.copy(alpha = DANGER_VEIL).compositeOver(palette.panel)
                for (x in listOf(button.left + 8f, button.right - 8f))
                    assertTrue("its ground, $theme: ${Integer.toHexString(bitmap.getPixel(x.toInt(), button.center.y.toInt()))}, not ${Integer.toHexString(ground.toArgb())}",
                        near(bitmap.getPixel(x.toInt(), button.center.y.toInt()), ground))
                val name = rule.onNode(hasText(str(R.string.setup_online_flights)), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                assertEquals("its ground begins where the service's name does, $theme", name.left, button.left, 0.5f)
                assertTrue("under the switch's row, $theme", button.top >= name.bottom)
            }
            rule.onNode(remove).performClick()
            rule.waitForIdle()
            assertFalse(Online.hasKey(service))
            rule.onNode(remove).assertDoesNotExist()
        } finally {
            Online.removeKey(service)
        }
    }

    @Test fun onceOnSetupSaysOnAndListsThePermissionsEachAllowedOrWithItsButton() {
        setup(SetupState(serviceOn = true, notifications = true, calendar = true, usageAccess = true))
        rule.onNode(hasText(str(R.string.setup_turn_on_title)) and heading).assertExists()
        rule.onNode(hasText(str(R.string.setup_on))).assertExists()
        rule.onNode(hasText(str(R.string.setup_needed))).assertDoesNotExist()
        // On, there is nothing to turn on: the system's settings and App info.
        rule.onNode(hasTestTag("setup-turn-on")).assertDoesNotExist()
        rule.onNode(hasText(str(R.string.setup_accessibility_settings)) and button).assertExists()
        rule.onNode(hasText(str(R.string.setup_permissions)) and heading).assertExists()
        // An allowed one says so and keeps its way out; the others have Allow, each named for a screen reader.
        for (tag in listOf("notifications", "calendar", "usage"))
            rule.onNode(hasText(str(R.string.setup_allowed)) and within("permission:$tag"), useUnmergedTree = true).assertExists()
        rule.onNode(hasContentDescription(str(R.string.setup_action_for, str(R.string.setup_stop_using), str(R.string.setup_calendar_title)))).assertExists()
        rule.onNode(hasContentDescription(str(R.string.setup_action_for, str(R.string.setup_open_setting), str(R.string.setup_usage_title)))).assertExists()
        for (id in listOf(R.string.setup_live_title, R.string.setup_alarms_title, R.string.setup_listener_title))
            rule.onNode(hasContentDescription(str(R.string.setup_action_for, str(R.string.common_allow), str(id)))).assertExists()
        rule.onNode(hasText(str(R.string.setup_online_title)) and heading).assertExists()
        for (id in listOf(R.string.setup_online_weather, R.string.setup_online_flights, R.string.setup_online_stocks, R.string.setup_online_fx))
            rule.onNode(hasText(str(id)) and role(Role.Switch)).assertExists()
        picture("setup-on")
        dark = true
        rule.waitForIdle()
        picture("setup-on-dark")
    }

    @Test fun beforeItIsOnSetupSaysNeededAndTurnOnIsItsOneFilledButton() {
        setup(SetupState())
        rule.onNode(hasText(str(R.string.setup_needed))).assertExists()
        rule.onNode(hasText(str(R.string.setup_on))).assertDoesNotExist()
        rule.onNode(hasText(str(R.string.setup_switches))).assertExists()
        rule.onNode(hasText(str(R.string.setup_restricted_intro))).assertExists()
        rule.onNode(hasText(str(R.string.setup_later)) and heading).assertExists()
        rule.onNode(hasText(str(R.string.setup_permissions)) and heading).assertDoesNotExist()
        // No answer was No thanks: nothing says so.
        rule.onNode(hasTestTag("declined")).assertDoesNotExist()
        // On Setup the header has no Turn on of its own: the page's is the only one.
        rule.onAllNodes(hasText(str(R.string.setup_turn_on)) and button).assertCountEquals(1)
        rule.onNode(hasTestTag("setup-turn-on")).performClick()
        rule.runOnIdle { assertEquals(1, turnOns) }
        picture("firstrun-2-turnon")
    }

    @Test fun afterNoThanksSetupSaysSoAboveTurnOn() {
        setup(SetupState(), declined = true)
        val notice = rule.onNode(hasTestTag("declined")).fetchSemanticsNode().boundsInRoot
        val turnOn = rule.onNode(hasTestTag("setup-turn-on")).fetchSemanticsNode().boundsInRoot
        rule.onNode(hasText(str(R.string.setup_declined))).assertExists()
        assertTrue("the notice stands above the button it speaks of", notice.bottom <= turnOn.top)
        picture("firstrun-nothanks")
    }

    @Test fun advancedProtectionTakesTurnOnsPlace() {
        setup(SetupState(advancedProtection = true), declined = true)
        rule.onNode(hasText(str(R.string.setup_aap_title))).assertExists()
        rule.onNode(hasTestTag("setup-turn-on")).assertDoesNotExist()
        rule.onNode(hasTestTag("declined")).assertDoesNotExist()
        rule.onNode(hasText(str(R.string.setup_app_info)) and button).assertExists()
        picture("setup-advanced-protection")
    }

    @Test fun aPermissionAndroidWontAskAboutAgainSaysWhereToAllowIt() {
        setup(SetupState(serviceOn = true), wontAsk = setOf(Manifest.permission.READ_CALENDAR))
        rule.onAllNodes(hasTestTag("wont-ask")).assertCountEquals(1)
        rule.onNode(hasText(str(R.string.setup_denied_settings))).assertExists()
        rule.onNode(hasText(str(R.string.common_open_settings)) and button).assertExists()
        val row = rule.onNode(hasTestTag("permission:calendar")).fetchSemanticsNode().boundsInRoot
        val notice = rule.onNode(hasTestTag("wont-ask")).fetchSemanticsNode().boundsInRoot
        assertTrue("under its own permission", notice.top >= row.bottom - 1f && notice.top < row.bottom + 24f)
        picture("setup-wont-ask")
    }

    @Test fun aPermissionAllowedSinceSaysNothingOfTheRefusal() {
        setup(SetupState(serviceOn = true, calendar = true), wontAsk = setOf(Manifest.permission.READ_CALENDAR))
        rule.onNode(hasTestTag("wont-ask")).assertDoesNotExist()
    }

    /** A type of the newer kind, which needs the camera and Modify system settings: Setup has a row for each, and none for the rest. */
    private val needing = object : ItemType("needs-test", R.string.access_camera_title, com.meridian11.books.bentobar.util.Sym.VIDEOCAM, R.string.access_camera_explain) {
        override val group = ItemGroup.TOOLS
        override val needs = listOf(Access.CAMERA, Access.WRITE_SETTINGS)
        override fun state(item: ItemConfig) = ItemState()
    }

    @Test fun aTypeThatNeedsAnAccessGetsItsRow() {
        val state = SetupState(serviceOn = true, access = setOf(Access.WRITE_SETTINGS))
        window(Page.SETUP, running = true) { SetupPage(state, declined = false, wontAsk = setOf(Manifest.permission.CAMERA), types = listOf(needing), onTurnOn = {}) }
        // Not given: Allow, named for a screen reader. Given: Allowed, and the way to Android's setting.
        rule.onNode(hasTestTag("permission:access-camera")).assertExists()
        rule.onNode(hasContentDescription(str(R.string.setup_action_for, str(R.string.common_allow), str(R.string.access_camera_title)))).assertExists()
        rule.onNode(hasText(str(R.string.setup_allowed)) and within("permission:access-write_settings"), useUnmergedTree = true).assertExists()
        rule.onNode(hasContentDescription(str(R.string.setup_action_for, str(R.string.setup_open_setting), str(R.string.access_write_settings_title)))).assertExists()
        // The camera refused for good says so once, under its own row, as the other permissions do.
        rule.onAllNodes(hasTestTag("wont-ask")).assertCountEquals(1)
        val row = rule.onNode(hasTestTag("permission:access-camera")).fetchSemanticsNode().boundsInRoot
        val notice = rule.onNode(hasTestTag("wont-ask")).fetchSemanticsNode().boundsInRoot
        assertTrue("under its own row", notice.top >= row.bottom - 1f && notice.top < row.bottom + 24f)
        // What no type needs has no row.
        rule.onNode(hasTestTag("permission:access-notices")).assertDoesNotExist()
        rule.onNode(hasTestTag("permission:access-do_not_disturb")).assertDoesNotExist()
    }

    @Test fun aGuardedNotificationAccessSaysHowAndOffersAppInfo() {
        setup(SetupState(serviceOn = true, sideloaded = true))
        // Notification icons' access is guarded the same way, and its row says so too: each says it in its own row.
        for (row in listOf("permission:listener", "permission:access-notices")) {
            rule.onNode(hasText(str(R.string.media_access_restricted), substring = true) and within(row), useUnmergedTree = true).assertExists()
            rule.onNode(hasText(str(R.string.setup_app_info)) and button and within(row)).assertExists()
        }
    }

    @Test @Config(sdk = [37], qualifiers = "w1180dp-h780dp-mdpi")
    fun theDisclosureIsAPageWithTodaysWordsAndExactlyTwoButtons() {
        disclosure()
        rule.onNode(hasText(str(R.string.disclosure_title)) and heading).assertExists()
        for (id in listOf(R.string.disclosure_intro, R.string.setup_turn_on_draws, R.string.setup_turn_on_reads, R.string.setup_turn_on_windows,
            R.string.setup_turn_on_copies, R.string.setup_turn_on_runs, R.string.setup_turn_on_doesnt, R.string.disclosure_next))
            rule.onNode(hasText(str(id))).assertExists()
        // Agree and No thanks, and no third control on the page; the header has none either while it is up.
        rule.onAllNodes(button and within("disclosure")).assertCountEquals(2)
        rule.onNode(hasText(str(R.string.disclosure_decline)) and button).assertExists()
        rule.onNode(hasText(str(R.string.disclosure_agree)) and button).assertExists()
        rule.onNode(hasText(str(R.string.setup_turn_on)) and button).assertDoesNotExist()
        rule.onNode(role(Role.Switch)).assertDoesNotExist()
        rule.runOnIdle { assertTrue("nothing is answered by showing it", answers.isEmpty()) }
        picture("firstrun-1-consent")
        dark = true
        rule.waitForIdle()
        picture("firstrun-1-consent-dark")
    }

    @Test @Config(sdk = [37], qualifiers = "w1180dp-h780dp-mdpi")
    fun eachButtonGivesItsOwnAnswerAndBackGivesNone() {
        disclosure()
        rule.runOnIdle { activity.onBackPressedDispatcher.onBackPressed() }
        rule.runOnIdle { assertEquals("Back is no answer", listOf("dismiss"), answers) }
        rule.onNode(hasTestTag("disclosure-decline")).performClick()
        rule.runOnIdle { assertEquals(listOf("dismiss", "decline"), answers) }
        rule.onNode(hasTestTag("disclosure-agree")).performClick()
        rule.runOnIdle { assertEquals(listOf("dismiss", "decline", "agree"), answers) }
    }

    @Test @Config(sdk = [37], qualifiers = "w700dp-h1100dp-mdpi")
    fun inANarrowWindowTheDisclosureKeepsItsTwoButtons() {
        disclosure()
        rule.onNode(hasTestTag("disclosure-decline")).performScrollTo().assertExists()
        rule.onNode(hasTestTag("disclosure-agree")).performScrollTo().assertExists()
        picture("firstrun-1-consent-narrow")
    }

    @Test @Config(sdk = [37], qualifiers = "w700dp-h1900dp-mdpi")
    fun inANarrowWindowAPermissionsButtonGoesUnderItsWords() {
        setup(SetupState())
        val name = rule.onNode(hasText(str(R.string.setup_calendar_title))).fetchSemanticsNode().boundsInRoot
        val allow = rule.onNode(hasContentDescription(str(R.string.setup_action_for, str(R.string.common_allow), str(R.string.setup_calendar_title)))).fetchSemanticsNode().boundsInRoot
        assertTrue(allow.top >= name.bottom)
        picture("setup-narrow")
    }

    @Test @Config(sdk = [37], qualifiers = "w1180dp-h1100dp-mdpi")
    fun aboutIsTwoColumnsAndAnItemsPrivacyOpensOnAClick() {
        window(Page.ABOUT, running = true) { AboutPage() }
        for (id in listOf(R.string.about_privacy, R.string.about_privacy_items, R.string.about_open_source, R.string.about_how, R.string.about_who))
            rule.onNode(hasText(str(id)) and heading).assertExists()
        val app = rule.onNode(hasText(str(R.string.about_tagline))).fetchSemanticsNode().boundsInRoot
        val privacy = rule.onNode(hasText(str(R.string.about_privacy_text))).fetchSemanticsNode().boundsInRoot
        assertTrue("privacy stands beside what the app is", privacy.left > app.right - 1f || privacy.left > app.left + 400f)
        rule.onNode(hasText(str(R.string.about_privacy_policy)) and button).assertExists()
        rule.onNode(hasText(str(R.string.weather_open_site)) and button).assertExists()
        // Every item's paragraph is behind its name: nothing is open until a click, and a screen reader can open it.
        rule.onNode(hasText(str(R.string.about_privacy_media))).assertDoesNotExist()
        val row = rule.onNode(hasTestTag("privacy:media"))
        assertTrue(SemanticsActions.Expand in row.fetchSemanticsNode().config)
        row.performClick()
        rule.onNode(hasText(str(R.string.about_privacy_media))).assertExists()
        assertTrue(SemanticsActions.Collapse in rule.onNode(hasTestTag("privacy:media")).fetchSemanticsNode().config)
        for (key in listOf("weather", "air", "flight", "stocks", "fx", "media", "notices", "mirror", "inuse", "screentime", "prompter", "others", "backup")) rule.onNode(hasTestTag("privacy:$key")).assertExists()
        picture("about")
        dark = true
        rule.waitForIdle()
        picture("about-dark")
    }

    @Test @Config(sdk = [37], qualifiers = "w700dp-h1900dp-mdpi")
    fun inANarrowWindowAboutIsOneColumn() {
        window(Page.ABOUT, running = true) { AboutPage() }
        val app = rule.onNode(hasText(str(R.string.about_tagline))).fetchSemanticsNode().boundsInRoot
        val privacy = rule.onNode(hasText(str(R.string.about_privacy_text))).fetchSemanticsNode().boundsInRoot
        assertTrue(privacy.top > app.bottom)
        picture("about-narrow")
    }
}
