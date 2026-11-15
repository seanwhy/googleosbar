package com.meridian11.books.bentobar.items

import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.items.OutputRules.Kind
import com.meridian11.books.bentobar.items.OutputRules.Out
import com.meridian11.books.bentobar.ui.InfoRow
import com.meridian11.books.bentobar.ui.MenuCard
import com.meridian11.books.bentobar.ui.MenuDivider
import com.meridian11.books.bentobar.ui.MenuEntry
import com.meridian11.books.bentobar.ui.MenuNote
import com.meridian11.books.bentobar.ui.Meter
import com.meridian11.books.bentobar.ui.SwitchRow
import com.meridian11.books.bentobar.ui.rememberTick
import com.meridian11.books.bentobar.util.Fmt
import com.meridian11.books.bentobar.util.Sym
import kotlin.math.roundToInt

/**
 * Headphones and output: where sound plays now, the headphones' or the display's own name beside the icon, and every
 * output Android lists in the menu with the media volume. No permission: Android's audio outputs and its own word on
 * where media goes are open to every app. It shows no battery (that would need a Bluetooth permission) and chooses no
 * output (Android does; its Sound settings let the user).
 *
 * While an item is live, Android tells it when an output comes or goes, and where media goes is asked again every few
 * seconds (a choice in Android's output switcher comes with no event). Idle, nothing is asked. The rules are [OutputRules].
 */
object OutputItem : ItemType("output", R.string.item_output_title, Sym.HEADPHONES, R.string.item_output_desc) {
    override val group = ItemGroup.SYSTEM
    override val canBeActive = true
    override val addsWhenActive = true
    override val trigger = Trigger(R.string.trigger_output, R.string.trigger_output_short)

    private val showName = Opt.bool("name", true)

    /** Where media goes can change with no event (the output switcher): asked again this often while live. */
    private const val ASK_AGAIN_MS = 5_000L
    private const val STREAM = AudioManager.STREAM_MUSIC

    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val media by lazy { AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build() }

    @Volatile private var outs: List<Out> = emptyList()
    @Volatile private var routed: List<Out> = emptyList()
    /** The test hook's outputs, shown instead of the real ones; null: none staged. */
    @Volatile private var staged: List<Out>? = null
    private var live = false
    private var listening = false
    private var askedAt = 0L

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = changed()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = changed()
    }

    private fun am(): AudioManager? = Env.app.getSystemService(AudioManager::class.java)

    private fun out(d: AudioDeviceInfo) = Out(OutputRules.kind(d.type), d.productName?.toString().orEmpty())

    /** What Android lists and where it says media goes. Each call that Android refuses leaves its list empty, never the bar down. */
    private fun read() {
        val am = am()
        outs = runCatching { am?.getDevices(AudioManager.GET_DEVICES_OUTPUTS).orEmpty().filter { OutputRules.listed(it.type) }.map(::out).distinct() }
            .getOrDefault(emptyList())
        routed = runCatching { am?.getAudioDevicesForAttributes(media).orEmpty().filter { OutputRules.listed(it.type) }.map(::out) }
            .getOrDefault(emptyList())
    }

    /** From Android's callback, on the main thread: it must never throw. */
    private fun changed() {
        if (!live) return
        read()
        try { Ticker.refresh(type) } catch (e: Exception) { Log.w(Env.TAG, "output: ${e.javaClass.simpleName}") }
    }

    override fun onLive() {
        live = true
        read()
        val am = am()
        // Android answers the registration at once with every output there is, and then with each that comes or goes.
        if (am != null && !listening) listening = runCatching { am.registerAudioDeviceCallback(callback, main) }.isSuccess
    }

    override fun onIdle() {
        live = false
        if (listening) { runCatching { am()?.unregisterAudioDeviceCallback(callback) }; listening = false }
        outs = emptyList()
        routed = emptyList()
    }

    override fun sample(now: Long) {
        if (now - askedAt >= ASK_AGAIN_MS) { askedAt = now; read() }
    }

    /** The outputs to show and the one sound goes to. */
    private fun shown(): Pair<List<Out>, Out?> {
        val s = staged
        return if (s != null) s to OutputRules.current(s) else outs to OutputRules.current(outs, routed)
    }

    @StringRes private fun wordRes(kind: Kind): Int = when (kind) {
        Kind.SPEAKER -> R.string.output_kind_speaker
        Kind.WIRED -> R.string.output_kind_wired
        Kind.BLUETOOTH -> R.string.output_kind_bluetooth
        Kind.USB -> R.string.output_kind_usb
        Kind.DISPLAY -> R.string.output_kind_display
        Kind.OTHER -> R.string.output_kind_other
    }

    private fun word(kind: Kind): String = Env.str(wordRes(kind))

    override fun state(item: ItemConfig): ItemState {
        val (_, now) = shown()
        val name = now?.let { OutputRules.name(it, ::word) }
        val desc = if (name != null) Env.str(R.string.output_desc, name) else Env.str(R.string.output_none)
        return ItemState(icon = OutputRules.icon(now?.kind ?: Kind.SPEAKER), text = name?.takeIf { showName.of(item) },
            active = OutputRules.active(now), desc = desc, tooltip = desc, textLimit = OutputRules.NAME_MAX)
    }

    override val menu: @Composable (ItemConfig, MenuHost) -> Unit = { _, host ->
        rememberTick()
        val (list, now) = shown()
        val nowName = now?.let { OutputRules.name(it, ::word) }
        MenuCard(OutputRules.icon(now?.kind ?: Kind.SPEAKER), stringResource(R.string.item_output_title),
            if (nowName != null) stringResource(R.string.output_goes_to, nowName) else stringResource(R.string.output_none)) {
            for (out in list) {
                val inUse = out == now
                InfoRow(OutputRules.name(out, ::word), stringResource(if (inUse) R.string.output_in_use else wordRes(out.kind)),
                    if (inUse) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val am = am()
            if (am != null) {
                val max = runCatching { am.getStreamMaxVolume(STREAM) }.getOrDefault(0).coerceAtLeast(1)
                val volume = runCatching { am.getStreamVolume(STREAM) }.getOrDefault(0).coerceIn(0, max)
                val muted = runCatching { am.isStreamMute(STREAM) }.getOrDefault(false)
                if (list.isNotEmpty()) Spacer(Modifier.height(6.dp))
                InfoRow(stringResource(R.string.output_media_volume),
                    if (muted) stringResource(R.string.output_muted) else Fmt.percent((volume * 100f / max).roundToInt()))
                Meter(if (muted) 0f else volume.toFloat() / max)
            }
            Spacer(Modifier.height(6.dp))
            MenuNote(stringResource(R.string.output_note))
            MenuDivider()
            MenuEntry(Sym.TUNE, stringResource(R.string.output_sound_settings)) { host.close(); Env.launch(Intent(Settings.ACTION_SOUND_SETTINGS)) }
            MenuEntry(Sym.BLUETOOTH, stringResource(R.string.output_bluetooth_settings)) { host.close(); Env.launch(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
        }
    }

    override val options: @Composable (ItemConfig, (ItemConfig) -> Unit) -> Unit = { item, set ->
        // On is the default, and stored as no key at all, so that turning it back on leaves the item as the + made it.
        SwitchRow(stringResource(R.string.output_show_name), showName.of(item), help = stringResource(R.string.output_show_name_help)) {
            set(item.with(showName.key, if (it) null else "false"))
        }
    }

    override val stages = listOf("stage headphones", "stage usb", "stage two", "stage speaker")

    // Kinds only: a device's name is the user's own (a headset named after its owner).
    override fun debug(args: List<String>): String? = when (args.firstOrNull()) {
        null -> shown().let { (list, now) -> "staged=${staged != null} outputs=${list.map { it.kind }} current=${now?.kind}" }
        "stage" -> OutputRules.staged(args.getOrNull(1))?.let { staged = it; Ticker.refresh(type); "staged ${args[1]}" }
        "off" -> { staged = null; Ticker.refresh(type); "staging off" }
        else -> null
    }
}
