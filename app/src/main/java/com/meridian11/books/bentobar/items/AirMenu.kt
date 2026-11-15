package com.meridian11.books.bentobar.items

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.width
import com.meridian11.books.bentobar.util.SymIcon
import com.meridian11.books.bentobar.ui.Space
import com.meridian11.books.bentobar.ui.Bento
import com.meridian11.books.bentobar.ui.ButtonKind
import com.meridian11.books.bentobar.ui.BentoButton
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.data.Online
import com.meridian11.books.bentobar.data.Store
import com.meridian11.books.bentobar.ui.ChoiceRow
import com.meridian11.books.bentobar.ui.InfoRow
import com.meridian11.books.bentobar.ui.MenuCard
import com.meridian11.books.bentobar.ui.MenuDivider
import com.meridian11.books.bentobar.ui.MenuEntry
import com.meridian11.books.bentobar.ui.MenuNote
import com.meridian11.books.bentobar.ui.OffGate
import com.meridian11.books.bentobar.ui.Setup
import com.meridian11.books.bentobar.ui.rememberTick
import com.meridian11.books.bentobar.ui.updatedNote
import com.meridian11.books.bentobar.util.Now
import com.meridian11.books.bentobar.util.Sym
import java.time.ZoneId

// Menu and settings for the Air quality and UV item. No logging.

private const val OPEN_METEO_SITE = "https://open-meteo.com/"

@Composable
internal fun AirMenu(item: ItemConfig, host: MenuHost) {
    rememberTick()
    val online by Online.state.collectAsState()
    val on = Online.Service.OPEN_METEO in online.on
    val now = Now.wall()
    val place = AirLoad.place(item, WeatherHere.place)
    LaunchedEffect(item.id, place, on) { AirItem.opened(item) }

    var changing by remember(item.id) { mutableStateOf(false) }
    val name = stringResource(R.string.item_air_title)
    val here = WeatherRules.here(item)
    val city = if (here) stringResource(R.string.weather_here) else WeatherRules.city(item) ?: if (AirItem.isStaged(item)) WeatherSamples.CITY else name
    val by = "air-menu:" + item.id
    val source = WeatherItem.source
    val pick: (City, String?) -> Unit = { found, region ->
        Store.updateItem(item.id) { WeatherRules.picked(it, found, region) }
        source.typed(by)
        changing = false
        Ticker.refresh(AirItem.type)
    }
    val changeCity: @Composable () -> Unit = { MenuEntry(Sym.LOCATION_ON, stringResource(R.string.weather_change_city)) { changing = true } }
    val kept = WeatherRules.city(item)
    val useCity: @Composable () -> Unit = {
        if (here && kept != null) MenuEntry(Sym.LOCATION_ON, stringResource(R.string.weather_use_city, kept)) {
            Store.updateItem(item.id) { WeatherRules.useCity(it) }
            source.typed(by)
            changing = false
            Ticker.refresh(AirItem.type)
        }
    }
    val useHere: @Composable () -> Unit = {
        MenuEntry(Sym.MY_LOCATION, stringResource(R.string.weather_use_here)) {
            Store.updateItem(item.id) { WeatherRules.useHere(it) }
            source.typed(by)
            changing = false
            if (hereChosen()) host.close()
        }
    }

    val status = AirItem.status(item, now)
    when {
        status == Status.NotSetUp -> MenuCard(Sym.AIR, name, stringResource(R.string.common_not_set_up)) {
            MenuNote(stringResource(R.string.weather_consent))
            CitySearch(item, by, initial = WeatherRules.cityOf(ZoneId.systemDefault().id), onPick = pick)
            MenuDivider()
            useHere()
        }
        changing -> MenuCard(Sym.AIR, city, stringResource(R.string.weather_change_city)) {
            CitySearch(item, by, initial = WeatherRules.city(item).orEmpty(), onPick = pick)
            MenuDivider()
            if (!here) useHere() else useCity()
            MenuEntry(Sym.CLOSE, stringResource(R.string.common_cancel)) { source.typed(by); changing = false }
        }
        status is Status.Off -> OffGate(Sym.AIR, city, stringResource(if (status.everOn) R.string.common_off_in_setup else R.string.weather_not_turned_on),
            stringResource(R.string.weather_consent), stringResource(R.string.weather_turn_on)) { Online.turnOn(Online.Service.OPEN_METEO); Ticker.refresh(AirItem.type) }
        status is Status.NoLocation -> {
            val why = status.why
            MenuCard(Sym.AIR, city, stringResource(when (why) {
                Locate.NOT_ALLOWED -> R.string.weather_here_not_allowed
                Locate.OFF -> R.string.weather_here_off
                else -> R.string.weather_here_none
            })) {
                MenuNote(stringResource(when (why) {
                    Locate.NOT_ALLOWED -> R.string.weather_here_not_allowed_note
                    Locate.OFF -> R.string.weather_here_off_note
                    else -> R.string.weather_here_none_note
                }))
                when (why) {
                    Locate.NOT_ALLOWED -> MenuEntry(Sym.MY_LOCATION, stringResource(R.string.weather_allow_location)) { host.close(); askLocation() }
                    Locate.OFF -> MenuEntry(Sym.SETTINGS, stringResource(R.string.weather_location_settings)) {
                        host.close(); Env.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    }
                    else -> {}
                }
                useCity()
                changeCity()
            }
        }
        status is Status.Missing -> {
            val offline = status.failure == Failure.OFFLINE
            MenuCard(Sym.AIR, city, stringResource(if (offline) R.string.common_no_connection else R.string.weather_no_answer)) {
                MenuEntry(Sym.REFRESH, stringResource(R.string.common_try_again)) { AirItem.refresh(item) }
                useCity(); changeCity()
            }
        }
        status is Status.Live -> AirLive(item, status.reading, city, host, useCity, changeCity)
        else -> MenuCard(Sym.AIR, city, stringResource(R.string.usage_loading)) {
            useCity(); changeCity()
        }
    }
}

/** A reading worth showing. What the reply didn't have is left out, and each part is said on the scale it is on. */
@Composable
private fun AirLive(item: ItemConfig, r: AirRules.AirReading, city: String, host: MenuHost, useCity: @Composable () -> Unit, changeCity: @Composable () -> Unit) {
    val eu = item.opt("scale", "us") == "eu"
    val aqi = AirRules.aqiOf(r, eu)
    val band = aqi?.let { AirItem.bandLabel(AirRules.bandOf(it)) }
    val uv = r.uv?.let { AirRules.formatUv(it) }
    val uvBand = r.uv?.let { AirItem.uvBandLabel(AirRules.uvBand(it)) }
    val sub = when {
        aqi != null && uv != null -> stringResource(R.string.air_subtitle, "$band (${aqi.value})", uv, uvBand.orEmpty())
        aqi != null -> "$band (${aqi.value})"
        else -> stringResource(R.string.air_subtitle_uv, uv.orEmpty(), uvBand.orEmpty())
    }
    MenuCard(Sym.AIR, city, sub) {
        if (aqi != null) InfoRow(stringResource(if (aqi.eu) R.string.air_scale_eu else R.string.air_scale_us), "${aqi.value} · $band")
        if (uv != null) InfoRow(stringResource(R.string.air_uv_index), "$uv · $uvBand")
        r.uvMax?.let { InfoRow(stringResource(R.string.air_uv_peak), AirRules.formatUv(it)) }
        r.pm25?.let { InfoRow(stringResource(R.string.air_pm25), stringResource(R.string.air_ugm3, AirRules.formatUg(it))) }
        r.pm10?.let { InfoRow(stringResource(R.string.air_pm10), stringResource(R.string.air_ugm3, AirRules.formatUg(it))) }
        r.ozone?.let { InfoRow(stringResource(R.string.air_ozone), stringResource(R.string.air_ugm3, AirRules.formatUg(it))) }
        Spacer(Modifier.height(4.dp))
        MenuNote(stringResource(R.string.air_note, updatedNote(r.fetchedAt, r.failure)))
        MenuDivider()
        MenuEntry(Sym.REFRESH, stringResource(R.string.common_refresh)) { AirItem.refresh(item) }
        useCity()
        changeCity()
        val site = stringResource(R.string.weather_open_site)
        val opens = stringResource(R.string.common_opens_browser, site)
        MenuEntry(Sym.OPEN_IN_NEW, site, modifier = Modifier.semantics { contentDescription = opens }) {
            host.close()
            Env.launch(Intent(Intent.ACTION_VIEW, Uri.parse(OPEN_METEO_SITE)))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AirOptions(item: ItemConfig, set: (ItemConfig) -> Unit) {
    val online by Online.state.collectAsState()
    val on = Online.Service.OPEN_METEO in online.on
    val source = WeatherItem.source
    val city = WeatherRules.city(item)
    val here = WeatherRules.here(item)
    val by = "air-settings:" + item.id
    val setup by Setup.state.collectAsState()
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
            } else FlowRow(Modifier.offset(x = -Space.m)) {
                BentoButton(stringResource(R.string.weather_change_city), { changing = true }, kind = ButtonKind.TEXT)
                if (!here) chooseHere()
                else if (city != null) BentoButton(stringResource(R.string.weather_use_city, city), { set(WeatherRules.useCity(item)); source.typed(by) }, kind = ButtonKind.TEXT)
                if (!on) BentoButton(stringResource(R.string.weather_turn_on), { Online.turnOn(Online.Service.OPEN_METEO); Ticker.refresh(AirItem.type) }, kind = ButtonKind.TEXT)
                if (here && !setup.location) BentoButton(stringResource(R.string.weather_allow_location), { askLocation() }, kind = ButtonKind.TEXT)
            }
        }
    }
    ChoiceRow(
        stringResource(R.string.air_show_label),
        listOf(
            "aqi" to stringResource(R.string.air_show_aqi),
            "both" to stringResource(R.string.air_show_aqi_uv),
            "uv" to stringResource(R.string.air_show_uv),
        ),
        item.opt("show", "aqi"),
    ) { set(item.with("show", it.takeIf { v -> v != "aqi" })) }
    ChoiceRow(
        stringResource(R.string.air_scale_label),
        listOf("us" to stringResource(R.string.air_scale_us), "eu" to stringResource(R.string.air_scale_eu)),
        item.opt("scale", "us"),
    ) { set(item.with("scale", it.takeIf { v -> v != "us" })) }
}
