package com.meridian11.books.bentobar.items

import androidx.compose.runtime.Immutable

// What the online items that ask about a place have in common: a place, where the device is, why that isn't known, and
// why a try brought nothing. Kept apart from any one service's rules, so that a new service doesn't begin by importing Weather.

/** Why the last try brought no reading. Each is a state with words of its own, never the failed item's "!". */
enum class Failure { OFFLINE, NO_ANSWER, SLOW_DOWN }

/**
 * A city's coordinates as they are stored in the layout and sent: two decimals each, about a
 * kilometer. Printed, it says nothing of where it is.
 */
@Immutable
data class Place(val lat: String, val lon: String) {
    /** "47.37,8.55": what a reading is loaded and kept under, so two items with one city share one request. */
    val key: String get() = "$lat,$lon"
    override fun toString() = "a place"
}

/**
 * Where the device is, as Android said it (My location): for [WeatherHere] and [HereKeeper] alone. Held in memory only,
 * and never used as it is: [WeatherHere.place] rounds it to a [Place] ([WeatherRules.nearby]), the only form anything else gets.
 */
data class Fix(val lat: Double, val lon: Double) {
    override fun toString() = "a fix"
}

/**
 * Why an item that shows the weather of where the device is has no place yet ([WeatherRules.status]).
 * [FINDING]: Android was asked and hasn't answered. The others are states with words of their own.
 */
enum class Locate { FINDING, NOT_ALLOWED, OFF, NONE }

/** Which of its states an item that asks a service about a place is in; [R] is that service's reading. */
sealed interface Status<out R> {
    /** No city yet. */
    data object NotSetUp : Status<Nothing>
    /** A city, but the service is switched off: in Setup ([everOn]), or it never was on here (a layout that came with a city). */
    data class Off(val everOn: Boolean) : Status<Nothing>
    /**
     * Nothing to show yet: a city's or My location's first reading is on its way, one too old to show is
     * asked again, or Android is asked where the device is for the first time.
     */
    data object Loading : Status<Nothing>
    /** My location, and the device's location isn't known: not allowed, switched off, or Android has none. */
    data class NoLocation(val why: Locate) : Status<Nothing>
    /** No numbers worth showing, and the last try failed. */
    data class Missing(val failure: Failure) : Status<Nothing>
    /** Numbers worth showing. With the reading's failure they are "not live": the last try failed. */
    data class Live<R>(val reading: R) : Status<R>

    companion object {
        /**
         * The state of an item. [hasPlace]: it has a city, or My location knows where the device is. [on], [setUp]: the
         * service's switch, and whether it was ever turned on here. [reading]: what is known of the place, or null. [shows]: its
         * numbers are worth showing; [failure]: what its last try came to. [locating]: an item of My location whose place isn't
         * known, and why; null for any other.
         */
        fun <R> of(hasPlace: Boolean, on: Boolean, setUp: Boolean, reading: R?, locating: Locate?, shows: (R) -> Boolean, failure: (R) -> Failure?): Status<R> = when {
            !hasPlace && locating == null -> NotSetUp
            !on -> Off(everOn = setUp)
            locating == Locate.FINDING -> Loading
            locating != null -> NoLocation(locating)
            reading == null -> Loading
            shows(reading) -> Live(reading)
            else -> failure(reading)?.let { Missing(it) } ?: Loading
        }
    }
}
