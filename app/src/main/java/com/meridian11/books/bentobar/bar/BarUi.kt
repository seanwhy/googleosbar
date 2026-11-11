package com.meridian11.books.bentobar.bar

import android.graphics.Rect
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.MotionDurationScale
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.composed
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.areAnyPressed
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalLayoutDirection
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.meridian11.books.bentobar.ui.Motion
import com.meridian11.books.bentobar.ui.animatePlacement
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import com.meridian11.books.bentobar.util.Fmt
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meridian11.books.bentobar.data.Display
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.data.Pill
import com.meridian11.books.bentobar.data.TextSize
import com.meridian11.books.bentobar.items.AppItem
import com.meridian11.books.bentobar.items.BarRoute
import com.meridian11.books.bentobar.items.BarSlider
import com.meridian11.books.bentobar.items.ItemState
import com.meridian11.books.bentobar.items.QuotaRing
import com.meridian11.books.bentobar.items.Stands
import com.meridian11.books.bentobar.items.Tone
import com.meridian11.books.bentobar.util.Fonts
import com.meridian11.books.bentobar.util.Sym
import com.meridian11.books.bentobar.util.SymIcon

/** Colours and sizes for the strip, matched to the status bar. */
@androidx.compose.runtime.Immutable
data class StripLook(
    val fg: Color,
    /** True when [fg] is light, i.e. the bar behind it is dark. */
    val lightText: Boolean,
    val textSize: TextSize,
    val spacing: Dp,
    val pill: Pill,
    /** What the strip is drawn on: the sampled bar colour, or black/white for a dark/light bar. */
    val background: Color = if (lightText) Color.Black else Color.White,
) {
    val textSp: TextUnit get() = when (textSize) { TextSize.SMALL -> 12.5.sp; TextSize.DEFAULT -> 14.sp; TextSize.LARGE -> 15.5.sp }
    /**
     * The one rule for sizes in the strip: the words are in sp and grow with the user's text size, everything drawn around them is
     * in dp, as the system bar's own icons are, so a large text size can't push a symbol or a date badge out of a bar of fixed height.
     * Measured on the desktop bar: system glyphs are about 13 px wide and 14–16 px tall at 1.125x, which a 15 dp Material Symbol matches.
     */
    val iconDp: Dp get() = when (textSize) { TextSize.SMALL -> 13.5.dp; TextSize.DEFAULT -> 15.dp; TextSize.LARGE -> 17.dp }
    // Accent and warning colours keep 4.5:1 on the bar too; on a bar where they wouldn't (a mid-tone
    // colour), the text colour is used instead.
    val accent: Color get() = Contrast.orElse(if (lightText) Color(0xFFA8C7FA) else Color(0xFF0B57D0), background, fg)
    val warn: Color get() = Contrast.orElse(if (lightText) Color(0xFFFFD27A) else Color(0xFF8A5100), background, fg)
    val alertBg: Color get() = if (lightText) Color(0xFFFFB4AB) else Color(0xFFB3261E)
    val alertFg: Color get() = if (lightText) Color(0xFF690005) else Color.White

    // The route line's colors, for a flight that goes to plan, is late, or is very late. A line is a graphic, and
    // needs 3:1 on the bar where words need 4.5:1; on a bar where a color wouldn't reach that (a mid-tone color),
    // the text color is used instead. Late is the warning color as it is.
    val routeGood: Color get() = Contrast.orElse(if (lightText) Color(0xFF6DD58C) else Color(0xFF146C2E), background, fg, min = 3f)
    val routeLate: Color get() = Contrast.orElse(if (lightText) Color(0xFFFFD27A) else Color(0xFF8A5100), background, fg, min = 3f)
    val routeVeryLate: Color get() = Contrast.orElse(if (lightText) Color(0xFFFF897D) else Color(0xFFB3261E), background, fg, min = 3f)

    /**
     * What a route line's plane and the part flown are drawn in, by how the flight [stands]: the text
     * color where nothing is claimed of it, and for one that will not arrive the red of one that is
     * very late. In the alert pill ([alert]) it is the pill's ink, whatever the flight does: the pill
     * is as red as the reds are.
     */
    fun route(stands: Stands, alert: Boolean = false): Color = if (alert) alertFg else when (stands) {
        Stands.NO_CLAIM -> fg
        Stands.GOOD -> routeGood
        Stands.LATE -> routeLate
        Stands.VERY_LATE, Stands.WILL_NOT_ARRIVE -> routeVeryLate
    }

    /**
     * How strongly the text color is drawn for the slider's empty track: 0.38 on a dark bar and 0.50
     * on a light one (3.4:1 and 3.2:1 against black and white), raised in steps of 0.1 on a bar of
     * another color until the track stands out from it at 3:1, the contrast a control's parts need.
     * It stops at 0.7: any stronger and the track is no longer told from the filled part, which is
     * what shows the level. On the few mid-tone bars where the text itself only just reaches 4.5:1
     * the track then ends a little under 3:1 (2.8:1 at the least, see SliderMathTest).
     */
    val sliderTrackAlpha: Float get() {
        var a = if (lightText) 0.38f else 0.50f
        while (a < 0.7f && Contrast.ratio(fg.copy(alpha = a).compositeOver(background), background) < 3f) a = (a + 0.1f).coerceAtMost(0.7f)
        return a
    }
    /** The slider's empty track. */
    val sliderTrack: Color get() = fg.copy(alpha = sliderTrackAlpha)
    /**
     * The slider's filled part while muted: between the track and the text color, so the kept level
     * still shows (0.62 on a dark bar, 0.74 on a light one; stronger where the track had to be).
     */
    val sliderMuted: Color get() = fg.copy(alpha = (sliderTrackAlpha + 0.24f).coerceAtMost(1f))
}

/**
 * The slider in the bar: the track, the zone that takes the pointer for it (the track and the item's end), its handle.
 * The route line is the same track, as long and as high, so that the two read as one family.
 */
val SLIDER_TRACK = 64.dp
private val SLIDER_ZONE = 70.dp
private val SLIDER_HEIGHT = 4.dp
private val SLIDER_HANDLE_WIDTH = 4.dp
private val SLIDER_HANDLE_HEIGHT = 14.dp

/**
 * The strip's padding at each end: the pill's own, and the room the highlight's round ends need (it reaches 4 dp past
 * the end items' boxes, and keeps 2 dp to the strip's ends). With a pill the controller counts it in the width budget;
 * without one it places the window that much further out, so the items stay where they were.
 */
val STRIP_PILL_PADDING = 6.dp

@androidx.compose.runtime.Immutable
data class StripEntry(val item: ItemConfig, val state: ItemState)

/** What the strip reports back to [BarController]. Rects are in window coordinates. */
interface StripEvents {
    fun click(item: ItemConfig, at: Rect)
    fun context(item: ItemConfig, at: Rect)
    fun scroll(item: ItemConfig, steps: Int)
    fun chevron(at: Rect)
    fun chevronContext(at: Rect)
    fun hover(inside: Boolean)
    /** Where an item (or "chevron") was drawn, in window coordinates. */
    fun placed(id: String, at: Rect)
    /** [id] is no longer drawn (it left, or FitRow had no room for it): its last place is out of date. */
    fun gone(id: String) {}
    /** The pointer is over [item] (or has left it), for its tooltip. */
    fun itemHover(item: ItemConfig, at: Rect, inside: Boolean) {}
    /** The wheel over the strip, where no item uses it: [up] reveals hidden items, down folds them. */
    fun wheel(up: Boolean) {}
    /** A click in a gap between items: it is the click of the item the highlight is on ([key]: an item's id, or "chevron"). */
    fun gapClick(key: String) {}
    /** [item] dragged sideways by [dx] px; [done] on release, where it should land. */
    fun drag(item: ItemConfig, dx: Float, done: Boolean) {}
    /** The drag of [item] was taken away (the system cancelled the press, the item left the strip): nothing lands. */
    fun dragTakenAway(item: ItemConfig) {}
    /** [item]'s slider was set to [level] (0 to 1, on one of its steps); [done] when the pointer let go. */
    fun slide(item: ItemConfig, level: Float, done: Boolean) {}
    /** Whether a slider in this strip is a control. In the settings preview it is only drawn: a click there selects the item. */
    val slidable: Boolean get() = true
    /** Whether an item can be picked up in this strip. In the settings preview it can't: a drag there would spring back doing nothing. */
    val draggable: Boolean get() = true
}

/**
 * The strip: optional ‹ button, revealed hidden items, then the visible items. With
 * [keepEnd], items at the end (next to the system icons) win when space runs out.
 */
@Composable
fun Strip(
    visible: List<StripEntry>,
    revealed: List<StripEntry>,
    showChevron: Boolean,
    /** ‹ shows whatever fits (presenting, or hidden items behind it), so its room is always kept. */
    chevronAlways: Boolean,
    /** Room kept for ‹ inside [maxWidthPx] when it shows. */
    chevronReservePx: Int,
    expanded: Boolean,
    chevronOnLeft: Boolean,
    look: StripLook,
    maxWidthPx: Int,
    heightDp: Dp,
    events: StripEvents,
    /** Ids of the items that didn't fit, each time that changes. */
    onOverflow: (Set<String>) -> Unit = {},
    /** The item whose popup is open ("chevron" for the ‹ menu), or null: the highlight stays on it. */
    open: String? = null,
) {
    val pillBg = when (look.pill) {
        Pill.NONE -> Color.Transparent
        Pill.SUBTLE -> look.fg.copy(alpha = 0.12f)
        Pill.SOLID -> if (look.lightText) Color(0xE6202124) else Color(0xE6F1F3F4)
    }
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val glow = remember(density) { Glow(density) }
    val view = androidx.compose.ui.platform.LocalView.current
    SideEffect {
        glow.model.refresh = view.display?.refreshRate ?: 60f
        glow.hold(open)
    }
    // The highlight's frames: none at rest, and a frame each while it moves or fades. The system's animator duration
    // scale comes with the effect's context, as every animation of Compose's takes it.
    LaunchedEffect(glow) {
        val context = coroutineContext
        glow.frames { if (Motion.off) 0f else context[MotionDurationScale]?.scaleFactor ?: 1f }
    }
    // The strip's order runs left to right in every language: ‹ and its side, which items are kept when room runs out and where a
    // dragged item lands are all physical. Inside an item the real direction holds ([ItemView]), so its icon and words, its slider
    // and its route line read as the language does.
    val real = LocalLayoutDirection.current
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) { Row(
        Modifier.height(heightDp)
            .onPlaced { glow.row = it }
            // On glow and events: glow is made anew when the display's density changes, and the events are the host's.
            .pointerInput(glow, events) {
                awaitPointerEventScope {
                    // A press no item took (it fell in a gap): its click goes to the item the highlight is on, which
                    // never leaves the gaps (motion.md §4). Items take their own presses before this sees them.
                    var gap = false
                    while (true) {
                        val e = awaitPointerEvent()
                        when (e.type) {
                            PointerEventType.Press -> gap = e.changes.none { it.isConsumed } && !e.buttons.isSecondaryPressed
                            PointerEventType.Release -> {
                                if (gap && e.changes.none { it.isConsumed }) glow.model.under?.let { events.gapClick(it) }
                                gap = false
                            }
                            PointerEventType.Enter -> events.hover(true)
                            PointerEventType.Exit -> events.hover(false)
                            PointerEventType.Scroll -> e.changes.firstOrNull()?.takeIf { !it.isConsumed }?.let {
                                if (it.scrollDelta.y != 0f) { events.wheel(it.scrollDelta.y < 0); it.consume() }
                            }
                        }
                    }
                }
            }
            // The highlight follows the pointer. Read on the Initial pass, before anything inside sees the event, and
            // never consumed: clicks, sliders, dragging and the wheel get every event as they did. (The handler above
            // stays on the Main pass: the wheel there must know whether an item took the scroll.)
            .pointerInput(glow) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Initial)
                        val c = e.changes.firstOrNull() ?: continue
                        // A pressed pointer is still reported once it has left the strip (a slider dragged past its end):
                        // only the Exit counts there.
                        val over = c.position.x >= 0f && c.position.y >= 0f && c.position.x < size.width && c.position.y < size.height
                        when (e.type) {
                            PointerEventType.Enter, PointerEventType.Move -> if (over) glow.move(c.position.x)
                            PointerEventType.Press -> { if (over) glow.move(c.position.x); glow.press(true) }
                            PointerEventType.Release -> {
                                glow.press(e.changes.any { it.pressed })
                                // A finger has no hover: lifted, it has left.
                                if (c.type == PointerType.Touch) glow.leave()
                            }
                            PointerEventType.Exit -> glow.leave()
                        }
                    }
                }
            }
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(50))
            .background(pillBg)
            // Over the strip's own pill and under every item (an alert's red capsule is drawn over it). Its place is
            // read here, at draw time, so a frame of its way draws it again and recomposes nothing.
            .drawBehind { with(glow) { draw(look.fg) } }
            // Without a pill the ends are only room for the highlight: with nothing listed the strip stays 0 wide, and its
            // window a pixel wide while it is invisible ([StripContent] says when that is).
            .padding(horizontal = if (look.pill == Pill.NONE && visible.isEmpty() && revealed.isEmpty() && !showChevron) 0.dp else STRIP_PILL_PADDING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val chevron: @Composable () -> Unit = {
            if (showChevron) Chevron(expanded, chevronOnLeft, look, events, glow)
        }
        val items = if (chevronOnLeft) revealed + visible else visible + revealed
        if (chevronOnLeft) chevron()
        FitRow(ids = items.map { it.item.id }, maxWidthPx = maxWidthPx, chevronAlways = chevronAlways, chevronPx = chevronReservePx,
            keepEnd = chevronOnLeft, spacing = look.spacing, onDropped = { ids -> glow.dropped(ids); ids.forEach(events::gone); onOverflow(ids) }) {
            // key(): remembered state (hover, held width) belongs to the item, not to its position,
            // or an item popping in would inherit its neighbour's width.
            items.forEach { androidx.compose.runtime.key(it.item.id) { CompositionLocalProvider(LocalLayoutDirection provides real) { ItemView(it, look, events, glow) } } }
        }
        if (!chevronOnLeft) chevron()
    } }
}

/**
 * The strip's one highlight on the Compose side of [StripHighlight], which decides everything: this hands it the
 * pointer and where each item is drawn, runs its frames, and draws it.
 *
 * Nothing runs at rest. A change that needs frames wakes [frames], which steps the model once a frame until it rests; the
 * grace after the pointer leaves is one wait, not frames. Each frame bumps [drawn], which only the row's drawBehind
 * reads: the strip draws again and recomposes nothing. Most pointer moves change nothing and cost one lookup.
 */
private class Glow(private val density: Float) {
    val model = StripHighlight()
    private val drawn = mutableIntStateOf(0)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    /** The strip's row: x is measured from its left end, by the pointer, the items' boxes and the drawing alike. */
    var row: LayoutCoordinates? = null
    private val boxes = HashMap<String, Span>()
    /** Items FitRow left out: not placed, so their last box would be stale. */
    private var dropped: Set<String> = emptySet()
    private var pinned: String? = null

    private fun now() = System.nanoTime()
    private fun did(changed: Boolean) { if (changed) { drawn.intValue++; wake.trySend(Unit) } }

    fun move(xPx: Float) = did(model.move(xPx / density, now()))
    fun leave() = did(model.leave(now()))
    fun press(down: Boolean) = did(model.press(down, now()))
    fun hold(key: String?) = did(model.hold(key, now()))
    fun lift(up: Boolean) = did(model.lift(up, now()))

    /** [id]'s slider is held ([on]) or let go; only its own let-go unpins. */
    fun pin(id: String, on: Boolean) {
        if (on) { pinned = id; did(model.pin(id, now())) }
        else if (pinned == id) { pinned = null; did(model.pin(null, now())) }
    }

    /** [id] (an item, or "chevron") is drawn at [box]: the box it is drawn in, after any slide of its own. */
    fun placed(id: String, box: LayoutCoordinates) {
        val r = row?.takeIf { it.isAttached } ?: return
        if (!box.isAttached) return
        val b = r.localBoundingBoxOf(box, clipBounds = false)
        val span = Span(id, b.left / density, b.right / density)
        if (boxes.put(id, span) != span) relayout()
    }

    fun gone(id: String) { if (boxes.remove(id) != null) relayout() }

    fun dropped(ids: Set<String>) { if (ids != dropped) { dropped = ids; relayout() } }

    private fun relayout() = did(model.layout(boxes.values.filter { it.key !in dropped }, now()))

    /** Runs the model's frames while it moves or fades, waits out its grace, and sleeps until the next change. */
    suspend fun frames(scale: () -> Float) {
        model.scale = scale()
        for (change in wake) {
            while (true) {
                model.scale = scale()
                if (!model.moving) {
                    val at = model.wakeAt ?: break
                    val ms = (at - System.nanoTime()) / 1_000_000
                    if (ms > 0) delay(ms)
                }
                withFrameNanos { model.step(it) }
                drawn.intValue++
            }
        }
    }

    /** One rounded rectangle, in the bar's text colour (visual.md §3); nothing while it is gone. */
    fun DrawScope.draw(fg: Color) {
        drawn.intValue
        val a = model.alpha
        if (a <= 0f) return
        val h = StripHighlight.height(size.height / density) * density
        if (h <= 0f) return
        val l = model.left * density
        val r = model.right * density
        drawRoundRect(fg.copy(alpha = model.fill * a), Offset(l, (size.height - h) / 2), Size(r - l, h), CornerRadius(h / 2))
    }
}

/**
 * A stadium over this box, in a strip [room] dp tall: the highlight's shape for the tile a dragged item lifts on
 * ([pill]), or an alert's red capsule, 4 dp lower and only 2 dp past the box, so the highlight shows round it as a ring.
 */
private fun DrawScope.capsule(color: Color, room: Float, pill: Boolean) {
    val h = (if (pill) StripHighlight.height(room) else StripHighlight.alertHeight(room)).dp.toPx()
    if (h <= 0f) return
    val out = (if (pill) StripHighlight.OUTSET else StripHighlight.ALERT_OUTSET).dp.toPx()
    drawRoundRect(color, Offset(-out, (size.height - h) / 2), Size(size.width + 2 * out, h), CornerRadius(h / 2))
}

/**
 * Which of [widths] fit in [limit] with [gap] between them, keeping from the start (or the end with
 * [keepEnd]) and stopping at the first that doesn't fit; and the width they take.
 */
internal fun fitWidths(widths: List<Int>, gap: Int, limit: Int, keepEnd: Boolean): Pair<BooleanArray, Int> {
    val keep = BooleanArray(widths.size)
    var used = 0
    for (i in if (keepEnd) widths.indices.reversed() else widths.indices) {
        val w = widths[i] + if (used > 0) gap else 0
        if (used + w > limit) break
        keep[i] = true
        used += w
    }
    return keep to used
}

/** [fitWidths] for the strip: everything in [budget] if it fits there (‹ needn't show), else room for ‹. */
internal fun fitStrip(widths: List<Int>, gap: Int, budget: Int, chevronAlways: Boolean, chevronPx: Int, keepEnd: Boolean) =
    fitWidths(widths, gap, if (chevronAlways) budget - chevronPx else budget, keepEnd).takeIf { (k, _) -> k.all { it } }
        ?: fitWidths(widths, gap, budget - chevronPx, keepEnd)

/**
 * A row that drops children which don't fit, from the start (or the end when !keepEnd), and
 * reports the dropped [ids] so they can be offered elsewhere instead of silently vanishing.
 * [maxWidthPx] includes ‹: its [chevronPx] is kept when it shows anyway, or when something has to be
 * dropped (‹ then offers it). Decided from this measure pass alone, so the strip can't stay
 * overflowed just because it was overflowed before.
 */
@Composable
private fun FitRow(ids: List<String>, maxWidthPx: Int, chevronAlways: Boolean, chevronPx: Int, keepEnd: Boolean, spacing: Dp,
                   onDropped: (Set<String>) -> Unit, content: @Composable () -> Unit) {
    val last = remember { arrayOf<Set<String>?>(null) }
    Layout(content) { measurables, constraints ->
        val gap = spacing.roundToPx()
        val placeables = measurables.map { it.measure(Constraints(maxHeight = constraints.maxHeight)) }
        val widths = placeables.map { it.width }
        val budget = minOf(maxWidthPx, constraints.maxWidth).coerceAtLeast(0)
        val (keep, used) = fitStrip(widths, gap, budget, chevronAlways, chevronPx, keepEnd)
        val dropped = ids.filterIndexed { i, _ -> i < keep.size && !keep[i] }.toSet()
        if (dropped != last[0]) { last[0] = dropped; onDropped(dropped) }
        val height = placeables.maxOfOrNull { it.height } ?: 0
        layout(used, height) {
            var x = 0
            placeables.forEachIndexed { i, p ->
                if (!keep[i]) return@forEachIndexed
                p.placeRelative(x, (height - p.height) / 2)
                x += p.width + gap
            }
        }
    }
}

@Composable
private fun Chevron(expanded: Boolean, onLeft: Boolean, look: StripLook, events: StripEvents, glow: Glow) {
    var bounds by remember { mutableStateOf(Rect()) }
    DisposableEffect(glow) { onDispose { glow.gone("chevron"); events.gone("chevron") } }
    // Hidden items sit on the chevron's far side; the arrow points where they'll appear.
    val sym = if (onLeft != expanded) Sym.CHEVRON_LEFT else Sym.CHEVRON_RIGHT
    val label = androidx.compose.ui.res.stringResource(if (expanded) com.meridian11.books.bentobar.R.string.chevron_hide else com.meridian11.books.bentobar.R.string.chevron_show)
    val menuLabel = androidx.compose.ui.res.stringResource(com.meridian11.books.bentobar.R.string.chevron_menu)
    Box(
        Modifier.fillMaxHeight()
            // ‹ is one more item to the strip's highlight, which is its hover: it draws none of its own.
            .onGloballyPositioned { bounds = it.boundsInWindow().toRect(); events.placed("chevron", bounds); glow.placed("chevron", it) }
            .clicks({ events.chevron(bounds) }, { events.chevronContext(bounds) }, null)
            .semantics {
                contentDescription = label; role = Role.Button
                onClick { events.chevron(bounds); true }
                onLongClick(menuLabel) { events.chevronContext(bounds); true }
            }
            .padding(horizontal = 3.dp),
        contentAlignment = Alignment.Center,
    ) { SymIcon(sym, size = dpFont(look.iconDp), color = look.fg.copy(alpha = 0.85f)) }
}

/**
 * An item being dragged along the strip: the gesture's state, apart from what [ItemView] draws. It lifts and follows the
 * pointer while the others slide aside (animatePlacement, as the controller previews the new order), then settles into its slot.
 * Positions are measured against the item's untransformed slot: pointer positions relative to the moving item itself would
 * feed its own movement back in and make it flicker.
 */
@androidx.compose.runtime.Stable
private class ItemDrag(private val scope: kotlinx.coroutines.CoroutineScope) {
    /** Where the item's slot is, in window coordinates. */
    var slotX by mutableStateOf(0f)
    /** Where in the item it was picked up; null when not dragging. */
    private var grab by mutableStateOf<Float?>(null)
    private var startSlot by mutableStateOf(0f)
    // Where the item's left edge follows the pointer, in window coordinates; null when not dragging.
    // The draw offset is worked out from it and the item's current slot at draw time, so a slot that
    // moves (the preview reordering) can't leave the item a slot's width off for a frame.
    private var left by mutableStateOf<Float?>(null)
    private val settle = androidx.compose.animation.core.Animatable(0f)

    val lifted: Boolean get() = grab != null
    /** Lifted, or still springing back: other items' placement moves don't apply to it. */
    val moving: Boolean get() = lifted || settle.isRunning
    /** How far from its slot it is drawn, read at draw time. */
    val offset: Float get() = (left?.let { it - slotX } ?: 0f) + settle.value

    fun move(localX: Float, downX: Float, done: Boolean, report: (dx: Float) -> Unit) {
        // Grabbed where the pointer is when the drag begins (past the touch slop), not where it
        // was pressed: otherwise the item jumps the slop distance to catch up on the first frame.
        if (grab == null) { grab = localX; startSlot = slotX; scope.launch { settle.snapTo(0f) } }
        val edge = slotX + localX - (grab ?: downX) // where the item's left edge follows the pointer
        left = edge
        report(edge - startSlot)
        if (done) drop()
    }

    /** Let go, or taken away: it springs from where it is now into its slot (the previewed order is the real one, or the old one again). */
    fun drop() {
        val from = (left ?: slotX) - slotX
        grab = null
        left = null
        scope.launch {
            settle.snapTo(from)
            settle.animateTo(0f, Motion.spec(androidx.compose.animation.core.spring(dampingRatio = 0.8f,
                stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow)))
        }
    }
}

@Composable
private fun ItemView(entry: StripEntry, look: StripLook, events: StripEvents, glow: Glow) {
    val s = entry.state
    if (entry.item.type == com.meridian11.books.bentobar.items.SpacerItem.TYPE) {
        Box(Modifier.width(s.gapDp.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
            if (StripContent.line(s)) Box(Modifier.width(1.dp).height(16.dp).background(look.fg.copy(alpha = 0.45f)))
        }
        return
    }
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    var bounds by remember { mutableStateOf(Rect()) }
    val itemMenuLabel = androidx.compose.ui.res.stringResource(com.meridian11.books.bentobar.R.string.strip_item_menu)
    LaunchedEffect(hovered) { events.itemHover(entry.item, bounds, hovered) }
    // An item that leaves with the pointer on it (its rule ended, the wheel folded it): the effect above is cancelled, not
    // run, so the hover's end, and the tooltip's, is said here.
    DisposableEffect(entry.item.id) { onDispose { if (hovered) events.itemHover(entry.item, bounds, false) } }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val drag = remember { ItemDrag(scope) }
    val lifted = drag.lifted
    // Lifted, the item hides the strip's highlight; dropped (or gone mid-drag), the highlight waits for the pointer to move.
    DisposableEffect(lifted) {
        if (lifted) glow.lift(true)
        onDispose { if (lifted) glow.lift(false) }
    }
    DisposableEffect(entry.item.id) { onDispose { glow.gone(entry.item.id); events.gone(entry.item.id) } }
    val liftScale by androidx.compose.animation.core.animateFloatAsState(if (lifted) 1.08f else 1f,
        Motion.spec(androidx.compose.animation.core.spring(stiffness = androidx.compose.animation.core.Spring.StiffnessMedium)), label = "lift")
    // Live numbers (speeds, percentages, clocks) sit in a fixed slot sized for their widest
    // reading (Fmt.widthTemplate), right-aligned, so nothing next to them moves as they change.
    val density = androidx.compose.ui.platform.LocalDensity.current
    val measurer = rememberTextMeasurer()
    val textStyle = TextStyle(fontFamily = Fonts.bar, fontWeight = Fonts.barWeight, fontSize = look.textSp, fontFeatureSettings = "tnum", lineHeight = look.textSp)
    // Appearing (the strip starting, an item popping out, ‹ revealing hidden items) fades and slides
    // in instead of popping. Drawn in the graphics layer only: no relayout, so the window doesn't
    // resize per frame.
    val appear = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) {
        appear.animateTo(1f, Motion.spec(androidx.compose.animation.core.tween(180,
            easing = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0f, 0f, 1f))))
    }
    val alert = StripContent.capsule(s)
    val color = when (s.tone) {
        Tone.ALERT -> look.alertFg
        Tone.WARN -> look.warn
        Tone.ACCENT -> look.accent
        Tone.NORMAL -> look.fg
    }
    // What this item draws is [StripContent]'s to say, for the strip's emptiness as much as here.
    val parts = StripContent.parts(s, entry.item.display)
    val slider = parts.slider
    val route = parts.route
    // While the pointer holds the slider: the level under it. Otherwise the item's own, which is the real one.
    var held by remember { mutableStateOf<Float?>(null) }
    // A held slider keeps the strip's highlight on its item, wherever the pointer goes, until it lets go.
    val sliding = held != null
    DisposableEffect(sliding) {
        if (sliding) glow.pin(entry.item.id, true)
        onDispose { if (sliding) glow.pin(entry.item.id, false) }
    }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val context by rememberUpdatedState { events.context(entry.item, bounds) }
    val quotaRing = s.quotaRing?.takeIf { entry.item.display != Display.ICON }
    val showText = parts.text && quotaRing == null
    Row(
        Modifier.fillMaxHeight()
            // The slot is read during placement (before this frame draws), the bounds after layout.
            .onPlaced { drag.slotX = it.positionInWindow().x }
            .onGloballyPositioned { bounds = it.boundsInWindow().toRect(); events.placed(entry.item.id, bounds) }
            .clicks({ events.click(entry.item, bounds) }, { events.context(entry.item, bounds) },
                if (com.meridian11.books.bentobar.items.Items.of(entry.item.type)?.usesWheel == true) { steps -> events.scroll(entry.item, steps) } else null,
                onDrag = if (!events.draggable) null else { localX, downX, done -> drag.move(localX, downX, done) { dx -> events.drag(entry.item, dx, done) } },
                onDragTakenAway = { if (drag.lifted) { drag.drop(); events.dragTakenAway(entry.item) } })
            // Neighbors slide to their new places like magnets; the lifted item follows the pointer instead.
            .then(if (drag.moving) Modifier else Modifier.animatePlacement())
            // Where it is drawn, sliding included, for the strip's highlight, which sits on the item wherever it goes.
            .onGloballyPositioned { glow.placed(entry.item.id, it) }
            .zIndex(if (drag.moving) 1f else 0f)
            .graphicsLayer {
                alpha = appear.value
                translationX = (1f - appear.value) * 6.dp.toPx() + drag.offset
                scaleX = liftScale; scaleY = liftScale
            }
            // Hovering draws nothing here: the strip's one highlight is the hover (Strip, StripHighlight). An alert's
            // red capsule lies over it; lifted, the item is a solid tile in the highlight's shape and the bar's own
            // colour, so text it passes over doesn't show through.
            .drawBehind {
                when {
                    alert -> capsule(look.alertBg, size.height.toDp().value, pill = false)
                    lifted -> capsule(look.fg.copy(alpha = 0.18f).compositeOver(look.background), size.height.toDp().value, pill = true)
                }
            }
            // For the tooltip and the slider's handle.
            .hoverable(source)
            .semantics(mergeDescendants = true) {
                // Never an unnamed button: with no words of its own, an item is what its type is called.
                contentDescription = s.desc.ifEmpty { s.text.orEmpty() }.ifEmpty { com.meridian11.books.bentobar.items.Items.of(entry.item.type)?.title.orEmpty() }
                // The clicks come from raw pointer input, so tell assistive tech how to press it.
                onClick { events.click(entry.item, bounds); true }
                onLongClick(itemMenuLabel) { events.context(entry.item, bounds); true }
                // In the settings preview the track is only drawn: the item is a button there, like every other.
                if (slider == null || !events.slidable) role = Role.Button
                else {
                    // A slider for assistive tech too: it reads the description and can raise and lower the
                    // level, step by step where the slider has steps (0 to 15 for a volume of fifteen steps).
                    val top = if (slider.steps > 0) slider.steps.toFloat() else 1f
                    progressBarRangeInfo = ProgressBarRangeInfo(slider.level.coerceIn(0f, 1f) * top, 0f..top, (slider.steps - 1).coerceAtLeast(0))
                    setProgress { to -> events.slide(entry.item, SliderMath.snap(to / top, slider.steps), true); true }
                }
            }
            // The slider's zone runs to the item's very end (the last 6 dp mean "full"), so it brings that padding itself.
            .padding(start = 6.dp, end = if (slider != null) 0.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (route != null) {
            RouteTrack(route, look, alert, rtl)
            // 6 dp to the words, as much as the item has at its two ends: the line is no glyph that belongs to them.
            if (parts.gapAfterRoute) Spacer(Modifier.width(6.dp))
        }
        if (parts.iconDrawn) {
            val img = s.image
            if (img != null) {
                Image(img.asImageBitmap(), null, Modifier.size(look.iconDp),
                    colorFilter = if (AppItem.mono.of(entry.item) && entry.item.type == "app") ColorFilter.tint(color) else null)
            } else if (s.dayNumber != null) {
                DayBadge(s.dayNumber, look.iconDp, color, look.background)
            } else if (s.icon != null) {
                SymIcon(s.icon, size = dpFont(look.iconDp), filled = s.filled, color = color)
            }
        }
        if (parts.iconDrawn && (showText || slider != null || quotaRing != null) && (s.image != null || !s.icon.isNullOrEmpty())) {
            Spacer(Modifier.width(if (quotaRing != null) 3.5.dp else 5.dp))
        } else if (parts.gapAfterIcon && quotaRing == null) {
            Spacer(Modifier.width(5.dp))
        }
        if (quotaRing != null) {
            QuotaRingBadge(quotaRing, look.iconDp, look.fg, look.lightText)
        }
        if (slider != null) {
            VolumeTrack(slider, held, handle = hovered || held != null, look = look, rtl = rtl,
                modifier = if (!events.slidable) Modifier
                else Modifier.slides(slider.steps, rtl, onHeld = { held = it }, onContext = { context() }) { level, done -> events.slide(entry.item, level, done) })
        }
        if (showText) {
            // Texts that take turns sit in a place as wide as the widest of them; a ticking number in one sized for its widest reading.
            val templates = s.turns ?: if (s.widthKey != null) listOf(Fmt.widthTemplate(s.text!!)) else null
            val slot = templates?.let { tpls -> remember(tpls, textStyle) { tpls.maxOf { measurer.measure(it, textStyle, maxLines = 1).size.width } } }
            // With an icon, the number stays next to it and the spare room trails; text alone sits at the end.
            val iconShown = parts.leadInBeforeText
            // An item that promises a length is held to it in width too: 8.5 dp a character at the usual text
            // size. The count decides what is said; this only catches scripts whose characters are wide.
            val cap = if (s.textLimit > 0) with(density) { (8.5f * s.textLimit * look.textSp.value / 14f).sp.toDp() } else Dp.Unspecified
            Text(s.text!!, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = if (iconShown) TextAlign.Start else TextAlign.End,
                modifier = (if (slot != null) Modifier.widthIn(min = with(density) { slot.toDp() }) else Modifier).widthIn(max = cap),
                style = textStyle)
        }
    }
}

/**
 * The slider in the bar, where the item's text would be: a track [SLIDER_TRACK] wide and 4 dp high,
 * filled to the level, in a zone that runs on to the item's end and takes the pointer over the bar's
 * whole height. A quiet line at rest. With [handle] (the pointer is over the item, or holds the
 * slider) a small upright bar marks the level, with a gap on each side of it.
 *
 * While the pointer holds the slider ([held]) the level under the pointer is drawn; otherwise the
 * item's own, which is the real one and glides when it changes from elsewhere (the volume keys).
 */
@Composable
private fun VolumeTrack(slider: BarSlider, held: Float?, handle: Boolean, look: StripLook, rtl: Boolean, modifier: Modifier) {
    // Under the pointer nothing glides: the level set there is the item's own a moment later, and a glide that
    // were still on its way when the pointer lets go would draw the fill a step back first.
    val glided by androidx.compose.animation.core.animateFloatAsState(slider.level.coerceIn(0f, 1f),
        if (held != null) androidx.compose.animation.core.snap()
        else Motion.spec(androidx.compose.animation.core.tween(120, easing = androidx.compose.animation.core.FastOutSlowInEasing)), label = "level")
    val level = held ?: glided
    val track = look.sliderTrack
    // Muted, the fill is drawn solid in the color its strength gives on the bar: its round end lies over the
    // track's start, and a see-through fill would show that as a darker or lighter cap.
    val fill = if (slider.dimmed && held == null) look.sliderMuted.compositeOver(look.background) else look.fg
    val mark = look.fg
    Canvas(modifier.width(SLIDER_ZONE).fillMaxHeight()) {
        val w = SLIDER_TRACK.toPx()
        val h = SLIDER_HEIGHT.toPx()
        val top = (size.height - h) / 2
        val radius = CornerRadius(h / 2)
        // The track starts where the zone starts; right to left, that is the zone's right edge.
        fun x(along: Float) = if (rtl) size.width - along else along
        fun span(from: Float, to: Float, color: Color) {
            if (to - from <= 0f) return
            drawRoundRect(color, Offset(minOf(x(from), x(to)), top), Size(to - from, h), radius)
        }
        // Any level above nothing shows at least a dot; nothing shows nothing.
        val filled = SliderMath.fillWidth(level, w, min = h)
        if (!handle) {
            // The rest of the track is cut out of the whole track's shape, starting under the fill's round end:
            // the two meet without a notch, and the track's far end stays round.
            val from = (filled - h / 2).coerceAtLeast(0f)
            clipRect(left = minOf(x(from), x(w)), right = maxOf(x(from), x(w))) {
                drawRoundRect(track, Offset(minOf(x(0f), x(w)), top), Size(w, h), radius)
            }
            span(0f, filled, fill)
        } else {
            // The handle stands on the fill's end, and for 2 dp on each side of it neither fill nor track is drawn.
            val hw = SLIDER_HANDLE_WIDTH.toPx()
            val center = SliderMath.handleCenter(level, w, edge = hw / 2)
            val gap = hw / 2 + 2.dp.toPx()
            span(0f, center - gap, fill)
            span(center + gap, w, track)
            val hh = SLIDER_HANDLE_HEIGHT.toPx()
            drawRoundRect(mark, Offset(x(center) - hw / 2, (size.height - hh) / 2), Size(hw, hh), CornerRadius(hw / 2))
        }
    }
}

/**
 * The route line an item can have where its icon would be (the Flight item's, see [BarRoute]): the
 * slider's line, [SLIDER_TRACK] wide and 4 dp high, with a plane on it and 2 dp of nothing on each
 * side of the plane. The plane and the part flown take the color of how the flight stands; the part
 * ahead is the slider's quiet track, unless the line is one color from end to end. The plane is the
 * flight glyph at the icons' size, measured as text and turned so that its nose points along the line
 * (the Flight menu's line draws it the same way); the struck one stands upright.
 *
 * Not a control: it takes no pointer, so a click, a right-click and a drag on it are the item's own,
 * and hovering shows the strip's highlight and no handle. Nothing glides either: the plane is drawn
 * where the item says it stands, each time the item is drawn again.
 * Where each part stands is [SliderMath.route]'s arithmetic; this only draws it.
 */
@Composable
private fun RouteTrack(route: BarRoute, look: StripLook, alert: Boolean, rtl: Boolean) {
    val ink = look.route(route.stands, alert)
    // In the alert pill the bar's own track would be lost, as its colors would: the pill's ink there, as strong as the track is.
    val ahead = if (route.whole) ink else if (alert) look.alertFg.copy(alpha = look.sliderTrackAlpha) else look.sliderTrack
    val glyph = if (route.struck) Sym.AIRPLANEMODE_INACTIVE else Sym.FLIGHT
    val measurer = rememberTextMeasurer()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val planeFont = dpFont(look.iconDp)
    val plane = remember(measurer, density, glyph, planeFont) { measurer.measure(glyph, TextStyle(fontFamily = Fonts.symbolsFilled, fontSize = planeFont, lineHeight = planeFont)) }
    Canvas(Modifier.width(SLIDER_TRACK).fillMaxHeight()) {
        val h = SLIDER_HEIGHT.toPx()
        val top = (size.height - h) / 2
        val radius = CornerRadius(h / 2)
        // The plane is a symbol and grows with the text size: the line makes room for it as it is.
        val line = SliderMath.route(size.width, route.share, plane = look.iconDp.toPx(), gap = 2.dp.toPx(), rtl = rtl) ?: return@Canvas
        line.flown?.let { drawRoundRect(ink, Offset(it.start, top), Size(it.endInclusive - it.start, h), radius) }
        line.ahead?.let { drawRoundRect(ahead, Offset(it.start, top), Size(it.endInclusive - it.start, h), radius) }
        val middle = Offset(line.center, size.height / 2)
        val topLeft = Offset(middle.x - plane.size.width / 2f, middle.y - plane.size.height / 2f)
        // The glyph points up: a quarter turn puts its nose towards the arrival, which is on the left where the language reads from the right.
        if (route.struck) drawText(plane, color = ink, topLeft = topLeft)
        else rotate(if (rtl) -90f else 90f, middle) { drawText(plane, color = ink, topLeft = topLeft) }
    }
}

/**
 * The slider's pointer rules, on its zone (the track and the item's end; the icon and the gap after
 * it stay the item's own, see [clicks]).
 *
 * A mouse, a touchpad or a stylus: the level follows from the moment the button goes down, with no
 * slop, until it is released. A finger: a tap sets the level when it lifts, a finger that moves past
 * the touch slop makes the level follow, and one held without moving for the system's long-press
 * time (the setting a person can change) opens the item's menu ([onContext]) and changes nothing. A click or a tap never sets nothing at all: its
 * lowest level is one step (a slip at the track's start must not silence a call); a drag can reach 0.
 *
 * The press is consumed, so the item around the slider takes it neither for a click nor for the
 * start of a drag to reorder. A secondary press is left alone: it is the item's menu, as anywhere on
 * the item. [onHeld] gets the level while the pointer holds the slider, and null when it lets go.
 * Whatever was reported while it held is followed by one last report with `done`, also when the
 * press is taken away.
 */
private fun Modifier.slides(steps: Int, rtl: Boolean, onHeld: (Float?) -> Unit, onContext: () -> Unit,
                            onLevel: (level: Float, done: Boolean) -> Unit): Modifier = composed {
    val level by rememberUpdatedState(onLevel)
    val holds by rememberUpdatedState(onHeld)
    val menu by rememberUpdatedState(onContext)
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    pointerInput(steps, rtl) {
        val track = SLIDER_TRACK.toPx()
        // The rules are SliderGesture's (unit-tested); this turns pointer events into its four calls.
        val gesture = SliderGesture(steps)
        fun inside(c: androidx.compose.ui.input.pointer.PointerInputChange) = c.position.x >= 0 && c.position.y >= 0 && c.position.x <= size.width && c.position.y <= size.height
        fun say(l: Float) { holds(l); level(l, false) }
        fun end(how: SliderGesture.End) {
            holds(null)
            when (how) {
                is SliderGesture.End.Level -> level(how.level, true)
                SliderGesture.End.Menu -> menu()
                SliderGesture.End.None -> {}
            }
        }
        try {
            awaitPointerEventScope {
                var pointer: androidx.compose.ui.input.pointer.PointerId? = null
                var downX = 0f
                var downY = 0f
                var downAt = 0L
                var lastAt = 0L
                var longPress = 0L
                while (true) {
                    // A finger held still: the menu when the time is up, not when it lifts. The time counts from the press, not the last small move.
                    val e = if (gesture.holding) withTimeoutOrNull((downAt + longPress - lastAt).coerceAtLeast(0)) { awaitPointerEvent() } else awaitPointerEvent()
                    if (e == null) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        end(gesture.held())
                        continue
                    }
                    // The pointer that holds the slider, while one does; a second finger is not it.
                    val c = (if (gesture.down) e.changes.firstOrNull { it.id == pointer } else e.changes.firstOrNull()) ?: continue
                    lastAt = c.uptimeMillis
                    val at = SliderMath.level(c.position.x, if (rtl) size.width - track else 0f, track, steps, rtl)
                    when (e.type) {
                        PointerEventType.Press -> when {
                            // Another finger while the slider is held: the slider keeps its press, and the item around it gets none.
                            gesture.down -> e.changes.forEach { it.consume() }
                            // The primary button, a finger or a pen; a right or a middle click is the item's own.
                            !e.buttons.isSecondaryPressed && !e.buttons.isTertiaryPressed && !c.isConsumed -> {
                                pointer = c.id; downX = c.position.x; downY = c.position.y; downAt = c.uptimeMillis; lastAt = downAt
                                longPress = viewConfiguration.longPressTimeoutMillis // the system's setting, as it is now: a person can change it while the service runs
                                // A finger may still be about to tap or to hold: nothing is set until it says which. A touchpad's press has a button, whatever its pointer claims.
                                gesture.press(at, follows = !ClickGesture.isFinger(c.type == androidx.compose.ui.input.pointer.PointerType.Touch, e.buttons.areAnyPressed))?.let { say(it) }
                                c.consume()
                            }
                        }
                        // A pressed mouse that crosses the zone's edge arrives as Exit or Enter: it has moved all the same.
                        PointerEventType.Move, PointerEventType.Enter, PointerEventType.Exit -> if (gesture.down) {
                            if (c.pressed) {
                                val slop = viewConfiguration.touchSlop
                                gesture.move(at, kotlin.math.abs(c.position.x - downX) > slop, strayed = kotlin.math.abs(c.position.y - downY) > slop || !inside(c))?.let { say(it) }
                            }
                            c.consume()
                        }
                        PointerEventType.Release -> if (gesture.down && !c.pressed) {
                            // Taken away by the system, not lifted (such a change arrives consumed): what was set stays, nothing more is.
                            if (c.isConsumed) end(gesture.cancel())
                            else {
                                end(gesture.release(at, inside(c)))
                                c.consume()
                            }
                        }
                    }
                }
            }
        } finally {
            // Cut short (the strip went away, the item left it): what was set is the last word, and nothing holds the slider.
            end(gesture.cancel())
        }
    }
}

/**
 * Primary click, secondary click (right button or a finger held still), mouse-wheel steps and, with [onDrag], a sideways drag.
 * The rules of a press are [ClickGesture]'s, unit-tested: this only turns pointer events into its calls and what they say into
 * callbacks. A press that something inside the item has taken for itself (the slider's track, see [slides]) is not the item's,
 * and a press the system takes away, which arrives as a Release already consumed, is neither a click nor a drop.
 */
private fun Modifier.clicks(onClick: () -> Unit, onContext: () -> Unit, onScroll: ((Int) -> Unit)?,
                            onDrag: ((localX: Float, downX: Float, done: Boolean) -> Unit)? = null, onDragTakenAway: () -> Unit = {}): Modifier = composed {
    // Keep the gesture coroutine running across recompositions; call the latest callbacks.
    val click by rememberUpdatedState(onClick)
    val context by rememberUpdatedState(onContext)
    val scroll by rememberUpdatedState(onScroll)
    val drag by rememberUpdatedState(onDrag)
    val takenAway by rememberUpdatedState(onDragTakenAway)
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    pointerInput(Unit) {
        val gesture = ClickGesture(viewConfiguration.touchSlop)
        fun inside(c: androidx.compose.ui.input.pointer.PointerInputChange) = c.position.x >= 0 && c.position.y >= 0 && c.position.x <= size.width && c.position.y <= size.height
        fun act(did: ClickGesture.Did, downX: Float) = when (did) {
            ClickGesture.Did.Click -> click()
            ClickGesture.Did.Menu -> context()
            is ClickGesture.Did.Drag -> drag?.invoke(did.x, downX, false)
            is ClickGesture.Did.Drop -> drag?.invoke(did.x, downX, true)
            ClickGesture.Did.DragTakenAway -> takenAway()
            ClickGesture.Did.Nothing -> Unit
        }
        try {
            awaitPointerEventScope {
                while (true) {
                    // A finger held still is the menu when the time is up, not when it lifts.
                    val hold = gesture.holdMs
                    val e = if (hold == null) awaitPointerEvent() else withTimeoutOrNull(hold) { awaitPointerEvent() }
                    if (e == null) {
                        val did = gesture.held()
                        if (did == ClickGesture.Did.Menu) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        act(did, gesture.downX)
                        continue
                    }
                    val c = e.changes.firstOrNull()
                    when (e.type) {
                        PointerEventType.Press -> {
                            gesture.press(e.changes.any { it.isConsumed }, e.buttons.isSecondaryPressed, c?.type == PointerType.Touch, e.buttons.areAnyPressed,
                                c?.position?.x ?: 0f, c?.position?.y ?: 0f, c?.uptimeMillis ?: 0L, viewConfiguration.longPressTimeoutMillis)
                            if (gesture.armed) e.changes.forEach { it.consume() }
                        }
                        PointerEventType.Move -> if (c != null) {
                            val did = gesture.move(c.position.x, c.position.y, inside(c), c.pressed, c.isConsumed, drag != null, c.uptimeMillis)
                            if (did is ClickGesture.Did.Drag) { act(did, gesture.downX); c.consume() }
                        }
                        PointerEventType.Release -> if (c != null) {
                            val pressed = gesture.armed
                            val downX = gesture.downX
                            // Where it was let go: the pointer can travel past the last move event.
                            act(gesture.release(c.position.x, inside(c), c.isConsumed), downX)
                            if (pressed) e.changes.forEach { it.consume() }
                        }
                        PointerEventType.Scroll -> scroll?.let { onWheel ->
                            val dy = c?.scrollDelta?.y ?: 0f
                            if (dy != 0f) onWheel(if (dy < 0) 1 else -1)
                            e.changes.forEach { it.consume() }
                        }
                    }
                }
            }
        } finally {
            // Cut short (the item left the strip mid-drag): a drag in progress must not stay behind.
            act(gesture.cancel(), gesture.downX)
        }
    }
}

private fun androidx.compose.ui.geometry.Rect.toRect() = Rect(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())

/**
 * Measures [content] at its natural width and reports it, so the window can be sized exactly.
 * (A WRAP_CONTENT window is first measured at the system's dialog width, 580 dp here, and Compose
 * doesn't ask for more, which cut the strip off and slid items under the chevron.) While the
 * window catches up, the content is aligned by [bias]: -1 start, 0 centre, 1 end.
 */
@Composable
fun MeasuredStrip(bias: Float, onWidth: (Int) -> Unit, content: @Composable () -> Unit) {
    Layout(content) { measurables, constraints ->
        val p = measurables.first().measure(Constraints(minHeight = constraints.minHeight, maxHeight = constraints.maxHeight))
        onWidth(p.width)
        val w = if (constraints.hasBoundedWidth) constraints.maxWidth else p.width
        layout(w, p.height) { p.place((((w - p.width) * (1 + bias)) / 2).toInt(), 0) }
    }
}

/** A strip item's tooltip: dark, high-contrast (white on #303134 is over 12:1) on any bar. */
@Composable
fun Tooltip(label: String) {
    Box(Modifier.fillMaxHeight().clip(RoundedCornerShape(6.dp)).background(Color(0xF0303134)).padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center) {
        Text(label, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Today's day number as a small filled date badge, the size of a status bar icon: the number is cut
 * out in the bar's own color, so it reads at this size on any bar.
 */
@Composable
private fun DayBadge(day: Int, size: Dp, fg: Color, bg: Color) {
    val digits = dpFont(size * 0.68f)
    Box(Modifier.size(size + 1.dp).clip(RoundedCornerShape(4.dp)).background(fg), contentAlignment = Alignment.Center) {
        Text("$day", color = bg.copy(alpha = 1f), maxLines = 1,
            style = TextStyle(fontFamily = Fonts.bar, fontSize = digits, lineHeight = digits,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontFeatureSettings = "tnum"))
    }
}

/**
 * Option C: Concentric Dual-Track Progress Ring with centered remaining percentage number.
 * Outer ring represents weekly remaining percentage; optional inner ring represents 5-hour remaining percentage.
 */
@Composable
private fun QuotaRingBadge(ring: QuotaRing, iconDp: Dp, fg: Color, lightText: Boolean = true) {
    val boxSize = iconDp + 7.5.dp
    val pct = ring.outerPercent.coerceIn(0, 100)
    val innerPct = ring.innerPercent?.coerceIn(0, 100)
    val lowOuter = pct <= 20
    val lowInner = innerPct != null && innerPct <= 20
    val warnPink = if (lightText) Color(0xFFFF4D94) else Color(0xFFD81B60)
    val outerColor = if (lowOuter) warnPink else fg
    val innerColor = if (lowInner) warnPink else fg.copy(alpha = 0.78f)
    val fontScale = when {
        pct >= 100 -> 0.44f
        innerPct != null -> 0.50f
        else -> 0.55f
    }
    val fontSize = dpFont(iconDp * fontScale)
    Box(Modifier.size(boxSize), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(boxSize)) {
            val outerStroke = 1.9.dp.toPx()
            val outerRadius = (size.minDimension - outerStroke) / 2f
            val centerOffset = Offset(size.width / 2f, size.height / 2f)

            // Outer track & progress arc (Weekly remaining)
            drawCircle(
                color = outerColor.copy(alpha = 0.22f),
                radius = outerRadius,
                center = centerOffset,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = outerStroke)
            )
            if (pct > 0) {
                drawArc(
                    color = outerColor,
                    startAngle = -90f,
                    sweepAngle = 360f * (pct / 100f),
                    useCenter = false,
                    topLeft = Offset(centerOffset.x - outerRadius, centerOffset.y - outerRadius),
                    size = Size(outerRadius * 2f, outerRadius * 2f),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = outerStroke,
                        cap = androidx.compose.ui.graphics.StrokeCap.Round
                    )
                )
            }

            // Inner track & progress arc (5-Hour remaining)
            if (innerPct != null) {
                val innerStroke = 1.15.dp.toPx()
                val innerRadius = outerRadius - outerStroke / 2f - 1.05.dp.toPx() - innerStroke / 2f
                drawCircle(
                    color = innerColor.copy(alpha = 0.20f),
                    radius = innerRadius,
                    center = centerOffset,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = innerStroke)
                )
                if (innerPct > 0) {
                    drawArc(
                        color = innerColor,
                        startAngle = -90f,
                        sweepAngle = 360f * (innerPct / 100f),
                        useCenter = false,
                        topLeft = Offset(centerOffset.x - innerRadius, centerOffset.y - innerRadius),
                        size = Size(innerRadius * 2f, innerRadius * 2f),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(
                            width = innerStroke,
                            cap = androidx.compose.ui.graphics.StrokeCap.Round
                        )
                    )
                }
            }
        }
        Text(
            text = "$pct",
            color = outerColor,
            maxLines = 1,
            style = TextStyle(
                fontFamily = Fonts.bar,
                fontSize = fontSize,
                lineHeight = fontSize,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                fontFeatureSettings = "tnum"
            )
        )
    }
}

/** A font size that is [size] on the screen whatever the user's text size: for glyphs that are icons ([StripLook.iconDp]), not words. */
@Composable
private fun dpFont(size: Dp): TextUnit = with(androidx.compose.ui.platform.LocalDensity.current) { size.toSp() }
