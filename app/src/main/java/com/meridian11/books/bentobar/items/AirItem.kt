package com.meridian11.books.bentobar.items

import androidx.compose.runtime.Composable
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.data.Online
import com.meridian11.books.bentobar.data.Store
import com.meridian11.books.bentobar.items.AirRules.AirReading
import com.meridian11.books.bentobar.util.Now
import com.meridian11.books.bentobar.util.Sym

/**
 * Air quality and UV: US or European AQI, PM2.5, PM10, Ozone and UV index from Open-Meteo for a
 * chosen city or "My location" ([WeatherHere]). Shares the Open-Meteo online service switch with
 * [WeatherItem]. No logging.
 */
object AirItem : ItemType("air", R.string.item_air_title, Sym.AIR, R.string.item_air_desc) {
    override val group = ItemGroup.ONLINE
    override val onlineNote = R.string.about_privacy_air
    override val usesMyLocation = true
    override val refreshMs = 10_000L
    override val menuWidthDp = 340
    override val online = Online.Service.OPEN_METEO
    override val canBeActive = true

    private val aboveAqi = Threshold("aqi", 100, 50..200, step = 25) { "$it" }
    override val trigger = Trigger(R.string.trigger_air, R.string.trigger_air_short, aboveAqi)

    internal val readings: Refresher<Place, AirReading> = Background.refresher(
        type = type,
        service = online,
        every = { _, r -> AirRules.every(r) },
        load = { place, last -> AirLoad.load(place, last, Now.wall(), Now.elapsed()) },
    )

    private val staged = Staged<AirReading>(type)

    internal fun isStaged(item: ItemConfig): Boolean = staged.of(item) != null

    internal fun reading(item: ItemConfig, ask: Boolean = true): AirReading? {
        staged.of(item)?.let { return it }
        if (!Online.on(online)) return null
        val place = AirLoad.place(item, WeatherHere.place) ?: return null
        if (ask) {
            readings.want(place)
            readings.askIfOld(place, AirRules.AGAIN_MS, old = { AirRules.old(it, Now.wall(), Now.elapsed()) }, failed = { it.failure != null })
        }
        return readings.peek(place)
    }

    /** The state [item] is in at [now]: the staged one in a test, else the real one. It asks nothing. */
    internal fun status(item: ItemConfig, now: Long): Status<AirReading> {
        staged.of(item)?.let { return AirRules.status(hasPlace = true, on = true, setUp = true, reading = it, now = now) }
        val here = WeatherRules.here(item)
        val on = Online.on(online)
        val place = AirLoad.place(item, WeatherHere.place)
        return AirRules.status(hasPlace = place != null, on = on, setUp = Online.setUp(online),
            reading = if (on) place?.let { readings.peek(it) } else null, now = now, up = Now.elapsed(),
            locating = if (here && place == null) WeatherHere.why else null)
    }

    internal fun opened(item: ItemConfig) {
        if (isStaged(item) || !Online.on(online)) return
        val place = AirLoad.place(item, WeatherHere.place) ?: return
        readings.menuOpened(place, AirRules.MENU_MS) { it.failure != null }
    }

    internal fun refresh(item: ItemConfig) {
        if (isStaged(item) || !Online.on(online)) return
        val place = AirLoad.place(item, WeatherHere.place) ?: return
        readings.refresh(place, floorMs = 10_000L)
    }

    internal fun bandLabel(band: AirRules.Band): String = Env.str(when (band) {
        AirRules.Band.GOOD -> R.string.air_good
        AirRules.Band.MODERATE -> R.string.air_moderate
        AirRules.Band.SENSITIVE -> R.string.air_sensitive
        AirRules.Band.UNHEALTHY -> R.string.air_unhealthy
        AirRules.Band.VERY_UNHEALTHY -> R.string.air_very_unhealthy
        AirRules.Band.HAZARDOUS -> R.string.air_hazardous
    })

    internal fun uvBandLabel(band: AirRules.UvBand): String = Env.str(when (band) {
        AirRules.UvBand.LOW -> R.string.air_uv_low
        AirRules.UvBand.MODERATE -> R.string.air_uv_moderate
        AirRules.UvBand.HIGH -> R.string.air_uv_high
        AirRules.UvBand.VERY_HIGH -> R.string.air_uv_very_high
        AirRules.UvBand.EXTREME -> R.string.air_uv_extreme
    })

    override fun state(item: ItemConfig): ItemState {
        // Loads the place's reading if it is due: only for an item with a place, while the switch is on.
        reading(item)
        val status = status(item, Now.wall())
        val live = status as? Status.Live ?: return quiet(status)
        return shown(item, live.reading)
    }

    private fun quiet(status: Status<AirReading>): ItemState {
        val d = Env.str(when (status) {
            Status.NotSetUp -> R.string.air_desc_not_set_up
            is Status.Off -> R.string.air_desc_off
            is Status.Missing -> R.string.air_desc_no_reading
            is Status.NoLocation -> R.string.air_desc_no_location
            else -> R.string.air_desc_loading
        })
        return ItemState(icon = Sym.AIR, filled = false, text = null, desc = d, tooltip = d)
    }

    /** A reading worth showing. A part the reply didn't have is left out; it is never shown as a nought, nor on the wrong scale. */
    private fun shown(item: ItemConfig, r: AirReading): ItemState {
        val eu = item.opt("scale", "us") == "eu"
        val aqi = AirRules.aqiOf(r, eu)
        val band = aqi?.let { AirRules.bandOf(it) }
        val uv = r.uv?.let { AirRules.formatUv(it) }
        val parts = AirRules.parts(item.opt("show", "aqi"), aqi != null, uv != null)
        val aqiText = aqi?.takeIf { parts.aqi }?.let { Env.str(if (it.eu) R.string.air_bar_eaqi else R.string.air_bar_aqi, it.value) }
        val uvText = uv?.takeIf { parts.uv }
        val text = when {
            aqiText != null && uvText != null -> Env.str(R.string.air_bar_both, aqiText, uvText)
            aqiText != null -> aqiText
            else -> Env.str(R.string.air_bar_uv, uvText.orEmpty())
        }
        val city = if (WeatherRules.here(item)) Env.str(R.string.weather_here) else WeatherRules.city(item) ?: Env.str(R.string.item_air_title)
        val fullAqi = aqi?.let { Env.str(if (it.eu) R.string.air_bar_eaqi else R.string.air_bar_aqi, it.value) }
        val desc = when {
            fullAqi != null && uv != null -> Env.str(R.string.air_desc_live, city, fullAqi, bandLabel(band!!), uv)
            fullAqi != null -> Env.str(R.string.air_desc_aqi, city, fullAqi, bandLabel(band!!))
            else -> Env.str(R.string.air_desc_uv, city, uv.orEmpty())
        }
        return ItemState(icon = if (parts.uv && !parts.aqi) Sym.SUNNY else Sym.AIR, filled = true, text = text, tone = AirRules.toneOf(band, r.uv),
            active = AirRules.active(r, aboveAqi.shown(item), eu), desc = desc, tooltip = desc, textLimit = AirRules.BAR_CHARS)
    }

    override fun sample(now: Long) {
        readings.keepOnly(AirLoad.places(Store.config.value.items, WeatherHere.place))
    }

    override fun onIdle() {
        readings.keepOnly(AirLoad.places(Store.config.value.items, WeatherHere.place))
    }

    override fun forgetFetched() {
        staged.value = null
    }

    override val menu: @Composable (ItemConfig, MenuHost) -> Unit = { item, host -> AirMenu(item, host) }
    override val options: @Composable (ItemConfig, (ItemConfig) -> Unit) -> Unit = { item, set -> AirOptions(item, set) }

    override val stages = listOf("good", "moderate", "smoke", "highuv", "offline").map { "stage $it" }

    override fun debug(args: List<String>): String? = when (args.firstOrNull()) {
        null -> "staged=${staged.value != null} on=${Online.on(online)}"
        "stage" -> {
            val s = AirRules.sample(args.getOrNull(1).orEmpty(), Now.wall())
            if (s == null) null else {
                staged.value = s
                Ticker.refresh(type)
                "staged ${args[1]}"
            }
        }
        "off" -> {
            staged.value = null
            Ticker.refresh(type)
            "staging off"
        }
        else -> null
    }
}
