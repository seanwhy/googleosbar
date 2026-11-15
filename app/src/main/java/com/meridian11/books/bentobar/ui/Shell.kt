package com.meridian11.books.bentobar.ui

import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.bar.BarStatus
import kotlinx.coroutines.launch

/** The settings window's three layouts. */
enum class WindowClass {
    /** Below 760: the header in two rows, one thing at a time under the preview. */
    NARROW,
    /** 760 to 1099: one thing at a time at full width under the preview. */
    MID,
    /** From 1100: the bar's list and one panel beside it. */
    WIDE,
}

/**
 * What a window's width makes of the settings window: one model at every width, the bar's list and one panel chosen
 * by the switch. There is no layout with two panels: a wider window gives the list the room.
 */
data class WindowLayout(val kind: WindowClass, /** The panel's width beside the list, 0 where there is no room for one. */ val panelDp: Int) {
    /** The window's margin, also under the cards. */
    val marginDp: Int get() = if (kind == WindowClass.NARROW) 16 else 24
}

/**
 * The layout of a window [widthDp] wide. The design's widths are for the standard text size: at a larger one
 * ([fontScale] above 1) the window is counted in text sizes, so that at twice the size a window of 1180 is laid out as
 * one of 590 is (it holds as many words), and a panel beside the list grows with its words.
 */
fun windowLayout(widthDp: Int, fontScale: Float = 1f): WindowLayout {
    val scale = fontScale.coerceAtLeast(1f)
    val width = (widthDp / scale).toInt()
    return when {
        width >= 1500 -> WindowLayout(WindowClass.WIDE, (420 * scale).toInt())
        width >= 1100 -> WindowLayout(WindowClass.WIDE, (348 * scale).toInt())
        width >= 760 -> WindowLayout(WindowClass.MID, 0)
        else -> WindowLayout(WindowClass.NARROW, 0)
    }
}

/** The layout of the window a page is drawn in ([Shell] provides it). */
val LocalWindowLayout = compositionLocalOf { windowLayout(1180) }

/** The header's tabs, in their order. */
val tabs: List<Page> = Page.entries

/**
 * What the header's status line says: what the strip is actually doing, not just whether the service is on. While it
 * is off the line names the one step left, on every tab: the control beside it, or Setup's own, takes it. While
 * Presenting mode is on ([presenting]) and the bar is there, the line says that: the bar is all but empty on purpose,
 * and the switch that says why is on Look. No bar at all (a full-screen app, a panel over it) is said first.
 */
@StringRes
fun statusLine(running: Boolean, enabled: Boolean, status: BarStatus, presenting: Boolean = false): Int = when {
    !running -> R.string.bar_banner_title
    !enabled -> R.string.status_hidden
    else -> when (status) {
        BarStatus.STOPPED -> R.string.status_starting
        BarStatus.NO_BAR -> R.string.status_no_bar
        BarStatus.COVERED -> R.string.status_covered
        else -> when {
            presenting -> R.string.status_presenting
            status == BarStatus.NO_ROOM -> R.string.status_no_room
            status == BarStatus.EMPTY -> R.string.status_empty
            else -> R.string.status_live
        }
    }
}

/** The dot before the status line. The line's words say the state; the dot only agrees with them. */
enum class StatusTone {
    /** The bar is in the status bar. */
    LIVE,
    /** The bar is not on screen right now, and nothing is asked of anyone. */
    WAITING,
    /** BentoBar is off: one step is left. */
    STEP,
}

fun statusTone(running: Boolean, enabled: Boolean, status: BarStatus, presenting: Boolean = false): StatusTone =
    if (!running) StatusTone.STEP else if (statusLine(running, enabled, status, presenting) == R.string.status_live) StatusTone.LIVE else StatusTone.WAITING

/**
 * The settings window: a header row directly under the system's caption and in its color (mark and name, the tabs,
 * the status line, one control), and the page under it. Nothing is placed in the caption: its ground is painted,
 * and everything else starts below [insets]' top. Below 760 the header takes two rows.
 *
 * The control is the labelled switch while BentoBar runs and "Turn on" while it is off; on the Setup tab, while
 * off, the header has the status only, since Setup's own Turn on is then the one filled button on the screen.
 */
@Composable
fun Shell(
    page: Page,
    onPage: (Page) -> Unit,
    running: Boolean,
    enabled: Boolean,
    status: BarStatus,
    onEnabled: (Boolean) -> Unit,
    onTurnOn: () -> Unit,
    presenting: Boolean = false,
    insets: WindowInsets = WindowInsets.safeDrawing,
    content: @Composable BoxScope.() -> Unit,
) {
    val c = Bento.colors
    BoxWithConstraints(Modifier.fillMaxSize().background(c.surface)) {
        val layout = windowLayout(maxWidth.value.toInt(), LocalDensity.current.fontScale)
        CompositionLocalProvider(LocalWindowLayout provides layout, LocalContentColor provides c.ink) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().background(c.chrome).windowInsetsPadding(insets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
                    Header(layout.kind == WindowClass.NARROW, page, onPage, running, enabled, status, presenting, onEnabled, onTurnOn)
                }
                // The page's name is no longer written above it: a screen reader still hears it when the page changes.
                val title = stringResource(page.title)
                Box(Modifier.weight(1f).fillMaxWidth().windowInsetsPadding(insets.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                    .semantics { paneTitle = title }, content = content)
            }
        }
    }
}

@Composable
private fun Header(narrow: Boolean, page: Page, onPage: (Page) -> Unit, running: Boolean, enabled: Boolean, status: BarStatus, presenting: Boolean,
                   onEnabled: (Boolean) -> Unit, onTurnOn: () -> Unit) {
    val line = stringResource(statusLine(running, enabled, status, presenting))
    val tone = statusTone(running, enabled, status, presenting)
    if (narrow) Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = Target.tab).padding(start = Space.l, end = Space.m), verticalAlignment = Alignment.CenterVertically) {
            Mark(stringResource(R.string.app_name))
            Spacer(Modifier.width(Space.xs))
            // The row scrolls sideways in a window too narrow for the tabs; its own padding keeps the focus ring whole.
            Tabs(page, onPage, step = !running, pad = Space.m, gap = 0.dp, Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(horizontal = Space.xs))
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(start = 20.dp, end = Space.l, bottom = Space.xs), verticalAlignment = Alignment.CenterVertically) {
            Status(line, tone, Modifier.weight(1f), atEnd = false)
            Spacer(Modifier.width(Space.m))
            Control(page, running, enabled, onEnabled, onTurnOn)
        }
    } else Row(Modifier.fillMaxWidth().heightIn(min = Target.tab).padding(horizontal = Space.xl), verticalAlignment = Alignment.CenterVertically) {
        Mark(null)
        Spacer(Modifier.width(10.dp))
        Text(stringResource(R.string.app_name), style = Bento.type.title.copy(letterSpacing = (-0.1).sp), color = Bento.colors.ink, maxLines = 1)
        Spacer(Modifier.width(22.dp))
        Tabs(page, onPage, step = !running, pad = 14.dp, gap = 2.dp)
        Spacer(Modifier.width(Space.l))
        Status(line, tone, Modifier.weight(1f), atEnd = true)
        Spacer(Modifier.width(28.dp))
        Control(page, running, enabled, onEnabled, onTurnOn)
    }
}

/**
 * The tabs. A click, or Enter or Space on the tab that has the keyboard, changes the page at once; the arrow keys
 * (and Home and End) carry the keyboard from tab to tab without changing it. The underline travels to the chosen tab.
 */
@Composable
private fun Tabs(page: Page, onPage: (Page) -> Unit, step: Boolean, pad: Dp, gap: Dp, modifier: Modifier = Modifier) {
    val c = Bento.colors
    val type = Bento.type
    val requesters = remember { tabs.map { FocusRequester() } }
    val forward = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1 else 1
    // Where each tab stands in the row (left and right edge, px), for the underline.
    val spans = remember { mutableStateMapOf<Page, Pair<Float, Float>>() }
    val left = remember { Animatable(0f) }
    val right = remember { Animatable(0f) }
    var under by remember { mutableStateOf<Page?>(null) }
    val target = spans[page]
    LaunchedEffect(page, target) {
        target ?: return@LaunchedEffect
        // It travels only for a change of tab: the first placing and a resized window put it where it belongs at once.
        if (under == null || under == page) {
            left.snapTo(target.first); right.snapTo(target.second)
        } else {
            launch { left.animateTo(target.first, Motion.spec(tween(Motion.NEIGHBOURS))) }
            right.animateTo(target.second, Motion.spec(tween(Motion.NEIGHBOURS)))
        }
        under = page
    }
    Row(modifier.selectableGroup().drawBehind {
        if (under != null) drawRect(c.accent, Offset(left.value + pad.toPx(), size.height - 8.dp.toPx()),
            Size((right.value - left.value - 2 * pad.toPx()).coerceAtLeast(0f), 2.dp.toPx()))
    }, horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
        tabs.forEachIndexed { i, p ->
            val chosen = p == page
            val interaction = remember { MutableInteractionSource() }
            Row(Modifier
                .onPlaced { spans[p] = it.positionInParent().x.let { x -> x to x + it.size.width } }
                .heightIn(min = Target.tab)
                .focusRequester(requesters[i])
                .onKeyEvent { e ->
                    val to = when (e.key) {
                        Key.DirectionRight -> i + forward
                        Key.DirectionLeft -> i - forward
                        Key.MoveHome -> 0
                        Key.MoveEnd -> tabs.lastIndex
                        else -> return@onKeyEvent false
                    }
                    if (e.type == KeyEventType.KeyDown && to in tabs.indices && to != i) requesters[to].requestFocus()
                    true
                }
                .states(interaction, inset = 8.dp)
                .selectable(chosen, interaction, indication = null, role = Role.Tab) { onPage(p) }
                .padding(horizontal = pad),
                verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(p.label), style = if (chosen) type.strong else type.label, color = if (chosen) c.ink else c.ink2, maxLines = 1)
                // Setup has a step waiting. The status line says so in words; the dot only points at the tab.
                if (p == Page.SETUP && step) {
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.size(7.dp).background(c.warnInk, CircleShape))
                }
            }
        }
    }
}

@Composable
private fun Status(text: String, tone: StatusTone, modifier: Modifier, atEnd: Boolean) {
    val c = Bento.colors
    Row(modifier, horizontalArrangement = if (atEnd) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(when (tone) { StatusTone.LIVE -> c.accent; StatusTone.STEP -> c.warnInk; StatusTone.WAITING -> c.ink3 }, CircleShape))
        Spacer(Modifier.width(Space.s))
        Text(text, Modifier.weight(1f, fill = false).semantics { liveRegion = LiveRegionMode.Polite },
            style = Bento.type.small, color = c.ink2, maxLines = lines(), overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Control(page: Page, running: Boolean, enabled: Boolean, onEnabled: (Boolean) -> Unit, onTurnOn: () -> Unit) {
    when {
        // Only meaningful once the service runs.
        running -> {
            val interaction = remember { MutableInteractionSource() }
            Row(Modifier.heightIn(min = Target.control).states(interaction, ground = false)
                .toggleable(enabled, interaction, indication = null, role = Role.Switch, onValueChange = onEnabled),
                verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.show_in_status_bar), style = Bento.type.small, color = Bento.colors.ink2, maxLines = 1)
                Spacer(Modifier.width(10.dp))
                SwitchMark(enabled)
            }
        }
        page != Page.SETUP -> BentoButton(stringResource(R.string.setup_turn_on), onTurnOn, kind = ButtonKind.FILLED)
    }
}

/** The app's mark, as the launcher icon draws it (`tools/logo.py`): the same in both themes. Also the disclosure's and About's, larger. */
@Composable
internal fun Mark(description: String?, side: Dp = 24.dp) {
    Canvas(Modifier.size(side).then(if (description == null) Modifier else Modifier.semantics { contentDescription = description; role = Role.Image })) {
        val u = size.width / 64f
        fun box(x: Float, y: Float, w: Float, h: Float, r: Float, color: Color) =
            drawRoundRect(color, Offset(x * u, y * u), Size(w * u, h * u), CornerRadius(r * u))
        box(0f, 0f, 64f, 64f, 16f, MARK_BOX)
        box(11f, 12f, 42f, 10f, 5f, MARK_RICE)
        drawPath(Path().apply { moveTo(19f * u, 14.5f * u); lineTo(16f * u, 17f * u); lineTo(19f * u, 19.5f * u) },
            MARK_BOX, style = Stroke(2f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
        for (x in listOf(37f, 42.5f, 48f)) drawCircle(MARK_BOX, 1.7f * u, Offset(x * u, 17f * u))
        box(11f, 26f, 19f, 27f, 5f, Color(0xFFCDE7DE))
        box(34f, 26f, 19f, 11.5f, 5f, Color(0xFFA6D3C6))
        box(34f, 41.5f, 19f, 11.5f, 5f, Color(0xFF82BEAE))
    }
}

private val MARK_BOX = Color(0xFF133F43)
private val MARK_RICE = Color(0xFFF2FAF7)
