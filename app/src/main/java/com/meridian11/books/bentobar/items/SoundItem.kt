package com.meridian11.books.bentobar.items

import android.content.Intent
import android.media.AudioManager
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.data.Display
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.ui.MediaButtons
import com.meridian11.books.bentobar.ui.MenuCard
import com.meridian11.books.bentobar.ui.MenuDivider
import com.meridian11.books.bentobar.ui.MenuEntry
import com.meridian11.books.bentobar.ui.SectionLabel
import com.meridian11.books.bentobar.ui.SwitchRow
import com.meridian11.books.bentobar.ui.rememberTick
import com.meridian11.books.bentobar.util.Sym
import com.meridian11.books.bentobar.util.SymIcon
import kotlin.math.roundToInt

/**
 * The Sound item: the media volume as an icon and a number, with the volume and the media keys in
 * its menu. With "Slider in the bar" on, the number's place is a slider instead: the strip draws it
 * and takes the pointer (`bar/BarUi.kt`), the item says how full it is and sets what it is dragged
 * to ([onSlide]). Its arithmetic is in [SoundRules]. With the option off, nothing here differs from
 * the item before there was a slider.
 */
object SoundItem : ItemType("sound", R.string.item_sound_title, Sym.VOLUME_UP, R.string.item_sound_desc) {
    override val group = ItemGroup.TOOLS
    private const val STREAM = AudioManager.STREAM_MUSIC

    private fun am() = Env.app.getSystemService(AudioManager::class.java)

    /**
     * The level the slider draws while the sound is muted: Android reports 0 for a muted stream, so
     * this is the last level seen while it was on, or the last one the slider itself set (dragged
     * down to nothing, nothing is kept). 0 until a level was seen.
     */
    private var kept = 0f

    /** The lowest media volume there is; it never changes, so it is asked once. */
    private var lowest: Int? = null
    private fun min(am: AudioManager): Int = lowest ?: runCatching { am.getStreamMinVolume(STREAM) }.getOrDefault(0).also { lowest = it }

    /**
     * Whether the volume is fixed, so that nothing could be set. An output with a fixed volume can be
     * plugged in and taken away while the bar shows, so the answer is only good for a few seconds:
     * [sample] drops it, and the next [state] of an item with the slider on asks again.
     */
    private var fixedNow: Boolean? = null
    private var fixedAt = 0L
    private const val FIXED_GOOD_FOR_MS = 5_000L
    /** Debug builds: a fixed volume staged from adb, to see the item without its slider on a device that has none. */
    @Volatile private var fixedStaged: Boolean? = null
    private fun fixed(am: AudioManager): Boolean = fixedStaged ?: fixedNow ?: runCatching { am.isVolumeFixed }.getOrDefault(false).also { fixedNow = it }

    override fun onLive() { fixedNow = null }

    override fun sample(now: Long) {
        if (now - fixedAt >= FIXED_GOOD_FOR_MS) { fixedAt = now; fixedNow = null }
    }

    override fun state(item: ItemConfig): ItemState {
        val am = am() ?: return ItemState(icon = Sym.VOLUME_UP)
        val max = am.getStreamMaxVolume(STREAM).coerceAtLeast(1)
        val v = am.getStreamVolume(STREAM)
        val silenced = am.isStreamMute(STREAM)
        val muted = silenced || v == 0
        val pct = SoundRules.percent(v, max)
        val on = item.optBool("slider", false)
        // Only with the option on: a slider needs the lowest volume and whether there is anything to set.
        val slider = if (!on) null else {
            val min = min(am)
            // Only a muted stream hides its level. A volume of nothing that isn't muted is just that: nothing is kept, nothing filled.
            kept = SoundRules.kept(kept, v, min, max, silenced)
            SoundRules.slider(on = true, fixed = fixed(am), volume = v, min = min, max = max, muted = silenced, kept = kept)
        }
        return ItemState(icon = when { muted -> Sym.VOLUME_OFF; pct < 34 -> Sym.VOLUME_MUTE; pct < 67 -> Sym.VOLUME_DOWN; else -> Sym.VOLUME_UP },
            text = if (muted) Env.str(R.string.sound_muted) else com.meridian11.books.bentobar.util.Fmt.percent(pct), widthKey = if (muted) null else "pct",
            desc = if (muted) Env.str(R.string.sound_muted_desc) else Env.plural(R.plurals.sound_volume_desc, pct),
            slider = slider,
            // The number the slider stands for; shown as an icon only, the item has no slider and keeps its name as the tooltip.
            tooltip = if (slider == null || item.display == Display.ICON) null else if (muted) Env.str(R.string.sound_muted) else Env.str(R.string.sound_tooltip, pct))
    }

    override val usesWheel = true
    override fun onScroll(item: ItemConfig, steps: Int) {
        am()?.adjustStreamVolume(STREAM, if (steps > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0)
    }

    /**
     * The slider was clicked or is being dragged: [level] is on one of the volume's steps already. The
     * flags are 0, so Android shows no volume panel over the bar. This is called from the strip's
     * pointer handling, where nothing catches: Android refusing must not take the bar down.
     */
    override fun onSlide(item: ItemConfig, level: Float, done: Boolean) {
        val am = am() ?: return
        try {
            val min = min(am)
            val volume = SoundRules.volume(level, min, am.getStreamMaxVolume(STREAM).coerceAtLeast(1))
            // The level first: on current Android a level above nothing unmutes by itself, and unmuting first
            // would play the level from before the mute for a moment. Where it doesn't, unmute after.
            am.setStreamVolume(STREAM, volume, 0)
            if (volume > min && am.isStreamMute(STREAM)) am.adjustStreamVolume(STREAM, AudioManager.ADJUST_UNMUTE, 0)
            kept = level.coerceIn(0f, 1f)
        } catch (e: Exception) {
            Log.w(Env.TAG, "can't set the volume: ${e.javaClass.simpleName}")
        }
    }

    private fun mediaKey(code: Int) {
        val am = am() ?: return
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    override val menu: @Composable (ItemConfig, MenuHost) -> Unit = { _, host ->
        rememberTick()
        val am = am()
        if (am != null) {
            val max = am.getStreamMaxVolume(STREAM).coerceAtLeast(1)
            // Follows the volume keys and the scroll wheel while the menu is open, except mid-drag.
            val actual = am.getStreamVolume(STREAM).toFloat()
            var dragging by remember { mutableStateOf(false) }
            var v by remember { mutableFloatStateOf(actual) }
            // Also when the drag ends: a level Android refused (its hearing-safety limit) is then drawn as it is.
            LaunchedEffect(actual, dragging) { if (!dragging) v = actual }
            val muted = am.isStreamMute(STREAM)
            val volumeLabel = stringResource(R.string.sound_media_volume)
            MenuCard(Sym.VOLUME_UP, stringResource(R.string.item_sound_title),
                if (muted) stringResource(R.string.sound_muted) else stringResource(R.string.sound_media_volume_pct, SoundRules.percent(Math.round(v), max))) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalIconButton(onClick = {
                        am.adjustStreamVolume(STREAM, AudioManager.ADJUST_TOGGLE_MUTE, 0)
                    }) { SymIcon(if (muted) Sym.VOLUME_OFF else Sym.VOLUME_UP, size = 20.sp, contentDescription = stringResource(if (muted) R.string.sound_unmute else R.string.sound_mute)) }
                    Spacer(Modifier.width(8.dp))
                    Slider(value = v, onValueChange = {
                        dragging = true
                        // A stop can come in a hair under its number (6.9999995 for 7): the nearest volume, not the one below it.
                        val step = it.roundToInt()
                        v = step.toFloat()
                        am.setStreamVolume(STREAM, step, 0)
                    }, onValueChangeFinished = { dragging = false }, valueRange = 0f..max.toFloat(), steps = (max - 1).coerceAtLeast(0),
                        modifier = Modifier.weight(1f).semantics { contentDescription = volumeLabel })
                }
                SectionLabel(stringResource(R.string.sound_media))
                MediaButtons(playing = am.isMusicActive, onPrevious = { mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS) },
                    onPlayPause = { mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) }, onNext = { mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT) })
                MenuDivider()
                MenuEntry(Sym.TUNE, stringResource(R.string.sound_all_volumes)) { host.close(); Env.launch(Intent(Settings.Panel.ACTION_VOLUME)) }
                MenuEntry(Sym.SETTINGS, stringResource(R.string.sound_settings)) { host.close(); Env.launch(Intent(Settings.ACTION_SOUND_SETTINGS)) }
            }
        }
    }

    override val options: @Composable (ItemConfig, (ItemConfig) -> Unit) -> Unit = { item, set ->
        rememberTick() // the help follows the output while settings are open
        val fixed = am()?.let { fixed(it) } == true
        // The switch stays usable on a fixed volume: it applies as soon as there is something to set.
        SwitchRow(stringResource(R.string.sound_slider), item.optBool("slider", false),
            help = stringResource(if (fixed) R.string.sound_slider_fixed else R.string.sound_slider_help)) { set(item.with("slider", it.toString())) }
    }

    override fun debug(args: List<String>): String? = when (args.firstOrNull()) {
        // `sound fixed on|off` stages a fixed (or a settable) volume; `sound off`, or `sound fixed` with no word, ends the staging.
        "fixed" -> {
            fixedStaged = when (args.getOrNull(1)) { "on" -> true; "off" -> false; null -> null; else -> return null }
            Ticker.refresh()
            "fixed volume staged=$fixedStaged"
        }
        "off" -> { fixedStaged = null; Ticker.refresh(); "fixed volume staging off" }
        else -> null
    }
}
