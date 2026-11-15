package com.meridian11.books.bentobar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** How wide a page's one column is at most (Look, Setup, About). */
internal val PAGE_WIDTH = 760.dp

/** The share of a card's width a setting's name and help take; its choices begin where they end. */
private const val NAME_SHARE = 0.56f

/**
 * A page of settings: one column in the window's middle, [width] wide at most, that scrolls. The Bar page has its own
 * arrangement; these are the other three.
 */
@Composable
internal fun PageColumn(modifier: Modifier = Modifier, width: Dp = PAGE_WIDTH, content: @Composable ColumnScope.() -> Unit) {
    val margin = LocalWindowLayout.current.marginDp.dp
    Box(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = margin, end = margin, bottom = Space.xl),
        contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = width).fillMaxWidth(), content = content)
    }
}

/**
 * A section's title over its card, with what the section is for under it ([sub]) and one word at the row's end
 * ([end]: whether the service is on).
 */
@Composable
internal fun PageSection(title: String, sub: String? = null, end: (@Composable () -> Unit)? = null) {
    val c = Bento.colors
    Column(Modifier.fillMaxWidth().padding(start = Space.xs, top = 20.dp, end = Space.xs, bottom = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f).semantics { heading() }, style = Bento.type.title, color = c.ink)
            if (end != null) {
                Spacer(Modifier.width(Space.m))
                end()
            }
        }
        if (sub != null) Text(sub, Modifier.padding(top = 2.dp), style = Bento.type.small, color = c.ink2)
    }
}

/** A card on a page: the panel's ground with the large corners. Its rows are parted by a [RowLine]. */
@Composable
internal fun PageCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().background(Bento.colors.panel, RoundedCornerShape(Radius.large)).padding(horizontal = 20.dp, vertical = Space.xs), content = content)
}

/** The hairline between two rows of a card. */
@Composable
internal fun RowLine() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Bento.colors.line))
}

/** A setting's name with its help under it, as a row's first part. */
@Composable
private fun SettingName(name: String, help: String?, modifier: Modifier = Modifier, enabled: Boolean = true, warn: Boolean = false) {
    val c = Bento.colors
    Column(modifier) {
        Text(name, style = Bento.type.label, color = if (enabled) c.ink else c.ink3)
        if (help != null) Text(help, Modifier.padding(top = 2.dp).said(arrowInWords(help)), style = Bento.type.help, color = if (warn) c.warnInk else c.ink3)
    }
}

/**
 * One setting in a card: its [name] and [help] at the start ([helpWarn]: in the warning ink, for what went wrong),
 * [control] at the end, as wide as it is (buttons, a word).
 * In a narrow window the control goes under the name.
 */
@Composable
internal fun SettingRow(name: String, modifier: Modifier = Modifier, help: String? = null, helpWarn: Boolean = false, control: @Composable RowScope.() -> Unit) {
    if (LocalWindowLayout.current.kind == WindowClass.NARROW) Column(modifier.fillMaxWidth().padding(vertical = Space.m)) {
        SettingName(name, help, warn = helpWarn)
        // Buttons that don't fit side by side go on the next line.
        FlowRow(Modifier.padding(top = Space.s), verticalArrangement = Arrangement.spacedBy(Space.s), itemVerticalAlignment = Alignment.CenterVertically) { control() }
    } else Row(modifier.fillMaxWidth().heightIn(min = 60.dp).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        SettingName(name, help, Modifier.weight(1f), warn = helpWarn)
        Spacer(Modifier.width(Space.l))
        control()
    }
}

/**
 * A setting with something wide beside it (a list of radio buttons, a slider): the name takes the card's first part and
 * [control] the rest, from the same line in every row. In a narrow window the control goes under the name.
 */
@Composable
internal fun WideSettingRow(name: String, modifier: Modifier = Modifier, help: String? = null, top: Boolean = true, control: @Composable () -> Unit) {
    if (LocalWindowLayout.current.kind == WindowClass.NARROW) Column(modifier.fillMaxWidth().padding(vertical = Space.m)) {
        SettingName(name, help)
        Box(Modifier.fillMaxWidth().padding(top = Space.xs)) { control() }
    } else Row(modifier.fillMaxWidth().heightIn(min = 60.dp).padding(vertical = Space.m), verticalAlignment = if (top) Alignment.Top else Alignment.CenterVertically) {
        // Beside a list, the name stands level with the list's first line.
        SettingName(name, help, Modifier.weight(NAME_SHARE).padding(top = if (top) Space.s else 0.dp, end = Space.xl))
        Box(Modifier.weight(1f - NAME_SHARE)) { control() }
    }
}

/** A choice of one, every value under the other as a radio button, beside the setting's name. */
@Composable
internal fun <T> ChoiceSetting(name: String, choices: List<Pair<T, String>>, selected: T?, help: String? = null, onSelect: (T) -> Unit) {
    WideSettingRow(name, help = help) {
        RadioList(choices, selected, onSelect, Modifier.fillMaxWidth().semantics { contentDescription = name })
    }
}

/** A switch with its name and help: the whole row is the switch, so a click on the words toggles it and a screen reader hears its name. */
@Composable
internal fun SwitchSetting(name: String, checked: Boolean, help: String? = null, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp)
        .states(interaction, radius = Radius.small, inset = -Space.s)
        .toggleable(checked, interaction, indication = null, enabled = enabled, role = Role.Switch, onValueChange = onChange)
        .padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        SettingName(name, help, Modifier.weight(1f), enabled)
        Spacer(Modifier.width(Space.l))
        SwitchMark(checked, enabled = enabled)
    }
}
