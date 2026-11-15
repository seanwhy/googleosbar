package com.meridian11.books.bentobar.items

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.data.Store
import com.meridian11.books.bentobar.ui.CoreBars
import com.meridian11.books.bentobar.ui.InfoRow
import com.meridian11.books.bentobar.ui.MenuCard
import com.meridian11.books.bentobar.ui.MenuDivider
import com.meridian11.books.bentobar.ui.MenuEntry
import com.meridian11.books.bentobar.ui.MenuNote
import com.meridian11.books.bentobar.ui.SectionLabel
import com.meridian11.books.bentobar.ui.Sparkline
import com.meridian11.books.bentobar.ui.SwitchRow
import com.meridian11.books.bentobar.ui.rememberTick
import com.meridian11.books.bentobar.util.Fmt
import com.meridian11.books.bentobar.util.Sym
import java.util.Locale

// The CPU item, in a file of its own (it was in SystemItems.kt).

/**
 * CPU load. With "Also show when the device runs hot" (off unless turned on, so a layout saved before
 * the switch existed behaves as it did) the item also comes out from Android's moderate thermal
 * status on, with the thermometer in the chip's place and in the warning tone, so that the reason
 * shows. The rule is [CpuRule]; the status is [Heat]'s, the one the Heat item shows too.
 */
object CpuItem : ItemType("cpu", R.string.item_cpu_title, Sym.MEMORY, R.string.item_cpu_desc) {
    override val group = ItemGroup.SYSTEM
    override val holds = true
    override val canBeActive = true
    private val above = Threshold("activePct", 80, 30..99) { com.meridian11.books.bentobar.util.Fmt.percent(it) }
    // With the switch on, the rule's words say so: "…, or the device runs hot".
    override val trigger = object : Trigger(R.string.trigger_cpu, R.string.trigger_cpu_short, above) {
        override fun sentenceRes(item: ItemConfig) = if (CpuRule.alsoHot(item)) R.string.trigger_cpu_hot else sentence
        override fun shortRes(item: ItemConfig) = if (CpuRule.alsoHot(item)) R.string.trigger_cpu_hot_short else short
    }

    private val heatSampler = setOf("battery")
    /** CPU menus that are open: their "Heat" row shows the thermal status too. */
    @Volatile private var menus = 0

    /**
     * The thermal status is the battery sampler's reading. It runs for this type only while the
     * status is wanted: an item has the switch on, or a CPU menu is open. A CPU item as 0.8 saved it
     * reads what it read then, and nothing more.
     */
    override val samples: Set<String>
        get() = if (menus > 0 || Store.config.value.items.any { it.type == type && CpuRule.alsoHot(it) }) heatSampler else emptySet()

    override fun state(item: ItemConfig): ItemState {
        val c = Env.cpu
        val alsoHot = CpuRule.alsoHot(item)
        // The status is looked at only where the item's rule asks for it.
        val status = if (alsoHot) Heat.status() else HeatRules.NONE
        val shown = CpuRule.shown(if (c.available) c.total else null, above.of(item), alsoHot, status)
        val icon = if (shown.hot) Sym.DEVICE_THERMOSTAT else Sym.MEMORY
        val tone = if (shown.warn) Tone.WARN else Tone.NORMAL
        // No load to show on this device: with the switch on the item still comes out when hot, and says that instead.
        if (!c.available) return ItemState(icon = icon, text = "–", active = shown.active, tone = tone,
            desc = if (shown.hot) Env.str(R.string.heat_desc, Env.str(Heat.words(status))) else Env.str(R.string.cpu_unavailable_desc))
        return ItemState(icon = icon, text = Fmt.percent(c.total), active = shown.active, tone = tone, widthKey = "cpu",
            desc = Env.str(when {
                shown.hot -> R.string.cpu_state_desc_hot
                shown.high -> R.string.cpu_state_desc_high
                else -> R.string.cpu_state_desc
            }, Fmt.percent(c.total)))
    }

    override val menu: @Composable (ItemConfig, MenuHost) -> Unit = { _, host ->
        rememberTick()
        val c = Env.cpu
        androidx.compose.runtime.DisposableEffect(Unit) {
            c.detail++; menus++
            // The battery's sampler reads the thermal status for this menu from the next tick on. One reading now, and the
            // menu drawn again with it, so that the "Heat" row doesn't stand on a status from the last time anything looked.
            Heat.look(); Ticker.refresh(type)
            onDispose { c.detail--; menus-- }
        }
        MenuCard(Sym.MEMORY, stringResource(R.string.cpu_menu_title),
            if (c.available) pluralStringResource(R.plurals.cpu_menu_subtitle, c.perCore.size, Fmt.percent(c.total), c.perCore.size)
            else stringResource(R.string.cpu_menu_unavailable)) {
            if (c.available) {
                InfoRow(stringResource(R.string.cpu_overall), Fmt.percent(c.total), MaterialTheme.colorScheme.primary)
                Sparkline(c.history.toList(), max = 1.0)
                Spacer(Modifier.height(6.dp))
                HeatRow(Heat.status())
                Spacer(Modifier.height(8.dp))
                SectionLabel(stringResource(R.string.cpu_cores))
                CoreBars(c.perCore)
                Spacer(Modifier.height(6.dp))
                c.clusters.forEach { cl ->
                    // "Cores 0–3" for a group; a core with its own clock speed (each one, on some Intel
                    // chips) is "Core 0".
                    InfoRow(stringResource(if (cl.cores.all { it.isDigit() }) R.string.cpu_core_one else R.string.cpu_cluster, cl.cores), stringResource(R.string.cpu_cluster_clock,
                        String.format(Locale.ROOT, "%.2f", cl.curKhz / 1e6), String.format(Locale.ROOT, "%.2f", cl.maxKhz / 1e6)))
                }
                c.gpu?.let { g ->
                    MenuDivider()
                    InfoRow(stringResource(R.string.cpu_gpu), Fmt.percent(g), MaterialTheme.colorScheme.tertiary)
                    Sparkline(c.gpuHistory.toList(), color = MaterialTheme.colorScheme.tertiary, max = 1.0)
                }
            } else HeatRow(Heat.status()) // whether the device is hot doesn't depend on its sharing the CPU's load
            Spacer(Modifier.height(6.dp))
            MenuNote(stringResource(R.string.cpu_per_app_note))
            MenuDivider()
            MenuEntry(Sym.BOLT, stringResource(R.string.cpu_battery_usage)) { host.close(); Env.launch(Intent(Intent.ACTION_POWER_USAGE_SUMMARY)) }
            MenuEntry(Sym.APPS, stringResource(R.string.common_apps)) { host.close(); Env.launch(Intent(Settings.ACTION_APPLICATION_SETTINGS)) }
            // Developer options only when the user has turned them on.
            val dev = androidx.compose.runtime.remember { developerOptionsOn() }
            if (dev) MenuEntry(Sym.BUILD, stringResource(R.string.cpu_developer_options)) {
                host.close(); Env.launch(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            }
        }
    }

    override val options: @Composable (ItemConfig, (ItemConfig) -> Unit) -> Unit = { item, set ->
        SwitchRow(stringResource(R.string.cpu_also_hot), CpuRule.alsoHot(item), help = stringResource(R.string.cpu_also_hot_help)) { on ->
            // Off takes the key out again: the layout is then as it was before the switch existed.
            set(item.with(CpuRule.ALSO_HOT, if (on) "true" else null))
        }
    }
}

/**
 * The CPU menu's "Heat" row, whatever the item's switch says: Android's thermal status in words, in
 * the error color from "Hot" on. The status comes in from the menu, which is drawn again with every
 * tick: read in here, it would be read once.
 */
@Composable
private fun HeatRow(status: Int) {
    InfoRow(stringResource(R.string.cpu_heat), stringResource(Heat.words(status)),
        if (status >= HeatRules.MODERATE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
}

/** Whether the user has turned on Developer options (a global setting any app may read). */
private fun developerOptionsOn(): Boolean = runCatching {
    Settings.Global.getInt(Env.app.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
}.getOrDefault(false)
