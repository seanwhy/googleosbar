package com.meridian11.books.bentobar.items

import android.annotation.SuppressLint
import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.SystemClock
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.util.Now

/**
 * Where the device is, for the items of My location: Android's approximate location (the
 * only location permission BentoBar has), rounded here to about ten kilometers ([place]) before
 * anything else sees it.
 *
 * It lives in memory only. The layout holds the choice and never the place, and nothing here is
 * logged or written, nor is the reading of the rounded place: that too is held in memory only.
 * What is done is [HereKeeper]'s, by [HereRules] (both pure and tested); this object is the app's
 * keeper, on Android's location service, and recomputes the Weather items alone when what they show
 * changed (`Ticker.refresh("weather")`: no sampler runs, since this can come from the middle of a tick).
 */
object WeatherHere {
    private val keeper = HereKeeper(Android, Now::elapsed) { Ticker.refresh(WeatherItem.type); Ticker.refresh(AirItem.type) }

    /**
     * Where the device is, rounded to about ten kilometers ([WeatherRules.nearby]): the only form anything outside this object
     * gets, so that no caller can ask about or hold it as Android said it. Null until Android said, and once that is past its time ([HereRules.FIX_MS]). Any thread.
     */
    val place: Place? get() = keeper.fix?.let { WeatherRules.nearby(it.lat, it.lon) }

    /** Why there is no [place], while there is none. Any thread. */
    val why: Locate get() = keeper.why

    fun allowed(): Boolean = Android.allowed()

    /** One tick, from the ticker, once ([TickLoop.Wiring.location]): see [HereKeeper.follow]. Main thread. */
    fun follow(layout: List<ItemConfig>, sampled: List<ItemConfig>, on: Boolean) = keeper.follow(layout, sampled, on)

    /** Something changed that could let a place be found now: the permission was answered, an item chose My location. Main thread. */
    fun wake() = keeper.wake()

    /**
     * Forgets where the device is, and calls off an ask on its way: the switch went off (forgetFetched).
     * The layout's items forget it through [follow]. Main thread.
     */
    fun forget() = keeper.forget()

    /** Android's location service, as [HereKeeper] uses it. */
    private object Android : Locator {
        override fun allowed(): Boolean =
            Env.app.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        // A device without a location service at all is one with location off.
        private fun service(): LocationManager? = Env.app.getSystemService(LocationManager::class.java)

        override fun on(): Boolean = service()?.isLocationEnabled == true

        // The coarse-location grant is asked for only when a Weather item chooses My location (HereRules), and a call made
        // without it throws SecurityException, which the runCatching here (and the try below) turns into "no fix".
        @SuppressLint("MissingPermission")
        override fun last(): Located? {
            val lm = service() ?: return null
            return providers(lm).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                .maxByOrNull { it.elapsedRealtimeNanos }?.let(::located)
        }

        @SuppressLint("MissingPermission") // as in last(): a missing grant throws, and the catch below answers "no fix"
        override fun current(answer: (Located?) -> Unit): Locator.Cancel? {
            val lm = service() ?: return null
            val provider = providers(lm).firstOrNull() ?: return null
            val signal = CancellationSignal()
            return try {
                lm.getCurrentLocation(provider, signal, Env.app.mainExecutor) { answer(it?.let(::located)) }
                Locator.Cancel { signal.cancel() }
            } catch (e: Exception) {
                null
            }
        }

        /** The location providers an approximate location comes from, best first: Android's fused one, then the network's. */
        private fun providers(lm: LocationManager): List<String> =
            listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }

        /** How old it is is counted from when it was taken, not from when it came, by Android's own clock (a debug build's moved clock aside). */
        private fun located(location: Location) = Located(Fix(location.latitude, location.longitude),
            age = ((SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000).coerceAtLeast(0))
    }
}
