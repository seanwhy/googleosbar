package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.items.AirLabs.Failure
import com.meridian11.books.bentobar.net.Reply
import com.meridian11.books.bentobar.net.Request
import com.meridian11.books.bentobar.net.Why
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * A number that flies more than once a day. UA 1227 goes from Orlando to Newark in the morning, from
 * Newark to San Francisco after it and from San Francisco to Portland in the evening, every day, and
 * from Raleigh to Newark on Mondays. The three replies for it in `resources/airlabs` are what the
 * service answered on 6 October 2026 at 05:48 UTC, which is 10:48 PM on Monday the 5th in Los
 * Angeles, without their `request` object (it repeats the key and names the caller's address).
 */
class FlightCandidatesTest {
    private fun reply(name: String): String = javaClass.getResource("/airlabs/$name.json")!!.readText()
    private fun at(text: String): Instant = Instant.parse(text)
    private fun time(text: String): LocalDateTime = LocalDateTime.parse(text)
    private val flight = reply("flight-UA1227-first-leg-planned")
    private val table = reply("routes-UA1227-three-legs-a-day")
    private val soon = reply("schedules-UA1227")
    /** When the replies were asked for. */
    private val evening = at("2026-10-06T05:48:00Z")
    private val ua1227 = FlightNumber("UA", 1227)
    private val monday = LocalDate.of(2026, 10, 5)
    private val tuesday = LocalDate.of(2026, 10, 6)

    /** A service that answers each of its three questions with the text given (none: no connection), and remembers what it was asked. */
    private class Service(private val replies: Map<String, String>) {
        val asked = ArrayList<String>()
        fun get(request: Request): Reply {
            val what = request.path.substringAfterLast('/')
            asked.add(what)
            return replies[what]?.let { Reply.Ok(it) } ?: Reply.Failed(Why.OFFLINE)
        }
    }

    private fun service(vararg replies: Pair<String, String>) = Service(mapOf(*replies))
    /** The service as it answered that evening: the morning's flight, whatever is asked about the number, and its timetable. */
    private fun thatEvening() = service("flight" to flight, "routes" to table, "schedules" to soon)
    private val unknown = reply("error-not-found")
    private val none = """{"response":[]}"""
    private val la = ZoneId.of("America/Los_Angeles")
    private val tokyo = ZoneId.of("Asia/Tokyo")

    /** What a press of Track comes to: for a day chip, a day of the device's in [zone]; with no day, the next flight. */
    private fun press(s: Service, day: LocalDate? = null, zone: ZoneId = la, now: Instant = evening, n: FlightNumber = ua1227): AirLabs.Candidates =
        AirLabs.candidates(n, day, now, zone.takeIf { day != null }, s::get)!!

    /** Each flight as its two airports and the time it leaves, at its own airport. */
    private fun legs(c: AirLabs.Candidates) = c.flights.map { "${it.from.code}-${it.to.code} ${it.from.time}" }

    /** The evening's flight from San Francisco, which had landed, as the answer to the one-flight question: the coming hours' row for it, which has no names. */
    private val landed = "{\"response\":" + Json.parseToJsonElement(soon).jsonObject.getValue("response").jsonArray[1] + "}"

    // ---- the replies, read

    @Test fun theTimetableHasALineForEachFlightOfTheNumber() {
        val lines = AirLabs.routes(table).value!!
        assertEquals(listOf("EWR" to "SFO", "MCO" to "EWR", "RDU" to "EWR", "SFO" to "PDX"), lines.map { it.from to it.to })
        assertEquals(listOf(LocalTime.of(13, 20), LocalTime.of(8, 45), LocalTime.of(9, 10), LocalTime.of(19, 5)), lines.map { it.leaves })
        assertEquals(listOf(359, 161, 157, 115), lines.map { it.minutes })
        // Three of them every day, the one from Raleigh on Mondays.
        val daily = DayOfWeek.entries.toSet()
        assertEquals(listOf(daily, daily, setOf(DayOfWeek.MONDAY), daily), lines.map { it.days })
        // The East Coast four hours behind UTC, the West Coast seven: the evening flight's UTC twin is on the day after, which is read right.
        assertEquals(listOf(-240, -240, -240, -420), lines.map { it.fromOffset })
        assertEquals(listOf(-420, -240, -240, -420), lines.map { it.toOffset })
        // A terminal is said only where one is named, not two.
        assertEquals(listOf(null, "B", "2", null), lines.map { it.fromTerminal })
        assertTrue(lines.all { it.toTerminal == null && it.callsign == "UAL1227" })
    }

    @Test fun theServicesNearestFlightIsTheMorningsWhichHasNotLeftYet() {
        val f = AirLabs.flight(flight).value!!
        assertEquals("UA1227", f.number)
        assertEquals("United Airlines", f.airline)
        assertEquals(FlightState.PLANNED, f.state)
        assertEquals(FlightEnd("MCO", "Orlando", time("2026-10-06T08:45"), offset = -240, terminal = "B", gate = "48"), f.from)
        assertEquals(FlightEnd("EWR", "Newark", time("2026-10-06T11:26"), offset = -240, terminal = "A", gate = "20", belt = "3"), f.to)
        // Seven hours off when it was asked for.
        assertEquals(at("2026-10-06T12:45:00Z"), f.from.moment(f.from.planned!!))
        assertTrue(!f.loose && !f.timetable)
        assertNull(f.flownAs)
    }

    @Test fun theComingHoursHaveThatFlightAndTheEveningsThatLandedTwoHoursAgo() {
        val rows = AirLabs.schedules(soon).value!!
        assertEquals(listOf("MCO" to FlightState.PLANNED, "SFO" to FlightState.LANDED), rows.map { it.from.code to it.state })
        // No cities and no airline in this reply; the gate and the belt are there.
        assertEquals(listOf("", ""), rows.map { it.from.city })
        assertEquals("48", rows[0].from.gate)
        assertEquals(time("2026-10-05T20:50"), rows[1].to.actual)
        assertEquals("9", rows[1].to.belt)
        // The one that landed is still "the next" for an hour more, and over after that.
        assertTrue(!FlightRules.over(rows[1], evening))
        assertTrue(FlightRules.over(rows[1], at("2026-10-06T06:51:00Z")))
        assertTrue(rows.none { it.loose })
    }

    // ---- a flight that is followed is looked up again by its airport

    @Test fun withAnAirportNamedTheLookupLandsOnTheFlightThatLeavesFromIt() {
        // San Francisco on the 6th. The service answers with the morning's flight from Orlando: not that one, so the
        // timetable's line from San Francisco, and no other.
        val s = thatEvening()
        val third = AirLabs.lookup(ua1227, tuesday, "SFO", evening, s::get)!!.flight!!
        assertEquals(listOf("flight", "routes"), s.asked)
        assertEquals("SFO" to "PDX", third.from.code to third.to.code)
        assertEquals(time("2026-10-06T19:05"), third.from.planned)
        assertEquals(time("2026-10-06T21:00"), third.to.planned)
        assertTrue(third.timetable)
        assertEquals("United Airlines", third.airline)                 // the names are the ones the service gave for the number
        // Newark: the second, whose airport the service named as the first one's arrival.
        val second = AirLabs.lookup(ua1227, tuesday, "EWR", evening, thatEvening()::get)!!.flight!!
        assertEquals("EWR" to "SFO", second.from.code to second.to.code)
        assertEquals(time("2026-10-06T13:20"), second.from.planned)
        assertEquals(time("2026-10-06T16:19"), second.to.planned)      // 359 minutes later, on San Francisco's clock
        assertEquals("Newark" to "SFO", second.from.place to second.to.place)
        // Orlando: the service's own flight, with its gate, and one request.
        val o = thatEvening()
        val first = AirLabs.lookup(ua1227, tuesday, "MCO", evening, o::get)!!.flight!!
        assertEquals(listOf("flight"), o.asked)
        assertFalse(first.timetable)
        assertEquals("48", first.from.gate)
        // With no airport named it is as it was before there was a choice: the service's flight, if that is its day.
        assertEquals("MCO", AirLabs.lookup(ua1227, tuesday, null, evening, thatEvening()::get)!!.flight!!.from.code)
        assertEquals("MCO", AirLabs.lookup(ua1227, tuesday, evening, get = thatEvening()::get)!!.flight!!.from.code)
    }

    @Test fun anAirportTheNumberDoesNotLeaveOnThatDayIsNoFlight() {
        // Raleigh on a Tuesday: its line flies on Mondays. And an airport the number never leaves.
        assertEquals(Failure.NOT_THAT_DAY, AirLabs.lookup(ua1227, tuesday, "RDU", evening, thatEvening()::get)!!.failure)
        assertEquals(Failure.NOT_THAT_DAY, AirLabs.lookup(ua1227, tuesday, "LAX", evening, thatEvening()::get)!!.failure)
        val raleigh = AirLabs.lookup(ua1227, monday, "RDU", evening, thatEvening()::get)!!.flight!!
        assertEquals(time("2026-10-05T09:10"), raleigh.from.planned)
        assertEquals("RDU" to "Newark", raleigh.from.place to raleigh.to.place)
        // The timetable is not to be had: that is what went wrong, and the service's flight from another airport is no answer.
        val cut = service("flight" to flight)
        val a = AirLabs.lookup(ua1227, tuesday, "SFO", evening, cut::get)!!
        assertNull(a.flight)
        assertEquals(Failure.OFFLINE, a.failure)
        assertEquals(listOf("flight", "routes"), cut.asked)
        // A number the service's one-flight question does not know: the timetable's line from that airport, with no names.
        val bare = AirLabs.lookup(ua1227, tuesday, "SFO", evening, service("flight" to unknown, "routes" to table)::get)!!.flight!!
        assertEquals("SFO" to "PDX", bare.from.place to bare.to.place)
        assertEquals("", bare.airline)
        assertEquals(Failure.NOT_FOUND, AirLabs.lookup(ua1227, tuesday, "LAX", evening, service("flight" to unknown, "routes" to table)::get)!!.failure)
    }

    @Test fun withAnAirportAndNoDayItIsThatAirportsNextFlight() {
        // (What is kept of a flight always has its day, unless the service named no time for it to leave.)
        // From Orlando: the service's flight, which has not left. One request.
        val o = thatEvening()
        assertEquals("48", AirLabs.lookup(ua1227, null, "MCO", evening, o::get)!!.flight!!.from.gate)
        assertEquals(listOf("flight"), o.asked)
        // From San Francisco: not that flight. The coming hours have the one that landed there two hours ago, which is still "the next".
        val s = thatEvening()
        val down = AirLabs.lookup(ua1227, null, "SFO", evening, s::get)!!.flight!!
        assertEquals(listOf("flight", "schedules"), s.asked)
        assertEquals(FlightState.LANDED, down.state)
        assertEquals("SFO" to "PDX", down.from.code to down.to.code)
        // An hour later that one is over: the timetable's next from San Francisco, this evening's.
        val later = thatEvening()
        val next = AirLabs.lookup(ua1227, null, "SFO", at("2026-10-06T07:00:00Z"), later::get)!!.flight!!
        assertEquals(listOf("flight", "schedules", "routes"), later.asked)
        assertTrue(next.timetable)
        assertEquals(time("2026-10-06T19:05"), next.from.planned)
        // From Newark, where nothing of the coming hours leaves: the timetable's too.
        assertEquals(time("2026-10-06T13:20"), AirLabs.lookup(ua1227, null, "EWR", evening, thatEvening()::get)!!.flight!!.from.planned)
        // An airport it never leaves: nothing, and not the flight from Orlando "as it was".
        val nowhere = AirLabs.lookup(ua1227, null, "LAX", evening, thatEvening()::get)!!
        assertNull(nowhere.flight)
        assertEquals(Failure.NOT_FOUND, nowhere.failure)
    }

    // ---- a flight that is followed is asked about again, and stays the one it is

    /** The service's answer about another of Tuesday's flights: made here from the timetable's times, with a gate. */
    private fun leg(from: String, to: String, leaves: String, leavesUtc: String, lands: String, landsUtc: String, gate: String) = """{"response":{"flight_iata":"UA1227","flight_icao":"UAL1227",
        "airline_name":"United Airlines","status":"scheduled","dep_iata":"$from","dep_gate":"$gate","dep_time":"$leaves","dep_time_utc":"$leavesUtc","arr_iata":"$to","arr_time":"$lands","arr_time_utc":"$landsUtc"}}"""
    private val fromNewark = leg("EWR", "SFO", "2026-10-06 13:20", "2026-10-06 17:20", "2026-10-06 16:19", "2026-10-06 23:19", "C92")
    private val fromSanFrancisco = leg("SFO", "PDX", "2026-10-06 19:05", "2026-10-07 02:05", "2026-10-06 21:00", "2026-10-07 04:00", "F14")

    @Test fun followingTheThirdFlightOfTheDayStaysOnItWhileTheServiceAnswersWithTheFirstAndTheSecond() {
        val flights = press(thatEvening(), tuesday).flights
        val third = flights[2]
        // (Asked on Monday evening, more than ten hours before either leaves: the one-flight question's turn.)
        fun again(text: String, was: Flight = third) = AirLabs.again(ua1227, was, evening) { Reply.Ok(text) }!!.again
        // The service answers with the morning's flight from Orlando (the saved reply), then with the one from Newark: each leaves
        // before the one that is followed. Not yet: the plan stands, and it is asked about again when it is due.
        assertEquals(AirLabs.Again.NotYet, again(flight))
        assertEquals(AirLabs.Again.NotYet, again(fromNewark))
        // Monday evening's flight from San Francisco, which landed: the same airport, and another day's flight. Not yet either.
        assertEquals(AirLabs.Again.NotYet, again(landed))
        // Tuesday evening's own: that is the flight, with its gate.
        assertEquals("F14", (again(fromSanFrancisco) as AirLabs.Again.Is).flight.from.gate)
        // Wednesday's first: another airport's flight, which says nothing while Tuesday evening's has yet to fly. Asked once its
        // time to land has passed (04:00 UTC on Wednesday), nobody will say more of it: the asking ends.
        val wednesday = Regex("\\d{4}-\\d{2}-\\d{2}").replace(flight) { LocalDate.parse(it.value).plusDays(1).toString() }
        assertEquals(AirLabs.Again.NotYet, again(wednesday))
        assertEquals(AirLabs.Again.Gone, AirLabs.again(ua1227, third, at("2026-10-07T08:00:00Z")) { Reply.Ok(wednesday) }!!.again)
        // The second is followed the same way: not yet while the first is the answer, itself when it is. And not over when the
        // third is: another airport's flight of the same day says nothing of this one (1.0 took it for the end and stopped asking).
        assertEquals(AirLabs.Again.NotYet, again(flight, flights[1]))
        assertEquals("C92", (again(fromNewark, flights[1]) as AirLabs.Again.Is).flight.from.gate)
        assertEquals(AirLabs.Again.NotYet, again(fromSanFrancisco, flights[1]))
        // Nor when Wednesday's first is: another airport's flight, and Newark's has not landed. (A later leg can leave after
        // midnight, local or not: Singapore 23:55 to Frankfurt, then Frankfurt 08:35 to New York the next day.)
        assertEquals(AirLabs.Again.NotYet, again(wednesday, flights[1]))
        // And whichever is followed is one flight to the rule, and none of the others.
        for (a in flights) for (b in flights) assertEquals(a === b, FlightRules.same(a, b))
    }

    // ---- Tuesday, with the flight from Newark late: what the service answered at 17:34 and at 19:47 UTC

    /**
     * The one-flight question about UA 1227 on Tuesday the 6th. At 17:34 UTC, with the flight from
     * Newark due to leave in a quarter of an hour, it answered with the evening's flight from San
     * Francisco. At 19:47 UTC, with Newark's in the air, with a mix of the two: Newark's airports and
     * position, San Francisco's times, gates and belt. The coming hours' list, asked at 19:47 UTC,
     * had both flights, each as it was: Newark's left at 13:47 instead of 13:20.
     */
    private val thirdPlanned = reply("flight-UA1227-third-leg-planned")
    private val mixedUp = reply("flight-UA1227-second-leg-in-the-air-with-the-thirds-times")
    private val secondLate = reply("schedules-UA1227-second-leg-late-in-the-air")
    private val dueToLeave = at("2026-10-06T17:34:00Z")
    private val inTheAir = at("2026-10-06T19:47:00Z")

    /** The flight from Newark as it was chosen on Monday evening: the timetable's plan. */
    private fun fromNewarkPlan(): Flight = press(thatEvening(), tuesday).flights[1].also { assertEquals("EWR-SFO", "${it.from.code}-${it.to.code}") }

    @Test fun aFollowedFlightIsHeardOfInTheComingHoursListWhateverTheOneFlightQuestionSays() {
        val s = service("flight" to mixedUp, "schedules" to secondLate)
        val f = (AirLabs.again(ua1227, fromNewarkPlan(), inTheAir, s::get)!!.again as AirLabs.Again.Is).flight
        // One request, as before: the list of the coming hours, which has each flight of the number as it is.
        assertEquals(listOf("schedules"), s.asked)
        assertEquals("EWR-SFO", "${f.from.code}-${f.to.code}")
        assertEquals(FlightState.IN_AIR, f.state)
        assertFalse(f.timetable)
        assertEquals(time("2026-10-06T13:20"), f.from.planned)
        assertEquals(time("2026-10-06T13:47"), f.from.actual)
        assertEquals(27, f.from.late)
        assertEquals(time("2026-10-06T16:21"), f.to.expected)
        assertEquals("A28", f.from.gate)
        // The list names no airline and no cities: the ones the flight had stay.
        assertEquals("United Airlines", f.airline)
        assertEquals("Newark", f.from.place)
    }

    @Test fun anotherAirportsFlightOfTheSameDayIsNoWordAboutTheFollowedOne() {
        // 17:34 UTC: the coming hours' list has nothing to say (here: nothing at all), and the one-flight question answers with
        // San Francisco's flight. The plan for Newark's stands; whether it is over is the clock's to say, not that answer's.
        val s = service("flight" to thirdPlanned, "schedules" to none)
        val asked = AirLabs.again(ua1227, fromNewarkPlan(), dueToLeave, s::get)!!
        assertEquals(AirLabs.Again.NotYet, asked.again)
        assertNull(asked.failure)
        assertEquals(listOf("schedules", "flight"), s.asked)
    }

    @Test fun withinTheComingHoursAListThatCannotBeHadIsTheAnswer() {
        val was = fromNewarkPlan()
        // No connection, or a slow-down: no answer this time, and no second request to spend.
        val offline = service("flight" to mixedUp)
        val a = AirLabs.again(ua1227, was, inTheAir, offline::get)!!
        assertEquals(AirLabs.Again.Failed, a.again)
        assertEquals(Failure.OFFLINE, a.failure)
        assertEquals(listOf("schedules"), offline.asked)
        val slow = AirLabs.again(ua1227, was, inTheAir) { Reply.Failed(Why.STATUS, 429, retryAfterSec = 600) }!!
        assertEquals(AirLabs.Again.Failed, slow.again)
        assertEquals(600L, slow.retryAfterSec)
        // A key that is refused: the end of asking until it is put right, as for the one-flight question.
        val refused = service("schedules" to reply("error-unknown-key"), "flight" to mixedUp)
        val r = AirLabs.again(ua1227, was, inTheAir, refused::get)!!
        assertEquals(AirLabs.Again.Gone, r.again)
        assertEquals(Failure.REFUSED, r.failure)
        assertEquals(listOf("schedules"), refused.asked)
        // Switched off on the way: nothing to say.
        assertNull(AirLabs.again(ua1227, was, inTheAir) { Reply.Failed(Why.OFF) })
    }

    @Test fun theListIsAskedForTheNumberAsItWasEnteredAndNothingElse() {
        val sent = ArrayList<Request>()
        fun keep(text: String): (Request) -> Reply = { r -> sent += r; Reply.Ok(text) }
        AirLabs.again(ua1227, fromNewarkPlan(), inTheAir, keep(secondLate))
        // A callsign is asked for in the ticket's form the followed flight has: the list was not seen to take a callsign.
        AirLabs.again(FlightNumber("UAL", 1227), fromNewarkPlan(), inTheAir, keep(secondLate))
        assertEquals(List(2) { AirLabs.SCHEDULES to listOf("flight_iata" to "UA1227") }, sent.map { it.path to it.query })
    }

    @Test fun theListIsAskedFromTenHoursBeforeItLeavesUntilAnHourAfterItLanded() {
        // Newark's plan: leaves 17:20, lands 23:19 UTC. The list was seen to keep a landed flight for an hour and more.
        val plan = fromNewarkPlan()
        assertTrue(FlightRules.listed(plan, at("2026-10-07T00:19:00Z")))
        assertFalse(FlightRules.listed(plan, at("2026-10-07T00:20:00Z")))
        // By the time it is now expected to land, where the service said one: Newark's landed at 16:21, two minutes late.
        val heard = AirLabs.schedules(secondLate).value!![0]
        assertTrue(FlightRules.listed(heard, at("2026-10-07T00:21:00Z")))
        assertFalse(FlightRules.listed(heard, at("2026-10-07T00:22:00Z")))
        // Not a codeshare's number (the list was not seen to have one by the number it is sold as), and not a flight with no
        // planned time (that is what finds it there): those are asked about with the one-flight question, as before.
        val sold = AirLabs.flight(reply("flight-LH9152-codeshare")).value!!
        assertTrue(sold.flownAs != null)
        assertFalse(FlightRules.listed(sold, sold.from.moment(sold.from.planned!!)))
        assertFalse(FlightRules.listed(plan.copy(from = plan.from.copy(planned = null, expected = plan.from.planned)), inTheAir))
    }

    @Test fun wokenLongAfterItWasToLandOneQuestionAndAnotherAirportsFlightEndIt() {
        // The lid was closed through the landing. Two hours after it, the list has let it go: the one-flight question alone, and
        // San Francisco's flight. Newark's is over by the clock and nobody will say more of it: the end, which shows it landed.
        val heard = AirLabs.schedules(secondLate).value!![0]
        val s = service("flight" to thirdPlanned, "schedules" to secondLate)
        val asked = AirLabs.again(ua1227, heard, at("2026-10-07T01:30:00Z"), s::get)!!
        assertEquals(listOf("flight"), s.asked)
        assertEquals(AirLabs.Again.Gone, asked.again)
        assertNull(asked.failure)
    }

    @Test fun aFlightFoundByTrackIsAskedAboutAtOnceWhereTheComingHoursListCanHaveIt() {
        // A press of Track at 19:47 UTC: "Next flight" takes the one-flight question's mixed-up answer for Newark's, and a day chip
        // the timetable's plan. Either is put right by one ask of the list, at once.
        fun atOnce(f: Flight, now: Instant, left: Int? = 880) =
            FlightRules.askAtOnce(FlightRules.Tracked("UA1227", "2026-10-06", f, left = left, from = f.from.code), now)
        val plan = fromNewarkPlan()
        assertTrue(atOnce(plan, inTheAir))
        assertTrue(atOnce(AirLabs.flight(mixedUp).value!!, inTheAir))
        // Ten hours before it leaves (13:20 in Newark is 17:20 UTC), and not a minute earlier: the list reaches no further.
        assertTrue(atOnce(plan, at("2026-10-06T07:20:00Z")))
        assertFalse(atOnce(plan, at("2026-10-06T07:19:00Z")))
        // Nothing to ask once it has landed, or by the clock was to land hours ago.
        val flown = AirLabs.flight(landed).value!!
        assertEquals(FlightState.LANDED, flown.state)
        assertFalse(atOnce(flown, evening))
        assertFalse(atOnce(plan, at("2026-10-07T03:00:00Z")))
        // Nor with so few lookups left that nothing is asked unasked; with no count said yet, as usual.
        assertFalse(atOnce(plan, inTheAir, left = FlightRules.FEW - 1))
        assertTrue(atOnce(plan, inTheAir, left = null))
    }

    // ---- the flights a press of Track can mean

    @Test fun tomorrowSeenFromLosAngelesIsItsThreeFlightsInTheOrderTheyLeave() {
        val s = thatEvening()
        val c = press(s, tuesday)
        assertEquals(listOf("flight", "routes"), s.asked)
        assertNull(c.failure)
        // Not the one from Raleigh, which flies on Mondays.
        assertEquals(listOf("MCO-EWR 2026-10-06T08:45", "EWR-SFO 2026-10-06T13:20", "SFO-PDX 2026-10-06T19:05"), legs(c))
        assertEquals(listOf("2026-10-06T11:26", "2026-10-06T16:19", "2026-10-06T21:00"), c.flights.map { it.to.time.toString() })
        // The first is the service's own flight, as it said it; the two after it are the timetable's, a plan each.
        assertEquals(AirLabs.flight(flight).value, c.flights[0])
        assertEquals(listOf(false, true, true), c.flights.map { it.timetable })
        assertTrue(c.flights.drop(1).all { it.state == FlightState.PLANNED && it.from.gate == null && !it.loose })
        // They are said with the names the service gave: the airline for all of them, a city where it named that airport.
        assertEquals(listOf("Orlando" to "Newark", "Newark" to "SFO", "SFO" to "PDX"), c.flights.map { it.from.place to it.to.place })
        assertTrue(c.flights.all { it.number == "UA1227" && it.airline == "United Airlines" && it.callsign == "UAL1227" })
    }

    @Test fun onAMondayThereAreFour() {
        // "Today", that Monday evening in Los Angeles: all four have left, and all four are that day's.
        val c = press(thatEvening(), monday)
        assertEquals(listOf("MCO-EWR 2026-10-05T08:45", "RDU-EWR 2026-10-05T09:10", "EWR-SFO 2026-10-05T13:20", "SFO-PDX 2026-10-05T19:05"), legs(c))
        // The service's flight is Tuesday's: none of these is it.
        assertTrue(c.flights.all { it.timetable })
        assertEquals(listOf("Orlando" to "Newark", "RDU" to "Newark", "Newark" to "SFO", "SFO" to "PDX"), c.flights.map { it.from.place to it.to.place })
    }

    @Test fun theNextFlightIsAskedAboutAmongTheOnesThatLeaveWithinADay() {
        val s = thatEvening()
        val c = press(s)
        assertEquals(listOf("flight", "routes"), s.asked)
        // Seven, eleven and a half and twenty hours off. The one from Raleigh leaves in six days.
        assertEquals(listOf("MCO-EWR 2026-10-06T08:45", "EWR-SFO 2026-10-06T13:20", "SFO-PDX 2026-10-06T19:05"), legs(c))
        // The first is the flight the service answered with, as itself: it has not left yet.
        assertEquals(AirLabs.flight(flight).value, c.flights[0])
        assertEquals(listOf(false, true, true), c.flights.map { it.timetable })
        assertEquals(Duration.ofHours(24), AirLabs.DAY_AHEAD)
    }

    @Test fun aLineThatLeavesInExactlyADayIsOfferedAndOneAMinuteLaterIsNot() {
        // Only the timetable knows the number here. The Monday flight from Raleigh leaves on the 12th at 13:10 UTC.
        fun count(now: String) = press(service("flight" to unknown, "routes" to table), now = at(now)).flights.size
        assertEquals(4, count("2026-10-11T13:10:00Z"))
        assertEquals(3, count("2026-10-11T13:09:00Z"))
        // A flight that leaves this very minute is not one that is still to leave.
        assertEquals(listOf("EWR-SFO 2026-10-11T13:20", "SFO-PDX 2026-10-11T19:05", "MCO-EWR 2026-10-12T08:45"),
            legs(press(service("flight" to unknown, "routes" to table), now = at("2026-10-11T12:45:00Z"))))
    }

    @Test fun fromADeviceInTokyoTheDayIsTokyosAndTheFlightsAreTheOnesThatLeaveOnIt() {
        // 2:48 PM on Tuesday there. Its Tuesday began at 15:00 UTC on Monday: two of the flights that leave on it
        // leave on Monday by their own airports' clocks, and the morning's from Orlando is the service's own.
        val today = press(thatEvening(), tuesday, tokyo)
        assertEquals(listOf("EWR-SFO 2026-10-05T13:20", "SFO-PDX 2026-10-05T19:05", "MCO-EWR 2026-10-06T08:45"), legs(today))
        assertEquals(listOf(true, true, false), today.flights.map { it.timetable })
        for (f in today.flights) assertEquals(tuesday, LocalDate.ofInstant(f.from.moment(f.from.time!!), tokyo))
        // Its Wednesday: the two later ones of the airports' Tuesday, and Wednesday's first.
        val tomorrow = press(thatEvening(), tuesday.plusDays(1), tokyo)
        assertEquals(listOf("EWR-SFO 2026-10-06T13:20", "SFO-PDX 2026-10-06T19:05", "MCO-EWR 2026-10-07T08:45"), legs(tomorrow))
        assertTrue(tomorrow.flights.all { it.timetable })
        // The same Tuesday asked for in Los Angeles is other flights.
        assertEquals(listOf("MCO-EWR 2026-10-06T08:45", "EWR-SFO 2026-10-06T13:20", "SFO-PDX 2026-10-06T19:05"), legs(press(thatEvening(), tuesday, la)))
    }

    @Test fun theServicesOwnFlightStandsInItsLinesPlaceAndTakesFromThePlanOnlyWhatItLacks() {
        // The service's flight as it was said has everything: it is offered as it is, and the timetable's plan for it is not.
        val whole = press(thatEvening(), tuesday).flights
        assertEquals(1, whole.count { it.from.code == "MCO" })
        assertEquals("48", whole[0].from.gate)
        // The same flight with no time of landing and no terminal to leave from: the plan has both. What the service said stays.
        val bare = flight.replace("\"dep_terminal\": \"B\"", "\"dep_terminal\": null")
            .replace(Regex("\"arr_time(_utc)?\": \"[^\"]*\""), "\"arr_time$1\": null")
        val said = AirLabs.flight(bare).value!!
        assertNull(said.from.terminal); assertNull(said.to.time)
        val first = press(service("flight" to bare, "routes" to table), tuesday).flights[0]
        assertFalse(first.timetable)
        assertEquals("B", first.from.terminal)
        assertEquals(time("2026-10-06T11:26"), first.to.planned)
        assertEquals(at("2026-10-06T15:26:00Z"), first.to.moment(first.to.planned!!))
        assertEquals(said.copy(from = said.from.copy(terminal = "B"), to = said.to.copy(planned = time("2026-10-06T11:26"), offset = -240)), first)
        assertEquals(listOf("48", "A", "20", "3"), listOf(first.from.gate, first.to.terminal, first.to.gate, first.to.belt))
    }

    @Test fun aFlightTheTimetableHasNoLineForIsAmongThemAllTheSame() {
        // The timetable without its line from Orlando: the service's flight from there leaves on that day, and is one of the day's.
        val rows = Json.parseToJsonElement(table).jsonObject.getValue("response").jsonArray
        val without = """{"response":[${rows[0]},${rows[2]},${rows[3]}]}"""
        assertEquals(3, AirLabs.routes(without).value!!.size)
        for (day in listOf(tuesday, null)) {
            val c = press(service("flight" to flight, "routes" to without), day)
            assertEquals("$day", listOf("MCO-EWR 2026-10-06T08:45", "EWR-SFO 2026-10-06T13:20", "SFO-PDX 2026-10-06T19:05"), legs(c))
            assertEquals("$day", AirLabs.flight(flight).value, c.flights[0])
        }
    }

    @Test fun hoursAfterAFlightLandedTheComingHoursFlightIsFirstAsItself() {
        // Say the one-flight question answers, an hour later, with the evening's flight, which landed over three hours before:
        // the next is among the coming hours' flights, and the timetable is asked for besides. Three requests, the most there are.
        val s = service("flight" to landed, "schedules" to soon, "routes" to table)
        val c = press(s, now = at("2026-10-06T07:00:00Z"))
        assertEquals(listOf("flight", "schedules", "routes"), s.asked)
        assertEquals(listOf("MCO-EWR 2026-10-06T08:45", "EWR-SFO 2026-10-06T13:20", "SFO-PDX 2026-10-06T19:05"), legs(c))
        assertEquals(listOf(false, true, true), c.flights.map { it.timetable })
        assertEquals(listOf("48", "3"), listOf(c.flights[0].from.gate, c.flights[0].to.belt))
        // The flight that landed is none of them: the one from San Francisco is this evening's.
        assertTrue(c.flights.none { it.state == FlightState.LANDED })
        // A coming hours' flight stands in its line's place too where it is not the first: here the one from Newark has a gate already.
        val both = soon.replaceFirst("\"response\": [", """"response": [{"flight_iata":"UA1227","flight_icao":"UAL1227","dep_iata":"EWR","arr_iata":"SFO","status":"scheduled",
            "dep_gate":"C92","dep_time":"2026-10-06 13:20","dep_time_utc":"2026-10-06 17:20","arr_time":"2026-10-06 16:19","arr_time_utc":"2026-10-06 23:19"},""")
        assertEquals(3, AirLabs.schedules(both).value!!.size)
        val d = press(service("flight" to landed, "schedules" to both, "routes" to table), now = at("2026-10-06T07:00:00Z"))
        assertEquals(legs(c), legs(d))
        assertEquals(listOf(false, false, true), d.flights.map { it.timetable })
        assertEquals("C92", d.flights[1].from.gate)
    }

    @Test fun aFlightThatLandedIsNotOneOfTheNextButStaysTheAnswerWhereThereIsNothingToChoose() {
        // Say the service answers, two hours after the evening's flight landed, with that flight: it is "the next" for three
        // hours, where nothing is asked. Here there are three that leave within a day, and it is not among them.
        val s = service("flight" to landed, "routes" to table)
        val c = press(s)
        assertEquals(listOf("flight", "routes"), s.asked)
        assertEquals(listOf("MCO-EWR 2026-10-06T08:45", "EWR-SFO 2026-10-06T13:20", "SFO-PDX 2026-10-06T19:05"), legs(c))
        assertTrue(c.flights.all { it.timetable })
        // A number that flies once a day: twenty minutes after it landed the one flight of the next day is no choice, and the answer is the one that landed.
        val lh455 = service("flight" to reply("flight-LH455-landed"), "routes" to reply("routes-LH455"))
        val one = press(lh455, now = at("2026-10-02T08:16:00Z"), n = FlightNumber("LH", 455)).flights.single()
        assertEquals(FlightState.LANDED, one.state)
        assertEquals(listOf("flight", "routes"), lh455.asked)
    }

    @Test fun aFlightThatWasCanceledIsFirstAmongTheNextAsItHasNotLeft() {
        val canceled = flight.replace("\"scheduled\"", "\"cancelled\"")
        val c = press(service("flight" to canceled, "routes" to table))
        assertEquals(listOf("MCO-EWR 2026-10-06T08:45", "EWR-SFO 2026-10-06T13:20", "SFO-PDX 2026-10-06T19:05"), legs(c))
        assertEquals(FlightState.CANCELED, c.flights[0].state)
        // An hour after it was to leave it is still the service's answer, and still first: whoever asks should hear of it.
        // The timetable would offer tomorrow's from Orlando in its place, and call it planned.
        val after = press(service("flight" to canceled, "routes" to table), now = at("2026-10-06T13:45:00Z"))
        assertEquals(legs(c), legs(after))
        assertEquals(FlightState.CANCELED, after.flights[0].state)
    }

    // ---- one flight is no question

    @Test fun oneFlightThatDayIsTheAnswerAsItAlwaysWas() {
        val lh455 = FlightNumber("LH", 455)
        val asked = at("2026-10-02T07:29:00Z")
        fun both() = service("flight" to reply("flight-LH455-in-the-air"), "routes" to reply("routes-LH455"))
        // The next flight, a day chip that is its day, and a day chip that is the timetable's: one each, and the one the lookup finds.
        for (day in listOf(null, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 3))) {
            val s = both()
            val c = press(s, day, la, asked, lh455)
            val one = AirLabs.lookup(lh455, day, asked, la.takeIf { day != null }, both()::get)!!.flight
            assertEquals("$day", listOf(one), c.flights)
            assertNull(c.failure)
            // The timetable is asked for in every case: it is what says that there is nothing to choose.
            assertEquals("$day", listOf("flight", "routes"), s.asked)
        }
        assertEquals(FlightState.IN_AIR, press(both(), null, la, asked, lh455).flights.single().state)
        assertTrue(press(both(), LocalDate.of(2026, 10, 3), la, asked, lh455).flights.single().timetable)
    }

    @Test fun aNumberThatFliesOnceADayAsksNothingWhileItsFlightIsInTheAirOrLate() {
        // LH 455 is in the air, and tomorrow's leaves in fourteen hours: within a day, and still no second flight to choose.
        // The flight the service answers with stands for its route.
        val air = press(service("flight" to reply("flight-LH455-in-the-air"), "routes" to reply("routes-LH455")), now = at("2026-10-02T07:29:00Z"), n = FlightNumber("LH", 455))
        assertEquals(listOf("SFO-FRA 2026-10-01T14:47"), legs(air))
        // LH 454 was to leave Frankfurt a quarter of an hour ago and has not: the timetable's next is tomorrow's, and it is no choice either.
        val daily = """{"response":[{"flight_iata":"LH454","flight_icao":"DLH454","dep_iata":"FRA","arr_iata":"SFO","dep_time":"10:25","dep_time_utc":"08:25",
            "arr_time":"12:40","arr_time_utc":"19:40","duration":675,"days":["mon","tue","wed","thu","fri","sat","sun"]}]}"""
        val late = press(service("flight" to reply("flight-LH454-planned"), "routes" to daily), now = at("2026-10-02T08:40:00Z"), n = FlightNumber("LH", 454))
        assertEquals(listOf("FRA-SFO 2026-10-02T10:25"), legs(late))
        assertFalse(late.flights.single().timetable)
        // With a second route it is a question, and the flight in the air is first.
        val two = daily.replace("\"dep_iata\":\"FRA\",\"arr_iata\":\"SFO\"", "\"dep_iata\":\"FRA\",\"arr_iata\":\"MUC\"").replace("DLH454", "DLH455")
        val c = press(service("flight" to reply("flight-LH455-in-the-air"), "routes" to reply("routes-LH455").replaceFirst("\"response\": [", "\"response\": [" + two.substringAfter("[").substringBeforeLast("]") + ",")),
            now = at("2026-10-02T07:29:00Z"), n = FlightNumber("LH", 455))
        assertEquals(listOf("SFO-FRA 2026-10-01T14:47", "FRA-MUC 2026-10-02T10:25"), legs(c))
    }

    @Test fun aRouteWhoseTimetableHasALineForEachKindOfDayHasOneNextFlight() {
        // A timetable can have several lines for one route: this one leaves AAA at 15:10 on Tuesdays and at 14:40 on Wednesdays.
        // Five minutes before Tuesday's, both leave within a day, and "the next flight" of the route is still one: Tuesday's.
        fun line(from: String, to: String, time: String, day: String) = """{"flight_iata":"XX12","dep_iata":"$from","arr_iata":"$to","dep_time":"$time","dep_time_utc":"$time",
            "arr_time":"23:00","arr_time_utc":"23:00","duration":60,"days":["$day"]}"""
        val oneRoute = """{"response":[${line("AAA", "BBB", "14:40", "wed")},${line("AAA", "BBB", "15:10", "tue")}]}"""
        val now = at("2026-10-06T15:05:00Z")
        val one = press(service("flight" to unknown, "routes" to oneRoute), now = now, n = FlightNumber("XX", 12))
        assertEquals(listOf("AAA-BBB 2026-10-06T15:10"), legs(one))
        // With a second route it is a question between the two routes' next flights, and Wednesday's is not a third.
        val twoRoutes = oneRoute.replace("]}", ",${line("BBB", "CCC", "20:00", "tue")}]}")
        assertEquals(3, AirLabs.routes(twoRoutes).value!!.size)
        val two = press(service("flight" to unknown, "routes" to twoRoutes), now = now, n = FlightNumber("XX", 12))
        assertEquals(listOf("AAA-BBB 2026-10-06T15:10", "BBB-CCC 2026-10-06T20:00"), legs(two))
        // On a day chip each line counts for the days it flies: Wednesday's is the 14:40.
        val wednesday = press(service("flight" to unknown, "routes" to twoRoutes), tuesday.plusDays(1), ZoneId.of("UTC"), now, FlightNumber("XX", 12))
        assertEquals(listOf("AAA-BBB 2026-10-07T14:40"), legs(wednesday))
    }

    @Test fun twoFlightsThatAreTheSameFlightAreOfferedOnce() {
        // A number that leaves one airport twice on one day is one flight to the rule that follows it (the airport and the
        // planned day), so the second could not be followed apart from the first. With another airport's flight: two to choose from.
        val twice = """{"response":[
            {"flight_iata":"XX12","dep_iata":"AAA","arr_iata":"BBB","dep_time":"18:00","dep_time_utc":"18:00","arr_time":"19:00","arr_time_utc":"19:00","duration":60},
            {"flight_iata":"XX12","dep_iata":"AAA","arr_iata":"BBB","dep_time":"08:00","dep_time_utc":"08:00","arr_time":"09:00","arr_time_utc":"09:00","duration":60},
            {"flight_iata":"XX12","dep_iata":"CCC","arr_iata":"AAA","dep_time":"12:00","dep_time_utc":"12:00","arr_time":"13:00","arr_time_utc":"13:00","duration":60}]}"""
        val c = press(service("flight" to unknown, "routes" to twice), tuesday, ZoneId.of("UTC"), n = FlightNumber("XX", 12))
        assertEquals(listOf("AAA-BBB 2026-10-06T08:00", "CCC-AAA 2026-10-06T12:00"), legs(c))
        for (a in c.flights) for (b in c.flights) assertEquals(a === b, FlightRules.same(a, b))
    }

    @Test fun aTimetableThatSaysTheNumberFliesFortyTimesADayIsNotBelievedBeyondAMenusLength() {
        // Nothing of a reply is trusted to be short: forty lines from forty airports, one every half hour, are a list no menu shows.
        val forty = (0 until 40).joinToString(",", """{"response":[""", "]}") { i ->
            val from = "A" + ('A' + i / 26) + ('A' + i % 26)
            val time = "%02d:%02d".format(i / 2, i % 2 * 30)
            """{"flight_iata":"XX12","dep_iata":"$from","arr_iata":"ZZZ","dep_time":"$time","dep_time_utc":"$time","arr_time":"23:59","arr_time_utc":"23:59","duration":20}"""
        }
        assertEquals(40, AirLabs.routes(forty).value!!.size)
        for (day in listOf(tuesday, null)) {
            val c = press(service("flight" to unknown, "routes" to forty), day, ZoneId.of("UTC"), at("2026-10-05T23:59:00Z"), FlightNumber("XX", 12))
            // The first to leave, in their order.
            assertEquals("$day", AirLabs.MOST_CANDIDATES, c.flights.size)
            assertEquals("$day", listOf("AAA-ZZZ 2026-10-06T00:00", "AAB-ZZZ 2026-10-06T00:30", "AAC-ZZZ 2026-10-06T01:00"), legs(c).take(3))
        }
        assertEquals(12, AirLabs.MOST_CANDIDATES)
    }

    // ---- what went wrong, and what a press costs

    @Test fun whenTheTimetableCannotBeHadTheFlightThatWasFoundIsTheAnswer() {
        val found = AirLabs.flight(flight).value
        // For the second request: no connection, told to slow down, a reply nobody can read, the month's lookups used up by the first,
        // a timetable with nothing in it.
        val spent = """{"error":{"message":"x","code":"month_limit_exceeded"}}"""
        val tries: List<(Request) -> Reply> = listOf(
            { r -> if (r.path == AirLabs.FLIGHT) Reply.Ok(flight) else Reply.Failed(Why.OFFLINE) },
            { r -> if (r.path == AirLabs.FLIGHT) Reply.Ok(flight) else Reply.Failed(Why.STATUS, 429) },
            { r -> Reply.Ok(if (r.path == AirLabs.FLIGHT) flight else "<html>502</html>") },
            { r -> Reply.Ok(if (r.path == AirLabs.FLIGHT) flight else spent) },
            { r -> Reply.Ok(if (r.path == AirLabs.FLIGHT) flight else none) },
        )
        for ((i, get) in tries.withIndex()) for (day in listOf(null, tuesday)) {
            val c = AirLabs.candidates(ua1227, day, evening, la.takeIf { day != null }, get)!!
            assertEquals("$i $day", listOf(found), c.flights)
            assertNull("$i $day", c.failure)
        }
        // A flight that is not the day's is no answer, and why the timetable was not to be had is said, as it always was.
        val cut = service("flight" to flight)
        val c = press(cut, tuesday.plusDays(1))
        assertEquals(emptyList<Flight>(), c.flights)
        assertEquals(Failure.OFFLINE, c.failure)
        assertEquals(listOf("flight", "routes"), cut.asked)
        // A request that was not sent at all is nothing to say: also when the flight was found before it.
        assertNull(AirLabs.candidates(ua1227, null, evening) { r -> if (r.path == AirLabs.FLIGHT) Reply.Ok(flight) else Reply.Failed(Why.OFF) })
        assertNull(AirLabs.candidates(ua1227, null, evening) { Reply.Failed(Why.OFF) })
    }

    @Test fun aNumberOnlyTheTimetableKnowsIsAQuestionToo() {
        val s = service("flight" to unknown, "routes" to table)
        val c = press(s, tuesday)
        assertEquals(listOf("flight", "routes"), s.asked)
        assertEquals(listOf("MCO-EWR 2026-10-06T08:45", "EWR-SFO 2026-10-06T13:20", "SFO-PDX 2026-10-06T19:05"), legs(c))
        // Nobody named an airline or a city: the letters. The timetable has the callsign.
        assertEquals(listOf("MCO" to "EWR", "EWR" to "SFO", "SFO" to "PDX"), c.flights.map { it.from.place to it.to.place })
        assertTrue(c.flights.all { it.timetable && it.airline == "" && it.number == "UA1227" && it.callsign == "UAL1227" })
        assertEquals(legs(c), legs(press(service("flight" to unknown, "routes" to table))))
    }

    @Test fun whatWentWrongIsWhatItAlwaysWas() {
        fun failed(s: Service, day: LocalDate? = null): Failure? = press(s, day).also { assertEquals(emptyList<Flight>(), it.flights) }.failure
        // Nobody knows the number; the timetable that would have said so is not to be had; the key is not accepted; no connection at all.
        assertEquals(Failure.NOT_FOUND, failed(service("flight" to unknown, "routes" to none)))
        assertEquals(Failure.OFFLINE, failed(service("flight" to unknown)))
        val refused = service("flight" to reply("error-unknown-key"), "routes" to table)
        assertEquals(Failure.REFUSED, failed(refused))
        assertEquals(listOf("flight"), refused.asked)
        val offline = service()
        assertEquals(Failure.OFFLINE, failed(offline, tuesday))
        assertEquals(listOf("flight"), offline.asked)
        // A day none of its lines flies on: the Monday flight from Raleigh alone, asked for on a Tuesday.
        val mondays = """{"response":[""" + Json.parseToJsonElement(table).jsonObject.getValue("response").jsonArray[2] + "]}"
        assertEquals(Failure.NOT_THAT_DAY, failed(service("flight" to flight, "routes" to mondays), tuesday.plusDays(1)))
        assertEquals(Failure.NOT_THAT_DAY, failed(service("flight" to unknown, "routes" to mondays), tuesday))
    }

    @Test fun whatAPressOfTrackCosts() {
        fun asked(s: Service, day: LocalDate? = null, now: Instant = evening): List<String> { press(s, day, now = now); return s.asked }
        // One flight found, several found, a day chosen: the one flight and the timetable.
        assertEquals(listOf("flight", "routes"), asked(thatEvening()))
        assertEquals(listOf("flight", "routes"), asked(thatEvening(), tuesday))
        assertEquals(listOf("flight", "routes"), asked(thatEvening(), tuesday.plusDays(3)))
        // Not found: the same two, as before.
        assertEquals(listOf("flight", "routes"), asked(service("flight" to unknown, "routes" to none)))
        // The next flight, hours after the last one landed: the coming hours' too. Never more.
        assertEquals(listOf("flight", "schedules", "routes"), asked(service("flight" to landed, "schedules" to soon, "routes" to table), now = at("2026-10-06T07:00:00Z")))
        assertEquals(listOf("flight", "schedules", "routes"), asked(service("flight" to landed, "schedules" to none, "routes" to table), now = at("2026-10-06T07:00:00Z")))
        // A key that is not accepted, or no connection: one, and nothing after it.
        assertEquals(listOf("flight"), asked(service("flight" to reply("error-unknown-key"))))
        assertEquals(listOf("flight"), asked(service()))
    }

    @Test fun howManyLookupsAreLeftIsWhatTheLastReplySaid() {
        fun counted(text: String, left: Int) = text.replaceFirst("{", """{"request":{"key":{"limits_total":$left}},""")
        assertEquals(939, press(service("flight" to counted(flight, 940), "routes" to counted(table, 939)), tuesday).left)
        assertEquals(940, press(service("flight" to counted(flight, 940), "routes" to table), tuesday).left)
        assertEquals(940, press(service("flight" to counted(flight, 940))).left)
    }

    @Test fun aCallsignIsAskedForAsOneAndTheTimetableByTheTicketsNumber() {
        val asked = ArrayList<Request>()
        val c = AirLabs.candidates(FlightNumber("UAL", 1227), tuesday, evening, la) { r -> asked += r; Reply.Ok(if (r.path == AirLabs.FLIGHT) flight else table) }!!
        assertEquals(3, c.flights.size)
        assertEquals(listOf(AirLabs.FLIGHT to ("flight_icao" to "UAL1227"), AirLabs.ROUTES to ("flight_iata" to "UA1227")), asked.map { it.path to it.query.first() })
        for (r in asked) assertEquals(listOf(r.query.first().first), r.query.map { it.first })
    }

    // ---- what the menu says of them

    private val us = FlightVoices.us(la)

    @Test fun theCardSaysWhatIsAsked() {
        // A day chip: the day that was chosen, which is the device's. "Next flight": the hours the rule looks ahead.
        assertEquals("UA 1227 flies 3 times on Tue, Oct 6", FlightText.several("UA 1227", tuesday, 3, us))
        assertEquals("UA 1227 flies 4 times on Mon, Oct 5", FlightText.several("UA 1227", monday, 4, us))
        assertEquals("UA 1227: 3 flights in the next 24 hours", FlightText.several("UA 1227", null, 3, us))
        assertEquals("UA 1227: 2 flights in the next 24 hours", FlightText.several("UA 1227", null, 2, us))
        assertEquals(Duration.ofHours(24), AirLabs.DAY_AHEAD)
        // As the counts are written, should there ever be one.
        assertEquals("1 time", us.count(FlightText.Count.FLIGHT_CHOICE_TIMES, 1))
        assertEquals("1 flight", us.count(FlightText.Count.FLIGHT_CHOICE_FLIGHTS, 1))
    }

    @Test fun eachFlightToChooseFromIsItsRouteAndItsTimesAtItsOwnAirports() {
        // "Tomorrow", that Monday evening in Los Angeles: the day stands in front of the time it leaves.
        val flights = press(thatEvening(), tuesday).flights
        val rows = flights.map { FlightText.choice(it, evening, us) }
        // The cities where the service named them, else the airport's letters.
        assertEquals(listOf("Orlando → Newark", "Newark → SFO", "SFO → PDX"), rows.map { it.title })
        assertEquals(listOf("Tue 8:45 AM – 11:26 AM", "Tue 1:20 PM – 4:19 PM", "Tue 7:05 PM – 9:00 PM"), rows.map { it.detail })
        // A screen reader gets one sentence for each, with the day's whole name: "Tue" is not a word everyone's reader says as one.
        assertEquals(listOf("Orlando to Newark, leaves Tuesday 8:45 AM, lands 11:26 AM", "Newark to SFO, leaves Tuesday 1:20 PM, lands 4:19 PM", "SFO to PDX, leaves Tuesday 7:05 PM, lands 9:00 PM"),
            rows.map { it.spoken })
        // "Next flight" at that moment is the same three, and reads the same.
        assertEquals(rows, press(thatEvening()).flights.map { FlightText.choice(it, evening, us) })
        // On the day itself, an hour past midnight in Los Angeles, no day is said.
        val today = flights.map { FlightText.choice(it, at("2026-10-06T08:00:00Z"), us) }
        assertEquals(listOf("8:45 AM – 11:26 AM", "1:20 PM – 4:19 PM", "7:05 PM – 9:00 PM"), today.map { it.detail })
        assertEquals("Orlando to Newark, leaves 8:45 AM, lands 11:26 AM", today[0].spoken)
        // With 24 hours on the device.
        assertEquals("Tue 19:05 – 21:00", FlightText.choice(flights[2], evening, FlightVoices.us(la, h24 = true)).detail)
        assertEquals("SFO to PDX, leaves Tuesday 19:05, lands 21:00", FlightText.choice(flights[2], evening, FlightVoices.us(la, h24 = true)).spoken)
        assertEquals("Tuesday 8:45 AM", us.time(time("2026-10-06T08:45"), FlightText.TimeForm.WEEKDAY_TIME))
        // Nobody named a city: the letters, for both ends.
        val bare = press(service("flight" to unknown, "routes" to table), tuesday).flights.map { FlightText.choice(it, evening, us) }
        assertEquals(listOf("MCO → EWR", "EWR → SFO", "SFO → PDX"), bare.map { it.title })
        assertEquals("MCO to EWR, leaves Tuesday 8:45 AM, lands 11:26 AM", bare[0].spoken)
        // "Today", that Monday: the four, and no day in front of them.
        val four = press(thatEvening(), monday).flights.map { FlightText.choice(it, evening, us) }
        assertEquals(listOf("Orlando → Newark", "RDU → Newark", "Newark → SFO", "SFO → PDX"), four.map { it.title })
        assertEquals(listOf("8:45 AM – 11:26 AM", "9:10 AM – 11:47 AM", "1:20 PM – 4:19 PM", "7:05 PM – 9:00 PM"), four.map { it.detail })
        assertEquals("RDU to Newark, leaves 9:10 AM, lands 11:47 AM", four[1].spoken)
    }

    @Test fun onceItIsChosenAFlightReadsAsAnyFlightThatIsFollowed() {
        // The third of Tuesday's, as an item holds it from the moment it was chosen: a plan from the timetable, a day ahead.
        val third = press(thatEvening(), tuesday).flights[2]
        val ms = evening.toEpochMilli()
        val t = FlightRules.Tracked("UA1227", "2026-10-06", third, askedAt = ms, heardAt = ms, from = "SFO")
        val card = FlightText.card(t, evening, us)!!
        assertEquals(listOf("UA 1227 · United Airlines", "SFO → PDX", "Leaves Tue 7:05 PM", "Planned"), listOf(card.title, card.route, card.headline, card.badge))
        // In the bar the number closes up to make room for the day.
        assertEquals("UA1227 · Tue 7:05 PM", FlightText.bar(t, null, evening, 24, us).text)
        // And while the choice was open, the number as it is while it is looked up.
        assertEquals("UA 1227 …", FlightText.bar(FlightRules.Tracked(), "UA 1227", evening, 24, us).text)
        assertEquals("Looking up UA 1227", FlightText.bar(FlightRules.Tracked(), "UA 1227", evening, 24, us).desc)
        // The plane it is drawn with is its own: the same number and day from another airport is another flight's.
        assertEquals("UA1227 2026-10-06 SFO", FlightText.plane(t))
    }

    @Test fun theDayInFrontIsTheAirportsOwnWhereItIsNotTheDevicesToday() {
        // Tokyo, Tuesday afternoon, "Today": two of its flights leave on Monday by their airports' clocks, and say so.
        val there = FlightVoices.us(tokyo)
        val rows = press(thatEvening(), tuesday, tokyo).flights.map { FlightText.choice(it, evening, there) }
        assertEquals(listOf("Newark → SFO", "SFO → PDX", "Orlando → Newark"), rows.map { it.title })
        assertEquals(listOf("Mon 1:20 PM – 4:19 PM", "Mon 7:05 PM – 9:00 PM", "8:45 AM – 11:26 AM"), rows.map { it.detail })
        // The day that is asked about is the device's, whatever the airports call it.
        assertEquals("UA 1227 flies 3 times on Tue, Oct 6", FlightText.several("UA 1227", tuesday, 3, there))
        // More than six days off it is a date.
        val first = press(thatEvening(), tuesday).flights[0]
        val far = first.copy(from = first.from.copy(planned = time("2026-10-13T08:45")), to = first.to.copy(planned = time("2026-10-13T11:26")))
        assertEquals("Oct 13, 8:45 AM – 11:26 AM", FlightText.choice(far, evening, us).detail)
        assertEquals("Orlando to Newark, leaves Oct 13, 8:45 AM, lands 11:26 AM", FlightText.choice(far, evening, us).spoken)
    }

    @Test fun aFlightToChooseFromSaysTheTimesThatAreKnownOfIt() {
        val first = press(thatEvening(), tuesday).flights[0]
        // Late: the time it is now expected to leave, as its card will say once it is followed.
        val late = first.copy(from = first.from.copy(expected = time("2026-10-06T09:30")), to = first.to.copy(expected = time("2026-10-06T12:10")))
        assertEquals("Tue 9:30 AM – 12:10 PM", FlightText.choice(late, evening, us).detail)
        // No time of landing: when it leaves, alone.
        val open = first.copy(to = first.to.copy(planned = null))
        assertEquals("Tue 8:45 AM", FlightText.choice(open, evening, us).detail)
        assertEquals("Orlando to Newark, leaves Tuesday 8:45 AM", FlightText.choice(open, evening, us).spoken)
        // No time to leave: no times, for a time of landing alone would read as the other.
        val blank = first.copy(from = first.from.copy(planned = null))
        assertEquals("", FlightText.choice(blank, evening, us).detail)
        assertEquals("Orlando to Newark, lands 11:26 AM", FlightText.choice(blank, evening, us).spoken)
        assertEquals("Orlando → Newark", FlightText.choice(blank, evening, us).title)
    }
}
