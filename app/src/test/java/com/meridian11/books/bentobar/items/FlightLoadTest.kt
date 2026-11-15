package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.data.Kept
import com.meridian11.books.bentobar.data.Online
import com.meridian11.books.bentobar.data.Online.Service.AIRLABS
import com.meridian11.books.bentobar.items.AirLabs.Failure
import com.meridian11.books.bentobar.items.FlightLoad.Outcome
import com.meridian11.books.bentobar.items.FlightRules.Tracked
import com.meridian11.books.bentobar.net.FakeHttp
import com.meridian11.books.bentobar.net.Host
import com.meridian11.books.bentobar.net.Http
import com.meridian11.books.bentobar.net.Reply
import com.meridian11.books.bentobar.net.Request
import com.meridian11.books.bentobar.net.Transport
import com.meridian11.books.bentobar.net.Why
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Executor

/**
 * The Flight item's requests and what it keeps: what is sent and when, that nothing is sent without
 * a key and the switch, that the key is in nothing that is kept, and that a restart, a night with the
 * lid closed and a landed flight cost no lookup. The service is a fake; the key is `test-key`.
 */
class FlightLoadTest {
    private val dir = File(System.getProperty("java.io.tmpdir"), "bentobar-flight-${System.nanoTime()}")
    private val key = "test-key"
    private val item = "item1"
    private val ids = setOf(item)
    private fun reply(name: String): String = javaClass.getResource("/airlabs/$name.json")!!.readText()
    private fun at(text: String): Instant = Instant.parse(text)
    private fun ms(text: String) = at(text).toEpochMilli()
    /** When the saved replies were asked for. */
    private val asked = ms("2026-10-02T07:29:00Z")
    private val min = 60_000L

    @Before fun fresh() {
        Online.init(dir)
        Online.saveKey(AIRLABS, key)
        FlightLoad.forget(keyGone = false)
    }

    @After fun gone() {
        dir.deleteRecursively()
        FlightLoad.forget(keyGone = false)
        // Leave nothing switched on for whoever runs next.
        Online.init(File(dir, "empty"))
    }

    /**
     * The service as the test says it is right now: it answers by a request's path, and remembers what it was asked.
     * Where a test gives the one-flight question's answer and not the coming hours' list's, the list says the same: that
     * flight as its one row, or the same error. That is the service for a number that flies once a day; a test about
     * one that flies more often gives the list itself. (A test that answers every path with one reply gets it as the
     * list's one row too.)
     */
    private class Service : Transport {
        val asked = ArrayList<Request>()
        var answer: (Request) -> Reply = { Reply.Failed(Why.STATUS, 404) }
        override fun get(request: Request): Reply {
            asked += request
            val reply = answer(request)
            return if (request.path == AirLabs.SCHEDULES && reply is Reply.Ok) Reply.Ok(oneRow(reply.text)) else reply
        }
        fun says(vararg byPath: Pair<String, String>) {
            val texts = byPath.toMap().toMutableMap()
            texts[AirLabs.FLIGHT]?.let { texts.putIfAbsent(AirLabs.SCHEDULES, it) }
            answer = { r -> texts[r.path]?.let { Reply.Ok(it) } ?: Reply.Failed(Why.STATUS, 404) }
        }
        /** A one-flight reply as a list of one: the same object with its `response` in an array; anything else as it is. */
        private fun oneRow(one: String): String = runCatching {
            val o = Json.parseToJsonElement(one).jsonObject
            val r = o["response"] as? JsonObject ?: return one
            JsonObject(o + ("response" to JsonArray(listOf(r)))).toString()
        }.getOrDefault(one)
        fun fails(why: Why, status: Int = 0, retryAfterSec: Long? = null) { answer = { Reply.Failed(why, status, retryAfterSec) } }
        val paths: List<String> get() = asked.map { it.path }
    }

    /** Runs [test] with the gate open (the service switched on, its item in the bar, the bar on screen) and this fake in the network's place. */
    private fun <T> online(test: (Service) -> T): T = FakeHttp.use { Service().let { Http.transport = it; test(it) } }

    private fun question(text: String, day: LocalDate? = null) = FlightLoad.question(item, text, day)!!
    private fun found(o: Outcome): Tracked = (o as Outcome.Found).tracked
    private fun failed(o: Outcome): Failure = (o as Outcome.Failed).failure
    private fun sent() = Http.sent(Host.AIRLABS)

    /** A reply as it really comes: with the object that repeats the request, the key and the caller's address in it. */
    private fun withRequest(reply: String, left: Int = 940): String = reply.replaceFirst("{", """{"request":{"lang":"en","currency":"USD","id":"abc","host":"airlabs.co",
        "key":{"id":7,"api_key":"$key","type":"free","limits_by_hour":2500,"limits_by_minute":250,"limits_by_month":1000,"limits_total":$left},
        "params":{"flight_iata":"LH455","api_key":"$key"},"client":{"ip":"203.0.113.7","geo":{"city":"San Francisco"},"connection":{"isp_name":"Example Net"}}},""")

    /** Everything under the test's directory that holds [text], by file name. */
    private fun filesWith(text: String) = dir.walkTopDown().filter { it.isFile && it.readText().contains(text) }.map { it.name }.toList()

    /** Tracks LH 455 as it was in the air on 2 October, and takes the answer. */
    private fun follow(net: Service, now: Long = asked): Tracked {
        net.says(AirLabs.FLIGHT to withRequest(reply("flight-LH455-in-the-air")))
        return found(FlightLoad.track(question("lh455"), now)).also { assertTrue(FlightLoad.take(item, it, now)) }
    }

    // ---- nothing without a key and the switch

    @Test fun withoutAKeyNothingIsSent() {
        Online.removeKey(AIRLABS)
        online { net ->
            net.says(AirLabs.FLIGHT to reply("flight-LH455-in-the-air"))
            assertEquals(Outcome.Unasked, FlightLoad.track(question("LH455"), asked))
            assertNull(FlightLoad.load(item, null, asked, ids))
            assertEquals(emptyList<Request>(), net.asked)
            assertEquals(0, sent())
        }
    }

    @Test fun withTheSwitchOffNothingIsSentThoughTheKeyIsThere() {
        Online.turnOff(AIRLABS)
        assertTrue(Online.hasKey(AIRLABS))
        online { net ->
            net.says(AirLabs.FLIGHT to reply("flight-LH455-in-the-air"))
            assertEquals(Outcome.Unasked, FlightLoad.track(question("LH455"), asked))
            assertNull(FlightLoad.load(item, null, asked, ids))
            assertEquals(emptyList<Request>(), net.asked)
            assertEquals(0, sent())
        }
    }

    @Test fun whatIsNotAFlightNumberIsNeverAsked() {
        online { net ->
            net.says(AirLabs.FLIGHT to reply("flight-LH455-in-the-air"))
            // What the field does with its text on Track: only a flight number makes a question, and only a question is sent.
            fun entered(text: String): Outcome? = FlightLoad.question(item, text, null)?.let { FlightLoad.track(it, asked) }
            for (text in listOf("hello", "", "LH", "455", "LH 455 tomorrow", "api_key=x", "LH455&flight_iata=UA1")) assertNull(text, entered(text))
            assertEquals(emptyList<Request>(), net.asked)
            assertEquals(0, sent())
            // The same way does send for a number, with the service there to answer: the zero above is not zero by construction.
            assertTrue(entered("lh455") is Outcome.Found)
            // (Two requests: the one flight, and the timetable, which says whether the number flies more than once that day.)
            assertEquals(List(2) { listOf("flight_iata" to "LH455") }, net.asked.map { it.query })
            assertEquals(2, sent())
        }
    }

    // ---- what is sent

    @Test fun aPressOfTrackSendsTheNumberAndTheKeyToOneHostAndNothingElse() {
        online { net ->
            net.says(AirLabs.FLIGHT to withRequest(reply("flight-LH455-in-the-air")))
            val t = found(FlightLoad.track(question("lh 455"), asked))
            // Two requests, for the one flight and for the number's timetable: one host, and the same two things in each.
            assertEquals(listOf("/api/v9/flight", "/api/v9/routes"), net.paths)
            for (r in net.asked) {
                assertEquals(Host.AIRLABS, r.host)
                assertEquals(listOf("flight_iata" to "LH455"), r.query)
            }
            assertEquals(2, sent())
            assertEquals(0, Http.sent(Host.OPEN_METEO) + Http.sent(Host.OPEN_METEO_GEOCODING))
            // What came back: the flight, the day it leaves, and how many lookups are left.
            assertEquals("LH455", t.number)
            assertEquals("2026-10-01", t.day)
            assertEquals(FlightState.IN_AIR, t.flight!!.state)
            assertEquals(940, t.left)
            assertEquals(940, FlightLoad.left)
            assertEquals(asked, t.askedAt); assertEquals(asked, t.heardAt)
        }
    }

    @Test fun aCallsignIsAskedForAsOneAndADayTakesTheTimetable() {
        online { net ->
            net.says(AirLabs.FLIGHT to reply("flight-LH455-in-the-air"), AirLabs.ROUTES to reply("routes-LH455"))
            val t = found(FlightLoad.track(question("dlh455", LocalDate.of(2026, 10, 3)), asked))
            assertEquals(listOf("/api/v9/flight", "/api/v9/routes"), net.paths)
            // The callsign for the one flight; for the timetable the ticket's number, which the first reply named.
            assertEquals(listOf("flight_icao" to "DLH455"), net.asked[0].query)
            assertEquals(listOf("flight_iata" to "LH455"), net.asked[1].query)
            assertEquals("DLH455", t.number)
            assertEquals("2026-10-03", t.day)
            assertTrue(t.flight!!.timetable)
            assertEquals(2, sent())
        }
    }

    @Test fun aDayChipIsTheDevicesDayAndWhatIsKeptIsTheFlightsOwn() {
        online { net ->
            net.says(AirLabs.FLIGHT to reply("flight-LH454-planned"))
            // The evening of 1 October in Honolulu. The flight leaves Frankfurt in an hour, on the 2nd there.
            val q = FlightLoad.question(item, "LH454", LocalDate.of(2026, 10, 1), ZoneId.of("Pacific/Honolulu"))!!
            val t = found(FlightLoad.track(q, asked))
            assertEquals(2, sent())
            assertEquals("2026-10-02", t.day)
        }
    }

    // ---- the key

    @Test fun whatIsKeptCannotContainTheKey() {
        online { net ->
            val t = follow(net)
            // The model itself, written out, and everything on the disk: the key is in its own file and nowhere else.
            assertFalse(Json.encodeToString(Tracked.serializer(), t).contains(key))
            assertFalse(t.toString().contains(key))
            assertEquals(listOf("online.json"), filesWith(key))
            // Nor is anything else of the object that repeats the request: the caller's address, its provider.
            assertEquals(emptyList<String>(), filesWith("203.0.113.7"))
            assertEquals(emptyList<String>(), filesWith("Example Net"))
            assertEquals(emptyList<String>(), filesWith("api_key"))
            // The same after it was asked about again, after a refused key, and after the service moved on.
            net.says(AirLabs.FLIGHT to withRequest(reply("flight-LH455-landed"), left = 939))
            val landed = FlightLoad.load(item, t, asked + 30 * min, ids)!!
            assertEquals(FlightState.LANDED, landed.flight!!.state)
            assertEquals(939, landed.left)
            net.says(AirLabs.FLIGHT to withRequest(reply("error-unknown-key")))
            assertEquals(Failure.REFUSED, FlightLoad.load(item, landed, asked + 60 * min, ids)!!.failure)
            net.says(AirLabs.FLIGHT to withRequest(reply("flight-LH454-planned")))
            assertTrue(FlightLoad.load(item, landed, asked + 90 * min, ids)!!.ended)
            assertEquals(listOf("online.json"), filesWith(key))
            assertEquals(emptyList<String>(), filesWith("203.0.113.7"))
            // And what is kept is there, and reads as the flight.
            assertTrue(Kept.fetched(AIRLABS).read(item)!!.text.contains("\"LH455\""))
        }
    }

    @Test fun aNewKeyStartsWithNoCountOfLookupsAlsoInWhatIsKept() {
        online { net ->
            // The old key had twelve lookups left: too few to ask unasked, so the flight was left alone.
            net.says(AirLabs.FLIGHT to withRequest(reply("flight-LH455-in-the-air"), left = 12))
            val t = found(FlightLoad.track(question("lh455"), asked)).also { assertTrue(FlightLoad.take(item, it, asked)) }
            assertEquals(12, t.left)
            assertNull(FlightRules.every(t, at("2026-10-02T07:35:00Z")))
            // Replace key.
            Online.saveKey(AIRLABS, "another-test-key")
            FlightLoad.newKey()
            assertNull(FlightLoad.left)
            // What is kept says nothing of the old key's count any more, and is as old as it was: the flight has its turn again,
            // now and after a restart, and no menu says "few lookups left" of a key with a thousand.
            val kept = FlightLoad.kept(item, asked + 5 * min, ids)!!
            assertEquals(t.copy(left = null), kept.value)
            assertEquals(5 * min, kept.ageMs)
            assertNull(FlightLoad.left)
            assertEquals(30 * min, FlightRules.every(kept.value, at("2026-10-02T07:35:00Z")))
            // Nobody was asked for any of this: the two requests are the press of Track's.
            assertEquals(2, sent())
        }
    }

    @Test fun nothingThatPrintsAValueSaysWhoseFlightItIs() {
        online { net ->
            val t = follow(net)
            assertEquals("Tracked(a flight)", t.toString())
            assertEquals("Tracked(nothing)", Tracked().toString())
            assertEquals("Question(a flight)", question("LH455").toString())
            assertEquals(listOf("airlabs.co/api/v9/flight", "airlabs.co/api/v9/routes"), net.asked.map { it.toString() })
        }
    }

    // ---- a number that was not found

    @Test fun aNumberThatWasNotFoundIsRememberedForAnHour() {
        online { net ->
            net.says(AirLabs.FLIGHT to reply("error-not-found"), AirLabs.ROUTES to """{"response":[]}""")
            assertEquals(Failure.NOT_FOUND, failed(FlightLoad.track(question("LH9999"), asked)))
            assertEquals(2, sent())                                   // finding out took two lookups
            // A second try within the hour sends nothing.
            assertEquals(Failure.NOT_FOUND, failed(FlightLoad.track(question("lh 9999"), asked + 5 * min)))
            assertEquals(Failure.NOT_FOUND, failed(FlightLoad.track(question("LH9999"), asked + 59 * min)))
            assertEquals(2, sent())
            // Another number is asked for, and so is the same one on a day of its own.
            FlightLoad.track(question("LH9998"), asked + 5 * min)
            assertEquals(4, sent())
            FlightLoad.track(question("LH9999", LocalDate.of(2026, 10, 3)), asked + 5 * min)
            assertEquals(6, sent())
            // After the hour it is asked for again.
            FlightLoad.track(question("LH9999"), asked + 60 * min)
            assertEquals(8, sent())
        }
    }

    @Test fun aDayTheNumberDoesNotFlyOnIsRememberedTooButWhatMayPassIsNot() {
        online { net ->
            net.says(AirLabs.FLIGHT to reply("flight-LH455-in-the-air"), AirLabs.ROUTES to reply("routes-LH455").replace(Regex("\"days\": \\[[^]]*]"), "\"days\": [\"mon\"]"))
            val saturday = LocalDate.of(2026, 10, 3)
            assertEquals(Failure.NOT_THAT_DAY, failed(FlightLoad.track(question("LH455", saturday), asked)))
            assertEquals(2, sent())
            assertEquals(Failure.NOT_THAT_DAY, failed(FlightLoad.track(question("LH455", saturday), asked + 10 * min)))
            assertEquals(2, sent())
            // No connection: asked again as soon as the user asks again.
            net.fails(Why.OFFLINE)
            assertEquals(Failure.OFFLINE, failed(FlightLoad.track(question("LH454"), asked)))
            assertEquals(Failure.OFFLINE, failed(FlightLoad.track(question("LH454"), asked + 1_000)))
            assertEquals(4, sent())
            // A wrong key costs one request each time it is tried, and nothing more is sent for it.
            net.says(AirLabs.FLIGHT to reply("error-unknown-key"))
            assertEquals(Failure.REFUSED, failed(FlightLoad.track(question("LH454"), asked)))
            assertEquals(5, sent())
            assertEquals(listOf("/api/v9/flight"), net.paths.takeLast(1))
        }
    }

    // ---- what is kept

    @Test fun takingAnAnswerKeepsTheFlightOutsideTheLayoutAndARestartCostsNoLookup() {
        online { net ->
            val t = follow(net)
            assertEquals(setOf(item), Kept.own("flight").names())
            assertEquals(setOf(item), Kept.fetched(AIRLABS).names())
            // A restart: what was kept comes back, as old as it is, and nobody is asked.
            val kept = FlightLoad.kept(item, asked + 5 * min, ids)!!
            assertEquals(t, kept.value)
            assertEquals(5 * min, kept.ageMs)
            assertEquals(2, sent())
            // A clock that was set back makes nothing younger than new.
            assertEquals(0L, FlightLoad.kept(item, asked - 5 * min, ids)!!.ageMs)
            // An item that follows nothing has nothing kept.
            assertNull(FlightLoad.kept("item2", asked, setOf(item, "item2")))
        }
    }

    @Test fun anAnswerThatNobodyTakesChangesNothing() {
        online { net ->
            follow(net)
            // Track another flight: until its answer is taken, the item follows the first one.
            net.says(AirLabs.FLIGHT to reply("flight-LH454-planned"))
            val other = found(FlightLoad.track(question("LH454"), asked + min))
            assertEquals("LH455", FlightLoad.kept(item, asked + min, ids)!!.value.number)
            assertTrue(FlightLoad.take(item, other, asked + min))
            assertEquals("LH454", FlightLoad.kept(item, asked + min, ids)!!.value.number)
        }
    }

    @Test fun aLoadThatComesBackForAFlightTheItemNoLongerFollowsIsDropped() {
        online { net ->
            val old = follow(net)
            net.says(AirLabs.FLIGHT to reply("flight-LH454-planned"))
            val other = found(FlightLoad.track(question("LH454"), asked + min))
            // The refresh of the first flight is on its way while the second is taken: it must not put the first one back.
            net.answer = { FlightLoad.take(item, other, asked + min); Reply.Ok(reply("flight-LH455-landed")) }
            assertNull(FlightLoad.load(item, old, asked + min, ids))
            assertEquals("LH454", FlightLoad.kept(item, asked + min, ids)!!.value.number)
            // The same when tracking stopped under it.
            net.answer = { FlightLoad.stop(item); Reply.Ok(reply("flight-LH454-planned")) }
            assertNull(FlightLoad.load(item, other, asked + 2 * min, ids))
            assertEquals(emptySet<String>(), Kept.fetched(AIRLABS).names())
        }
    }

    @Test fun stopTrackingDeletesTheAnswerAndTheNote() {
        online { net ->
            follow(net)
            FlightLoad.stop(item)
            assertEquals(emptySet<String>(), Kept.own("flight").names())
            assertEquals(emptySet<String>(), Kept.fetched(AIRLABS).names())
            assertNull(FlightLoad.kept(item, asked, ids))
            // What the item holds from then on is "nothing followed", found out without a request.
            assertEquals(Tracked(), FlightLoad.load(item, null, asked, ids))
            assertEquals(2, sent())
        }
    }

    @Test fun removingTheKeyDeletesTheKeyTheFlightAndTheAnswer() {
        online { net ->
            follow(net)
            Online.removeKey(AIRLABS)
            FlightLoad.forget(keyGone = true)                          // what the item does when it is told
            assertEquals(emptySet<String>(), Kept.own("flight").names())
            assertEquals(emptySet<String>(), Kept.fetched(AIRLABS).names())
            assertEquals(emptyList<String>(), filesWith(key))
            assertNull(FlightLoad.left)
            assertNull(FlightLoad.load(item, null, asked, ids))
            assertEquals(2, sent())
        }
    }

    @Test fun switchingTheServiceOffDeletesTheAnswerAndKeepsWhatIsFollowedWhichIsLookedUpAfresh() {
        online { net ->
            follow(net)
            Online.turnOff(AIRLABS)
            FlightLoad.forget(keyGone = false)
            assertEquals(emptySet<String>(), Kept.fetched(AIRLABS).names())
            assertEquals(setOf(item), Kept.own("flight").names())
            // Off: nothing is asked.
            assertNull(FlightLoad.load(item, null, asked + min, ids))
            assertEquals(2, sent())
            // On again: the same number on the same day, with one request (it is still that day's flight).
            assertTrue(Online.turnOn(AIRLABS))
            assertNull(FlightLoad.kept(item, asked + 2 * min, ids))
            val again = FlightLoad.load(item, null, asked + 2 * min, ids)!!
            assertEquals("LH455", again.number); assertEquals("2026-10-01", again.day)
            assertEquals(FlightState.IN_AIR, again.flight!!.state)
            assertEquals(3, sent())
            assertEquals(listOf("flight_iata" to "LH455"), net.asked.last().query)
            assertEquals(setOf(item), Kept.fetched(AIRLABS).names())
        }
    }

    @Test fun anAnswerThatArrivesAfterTheSwitchWentOffIsNotKept() {
        online { net ->
            val t = follow(net)
            // The switch goes off while the request is on its way.
            net.answer = { Online.turnOff(AIRLABS); Reply.Ok(withRequest(reply("flight-LH455-landed"))) }
            FlightLoad.load(item, t, asked + 30 * min, ids)
            assertEquals(emptySet<String>(), Kept.fetched(AIRLABS).names())
            assertEquals(listOf("online.json"), filesWith(key))
        }
    }

    @Test fun aFlightLookedUpAfreshWithoutAConnectionIsTriedAgain() {
        online { net ->
            follow(net)
            Online.turnOff(AIRLABS); Online.turnOn(AIRLABS)
            Http.connected = { false }
            val first = FlightLoad.load(item, null, asked + min, ids)!!
            assertEquals("LH455", first.number)
            assertNull(first.flight)
            // Without a connection nothing was sent: no count of failures goes up, and it is tried again in two minutes each time.
            assertEquals(Failure.OFFLINE, first.failure); assertEquals(0, first.failures)
            assertEquals(2 * min, FlightRules.every(first, at("2026-10-02T07:30:00Z")))
            val second = FlightLoad.load(item, first, asked + 3 * min, ids)!!
            assertEquals(0, second.failures)
            assertEquals(2 * min, FlightRules.every(second, at("2026-10-02T07:32:00Z")))
            // What is kept of it reads back, so a restart knows what it was waiting for.
            assertEquals(second, FlightLoad.kept(item, asked + 3 * min, ids)!!.value)
        }
    }

    @Test fun whatBelongedToAnItemThatIsGoneGoesWithIt() {
        online { net ->
            val t = follow(net)
            assertTrue(FlightLoad.take("item2", t, asked))
            assertEquals(setOf(item, "item2"), Kept.own("flight").names())
            // The layout has only the first item now.
            FlightLoad.kept(item, asked, ids)
            assertEquals(setOf(item), Kept.own("flight").names())
            assertEquals(setOf(item), Kept.fetched(AIRLABS).names())
            // With no Flight item left at all nothing is cleared yet: the last one went a moment ago, and one may be back within it.
            FlightLoad.kept(item, asked, emptySet())
            assertEquals(setOf(item), Kept.own("flight").names())
        }
    }

    @Test fun whatWasKeptForTheLastFlightItemGoesAMomentAfterItWentIfNoneIsBack() {
        online { net ->
            follow(net)
            assertEquals(setOf(item), Kept.own("flight").names())
            assertEquals(setOf(item), Kept.fetched(AIRLABS).names())
            // The moment has passed (half a minute: not Undo's time, which has no end; see FlightRemovedTest), and an item is back: nothing is touched.
            assertTrue(FlightLoad.UNDO_MS >= 30_000)
            assertFalse(FlightLoad.clearWithout(ids))
            assertEquals(setOf(item), Kept.own("flight").names())
            assertEquals(setOf(item), Kept.fetched(AIRLABS).names())
            // It has passed and there is still no Flight item: its number, its day and its last answer go, and nothing of a flight stays for good.
            assertTrue(FlightLoad.clearWithout(emptySet()))
            assertEquals(emptySet<String>(), Kept.own("flight").names())
            assertEquals(emptySet<String>(), Kept.fetched(AIRLABS).names())
            assertEquals(emptyList<String>(), filesWith("LH455"))
            // The key is not a flight's: it stays where it is.
            assertEquals(listOf("online.json"), filesWith(key))
            assertEquals(2, sent())
        }
    }

    @Test fun whatCannotBeReadIsNotKept() {
        online { net ->
            val t = follow(net)
            Kept.fetched(AIRLABS).write(item, "not what was written here", asked)
            assertNull(FlightLoad.kept(item, asked, ids))
            assertEquals(emptySet<String>(), Kept.fetched(AIRLABS).names())
            // An answer for another flight than the one the item follows is not its answer.
            Kept.fetched(AIRLABS).write(item, Json.encodeToString(Tracked.serializer(), t.copy(number = "LH454")), asked)
            assertNull(FlightLoad.kept(item, asked, ids))
            // A flight with a clock nobody has is no flight.
            val f = t.flight!!
            Kept.fetched(AIRLABS).write(item, Json.encodeToString(Tracked.serializer(), t.copy(flight = f.copy(from = f.from.copy(offset = 99_999)))), asked)
            assertNull(FlightLoad.kept(item, asked, ids))
            assertEquals(emptySet<String>(), Kept.fetched(AIRLABS).names())
        }
    }

    // ---- asking again

    @Test fun askingAgainIsOneRequestAboutTheSameFlight() {
        online { net ->
            val t = follow(net)
            net.says(AirLabs.FLIGHT to reply("flight-LH455-landed"))
            val next = FlightLoad.load(item, t, asked + 30 * min, ids)!!
            // (The first two are the press of Track's.) In the air, it is the coming hours' list that is asked.
            assertEquals(listOf("/api/v9/flight", "/api/v9/routes", "/api/v9/schedules"), net.paths)
            assertEquals(listOf("flight_iata" to "LH455"), net.asked.last().query)
            assertEquals(FlightState.LANDED, next.flight!!.state)
            assertNull(next.failure); assertEquals(0, next.failures); assertFalse(next.ended)
            assertEquals(asked + 30 * min, next.askedAt); assertEquals(asked + 30 * min, next.heardAt)
            assertEquals(940, next.left)                               // this reply did not say: what was known stands
            // A landed flight is not asked about again.
            assertNull(FlightRules.every(next, at("2026-10-02T08:30:00Z")))
        }
    }

    @Test fun aFailedAskKeepsTheFlightSaysWhyAndCountsTheTriesThatWentOut() {
        online { net ->
            val t = follow(net)
            // No network: nothing leaves the device.
            Http.connected = { false }
            val first = FlightLoad.load(item, t, asked + 30 * min, ids)!!
            assertEquals(t.flight, first.flight)
            // A try that reached nobody doesn't make the next wait longer: at the gate, before the Wi-Fi is up, the back-off
            // would otherwise grow to half an hour before anything was ever asked.
            assertEquals(Failure.OFFLINE, first.failure); assertEquals(0, first.failures)
            assertEquals(asked + 30 * min, first.askedAt)
            assertEquals(asked, first.heardAt)                         // "updated" stays when it was last heard of
            assertEquals(0, FlightLoad.load(item, first, asked + 31 * min, ids)!!.failures)
            // A connection that broke after the request went out may have cost a lookup: that one counts, as for Weather.
            Http.connected = { true }
            net.fails(Why.OFFLINE)
            val reset = FlightLoad.load(item, first, asked + 31 * min, ids)!!
            assertEquals(Failure.OFFLINE, reset.failure); assertEquals(1, reset.failures)
            net.fails(Why.STATUS, 429, retryAfterSec = 900)
            val second = FlightLoad.load(item, reset, asked + 32 * min, ids)!!
            assertEquals(Failure.NO_ANSWER, second.failure); assertEquals(2, second.failures)
            assertEquals(900L, second.waitSec)
            assertEquals(15 * min, FlightRules.every(second, at("2026-10-02T08:01:00Z")))
            Http.resetCounts() // the service's wait has passed by the time of the next answer
            // The next answer puts all of it right.
            net.says(AirLabs.FLIGHT to reply("flight-LH455-in-the-air"))
            val third = FlightLoad.load(item, second, asked + 47 * min, ids)!!
            assertNull(third.failure); assertEquals(0, third.failures); assertNull(third.waitSec)
            assertEquals(asked + 47 * min, third.heardAt)
        }
    }

    @Test fun aRefusedOrUsedUpKeyStopsTheAskingUntilTheUserActs() {
        online { net ->
            val t = follow(net)
            net.says(AirLabs.FLIGHT to reply("error-unknown-key"))
            val refused = FlightLoad.load(item, t, asked + 30 * min, ids)!!
            assertEquals(Failure.REFUSED, refused.failure)
            assertEquals(t.flight, refused.flight)
            assertFalse(refused.ended)
            assertNull(FlightRules.every(refused, at("2026-10-02T08:00:00Z")))
            net.says(AirLabs.FLIGHT to """{"error":{"message":"x","code":"month_limit_exceeded"}}""")
            val spent = FlightLoad.load(item, t, asked + 30 * min, ids)!!
            assertEquals(Failure.USED_UP, spent.failure)
            assertNull(FlightRules.every(spent, at("2026-10-02T08:00:00Z")))
            // A new key, or Refresh: the next ask with an answer puts it right.
            net.says(AirLabs.FLIGHT to reply("flight-LH455-in-the-air"))
            val fine = FlightLoad.load(item, spent, asked + 40 * min, ids)!!
            assertNull(fine.failure)
            assertEquals(30 * min, FlightRules.every(fine, at("2026-10-02T07:30:00Z")))
        }
    }

    @Test fun whenTheServiceGoesOnToTheNextDaysFlightTheAskingEndsAndTheFlightStays() {
        online { net ->
            val t = follow(net)
            net.says(AirLabs.FLIGHT to Regex("\\d{4}-\\d{2}-\\d{2}").replace(reply("flight-LH455-in-the-air")) { LocalDate.parse(it.value).plusDays(1).toString() })
            val over = FlightLoad.load(item, t, asked + 30 * min, ids)!!
            // An item follows one flight, never the next day's of the same number.
            assertTrue(over.ended)
            assertEquals(t.flight, over.flight)
            assertEquals("2026-10-01", over.day)
            assertNull(over.failure)
            assertEquals(asked, over.heardAt)
            assertNull(FlightRules.every(over, at("2026-10-02T08:00:00Z")))
        }
    }

    @Test fun aRequestThatIsNotSentLeavesEverythingAsItIs() {
        online { net ->
            val t = follow(net)
            val before = Kept.fetched(AIRLABS).read(item)!!.text
            // The bar hid under the load: the request code refuses, which is neither an answer nor a failure.
            Http.allowed = { false }
            assertNull(FlightLoad.load(item, t, asked + 30 * min, ids))
            assertEquals(Outcome.Unasked, FlightLoad.track(question("LH454"), asked + 30 * min))
            assertEquals(2, sent())
            assertEquals(2, net.asked.size)
            assertEquals(before, Kept.fetched(AIRLABS).read(item)!!.text)
        }
    }

    @Test fun aCancellationIsNotedWhenItIsFirstSeenAndKeptThroughLaterAnswers() {
        online { net ->
            net.says(AirLabs.FLIGHT to reply("flight-LH454-planned"))
            val t = found(FlightLoad.track(question("LH454"), asked)).also { FlightLoad.take(item, it, asked) }
            assertNull(t.alertSince)
            net.says(AirLabs.FLIGHT to reply("flight-LH454-planned").replace("\"scheduled\"", "\"cancelled\""))
            val gone = FlightLoad.load(item, t, asked + 30 * min, ids)!!
            assertEquals(FlightState.CANCELED, gone.flight!!.state)
            assertEquals(asked + 30 * min, gone.alertSince)
            // Refresh, an hour and a half later: still the first moment.
            val later = FlightLoad.load(item, gone, asked + 120 * min, ids)!!
            assertEquals(asked + 30 * min, later.alertSince)
            assertTrue(FlightRules.alert(gone, at("2026-10-02T08:58:00Z")))
            assertFalse(FlightRules.alert(later, at("2026-10-02T09:29:00Z")))
            // It is kept: a restart inside the hour still alerts, and one after it does not start over.
            assertEquals(asked + 30 * min, FlightLoad.kept(item, asked + 121 * min, ids)!!.value.alertSince)
            // A flight found canceled at the first lookup alerts from then.
            net.says(AirLabs.FLIGHT to reply("flight-LH1184-cancelled"))
            assertEquals(asked, found(FlightLoad.track(question("LH1184"), asked)).alertSince)
        }
    }

    @Test fun aFlightIsPutAwayADayAfterItLandedWithoutAskingAnybody() {
        online { net ->
            net.says(AirLabs.FLIGHT to reply("flight-LH96-landed"))                            // landed 07:13 UTC
            val t = found(FlightLoad.track(question("LH96"), asked)).also { FlightLoad.take(item, it, asked) }
            val dayLater = ms("2026-10-03T07:13:00Z")
            // Kept until then, and no request for it.
            assertEquals(t, FlightLoad.kept(item, dayLater - min, ids)!!.value)
            assertNull(FlightRules.every(t, Instant.ofEpochMilli(dayLater - min)))
            // Then the load that is due only removes it.
            assertEquals(0L, FlightRules.every(t, Instant.ofEpochMilli(dayLater)))
            assertEquals(Tracked(), FlightLoad.load(item, t, dayLater, ids))
            assertEquals(emptySet<String>(), Kept.own("flight").names())
            assertEquals(emptySet<String>(), Kept.fetched(AIRLABS).names())
            assertEquals(2, sent())
            // The same when it is found after a restart.
            FlightLoad.take(item, t, asked)
            assertEquals(Tracked(), FlightLoad.kept(item, dayLater, ids)!!.value)
            assertEquals(emptySet<String>(), Kept.own("flight").names())
        }
    }

    // ---- a number that flies more than once a day (UA 1227, as the service answered on 6 October 2026 at 05:48 UTC)

    /** When the replies for UA 1227 were asked for: 10:48 PM on Monday 5 October in Los Angeles. */
    private val evening = ms("2026-10-06T05:48:00Z")
    private val la = ZoneId.of("America/Los_Angeles")
    private val tuesday = LocalDate.of(2026, 10, 6)
    private val morning = "flight-UA1227-first-leg-planned"

    /** The service as it answered about UA 1227 that evening: with the morning's flight from Orlando, and with the timetable of all its flights. */
    private fun ua1227(net: Service) = net.says(AirLabs.FLIGHT to withRequest(reply(morning)), AirLabs.ROUTES to withRequest(reply("routes-UA1227-three-legs-a-day"), left = 939))
    /** "Tomorrow" was chosen with the number, that evening in Los Angeles. */
    private fun tomorrow(text: String = "UA1227") = FlightLoad.question(item, text, tuesday, la)!!
    private fun several(o: Outcome): List<Tracked> = (o as Outcome.Several).flights

    /** The service's answer about another flight of UA 1227 on that Tuesday: made here from the timetable's times, with a gate. */
    private fun leg(from: String, to: String, leaves: String, leavesUtc: String, lands: String, landsUtc: String, gate: String) = """{"response":{"flight_iata":"UA1227","flight_icao":"UAL1227",
        "airline_name":"United Airlines","status":"scheduled","dep_iata":"$from","dep_gate":"$gate","dep_time":"$leaves","dep_time_utc":"$leavesUtc","arr_iata":"$to","arr_time":"$lands","arr_time_utc":"$landsUtc"}}"""
    private val fromNewark = leg("EWR", "SFO", "2026-10-06 13:20", "2026-10-06 17:20", "2026-10-06 16:19", "2026-10-06 23:19", "C92")
    private val fromSanFrancisco = leg("SFO", "PDX", "2026-10-06 19:05", "2026-10-07 02:05", "2026-10-06 21:00", "2026-10-07 04:00", "F14")

    @Test fun aNumberThatFliesThreeTimesThatDayIsAQuestionAndNothingIsKeptUntilOneIsChosen() {
        online { net ->
            ua1227(net)
            val flights = several(FlightLoad.track(tomorrow("ua 1227"), evening))
            assertEquals(listOf("/api/v9/flight", "/api/v9/routes"), net.paths)
            assertEquals(2, sent())
            // Each as it is kept once it is the one chosen: the number, the day it leaves at its own airport, that airport, the flight.
            assertEquals(listOf("MCO", "EWR", "SFO"), flights.map { it.from })
            assertEquals(flights.map { it.from }, flights.map { it.flight!!.from.code })
            assertTrue(flights.all { it.number == "UA1227" && it.day == "2026-10-06" && it.askedAt == evening && it.heardAt == evening && it.left == 939 })
            assertEquals(listOf(false, true, true), flights.map { it.flight!!.timetable })
            assertEquals(939, FlightLoad.left)
            // The question is no answer: nothing is followed, and nothing kept.
            assertEquals(emptySet<String>(), Kept.own("flight").names())
            assertEquals(emptySet<String>(), Kept.fetched(AIRLABS).names())
            assertEquals(Tracked(), FlightLoad.load(item, null, evening, ids))
            // The third is chosen: it is what the item follows, and the note names its airport.
            assertTrue(FlightLoad.take(item, flights[2], evening))
            assertEquals(flights[2], FlightLoad.kept(item, evening + min, ids)!!.value)
            assertTrue(Kept.own("flight").read(item)!!.text.contains("\"from\":\"SFO\""))
            // Choosing asked nobody.
            assertEquals(2, sent())
            // "Next flight" is a question too: the three leave within a day.
            assertEquals(listOf("MCO", "EWR", "SFO"), several(FlightLoad.track(question("UA1227"), evening)).map { it.from })
            assertEquals(4, sent())
            // And nothing of it says whose flights they are.
            assertFalse(flights.toString().contains("UA1227"))
        }
    }

    @Test fun severalFlightsWaitForTheOpenMenuAndAreLetGoWithoutOneWhileOneFlightIsTakenEitherWay() {
        val one = Outcome.Found(Tracked("LH455", "2026-10-01"))
        val three = Outcome.Several(List(3) { Tracked("UA1227", "2026-10-06") })
        val others = listOf(Outcome.Failed(Failure.NOT_FOUND), Outcome.Failed(Failure.OFFLINE), Outcome.Unasked)
        // While it waits the bar shows the number, as it does while it is looked up: a flight until it is taken, several while the menu asks which.
        assertTrue(one.waits)
        assertTrue(three.waits)
        assertEquals(listOf(false, false, false), others.map { it.waits })
        val asked = FlightLoad.Question("flight-1", FlightNumber.read("UA1227")!!)
        fun turn(answer: Outcome, focused: String?, opening: Boolean = false) = FlightLoad.turn(Ask.State.Done(asked, answer), focused, opening)
        // Several flights are a question for the menu of their item, while it is open.
        assertEquals(FlightLoad.Turn.KEEP, turn(three, focused = "flight-1"))
        // The menu was closed while the number was looked up: nobody is there to say which, so none is taken. Another item's menu is nobody either.
        assertEquals(FlightLoad.Turn.DROP, turn(three, focused = null))
        assertEquals(FlightLoad.Turn.DROP, turn(three, focused = "flight-2"))
        // A list found waiting when the menu opens again is from an earlier look (a hidden item has no tick that would have let it go).
        assertEquals(FlightLoad.Turn.DROP, turn(three, focused = "flight-1", opening = true))
        // One flight is never left for want of a menu: it is taken whoever looks, and the lookups it cost are not lost.
        for (focused in listOf(null, "flight-1", "flight-2")) for (opening in listOf(false, true)) assertEquals(FlightLoad.Turn.TAKE, turn(one, focused, opening))
        // What went wrong waits for the menu to say it, and a lookup on its way is left alone.
        for (o in others) for (focused in listOf(null, "flight-1")) for (opening in listOf(false, true)) assertEquals(FlightLoad.Turn.KEEP, turn(o, focused, opening))
        assertEquals(FlightLoad.Turn.KEEP, FlightLoad.turn(Ask.State.Busy(asked), "flight-1", opening = true))
        assertEquals(FlightLoad.Turn.KEEP, FlightLoad.turn(Ask.State.Idle, null))
    }

    @Test fun theFlightThatIsChosenIsTheOneInThatPlaceOfTheList() {
        val flights = listOf("MCO", "EWR", "SFO").map { Tracked("UA1227", "2026-10-06", from = it) }
        val three = Outcome.Several(flights)
        assertEquals(listOf("MCO", "EWR", "SFO"), (0..2).map { three.chosen(it)?.from })
        assertNull(three.chosen(3))
        assertNull(three.chosen(-1))
        // One flight is no list to choose from.
        assertNull(Outcome.Found(flights[0]).chosen(0))
    }

    @Test fun oneFlightThatDayIsFollowedWithNoQuestionAndItsAirportIsKeptToo() {
        online { net ->
            net.says(AirLabs.FLIGHT to reply("flight-LH455-in-the-air"), AirLabs.ROUTES to reply("routes-LH455"))
            // The next flight, and the day chip that is its day: the flight in the air, though tomorrow's leaves within a day.
            for (q in listOf(question("LH455"), FlightLoad.question(item, "LH455", LocalDate.of(2026, 10, 1), la)!!)) {
                val t = found(FlightLoad.track(q, asked))
                assertEquals(FlightState.IN_AIR, t.flight!!.state)
                assertEquals("SFO", t.from)
                assertTrue(FlightLoad.take(item, t, asked))
                assertTrue(Kept.own("flight").read(item)!!.text.contains("\"from\":\"SFO\""))
            }
            assertEquals(4, sent())
        }
    }

    @Test fun whenTheTimetableCannotBeHadTheFlightThatWasFoundIsFollowedAsBefore() {
        online { net ->
            // The fake knows no timetable: no answer about it. The morning's flight is the answer, for the day and for "Next flight".
            net.says(AirLabs.FLIGHT to reply(morning))
            assertEquals("MCO", found(FlightLoad.track(tomorrow(), evening)).from)
            assertEquals("MCO", found(FlightLoad.track(question("UA1227"), evening)).from)
            assertEquals(listOf("/api/v9/flight", "/api/v9/routes", "/api/v9/flight", "/api/v9/routes"), net.paths)
            // No connection for the second request, or told to slow down: the same.
            for (why in listOf(Why.OFFLINE, Why.STATUS, Why.TIMEOUT)) {
                net.answer = { r -> if (r.path == AirLabs.FLIGHT) Reply.Ok(reply(morning)) else Reply.Failed(why, if (why == Why.STATUS) 429 else 0) }
                assertEquals("$why", "MCO", found(FlightLoad.track(tomorrow(), evening)).from)
                Http.resetCounts() // the service's wait after that 429 (a later test's question)
            }
            // The bar hid while the first request was on its way: the second is not sent, and there is nothing to say of the first.
            val before = net.asked.size
            net.answer = { Http.allowed = { false }; Reply.Ok(reply(morning)) }
            assertEquals(Outcome.Unasked, FlightLoad.track(tomorrow(), evening))
            assertEquals(1, net.asked.size - before)
        }
    }

    @Test fun theFlightThatWasChosenIsFoundAgainByItsAirportAndNobodyIsAskedTwice() {
        online { net ->
            ua1227(net)
            val flights = several(FlightLoad.track(tomorrow(), evening))
            assertTrue(FlightLoad.take(item, flights[2], evening))
            // The switch goes off and on again: the answer is deleted, the note stays.
            Online.turnOff(AIRLABS); FlightLoad.forget(keyGone = false); assertTrue(Online.turnOn(AIRLABS))
            assertNull(FlightLoad.kept(item, evening + min, ids))
            // The service still answers with the morning's flight from Orlando. The note names San Francisco: that evening's it is.
            val again = FlightLoad.load(item, null, evening + 2 * min, ids)!!
            assertEquals(listOf("/api/v9/flight", "/api/v9/routes"), net.paths.drop(2))
            assertEquals("SFO" to "PDX", again.flight!!.from.code to again.flight.to.code)
            assertEquals(flights[2].flight, again.flight)
            assertEquals(listOf("UA1227", "2026-10-06", "SFO"), listOf(again.number, again.day, again.from))
            assertNull(again.failure)
            assertEquals(again, FlightLoad.kept(item, evening + 3 * min, ids)!!.value)
            // The same for the second, and for the first, which is the service's own flight and costs one request.
            assertTrue(FlightLoad.take(item, flights[1], evening))
            Online.turnOff(AIRLABS); FlightLoad.forget(keyGone = false); Online.turnOn(AIRLABS)
            assertEquals("EWR" to "SFO", FlightLoad.load(item, null, evening + 4 * min, ids)!!.flight!!.let { it.from.code to it.to.code })
            assertTrue(FlightLoad.take(item, flights[0], evening))
            Online.turnOff(AIRLABS); FlightLoad.forget(keyGone = false); Online.turnOn(AIRLABS)
            val before = sent()
            val first = FlightLoad.load(item, null, evening + 5 * min, ids)!!
            assertEquals("48", first.flight!!.from.gate)
            assertEquals(1, sent() - before)
        }
    }

    @Test fun aFlightLookedUpByItsAirportThatIsNotToBeHadKeepsItsAirportForTheNextTry() {
        online { net ->
            ua1227(net)
            assertTrue(FlightLoad.take(item, several(FlightLoad.track(tomorrow(), evening))[2], evening))
            Online.turnOff(AIRLABS); FlightLoad.forget(keyGone = false); Online.turnOn(AIRLABS)
            // The timetable is not to be had, and the service's flight is another airport's: no flight, and why.
            net.says(AirLabs.FLIGHT to reply(morning))
            val missing = FlightLoad.load(item, null, evening + min, ids)!!
            assertNull(missing.flight)
            assertEquals(Failure.NO_ANSWER, missing.failure)
            assertEquals("SFO", missing.from)
            assertEquals(missing, FlightLoad.kept(item, evening + min, ids)!!.value)
            // The next try has the timetable, and lands on the same flight.
            ua1227(net)
            assertEquals("SFO", FlightLoad.load(item, missing, evening + 3 * min, ids)!!.flight!!.from.code)
        }
    }

    @Test fun followingTheThirdFlightOfTheDayStaysOnItWhileTheServiceAnswersWithTheFirstAndTheSecond() {
        online { net ->
            ua1227(net)
            val third = several(FlightLoad.track(tomorrow(), evening))[2]
            assertTrue(FlightLoad.take(item, third, evening))
            // Asked again, the service answers with the morning's flight from Orlando: the plan stands, and it is not over.
            val early = FlightLoad.load(item, third, ms("2026-10-06T11:00:00Z"), ids)!!
            assertEquals(third.flight, early.flight)
            assertFalse(early.ended); assertNull(early.failure)
            assertEquals(ms("2026-10-06T11:00:00Z"), early.heardAt)
            // Then, the first one down and Newark's in the air, eight hours before it leaves: the coming hours' list is asked. It has
            // the evening's flight as it is (as it said at 19:47 UTC that day), whatever the one-flight question says, which by
            // then mixed Newark's airports with this one's times. The plan becomes the flight, with its gate.
            net.says(AirLabs.FLIGHT to reply("flight-UA1227-second-leg-in-the-air-with-the-thirds-times"),
                AirLabs.SCHEDULES to reply("schedules-UA1227-second-leg-late-in-the-air"))
            val midday = FlightLoad.load(item, early, ms("2026-10-06T18:00:00Z"), ids)!!
            assertFalse(midday.flight!!.timetable)
            assertEquals("SFO" to "PDX", midday.flight.from.code to midday.flight.to.code)
            assertEquals("E7", midday.flight.from.gate)
            assertFalse(midday.ended); assertNull(midday.failure)
            assertEquals("SFO", midday.from)
            // The first was the one question, more than ten hours before it leaves, the second the list; neither went to the timetable.
            assertEquals(listOf("/api/v9/flight", "/api/v9/schedules"), net.paths.drop(2))
            // In the evening it has another gate: the flight as it is now.
            net.says(AirLabs.FLIGHT to fromSanFrancisco)
            val live = FlightLoad.load(item, midday, ms("2026-10-07T00:30:00Z"), ids)!!
            assertFalse(live.flight!!.timetable)
            assertEquals("F14", live.flight.from.gate)
            assertEquals("SFO", live.from)
            // The day after, the service has gone on to Wednesday's first: the asking ends, and the flight stays the one it was.
            net.says(AirLabs.FLIGHT to Regex("\\d{4}-\\d{2}-\\d{2}").replace(reply(morning)) { LocalDate.parse(it.value).plusDays(1).toString() })
            val over = FlightLoad.load(item, live, ms("2026-10-07T08:00:00Z"), ids)!!
            assertTrue(over.ended)
            assertEquals(live.flight, over.flight)
        }
    }

    @Test fun aLoadForOneFlightOfTheDayIsNotKeptForAnotherOfTheSameNumberAndDay() {
        online { net ->
            ua1227(net)
            val flights = several(FlightLoad.track(tomorrow(), evening))
            assertTrue(FlightLoad.take(item, flights[0], evening))
            // The first is asked about again, and while that is on its way the third is chosen in its place: the same number,
            // the same day, another airport. The late answer is the first one's, and must not be kept as the third.
            net.answer = { FlightLoad.take(item, flights[2], evening + min); Reply.Ok(reply(morning)) }
            assertNull(FlightLoad.load(item, flights[0], evening + min, ids))
            assertEquals(flights[2], FlightLoad.kept(item, evening + min, ids)!!.value)
            // An answer on the disk for another airport than the note's is not the item's either.
            Kept.fetched(AIRLABS).write(item, Json.encodeToString(Tracked.serializer(), flights[1]), evening)
            assertNull(FlightLoad.kept(item, evening + min, ids))
            assertEquals(emptySet<String>(), Kept.fetched(AIRLABS).names())
        }
    }

    @Test fun whatWasKeptBeforeThereWasAChoiceStillReadsAndIsLookedUpAsItWas() {
        online { net ->
            // A note and an answer as the version before wrote them: neither names an airport.
            val old = follow(net).copy(from = null)
            Kept.own("flight").write(item, """{"number":"LH455","day":"2026-10-01"}""", asked)
            Kept.fetched(AIRLABS).write(item, Json.encodeToString(Tracked.serializer(), old), asked)
            assertFalse(Kept.fetched(AIRLABS).read(item)!!.text.contains("from\":\"SFO"))
            // A restart reads it back, and asking again is the one request about the same flight.
            assertEquals(old, FlightLoad.kept(item, asked + min, ids)!!.value)
            net.says(AirLabs.FLIGHT to reply("flight-LH455-landed"))
            val landed = FlightLoad.load(item, old, asked + 30 * min, ids)!!
            assertEquals(FlightState.LANDED, landed.flight!!.state)
            assertNull(landed.from)
            assertEquals(landed, FlightLoad.kept(item, asked + 31 * min, ids)!!.value)
            // With the answer gone it is looked up with no airport, as it always was: the service's flight of that day.
            Online.turnOff(AIRLABS); FlightLoad.forget(keyGone = false); Online.turnOn(AIRLABS)
            val before = sent()
            val again = FlightLoad.load(item, null, asked + 32 * min, ids)!!
            assertEquals(FlightState.LANDED, again.flight!!.state)
            assertNull(again.from)
            assertEquals(1, sent() - before)
        }
    }

    // ---- the request log, against a clock

    /** Milliseconds since the simulated bar came up; the wall clock runs with it from [start]. */
    private var elapsed = 0L
    private var start = 0L
    private fun wall() = start + elapsed

    /** The loader as the item wires it, on this test's clock and thread. */
    private fun tracker() = Refresher<String, Tracked>(Refresher.Wiring(Executor { it.run() }, { it.run() }, { elapsed }),
        every = { _, t -> FlightRules.every(t, Instant.ofEpochMilli(wall())) },
        restore = { id -> FlightLoad.kept(id, wall(), ids) },
        load = { id, last -> FlightLoad.load(id, last, wall(), ids) })

    /**
     * The bar is on screen until [until]: the item is asked for its state every ten seconds, as the
     * ticker does. The moments at which a request went out, as "day hour:minute" in UTC.
     */
    private fun watch(r: Refresher<String, Tracked>, until: String): List<String> {
        val log = ArrayList<String>()
        while (wall() < ms(until)) {
            elapsed += 10_000
            val before = sent()
            r.want(item)
            if (sent() > before) log += Instant.ofEpochMilli(wall()).toString().substring(8, 16).replace('T', ' ')
        }
        return log
    }

    /** The lid is closed until [until]: time passes and nobody asks the item for anything. */
    private fun sleep(until: String) { elapsed = ms(until) - start }

    /** The service's answer about LH 454 by the clock: Frankfurt 08:25 UTC to San Francisco 19:40 UTC on 2 October. */
    private fun lh454(): String {
        val planned = reply("flight-LH454-planned")
        val gone = planned.replace("\"dep_actual\": null", "\"dep_actual\": \"2026-10-02 10:25\"")
        return when {
            wall() < ms("2026-10-02T08:25:00Z") -> planned
            wall() < ms("2026-10-02T19:40:00Z") -> gone.replace("\"scheduled\"", "\"en-route\"")
            else -> gone.replace("\"scheduled\"", "\"landed\"").replace("\"arr_actual\": null", "\"arr_actual\": \"2026-10-02 12:40\"")
        }
    }

    @Test fun theRequestLogOfAFlightFromTheEveningBeforeUntilItHasLanded() {
        online { net ->
            net.answer = { Reply.Ok(lh454()) }
            start = ms("2026-10-01T18:00:00Z")                         // fourteen and a half hours before it leaves
            FlightLoad.take(item, found(FlightLoad.track(question("LH454"), wall())), wall())
            assertEquals(2, sent())                                    // the one flight, and the timetable
            val r = tracker()
            // Far out: one request every three hours. (The first look at the item only reads what was kept.)
            assertEquals(listOf("01 21:00", "02 00:00", "02 03:00"), watch(r, "2026-10-02T05:24:50Z"))
            // From three hours before it leaves: one every half hour, through take-off, until the service says it has landed.
            val near = watch(r, "2026-10-02T20:00:00Z")
            assertEquals(listOf("02 05:25", "02 05:55", "02 06:25", "02 06:55", "02 07:25", "02 07:55", "02 08:25", "02 08:55"), near.take(8))
            assertEquals("02 19:55", near.last())
            assertEquals(30, near.size)
            assertEquals(FlightState.LANDED, r.peek(item)!!.flight!!.state)
            // After landing: none, for as long as the bar stays up.
            assertEquals(emptyList<String>(), watch(r, "2026-10-03T19:00:00Z"))
            assertEquals(2 + 3 + 30, sent())
            // The press of Track asked for the one flight and the timetable. After it, the first ask, more than ten hours before
            // it leaves, asked the one-flight question; every one from ten hours before, the coming hours' list. All about the one number.
            assertEquals(listOf("/api/v9/flight", "/api/v9/routes"), net.paths.take(2))
            assertEquals(listOf("/api/v9/flight") + List(32) { "/api/v9/schedules" }, net.paths.drop(2))
            assertEquals(setOf(listOf("flight_iata" to "LH454")), net.asked.map { it.query }.toSet())
            // A day after it landed it is put away, which asks nobody.
            assertEquals(emptyList<String>(), watch(r, "2026-10-03T20:00:00Z"))
            assertEquals(Tracked(), r.peek(item))
            assertEquals(emptySet<String>(), Kept.own("flight").names())
            assertEquals(35, sent())
        }
    }

    @Test fun aNightWithTheLidClosedCostsNothingAndWakingCostsOneRequest() {
        online { net ->
            net.answer = { Reply.Ok(lh454()) }
            start = ms("2026-10-01T18:00:00Z")
            FlightLoad.take(item, found(FlightLoad.track(question("LH454"), wall())), wall())
            val r = tracker()
            assertEquals(listOf("01 21:00"), watch(r, "2026-10-01T22:00:00Z"))
            // Closed at ten in the evening, opened at six: nothing meanwhile, and one request, which was due, on waking.
            sleep("2026-10-02T06:00:00Z")
            assertEquals(3, sent())
            assertEquals(listOf("02 06:00", "02 06:30"), watch(r, "2026-10-02T06:59:00Z"))
            // An hour with the screen off in the near phase: the same.
            sleep("2026-10-02T08:00:00Z")
            assertEquals(5, sent())
            assertEquals(listOf("02 08:00"), watch(r, "2026-10-02T08:29:00Z"))
            // Asleep through the rest of the flight and long after: one request on waking, and it says landed.
            sleep("2026-10-03T02:00:00Z")
            assertEquals(listOf("03 02:00"), watch(r, "2026-10-03T06:00:00Z"))
            assertEquals(FlightState.LANDED, r.peek(item)!!.flight!!.state)
            assertEquals(7, sent())
        }
    }

    @Test fun aRestartCostsNoLookupAndGoesOnWhereItWas() {
        online { net ->
            net.answer = { Reply.Ok(lh454()) }
            start = ms("2026-10-02T06:00:00Z")
            FlightLoad.take(item, found(FlightLoad.track(question("LH454"), wall())), wall())
            assertEquals(listOf("02 06:30"), watch(tracker(), "2026-10-02T06:40:00Z"))
            // The process is made anew ten minutes after the last answer: a new loader, with nothing in memory.
            val again = tracker()
            assertEquals(emptyList<String>(), watch(again, "2026-10-02T06:59:50Z"))
            assertEquals(FlightState.PLANNED, again.peek(item)!!.flight!!.state)
            // The next ask comes half an hour after the last one, not after the restart.
            assertEquals(listOf("02 07:00"), watch(again, "2026-10-02T07:10:00Z"))
        }
    }

    @Test fun aPlanFromTheTimetableIsNotAskedAboutUntilTenHoursBeforeItLeaves() {
        // LH 455 on 5 October, found in the timetable three days ahead: it leaves at 21:40 UTC that day.
        fun shifted(days: Long) = Regex("\\d{4}-\\d{2}-\\d{2}").replace(reply("flight-LH455-in-the-air")) { LocalDate.parse(it.value).plusDays(days).toString() }
        online { net ->
            net.answer = { request ->
                when {
                    request.path == AirLabs.ROUTES -> Reply.Ok(reply("routes-LH455"))
                    // Until three hours before, the service still answers with the flight of the day before; then with this one.
                    wall() < ms("2026-10-05T18:40:00Z") -> Reply.Ok(shifted(((wall() - ms("2026-10-01T21:40:00Z")) / 86_400_000L).coerceIn(0L, 3L)))
                    else -> Reply.Ok(shifted(4))
                }
            }
            start = asked
            val t = found(FlightLoad.track(question("LH455", LocalDate.of(2026, 10, 5)), wall()))
            assertTrue(t.flight!!.timetable)
            FlightLoad.take(item, t, wall())
            assertEquals(2, sent())                                    // the one flight, then the timetable
            val r = tracker()
            // More than ten hours off: nothing, for three days.
            assertEquals(emptyList<String>(), watch(r, "2026-10-05T11:39:50Z"))
            // Then every three hours, and the plan stands while the service is not there yet.
            assertEquals(listOf("05 11:40", "05 14:40", "05 17:40"), watch(r, "2026-10-05T18:39:50Z"))
            assertTrue(r.peek(item)!!.flight!!.timetable)
            assertFalse(r.peek(item)!!.ended)
            // From three hours before, every half hour; the service knows the day now, and the plan becomes the flight.
            assertEquals(listOf("05 18:40", "05 19:10", "05 19:40"), watch(r, "2026-10-05T20:00:00Z"))
            assertFalse(r.peek(item)!!.flight!!.timetable)
            assertEquals("G13", r.peek(item)!!.flight!!.from.gate)
        }
    }

    @Test fun withLookupsToSpareTheLastHourBeforeItLeavesAndTheLastHalfHourBeforeItLandsAreAskedEveryTenMinutes() {
        online { net ->
            // The same service, saying with every reply that plenty of lookups are left.
            net.answer = { Reply.Ok(withRequest(lh454(), left = 900)) }
            start = ms("2026-10-02T06:55:00Z")
            FlightLoad.take(item, found(FlightLoad.track(question("LH454"), wall())), wall())
            val r = tracker()
            assertEquals(listOf("02 07:25", "02 07:35", "02 07:45", "02 07:55", "02 08:05", "02 08:15", "02 08:25", "02 08:55", "02 09:25"), watch(r, "2026-10-02T09:30:00Z"))
            sleep("2026-10-02T18:29:50Z")
            assertEquals(listOf("02 18:30", "02 19:00", "02 19:10", "02 19:20", "02 19:30", "02 19:40"), watch(r, "2026-10-02T20:30:00Z"))
            assertEquals(FlightState.LANDED, r.peek(item)!!.flight!!.state)
            // With fewer than a hundred left the usual pace holds; with fewer than twenty nothing is asked unasked.
            net.answer = { Reply.Ok(withRequest(lh454(), left = 99)) }
            start = ms("2026-10-02T06:55:00Z"); elapsed = 0
            FlightLoad.take(item, found(FlightLoad.track(question("LH454"), wall())), wall())
            assertEquals(listOf("02 07:25", "02 07:55", "02 08:25"), watch(tracker(), "2026-10-02T08:30:00Z"))
            net.answer = { Reply.Ok(withRequest(lh454(), left = 19)) }
            start = ms("2026-10-02T06:55:00Z"); elapsed = 0
            FlightLoad.take(item, found(FlightLoad.track(question("LH454"), wall())), wall())
            assertEquals(emptyList<String>(), watch(tracker(), "2026-10-02T12:00:00Z"))
        }
    }

    @Test fun pastATimeToLeaveThatCameWithNoWordItIsAskedEveryTenMinutesForAnHour() {
        online { net ->
            // The service goes on calling LH 454 planned after its time to leave, 08:25 UTC, and says with every reply that plenty of lookups are left.
            net.answer = { Reply.Ok(withRequest(reply("flight-LH454-planned"), left = 900)) }
            start = ms("2026-10-02T07:55:00Z")
            FlightLoad.take(item, found(FlightLoad.track(question("LH454"), wall())), wall())
            val r = tracker()
            // Every ten minutes up to its time, as before.
            assertEquals(listOf("02 08:05", "02 08:15", "02 08:25"), watch(r, "2026-10-02T08:29:50Z"))
            // And through the hour after it, while it stays "planned": six asks, where the usual pace had two.
            val past = watch(r, "2026-10-02T09:29:50Z")
            assertEquals(listOf("02 08:35", "02 08:45", "02 08:55", "02 09:05", "02 09:15", "02 09:25"), past)
            assertEquals(FlightState.PLANNED, r.peek(item)!!.flight!!.state)
            // After that hour, the usual half hour again.
            assertEquals(listOf("02 09:55", "02 10:25", "02 10:55"), watch(r, "2026-10-02T11:00:00Z"))
            assertEquals(2 + 3 + 6 + 3, sent())
            // An answer that puts the time ahead again ends it sooner: here the service says from 08:40 on that it will leave at 10:40.
            net.answer = { Reply.Ok(withRequest(if (wall() < ms("2026-10-02T08:40:00Z")) reply("flight-LH454-planned")
                else reply("flight-LH454-planned").replace("\"dep_estimated\": null", "\"dep_estimated\": \"2026-10-02 12:40\""), left = 900)) }
            start = ms("2026-10-02T07:55:00Z"); elapsed = 0
            FlightLoad.take(item, found(FlightLoad.track(question("LH454"), wall())), wall())
            // Two asks past the old time, the second of which brings the new one; then the usual half hour, until the last hour before that.
            assertEquals(listOf("02 08:05", "02 08:15", "02 08:25", "02 08:35", "02 08:45", "02 09:15", "02 09:40", "02 09:50"), watch(tracker(), "2026-10-02T09:55:00Z"))
        }
    }

    @Test fun refreshByHandIsHeldToTwoMinutesAndATryThatReachedNobodyToTenSeconds() {
        online { net ->
            start = asked
            val t = follow(net, wall())
            val r = tracker()
            r.want(item)                                               // reads what was kept
            assertEquals(2 * min, FlightRules.byHand(t))
            elapsed = 119_000
            assertFalse(r.refresh(item, floorMs = FlightRules.byHand(r.peek(item)!!)))
            assertEquals(2, sent())
            elapsed = 120_000
            assertTrue(r.refresh(item, floorMs = FlightRules.byHand(r.peek(item)!!)))
            assertEquals(3, sent())
            // No connection: the next try by hand may come ten seconds later, not two minutes.
            net.fails(Why.OFFLINE)
            elapsed += 2 * min
            assertTrue(r.refresh(item, floorMs = FlightRules.byHand(r.peek(item)!!)))
            assertEquals(Failure.OFFLINE, r.peek(item)!!.failure)
            assertEquals(10_000L, FlightRules.byHand(r.peek(item)!!))
            elapsed += 9_000
            assertFalse(r.refresh(item, floorMs = FlightRules.byHand(r.peek(item)!!)))
            elapsed += 1_000
            assertTrue(r.refresh(item, floorMs = FlightRules.byHand(r.peek(item)!!)))
            // Told to slow down, or any other "no answer": two minutes again. Six presses in a minute are no request.
            net.fails(Why.STATUS, 429)
            elapsed += 2 * min
            assertTrue(r.refresh(item, floorMs = FlightRules.byHand(r.peek(item)!!)))
            assertEquals(Failure.NO_ANSWER, r.peek(item)!!.failure)
            val before = sent()
            repeat(6) { elapsed += 10_000; assertFalse(r.refresh(item, floorMs = FlightRules.byHand(r.peek(item)!!))) }
            assertEquals(before, sent())
        }
    }
}
