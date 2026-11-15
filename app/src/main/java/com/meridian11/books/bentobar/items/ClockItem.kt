package com.meridian11.books.bentobar.items

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.semantics.heading
import com.meridian11.books.bentobar.ui.Hairline
import com.meridian11.books.bentobar.ui.Space
import com.meridian11.books.bentobar.ui.Bento
import com.meridian11.books.bentobar.ui.ButtonKind
import com.meridian11.books.bentobar.ui.BentoButton
import android.content.Intent
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.data.Store
import com.meridian11.books.bentobar.data.WorldCity
import com.meridian11.books.bentobar.ui.ChoiceRow
import com.meridian11.books.bentobar.ui.MenuCard
import com.meridian11.books.bentobar.ui.MenuDivider
import com.meridian11.books.bentobar.ui.MenuEntry
import com.meridian11.books.bentobar.ui.MenuNote
import com.meridian11.books.bentobar.ui.SearchField
import com.meridian11.books.bentobar.ui.SearchStatus
import com.meridian11.books.bentobar.ui.SmallIconButton
import com.meridian11.books.bentobar.ui.SwitchRow
import com.meridian11.books.bentobar.ui.TextRow
import com.meridian11.books.bentobar.ui.rememberTick
import com.meridian11.books.bentobar.util.Dates
import com.meridian11.books.bentobar.util.Now
import com.meridian11.books.bentobar.util.Sym
import com.meridian11.books.bentobar.util.SymIcon
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import java.util.TimeZone

// The Clock item and its World clock menu: the places, Plan a time, adding and removing cities. What
// the menu says is worked out in WorldClock.kt and PlanATime.kt, which are pure and unit-tested.

private fun is24(item: ItemConfig) = when (ClockItem.hours.of(item)) {
    "24" -> true
    "12" -> false
    else -> DateFormat.is24HourFormat(Env.app)
}

/** The time in [t]'s zone, 24-hour or 12-hour as asked, laid out the locale's way. */
private fun formatTime(t: ZonedDateTime, h24: Boolean, seconds: Boolean): String = Dates.format(Dates.timeSkeleton(h24, seconds), t)

/** The item's own zone, or the device's when it has none or this device doesn't know it. */
private fun zoneOf(item: ItemConfig): ZoneId = WorldClock.zone(item.options["zone"]) ?: ZoneId.systemDefault()

/** What "Add a city" searches: the zones this device knows and the cities of the table, read once (the zone data only changes with a restart). */
private val zoneIndex by lazy { WorldClock.Index(ZoneId.getAvailableZoneIds()) }

/** World clock's words from the app's texts, and its times as the item is set to show them. */
private fun words(h24: Boolean) = WorldClock.Words(
    local = Env.str(R.string.clock_local), sameTime = Env.str(R.string.clock_same_time),
    tomorrow = Env.str(R.string.clock_tomorrow), yesterday = Env.str(R.string.clock_yesterday),
    here = { Env.str(R.string.clock_here, it) },
    time = { moment, zone -> Dates.format(Dates.timeSkeleton(h24), moment, zone) },
    weekday = { moment, zone -> Dates.format("EEE", moment, zone) },
    date = { moment, zone -> Dates.format("EEEMMMd", moment, zone) },
)

object ClockItem : ItemType("clock", R.string.item_clock_title, Sym.SCHEDULE, R.string.item_clock_desc) {
    override val group = ItemGroup.TIME
    // A rule without a number: a clock of home that shows only while the device is somewhere else.
    override val canBeActive = true
    override val trigger = Trigger(R.string.trigger_clock, R.string.trigger_clock_short)
    /** The name before the time. */
    val label = Opt.text("label", 12)
    val seconds = Opt.bool("seconds", true)
    val hours = Opt.oneOf("hours", "system", "system", "24", "12")

    override fun state(item: ItemConfig): ItemState {
        val zone = zoneOf(item)
        val now = Now.wall()
        val time = formatTime(Instant.ofEpochMilli(now).atZone(zone), is24(item), seconds.of(item))
        val name = label.of(item)
        return ItemState(icon = Sym.SCHEDULE, text = if (name.isBlank()) time else "$name $time", widthKey = "clock",
            desc = "${name.ifBlank { WorldClock.cityOf(zone.id) }} $time",
            // Asked only of a clock that has the rule on and a zone of its own: one that follows the device is never away.
            active = item.whenActive && item.options["zone"] != null && WorldClock.away(zone, ZoneId.systemDefault(), now))
    }

    override val menu: @Composable (ItemConfig, MenuHost) -> Unit = { item, host -> WorldClockMenu(item, host) }

    override val options: @Composable (ItemConfig, (ItemConfig) -> Unit) -> Unit = { item, set ->
        ZonePicker(item.options["zone"]) { set(item.with("zone", it)) }
        TextRow(stringResource(R.string.option_label), label.of(item), help = stringResource(R.string.clock_label_help),
            placeholder = stringResource(R.string.option_none), maxLength = 12) {
            set(label.set(item, it))
        }
        ChoiceRow(stringResource(R.string.clock_hours), listOf("system" to stringResource(R.string.option_like_system),
            "24" to stringResource(R.string.clock_hours_24), "12" to stringResource(R.string.clock_hours_12)), hours.of(item)) {
            set(hours.set(item, it))
        }
        SwitchRow(stringResource(R.string.clock_seconds), seconds.of(item)) { set(seconds.set(item, it)) }
        CitiesInSettings()
    }
}

/**
 * World clock: the device's time and the other places, a slider to see any time of day in all of
 * them, and the list of cities. Everything is worked out on the device, so nothing here loads, is
 * offline or needs an access.
 */
@Composable
private fun WorldClockMenu(item: ItemConfig, host: MenuHost) {
    rememberTick()
    // The layout as it is now: a city added here, or named in settings, shows at once.
    val cfg by Store.config.collectAsState()
    val now = Now.wall()
    val local = remember(TimeZone.getDefault().id) { ZoneId.systemDefault() }
    val h24 = is24(item)
    val words = remember(h24) { words(h24) }
    // The places keep their zones. They are looked up again when the list changes and once a minute (their order is that
    // of the offsets right now), not for every frame of the slider: Android keeps the rules of only a few zones at hand.
    val clocks = cfg.items.filter { it.type == "clock" }.map { it.options["zone"] to ClockItem.label.of(it) }
    val places = remember(local, clocks, cfg.cities, now / 60_000) {
        WorldClock.places(local, clocks.map { (zone, label) -> WorldClock.Clock(WorldClock.zone(zone) ?: local, label) }, cfg.cities, now)
    }
    // A plan lives as long as the menu is open: opening it again is the present.
    var chosen by remember { mutableStateOf<PlanATime.Plan?>(null) }
    // Plan a time compares places, so it is there only with a second row. A plan made while there was one ends with
    // that row: its "Now" goes with the slider, and nothing else would bring the present back.
    val plannable = WorldClock.plannable(places)
    LaunchedEffect(plannable) { if (!plannable) chosen = null }
    val plan = chosen.takeIf { plannable }
    var editing by remember { mutableStateOf(false) }
    val edit = remember { FocusRequester() }
    // With the last city gone there is no "Done" left to end editing with.
    LaunchedEffect(cfg.cities.isEmpty()) { if (cfg.cities.isEmpty()) editing = false }
    val here = Instant.ofEpochMilli(now).atZone(local)
    val planned = plan?.let { PlanATime.moment(it, local).toInstant().toEpochMilli() }
    val moment = planned ?: now
    MenuCard(Sym.PUBLIC, stringResource(R.string.clock_world),
        if (planned != null) stringResource(R.string.clock_plan_subtitle, words.date(planned, local), words.time(planned, local)) else Dates.format("EEEEdMMMM", here),
        subtitleColor = if (planned != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) {
        WorldClock.rows(places, moment, planned != null, words).forEach { row ->
            key(row.place.zone.id) {
                PlaceRow(row, planned != null, editing && cfg.cities.isNotEmpty()) { city -> Store.update { it.copy(cities = WorldClock.remove(it.cities, city.zone)) } }
            }
        }
        if (plannable) PlanTime(plan, here, time = { words.time(it, local) }, line = { WorldClock.copyLine(places, moment, words) }, onPlan = { chosen = it }) { at ->
            // The calendar app's own editor for a new event, from that moment for an hour; saving it is the calendar app's business.
            host.close()
            Env.launch(Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI).putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, at)
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, PlanATime.eventEnd(at)))
        }
        MenuDivider()
        AddCity(cfg.cities, places, now, next = edit)
        if (cfg.cities.isNotEmpty()) MenuEntry(if (editing) Sym.CHECK else Sym.EDIT, stringResource(if (editing) R.string.common_done else R.string.clock_edit_cities),
            modifier = Modifier.focusRequester(edit)) { editing = !editing }
        MenuEntry(Sym.ALARM, stringResource(R.string.clock_alarms)) { host.close(); Env.launch(Intent(AlarmClock.ACTION_SHOW_ALARMS)) }
        MenuEntry(Sym.SETTINGS, stringResource(R.string.clock_date_settings)) { host.close(); Env.launch(Intent(Settings.ACTION_DATE_SETTINGS)) }
    }
}

/**
 * One place: its name over what tells it from the device's time, its time at the end, with a moon
 * before it while it is night there. A planned time is drawn in the accent color. While the cities
 * are [editing] every row keeps a box after its time, so the times stay in one column; in the row of
 * an added city it holds the button that removes it.
 */
@Composable
private fun PlaceRow(row: WorldClock.Row, planned: Boolean, editing: Boolean, onRemove: (WorldCity) -> Unit) {
    // One line for a screen reader, in the order it is read: "Tokyo, 6:10 AM, Tomorrow · +16h, Night there".
    val spoken = WorldClock.spoken(row, stringResource(R.string.clock_night))
    val focus = LocalFocusManager.current
    // Whether the keyboard's focus is on this row's button.
    val held = remember { BooleanArray(1) }
    // As high as the box that editing adds, also with the smallest font: the rows must not grow when "Edit cities" is clicked.
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).padding(vertical = 6.dp).clearAndSetSemantics { contentDescription = spoken }, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text(row.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(row.sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (row.night) {
                SymIcon(Sym.BEDTIME, size = 16.sp, filled = true, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
            }
            Text(row.time, style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"), maxLines = 1,
                color = if (planned) MaterialTheme.colorScheme.primary else Color.Unspecified)
        }
        if (editing) Box(Modifier.size(48.dp).onFocusChanged { held[0] = it.hasFocus }, contentAlignment = Alignment.Center) {
            row.place.city?.let { city ->
                SmallIconButton(Sym.CLOSE, stringResource(R.string.clock_remove_city, WorldClock.nameOf(city))) {
                    // The button goes with its city. If it holds the keyboard's focus, that moves on to the next control
                    // first: dropped, it would start again at the top of the menu.
                    if (held[0]) focus.moveFocus(FocusDirection.Next)
                    onRemove(city)
                }
            }
        }
    }
}

/**
 * "Add a city": the entry, and in its place once it is clicked a field that lists cities and zones
 * as they are typed, from what the device knows (no connection is involved). Enter takes the first
 * result that isn't in the list yet, a click any; the field then closes and the entry is back. With
 * the list full the entry is dimmed and the reason stands under it. [places]: the rows as they are,
 * so that a place that has one reads "added".
 */
@Composable
private fun AddCity(cities: List<WorldCity>, places: List<WorldClock.Place>, now: Long, next: FocusRequester) {
    var adding by remember { mutableStateOf(false) }
    // The field and its results go away when a city is taken or the search is closed. If one of them held the keyboard's
    // focus, it goes to the entry that comes back (the next city is one more Enter away), or with the list now full
    // to the entry after it ([next], "Edit cities"); dropped, it would start again at the top of the menu.
    var refocus by remember { mutableStateOf(false) }
    val entry = remember { FocusRequester() }
    // Counted by the cities that have a row, which means looking their zones up: when the list changes, not with every tick.
    val full = remember(cities) { WorldClock.full(cities) }
    LaunchedEffect(full) { if (full) adding = false }
    if (!adding || full) {
        MenuEntry(Sym.ADD, stringResource(R.string.clock_add_city), enabled = !full, modifier = Modifier.focusRequester(entry)) { adding = true }
        // Said without a click, so that the dimmed entry is no dead end.
        if (full) MenuNote(stringResource(R.string.clock_full))
        LaunchedEffect(Unit) { if (refocus) { refocus = false; runCatching { (if (full) next else entry).requestFocus() } } }
        return
    }
    var query by remember { mutableStateOf("") }
    // The differences beside the results are those of this minute.
    val hits = remember(query, places, now / 60_000) { zoneIndex.search(query, places, now) }
    val free = WorldClock.firstFree(hits)
    val focus = LocalFocusManager.current
    val field = remember { FocusRequester() }
    val first = remember { FocusRequester() }
    // Whether the keyboard's focus is in the field or on the × inside it, whether it is on the field itself, and
    // whether it is on a result.
    val inField = remember { BooleanArray(1) }
    val onField = remember { BooleanArray(1) }
    val inResults = remember { BooleanArray(1) }
    fun close(focused: Boolean) { refocus = focused; adding = false }
    fun take(hit: WorldClock.Hit?, focused: Boolean) {
        if (hit == null || hit.added) return
        Store.update { it.copy(cities = WorldClock.add(it.cities, hit.zone, hit.nameInList)) }
        close(focused)
    }
    // The arrows walk the menu from the field as from any other control: Down to the first result that can be taken
    // (and Up from there back to the field), else on to whatever is under the field; Up to what is above it.
    Box(Modifier.onFocusChanged { inField[0] = it.hasFocus; onField[0] = it.isFocused }.onPreviewKeyEvent { e ->
        val down = e.type == KeyEventType.KeyDown
        when (e.key) {
            Key.DirectionDown -> { if (down) { if (free != null) runCatching { first.requestFocus() } else focus.moveFocus(FocusDirection.Down) }; true }
            Key.DirectionUp -> { if (down) focus.moveFocus(FocusDirection.Up); true }
            // Enter on the × inside the field closes the search, as a click on it does. The field sees the keys of what is
            // inside it before the × does and takes Enter for its own, which would add the first result instead.
            Key.Enter, Key.NumPadEnter -> if (inField[0] && !onField[0]) { if (down) close(focused = true); true } else false
            else -> false
        }
    }) {
        SearchField(stringResource(R.string.clock_search_label), placeholder = stringResource(R.string.clock_zone_find_hint), focus = field,
            onClose = { close(inField[0]) }, onChange = { query = it }, onEnter = { take(free, focused = true) })
    }
    when {
        WorldClock.key(query).length < WorldClock.MIN_LETTERS -> {}
        hits.isEmpty() -> SearchStatus(stringResource(R.string.clock_search_none))
        else -> Column(Modifier.onFocusChanged { inResults[0] = it.hasFocus }) {
            hits.forEach { hit ->
                MenuEntry(Sym.PUBLIC, if (hit.region != null) stringResource(R.string.clock_search_region, hit.name, hit.region) else hit.name,
                    detail = WorldClock.beside(hit, stringResource(R.string.clock_search_added), stringResource(R.string.clock_same_time)), enabled = !hit.added,
                    modifier = if (hit !== free) Modifier else Modifier.focusRequester(first).onPreviewKeyEvent { e ->
                        if (e.key == Key.DirectionUp) { if (e.type == KeyEventType.KeyDown) runCatching { field.requestFocus() }; true } else false
                    }) { take(hit, focused = inResults[0]) }
            }
        }
    }
}

/**
 * "World clock cities" at the end of a Clock item's settings: the one list the whole bar has, each
 * city with the name it goes by and a way to remove it. Cities are added in the menu.
 */
@Composable
private fun CitiesInSettings() {
    val cfg by Store.config.collectAsState()
    Hairline()
    Text(stringResource(R.string.clock_cities_section), Modifier.semantics { heading() }, style = Bento.type.strong, color = Bento.colors.ink)
    Text(stringResource(if (cfg.cities.isEmpty()) R.string.clock_cities_empty else R.string.clock_cities_help), Modifier.padding(top = 2.dp, bottom = Space.xs),
        style = Bento.type.help, color = Bento.colors.ink2)
    if (cfg.cities.isEmpty()) return
    // The menu's order, worked out when a city comes or goes and not with every letter of a name: among cities of one
    // offset the names decide, and the row being named would move away under the typing.
    val order = remember(cfg.cities.map { it.zone }) { WorldClock.ordered(cfg.cities, Now.wall()).map { it.zone } }
    order.mapNotNull { zone -> cfg.cities.firstOrNull { it.zone == zone } }.forEach { city ->
        key(city.zone) {
            // "Remove" is what the button reads; a screen reader hears which city.
            val remove = stringResource(R.string.clock_remove_city, WorldClock.nameOf(city))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    TextRow(stringResource(R.string.clock_city_name), city.name, help = city.zone.take(64), placeholder = WorldClock.nameOf(city.copy(name = ""))) { typed ->
                        Store.update { it.copy(cities = WorldClock.rename(it.cities, city.zone, typed)) }
                    }
                }
                BentoButton(stringResource(R.string.clock_city_remove), { Store.update { it.copy(cities = WorldClock.remove(it.cities, city.zone)) } },
                    Modifier.semantics { contentDescription = remove }, kind = ButtonKind.TEXT)
            }
        }
    }
}

/** Search-as-you-type over the IANA zones. */
@Composable
fun ZonePicker(current: String?, onPick: (String?) -> Unit) {
    var query by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(current?.let { stringResource(R.string.clock_zone_named, WorldClock.cityOf(it), it) } ?: stringResource(R.string.clock_zone_device),
            style = Bento.type.strong, color = Bento.colors.ink)
        TextRow(stringResource(R.string.clock_zone_find), query, placeholder = stringResource(R.string.clock_zone_find_hint)) { query = it }
        if (query.length >= 2) {
            val q = query.trim().replace(' ', '_').lowercase(Locale.ROOT)
            val hits = ZoneId.getAvailableZoneIds().filter { it.lowercase(Locale.ROOT).contains(q) && it.contains('/') }.sorted().take(8)
            hits.forEach { id -> MenuEntry(Sym.PUBLIC, WorldClock.cityOf(id), id) { onPick(id); query = "" } }
            if (hits.isEmpty()) Text(stringResource(R.string.clock_zone_none), style = Bento.type.help, color = Bento.colors.ink2)
        }
        if (current != null) BentoButton(stringResource(R.string.clock_zone_use_device), { onPick(null) }, Modifier.offset(x = -Space.m), kind = ButtonKind.TEXT)
    }
}
