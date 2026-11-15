package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.data.Kept
import com.meridian11.books.bentobar.data.Online
import com.meridian11.books.bentobar.net.Host
import com.meridian11.books.bentobar.net.Http
import com.meridian11.books.bentobar.net.Reply
import com.meridian11.books.bentobar.net.Request
import com.meridian11.books.bentobar.net.Why
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.Executor

// Everything of the Weather item that goes online or keeps something: the two requests, what a reply
// or a failure becomes, the last reading on the device. And WeatherSource, the one way from the item
// to all of it, which decides when anything is asked at all. No Android, and nothing here is logged:
// a city and its coordinates pass through this file.

/** What a search came to. Printed, none of them names a place. */
sealed interface Found {
    /** The places the service found, five at most. None: "No place found". */
    class Places(val list: List<City>) : Found
    data object Offline : Found
    data object NoAnswer : Found
    /** Not asked at all (nothing shows items): nothing is shown for it. */
    data object Unasked : Found
}

/** One press of Search: the text that is sent, and which field asked ([by]), so that an answer shows only where it was asked for. */
class Query(val text: String, val by: String) {
    override fun toString() = "a search"
}

object WeatherLoad {
    private val service = Online.Service.OPEN_METEO

    /**
     * The forecast for a place: its coordinates with two decimals, and which values. Always metric
     * (the service's default) and converted on the device, so no unit is ever sent; the city's time
     * zone is worked out by the service ("auto"), so the device's own isn't sent either.
     */
    fun forecastRequest(place: Place) = Request(Host.OPEN_METEO, "/v1/forecast", listOf(
        "latitude" to place.lat,
        "longitude" to place.lon,
        "current" to "temperature_2m,apparent_temperature,is_day,precipitation,weather_code,wind_speed_10m",
        "hourly" to "temperature_2m,precipitation_probability,weather_code,is_day",
        "daily" to "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,sunrise,sunset",
        "timezone" to "auto",
        "timeformat" to "unixtime",
        "forecast_days" to "7",
        "forecast_hours" to "24",
    ))

    /** The city search: the text of the field and nothing of the user. No language is sent (the consent words name none), so names come in English. */
    fun searchRequest(text: String) = Request(Host.OPEN_METEO_GEOCODING, "/v1/search", listOf("name" to text, "count" to "5", "format" to "json"))

    /**
     * The places the layout's Weather items ask about. [here]: where the device is, for the items of My
     * location; without it, the cities alone, which are the places whose reading is kept on the device.
     */
    fun places(items: List<ItemConfig>, here: Place? = null): Set<Place> =
        items.filter { it.type == "weather" }.mapNotNullTo(HashSet()) { place(it, here) }

    /**
     * The place [item] asks about: its city, or for one of My location where the device is ([here], which comes rounded
     * from [WeatherHere.place]: what is asked and what a reading is held under is never the location as Android said it).
     */
    fun place(item: ItemConfig, here: Place?): Place? = if (WeatherRules.here(item)) here else WeatherRules.place(item)

    /**
     * Asks the service about [place]; blocks, so only a background load calls it. A good answer is a
     * new [Reading], kept on the device. A failure is a reading too: [last]'s numbers with what went
     * wrong, so the bar keeps what it shows. Null: it was not asked after all (the switch went off or
     * the bar hid under the load), which is neither. [now] is the wall clock and [up] the time since
     * boot, both of this moment; [places]: the layout's cities right now, the places a reading is kept
     * for (where the device is, for My location, is not among them: it is held in memory only).
     */
    fun load(place: Place, last: Reading?, now: Long, up: Long = 0, places: () -> Set<Place>): Reading? {
        val reply = Http.get(forecastRequest(place))
        val good = (reply as? Reply.Ok)?.let { WeatherRules.read(it.text, place, now, up) }
        if (good != null) {
            keep(good, places())
            return good
        }
        // Without a network the request helper answers by itself and nothing leaves the device: no miss counts for that.
        // A status, a timeout, too much, or a reply that is no forecast (a public network's sign-in page) is no answer.
        val missed = Failures.missed(reply, slowAfterMs = WeatherRules.RETRY_MS) ?: return null
        return failed(place, last, missed.failure, missed.went, missed.waitSec)
    }

    /**
     * What a failed try leaves: the numbers that were there for [place] (never another place's), with
     * what went wrong. [went]: the request did go out, so it counts as one more miss in a row.
     */
    fun failed(place: Place, last: Reading?, failure: Failure, went: Boolean, waitSec: Long = 0): Reading {
        val known = last?.takeIf { it.place == place.key } ?: Reading(place.key)
        return known.copy(failure = failure, misses = known.misses + if (went) 1 else 0, retryAfterSec = waitSec)
    }

    /**
     * Keeps a good reading for a restart, and removes what belongs to no item any more. A place the
     * layout no longer has (its item was deleted or its city changed while the answer was on its way)
     * is not kept. While the service is off nothing is written at all (see [Kept.fetched]).
     */
    private fun keep(reading: Reading, places: Set<Place>) {
        try {
            val kept = Kept.fetched(service)
            if (places.any { it.key == reading.place }) kept.write(reading.place, WeatherRules.keep(reading), reading.fetchedAt)
            kept.keepOnly(places.mapTo(HashSet()) { it.key })
        } catch (e: Exception) {
            // Nothing kept: the next start asks the service instead.
        }
    }

    /** "Deleted with the item": what is kept for a place that is not among [places] goes. Disk work, for a background thread. */
    fun tidy(places: Set<Place>) {
        try { Kept.fetched(service).keepOnly(places.mapTo(HashSet()) { it.key }) } catch (e: Exception) { /* nothing there to remove */ }
    }

    /**
     * What was kept for [place] and how old it is by the wall clock, so that a restart shows the last
     * reading with its time and asks nobody while it is fresh. What can't be read back is removed.
     */
    fun kept(place: Place, now: Long): Refresher.Restored<Reading>? = try {
        val kept = Kept.fetched(service)
        val entry = kept.read(place.key)
        val reading = entry?.let { WeatherRules.kept(it.text) }?.takeIf { it.place == place.key }
        if (entry != null && reading == null) kept.remove(place.key)
        reading?.let { Refresher.Restored(it, (now - it.fetchedAt).coerceAtLeast(0)) }
    } catch (e: Exception) {
        null
    }

    /** Asks the service for places named [text]; blocks. */
    fun search(text: String): Found = when (val reply = Http.get(searchRequest(text))) {
        is Reply.Ok -> WeatherRules.cities(reply.text)?.let { Found.Places(it) } ?: Found.NoAnswer
        is Reply.Failed -> when (reply.why) {
            Why.OFF -> Found.Unasked
            Why.OFFLINE -> Found.Offline
            else -> Found.NoAnswer
        }
    }
}

/**
 * The Weather item's one way to the service: everything the item, its menu and its settings do that
 * could send something goes through here, and the tests drive exactly this. The rules it holds:
 *
 * Nothing is asked for an item without a city, or while the service is switched off;
 * and it is switched on in two places only, [search] and [turnOn], which are the user's own acts.
 * One reading per place serves every item with that city.
 */
class WeatherSource(
    private val readings: Refresher<Place, Reading>,
    private val asking: Ask<Query, Found>,
    private val background: Executor,
    /** The wall clock, and the time since boot: a reading's age is asked of both ([WeatherRules.old]). */
    private val wall: () -> Long,
    private val up: () -> Long,
    /** Where the device is, rounded ([WeatherHere.place]), and why not where that isn't known: for the items of My location. */
    private val here: () -> Place? = { null },
    private val locating: () -> Locate = { Locate.FINDING },
) {
    private val service = Online.Service.OPEN_METEO

    /** The place [item] asks about: its city, or where the device is. Null: none yet. */
    private fun place(item: ItemConfig): Place? = WeatherLoad.place(item, here())

    /** The place [item] may ask about right now, or null: no city, or the switch off. */
    private fun asked(item: ItemConfig): Place? =
        if (!Online.on(service)) null else place(item)

    /**
     * What is known for [item]'s place, loading it first if that is due. This is what the bar's state
     * calls, every time it is computed. Null: nothing yet, no city, or the switch off. Main thread.
     */
    fun reading(item: ItemConfig): Reading? {
        if (!Online.on(service)) return null
        val place = place(item) ?: return null
        readings.want(place)
        askIfOld(place)
        return readings.peek(place)
    }

    /**
     * The loader asks again by the time since boot, half an hour after a reading. "Too old to show"
     * is also read off the clock on the wall, and that can be set: two minutes after a reading it is
     * four hours later, the numbers go, and the item says "Loading…" while nothing loads. So a reading
     * that is too old and has no failure to explain it is asked for now (not within a minute of the
     * last try, like Refresh). One whose last try failed keeps to the failure's pace.
     */
    private fun askIfOld(place: Place) {
        val reading = readings.peek(place) ?: return
        if (reading.failure == null && WeatherRules.old(reading, wall(), up()) && !readings.loading(place))
            readings.refresh(place, floorMs = WeatherRules.AGAIN_MS)
    }

    /** What there is for [item]'s place, asking nothing. Any thread. */
    fun peek(item: ItemConfig): Reading? = place(item)?.let { readings.peek(it) }

    /** The state [item] is in, asking nothing. */
    fun status(item: ItemConfig, now: Long): Status<Reading> {
        val on = Online.on(service)
        val place = place(item)
        return WeatherRules.status(hasPlace = place != null, on = on, setUp = Online.setUp(service),
            reading = if (on) place?.let { readings.peek(it) } else null, now = now, up = up(),
            locating = if (WeatherRules.here(item) && place == null) locating() else null)
    }

    fun loading(item: ItemConfig): Boolean = place(item)?.let { readings.loading(it) } ?: false

    /**
     * The item's menu opened: a good reading older than ten minutes is asked again. One whose last
     * try failed keeps to its own pace, as its words say ("tries again in 15 minutes"). And without
     * a network a good reading of any age is tried at once: nothing goes out for that, and the menu
     * then says "no connection" beside the reading's time instead of passing it off as live.
     * Main thread.
     */
    fun opened(item: ItemConfig) {
        val place = asked(item) ?: return
        val reading = readings.peek(place)
        when {
            reading == null -> readings.want(place)
            reading.failure != null -> {}
            // Too old to show (the clock was set on): the menu would say "Loading…", so it is.
            WeatherRules.old(reading, wall(), up()) -> askIfOld(place)
            else -> readings.refresh(place, floorMs = if (Http.connected()) WeatherRules.MENU_MS else 0)
        }
    }

    /**
     * Refresh (or Try again) as [item]'s menu draws it right now: see [WeatherRules.again]. For an
     * item that may ask nothing (no city, the switch off) it is dimmed and says nothing.
     * Main thread.
     */
    fun againEntry(item: ItemConfig): Again {
        val place = asked(item) ?: return Again.WAIT
        return WeatherRules.again(readings.peek(place), readings.age(place), readings.loading(place))
    }

    /**
     * Refresh, or Try again: asks now, unless the last try came back too short a while ago for that
     * ([WeatherRules.againAfter]). False: nothing was asked. Main thread.
     */
    fun again(item: ItemConfig): Boolean {
        val place = asked(item) ?: return false
        return !readings.loading(place) && readings.refresh(place, floorMs = readings.peek(place)?.let { WeatherRules.againAfter(it) } ?: 0)
    }

    /** The search's state, for whoever draws it; see [shown]. */
    val searching: StateFlow<Ask.State<Query, Found>> get() = asking.state

    /**
     * Search, or Enter in the field: the one act that sends a name, and with it the Weather switch
     * goes on. Typing never comes here. False, and nothing happens: fewer than two letters. [by]
     * names the field that asked. Main thread.
     */
    fun search(text: String, item: ItemConfig, by: String): Boolean {
        val name = WeatherRules.query(text) ?: return false
        Online.turnOn(service)
        asking.ask(Query(name, by))
        return true
    }

    /** The text of the field [by] changed: what was found for the old text goes. Nothing is sent. Main thread. */
    fun typed(by: String) = asking.clearIf { it.by == by }

    /** "Turn on weather": for a layout that came with a city, or after the switch was turned off in Setup. Also "Use my location". */
    fun turnOn(): Boolean = Online.turnOn(service)

    /**
     * The layout's places are now [places]: readings of any other place go, from memory at once and
     * from the device in the background ("deleted with the item"). [cities]: those of them that are
     * kept on the device, the cities; where the device is (My location) is held in memory only. On an
     * install that never set Weather up nothing was ever fetched, so there is nothing to look for.
     * Main thread.
     */
    fun keepOnly(places: Set<Place>, cities: Set<Place> = places) {
        readings.keepOnly(places)
        if (Online.setUp(service)) background.execute { WeatherLoad.tidy(cities) }
    }

    companion object {
        /**
         * The source with its loader and its search wired to [WeatherLoad]: the app and the tests make
         * it here alike and differ only in where work runs and what time it is. [wall]: the wall
         * clock; [up]: the time since boot, which the loader counts by (the app's `Now.elapsed`);
         * [layout]: the layout's items right now (asked from the background thread).
         * [here] and [locating]: where the device is, for My location ([WeatherHere]).
         * [staged]: a debug build's test hook; when it names a failure, the load that asked is not
         * sent and comes back as that failure, so the pace after an error can be watched on a device.
         */
        fun make(
            refresher: (every: (Place, Reading) -> Long?, restore: (Place) -> Refresher.Restored<Reading>?, load: (Place, Reading?) -> Reading?) -> Refresher<Place, Reading>,
            ask: (work: (Query) -> Found) -> Ask<Query, Found>,
            background: Executor,
            wall: () -> Long,
            up: () -> Long,
            layout: () -> List<ItemConfig>,
            staged: () -> Failure? = { null },
            here: () -> Place? = { null },
            locating: () -> Locate = { Locate.FINDING },
        ): WeatherSource = WeatherSource(
            // Only a city's reading is kept on the device and read back from it: where the device is (My location) is held in
            // memory only, so a reading of that place is neither written nor looked for there.
            refresher({ _, reading -> WeatherRules.every(reading) }, { place -> if (place in WeatherLoad.places(layout())) WeatherLoad.kept(place, wall()) else null },
                { place, last ->
                    // "No connection" is what nothing-went-out looks like; the other two stand for a try that did.
                    staged()?.let { WeatherLoad.failed(place, last, it, went = it != Failure.OFFLINE) }
                        ?: WeatherLoad.load(place, last, wall(), up()) { WeatherLoad.places(layout()) }
                }),
            ask { query -> WeatherLoad.search(query.text) },
            background, wall, up, here, locating,
        )

        /** [state] as the field [by] shows it: an answer to another field's question is none of its business. */
        fun shown(state: Ask.State<Query, Found>, by: String): Ask.State<Query, Found> = state.shownTo { it.by == by }
    }
}
