package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.net.Host
import com.meridian11.books.bentobar.net.Reply
import com.meridian11.books.bentobar.net.Request
import com.meridian11.books.bentobar.net.Why
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * A flight number as it is entered: `LH455`, `lh 455`, `LH-455`, `LH0455`, or the callsign `DLH455`.
 * Two characters (an airline's designator: two letters, or a letter and a digit) or a callsign's
 * three letters, then one to four digits and at most one letter. There is no table of airlines:
 * whether anyone flies a number is the service's to say, and its reply names the airline.
 */
data class FlightNumber(val designator: String, val number: Int, val suffix: String = "") {
    /** Entered in the callsign's form (DLH455): it is asked for as one. */
    val callsign: Boolean get() = designator.length == 3
    /** Closed up, as it is asked for and kept: LH455. */
    val code: String get() = "$designator$number$suffix"
    /** As it is shown: "LH 455". */
    val shown: String get() = "$designator $number$suffix"

    companion object {
        private val CALLSIGN = Regex("([A-Z]{3})[ -]?0*([1-9][0-9]{0,3})([A-Z]?)")
        private val TICKET = Regex("([A-Z]{2}|[A-Z][0-9]|[0-9][A-Z])[ -]?0*([1-9][0-9]{0,3})([A-Z]?)")

        /** The number [text] is, or null: nothing but a flight number may stand in it. Three letters can only be a callsign's, so that form is tried first. */
        fun read(text: String): FlightNumber? {
            val t = text.trim().uppercase(Locale.ROOT)
            val m = CALLSIGN.matchEntire(t) ?: TICKET.matchEntire(t) ?: return null
            return FlightNumber(m.groupValues[1], m.groupValues[2].toInt(), m.groupValues[3])
        }

        /** [number] as it is shown ("LH455" as "LH 455"); what does not read as a flight number stays as it is. */
        fun shown(number: String): String = read(number)?.shown ?: number
    }
}

/**
 * AirLabs (airlabs.co, API v9), read: what to ask, and what its replies say. What is written here was
 * seen in its replies on 2 October 2026: `flight` answers with the one flight of a number that is
 * nearest to now, `schedules` with the flights of the next ten hours, `routes` with the timetable. An
 * error comes with the status 200 and an `error` object. A reply from the network is never trusted to
 * be well formed, short or one line.
 *
 * Seen on 6 October 2026, for a number that flies more than once a day (UA 1227): `flight` is not
 * the nearest of its flights. With the one from Newark late and due in a quarter of an hour it answered
 * with the evening's from San Francisco, and once Newark's was in the air with a mix of the two:
 * Newark's airports and position, San Francisco's times, gates and belt. It takes no airport to narrow
 * it down. `schedules` had each flight as it was, the one in the air too.
 *
 * Every reply repeats the key it was asked with (and names the caller's address) in a `request`
 * object. Of that object one number is read, how many lookups are left, and nothing else of a reply
 * leaves this file but the model made here: a reply's text is never kept, logged or shown.
 */
object AirLabs {
    /** Where a key is got. */
    const val SIGN_UP = "https://airlabs.co/signup"
    /** The one flight of a number the service picks (not always the nearest to now, see above). */
    const val FLIGHT = "/api/v9/flight"
    /** The flights of a number in the next ten hours, and the ones just flown. */
    const val SCHEDULES = "/api/v9/schedules"
    /** The timetable for a number. */
    const val ROUTES = "/api/v9/routes"

    /** Why there is no answer, to be said in plain words. */
    @Serializable
    enum class Failure {
        /** The service knows no flight of that number. */
        NOT_FOUND,
        /** The key is unknown to the service, or has run out. */
        REFUSED,
        /** The key's lookups for the month are used up. */
        USED_UP,
        /** The number does not fly on the day that was asked for. */
        NOT_THAT_DAY,
        /** The device reached nobody. */
        OFFLINE,
        /** Anything else: a reply that could not be read, an error of the service's own, a limit for the minute or the hour. */
        NO_ANSWER,
    }

    /** What a reply held: [value], or why not. [left]: how many lookups the key has left this month, where the reply says. */
    class Read<out T>(val value: T?, val failure: Failure? = null, val left: Int? = null)

    /** A line of an airline's timetable: this number flies from here to there at these times on these days. Times are each airport's own. */
    data class Route(
        val from: String, val to: String, val leaves: LocalTime, val minutes: Int, val days: Set<DayOfWeek>,
        /** How many minutes each airport's clock is ahead of UTC. */
        val fromOffset: Int, val toOffset: Int,
        val fromTerminal: String? = null, val toTerminal: String? = null,
        val callsign: String? = null,
    )

    /**
     * One question about [n]: the host, the path, and the two things that are sent, the number and the
     * user's own key, which the transport adds. Nothing else goes to the service. (The transport encodes them; a request prints
     * as its host and path alone.)
     */
    fun request(path: String, n: FlightNumber): Request =
        Request(Host.AIRLABS, path, listOf((if (n.callsign) "flight_icao" else "flight_iata") to n.code))

    /** What a lookup came to: the flight, or why there is none; and how many lookups the key has left, where a reply said. */
    class Answer(val flight: Flight?, val failure: Failure?, val left: Int?)

    /**
     * The next flight of [n], or the one on [day], asked for through [get] (which sends a request and
     * says what came of it). `flight` answers with the one nearest to now. If that one landed more than
     * three hours ago, the next is among the coming ten hours' (`schedules`) or, further off, in the
     * timetable (`routes`); a day that is not that flight's is the timetable's too, and so is a number
     * `flight` does not know (one that flies once a week). Up to three requests, and one in the usual
     * case.
     *
     * [zone]: where [day] was chosen, when it is a day of the device's (a day chip: "Today" in Los
     * Angeles is tomorrow's date in Tokyo for a flight that leaves there in an hour). The flight is
     * then that day's if the moment it leaves falls on it by the device's clock, and by nothing
     * else: the service's flight and the timetable's alike. Null: [day] is the departure airport's
     * own date (what is kept of a followed flight).
     *
     * Null: a request was not sent at all (the service was switched off or the bar hid under the
     * lookup), so there is nothing to say, neither an answer nor a failure.
     */
    fun lookup(n: FlightNumber, day: LocalDate?, now: Instant, zone: ZoneId? = null, get: (Request) -> Reply): Answer? =
        hear(n, day, null, now, zone, withTimetable = false, get)?.answer

    /**
     * [lookup] for a flight that is followed already, when what was heard of it is gone: the flight of
     * [n] that leaves the airport [from] on [day], which is that airport's own date. A number can fly
     * more than once a day (Orlando to Newark in the morning, Newark to San Francisco after it), and
     * `flight` answers with whichever is nearest to now. With an airport named, only the flights that
     * leave from it and its lines of the timetable count: the flight that was chosen is found again,
     * and nobody is asked a second time which one was meant. Null [from]: any airport's, which is what
     * was kept before there was a choice.
     */
    fun lookup(n: FlightNumber, day: LocalDate?, from: String?, now: Instant, get: (Request) -> Reply): Answer? =
        hear(n, day, from, now, null, withTimetable = false, get)?.answer

    /**
     * What a press of Track came to: the flights it can mean, or why there is none; and how many lookups
     * the key has left, where a reply said. One flight is the usual case, and it is the one [lookup] finds.
     */
    class Candidates(val flights: List<Flight>, val failure: Failure?, val left: Int?)

    /** "The next flight" of a number that flies more than once is one of those that leave within this long. */
    val DAY_AHEAD: Duration = Duration.ofHours(24)
    /** No number flies more often in a day than this, and no menu lists more: of a timetable that says otherwise, the first to leave. */
    const val MOST_CANDIDATES = 12

    /**
     * The flights of [n] that a press of Track can mean: one, mostly, and several where the number
     * flies more than once on the day that was asked for (UA 1227: Orlando to Newark in the morning,
     * Newark to San Francisco after it, San Francisco to Portland in the evening). Then the user says
     * which. The one flight is [lookup]'s, found with [lookup]'s requests; the timetable is asked for
     * besides wherever a flight was found, since it is what says how often the number flies: two
     * requests in the usual case, three at most.
     *
     * With a [day] (a day chip, a day of the device's in [zone]) there is to choose from: every line of
     * the timetable that leaves on that day, as a plan, in the order they leave.
     *
     * With none ("Next flight"): each route's next flight to leave (the soonest of its lines, where
     * the timetable has a line for each kind of day), where that is within [DAY_AHEAD], in the order
     * they leave; and before them the flight that is the answer where nothing is asked, if the
     * service itself knows it and it is in the air or has not left. That one stands for its route:
     * the route's flight of the day after is not offered beside it, so a number that flies once a day
     * asks nothing while its flight is in the air or late.
     *
     * A flight the service itself answered with stands in the place of the timetable's plan for it (the
     * same airport, the same planned day: [FlightRules.same]): it has the gate and the delays, and takes
     * from the plan only what it lacks. Two flights that are the same by that rule are offered once, the
     * earlier: the app could not tell them apart once one of them is followed.
     *
     * Fewer than two to choose from, or no timetable to be had: the one flight, as [lookup] finds it.
     * Never more than [MOST_CANDIDATES]. Null, and every failure: as for [lookup].
     */
    fun candidates(n: FlightNumber, day: LocalDate?, now: Instant, zone: ZoneId? = null, get: (Request) -> Reply): Candidates? {
        val heard = hear(n, day, null, now, zone, withTimetable = true, get) ?: return null
        val one = heard.answer.flight ?: return Candidates(emptyList(), heard.answer.failure, heard.answer.left)
        val like = heard.nearest ?: unnamed(n)
        val live = listOfNotNull(heard.nearest) + heard.coming.map { named(it, like) }
        val several = if (day != null) thatDay(heard.lines, like, live, day, zone, now) else ahead(heard.lines, like, live, one, now)
        return Candidates(if (several.size > 1) several.take(MOST_CANDIDATES) else listOf(one), null, heard.answer.left)
    }

    /**
     * The flights of [day] to choose from, as [candidates] says them. A flight the service answered
     * with that leaves on that day is one of them, whether the timetable has its line or not.
     */
    private fun thatDay(lines: List<Route>, like: Flight, live: List<Flight>, day: LocalDate, zone: ZoneId?, now: Instant): List<Flight> =
        ordered(offered(lines.mapNotNull { on(it, like, day, zone) }, live, now) + live.filter { leaves(it, day, zone) })

    /** The next flights to choose from, as [candidates] says them. [one]: the flight that is the answer where nothing is asked. */
    private fun ahead(lines: List<Route>, like: Flight, live: List<Flight>, one: Flight, now: Instant): List<Flight> {
        // (A flight that landed, or was diverted, is not one of the next. One that was canceled has not left, and whoever asks should hear of it.)
        val first = one.takeIf { !it.timetable && it.state != FlightState.LANDED && it.state != FlightState.DIVERTED }
        fun own(r: Route) = first != null && r.from == first.from.code && r.to == first.to.code
        val until = now.plus(DAY_AHEAD)
        // (A route can have a line for each kind of day, with a time of its own: its next flight is the soonest of theirs.)
        val plans = lines.filterNot(::own).groupBy { it.from to it.to }.values
            .mapNotNull { route -> route.mapNotNull { upcoming(it, like, now) }.minByOrNull { leaves(it) ?: Instant.MAX } }
            .filter { leaves(it)?.isAfter(until) == false }
        val plan = first?.from?.planned?.let { left -> lines.filter(::own).firstNotNullOfOrNull { planned(it, like, left.toLocalDate()) } }
        val rest = ordered(offered(plans, live, now)).filter { first == null || !FlightRules.same(first, it) }
        return listOfNotNull(first?.let { if (plan == null) it else filled(it, plan) }) + rest
    }

    /**
     * [plans] of the timetable as flights to choose from: where the service itself answered with one
     * of them ([live]), that flight in the plan's place, with what it lacks from the plan.
     */
    private fun offered(plans: List<Flight>, live: List<Flight>, now: Instant): List<Flight> =
        plans.map { p -> live.firstOrNull { FlightRules.same(p, it) }?.let { filled(it, p) } ?: dated(p, now) }

    /** [flights] in the order they leave, and none of them twice: of two that are the same flight by [FlightRules.same], the earlier. */
    private fun ordered(flights: List<Flight>): List<Flight> =
        flights.sortedBy { leaves(it) ?: Instant.MAX }.fold(emptyList()) { kept, f -> if (kept.any { FlightRules.same(it, f) }) kept else kept + f }

    /**
     * [live], a flight as the service said it, with what only the timetable's [plan] for it has: the
     * time of an end the service gave none for (and the clock that time is read by), a terminal it did
     * not name, the callsign. Nothing the service said is changed.
     */
    private fun filled(live: Flight, plan: Flight): Flight {
        fun end(e: FlightEnd, p: FlightEnd): FlightEnd = when {
            e.code != p.code -> e
            e.time == null -> e.copy(planned = p.planned, offset = p.offset, terminal = e.terminal ?: p.terminal)
            else -> e.copy(terminal = e.terminal ?: p.terminal)
        }
        return live.copy(from = end(live.from, plan.from), to = end(live.to, plan.to), callsign = live.callsign ?: plan.callsign)
    }

    /** The moment [f] leaves, by the best time there is for it. */
    private fun leaves(f: Flight): Instant? = f.from.time?.let(f.from::moment)

    /**
     * What one lookup heard: its [answer], and what the service said on the way to it. [nearest]: the
     * flight `flight` answered with; [coming]: the coming hours' flights; [lines]: the timetable; each
     * where it was asked for and came.
     */
    private class Heard(val answer: Answer, val nearest: Flight?, val coming: List<Flight>, val lines: List<Route>)

    /**
     * The lookup itself, as [lookup] describes it. [from]: only this airport's flights and lines count.
     * [withTimetable]: the timetable is asked for also where a flight was found without it (a press of
     * Track: it says what else the number flies). No question is asked twice.
     */
    private fun hear(n: FlightNumber, day: LocalDate?, from: String?, now: Instant, zone: ZoneId?, withTimetable: Boolean, get: (Request) -> Reply): Heard? {
        var left: Int? = null
        var unsent = false
        // What is asked for: the number as it was entered, until the service has named the ticket's form of a callsign.
        var number = n
        var nearest: Flight? = null
        var coming: List<Flight> = emptyList()
        var table: Read<List<Route>>? = null
        fun <T> ask(path: String, read: (String) -> Read<T>): Read<T> {
            // Once a request was not sent, the lookup is over: nothing more is tried.
            if (unsent) return Read(null, Failure.NO_ANSWER)
            return when (val reply = send(request(path, number), get)) {
                is Reply.Ok -> read(reply.text).also { r -> r.left?.let { left = it } }
                is Reply.Failed -> { if (reply.why == Why.OFF) unsent = true; Read(null, failure(reply)) }
            }
        }
        fun counts(f: Flight) = from == null || f.from.code == from
        /** The timetable, asked for once, whoever wants it. */
        fun timetable(): Read<List<Route>> = table
            ?: ask(ROUTES) { routes(it) }.let { r -> Read(r.value?.filter { from == null || it.from == from }, r.failure, r.left) }.also { table = it }
        fun found(): Answer {
            val first = ask(FLIGHT) { flight(it) }
            if (first.failure == Failure.NOT_FOUND) {
                val timetable = timetable()
                // The timetable was not to be had (no connection, no lookups left): that is what went wrong, not "nothing found".
                timetable.failure?.takeIf { it != Failure.NOT_FOUND }?.let { return Answer(null, it, left) }
                val lines = timetable.value.orEmpty()
                val like = unnamed(n)
                val planned = (if (day != null) on(lines, like, day, zone) else upcoming(lines, like, now))?.let { dated(it, now) }
                return Answer(planned, if (planned != null) null else if (day != null && lines.isNotEmpty()) Failure.NOT_THAT_DAY else Failure.NOT_FOUND, left)
            }
            val f = first.value ?: return Answer(null, first.failure ?: Failure.NO_ANSWER, left)
            nearest = f
            // The one-flight question was tried with a callsign; the other two only with a ticket's number, which this reply has.
            if (n.callsign) FlightNumber.read(f.number)?.takeUnless { it.callsign }?.let { number = it }
            if (day != null) {
                if (counts(f) && leaves(f, day, zone)) return Answer(f, null, left)
                val timetable = timetable()
                val lines = timetable.value ?: return Answer(null, timetable.failure?.takeIf { it != Failure.NOT_FOUND } ?: Failure.NOT_THAT_DAY, left)
                return on(lines, f, day, zone)?.let { Answer(dated(it, now), null, left) } ?: Answer(null, Failure.NOT_THAT_DAY, left)
            }
            if (counts(f) && !FlightRules.over(f, now)) return Answer(f, null, left)
            val soon = ask(SCHEDULES) { schedules(it) }
            coming = soon.value.orEmpty().filter(::counts)
            FlightRules.next(coming, now)?.let { return Answer(named(it, f), null, left) }
            val lines = timetable()
            upcoming(lines.value.orEmpty(), f, now)?.let { return Answer(dated(it, now), null, left) }
            // No next flight, and one of the two was not to be had (no connection, told to slow down): that is what went wrong.
            // To follow the one that landed hours ago instead would be to say "Landed" and never ask again.
            listOf(soon.failure, lines.failure).firstOrNull { it != null && it != Failure.NOT_FOUND }?.let { return Answer(null, it, left) }
            // Nothing ahead that anyone knows of: the one that was, as it was. (Another airport's is not the flight that was asked for.)
            return if (counts(f)) Answer(f, null, left) else Answer(null, Failure.NOT_FOUND, left)
        }
        val answer = found()
        if (withTimetable && answer.flight != null) timetable()
        // (How many lookups are left is what the last reply said, which can be the timetable's.)
        return Heard(Answer(answer.flight, answer.failure, left), nearest, coming, table?.value.orEmpty()).takeIf { !unsent }
    }

    /** A flight of [n] of which the number is all that is known: what a timetable's flight is said with when the service knows no flight to take the names from. */
    private fun unnamed(n: FlightNumber): Flight = Flight(n.code, "", FlightEnd(""), FlightEnd(""), FlightState.PLANNED)

    /** What sending came to. Whoever carries requests must not throw; if one does, its message goes nowhere (it could hold the address, and with it the key). */
    private fun send(request: Request, get: (Request) -> Reply): Reply = try { get(request) } catch (_: Exception) { Reply.Failed(Why.UNREADABLE) }

    /** No connection is itself; everything else that is no reply is "no answer". */
    private fun failure(reply: Reply.Failed): Failure = if (reply.why == Why.OFFLINE) Failure.OFFLINE else Failure.NO_ANSWER

    /** What asking again about one flight came to. */
    sealed interface Again {
        /** The same flight, as the service says it now. */
        data class Is(val flight: Flight) : Again
        /** The service has nothing more to say about it: it answers with a later day's flight from its airport (or with another flight once it was to have landed), knows none, or will not answer this key. Asking ends. */
        data object Gone : Again
        /** No word about it yet (the service answers with the flight before, or another airport's): the plan stands, and the next ask comes when it is due. */
        data object NotYet : Again
        /** No answer this time (no connection, a reply nobody can read): worth another try soon. */
        data object Failed : Again
    }

    /** [again]; how many lookups the key has left, where the reply said; why nothing came, where nothing did; and how long a "slow down" asked to be left alone. */
    class Asked(val again: Again, val left: Int?, val failure: Failure? = null, val retryAfterSec: Long? = null)

    /**
     * Asks about the flight [was] once more, at [now]: one request in the usual case. From ten hours
     * before it leaves ([FlightRules.listed]) that is the coming hours' list (`schedules`), which has
     * each flight of the number as it is, and the flight is the one of its airport and its day there.
     * The one-flight question (`flight`) cannot be trusted with that for a number that flies more than
     * once a day (see above). It is asked further off, from an hour after the landing, for a codeshare,
     * and where the list has no such flight: one request more then.
     *
     * A bar item follows one flight, not "the next" of its number: when the service has gone on to a
     * later day's flight from the same airport, that is the end of the asking, never a new flight to
     * count down to. Another airport's flight, or an earlier one, is no word about this one: the service
     * is not there yet, or speaks of another flight of the trip. The plan stands, until this one's time
     * to land has passed; then nobody will say more of it, and that is the end too. (1.0 took San
     * Francisco's evening flight for the end of Newark's afternoon one, stopped asking, and never
     * showed that it was late.)
     *
     * Null: the request was not sent at all, as for [lookup].
     */
    fun again(n: FlightNumber, was: Flight, now: Instant, get: (Request) -> Reply): Asked? {
        var listLeft: Int? = null
        if (FlightRules.listed(was, now)) {
            // Asked for by the number as it was entered; a callsign by the ticket's number the flight that is followed has.
            val ticket = if (n.callsign) FlightNumber.read(was.number)?.takeUnless { it.callsign } ?: n else n
            val list = when (val reply = send(request(SCHEDULES, ticket), get)) {
                is Reply.Ok -> schedules(reply.text)
                is Reply.Failed -> return if (reply.why == Why.OFF) null else Asked(Again.Failed, null, failure(reply), reply.retryAfterSec)
            }
            list.value?.firstOrNull { FlightRules.same(was, it) }?.let { return Asked(Again.Is(listed(it, was)), list.left) }
            when (list.failure) {
                // The list has no such flight: the one-flight question may know it.
                null, Failure.NOT_FOUND -> listLeft = list.left
                // A key that is refused or used up may be put right; anything else is no answer this time, and no second request is spent on it.
                Failure.REFUSED, Failure.USED_UP -> return Asked(Again.Gone, list.left, list.failure)
                else -> return Asked(Again.Failed, list.left, list.failure)
            }
        }
        val r = when (val reply = send(request(FLIGHT, n), get)) {
            is Reply.Ok -> flight(reply.text)
            is Reply.Failed -> return if (reply.why == Why.OFF) null else Asked(Again.Failed, listLeft, failure(reply), reply.retryAfterSec)
        }
        val got = r.value
        val again = when {
            got != null && FlightRules.same(was, got) -> Again.Is(got)
            // A flight with no planned time: nobody can tell which day's it is, so it is no word about this one, and no end of it either.
            got != null && got.from.planned == null -> Again.Failed
            // Another flight of the number. From the same airport on a later day: this one is over. Another airport's, or an earlier
            // one: no word about this one, unless its time to land has passed, so that nobody will say more of it.
            got != null -> if (laterFromThere(got, was) || FlightRules.pastLanding(was, now)) Again.Gone else Again.NotYet
            r.failure == Failure.OFFLINE || r.failure == Failure.NO_ANSWER -> Again.Failed
            r.failure == Failure.NOT_FOUND && was.timetable -> Again.NotYet
            else -> Again.Gone
        }
        return Asked(again, r.left ?: listLeft, r.failure ?: Failure.NO_ANSWER.takeIf { again == Again.Failed })
    }

    /**
     * True if [a] leaves from [b]'s airport on a later day. (Another airport's later date says nothing: a
     * later leg of the same trip can leave after midnight, Singapore 23:55 to Frankfurt, then on to New York.)
     */
    private fun laterFromThere(a: Flight, b: Flight): Boolean {
        if (a.from.code != b.from.code) return false
        val x = a.from.planned?.toLocalDate() ?: return false
        val y = b.from.planned?.toLocalDate() ?: return false
        return x.isAfter(y)
    }

    /** [got], a flight of the coming hours' list, which names no airline, no cities and no aircraft: with the ones [was] had. */
    private fun listed(got: Flight, was: Flight): Flight = named(got, was).let { if (it.aircraft == null) it.copy(aircraft = was.aircraft) else it }

    /** How long what an ask came to stands before the same thing is asked again: by hand, or for a number that was not found. */
    fun keep(failure: Failure?): Duration = when (failure) {
        // No connection may be back in a moment, and a try without one reaches nobody.
        Failure.OFFLINE -> Duration.ofSeconds(10)
        // A number nobody flies, or not on that day, costs two lookups to find out and stays so: an hour.
        Failure.NOT_FOUND, Failure.NOT_THAT_DAY -> Duration.ofHours(1)
        // An answer, no answer (an error of the service's, a limit for the minute, a "slow down"), a refused key, a month's lookups used up: two minutes.
        else -> Duration.ofMinutes(2)
    }

    fun flight(body: String): Read<Flight> = read(body) { (it as? JsonObject)?.let(::flight) }

    fun schedules(body: String): Read<List<Flight>> = read(body) { r -> (r as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::flight) } }

    fun routes(body: String): Read<List<Route>> = read(body) { r -> (r as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::route) } }

    private fun <T> read(body: String, take: (JsonElement?) -> T?): Read<T> {
        val all = try { Json.parseToJsonElement(body) as? JsonObject } catch (_: Exception) { null } ?: return Read(null, Failure.NO_ANSWER)
        // The one thing read of the object that repeats the request: how many lookups are left. The key beside it is never touched.
        val left = ((all["request"] as? JsonObject)?.get("key") as? JsonObject)?.number("limits_total")?.toInt()?.coerceAtLeast(0)
        (all["error"] as? JsonObject)?.let { e ->
            return Read(null, when (e.text("code", 40)) {
                "not_found" -> Failure.NOT_FOUND
                "unknown_api_key", "expired_api_key" -> Failure.REFUSED
                "month_limit_exceeded" -> Failure.USED_UP
                else -> Failure.NO_ANSWER
            }, left)
        }
        val value = try { take(all["response"]) } catch (_: Exception) { null }
        return if (value == null) Read(null, Failure.NO_ANSWER, left) else Read(value, null, left)
    }

    private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    /** A text of the reply made fit to show: one line, no longer than [max], and null where there is none. */
    private fun JsonObject.text(key: String, max: Int): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.let { NowPlayingRules.oneLine(it.content, max) }?.takeIf { it.isNotEmpty() }
    private fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
    /** A time of the reply; one in a year nobody flies in is no time. */
    private fun JsonObject.time(key: String): LocalDateTime? =
        text(key, 16)?.let { runCatching { LocalDateTime.parse(it, TIME) }.getOrNull() }?.takeIf { it.year in FlightRules.YEARS }

    private val CALLSIGN = Regex("[A-Z0-9]{3,8}")

    /** The number as air traffic control says it. It becomes part of an address (the flight's page), so anything but letters and digits is none. */
    private fun JsonObject.callsign(): String? = text("flight_icao", 16)?.takeIf { CALLSIGN.matches(it) }

    private fun flight(m: JsonObject): Flight? {
        val number = m.text("flight_iata", 8) ?: m.text("flight_icao", 8) ?: return null
        val leaves = clock(m, "dep")
        val lands = clock(m, "arr")
        val from = end(m, "dep", leaves ?: 0) ?: return null
        val to = end(m, "arr", lands ?: 0) ?: return null
        // An end with a time and no clock to read it by: the time is right at its airport, the moment it stands for is anybody's guess.
        val loose = (leaves == null && from.time != null) || (lands == null && to.time != null)
        val state = when (m.text("status", 20)) {
            "scheduled" -> FlightState.PLANNED
            "en-route", "active" -> FlightState.IN_AIR
            "landed" -> FlightState.LANDED
            "cancelled" -> FlightState.CANCELED
            "diverted" -> FlightState.DIVERTED
            // A word not seen before: what the times say.
            else -> if (to.actual != null) FlightState.LANDED else if (from.actual != null) FlightState.IN_AIR else FlightState.PLANNED
        }
        return Flight(number, m.text("airline_name", 40).orEmpty(), from, to, state, flownAs = m.text("cs_flight_iata", 8), loose = loose,
            aircraft = m.text("model", 60)?.let(::aircraft), callsign = m.callsign())
    }

    private val REMARK = Regex("\\s*\\([^)]*\\)")

    /** The aircraft's type as it is said: the service's "Boeing 747-8 pax" without what it adds to the type (a remark in brackets, and "pax" for one that carries passengers). */
    private fun aircraft(model: String): String? = model.replace(REMARK, "").trim().removeSuffix(" pax").trim().take(40).trim().takeIf { it.isNotEmpty() }

    /**
     * How far an end's clock is from UTC, in minutes: read off a time of the fields that start with
     * [p] (`dep`, `arr`) that is given both ways. Null: none is, so nobody knows which moment its
     * times stand for (taken as UTC they would be out by hours, with nothing saying so).
     */
    private fun clock(m: JsonObject, p: String): Int? = listOf("time", "estimated", "actual").firstNotNullOfOrNull { k ->
        val local = m.time("${p}_$k"); val utc = m.time("${p}_${k}_utc")
        // (No clock on earth is further from UTC than fourteen hours: anything else is not an airport's time.)
        if (local != null && utc != null) Duration.between(utc, local).toMinutes().takeIf { it in -FlightRules.MOST_OFFSET..FlightRules.MOST_OFFSET }?.toInt() else null
    }

    /** One end, from the fields that start with [p], with its clock [offset] minutes from UTC. */
    private fun end(m: JsonObject, p: String, offset: Int): FlightEnd? {
        val code = m.text("${p}_iata", 4) ?: m.text("${p}_icao", 4) ?: return null
        return FlightEnd(
            code, m.text("${p}_city", 40).orEmpty(),
            m.time("${p}_time"), m.time("${p}_estimated"), m.time("${p}_actual"), offset,
            m.text("${p}_terminal", 12), m.text("${p}_gate", 8), if (p == "arr") m.text("arr_baggage", 8) else null,
        )
    }

    private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")
    private val DAYS = mapOf("mon" to DayOfWeek.MONDAY, "tue" to DayOfWeek.TUESDAY, "wed" to DayOfWeek.WEDNESDAY, "thu" to DayOfWeek.THURSDAY,
        "fri" to DayOfWeek.FRIDAY, "sat" to DayOfWeek.SATURDAY, "sun" to DayOfWeek.SUNDAY)
    /** No flight is two days long: a timetable's line that says so is no line. */
    private const val MOST_MINUTES = 48 * 60

    private fun route(m: JsonObject): Route? {
        fun clock(key: String) = m.text(key, 5)?.let { runCatching { LocalTime.parse(it, CLOCK) }.getOrNull() }
        /**
         * A clock against UTC, from the same moment said both ways without a day. Ten hours behind and
         * fourteen ahead are the same two clocks, and which it is cannot be told from them: taken as
         * between eleven hours behind and thirteen ahead, which is right for Hawaii, New Zealand, Fiji
         * and Tonga and wrong only for American Samoa, Niue and Kiritimati. ([planned] takes an
         * airport's clock from a dated reply where it has one.)
         */
        fun offset(local: LocalTime?, utc: LocalTime?): Int {
            if (local == null || utc == null) return 0
            val d = (local.toSecondOfDay() - utc.toSecondOfDay()) / 60
            return if (d > 13 * 60) d - 24 * 60 else if (d <= -11 * 60) d + 24 * 60 else d
        }
        fun strings(key: String) = (m[key] as? JsonArray)?.mapNotNull { e -> (e as? JsonPrimitive)?.takeIf { it.isString }?.content }
        fun one(key: String) = strings(key)?.singleOrNull()?.let { NowPlayingRules.oneLine(it, 12) }?.takeIf { it.isNotEmpty() }
        val leaves = clock("dep_time") ?: return null
        val minutes = m.number("duration")?.toInt()?.takeIf { it in 1..MOST_MINUTES } ?: return null
        val days = strings("days")?.mapNotNull { DAYS[it] }?.toSet().orEmpty()
        return Route(
            m.text("dep_iata", 4) ?: return null, m.text("arr_iata", 4) ?: return null, leaves, minutes, days,
            offset(leaves, clock("dep_time_utc")), offset(clock("arr_time"), clock("arr_time_utc")), one("dep_terminals"), one("arr_terminals"),
            m.callsign(),
        )
    }

    /**
     * The flight a line of the timetable is on [day], said with the names [like] has (the same number,
     * another day). A plan: no gate, no delay. Null if it does not fly on that day of the week. Where
     * [like] has the same airport with a dated time, that reply's clock is taken for it: read off a
     * date, it is exact, and the timetable's own is a guess for the clocks furthest from UTC.
     */
    fun planned(route: Route, like: Flight, day: LocalDate): Flight? {
        if (route.days.isNotEmpty() && day.dayOfWeek !in route.days) return null
        fun known(code: String) = listOf(like.from, like.to).firstOrNull { it.code == code }
        // (Not from a reply whose own clocks could not be read.)
        val fromOffset = known(route.from)?.takeIf { it.time != null && !like.loose }?.offset ?: route.fromOffset
        val toOffset = known(route.to)?.takeIf { it.time != null && !like.loose }?.offset ?: route.toOffset
        val leaves = day.atTime(route.leaves)
        val lands = LocalDateTime.ofEpochSecond(leaves.toEpochSecond(ZoneOffset.ofTotalSeconds(fromOffset * 60)) + route.minutes * 60L, 0, ZoneOffset.ofTotalSeconds(toOffset * 60))
        return Flight(
            like.number, like.airline,
            FlightEnd(route.from, known(route.from)?.city.orEmpty(), planned = leaves, offset = fromOffset, terminal = route.fromTerminal),
            FlightEnd(route.to, known(route.to)?.city.orEmpty(), planned = lands, offset = toOffset, terminal = route.toTerminal),
            FlightState.PLANNED, like.flownAs, timetable = true, callsign = like.callsign ?: route.callsign,
        )
    }

    /**
     * The next flight of the timetable to leave after [now], within a week: the soonest among the
     * lines that start where [like] started (a number with two legs stays on its leg), else the
     * soonest of the others.
     */
    fun upcoming(routes: List<Route>, like: Flight, now: Instant): Flight? {
        fun soonest(lines: List<Route>): Flight? = lines.mapNotNull { upcoming(it, like, now) }.minByOrNull { leaves(it) ?: Instant.MAX }
        val (same, other) = routes.partition { it.from == like.from.code }
        return soonest(same) ?: soonest(other)
    }

    /** The next flight of one line of the timetable to leave after [now], within a week. */
    private fun upcoming(route: Route, like: Flight, now: Instant): Flight? {
        val today = LocalDateTime.ofEpochSecond(now.epochSecond, 0, ZoneOffset.ofTotalSeconds(route.fromOffset * 60)).toLocalDate()
        return (-1L..7L).firstNotNullOfOrNull { d -> planned(route, like, today.plusDays(d))?.takeIf { leaves(it)?.isAfter(now) == true } }
    }

    /**
     * The day [f] leaves at the time [t] of its start: by the clock of [zone], where a day of the
     * device's is meant, else the airport's own date.
     */
    private fun leavesOn(f: Flight, t: LocalDateTime, zone: ZoneId?): LocalDate = if (zone == null) t.toLocalDate() else LocalDate.ofInstant(f.from.moment(t), zone)

    /** True if [f] leaves on [day], by its plan or by the time it is now expected to: the service's flight is that day's then. */
    private fun leaves(f: Flight, day: LocalDate, zone: ZoneId?): Boolean = listOfNotNull(f.from.planned, f.from.time).any { leavesOn(f, it, zone) == day }

    /**
     * The timetable's flight on [day], whichever of its lines flies then; the lines that start where
     * [like] started come first. With a [zone], [day] is a day of the device's and the flight is the
     * one that leaves on it by that clock, which can be the day before or after at its airport (two,
     * between the clocks furthest apart). Without one, [day] is the airport's own date.
     */
    fun on(routes: List<Route>, like: Flight, day: LocalDate, zone: ZoneId? = null): Flight? =
        routes.sortedBy { it.from != like.from.code }.firstNotNullOfOrNull { on(it, like, day, zone) }

    /** The flight of one line of the timetable on [day], which is a day of the device's with a [zone] and the airport's own date without. */
    private fun on(route: Route, like: Flight, day: LocalDate, zone: ZoneId?): Flight? =
        if (zone == null) planned(route, like, day)
        else (-2L..2L).firstNotNullOfOrNull { d -> planned(route, like, day.plusDays(d))?.takeIf { f -> f.from.planned?.let { leavesOn(f, it, zone) } == day } }

    /** The zones there are, for [steady]: read once. */
    private val ZONES: List<ZoneId> by lazy { ZoneId.getAvailableZoneIds().mapNotNull { runCatching { ZoneId.of(it) }.getOrNull() } }

    /**
     * True if the clocks a timetable's flight was reckoned with still hold on its day. The timetable
     * gives each airport's clock as it is today and no zone, so nobody can say what that clock will
     * be after a change. What can be said: whether any place whose clock stands where this airport's
     * stands today changes it between [now] and the flight. If none does, the moments are right; if
     * one does (Frankfurt's, on a day after the end of summer time), they may be an hour out.
     */
    fun steady(f: Flight, now: Instant): Boolean = listOf(f.from, f.to).all { e ->
        val then = e.time?.let(e::moment) ?: return@all true
        ZONES.none { z -> z.rules.getOffset(now).totalSeconds == e.offset * 60 && z.rules.getOffset(then).totalSeconds != e.offset * 60 }
    }

    /** A timetable's flight with what is known of its clocks: [Flight.loose] where they may not hold on its day. */
    private fun dated(f: Flight, now: Instant): Flight = if (f.timetable && !steady(f, now)) f.copy(loose = true) else f

    /** [f], a flight from a reply that names no airline and no cities (`schedules`), with the names [like] has for them. */
    fun named(f: Flight, like: Flight): Flight {
        fun end(e: FlightEnd) = listOf(like.from, like.to).firstOrNull { it.code == e.code }?.let { e.copy(city = e.city.ifEmpty { it.city }) } ?: e
        return f.copy(airline = f.airline.ifEmpty { like.airline }, from = end(f.from), to = end(f.to), callsign = f.callsign ?: like.callsign)
    }
}
