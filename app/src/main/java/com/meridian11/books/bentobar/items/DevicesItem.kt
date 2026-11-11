package com.meridian11.books.bentobar.items

import android.content.Intent
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.items.DevicesRules.Device
import com.meridian11.books.bentobar.ui.MenuCard
import com.meridian11.books.bentobar.ui.MenuDivider
import com.meridian11.books.bentobar.ui.MenuEntry
import com.meridian11.books.bentobar.ui.MenuNote
import com.meridian11.books.bentobar.ui.Meter
import com.meridian11.books.bentobar.ui.rememberTick
import com.meridian11.books.bentobar.util.Now
import com.meridian11.books.bentobar.util.Sym
import com.meridian11.books.bentobar.util.SymIcon

/**
 * Device batteries: the battery of a mouse, keyboard, stylus or game controller that tells Android
 * its level. No permission is involved: Android's input devices report it themselves, and a device
 * that doesn't (headphones, a receiver that passes nothing on) is not listed.
 *
 * While an item of this type is live, each input device is asked for its battery once a minute, on a
 * background thread, and Android is listened to for devices being connected, changed or removed:
 * that, and never what a device types or clicks. Idle, nothing is asked and nothing is listened to.
 * Which devices are listed and what the bar makes of them is in [DevicesRules].
 */
object DevicesItem : ItemType("devices", R.string.item_devices_title, Sym.MOUSE, R.string.item_devices_desc) {
    override val group = ItemGroup.SYSTEM
    override val refreshMs = 5_000L
    override val canBeActive = true
    override val addsWhenActive = true

    /** The same rule as the Battery item's, so the two behave alike. */
    private val below = Threshold("lowPct", 20, 5..50, step = 5) { com.meridian11.books.bentobar.util.Fmt.percent(it) }
    override val trigger = Trigger(R.string.trigger_devices, R.string.trigger_devices_short, below)

    private val words = DevicesRules.Words(
        none = { Env.str(R.string.devices_none) },
        level = { name, percent -> Env.plural(R.plurals.devices_desc, percent, name, percent) },
        low = { name, percent -> Env.plural(R.plurals.devices_desc_low, percent, name, percent) },
        charging = { name, percent -> Env.plural(R.plurals.devices_desc_charging, percent, name, percent) },
        unknown = { name -> Env.str(R.string.devices_desc_unknown, name) },
        unnamed = { Env.str(R.string.common_unknown) },
    )

    /**
     * What the devices last said, read again when it is a minute old and whenever [watch] asks. A
     * device's battery is asked for across into the system, which can wait for the Bluetooth stack:
     * it is the loader's background thread that asks.
     */
    private val readings = Background.refresher<Unit, List<Device>>(type, every = { _, _ -> DevicesRules.EVERY_MS }, load = { _, _ -> read() })
    // At most one reading a second, however often Android reports: one that can't be taken now (another is under
    // way, or the last is under a second old) is asked for again on the next tick, never queued behind the other.
    private val watch = DevicesWatch { !readings.loading(Unit) && readings.refresh(Unit, floorMs = DevicesRules.FLOOR_MS) }
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val listener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = changed()
        override fun onInputDeviceRemoved(deviceId: Int) = changed()
        override fun onInputDeviceChanged(deviceId: Int) = changed()
    }

    /** An item of this type is being sampled. Only then is anything read: a state asked for in passing reads nothing. */
    @Volatile private var live = false
    private var listening = false
    /** The test hook's devices, shown instead of the real ones, which are then not read; null: none staged. */
    @Volatile private var staged: List<Device>? = null

    private fun input(): InputManager? = Env.app.getSystemService(InputManager::class.java)

    /** A device came, went or changed, or nobody had been listening: read now, and once more a moment later. */
    private fun changed() { if (live && staged == null) watch.changed(Now.elapsed()) }

    override fun onLive() {
        live = true
        // What was read before the bar went away is shown while it is read again, so that the item doesn't blink
        // out and in each time the bar returns; unless that was long ago, when a device may be long gone.
        if (DevicesRules.tooOld(readings.age(Unit))) readings.forget()
        val input = input()
        if (input != null && !listening) listening = runCatching { input.registerInputDeviceListener(listener, main) }.isSuccess
        changed()
    }

    override fun onIdle() {
        live = false
        if (listening) { runCatching { input()?.unregisterInputDeviceListener(listener) }; listening = false }
        watch.clear()
    }

    override fun sample(now: Long) { if (staged == null) watch.tick(now) }

    /**
     * Every input device with what it says of its battery, as the devices to list. Background thread.
     * A device that goes away under the reading doesn't take the others with it, and no name is logged.
     */
    private fun read(): List<Device> = runCatching {
        val input = input() ?: return emptyList()
        val seen = ArrayList<DevicesRules.Seen>()
        for (id in input.inputDeviceIds) runCatching {
            val device = input.getInputDevice(id)
            if (device != null && !device.isVirtual) {
                val battery = device.batteryState
                seen += DevicesRules.Seen(device.descriptor.orEmpty(), device.name.orEmpty(), device.sources, device.keyboardType,
                    battery.isPresent, battery.capacity, battery.status)
            }
        }
        DevicesRules.devices(seen)
    }.getOrDefault(emptyList())

    /** The devices to show: the staged ones, else what was last read, which is read again when it has grown old. Main thread. */
    private fun devices(): List<Device> {
        staged?.let { return it }
        if (live) readings.want(Unit)
        return readings.peek(Unit).orEmpty()
    }

    override fun state(item: ItemConfig): ItemState {
        val shown = DevicesRules.bar(devices(), below.of(item), words)
        return ItemState(icon = shown.glyph, filled = shown.filled, text = shown.text, tone = shown.tone, active = shown.active,
            desc = shown.desc, tooltip = shown.tooltip)
    }

    override val menu: @Composable (ItemConfig, MenuHost) -> Unit = { _, host ->
        rememberTick()
        // Whoever opens the menu looks at the levels: read them again, unless they were read a moment ago.
        LaunchedEffect(Unit) { if (live && staged == null) readings.refresh(Unit, floorMs = DevicesRules.MENU_FLOOR_MS) }
        val devices = devices()
        MenuCard(Sym.MOUSE, stringResource(R.string.item_devices_title),
            if (devices.isEmpty()) stringResource(R.string.devices_none) else pluralStringResource(R.plurals.devices_count, devices.size, devices.size)) {
            devices.forEach { MeterRow(it, DevicesRules.name(it, words), DevicesRules.desc(it, words)) }
            Spacer(Modifier.height(6.dp))
            // The note is what makes an empty menu honest: it says what would show here, and why headphones never do.
            MenuNote(stringResource(R.string.devices_note))
            MenuDivider()
            MenuEntry(Sym.BLUETOOTH, stringResource(R.string.devices_bluetooth_settings)) { host.close(); Env.launch(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
        }
    }

    /**
     * The test hook (debug builds): `stage mouse=15 keyboard=85c stylus=?` shows these devices instead
     * of the real ones (c: charging; ? or `unknown`: a battery without a level; `mouse:MX_Master_3S=8`
     * to name one), `stage none` no device at all, `off` the real ones again. Without a word: what is
     * shown now.
     */
    override val stages = listOf("stage mouse=15 keyboard=40c stylus=unknown", "stage keyboard:Keychron_K3=85c", "stage mouse=5", "stage none")

    override fun debug(args: List<String>): String? {
        when (args.firstOrNull()) {
            null -> return line()
            "stage" -> staged = DevicesRules.staged(args.drop(1)) ?: return null
            "off" -> { staged = null; changed() }
            else -> return null
        }
        Ticker.refresh()
        return line()
    }

    /** Kinds and levels in the hook's own words, and no names: a device's name can be a person's. */
    private fun line(): String {
        val devices = staged ?: readings.peek(Unit).orEmpty()
        return "live=$live listening=$listening staged=${staged != null} devices=${devices.size}" +
            devices.joinToString("") { " ${it.kind.name.lowercase()}=${it.percent ?: "?"}${if (it.charging) "c" else ""}" } +
            " shown=${DevicesRules.shown(devices)?.kind?.name?.lowercase() ?: "none"}"
    }
}

/**
 * One device and its battery: its glyph, its name and its level, and under them a meter that starts
 * where the name does. Low (under 20% and not charging), the level and the meter are in the error
 * color; a battery that doesn't say its level has the words for that and no meter. To a screen
 * reader the row is one sentence, [spoken], which says "low" in words.
 */
@Composable
private fun MeterRow(device: Device, name: String, spoken: String) {
    val low = DevicesRules.low(device)
    val percent = device.percent
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).clearAndSetSemantics { contentDescription = spoken }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                SymIcon(device.kind.glyph, size = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(10.dp))
            Text(name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            if (percent == null) Text(stringResource(R.string.devices_unknown), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            else {
                val level = DevicesRules.percentText(percent)
                Text(if (device.charging) stringResource(R.string.devices_level_charging, level) else level,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"), fontWeight = FontWeight.Medium,
                    color = if (low) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface, maxLines = 1)
            }
        }
        if (percent != null) {
            Spacer(Modifier.height(6.dp))
            Box(Modifier.padding(start = 30.dp)) { Meter(percent / 100f, if (low) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
        }
    }
}
