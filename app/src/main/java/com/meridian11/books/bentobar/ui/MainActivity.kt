package com.meridian11.books.bentobar.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarHost
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.bar.BarService
import com.meridian11.books.bentobar.bar.BarStatus
import com.meridian11.books.bentobar.data.Store
import com.meridian11.books.bentobar.items.Chips
import com.meridian11.books.bentobar.items.Env
import com.meridian11.books.bentobar.items.HereRules
import com.meridian11.books.bentobar.items.Items
import com.meridian11.books.bentobar.items.Notify
import com.meridian11.books.bentobar.items.Ticker
import com.meridian11.books.bentobar.util.Sym
import com.meridian11.books.bentobar.util.SymIcon

class MainActivity : ComponentActivity() {
    // Accessibility services turned on or off (in Settings' own window, on a Googlebook).
    private val services = AccessibilityManager.AccessibilityServicesStateChangeListener { Setup.refresh(this) }
    private var page by mutableIntStateOf(0)
    private var selected by mutableStateOf<String?>(null)
    /** The accessibility disclosure is up ([AccessibilityDisclosure]). */
    private var disclosure by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = this
        Env.init(this)
        Notify.channels(this)
        enableEdgeToEdge()
        // Recreated (theme, language, text size or density changed): stay where the user was, and
        // don't act on the launch intent again (it could ask for a permission twice).
        if (savedInstanceState == null) {
            handle(intent)
            // First opening, service still off: the disclosure comes up by itself, in the app's normal
            // use, not behind a page someone has to find. Once answered, only Turn on brings it back.
            disclosure = Store.consent.value == Store.Consent.NOT_ASKED && !serviceOn(this) && !turnedOn(this) && !Env.advancedProtection()
            if (!disclosure && Store.config.value.items.any { it.type == "cloud_clipboard" && it.section != com.meridian11.books.bentobar.data.Section.OFF }) {
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
            page = savedInstanceState.getInt(STATE_PAGE); selected = savedInstanceState.getString(STATE_SELECTED)
            disclosure = savedInstanceState.getBoolean(STATE_DISCLOSURE)
        }
        setContent {
            BentoBarTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    App()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_PAGE, page)
        outState.putString(STATE_SELECTED, selected)
        outState.putBoolean(STATE_DISCLOSURE, disclosure)
    }

    /**
     * Setup's Turn on. BentoBar opens Accessibility settings only after the user agreed to what its
     * service does (Google Play's prominent disclosure and consent): until then this shows the
     * disclosure, and Agree there opens the settings.
     */
    private fun turnOn() {
        if (Store.consent.value == Store.Consent.AGREED) openAccessibility(this) else disclosure = true
    }

    private fun handle(intent: Intent?) {
        intent ?: return
        intent.getStringExtra(EXTRA_ITEM)?.let { selected = it; page = 0 }
        if (intent.getBooleanExtra(EXTRA_EDIT, false)) page = 0
        intent.getStringExtra(EXTRA_REQUEST)?.let { perm ->
            // Location only for a layout with an item of My location: this activity is exported, and another app's intent mustn't ask it.
            if (perm == android.Manifest.permission.ACCESS_COARSE_LOCATION && !HereRules.mayAsk(Store.config.value.items)) return@let
            if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(perm), 1)
        }
        if (intent.getBooleanExtra(EXTRA_REQUEST_FILES, false)) {
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
        if (intent.action == "android.service.quicksettings.action.QS_TILE_PREFERENCES") page = 2
    }

    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        getSystemService(AccessibilityManager::class.java)?.addAccessibilityServicesStateChangeListener(mainExecutor, services)
    }

    override fun onStop() {
        getSystemService(AccessibilityManager::class.java)?.removeAccessibilityServicesStateChangeListener(services)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        Setup.refresh(this)
        Ticker.start("settings")
        Chips.update(this)
    }

    override fun onPause() {
        super.onPause()
        Ticker.stop("settings")
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
                if (grantResults.getOrNull(i) == PackageManager.PERMISSION_DENIED && !shouldShowRequestPermissionRationale(p))
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
        if (hasFocus) Setup.refresh(this)
    }

    @Composable
    private fun App() {
        val cfg by Store.config.collectAsState()
        val setup by Setup.state.collectAsState()
        val status by BarStatus.current.collectAsState()
        val running = setup.serviceOn
        Row(Modifier.fillMaxSize().safeDrawingPadding()) {
            NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxHeight()) {
                Spacer(Modifier.height(12.dp))
                androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(com.meridian11.books.bentobar.R.drawable.ic_bentobar), contentDescription = stringResource(R.string.app_name),
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
                Spacer(Modifier.height(20.dp))
                val pages = listOf(Sym.WYSIWYG to R.string.nav_bar, Sym.ADD to R.string.nav_add, Sym.PALETTE to R.string.nav_look,
                    Sym.TUNE to R.string.nav_setup, Sym.INFO to R.string.nav_about)
                pages.forEachIndexed { i, (icon, label) ->
                    NavigationRailItem(selected = page == i, onClick = { page = i },
                        icon = { SymIcon(icon, size = 22.sp, filled = page == i) }, label = { Text(stringResource(label)) })
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Row(Modifier.fillMaxWidth().padding(start = 28.dp, end = 20.dp, top = 18.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(listOf(R.string.page_bar, R.string.page_add, R.string.page_look, R.string.page_setup, R.string.page_about)[page]),
                            style = MaterialTheme.typography.headlineSmall)
                        // Says what the strip is actually doing, not just whether the service is on.
                        Text(stringResource(when {
                            !running -> if (page == 3) R.string.status_service_off_setup else R.string.status_service_off
                            !cfg.enabled -> R.string.status_hidden
                            else -> when (status) {
                                BarStatus.STOPPED -> R.string.status_starting
                                BarStatus.NO_ROOM -> R.string.status_no_room
                                BarStatus.NO_BAR -> R.string.status_no_bar
                                BarStatus.COVERED -> R.string.status_covered
                                else -> R.string.status_live
                            }
                        }), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    // Only meaningful once the service runs; before that, Setup is the way in.
                    if (running) {
                        Row(Modifier.toggleable(cfg.enabled, role = androidx.compose.ui.semantics.Role.Switch) { on -> Store.update { it.copy(enabled = on) } },
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.show_in_status_bar), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(end = 10.dp))
                            Switch(checked = cfg.enabled, onCheckedChange = null)
                        }
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (page) {
                        0 -> BarPage(running, selected, onSelect = { selected = it }, onSetup = { page = 3 })
                        // Adding keeps you on Add: a message confirms it, with Undo, instead of jumping pages.
                        1 -> AddPage { id ->
                            val added = Store.config.value.items.firstOrNull { it.id == id }
                            val type = added?.let { Items.of(it.type) }
                            val title = type?.title.orEmpty()
                            // Added to When active, the bar looks as before: the message says when the item will show.
                            val rule = added?.takeIf { it.whenActive && it.section == com.meridian11.books.bentobar.data.Section.HIDDEN }?.let { type?.trigger?.short(it) }
                            if (added?.type == "cloud_clipboard" && !com.meridian11.books.bentobar.items.CloudClipboardItem.hasAllFilesAccess(this@MainActivity)) {
                                Notice.post("已添加云剪切板 · 上传本地文件需开启文件读取权限", "去开启") {
                                    requestFilePermissions(this@MainActivity)
                                }
                            } else {
                                Notice.post(if (rule != null) getString(R.string.add_added_rule, title, rule) else getString(R.string.add_added, title),
                                    getString(R.string.bar_undo)) { Store.remove(id) }
                            }
                        }
                        2 -> LookPage()
                        3 -> SetupPage(this@MainActivity, setup, onTurnOn = ::turnOn)
                        else -> AboutPage()
                    }
                    val snackbar = androidx.compose.runtime.remember { SnackbarHostState() }
                    androidx.compose.runtime.LaunchedEffect(Unit) {
                        Notice.messages.collect { m ->
                            if (snackbar.showSnackbar(m.text, m.action, duration = androidx.compose.material3.SnackbarDuration.Long) == SnackbarResult.ActionPerformed) m.onAction?.invoke()
                        }
                    }
                    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
                }
            }
        }
        if (disclosure) AccessibilityDisclosure(
            onAgree = { disclosure = false; Store.setConsent(Store.Consent.AGREED); openAccessibility(this) },
            onDecline = { disclosure = false; Store.setConsent(Store.Consent.DECLINED) },
            onDismiss = { disclosure = false },
        )
    }

    companion object {
        /** The open settings window, for the adb test hooks. */
        @Volatile var current: MainActivity? = null
        const val EXTRA_ITEM = "item"
        const val EXTRA_EDIT = "edit"
        const val EXTRA_REQUEST = "request"
        const val EXTRA_REQUEST_FILES = "request_files"
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
            context.startActivity(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_REQUEST, permission))
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
            if (Env.service != null) return true
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
