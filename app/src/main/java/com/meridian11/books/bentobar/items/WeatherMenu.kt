package com.meridian11.books.bentobar.items

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.meridian11.books.bentobar.ui.Space
import com.meridian11.books.bentobar.ui.Bento
import com.meridian11.books.bentobar.ui.ButtonKind
import com.meridian11.books.bentobar.ui.BentoButton
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.key.key
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.data.Online
import com.meridian11.books.bentobar.data.Store
import com.meridian11.books.bentobar.ui.ChoiceRow
import com.meridian11.books.bentobar.ui.InfoRow
import com.meridian11.books.bentobar.ui.MainActivity
import com.meridian11.books.bentobar.ui.MenuCard
import com.meridian11.books.bentobar.ui.MenuDivider
import com.meridian11.books.bentobar.ui.MenuEntry
import com.meridian11.books.bentobar.ui.MenuNote
import com.meridian11.books.bentobar.ui.OffGate
import com.meridian11.books.bentobar.ui.SearchField
import com.meridian11.books.bentobar.ui.SearchStatus
import com.meridian11.books.bentobar.ui.SearchWithResults
import com.meridian11.books.bentobar.ui.SectionLabel
import com.meridian11.books.bentobar.ui.Setup
import com.meridian11.books.bentobar.ui.TextRow
import com.meridian11.books.bentobar.ui.fadeIn
import com.meridian11.books.bentobar.ui.popIn
import com.meridian11.books.bentobar.ui.rememberTick
import com.meridian11.books.bentobar.util.Now
import com.meridian11.books.bentobar.util.Sym
import com.meridian11.books.bentobar.util.SymIcon
import java.time.ZoneId

// The Weather item's menu and settings, drawn from what WeatherRules works out. Nothing here asks the
// service: a click that could goes through WeatherItem.source, where the rules for that are.

/** Open-Meteo's site. Its licence asks for the credit and a link; a click opens the browser, which is the user's act and no request of BentoBar's. */
private const val SITE = "https://open-meteo.com/"

/**
 * "Next hours": six cells of time, glyph and temperature, and the chance of rain under them where it
 * is 20% or more. The chance's line keeps its height in every cell when one cell has it, and is left
 * out when none has. A value the reply left out leaves its place empty.
 */
@Composable
internal fun HourStrip(cells: List<HourCell>) {
    val anyChance = cells.any { it.chance != null }
    Row(Modifier.fillMaxWidth()) {
        // Always six places, so the cells stand where they stood when fewer hours are left to show.
        // Each pops in whole, 16 ms after the one to its left (motion.md §7.2).
        for (i in 0 until 6) {
            val cell = cells.getOrNull(i)
            Column(Modifier.popIn(10f * i, 0.9f).weight(1f).clearAndSetSemantics { if (cell != null) contentDescription = cell.desc },
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text(cell?.time.orEmpty(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                Spacer(Modifier.height(4.dp))
                SymIcon(cell?.glyph.orEmpty(), size = 20.sp, filled = true, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(4.dp))
                Text(cell?.temp.orEmpty(), style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                    fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                if (anyChance) {
                    Spacer(Modifier.height(2.dp))
                    Text(cell?.chance.orEmpty(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                }
            }
        }
    }
}

/** One of "Next days": the weekday, its glyph, the chance from 20%, then high and low as two aligned columns of numbers. */
@Composable
internal fun DayRow(line: DayLine) {
    Row(Modifier.fillMaxWidth().heightIn(min = 32.dp).clearAndSetSemantics { contentDescription = line.desc },
        verticalAlignment = Alignment.CenterVertically) {
        // Left to right as it reads (motion.md §7.2): the day, its glyph popping, the chance, high, low.
        Text(line.day, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1,
            modifier = Modifier.fadeIn(0f).width(44.dp))
        // The glyph's place stays when the reply named no condition, so the chances stay in line.
        Box(Modifier.popIn(12f, 0.8f).widthIn(min = 20.dp), contentAlignment = Alignment.Center) {
            SymIcon(line.glyph.orEmpty(), size = 18.sp, filled = true, color = MaterialTheme.colorScheme.onSurface)
        }
        Spacer(Modifier.width(8.dp))
        if (line.chance != null) Text(line.chance, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1,
            modifier = Modifier.fadeIn(24f))
        Spacer(Modifier.weight(1f))
        Text(line.high.orEmpty(), style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"), fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.End, maxLines = 1, modifier = Modifier.fadeIn(36f).width(40.dp))
        Text(line.low.orEmpty(), style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End, maxLines = 1, modifier = Modifier.fadeIn(48f).width(40.dp))
    }
}

/**
 * After an item chose My location: that is the user's own act, like Search, so the Weather switch goes
 * on; and Android asks for the approximate location if it isn't allowed yet. True: it asks, in
 * BentoBar's settings window, which opens for it (a menu then closes).
 */
internal fun hereChosen(): Boolean {
    WeatherItem.source.turnOn()
    WeatherHere.wake()
    Ticker.refresh()
    if (WeatherHere.allowed()) return false
    askLocation()
    return true
}

/**
 * Android's question for the approximate location, and nothing more: Allow location, in the menu and
 * in the settings, leaves the Weather switch as it is. The answer
 * wakes [WeatherHere] (MainActivity).
 */
internal fun askLocation() = MainActivity.requestPermission(Env.app, Manifest.permission.ACCESS_COARSE_LOCATION)

/** The item's menu: one card for each state the item can be in. */
@Composable
internal fun WeatherMenu(item: ItemConfig, host: MenuHost) {
    // Redrawn with the tick: a reading that came in, the minute passing, Refresh's wait running out.
    rememberTick()
    // The switch can change under an open menu: turned on here, or off in Setup's window beside it.
    val online by Online.state.collectAsState()
    val on = Online.Service.OPEN_METEO in online.on
    val source = WeatherItem.source
    val now = Now.wall()
    val staged = WeatherItem.staged(item) != null
    val place = WeatherLoad.place(item, WeatherHere.place)
    // On opening (and when the city or the switch changes under the menu): a reading older than ten minutes is asked again.
    LaunchedEffect(item.id, place, on, staged) { if (!staged) source.opened(item) }

    var changing by remember(item.id) { mutableStateOf(false) }
    // A press of Refresh shows at once (the entry dims), not at the next tick.
    var pressed by remember { mutableIntStateOf(0) }
    val entry = if (pressed >= 0) WeatherItem.againEntry(item) else Again.WAIT
    val again: () -> Unit = { WeatherItem.again(item); pressed++ }

    val name = stringResource(R.string.item_weather_title)
    val here = WeatherRules.here(item)
    val city = if (here) stringResource(R.string.weather_here) else WeatherRules.city(item) ?: if (staged) WeatherSamples.CITY else name
    val by = "menu:" + item.id
    val pick: (City, String?) -> Unit = { found, region ->
        Store.updateItem(item.id) { WeatherRules.picked(it, found, region) }
        source.typed(by)
        changing = false
        Ticker.refresh()
    }
    val changeCity: @Composable () -> Unit = { MenuEntry(Sym.LOCATION_ON, stringResource(R.string.weather_change_city)) { changing = true } }
    // My location keeps the item's city for a way back: one press, and nothing to search for.
    val kept = WeatherRules.city(item)
    val useCity: @Composable () -> Unit = {
        if (here && kept != null) MenuEntry(Sym.LOCATION_ON, stringResource(R.string.weather_use_city, kept)) {
            Store.updateItem(item.id) { WeatherRules.useCity(it) }
            source.typed(by)
            changing = false
            Ticker.refresh()
        }
    }
    // My location instead of a city. Without the permission yet, the settings window opens to ask for it, and the menu closes.
    val useHere: @Composable () -> Unit = {
        MenuEntry(Sym.MY_LOCATION, stringResource(R.string.weather_use_here)) {
            Store.updateItem(item.id) { WeatherRules.useHere(it) }
            source.typed(by)
            changing = false
            if (hereChosen()) host.close()
        }
    }

    val status = WeatherItem.status(item, now)
    when {
        // The words before anything is sent, and the field. Typing sends nothing; Search (or Enter) sends the name.
        status == Status.NotSetUp -> MenuCard(Sym.CLOUD, name, stringResource(R.string.common_not_set_up)) {
            MenuNote(stringResource(R.string.weather_consent))
            CitySearch(item, by, initial = WeatherRules.cityOf(ZoneId.systemDefault().id), onPick = pick)
            MenuDivider()
            useHere()
        }
        // The same card for another city: no note (it was read before), and a way back.
        changing -> MenuCard(Sym.CLOUD, city, stringResource(R.string.weather_change_city)) {
            CitySearch(item, by, initial = WeatherRules.city(item).orEmpty(), onPick = pick)
            MenuDivider()
            if (!here) useHere() else useCity()
            MenuEntry(Sym.CLOSE, stringResource(R.string.common_cancel)) { source.typed(by); changing = false }
        }
        // A layout that came with a city, or the switch off in Setup: the words, and the one entry that turns it on. Nothing is sent before it.
        status is Status.Off -> OffGate(Sym.CLOUD, city, stringResource(if (status.everOn) R.string.common_off_in_setup else R.string.weather_not_turned_on),
            stringResource(R.string.weather_consent), stringResource(R.string.weather_turn_on)) { source.turnOn(); Ticker.refresh() }
        // My location, and where the device is isn't known: why, the one thing that helps, and a city instead.
        status is Status.NoLocation -> MenuCard(Sym.CLOUD, city, stringResource(when (status.why) {
            Locate.NOT_ALLOWED -> R.string.weather_here_not_allowed
            Locate.OFF -> R.string.weather_here_off
            else -> R.string.weather_here_none
        })) {
            MenuNote(stringResource(when (status.why) {
                Locate.NOT_ALLOWED -> R.string.weather_here_not_allowed_note
                Locate.OFF -> R.string.weather_here_off_note
                else -> R.string.weather_here_none_note
            }))
            when (status.why) {
                Locate.NOT_ALLOWED -> MenuEntry(Sym.MY_LOCATION, stringResource(R.string.weather_allow_location)) {
                    host.close()
                    askLocation()
                }
                Locate.OFF -> MenuEntry(Sym.SETTINGS, stringResource(R.string.weather_location_settings)) {
                    host.close()
                    Env.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
                else -> {}
            }
            useCity()
            changeCity()
        }
        // No spinner: the subtitle says it, and the body shows nothing that isn't known yet.
        status == Status.Loading -> MenuCard(Sym.CLOUD, city, stringResource(R.string.usage_loading)) { useCity(); changeCity() }
        status is Status.Missing -> {
            val offline = status.failure == Failure.OFFLINE
            MenuCard(Sym.CLOUD, city, stringResource(if (offline) R.string.common_no_connection else R.string.weather_no_answer)) {
                if (!offline) Text(stringResource(if (status.failure == Failure.SLOW_DOWN) R.string.weather_no_answer_hour else R.string.weather_no_answer_15),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 6.dp))
                // Dimmed for ten seconds after the try that failed (longer if the service asked for that), with no word of its own: the card says what went wrong.
                MenuEntry(Sym.REFRESH, stringResource(R.string.common_try_again), enabled = entry == Again.READY, onClick = again)
                useCity()
                changeCity()
            }
        }
        status is Status.Live -> {
            val f = WeatherRules.menu(status.reading, WeatherItem.look(item), now, WeatherItem.Words, WeatherItem.times())
            MenuCard(f.glyph, city, f.subtitle) {
                ForecastBody(f)
                MenuDivider()
                // Dimmed for a minute after an answer (an automatic one too), and then it says why.
                MenuEntry(Sym.REFRESH, stringResource(R.string.common_refresh),
                    detail = if (entry == Again.UP_TO_DATE) stringResource(R.string.weather_up_to_date) else null, enabled = entry == Again.READY, onClick = again)
                useCity()
                changeCity()
                val site = stringResource(R.string.weather_open_site)
                val opens = stringResource(R.string.common_opens_browser, site)
                MenuEntry(Sym.OPEN_IN_NEW, site, modifier = Modifier.semantics { contentDescription = opens }) {
                    host.close()
                    Env.launch(Intent(Intent.ACTION_VIEW, Uri.parse(SITE)))
                }
            }
        }
    }
}

/** A reading, top to bottom: the hero row, the next hours, the next days, sunrise and sunset, and the service's credit with the reading's time. */
@Composable
private fun ForecastBody(f: Forecast) {
    if (f.temp != null || f.highLow != null || f.rainWind != null) {
        // One node for a screen reader: "72 degrees. High 78, low 61. Rain, 20 percent chance. Wind 9 miles per hour."
        Row(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = f.heroDesc }, verticalAlignment = Alignment.CenterVertically) {
            if (f.temp != null) {
                // The big value grows from where it stands on its line, its bottom at the start (near enough its baseline).
                val start = if (LocalLayoutDirection.current == LayoutDirection.Rtl) 1f else 0f
                Text(f.temp, style = MaterialTheme.typography.displaySmall.copy(fontFeatureSettings = "tnum"), color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1, modifier = Modifier.popIn(0f, 0.94f, TransformOrigin(start, 1f)))
                Spacer(Modifier.width(16.dp))
            }
            // Its lines then fade in one after the other.
            Column {
                if (f.highLow != null) Text(f.highLow, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fadeIn(24f))
                if (f.rainWind != null) Text(f.rainWind, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fadeIn(if (f.highLow != null) 40f else 24f))
            }
        }
        Spacer(Modifier.height(8.dp))
    }
    if (f.hours.isNotEmpty()) {
        SectionLabel(stringResource(R.string.weather_next_hours))
        HourStrip(f.hours)
    }
    if (f.days.isNotEmpty()) {
        SectionLabel(stringResource(R.string.weather_next_days))
        f.days.forEach { DayRow(it) }
    }
    Spacer(Modifier.height(4.dp))
    // In the city's own time; neither row in polar day and night.
    if (f.sunrise != null) InfoRow(stringResource(R.string.weather_sunrise), f.sunrise)
    if (f.sunset != null) InfoRow(stringResource(R.string.weather_sunset), f.sunset)
    MenuNote(f.note)
}

/**
 * The city search, in the menu and in the item's settings: the field with its Search button, and
 * under it what the last search came to. Typing sends nothing; the button or Enter sends the name
 * (and turns the Weather switch on, see [WeatherSource.search]). [by] names this field, so an answer
 * shows only where it was asked for.
 */
@Composable
internal fun CitySearch(item: ItemConfig, by: String, initial: String, onPick: (City, String?) -> Unit) {
    val source = WeatherItem.source
    val asked by source.searching.collectAsState()
    // A debug build's test hook can put an answer here without asking anyone; it is looked for with the tick.
    rememberTick()
    val state = WeatherItem.stagedSearch ?: WeatherSource.shown(asked, by)
    var short by remember { mutableStateOf(false) }
    val places = ((state as? Ask.State.Done)?.answer as? Found.Places)?.list.orEmpty()
    // What was found belongs to this field while it is drawn: gone with it, so a menu opened later starts empty.
    DisposableEffect(by) { onDispose { source.typed(by) } }
    SearchWithResults(hasResults = places.isNotEmpty(), arrival = places,
        field = {
            SearchField(label = stringResource(R.string.weather_city_label), initial = initial, submit = stringResource(R.string.common_search), selectAll = true,
                error = if (short) stringResource(R.string.weather_two_letters) else null, focus = field,
                onChange = { short = false; source.typed(by) },
                onEnter = { text -> short = WeatherRules.query(text) == null; if (!short) source.search(text, item, by) })
        },
        results = results@{ firstResult ->
            if (short) return@results
            when (state) {
                Ask.State.Idle -> {}
                is Ask.State.Busy -> SearchStatus(stringResource(R.string.weather_searching))
                is Ask.State.Done -> when (val found = state.answer) {
                    Found.Offline -> SearchStatus(stringResource(R.string.common_no_connection))
                    Found.NoAnswer -> SearchStatus(stringResource(R.string.weather_search_no_answer))
                    Found.Unasked -> {}
                    is Found.Places -> if (found.list.isEmpty()) SearchStatus(stringResource(R.string.weather_not_found)) else found.list.forEachIndexed { i, city ->
                        // Region and country on every place, so two of one name can be told apart.
                        val region = WeatherRules.region(city, WeatherItem.Words)
                        MenuEntry(Sym.LOCATION_ON, city.name, sub = region, modifier = if (i != 0) Modifier else firstResult) { onPick(city, region) }
                    }
                }
            }
        })
}

/** The item's settings: the city (the same search as in the menu) or My location, what the bar shows, the unit and a label. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WeatherOptions(item: ItemConfig, set: (ItemConfig) -> Unit) {
    val online by Online.state.collectAsState()
    val on = Online.Service.OPEN_METEO in online.on
    val source = WeatherItem.source
    val city = WeatherRules.city(item)
    val here = WeatherRules.here(item)
    val by = "settings:" + item.id
    // Whether location is allowed can change in Android's settings beside this window: observed, like Setup's permissions.
    val setup by Setup.state.collectAsState()
    // One card serves whichever item is selected: nothing typed or opened for one item stays for the next.
    key(item.id) {
        var changing by remember { mutableStateOf(false) }
        val chooseHere: @Composable () -> Unit = {
            BentoButton(stringResource(R.string.weather_use_here), { set(WeatherRules.useHere(item)); source.typed(by); changing = false; hereChosen() },
                kind = ButtonKind.TEXT)
        }
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SymIcon(if (here) Sym.MY_LOCATION else Sym.LOCATION_ON, size = 18.sp, color = Bento.colors.ink2)
                Spacer(Modifier.width(Space.s))
                Text(when {
                    here -> stringResource(R.string.weather_here_current)
                    city != null -> stringResource(R.string.weather_city_current, city)
                    else -> stringResource(R.string.weather_city_none)
                }, style = Bento.type.strong, color = Bento.colors.ink)
            }
            // The words before the first request, here as in the menu.
            Text(stringResource(R.string.weather_consent), Modifier.padding(top = 2.dp), style = Bento.type.help, color = Bento.colors.ink2)
            if ((!here && WeatherRules.place(item) == null) || changing) {
                CitySearch(item, by, initial = city ?: WeatherRules.cityOf(ZoneId.systemDefault().id)) { found, region ->
                    set(WeatherRules.picked(item, found, region))
                    source.typed(by)
                    changing = false
                }
                FlowRow(Modifier.offset(x = -Space.m)) {
                    if (!here) chooseHere()
                    if (changing) BentoButton(stringResource(R.string.common_cancel), { source.typed(by); changing = false }, kind = ButtonKind.TEXT)
                }
            } else FlowRow(Modifier.offset(x = -Space.m)) { // a text button's own padding: its words then start where the lines above do
                // The field appears on a click, as in the menu, and takes the focus then: not each time the item is selected.
                BentoButton(stringResource(R.string.weather_change_city), { changing = true }, kind = ButtonKind.TEXT)
                if (!here) chooseHere()
                // Back from My location to the item's own city, which it kept.
                else if (city != null) BentoButton(stringResource(R.string.weather_use_city, city), { set(WeatherRules.useCity(item)); source.typed(by) }, kind = ButtonKind.TEXT)
                // A layout that came with a city, or the switch off in Setup: the same act as the menu's.
                if (!on) BentoButton(stringResource(R.string.weather_turn_on), { source.turnOn(); Ticker.refresh() }, kind = ButtonKind.TEXT)
                // My location without the permission (refused, or taken back in Android's settings): Android's question only.
                if (here && !setup.location) BentoButton(stringResource(R.string.weather_allow_location), { askLocation() }, kind = ButtonKind.TEXT)
            }
        }
    }
    ChoiceRow(stringResource(R.string.option_show), listOf(WeatherRules.SHOW_TEMP to stringResource(R.string.weather_show_temp),
        WeatherRules.SHOW_HIGH_LOW to stringResource(R.string.weather_show_high_low), WeatherRules.SHOW_FEELS to stringResource(R.string.weather_show_feels),
        WeatherRules.SHOW_SKY to stringResource(R.string.weather_show_sky)),
        item.opt("show", WeatherRules.SHOW_TEMP)) { set(item.with("show", it)) }
    // Converted on the device: switching redraws the bar and the menu at once, and nothing is asked.
    ChoiceRow(stringResource(R.string.option_temperature_unit), listOf("system" to stringResource(R.string.option_like_system),
        "c" to stringResource(R.string.option_celsius), "f" to stringResource(R.string.option_fahrenheit)), item.opt("unit", "system")) {
        set(item.with("unit", it))
    }
    TextRow(stringResource(R.string.option_label), item.opt("label", ""), help = stringResource(R.string.weather_label_help),
        placeholder = stringResource(R.string.option_none), maxLength = WeatherRules.LABEL_CHARS) { set(item.with("label", WeatherRules.label(it).ifEmpty { null })) }
    Text(stringResource(R.string.weather_data_by), Modifier.padding(bottom = Space.s), style = Bento.type.help, color = Bento.colors.ink3)
}
