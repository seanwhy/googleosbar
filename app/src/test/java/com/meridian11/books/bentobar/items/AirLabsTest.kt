package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.items.AirLabs.Failure
import com.meridian11.books.bentobar.items.FlightRules.DayForm
import com.meridian11.books.bentobar.items.FlightRules.Said
import com.meridian11.books.bentobar.items.FlightRules.Saying
import com.meridian11.books.bentobar.items.FlightRules.Stage
import com.meridian11.books.bentobar.items.FlightRules.Standing
import com.meridian11.books.bentobar.items.FlightRules.Where
import com.meridian11.books.bentobar.net.Host
import com.meridian11.books.bentobar.net.Reply
import com.meridian11.books.bentobar.net.Request
import com.meridian11.books.bentobar.net.Why
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * AirLabs' replies, read. The files in `resources/airlabs` are what the service answered on 2 October
 * 2026 between 07:26 and 07:36 UTC, without their `request` object (it repeats the key and names the
 * caller's address).
 */
class AirLabsTest {
    private fun reply(name: String): String = javaClass.getResource("/airlabs/$name.json")!!.readText()
    private fun flight(name: String): Flight = AirLabs.flight(reply(name)).value!!
    private fun at(text: String): Instant = Instant.parse(text)
    private fun time(text: String): LocalDateTime = LocalDateTime.parse(text)
    private val lh455 = FlightNumber("LH", 455)
    /** When the saved replies were asked for. */
    private val asked = Instant.parse("2026-10-02T07:29:00Z")

    /** A service that answers each of its three questions with a saved reply (or the text given), and counts what it was asked. */
    private class Service(private val replies: Map<String, String>) {
        val asked = ArrayList<String>()
        fun get(request: Request): Reply {
            val what = request.path.substringAfterLast('/')
            asked.add(what)
            return replies[what]?.let { Reply.Ok(it) } ?: Reply.Failed(Why.OFFLINE)
        }
    }

    private fun lookup(n: FlightNumber, now: Instant, day: LocalDate? = null, get: (Request) -> Reply): AirLabs.Answer = AirLabs.lookup(n, day, now, get = get)!!
    private fun ok(text: String): (Request) -> Reply = { Reply.Ok(text) }

    private val none = """{"response":[]}"""

    @Test fun theRequestsNameTheHostThePathAndTwoThingsToSend() {
        fun said(r: Request) = Triple(r.host, r.path, r.query)
        assertEquals(Triple(Host.AIRLABS, "/api/v9/flight", listOf("flight_iata" to "LH455")), said(AirLabs.request(AirLabs.FLIGHT, lh455)))
        // A callsign is asked for as one, and a key loses the space or line end it was pasted with.
        assertEquals(Triple(Host.AIRLABS, "/api/v9/flight", listOf("flight_icao" to "DLH455")), said(AirLabs.request(AirLabs.FLIGHT, FlightNumber("DLH", 455))))
        assertEquals(Triple(Host.AIRLABS, "/api/v9/schedules", listOf("flight_iata" to "LH455")), said(AirLabs.request(AirLabs.SCHEDULES, lh455)))
        // The key goes as it is: the transport encodes what it sends.
        assertEquals(Triple(Host.AIRLABS, "/api/v9/routes", listOf("flight_iata" to "LH455")), said(AirLabs.request(AirLabs.ROUTES, lh455)))
        // And a request says nothing of either when it is printed.
        assertEquals("airlabs.co/api/v9/flight", AirLabs.request(AirLabs.FLIGHT, lh455).toString())
    }

    @Test fun aFlightInTheAir() {
        val f = flight("flight-LH455-in-the-air")
        assertEquals("LH455", f.number)
        assertEquals("DLH455", f.callsign)
        assertEquals("Lufthansa", f.airline)
        assertEquals(FlightState.IN_AIR, f.state)
        assertTrue(f.left)
        assertNull(f.flownAs)
        assertEquals("SFO", f.from.code)
        assertEquals("San Francisco", f.from.city)
        assertEquals(time("2026-10-01T14:40"), f.from.planned)
        assertEquals(time("2026-10-01T14:47"), f.from.actual)
        assertEquals(-420, f.from.offset)
        assertEquals(7, f.from.late)
        assertEquals("INTL", f.from.terminal)
        assertEquals("G13", f.from.gate)
        assertEquals("Boeing 747-8", f.aircraft)
        assertEquals("FRA", f.to.code)
        assertEquals("Frankfurt/Main", f.to.city)
        assertEquals(time("2026-10-02T10:25"), f.to.planned)
        assertEquals(time("2026-10-02T10:01"), f.to.expected)
        assertNull(f.to.actual)
        assertEquals(time("2026-10-02T10:01"), f.to.time)
        assertEquals(120, f.to.offset)
        assertEquals(-24, f.to.late)
        assertEquals("1", f.to.terminal)
        assertNull(f.to.belt)
        // The airport's own time is a moment.
        assertEquals(at("2026-10-01T21:47:00Z"), f.from.moment(f.from.time!!))
        assertEquals(at("2026-10-02T08:01:00Z"), f.to.moment(f.to.time!!))
    }

    @Test fun whatIsSaidOfIt() {
        val f = flight("flight-LH455-in-the-air")
        assertEquals(Said(Saying.AIR_EARLY, 24), FlightRules.said(f, asked))
        assertEquals(Where("1", null, null), FlightRules.where(f))
        // Asked from San Francisco on Thursday: it left today and lands on another day.
        val today = LocalDate.of(2026, 10, 1)
        assertEquals(DayForm.NONE, FlightRules.dayForm(f.from.time!!, today))
        assertEquals(DayForm.WEEKDAY, FlightRules.dayForm(f.to.time!!, today))
        // Asked from Frankfurt on Friday it is the other way round.
        assertEquals(DayForm.WEEKDAY, FlightRules.dayForm(f.from.time!!, today.plusDays(1)))
        assertEquals(DayForm.NONE, FlightRules.dayForm(f.to.time!!, today.plusDays(1)))
    }

    @Test fun plannedWithAGateAndNoNewTime() {
        val f = flight("flight-LH454-planned")
        assertEquals(FlightState.PLANNED, f.state)
        assertFalse(f.left)
        assertEquals(Said(Saying.PLANNED), FlightRules.said(f, asked))
        assertEquals(Where("1", "Z58", null), FlightRules.where(f))
        assertEquals(time("2026-10-02T10:25"), f.from.time)
        assertEquals(time("2026-10-02T12:40"), f.to.time)
        assertEquals(-420, f.to.offset)
    }

    @Test fun delayedBeforeItLeaves() {
        val f = flight("flight-LH152-delayed")
        assertEquals(FlightState.PLANNED, f.state)
        assertEquals(Said(Saying.DELAYED, 45), FlightRules.said(f, asked))
        assertEquals(time("2026-10-02T09:35"), f.from.time)
        assertEquals(Where("1", "A2", null), FlightRules.where(f))
        assertEquals("EN152", f.flownAs)
    }

    @Test fun landedThreeMinutesAfterItsPlanIsLanded() {
        val f = flight("flight-LH96-landed")
        assertEquals(FlightState.LANDED, f.state)
        assertEquals(Said(Saying.LANDED), FlightRules.said(f, asked))
        assertEquals(time("2026-10-02T09:13"), f.to.actual)
        assertEquals(Where("2", null, null), FlightRules.where(f))
    }

    @Test fun justLandedHalfAnHourEarlyBeforeTheServiceHasTheMinuteItTouchedDown() {
        // The same flight as "in the air", asked again five minutes after it landed (07:58 UTC): landed, and still no actual time.
        val f = flight("flight-LH455-landed")
        assertEquals(FlightState.LANDED, f.state)
        assertNull(f.to.actual)
        assertEquals(time("2026-10-02T09:56"), f.to.time)
        assertEquals(Said(Saying.LANDED_EARLY, 29), FlightRules.said(f, asked))
        assertEquals(Where("1", null, null), FlightRules.where(f))
        assertEquals(Standing(Stage.LANDED), FlightRules.standing(f, at("2026-10-02T07:58:00Z")))
        assertFalse(FlightRules.over(f, at("2026-10-02T10:56:00Z")))
        assertTrue(FlightRules.over(f, at("2026-10-02T10:57:00Z")))
    }

    @Test fun canceledKeepsItsPlanAndSaysNothingOfWhereToGo() {
        val f = flight("flight-LH1184-cancelled")
        assertEquals(FlightState.CANCELED, f.state)
        assertEquals(Said(Saying.CANCELED), FlightRules.said(f, asked))
        assertEquals(Where(null, null, null), FlightRules.where(f))
        assertEquals(time("2026-10-02T07:45"), f.from.time)
    }

    @Test fun aNumberSoldByOneAirlineAndFlownByAnother() {
        val f = flight("flight-LH9152-codeshare")
        assertEquals("LH9152", f.number)
        assertEquals("Lufthansa", f.airline)
        assertEquals("UA945", f.flownAs)
        // Thirteen minutes behind its plan: on time, since late starts at fifteen.
        assertEquals(13, f.to.late)
        assertEquals(Said(Saying.AIR_ON_TIME), FlightRules.said(f, asked))
        assertEquals(-300, f.to.offset)
    }

    @Test fun theErrors() {
        assertEquals(Failure.NOT_FOUND, AirLabs.flight(reply("error-not-found")).failure)
        assertEquals(Failure.REFUSED, AirLabs.flight(reply("error-unknown-key")).failure)
        assertEquals(Failure.NO_ANSWER, AirLabs.flight(reply("error-wrong-params")).failure)
        // The codes the documentation names and nobody could try.
        fun code(c: String) = AirLabs.flight("""{"error":{"message":"x","code":"$c"}}""").failure
        assertEquals(Failure.REFUSED, code("expired_api_key"))
        assertEquals(Failure.USED_UP, code("month_limit_exceeded"))
        assertEquals(Failure.NO_ANSWER, code("minute_limit_exceeded"))
        assertEquals(Failure.NO_ANSWER, code("hour_limit_exceeded"))
        assertEquals(Failure.NO_ANSWER, code("internal_error"))
        assertNull(AirLabs.flight(reply("error-not-found")).value)
    }

    @Test fun whatIsNotAReplyIsNoAnswer() {
        for (body in listOf("", "<html>502</html>", "[]", "{", """{"response":"pong"}""", """{"response":{}}""", """{"response":{"flight_iata":"LH455"}}""", """{"response":[1,2]}""", "null", "{\"response\":" + "[".repeat(100) + "}"))
            assertEquals(body.take(30), Failure.NO_ANSWER, AirLabs.flight(body).failure)
        assertEquals(Failure.NO_ANSWER, AirLabs.schedules("{}").failure)
        assertEquals(Failure.NO_ANSWER, AirLabs.routes("""{"response":{}}""").failure)
    }

    @Test fun aStatusNobodyHasSeenIsReadOffTheTimes() {
        fun f(status: String, extra: String = "") = AirLabs.flight("""{"response":{"flight_iata":"XX1","dep_iata":"AAA","arr_iata":"BBB","status":"$status","dep_time":"2026-10-02 08:00","dep_time_utc":"2026-10-02 06:00"$extra}}""").value!!
        assertEquals(FlightState.PLANNED, f("boarding").state)
        assertEquals(FlightState.IN_AIR, f("x", ""","dep_actual":"2026-10-02 08:05"""").state)
        assertEquals(FlightState.LANDED, f("x", ""","arr_actual":"2026-10-02 09:05"""").state)
        assertEquals(FlightState.DIVERTED, f("diverted").state)
        assertEquals(120, f("scheduled").from.offset)
        assertEquals("AAA", f("scheduled").from.place)
    }

    @Test fun howManyLookupsAreLeftIsReadWhereTheReplySays() {
        val r = AirLabs.flight("""{"request":{"key":{"type":"free","limits_total":994}},"error":{"message":"Flight not found","code":"not_found"}}""")
        assertEquals(994, r.left)
        assertNull(AirLabs.flight(reply("error-unknown-key")).left)
    }

    @Test fun theNextTenHoursOfOneNumber() {
        val list = AirLabs.schedules(reply("schedules-LH455")).value!!
        assertEquals(1, list.size)
        assertEquals(FlightState.IN_AIR, list[0].state)       // `active` here is `en-route` there
        assertEquals("", list[0].from.city)                   // no names in this reply
        assertEquals("SFO", list[0].from.place)
    }

    @Test fun whichFlightIsTheNext() {
        // SQ 26 is Singapore to Frankfurt and on to New York: three flights under one number in one reply.
        val list = AirLabs.schedules(reply("schedules-SQ26-two-legs")).value!!
        assertEquals(3, list.size)
        val now = at("2026-10-02T07:29:00Z")
        // The one in the air.
        assertEquals("JFK", FlightRules.next(list, now)!!.to.code)
        // Without it: the one that landed two hours ago.
        val rest = list.filter { it.state != FlightState.IN_AIR }
        val landed = FlightRules.next(rest, now)!!
        assertEquals(FlightState.LANDED, landed.state)
        assertEquals("21", landed.to.belt)
        assertEquals(Said(Saying.LANDED_LATE, 24), FlightRules.said(landed, asked))
        assertEquals(Where("1", null, "21"), FlightRules.where(landed))
        // Four hours after that landing: the next to leave.
        val later = FlightRules.next(rest, at("2026-10-02T09:30:00Z"))!!
        assertEquals(FlightState.PLANNED, later.state)
        assertEquals(time("2026-10-02T23:55"), later.from.planned)
        // Nothing to choose from.
        assertNull(FlightRules.next(emptyList(), now))
    }

    @Test fun aFlightIsOverThreeHoursAfterItLanded() {
        val f = flight("flight-JL101-landed-nine-hours-ago")        // landed 22:34 UTC
        assertFalse(FlightRules.over(f, at("2026-10-01T23:00:00Z")))
        assertFalse(FlightRules.over(f, at("2026-10-02T01:34:00Z")))
        assertTrue(FlightRules.over(f, at("2026-10-02T01:35:00Z")))
        assertTrue(FlightRules.over(f, at("2026-10-02T07:28:00Z")))
        // In the air or still to leave is never over; a canceled one is, three hours after it was to leave.
        assertFalse(FlightRules.over(flight("flight-LH455-in-the-air"), at("2026-10-03T00:00:00Z")))
        assertFalse(FlightRules.over(flight("flight-LH454-planned"), at("2026-10-03T00:00:00Z")))
        val gone = flight("flight-LH1184-cancelled")              // was to leave 05:45 UTC
        assertFalse(FlightRules.over(gone, at("2026-10-02T07:29:00Z")))
        assertTrue(FlightRules.over(gone, at("2026-10-02T08:46:00Z")))
    }

    @Test fun theTimetable() {
        val routes = AirLabs.routes(reply("routes-LH455")).value!!
        assertEquals(1, routes.size)
        val r = routes[0]
        assertEquals("SFO", r.from); assertEquals("FRA", r.to)
        assertEquals(LocalTime.of(14, 40), r.leaves)
        assertEquals(645, r.minutes)
        assertEquals(DayOfWeek.entries.toSet(), r.days)
        assertEquals(-420, r.fromOffset)
        assertEquals(120, r.toOffset)
        assertNull(r.fromTerminal)              // two are named: none is said
        assertEquals("1", r.toTerminal)
    }

    @Test fun anotherDayIsTheTimetablesFlightWithTheNamesOfTodays() {
        val today = flight("flight-LH455-in-the-air")
        val r = AirLabs.routes(reply("routes-LH455")).value!![0]
        val f = AirLabs.planned(r, today, LocalDate.of(2026, 10, 3))!!
        assertTrue(f.timetable)
        assertEquals(FlightState.PLANNED, f.state)
        assertEquals(Said(Saying.PLANNED), FlightRules.said(f, asked))
        assertEquals("LH455", f.number); assertEquals("Lufthansa", f.airline)
        assertEquals("San Francisco", f.from.city); assertEquals("Frankfurt/Main", f.to.city)
        assertEquals(time("2026-10-03T14:40"), f.from.planned)
        assertEquals(time("2026-10-04T10:25"), f.to.planned)      // 645 minutes later, on Frankfurt's clock
        assertNull(f.from.gate)
        assertEquals(Where(null, null, null), FlightRules.where(f))
        // A number that does not fly that day.
        assertNull(AirLabs.planned(r.copy(days = setOf(DayOfWeek.MONDAY)), today, LocalDate.of(2026, 10, 3)))
        // A timetable that names no days is taken to fly on all of them.
        assertNotNull(AirLabs.planned(r.copy(days = emptySet()), today, LocalDate.of(2026, 10, 3)))
    }

    @Test fun theNextOneInTheTimetable() {
        val today = flight("flight-LH455-in-the-air")
        val routes = AirLabs.routes(reply("routes-LH455")).value!!
        // Hours after it landed on Friday: Friday's own, which leaves at 21:40 UTC.
        assertEquals(time("2026-10-02T14:40"), AirLabs.upcoming(routes, today, at("2026-10-02T12:00:00Z"))!!.from.planned)
        // After that one has left: Saturday's.
        assertEquals(time("2026-10-03T14:40"), AirLabs.upcoming(routes, today, at("2026-10-02T21:41:00Z"))!!.from.planned)
        // Only on Mondays: the next Monday.
        val mondays = routes.map { it.copy(days = setOf(DayOfWeek.MONDAY)) }
        assertEquals(time("2026-10-05T14:40"), AirLabs.upcoming(mondays, today, at("2026-10-02T12:00:00Z"))!!.from.planned)
        assertNull(AirLabs.upcoming(emptyList(), today, at("2026-10-02T12:00:00Z")))
    }

    @Test fun oneLookupInTheUsualCase() {
        val s = Service(mapOf("flight" to reply("flight-LH455-in-the-air")))
        val a = lookup(lh455, at("2026-10-02T07:29:00Z"), get = s::get)
        assertEquals(listOf("flight"), s.asked)
        assertEquals(FlightState.IN_AIR, a.flight!!.state)
        assertNull(a.failure)
    }

    @Test fun hoursAfterItLandedTheNextOneIsAskedFor() {
        val jl101 = FlightNumber("JL", 101)
        val landed = reply("flight-JL101-landed-nine-hours-ago")
        val now = at("2026-10-02T07:28:00Z")
        // Among the coming ten hours' flights, with the names of the one that landed.
        val soon = Service(mapOf("flight" to landed, "schedules" to """{"response":[{"flight_iata":"JL101","dep_iata":"HND","arr_iata":"ITM","status":"scheduled",
            "dep_time":"2026-10-03 06:30","dep_time_utc":"2026-10-02 21:30","arr_time":"2026-10-03 07:35","arr_time_utc":"2026-10-02 22:35","dep_terminal":"1","dep_gate":"14"}]}"""))
        val a = lookup(jl101, now, get = soon::get).flight!!
        assertEquals(listOf("flight", "schedules"), soon.asked)
        assertEquals(time("2026-10-03T06:30"), a.from.planned)
        assertEquals("Tokyo", a.from.city)
        assertEquals("Japan Airlines", a.airline)
        assertEquals("14", a.from.gate)
        // Not among them: the timetable's next (here another number's lines stand in for it: the flow is what is tried).
        val far = Service(mapOf("flight" to landed, "schedules" to none, "routes" to reply("routes-LH455")))
        val b = lookup(jl101, now, get = far::get).flight!!
        assertEquals(listOf("flight", "schedules", "routes"), far.asked)
        assertTrue(b.timetable)
        assertEquals(time("2026-10-02T14:40"), b.from.planned)
        // Nobody knows of a next one: the one that landed, as it was.
        val last = Service(mapOf("flight" to landed, "schedules" to none, "routes" to none))
        assertEquals(FlightState.LANDED, lookup(jl101, now, get = last::get).flight!!.state)
        // Under three hours after the landing it is still the answer, and nothing more is asked.
        val fresh = Service(mapOf("flight" to landed))
        assertEquals(FlightState.LANDED, lookup(jl101, at("2026-10-02T00:00:00Z"), get = fresh::get).flight!!.state)
        assertEquals(listOf("flight"), fresh.asked)
    }

    @Test fun whenTheNextFlightCouldNotBeAskedForThatIsSaidNotTheOneThatLanded() {
        val jl101 = FlightNumber("JL", 101)
        val landed = reply("flight-JL101-landed-nine-hours-ago")
        val now = at("2026-10-02T07:28:00Z")
        // The one-flight question was answered; the two for the next flight found no connection.
        val cut = Service(mapOf("flight" to landed))
        val a = lookup(jl101, now, get = cut::get)
        assertNull(a.flight)
        assertEquals(Failure.OFFLINE, a.failure)
        assertEquals(listOf("flight", "schedules", "routes"), cut.asked)
        // One of the two is enough: told to slow down for the coming hours, or a timetable that timed out.
        val slow = lookup(jl101, now) { r -> when (r.path) { AirLabs.FLIGHT -> Reply.Ok(landed); AirLabs.SCHEDULES -> Reply.Failed(Why.STATUS, 429); else -> Reply.Ok(none) } }
        assertNull(slow.flight)
        assertEquals(Failure.NO_ANSWER, slow.failure)
        val late = lookup(jl101, now) { r -> when (r.path) { AirLabs.FLIGHT -> Reply.Ok(landed); AirLabs.SCHEDULES -> Reply.Ok(none); else -> Reply.Failed(Why.TIMEOUT) } }
        assertNull(late.flight)
        assertEquals(Failure.NO_ANSWER, late.failure)
        // The timetable has the next one though the coming hours could not be asked: that one, and nothing went wrong.
        val far = lookup(jl101, now) { r -> when (r.path) { AirLabs.FLIGHT -> Reply.Ok(landed); AirLabs.SCHEDULES -> Reply.Failed(Why.TIMEOUT); else -> Reply.Ok(reply("routes-LH455")) } }
        assertTrue(far.flight!!.timetable)
        assertNull(far.failure)
        // "Not found" is an answer: nobody knows of a next one, so it is the one that landed, as it was.
        val unknown = lookup(jl101, now) { r -> Reply.Ok(if (r.path == AirLabs.FLIGHT) landed else reply("error-not-found")) }
        assertEquals(FlightState.LANDED, unknown.flight!!.state)
        assertNull(unknown.failure)
    }

    @Test fun aDayChosenWithTheNumber() {
        val now = at("2026-10-02T07:29:00Z")
        val both = mapOf("flight" to reply("flight-LH455-in-the-air"), "routes" to reply("routes-LH455"))
        // The day of the flight that is in the air: that flight, one request.
        val same = Service(both)
        assertEquals(FlightState.IN_AIR, lookup(lh455, now, LocalDate.of(2026, 10, 1), same::get).flight!!.state)
        assertEquals(listOf("flight"), same.asked)
        // Another day: the timetable's.
        val other = Service(both)
        val a = lookup(lh455, now, LocalDate.of(2026, 10, 3), other::get).flight!!
        assertEquals(listOf("flight", "routes"), other.asked)
        assertTrue(a.timetable)
        assertEquals(time("2026-10-03T14:40"), a.from.planned)
        assertEquals("San Francisco", a.from.city)
        // A day the timetable does not have it on, and a timetable that is not to be had.
        val mondays = Service(mapOf("flight" to reply("flight-LH455-in-the-air"), "routes" to reply("routes-LH455").replace(Regex("\"days\": \\[[^]]*]"), "\"days\": [\"mon\"]")))
        assertEquals(Failure.NOT_THAT_DAY, lookup(lh455, now, LocalDate.of(2026, 10, 3), mondays::get).failure)
        val gone = Service(mapOf("flight" to reply("flight-LH455-in-the-air")))
        assertEquals(Failure.OFFLINE, lookup(lh455, now, LocalDate.of(2026, 10, 3), gone::get).failure)
    }

    @Test fun aDayChosenIsTheDevicesDayAndTakesTheFlightThatLeavesOnIt() {
        // LH 454 leaves Frankfurt on 2 October at 10:25, which is 08:25 UTC: in Honolulu the evening of the 1st, an hour from now.
        val now = at("2026-10-02T07:29:00Z")
        val honolulu = ZoneId.of("Pacific/Honolulu")
        val lh454 = FlightNumber("LH", 454)
        val today = LocalDate.of(2026, 10, 1)
        val s = Service(mapOf("flight" to reply("flight-LH454-planned"), "routes" to none))
        val a = AirLabs.lookup(lh454, today, now, honolulu, s::get)!!
        // "Today" there is that flight, not the one that left Frankfurt 23 hours ago.
        assertEquals(listOf("flight"), s.asked)
        assertEquals(time("2026-10-02T10:25"), a.flight!!.from.planned)
        assertFalse(a.flight.timetable)
        // A day that is the airport's own (what is kept of a followed flight) is read as that: no device's zone, no match.
        val kept = Service(mapOf("flight" to reply("flight-LH454-planned"), "routes" to none))
        assertEquals(Failure.NOT_THAT_DAY, AirLabs.lookup(lh454, today, now, null, kept::get)!!.failure)
        // "Tomorrow" in Honolulu is not that flight, though the 2nd is its date on the departure board: the timetable is asked
        // for the one that leaves on the 2nd by Honolulu's clock (and is not to be had here).
        val there = Service(mapOf("flight" to reply("flight-LH454-planned")))
        assertEquals(Failure.OFFLINE, AirLabs.lookup(lh454, LocalDate.of(2026, 10, 2), now, honolulu, there::get)!!.failure)
        assertEquals(listOf("flight", "routes"), there.asked)
    }

    @Test fun aDayChipMeansTheDayItLeavesByTheDevicesClockForTheLiveFlightAndForTheTimetable() {
        // Los Angeles, 8 PM on 5 October. JL 2 leaves Tokyo in an hour: at 1 PM on the 6th there, which is 9 PM on the 5th here.
        val now = at("2026-10-06T03:00:00Z")
        val la = ZoneId.of("America/Los_Angeles")
        val jl2 = FlightNumber("JL", 2)
        val live = """{"response":{"flight_iata":"JL2","flight_icao":"JAL2","airline_name":"Japan Airlines","status":"scheduled",
            "dep_iata":"HND","dep_city":"Tokyo","dep_time":"2026-10-06 13:00","dep_time_utc":"2026-10-06 04:00",
            "arr_iata":"SFO","arr_city":"San Francisco","arr_time":"2026-10-06 06:30","arr_time_utc":"2026-10-06 13:30"}}"""
        val table = """{"response":[{"flight_iata":"JL2","flight_icao":"JAL2","dep_iata":"HND","arr_iata":"SFO","dep_time":"13:00","dep_time_utc":"04:00",
            "arr_time":"06:30","arr_time_utc":"13:30","duration":570,"days":["mon","tue","wed","thu","fri","sat","sun"]}]}"""
        fun service() = Service(mapOf("flight" to live, "routes" to table))
        // "Today" is that flight: one request.
        val today = service()
        val a = AirLabs.lookup(jl2, LocalDate.of(2026, 10, 5), now, la, today::get)!!.flight!!
        assertEquals(listOf("flight"), today.asked)
        assertEquals(time("2026-10-06T13:00"), a.from.planned)
        assertFalse(a.timetable)
        // "Tomorrow" is not that flight, though the 6th is its date in Tokyo. It is the one that leaves on the 6th by this
        // device's clock: 1 PM on the 7th in Tokyo, from the timetable.
        val tomorrow = service()
        val b = AirLabs.lookup(jl2, LocalDate.of(2026, 10, 6), now, la, tomorrow::get)!!.flight!!
        assertEquals(listOf("flight", "routes"), tomorrow.asked)
        assertTrue(b.timetable)
        assertEquals(time("2026-10-07T13:00"), b.from.planned)
        assertEquals(LocalDate.of(2026, 10, 6), LocalDate.ofInstant(b.from.moment(b.from.planned!!), la))
        assertEquals("Tokyo", b.from.city)
        // The same when the service does not know the number and only the timetable does.
        val unknown = Service(mapOf("flight" to reply("error-not-found"), "routes" to table))
        assertEquals(time("2026-10-07T13:00"), AirLabs.lookup(jl2, LocalDate.of(2026, 10, 6), now, la, unknown::get)!!.flight!!.from.planned)
        // A day that comes with no zone is the airport's own date (what is kept of a followed flight): the 6th is the live flight then,
        // and the timetable's 6th is Tokyo's.
        val kept = service()
        assertFalse(AirLabs.lookup(jl2, LocalDate.of(2026, 10, 6), now, null, kept::get)!!.flight!!.timetable)
        assertEquals(listOf("flight"), kept.asked)
        assertEquals(time("2026-10-07T13:00"), AirLabs.lookup(jl2, LocalDate.of(2026, 10, 7), now, null, service()::get)!!.flight!!.from.planned)

        // Frankfurt, half past midnight on the 6th. A flight that left San Francisco twenty minutes ago, on the 5th there, left today here.
        val frankfurt = ZoneId.of("Europe/Berlin")
        val gone = """{"response":{"flight_iata":"UA926","dep_iata":"SFO","arr_iata":"FRA","status":"en-route",
            "dep_time":"2026-10-05 15:10","dep_time_utc":"2026-10-05 22:10","dep_actual":"2026-10-05 15:10","dep_actual_utc":"2026-10-05 22:10",
            "arr_time":"2026-10-06 11:00","arr_time_utc":"2026-10-06 09:00"}}"""
        val s = Service(mapOf("flight" to gone))
        val c = AirLabs.lookup(FlightNumber("UA", 926), LocalDate.of(2026, 10, 6), at("2026-10-05T22:30:00Z"), frankfurt, s::get)!!.flight!!
        assertEquals(FlightState.IN_AIR, c.state)
        assertEquals(listOf("flight"), s.asked)
    }

    @Test fun aNumberTheServiceDoesNotKnowMayStillBeInTheTimetable() {
        val now = at("2026-10-02T07:29:00Z")
        val unknown = Service(mapOf("flight" to reply("error-not-found"), "routes" to none))
        val a = lookup(lh455, now, get = unknown::get)
        assertEquals(Failure.NOT_FOUND, a.failure)
        assertEquals(listOf("flight", "routes"), unknown.asked)
        val weekly = Service(mapOf("flight" to reply("error-not-found"), "routes" to reply("routes-LH455")))
        val b = lookup(lh455, now, get = weekly::get).flight!!
        assertTrue(b.timetable)
        assertEquals("LH455", b.number)
        assertEquals("", b.airline)                              // the timetable names no airline, and there is no table of them here
        assertEquals("DLH455", b.callsign)                       // but it has the callsign, for the flight's page
        assertEquals("SFO", b.from.place)                        // no city: the three letters
        assertEquals(time("2026-10-02T14:40"), b.from.planned)
        // The timetable could not be asked: that is what is said, not "nothing found" (which would be remembered for an hour).
        val cut = Service(mapOf("flight" to reply("error-not-found")))
        assertEquals(Failure.OFFLINE, lookup(lh455, now, get = cut::get).failure)
        val spent = Service(mapOf("flight" to reply("error-not-found"), "routes" to """{"error":{"message":"x","code":"month_limit_exceeded"}}"""))
        assertEquals(Failure.USED_UP, lookup(lh455, now, get = spent::get).failure)
    }

    @Test fun aClockThatIsDaysFromUtcIsNobodysClock() {
        val f = AirLabs.flight("""{"response":{"flight_iata":"XX12","dep_iata":"AAA","arr_iata":"BBB","status":"scheduled",
            "dep_time":"2026-10-02 08:00","dep_time_utc":"2026-09-02 06:00","arr_time":"2026-10-02 10:00","arr_time_utc":"2026-10-02 23:30"}}""").value!!
        assertEquals(0, f.from.offset)
        assertEquals(-13 * 60 - 30, f.to.offset)
        // A flight with such an end is marked: its moments are anybody's guess, and nothing is counted from them.
        assertTrue(f.loose)
        // And nothing that is made from it throws.
        FlightRules.standing(f, at("2026-10-02T07:00:00Z"))
        FlightRules.over(f, at("2026-10-02T07:00:00Z"))
    }

    @Test fun whatWentWrongIsSaidAndCostsOneRequest() {
        val now = at("2026-10-02T07:29:00Z")
        val refused = Service(mapOf("flight" to reply("error-unknown-key")))
        assertEquals(Failure.REFUSED, lookup(lh455, now, get = refused::get).failure)
        assertEquals(listOf("flight"), refused.asked)
        assertEquals(Failure.OFFLINE, lookup(lh455, now, get = Service(emptyMap())::get).failure)
        assertEquals(Failure.NO_ANSWER, lookup(lh455, now) { throw IllegalStateException("x") }.failure)
        assertEquals(Failure.NO_ANSWER, lookup(lh455, now, get = ok("")).failure)
        val spent = """{"request":{"key":{"limits_total":0}},"error":{"message":"x","code":"month_limit_exceeded"}}"""
        assertEquals(Failure.USED_UP, lookup(lh455, now, get = ok(spent)).failure)
        assertEquals(0, lookup(lh455, now, get = ok(spent)).left)
    }

    @Test fun aCallsignIsAskedForByItsTicketNumberOnceTheServiceHasNamedIt() {
        // JAL101 was entered. The one-flight question takes a callsign; its reply names the ticket's number, and the
        // two further questions are then asked with that, the way they were tried against the service.
        val asked = ArrayList<Request>()
        val replies = mapOf(AirLabs.FLIGHT to reply("flight-JL101-landed-nine-hours-ago"), AirLabs.SCHEDULES to none, AirLabs.ROUTES to none)
        val a = lookup(FlightNumber("JAL", 101), at("2026-10-02T07:28:00Z")) { r -> asked += r; Reply.Ok(replies.getValue(r.path)) }
        assertEquals(FlightState.LANDED, a.flight!!.state)
        assertEquals(listOf(AirLabs.FLIGHT to ("flight_icao" to "JAL101"), AirLabs.SCHEDULES to ("flight_iata" to "JL101"), AirLabs.ROUTES to ("flight_iata" to "JL101")),
            asked.map { it.path to it.query.first() })
        for (r in asked) assertEquals(listOf(r.query.first().first), r.query.map { it.first })
        // A number the service does not know has no ticket form to go by: its timetable is asked for as it was entered.
        asked.clear()
        lookup(FlightNumber("JAL", 9999), at("2026-10-02T07:28:00Z")) { r -> asked += r; Reply.Ok(if (r.path == AirLabs.FLIGHT) reply("error-not-found") else none) }
        assertEquals(listOf("flight_icao" to "JAL9999", "flight_icao" to "JAL9999"), asked.map { it.query.first() })
    }

    @Test fun aRequestThatWasNotSentIsNoAnswerOfAnyKind() {
        // The switch went off or the bar hid under the lookup: nothing was asked, so there is nothing to say, not "no answer".
        val now = at("2026-10-02T07:29:00Z")
        assertNull(AirLabs.lookup(lh455, null, now) { Reply.Failed(Why.OFF) })
        // Also when it happens at the second step: what the first one brought does not count either.
        var asked = 0
        assertNull(AirLabs.lookup(FlightNumber("JL", 101), null, now) { if (asked++ == 0) Reply.Ok(reply("flight-JL101-landed-nine-hours-ago")) else Reply.Failed(Why.OFF) })
        assertEquals(2, asked)
        assertNull(AirLabs.again(lh455, flight("flight-LH455-in-the-air"), now) { Reply.Failed(Why.OFF) })
        // Anything else that is no reply is "no answer", and no connection is itself.
        assertEquals(Failure.NO_ANSWER, lookup(lh455, now) { Reply.Failed(Why.STATUS, 502) }.failure)
        assertEquals(Failure.NO_ANSWER, lookup(lh455, now) { Reply.Failed(Why.TIMEOUT) }.failure)
        assertEquals(Failure.OFFLINE, lookup(lh455, now) { Reply.Failed(Why.OFFLINE) }.failure)
    }

    @Test fun whatAFollowerCounts() {
        val air = flight("flight-LH455-in-the-air")               // lands 08:01 UTC
        assertEquals(Standing(Stage.IN_AIR, 32), FlightRules.standing(air, at("2026-10-02T07:29:00Z")))
        assertEquals(Standing(Stage.IN_AIR, 1), FlightRules.standing(air, at("2026-10-02T08:00:30Z")))
        assertEquals(Standing(Stage.IN_AIR, 0), FlightRules.standing(air, at("2026-10-02T09:00:00Z")))
        val planned = flight("flight-LH454-planned")               // leaves 08:25 UTC
        assertEquals(Standing(Stage.BEFORE, 56), FlightRules.standing(planned, at("2026-10-02T07:29:00Z")))
        assertEquals(Standing(Stage.LANDED), FlightRules.standing(flight("flight-LH96-landed"), at("2026-10-02T07:29:00Z")))
        assertEquals(Standing(Stage.CANCELED), FlightRules.standing(flight("flight-LH1184-cancelled"), at("2026-10-02T07:29:00Z")))
    }

    @Test fun onTimeNeedsANewTimeThatIsThePlans() {
        val planned = flight("flight-LH454-planned")
        assertEquals(Said(Saying.PLANNED), FlightRules.said(planned, asked))
        val same = planned.copy(from = planned.from.copy(expected = planned.from.planned))
        assertEquals(Said(Saying.ON_TIME), FlightRules.said(same, asked))
        // Late starts fifteen minutes after the plan.
        val plan = planned.from.planned!!
        val fourteen = planned.copy(from = planned.from.copy(expected = plan.plusMinutes(14)))
        assertEquals(Said(Saying.ON_TIME), FlightRules.said(fourteen, asked))
        val fifteen = planned.copy(from = planned.from.copy(expected = plan.plusMinutes(15)))
        assertEquals(Said(Saying.DELAYED, 15), FlightRules.said(fifteen, asked))
        // In the air with nothing known of the landing but its plan.
        val air = flight("flight-LH455-in-the-air")
        assertEquals(Said(Saying.IN_AIR), FlightRules.said(air.copy(to = air.to.copy(expected = null)), asked))
        assertEquals(Said(Saying.AIR_ON_TIME), FlightRules.said(air.copy(to = air.to.copy(expected = air.to.planned)), asked))
        assertEquals(Said(Saying.DIVERTED), FlightRules.said(air.copy(state = FlightState.DIVERTED), asked))
    }

    // ---- what a reply may not do

    @Test fun whatAReplySaysIsMadeFitToShow() {
        // Nothing of a reply is trusted to be short, or one line: it ends up in a 36 dp bar.
        val long = "x".repeat(500)
        val f = AirLabs.flight("""{"response":{"flight_iata":"LH455","flight_icao":"DLH455/../x","airline_name":"Luft\nhansa  $long","status":"scheduled",
            "dep_iata":"SFO","dep_city":" San\tFrancisco ","dep_gate":"G13\n$long","dep_terminal":"$long","arr_iata":"FRA","arr_baggage":"21 $long","model":"$long"}}""").value!!
        assertEquals("San Francisco", f.from.city)
        assertTrue(f.airline.startsWith("Luft hansa x") && f.airline.length <= 40)
        assertTrue(f.from.gate!!.let { it.length <= 8 && '\n' !in it })
        assertTrue(f.from.terminal!!.length <= 12)
        assertTrue(f.to.belt!!.length <= 8)
        assertTrue(f.aircraft!!.length <= 40)
        // A callsign that is not letters and digits is none: it becomes part of an address.
        assertNull(f.callsign)
    }

    @Test fun aTimeNobodyCouldMeanIsNoTime() {
        val f = AirLabs.flight("""{"response":{"flight_iata":"LH455","status":"scheduled","dep_iata":"SFO","arr_iata":"FRA",
            "dep_time":"99999-10-02 08:00","dep_estimated":"2026-13-45 08:00","arr_time":"1066-10-14 09:00"}}""").value!!
        assertNull(f.from.planned); assertNull(f.from.expected); assertNull(f.to.planned)
        // A timetable's line with a length nobody flies is no line.
        assertEquals(0, AirLabs.routes("""{"response":[{"dep_iata":"SFO","arr_iata":"FRA","dep_time":"14:40","dep_time_utc":"21:40","duration":99999999}]}""").value!!.size)
    }

    // ---- the number

    @Test fun aFlightNumberIsReadAsItIsTyped() {
        fun shown(text: String) = FlightNumber.read(text)?.shown
        assertEquals("LH 455", shown("lh455"))
        assertEquals("LH 455", shown("LH 455"))
        assertEquals("LH 455", shown(" LH-455 "))
        assertEquals("LH 455", shown("LH0455"))
        assertEquals("LH 4", shown("lh4"))
        assertEquals("UA 1234A", shown("ua1234a"))
        // A designator can hold a digit.
        assertEquals("U2 8001", shown("U28001"))
        assertEquals("4Y 123", shown("4y123"))
        // Three letters are a callsign, asked for as one.
        assertEquals("DLH 455", shown("dlh455"))
        assertTrue(FlightNumber.read("DLH455")!!.callsign)
        assertFalse(FlightNumber.read("LH455")!!.callsign)
        assertEquals("DLH455", FlightNumber.read("dlh 455")!!.code)
        assertEquals("LH455", FlightNumber.read("lh 0455")!!.code)
    }

    @Test fun whatIsNotAFlightNumberIsNone() {
        for (text in listOf("", " ", "hello", "LH", "455", "L 455", "LH 0", "LH12345", "LHLH455", "LH455AB", "LH 455 fri", "LH45 5", "\uFF2C\uFF28455", "lh455\nlh456", "12 34", "x".repeat(200)))
            assertNull(text, FlightNumber.read(text))
    }
}
