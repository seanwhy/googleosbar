package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.data.Kept
import com.meridian11.books.bentobar.data.Online
import com.meridian11.books.bentobar.data.Online.Service.OPEN_METEO
import com.meridian11.books.bentobar.data.Section
import com.meridian11.books.bentobar.net.FakeHttp
import com.meridian11.books.bentobar.net.Host
import com.meridian11.books.bentobar.net.Http
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
import java.util.concurrent.Executor

/**
 * The promise the Weather item makes, as tests: nothing is sent before the user sets the item up,
 * and after that only what the product spec says, as often as it says. The item's whole way to the
 * network ([WeatherSource]) runs here as it does in the app, on a clock the test moves, against a
 * fake that counts every request.
 */
class WeatherSourceTest {
    private val dir = File(System.getProperty("java.io.tmpdir"), "bentobar-weather-source-${System.nanoTime()}")
    private val sec = 1_000L
    private val min = 60 * sec
    private fun file(name: String) = File("src/test/resources/openmeteo/$name").readText()

    /** Time since boot (ages, intervals) and the wall clock (what a reading says it was read at) move together. */
    private var elapsed = 5_000_000L
    private var wall = 1791192325_000L
    private fun pass(ms: Long) { elapsed += ms; wall += ms }

    /** Something shows items: the bar, a menu, the settings preview. */
    private var shown = true
    private var layout = emptyList<ItemConfig>()
    private var changes = 0
    private lateinit var readings: Refresher<Place, Reading>
    private lateinit var asking: Ask<Query, Found>

    /**
     * As the app wires it, but on this thread and this clock: nothing loads while nothing shows items.
     * The app's wiring also refuses while the service is switched off, and so does the request helper
     * (which the fake leaves wide open). Here neither does, on purpose: what keeps a request from
     * going out in these tests is the item's own rule and nothing behind it.
     */
    /** Where the device is, for My location, as Android says it (rounded before the source sees it, as [WeatherHere.place] does), and why not while that isn't known. */
    private var here: Fix? = null
    private var locating = Locate.FINDING

    /** Loads wait here until [runHeld] while [held]; otherwise they run at once, on this thread. */
    private val waiting = ArrayDeque<Runnable>()
    private var held = false
    private fun runHeld() { held = false; while (waiting.isNotEmpty()) waiting.removeFirst().run() }

    private fun source(staged: () -> Failure? = { null }): WeatherSource {
        // The service's wait counts by the same clock as the loader here, or an hour on the test's clock wouldn't pass for it.
        Http.clock = { elapsed }
        val wiring = Refresher.Wiring(Executor { r -> if (held) waiting.addLast(r) else r.run() }, { it.run() }, { elapsed }, { changes++ }, minGapMs = 10_000, afterThrowMs = 60_000,
            mayLoad = { shown })
        return WeatherSource.make(
            refresher = { every, restore, load -> Refresher(wiring, every, restore, load).also { readings = it } },
            ask = { work -> Ask(wiring, work).also { asking = it } },
            background = Executor { backgroundRuns++; it.run() }, wall = { wall }, layout = { layout }, staged = staged, up = { elapsed },
            here = { here?.let { WeatherRules.nearby(it.lat, it.lon) } }, locating = { locating })
    }

    /** How often something was handed to the background thread that tidies the device. */
    private var backgroundRuns = 0

    /** What the app does when the switch goes off (`Env.wireOnline`): the loader and the search forget. */
    private fun switchOff() { Online.turnOff(OPEN_METEO); readings.forget(); asking.clear() }

    @Before fun fresh() { Online.init(dir) } // nothing is on: a fresh install
    @After fun gone() { dir.deleteRecursively(); Online.init(File(dir, "empty")) }

    /** A Weather item as Add makes it: no options at all. */
    private val added = ItemConfig("w1", "weather")
    private fun zurich(id: String = "w1", section: Section = Section.SHOWN) =
        ItemConfig(id, "weather", section, options = mapOf("city" to "Zurich", "lat" to "47.37", "lon" to "8.55", "zone" to "Europe/Zurich"))
    private fun oslo(id: String = "w2") = ItemConfig(id, "weather", options = mapOf("city" to "Oslo", "lat" to "59.91", "lon" to "10.75"))

    private fun FakeHttp.forecasts() = reply(Host.OPEN_METEO, "/v1/forecast", file("forecast_zurich.json"))
    private fun FakeHttp.places() = reply(Host.OPEN_METEO_GEOCODING, "/v1/search", file("search_springfield.json"))
    private fun sentNothing(net: FakeHttp) {
        assertEquals(emptyList<Any>(), net.asked)
        for (host in Host.entries) assertEquals(host.domain, 0, Http.sent(host))
    }

    /** The bar draws an item: its state is asked every ten seconds while it shows. */
    private fun WeatherSource.watch(item: ItemConfig, forMs: Long) {
        var left = forMs
        while (left > 0) { pass(10 * sec); left -= 10 * sec; reading(item) }
    }

    // ---- nothing before the user sets it up -------------------------------------------------------

    @Test fun addingTheItemAndOpeningItsMenuSendsNothing() = FakeHttp.use { net ->
        net.forecasts(); net.places()
        val s = source()
        layout = listOf(added)
        // In the bar for an hour, its menu opened, Refresh tried: an item without a city asks nobody.
        assertNull(s.reading(added))
        s.watch(added, 60 * min)
        s.opened(added)
        assertFalse(s.again(added))
        assertEquals(Again.WAIT, s.againEntry(added))
        sentNothing(net)
        assertFalse(Online.on(OPEN_METEO))
        assertFalse(Online.setUp(OPEN_METEO))
        assertEquals(Status.NotSetUp, s.status(added, wall))
        assertFalse(File(dir, "fetched").exists())
    }

    @Test fun typingInTheCityFieldSendsNothing() = FakeHttp.use { net ->
        net.places()
        val s = source()
        layout = listOf(added)
        for (text in listOf("S", "Sp", "Spr", "Springfiel", "Springfield")) s.typed("menu:w1")
        sentNothing(net)
        assertFalse(Online.on(OPEN_METEO))
        assertEquals(Ask.State.Idle, s.searching.value)
    }

    @Test fun onlySearchSendsTheNameAndTurnsTheSwitchOn() = FakeHttp.use { net ->
        net.places(); net.forecasts()
        val s = source()
        layout = listOf(added)
        assertTrue(s.search("Springfield", added, "menu:w1"))
        assertTrue(Online.on(OPEN_METEO))
        assertTrue(Online.setUp(OPEN_METEO))
        // One request, to the search host, with the name and nothing of the user.
        val request = net.asked.single()
        assertEquals(Host.OPEN_METEO_GEOCODING, request.host)
        assertEquals("/v1/search", request.path)
        assertEquals(listOf("name" to "Springfield", "count" to "5", "format" to "json"), request.query)
        assertEquals(1, Http.sent(Host.OPEN_METEO_GEOCODING))
        assertEquals(0, Http.sent(Host.OPEN_METEO))
        assertEquals(0, Http.sent(Host.AIRLABS))
        val done = s.searching.value as Ask.State.Done
        assertEquals(5, (done.answer as Found.Places).list.size)
        // The item still has no city, so no forecast is asked for until one is picked.
        s.watch(added, 10 * min)
        assertEquals(1, net.asked.size)
    }

    @Test fun oneRequestForEachPressOfSearchNeverOneForEachLetter() = FakeHttp.use { net ->
        net.places()
        val s = source()
        layout = listOf(added)
        for (text in listOf("Sp", "Spr", "Springf")) s.typed("menu:w1")
        s.search("Springfield", added, "menu:w1")
        assertEquals(1, net.asked.size)
        s.typed("menu:w1")
        assertEquals(1, net.asked.size)
        s.search("Springfield", added, "menu:w1")
        assertEquals(2, net.asked.size)
        assertEquals(2, Http.sent(Host.OPEN_METEO_GEOCODING))
    }

    @Test fun fewerThanTwoLettersSendNothingAndTurnNothingOn() = FakeHttp.use { net ->
        net.places()
        val s = source()
        layout = listOf(added)
        for (text in listOf("", " ", "S", " S \n")) assertFalse(text, s.search(text, added, "menu:w1"))
        sentNothing(net)
        assertFalse(Online.on(OPEN_METEO))
        assertFalse(Online.setUp(OPEN_METEO))
    }

    @Test fun whatIsSentIsTheTextCleanedUpAndNothingElseOfTheField() = FakeHttp.use { net ->
        net.places()
        val s = source()
        layout = listOf(added)
        assertTrue(s.search("  New\nYork ", added, "menu:w1"))
        assertEquals("New York", net.query("name"))
    }

    @Test fun pickingAPlaceBringsItsForecastWithOneRequest() = FakeHttp.use { net ->
        net.places(); net.forecasts()
        val s = source()
        layout = listOf(added)
        s.search("Springfield", added, "menu:w1")
        val illinois = ((s.searching.value as Ask.State.Done).answer as Found.Places).list[1]
        val item = WeatherRules.picked(added, illinois, "Illinois, United States")
        layout = listOf(item)
        assertNotNull(s.reading(item))
        assertEquals(2, net.asked.size)
        val forecast = net.asked.last()
        assertEquals(Host.OPEN_METEO, forecast.host)
        assertEquals("/v1/forecast", forecast.path)
        // The coordinates as they are stored: two decimals, about a kilometer.
        assertEquals("39.80", net.query("latitude"))
        assertEquals("-89.64", net.query("longitude"))
        assertTrue(s.status(item, wall) is Status.Live)
    }

    // ---- My location ---------------------------------------------------------------------------------

    @Test fun myLocationAsksNothingUntilTheDeviceKnowsWhereItIs() = FakeHttp.use { net ->
        net.forecasts()
        val s = source()
        val item = WeatherRules.useHere(added)
        layout = listOf(item)
        s.turnOn()
        // Android hasn't answered yet: loading, and nothing goes out.
        assertNull(s.reading(item))
        s.watch(item, 10 * min)
        s.opened(item)
        assertFalse(s.again(item))
        assertEquals(Status.Loading, s.status(item, wall))
        // Refused, switched off, or no location at all: each a state of its own, and still nothing goes out.
        for (why in listOf(Locate.NOT_ALLOWED, Locate.OFF, Locate.NONE)) {
            locating = why
            s.watch(item, 10 * min)
            assertEquals(Status.NoLocation(why), s.status(item, wall))
        }
        sentNothing(net)
    }

    @Test fun myLocationSendsWhereTheDeviceIsToAboutTenKilometersAndNothingElse() = FakeHttp.use { net ->
        net.forecasts()
        val s = source()
        val item = WeatherRules.useHere(added)
        layout = listOf(item)
        s.turnOn()
        // Android says where the device is to a few meters; the source sends it, and holds its reading, to one decimal.
        here = Fix(47.376_887, 8.541_694)
        assertNotNull(s.reading(item))
        assertEquals(1, net.asked.size)
        assertEquals("47.4", net.query("latitude"))
        assertEquals("8.5", net.query("longitude"))
        assertTrue(net.asked.single().query.none { (_, v) -> "47.37" in v || "8.54" in v })
        assertEquals("47.4,8.5", s.peek(item)!!.place)
        assertTrue(s.status(item, wall) is Status.Live)
        // The layout holds the choice, never the place.
        assertEquals(mapOf("where" to "here"), item.options)
        assertEquals(setOf(Place("47.4", "8.5")), WeatherLoad.places(layout, here?.let { WeatherRules.nearby(it.lat, it.lon) }))
    }

    @Test fun myLocationsReadingIsHeldInMemoryAndNeverWrittenToTheDevice() = FakeHttp.use { net ->
        net.forecasts()
        val s = source()
        val item = WeatherRules.useHere(added)
        layout = listOf(item)
        s.turnOn()
        here = Fix(47.4, 8.5)
        assertNotNull(s.reading(item))
        assertEquals(1, net.asked.size)
        assertTrue(s.status(item, wall) is Status.Live)
        // In memory for the bar and the menu; on the device, nothing, under no name.
        assertEquals(emptySet<String>(), Kept.fetched(OPEN_METEO).names())
        // Beside a city, only the city's reading is kept.
        val city = zurich("w2")
        layout = listOf(item, city)
        assertNotNull(s.reading(city))
        assertEquals(setOf(Kept.safe("47.37,8.55")), Kept.fetched(OPEN_METEO).names())
        // What an earlier version kept for where the device was: a restart doesn't read it back, but asks, and the
        // answer's keeping removes it; so does a tidy.
        fun planted() = Kept.fetched(OPEN_METEO).write("47.4,8.5", Kept.fetched(OPEN_METEO).read("47.37,8.55")!!.text.replace("47.37,8.55", "47.4,8.5"), wall)
        planted()
        val restarted = source()
        assertNull(restarted.peek(item))
        assertNotNull(restarted.reading(item))
        assertEquals(2, net.asked.count { r -> ("latitude" to "47.4") in r.query })
        assertEquals(setOf(Kept.safe("47.37,8.55")), Kept.fetched(OPEN_METEO).names())
        planted()
        restarted.keepOnly(WeatherLoad.places(layout, here?.let { WeatherRules.nearby(it.lat, it.lon) }), cities = WeatherLoad.places(layout))
        assertEquals(setOf(Kept.safe("47.37,8.55")), Kept.fetched(OPEN_METEO).names())
    }

    @Test fun myLocationsFirstReadingStillOnItsWayWhenTheItemGoesIsNotHeld() = FakeHttp.use { net ->
        net.forecasts()
        val s = source()
        val item = WeatherRules.useHere(added)
        layout = listOf(item)
        s.turnOn()
        here = Fix(47.4, 8.5)
        held = true
        s.reading(item)
        // The item goes (deleted, Off, or back to its city) before the answer: the place is forgotten, and the tick tidies.
        layout = emptyList()
        here = null
        s.keepOnly(WeatherLoad.places(layout, here?.let { WeatherRules.nearby(it.lat, it.lon) }), cities = WeatherLoad.places(layout))
        runHeld()
        assertEquals(1, net.asked.size)
        // The answer came after: it is held under no place, so an item of My location added later finds nothing from before.
        layout = listOf(item)
        here = Fix(47.4, 8.5)
        assertNull(s.peek(item))
    }

    @Test fun myLocationFromAPastedLayoutSendsNothingUntilTurnOnWeather() = FakeHttp.use { net ->
        net.forecasts()
        val s = source()
        val item = WeatherRules.useHere(added)
        layout = listOf(item)
        here = Fix(47.4, 8.5)
        s.watch(item, 60 * min)
        sentNothing(net)
        assertEquals(Status.Off(everOn = false), s.status(item, wall))
    }

    @Test fun whenTheDeviceMovesTheNewPlaceIsAsked() = FakeHttp.use { net ->
        net.forecasts()
        val s = source()
        val item = WeatherRules.useHere(added)
        layout = listOf(item)
        s.turnOn()
        here = Fix(47.4, 8.5)
        s.watch(item, 5 * min)
        assertEquals(1, net.asked.size)
        // Moved within the same ten kilometers: the same place, nothing new is asked.
        here = Fix(47.41, 8.52)
        s.watch(item, 5 * min)
        assertEquals(1, net.asked.size)
        here = Fix(46.948, 7.447)
        s.watch(item, 1 * min)
        assertEquals(2, net.asked.size)
        assertEquals("46.9", net.query("latitude"))
        assertEquals("7.4", net.query("longitude"))
    }

    @Test fun aCityAndMyLocationSideBySide() = FakeHttp.use { net ->
        net.forecasts()
        val s = source()
        val mine = WeatherRules.useHere(oslo("w1"))
        val city = zurich("w2")
        layout = listOf(mine, city)
        s.turnOn()
        // The item of My location keeps its city for a way back, and asks nothing for it.
        s.watch(mine, 5 * min)
        assertEquals(Status.Loading, s.status(mine, wall))
        sentNothing(net)
        s.reading(city)
        assertEquals(1, net.asked.size)
        assertEquals("47.37", net.query("latitude"))
        // Back to its city.
        val back = WeatherRules.useCity(mine)
        layout = listOf(back, city)
        s.reading(back)
        assertEquals("59.91", net.query("latitude"))
    }

    @Test fun aPastedLayoutWithACitySendsNothingUntilTurnOnWeather() = FakeHttp.use { net ->
        net.forecasts(); net.places()
        val s = source()
        val item = zurich() // it came with the layout: this install never set Weather up
        layout = listOf(item)
        assertNull(s.reading(item))
        s.watch(item, 60 * min)
        s.opened(item)
        assertFalse(s.again(item))
        assertEquals(Again.WAIT, s.againEntry(item))
        sentNothing(net)
        assertFalse(Online.on(OPEN_METEO))
        assertEquals(Status.Off(everOn = false), s.status(item, wall))
        // "Turn on weather": the user's own act. From then on the item asks.
        s.turnOn()
        assertTrue(Online.on(OPEN_METEO))
        assertNotNull(s.reading(item))
        assertEquals(1, net.asked.size)
        assertEquals(Host.OPEN_METEO, net.asked.single().host)
        assertTrue(s.status(item, wall) is Status.Live)
    }

    @Test fun offInSetupRequestsStopAtOnceAndWhatWasFetchedIsGone() = FakeHttp.use { net ->
        net.forecasts()
        val s = source()
        val item = zurich()
        layout = listOf(item)
        s.turnOn()
        assertNotNull(s.reading(item))
        assertEquals(1, net.asked.size)
        assertEquals(1, Kept.fetched(OPEN_METEO).names().size)

        switchOff()
        assertNull(s.reading(item))
        s.watch(item, 3 * 60 * min)
        s.opened(item)
        assertFalse(s.again(item))
        assertEquals(1, net.asked.size)
        assertEquals(emptySet<String>(), Kept.fetched(OPEN_METEO).names())
        // It was on here before: the menu says "Off in Setup" and offers to turn it on.
        assertEquals(Status.Off(everOn = true), s.status(item, wall))

        s.turnOn()
        assertNotNull(s.reading(item))
        assertEquals(2, net.asked.size)
    }

    // ---- how often ------------------------------------------------------------------------------

    @Test fun oneRequestServesEveryItemWithTheSameCity() = FakeHttp.use { net ->
        net.forecasts()
        val s = source()
        val a = zurich("w1")
        val b = zurich("w2").with("label", "ZRH").with("show", "feels")
        layout = listOf(a, b)
        s.turnOn()
        assertNotNull(s.reading(a))
        assertNotNull(s.reading(b))
        repeat(30) { pass(10 * sec); s.reading(a); s.reading(b) }
        assertEquals(1, net.asked.size)
        // Another city is another request.
        val c = oslo()
        layout = listOf(a, b, c)
        s.reading(c)
        assertEquals(2, net.asked.size)
        assertEquals("59.91", net.query("latitude"))
    }

    @Test fun twoHoursOnScreenAreAtMostFiveForecastRequests() = FakeHttp.use { net ->
        net.forecasts()
        val s = source()
        val item = zurich()
        layout = listOf(item)
        s.turnOn()
        s.reading(item)
        assertEquals(1, net.asked.size)
        s.watch(item, 29 * min)
        assertEquals(1, net.asked.size) // a reading is fresh for half an hour
        s.watch(item, 1 * min)
        assertEquals(2, net.asked.size)
        s.watch(item, 90 * min)
        assertEquals(5, net.asked.size) // at 0, 30, 60, 90 and 120 minutes
        assertEquals(5, Http.sent(Host.OPEN_METEO))
        // A whole day on screen stays near the fifty the product names.
        s.watch(item, 22 * 60 * min)
        assertEquals(49, net.asked.size)
    }

    @Test fun whileTheBarIsHiddenNothingIsAskedAndWhenItReturnsWhatIsDueIsAskedOnce() = FakeHttp.use { net ->
        net.forecasts()
        val s = source()
        val item = zurich()
        layout = listOf(item)
        s.turnOn()
        s.reading(item)
        // Hidden for twenty minutes (a full-screen video): the reading is still fresh when the bar returns.
        shown = false
        s.watch(item, 20 * min) // even if a state is computed meanwhile
        shown = true
        s.watch(item, 1 * min)
        assertEquals(1, net.asked.size)
        // Hidden for five hours (the lid closed): one request when it returns, not ten.
        shown = false
        pass(5 * 60 * min)
        s.reading(item)
        assertEquals(1, net.asked.size)
        shown = true
        s.reading(item)
        assertEquals(2, net.asked.size)
        s.watch(item, 29 * min)
        assertEquals(2, net.asked.size)
    }

    @Test fun openingTheMenuAsksAgainOnlyIfTheReadingIsOlderThanTenMinutes() = FakeHttp.use { net ->
        net.forecasts()
        val s = source()
        val item = zurich()
        layout = listOf(item)
        s.turnOn()
        s.reading(item)
        pass(9 * min + 59 * sec)
        s.opened(item)
        assertEquals(1, net.asked.size)
        pass(1 * sec)
        s.opened(item)
        assertEquals(2, net.asked.size)
        s.opened(item); s.opened(item)
        assertEquals(2, net.asked.size)
        // Opening the menu of an item that has nothing yet asks for it, once.
        val other = oslo()
        layout = listOf(item, other)
        s.opened(other); s.opened(other)
        assertEquals(3, net.asked.size)
    }

    @Test fun openingTheMenuWithoutANetworkSaysSoAtOnceAndSendsNothing() = FakeHttp.use { net ->
        net.forecasts()
        val s = source()
        val item = zurich()
        layout = listOf(item)
        s.turnOn()
        s.reading(item)
        // Airplane mode, two minutes after a good reading: the menu's note should not go on saying "updated" as if all were well.
        pass(2 * min)
        Http.connected = { false }
        s.opened(item)
        val notLive = s.status(item, wall) as Status.Live
        assertEquals(Failure.OFFLINE, notLive.reading.failure)
        assertEquals(18.1, notLive.reading.current!!.temp!!, 1e-9) // the bar keeps its reading
        assertEquals(1, net.asked.size)
        assertEquals(1, Http.sent(Host.OPEN_METEO))
        s.opened(item); s.opened(item)
        assertEquals(1, net.asked.size)
        // Back online, the next check brings a live reading again.
        Http.connected = { true }
        s.watch(item, 1 * min)
        assertEquals(2, net.asked.size)
        assertNull((s.status(item, wall) as Status.Live).reading.failure)
    }

    @Test fun refreshIsOnceAMinuteAtMost() = FakeHttp.use { net ->
        net.forecasts()
        val s = source()
        val item = zurich()
        layout = listOf(item)
        s.turnOn()
        s.reading(item)
        // Dimmed for sixty seconds after an answer, the automatic one too, and the entry says why.
        assertEquals(Again.UP_TO_DATE, s.againEntry(item))
        assertFalse(s.again(item))
        pass(59 * sec)
        assertEquals(Again.UP_TO_DATE, s.againEntry(item))
        assertFalse(s.again(item))
        assertEquals(1, net.asked.size)
        pass(1 * sec)
        assertEquals(Again.READY, s.againEntry(item))
        assertTrue(s.again(item))
        assertEquals(2, net.asked.size)
        assertEquals(Again.UP_TO_DATE, s.againEntry(item))
        repeat(20) { s.again(item) }
        assertEquals(2, net.asked.size)
    }

    // ---- after an error -------------------------------------------------------------------------

    @Test fun afterNoAnswerThereIsOneRetryAfterFifteenMinutes() = FakeHttp.use { net ->
        net.fail(Host.OPEN_METEO, "/v1/forecast", Why.STATUS, 500)
        net.forecasts()
        val s = source()
        val item = zurich()
        layout = listOf(item)
        s.turnOn()
        assertEquals(Failure.NO_ANSWER, s.reading(item)!!.failure)
        assertEquals(Status.Missing(Failure.NO_ANSWER), s.status(item, wall))
        s.watch(item, 14 * min + 50 * sec)
        // Opening the menu doesn't hurry it: the words say fifteen minutes.
        s.opened(item)
        assertEquals(1, net.asked.size)
        s.watch(item, 10 * sec)
        assertEquals(2, net.asked.size)
        assertTrue(s.status(item, wall) is Status.Live)
        s.watch(item, 29 * min)
        assertEquals(2, net.asked.size)
    }

    @Test fun tryAgainMayBePressedTenSecondsAfterATryThatGotNoAnswer() = FakeHttp.use { net ->
        net.fail(Host.OPEN_METEO, "/v1/forecast", Why.STATUS, 500)
        net.fail(Host.OPEN_METEO, "/v1/forecast", Why.TIMEOUT)
        net.forecasts()
        val s = source()
        val item = zurich()
        layout = listOf(item)
        s.turnOn()
        s.reading(item)
        // The menu says "No answer", and its one retry is not kept from the user for a minute.
        assertEquals(Status.Missing(Failure.NO_ANSWER), s.status(item, wall))
        assertEquals(Again.WAIT, s.againEntry(item))
        assertFalse(s.again(item))
        pass(9 * sec)
        assertEquals(Again.WAIT, s.againEntry(item))
        assertFalse(s.again(item))
        assertEquals(1, net.asked.size)
        pass(1 * sec)
        assertEquals(Again.READY, s.againEntry(item))
        assertTrue(s.again(item))
        assertEquals(2, net.asked.size)
        // That one failed too: ten seconds again, counted from when it came back.
        assertEquals(Failure.NO_ANSWER, s.reading(item)!!.failure)
        assertEquals(Again.WAIT, s.againEntry(item))
        pass(9 * sec)
        assertFalse(s.again(item))
        pass(1 * sec)
        assertTrue(s.again(item))
        assertNull(s.reading(item)!!.failure)
        assertEquals(3, net.asked.size)
        // Now there is an answer: a minute, and the entry says that there is nothing newer.
        assertEquals(Again.UP_TO_DATE, s.againEntry(item))
        pass(59 * sec)
        assertFalse(s.again(item))
        pass(1 * sec)
        assertTrue(s.again(item))
        assertEquals(4, net.asked.size)
    }

    @Test fun whereTheServiceNamedAWaitTryAgainIsNotThereSooner() = FakeHttp.use { net ->
        net.fail(Host.OPEN_METEO, "/v1/forecast", Why.STATUS, 429, retryAfterSec = 300)
        net.fail(Host.OPEN_METEO, "/v1/forecast", Why.STATUS, 429)
        net.forecasts()
        val s = source()
        val item = zurich()
        layout = listOf(item)
        s.turnOn()
        s.reading(item)
        assertEquals(Status.Missing(Failure.SLOW_DOWN), s.status(item, wall))
        // "Slow down, five minutes": no press reaches the service before they have passed.
        repeat(29) {
            pass(10 * sec)
            assertEquals(Again.WAIT, s.againEntry(item))
            assertFalse(s.again(item))
        }
        assertEquals(1, net.asked.size)
        pass(10 * sec)
        assertEquals(Again.READY, s.againEntry(item))
        assertTrue(s.again(item))
        assertEquals(2, net.asked.size)
        // Told to slow down again, this time with no time named: no answer like any other, so ten seconds.
        assertEquals(Status.Missing(Failure.SLOW_DOWN), s.status(item, wall))
        pass(9 * sec)
        assertFalse(s.again(item))
        pass(1 * sec)
        assertTrue(s.again(item))
        assertEquals(3, net.asked.size)
        assertTrue(s.status(item, wall) is Status.Live)
    }

    @Test fun toldToSlowDownItLeavesTheServiceAloneForAnHour() = FakeHttp.use { net ->
        net.fail(Host.OPEN_METEO, "/v1/forecast", Why.STATUS, 429)
        net.forecasts()
        val s = source()
        val item = zurich()
        layout = listOf(item)
        s.turnOn()
        assertEquals(Status.Missing(Failure.SLOW_DOWN), s.reading(item).let { s.status(item, wall) })
        s.watch(item, 59 * min + 50 * sec)
        s.opened(item)
        assertEquals(1, net.asked.size)
        s.watch(item, 10 * sec)
        assertEquals(2, net.asked.size)
    }

    @Test fun withoutANetworkNothingGoesOutAndTheReadingStaysUntilItIsThreeHoursOld() = FakeHttp.use { net ->
        net.forecasts()
        val s = source()
        val item = zurich()
        layout = listOf(item)
        s.turnOn()
        s.reading(item)
        assertEquals(1, net.asked.size)
        Http.connected = { false } // airplane mode
        s.watch(item, 2 * 60 * min + 59 * min)
        assertEquals(1, net.asked.size)
        assertEquals(1, Http.sent(Host.OPEN_METEO))
        // The bar keeps its reading, and the menu's note will say "no connection".
        val notLive = s.status(item, wall) as Status.Live
        assertEquals(Failure.OFFLINE, notLive.reading.failure)
        assertEquals(18.1, notLive.reading.current!!.temp!!, 1e-9)
        // Three hours after it was read, the number goes.
        s.watch(item, 1 * min)
        assertEquals(Status.Missing(Failure.OFFLINE), s.status(item, wall))
        // Back online: the next check, within a minute, brings a reading.
        Http.connected = { true }
        s.watch(item, 1 * min)
        assertEquals(2, net.asked.size)
        assertNull((s.status(item, wall) as Status.Live).reading.failure)
    }

    @Test fun withoutANetworkTryAgainMayBePressedTenSecondsAfterATryThatReachedNobody() = FakeHttp.use { net ->
        net.forecasts()
        val s = source()
        val item = zurich()
        layout = listOf(item)
        s.turnOn()
        Http.connected = { false }
        assertEquals(Failure.OFFLINE, s.reading(item)!!.failure)
        assertEquals(Status.Missing(Failure.OFFLINE), s.status(item, wall))
        assertEquals(Again.WAIT, s.againEntry(item))
        assertFalse(s.again(item))
        pass(9 * sec)
        assertEquals(Again.WAIT, s.againEntry(item))
        pass(1 * sec)
        assertEquals(Again.READY, s.againEntry(item))
        assertTrue(s.again(item))
        // Still no network, and pressed again as soon as it may be: nothing goes out for any of it.
        assertFalse(s.again(item))
        pass(10 * sec)
        assertTrue(s.again(item))
        sentNothing(net)
        Http.connected = { true }
        pass(9 * sec)
        assertFalse(s.again(item))
        pass(1 * sec)
        assertTrue(s.again(item))
        assertEquals(1, net.asked.size)
        // That one was answered: now the minute holds.
        assertEquals(Again.UP_TO_DATE, s.againEntry(item))
        assertFalse(s.again(item))
    }

    @Test fun aReadingThatIsNotLiveMayBeRefreshedTenSecondsAfterTheTryThatFailed() = FakeHttp.use { net ->
        net.forecasts()
        net.fail(Host.OPEN_METEO, "/v1/forecast", Why.STATUS, 503)
        net.forecasts()
        val s = source()
        val item = zurich()
        layout = listOf(item)
        s.turnOn()
        s.reading(item)
        pass(1 * min)
        assertTrue(s.again(item))
        // The numbers stay (the note says "no answer"), and Refresh is dimmed without claiming they are up to date.
        assertEquals(Failure.NO_ANSWER, (s.status(item, wall) as Status.Live).reading.failure)
        assertEquals(Again.WAIT, s.againEntry(item))
        pass(9 * sec)
        assertEquals(Again.WAIT, s.againEntry(item))
        pass(1 * sec)
        assertEquals(Again.READY, s.againEntry(item))
        assertTrue(s.again(item))
        assertEquals(3, net.asked.size)
        assertNull((s.status(item, wall) as Status.Live).reading.failure)
    }

    @Test fun anItemThatMayAskNothingHasNoRefreshToPressAndNothingToSayOfIt() = FakeHttp.use { net ->
        net.forecasts()
        val s = source()
        val item = zurich()
        layout = listOf(item)
        // Nothing yet, and the switch off: a layout that came with a city.
        assertEquals(Again.WAIT, s.againEntry(item))
        assertEquals(Again.WAIT, s.againEntry(added))
        s.turnOn()
        // On, and nothing known yet: a press asks.
        assertEquals(Again.READY, s.againEntry(item))
        s.reading(item)
        assertEquals(Again.UP_TO_DATE, s.againEntry(item))
        // The same reading seen with the switch off: not "up to date", nothing.
        Online.turnOff(OPEN_METEO)
        assertEquals(Again.WAIT, s.againEntry(item))
        assertEquals(1, net.asked.size)
    }

    // ---- two clocks ----------------------------------------------------------------------------------
    // "Three hours old" is read off the clock on the wall (it has to hold after a restart), the loader's half hour
    // off the time since boot. A clock that is set moves the one and not the other.

    @Test fun aClockSetOnMakesAYoungReadingOldAndItIsAskedAgainAtOnce() = FakeHttp.use { net ->
        net.forecasts()
        val s = source()
        val item = zurich()
        layout = listOf(item)
        s.turnOn()
        s.reading(item)
        pass(2 * min)
        wall += 4 * 60 * min // set four hours on, two minutes after a reading
        // Its numbers are too old to show now ...
        assertEquals(Status.Loading, s.status(item, wall))
        // ... so they are asked for now, not when the loader's own half hour is up: "Loading…" must be loading.
        s.reading(item)
        assertEquals(2, net.asked.size)
        assertTrue(s.status(item, wall) is Status.Live)
        // Once: the new reading is of the new time.
        s.watch(item, 20 * min)
        assertEquals(2, net.asked.size)
    }

    @Test fun openingTheMenuAfterTheClockWasSetOnAsksToo() = FakeHttp.use { net ->
        net.forecasts()
        val s = source()
        val item = zurich()
        layout = listOf(item)
        s.turnOn()
        s.reading(item)
        pass(2 * min)
        wall += 4 * 60 * min
        s.opened(item) // within the ten minutes in which opening the menu asks nothing
        assertEquals(2, net.asked.size)
        assertTrue(s.status(item, wall) is Status.Live)
    }

    @Test fun anOldReadingIsAskedForOnceAMinuteAtMostAndNotAtAllOnceATryHasFailed() = FakeHttp.use { net ->
        net.forecasts()
        net.fail(Host.OPEN_METEO, "/v1/forecast", Why.STATUS, 500)
        val s = source()
        val item = zurich()
        layout = listOf(item)
        s.turnOn()
        s.reading(item)
        pass(30 * sec)
        wall += 4 * 60 * min
        // Half a minute after the last request: not yet.
        s.reading(item); s.opened(item)
        assertEquals(1, net.asked.size)
        pass(30 * sec)
        s.reading(item)
        assertEquals(2, net.asked.size)
        // That try failed: from here the failure's own pace holds (fifteen minutes), and the state says what is wrong.
        assertEquals(Status.Missing(Failure.NO_ANSWER), s.status(item, wall))
        s.watch(item, 14 * min + 50 * sec)
        s.opened(item)
        assertEquals(2, net.asked.size)
    }

    @Test fun aClockSetBackDoesNotKeepAnOldTemperature() = FakeHttp.use { net ->
        net.forecasts()
        val s = source()
        val item = zurich()
        layout = listOf(item)
        s.turnOn()
        s.reading(item)
        Http.connected = { false } // offline from here on
        pass(10 * min)
        wall -= 5 * 60 * min // and the clock is set five hours back: by it the reading is from the future
        s.watch(item, 2 * 60 * min + 49 * min)
        // Two hours and 59 minutes after it was read, by the time since boot: the bar keeps it.
        assertEquals(18.1, (s.status(item, wall) as Status.Live).reading.current!!.temp!!, 1e-9)
        s.watch(item, 1 * min)
        // Three hours: an old temperature is a wrong temperature, whatever the clock on the wall was told.
        assertEquals(Status.Missing(Failure.OFFLINE), s.status(item, wall))
        assertEquals(1, net.asked.size)
    }

    @Test fun aClockSetBackWhileOnlineChangesNothing() = FakeHttp.use { net ->
        net.forecasts()
        val s = source()
        val item = zurich()
        layout = listOf(item)
        s.turnOn()
        s.reading(item)
        pass(10 * min)
        wall -= 5 * 60 * min
        s.watch(item, 19 * min)
        assertEquals(1, net.asked.size) // the half hour is the loader's, by the time since boot
        assertTrue(s.status(item, wall) is Status.Live)
        s.watch(item, 1 * min)
        assertEquals(2, net.asked.size)
    }

    // ---- a tester's staged failure ------------------------------------------------------------------

    @Test fun aStagedFailureStandsInForTheNextAnswerIsNotSentAndIsRetriedLikeARealOne() = FakeHttp.use { net ->
        net.forecasts()
        // `./bento debug weather fail error`: the next forecast request is not sent and counts as "no answer".
        var next: Failure? = Failure.NO_ANSWER
        val s = source(staged = { next.also { next = null } })
        val item = zurich()
        layout = listOf(item)
        s.turnOn()
        assertEquals(Failure.NO_ANSWER, s.reading(item)!!.failure)
        assertEquals(Status.Missing(Failure.NO_ANSWER), s.status(item, wall))
        sentNothing(net)
        // One retry after fifteen minutes, and that one is real.
        s.watch(item, 14 * min + 50 * sec)
        sentNothing(net)
        s.watch(item, 10 * sec)
        assertEquals(1, net.asked.size)
        assertTrue(s.status(item, wall) is Status.Live)
    }

    @Test fun aStagedSlowDownWaitsAnHourAndAStagedOfflineAMinute() = FakeHttp.use { net ->
        net.forecasts()
        var next: Failure? = Failure.SLOW_DOWN
        val s = source(staged = { next.also { next = null } })
        val item = zurich()
        layout = listOf(item)
        s.turnOn()
        s.reading(item)
        s.watch(item, 59 * min + 50 * sec)
        sentNothing(net)
        s.watch(item, 10 * sec)
        assertEquals(1, net.asked.size)
        // Not live, with a reading: the numbers stay and the next check comes within a minute.
        next = Failure.OFFLINE
        assertTrue(s.again(item.also { pass(1 * min) }))
        assertEquals(Failure.OFFLINE, (s.status(item, wall) as Status.Live).reading.failure)
        assertEquals(1, net.asked.size)
        s.watch(item, 1 * min)
        assertEquals(2, net.asked.size)
    }

    // ---- kept, and deleted ------------------------------------------------------------------------

    @Test fun aRestartShowsTheLastReadingWithoutAsking() = FakeHttp.use { net ->
        net.forecasts()
        val item = zurich()
        layout = listOf(item)
        val before = source()
        before.turnOn()
        val read = before.reading(item)!!
        val readAt = wall
        assertEquals(1, net.asked.size)

        // Twenty minutes later the app starts again: a new loader, with nothing in memory.
        pass(20 * min)
        val after = source()
        val back = after.reading(item)!!
        assertEquals(read, back)
        assertEquals(readAt, back.fetchedAt) // "updated 11:25 AM": its own time, not the restart's
        assertEquals(1, net.asked.size)
        after.watch(item, 9 * min)
        assertEquals(1, net.asked.size)
        // Half an hour after it was read, it is asked again as if nothing had happened.
        after.watch(item, 1 * min + 10 * sec)
        assertEquals(2, net.asked.size)
    }

    @Test fun aRestartAfterALongWhileAsksOnceAndShowsNoOldNumber() = FakeHttp.use { net ->
        net.forecasts()
        val item = zurich()
        layout = listOf(item)
        val before = source()
        before.turnOn()
        before.reading(item)
        pass(4 * 60 * min)
        Http.connected = { false }
        val after = source()
        after.reading(item)
        after.reading(item)
        // What was kept is four hours old: no number, and offline there is none to get.
        assertEquals(Status.Missing(Failure.OFFLINE), after.status(item, wall))
        Http.connected = { true }
        after.watch(item, 1 * min)
        assertEquals(2, net.asked.size)
        assertTrue(after.status(item, wall) is Status.Live)
    }

    @Test fun aRemovedItemLeavesNoReadingAndOneThatComesBackAsksAfresh() = FakeHttp.use { net ->
        net.forecasts()
        val s = source()
        val item = zurich()
        layout = listOf(item)
        s.turnOn()
        s.reading(item)
        assertEquals(setOf(Kept.safe("47.37,8.55")), Kept.fetched(OPEN_METEO).names())
        // Removed. The tick that puts the type to sleep looks at the layout one last time, and that is when the reading goes.
        layout = emptyList()
        s.keepOnly(WeatherLoad.places(layout))
        assertEquals(emptySet<String>(), Kept.fetched(OPEN_METEO).names())
        assertNull(s.peek(item))
        // Back again (an Undo), it asks afresh: one request, like an item that is new.
        layout = listOf(item)
        s.keepOnly(WeatherLoad.places(layout))
        assertNotNull(s.reading(item))
        assertEquals(2, net.asked.size)
    }

    @Test fun onAnInstallThatNeverSetWeatherUpThereIsNothingToTidy() = FakeHttp.use { net ->
        val s = source()
        layout = listOf(added)
        s.keepOnly(WeatherLoad.places(layout))
        s.keepOnly(emptySet())
        // No work for a background thread, no folder made: nothing was ever fetched here.
        assertEquals(0, backgroundRuns)
        assertFalse(File(dir, "fetched").exists())
        sentNothing(net)
        // Once it was set up, a change of the layout does look.
        s.turnOn()
        s.keepOnly(emptySet())
        assertEquals(1, backgroundRuns)
    }

    @Test fun theLastReadingIsDeletedWithTheItem() = FakeHttp.use { net ->
        net.forecasts()
        val s = source()
        val a = zurich()
        val b = oslo()
        layout = listOf(a, b)
        s.turnOn()
        s.reading(a); s.reading(b)
        assertEquals(2, Kept.fetched(OPEN_METEO).names().size)
        // Oslo's item is deleted.
        layout = listOf(a)
        s.keepOnly(WeatherLoad.places(layout))
        assertEquals(setOf(Kept.safe("47.37,8.55")), Kept.fetched(OPEN_METEO).names())
        assertNull(s.peek(b))
        assertNotNull(s.peek(a))
        // The last one too.
        layout = emptyList()
        s.keepOnly(WeatherLoad.places(layout))
        assertEquals(emptySet<String>(), Kept.fetched(OPEN_METEO).names())
        assertNull(s.peek(a))
        assertEquals(2, net.asked.size)
    }

    @Test fun changingTheCityDropsTheOldCitysReading() = FakeHttp.use { net ->
        net.forecasts()
        val s = source()
        val item = zurich()
        layout = listOf(item)
        s.turnOn()
        s.reading(item)
        val moved = WeatherRules.picked(item, City("Oslo", "Oslo", "Norway", "59.91", "10.75", "Europe/Oslo"), "Oslo, Norway")
        layout = listOf(moved)
        s.keepOnly(WeatherLoad.places(layout))
        s.reading(moved)
        assertEquals(setOf(Kept.safe("59.91,10.75")), Kept.fetched(OPEN_METEO).names())
        assertEquals("59.91,10.75", s.peek(moved)!!.place)
    }

    // ---- the search's answer ----------------------------------------------------------------------

    @Test fun anAnswerShowsOnlyWhereItWasAskedFor() = FakeHttp.use { net ->
        net.places()
        val s = source()
        layout = listOf(added)
        s.search("Springfield", added, "menu:w1")
        assertTrue(WeatherSource.shown(s.searching.value, "menu:w1") is Ask.State.Done)
        assertEquals(Ask.State.Idle, WeatherSource.shown(s.searching.value, "settings:w1"))
        assertEquals(Ask.State.Idle, WeatherSource.shown(s.searching.value, "menu:w2"))
        // Typing in another field leaves it; typing in its own clears it.
        s.typed("settings:w1")
        assertTrue(s.searching.value is Ask.State.Done)
        s.typed("menu:w1")
        assertEquals(Ask.State.Idle, s.searching.value)
    }

    @Test fun switchedOffTheSearchForgetsWhatItFound() = FakeHttp.use { net ->
        net.places()
        val s = source()
        layout = listOf(added)
        s.search("Springfield", added, "menu:w1")
        switchOff()
        assertEquals(Ask.State.Idle, s.searching.value)
    }

    @Test fun everyNewReadingIsToldSoTheBarShowsItAtOnce() = FakeHttp.use { net ->
        net.forecasts()
        val s = source()
        val item = zurich()
        layout = listOf(item)
        s.turnOn()
        val before = changes
        s.reading(item)
        assertEquals(before + 1, changes)
    }
}
