package com.meridian11.books.bentobar.items

import java.util.Locale
import androidx.compose.runtime.Immutable
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.util.Sym
import com.meridian11.books.bentobar.util.Units
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.roundToInt

// The Weather item's rules: what it reads from a reply, what it shows and says, when it asks again.
// No Android here: the moment, the unit, the clock's way of writing a time and the words all come in
// from outside, so every example of the product's and the design's tables is a unit test.

/**
 * Every word these rules say, by the name of its text resource. The app reads them from its
 * resources, a test from the text files themselves: what the tests compare is then the app's own text.
 */
enum class W(val res: String, val plural: Boolean = false) {
    TITLE("item_weather_title"),
    CLEAR("weather_clear"), MOSTLY_CLEAR("weather_mostly_clear"), PARTLY_CLOUDY("weather_partly_cloudy"), CLOUDY("weather_cloudy"),
    FOG("weather_fog"), DRIZZLE("weather_drizzle"), FREEZING_DRIZZLE("weather_freezing_drizzle"), LIGHT_RAIN("weather_light_rain"),
    RAIN("weather_rain"), HEAVY_RAIN("weather_heavy_rain"), FREEZING_RAIN("weather_freezing_rain"), LIGHT_SNOW("weather_light_snow"),
    SNOW("weather_snow"), HEAVY_SNOW("weather_heavy_snow"), SHOWERS("weather_showers"), HEAVY_SHOWERS("weather_heavy_showers"),
    SNOW_SHOWERS("weather_snow_showers"), THUNDERSTORM("weather_thunderstorm"), THUNDERSTORM_HAIL("weather_thunderstorm_hail"),
    WINDY("weather_windy"),
    BAR_RAIN("weather_bar_rain"), BAR_SNOW("weather_bar_snow"), BAR_STORM("weather_bar_storm"),
    BAR_SOON("weather_bar_soon"), BAR_NOW("weather_bar_now"), BAR_HIGH_LOW("weather_bar_high_low"), BAR_LABEL("weather_bar_label"),
    TOOLTIP("weather_tooltip"),
    DESC("weather_desc"), DESC_SHORT("weather_desc_short"), DESC_SOON("weather_desc_soon"), DESC_NOW("weather_desc_now"),
    DESC_THIS_HOUR("weather_likely_now_desc"),
    DESC_NOT_SET_UP("weather_desc_not_set_up"), DESC_LOADING("weather_desc_loading"), DESC_OFF("weather_desc_off"),
    DESC_NO_READING("weather_desc_no_reading"), DESC_NO_LOCATION("weather_desc_no_location"), DEGREES("weather_degrees", plural = true),
    SUBTITLE("weather_subtitle"), SUBTITLE_THERE("weather_subtitle_there"), HIGH_LOW("weather_high_low"),
    RAIN_WIND("weather_rain_wind"), SNOW_WIND("weather_snow_wind"), WIND_MPH("weather_wind_mph"), WIND_KMH("weather_wind_kmh"),
    CHANCE("weather_chance"), NOTE("weather_note"),
    UPDATED("common_updated"), UPDATED_OFFLINE("common_updated_offline"), UPDATED_NO_ANSWER("common_updated_no_answer"),
    HIGH_LOW_DESC("weather_high_low_desc"), CHANCE_DESC("weather_chance_desc"),
    WIND_MPH_DESC("weather_wind_mph_desc", plural = true), WIND_KMH_DESC("weather_wind_kmh_desc", plural = true),
    LIST("weather_list"), SENTENCE("weather_sentence"), DAY_HIGH_LOW("weather_day_high_low"),
}

/** Where the words come from. [say]: a text with its arguments put in; [count]: one that depends on a number ([W.plural]). */
interface WeatherWords {
    fun say(word: W, vararg args: Any): String
    fun count(word: W, quantity: Int, vararg args: Any): String
}

/**
 * How times are written: with the system's 12 or 24 hours, in a zone. [hour] is a forecast's whole
 * hour ("3 PM", "15:00"), [clock] a time of day ("2:40 PM"), [weekday] "Tue" and [weekdayLong]
 * "Tuesday". [here] is the device's own zone.
 */
class Times(
    val here: ZoneId,
    val hour: (epochMs: Long, zone: ZoneId) -> String,
    val clock: (epochMs: Long, zone: ZoneId) -> String,
    val weekday: (epochMs: Long, zone: ZoneId) -> String,
    val weekdayLong: (epochMs: Long, zone: ZoneId) -> String,
)

/** A place a search found, with what tells it from others of its name. Coordinates are rounded already. */
@Immutable
data class City(val name: String, val region: String, val country: String, val lat: String, val lon: String, val zone: String) {
    override fun toString() = "a city"
}

/** The sky of this minute. Metric, as the service sends it; [at] in seconds since 1970. */
@Serializable
@Immutable
data class Current(val at: Long, val temp: Double? = null, val feels: Double? = null, val code: Int? = null, val day: Boolean = true,
                   val windKmh: Double? = null)

/**
 * One entry of the hourly forecast. [temp], [code] and [day] are of the moment [at]. [chance], of
 * rain or snow in percent, is not: the service files an hour's chance under the time that hour
 * ends at ("preceding hour"), so it is for the hour before [at] (see [WeatherRules.begins]).
 */
@Serializable
@Immutable
data class Hour(val at: Long, val temp: Double? = null, val chance: Int? = null, val code: Int? = null, val day: Boolean = true)

/** One day of the forecast, starting at [at] in the city's own time. No [sunrise] and [sunset] in polar day and night. */
@Serializable
@Immutable
data class Day(val at: Long, val code: Int? = null, val high: Double? = null, val low: Double? = null, val chance: Int? = null,
               val sunrise: Long? = null, val sunset: Long? = null)

/**
 * What is known of one place's weather: the last good answer, and what came of the last try. The
 * app's own model, not the reply: this is what is kept on the device for a restart.
 *
 * [fetchedAt]: the wall clock when the numbers were read, in milliseconds; 0 when there never were
 * any. [fetchedUp]: the time since boot at that moment, the clock the loader counts by; 0 when it is
 * not known (a sample, a reading kept before this mark existed). The wall clock can be set and the
 * time since boot begins again with every boot, so a reading's age is asked of both ([WeatherRules.old]).
 * [failure]: the last try failed (the numbers are then the ones from before). [misses]: how many
 * tries in a row went out and failed. [retryAfterSec]: how long the service asked to be left alone.
 */
@Serializable
@Immutable
data class Reading(
    val place: String,
    val fetchedAt: Long = 0,
    val zone: String = "",
    val offsetSec: Int = 0,
    val current: Current? = null,
    val hours: List<Hour> = emptyList(),
    val days: List<Day> = emptyList(),
    val failure: Failure? = null,
    val misses: Int = 0,
    val retryAfterSec: Long = 0,
    val fetchedUp: Long = 0,
) {
    override fun toString() = "a reading"
}

/** How one item wants its weather shown: its options, read once ([WeatherRules.look]). */
class Look(val city: String?, val label: String?, val show: String, val fahrenheit: Boolean, val miles: Boolean, val rainHours: Int)

/** Refresh, or Try again, as the menu draws it ([WeatherRules.again]). */
enum class Again {
    /** A press asks now. */
    READY,
    /** Dimmed, and the entry says why: an answer came less than a minute ago, there is nothing newer to fetch. */
    UP_TO_DATE,
    /** Dimmed with nothing to add: a request is on its way, or a try just failed and the menu says so above the entry. */
    WAIT,
}

/** What the bar shows; the item turns it into its state. */
class Bar(val icon: String, val filled: Boolean, val text: String? = null, val desc: String, val active: Boolean = false,
          val tone: Tone = Tone.NORMAL, val tooltip: String? = null)

/** One cell of "Next hours". What the reply left out is null, and its place stays empty. */
@Immutable
class HourCell(val time: String, val glyph: String?, val temp: String?, val chance: String?, val desc: String)

/** One row of "Next days". */
@Immutable
class DayLine(val day: String, val glyph: String?, val chance: String?, val high: String?, val low: String?, val desc: String)

/** The menu with a reading, as text: every line the design draws. A line that is null is left out. */
@Immutable
class Forecast(val glyph: String, val subtitle: String?, val temp: String?, val highLow: String?, val rainWind: String?, val heroDesc: String,
               val hours: List<HourCell>, val days: List<DayLine>, val sunrise: String?, val sunset: String?, val note: String)

object WeatherRules {
    const val SHOW_TEMP = "temp"
    const val SHOW_HIGH_LOW = "highlow"
    const val SHOW_FEELS = "feels"
    /** The temperature and a word for the sky: "72° · Partly cloudy". */
    const val SHOW_SKY = "sky"

    private const val MIN_MS = 60_000L
    private const val HOUR_MS = 60 * MIN_MS
    /** A good reading is asked again after half an hour (while the bar is on screen). */
    const val FRESH_MS = 30 * MIN_MS
    /** Opening the menu asks again if the reading is older than this. */
    const val MENU_MS = 10 * MIN_MS
    /** Refresh after an answer: once a minute at most. */
    const val AGAIN_MS = MIN_MS
    /** Try again after a try that reached nobody or got no answer ([againAfter]). */
    const val SOON_MS = 10_000L
    /** After no answer. */
    const val RETRY_MS = 15 * MIN_MS
    /** After being told to slow down, unless the service named a longer time. */
    const val SLOW_MS = 60 * MIN_MS
    /** An old temperature is a wrong temperature: after this long without an answer the numbers go, from the bar and from the menu. */
    const val OLD_MS = 3 * HOUR_MS

    /** The most characters the bar's text has; what is longer loses the time, then the label. */
    const val BAR_CHARS = 20
    const val LABEL_CHARS = 12
    /** The longest name kept or shown of a place, and the longest text sent as a search. */
    const val NAME_CHARS = 80
    const val QUERY_CHARS = 64
    /** What is read of a reply at most; the request asks for 24 hours and 7 days. */
    const val MAX_HOURS = 48
    const val MAX_DAYS = 10
    const val MAX_PLACES = 5
    /** "Likely": a chance of this many percent or more. */
    private const val LIKELY = 50
    /** A chance is shown in the menu from this many percent. */
    private const val SHOWN_CHANCE = 20
    private const val HOUR_CELLS = 6
    private const val DAY_ROWS = 5
    private const val DAY_SEC = 86_400L
    private const val HOUR_SEC = 3_600L

    private val json = Json { ignoreUnknownKeys = true }

    // ---- what a layout holds --------------------------------------------------------------------

    /** Where "My location" is rounded to before it is sent or kept: one decimal, about ten kilometers. */
    private const val HERE_DECIMALS = 1

    /**
     * The item shows the weather where the device is ("My location"), not a city's. The layout holds
     * only that choice, never where the device is: a layout is copied and backed up, a location must not be.
     */
    fun here(item: ItemConfig): Boolean = item.options["where"] == "here"

    /** [item] showing where the device is. Its city, if it has one, stays for a way back. */
    fun useHere(item: ItemConfig): ItemConfig = item.with("where", "here")

    /** [item] showing its city again (or asking for one, if it has none). */
    fun useCity(item: ItemConfig): ItemConfig = item.with("where", null)

    /**
     * Where the device is, as it is sent and kept: rounded to one decimal, about ten kilometers ("47.4",
     * "-122.4"); null for what is no location. Android's approximate location is already blurred to a few
     * kilometers; this makes it the size of a town, which is all a forecast needs.
     */
    fun nearby(lat: Double, lon: Double): Place? {
        if (!lat.isFinite() || !lon.isFinite() || abs(lat) > 90.0 || abs(lon) > 180.0) return null
        fun round(v: Double) = BigDecimal.valueOf(v).setScale(HERE_DECIMALS, RoundingMode.HALF_UP).toPlainString().let { if (it == "-0.0") "0.0" else it }
        return Place(round(lat), round(lon))
    }

    /**
     * [value] with two decimals, as a coordinate is stored and sent ("47.37", "-122.42", "8.50"), or
     * null if it is none (beyond [limit] degrees, not a number).
     */
    fun coordinate(value: Double, limit: Double): String? =
        if (!value.isFinite() || abs(value) > limit) null else BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).toPlainString()

    private fun coordinate(text: String?, limit: Double): String? =
        text?.trim()?.takeIf { it.isNotEmpty() && it.length <= 24 }?.toDoubleOrNull()?.let { coordinate(it, limit) }

    /**
     * The place an item asks about, or null while it has none. A layout can come from anywhere, so
     * its coordinates are checked and rounded once more: whatever it holds, no more than two decimals
     * are ever sent.
     */
    fun place(item: ItemConfig): Place? {
        val lat = coordinate(item.options["lat"], 90.0) ?: return null
        val lon = coordinate(item.options["lon"], 180.0) ?: return null
        return Place(lat, lon)
    }

    /** The city's name as the item shows it, or null. */
    fun city(item: ItemConfig): String? = TextRules.oneLine(item.options["city"].orEmpty(), NAME_CHARS).ifEmpty { null }

    /** A label as it is stored: one line, twelve characters at most. */
    fun label(text: String): String = TextRules.oneLine(text, LABEL_CHARS)

    /** [hereName]: what My location is called, the name an item showing it goes by. */
    fun look(item: ItemConfig, fahrenheit: Boolean, miles: Boolean, rainHours: Int, hereName: String? = null): Look =
        Look(if (here(item)) hereName else city(item), label(item.opt("label", "")).ifEmpty { null }, item.opt("show", SHOW_TEMP), fahrenheit, miles, rainHours)

    private val PLACE_KEYS = setOf("city", "region", "lat", "lon", "zone")

    /** [item] with [city] as its place; [region] is the line that told it from others of its name. Its other options stay; My location ends. */
    fun picked(item: ItemConfig, city: City, region: String?): ItemConfig = item.copy(options = item.options - PLACE_KEYS - "where" + listOfNotNull(
        "city" to city.name, region?.takeIf { it.isNotEmpty() }?.let { "region" to it }, "lat" to city.lat, "lon" to city.lon,
        city.zone.takeIf { it.isNotEmpty() }?.let { "zone" to it }))

    // ---- the search field -----------------------------------------------------------------------

    /** What a press of Search sends for [text]: one short line, or null if it has fewer than two characters. */
    fun query(text: String): String? = TextRules.oneLine(text, QUERY_CHARS).takeIf { it.codePointCount(0, it.length) >= 2 }

    private val AREAS = setOf("Africa", "America", "Antarctica", "Arctic", "Asia", "Atlantic", "Australia", "Europe", "Indian", "Pacific")

    /** "Los Angeles" from "America/Los_Angeles": what the search field starts with. Empty for a zone that names no city. */
    fun cityOf(zoneId: String): String =
        if ('/' !in zoneId || zoneId.substringBefore('/') !in AREAS) "" else TextRules.oneLine(zoneId.substringAfterLast('/').replace('_', ' '), NAME_CHARS)

    /** "Illinois, United States": the parts the reply has, or null when it has none. */
    fun region(city: City, w: WeatherWords): String? = when {
        city.region.isNotEmpty() && city.country.isNotEmpty() -> w.say(W.LIST, city.region, city.country)
        city.region.isNotEmpty() -> city.region
        city.country.isNotEmpty() -> city.country
        else -> null
    }

    // ---- text from outside: a layout, a reply ---------------------------------------------------

    /** At most [BAR_CHARS] characters, as a reader counts them. */
    private fun fits(text: String) = TextRules.fits(text, BAR_CHARS)

    // ---- reading the replies --------------------------------------------------------------------

    private fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject
    private fun JsonObject.list(name: String): JsonArray? = this[name] as? JsonArray
    private fun JsonArray?.at(i: Int): JsonElement? = this?.getOrNull(i)
    private fun JsonElement?.number(): Double? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }
    private fun JsonElement?.flag(): Boolean? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
    private fun JsonElement?.line(max: Int): String = (this as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { TextRules.oneLine(it, max) }.orEmpty()

    // A value beyond all sense is a value that is missing: its cell is left out.
    private fun JsonElement?.seconds(): Long? = number()?.takeIf { it >= 1 && it < 1e11 }?.toLong()
    private fun JsonElement?.celsius(): Double? = number()?.takeIf { it in -150.0..150.0 }
    private fun JsonElement?.percent(): Int? = number()?.takeIf { it in 0.0..100.0 }?.roundToInt()
    private fun JsonElement?.code(): Int? = number()?.takeIf { it in 0.0..999.0 }?.toInt()
    private fun JsonElement?.speed(): Double? = number()?.takeIf { it in 0.0..1000.0 }
    private fun JsonElement?.isDay(): Boolean = number()?.let { it != 0.0 } ?: true
    private fun JsonElement?.zoneName(): String =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.length <= 64 && it.all { c -> c.isLetterOrDigit() || c in "/_+-" } }.orEmpty()

    /**
     * A forecast reply as a [Reading] taken at [now] (the wall clock; [up] is the time since boot at
     * that moment), or null for anything that is none: an error's body, a sign-in page, half a reply.
     * A value that is missing or makes no sense is left out and the rest is read. It never throws.
     */
    fun read(text: String, place: Place, now: Long, up: Long = 0): Reading? = try {
        val root = Json.parseToJsonElement(text) as? JsonObject
        val current = root?.obj("current")?.let { c ->
            Current(c["time"].seconds() ?: (now / 1000), c["temperature_2m"].celsius(), c["apparent_temperature"].celsius(),
                c["weather_code"].code(), c["is_day"].isDay(), c["wind_speed_10m"].speed())
        }
        if (root == null || current == null || root["error"].flag() == true || (current.temp == null && current.code == null)) null
        else Reading(place.key, now, root["timezone"].zoneName(), root["utc_offset_seconds"].number()?.takeIf { abs(it) <= 18 * 3600 }?.toInt() ?: 0,
            current, hours(root.obj("hourly")), days(root.obj("daily")), fetchedUp = up)
    } catch (e: Exception) {
        null
    } catch (e: StackOverflowError) {
        // A reply nested thousands deep is nobody's forecast.
        null
    }

    private fun hours(o: JsonObject?): List<Hour> {
        val times = o?.list("time") ?: return emptyList()
        val temps = o.list("temperature_2m"); val chances = o.list("precipitation_probability")
        val codes = o.list("weather_code"); val days = o.list("is_day")
        val out = ArrayList<Hour>()
        for (i in 0 until minOf(times.size, MAX_HOURS)) {
            val at = times[i].seconds() ?: continue
            out += Hour(at, temps.at(i).celsius(), chances.at(i).percent(), codes.at(i).code(), days.at(i).isDay())
        }
        return out.distinctBy { it.at }.sortedBy { it.at }
    }

    private fun days(o: JsonObject?): List<Day> {
        val times = o?.list("time") ?: return emptyList()
        val codes = o.list("weather_code"); val highs = o.list("temperature_2m_max"); val lows = o.list("temperature_2m_min")
        val chances = o.list("precipitation_probability_max"); val rises = o.list("sunrise"); val sets = o.list("sunset")
        val out = ArrayList<Day>()
        for (i in 0 until minOf(times.size, MAX_DAYS)) {
            val at = times[i].seconds() ?: continue
            val up = rises.at(i).seconds()
            val down = sets.at(i).seconds()
            // Polar day and night: the service then sends the day's own start and end, or one moment twice.
            // Only a sun that rises and then sets within a day has two times worth a row each.
            val sun = up != null && down != null && down > up && down - up < DAY_SEC
            out += Day(at, codes.at(i).code(), highs.at(i).celsius(), lows.at(i).celsius(), chances.at(i).percent(), up.takeIf { sun }, down.takeIf { sun })
        }
        return out.distinctBy { it.at }.sortedBy { it.at }
    }

    /**
     * The places a search reply lists, five at most, none twice; empty when nothing matched (the
     * reply then has no list at all); null for a reply of another shape.
     */
    fun cities(text: String): List<City>? = try {
        val root = Json.parseToJsonElement(text) as? JsonObject
        val results = root?.get("results")
        when {
            root == null || root["error"].flag() == true -> null
            results == null -> emptyList()
            results !is JsonArray -> null
            else -> results.asSequence().take(50).mapNotNull { city(it as? JsonObject) }.distinct().take(MAX_PLACES).toList()
        }
    } catch (e: Exception) {
        null
    } catch (e: StackOverflowError) {
        null
    }

    private fun city(o: JsonObject?): City? {
        val name = o?.get("name").line(NAME_CHARS)
        val lat = o?.get("latitude").number()?.let { coordinate(it, 90.0) }
        val lon = o?.get("longitude").number()?.let { coordinate(it, 180.0) }
        return if (o == null || name.isEmpty() || lat == null || lon == null) null
        else City(name, o["admin1"].line(NAME_CHARS), o["country"].line(NAME_CHARS), lat, lon, o["timezone"].zoneName())
    }

    /** A reading as the text that is kept on the device: this model, never the reply. */
    fun keep(r: Reading): String = json.encodeToString(Reading.serializer(), r)

    /** What [keep] wrote, or null for anything else. Only numbers are kept, never what a try came to. */
    fun kept(text: String): Reading? = try {
        json.decodeFromString(Reading.serializer(), text).copy(failure = null, misses = 0, retryAfterSec = 0)
    } catch (e: Exception) {
        null
    }

    // ---- when to ask again, and when a reading is none ------------------------------------------

    /**
     * How long [r] stays as it is before the service is asked again, in milliseconds since the last
     * try. Half an hour after a good reading; 15 minutes after no answer; an hour after being told
     * to slow down, or as long as the service said. Without a network nothing goes out, so that
     * check is made every minute; a network that doesn't reach the service (a sign-in page, a name
     * that can't be found) does cost a try each time, and is tried less and less often.
     */
    fun every(r: Reading): Long = if (r.failure == null) FRESH_MS else Pace.after(r.failure, r.misses, r.retryAfterSec, RETRY_MS, SLOW_MS)

    /**
     * How long after a try came back the next one by hand (Refresh, Try again) has to wait. A minute
     * after an answer: there is nothing newer to fetch. Ten seconds after a try that reached nobody
     * or got no answer: the menu then says what went wrong, and the retry is the one thing it offers.
     * And never less than the service asked for, where it named a time.
     */
    fun againAfter(r: Reading): Long = maxOf(if (r.failure == null) AGAIN_MS else SOON_MS, r.retryAfterSec * 1000)

    /**
     * Refresh, or Try again, as the menu draws it. [age]: how long ago the last try for [r] came
     * back, in milliseconds; null when there was none. [loading]: a request is on its way.
     */
    fun again(r: Reading?, age: Long?, loading: Boolean): Again = when {
        loading -> Again.WAIT
        r == null || age == null || age >= againAfter(r) -> Again.READY
        r.failure == null -> Again.UP_TO_DATE
        else -> Again.WAIT
    }

    /**
     * No numbers worth showing: there are none, or they were read three hours ago or more. That is
     * asked of two clocks, and one saying so is enough. [now], the wall clock, holds across a restart
     * of the device but can be set: set back, it alone would keep an old temperature for as long as
     * it was set back. [up], the time since boot, can't be set but begins again with every boot: it
     * counts where the reading has its mark ([Reading.fetchedUp]) and has run three hours past it,
     * which after a new boot it can only have if that long has really passed.
     */
    fun old(r: Reading, now: Long, up: Long = 0): Boolean = Pace.old(r.fetchedAt, r.fetchedUp, now, up, OLD_MS)

    /**
     * The state of an item. [hasPlace]: it has a city, or My location knows where the device is. [on],
     * [setUp]: the service's switch, and whether it was ever turned on here. [reading]: what is known of
     * the place, or null. [now] is the wall clock and [up] the time since boot (see [old]). [locating]:
     * an item of My location whose place isn't known, and why; null for any other.
     */
    fun status(hasPlace: Boolean, on: Boolean, setUp: Boolean, reading: Reading?, now: Long, up: Long = 0, locating: Locate? = null): Status<Reading> =
        Status.of(hasPlace, on, setUp, reading, locating, shows = { it.current != null && !old(it, now, up) }, failure = { it.failure })

    /**
     * The service's silence, as the settings window says it over the item's options and in its row: which failure, and
     * when the reading that still shows was fetched (0: nothing shows). Null while the service answers, and with no
     * connection: that is the device's, and the item's own words say it.
     */
    fun silence(status: Status<Reading>): Pair<Failure, Long>? = when (status) {
        is Status.Missing -> status.failure to 0L
        is Status.Live -> status.reading.failure?.let { it to status.reading.fetchedAt }
        else -> null
    }?.takeIf { it.first != Failure.OFFLINE }

    // ---- the city's own time ---------------------------------------------------------------------

    /** The city's time zone as the service named it; if this device doesn't know that name, its distance from UTC then. */
    fun zone(r: Reading): ZoneId {
        val named = if (r.zone.isEmpty()) null else try { ZoneId.of(r.zone) } catch (e: Exception) { null }
        return named ?: ZoneId.ofOffset("GMT", ZoneOffset.ofTotalSeconds(r.offsetSec.coerceIn(-18 * 3600, 18 * 3600)))
    }

    /** A day's date in the city: taken at its noon, so an hour's difference over where a day starts can't move it. */
    private fun dateOf(day: Day, zone: ZoneId): LocalDate = Instant.ofEpochSecond(day.at + DAY_SEC / 2).atZone(zone).toLocalDate()

    /** The day it is now in the city: found by its date, not by its place in the reply (after midnight the first one is yesterday). */
    private fun today(r: Reading, now: Long, zone: ZoneId): Day? {
        val date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return r.days.firstOrNull { dateOf(it, zone) == date }
    }

    // ---- the rain rule ---------------------------------------------------------------------------

    /**
     * When the hour an entry's chance is for begins, in seconds since 1970: an hour before the
     * entry's own time. The service files an hour's chance under the time that hour ends at, so 80%
     * in the entry of 3 PM says that rain is likely between 2 and 3.
     */
    fun begins(entry: Hour): Long = entry.at - HOUR_SEC

    /**
     * The entry of the first hour in which rain or snow is likely (a chance of 50% or more), among
     * the hour that is running and those that begin within the next [hours] hours; or null. Its hour
     * is the one before its time ([begins]), and that is the hour the bar names. "Likely within N
     * hours" includes the hour that is running: a warning that went the moment its hour began would
     * go just when rain is nearest. An hour that is over counts for nothing. Found by their time.
     */
    fun likely(r: Reading, now: Long, hours: Int): Hour? {
        val until = now + hours * HOUR_MS
        return r.hours.filter { it.at * 1000 > now && begins(it) * 1000 <= until && (it.chance ?: 0) >= LIKELY }.minByOrNull { it.at }
    }

    /** The hour [entry]'s chance is for has begun (and, for an entry [likely] found, is not over): it is the present. */
    private fun running(entry: Hour, now: Long): Boolean = begins(entry) * 1000 <= now

    // ---- the bar ---------------------------------------------------------------------------------

    fun bar(status: Status<Reading>, look: Look, now: Long, w: WeatherWords, t: Times): Bar = when (status) {
        // An outlined cloud for "no number yet", a crossed one for "no reading": both plainly not the filled cloud of a cloudy day.
        Status.NotSetUp -> Bar(Sym.CLOUD, filled = false, desc = w.say(W.DESC_NOT_SET_UP))
        is Status.Off -> Bar(Sym.CLOUD, filled = false, desc = w.say(W.DESC_OFF))
        Status.Loading -> Bar(Sym.CLOUD, filled = false, desc = w.say(W.DESC_LOADING))
        is Status.Missing -> Bar(Sym.CLOUD_OFF, filled = false, desc = w.say(W.DESC_NO_READING))
        is Status.NoLocation -> Bar(Sym.CLOUD_OFF, filled = false, desc = w.say(W.DESC_NO_LOCATION))
        is Status.Live -> live(status.reading, look, now, w, t)
    }

    private fun live(r: Reading, look: Look, now: Long, w: WeatherWords, t: Times): Bar {
        val cur = r.current ?: return Bar(Sym.CLOUD_OFF, filled = false, desc = w.say(W.DESC_NO_READING))
        val zone = zone(r)
        fun degrees(celsius: Double) = Units.degrees(celsius, look.fahrenheit)
        // The number Show leads with: the temperature, or what it feels like.
        val lead = (if (look.show == SHOW_FEELS) cur.feels ?: cur.temp else cur.temp)?.let { Units.whole(it, look.fahrenheit) }
        val number = lead?.let { Units.degrees(it) }

        val falling = WeatherCodes.falls(cur.code)
        // The word for the sky of this minute, as the menu says it; a strong wind where nothing falls is "Windy".
        val windy = WeatherCodes.windy(cur.code, cur.windKmh)
        val sky = if (windy) w.say(W.WINDY) else WeatherCodes.sky(cur.code)?.let { w.say(it.word) }
        val next = if (falling == null) likely(r, now, look.rainHours) else null
        // What falls in a likely hour is told by the code of the entry that holds its chance: the code at the hour's
        // end comes of what fell in it. The chance comes from many forecasts and the code from one, so a likely
        // hour whose code names nothing that falls is rain, or snow when it freezes.
        val falls = falling ?: next?.let { WeatherCodes.falls(it.code) ?: if ((it.temp ?: cur.temp ?: 1.0) <= 0.0) Falls.SNOW else Falls.RAIN }
        val word = falls?.let { w.say(it.word) }
        // The hour that is named is the one the chance is for: it begins an hour before the entry's time. One that
        // is running is the present and has no time to announce: the bar says what is likely, as it does for what falls.
        val time = next?.takeIf { !running(it, now) }?.let { t.hour(begins(it) * 1000, zone) }

        // Something falling or coming is said in words, whatever Show says: that is why the item came out.
        val short = when {
            word != null -> if (number != null) w.say(W.BAR_NOW, number, word) else word
            number != null && look.show == SHOW_HIGH_LOW -> today(r, now, zone)
                ?.let { d -> if (d.high != null && d.low != null) w.say(W.BAR_HIGH_LOW, number, degrees(d.high), degrees(d.low)) else null } ?: number
            else -> number
        }
        val full = if (short != null && word != null && number != null && time != null) w.say(W.BAR_SOON, number, word, time) else short
        // Show "with conditions": the sky's own word beside the number ("72° · Partly cloudy", "54° · Light rain" where
        // the bar would say "Rain"). Rain or snow that is coming keeps its hour instead: that is why the item came out.
        val rich = if (look.show != SHOW_SKY || number == null || sky == null || (word != null && falling == null)) null
            else w.say(W.BAR_NOW, number, sky)
        // Longer than twenty characters: first the sky's word or the time goes, then the label.
        val text = if (short == null || full == null) null else {
            val label = look.label
            val forms = if (label == null) listOfNotNull(rich, full, short)
                else listOfNotNull(rich?.let { w.say(W.BAR_LABEL, label, it) }, w.say(W.BAR_LABEL, label, full), w.say(W.BAR_LABEL, label, short), short)
            forms.firstOrNull(::fits) ?: short
        }

        val city = look.city ?: w.say(W.TITLE)
        // "−4 degrees": the number as it is written, the form by its size.
        val spoken = lead?.let { w.count(W.DEGREES, abs(it), Units.degrees(it).dropLast(1)) }
        val nowIs = when {
            spoken != null && sky != null -> w.say(W.DESC, city, spoken, sky)
            spoken != null -> w.say(W.DESC_SHORT, city, spoken)
            sky != null -> w.say(W.DESC_SHORT, city, sky)
            else -> null
        }
        // "Rain now." is said only of what the sky of this minute does; of an hour that is running it is "likely this hour".
        val then = when {
            word == null -> null
            falling != null -> w.say(W.DESC_NOW, word)
            time != null -> w.say(W.DESC_SOON, word, time)
            else -> w.say(W.DESC_THIS_HOUR, word)
        }
        return Bar(
            icon = if (next != null) (if (WeatherCodes.falls(next.code) != null) WeatherCodes.glyph(next.code, next.day) else WeatherCodes.glyph(falls))
                else if (windy) Sym.AIR else WeatherCodes.glyph(cur.code, cur.day),
            filled = true,
            text = text,
            desc = listOfNotNull(nowIs, then).joinToString(" ").ifEmpty { w.say(W.DESC_NO_READING) },
            active = falls != null,
            // Tone is never the only signal: the word says it too.
            tone = when (falls) { null -> Tone.NORMAL; Falls.STORM -> Tone.WARN; else -> Tone.ACCENT },
            tooltip = if (sky != null) w.say(W.TOOLTIP, city, sky) else city,
        )
    }

    // ---- the menu --------------------------------------------------------------------------------

    /** Parts that are each a fact of their own, as one spoken line: "3 PM, Partly cloudy, 72 degrees". */
    private fun spokenLine(w: WeatherWords, parts: List<String>): String = parts.reduce { a, b -> w.say(W.LIST, a, b) }

    fun menu(r: Reading, look: Look, now: Long, w: WeatherWords, t: Times): Forecast {
        val zone = zone(r)
        val cur = r.current
        fun degrees(celsius: Double) = Units.degrees(celsius, look.fahrenheit)
        /** "78", "−4": the number alone, as it is written. */
        fun bare(celsius: Double) = degrees(celsius).dropLast(1)
        fun spoken(celsius: Double) = w.count(W.DEGREES, abs(Units.whole(celsius, look.fahrenheit)), bare(celsius))

        val moment = Instant.ofEpochMilli(now)
        val today = today(r, now, zone)
        val sky = WeatherCodes.sky(cur?.code)?.let { w.say(it.word) }
        val feels = cur?.feels?.let(::degrees)
        // The city's own time, when it is not the device's: another name for the same time is no other zone.
        val there = if (zone.rules.getOffset(moment) != t.here.rules.getOffset(moment)) t.clock(now, zone) else null
        val subtitle = when {
            sky != null && feels != null && there != null -> w.say(W.SUBTITLE_THERE, sky, feels, there)
            sky != null && feels != null -> w.say(W.SUBTITLE, sky, feels)
            else -> sky
        }

        val high = today?.high
        val low = today?.low
        val date = moment.atZone(zone).toLocalDate()
        // The highest chance in what is left of today, from the hours: the day's own figure counts the hours
        // that are over, and read "Rain 90%" on a clear afternoon after a wet morning, beside six dry hours.
        // An entry's chance is for the hour before its time: it counts when that hour ends after now (the one
        // that is running included) and begins today in the city, so the day's last hour is the entry of midnight.
        // Without hours for today, the day's figure. On a snow day it is the chance of snow.
        val chance = if (today == null) null else r.hours
            .filter { it.at * 1000 > now && Instant.ofEpochSecond(begins(it)).atZone(zone).toLocalDate() == date }
            .mapNotNull { it.chance }.maxOrNull() ?: today.chance
        val falls = if (WeatherCodes.falls(today?.code) == Falls.SNOW) Falls.SNOW else Falls.RAIN
        val wind = cur?.windKmh?.let { if (look.miles) Units.milesPerHour(it).roundToInt() else it.roundToInt() }
        val heroDesc = listOfNotNull(
            cur?.temp?.let { w.say(W.SENTENCE, spoken(it)) },
            if (high != null && low != null) w.say(W.HIGH_LOW_DESC, bare(high), bare(low)) else null,
            chance?.let { w.say(W.SENTENCE, w.say(W.LIST, w.say(falls.word), w.say(W.CHANCE_DESC, it))) },
            wind?.let { w.count(if (look.miles) W.WIND_MPH_DESC else W.WIND_KMH_DESC, it, it) },
        ).joinToString(" ")

        // A cell is a time, the temperature and the sky at that time, and the chance for the hour from then on.
        // That chance is filed under the time the hour ends at: it is the entry an hour later, found by its
        // time. Without that entry (the last cell needs one more than there are cells), no chance.
        val chanceFrom = r.hours.associate { begins(it) to it.chance }
        val hours = r.hours.filter { it.at * 1000 > now }.distinctBy { it.at }.sortedBy { it.at }.take(HOUR_CELLS).map { h ->
            val time = t.hour(h.at * 1000, zone)
            val likely = chanceFrom[h.at]?.takeIf { it >= SHOWN_CHANCE }
            HourCell(time, h.code?.let { WeatherCodes.glyph(it, h.day) }, h.temp?.let(::degrees), likely?.let { w.say(W.CHANCE, it) },
                spokenLine(w, listOfNotNull(time, WeatherCodes.sky(h.code)?.let { w.say(it.word) }, h.temp?.let(::spoken), likely?.let { w.say(W.CHANCE_DESC, it) })))
        }

        val days = r.days.filter { dateOf(it, zone) > date }.distinctBy { it.at }.sortedBy { it.at }.take(DAY_ROWS).map { d ->
            val noon = (d.at + DAY_SEC / 2) * 1000
            val likely = d.chance?.takeIf { it >= SHOWN_CHANCE }
            val both = if (d.high != null && d.low != null) w.say(W.DAY_HIGH_LOW, bare(d.high), bare(d.low)) else null
            DayLine(t.weekday(noon, zone), d.code?.let { WeatherCodes.glyph(it, day = true) }, likely?.let { w.say(W.CHANCE, it) },
                d.high?.let(::degrees), d.low?.let(::degrees),
                spokenLine(w, listOfNotNull(t.weekdayLong(noon, zone), WeatherCodes.sky(d.code)?.let { w.say(it.word) }, both, likely?.let { w.say(W.CHANCE_DESC, it) })))
        }

        // The service's credit stays while its numbers are on screen; only what follows it says that they are not live.
        val updated = w.say(when (r.failure) { null -> W.UPDATED; Failure.OFFLINE -> W.UPDATED_OFFLINE; else -> W.UPDATED_NO_ANSWER },
            t.clock(r.fetchedAt, t.here))
        return Forecast(
            glyph = WeatherCodes.glyph(cur?.code, cur?.day ?: true),
            subtitle = subtitle,
            temp = cur?.temp?.let(::degrees),
            highLow = if (high != null && low != null) w.say(W.HIGH_LOW, degrees(high), degrees(low)) else null,
            rainWind = if (chance != null && wind != null)
                w.say(if (falls == Falls.SNOW) W.SNOW_WIND else W.RAIN_WIND, chance, w.say(if (look.miles) W.WIND_MPH else W.WIND_KMH, wind)) else null,
            heroDesc = heroDesc,
            hours = hours,
            days = days,
            sunrise = today?.sunrise?.let { t.clock(it * 1000, zone) },
            sunset = today?.sunset?.let { t.clock(it * 1000, zone) },
            note = w.say(W.NOTE, updated),
        )
    }
}
