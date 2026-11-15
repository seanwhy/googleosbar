package com.meridian11.books.bentobar.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.bar.SliderMath
import com.meridian11.books.bentobar.data.ChipMode
import com.meridian11.books.bentobar.data.ColorMode
import com.meridian11.books.bentobar.data.HiddenMode
import com.meridian11.books.bentobar.data.LayoutRead
import com.meridian11.books.bentobar.data.Pill
import com.meridian11.books.bentobar.data.Position
import com.meridian11.books.bentobar.data.SPACING_DP
import com.meridian11.books.bentobar.data.Store
import com.meridian11.books.bentobar.data.TextSize
import com.meridian11.books.bentobar.data.oneArea
import com.meridian11.books.bentobar.data.putAllIn
import com.meridian11.books.bentobar.data.spacingDp
import com.meridian11.books.bentobar.util.Sym
import com.meridian11.books.bentobar.util.SymIcon

/**
 * Look: how the whole bar looks and behaves, one column of cards. Every choice of one is a list of radio buttons beside its
 * name, so all its values are read at once.
 */
@Composable
fun LookPage() {
    val cfg by Store.config.collectAsState()
    PageColumn(Modifier.testTag("look")) {
        PageSection(stringResource(R.string.look_items))
        PageCard {
            // A command more than a setting: a press puts every item in that area, and one is chosen only while they all are in it
            // (none, a null here, while the items are in several). The help says what a press does: with one area in use, where
            // single items are placed; with several, that they all move.
            val one = cfg.oneArea()
            ChoiceSetting<Position?>(stringResource(R.string.look_position), areaChoices(), one,
                help = stringResource(if (one == null) R.string.areas_look_several else R.string.look_position_help)) { area ->
                if (area != null) Undo.change(allTo = area) { it.putAllIn(area) }
            }
            // The press moved things the page doesn't show: it says so, with the way back, while that is the window's last change.
            val kept by Undo.last.collectAsState()
            val over by Undo.over.collectAsState()
            Undo.allTo(Undo.noted(kept, over), cfg)?.let { MovedAll(it) }
            RowLine()
            Spacing(cfg.spacingDp) { v -> Store.update { it.copy(spacing = v) } }
            RowLine()
            ChoiceSetting(stringResource(R.string.look_text_size), listOf(TextSize.SMALL to stringResource(R.string.look_text_small),
                TextSize.DEFAULT to stringResource(R.string.option_like_system), TextSize.LARGE to stringResource(R.string.look_text_large)), cfg.textSize) { v ->
                Store.update { it.copy(textSize = v) }
            }
            RowLine()
            ChoiceSetting(stringResource(R.string.look_color), listOf(ColorMode.AUTO to stringResource(R.string.look_color_auto),
                ColorMode.LIGHT to stringResource(R.string.look_color_light), ColorMode.DARK to stringResource(R.string.look_color_dark)), cfg.color,
                help = stringResource(R.string.look_color_help)) { v -> Store.update { it.copy(color = v) } }
            RowLine()
            ChoiceSetting(stringResource(R.string.look_background), listOf(Pill.NONE to stringResource(R.string.look_pill_none),
                Pill.SUBTLE to stringResource(R.string.look_pill_subtle), Pill.SOLID to stringResource(R.string.look_pill_solid)), cfg.pill) { v ->
                Store.update { it.copy(pill = v) }
            }
        }

        PageSection(stringResource(R.string.barmenu_hidden_items))
        PageCard {
            ChoiceSetting(stringResource(R.string.look_hidden_mode), listOf(HiddenMode.SHOW_ALL to stringResource(R.string.look_hidden_show_all),
                HiddenMode.CLICK to stringResource(R.string.look_hidden_click), HiddenMode.HOVER to stringResource(R.string.look_hidden_hover)),
                cfg.hiddenMode, help = stringResource(R.string.look_hidden_mode_help)) { v -> Store.update { it.copy(hiddenMode = v) } }
            // Folding back only applies when hidden items wait behind ‹.
            if (cfg.hiddenMode != HiddenMode.SHOW_ALL) {
                RowLine()
                ChoiceSetting(stringResource(R.string.look_collapse), listOf(0 to stringResource(R.string.look_collapse_never),
                    5 to pluralStringResource(R.plurals.look_collapse_after, 5, 5), 10 to pluralStringResource(R.plurals.look_collapse_after, 10, 10),
                    30 to pluralStringResource(R.plurals.look_collapse_after, 30, 30)),
                    if (cfg.autoCollapseSec in listOf(0, 5, 10, 30)) cfg.autoCollapseSec else 0) { v -> Store.update { it.copy(autoCollapseSec = v) } }
            }
        }

        PageSection(stringResource(R.string.look_popups))
        PageCard {
            SwitchSetting(stringResource(R.string.look_hover_switch), cfg.hoverSwitchesPopups, help = stringResource(R.string.look_hover_switch_help)) { on ->
                Store.update { it.copy(hoverSwitchesPopups = on) }
            }
            RowLine()
            SwitchSetting(stringResource(R.string.look_no_animations), cfg.noAnimations, help = stringResource(R.string.look_no_animations_help)) { on ->
                Store.update { it.copy(noAnimations = on) }
            }
            RowLine()
            SwitchSetting(stringResource(R.string.look_system_tint), cfg.systemTint, help = stringResource(R.string.look_system_tint_help)) { on ->
                Store.update { it.copy(systemTint = on) }
            }
        }

        PageSection(stringResource(R.string.look_presenting_live))
        PageCard {
            SwitchSetting(stringResource(R.string.look_presenting), cfg.presenting, help = stringResource(R.string.look_presenting_help)) { on ->
                Store.update { it.copy(presenting = on) }
            }
            RowLine()
            ChoiceSetting(stringResource(R.string.look_chip), listOf(ChipMode.OFF to stringResource(R.string.common_off),
                ChipMode.FALLBACK to stringResource(R.string.look_chip_fallback), ChipMode.ALWAYS to stringResource(R.string.look_chip_always)),
                cfg.chipMode, help = stringResource(R.string.look_chip_help)) { v ->
                Store.update { it.copy(chipMode = v) }
            }
        }

        PageSection(stringResource(R.string.look_backup))
        PageCard {
            Backup()
            val aside by Store.setAside.collectAsState()
            aside?.let { why ->
                RowLine()
                SetAsideOffer(why)
            }
        }
    }
}

@Composable
private fun Spacing(value: Int, onChange: (Int) -> Unit) {
    val res = LocalResources.current
    val name = stringResource(R.string.look_spacing)
    var v by remember { mutableFloatStateOf(value.toFloat()) }
    LaunchedEffect(value) { v = value.toFloat() }
    WideSettingRow(name, top = false) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // Spoken with its unit ("12 dp"); the figure alone stands at the row's end.
            BentoSlider(v, { v = it }, { onChange(SliderMath.stop(v)) }, SPACING_DP, name, res.getString(R.string.common_dp, SliderMath.stop(v)), Modifier.weight(1f))
            Spacer(Modifier.width(Space.l))
            SliderValue(SliderMath.stop(v).toString(), SPACING_DP.last.toString())
        }
    }
}

/** What Copy and Paste said last, in the row itself: [good] ones (copied, restored) with the tick. */
private data class Said(val text: Int, val good: Boolean)

@Composable
private fun SaidLine(said: Said?) {
    val c = Bento.colors
    if (said != null) Row(Modifier.padding(end = Space.m).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically) {
        if (said.good) {
            SymIcon(Sym.CHECK, size = 16.sp, color = c.accent)
            Spacer(Modifier.width(Space.xs))
        }
        Text(stringResource(said.text), style = Bento.type.small, color = if (said.good) c.accent else c.warnInk)
    }
}

/**
 * What "Where BentoBar sits" just did, under it, with the Undo that takes it back (the window's one [Undo], so Ctrl+Z here and
 * on the Bar page is the same step): the Bar page's slip for a closed lane, on Look. Like it, the line stands a
 * note's time ([NoteTime]) and Ctrl+Z takes the step back after it too.
 */
@Composable
private fun MovedAll(area: Position) {
    val c = Bento.colors
    val hold = remember { NoteHold() }
    NoteTime(hold)
    Row(Modifier.fillMaxWidth().padding(bottom = Space.m).heightIn(min = Target.row).background(c.sunk, RoundedCornerShape(Radius.medium))
        .holdsNote(hold).padding(start = 14.dp, end = Space.xs).testTag("look-moved"), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.areas_moved_all, stringResource(areaRes(area))), Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
            style = Bento.type.small, color = c.ink2)
        BentoButton(stringResource(R.string.bar_undo), { Undo.undo() }, Modifier.deafAtFirst(), kind = ButtonKind.TEXT)
    }
}

/** Copy settings and Paste settings: the layout as text on the clipboard, and back. */
@Composable
private fun Backup() {
    val context = LocalContext.current
    val res = LocalResources.current
    var said by remember { mutableStateOf<Said?>(null) }
    val paste = {
        // Its own text only (a layout always is): a copied file isn't opened on the main thread to find out.
        val text = context.getSystemService(ClipboardManager::class.java)?.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
        said = when (Store.import(text)) {
            is LayoutRead.Ok -> Said(R.string.look_restored, true)
            is LayoutRead.Newer -> Said(R.string.look_paste_newer, false)
            LayoutRead.NotALayout -> Said(R.string.look_not_settings, false)
        }
    }
    // What a paste could not use takes a line of its own: it is a sentence, and the buttons keep their place.
    val long = said?.good == false
    SettingRow(stringResource(R.string.look_your_settings), help = if (long) stringResource(said!!.text) else null, helpWarn = long) {
        if (!long) SaidLine(said)
        BentoButton(stringResource(R.string.look_copy), {
            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(res.getString(R.string.look_clip_label), Store.export()))
            said = Said(R.string.look_copied, true)
        }, icon = Sym.CONTENT_COPY)
        Spacer(Modifier.width(Space.s))
        BentoButton(stringResource(R.string.look_paste), paste)
    }
}

/**
 * A layout that could not be read was kept instead of being lost, and this is where it is offered, until it is put back or
 * discarded. Putting it back is only ever the user's press, never something done by itself over their edits.
 */
@Composable
private fun SetAsideOffer(why: Store.SetAside) {
    val context = LocalContext.current
    val res = LocalResources.current
    var copied by rememberSaveable { mutableStateOf(false) }
    SettingRow(stringResource(R.string.look_kept), help = stringResource(when (why) {
        Store.SetAside.READABLE_NOW -> R.string.look_kept_readable
        Store.SetAside.FROM_NEWER_VERSION -> R.string.look_kept_newer
        Store.SetAside.UNREADABLE -> R.string.look_kept_unreadable
    }), modifier = Modifier.testTag("kept")) {
        if (copied) SaidLine(Said(R.string.look_copied, true))
        if (why == Store.SetAside.READABLE_NOW) BentoButton(stringResource(R.string.look_kept_restore), { Store.restoreSetAside() })
        // Only an unreadable layout needs this: a readable one can be put back and copied as settings, a newer one waits for an update.
        if (why == Store.SetAside.UNREADABLE) BentoButton(stringResource(R.string.look_kept_copy), {
            Store.setAsideText()?.let { text ->
                context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(res.getString(R.string.look_clip_label), text))
                copied = true
            }
        }, kind = ButtonKind.TEXT)
        BentoButton(stringResource(R.string.look_kept_discard), { Store.discardSetAside() }, kind = ButtonKind.DANGER)
    }
}
