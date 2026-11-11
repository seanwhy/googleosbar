package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.util.Sym
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.roundToInt

/**
 * Pure rules for the Air quality and UV item: parsing Open-Meteo's `/v1/air-quality` reply,
 * US and European AQI bands, UV bands, and what the bar and menu show. No Android imports.
 */
object AirRules {
    const val FRESH_MS = 30 * 60_000L
    const val MENU_MS = 10 * 60_000L
    /** After no answer. */
    const val RETRY_MS = 15 * 60_000L
    /** After being told to slow down, unless the service named a longer time. */
    const val SLOW_MS = 60 * 60_000L
    /** Refresh after an answer: once a minute at most. */
    const val AGAIN_MS = 60_000L
    /** An old index is a wrong index: after this long without an answer the numbers go, from the bar and from the menu. */
    const val OLD_MS = 3 * 60 * 60_000L
    const val BAR_CHARS = 20

    enum class Band { GOOD, MODERATE, SENSITIVE, UNHEALTHY, VERY_UNHEALTHY, HAZARDOUS }
    enum class UvBand { LOW, MODERATE, HIGH, VERY_HIGH, EXTREME }

    data class AirReading(
        val place: String,
        val usAqi: Int? = null,
        val euAqi: Int? = null,
        val pm25: Double? = null,
        val pm10: Double? = null,
        val ozone: Double? = null,
        val uv: Double? = null,
        val uvMax: Double? = null,
        val fetchedAt: Long = 0,
        val failure: Failure? = null,
        /** How many tries in a row went out and failed. */
        val misses: Int = 0,
        /** How long the service asked to be left alone. */
        val retryAfterSec: Long = 0,
        /** The time since boot when the numbers were read; 0 when not known. See [Pace.old]. */
        val fetchedUp: Long = 0,
    ) {
        val hasValues: Boolean get() = usAqi != null || euAqi != null || uv != null
        override fun toString() = "AirReading(hasValues=$hasValues, failure=$failure)"
    }

    /** An index and the scale it is on: what a band, a label and a threshold have to go by, whichever scale was asked for. */
    data class Aqi(val value: Int, val eu: Boolean)

    /** Which parts of a reading an item shows: an index, a UV figure, or both. */
    data class Parts(val aqi: Boolean, val uv: Boolean)

    class Bar(
        val icon: String,
        val filled: Boolean,
        val text: String?,
        val tone: Tone,
        val active: Boolean,
        val desc: String,
        val tooltip: String,
    )

    fun usBand(aqi: Int): Band = when {
        aqi <= 50 -> Band.GOOD
        aqi <= 100 -> Band.MODERATE
        aqi <= 150 -> Band.SENSITIVE
        aqi <= 200 -> Band.UNHEALTHY
        aqi <= 300 -> Band.VERY_UNHEALTHY
        else -> Band.HAZARDOUS
    }

    fun euBand(aqi: Int): Band = when {
        aqi <= 20 -> Band.GOOD
        aqi <= 40 -> Band.MODERATE
        aqi <= 60 -> Band.SENSITIVE
        aqi <= 80 -> Band.UNHEALTHY
        aqi <= 100 -> Band.VERY_UNHEALTHY
        else -> Band.HAZARDOUS
    }

    fun uvBand(uv: Double): UvBand = when {
        uv < 3.0 -> UvBand.LOW
        uv < 6.0 -> UvBand.MODERATE
        uv < 8.0 -> UvBand.HIGH
        uv < 11.0 -> UvBand.VERY_HIGH
        else -> UvBand.EXTREME
    }

    fun formatUv(uv: Double): String {
        val rounded10 = (uv * 10.0).roundToInt()
        return if (rounded10 % 10 == 0) "${rounded10 / 10}" else "${rounded10 / 10}.${rounded10 % 10}"
    }

    fun formatUg(v: Double): String {
        val rounded10 = (v * 10.0).roundToInt()
        return if (rounded10 % 10 == 0) "${rounded10 / 10}" else "${rounded10 / 10}.${rounded10 % 10}"
    }

    /** Parses Open-Meteo's `/v1/air-quality` JSON reply. [now] is the wall clock and [up] the time since boot, both of this moment. */
    fun read(jsonText: String, place: Place, now: Long, up: Long = 0): AirReading? = try {
        val root = Json.parseToJsonElement(jsonText).jsonObject
        val cur = root["current"] as? JsonObject ?: return null
        val us = cur["us_aqi"]?.jsonPrimitive?.intOrNull
        val eu = cur["european_aqi"]?.jsonPrimitive?.intOrNull
        val pm25 = cur["pm2_5"]?.jsonPrimitive?.doubleOrNull
        val pm10 = cur["pm10"]?.jsonPrimitive?.doubleOrNull
        val ozone = cur["ozone"]?.jsonPrimitive?.doubleOrNull
        val uv = cur["uv_index"]?.jsonPrimitive?.doubleOrNull
        val hourly = root["hourly"] as? JsonObject
        val uvHourly = (hourly?.get("uv_index") as? JsonArray)?.mapNotNull { it.jsonPrimitive.doubleOrNull }.orEmpty()
        val uvMax = (uvHourly + listOfNotNull(uv)).maxOrNull()
        if (us == null && eu == null && uv == null) null
        else AirReading(place.key, us, eu, pm25, pm10, ozone, uv, uvMax, fetchedAt = now, fetchedUp = up)
    } catch (e: Exception) {
        null
    }

    /** Half an hour after a good reading; a failure at its own pace, as Weather's: the two share a service and a user's expectations. */
    fun every(r: AirReading): Long = if (r.failure == null) FRESH_MS else Pace.after(r.failure, r.misses, r.retryAfterSec, RETRY_MS, SLOW_MS)

    /** No numbers worth showing: there are none, or they are three hours old ([Pace.old]). Yesterday's AQI is no alert today. */
    fun old(r: AirReading, now: Long, up: Long = 0): Boolean = Pace.old(r.fetchedAt, r.fetchedUp, now, up, OLD_MS)

    /** The state of an item; see [Status.of]. [now] is the wall clock and [up] the time since boot ([old]). */
    fun status(hasPlace: Boolean, on: Boolean, setUp: Boolean, reading: AirReading?, now: Long, up: Long = 0, locating: Locate? = null): Status<AirReading> =
        Status.of(hasPlace, on, setUp, reading, locating, shows = { it.hasValues && !old(it, now, up) }, failure = { it.failure })

    /**
     * The index the reading has, on the scale asked for ([eu]) when the reply had that one, else on the other: with
     * its scale, so that a US 75 is never called "EAQI 75, Unhealthy". Null: the reply had neither, and the item says so.
     */
    fun aqiOf(reading: AirReading, eu: Boolean): Aqi? {
        val first = if (eu) reading.euAqi else reading.usAqi
        val other = if (eu) reading.usAqi else reading.euAqi
        return first?.let { Aqi(it, eu) } ?: other?.let { Aqi(it, !eu) }
    }

    fun bandOf(aqi: Aqi): Band = if (aqi.eu) euBand(aqi.value) else usBand(aqi.value)

    /** What is shown of [show] ("aqi", "uv", "both"): a part with no value is left out, and if that leaves nothing the part that has one is shown instead. */
    fun parts(show: String, hasAqi: Boolean, hasUv: Boolean): Parts {
        val wantsAqi = show != "uv"
        val wantsUv = show != "aqi"
        val aqi = hasAqi && wantsAqi
        val uv = hasUv && wantsUv
        return if (aqi || uv) Parts(aqi, uv) else Parts(hasAqi, hasUv)
    }

    fun toneOf(band: Band?, uv: Double?): Tone {
        val aqiTone = when (band) {
            Band.UNHEALTHY, Band.VERY_UNHEALTHY, Band.HAZARDOUS -> Tone.ALERT
            Band.SENSITIVE -> Tone.WARN
            else -> Tone.NORMAL
        }
        val uvTone = when {
            uv == null -> Tone.NORMAL
            uv >= 8.0 -> Tone.ALERT
            uv >= 6.0 -> Tone.WARN
            else -> Tone.NORMAL
        }
        return when {
            aqiTone == Tone.ALERT || uvTone == Tone.ALERT -> Tone.ALERT
            aqiTone == Tone.WARN || uvTone == Tone.WARN -> Tone.WARN
            else -> Tone.NORMAL
        }
    }

    fun active(reading: AirReading, thresholdAqi: Int, eu: Boolean): Boolean {
        val aqi = aqiOf(reading, eu)
        val uv = reading.uv
        val aqiHit = if (aqi == null) false else if (aqi.eu) aqi.value >= (thresholdAqi * 0.6).roundToInt() else aqi.value >= thresholdAqi
        val uvHit = uv != null && uv >= 6.0
        return aqiHit || uvHit
    }

    /** Debug samples (`./bento debug air stage good|moderate|smoke|highuv|offline`). */
    fun sample(name: String, now: Long): AirReading? = when (name) {
        "good" -> AirReading("47.37,8.55", usAqi = 28, euAqi = 16, pm25 = 6.4, pm10 = 12.1, ozone = 54.0, uv = 2.0, uvMax = 4.5, fetchedAt = now - 60_000)
        "moderate" -> AirReading("47.37,8.55", usAqi = 74, euAqi = 42, pm25 = 22.8, pm10 = 38.0, ozone = 82.0, uv = 5.0, uvMax = 6.2, fetchedAt = now - 60_000)
        "smoke" -> AirReading("47.37,8.55", usAqi = 168, euAqi = 88, pm25 = 84.5, pm10 = 112.0, ozone = 96.0, uv = 3.0, uvMax = 5.0, fetchedAt = now - 60_000)
        "highuv" -> AirReading("47.37,8.55", usAqi = 42, euAqi = 24, pm25 = 9.2, pm10 = 18.0, ozone = 68.0, uv = 8.5, uvMax = 9.2, fetchedAt = now - 60_000)
        "offline" -> AirReading("47.37,8.55", usAqi = 35, euAqi = 20, pm25 = 8.0, pm10 = 15.0, ozone = 60.0, uv = 3.0, uvMax = 5.0, fetchedAt = now - 600_000, failure = Failure.OFFLINE)
        else -> null
    }
}
