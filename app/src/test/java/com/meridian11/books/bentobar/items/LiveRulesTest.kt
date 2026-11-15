package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.data.MapPrefs
import com.meridian11.books.bentobar.data.Section
import com.meridian11.books.bentobar.util.ClockAnchor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime

/**
 * The Live activity: which of the things happening now stands in the bar, which types it keeps live and asks, and when
 * each source this task added speaks. The sources' rules are tried against those types' own pure rules (the timer's
 * clock, the players' value, a flight's phase); the item itself runs on fake types and a fake registry.
 */
class LiveRulesTest {
    private fun live(rank: Int, text: String = "r$rank", since: Long = 0L) = LiveNow(rank, "sym", text, "said $text", since = since)

    // ---- which one stands

    @Test fun theHighestRankStands() {
        val media = live(LiveRank.MEDIA); val timer = live(LiveRank.TIMER); val call = live(LiveRank.CALL)
        assertEquals(call, LiveRules.first(listOf(media, timer, call)))
        assertEquals(listOf(call, timer, media), LiveRules.rest(listOf(media, timer, call)))
    }

    @Test fun amongEqualsTheLatest() {
        val older = live(LiveRank.PROGRESS, "older", since = 5_000)
        val newer = live(LiveRank.PROGRESS, "newer", since = 9_000)
        assertEquals(newer, LiveRules.first(listOf(older, newer)))
        assertEquals(listOf(newer, older), LiveRules.rest(listOf(older, newer)))
        // A higher rank still goes first, however old.
        val timer = live(LiveRank.TIMER, since = 1)
        assertEquals(listOf(timer, newer, older), LiveRules.rest(listOf(older, timer, newer)))
    }

    @Test fun nothingIsNothing() {
        assertNull(LiveRules.first(emptyList()))
        assertEquals(emptyList<LiveNow>(), LiveRules.rest(emptyList()))
        val state = LiveItem.state(liveItem("l"))
        assertFalse("nothing live is nothing to say", state.active)
        assertNull(state.text)
        assertFalse("the idle glyph is the outlined one", state.filled)
        assertEquals("nothing", state.desc)
    }

    // ---- the item on fake types

    private var registry = listOf<ItemType>()
    private var sampled = listOf<ItemConfig>()
    private var layout = listOf<ItemConfig>()
    private val redrawn = ArrayList<String>()
    private lateinit var before: LiveItem.Wiring

    @Before fun fake() {
        before = LiveItem.wiring
        LiveItem.wiring = LiveItem.Wiring(types = { registry + LiveItem }, sampled = { sampled }, layout = { layout },
            guarded = { _, ask -> runCatching(ask).getOrNull() }, idle = { "nothing" }, redraw = { redrawn += "live" })
        LiveItem.onIdle()
    }

    @After fun back() {
        LiveItem.onIdle()
        LiveItem.wiring = before
    }

    private class Source(id: String, var says: LiveNow?, override val refreshMs: Long = 1000, val fails: Boolean = false) : ItemType(id, 0, "sym", 0) {
        var asked = 0
        var seen: List<ItemConfig>? = null
        override val group = ItemGroup.TIME
        override val liveSource = true
        override fun state(item: ItemConfig): ItemState = error("a woken source's state is never asked for")
        override fun liveNow(now: Long, items: List<ItemConfig>): LiveNow? {
            asked++; seen = items
            if (fails) error("boom")
            return says
        }
    }

    /** A type that is not a source, which must never be woken or asked. */
    private class Plain(id: String) : ItemType(id, 0, "sym", 0) {
        override val group = ItemGroup.TIME
        override fun state(item: ItemConfig) = ItemState()
        override fun liveNow(now: Long, items: List<ItemConfig>): LiveNow? = error("not a source")
    }

    private fun liveItem(id: String, vararg off: String) = off.fold(ItemConfig(id, LiveItem.type)) { i, t -> LiveItem.switch(t).set(i, false) }

    @Test fun aSourceSwitchedOffIsNeitherWokenNorShown() {
        val timer = Source("faketimer", live(LiveRank.TIMER, "12:34"))
        val meeting = Source("fakemeeting", live(LiveRank.MEETING, "Standup in 5m"))
        registry = listOf(timer, meeting, Plain("plain"))
        val noMeetings = liveItem("a", "fakemeeting")
        assertEquals(setOf("faketimer"), LiveItem.wakes(noMeetings))
        sampled = listOf(noMeetings)
        LiveItem.sample(1_000)
        assertEquals(1, timer.asked)
        assertEquals("switched off in every Live item sampled: not asked", 0, meeting.asked)
        assertEquals("12:34", LiveItem.state(noMeetings).text)
        // Another Live item wants meetings: they are asked, and still never shown by the one that switched them off.
        val all = liveItem("b")
        sampled = listOf(noMeetings, all)
        LiveItem.sample(2_000)
        assertEquals(1, meeting.asked)
        assertEquals("Standup in 5m", LiveItem.state(all).text)
        assertEquals("12:34", LiveItem.state(noMeetings).text)
        assertEquals(1, LiveItem.shown(noMeetings).size)
    }

    @Test fun everyRegisteredLiveSourceHasASwitchAndIsWoken() {
        LiveItem.wiring = before
        val sources = Items.all.filter { it.liveSource }
        assertTrue("the timer, the meeting, the music and the flight at least", sources.size >= 4)
        assertFalse("not a source of itself", LiveItem.liveSource)
        // The options draw one switch for each of these, in this order.
        assertEquals(sources, LiveItem.sources())
        assertEquals("one key each", sources.size, sources.map { LiveRules.key(it.type) }.toSet().size)
        val everyOn = sources.fold(ItemConfig("x", LiveItem.type)) { i, t -> LiveItem.switch(t.type).set(i, true) }
        assertEquals(sources.map { it.type }.toSet(), LiveItem.wakes(everyOn))
        // As the + makes it: every one on but those the user chooses.
        assertEquals(sources.filter { LiveRules.onAtFirst(it.type) }.map { it.type }.toSet(), LiveItem.wakes(ItemConfig("x", LiveItem.type)))
        val everyOff = sources.fold(ItemConfig("x", LiveItem.type)) { i, t -> LiveItem.switch(t.type).set(i, false) }
        assertEquals(emptySet<String>(), LiveItem.wakes(everyOff))
    }

    @Test fun otherAppsProgressIsOffUntilChosen() {
        LiveItem.wiring = before
        assertTrue(NoticesItem.liveSource)
        assertFalse(LiveRules.onAtFirst(NoticesItem.type))
        assertFalse(NoticesItem.type in LiveItem.wakes(ItemConfig("x", LiveItem.type)))
        assertTrue(NoticesItem.type in LiveItem.wakes(LiveItem.switch(NoticesItem.type).set(ItemConfig("x", LiveItem.type), true)))
        // Every other source is on until switched off.
        for (t in LiveItem.sources().filter { it != NoticesItem }) assertTrue(t.type, LiveRules.onAtFirst(t.type))
    }

    @Test fun aWokenSourceIsAskedInSampleNotInState() {
        val timer = Source("faketimer", live(LiveRank.TIMER, "12:34"))
        // A source whose own item is recomputed every ten seconds is asked at that pace, and its answer stands meanwhile.
        val flight = Source("fakeflight", live(LiveRank.FLIGHT, "LH455"), refreshMs = 10_000)
        registry = listOf(timer, flight)
        val item = liveItem("a")
        val placed = ItemConfig("f1", "fakeflight", Section.HIDDEN)
        layout = listOf(item, placed)
        sampled = listOf(item)
        LiveItem.sample(100_000)
        assertEquals(1, timer.asked); assertEquals(1, flight.asked)
        assertEquals("its items in the layout", listOf(placed), flight.seen)
        assertEquals("a woken type with no item of its own gets none", emptyList<ItemConfig>(), timer.seen)
        repeat(3) { LiveItem.state(item) }
        assertEquals("state() asks nobody", 1, timer.asked)
        assertEquals(1, flight.asked)
        LiveItem.sample(101_000)
        assertEquals(2, timer.asked); assertEquals(1, flight.asked)
        timer.says = null
        LiveItem.sample(102_000)
        assertEquals("LH455", LiveItem.state(item).text)
        LiveItem.sample(110_000)
        assertEquals(2, flight.asked)
        // A clock set back asks again rather than never.
        LiveItem.sample(50_000)
        assertEquals(3, flight.asked)
    }

    @Test fun whatTheBarShowsIsTheFirstCutToItsLength() {
        val meeting = Source("fakemeeting", LiveNow(LiveRank.MEETING, "groups", "A very long meeting title indeed", "said", Tone.ACCENT))
        registry = listOf(meeting, Source("faketimer", live(LiveRank.TIMER)))
        val item = liveItem("a")
        sampled = listOf(item)
        LiveItem.sample(1_000)
        val state = LiveItem.state(item)
        assertTrue(state.active)
        assertEquals("groups", state.icon)
        assertEquals(Tone.ACCENT, state.tone)
        assertEquals("said", state.desc)
        assertEquals(LiveRules.LIMIT, TextRules.count(state.text!!))
        assertTrue(state.text!!.endsWith("…"))
        assertEquals(LiveRules.LIMIT, state.textLimit)
    }

    @Test fun aSourceThatFailsCostsTheOthersNothing() {
        registry = listOf(Source("bad", live(LiveRank.CALL), fails = true), Source("faketimer", live(LiveRank.TIMER, "12:34")))
        val item = liveItem("a")
        sampled = listOf(item)
        LiveItem.sample(1_000)
        assertEquals("12:34", LiveItem.state(item).text)
    }

    @Test fun goingIdleForgetsWhatWasLive() {
        registry = listOf(Source("faketimer", live(LiveRank.TIMER, "12:34")))
        val item = liveItem("a")
        sampled = listOf(item)
        LiveItem.sample(1_000)
        assertTrue(LiveItem.state(item).active)
        LiveItem.onIdle()
        assertFalse(LiveItem.state(item).active)
        assertEquals(emptyList<LiveNow>(), LiveItem.shown(item))
    }

    @Test fun aDebugLinePrintsRanksNotWords() {
        registry = listOf(Source("fakemusic", LiveNow(LiveRank.MEDIA, "note", "SECRET title", "Playing: SECRET title")))
        sampled = listOf(liveItem("a"))
        LiveItem.sample(1_000)
        val line = LiveItem.debug(emptyList())!!
        assertFalse(line, "SECRET" in line)
        assertTrue(line, "fakemusic" in line)
        assertFalse("SECRET" in LiveItem.shown(liveItem("a")).single().toString())
    }

    // ---- each source this task adds, by its own type's rules

    private var wall = 1_800_000_000_000L
    private val minute = 60_000L

    @Test fun aRunningTimerIsLiveAndAFinishedOneOutranksIt() {
        assertTrue(TimerItem.liveSource)
        val timer = TimerClock(TimerClock.Wiring(now = { wall }, anchor = { ClockAnchor(wall, wall, 1) }, prefs = { MapPrefs() },
            arm = {}, done = {}, changed = {}, log = {}))
        fun says() = LiveRules.timer(timer.state.value, timer.finishedAt, wall)
        assertNull("no timer, nothing", says())
        timer.startTimer(10 * minute)
        assertEquals(LiveRules.Timer.RUNNING, says())
        timer.pause()
        assertNull("paused, nothing runs", says())
        timer.resume()
        wall += 10 * minute; timer.check()
        assertNull(timer.state.value)
        assertEquals("done and not yet dismissed", LiveRules.Timer.DONE, says())
        assertTrue(LiveRank.TIMER_DONE > LiveRank.TIMER)
        wall += LiveRules.DONE_MS - 1
        assertEquals(LiveRules.Timer.DONE, says())
        wall += 1
        assertNull("as long as the item says Done, and no longer", says())
        // Stop dismisses a finished one at once.
        timer.startTimer(minute); wall += minute; timer.check()
        assertEquals(LiveRules.Timer.DONE, says())
        timer.stop()
        assertNull(says())
        // The stopwatch and a Pomodoro run too.
        timer.startStopwatch()
        assertEquals(LiveRules.Timer.RUNNING, says())
        timer.startPomodoro()
        assertEquals(LiveRules.Timer.RUNNING, says())
    }

    private fun meeting(title: String, begin: Long, end: Long) = Calendar.Event(begin, title, begin, end, allDay = false, color = 0, location = "",
        editable = true, meeting = true, link = null)

    @Test fun aMeetingIsLiveFromTenMinutesBeforeToTenAfter() {
        assertTrue(EventItem.liveSource)
        val t = wall
        val standup = meeting("Standup", t, t + 30 * minute)
        val list = listOf(standup)
        assertNull(LiveRules.meeting(list, t - 10 * minute - 1))
        assertEquals(standup, LiveRules.meeting(list, t - 10 * minute))
        assertEquals(standup, LiveRules.meeting(list, t))
        assertEquals(standup, LiveRules.meeting(list, t + 10 * minute - 1))
        assertNull("ten minutes in, it is no news", LiveRules.meeting(list, t + 10 * minute))
        // One that has ended is over, whatever its start.
        assertNull(LiveRules.meeting(listOf(meeting("Quick", t, t + 5 * minute)), t + 5 * minute))
        // Two near now: the one whose start is nearest.
        val review = meeting("Review", t + 12 * minute, t + 42 * minute)
        assertEquals(review, LiveRules.meeting(listOf(standup, review), t + 8 * minute))
        assertEquals(standup, LiveRules.meeting(listOf(standup, review), t + 5 * minute))
        // A long title gives way, and the countdown stays whole.
        val text = LiveRules.around("Weekly product planning sync", 20) { "$it in 12m" }
        assertEquals(text, LiveRules.LIMIT, TextRules.count(text))
        assertTrue(text, text.endsWith("… in 12m"))
        assertEquals("Standup in 5m", LiveRules.around("Standup", 20) { "$it in 5m" })
        assertEquals("the item's own limit holds", "Standu… in 5m", LiveRules.around("Standup meeting", 7) { "$it in 5m" })
    }

    private fun session(playing: Boolean, title: String = "Song") = NowPlaying.Session(key = "k", pkg = "player.app", app = "Player", title = title,
        artist = "", album = "", playing = playing, durationMs = 0, positionMs = 0, positionAt = 0, speed = 1f, canPlayPause = true, canNext = false,
        canPrevious = false, canSeek = false, art = null, startedAt = 5)

    @Test fun musicIsLiveOnlyWhilePlaying() {
        assertTrue(MediaItem.liveSource)
        val playing = session(true)
        assertEquals(playing, LiveRules.media(NowPlaying.Playing(access = true, sessions = listOf(playing))))
        assertNull("paused", LiveRules.media(NowPlaying.Playing(access = true, sessions = listOf(session(false)))))
        assertNull("nothing", LiveRules.media(NowPlaying.Playing(access = true)))
        assertNull("something audible, but what it is only Now playing's access says", LiveRules.media(NowPlaying.Playing(audible = true)))
        assertNull("the players not listed yet", LiveRules.media(NowPlaying.Playing(access = true, starting = true, audible = true)))
    }

    private fun time(text: String): LocalDateTime = LocalDateTime.parse(text)
    private fun at(text: String): Instant = Instant.parse(text)
    // LH 455, San Francisco (seven hours behind UTC) at 2:40 PM to Frankfurt (two ahead) at 10:25 AM the next day.
    private fun lh455(state: FlightState = FlightState.PLANNED, leftAt: String? = null, timetable: Boolean = false) = Flight("LH455", "Lufthansa",
        FlightEnd("SFO", "San Francisco", time("2026-10-02T14:40"), actual = leftAt?.let(::time), offset = -420, gate = "G13"),
        FlightEnd("FRA", "Frankfurt", time("2026-10-03T10:25"), offset = 120), state, timetable = timetable)

    @Test fun aFlightIsLiveFromBoardingToLanding() {
        assertTrue(FlightItem.liveSource)
        val planned = lh455()
        // It leaves at 21:40 UTC: boarding from 20:55.
        assertNull("counted down to, not boarding yet", LiveRules.flight(planned, at("2026-10-02T20:03:00Z")))
        assertNull(LiveRules.flight(planned, at("2026-10-02T20:54:59Z")))
        assertEquals(at("2026-10-02T20:55:00Z"), LiveRules.flight(planned, at("2026-10-02T20:55:00Z")))
        assertNotNull("still called planned after its time: still at the gate", LiveRules.flight(planned, at("2026-10-02T21:55:00Z")))
        assertNotNull("in the air", LiveRules.flight(lh455(FlightState.IN_AIR, leftAt = "2026-10-02T14:47"), at("2026-10-03T03:00:00Z")))
        assertNull("landed", LiveRules.flight(lh455(FlightState.LANDED, leftAt = "2026-10-02T14:47"), at("2026-10-03T08:30:00Z")))
        assertNull("canceled", LiveRules.flight(lh455(FlightState.CANCELED), at("2026-10-02T21:00:00Z")))
        assertNull("a timetable's flight past its time: nobody knows", LiveRules.flight(lh455(timetable = true), at("2026-10-02T21:45:00Z")))
        assertNotNull("a timetable's flight at boarding", LiveRules.flight(lh455(timetable = true), at("2026-10-02T21:00:00Z")))
    }
}
