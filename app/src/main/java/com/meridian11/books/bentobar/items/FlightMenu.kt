package com.meridian11.books.bentobar.items

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.meridian11.books.bentobar.ui.Bento
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.key.key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.data.Online
import com.meridian11.books.bentobar.items.AirLabs.Failure
import com.meridian11.books.bentobar.items.FlightRules.Kind
import com.meridian11.books.bentobar.items.FlightRules.Tracked
import com.meridian11.books.bentobar.items.FlightSamples.Staged
import com.meridian11.books.bentobar.items.FlightText.TimeForm
import com.meridian11.books.bentobar.items.FlightText.Voice
import com.meridian11.books.bentobar.items.FlightText.Word
import com.meridian11.books.bentobar.ui.ChipRow
import com.meridian11.books.bentobar.ui.CopyEntry
import com.meridian11.books.bentobar.ui.KeyWords
import com.meridian11.books.bentobar.ui.MenuCard
import com.meridian11.books.bentobar.ui.MenuDivider
import com.meridian11.books.bentobar.ui.MenuEntry
import com.meridian11.books.bentobar.ui.MenuMotion
import com.meridian11.books.bentobar.ui.MenuNote
import com.meridian11.books.bentobar.ui.OffGate
import com.meridian11.books.bentobar.ui.SearchField
import com.meridian11.books.bentobar.ui.SearchWithResults
import com.meridian11.books.bentobar.ui.ServiceKey
import com.meridian11.books.bentobar.ui.fadeIn
import com.meridian11.books.bentobar.ui.part
import com.meridian11.books.bentobar.ui.rememberPart
import com.meridian11.books.bentobar.ui.rememberTick
import com.meridian11.books.bentobar.util.Fonts
import com.meridian11.books.bentobar.util.Now
import com.meridian11.books.bentobar.util.Sym
import java.time.Instant
import java.time.LocalDate

/** A press of Track that came to nothing, for the words under the field: the number as it is shown, why, and the day that was chosen. */
private class FlightMiss(val number: String, val failure: Failure, val day: LocalDate?)

/**
 * A press of Track that found the number flying more than once, for the entries under the field: the
 * number as it is shown, the day that was chosen, what the card asks, and the flights to choose from,
 * each in the place it is chosen by.
 */
private class FlightChoice(val number: String, val day: LocalDate?, val asks: String, val flights: List<FlightText.Choice?>)

private fun flightChoice(number: String, day: LocalDate?, flights: List<Tracked>, now: Instant, v: Voice) =
    FlightChoice(number, day, FlightText.several(number, day, flights.size, v), flights.map { t -> t.flight?.let { FlightText.choice(it, now, v) } })

/**
 * The Flight item's menu. Which card it is follows from what is known, never from a step the user
 * is on: no key (the words, and the way to the settings), switched off (the words, and Turn on),
 * looking up, a flight that is followed, or nothing followed (the field, the day chips, the note);
 * and where a press of Track found several flights of the number, the field's card asks which.
 * Everything it says of a flight is worked out by [FlightText]; it redraws with the ticker, so the
 * figures count by the clock between two answers.
 */
@Composable
internal fun FlightMenu(item: ItemConfig, host: MenuHost) {
    val tick = rememberTick()
    val online by Online.state.collectAsState()
    val asked by FlightItem.search.state.collectAsState()
    // "Track another flight": the field again, while the flight that is followed stays until another is found.
    // It is over once an answer was taken, by this menu or by the item's own tick a moment before it.
    var anotherSince by remember { mutableStateOf<Int?>(null) }
    val another = anotherSince == FlightItem.takes
    // What stood in the field when tracking stopped: it is left there.
    var last by remember { mutableStateOf("") }
    // An answer is taken as soon as it is in. (With the menu closed, the item takes it with its next tick.)
    LaunchedEffect(asked) { FlightItem.takeAnswer() }
    // A message about a lookup that found nothing belongs to the menu it was read in.
    DisposableEffect(item.id) { onDispose { FlightItem.dropFailure(item.id) } }

    val staged = FlightItem.stagedFor(item)
    val service = Online.Service.AIRLABS
    val title = stringResource(R.string.item_flight_title)
    if (staged == Staged.NoKey || (staged == null && service !in online.keyed)) {
        MenuCard(Sym.FLIGHT, title, stringResource(R.string.common_not_set_up)) {
            MenuNote(stringResource(R.string.flight_consent))
            MenuEntry(Sym.KEY, stringResource(R.string.flight_add_key)) { host.openItemSettings(item.id) }
        }
        return
    }
    if (staged == null && service !in online.on) {
        OffGate(Sym.FLIGHT, title, stringResource(R.string.common_off_in_setup), stringResource(R.string.flight_consent), stringResource(R.string.flight_turn_on)) { FlightItem.turnOn() }
        return
    }

    val now = Instant.ofEpochMilli(Now.wall())
    val v = FlightItem.voice()
    // With a sample staged, the real thing is out of sight: what is followed, and any lookup of its own.
    val tracked: Tracked? = when (staged) {
        null -> FlightItem.followed(item.id)
        is Staged.Following -> staged.tracked
        else -> Tracked()
    }
    // A press of Track that found several flights of the number: the search card asks which. (With a sample staged, the sample's.)
    val choice = when {
        staged is Staged.Several -> flightChoice(staged.number, staged.day, staged.flights, now, v)
        staged != null -> null
        else -> (asked as? Ask.State.Done)?.takeIf { it.question.item == item.id }?.let { done ->
            (done.answer as? FlightLoad.Outcome.Several)?.let { flightChoice(done.question.number.shown, done.question.day, it.flights, now, v) }
        }
    }
    // While another flight is being chosen only its own lookup takes the field away, not a retry for the one that stays.
    val busy = when {
        // (The lookup is over when its flights wait to be chosen from, though the bar still shows the number.)
        choice != null -> null
        staged != null -> (staged as? Staged.Looking)?.number
        else -> FlightItem.looking(item.id, tracked?.takeUnless { another })
    }
    val missed = when {
        staged is Staged.Failed -> FlightMiss(staged.number, staged.failure, null)
        staged != null -> null
        else -> (asked as? Ask.State.Done)?.takeIf { it.question.item == item.id }?.let { done ->
            (done.answer as? FlightLoad.Outcome.Failed)?.let { FlightMiss(done.question.number.shown, it.failure, done.question.day) }
        }
    }
    // Not read yet: the item asks for it when it is next looked at, which can be ten seconds off; an open menu asks with every tick.
    if (staged == null && FlightItem.tracker.peek(item.id) == null) LaunchedEffect(tick) { FlightItem.tracker.want(item.id) }
    // The rules take whatever a reply or a file holds; should one trip all the same, the menu still opens, on the field.
    val card = tracked?.let { runCatching { FlightText.card(it, now, v) }.getOrNull() }
    val changeKey = { host.openItemSettings(item.id) }
    when {
        busy != null -> MenuCard(Sym.FLIGHT, title, v.say(Word.FLIGHT_LOOKING_UP, busy)) {}
        // Not read yet (the menu was opened in the item's first moment): what is kept is a moment away.
        tracked == null -> MenuCard(Sym.FLIGHT, title, stringResource(R.string.usage_loading)) {}
        // (The question of which flight comes before the flight that stays meanwhile: it is asked on the search card.)
        card != null && !another && choice == null -> FollowedCard(card, FlightItem.place(card), refresh = staged == null && FlightItem.mayRefresh(item.id),
            upToDate = staged == null && FlightItem.upToDate(item.id),
            onRefresh = { FlightItem.refresh(item.id) }, onChangeKey = changeKey, onPage = { host.close(); FlightItem.openPage(it) },
            onAnother = { if (staged == null) anotherSince = FlightItem.takes else FlightItem.stop(item) },
            onStop = { last = FlightNumber.shown(tracked.number); FlightItem.stop(item) })
        tracked.following && tracked.flight == null && !another && choice == null -> WaitingCard(FlightNumber.shown(tracked.number), tracked.failure, v,
            retry = FlightItem.mayRefresh(item.id), onRetry = { FlightItem.refresh(item.id) }, onChangeKey = changeKey, onAnother = { anotherSince = FlightItem.takes },
            onStop = { last = FlightNumber.shown(tracked.number); FlightItem.stop(item) })
        else -> {
            // Another flight is being chosen only while there is one that stays meanwhile.
            val staying = FlightNumber.shown(tracked.number).takeIf { another && tracked.following }
            SearchCard(item, v, LocalDate.ofInstant(now, v.zone), initial = missed?.number ?: choice?.number ?: last, missed = missed, choice = choice, staying = staying,
                left = FlightLoad.left, onChangeKey = changeKey, onChoose = { FlightItem.choose(item, it) },
                onCancel = if (staying != null) { { anotherSince = null; FlightItem.dropFailure(item.id) } } else null)
        }
    }
}

/**
 * Nothing is followed (or another flight is being chosen, [staying] being the one that stays until
 * then): the field, the day, and what a lookup costs. Under the field stands why the last press of
 * Track found nothing; text that is no flight number is told so, and nothing is sent for it.
 *
 * Where the press found the number flying more than once ([choice]), the card asks which: its
 * subtitle says what is asked, and the flights stand under the field as entries, with Cancel under
 * them. A click or Enter chooses one ([onChoose], with its place among them). Changing the number,
 * Cancel and closing the menu drop the question: nothing is followed for it.
 */
@Composable
private fun SearchCard(item: ItemConfig, v: Voice, today: LocalDate, initial: String, missed: FlightMiss?, choice: FlightChoice?, staying: String?, left: Int?,
                       onChangeKey: () -> Unit, onChoose: (Int) -> Unit, onCancel: (() -> Unit)?) {
    var text by remember { mutableStateOf(initial) }
    val days = remember(today) { FlightText.days(today) }
    // The card was away while the number was looked up: it comes back with the number and with the day that was chosen.
    var day by remember { mutableIntStateOf((missed?.day ?: choice?.day)?.let { days.indexOf(it) }?.coerceAtLeast(0) ?: 0) }
    var notNumber by remember { mutableStateOf(false) }
    val error = when {
        notNumber -> stringResource(R.string.flight_err_not_number)
        missed != null && FlightText.ofTheField(missed.failure) -> FlightText.error(missed.failure, missed.number, missed.day, v)
        else -> null
    }
    MenuCard(Sym.FLIGHT, stringResource(R.string.item_flight_title), choice?.asks ?: staying?.let { v.say(Word.FLIGHT_ANOTHER_SUBTITLE, it) } ?: stringResource(R.string.flight_none)) {
        // The flights take the focus when they come, after the field (which asks for it when it appears): Enter chooses the first, the arrow keys go to the others.
        SearchWithResults(hasResults = choice != null, arrival = choice != null,
            field = {
                SearchField(label = stringResource(R.string.flight_number_label), placeholder = stringResource(R.string.flight_number_hint), initial = initial,
                    submit = stringResource(R.string.flight_track), selectAll = true, error = error, canSubmit = text.isNotBlank(), focus = field,
                    onChange = { text = it; notNumber = false; FlightItem.dropFailure(item.id) },
                    onEnter = { entered ->
                        val question = FlightLoad.question(item.id, entered, days.getOrNull(day), v.zone)
                        when {
                            // The flights of this number and day are here already: Track and Enter go to them, and nothing is looked up a second time.
                            choice != null -> toFirst()
                            question == null -> notNumber = true
                            else -> FlightItem.track(question, item)
                        }
                    })
            },
            results = { firstResult ->
                if (choice != null) {
                    val top = choice.flights.indexOfFirst { it != null }
                    choice.flights.forEachIndexed { i, flight ->
                        if (flight != null) MenuEntry(Sym.FLIGHT_TAKEOFF, flight.title, sub = flight.detail.ifEmpty { null },
                            modifier = (if (i != top) Modifier else firstResult).semantics { contentDescription = flight.spoken }) { onChoose(i) }
                    }
                    MenuDivider()
                    MenuEntry(Sym.CLOSE, stringResource(R.string.common_cancel)) { FlightItem.dropFailure(item.id); toField() }
                } else {
                    if (missed != null && error == null) {
                        // The status under a search field is the one thing a menu says unasked: a screen reader hears what the press of Track came to.
                        Text(FlightText.error(missed.failure, missed.number, missed.day, v), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 6.dp).semantics { liveRegion = LiveRegionMode.Polite })
                        if (missed.failure == Failure.REFUSED) MenuEntry(Sym.KEY, stringResource(R.string.flight_change_key), onClick = onChangeKey)
                    }
                    Spacer(Modifier.height(8.dp))
                    ChipRow(days.mapIndexed { i, d ->
                        when (i) {
                            0 -> stringResource(R.string.flight_day_next)
                            1 -> stringResource(R.string.calendar_today)
                            2 -> stringResource(R.string.calendar_tomorrow)
                            else -> d?.let { v.time(it.atStartOfDay(), TimeForm.DAY) }.orEmpty()
                        }
                    }, selected = day) { day = it; FlightItem.dropFailure(item.id) }
                    Spacer(Modifier.height(4.dp))
                    MenuNote(listOfNotNull(stringResource(R.string.flight_lookup_note), left?.let { pluralStringResource(R.plurals.flight_lookups_left, it, it) }).joinToString("\n"))
                    if (onCancel != null) {
                        MenuDivider()
                        MenuEntry(Sym.CLOSE, stringResource(R.string.common_cancel), onClick = onCancel)
                    }
                }
            })
    }
}

/** A flight that is followed: the one thing needed as the headline, whether it runs to plan under it, the flight as a line, and what can be done. */
@Composable
private fun FollowedCard(card: FlightText.Card, share: Double?, refresh: Boolean, upToDate: Boolean, onRefresh: () -> Unit, onChangeKey: () -> Unit, onPage: (String) -> Unit,
                         onAnother: () -> Unit, onStop: () -> Unit) {
    MenuCard(Sym.FLIGHT, card.title, card.route) {
        // Headline and badge are one thing to a screen reader: "Leaves in 1 hour 37 minutes. Delayed 25 minutes."
        Column(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = card.spoken }) {
            // No tabular figures here or in the two times below: in this font they widen the spaces between the words as well.
            Text(card.headline, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (card.gone) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            if (card.badge != null) {
                Spacer(Modifier.height(4.dp))
                StatusBadge(card.badge, card.kind)
            }
        }
        Spacer(Modifier.height(12.dp))
        RouteLine(card.from, card.to, share)
        Spacer(Modifier.height(8.dp))
        if (card.operatedAs != null) MenuNote(card.operatedAs)
        if (card.loose) MenuNote(stringResource(R.string.flight_note_timetable))
        MenuNote(card.note)
        MenuDivider()
        if (card.changeKey) MenuEntry(Sym.KEY, stringResource(R.string.flight_change_key), onClick = onChangeKey)
        // Dimmed because the service was just asked and answered: say so, or the entry looks broken.
        MenuEntry(Sym.REFRESH, stringResource(R.string.common_refresh), detail = if (upToDate) stringResource(R.string.flight_up_to_date) else null,
            enabled = refresh, onClick = onRefresh)
        if (card.callsign != null) {
            val says = stringResource(R.string.flight_open_page_desc)
            MenuEntry(Sym.OPEN_IN_NEW, stringResource(R.string.flight_open_page), modifier = Modifier.semantics { contentDescription = says }) { onPage(card.callsign) }
        }
        CopyEntry(stringResource(R.string.flight_copy_status)) { card.copy }
        MenuEntry(Sym.SEARCH, stringResource(R.string.flight_track_another), onClick = onAnother)
        MenuEntry(Sym.CLOSE, stringResource(R.string.flight_stop), onClick = onStop)
    }
}

/**
 * A flight the item follows and has heard nothing of yet: the service was switched off, which
 * deletes what it said, and the lookup after it came back found no connection, or no such flight
 * any more. The number, why, and what can be done.
 */
@Composable
private fun WaitingCard(number: String, failure: Failure?, v: Voice, retry: Boolean, onRetry: () -> Unit, onChangeKey: () -> Unit, onAnother: () -> Unit, onStop: () -> Unit) {
    MenuCard(Sym.FLIGHT, number, null) {
        if (failure != null) Text(FlightText.error(failure, number, null, v), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 6.dp))
        MenuDivider()
        if (failure == Failure.REFUSED) MenuEntry(Sym.KEY, stringResource(R.string.flight_change_key), onClick = onChangeKey)
        MenuEntry(Sym.REFRESH, stringResource(R.string.common_try_again), enabled = retry, onClick = onRetry)
        MenuEntry(Sym.SEARCH, stringResource(R.string.flight_track_another), onClick = onAnother)
        MenuEntry(Sym.CLOSE, stringResource(R.string.flight_stop), onClick = onStop)
    }
}

/**
 * A flight as a line between its two airports, with their times and a few small words under its
 * ends. The part flown is solid and the part to fly dotted, with the plane where the two meet; with
 * no [share] (a flight that will not happen, or of which nobody knows where it is) the line is faint
 * dots and has no plane. Not a control; to a screen reader it is two sentences.
 */
@Composable
internal fun RouteLine(from: FlightText.End, to: FlightText.End, share: Double?) {
    val spoken = from.spoken + " " + to.spoken
    Column(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = spoken }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Codes, times and words fade in as rows; the line arrives on its own (FlightPath).
            Text(from.code, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1,
                modifier = Modifier.fadeIn(0f))
            Spacer(Modifier.width(8.dp))
            FlightPath(share, Modifier.weight(1f).height(24.dp))
            Spacer(Modifier.width(8.dp))
            Text(to.code, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1,
                modifier = Modifier.fadeIn(0f))
        }
        Spacer(Modifier.height(2.dp))
        Row(Modifier.fadeIn(0f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EndTime(from, TextAlign.Start, Modifier.weight(1f))
            EndTime(to, TextAlign.End, Modifier.weight(1f))
        }
        if (from.words.isNotEmpty() || to.words.isNotEmpty()) Row(Modifier.fadeIn(0f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EndWords(from.words, TextAlign.Start, Modifier.weight(1f))
            EndWords(to.words, TextAlign.End, Modifier.weight(1f))
        }
    }
}

/** An end's time; struck through and quieter when it will not happen. */
@Composable
private fun EndTime(end: FlightText.End, align: TextAlign, modifier: Modifier) {
    Text(end.time, modifier = modifier, textAlign = align, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium,
        style = MaterialTheme.typography.bodyMedium.copy(textDecoration = if (end.struck) TextDecoration.LineThrough else null),
        color = if (end.struck) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
}

@Composable
private fun EndWords(words: String, align: TextAlign, modifier: Modifier) {
    Text(words, modifier = modifier, textAlign = align, maxLines = 1, overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * The line itself. Dots of 3 dp about 8 dp apart, the first and the last on the line's two ends; the
 * part flown a solid 3 dp line up to 4 dp behind the plane; the plane 20 sp, nose to the arrival,
 * its center half its length in at the start (10 dp, at the usual text size) and as far from the end
 * when it has landed. No dot stands under the plane or within 2 dp of its nose. It moves in steps, as
 * answers and minutes arrive: nothing glides, but for once as its popup opens (motion.md §7.2): the dots
 * fade in, and the plane flies out from the departure to where it is, the part flown drawing behind it.
 * Where each of these stands is [FlightLine]'s arithmetic; this only draws it.
 */
@Composable
private fun FlightPath(share: Double?, modifier: Modifier) {
    val flown = MaterialTheme.colorScheme.primary
    val ahead = if (share == null) MaterialTheme.colorScheme.outlineVariant else MaterialTheme.colorScheme.outline
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val plane = remember(measurer, density) { measurer.measure(Sym.FLIGHT, TextStyle(fontFamily = Fonts.symbolsFilled, fontSize = 20.sp, lineHeight = 20.sp)) }
    val part = rememberPart(30f)
    Canvas(modifier.part(part)) {
        val ms = part?.ms() ?: Float.POSITIVE_INFINITY
        val shown = MenuMotion.fade(ms)
        // The plane's flight takes 160 ms plus 160 times the share flown, on the glass's curve: one just off lands sooner.
        val flying = share?.let { it * MenuMotion.OPENS.at(ms / (160f + 160f * it.coerceIn(0.0, 1.0).toFloat())) }
        val dot = 3.dp.toPx()
        // The plane is a symbol and grows with the text size: the line makes room for it as it is.
        val line = FlightLine.of(size.width, flying, dot = dot, apart = 8.dp.toPx(), plane = 20.sp.toPx(), behind = 4.dp.toPx(), ahead = 2.dp.toPx())
        val y = size.height / 2
        // The line is reckoned from the departure's end, which is on the right where the language reads from there.
        fun x(along: Float) = if (rtl) size.width - along else along
        line.flownUntil?.let { drawLine(flown, Offset(x(line.start), y), Offset(x(it), y), strokeWidth = dot, cap = StrokeCap.Round) }
        for (at in line.dots) drawCircle(ahead, dot / 2, Offset(x(at), y), alpha = shown)
        line.center?.let { center ->
            rotate(if (rtl) -90f else 90f, Offset(x(center), y)) {
                drawText(plane, color = flown, topLeft = Offset(x(center) - plane.size.width / 2f, y - plane.size.height / 2f), alpha = shown)
            }
        }
    }
}

/**
 * Whether a flight runs to plan, as a small pill: plain for "Planned", in the theme's own good news
 * for on time and early, in its error tones for late. Lower than a chip, and neither a button nor a
 * focus stop; what it says is in its words, never in its color alone.
 */
@Composable
internal fun StatusBadge(text: String, kind: Kind) {
    val scheme = MaterialTheme.colorScheme
    val (fill, ink) = when (kind) {
        Kind.PLAIN -> scheme.surfaceContainerHighest to scheme.onSurfaceVariant
        Kind.GOOD -> scheme.primaryContainer to scheme.onPrimaryContainer
        Kind.LATE -> scheme.errorContainer to scheme.onErrorContainer
    }
    Box(Modifier.height(22.dp).clip(RoundedCornerShape(11.dp)).background(fill).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The key, in the item's settings: the shared [ServiceKey] under its name, with how many lookups it has left where that is
 * known, and the service's refusal of it (heard for [item]'s flight) as a notice that offers another key.
 */
@Composable
internal fun FlightKey(item: ItemConfig) {
    rememberTick()
    val online by Online.state.collectAsState()
    val refused = FlightItem.followed(item.id)?.failure == Failure.REFUSED
    ServiceKey(Online.Service.AIRLABS in online.keyed, FLIGHT_KEY, onSave = { FlightItem.saveKey(it) }, onRemove = { FlightItem.removeKey() },
        onGetKey = { FlightItem.openSignUp() }, heading = stringResource(R.string.flight_key_section),
        refused = if (refused) stringResource(R.string.flight_err_refused) else null) {
        FlightLoad.left?.let { Text(pluralStringResource(R.plurals.flight_lookups_left, it, it), style = Bento.type.help, color = Bento.colors.ink2) }
    }
}

private val FLIGHT_KEY = KeyWords(R.string.flight_key_section, R.string.flight_consent, R.string.flight_get_key, R.string.flight_save_key,
    R.string.flight_key_help, R.string.flight_key_saved, R.string.flight_replace_key, R.string.flight_remove_key, R.string.flight_remove_key_help)

