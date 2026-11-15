package com.meridian11.books.bentobar.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.foundation.interaction.MutableInteractionSource
import android.content.pm.PackageManager
import android.content.res.Resources
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.data.BarConfig
import com.meridian11.books.bentobar.data.Display
import com.meridian11.books.bentobar.data.HiddenMode
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.data.Part
import com.meridian11.books.bentobar.data.Position
import com.meridian11.books.bentobar.data.Standing
import com.meridian11.books.bentobar.data.Step
import com.meridian11.books.bentobar.data.Store
import com.meridian11.books.bentobar.data.Uses
import com.meridian11.books.bentobar.data.canGoBelow
import com.meridian11.books.bentobar.data.listAreas
import com.meridian11.books.bentobar.data.partOf
import com.meridian11.books.bentobar.data.parts
import com.meridian11.books.bentobar.data.standing
import com.meridian11.books.bentobar.data.stepped
import com.meridian11.books.bentobar.items.Access
import com.meridian11.books.bentobar.items.Accesses
import com.meridian11.books.bentobar.items.ItemState
import com.meridian11.books.bentobar.items.ItemType
import com.meridian11.books.bentobar.items.Items
import com.meridian11.books.bentobar.items.MediaAccess
import com.meridian11.books.bentobar.items.Ticker
import com.meridian11.books.bentobar.items.Trigger
import com.meridian11.books.bentobar.items.Trouble
import com.meridian11.books.bentobar.util.Now
import com.meridian11.books.bentobar.util.Sym
import com.meridian11.books.bentobar.util.SymIcon

/**
 * What [item]'s service has not answered, read again with the tick while it is drawn (a try that came back, Try again's
 * wait running out). Only a type that asks a service is read at all.
 */
@Composable
internal fun troubleOf(item: ItemConfig, type: ItemType?): Trouble? {
    if (type?.online == null) return null
    rememberTick()
    return type.trouble(item, Now.wall())
}

/** The text resource that makes a place of a number: 1st, 2nd, 3rd, 4th, and 11th to 13th. */
fun ordinalRes(n: Int): Int = when {
    n % 100 in 11..13 -> R.string.order_th
    n % 10 == 1 -> R.string.order_st
    n % 10 == 2 -> R.string.order_nd
    n % 10 == 3 -> R.string.order_rd
    else -> R.string.order_th
}

fun areaRes(area: Position): Int = when (area) {
    Position.LEFT -> R.string.areas_left
    Position.CENTER -> R.string.areas_middle
    Position.RIGHT -> R.string.areas_right
}

/** What an item is called in the list, in Options and when it is removed: a type this version doesn't know has no name here. */
fun itemName(res: Resources, item: ItemConfig): String = Items.of(item.type)?.let { res.getString(it.titleRes) } ?: res.getString(R.string.row_from_newer)

/**
 * Where [item] stands, as Order says it and as a moved row is read out: "5th of 7 in the bar", "2nd of 2 when active",
 * with [several] lanes on screen "2nd of 2 in Left"; Next meeting, which never moves, "Always first in Right". Null for an
 * item that is not in the bar.
 */
fun orderWords(res: Resources, cfg: BarConfig, item: ItemConfig, several: Boolean = cfg.listAreas().size > 1): String? {
    val part = cfg.partOf(item) ?: return null
    val area = res.getString(areaRes(item.area))
    if (item.type == Store.PINNED_LEFT) return res.getString(R.string.order_pinned, area)
    val rows = cfg.parts(item.area)[part]
    val at = rows.indexOfFirst { it.id == item.id } + 1
    val nth = res.getString(ordinalRes(at), at)
    return when {
        part == Part.LOWER -> res.getString(if (cfg.hiddenMode == HiddenMode.SHOW_ALL) R.string.order_when_active else R.string.order_hidden, nth, rows.size)
        several -> res.getString(R.string.order_in_area, nth, rows.size, area)
        else -> res.getString(R.string.order_in_bar, nth, rows.size)
    }
}

/**
 * The selected item's options, in the panel: its name, what it waits for, then Area (first, as a three-way choice with
 * its keys, while [several] lanes are on screen; else last, as a menu), Visibility with its rule, Order, Show as and the type's own options;
 * Duplicate and Remove from the bar (the red button) at the card's foot. Every command is [edit]'s, the same one the keys and a row's
 * screen-reader actions use.
 */
@Composable
internal fun ItemOptions(item: ItemConfig, cfg: BarConfig, state: ItemState?, edit: BarEdit, several: Boolean, modifier: Modifier = Modifier) {
    val c = Bento.colors
    val res = androidx.compose.ui.platform.LocalResources.current
    val type = Items.of(item.type)
    val scroll = rememberScrollState()
    Column(modifier.fillMaxSize().testTag("options")) {
        Column(Modifier.weight(1f).scrollThumb(scroll, c.thumb).verticalScroll(scroll).padding(start = 20.dp, top = 20.dp, end = 20.dp, bottom = Space.s)) {
            Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { heading() }.testTag("options-head"), verticalAlignment = Alignment.CenterVertically) {
                SymIcon(type?.icon ?: Sym.UPDATE, size = 20.sp, color = c.ink)
                Spacer(Modifier.width(10.dp))
                Text(itemName(res, item), Modifier.weight(1f, fill = false), style = Bento.type.title, color = c.ink, maxLines = lines(), overflow = TextOverflow.Ellipsis)
                state?.text?.takeIf { it.isNotBlank() && type != null }?.let {
                    Spacer(Modifier.width(Space.s))
                    Text(it, style = Bento.type.small.tabular, color = c.ink2, maxLines = lines(), overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(Space.s))
            if (type != null) Needs(item, type)
            if (several) OptionRow(stringResource(R.string.areas_label), keys = AreaKeys.caps(res)) {
                val areas = areaChoices()
                Segment(areas.map { SegmentPart(it.second) }, areas.indexOfFirst { it.first == item.area }, { edit.area(item.id, areas[it].first) },
                    Modifier.weight(1f).testTag("area"), sides = true)
            }
            Visibility(item, cfg, type, edit)
            OptionRow(stringResource(R.string.order_label),
                keys = if (item.type == Store.PINNED_LEFT) emptyList() else BarKeys.orderCaps(res)) {
                Text(orderWords(res, cfg, item, several).orEmpty(), Modifier.weight(1f).testTag("order"), style = Bento.type.small, color = c.ink)
                if (item.type != Store.PINNED_LEFT) {
                    Spacer(Modifier.width(Space.s))
                    BentoIconButton(Sym.ARROW_UPWARD, stringResource(R.string.row_move_up), { edit.step(item.id, down = false) },
                        enabled = cfg.stepped(item.id, down = false) is Step.Moved)
                    Spacer(Modifier.width(Space.xs))
                    BentoIconButton(Sym.ARROW_DOWNWARD, stringResource(R.string.row_move_down), { edit.step(item.id, down = true) },
                        enabled = cfg.stepped(item.id, down = true) is Step.Moved)
                }
            }
            if (type == null) {
                Hairline()
                Text(stringResource(R.string.detail_unknown_help), Modifier.padding(vertical = Space.s), style = Bento.type.small, color = c.ink2)
            } else {
                if (!type.iconOnly || type.options != null) Hairline()
                if (!type.iconOnly) OptionRow(stringResource(R.string.display_show_as)) {
                    val looks = listOf(Display.ICON_AND_TEXT to R.string.display_icon_and_text, Display.TEXT to R.string.display_text, Display.ICON to R.string.display_icon)
                    Segment(looks.map { SegmentPart(stringResource(it.second)) }, looks.indexOfFirst { it.first == item.display }, { i ->
                        Store.updateItem(item.id) { it.copy(display = looks[i].first) }
                        Ticker.refresh()
                    }, Modifier.weight(1f).testTag("show-as"), equal = false)
                }
                // The type's own settings follow a hairline, on the panel's own controls: no heading in capitals over them.
                type.options?.let { opts ->
                    if (!type.iconOnly) Hairline()
                    opts(item) { changed -> Store.updateItem(item.id) { changed }; Ticker.refresh() }
                }
            }
            if (!several) {
                Hairline()
                OptionRow(stringResource(R.string.areas_label)) {
                    MenuButton(areaChoices(), item.area, { edit.area(item.id, it) }, Modifier.testTag("area"))
                }
            }
        }
        // The foot is pinned and has its edge: the options scroll under a line, so a row is never cut off by nothing.
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.line).testTag("options-edge"))
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = Space.s, top = Space.m, bottom = Space.l), verticalAlignment = Alignment.CenterVertically) {
            if (type != null) {
                BentoButton(stringResource(R.string.detail_duplicate), { edit.duplicate(item.id) })
                Spacer(Modifier.width(Space.s))
            }
            // The red button, with a trash: the one command here that takes something away. Its keys (Delete, Backspace) are in the
            // system's list of shortcuts and not beside it.
            BentoButton(stringResource(R.string.detail_remove), { edit.remove(item.id) }, kind = ButtonKind.DANGER, icon = Sym.DELETE)
        }
    }
}

/**
 * What the item waits for before it can say anything current: one notice each, with the one button that grants it or
 * asks again.
 */
@Composable
private fun Needs(item: ItemConfig, type: ItemType) {
    val context = LocalContext.current
    val setup by Setup.state.collectAsState()
    troubleOf(item, type)?.let { trouble ->
        NoticeLine(stringResource(trouble.text), Modifier.padding(bottom = Space.s).testTag("trouble")) {
            BentoButton(stringResource(R.string.common_try_again), { type.tryAgain(item); Ticker.refresh() }, kind = ButtonKind.WARN, enabled = trouble.ready)
        }
    }
    if (android.Manifest.permission.READ_CALENDAR in type.permissions && !setup.calendar) {
        NoticeLine(stringResource(R.string.detail_needs_calendar), Modifier.padding(bottom = Space.s)) {
            BentoButton(stringResource(R.string.common_allow), {
                Store.update { it.copy(turnedOff = it.turnedOff - Uses.CALENDAR) }
                if (context.checkSelfPermission(android.Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED)
                    (context as? android.app.Activity)?.requestPermissions(arrayOf(android.Manifest.permission.READ_CALENDAR), 2)
                Setup.refresh(context)
                Ticker.refresh()
            }, kind = ButtonKind.WARN)
        }
    }
    if (type.notificationAccess && !setup.mediaAccess) {
        val text = stringResource(R.string.media_access_explain) + if (setup.sideloaded) "\n" + stringResource(R.string.media_access_restricted) else ""
        NoticeLine(text, Modifier.padding(bottom = Space.s), stacked = true) {
            BentoButton(stringResource(R.string.media_access_allow), { MediaAccess.openSettings(context) }, kind = ButtonKind.WARN)
        }
    }
    // The newer items' accesses: one line each that Android has not given, with the button that leads to it.
    for (access in type.needs.distinct()) if (access !in setup.access) {
        val sideGuarded = access == Access.NOTICES && setup.sideloaded
        val text = stringResource(Accesses.explain(access)) + if (sideGuarded) "\n" + stringResource(R.string.media_access_restricted) else ""
        NoticeLine(text, Modifier.padding(bottom = Space.s).testTag("needs-${access.name.lowercase()}"), stacked = true) {
            BentoButton(stringResource(Accesses.allow(access)), { Accesses.open(context, access) }, kind = ButtonKind.WARN)
        }
    }
}

/**
 * Visibility: one choice of two, and under it only what the chosen part needs. By default "Always" or "Only when active",
 * with the type's rule as a sentence and its slider; a type with no rule has the second part dimmed and the reason under
 * it. With ‹, "Always" or "Behind ‹", and behind it a switch for a type that can pop out by its rule.
 */
@Composable
private fun Visibility(item: ItemConfig, cfg: BarConfig, type: ItemType?, edit: BarEdit) {
    val c = Bento.colors
    val chevron = cfg.hiddenMode != HiddenMode.SHOW_ALL
    val standing = cfg.standing(item)
    val behind = stringResource(R.string.visibility_behind)
    val second = if (chevron) SegmentPart(behind, enabled = cfg.canGoBelow(item, type), spoken = arrowInWords(behind))
        else SegmentPart(stringResource(R.string.visibility_when_active), enabled = cfg.canGoBelow(item, type))
    OptionRow(stringResource(R.string.visibility_label)) {
        Segment(listOf(SegmentPart(stringResource(R.string.visibility_always)), second), if (standing == Standing.ALWAYS) 0 else 1,
            { if (it == 0) edit.always(item.id) else edit.below(item.id) }, Modifier.weight(1f).testTag("visibility"), equal = false)
    }
    val trigger = type?.trigger
    Column(Modifier.fillMaxWidth().padding(start = OPTION_LABEL.ofText())) {
        when {
            type == null -> Unit
            !chevron && !type.canBeActive ->
                Text(stringResource(R.string.visibility_no_rule, stringResource(type.titleRes)), Modifier.padding(bottom = Space.s), style = Bento.type.help, color = c.ink2)
            !chevron && standing == Standing.WHEN_ACTIVE && trigger != null -> Rule(item, trigger, R.string.rule_shows, switch = false, edit)
            chevron && standing == Standing.BEHIND && type.canBeActive && trigger != null -> Rule(item, trigger, R.string.rule_pops_out, switch = true, edit)
        }
    }
}

/**
 * A type's rule in words with its value ("Shows while a device's battery is below 20%"), and the slider that sets the
 * value where the rule has one. Behind ‹ the sentence is a switch's label, and the slider is there while it is on.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun Rule(item: ItemConfig, trigger: Trigger, words: Int, switch: Boolean, edit: BarEdit) {
    val c = Bento.colors
    val threshold = trigger.threshold
    val stored = threshold?.shown(item) ?: 0
    // The slider's value while it is dragged; the layout is written when it is let go.
    var live by remember(item.id, stored) { mutableIntStateOf(stored) }
    val sentence = stringResource(words, trigger.sentence(item, live))
    // A sentence over two lines breaks into lines of like length, not a full line and a word.
    val balanced = Bento.type.small.copy(lineBreak = LineBreak.Heading)
    if (switch) {
        Row(Modifier.fillMaxWidth().heightIn(min = Target.control)
            .toggleable(item.whenActive, role = Role.Switch) { edit.popsOut(item.id, it) }.testTag("pops-out"),
            verticalAlignment = Alignment.CenterVertically) {
            Text(sentence, Modifier.weight(1f).padding(end = Space.s), style = balanced, color = c.ink)
            SwitchMark(item.whenActive)
        }
    } else {
        Text(sentence, Modifier.fillMaxWidth().testTag("rule"), style = balanced, color = c.ink)
    }
    if (threshold != null && (!switch || item.whenActive)) {
        val r = threshold.range
        val interaction = remember { MutableInteractionSource() }
        val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Slider(
                value = live.toFloat(), onValueChange = { live = threshold.snap(it) },
                onValueChangeFinished = {
                    if (live != threshold.of(item)) {
                        Store.updateItem(item.id) { it.with(threshold.key, live.toString()) }
                        Ticker.refresh()
                    }
                },
                steps = ((r.last - r.first) / threshold.step - 1).coerceAtLeast(0),
                valueRange = r.first.toFloat()..r.last.toFloat(),
                interactionSource = interaction,
                // A thin line and a round thumb: the value is in the sentence and at the line's end, not in the track.
                thumb = { Box(Modifier.size(RULE_THUMB).states(interaction, radius = RULE_THUMB / 2, ground = false).background(c.accent, CircleShape)) },
                track = { state ->
                    val span = state.valueRange.endInclusive - state.valueRange.start
                    val done = if (span > 0f) ((state.value - state.valueRange.start) / span).coerceIn(0f, 1f) else 0f
                    Box(Modifier.fillMaxWidth().height(4.dp).drawBehind {
                        val round = CornerRadius(size.height / 2)
                        val w = size.width * done
                        drawRoundRect(c.edge, size = size, cornerRadius = round)
                        drawRoundRect(c.accent, Offset(if (rtl) size.width - w else 0f, 0f), Size(w, size.height), round)
                    })
                },
                modifier = Modifier.weight(1f).semantics { contentDescription = sentence },
            )
            Spacer(Modifier.width(Space.m))
            // As wide as its widest value, so the line keeps its length while the thumb is carried. Spoken with the sentence.
            Box(Modifier.clearAndSetSemantics { }, contentAlignment = Alignment.CenterEnd) {
                Text(listOf(threshold.format(r.first), threshold.format(r.last)).maxBy { it.length }, style = Bento.type.small.tabular, color = Color.Transparent, maxLines = 1)
                Text(threshold.format(live), style = Bento.type.small.tabular, color = c.ink, maxLines = 1)
            }
        }
    }
    Spacer(Modifier.height(Space.xs))
}

private val RULE_THUMB = 16.dp
