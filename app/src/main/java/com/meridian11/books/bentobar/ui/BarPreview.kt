package com.meridian11.books.bentobar.ui

import android.graphics.Rect
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.bar.BarRules
import com.meridian11.books.bentobar.bar.Contrast
import com.meridian11.books.bentobar.bar.STRIP_PILL_PADDING
import com.meridian11.books.bentobar.bar.Strip
import com.meridian11.books.bentobar.bar.StripContent
import com.meridian11.books.bentobar.bar.StripEntry
import com.meridian11.books.bentobar.bar.StripEvents
import com.meridian11.books.bentobar.bar.StripLook
import com.meridian11.books.bentobar.data.BAR_ORDER
import com.meridian11.books.bentobar.data.ColorMode
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.data.Position
import com.meridian11.books.bentobar.data.Store
import com.meridian11.books.bentobar.data.areasInUse
import com.meridian11.books.bentobar.data.homeArea
import com.meridian11.books.bentobar.data.spacingDp
import com.meridian11.books.bentobar.data.stripPlan
import com.meridian11.books.bentobar.items.ItemState
import com.meridian11.books.bentobar.items.Ticker
import com.meridian11.books.bentobar.util.Dates
import com.meridian11.books.bentobar.util.Fonts
import com.meridian11.books.bentobar.util.Sym
import com.meridian11.books.bentobar.util.SymIcon
import java.time.ZonedDateTime
import kotlin.math.roundToInt
import android.content.res.Resources
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalResources
import com.meridian11.books.bentobar.data.BarConfig
import com.meridian11.books.bentobar.data.HiddenMode
import com.meridian11.books.bentobar.data.Part
import com.meridian11.books.bentobar.items.Items

/**
 * Whether the status bar is mirrored: the system's clock at the right and its icons at the left, as a status bar is
 * where the layout runs from right to left. The one place that says so. The preview draws its clock and icons by it,
 * and the words for where an area is ([whereRes]) follow it; Left and Right are sides of the screen either way, as
 * the bar's own windows are. A desktop status bar that turns out to keep its clock on the left from right to left
 * needs `false` here and nothing else.
 */
internal fun barMirrored(direction: LayoutDirection): Boolean = direction == LayoutDirection.Rtl

/**
 * The time the mock clock shows: now. Its text is as wide as the hour and the day make it, and what is left is the
 * areas' room, so a test of that room holds the time here. The Date item's format choices show the same day.
 */
internal val LocalPreviewClock = staticCompositionLocalOf<() -> ZonedDateTime> { { ZonedDateTime.now() } }

/**
 * The air the Bar page leaves between the header and the preview. The bar's shadow keeps within it, short of the
 * page's first line: the header's ground and the page's meet in one clean line.
 */
internal val PREVIEW_AIR = Space.m

/**
 * A mock status bar with the real strips in it, one for each area the layout uses, standing where the bar has them;
 * clicking an item selects it. With several areas in use each strip has its area's name on a bracket under it, the
 * selected item's in the accent; an area the bar has [noRoom] for right now has neither, and the Middle's place then
 * says who has it. Under it one quiet line: what the preview is, or [said], what a key just could not do. While
 * BentoBar is [off] the bar is one solid teal and the line says that it appears once BentoBar is on.
 *
 * While something is carried ([reorder]) the line says what a drop would do, in the strong color; an insertion bar
 * stands where the item would, and a carried row's own chip is dimmed. Nothing else moves: the strips are the Store's
 * until the drop. The three areas show as drop zones with their names under them, from the pickup with several lanes
 * on screen ([lanesSeveral]), else only while the pointer is over the bar; the room for the names is kept under the
 * line, so the preview is as tall with them as without.
 * Internal for its test.
 */
@Composable
internal fun BarPreview(states: Map<String, ItemState>, selected: String?, said: String? = null, noRoom: Set<Position> = emptySet(),
                        reorder: Reorder? = null, lanesSeveral: Boolean = false, off: Boolean = false, onSelect: (String?) -> Unit) {
    val cfg by Store.config.collectAsState()
    val res = LocalResources.current
    val density = LocalDensity.current
    val tick by Ticker.tick.collectAsState()
    var expanded by remember { mutableStateOf(false) }
    // The preview is narrower than a status bar: what it has no room for is said under it, or an item
    // that is in the list and not in the picture looks lost.
    // Each area's own, as each of the bar's strips has its own.
    val dropped = remember { mutableStateMapOf<Position, Set<String>>() }
    // The areas the bar has a strip for, in the order it gives them room: with every item in one area, that one.
    val wanted = BarRules.wantedAreas(cfg.homeArea(), cfg.areasInUse())
    val several = wanted.size > 1
    val areas = if (several) wanted.filter { it !in noRoom } else wanted
    // Where each strip stands in the bar, for the brackets under it.
    var spans by remember { mutableStateOf(emptyMap<Position, IntRange>()) }
    var origin by remember { mutableIntStateOf(0) }
    val barAt = remember { arrayOfNulls<LayoutCoordinates>(1) }
    // An area that left the layout takes what it had no room for with it: back later, it starts as a new strip does.
    SideEffect { dropped.keys.retainAll(areas.toSet()) }
    // What the bar shows, by the same function: the preview makes no choice of its own.
    val plans = areas.associateWith { cfg.stripPlan(it, { item -> states[item.id]?.active == true }, expanded, !dropped[it].isNullOrEmpty()) }
    val more = areas.flatMapTo(HashSet()) { dropped[it].orEmpty() }
    val entries = { list: List<ItemConfig> -> list.map { StripEntry(it, states[it.id] ?: Ticker.placeholder(it)) } }
    // The preview uses the strip's real colours: the sampled bar when BentoBar is running and the
    // colour is automatic, else the forced Light or Dark choice.
    val live by com.meridian11.books.bentobar.bar.BarLook.current.collectAsState()
    val bar = when (cfg.color) {
        ColorMode.LIGHT -> Contrast.resolve(Color.White, null)
        ColorMode.DARK -> Contrast.resolve(Contrast.DARK_TEXT, null)
        ColorMode.AUTO -> live ?: Contrast.resolve(Color.White, null)
    }
    // While BentoBar is off there is no bar of its own to show: the preview is the window's own teal, in both themes.
    val teal = Bento.colors.stripOff
    val look = if (off) StripLook(Bento.colors.stripOffInk, true, cfg.textSize, cfg.spacingDp.dp, cfg.pill, teal)
        else StripLook(bar.fg, bar.barDark, cfg.textSize, cfg.spacingDp.dp, cfg.pill, bar.background)
    val barBrush = when {
        off -> androidx.compose.ui.graphics.SolidColor(teal)
        bar.opaque -> androidx.compose.ui.graphics.SolidColor(bar.background)
        bar.barDark -> Brush.horizontalGradient(listOf(Color(0xFF5B6F8A), Color(0xFF3F6F73), Color(0xFF4F7E78)))
        else -> Brush.horizontalGradient(listOf(Color(0xFFDDE6F3), Color(0xFFD5E8E6), Color(0xFFE3EEE9)))
    }
    val select by androidx.compose.runtime.rememberUpdatedState(onSelect)
    val drags by androidx.compose.runtime.rememberUpdatedState(reorder)
    SideEffect {
        reorder?.look = look
        // The chip a carried row becomes stands on one plain color of the bar.
        reorder?.ground = if (off) teal else if (bar.opaque) bar.background else if (bar.barDark) Color(0xFF3F6F73) else Color(0xFFD5E8E6)
    }
    val carried = reorder?.carried
    val target = (reorder?.drop as? Drop.At)?.spot?.zone?.area?.takeIf { carried != null }
    val zonesUp = reorder != null && carried != null && reorder.live && (lanesSeveral || reorder.inPreview)
    // One object for the page's life, as the bar's own events are: rebuilt each time, every item would redraw on every tick.
    val events = remember { object : StripEvents {
        override fun click(item: ItemConfig, at: Rect) = select(item.id)
        override fun context(item: ItemConfig, at: Rect) = select(item.id)
        // The picture only selects: a wheel over it must not start a timer that later rings, or change the volume.
        override fun scroll(item: ItemConfig, steps: Int) {}
        // A slider is only drawn here: a click on it selects the item, like a click anywhere on an item.
        override val slidable: Boolean get() = false
        // Nor can an item be picked up: Sections' rows are where the order changes.
        override val draggable: Boolean get() = false
        // Nor does an item fade in: the picture is whole in the window's first frame, and an item added later is simply there.
        override val appearing: Boolean get() = false
        override fun chevron(at: Rect) { expanded = !expanded }
        override fun chevronContext(at: Rect) { expanded = !expanded }
        override fun hover(inside: Boolean) {}
        // Where each chip stands, for the insertion bar of a drag.
        override fun placed(id: String, at: Rect) { drags?.chips?.put(id, at) }
        override fun gone(id: String) { drags?.chips?.remove(id) }
    } }
    Column(Modifier.testTag("preview")) {
        BoxWithConstraints(
            Modifier.fillMaxWidth().height(52.dp).onGloballyPositioned { barAt[0] = it; reorder?.previewBar = it }
                .softShadow(Bento.colors.dark, Radius.large, above = PREVIEW_AIR - 1.dp).clip(RoundedCornerShape(Radius.large))
                .background(barBrush),
        ) {
            // The strip may take what the mock clock and system icons leave (about 380 dp go to them and the gaps).
            val maxPx = with(LocalDensity.current) { (maxWidth - 380.dp).coerceAtLeast(200.dp).roundToPx() }
            // The stand-ins for the system's clock and icons are a drawing: they keep their size at a larger text size,
            // where the bar in a narrow window would have no room for them.
            val clockSize = with(LocalDensity.current) { 14.dp.toSp() }
            val iconsSize = with(LocalDensity.current) { 13.dp.toSp() }
            val clock = LocalPreviewClock.current
            val now = remember(tick, clock) { clock() }
            // The system's 12/24-hour setting and the locale's own short date, like the real status bar.
            val h24 = android.text.format.DateFormat.is24HourFormat(LocalContext.current)
            val mirrored = barMirrored(LocalLayoutDirection.current)
            // The bar's own direction, which is not the window's by itself: see [barMirrored].
            CompositionLocalProvider(LocalLayoutDirection provides if (mirrored) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                Row(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(Dates.format(Dates.timeSkeleton(h24), now) + "   " + Dates.format("EEEMMMd", now), Modifier.testTag("preview-clock").onGloballyPositioned { reorder?.clock = it }, color = look.fg,
                        fontFamily = Fonts.bar, fontWeight = Fonts.barWeight, fontSize = clockSize,
                        // A time and a date read from left to right wherever the clock stands.
                        style = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr))
                    // One area's strip, as the bar composes each of its own: the area's plan, its ‹ on the area's side, and what
                    // it has no room for kept as the area's. [budget] is what its items may take.
                    // An area the layout has just lost still has its place in [PreviewAreas] until that is measured again, and
                    // is composed once more before then, with this layout: there is no plan for it, and it composes nothing.
                    val strip: @Composable (Position, Int) -> Unit = { area, budget ->
                        plans[area]?.let { plan ->
                            // One node, as the strip is: a drag asks it where the area stands.
                            Box(Modifier.testTag("strip:$area").onGloballyPositioned { reorder?.strips?.put(area, it) }) {
                                Strip(entries(plan.visible), entries(plan.revealed), plan.chevron,
                                    chevronAlways = plan.chevronAlways, chevronReservePx = 0, expanded = plan.open,
                                    chevronOnLeft = area != Position.LEFT, look = look, maxWidthPx = budget, heightDp = 52.dp, events = events,
                                    onOverflow = { dropped[area] = it })
                            }
                        }
                    }
                    val only = areas.singleOrNull()
                    if (only != null && !several) {
                        // A layout in one area is the one strip between the spacers it always had. The area keeps its side of
                        // the screen: mirrored, it is Right that stands beside the clock, where the row starts.
                        val beside = only == if (mirrored) Position.RIGHT else Position.LEFT
                        if (beside) Spacer(Modifier.width(16.dp)) else Spacer(Modifier.weight(1f))
                        strip(only, maxPx)
                        if (beside || only == Position.CENTER) Spacer(Modifier.weight(1f))
                    } else {
                        // The bar's rule for an empty strip, asked here as there: an area that draws nothing is not in the
                        // picture, and the empty capsule is the home area's while no other area draws.
                        fun draws(area: Position, home: Boolean) = plans.getValue(area).let {
                            StripContent.drawn(entries(it.visible) + entries(it.revealed), it.chevron, look.pill, home)
                        }
                        val drawing = areas.filterTo(HashSet()) { area ->
                            draws(area, home = area == cfg.homeArea() && areas.none { it != area && draws(it, home = false) })
                        }
                        val pill: (@Composable () -> Unit)? = if (Position.CENTER in noRoom && Position.CENTER in wanted) { { SystemPill(look.fg) } } else null
                        PreviewAreas(areas, drawing, maxPx, Modifier.weight(1f).onGloballyPositioned { at ->
                            barAt[0]?.takeIf { it.isAttached }?.let { origin = it.localPositionOf(at, Offset.Zero).x.roundToInt() }
                        }, pill, {
                            if (spans != it) spans = it
                            // A strip that was not placed has no place to ask about.
                            reorder?.strips?.keys?.retainAll(it.keys)
                        }, strip)
                    }
                    Spacer(Modifier.width(12.dp))
                    Row(Modifier.testTag("preview-icons").onGloballyPositioned { reorder?.icons = it }, horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("US", color = look.fg, fontFamily = Fonts.bar, fontWeight = Fonts.barWeight, fontSize = iconsSize)
                        SymIcon(Sym.NOTIFICATIONS, size = 18.sp, color = look.fg)
                        SymIcon(Sym.WIFI, size = 18.sp, color = look.fg)
                        SymIcon(Sym.BATTERY_FULL, size = 18.sp, color = look.fg)
                    }
                }
            }
            if (reorder != null && carried != null) {
                if (zonesUp) Zones(reorder.areas, cfg.areasInUse(), target, look.fg, Modifier.matchParentSize())
                val gap = with(density) { cfg.spacingDp.dp.toPx() }
                Box(Modifier.matchParentSize().drawBehind {
                    val here = barAt[0]?.takeIf { it.isAttached } ?: return@drawBehind
                    fun stretch(r: Rect) = here.windowToLocal(Offset(r.left.toFloat(), r.top.toFloat())).let { it.x..it.x + r.width() }
                    // A carried row's own chip stays where it is until the drop, dimmed.
                    if (carried.from == From.BAR) reorder.chips[carried.id]?.let { r ->
                        val at = stretch(r)
                        clipRect(at.start, 0f, at.endInclusive, size.height) { drawRect(barBrush, alpha = 0.65f) }
                    }
                    reorder.insertion = null
                    val drop = reorder.drop as? Drop.At ?: return@drawBehind
                    val area = drop.spot.zone.area
                    val zone = reorder.areas[area] ?: return@drawBehind
                    // The area as the bar would draw it after the drop: the bar stands before the item's next neighbour that has a chip.
                    val plan = cfg.dropped(carried, drop).stripPlan(area, { item -> states[item.id]?.active == true }, expanded, false)
                    val order = (if (area == Position.LEFT) plan.visible + plan.revealed else plan.revealed + plan.visible).map { it.id }
                    if (carried.id !in order) return@drawBehind
                    val chips = buildMap { order.forEach { id -> if (id != carried.id) reorder.chips[id]?.let { put(id, stretch(it)) } } }
                    // In an area with nothing drawn the bar stands inside the zone's outline, not on it.
                    val x = insertionX(order, carried.id, chips, zone, area, gap).let {
                        if (chips.isEmpty()) it.coerceIn(zone.start + 10.dp.toPx(), maxOf(zone.start + 10.dp.toPx(), zone.endInclusive - 10.dp.toPx())) else it
                    }
                    val tall = 24.dp.toPx()
                    drawRoundRect(look.fg, Offset(x - 1.dp.toPx(), (size.height - tall) / 2), Size(2.dp.toPx(), tall), CornerRadius(1.dp.toPx()))
                    reorder.container?.takeIf { it.isAttached }?.let { root ->
                        reorder.insertion = root.windowToLocal(here.localToWindow(Offset(x + 2.dp.toPx(), (size.height - CHIP_HEIGHT.toPx()) / 2)))
                    }
                }.testTag("insertion"))
            }
        }
        if (zonesUp) Brackets(reorder.areas.mapValues { (_, r) -> r.start.roundToInt()..r.endInclusive.roundToInt() }, 0, target)
        else if (several) Brackets(spans.filterKeys { it in areas }, origin, cfg.items.firstOrNull { it.id == selected }?.area)
        val hint = stringResource(if (off) R.string.bar_preview_hint_off else if (plans.values.any { it.hidden.isNotEmpty() }) R.string.bar_preview_hint_hidden
            else R.string.bar_preview_hint)
        val words = if (reorder != null && carried != null) dropWords(res, cfg, carried, reorder.drop, lanesSeveral, reorder.touch) else null
        val line = words ?: said ?: if (more.isEmpty()) hint else hint + " " + pluralStringResource(R.plurals.bar_preview_more, more.size, more.size)
        Text(line, style = if (words != null) Bento.type.help.copy(fontWeight = FontWeight.SemiBold) else Bento.type.help,
            color = if (words != null) Bento.colors.ink else Bento.colors.ink2, maxLines = lines(2),
            // As far under the areas' names as under a bar without them: a line of its own, not a second line of theirs.
            modifier = Modifier.padding(start = 4.dp, top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite }
                .said(arrowInWords(line)).testTag("preview-line"))
        // The room the areas' names take while a drag shows them: kept, so that nothing under the preview moves then.
        if (!several && !zonesUp) Spacer(Modifier.height(BRACKETS))
    }
}

/** The height of the chip a carried row becomes over the preview, and of the brackets' line under it. */
internal val CHIP_HEIGHT = 36.dp
private val BRACKETS = 20.dp

/**
 * The areas as drop zones over the preview's bar: an area in use has a hairline around its strip, one that is not a
 * dashed box where it would start, and the [target] of the drop a strong outline on a light tint. [areas] are their
 * stretches along the bar.
 */
@Composable
private fun Zones(areas: Map<Position, ClosedFloatingPointRange<Float>>, inUse: Set<Position>, target: Position?, fg: Color, modifier: Modifier) {
    val order = BAR_ORDER.filter { it in areas }
    Layout({
        order.forEach { area ->
            Box(Modifier.testTag("zone:$area").drawBehind {
                val round = CornerRadius(Radius.medium.toPx())
                if (area == target) {
                    drawRoundRect(fg.copy(alpha = 0.12f), cornerRadius = round)
                    drawRoundRect(fg, cornerRadius = round, style = Stroke(1.5.dp.toPx()))
                } else drawRoundRect(fg.copy(alpha = 0.5f), cornerRadius = round,
                    style = Stroke(1.dp.toPx(), pathEffect = if (area in inUse) null else PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))))
            })
        }
    }, modifier) { measurables, cs ->
        val tall = 40.dp.roundToPx()
        val placed = measurables.mapIndexed { i, m ->
            val at = areas.getValue(order[i])
            // A strip's outline stands a little off its items.
            val reach = if (order[i] in inUse) 4.dp.roundToPx() else 0
            m.measure(Constraints.fixed((at.endInclusive - at.start).roundToInt().coerceAtLeast(0) + 2 * reach, tall)) to at.start.roundToInt() - reach
        }
        layout(cs.maxWidth, cs.maxHeight) { placed.forEach { (p, x) -> p.place(x, (cs.maxHeight - tall) / 2) } }
    }
}

/**
 * The line under the preview while [c] is carried: what letting go right now would do ([drop]), naming the row it would
 * stand beside, and with [several] lanes on screen the area. A finger is told how it cancels ([touch]).
 */
internal fun dropWords(res: Resources, cfg: BarConfig, c: Carried, drop: Drop, several: Boolean, touch: Boolean): String {
    val item = cfg.items.firstOrNull { it.id == c.id }
    val name = if (c.from == From.TYPE || item == null) Items.of(c.type)?.let { res.getString(it.titleRes) }.orEmpty() else itemName(res, item)
    val line = cfg.dropLine(c, drop, several)
    fun area(of: Position) = res.getString(areaRes(of))
    fun text(pointer: Int, finger: Int, vararg args: Any) = res.getString(if (touch) finger else pointer, *args)
    val beside = line.beside?.let { id -> cfg.items.firstOrNull { it.id == id } }?.let { itemName(res, it) }.orEmpty()
    val there = if (line.area != null && line.beside != null) res.getString(R.string.areas_drop_in, beside, area(line.area)) else beside
    return when (line.says) {
        Says.MOVE_AFTER -> text(R.string.bar_drop_move, R.string.bar_drop_move_touch, name, there)
        Says.MOVE_BEFORE -> text(R.string.bar_drop_move_first, R.string.bar_drop_move_first_touch, name, there)
        Says.ADD_AFTER -> text(R.string.bar_drop_add, R.string.bar_drop_add_touch, name, there)
        Says.ADD_BEFORE -> text(R.string.bar_drop_add_first, R.string.bar_drop_add_first_touch, name, there)
        Says.MOVE_TO, Says.ADD_TO -> {
            val add = line.says == Says.ADD_TO
            val to = line.area ?: cfg.homeArea()
            when {
                line.part == Part.LOWER -> {
                    val who = if (several) res.getString(R.string.areas_drop_in, name, area(to)) else name
                    if (cfg.hiddenMode == HiddenMode.SHOW_ALL) text(R.string.bar_drop_when_active, R.string.bar_drop_when_active_touch, who)
                    else text(R.string.bar_drop_behind, R.string.bar_drop_behind_touch, who)
                }
                several -> if (add) text(R.string.areas_drop_add_to, R.string.areas_drop_add_to_touch, name, area(to))
                    else text(R.string.areas_drop_move_to, R.string.areas_drop_move_to_touch, name, area(to))
                add -> text(R.string.bar_drop_add_to_bar, R.string.bar_drop_add_to_bar_touch, name)
                else -> text(R.string.bar_drop_always, R.string.bar_drop_always_touch, name)
            }
        }
        Says.STARTS -> text(R.string.areas_drop_starts, R.string.areas_drop_starts_touch, name, area(line.area ?: cfg.homeArea()))
        Says.OFF -> text(R.string.bar_drop_off, R.string.bar_drop_off_touch, name)
        Says.NO_RULE -> res.getString(R.string.visibility_no_rule_drop, name)
        Says.NO_RULE_ADD -> res.getString(R.string.bar_drop_no_rule_add, name)
        Says.NONE -> res.getString(R.string.bar_drop_none)
    }
}

/**
 * The strips of a layout in several areas, in the room between the mock clock and the mock system icons: where the bar
 * has them, by the bar's own rule of room ([BarRules.lane], [BarRules.middleLeft]). Left has the room first, then Right,
 * then the Middle; Right stands at the right end, Left at the left end and the Middle in the middle, moved only as far as
 * a neighbour makes it. They are sides of the screen, so the picture is not mirrored around them in a right to left
 * language. [areas] are in that order of room; [drawing] are the ones that put anything on the screen; [strip] composes
 * an area's strip whose items may take the width it is given, never more than [maxPx].
 *
 * A strip's items get its room less the strip's two ends and the room of a ‹, whether it has a ‹ or not: the bar keeps
 * that room only once a ‹ is needed, and the picture is steadier for keeping it always. An area with no room for a ‹ is
 * not drawn (the bar hands its items to another strip's ‹; here they are in the count under the picture), and one that
 * draws nothing takes no room.
 *
 * [pill], if there is one, stands for a Middle the bar has no room for: it fills what Left and Right leave, where that
 * is enough for it. [onSpans] hears where each strip was placed, from its left edge to its right one.
 */
@Composable
private fun PreviewAreas(areas: List<Position>, drawing: Set<Position>, maxPx: Int, modifier: Modifier, pill: (@Composable () -> Unit)?,
                         onSpans: (Map<Position, IntRange>) -> Unit, strip: @Composable (Position, Int) -> Unit) {
    // Composed while measuring: a strip's budget comes from what the areas before it measured.
    SubcomposeLayout(modifier) { constraints ->
        val width = constraints.maxWidth
        val rtl = layoutDirection == LayoutDirection.Rtl
        // 16 dp stay free beside the clock, as they always did before a strip on the left.
        val beside = 16.dp.roundToPx()
        val lo = if (rtl) 0 else beside
        val hi = if (rtl) width - beside else width
        val ends = STRIP_PILL_PADDING.roundToPx()
        val chevron = 30.dp.roundToPx()
        var usedRight = 0
        var usedLeft = 0
        val placed = areas.mapNotNull { area ->
            val lane = BarRules.lane(area, lo, hi, between = 6.dp.roundToPx(), usedRight, usedLeft)
            val room = lane.max >= chevron + 2 * ends
            // With no room it is still composed, on no budget: what it would show is what the picture has no room for.
            val budget = if (room) minOf(maxPx, lane.max - chevron - 2 * ends) else 0
            // An area whose strip composed nothing has nothing to place.
            val p = subcompose(area) { strip(area, budget) }.singleOrNull()?.measure(Constraints(maxWidth = maxOf(0, lane.max), maxHeight = constraints.maxHeight))
            if (p == null || !room || area !in drawing || p.width == 0) return@mapNotNull null
            val x = when (area) {
                Position.RIGHT -> hi - p.width
                Position.LEFT -> lo
                Position.CENTER -> {
                    // Centred where a layout in the middle is: the page's Row gives an odd pixel to the second of its two spacers.
                    val half = (width - p.width) / 2
                    val natural = if (rtl) width - p.width - half else half
                    // With no neighbour it has nothing to clear and stays there.
                    if (usedRight == 0 && usedLeft == 0) natural else BarRules.middleLeft(lane, p.width, natural)
                }
            }
            if (area == Position.RIGHT) usedRight = p.width else if (area == Position.LEFT) usedLeft = p.width
            Triple(area, p, x)
        }
        onSpans(placed.associate { (area, p, x) -> area to (x until x + p.width) })
        // The brackets reach past their strips, so the pill keeps clear of both by more than the strips do of each other.
        val stand = pill?.let {
            val lane = BarRules.lane(Position.CENTER, lo, hi, between = 14.dp.roundToPx(), usedRight, usedLeft)
            val m = subcompose("pill", it).singleOrNull() ?: return@let null
            if (m.minIntrinsicWidth(constraints.maxHeight) > lane.max) null else m.measure(Constraints.fixedWidth(lane.max).copy(maxHeight = constraints.maxHeight)) to lane.left
        }
        val height = maxOf(placed.maxOfOrNull { it.second.height } ?: 0, stand?.first?.height ?: 0)
        layout(width, height) {
            placed.forEach { (_, p, x) -> p.place(x, (height - p.height) / 2) }
            stand?.let { (p, x) -> p.place(x, (height - p.height) / 2) }
        }
    }
}

/** In the place of a Middle the bar has no room for: the system has that part of the bar right now. */
@Composable
private fun SystemPill(fg: Color) {
    Box(Modifier.heightIn(min = 32.dp).background(fg.copy(alpha = 0.1f), RoundedCornerShape(Radius.medium)).padding(horizontal = Space.m).testTag("system-pill"),
        contentAlignment = Alignment.Center) {
        Text(stringResource(R.string.areas_system_now), style = Bento.type.help, color = fg.copy(alpha = 0.75f), maxLines = 1)
    }
}

/**
 * Where the brackets stand in a bar [room] wide. Each is its strip's span, [from] to [to], and never narrower than its
 * word with a short line on each side ([need]): a wider one stands around its strip's middle. Two that would meet
 * then give way by the same amount and keep [apart], and none leaves the bar. Pure.
 */
internal fun bracketPlaces(from: IntArray, to: IntArray, need: IntArray, apart: Int, room: Int): List<IntRange> {
    val n = from.size
    val wide = IntArray(n) { maxOf(to[it] - from[it], need[it], 0) }
    val x = IntArray(n) { from[it] - (wide[it] - (to[it] - from[it]).coerceAtLeast(0)) / 2 }
    for (i in 1 until n) {
        val over = x[i - 1] + wide[i - 1] + apart - x[i]
        if (over > 0) { x[i - 1] -= over / 2; x[i] += over - over / 2 }
    }
    // The bar's ends, and three in a row: whoever is pushed pushes on.
    for (i in 0 until n) x[i] = maxOf(x[i], if (i == 0) 0 else x[i - 1] + wide[i - 1] + apart)
    for (i in n - 1 downTo 0) x[i] = maxOf(0, minOf(x[i], if (i == n - 1) room - wide[i] else x[i + 1] - apart - wide[i]))
    return List(n) { x[it]..x[it] + wide[it] }
}

/**
 * The areas' names under the bar, each on a bracket as wide as its strip and a little more: which part of the bar is
 * which area. [spans] are the strips' places in the room that starts [origin] into the bar; [chosen] is the selected
 * item's area, whose bracket takes the accent. They are sides of the screen, so they are not mirrored.
 */
@Composable
private fun Brackets(spans: Map<Position, IntRange>, origin: Int, chosen: Position?) {
    val c = Bento.colors
    val order = BAR_ORDER.filter { it in spans }
    Layout({
        order.forEach { area ->
            val tint = if (area == chosen) c.accent else c.ink3
            val line = if (area == chosen) c.accent else c.ink3.copy(alpha = 0.55f)
            // Read out as "Left area": the name alone, under the bar, would be a word with nothing around it.
            val spoken = stringResource(R.string.areas_bracket_spoken, stringResource(areaRes(area)))
            Row(Modifier.testTag("bracket:$area").clearAndSetSemantics { contentDescription = spoken }, verticalAlignment = Alignment.CenterVertically) {
                // A line is never shorter than this: it is what a bracket's own least width counts on each side of its word.
                Box(Modifier.width(1.dp).height(7.dp).background(line))
                Box(Modifier.weight(1f).widthIn(min = 6.dp).height(1.dp).background(line))
                Text(stringResource(areaRes(area)), Modifier.padding(horizontal = 6.dp), style = Bento.type.help.copy(fontWeight = if (area == chosen) FontWeight.SemiBold else FontWeight.Medium),
                    color = tint, maxLines = 1, overflow = TextOverflow.Clip)
                Box(Modifier.weight(1f).widthIn(min = 6.dp).height(1.dp).background(line))
                Box(Modifier.width(1.dp).height(7.dp).background(line))
            }
        }
    }, Modifier.fillMaxWidth().heightIn(min = BRACKETS)) { measurables, cs ->
        val reach = 6.dp.roundToPx()
        val apart = 2.dp.roundToPx()
        val from = IntArray(order.size) { origin + spans.getValue(order[it]).first - reach }
        val to = IntArray(order.size) { origin + spans.getValue(order[it]).last + 1 + reach }
        // Two that would meet share the room between their strips.
        for (i in 1 until order.size) if (to[i - 1] + apart > from[i]) {
            val mid = (to[i - 1] + from[i]) / 2
            to[i - 1] = mid - apart / 2
            from[i] = mid + apart - apart / 2
        }
        // A name wider than its strip keeps its letters and a short line beside them: its bracket reaches past the
        // strip on both sides ([bracketPlaces]). A name is as tall as its text size makes it; at a larger one the line
        // under the bar grows with it.
        val places = bracketPlaces(from, to, IntArray(order.size) { measurables[it].minIntrinsicWidth(Constraints.Infinity) }, apart, cs.maxWidth)
        val placed = measurables.mapIndexed { i, m -> m.measure(Constraints.fixedWidth(places[i].last - places[i].first)) }
        val tall = maxOf(16.dp.roundToPx(), placed.maxOfOrNull { it.height } ?: 0)
        layout(cs.maxWidth, maxOf(cs.minHeight, 4.dp.roundToPx() + tall)) {
            placed.forEachIndexed { i, p -> p.place(places[i].first, 4.dp.roundToPx() + (tall - p.height) / 2) }
        }
    }
}
