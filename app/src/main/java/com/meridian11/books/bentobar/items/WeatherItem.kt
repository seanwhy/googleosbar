package com.meridian11.books.bentobar.items

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.data.Online
import com.meridian11.books.bentobar.data.Store
import com.meridian11.books.bentobar.util.Dates
import com.meridian11.books.bentobar.util.Now
import com.meridian11.books.bentobar.util.Sym
import com.meridian11.books.bentobar.util.Units
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/**
 * Weather: the temperature and conditions of a city the user picks, or of where the device is ("My
 * location", [WeatherHere]), from Open-Meteo, with the next hours and days in its menu. One of the two
 * item types that go online ([online]), and only after the user set it up on this install: until then
 * nothing is asked, whatever the layout says.
 *
 * The item itself is thin. What it shows and says is worked out in [WeatherRules] (pure, unit-tested
 * against the product's tables); everything that could send something goes through [source]
 * ([WeatherSource], tested against a fake network); the menu and the settings are in `WeatherMenu.kt`.
 */
object WeatherItem : ItemType("weather", R.string.item_weather_title, Sym.PARTLY_CLOUDY_DAY, R.string.item_weather_desc) {
    override val group = ItemGroup.ONLINE
    override val onlineNote = R.string.about_privacy_weather
    override val usesMyLocation = true
    override val refreshMs = 10_000L
    override val menuWidthDp = 340
    override val online = Online.Service.OPEN_METEO
    override val canBeActive = true

    /** How many hours ahead rain or snow brings the item out. */
    private val within = Threshold("rainHours", 2, 1..12) { Env.plural(R.plurals.common_hours, it, it) }
    override val trigger = Trigger(R.string.trigger_weather, R.string.trigger_weather_short, within)

    /**
     * The one way to the service. Its loader and its search belong to the Open-Meteo switch: the
     * moment that goes off they forget what they have, and while it is off they load nothing.
     */
    internal val source: WeatherSource = WeatherSource.make(
        refresher = { every, restore, load -> Background.refresher(type, online, every, restore, load) },
        ask = { work -> Background.ask(type, online, work) },
        background = Background.executor(type),
        wall = Now::wall,
        up = Now::elapsed,
        layout = { Store.config.value.items },
        staged = { stagedFailure.getAndSet(null) },
        here = { WeatherHere.place },
        locating = { WeatherHere.why },
    )

    /** The rules' words, from the app's resources. */
    internal object Words : WeatherWords {
        override fun say(word: W, vararg args: Any): String = Env.str(weatherRes(word), *args)
        override fun count(word: W, quantity: Int, vararg args: Any): String = Env.app.resources.getQuantityString(weatherRes(word), quantity, *args)
    }

    /** Times as the system writes them, with its 12 or 24 hours: a forecast's hour is "3 PM" or "15:00". */
    internal fun times(): Times {
        val h24 = DateFormat.is24HourFormat(Env.app)
        return Times(ZoneId.systemDefault(),
            hour = { ms, zone -> Dates.format(if (h24) "Hm" else "ha", ms, zone) },
            clock = { ms, zone -> Dates.format(Dates.timeSkeleton(h24), ms, zone) },
            weekday = { ms, zone -> Dates.format("EEE", ms, zone) },
            weekdayLong = { ms, zone -> Dates.format("EEEE", ms, zone) })
    }

    /**
     * An item's options as the rules take them. The unit is the item's own choice or Android's
     * regional preference; wind is in miles where temperatures are in Fahrenheit and in the United
     * Kingdom. Both are applied on the device: a change shows at once and asks nobody.
     */
    internal fun look(item: ItemConfig): Look {
        val fahrenheit = Units.fahrenheit(item.opt("unit", "system"))
        return WeatherRules.look(item, fahrenheit, Units.windInMiles(fahrenheit, Locale.getDefault().country), within.shown(item),
            hereName = Env.str(R.string.weather_here))
    }

    /** The state [item] is in at [now]: the staged one in a test, else the real one. It asks nothing. */
    internal fun status(item: ItemConfig, now: Long): Status<Reading> =
        staged(item)?.let { WeatherRules.status(hasPlace = true, on = true, setUp = true, reading = it.reading, now = now) } ?: source.status(item, now)

    /**
     * Refresh (or Try again) as [item]'s menu draws it. A staged sample goes by the same rule, counted
     * from when it was staged, so what a tester sees there is what a real reading does.
     */
    internal fun againEntry(item: ItemConfig): Again =
        staged(item)?.let { WeatherRules.again(it.reading, Now.elapsed() - stagedSince, loading = false) } ?: source.againEntry(item)

    /** A press of Refresh or Try again. A staged sample asks nobody: its wait begins again, as after a try that came back the same. */
    internal fun again(item: ItemConfig) {
        if (staged(item) != null) stagedSince = Now.elapsed() else source.again(item)
    }

    override fun trouble(item: ItemConfig, now: Long): Trouble? = WeatherRules.silence(status(item, now))?.let { (failure, lastAt) ->
        Trouble(if (failure == Failure.SLOW_DOWN) R.string.weather_no_answer_hour else R.string.weather_no_answer_15, lastAt, againEntry(item) == Again.READY)
    }

    override fun tryAgain(item: ItemConfig) = again(item)

    override fun state(item: ItemConfig): ItemState {
        val now = Now.wall()
        // Loads the place's reading if it is due: only for an item with a city, while the switch is on.
        if (staged(item) == null) source.reading(item)
        val bar = WeatherRules.bar(status(item, now), look(item), now, Words, times())
        return ItemState(icon = bar.icon, filled = bar.filled, text = bar.text, desc = bar.desc, active = bar.active, tone = bar.tone,
            tooltip = bar.tooltip, textLimit = WeatherRules.BAR_CHARS)
    }

    // ---- deleted with the item --------------------------------------------------------------------

    private var seenItems: List<ItemConfig>? = null
    private var seenNear: Place? = null
    private var keptFor: Set<Place>? = null

    /**
     * The last reading of a place is deleted with the last item that shows it, and when that item gets
     * another city: looked at whenever the layout's items changed, from [sample] and from [onIdle]
     * ([WeatherLoad.places]). Main thread.
     */
    private fun tidy() {
        val items = Store.config.value.items
        val near = WeatherHere.place
        if (items === seenItems && near == seenNear) return
        seenItems = items
        seenNear = near
        val places = WeatherLoad.places(items, near)
        if (places == keptFor) return
        keptFor = places
        source.keepOnly(places, cities = WeatherLoad.places(items))
    }

    override fun sample(now: Long) = tidy()

    // Also when the last Weather item went, or the last of My location: nothing samples this type any more, so this is the moment that is left.
    override fun onIdle() = tidy()

    override val menu: @Composable (ItemConfig, MenuHost) -> Unit = { item, host -> WeatherMenu(item, host) }

    override val options: @Composable (ItemConfig, (ItemConfig) -> Unit) -> Unit = { item, set -> WeatherOptions(item, set) }

    // ---- tests on a device (debug builds) ----------------------------------------------------------

    /** A made-up reading shown instead of the real one, for the first Weather item; it asks nothing. */
    @Volatile private var stagedReading: WeatherSamples.Sample? = null

    /** When that sample was staged, or its Refresh last pressed, by the time since boot: the moment its "last try came back". */
    @Volatile private var stagedSince = 0L

    /** A made-up answer under the search field, wherever one is drawn. */
    @Volatile internal var stagedSearch: Ask.State<Query, Found>? = null
        private set

    /** The first Weather item that could show: the one a staged sample is for. */
    private fun firstShown(): ItemConfig? = Store.config.value.items.firstOrNull { it.type == type }

    /** The sample staged for [item], if it is the first Weather item of the layout. */
    internal fun staged(item: ItemConfig): WeatherSamples.Sample? = stagedReading?.takeIf { firstShown()?.id == item.id }

    /**
     * A failure the next forecast request comes back as, without being sent: with it the pace after
     * an error ("one retry after 15 minutes") can be watched on a device, with the real loader and
     * the request counter. Taken once, on the loader's thread.
     */
    private val stagedFailure = AtomicReference<Failure?>(null)

    // The switch went off: the loader, the search and what was kept have forgotten already; a staged sample goes too.
    // A staged failure stays: a tester stages it while the switch is off, so that the first request after "on" is the one that fails.
    override fun forgetFetched() { stagedReading = null; stagedSearch = null; WeatherHere.forget() }

    /**
     * `./bento debug weather stage <sample>`, `weather search places|none|offline|error|busy`,
     * `weather fail error|slow-down|offline`, `weather off`, and `weather` alone for where things
     * stand. What it answers is logged, so it names no city and no coordinates.
     */
    // The searches first: the field they answer in is drawn while nothing is set up, which a staged sample ends.
    override val stages = listOf("places", "none", "offline", "error", "busy").map { "search $it" } + WeatherSamples.names.map { "stage $it" }

    override fun debug(args: List<String>): String? = when (args.firstOrNull()) {
        null -> {
            val items = Store.config.value.items.filter { it.type == type }
            val first = firstShown()
            val reading = first?.let { source.peek(it) }
            "staged=${stagedReading?.name ?: "none"} here=${if (WeatherHere.place != null) "known" else WeatherHere.why} search=${if (stagedSearch == null) "real" else "staged"} fail=${stagedFailure.get() ?: "none"}" +
                " items=${items.size} places=${WeatherLoad.places(items, WeatherHere.place).size}" +
                " on=${Online.on(online)} setUp=${Online.setUp(online)}" + (first?.let {
                    " first=${status(it, Now.wall()).javaClass.simpleName} loading=${source.loading(it)} failure=${reading?.failure ?: "none"}" +
                        " again=${againEntry(it)}" +
                        " read=${if (reading == null || reading.fetchedAt == 0L) "never" else "${(Now.wall() - reading.fetchedAt) / 1000}s ago"}"
                } ?: "")
        }
        "stage" -> {
            val sample = WeatherSamples.of(args.getOrNull(1).orEmpty(), Now.wall(), ZoneId.systemDefault())
            if (sample == null) null else {
                stagedReading = sample
                stagedSince = Now.elapsed()
                Ticker.refresh()
                "staged ${sample.name} for the first Weather item"
            }
        }
        "search" -> {
            val asked = Query("", "staged")
            val state: Ask.State<Query, Found>? = when (args.getOrNull(1)) {
                // Three places of one name, told apart by their regions. Picking one stores it like a found one.
                "places" -> Ask.State.Done(asked, Found.Places(listOf(
                    City("Springfield", "Illinois", "United States", "39.80", "-89.64", "America/Chicago"),
                    City("Springfield", "Missouri", "United States", "37.22", "-93.30", "America/Chicago"),
                    City("Springfield", "Massachusetts", "United States", "42.10", "-72.59", "America/New_York"))))
                "none" -> Ask.State.Done(asked, Found.Places(emptyList()))
                "offline" -> Ask.State.Done(asked, Found.Offline)
                "error" -> Ask.State.Done(asked, Found.NoAnswer)
                "busy" -> Ask.State.Busy(asked)
                "off", null -> null
                else -> return null
            }
            stagedSearch = state
            Ticker.refresh()
            if (state == null) "search staging off (places, none, offline, error or busy stage an answer)" else "staged a search answer: ${args[1]}"
        }
        "fail" -> {
            val failure = when (args.getOrNull(1)) { "error" -> Failure.NO_ANSWER; "slow-down" -> Failure.SLOW_DOWN; "offline" -> Failure.OFFLINE; "off", null -> null; else -> return null }
            stagedFailure.set(failure)
            if (failure == null) "no failure staged (error, slow-down or offline stage one)"
            else "the next forecast request is not sent and counts as ${args[1]}"
        }
        "off" -> {
            stagedReading = null
            stagedSearch = null
            stagedFailure.set(null)
            Ticker.refresh()
            "staging off"
        }
        else -> null
    }
}

/**
 * The text resource behind each of the rules' words. A unit test compares it to the names in [W], so
 * the app can't say one text where the tests read another.
 */
fun weatherRes(word: W): Int = when (word) {
    W.TITLE -> R.string.item_weather_title
    W.CLEAR -> R.string.weather_clear
    W.MOSTLY_CLEAR -> R.string.weather_mostly_clear
    W.PARTLY_CLOUDY -> R.string.weather_partly_cloudy
    W.CLOUDY -> R.string.weather_cloudy
    W.FOG -> R.string.weather_fog
    W.DRIZZLE -> R.string.weather_drizzle
    W.FREEZING_DRIZZLE -> R.string.weather_freezing_drizzle
    W.LIGHT_RAIN -> R.string.weather_light_rain
    W.RAIN -> R.string.weather_rain
    W.HEAVY_RAIN -> R.string.weather_heavy_rain
    W.FREEZING_RAIN -> R.string.weather_freezing_rain
    W.LIGHT_SNOW -> R.string.weather_light_snow
    W.SNOW -> R.string.weather_snow
    W.HEAVY_SNOW -> R.string.weather_heavy_snow
    W.SHOWERS -> R.string.weather_showers
    W.HEAVY_SHOWERS -> R.string.weather_heavy_showers
    W.SNOW_SHOWERS -> R.string.weather_snow_showers
    W.THUNDERSTORM -> R.string.weather_thunderstorm
    W.THUNDERSTORM_HAIL -> R.string.weather_thunderstorm_hail
    W.WINDY -> R.string.weather_windy
    W.BAR_RAIN -> R.string.weather_bar_rain
    W.BAR_SNOW -> R.string.weather_bar_snow
    W.BAR_STORM -> R.string.weather_bar_storm
    W.BAR_SOON -> R.string.weather_bar_soon
    W.BAR_NOW -> R.string.weather_bar_now
    W.BAR_HIGH_LOW -> R.string.weather_bar_high_low
    W.BAR_LABEL -> R.string.weather_bar_label
    W.TOOLTIP -> R.string.weather_tooltip
    W.DESC -> R.string.weather_desc
    W.DESC_SHORT -> R.string.weather_desc_short
    W.DESC_SOON -> R.string.weather_desc_soon
    W.DESC_NOW -> R.string.weather_desc_now
    W.DESC_THIS_HOUR -> R.string.weather_likely_now_desc
    W.DESC_NOT_SET_UP -> R.string.weather_desc_not_set_up
    W.DESC_LOADING -> R.string.weather_desc_loading
    W.DESC_OFF -> R.string.weather_desc_off
    W.DESC_NO_READING -> R.string.weather_desc_no_reading
    W.DESC_NO_LOCATION -> R.string.weather_desc_no_location
    W.DEGREES -> R.plurals.weather_degrees
    W.SUBTITLE -> R.string.weather_subtitle
    W.SUBTITLE_THERE -> R.string.weather_subtitle_there
    W.HIGH_LOW -> R.string.weather_high_low
    W.RAIN_WIND -> R.string.weather_rain_wind
    W.SNOW_WIND -> R.string.weather_snow_wind
    W.WIND_MPH -> R.string.weather_wind_mph
    W.WIND_KMH -> R.string.weather_wind_kmh
    W.CHANCE -> R.string.weather_chance
    W.NOTE -> R.string.weather_note
    W.UPDATED -> R.string.common_updated
    W.UPDATED_OFFLINE -> R.string.common_updated_offline
    W.UPDATED_NO_ANSWER -> R.string.common_updated_no_answer
    W.HIGH_LOW_DESC -> R.string.weather_high_low_desc
    W.CHANCE_DESC -> R.string.weather_chance_desc
    W.WIND_MPH_DESC -> R.plurals.weather_wind_mph_desc
    W.WIND_KMH_DESC -> R.plurals.weather_wind_kmh_desc
    W.LIST -> R.string.weather_list
    W.SENTENCE -> R.string.weather_sentence
    W.DAY_HIGH_LOW -> R.string.weather_day_high_low
}
