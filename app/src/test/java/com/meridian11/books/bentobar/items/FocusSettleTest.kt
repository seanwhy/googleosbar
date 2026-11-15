package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.data.Kept
import com.meridian11.books.bentobar.items.FocusRules.Session
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Every road to a Focus session's end, at the seams the app calls (the alarm's receiver, the tick, `settle`), over what an
 * earlier process kept on disk: each road that finds a session past its end switches Do Not Disturb off once and forgets it,
 * and the next road finds nothing to do. Do Not Disturb and the alarm are fakes; the kept file is real.
 */
class FocusSettleTest {
    private val t0 = 1_800_000_000_000L
    private val min = 60_000L
    private val dir = File(System.getProperty("java.io.tmpdir"), "bentobar-focus-${System.nanoTime()}")
    private var now = t0
    private var placed = true
    private val quiet = FakeQuiet()
    private val alarms = ArrayList<Long?>()
    private lateinit var before: FocusKeeper

    @Before fun setUp() { Kept.init(dir); before = FocusItem.keeper }
    @After fun tearDown() { FocusItem.keeper = before; dir.deleteRecursively() }

    /** The process as it starts: a keeper that has read nothing yet. [background]: where the kept file is read. */
    private fun process(background: (() -> Unit) -> Unit = { it() }): FocusKeeper =
        FocusKeeper(FocusKeeper.Wiring(quiet, alarm = { alarms += it }, kept = { Kept.own("focus") }, wall = { now }, placed = { placed },
            background = background, main = { it() })).also { FocusItem.keeper = it }

    /** What an earlier process kept. */
    private fun keptBefore(s: Session) = assertTrue(Kept.own("focus").write(FocusKeeper.NAME, FocusRules.write(s), t0))
    private fun kept(): Session? = FocusRules.read(Kept.own("focus").read(FocusKeeper.NAME)?.text)

    private val roads: List<Pair<String, () -> Unit>> = listOf(
        "the alarm" to { var done = 0; FocusItem.alarmRang { done++ }; assertEquals("the receiver is let go once", 1, done) },
        "the tick" to { FocusItem.sample(0L) },
        "the service starting" to { FocusItem.settle(Settle.SERVICE_STARTED) },
    )

    @Test fun eachRoadEndsASessionPastItsEndOnceAndTheNextFindsNothing() {
        for ((first, road) in roads) {
            quiet.offs = 0; alarms.clear()
            keptBefore(Session(t0 - 60 * min, t0 - 10 * min, false))
            process()
            road()
            assertEquals("$first: Do Not Disturb switched off once", 1, quiet.offs)
            assertNull("$first: forgotten", FocusItem.keeper.session)
            assertNull("$first: forgotten on disk too", kept())
            assertEquals("$first: its alarm cancelled", listOf<Long?>(null), alarms)
            for ((other, next) in roads) if (other != first) {
                next()
                assertEquals("$other after $first switched something", 1, quiet.offs)
                assertEquals("$other after $first touched the alarm", listOf<Long?>(null), alarms)
            }
            assertEquals("nobody asked whether a forgotten rule is on", 0, quiet.asked)
        }
    }

    @Test fun leavingTheLayoutEndsARunningSessionAndTheBarHidingDoesNot() {
        process()
        assertTrue(FocusItem.keeper.start(50, dark = false))
        assertEquals(1, quiet.ons)
        assertEquals(listOf<Long?>(t0 + 50 * min), alarms)
        assertEquals(Session(t0, t0 + 50 * min, false), kept())
        FocusItem.onIdle()
        assertEquals("the bar hiding ends nothing", 0, quiet.offs)
        FocusItem.settle(Settle.LEFT_LAYOUT)
        assertEquals(1, quiet.offs)
        assertNull(FocusItem.keeper.session)
        assertNull(kept())
        assertEquals(listOf(t0 + 50 * min, null), alarms)
    }

    @Test fun theServiceStartingWithTimeLeftArmsTheAlarmOnce() {
        val s = Session(t0 - 10 * min, t0 + 15 * min, false)
        keptBefore(s)
        process()
        FocusItem.settle(Settle.SERVICE_STARTED)
        assertEquals(listOf<Long?>(t0 + 15 * min), alarms)
        assertEquals(0, quiet.offs)
        assertEquals(s, FocusItem.keeper.session)
        // Its end then comes by the alarm, as if nothing had happened.
        now = t0 + 15 * min
        FocusItem.alarmRang {}
        assertEquals(1, quiet.offs)
    }

    @Test fun anEndlessSessionEndsWithTheServiceAndOnAStartWithItsItemGone() {
        process()
        assertTrue(FocusItem.keeper.start(0, dark = false))
        assertEquals("no end: no alarm", listOf<Long?>(null), alarms)
        FocusItem.settle(Settle.SERVICE_ENDING)
        assertEquals(1, quiet.offs)
        // Kept by a process that was killed, so no ending came: the next start ends it once its item is gone.
        keptBefore(Session(t0 - min, 0L, false))
        placed = false
        process()
        FocusItem.settle(Settle.SERVICE_STARTED)
        assertEquals(2, quiet.offs)
        assertNull(kept())
        // With its item still there it goes on (a rebind with no ending before it).
        keptBefore(Session(t0 - min, 0L, false))
        placed = true
        process()
        FocusItem.settle(Settle.SERVICE_STARTED)
        assertEquals(2, quiet.offs)
        assertEquals(Session(t0 - min, 0L, false), FocusItem.keeper.session)
    }

    @Test fun aRuleSwitchedOffInAndroidsSettingsIsForgottenWithNothingSwitched() {
        process()
        assertTrue(FocusItem.keeper.start(50, dark = false))
        quiet.ruleOn = false
        FocusItem.sample(0L)
        assertNull(FocusItem.keeper.session)
        assertEquals(0, quiet.offs)
        assertNull(kept())
        assertEquals(listOf(t0 + 50 * min, null), alarms)
    }

    @Test fun aSwitchOfExactAlarmsSetsATimedSessionsAlarmAgainAndNoOther() {
        process()
        FocusItem.rearm()
        assertEquals("no session: nothing to set", emptyList<Long?>(), alarms)
        assertTrue(FocusItem.keeper.start(50, dark = false))
        alarms.clear()
        FocusItem.rearm()
        assertEquals(listOf<Long?>(t0 + 50 * min), alarms)
        assertEquals(0, quiet.offs)
        FocusItem.keeper.end()
        assertTrue(FocusItem.keeper.start(0, dark = false))
        alarms.clear()
        FocusItem.rearm()
        assertEquals("no end: no alarm", emptyList<Long?>(), alarms)
    }

    @Test fun androidRefusingToSwitchItOnStartsNothingAndSaysSo() {
        quiet.accepts = false
        process()
        assertFalse(FocusItem.keeper.start(50, dark = false))
        assertNull(FocusItem.keeper.session)
        assertTrue(FocusItem.keeper.refused)
        assertEquals(emptyList<Long?>(), alarms)
        quiet.accepts = true
        assertTrue(FocusItem.keeper.start(50, dark = false))
        assertFalse(FocusItem.keeper.refused)
    }

    @Test fun aSessionStartedWhileTheKeptOneIsReadWins() {
        keptBefore(Session(t0 - 60 * min, t0 - 10 * min, false))
        val waiting = ArrayList<() -> Unit>()
        process(background = { waiting += it })
        FocusItem.settle(Settle.SERVICE_STARTED)
        assertTrue(FocusItem.keeper.start(25, dark = false))
        waiting.toList().forEach { it() }
        // The old one, past its end, is not read back over the new one, and the start's settling arms the new one's alarm.
        assertEquals(Session(t0, t0 + 25 * min, false), FocusItem.keeper.session)
        assertEquals(0, quiet.offs)
        assertEquals(t0 + 25 * min, alarms.last())
    }
}
