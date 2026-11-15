package com.meridian11.books.bentobar.items

import android.annotation.SuppressLint
import android.Manifest
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.ui.ChipRow
import com.meridian11.books.bentobar.ui.ChoiceRow
import com.meridian11.books.bentobar.ui.InfoRow
import com.meridian11.books.bentobar.ui.LocalPreviewClock
import com.meridian11.books.bentobar.ui.MainActivity
import com.meridian11.books.bentobar.ui.MenuCard
import com.meridian11.books.bentobar.ui.MenuDivider
import com.meridian11.books.bentobar.ui.MenuEntry
import com.meridian11.books.bentobar.ui.Meter
import com.meridian11.books.bentobar.ui.SectionLabel
import com.meridian11.books.bentobar.ui.SliderRow
import com.meridian11.books.bentobar.ui.SmallIconButton
import com.meridian11.books.bentobar.ui.SwitchRow
import com.meridian11.books.bentobar.ui.TextRow
import com.meridian11.books.bentobar.ui.fadeIn
import com.meridian11.books.bentobar.ui.popIn
import com.meridian11.books.bentobar.ui.rememberTick
import com.meridian11.books.bentobar.util.Dates
import com.meridian11.books.bentobar.util.Fmt
import com.meridian11.books.bentobar.util.Sym
import com.meridian11.books.bentobar.util.SymIcon
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * The date in the bar; the menu is the month, with the picked day's agenda: every calendar event,
 * timed or all-day (meetings, flights, holidays, birthdays), with Join and Directions where they apply.
 */
object CalendarItem : ItemType("calendar", R.string.item_calendar_title, Sym.CALENDAR_MONTH, R.string.item_calendar_desc) {
    override val group = ItemGroup.TIME
    override val refreshMs = 10_000L
    // Room for Join and Directions next to an event in the agenda.
    override val menuWidthDp = 340

    override fun onLive() = Calendar.hold(type)
    override fun onIdle() = Calendar.release(type)

    override fun state(item: ItemConfig): ItemState {
        val today = LocalDate.now()
        // No pattern chosen: the locale's own short date ("Sun, Sep 28" in the US, "Sun 28 Sep" in the UK).
        val pattern = item.options["format"]?.takeIf { it.isNotBlank() }
        val text = if (pattern == null) Dates.format("EEEdMMM", today)
            else runCatching { today.format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault())) }.getOrDefault(today.toString())
        return ItemState(icon = Sym.CALENDAR_MONTH, dayNumber = today.dayOfMonth, text = text, desc = Dates.format("EEEEdMMMMyyyy", today))
    }

    override val menu: @Composable (ItemConfig, MenuHost) -> Unit = { item, host -> MonthMenu(item, host) }

    override val options: @Composable (ItemConfig, (ItemConfig) -> Unit) -> Unit = { item, set ->
        // Each pattern is shown with today's date, as the header's preview and the bar say it: a fixed sample beside them read as a mistake.
        val today = LocalPreviewClock.current().toLocalDate()
        fun sampleOf(p: String) = today.format(DateTimeFormatter.ofPattern(p, Locale.getDefault()))
        // "" is the locale's own date; choosing it removes the option rather than storing a pattern.
        ChoiceRow(stringResource(R.string.calendar_date_in_bar), listOf("" to stringResource(R.string.option_like_system),
            "EEE d MMM" to sampleOf("EEE d MMM"), "d MMM" to sampleOf("d MMM"), "EEEE" to sampleOf("EEEE"),
            "'W'w" to stringResource(R.string.calendar_week_number), "d.M." to sampleOf("d.M.")), item.opt("format", "")) {
            set(item.with("format", it.ifBlank { null }))
        }
        TextRow(stringResource(R.string.calendar_own_pattern), item.opt("format", ""),
            help = stringResource(R.string.calendar_own_pattern_help)) { if (it.isNotBlank()) set(item.with("format", it)) }
        SwitchRow(stringResource(R.string.calendar_week_numbers), item.optBool("weeks", true)) { set(item.with("weeks", it.toString())) }
    }
}

/** The agenda shows this many events at most; the rest are a "+N more" row. */
private const val AGENDA_MAX = 12

// The menu is composed afresh each time it opens, and a language change restarts the service's windows, so a locale read
// here once per opening is never stale: nothing needs to observe it.
@SuppressLint("NonObservableLocale")
@Composable
private fun MonthMenu(item: ItemConfig, host: MenuHost) {
    rememberTick()
    val today = LocalDate.now()
    var offset by remember { mutableIntStateOf(0) }
    var picked by remember { mutableStateOf(today) }
    val month = YearMonth.from(today).plusMonths(offset.toLong())
    val zone = ZoneId.systemDefault()
    val firstDow = WeekFields.of(Locale.getDefault()).firstDayOfWeek
    val lead = ((month.atDay(1).dayOfWeek.value - firstDow.value) + 7) % 7
    val allowed = Calendar.allowed()
    if (allowed) Calendar.refresh()
    // A month beyond the days always kept loaded: its events are loaded while the menu shows it (a
    // week before and two after, for the grid's dots and the agenda's week from a picked day).
    androidx.compose.runtime.DisposableEffect(month) {
        Calendar.view(month.atDay(1).minusDays(7).atStartOfDay(zone).toInstant().toEpochMilli(),
            month.atEndOfMonth().plusDays(14).atStartOfDay(zone).toInstant().toEpochMilli())
        onDispose { Calendar.view(null, null) }
    }
    fun eventsOn(d: LocalDate): List<Calendar.Event> {
        val s = d.atStartOfDay(zone).toInstant().toEpochMilli()
        val e = d.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        // Meetings have their own item (Next meeting); the calendar shows everything else.
        return Calendar.on(s, e, d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()).filterNot { it.meeting }
    }
    val weekOf = WeekFields.of(Locale.getDefault()).weekOfWeekBasedYear()
    MenuCard(Sym.CALENDAR_MONTH, Dates.format("EEEEdMMMM", today),
        stringResource(R.string.calendar_week, today.get(weekOf))) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(Dates.format("MMMMyyyy", month.atDay(1)),
                style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            SmallIconButton(Sym.CHEVRON_LEFT, stringResource(R.string.calendar_previous_month)) { offset-- }
            if (offset != 0) TextButton(onClick = { offset = 0; picked = today }) { Text(stringResource(R.string.calendar_today)) }
            SmallIconButton(Sym.CHEVRON_RIGHT, stringResource(R.string.calendar_next_month)) { offset++ }
        }
        val weeks = item.optBool("weeks", true)
        // The grid is one block, so its days can pop in a diagonal from the top left, 10 ms a step down or across
        // (motion.md §7.2); the weekday letters fade in first, and each week's number with its first day.
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fadeIn(0f).fillMaxWidth()) {
                if (weeks) Text("", Modifier.width(24.dp))
                for (i in 0 until 7) {
                    val dow = firstDow.plus(i.toLong())
                    Text(dow.getDisplayName(TextStyle.NARROW, Locale.getDefault()), Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val cells = lead + month.lengthOfMonth()
            val rows = (cells + 6) / 7
            for (r in 0 until rows) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    val firstDay = month.atDay(1).plusDays((r * 7 - lead).toLong())
                    if (weeks) Text(firstDay.get(weekOf).toString(), Modifier.fadeIn(5f * r).width(24.dp),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    for (c in 0 until 7) {
                        val i = r * 7 + c - lead
                        Box(Modifier.weight(1f).height(30.dp), contentAlignment = Alignment.Center) {
                            if (i in 0 until month.lengthOfMonth()) {
                                val d = month.atDay(i + 1)
                                val isToday = d == today
                                val isPicked = d == picked
                                val has = allowed && eventsOn(d).isNotEmpty()
                                // TalkBack reads the whole date (and whether anything's on), not just "14".
                                val full = Dates.format("EEEEdMMMM", d)
                                val label = if (has) stringResource(R.string.calendar_day_has_events, full) else full
                                // Today's disc pops from further in, 40 ms after its day would, its number and dot with it.
                                Column(
                                    Modifier.popIn(5f * (r + c), if (isToday) 0.8f else 0.9f).size(28.dp).clip(CircleShape)
                                        .background(when { isToday -> MaterialTheme.colorScheme.primary; isPicked -> MaterialTheme.colorScheme.secondaryContainer; else -> Color.Transparent })
                                        .selectable(selected = isPicked, onClick = { picked = d }).pointerHoverIcon(PointerIcon.Hand)
                                        .semantics { contentDescription = label },
                                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                                ) {
                                    Text("${i + 1}", modifier = Modifier.clearAndSetSemantics {}, style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                                        color = if (isToday) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                        fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal)
                                    if (has) Box(Modifier.size(4.dp).clip(CircleShape).background(if (isToday) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary))
                                }
                            }
                        }
                    }
                }
            }
        }
        MenuDivider()
        if (!allowed) {
            Text(stringResource(R.string.calendar_allow_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            MenuEntry(Sym.EVENT, stringResource(R.string.calendar_allow)) { host.close(); MainActivity.requestPermission(Env.app, Manifest.permission.READ_CALENDAR) }
        } else {
            // A week from the picked day, grouped by day; days with nothing on are left out.
            val week = (0L until 7L).map { picked.plusDays(it) }.map { it to eventsOn(it) }.filter { it.second.isNotEmpty() }
            if (week.isEmpty()) Text(stringResource(R.string.calendar_no_events_week), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            var shown = 0
            for ((d, list) in week) {
                if (shown >= AGENDA_MAX) break
                SectionLabel(when (d) {
                    today -> stringResource(R.string.calendar_today)
                    today.plusDays(1) -> stringResource(R.string.calendar_tomorrow)
                    else -> Dates.format("EEEEdMMMM", d)
                })
                list.take(AGENDA_MAX - shown).forEach { e -> EventRow(e, host::close); shown++ }
            }
            // What didn't fit isn't dropped silently: "+3 more" opens Calendar on the first day left out.
            val more = week.sumOf { it.second.size } - shown
            if (more > 0) {
                var skip = shown
                val firstLeft = week.first { (_, list) -> (skip < list.size).also { skip -= list.size } }.first
                MenuEntry(Sym.EVENT, pluralStringResource(R.plurals.calendar_more, more, more)) {
                    host.close(); Calendar.openDay(firstLeft.atTime(9, 0).atZone(zone).toInstant().toEpochMilli())
                }
            }
        }
        MenuEntry(Sym.OPEN_IN_NEW, stringResource(R.string.calendar_open)) {
            host.close(); Calendar.openDay(picked.atTime(9, 0).atZone(zone).toInstant().toEpochMilli())
        }
    }
}

/** A wall time as the system shows it: its 12/24-hour setting, the locale's layout. */
private fun shortTime(millis: Long): String = Dates.format(Dates.timeSkeleton(DateFormat.is24HourFormat(Env.app)), millis)

/**
 * One event in a list. Clicking it opens the event in the calendar app; it also gets a Join button
 * when it has a video-call link, and a Directions button when it has a place to go. [close] closes
 * the menu first.
 */
@Composable
fun EventRow(e: Calendar.Event, close: () -> Unit) {
    val range = if (e.allDay) stringResource(R.string.calendar_all_day) else shortTime(e.begin) + " – " + shortTime(e.end)
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { close(); Calendar.open(e) }.pointerHoverIcon(PointerIcon.Hand)
        .padding(vertical = 6.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 4.dp, height = 30.dp).clip(RoundedCornerShape(2.dp)).background(Color(e.color or 0xFF000000.toInt())))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(e.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(range + if (e.location.isNotBlank() && e.link == null) " · ${e.location}" else "",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
        if (e.place != null) SmallIconButton(Sym.DIRECTIONS, stringResource(R.string.event_directions)) { close(); Calendar.directions(e) }
        if (e.link != null) SmallIconButton(Sym.VIDEOCAM, stringResource(R.string.common_join), MaterialTheme.colorScheme.primary) { close(); Calendar.join(e) }
    }
}

/**
 * Next meeting: the next meeting before 03:00 tomorrow ([Meetings]) and a countdown ("Standup in 12m",
 * "Standup · 24m left"), accented from "Show when" minutes before it until it ends. With no meeting
 * left in that horizon, just the icon. The menu lists the horizon's meetings.
 */
object EventItem : ItemType("event", R.string.item_event_title, Sym.GROUPS, R.string.item_event_desc) {
    override val group = ItemGroup.TIME
    override val presents = true
    override val refreshMs = 5_000L
    // Room for Join and Directions next to a meeting.
    override val menuWidthDp = 340
    override val canBeActive = true
    override val permissions = listOf(Manifest.permission.READ_CALENDAR)
    /** How long before a meeting the item pops out (with "Show when") and turns the accent color. */
    val before = Threshold("soonMin", 15, 1..60) { Env.plural(R.plurals.common_minutes_short, it, it) }
    override val trigger = Trigger(R.string.trigger_event, R.string.trigger_event_short, before)
    /** How many characters of a title the bar shows. */
    val chars = IntOpt("chars", 20, 8..40)

    override fun onLive() = Calendar.hold(type)
    override fun onIdle() = Calendar.release(type)

    override val liveSource = true

    /**
     * For the Live activity: a meeting from ten minutes before it starts until ten minutes in ([LiveRules.meeting]), in the
     * words of [state], its title cut so that the countdown stays whole. Woken with no item of its own, nobody runs [state],
     * so this asks for the calendar's refresh as [state] does (it reads again only what has grown old). Without calendar
     * access, nothing.
     */
    override fun liveNow(now: Long, items: List<ItemConfig>): LiveNow? {
        if (!Calendar.allowed()) return null
        Calendar.refresh()
        val wall = System.currentTimeMillis()
        val e = LiveRules.meeting(Calendar.meetings(wall), wall) ?: return null
        val max = items.firstOrNull()?.let(chars::of) ?: chars.default
        val since = now - (wall - (e.begin - LiveRules.MEETING_MS)).coerceAtLeast(0)
        if (e.begin <= wall) {
            val left = Fmt.duration(e.end - wall)
            return LiveNow(LiveRank.MEETING, Sym.GROUPS, LiveRules.around(e.title, max) { Env.str(R.string.event_now_text, it, left) },
                Env.str(R.string.event_now_desc, e.title, Fmt.spokenMinutes(e.end - wall)), Tone.ACCENT, since, open = { Calendar.open(e) })
        }
        val until = (e.begin - wall).coerceAtLeast(60_000)
        val whenText = Env.str(R.string.event_in, Fmt.duration(until))
        return LiveNow(LiveRank.MEETING, Sym.GROUPS, LiveRules.around(e.title, max) { Env.str(R.string.event_next_text, it, whenText) },
            Env.str(R.string.event_next_desc, e.title, Env.str(R.string.event_in, Fmt.spokenMinutes(until))), Tone.ACCENT, since, open = { Calendar.open(e) })
    }

    override fun state(item: ItemConfig): ItemState {
        if (!Calendar.allowed()) return ItemState(icon = Sym.GROUPS, desc = Env.str(R.string.event_access_needed))
        Calendar.refresh()
        val now = System.currentTimeMillis()
        val max = chars.of(item)
        Calendar.current(now)?.let { e ->
            val left = Fmt.duration(e.end - now)
            return ItemState(icon = Sym.GROUPS, text = Env.str(R.string.event_now_text, TextRules.short(e.title, max), left), label = e.title, active = true,
                tone = Tone.ACCENT, desc = Env.str(R.string.event_now_desc, e.title, Fmt.spokenMinutes(e.end - now)))
        }
        val next = Calendar.next(now) ?: return ItemState(icon = Sym.GROUPS, desc = Env.str(R.string.event_no_more))
        val until = next.begin - now
        val near = until <= before.of(item) * 60_000L
        val whenText = if (until < 60 * 60_000L) Env.str(R.string.event_in, Fmt.duration(until.coerceAtLeast(60_000))) else shortTime(next.begin)
        val whenSaid = if (until < 60 * 60_000L) Env.str(R.string.event_in, Fmt.spokenMinutes(until.coerceAtLeast(60_000))) else whenText
        return ItemState(icon = Sym.GROUPS, text = Env.str(R.string.event_next_text, TextRules.short(next.title, max), whenText), label = next.title, active = near,
            tone = if (near) Tone.ACCENT else Tone.NORMAL, desc = Env.str(R.string.event_next_desc, next.title, whenSaid))
    }

    override val menu: @Composable (ItemConfig, MenuHost) -> Unit = { _, host ->
        rememberTick()
        if (!Calendar.allowed()) {
            MenuCard(Sym.GROUPS, stringResource(R.string.event_today), stringResource(R.string.event_needs_calendar)) {
                MenuEntry(Sym.EVENT, stringResource(R.string.calendar_allow)) { host.close(); MainActivity.requestPermission(Env.app, Manifest.permission.READ_CALENDAR) }
            }
        } else {
            Calendar.refresh()
            val now = System.currentTimeMillis()
            val today = LocalDate.now()
            MenuCard(Sym.GROUPS, stringResource(R.string.event_today), Dates.format("EEEEdMMMM", today)) {
                val list = Calendar.meetings(now)
                if (list.isEmpty()) Text(stringResource(R.string.event_no_more), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                var lastDay = today
                list.forEach { e ->
                    // A meeting after midnight (the horizon runs to 03:00) gets its day above it.
                    val d = Instant.ofEpochMilli(e.begin).atZone(ZoneId.systemDefault()).toLocalDate()
                    if (d > lastDay) {
                        Text(Dates.format("EEEEdMMM", d), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                        lastDay = d
                    }
                    EventRow(e, host::close)
                }
                MenuDivider()
                MenuEntry(Sym.OPEN_IN_NEW, stringResource(R.string.calendar_open)) { host.close(); Calendar.openDay(now) }
            }
        }
    }

    override val options: @Composable (ItemConfig, (ItemConfig) -> Unit) -> Unit = { item, set ->
        val res = androidx.compose.ui.platform.LocalResources.current
        SliderRow(stringResource(R.string.event_longest_title), chars.of(item), chars.range,
            { res.getQuantityString(R.plurals.event_characters, it, it) }) { set(chars.set(item, it)) }
    }
}

object TimerItem : ItemType("timer", R.string.item_timer_title, Sym.TIMER, R.string.item_timer_desc) {
    override val group = ItemGroup.TIME
    override val presents = true
    override val canBeActive = true
    // Room for the four presets and Custom on one line.
    override val menuWidthDp = 380

    /** The presets, in minutes. */
    private val presets = listOf(5, 10, 25, 60)

    override val trigger = Trigger(R.string.trigger_timer, R.string.trigger_timer_short)

    override val liveSource = true

    /**
     * For the Live activity: the timer while it runs, its face as the bar has it, and for the minute the bar says "Done"
     * the one that has rung ([LiveRules.timer]). [Timers] is at hand and checked on every tick: nothing is asked of anybody.
     */
    override fun liveNow(now: Long, items: List<ItemConfig>): LiveNow? {
        val s = Timers.state.value
        val wall = Timers.now()
        val says = LiveRules.timer(s, Timers.finishedAt, wall) ?: return null
        if (says == LiveRules.Timer.DONE || s == null) return LiveNow(LiveRank.TIMER_DONE, Sym.TIMER, Env.str(R.string.common_done),
            Env.str(R.string.timer_finished_desc), Tone.ALERT, since = now - (wall - Timers.finishedAt).coerceAtLeast(0))
        if (s.mode == Timers.Mode.STOPWATCH) {
            val taken = Timers.elapsed(s, wall)
            return LiveNow(LiveRank.TIMER, Sym.AVG_PACE, Fmt.clock(taken, elapsed = true), Env.str(R.string.timer_stopwatch_desc, Fmt.spoken(taken)),
                since = now - taken)
        }
        val left = Timers.remaining(s, wall)
        val onBreak = s.mode == Timers.Mode.POMODORO && s.phase != Timers.Phase.WORK
        return LiveNow(LiveRank.TIMER, if (onBreak) Sym.COFFEE else Sym.TIMER, Fmt.clock(left),
            Env.str(if (onBreak) R.string.timer_break_left_desc else R.string.timer_left_desc, Fmt.spokenLeft(left)),
            if (left < 60_000) Tone.ACCENT else Tone.NORMAL, since = now - (s.lengthMs - left).coerceAtLeast(0))
    }

    override fun state(item: ItemConfig): ItemState {
        val s = Timers.state.value
        val now = Timers.now()
        if (s == null) {
            val done = Timers.finishedAt != 0L && now - Timers.finishedAt < 60_000
            return if (done) ItemState(icon = Sym.TIMER, filled = true, text = Env.str(R.string.common_done), active = true, tone = Tone.ALERT,
                desc = Env.str(R.string.timer_finished_desc))
            else ItemState(icon = Sym.TIMER, desc = Env.str(R.string.item_timer_title))
        }
        return when (s.mode) {
            Timers.Mode.STOPWATCH -> ItemState(icon = if (s.running) Sym.AVG_PACE else Sym.PAUSE, filled = true,
                text = Fmt.clock(Timers.elapsed(s, now), elapsed = true), active = true, widthKey = "stopwatch",
                desc = Env.str(R.string.timer_stopwatch_desc, Fmt.spoken(Timers.elapsed(s, now))))
            else -> {
                val left = Timers.remaining(s, now)
                val onBreak = s.mode == Timers.Mode.POMODORO && s.phase != Timers.Phase.WORK
                ItemState(icon = if (!s.running) Sym.PAUSE else if (onBreak) Sym.COFFEE else Sym.TIMER, filled = true,
                    text = Fmt.clock(left), active = true, tone = if (left < 60_000 && s.running) Tone.ACCENT else Tone.NORMAL,
                    widthKey = "countdown",
                    desc = Env.str(if (onBreak) R.string.timer_break_left_desc else R.string.timer_left_desc, Fmt.spokenLeft(left)))
            }
        }
    }

    override val usesWheel = true
    override fun onScroll(item: ItemConfig, steps: Int) {
        val s = Timers.state.value
        if (s == null) Timers.startTimer(steps.coerceAtLeast(1) * 60_000L) else Timers.add(steps * 60_000L)
    }

    override val menu: @Composable (ItemConfig, MenuHost) -> Unit = { item, host ->
        rememberTick()
        val s = Timers.state.collectAsStateValue()
        if (s == null) {
            var showCustom by remember { mutableStateOf(false) }
            MenuCard(Sym.TIMER, stringResource(R.string.item_timer_title), stringResource(R.string.timer_menu_hint)) {
                val labels = presets.map {
                    if (it < 60) pluralStringResource(R.plurals.common_minutes_short, it, it) else pluralStringResource(R.plurals.common_hours, it / 60, it / 60)
                } + stringResource(R.string.timer_custom)
                ChipRow(labels) { i ->
                    if (i < presets.size) Timers.startTimer(presets[i] * 60_000L, item.opt("label", "")) else showCustom = !showCustom
                }
                if (showCustom) CustomMinutes { Timers.startTimer(it * 60_000L, item.opt("label", "")) }
                MenuDivider()
                MenuEntry(Sym.AVG_PACE, stringResource(R.string.timer_stopwatch)) { Timers.startStopwatch() }
                MenuEntry(Sym.COFFEE, stringResource(R.string.timer_pomodoro), stringResource(R.string.timer_pomodoro_detail)) { Timers.startPomodoro() }
            }
        } else {
            val now = Timers.now()
            val title = when (s.mode) {
                Timers.Mode.STOPWATCH -> stringResource(R.string.timer_stopwatch)
                Timers.Mode.POMODORO -> stringResource(if (s.phase == Timers.Phase.WORK) R.string.timer_focus else R.string.timer_break)
                Timers.Mode.TIMER -> s.label.ifBlank { stringResource(R.string.item_timer_title) }
            }
            val sub = when (s.mode) {
                Timers.Mode.POMODORO -> stringResource(R.string.timer_round, s.round + if (s.phase == Timers.Phase.WORK) 1 else 0)
                Timers.Mode.STOPWATCH -> stringResource(if (s.running) R.string.timer_running else R.string.common_paused)
                Timers.Mode.TIMER -> if (s.running) stringResource(R.string.common_ends_at, shortTime(s.at)) else stringResource(R.string.common_paused)
            }
            MenuCard(if (s.mode == Timers.Mode.STOPWATCH) Sym.AVG_PACE else Sym.TIMER, title, sub) {
                val face = if (s.mode == Timers.Mode.STOPWATCH) Timers.elapsed(s, now) else Timers.remaining(s, now)
                Text(Fmt.clock(face, elapsed = s.mode == Timers.Mode.STOPWATCH), style = MaterialTheme.typography.headlineLarge.copy(fontFeatureSettings = "tnum"),
                    modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                if (s.mode != Timers.Mode.STOPWATCH && s.lengthMs > 0) {
                    Meter(1f - Timers.remaining(s, now).toFloat() / s.lengthMs)
                    Spacer(Modifier.height(10.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalButton(onClick = { Timers.toggle() }, modifier = Modifier.weight(1f),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp)) {
                        SymIcon(if (s.running) Sym.PAUSE else Sym.PLAY_ARROW, size = 18.sp); Spacer(Modifier.width(6.dp))
                        Text(stringResource(if (s.running) R.string.common_pause else R.string.common_resume), maxLines = 1)
                    }
                    if (s.mode != Timers.Mode.STOPWATCH) OutlinedButton(onClick = { Timers.add(60_000) },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp)) { Text(stringResource(R.string.common_plus_one_min), maxLines = 1) }
                    androidx.compose.material3.OutlinedIconButton(onClick = { Timers.stop() }) {
                        SymIcon(if (s.mode == Timers.Mode.STOPWATCH) Sym.RESTART_ALT else Sym.STOP, size = 20.sp,
                            contentDescription = stringResource(if (s.mode == Timers.Mode.STOPWATCH) R.string.timer_reset_stopwatch else R.string.timer_stop))
                    }
                }
            }
        }
    }

    override val options: @Composable (ItemConfig, (ItemConfig) -> Unit) -> Unit = { item, set ->
        TextRow(stringResource(R.string.timer_name), item.opt("label", ""), placeholder = stringResource(R.string.item_timer_title),
            help = stringResource(R.string.timer_name_help), maxLength = 30) {
            set(item.with("label", it.take(30).ifBlank { null }))
        }
    }
}

/**
 * The minutes field behind the timer's Custom chip: focused when it appears, with a Start button
 * that's enabled once the field holds a number of minutes. Enter starts too.
 */
@Composable
private fun CustomMinutes(onStart: (Int) -> Unit) {
    var text by remember { mutableStateOf("") }
    val minutes = text.toIntOrNull()?.takeIf { it > 0 }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    fun start() { minutes?.let(onStart) }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { v -> text = v.filter(Char::isDigit).take(4) },
            label = { Text(stringResource(R.string.timer_minutes)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { start() }, onDone = { start() }),
            modifier = Modifier.weight(1f).focusRequester(focus).onPreviewKeyEvent { e ->
                // A hardware keyboard's Enter, which doesn't always arrive as an IME action. Both key
                // down and up are consumed, so the IME action can't start the timer a second time.
                if (e.key == Key.Enter || e.key == Key.NumPadEnter) { if (e.type == KeyEventType.KeyDown) start(); true } else false
            },
        )
        FilledTonalButton(onClick = { start() }, enabled = minutes != null) { Text(stringResource(R.string.timer_start)) }
    }
}

object CountdownItem : ItemType("countdown", R.string.item_countdown_title, Sym.HOURGLASS_TOP, R.string.item_countdown_desc) {
    override val group = ItemGroup.TIME
    override val presents = true
    override val refreshMs = 10_000L
    override val canBeActive = true
    override val trigger = Trigger(R.string.trigger_countdown, R.string.trigger_countdown_short)
    /** The name before the time. */
    val label = Opt.text("label", 16)

    /** "2026-12-24 18:00". Strict: February 30 is an error, not March 2 or February 28. */
    private val format = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withResolverStyle(java.time.format.ResolverStyle.STRICT)

    fun parse(text: String): LocalDateTime? = runCatching { LocalDateTime.parse(text.trim(), format) }.getOrNull()

    fun target(item: ItemConfig): LocalDateTime? = parse(item.opt("at", ""))

    override fun state(item: ItemConfig): ItemState {
        val t = target(item) ?: return ItemState(icon = Sym.HOURGLASS_TOP, text = Env.str(R.string.countdown_set_date), desc = Env.str(R.string.countdown_no_date))
        val left = t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() - System.currentTimeMillis()
        val name = label.of(item)
        val text = if (left <= 0) "${name.ifBlank { Env.str(R.string.countdown_now) }} ✓" else (if (name.isBlank()) "" else "$name ") + Fmt.duration(left)
        return ItemState(icon = Sym.HOURGLASS_TOP, text = text.trim(), active = left in 0..86_400_000L,
            desc = Env.str(R.string.countdown_desc, name.ifBlank { Env.str(R.string.item_countdown_title) }, Fmt.spokenMinutes(left.coerceAtLeast(0))))
    }

    override val menu: @Composable (ItemConfig, MenuHost) -> Unit = { item, host ->
        rememberTick()
        val t = target(item)
        val name = stringResource(R.string.item_countdown_title)
        MenuCard(Sym.HOURGLASS_TOP, label.of(item).ifBlank { name },
            t?.let { Dates.format("EEEEdMMMMyyyy" + Dates.timeSkeleton(DateFormat.is24HourFormat(Env.app)), it.atZone(ZoneId.systemDefault())) }) {
            if (t != null) {
                val left = t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() - System.currentTimeMillis()
                InfoRow(stringResource(R.string.countdown_time_left), if (left > 0) Fmt.duration(left) else stringResource(R.string.common_done))
                InfoRow(stringResource(R.string.countdown_days), (left / 86_400_000L).coerceAtLeast(0).toString())
            }
            MenuDivider()
            MenuEntry(Sym.EDIT, stringResource(R.string.countdown_change_date)) { host.openItemSettings(item.id) }
        }
    }

    override val options: @Composable (ItemConfig, (ItemConfig) -> Unit) -> Unit = { item, set ->
        TextRow(stringResource(R.string.option_label), label.of(item), placeholder = stringResource(R.string.countdown_label_hint), maxLength = 16) {
            set(label.set(item, it))
        }
        val at = item.opt("at", "")
        TextRow(stringResource(R.string.countdown_date_time), at, placeholder = "2026-12-24 18:00",
            help = stringResource(R.string.countdown_date_time_help),
            error = if (at.isNotBlank() && parse(at) == null) stringResource(R.string.countdown_date_invalid) else null) {
            set(item.with("at", it.trim().ifBlank { null }))
        }
    }

    // No default date: a new countdown says "Set a date".
}

@Composable
private fun <T> kotlinx.coroutines.flow.StateFlow<T>.collectAsStateValue(): T = collectAsState().value
