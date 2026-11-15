package com.meridian11.books.bentobar.items

import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Display
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.data.Store
import com.meridian11.books.bentobar.items.DisplaysRules.Screen
import com.meridian11.books.bentobar.ui.ChoiceRow
import com.meridian11.books.bentobar.ui.MenuCard
import com.meridian11.books.bentobar.ui.MenuDivider
import com.meridian11.books.bentobar.ui.MenuEntry
import com.meridian11.books.bentobar.ui.SwitchRow
import com.meridian11.books.bentobar.ui.rememberTick
import com.meridian11.books.bentobar.util.Sym
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Displays: connected external monitors, their resolutions and refresh rates, with an option to
 * keep the Googlebook awake while an external display is connected. Uses Android's [DisplayManager]
 * without any extra permission.
 */
object DisplaysItem : ItemType("displays", R.string.item_displays_title, Sym.DESKTOP_WINDOWS, R.string.item_displays_desc) {
    override val group = ItemGroup.SYSTEM
    override val refreshMs = 5_000L
    override val canBeActive = true
    override val addsWhenActive = true
    override val trigger = Trigger(R.string.trigger_displays, R.string.trigger_displays_short)

    /**
     * True while an external display is connected and a Displays item has "Keep awake while connected" on.
     * Kept up to date by [follow], whatever is being sampled: the promise is about the screen, and a bar hidden by a
     * full-screen app is exactly when it matters.
     */
    val keepingAwake = MutableStateFlow(false)

    private val main by lazy { Handler(Looper.getMainLooper()) }
    @Volatile private var screens: List<Screen> = emptyList()
    @Volatile private var staged: List<Screen>? = null
    private var listening = false
    /** An item is being sampled: between [onLive] and [onIdle]. */
    private var live = false
    /** The accessibility service is up and the layout asks to keep awake ([follow]). */
    private var ruled = false

    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = refreshScreens()
        override fun onDisplayRemoved(displayId: Int) = refreshScreens()
        override fun onDisplayChanged(displayId: Int) = refreshScreens()
    }

    private fun displayManager(): DisplayManager? = Env.app.getSystemService(DisplayManager::class.java)

    private fun readScreens(): List<Screen> = runCatching {
        val dm = displayManager() ?: return emptyList()
        dm.displays.mapNotNull { d ->
            runCatching {
                val mode = d.mode
                val builtIn = d.displayId == Display.DEFAULT_DISPLAY
                val label = if (builtIn) Env.str(R.string.displays_built_in) else d.name?.takeIf { it.isNotBlank() } ?: Env.str(R.string.displays_external)
                Screen(
                    id = d.displayId,
                    name = label,
                    width = mode.physicalWidth,
                    height = mode.physicalHeight,
                    hz = DisplaysRules.roundHz(mode.refreshRate),
                    hdr = d.isHdr,
                    builtIn = builtIn,
                )
            }.getOrNull()
        }.sortedBy { if (it.builtIn) 1 else 0 }
    }.getOrDefault(emptyList())

    private fun currentScreens(): List<Screen> = staged ?: screens

    private fun refreshScreens() {
        screens = readScreens()
        syncAwake()
        Ticker.refresh(type)
    }

    private fun syncAwake() {
        val want = ruled && DisplaysRules.wantAwake(Store.config.value.items, currentScreens())
        if (keepingAwake.value != want) keepingAwake.value = want
    }

    /** The display listener lives while something needs it: a sampled item, or the rule in force. */
    private fun syncListener() {
        val want = live || ruled
        if (want == listening) return
        val dm = displayManager()
        listening = if (want) runCatching { dm?.registerDisplayListener(listener, main) }.isSuccess
        else { runCatching { dm?.unregisterDisplayListener(listener) }; false }
    }

    /**
     * From the service, on every change of the layout and when it stops ([serviceUp] false): whether the rule is in
     * force, apart from sampling, which stops while the bar is hidden and never starts for an item folded behind ‹.
     * While it is in force the listener lives (the one exception to "listeners hang on onLive", see AGENTS.md) so
     * a monitor coming or going is heard; the wake lock's own guard still keeps the lock screen out of it.
     */
    fun follow(serviceUp: Boolean) {
        val now = serviceUp && DisplaysRules.ruleOn(Store.config.value.items)
        if (now != ruled) {
            ruled = now
            if (now) screens = readScreens()
            syncListener()
        }
        syncAwake()
    }

    override fun onLive() {
        screens = readScreens()
        live = true
        syncListener()
        syncAwake()
    }

    override fun onIdle() {
        live = false
        syncListener()
    }

    override fun sample(now: Long) {
        if (!listening) screens = readScreens()
        syncAwake()
    }

    override fun state(item: ItemConfig): ItemState {
        val list = currentScreens()
        val bar = DisplaysRules.bar(
            screens = list,
            mode = item.opt("show", "res_hz"),
            noneDesc = Env.str(R.string.displays_desc_none),
            connectedDesc = { n, summary -> Env.plural(R.plurals.displays_desc_connected, n, n, summary) },
        )
        return ItemState(icon = bar.icon, filled = bar.filled, text = bar.text, active = bar.active,
            desc = bar.desc, tooltip = bar.tooltip)
    }

    override val menu: @Composable (ItemConfig, MenuHost) -> Unit = { item, host ->
        rememberTick()
        val list = currentScreens()
        val externals = list.count { !it.builtIn }
        val baseSub = if (externals == 0) stringResource(R.string.displays_none)
        else pluralStringResource(R.plurals.displays_external_count, externals, externals)
        val awakeOn = item.optBool("awake", false)
        val sub = if (externals > 0 && awakeOn) stringResource(R.string.displays_keeping_awake, baseSub) else baseSub
        MenuCard(Sym.DESKTOP_WINDOWS, stringResource(R.string.item_displays_title), sub) {
            list.forEach { s ->
                ScreenRow(s)
            }
            Spacer(Modifier.height(4.dp))
            MenuDivider()
            MenuEntry(
                icon = Sym.COFFEE,
                label = stringResource(R.string.displays_keep_awake),
                detail = stringResource(if (awakeOn) R.string.common_on else R.string.common_off),
            ) {
                Store.updateItem(item.id) { it.with("awake", if (!awakeOn) "true" else null) }
                syncAwake()
            }
            MenuEntry(Sym.DESKTOP_WINDOWS, stringResource(R.string.displays_settings)) {
                host.close()
                Env.launch(Intent(Settings.ACTION_DISPLAY_SETTINGS))
            }
            MenuEntry(Sym.WIFI, stringResource(R.string.displays_cast_settings)) {
                host.close()
                if (!Env.launch(Intent(Settings.ACTION_CAST_SETTINGS), quiet = true)) {
                    Env.launch(Intent(Settings.ACTION_DISPLAY_SETTINGS))
                }
            }
        }
    }

    override val options: @Composable (ItemConfig, (ItemConfig) -> Unit) -> Unit = { item, set ->
        ChoiceRow(
            stringResource(R.string.displays_bar_label),
            listOf(
                "res_hz" to stringResource(R.string.displays_bar_res_hz),
                "res" to stringResource(R.string.displays_bar_res),
                "count" to stringResource(R.string.displays_bar_count),
            ),
            item.opt("show", "res_hz"),
        ) { set(item.with("show", it.takeIf { v -> v != "res_hz" })) }
        SwitchRow(
            stringResource(R.string.displays_keep_awake),
            item.optBool("awake", false),
            help = stringResource(R.string.displays_keep_awake_help),
        ) {
            set(item.with("awake", if (it) "true" else null))
            syncAwake()
        }
    }

    override val stages = listOf("stage 4k", "stage dual", "stage none")

    override fun debug(args: List<String>): String? = when (args.firstOrNull()) {
        null -> "staged=${staged != null} screens=${currentScreens().size} external=${currentScreens().count { !it.builtIn }} awake=${keepingAwake.value}"
        "stage" -> {
            val s = DisplaysRules.staged(args.getOrNull(1).orEmpty())
            if (s == null) null else {
                staged = s
                syncAwake()
                Ticker.refresh(type)
                "staged ${args[1]}"
            }
        }
        "off" -> {
            staged = null
            syncAwake()
            Ticker.refresh(type)
            "staging off"
        }
        else -> null
    }
}

@Composable
private fun ScreenRow(s: Screen) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(s.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val detail = if (s.hdr) stringResource(R.string.displays_mode_hdr, s.width, s.height, s.hz)
            else stringResource(R.string.displays_mode, s.width, s.height, s.hz)
            Text(detail, style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Text(
            DisplaysRules.shortRes(s.width, s.height),
            style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
            color = if (s.builtIn) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
        )
    }
}
