package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.items.AirRules.AirReading
import com.meridian11.books.bentobar.net.Host
import com.meridian11.books.bentobar.net.Http
import com.meridian11.books.bentobar.net.Reply
import com.meridian11.books.bentobar.net.Request

// Requests and loading for the Air quality and UV item (Open-Meteo). No Android, and no logging.

object AirLoad {
    fun request(place: Place) = Request(Host.OPEN_METEO_AIR, "/v1/air-quality", listOf(
        "latitude" to place.lat,
        "longitude" to place.lon,
        "current" to "us_aqi,european_aqi,pm2_5,pm10,ozone,uv_index",
        "hourly" to "uv_index",
        "timezone" to "auto",
        "timeformat" to "unixtime",
        // Today, in the place's own time (timezone=auto), since the menu says "Peak UV today": 24 hours from now would be tomorrow's midday by evening.
        "forecast_days" to "1",
    ))

    /** The place [item] asks about: its city, or where the device is ([here], already rounded by [WeatherHere.place]). */
    fun place(item: ItemConfig, here: Place?): Place? = if (WeatherRules.here(item)) here else WeatherRules.place(item)

    fun places(items: List<ItemConfig>, here: Place? = null): Set<Place> =
        items.filter { it.type == "air" }.mapNotNullTo(HashSet()) { place(it, here) }

    /**
     * Asks the service about [place]; blocks, so only a background load calls it. A good answer is a new reading; a failure is a
     * reading too, with what went wrong and the numbers there were. Null: it was not asked after all (the switch went off or
     * the bar hid under the load). [now] is the wall clock and [up] the time since boot, both of this moment. No default for [up]: a caller that left it out would give the reading no second clock (Pace.old counts it only above 0).
     */
    fun load(place: Place, last: AirReading?, now: Long, up: Long): AirReading? {
        val reply = Http.get(request(place))
        val good = (reply as? Reply.Ok)?.let { AirRules.read(it.text, place, now, up) }
        if (good != null) return good
        val missed = Failures.missed(reply, slowAfterMs = AirRules.RETRY_MS) ?: return null
        val known = last?.takeIf { it.place == place.key } ?: AirReading(place.key)
        return known.copy(failure = missed.failure, misses = known.misses + if (missed.went) 1 else 0, retryAfterSec = missed.waitSec)
    }
}
