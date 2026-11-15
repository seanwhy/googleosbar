package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.data.Kept
import com.meridian11.books.bentobar.data.Online
import com.meridian11.books.bentobar.data.Online.Service.OPEN_METEO
import com.meridian11.books.bentobar.data.Section
import com.meridian11.books.bentobar.net.FakeHttp
import com.meridian11.books.bentobar.net.Host
import com.meridian11.books.bentobar.net.Http
import com.meridian11.books.bentobar.net.HttpTransport
import com.meridian11.books.bentobar.net.Reply
import com.meridian11.books.bentobar.net.Transport
import com.meridian11.books.bentobar.net.Why
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.ZoneId
import java.util.Locale

/**
 * What the Weather item sends, to the letter, and what it makes of every way a request can end. A
 * fake stands in the network's place; the replies are the real ones saved under `openmeteo`.
 */
class WeatherLoadTest {
    private val dir = File(System.getProperty("java.io.tmpdir"), "bentobar-weather-${System.nanoTime()}")
    private val zurich = Place("47.37", "8.55")
    private val oslo = Place("59.91", "10.75")
    private val now = 1791192325_000L
    private val min = 60_000L
    private fun file(name: String) = File("src/test/resources/openmeteo/$name").readText()
    private val forecast get() = file("forecast_zurich.json")

    @Before fun fresh() { Online.init(dir); Online.turnOn(OPEN_METEO) } // what was fetched is kept only while the service is on
    @After fun gone() { dir.deleteRecursively(); Online.init(File(dir, "empty")) }

    private fun load(place: Place = zurich, last: Reading? = null, at: Long = now, layout: Set<Place> = setOf(zurich, oslo)) =
        WeatherLoad.load(place, last, at) { layout }

    // ---- what is sent ---------------------------------------------------------------------------

    @Test fun theForecastRequestIsThisAndNothingElse() {
        val r = WeatherLoad.forecastRequest(zurich)
        assertEquals(Host.OPEN_METEO, r.host)
        assertEquals("api.open-meteo.com", r.host.domain)
        assertEquals("/v1/forecast", r.path)
        assertEquals(listOf(
            "latitude" to "47.37",
            "longitude" to "8.55",
            "current" to "temperature_2m,apparent_temperature,is_day,precipitation,weather_code,wind_speed_10m",
            "hourly" to "temperature_2m,precipitation_probability,weather_code,is_day",
            "daily" to "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,sunrise,sunset",
            "timezone" to "auto",
            "timeformat" to "unixtime",
            "forecast_days" to "7",
            "forecast_hours" to "24",
        ), r.query)
        assertEquals("https://api.open-meteo.com/v1/forecast?latitude=47.37&longitude=8.55" +
            "&current=temperature_2m%2Capparent_temperature%2Cis_day%2Cprecipitation%2Cweather_code%2Cwind_speed_10m" +
            "&hourly=temperature_2m%2Cprecipitation_probability%2Cweather_code%2Cis_day" +
            "&daily=weather_code%2Ctemperature_2m_max%2Ctemperature_2m_min%2Cprecipitation_probability_max%2Csunrise%2Csunset" +
            "&timezone=auto&timeformat=unixtime&forecast_days=7&forecast_hours=24", HttpTransport.address(r))
    }

    @Test fun unitsAndLanguageAreNeverSentSoAChoiceOfUnitNeedsNoRequest() {
        // Always metric, converted on the device. Nothing names the user, the device, its language or its own time zone.
        for (r in listOf(WeatherLoad.forecastRequest(zurich), WeatherLoad.searchRequest("Zurich"))) {
            val names = r.query.map { it.first }
            for (never in listOf("temperature_unit", "wind_speed_unit", "windspeed_unit", "precipitation_unit", "language", "apikey", "api_key",
                "models", "elevation", "past_days", "start_date", "countryCode"))
                assertFalse("$never is sent", never in names)
            assertEquals("no name twice", names.size, names.toSet().size)
            for ((name, value) in r.query) {
                assertFalse("$name is sent as $value", value.contains("fahrenheit", ignoreCase = true) || value.contains("mph", ignoreCase = true))
                assertFalse("$name is the device's language", value == Locale.getDefault().toLanguageTag() || value == Locale.getDefault().language)
                assertFalse("$name is the device's time zone", value == ZoneId.systemDefault().id)
            }
        }
        // The city's zone is worked out by the service from the coordinates: the device's own is not sent.
        assertEquals("auto", WeatherLoad.forecastRequest(zurich).query.toMap()["timezone"])
        assertEquals(setOf("latitude", "longitude", "current", "hourly", "daily", "timezone", "timeformat", "forecast_days", "forecast_hours"),
            WeatherLoad.forecastRequest(zurich).query.map { it.first }.toSet())
        assertEquals(setOf("name", "count", "format"), WeatherLoad.searchRequest("Zurich").query.map { it.first }.toSet())
    }

    @Test fun theSearchRequestIsTheTextAndNothingOfTheUser() {
        val r = WeatherLoad.searchRequest("São Paulo")
        assertEquals(Host.OPEN_METEO_GEOCODING, r.host)
        assertEquals("geocoding-api.open-meteo.com", r.host.domain)
        assertEquals("/v1/search", r.path)
        assertEquals(listOf("name" to "São Paulo", "count" to "5", "format" to "json"), r.query)
        assertEquals("https://geocoding-api.open-meteo.com/v1/search?name=S%C3%A3o+Paulo&count=5&format=json", HttpTransport.address(r))
    }

    @Test fun aRequestPrintsNothingOfACityOrItsCoordinates() {
        assertEquals("api.open-meteo.com/v1/forecast", WeatherLoad.forecastRequest(zurich).toString())
        assertEquals("geocoding-api.open-meteo.com/v1/search", WeatherLoad.searchRequest("Springfield").toString())
        assertFalse(Query("Springfield", "menu:w1").toString().contains("Springfield"))
        assertFalse(Found.Places(WeatherRules.cities(file("search_springfield.json"))!!).toString().contains("Springfield"))
    }

    @Test fun coordinatesGoOutWithTwoDecimalsWhateverTheLayoutHolds() = FakeHttp.use { net ->
        net.reply(Host.OPEN_METEO, "/v1/forecast", forecast)
        val item = ItemConfig("w1", "weather", options = mapOf("city" to "Zurich", "lat" to "47.3666712345", "lon" to "8.5500001"))
        val place = WeatherRules.place(item)!!
        assertNotNull(WeatherLoad.load(place, null, now) { setOf(place) })
        assertEquals("47.37", net.query("latitude"))
        assertEquals("8.55", net.query("longitude"))
        for ((name, value) in net.asked.single().query) if (name == "latitude" || name == "longitude")
            assertTrue("$name=$value", value.matches(Regex("-?\\d{1,3}\\.\\d{2}")))
        assertEquals(Host.OPEN_METEO, net.asked.single().host)
        assertEquals("/v1/forecast", net.asked.single().path)
    }

    // ---- a good answer ------------------------------------------------------------------------

    @Test fun aGoodAnswerIsAReadingTakenNow() = FakeHttp.use { net ->
        net.reply(Host.OPEN_METEO, "/v1/forecast", forecast)
        val r = load()!!
        assertEquals("47.37,8.55", r.place)
        assertEquals(now, r.fetchedAt)
        assertNull(r.failure)
        assertEquals(0, r.misses)
        assertEquals(18.1, r.current!!.temp!!, 1e-9)
        assertEquals(24, r.hours.size)
        assertEquals(1, Http.sent(Host.OPEN_METEO))
        assertEquals(0, Http.sent(Host.OPEN_METEO_GEOCODING))
        assertEquals(0, Http.sent(Host.AIRLABS))
    }

    @Test fun theLastReadingIsKeptOnTheDeviceSoARestartShowsItWithItsTime() = FakeHttp.use { net ->
        net.reply(Host.OPEN_METEO, "/v1/forecast", forecast)
        val r = load()!!
        assertEquals(setOf(Kept.safe("47.37,8.55")), Kept.fetched(OPEN_METEO).names())
        // Twenty minutes later, in a new process: what was kept, and how old it is by the wall clock.
        val back = WeatherLoad.kept(zurich, now + 20 * min)!!
        assertEquals(r, back.value)
        assertEquals(20 * min, back.ageMs)
        assertEquals(now, back.value.fetchedAt)
        assertEquals(1, net.asked.size) // reading it back asked nobody
        // Nothing is kept for a place that was never read.
        assertNull(WeatherLoad.kept(oslo, now))
    }

    @Test fun whatIsKeptIsTheAppsOwnModelNeverTheReplyAsItCame() = FakeHttp.use { net ->
        net.reply(Host.OPEN_METEO, "/v1/forecast", forecast)
        val r = load()!!
        val text = Kept.fetched(OPEN_METEO).read("47.37,8.55")!!.text
        assertEquals(r, WeatherRules.kept(text))
        for (ofTheReply in listOf("generationtime_ms", "temperature_2m", "elevation", "utc_offset_seconds", "timezone_abbreviation"))
            assertFalse("$ofTheReply was kept", text.contains(ofTheReply))
    }

    @Test fun aClockSetBackMakesAKeptReadingNoOlderThanNew() = FakeHttp.use { net ->
        net.reply(Host.OPEN_METEO, "/v1/forecast", forecast)
        load()
        assertEquals(0L, WeatherLoad.kept(zurich, now - 5 * min)!!.ageMs)
    }

    @Test fun whatCannotBeReadBackIsRemoved() {
        val kept = Kept.fetched(OPEN_METEO)
        kept.write("47.37,8.55", "not a reading", now)
        assertNull(WeatherLoad.kept(zurich, now))
        assertEquals(emptySet<String>(), kept.names())
        // Another place's reading under this place's name is not this place's reading.
        kept.write("47.37,8.55", WeatherRules.keep(Reading(place = "59.91,10.75", fetchedAt = now, current = Current(now / 1000, 3.0))), now)
        assertNull(WeatherLoad.kept(zurich, now))
        assertEquals(emptySet<String>(), kept.names())
    }

    // ---- deleted with the item ------------------------------------------------------------------

    @Test fun aPlaceTheLayoutNoLongerHasIsNotKept() = FakeHttp.use { net ->
        net.reply(Host.OPEN_METEO, "/v1/forecast", forecast)
        // The item was deleted, or its city changed, while the answer was on its way.
        assertNotNull(load(zurich, layout = setOf(oslo)))
        assertEquals(emptySet<String>(), Kept.fetched(OPEN_METEO).names())
    }

    @Test fun everyLoadClearsOutWhatBelongsToNoItemAnyMore() = FakeHttp.use { net ->
        net.reply(Host.OPEN_METEO, "/v1/forecast", forecast)
        load(zurich, layout = setOf(zurich, oslo))
        load(oslo, layout = setOf(zurich, oslo))
        assertEquals(setOf(Kept.safe(zurich.key), Kept.safe(oslo.key)), Kept.fetched(OPEN_METEO).names())
        load(oslo, layout = setOf(oslo)) // Zurich's item is gone
        assertEquals(setOf(Kept.safe(oslo.key)), Kept.fetched(OPEN_METEO).names())
        WeatherLoad.tidy(emptySet()) // the last Weather item is gone
        assertEquals(emptySet<String>(), Kept.fetched(OPEN_METEO).names())
    }

    @Test fun thePlacesOfALayoutAreItsWeatherItemsCities() {
        val items = listOf(
            ItemConfig("a", "weather", options = mapOf("lat" to "47.37", "lon" to "8.55", "label" to "ZRH")),
            ItemConfig("b", "weather", options = mapOf("lat" to "47.37", "lon" to "8.55")),
            ItemConfig("c", "weather"),
            ItemConfig("d", "weather", section = Section.HIDDEN, options = mapOf("lat" to "59.91", "lon" to "10.75")),
            ItemConfig("e", "clock", options = mapOf("lat" to "1", "lon" to "2")),
        )
        assertEquals(setOf(zurich, oslo), WeatherLoad.places(items))
    }

    @Test fun aRemovedItemHasNoPlaceToKeepAReadingFor() {
        val gone = ItemConfig("d", "weather", options = mapOf("lat" to "59.91", "lon" to "10.75"))
        val shown = ItemConfig("a", "weather", options = mapOf("lat" to "47.37", "lon" to "8.55"))
        assertEquals(setOf(zurich, oslo), WeatherLoad.places(listOf(gone, shown)))
        assertEquals(setOf(zurich), WeatherLoad.places(listOf(shown)))
        // Removed, but another item shows the same city: the reading is that item's.
        assertEquals(setOf(oslo), WeatherLoad.places(listOf(gone.copy(id = "e", section = Section.HIDDEN))))
    }

    @Test fun anAnswerThatArrivesAfterItsItemWasRemovedIsNotKept() = FakeHttp.use { net ->
        net.reply(Host.OPEN_METEO, "/v1/forecast", forecast)
        assertNotNull(WeatherLoad.load(zurich, null, now) { WeatherLoad.places(emptyList()) })
        assertEquals(emptySet<String>(), Kept.fetched(OPEN_METEO).names())
    }

    @Test fun switchedOffNothingIsKeptAndAnAnswerOnItsWayIsNotKeptEither() {
        FakeHttp.use { net ->
            net.reply(Host.OPEN_METEO, "/v1/forecast", forecast)
            load()
            assertEquals(1, Kept.fetched(OPEN_METEO).names().size)
            Online.turnOff(OPEN_METEO)
            assertEquals(emptySet<String>(), Kept.fetched(OPEN_METEO).names())
            assertNull(WeatherLoad.kept(zurich, now))
        }
        // The switch goes off while the service is answering: the answer finds nowhere to stay.
        Online.turnOn(OPEN_METEO)
        FakeHttp.use {
            Http.transport = Transport { Online.turnOff(OPEN_METEO); Reply.Ok(forecast) }
            load()
            assertEquals(emptySet<String>(), Kept.fetched(OPEN_METEO).names())
        }
    }

    // ---- every way a request can fail ------------------------------------------------------------

    @Test fun withoutANetworkNothingGoesOutAndThatIsNoConnection() = FakeHttp.use { net ->
        Http.connected = { false }
        val r = load()!!
        assertEquals(Failure.OFFLINE, r.failure)
        assertEquals(0, r.misses) // nothing went out, so there is nothing to step back from
        assertNull(r.current)
        assertEquals(0L, r.fetchedAt)
        assertEquals(emptyList<Any>(), net.asked)
        assertEquals(0, Http.sent(Host.OPEN_METEO))
        assertEquals(1 * min, WeatherRules.every(r))
    }

    @Test fun aNetworkThatDoesNotReachTheServiceIsNoConnectionTooButEachTryCounts() = FakeHttp.use { net ->
        // No route, a name that can't be found, a public network's sign-in page with its own certificate.
        net.fail(Host.OPEN_METEO, "/v1/forecast", Why.OFFLINE)
        var r: Reading? = null
        val waits = (1..6).map { n ->
            r = load(last = r)
            assertEquals(Failure.OFFLINE, r!!.failure)
            assertEquals(n, r!!.misses)
            WeatherRules.every(r!!) / min
        }
        assertEquals(listOf(1L, 2L, 4L, 8L, 15L, 15L), waits)
        assertEquals(6, Http.sent(Host.OPEN_METEO))
    }

    @Test fun anErrorATimeoutOrTooMuchIsNoAnswer() {
        val ways: List<(FakeHttp) -> Unit> = listOf(
            { it.fail(Host.OPEN_METEO, "/v1/forecast", Why.STATUS, 400) },      // the service's own error
            { it.fail(Host.OPEN_METEO, "/v1/forecast", Why.STATUS, 500) },
            { it.fail(Host.OPEN_METEO, "/v1/forecast", Why.STATUS, 302) },      // a sign-in page's redirect, never followed
            { it.fail(Host.OPEN_METEO, "/v1/forecast", Why.TIMEOUT) },
            { it.fail(Host.OPEN_METEO, "/v1/forecast", Why.TOO_LARGE, 200) },
            { it.fail(Host.OPEN_METEO, "/v1/forecast", Why.UNREADABLE) },
            { it.reply(Host.OPEN_METEO, "/v1/forecast", "<html><body>Sign in to this network</body></html>") },
            { it.reply(Host.OPEN_METEO, "/v1/forecast", file("error_400.json")) },
            { it.reply(Host.OPEN_METEO, "/v1/forecast", forecast.take(900)) },
            { it.reply(Host.OPEN_METEO, "/v1/forecast", "") },
            { },                                                                 // no such path: the fake's "not found"
        )
        for ((i, way) in ways.withIndex()) FakeHttp.use { net ->
            way(net)
            val r = load()!!
            assertEquals("way $i", Failure.NO_ANSWER, r.failure)
            assertEquals(1, r.misses)
            assertEquals(15 * min, WeatherRules.every(r))
            assertEquals(1, net.asked.size)
        }
    }

    @Test fun toldToSlowDownItWaitsAnHourOrAsLongAsTheServiceSays() = FakeHttp.use { net ->
        // The fake gives its answers in the order they were prepared.
        net.fail(Host.OPEN_METEO, "/v1/forecast", Why.STATUS, 429)
        net.fail(Host.OPEN_METEO, "/v1/forecast", Why.STATUS, 429, retryAfterSec = 7200)
        net.fail(Host.OPEN_METEO, "/v1/forecast", Why.STATUS, 429, retryAfterSec = 30)
        net.fail(Host.OPEN_METEO, "/v1/forecast", Why.STATUS, 503, retryAfterSec = 3600)
        net.fail(Host.OPEN_METEO, "/v1/forecast", Why.STATUS, 503, retryAfterSec = 60)
        val plain = load()!!
        assertEquals(Failure.SLOW_DOWN, plain.failure)
        assertEquals(60 * min, WeatherRules.every(plain))
        Http.resetCounts() // Each answer here is the service's own: the wait Http holds for the service is another matter (HttpTest).
        assertEquals(120 * min, WeatherRules.every(load()!!))
        Http.resetCounts()
        assertEquals(60 * min, WeatherRules.every(load()!!))
        Http.resetCounts()
        // Any answer that asks for more patience than a quarter of an hour is "slow down", so the words and the wait agree.
        val busy = load()!!
        assertEquals(Failure.SLOW_DOWN, busy.failure)
        assertEquals(60 * min, WeatherRules.every(busy))
        Http.resetCounts()
        assertEquals(Failure.NO_ANSWER, load()!!.failure)
    }

    @Test fun aFailureKeepsTheNumbersThatWereThereAndSaysWhatWentWrong() = FakeHttp.use { net ->
        net.reply(Host.OPEN_METEO, "/v1/forecast", forecast)
        net.fail(Host.OPEN_METEO, "/v1/forecast", Why.STATUS, 500)
        net.reply(Host.OPEN_METEO, "/v1/forecast", forecast)
        val good = load()!!
        val keptText = Kept.fetched(OPEN_METEO).read(zurich.key)!!.text
        val failed = load(last = good, at = now + 30 * min)!!
        assertEquals(good.copy(failure = Failure.NO_ANSWER, misses = 1), failed)
        assertEquals(now, failed.fetchedAt) // as old as it was: three hours after this moment the number goes
        // What is kept stays the good reading as it was written: a failed try writes nothing.
        assertEquals(keptText, Kept.fetched(OPEN_METEO).read(zurich.key)!!.text)
        assertFalse(keptText.contains("NO_ANSWER"))
        assertEquals(good, WeatherLoad.kept(zurich, now + 31 * min)!!.value)
        // And the next good answer clears it all.
        val again = load(last = failed, at = now + 45 * min)!!
        assertNull(again.failure)
        assertEquals(0, again.misses)
        assertEquals(now + 45 * min, again.fetchedAt)
    }

    @Test fun aFailureWithNothingReadBeforeIsAnEmptyReadingForThisPlace() = FakeHttp.use { net ->
        net.fail(Host.OPEN_METEO, "/v1/forecast", Why.TIMEOUT)
        assertEquals(Reading(place = "47.37,8.55", failure = Failure.NO_ANSWER, misses = 1), load())
        // Numbers of another place are never passed on as this one's.
        val other = Reading(place = "59.91,10.75", fetchedAt = now, current = Current(now / 1000, 3.0))
        assertEquals(Reading(place = "47.37,8.55", failure = Failure.NO_ANSWER, misses = 1), load(last = other))
        // Only what the service sent is ever kept: a failure leaves nothing on the device.
        assertEquals(emptySet<String>(), Kept.fetched(OPEN_METEO).names())
    }

    @Test fun notAskedAfterAllIsNothingToSay() = FakeHttp.use { net ->
        // The switch went off or the bar hid under the load: neither an answer nor a failure.
        Http.allowed = { false }
        assertNull(load())
        assertNull(load(last = Reading(place = "47.37,8.55", fetchedAt = now, current = Current(now / 1000, 3.0))))
        assertEquals(emptyList<Any>(), net.asked)
        assertEquals(0, Http.sent(Host.OPEN_METEO))
    }

    // ---- the search ----------------------------------------------------------------------------

    @Test fun aSearchListsThePlacesTheServiceFound() = FakeHttp.use { net ->
        net.reply(Host.OPEN_METEO_GEOCODING, "/v1/search", file("search_springfield.json"))
        val found = WeatherLoad.search("Springfield") as Found.Places
        assertEquals(5, found.list.size)
        assertEquals("Springfield", net.query("name"))
        assertEquals(listOf(Host.OPEN_METEO_GEOCODING), net.asked.map { it.host })
        assertEquals(1, Http.sent(Host.OPEN_METEO_GEOCODING))
        assertEquals(0, Http.sent(Host.OPEN_METEO))
    }

    @Test fun aNameNobodyKnowsIsAnEmptyList() = FakeHttp.use { net ->
        net.reply(Host.OPEN_METEO_GEOCODING, "/v1/search", file("search_none.json"))
        assertEquals(emptyList<City>(), (WeatherLoad.search("Zzzzqq") as Found.Places).list)
    }

    @Test fun aSearchThatFailsSaysHow() {
        FakeHttp.use { net ->
            Http.connected = { false }
            assertEquals(Found.Offline, WeatherLoad.search("Springfield"))
            assertEquals(emptyList<Any>(), net.asked)
        }
        FakeHttp.use { net ->
            net.fail(Host.OPEN_METEO_GEOCODING, "/v1/search", Why.OFFLINE)
            assertEquals(Found.Offline, WeatherLoad.search("Springfield"))
        }
        val ways: List<(FakeHttp) -> Unit> = listOf(
            { it.fail(Host.OPEN_METEO_GEOCODING, "/v1/search", Why.STATUS, 500) },
            { it.fail(Host.OPEN_METEO_GEOCODING, "/v1/search", Why.STATUS, 429) },
            { it.fail(Host.OPEN_METEO_GEOCODING, "/v1/search", Why.TIMEOUT) },
            { it.reply(Host.OPEN_METEO_GEOCODING, "/v1/search", "<html>Sign in</html>") },
            { it.reply(Host.OPEN_METEO_GEOCODING, "/v1/search", file("error_400.json")) },
        )
        for (way in ways) FakeHttp.use { net -> way(net); assertEquals(Found.NoAnswer, WeatherLoad.search("Springfield")) }
        // Not asked at all is an answer of its own kind, and nothing is shown for it.
        FakeHttp.use { net ->
            Http.allowed = { false }
            assertEquals(Found.Unasked, WeatherLoad.search("Springfield"))
            assertEquals(emptyList<Any>(), net.asked)
        }
    }
}
