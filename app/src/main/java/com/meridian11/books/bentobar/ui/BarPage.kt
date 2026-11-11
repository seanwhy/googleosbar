package com.meridian11.books.bentobar.ui

import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.SystemClock
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import com.meridian11.books.bentobar.items.Trigger
import kotlin.math.roundToInt
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.bar.Strip
import com.meridian11.books.bentobar.bar.StripEntry
import com.meridian11.books.bentobar.bar.StripEvents
import com.meridian11.books.bentobar.bar.StripLook
import com.meridian11.books.bentobar.bar.Contrast
import com.meridian11.books.bentobar.data.ColorMode
import com.meridian11.books.bentobar.data.Display
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.data.Position
import com.meridian11.books.bentobar.data.Section
import com.meridian11.books.bentobar.data.HiddenMode
import com.meridian11.books.bentobar.data.Store
import com.meridian11.books.bentobar.data.hide
import com.meridian11.books.bentobar.data.putIn
import com.meridian11.books.bentobar.data.setWhenActive
import com.meridian11.books.bentobar.data.show
import com.meridian11.books.bentobar.data.turnOff
import com.meridian11.books.bentobar.data.spacingDp
import com.meridian11.books.bentobar.data.stripPlan
import com.meridian11.books.bentobar.items.ItemState
import com.meridian11.books.bentobar.items.Items
import com.meridian11.books.bentobar.items.Ticker
import com.meridian11.books.bentobar.util.Dates
import com.meridian11.books.bentobar.util.Fonts
import com.meridian11.books.bentobar.util.Sym
import com.meridian11.books.bentobar.util.SymIcon
import java.time.ZonedDateTime

@Composable
fun BarPage(running: Boolean, selected: String?, onSelect: (String?) -> Unit, onSetup: () -> Unit) {
    val cfg by Store.config.collectAsState()
    val states by Ticker.states.collectAsState()
    val item = cfg.items.firstOrNull { it.id == selected }
    val deleted by Undo.deleted.collectAsState()
    val res = androidx.compose.ui.platform.LocalResources.current
    LaunchedEffect(deleted) {
        val d = deleted ?: return@LaunchedEffect
        val name = Items.of(d.item.type)?.title ?: res.getString(R.string.bar_deleted_fallback)
        if (!Undo.offered(d, SystemClock.elapsedRealtime())) { Undo.deleted.compareAndSet(d, null); return@LaunchedEffect }
        // The window's one snackbar, like every other message. Forgotten once it has had its time, not when the page goes
        // first (a recreated window offers it again, within [Undo.offered]'s time).
        Notice.post(res.getString(R.string.bar_deleted, name), res.getString(R.string.bar_undo), onAction = { Undo.restore(d) }, onGone = { Undo.deleted.compareAndSet(d, null) })
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 900.dp
        val listScroll = rememberScrollState()
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 8.dp)) {
            if (!running) SetupBanner(onSetup)
            BarPreview(states, selected, onSelect)
            Spacer(Modifier.height(16.dp))
            if (wide) {
                Row(Modifier.weight(1f)) {
                    Column(Modifier.width(470.dp).fillMaxHeight().verticalScroll(listScroll)) {
                        Sections(cfg.items, states, selected, onSelect, listScroll)
                    }
                    Spacer(Modifier.width(20.dp))
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        if (item != null) Column(Modifier.verticalScroll(rememberScrollState())) { ItemDetail(item, states[item.id], onSelect) }
                        else Placeholder()
                    }
                }
            } else {
                Column(Modifier.weight(1f).verticalScroll(if (item != null) rememberScrollState() else listScroll)) {
                    if (item != null) {
                        TextButton(onClick = { onSelect(null) }) { SymIcon(Sym.ARROW_BACK, size = 18.sp); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.bar_all_items)) }
                        ItemDetail(item, states[item.id], onSelect)
                    } else Sections(cfg.items, states, selected, onSelect, listScroll)
                }
            }
        }
    }
}

@Composable
private fun SetupBanner(onSetup: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            SymIcon(Sym.INFO, size = 24.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.bar_banner_title), style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(stringResource(R.string.bar_banner_text),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            FilledTonalButton(onClick = onSetup) { Text(stringResource(R.string.bar_banner_set_up)) }
        }
    }
}

@Composable
private fun Placeholder() {
    Column(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        SymIcon(Sym.TOUCH_APP, size = 40.sp, color = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.bar_placeholder_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.bar_placeholder_text), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A mock status bar with the real strip in it; clicking an item selects it. */
@Composable
private fun BarPreview(states: Map<String, ItemState>, selected: String?, onSelect: (String?) -> Unit) {
    val cfg by Store.config.collectAsState()
    val tick by Ticker.tick.collectAsState()
    var expanded by remember { mutableStateOf(false) }
    // The preview is narrower than a status bar: what it has no room for is said under it, or an item
    // that is in the list and not in the picture looks lost.
    var dropped by remember { mutableStateOf(emptySet<String>()) }
    // What the bar shows, by the same function: the preview makes no choice of its own.
    val plan = cfg.stripPlan({ states[it.id]?.active == true }, expanded, dropped.isNotEmpty())
    val entries = { list: List<ItemConfig> -> list.map { StripEntry(it, states[it.id] ?: Ticker.placeholder(it)) } }
    // The preview uses the strip's real colours: the sampled bar when BentoBar is running and the
    // colour is automatic, else the forced Light or Dark choice.
    val live by com.meridian11.books.bentobar.bar.BarLook.current.collectAsState()
    val bar = when (cfg.color) {
        ColorMode.LIGHT -> Contrast.resolve(Color.White, null)
        ColorMode.DARK -> Contrast.resolve(Contrast.DARK_TEXT, null)
        ColorMode.AUTO -> live ?: Contrast.resolve(Color.White, null)
    }
    val look = StripLook(bar.fg, bar.barDark, cfg.textSize, cfg.spacingDp.dp, cfg.pill, bar.background)
    val barBrush = when {
        bar.opaque -> androidx.compose.ui.graphics.SolidColor(bar.background)
        bar.barDark -> Brush.horizontalGradient(listOf(Color(0xFF5B6F8A), Color(0xFF3F6F73), Color(0xFF4F7E78)))
        else -> Brush.horizontalGradient(listOf(Color(0xFFDDE6F3), Color(0xFFD5E8E6), Color(0xFFE3EEE9)))
    }
    val select by androidx.compose.runtime.rememberUpdatedState(onSelect)
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
        override fun chevron(at: Rect) { expanded = !expanded }
        override fun chevronContext(at: Rect) { expanded = !expanded }
        override fun hover(inside: Boolean) {}
        override fun placed(id: String, at: Rect) {}
    } }
    Column {
        BoxWithConstraints(
            Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(18.dp))
                .background(barBrush),
        ) {
            // The strip may take what the mock clock and system icons leave (about 380 dp go to them and the gaps).
            val maxPx = with(LocalDensity.current) { (maxWidth - 380.dp).coerceAtLeast(200.dp).roundToPx() }
            val now = remember(tick) { ZonedDateTime.now() }
            // The system's 12/24-hour setting and the locale's own short date, like the real status bar.
            val h24 = android.text.format.DateFormat.is24HourFormat(LocalContext.current)
            Row(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(Dates.format(Dates.timeSkeleton(h24), now) + "   " + Dates.format("EEEMMMd", now), color = look.fg,
                    fontFamily = Fonts.bar, fontWeight = Fonts.barWeight, fontSize = 14.sp)
                if (cfg.position == Position.LEFT) Spacer(Modifier.width(16.dp)) else Spacer(Modifier.weight(1f))
                Strip(entries(plan.visible), entries(plan.revealed), plan.chevron,
                    chevronAlways = plan.chevronAlways, chevronReservePx = 0, expanded = plan.open,
                    chevronOnLeft = cfg.position != Position.LEFT, look = look, maxWidthPx = maxPx, heightDp = 52.dp, events = events,
                    onOverflow = { dropped = it })
                if (cfg.position == Position.LEFT) Spacer(Modifier.weight(1f)) else if (cfg.position == Position.CENTER) Spacer(Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("US", color = look.fg, fontFamily = Fonts.bar, fontWeight = Fonts.barWeight, fontSize = 13.sp)
                    SymIcon(Sym.NOTIFICATIONS, size = 18.sp, color = look.fg)
                    SymIcon(Sym.WIFI, size = 18.sp, color = look.fg)
                    SymIcon(Sym.BATTERY_FULL, size = 18.sp, color = look.fg)
                }
            }
        }
        val hint = stringResource(if (plan.hidden.isNotEmpty()) R.string.bar_preview_hint_hidden else R.string.bar_preview_hint)
        Text(if (dropped.isEmpty()) hint else hint + " " + pluralStringResource(R.plurals.bar_preview_more, dropped.size, dropped.size),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 6.dp, top = 6.dp))
    }
}

@Composable
private fun Sections(items: List<ItemConfig>, states: Map<String, ItemState>, selected: String?, onSelect: (String?) -> Unit, scroll: ScrollState) {
    val cfg by Store.config.collectAsState()
    val reorder = remember { Reorder() }
    val scope = rememberCoroutineScope()
    SideEffect { reorder.items = items }
    // The Store's new order has arrived (or something else changed it): stop previewing the drop.
    LaunchedEffect(items) { reorder.pending = null }
    // While dragging: scroll when the pointer nears the top or bottom edge, and keep the opening under it.
    val density = LocalDensity.current
    val edge = with(density) { 56.dp.toPx() }
    val maxStep = with(density) { 14.dp.toPx() }
    LaunchedEffect(reorder.dragging) {
        if (reorder.dragging == null) return@LaunchedEffect
        while (true) {
            withFrameNanos { }
            val v = reorder.edgeSpeed(edge, maxStep)
            if (v != 0f) scroll.scrollBy(v)
            reorder.sync()
        }
    }
    val order = reorder.order(items)
    // Says how hidden items come back with the current settings (hover reveal is off by default).
    val showAll = cfg.hiddenMode == HiddenMode.SHOW_ALL
    val reveal = stringResource(when (cfg.hiddenMode) {
        HiddenMode.HOVER -> R.string.section_hidden_reveal_both
        HiddenMode.CLICK -> R.string.section_hidden_reveal_chevron
        HiddenMode.SHOW_ALL -> R.string.section_hidden_reveal_show_all
    })
    val grabbing = remember { PointerIcon(android.view.PointerIcon.TYPE_GRABBING) }
    Box(Modifier.fillMaxWidth().onGloballyPositioned { reorder.container = it }.reorderable(reorder, scope)
        .pointerHoverIcon(if (reorder.dragging != null) grabbing else PointerIcon.Default, overrideDescendants = reorder.dragging != null)) {
        Column {
            SectionCard(Section.SHOWN, stringResource(R.string.section_shown), stringResource(R.string.section_shown_help), order, states, selected, onSelect, reorder)
            // With everything shown, this section holds the items that wait for their "Show when" rule.
            SectionCard(Section.HIDDEN, stringResource(if (showAll) R.string.section_when_active else R.string.section_hidden),
                if (showAll) reveal else stringResource(R.string.section_hidden_help, reveal), order, states, selected, onSelect, reorder)
            SectionCard(Section.OFF, stringResource(R.string.common_off), stringResource(R.string.section_off_help), order, states, selected, onSelect, reorder)
        }
        // The dragged row, above all the cards (each card clips its own rows).
        val dragged = reorder.dragging?.let { id -> items.firstOrNull { it.id == id } }
        if (dragged != null) {
            Box(Modifier.offset { IntOffset(reorder.rowLeft.roundToInt(), reorder.ghostTop.roundToInt()) }
                .width(with(density) { reorder.rowWidth.toDp() }).clearAndSetSemantics { }) {
                RowGhost(dragged, states[dragged.id])
            }
        }
    }
}

@Composable
private fun SectionCard(section: Section, title: String, help: String, all: List<ItemConfig>, states: Map<String, ItemState>,
                        selected: String?, onSelect: (String?) -> Unit, reorder: Reorder) {
    val items = all.filter { it.section == section }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp).onGloballyPositioned { reorder.cards[section] = it }) {
        // The card grows and shrinks smoothly as a dragged row's opening comes and goes.
        Column(Modifier.animateContentSize().padding(vertical = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp).semantics { heading() })
            Text(help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp))
            Spacer(Modifier.height(6.dp))
            if (items.isEmpty()) Text(stringResource(R.string.section_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            items.forEachIndexed { i, item ->
                key(item.id) {
                    if (item.id == reorder.dragging) DropOpening(item.id, reorder)
                    else ItemRow(item, states[item.id], i, items.size, item.id == selected, onSelect, reorder)
                }
            }
        }
    }
}

/** Where the dragged row will land. */
@Composable
private fun DropOpening(id: String, reorder: Reorder) {
    val shape = RoundedCornerShape(14.dp)
    val tint = MaterialTheme.colorScheme.primary
    Box(Modifier.onGloballyPositioned { reorder.rows[id] = it }.animatePlacement()
        .fillMaxWidth().padding(horizontal = 8.dp).height(with(LocalDensity.current) { reorder.rowHeight.toDp() })
        .clip(shape).background(tint.copy(alpha = 0.08f)).border(1.dp, tint.copy(alpha = 0.35f), shape))
}

private val rowShape = RoundedCornerShape(14.dp)

@Composable
private fun ItemRow(item: ItemConfig, state: ItemState?, index: Int, count: Int, selected: Boolean, onSelect: (String?) -> Unit, reorder: Reorder) {
    val type = Items.of(item.type)
    val name = rowName(item)
    var menu by remember { mutableStateOf(false) }
    val moveUp = stringResource(R.string.row_move_up)
    val moveDown = stringResource(R.string.row_move_down)
    val toShown = stringResource(R.string.row_show_in_bar)
    val toHidden = stringResource(R.string.row_move_to_hidden)
    val turnOff = stringResource(R.string.common_turn_off)
    val move = { to: Int -> Store.move(item.id, item.section, to) }
    // The arrows went away with drag and drop, so screen readers get the moves as actions on the row.
    val actions = buildList {
        if (index > 0) add(CustomAccessibilityAction(moveUp) { move(index - 1); true })
        if (index < count - 1) add(CustomAccessibilityAction(moveDown) { move(index + 1); true })
        if (item.section != Section.SHOWN) add(CustomAccessibilityAction(toShown) { Store.update { it.show(item.id) }; true })
        if (item.section != Section.HIDDEN) add(CustomAccessibilityAction(toHidden) { Store.update { it.hide(item.id) }; true })
        if (item.section != Section.OFF) add(CustomAccessibilityAction(turnOff) { Store.update { it.turnOff(item.id) }; true })
    }
    val grab = remember { PointerIcon(android.view.PointerIcon.TYPE_GRAB) }
    Row(
        Modifier.onGloballyPositioned { reorder.rows[item.id] = it }.animatePlacement()
            .fillMaxWidth().padding(horizontal = 8.dp).clip(rowShape)
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).onGloballyPositioned { reorder.grabAreas[item.id] = it }
                // Keyboard: Alt+Up and Alt+Down move the focused row (the ≡ menu has the same moves).
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown || !e.isAltPressed) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.DirectionUp -> { if (index > 0) move(index - 1); true }
                        Key.DirectionDown -> { if (index < count - 1) move(index + 1); true }
                        else -> false
                    }
                }
                .clickable(enabled = type != null) { onSelect(item.id) }.pointerHoverIcon(PointerIcon.Hand)
                .semantics { customActions = actions; this.selected = selected }
                .padding(start = 2.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The handle: a mouse can drag the whole row, a finger only this (elsewhere it scrolls).
            Box(Modifier.onGloballyPositioned { reorder.handles[item.id] = it }.pointerHoverIcon(grab).size(width = 28.dp, height = 36.dp),
                contentAlignment = Alignment.Center) {
                SymIcon(Sym.DRAG_INDICATOR, size = 20.sp, color = MaterialTheme.colorScheme.outline)
            }
            RowBody(item, state)
        }
        Box {
            IconButton(onClick = { menu = true }) { SymIcon(Sym.MENU, size = 18.sp, contentDescription = stringResource(R.string.row_more, name)) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (index > 0) DropdownMenuItem(text = { Text(moveUp) }, onClick = { menu = false; move(index - 1) },
                    leadingIcon = { SymIcon(Sym.ARROW_UPWARD, size = 18.sp) })
                if (index < count - 1) DropdownMenuItem(text = { Text(moveDown) }, onClick = { menu = false; move(index + 1) },
                    leadingIcon = { SymIcon(Sym.ARROW_DOWNWARD, size = 18.sp) })
                if (item.section != Section.SHOWN) DropdownMenuItem(text = { Text(toShown) }, onClick = { menu = false; Store.update { it.show(item.id) } },
                    leadingIcon = { SymIcon(Sym.VISIBILITY, size = 18.sp) })
                if (item.section != Section.HIDDEN) DropdownMenuItem(text = { Text(stringResource(R.string.section_hidden)) }, onClick = { menu = false; Store.update { it.hide(item.id) } },
                    leadingIcon = { SymIcon(Sym.VISIBILITY_OFF, size = 18.sp) })
                if (item.section != Section.OFF) DropdownMenuItem(text = { Text(turnOff) }, onClick = { menu = false; Store.update { it.turnOff(item.id) } },
                    leadingIcon = { SymIcon(Sym.REMOVE, size = 18.sp) })
                DropdownMenuItem(text = { Text(stringResource(R.string.common_delete)) }, onClick = { menu = false; if (selected) onSelect(null); Undo.delete(item) },
                    leadingIcon = { SymIcon(Sym.DELETE, size = 18.sp) })
            }
        }
    }
}

/** The floating copy of a row while it's dragged: same size and content, lifted. */
@Composable
private fun RowGhost(item: ItemConfig, state: ItemState?) {
    val name = rowName(item)
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp).shadow(8.dp, rowShape).clip(rowShape)
        .background(MaterialTheme.colorScheme.surfaceContainerHighest), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).padding(start = 2.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 28.dp, height = 36.dp), contentAlignment = Alignment.Center) {
                SymIcon(Sym.DRAG_INDICATOR, size = 20.sp, color = MaterialTheme.colorScheme.primary)
            }
            RowBody(item, state)
        }
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { SymIcon(Sym.MENU, size = 18.sp, contentDescription = name) }
    }
}

/** What a row calls an item: a type this version doesn't know has no name here, only where it came from. */
@Composable
private fun rowName(item: ItemConfig): String = Items.of(item.type)?.title ?: stringResource(R.string.row_from_newer)

/** The item's icon, name and detail line, shared by the row and its dragged copy. */
@Composable
private fun RowScope.RowBody(item: ItemConfig, state: ItemState?) {
    val type = Items.of(item.type)
    Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center) { SymIcon(type?.icon ?: Sym.UPDATE, size = 20.sp) }
    Spacer(Modifier.width(12.dp))
    Column(Modifier.weight(1f)) {
        Text(rowName(item), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val overflow by com.meridian11.books.bentobar.bar.BarOverflow.ids.collectAsState()
        val detail = if (type == null) "" else listOfNotNull(
            if (item.id in overflow) stringResource(R.string.row_overflow) else null,
            state?.text?.takeIf { it.isNotBlank() },
            // The rule in short ("shows above 80% CPU"), so the list says when each hidden item pops out.
            if (item.whenActive && item.section == Section.HIDDEN) type.trigger?.short(item) ?: stringResource(R.string.row_when_active) else null,
            if (type.iconOnly) null
            else when (item.display) { Display.ICON -> stringResource(R.string.row_icon_only); Display.TEXT -> stringResource(R.string.row_text_only); else -> null }).joinToString(" · ")
        if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * One item's settings, kept by its id: choosing another item (a click in the preview takes no focus)
 * starts afresh, so a field being typed in can't save what it holds into the next one.
 */
@Composable
private fun ItemDetail(item: ItemConfig, state: ItemState?, onSelect: (String?) -> Unit) {
    key(item.id) { ItemDetailCard(item, state, onSelect) }
}

@Composable
private fun ItemDetailCard(item: ItemConfig, state: ItemState?, onSelect: (String?) -> Unit) {
    val type = Items.of(item.type) ?: return
    val context = androidx.compose.ui.platform.LocalContext.current
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth().widthIn(max = 760.dp)) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center) {
                    SymIcon(type.icon, size = 26.sp, filled = true, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(type.title, style = MaterialTheme.typography.titleLarge)
                    Text(type.blurb, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (state?.text != null) Text(state.text, style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.primary, maxLines = 1)
            }
            Spacer(Modifier.height(12.dp))
            // "Needs calendar access" by the same rule the item itself uses (granted, and not switched off in
            // Setup), from the observed setup state: asking Android while drawing isn't re-read when it changes.
            val setup by Setup.state.collectAsState()
            if (android.Manifest.permission.READ_CALENDAR in type.permissions && !setup.calendar) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.detail_needs_calendar), style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onTertiaryContainer)
                        FilledTonalButton(onClick = {
                            // Allow here is also switching Calendar back on in Setup; Android is asked only if it still has to grant it.
                            Store.update { it.copy(turnedOff = it.turnedOff - com.meridian11.books.bentobar.data.Uses.CALENDAR) }
                            if (context.checkSelfPermission(android.Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED)
                                (context as? android.app.Activity)?.requestPermissions(arrayOf(android.Manifest.permission.READ_CALENDAR), 2)
                            Setup.refresh(context); Ticker.refresh()
                        }) { Text(stringResource(R.string.common_allow)) }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            // The same for notification access (Now playing): the words, then the way to Android's switch.
            if (type.notificationAccess && !setup.mediaAccess) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.media_access_explain), style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onTertiaryContainer)
                            if (setup.sideloaded) Text(stringResource(R.string.media_access_restricted), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.padding(top = 4.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        FilledTonalButton(onClick = { com.meridian11.books.bentobar.items.MediaAccess.openSettings(context) }) {
                            Text(stringResource(R.string.media_access_allow))
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            ChoiceRow(stringResource(R.string.detail_where), listOf(Section.SHOWN to stringResource(R.string.section_shown),
                Section.HIDDEN to stringResource(R.string.section_hidden), Section.OFF to stringResource(R.string.common_off)), item.section) { s ->
                Store.update { it.putIn(item.id, s) }
            }
            val trigger = type.trigger
            if (type.canBeActive && trigger != null) TriggerControl(item, trigger)
            if (!type.iconOnly) ChoiceRow(stringResource(R.string.display_show_as), listOf(Display.ICON_AND_TEXT to stringResource(R.string.display_icon_and_text),
                Display.TEXT to stringResource(R.string.display_text), Display.ICON to stringResource(R.string.display_icon)), item.display) { d ->
                Store.updateItem(item.id) { it.copy(display = d) }; Ticker.refresh()
            }
            type.options?.let { opts ->
                Spacer(Modifier.height(4.dp))
                SectionLabel(stringResource(type.optionsTitle))
                opts(item) { changed -> Store.updateItem(item.id) { changed }; Ticker.refresh() }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { onSelect(Store.duplicate(item)) }) { Text(stringResource(R.string.detail_duplicate)) }
                TextButton(onClick = { onSelect(null); Undo.delete(item) }) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

/**
 * "Show when…": the switch reads as the rule itself ("Show when CPU load is above 80%"), and a
 * slider below sets the number, updating the words as it moves. It saves when the slider is let go.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun TriggerControl(item: ItemConfig, trigger: Trigger) {
    val threshold = trigger.threshold
    val stored = threshold?.shown(item) ?: 0
    var live by remember(item.id, stored) { mutableIntStateOf(stored) }
    val sentence = trigger.sentence(item, live)
    SwitchRow(sentence, item.whenActive, help = stringResource(when {
        item.section == Section.HIDDEN -> R.string.detail_trigger_help
        item.whenActive -> R.string.detail_trigger_help_not_hidden
        else -> R.string.detail_trigger_help_move
    })) { on ->
        Store.update { it.setWhenActive(item.id, on) }
    }
    if (threshold != null) {
        val r = threshold.range
        Slider(
            value = live.toFloat(),
            onValueChange = { live = threshold.snap(it) },
            onValueChangeFinished = {
                if (live != threshold.of(item)) { Store.updateItem(item.id) { it.with(threshold.key, live.toString()) }; Ticker.refresh() }
            },
            steps = ((r.last - r.first) / threshold.step - 1).coerceAtLeast(0),
            // Steps make the keyboard and screen readers move one value at a time; dozens of tick marks would only clutter.
            track = { SliderDefaults.Track(it, drawTick = { _, _ -> }) },
            valueRange = r.first.toFloat()..r.last.toFloat(),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = sentence },
        )
    }
}

/** Delete with an undo: the item and where it was, until the snackbar goes. */
object Undo {
    /** [at]: when, in `SystemClock.elapsedRealtime()`. */
    data class Deleted(val item: ItemConfig, val index: Int, val at: Long = SystemClock.elapsedRealtime())
    val deleted = kotlinx.coroutines.flow.MutableStateFlow<Deleted?>(null)

    /** As long as the snackbar shows (SnackbarDuration.Long). */
    const val OFFER_MS = 10_000L

    /**
     * Whether [d] is still offered back at [now]: again when the window is recreated meanwhile (a resize,
     * a theme), never after that time (settings left with the snackbar up and opened again later).
     */
    fun offered(d: Deleted, now: Long): Boolean = now - d.at in 0 until OFFER_MS

    fun delete(item: ItemConfig) {
        deleted.value = Deleted(item, Store.config.value.items.indexOfFirst { it.id == item.id })
        Store.remove(item.id)
    }

    fun restore(d: Deleted) = Store.update { c ->
        if (c.items.any { it.id == d.item.id }) c
        else c.copy(items = c.items.toMutableList().apply { add(d.index.coerceIn(0, size), d.item) })
    }
}
