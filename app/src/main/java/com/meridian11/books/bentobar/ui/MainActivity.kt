package com.meridian11.books.bentobar.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.WindowInsetsController
import android.view.KeyboardShortcutGroup
import android.view.KeyboardShortcutInfo
import android.view.Menu
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.bar.BarService
import com.meridian11.books.bentobar.bar.BarStatus
import com.meridian11.books.bentobar.data.Store
import com.meridian11.books.bentobar.items.Chips
import com.meridian11.books.bentobar.items.Env
import com.meridian11.books.bentobar.items.Notify
import com.meridian11.books.bentobar.items.Ticker

/**
 * The settings window's pages: what its tab says ([label]) and what the page is called for a screen reader ([title]).
 * A new page goes here, in [tabs] and in [MainActivity]'s `when`. A saved window remembers its page by name.
 */
enum class Page(@androidx.annotation.StringRes val label: Int, @androidx.annotation.StringRes val title: Int) {
    BAR(R.string.nav_bar, R.string.page_bar),
    LOOK(R.string.nav_look, R.string.page_look),
    SETUP(R.string.nav_setup, R.string.page_setup),
    ABOUT(R.string.nav_about, R.string.page_about),
}

/**
 * Turns the settings window to a page, from a control on another (an item's options' "Open Setup"). In this process only:
 * the window is exported, and an intent that names a page is another app's say. Nothing outside the window.
 */
val LocalTurnTo = androidx.compose.runtime.staticCompositionLocalOf<(Page) -> Unit> { {} }

class MainActivity : ComponentActivity() {
    // Accessibility services turned on or off (in Settings' own window, on a Googlebook).
    private val services = AccessibilityManager.AccessibilityServicesStateChangeListener { Setup.refresh(this) }
    private var page by mutableStateOf(Page.BAR)
    private var selected by mutableStateOf<String?>(null)
    /** Counts the times an item's options were asked for from outside (the bar's menu): each turns the Bar page's panel to them. */
    private var turn by mutableIntStateOf(0)
    /** The accessibility disclosure is up ([AccessibilityDisclosure]). */
    private var disclosure by mutableStateOf(false)
    /** The permissions Android refused without showing its dialog, since this window opened: Setup says so in their rows. */
    private var wontAsk by mutableStateOf(setOf<String>())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = this
        Env.init(this)
        Notify.channels(this)
        enableEdgeToEdge()
        captionInHeaderColor()
        // Recreated (theme, language, text size or density changed): stay where the user was, and
        // don't act on the launch intent again (it could ask for a permission twice).
        if (savedInstanceState == null) {
            handle(intent)
            // First opening, service still off: the disclosure comes up by itself, in the app's normal
            // use, not behind a page someone has to find. Once answered, only Turn on brings it back.
            disclosure = ConsentRules.asksOnFirstOpening(Store.consent.value, { serviceOn(this) }, { turnedOn(this) }, { Env.advancedProtection() })
            // The disclosure is a page under the Setup tab: whatever its answer, Setup is what is under it.
            if (disclosure) page = Page.SETUP
            else if (Store.config.value.items.any { it.type == "cloud_clipboard" && it.section != com.meridian11.books.bentobar.data.Section.OFF }) {
                if (!com.meridian11.books.bentobar.items.CloudClipboardItem.hasAllFilesAccess(this)) {
                    val missing = runtimeFilePermissions().filter {
                        runCatching { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
                    }
                    if (missing.isNotEmpty()) {
                        runCatching { requestPermissions(missing.toTypedArray(), 2) }
                    } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                        Notice.post("云剪切板上传本地文件需开启「所有文件访问权限」", "去开启") { openAllFilesAccess(this) }
                    }
                }
            }
        } else {
            page = savedInstanceState.getString(STATE_PAGE).let { name -> Page.entries.firstOrNull { it.name == name } } ?: Page.BAR
            selected = savedInstanceState.getString(STATE_SELECTED)
            disclosure = savedInstanceState.getBoolean(STATE_DISCLOSURE)
        }
        // Before the first frame: what was on is told from what comes on (the window then switches to Bar).
        Setup.refresh(this)
        setContent { SettingsTheme { App() } }
    }

    /**
     * The system's caption takes the header's color: its own ground goes, [Shell] paints the header's under it, and its
     * buttons are drawn for a light or a dark ground. Nothing of BentoBar's is placed in the caption. A change of theme
     * recreates the window, so once is enough.
     */
    private fun captionInHeaderColor() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
        val light = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK != Configuration.UI_MODE_NIGHT_YES
        val ground = WindowInsetsController.APPEARANCE_TRANSPARENT_CAPTION_BAR_BACKGROUND
        val buttons = WindowInsetsController.APPEARANCE_LIGHT_CAPTION_BARS
        window.insetsController?.setSystemBarsAppearance(if (light) ground or buttons else ground, ground or buttons)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PAGE, page.name)
        outState.putString(STATE_SELECTED, selected)
        outState.putBoolean(STATE_DISCLOSURE, disclosure)
    }

    /**
     * Setup's Turn on. BentoBar opens Accessibility settings only after the user agreed to what its
     * service does (Google Play's prominent disclosure and consent): until then this shows the
     * disclosure, and Agree there opens the settings.
     */
    private fun turnOn() {
        when {
            // Advanced Protection turns the service off anyway: Setup says so where Turn on would be.
            Setup.state.value.advancedProtection -> page = Page.SETUP
            ConsentRules.turnOn(Store.consent.value) == ConsentRules.TurnOn.OPEN_SETTINGS -> openAccessibility(this)
            else -> { page = Page.SETUP; disclosure = true }
        }
    }

    private fun handle(intent: Intent?) {
        intent ?: return
        // The disclosure is up: it stays the page, under Setup, with its two buttons and no Turn on in the header. The
        // page an intent names (the launcher's "BentoBar menu", a long press on the tile, any app) is dropped, not kept
        // for after the answer: Setup is what the answer leaves, as on first opening.
        val turns = !disclosure
        if (turns) intent.getStringExtra(EXTRA_ITEM)?.let { selected = it; page = Page.BAR; turn++ }
        if (turns && intent.getBooleanExtra(EXTRA_EDIT, false)) page = Page.BAR
        // A permission's prompt is asked for in this process ([requestPermission]), never named by an intent: this activity is
        // exported, so any app could send one, and the prompt would be BentoBar's.
        takeAsked()?.let { perm -> if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(perm), 1) }
        if (turns && intent.getBooleanExtra(EXTRA_REQUEST_FILES, false)) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R &&
                !android.os.Environment.isExternalStorageManager()
            ) {
                openAllFilesAccess(this)
            } else {
                val missing = runtimeFilePermissions().filter {
                    runCatching { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
                }
                if (missing.isNotEmpty()) {
                    runCatching { requestPermissions(missing.toTypedArray(), 2) }
                } else {
                    openAllFilesAccess(this)
                }
            }
        }
        if (turns && intent.action == "android.service.quicksettings.action.QS_TILE_PREFERENCES") page = Page.LOOK
    }

    // A key no control took (nothing in the window has the focus): the Bar page's keys still act on the selected item.
    // Escape leaves the disclosure as Back does: a platform's key map need not turn the one into the other.
    // On Look, Undo's key takes back what Look itself did ("Where BentoBar sits"), which that page shows; a change of the
    // Bar page's, which Look doesn't show, stays for that page.
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean = when {
        disclosure && keyCode == android.view.KeyEvent.KEYCODE_ESCAPE -> { disclosure = false; true }
        page == Page.LOOK && lookUndo() && BarKeys.UNDO.pressed(androidx.compose.ui.input.key.KeyEvent(event)) -> Undo.undo()
        else -> (page == Page.BAR && BarKeys.handler?.invoke(event) == true) || super.onKeyDown(keyCode, event)
    }

    /** Whether the last change is Look's command and can still be taken back. */
    private fun lookUndo() = Undo.allTo(Undo.last.value, Store.config.value) != null

    /**
     * The system's list of shortcuts: the Bar page's bindings themselves ([BarKeys.listed]), while that page is shown,
     * and on Look its Undo while that has something to take back, so the list and what the window does with a key
     * cannot disagree.
     */
    override fun onProvideKeyboardShortcuts(data: MutableList<KeyboardShortcutGroup>, menu: Menu?, deviceId: Int) {
        super.onProvideKeyboardShortcuts(data, menu, deviceId)
        val keys = when {
            page == Page.BAR -> BarKeys.listed()
            page == Page.LOOK && lookUndo() -> listOf(BarKeys.UNDO)
            else -> emptyList()
        }
        if (keys.isEmpty()) return
        data += KeyboardShortcutGroup(getString(page.title), keys.map { KeyboardShortcutInfo(getString(it.does), it.keycode, it.modifiers) })
    }

    /** This window's end has been seen ([closing]). */
    private var closed = false

    /**
     * Undo is this window's, and its reach ends when the window is closed for good ([Undo.end]): asked at each step of
     * the window's way out, so at the first that knows. Not when it is made again (a rotation, a new size) or only left
     * for another window: it is not finishing then. A window opened since has Undo by now, with what it may have
     * changed already; an end that comes that late takes nothing.
     */
    private fun closing() {
        if (closed || !isFinishing) return
        closed = true
        if (current === this) Undo.end()
    }

    override fun onDestroy() {
        closing()
        if (current === this) current = null
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        getSystemService(AccessibilityManager::class.java)?.addAccessibilityServicesStateChangeListener(mainExecutor, services)
    }

    override fun onStop() {
        getSystemService(AccessibilityManager::class.java)?.removeAccessibilityServicesStateChangeListener(services)
        closing()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        Setup.cameBack(this)
        Ticker.start("settings")
        Chips.update(this)
    }

    override fun onPause() {
        super.onPause()
        Ticker.stop("settings")
        closing()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 2) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R &&
                !android.os.Environment.isExternalStorageManager()
            ) {
                Notice.post("云剪切板上传本地文件需开启「所有文件访问权限」", "去开启") { openAllFilesAccess(this) }
            }
        } else {
            // Refused, and Android won't show its dialog again: say where it can still be allowed.
            permissions.forEachIndexed { i, p ->
                val refused = grantResults.getOrNull(i) == PackageManager.PERMISSION_DENIED && !shouldShowRequestPermissionRationale(p)
                wontAsk = if (refused) wontAsk + p else wontAsk - p
                // On Setup the permission's own row says it; elsewhere, the window's message.
                if (refused && !SetupRules.saidInItsRow(page, p))
                    Notice.post(getString(R.string.setup_denied_settings), getString(R.string.common_open_settings)) { openAppInfo(this) }
            }
        }
        Setup.refresh(this)
        // Location allowed (or not) for Weather's My location: looked at now, not at its next look.
        com.meridian11.books.bentobar.items.WeatherHere.wake()
        Ticker.refresh()
    }

    // In desktop windowing, Settings opens in its own window and this one stays resumed, so coming
    // back is a focus change, not a resume.
    override fun onTopResumedActivityChanged(isTopResumedActivity: Boolean) {
        super.onTopResumedActivityChanged(isTopResumedActivity)
        if (isTopResumedActivity) Setup.refresh(this)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) Setup.cameBack(this)
    }

    @Composable
    private fun App() {
        val cfg by Store.config.collectAsState()
        val setup by Setup.state.collectAsState()
        val status by BarStatus.current.collectAsState()
        val consent by Store.consent.collectAsState()
        val running = setup.serviceOn
        // Setup says so: once BentoBar is on, this window shows the bar. A disclosure still up has nothing left to ask.
        val was = remember { booleanArrayOf(running) }
        LaunchedEffect(running) {
            if (running) disclosure = false
            if (SetupRules.switchesToBar(was[0], running, page)) page = Page.BAR
            was[0] = running
        }
        // A click on a tab leaves the disclosure like Escape does: no answer, nothing recorded.
        Shell(page, { disclosure = false; page = it }, running, cfg.enabled, status, onEnabled = { on -> Store.update { it.copy(enabled = on) } }, onTurnOn = ::turnOn,
            presenting = cfg.presenting) {
            // Before the pages: a message is seen only by someone already listening.
            val snackbar = rememberNotices()
            // The disclosure takes Setup's place while it is up: only Agree records consent and opens the system's settings.
            if (disclosure) AccessibilityDisclosure(
                onAgree = { disclosure = false; Store.setConsent(Store.Consent.AGREED); openAccessibility(this@MainActivity) },
                onDecline = { disclosure = false; Store.setConsent(Store.Consent.DECLINED) },
                onDismiss = { disclosure = false },
            ) else androidx.compose.runtime.CompositionLocalProvider(LocalTurnTo provides { if (!disclosure) page = it }) {
                when (page) {
                    Page.BAR -> BarPage(selected, onSelect = { selected = it }, turn = turn, running = running)
                    Page.LOOK -> LookPage()
                    Page.SETUP -> SetupPage(setup, consent == Store.Consent.DECLINED, wontAsk, onTurnOn = ::turnOn)
                    Page.ABOUT -> AboutPage()
                }
            }
            // Last, so it's drawn over the page (in a Box, later children are on top).
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
        }
    }

    companion object {
        /** The open settings window, for the adb test hooks. */
        @Volatile var current: MainActivity? = null
        const val EXTRA_ITEM = "item"
        const val EXTRA_EDIT = "edit"
        const val EXTRA_REQUEST_FILES = "request_files"
        /** The permission a menu asked for, and when: read once by the window that start opens. */
        private var asked: Pair<String, Long>? = null
        private const val ASKED_MS = 10_000L

        @Synchronized private fun ask(permission: String) { asked = permission to SystemClock.elapsedRealtime() }

        @Synchronized private fun takeAsked(): String? {
            val (permission, at) = asked ?: return null
            asked = null
            return permission.takeIf { SystemClock.elapsedRealtime() - at <= ASKED_MS }
        }
        private const val STATE_PAGE = "page"
        private const val STATE_SELECTED = "selected"
        private const val STATE_DISCLOSURE = "disclosure"

        /** Opens BentoBar's settings, on [itemId] if given. */
        fun open(context: Context, itemId: String?) {
            context.startActivity(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_EDIT, true).apply { if (itemId != null) putExtra(EXTRA_ITEM, itemId) })
        }

        /** Runtime permissions need an activity; the bar's menus come through here. */
        fun requestPermission(context: Context, permission: String) {
            // Asked for from a menu ("Allow calendar"): that's switching it back on in Setup, too. Done
            // here, not from the intent: the activity is exported, and another app's intent mustn't undo it.
            if (permission == android.Manifest.permission.READ_CALENDAR)
                Store.update { it.copy(turnedOff = it.turnedOff - com.meridian11.books.bentobar.data.Uses.CALENDAR) }
            ask(permission)
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }

        fun runtimeFilePermissions(): Array<String> =
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                arrayOf(
                    android.Manifest.permission.READ_MEDIA_IMAGES,
                    android.Manifest.permission.READ_MEDIA_VIDEO,
                    android.Manifest.permission.READ_MEDIA_AUDIO,
                )
            } else {
                arrayOf(
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                )
            }

        fun openAllFilesAccess(context: Context): Boolean {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                val pkgUri = Uri.fromParts("package", context.packageName, null)
                val appIntent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkgUri)
                if (Env.launch(appIntent, quiet = true)) return true
                val allIntent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                if (Env.launch(allIntent, quiet = true)) return true
            }
            openAppInfo(context)
            return true
        }

        fun requestFilePermissions(context: Context) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R &&
                !android.os.Environment.isExternalStorageManager()
            ) {
                openAllFilesAccess(context)
                return
            }
            val act = current
            val perms = runtimeFilePermissions()
            val missingRuntime = perms.filter {
                runCatching { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
            }
            if (missingRuntime.isNotEmpty()) {
                if (act != null) {
                    runCatching { act.requestPermissions(missingRuntime.toTypedArray(), 2) }
                } else {
                    context.startActivity(
                        Intent(context, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            .putExtra(EXTRA_REQUEST_FILES, true)
                    )
                }
            } else {
                openAllFilesAccess(context)
            }
        }

        fun serviceComponent(context: Context) = ComponentName(context, BarService::class.java)

        /** Enabled in Accessibility settings (the service may take a moment to connect). */
        fun serviceOn(context: Context): Boolean {
            if (Env.running) return true
            val am = context.getSystemService(AccessibilityManager::class.java) ?: return false
            val me = serviceComponent(context)
            return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { it.resolveInfo.serviceInfo.let { s -> s.packageName == me.packageName && s.name == me.className } }
        }

        /**
         * Turned on in Accessibility settings, as Android's own setting has it. It says so before the
         * service is bound again, as in the first moments after an update, when [serviceOn] still says no
         * and the disclosure came up over a service that was on.
         */
        private fun turnedOn(context: Context): Boolean = runCatching {
            val me = serviceComponent(context)
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
                .split(':').any { ComponentName.unflattenFromString(it) == me }
        }.getOrDefault(false)

        /**
         * Accessibility settings. The extras ask Settings to scroll to and highlight BentoBar
         * (honoured by Android's own Settings app; harmless elsewhere).
         */
        fun openAccessibility(context: Context) {
            val cn = serviceComponent(context).flattenToString()
            Env.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .putExtra(":settings:fragment_args_key", cn)
                .putExtra(":settings:show_fragment_args", Bundle().apply { putString(":settings:fragment_args_key", cn) }))
        }

        fun openAppInfo(context: Context) {
            Env.launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
        }
    }
}
