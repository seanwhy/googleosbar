package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.items.AirLabs.Failure
import com.meridian11.books.bentobar.items.FlightRules.DayForm
import com.meridian11.books.bentobar.items.FlightRules.Said
import com.meridian11.books.bentobar.items.FlightRules.Saying
import com.meridian11.books.bentobar.items.FlightRules.Stage
import com.meridian11.books.bentobar.items.FlightRules.Standing
import com.meridian11.books.bentobar.items.FlightRules.Tracked
import com.meridian11.books.bentobar.net.Reply
import com.meridian11.books.bentobar.net.Request
import com.meridian11.books.bentobar.net.Why
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The rules of flights that are easy to get wrong, each with the case that was. The replies are the
 * saved ones of `resources/airlabs`; where a case needs a reply nobody saved (a canceled row in
 * `schedules`, a timetable with two lines, an airport far from UTC), it is written here in the
 * service's own form.
 */
class FlightRulesTest {
    private fun reply(name: String): String = javaClass.getResource("/airlabs/$name.json")!!.readText()
    private fun flight(name: String): Flight = AirLabs.flight(reply(name)).value!!
    private fun at(text: String): Instant = Instant.parse(text)
    private fun time(text: String): LocalDateTime = LocalDateTime.parse(text)
    private val lh455 = FlightNumber("LH", 455)
    private val none = """{"response":[]}"""

    /** A saved reply with every date in it moved on by [days]: the same flight on another day. */
    private fun later(reply: String, days: Long): String =
        Regex("\\d{4}-\\d{2}-\\d{2}").replace(reply) { LocalDate.parse(it.value).plusDays(days).toString() }

    private class Service(private val replies: Map<String, String>) {
        val asked = ArrayList<String>()
        fun get(request: Request): Reply {
            val what = request.path.substringAfterLast('/')
            asked.add(what)
            return replies[what]?.let { Reply.Ok(it) } ?: Reply.Failed(Why.OFFLINE)
        }
    }

    private fun lookup(n: FlightNumber, now: Instant, day: LocalDate? = null, get: (Request) -> Reply): AirLabs.Answer = AirLabs.lookup(n, day, now, get = get)!!
    /** [was] asked about again a day before it is to leave: more than ten hours, so the one-flight question's turn, which these tests read. */
    private fun again(was: Flight, get: (Request) -> Reply): AirLabs.Asked = AirLabs.again(lh455, was, dayBefore(was), get)!!
    private fun dayBefore(f: Flight): Instant = f.from.moment(f.from.planned!!).minus(Duration.ofDays(1))
    private fun ok(text: String): (Request) -> Reply = { Reply.Ok(text) }

    private fun line(from: String, to: String, leaves: String, utc: String, lands: String, landsUtc: String, minutes: Int, days: String) =
        """{"flight_iata":"XX12","dep_iata":"$from","arr_iata":"$to","dep_time":"$leaves","dep_time_utc":"$utc","arr_time":"$lands","arr_time_utc":"$landsUtc","duration":$minutes,"days":[$days]}"""
    private fun lines(vararg l: String) = AirLabs.routes("""{"response":[${l.joinToString(",")}]}""").value!!
    private val daily = "\"mon\",\"tue\",\"wed\",\"thu\",\"fri\",\"sat\",\"sun\""

    // ---- the next flight

    @Test fun aCanceledNextFlightIsCanceledNotTheTimetablesPlan() {
        val jl101 = FlightNumber("JL", 101)
        val canceled = """{"response":[{"flight_iata":"JL101","dep_iata":"HND","arr_iata":"ITM","status":"cancelled",
            "dep_time":"2026-10-03 06:30","dep_time_utc":"2026-10-02 21:30","arr_time":"2026-10-03 07:35","arr_time_utc":"2026-10-02 22:35"}]}"""
        // The last one landed hours ago, the next is canceled, and the timetable knows of a flight every day.
        val s = Service(mapOf("flight" to reply("flight-JL101-landed-nine-hours-ago"), "schedules" to canceled,
            "routes" to """{"response":[${line("HND", "ITM", "06:30", "21:30", "07:35", "22:35", 65, daily)}]}"""))
        val a = lookup(jl101, at("2026-10-02T12:00:00Z"), get = s::get).flight!!
        assertEquals(listOf("flight", "schedules"), s.asked)
        assertEquals(FlightState.CANCELED, a.state)
        assertFalse(a.timetable)
        assertEquals(time("2026-10-03T06:30"), a.from.planned)
        assertEquals("Tokyo", a.from.city)
        assertEquals(Said(Saying.CANCELED), FlightRules.said(a, at("2026-10-02T12:00:00Z")))
        // Three hours after it was to leave it is over, and the one after it is the next.
        assertNull(FlightRules.next(AirLabs.schedules(canceled).value!!, at("2026-10-03T01:00:00Z")))
    }

    @Test fun theTimetablesNextFlightIsTheSoonestOfItsLinesNotTheFirstLines() {
        val like = flight("flight-LH455-in-the-air")
        val both = lines(
            line("SFO", "FRA", "14:40", "21:40", "10:25", "08:25", 645, "\"mon\",\"tue\",\"wed\",\"thu\",\"fri\""),
            line("SFO", "FRA", "15:10", "22:10", "10:55", "08:55", 645, "\"sat\",\"sun\""),
        )
        // Saturday morning in San Francisco: today's 15:10, not Monday's 14:40.
        assertEquals(time("2026-10-03T15:10"), AirLabs.upcoming(both, like, at("2026-10-03T12:00:00Z"))!!.from.planned)
        assertEquals(time("2026-10-03T15:10"), AirLabs.upcoming(both.reversed(), like, at("2026-10-03T12:00:00Z"))!!.from.planned)
        // On Sunday evening, after the 15:10 has gone: Monday's.
        assertEquals(time("2026-10-05T14:40"), AirLabs.upcoming(both, like, at("2026-10-04T23:00:00Z"))!!.from.planned)
        // A number with two legs stays on the leg the last flight was on, though the other leaves sooner.
        val legs = lines(
            line("FRA", "JFK", "08:35", "06:35", "11:10", "15:10", 515, daily),
            line("SFO", "FRA", "14:40", "21:40", "10:25", "08:25", 645, daily),
        )
        assertEquals("SFO", AirLabs.upcoming(legs, like, at("2026-10-03T00:00:00Z"))!!.from.code)
        // With no leg of its own in the timetable: the soonest of the others.
        assertEquals("FRA", AirLabs.upcoming(legs.take(1), like, at("2026-10-03T00:00:00Z"))!!.from.code)
    }

    // ---- clocks

    @Test fun aClockFarFromUtcIsReadTheSameAtEveryHourOfTheDay() {
        // Honolulu is ten hours behind UTC. Said without a day, 22:00 there and 08:00 UTC are fourteen hours apart the other way.
        val hawaii = lines(
            line("HNL", "LAX", "22:00", "08:00", "06:30", "13:30", 330, daily),
            line("HNL", "LAX", "08:00", "18:00", "16:30", "23:30", 330, daily),
        )
        assertEquals(listOf(-600, -600), hawaii.map { it.fromOffset })
        assertEquals(listOf(-420, -420), hawaii.map { it.toOffset })
        // Auckland in summer is thirteen ahead; Fiji twelve.
        val south = lines(
            line("AKL", "SYD", "07:00", "18:00", "08:35", "21:35", 215, daily),
            line("AKL", "SYD", "19:00", "06:00", "20:35", "09:35", 215, daily),
            line("NAN", "AKL", "09:00", "21:00", "13:00", "00:00", 180, daily),
        )
        assertEquals(listOf(780, 780, 720), south.map { it.fromOffset })
        assertEquals(listOf(660, 660, 780), south.map { it.toOffset })
        // The late flight from Honolulu leaves on Friday and lands on Saturday, after it left.
        val stub = Flight("XX12", "", FlightEnd(""), FlightEnd(""), FlightState.PLANNED)
        val f = AirLabs.planned(hawaii[0], stub, LocalDate.of(2026, 10, 2))!!
        assertEquals(time("2026-10-02T22:00"), f.from.planned)
        assertEquals(time("2026-10-03T06:30"), f.to.planned)
        assertEquals(at("2026-10-03T08:00:00Z"), f.from.moment(f.from.planned!!))
        // At two in the morning on Friday in Honolulu tonight's flight is still to come.
        assertEquals(time("2026-10-02T22:00"), AirLabs.upcoming(hawaii.take(1), stub, at("2026-10-02T12:00:00Z"))!!.from.planned)
        // Ordinary clocks are read as before.
        val usual = AirLabs.routes(reply("routes-LH455")).value!![0]
        assertEquals(-420, usual.fromOffset); assertEquals(120, usual.toOffset)
    }

    @Test fun anAirportsClockFromADatedReplyWinsOverTheTimetablesGuess() {
        // Pago Pago is eleven behind, which the timetable alone reads as thirteen ahead.
        val line = lines(line("PPG", "HNL", "23:20", "10:20", "05:50", "15:50", 330, daily))[0]
        assertEquals(780, line.fromOffset)
        val like = Flight("XX12", "Some Air", FlightEnd("PPG", "Pago Pago", planned = time("2026-10-01T23:20"), offset = -660), FlightEnd("HNL", "Honolulu", planned = time("2026-10-02T05:50"), offset = -600), FlightState.LANDED)
        val f = AirLabs.planned(line, like, LocalDate.of(2026, 10, 5))!!
        assertEquals(-660, f.from.offset)
        assertEquals(time("2026-10-06T05:50"), f.to.planned)
        assertEquals("Pago Pago", f.from.city)
    }

    @Test fun aTimetablesFlightAfterAClockChangeIsMarkedAndOneBeforeItIsNot() {
        val like = flight("flight-LH455-in-the-air")
        val route = AirLabs.routes(reply("routes-LH455")).value!![0]
        val now = at("2026-10-02T07:29:00Z")
        // Europe's clocks go back on 25 October 2026, America's on 1 November.
        assertTrue(AirLabs.steady(AirLabs.planned(route, like, LocalDate.of(2026, 10, 3))!!, now))
        assertTrue(AirLabs.steady(AirLabs.planned(route, like, LocalDate.of(2026, 10, 23))!!, now))
        assertFalse(AirLabs.steady(AirLabs.planned(route, like, LocalDate.of(2026, 10, 28))!!, now))      // Frankfurt's has changed
        assertFalse(AirLabs.steady(AirLabs.planned(route, like, LocalDate.of(2026, 11, 5))!!, now))       // both have
        // The lookup says so on the flight.
        fun asked(day: LocalDate): Flight {
            val s = Service(mapOf("flight" to reply("flight-LH455-in-the-air"), "routes" to reply("routes-LH455")))
            return lookup(lh455, now, day, s::get).flight!!
        }
        assertFalse(asked(LocalDate.of(2026, 10, 3)).loose)
        assertTrue(asked(LocalDate.of(2026, 10, 28)).loose)
        // A flight from that day's operations is never loose: its clocks came with its date.
        assertFalse(like.loose)
        assertTrue(AirLabs.steady(like, now))
    }

    // ---- what is said of a flight

    @Test fun aTimetablesFlightWhoseTimeHasPassedIsNotPlannedAnyMore() {
        val like = flight("flight-LH455-in-the-air")
        val route = AirLabs.routes(reply("routes-LH455")).value!![0]
        val f = AirLabs.planned(route, like, LocalDate.of(2026, 10, 3))!!          // leaves 21:40 UTC, lands 08:25 UTC the day after
        val before = at("2026-10-03T12:00:00Z")
        val during = at("2026-10-04T00:00:00Z")
        val after = at("2026-10-04T09:00:00Z")
        assertEquals(Said(Saying.PLANNED), FlightRules.said(f, before))
        assertEquals(Said(Saying.TIMETABLE), FlightRules.said(f, during))
        assertEquals(Said(Saying.TIMETABLE), FlightRules.said(f, after))
        assertEquals(listOf(false, true, true), listOf(before, during, after).map { FlightRules.departed(f, it) })
        assertEquals(listOf(false, false, true), listOf(before, during, after).map { FlightRules.arrived(f, it) })
        // A flight the service knows goes by what the service says, not by the clock.
        val planned = flight("flight-LH454-planned")
        assertFalse(FlightRules.departed(planned, at("2026-10-05T00:00:00Z")))
        assertEquals(Said(Saying.PLANNED), FlightRules.said(planned, at("2026-10-05T00:00:00Z")))
        // Through the lookup: a day that has passed, asked for by its date (as a flight is asked for again once its answer is gone).
        val s = Service(mapOf("flight" to reply("flight-LH455-in-the-air"), "routes" to reply("routes-LH455")))
        val old = lookup(lh455, at("2026-10-02T07:29:00Z"), LocalDate.of(2026, 9, 28), s::get).flight!!
        assertEquals(Said(Saying.TIMETABLE), FlightRules.said(old, at("2026-10-02T07:29:00Z")))
        assertEquals(Standing(Stage.LANDED), FlightRules.standing(old, at("2026-10-02T07:29:00Z")))
    }

    @Test fun aDayIsAWeekdayWithinSixDaysAndADateBeyond() {
        val today = LocalDate.of(2026, 10, 1)
        fun form(t: String) = FlightRules.dayForm(time(t), today)
        assertEquals(DayForm.NONE, form("2026-10-01T14:40"))
        assertEquals(DayForm.WEEKDAY, form("2026-10-02T10:25"))
        assertEquals(DayForm.WEEKDAY, form("2026-10-07T10:25"))
        assertEquals(DayForm.DATE, form("2026-10-08T14:40"))            // a week from today has today's weekday
        assertEquals(DayForm.DATE, form("2026-12-24T14:40"))
        assertEquals(DayForm.WEEKDAY, form("2026-09-30T23:10"))         // it left yesterday
        assertEquals(DayForm.DATE, form("2026-09-20T23:10"))
    }

    @Test fun howLongAnAnswerIsKept() {
        assertEquals(Duration.ofMinutes(2), AirLabs.keep(null))
        assertEquals(Duration.ofSeconds(10), AirLabs.keep(Failure.OFFLINE))
        // No answer is no reason to hurry: a "slow down", an error of the service's and a limit for the minute are all that.
        assertEquals(Duration.ofMinutes(2), AirLabs.keep(Failure.NO_ANSWER))
        // A number nobody flies costs two lookups to find out: it is not asked again for an hour.
        assertEquals(Duration.ofHours(1), AirLabs.keep(Failure.NOT_FOUND))
        assertEquals(Duration.ofHours(1), AirLabs.keep(Failure.NOT_THAT_DAY))
        assertEquals(Duration.ofMinutes(2), AirLabs.keep(Failure.REFUSED))
        assertEquals(Duration.ofMinutes(2), AirLabs.keep(Failure.USED_UP))
    }

    // ---- a flight that is followed

    @Test fun aFollowerThatSleptThroughTheLandingSaysLandedAndAsksNothing() {
        // (By the pace alone. A bar item asks once more on waking, see asleepThroughTheLandingItIsAskedAboutOnceOnWaking.)
        val air = flight("flight-LH455-in-the-air")                              // expected to land 08:01 UTC
        // Still in the air, and for three hours after the landing nobody saw: it counts down to nothing and goes on asking.
        assertEquals(Standing(Stage.IN_AIR, 0), FlightRules.standing(air, at("2026-10-02T11:00:00Z")))
        assertEquals(FlightRules.NEAR, FlightRules.pace(air, at("2026-10-02T11:00:00Z")))
        // The lid opens at nine in the morning in San Francisco: landed, at its last time, and nothing more to ask.
        assertEquals(Standing(Stage.LANDED), FlightRules.standing(air, at("2026-10-02T16:00:00Z")))
        assertNull(FlightRules.pace(air, at("2026-10-02T16:00:00Z")))
        // The same for one that was followed before it left and never heard of again.
        val planned = flight("flight-LH454-planned")                             // to land 19:40 UTC
        assertEquals(Standing(Stage.BEFORE, 0), FlightRules.standing(planned, at("2026-10-02T12:00:00Z")))
        assertEquals(Standing(Stage.LANDED), FlightRules.standing(planned, at("2026-10-02T22:41:00Z")))
        assertNull(FlightRules.pace(planned, at("2026-10-02T22:41:00Z")))
        assertNull(FlightRules.pace(flight("flight-LH96-landed"), at("2026-10-02T07:29:00Z")))
        assertNull(FlightRules.pace(flight("flight-LH1184-cancelled"), at("2026-10-02T07:29:00Z")))
    }

    @Test fun askingAgainIsOneRequestAboutTheSameFlightAndAnotherDaysFlightEndsIt() {
        val air = flight("flight-LH455-in-the-air")
        // The same flight, landed now.
        val landed = Service(mapOf("flight" to reply("flight-LH455-landed")))
        val a = again(air, landed::get).again
        assertTrue(a is AirLabs.Again.Is && a.flight.state == FlightState.LANDED)
        assertEquals(listOf("flight"), landed.asked)
        // The service has gone on to the next day's flight: that is not the flight that is followed.
        val next = Service(mapOf("flight" to later(reply("flight-LH455-in-the-air"), 1), "schedules" to none, "routes" to reply("routes-LH455")))
        assertEquals(AirLabs.Again.Gone, again(air, next::get).again)
        assertEquals(listOf("flight"), next.asked)
        // It knows the number no more, or refuses the key: nothing more to ask. No connection: try again.
        assertEquals(AirLabs.Again.Gone, again(air, ok(reply("error-not-found"))).again)
        assertEquals(AirLabs.Again.Gone, again(air, ok(reply("error-unknown-key"))).again)
        assertEquals(AirLabs.Again.Failed, again(air, Service(emptyMap())::get).again)
        assertEquals(AirLabs.Again.Failed, again(air, ok("<html>")).again)
        assertEquals(990, again(air, ok("""{"request":{"key":{"limits_total":990}},"error":{"message":"x","code":"not_found"}}""")).left)
        // Same day, same airport is the same flight; a day on is not; nor is another airport on the same day.
        assertTrue(FlightRules.same(air, flight("flight-LH455-landed")))
        assertFalse(FlightRules.same(air, AirLabs.flight(later(reply("flight-LH455-in-the-air"), 1)).value!!))
        assertFalse(FlightRules.same(air, air.copy(from = air.from.copy(code = "LAX"))))
    }

    @Test fun askingAgainSaysWhyItCameToNothing() {
        // What the note under a followed flight says depends on it.
        val air = flight("flight-LH455-in-the-air")
        assertEquals(Failure.OFFLINE, again(air, Service(emptyMap())::get).failure)
        assertEquals(Failure.NO_ANSWER, again(air, ok("<html>")).failure)
        assertEquals(Failure.NO_ANSWER, again(air) { Reply.Failed(Why.STATUS, 429, retryAfterSec = 600) }.failure)
        assertEquals(600L, again(air) { Reply.Failed(Why.STATUS, 429, retryAfterSec = 600) }.retryAfterSec)
        assertEquals(Failure.REFUSED, again(air, ok(reply("error-unknown-key"))).failure)
        assertEquals(Failure.USED_UP, again(air, ok("""{"error":{"message":"x","code":"month_limit_exceeded"}}""")).failure)
        assertNull(again(air, ok(reply("flight-LH455-landed"))).failure)
    }

    @Test fun aReplyThatNamesNoTimeToLeaveIsNoAnswerAndNotTheEndOfTheAsking() {
        val air = flight("flight-LH455-in-the-air")
        // The same flight, with its planned time left empty: nobody can tell which day's flight the reply is about.
        val bare = reply("flight-LH455-in-the-air").replace(Regex("\"dep_time\": \"[^\"]*\","), "\"dep_time\": null,")
        assertNull(AirLabs.flight(bare).value!!.from.planned)
        val asked = again(air, ok(bare))
        assertEquals(AirLabs.Again.Failed, asked.again)
        assertEquals(Failure.NO_ANSWER, asked.failure)
        // Another day's flight of the number is still the end of it: an item follows one flight.
        assertEquals(AirLabs.Again.Gone, again(air, ok(later(reply("flight-LH455-in-the-air"), 1))).again)
    }

    @Test fun aFollowedPlanFromTheTimetableWaitsForTheServiceToKnowItsDay() {
        val like = flight("flight-LH455-in-the-air")                              // left on 1 October
        val route = AirLabs.routes(reply("routes-LH455")).value!![0]
        val plan = AirLabs.planned(route, like, LocalDate.of(2026, 10, 5))!!      // leaves 5 October 21:40 UTC
        // Days off: nothing to ask. From ten hours before: every three hours. From three hours before, and in the air: every half hour.
        assertNull(FlightRules.pace(plan, at("2026-10-02T07:29:00Z")))
        assertNull(FlightRules.pace(plan, at("2026-10-05T11:39:00Z")))
        assertEquals(FlightRules.FAR, FlightRules.pace(plan, at("2026-10-05T11:40:00Z")))
        assertEquals(FlightRules.FAR, FlightRules.pace(plan, at("2026-10-05T18:39:00Z")))
        assertEquals(FlightRules.NEAR, FlightRules.pace(plan, at("2026-10-05T18:40:00Z")))
        assertEquals(FlightRules.NEAR, FlightRules.pace(plan, at("2026-10-06T02:00:00Z")))
        assertNull(FlightRules.pace(plan, at("2026-10-06T08:25:00Z")))          // its time to land: the plan is over
        // A flight the service knows is asked about however far off it is, but only every three hours until it is near.
        val known = flight("flight-LH454-planned")                                // leaves 2 October 08:25 UTC
        assertEquals(FlightRules.FAR, FlightRules.pace(known, at("2026-10-01T18:00:00Z")))
        assertEquals(FlightRules.NEAR, FlightRules.pace(known, at("2026-10-02T07:29:00Z")))
        // The service still answers with the flight before it: not yet. With its own day: that flight. With a later one: gone.
        assertEquals(AirLabs.Again.NotYet, again(plan, ok(reply("flight-LH455-in-the-air"))).again)
        assertEquals(AirLabs.Again.NotYet, again(plan, ok(reply("error-not-found"))).again)
        val day = again(plan, ok(later(reply("flight-LH455-in-the-air"), 4))).again
        assertTrue(day is AirLabs.Again.Is && !day.flight.timetable && day.flight.from.gate == "G13")
        assertEquals(AirLabs.Again.Gone, again(plan, ok(later(reply("flight-LH455-in-the-air"), 5))).again)
    }

    @Test fun aFlightFoundAmongTheComingHoursWaitsForTheServiceToo() {
        // "The next flight" was found in the coming ten hours' list while the one-flight question still answered with the
        // flight before it. Where that question is asked about it, it answers with that one once more: the flight that is
        // followed is still to come, so that is "not yet", never the end of asking.
        val list = """{"response":[{"flight_iata":"JL101","dep_iata":"HND","arr_iata":"ITM","status":"scheduled",
            "dep_time":"2026-10-03 06:30","dep_time_utc":"2026-10-02 21:30","arr_time":"2026-10-03 07:35","arr_time_utc":"2026-10-02 22:35"}]}"""
        val soon = AirLabs.schedules(list).value!![0]
        assertFalse(soon.timetable)
        assertEquals(AirLabs.Again.NotYet, again(soon, ok(reply("flight-JL101-landed-nine-hours-ago"))).again)
        // A flight after it is another flight: gone.
        assertEquals(AirLabs.Again.Gone, again(soon, ok(later(reply("flight-JL101-landed-nine-hours-ago"), 2))).again)
        // Asked again a moment later (a press of Refresh), within ten hours of its leaving: the list is asked, and has it.
        val s = Service(mapOf("schedules" to list, "flight" to reply("flight-JL101-landed-nine-hours-ago")))
        assertTrue(AirLabs.again(lh455, soon, at("2026-10-02T21:00:00Z"), s::get)!!.again is AirLabs.Again.Is)
        assertEquals(listOf("schedules"), s.asked)
    }

    @Test fun howOftenAFollowerAsksInTheUsualCase() {
        // Followed from an hour before it leaves, an eleven-hour flight: every half hour, about twenty-four asks of one request each.
        val air = flight("flight-LH455-in-the-air")                               // left 21:47 UTC, lands 08:01 UTC
        var asks = 0
        var t = at("2026-10-01T20:40:00Z")
        var last: Instant? = null
        while (t.isBefore(at("2026-10-02T20:00:00Z"))) {
            val gap = FlightRules.pace(air, t)
            if (gap != null && (last == null || Duration.between(last, t) >= gap)) { asks++; last = t }
            t = t.plusSeconds(60)
        }
        // (The saved reply stays "in the air" here, so the asks run on for the three hours after its landing time.)
        assertTrue("asks: $asks", asks in 20..30)
    }

    // ---- when the service is asked again by itself

    private val min = 60_000L
    private val hour = 60 * min
    private fun ms(text: String) = at(text).toEpochMilli()
    private fun every(t: Tracked, now: String) = FlightRules.every(t, at(now))
    private fun tracked(f: Flight, asked: String, left: Int? = 940) = Tracked("LH455", f.from.planned?.toLocalDate()?.toString(), f, askedAt = ms(asked), heardAt = ms(asked), left = left)

    @Test fun nothingIsAskedForAPlanMoreThanTenHoursOff() {
        val like = flight("flight-LH455-in-the-air")
        val plan = AirLabs.planned(AirLabs.routes(reply("routes-LH455")).value!![0], like, LocalDate.of(2026, 10, 5))!!   // leaves 5 October 21:40 UTC
        val t = tracked(plan, "2026-10-02T07:29:00Z")
        assertNull(every(t, "2026-10-02T07:30:00Z"))
        assertNull(every(t, "2026-10-05T11:39:00Z"))
        assertEquals(3 * hour, every(t, "2026-10-05T11:40:00Z"))
    }

    @Test fun everyThreeHoursFarOutAndEveryHalfHourFromThreeHoursBeforeUntilItHasLanded() {
        val known = flight("flight-LH454-planned")                                // leaves 2 October 08:25 UTC, to land 19:40 UTC
        val t = tracked(known, "2026-10-01T18:00:00Z")
        assertEquals(3 * hour, every(t, "2026-10-01T18:00:00Z"))
        assertEquals(3 * hour, every(t, "2026-10-02T05:24:00Z"))
        assertEquals(30 * min, every(t, "2026-10-02T05:25:00Z"))
        val air = tracked(flight("flight-LH455-in-the-air"), "2026-10-02T02:00:00Z")   // lands 08:01 UTC
        assertEquals(30 * min, every(air, "2026-10-02T02:00:00Z"))
        // None after landing, and none after a cancellation.
        assertNull(every(tracked(flight("flight-LH96-landed"), "2026-10-02T07:29:00Z"), "2026-10-02T07:30:00Z"))
        assertNull(every(tracked(flight("flight-LH1184-cancelled"), "2026-10-02T07:29:00Z"), "2026-10-02T07:30:00Z"))
        // None either three hours past a landing the service never reported, though it was asked all along: it is shown as landed.
        val asking = tracked(flight("flight-LH455-in-the-air"), "2026-10-02T10:31:00Z")
        assertEquals(30 * min, every(asking, "2026-10-02T11:00:00Z"))
        assertNull(every(asking, "2026-10-02T11:02:00Z"))
    }

    @Test fun asleepThroughTheLandingItIsAskedAboutOnceOnWaking() {
        // Last heard of in the air at two in the night; the lid opens at four in the afternoon, eight hours after it was to land.
        val air = tracked(flight("flight-LH455-in-the-air"), "2026-10-02T02:00:00Z")   // expected 08:01 UTC
        assertEquals(FlightState.LANDED, FlightRules.shown(air.flight!!, at("2026-10-02T16:00:00Z")).state)
        assertEquals(30 * min, every(air, "2026-10-02T16:00:00Z"))               // long overdue, so: at once
        // Whatever that ask brings, it was the one: still "in the air" as before, the service gone on to the next day's flight, or landed.
        assertNull(every(air.copy(askedAt = ms("2026-10-02T16:00:00Z"), heardAt = ms("2026-10-02T16:00:00Z")), "2026-10-02T16:01:00Z"))
        assertNull(every(air.copy(askedAt = ms("2026-10-02T16:00:00Z"), ended = true), "2026-10-02T16:01:00Z"))
        assertNull(every(tracked(flight("flight-LH455-landed"), "2026-10-02T16:00:00Z"), "2026-10-02T16:01:00Z"))
        // If it came to nothing, it is tried again like any failed ask.
        assertEquals(2 * min, every(air.copy(askedAt = ms("2026-10-02T16:00:00Z"), failure = Failure.OFFLINE, failures = 1), "2026-10-02T16:01:00Z"))
        // A plan from the timetable was never the service's word: nothing to ask about once its time has passed.
        val plan = AirLabs.planned(AirLabs.routes(reply("routes-LH455")).value!![0], flight("flight-LH455-in-the-air"), LocalDate.of(2026, 10, 3))!!
        assertNull(every(tracked(plan, "2026-10-02T07:29:00Z"), "2026-10-04T12:00:00Z"))
    }

    @Test fun afterAFailureTheNextAskComesInTwoMinutesAndThenTwiceAsLateEachTimeUpToThirty() {
        val t = tracked(flight("flight-LH455-in-the-air"), "2026-10-02T02:00:00Z")
        fun failed(n: Int, why: Failure = Failure.NO_ANSWER) = every(t.copy(failure = why, failures = n), "2026-10-02T02:00:00Z")
        assertEquals(listOf(2 * min, 4 * min, 8 * min, 16 * min, 30 * min, 30 * min, 30 * min), (1..7).map { failed(it) })
        assertEquals(2 * min, failed(1, Failure.OFFLINE))
        assertEquals(30 * min, failed(40, Failure.OFFLINE))
        // Far out, where the usual wait is three hours, an ask that reached nobody (it cost nothing) is tried again just as soon.
        val far = tracked(flight("flight-LH454-planned"), "2026-10-01T18:00:00Z").copy(failure = Failure.OFFLINE, failures = 1)
        assertEquals(2 * min, every(far, "2026-10-01T18:00:00Z"))
        // But one that went out and came to nothing (an error, a reply nobody can read) spent a lookup of the user's own key:
        // far out, the next waits the usual three hours, not two minutes. Retried at the back-off, a flight days away that kept
        // failing cost up to 48 lookups a day instead of 8.
        assertEquals(3 * hour, every(far.copy(failure = Failure.NO_ANSWER, failures = 1), "2026-10-01T18:00:00Z"))
        assertEquals(3 * hour, every(far.copy(failure = Failure.NO_ANSWER, failures = 5), "2026-10-01T18:00:00Z"))
        // But where nothing would be asked anyway, a failure changes nothing: a landed flight is not asked about again.
        assertNull(every(tracked(flight("flight-LH96-landed"), "2026-10-02T07:29:00Z").copy(failure = Failure.OFFLINE, failures = 1), "2026-10-02T07:30:00Z"))
        // A service that says how long to wait is given at least that long.
        assertEquals(10 * min, every(t.copy(failure = Failure.NO_ANSWER, failures = 1, waitSec = 600), "2026-10-02T02:00:00Z"))
        assertEquals(4 * min, every(t.copy(failure = Failure.NO_ANSWER, failures = 2, waitSec = 30), "2026-10-02T02:00:00Z"))
        // The answer after a failure brings the usual pace back.
        assertEquals(30 * min, every(t.copy(failure = null, failures = 0), "2026-10-02T02:00:00Z"))
    }

    @Test fun aKeyThatIsRefusedOrUsedUpIsNotAskedAgainByItself() {
        val t = tracked(flight("flight-LH455-in-the-air"), "2026-10-02T02:00:00Z")
        assertNull(every(t.copy(failure = Failure.REFUSED), "2026-10-02T02:00:00Z"))
        assertNull(every(t.copy(failure = Failure.USED_UP), "2026-10-02T02:00:00Z"))
        // Nor is a flight the service has gone on from: an item follows one flight, never the next day's of its number.
        assertNull(every(t.copy(ended = true), "2026-10-02T02:00:00Z"))
    }

    @Test fun aRefreshByHandWaitsTwoMinutesAndAsLongAsASlowDownAskedFor() {
        val t = tracked(flight("flight-LH455-in-the-air"), "2026-10-02T02:00:00Z")
        assertEquals(2 * min, FlightRules.byHand(t))
        // Only a try that found no connection may be repeated in ten seconds.
        assertEquals(10_000L, FlightRules.byHand(t.copy(failure = Failure.OFFLINE, failures = 1)))
        assertEquals(2 * min, FlightRules.byHand(t.copy(failure = Failure.NO_ANSWER, failures = 1)))
        assertEquals(5 * min, FlightRules.byHand(t.copy(failure = Failure.NO_ANSWER, failures = 1, waitSec = 300)))
        assertEquals(2 * min, FlightRules.byHand(t.copy(failure = Failure.NO_ANSWER, failures = 1, waitSec = 30)))
        // A wait nobody can mean is kept to for a day at most.
        assertEquals(24 * hour, FlightRules.byHand(t.copy(failure = Failure.NO_ANSWER, waitSec = Long.MAX_VALUE)))
    }

    @Test fun belowTwentyLookupsLeftFollowingStops() {
        val air = flight("flight-LH455-in-the-air")
        assertEquals(30 * min, every(tracked(air, "2026-10-02T02:00:00Z", left = 20), "2026-10-02T02:00:00Z"))
        assertNull(every(tracked(air, "2026-10-02T02:00:00Z", left = 19), "2026-10-02T02:00:00Z"))
        assertNull(every(tracked(air, "2026-10-02T02:00:00Z", left = 0), "2026-10-02T02:00:00Z"))
        // A reply that did not say how many are left stops nothing.
        assertEquals(30 * min, every(tracked(air, "2026-10-02T02:00:00Z", left = null), "2026-10-02T02:00:00Z"))
    }

    @Test fun aNewKeyAsksAtOnceOnlyAboutAFlightThatWaitedForOne() {
        val now = at("2026-10-02T02:00:00Z")
        val t = tracked(flight("flight-LH455-in-the-air"), "2026-10-02T02:00:00Z")
        // Followed as planned, it keeps its turn, new key or not. So does one that only found no connection.
        assertFalse(FlightRules.waitsForKey(t, now))
        assertFalse(FlightRules.waitsForKey(t.copy(left = 20), now))
        assertFalse(FlightRules.waitsForKey(t.copy(left = null), now))
        assertFalse(FlightRules.waitsForKey(t.copy(failure = Failure.OFFLINE, failures = 3), now))
        // The old key was refused or used up: the menu says so until the service is asked again.
        assertTrue(FlightRules.waitsForKey(t.copy(failure = Failure.REFUSED), now))
        assertTrue(FlightRules.waitsForKey(t.copy(failure = Failure.USED_UP), now))
        assertTrue(FlightRules.waitsForKey(t.copy(flight = null, failure = Failure.REFUSED), now))
        // Or it had too few lookups left to ask unasked, where there was something to ask.
        assertTrue(FlightRules.waitsForKey(t.copy(left = 19), now))
        assertTrue(FlightRules.waitsForKey(t.copy(left = 19, failure = Failure.NO_ANSWER, failures = 2), now))
        assertTrue(FlightRules.waitsForKey(t.copy(flight = null, left = 0, failure = Failure.OFFLINE, failures = 1), now))
        assertFalse(FlightRules.waitsForKey(tracked(flight("flight-LH96-landed"), "2026-10-02T07:29:00Z", left = 19), at("2026-10-02T07:30:00Z")))
        // No key brings back a flight the service has gone on from, and an item that follows nothing has nothing to ask.
        assertFalse(FlightRules.waitsForKey(t.copy(left = 19, ended = true), now))
        assertFalse(FlightRules.waitsForKey(t.copy(failure = Failure.REFUSED, ended = true), now))
        assertFalse(FlightRules.waitsForKey(Tracked(), now))
    }

    @Test fun inTheLastHourBeforeItLeavesAndTheLastHalfHourBeforeItLandsEveryTenMinutes() {
        val planned = flight("flight-LH454-planned")                              // leaves 08:25 UTC
        val t = tracked(planned, "2026-10-02T07:00:00Z")
        assertEquals(30 * min, every(t, "2026-10-02T07:24:00Z"))
        assertEquals(10 * min, every(t, "2026-10-02T07:25:00Z"))
        assertEquals(10 * min, every(t, "2026-10-02T08:25:00Z"))
        // Its time has passed and nobody says it has left: that is when someone watches most, so the quicker pace holds
        // for an hour more, and then it is the usual one again.
        assertEquals(10 * min, every(t, "2026-10-02T08:26:00Z"))
        assertEquals(10 * min, every(t, "2026-10-02T09:25:00Z"))
        assertEquals(30 * min, every(t, "2026-10-02T09:26:00Z"))
        val air = tracked(flight("flight-LH455-in-the-air"), "2026-10-02T07:00:00Z")   // expected to land 08:01 UTC
        assertEquals(30 * min, every(air, "2026-10-02T07:30:00Z"))
        assertEquals(10 * min, every(air, "2026-10-02T07:31:00Z"))
        assertEquals(10 * min, every(air, "2026-10-02T08:01:00Z"))
        // The same past the time it was to land, while nobody says that it has.
        assertEquals(10 * min, every(air, "2026-10-02T08:02:00Z"))
        assertEquals(10 * min, every(air, "2026-10-02T09:01:00Z"))
        assertEquals(30 * min, every(air, "2026-10-02T09:02:00Z"))
        // Past its time too, only with lookups to spare.
        assertEquals(30 * min, every(t.copy(left = 100), "2026-10-02T08:26:00Z"))
        assertEquals(30 * min, every(air.copy(left = null), "2026-10-02T08:02:00Z"))
        // And not for a flight whose clock nobody gave: its moments are a guess, and no lookups are spent on one.
        assertEquals(30 * min, every(t.copy(flight = planned.copy(loose = true)), "2026-10-02T08:00:00Z"))
        // Only while more than a hundred lookups are left, and only where the reply says how many.
        assertEquals(30 * min, every(t.copy(left = 100), "2026-10-02T07:25:00Z"))
        assertEquals(10 * min, every(t.copy(left = 101), "2026-10-02T07:25:00Z"))
        assertEquals(30 * min, every(t.copy(left = null), "2026-10-02T07:25:00Z"))
        // A plan from the timetable is nobody's word on when it leaves: no quicker for it.
        val plan = AirLabs.planned(AirLabs.routes(reply("routes-LH455")).value!![0], flight("flight-LH455-in-the-air"), LocalDate.of(2026, 10, 5))!!
        assertEquals(30 * min, every(tracked(plan, "2026-10-05T20:00:00Z"), "2026-10-05T21:00:00Z"))
    }

    @Test fun aFlightIsClearedADayAfterItLandedOrWasToLand() {
        val landed = tracked(flight("flight-LH96-landed"), "2026-10-02T07:29:00Z")     // landed 07:13 UTC
        assertFalse(FlightRules.cleared(landed, at("2026-10-03T07:12:00Z")))
        assertTrue(FlightRules.cleared(landed, at("2026-10-03T07:13:00Z")))
        // What is cleared is put away by the next load, which is due at once and asks nobody.
        assertNull(every(landed, "2026-10-03T07:12:00Z"))
        assertEquals(0L, every(landed, "2026-10-03T07:13:00Z"))
        // A canceled one: a day after it was to land. One that nobody heard of again: a day after its expected landing.
        val canceled = tracked(flight("flight-LH1184-cancelled"), "2026-10-02T07:29:00Z")   // was to land 06:45 UTC
        assertFalse(FlightRules.cleared(canceled, at("2026-10-03T06:44:00Z")))
        assertTrue(FlightRules.cleared(canceled, at("2026-10-03T06:45:00Z")))
        val air = tracked(flight("flight-LH455-in-the-air"), "2026-10-02T07:29:00Z")       // expected 08:01 UTC
        assertFalse(FlightRules.cleared(air, at("2026-10-03T08:00:00Z")))
        assertTrue(FlightRules.cleared(air, at("2026-10-03T08:01:00Z")))
        // Nothing followed is nothing to clear, and nothing to ask.
        assertFalse(FlightRules.cleared(Tracked(), at("2026-10-03T08:01:00Z")))
        assertNull(every(Tracked(), "2026-10-03T08:01:00Z"))
    }

    @Test fun aFlightAskedForAndNeverAnsweredIsTriedAgainLikeAnyFailure() {
        // The switch came back on without a connection: the item knows its number and day, and nothing else yet.
        val t = Tracked("LH455", "2026-10-02", null, failure = Failure.OFFLINE, failures = 1, askedAt = ms("2026-10-02T07:29:00Z"))
        assertEquals(2 * min, every(t, "2026-10-02T07:30:00Z"))
        assertEquals(8 * min, every(t.copy(failures = 3), "2026-10-02T07:30:00Z"))
        // A number the service does not know on that day is not asked for again by itself.
        assertNull(every(t.copy(failure = Failure.NOT_FOUND), "2026-10-02T07:30:00Z"))
        assertNull(every(t.copy(failure = Failure.NOT_THAT_DAY), "2026-10-02T07:30:00Z"))
        assertNull(every(t.copy(failure = Failure.REFUSED), "2026-10-02T07:30:00Z"))
    }

    @Test fun aFlightNobodyHeardOfForThreeHoursPastItsLandingIsShownAsLanded() {
        val air = flight("flight-LH455-in-the-air")                              // expected 08:01 UTC
        assertEquals(FlightState.IN_AIR, FlightRules.shown(air, at("2026-10-02T11:01:00Z")).state)
        assertEquals(FlightState.LANDED, FlightRules.shown(air, at("2026-10-02T11:02:00Z")).state)
        val planned = flight("flight-LH454-planned")                             // to land 19:40 UTC
        assertEquals(FlightState.PLANNED, FlightRules.shown(planned, at("2026-10-02T12:00:00Z")).state)
        assertEquals(FlightState.LANDED, FlightRules.shown(planned, at("2026-10-02T22:41:00Z")).state)
        // What the service called canceled or diverted stays so, and a timetable's flight stays what nobody knows.
        val gone = flight("flight-LH1184-cancelled")
        assertEquals(gone, FlightRules.shown(gone, at("2026-10-05T00:00:00Z")))
        val diverted = air.copy(state = FlightState.DIVERTED)
        assertEquals(diverted, FlightRules.shown(diverted, at("2026-10-05T00:00:00Z")))
        val plan = AirLabs.planned(AirLabs.routes(reply("routes-LH455")).value!![0], air, LocalDate.of(2026, 10, 3))!!
        assertEquals(plan, FlightRules.shown(plan, at("2026-10-05T00:00:00Z")))
    }

    @Test fun theAlertOfACancellationLastsAnHourFromWhenItWasFirstSeen() {
        val gone = tracked(flight("flight-LH1184-cancelled"), "2026-10-02T07:29:00Z").copy(alertSince = ms("2026-10-02T07:29:00Z"))
        assertTrue(FlightRules.alert(gone, at("2026-10-02T07:29:00Z")))
        assertTrue(FlightRules.alert(gone, at("2026-10-02T08:28:59Z")))
        assertFalse(FlightRules.alert(gone, at("2026-10-02T08:29:00Z")))
        // Heard of again many times since: the hour still counts from the first.
        assertFalse(FlightRules.alert(gone.copy(heardAt = ms("2026-10-02T09:00:00Z")), at("2026-10-02T09:01:00Z")))
        // A flight that goes its way has nothing to alert of.
        assertFalse(FlightRules.alert(tracked(flight("flight-LH455-in-the-air"), "2026-10-02T07:29:00Z"), at("2026-10-02T07:30:00Z")))
        // When it was first seen is noted with the answer that said so, and kept through the ones after it.
        assertEquals(1_000L, FlightRules.alertSince(flight("flight-LH1184-cancelled"), null, 1_000L))
        assertEquals(1_000L, FlightRules.alertSince(flight("flight-LH1184-cancelled"), 1_000L, 9_000L))
        assertEquals(9_000L, FlightRules.alertSince(flight("flight-LH455-in-the-air").copy(state = FlightState.DIVERTED), null, 9_000L))
        assertNull(FlightRules.alertSince(flight("flight-LH455-in-the-air"), 1_000L, 9_000L))
    }

    @Test fun anAnswerOlderThanAnHourIsNotLive() {
        val air = tracked(flight("flight-LH455-in-the-air"), "2026-10-02T06:00:00Z")
        assertFalse(FlightRules.stale(air, at("2026-10-02T07:00:00Z")))
        assertTrue(FlightRules.stale(air, at("2026-10-02T07:00:01Z")))
        // A clock that was set back makes nothing old.
        assertFalse(FlightRules.stale(air, at("2026-10-01T07:00:00Z")))
    }

    @Test fun onlyAFlightThatMakesSenseIsKept() {
        val air = flight("flight-LH455-in-the-air")
        assertTrue(FlightRules.sound(air))
        assertTrue(FlightRules.sound(flight("flight-LH1184-cancelled")))
        assertFalse(FlightRules.sound(air.copy(from = air.from.copy(offset = 5_000))))
        assertFalse(FlightRules.sound(air.copy(to = air.to.copy(expected = time("9999-01-01T00:00")))))
        assertFalse(FlightRules.sound(air.copy(to = air.to.copy(planned = time("1066-10-14T09:00")))))
    }

    // ---- how a flight stands, which is the color of its line in the bar

    /** When the saved replies were asked for. */
    private val saved = at("2026-10-02T07:29:00Z")
    private fun stands(f: Flight, now: Instant = saved, stale: Boolean = false) = FlightRules.stands(f, now, stale)

    @Test fun lateIsFromFifteenMinutesBehindThePlanAndVeryLateFromFortyFiveAtBothEnds() {
        assertEquals(15, FlightRules.ON_TIME)
        assertEquals(45, FlightRules.VERY_LATE)
        val minutes = listOf(14L, 15L, 44L, 45L)
        // Before it leaves, by when it is expected to.
        val planned = flight("flight-LH454-planned")                              // leaves 08:25 UTC
        fun leaving(late: Long) = stands(planned.copy(from = planned.from.copy(expected = planned.from.planned!!.plusMinutes(late))))
        assertEquals(listOf(Stands.NO_CLAIM, Stands.LATE, Stands.LATE, Stands.VERY_LATE), minutes.map { leaving(it) })
        // In the air, by when it is expected to land.
        val air = flight("flight-LH455-in-the-air")                               // planned to land 08:25 UTC
        fun landing(late: Long) = stands(air.copy(to = air.to.copy(expected = air.to.planned!!.plusMinutes(late))))
        assertEquals(listOf(Stands.GOOD, Stands.LATE, Stands.LATE, Stands.VERY_LATE), minutes.map { landing(it) })
        // Landed, by when it did.
        val down = flight("flight-LH96-landed")
        fun landed(late: Long) = stands(down.copy(to = down.to.copy(actual = down.to.planned!!.plusMinutes(late))))
        assertEquals(listOf(Stands.GOOD, Stands.LATE, Stands.LATE, Stands.VERY_LATE), minutes.map { landed(it) })
        // Hours behind is very late and nothing worse, and the saved reply that runs 45 minutes behind is the first of them.
        assertEquals(Stands.VERY_LATE, leaving(300))
        assertEquals(Stands.VERY_LATE, landing(300))
        assertEquals(Stands.VERY_LATE, stands(flight("flight-LH152-delayed")))
    }

    @Test fun theDepartureCountsUntilItHasLeftAndTheLandingAfter() {
        // LH 455 left seven minutes late and is expected 24 minutes early.
        val air = flight("flight-LH455-in-the-air")
        assertEquals(Stands.GOOD, stands(air))
        // However late it left: an hour behind at the gate and ahead of its plan at the far end is good news now.
        assertEquals(Stands.GOOD, stands(air.copy(from = air.from.copy(planned = air.from.actual!!.minusMinutes(60)))))
        // And one that left to the minute and is expected an hour behind is very late.
        assertEquals(Stands.VERY_LATE, stands(air.copy(from = air.from.copy(planned = air.from.actual), to = air.to.copy(expected = air.to.planned!!.plusMinutes(60)))))
        // Before it has left it is the other way round: what is expected of the landing says nothing yet.
        val planned = flight("flight-LH454-planned")
        val lateIn = planned.copy(from = planned.from.copy(expected = planned.from.planned), to = planned.to.copy(expected = planned.to.planned!!.plusMinutes(60)))
        assertEquals(Stands.NO_CLAIM, stands(lateIn))
        val lateOut = planned.copy(from = planned.from.copy(expected = planned.from.planned!!.plusMinutes(60)), to = planned.to.copy(expected = planned.to.planned))
        assertEquals(Stands.VERY_LATE, stands(lateOut))
        // From the moment the service says it has left, the landing is what counts.
        assertEquals(Stands.GOOD, stands(lateOut.copy(state = FlightState.IN_AIR, from = lateOut.from.copy(actual = lateOut.from.expected))))
    }

    @Test fun earlyIsGoodOnceItFliesAndBeforeItLeavesNothingIsClaimed() {
        assertEquals(Stands.GOOD, stands(flight("flight-LH455-in-the-air")))      // expected 24 minutes before its plan
        assertEquals(Stands.GOOD, stands(flight("flight-LH455-landed")))          // down 29 minutes before it
        assertEquals(Stands.GOOD, stands(flight("flight-LH9152-codeshare")))      // 13 minutes behind its plan is on time
        assertEquals(Stands.GOOD, stands(flight("flight-LH96-landed")))           // and so is three
        // Before it leaves, "on time" is a plan like any other: good news starts when it flies.
        val planned = flight("flight-LH454-planned")
        fun leaving(late: Long) = stands(planned.copy(from = planned.from.copy(expected = planned.from.planned!!.plusMinutes(late))))
        assertEquals(Stands.NO_CLAIM, leaving(0))
        assertEquals(Stands.NO_CLAIM, leaving(-20))
    }

    /** In the air past the time it was to land, and no word that it has: "on time" is nobody's to say any more, as before it leaves. */
    @Test fun inTheAirPastItsLandingTimeGoodNewsIsNoLongerClaimed() {
        val air = flight("flight-LH455-in-the-air")
        val lands = air.to.moment(air.to.time!!)
        assertEquals(Stands.GOOD, stands(air, lands.minusSeconds(60)))
        assertEquals(Stands.NO_CLAIM, stands(air, lands))
        assertEquals(Stands.NO_CLAIM, stands(air, lands.plusSeconds(15 * 60)))
        // A delay that was known is still true then.
        val late = air.copy(to = air.to.copy(expected = air.to.planned!!.plusMinutes(20)))
        assertEquals(Stands.LATE, stands(late, late.to.moment(late.to.time!!).plusSeconds(15 * 60)))
        // And a flight that has landed is on time or not by when it did.
        assertEquals(Stands.GOOD, stands(flight("flight-LH455-landed"), saved.plusSeconds(30 * 60)))
    }

    @Test fun onlyThePlanOrAnAnswerOverAnHourOldClaimsNothing() {
        // Only the plan is known: before it leaves, in the air and after it landed.
        assertEquals(Stands.NO_CLAIM, stands(flight("flight-LH454-planned")))
        val air = flight("flight-LH455-in-the-air")
        assertEquals(Stands.NO_CLAIM, stands(air.copy(to = air.to.copy(expected = null))))
        val down = flight("flight-LH96-landed")
        assertEquals(Stands.NO_CLAIM, stands(down.copy(to = down.to.copy(expected = null, actual = null))))
        // Not live: what the last answer said of late and early may be wrong by now, the good news and the bad.
        assertEquals(Stands.NO_CLAIM, stands(air, stale = true))
        assertEquals(Stands.NO_CLAIM, stands(flight("flight-LH152-delayed"), stale = true))
        assertEquals(Stands.NO_CLAIM, stands(air.copy(to = air.to.copy(expected = air.to.planned!!.plusMinutes(90))), stale = true))
        assertEquals(Stands.NO_CLAIM, stands(flight("flight-LH96-landed"), stale = true))
        // A timetable's flight is a plan, and nobody knows what became of it once its time has passed.
        val plan = AirLabs.planned(AirLabs.routes(reply("routes-LH455")).value!![0], air, LocalDate.of(2026, 10, 3))!!   // leaves 21:40 UTC
        assertEquals(Stands.NO_CLAIM, stands(plan))
        assertEquals(Stands.NO_CLAIM, stands(plan, at("2026-10-04T00:00:00Z")))
        // Past its time to leave with no word that it left, "on time" is nobody's to say. A delay that was known is still true.
        val planned = flight("flight-LH454-planned")
        assertEquals(Stands.NO_CLAIM, stands(planned.copy(from = planned.from.copy(expected = planned.from.planned)), at("2026-10-02T08:26:00Z")))
        assertEquals(Stands.VERY_LATE, stands(flight("flight-LH152-delayed"), at("2026-10-02T07:40:00Z")))
    }

    @Test fun aFlightThatIsCanceledOrDivertedWillNotArriveHoweverOldTheAnswer() {
        val gone = flight("flight-LH1184-cancelled")
        assertEquals(Stands.WILL_NOT_ARRIVE, stands(gone))
        assertEquals(Stands.WILL_NOT_ARRIVE, stands(gone, stale = true))
        val diverted = flight("flight-LH455-in-the-air").copy(state = FlightState.DIVERTED)
        assertEquals(Stands.WILL_NOT_ARRIVE, stands(diverted))
        assertEquals(Stands.WILL_NOT_ARRIVE, stands(diverted, stale = true))
    }
}
