package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.items.AirLabs.Failure
import com.meridian11.books.bentobar.items.FlightRules.Kind
import com.meridian11.books.bentobar.items.FlightRules.Tracked
import com.meridian11.books.bentobar.items.FlightText.Voice
import com.meridian11.books.bentobar.util.Sym
import com.meridian11.books.bentobar.data.Display
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * What the Flight item says, to the character: its 20 characters in the bar for every phase, the
 * sentence a screen reader gets, and the lines of its menu. The words are the app's own resource
 * files; the flight is the examples' LH 455, San Francisco (seven hours behind UTC) to Frankfurt
 * (two ahead), planned to leave at 2:40 PM on Friday 2 October 2026 and to land at 10:25 AM on
 * Saturday.
 */
class FlightTextTest {
    private val la = ZoneId.of("America/Los_Angeles")
    private val us = FlightVoices.us(la)
    private fun at(text: String): Instant = Instant.parse(text)
    private fun time(text: String): LocalDateTime = LocalDateTime.parse(text)
    private fun ms(text: String) = at(text).toEpochMilli()
    /** The space before a unit in a headline ("1 min"); the copied line has a plain one there. */
    private val NBSP = Char(0xA0)

    private fun sfo(planned: String? = "2026-10-02T14:40", expected: String? = null, actual: String? = null, gate: String? = "G13", terminal: String? = "1") =
        FlightEnd("SFO", "San Francisco", planned?.let(::time), expected?.let(::time), actual?.let(::time), -420, terminal, gate)
    private fun fra(planned: String? = "2026-10-03T10:25", expected: String? = null, actual: String? = null, terminal: String? = "1", gate: String? = null, belt: String? = null) =
        FlightEnd("FRA", "Frankfurt", planned?.let(::time), expected?.let(::time), actual?.let(::time), 120, terminal, gate, belt)
    private fun lh455(from: FlightEnd = sfo(), to: FlightEnd = fra(), state: FlightState = FlightState.PLANNED, number: String = "LH455", timetable: Boolean = false, aircraft: String? = null) =
        Flight(number, "Lufthansa", from, to, state, timetable = timetable, aircraft = aircraft, callsign = "DLH455")
    private val left = sfo(actual = "2026-10-02T14:47")
    private fun inAir(to: FlightEnd) = lh455(left, to, FlightState.IN_AIR, aircraft = "Boeing 747-8")

    /** [f] as an item holds it: heard of at [heard] (just now, where nothing else is said). */
    private fun tracked(f: Flight, heard: String) = Tracked(f.number, f.from.planned?.toLocalDate()?.toString(), f, askedAt = ms(heard), heardAt = ms(heard), left = 940,
        alertSince = FlightRules.alertSince(f, null, ms(heard)))
    private fun bar(f: Flight, now: String, heard: String = now, v: Voice = us, before: Int = 24) = FlightText.bar(tracked(f, heard), null, at(now), before, v)
    private fun card(f: Flight, now: String, heard: String = now, v: Voice = us) = FlightText.card(tracked(f, heard), at(now), v)!!

    // Friday 2 October 2026 in San Francisco: 9 AM, and an hour and 37 minutes before LH 455 leaves.
    private val morning = "2026-10-02T16:00:00Z"
    private val near = "2026-10-02T20:03:00Z"

    // ---- the bar, phase by phase

    @Test fun nothingFollowedIsThePlaneAlone() {
        for (t in listOf(null, Tracked())) {
            val b = FlightText.bar(t, null, at(morning), 24, us)
            assertEquals(FlightText.Bar(Sym.FLIGHT, null, Tone.NORMAL, false, "No flight tracked", null), b)
        }
    }

    @Test fun aFlightThatIsFollowedAndNotHeardOfYetIsThePlaneAloneToo() {
        // The service was switched off and on again, and the lookup after that found no connection.
        val waiting = Tracked("LH455", "2026-10-02", null, failure = Failure.OFFLINE, failures = 1)
        val b = FlightText.bar(waiting, null, at(morning), 24, us)
        assertEquals(FlightText.Bar(Sym.FLIGHT, null, Tone.NORMAL, false, "LH 455: no update", null), b)
        assertNull(FlightText.card(waiting, at(morning), us))
        // While it is asked for again, the bar says so.
        assertEquals("LH 455 …", FlightText.bar(waiting, "LH 455", at(morning), 24, us).text)
    }

    @Test fun lookingUpShowsTheNumber() {
        val b = FlightText.bar(Tracked(), "LH 455", at(morning), 24, us)
        assertEquals("LH 455 …", b.text)
        assertEquals(8, FlightText.length(b.text!!))
        assertEquals(Sym.FLIGHT, b.icon)
        assertEquals(Tone.NORMAL, b.tone)
        assertFalse(b.active)
        assertEquals("Looking up LH 455", b.desc)
    }

    @Test fun moreThanThreeHoursBeforeItIsTheNumberAndWhenItLeaves() {
        // Today: the time alone.
        val today = bar(lh455(), morning)
        assertEquals("LH 455 · 2:40 PM", today.text)
        assertEquals(Sym.FLIGHT_TAKEOFF, today.icon)
        assertEquals(Tone.NORMAL, today.tone)
        // On another day: its weekday. Exactly twenty characters.
        val wednesday = "2026-09-30T19:00:00Z"
        assertEquals("LH 455 · Fri 2:40 PM", bar(lh455(), wednesday).text)
        assertEquals(20, FlightText.length("LH 455 · Fri 2:40 PM"))
        assertEquals("LH 455 to Frankfurt leaves Fri 2:40 PM", bar(lh455(), wednesday).desc)
        assertEquals("LH 455 to Frankfurt leaves 2:40 PM", today.desc)
        // The countdown starts exactly three hours before; a minute earlier it is still the time.
        assertEquals("LH 455 · 2:40 PM", bar(lh455(), "2026-10-02T18:39:00Z").text)
        assertEquals("3h 00m · Gate G13", bar(lh455(), "2026-10-02T18:40:00Z").text)
    }

    @Test fun aPlanThatIsTooLongFirstLosesTheSpaceInItsNumberAndThenItsTime() {
        val wednesday = "2026-09-30T19:00:00Z"
        // 21 characters with the space: the number closes up.
        assertEquals("LH455 · Fri 12:40 PM", bar(lh455(sfo(planned = "2026-10-02T12:40")), wednesday).text)
        // 22, and 21 closed up: the day alone.
        assertEquals("UA 1234 · Fri", bar(lh455(sfo(planned = "2026-10-02T12:40"), number = "UA1234"), wednesday).text)
        assertEquals("DLH 1234A · Fri", bar(lh455(sfo(planned = "2026-10-02T12:40"), number = "DLH1234A"), wednesday).text)
        // Today the number and the time always fit.
        assertEquals("UA 1234A · 12:40 PM", bar(lh455(sfo(planned = "2026-10-02T12:40"), number = "UA1234A"), morning).text)
        // With 24 hours there is room for everything.
        assertEquals("LH 455 · Fri 12:40", bar(lh455(sfo(planned = "2026-10-02T12:40")), wednesday, v = FlightVoices.us(la, h24 = true)).text)
    }

    @Test fun beyondSixDaysItIsADateAndNoTime() {
        val christmas = lh455(sfo(planned = "2026-12-24T14:40"), fra(planned = "2026-12-25T10:25"))
        assertEquals("LH 455 · Dec 24", bar(christmas, morning).text)
        assertEquals("LH 455 to Frankfurt leaves Dec 24, 2:40 PM", bar(christmas, morning).desc)
        // Six days off is still a weekday; a week off has today's, so it is a date.
        assertEquals("LH 455 · Thu 2:40 PM", bar(lh455(sfo(planned = "2026-10-08T14:40")), morning).text)
        assertEquals("LH 455 · Oct 9", bar(lh455(sfo(planned = "2026-10-09T14:40")), morning).text)
    }

    @Test fun withinThreeHoursItCountsDownAndNamesTheGate() {
        val b = bar(lh455(sfo(expected = "2026-10-02T14:40")), near)
        assertEquals("1h 37m · Gate G13", b.text)
        assertEquals(17, FlightText.length(b.text!!))
        assertEquals(Sym.FLIGHT_TAKEOFF, b.icon)
        assertEquals(Tone.NORMAL, b.tone)
        assertTrue(b.active)
        assertEquals("LH 455 to Frankfurt leaves in 1 hour 37 minutes, gate G13", b.desc)
        assertEquals("45m · Gate G13", bar(lh455(), "2026-10-02T20:55:00Z").text)
        // No gate and on time: when it leaves.
        assertEquals("1h 37m · 2:40 PM", bar(lh455(sfo(gate = null)), near).text)
        // A gate with a long name: without the word, and then not at all.
        assertEquals("1h 37m · Gate B42A", bar(lh455(sfo(gate = "B42A")), near).text)
        assertEquals("1h 37m · PIER-C12", bar(lh455(sfo(gate = "PIER-C12")), near).text)
        // The figure is whole minutes, a begun one counting as still to go, and never under one.
        assertEquals("1m · Gate G13", bar(lh455(), "2026-10-02T21:39:30Z").text)
    }

    @Test fun pastItsTimeWithNoWordItStaysAtOneMinuteAndAfterAnHourSaysNoUpdate() {
        // LH 455 leaves 2:40 PM; the last answer, at 2:20, called it planned and on time.
        val f = lh455(sfo(expected = "2026-10-02T14:40"))
        val heard = "2026-10-02T21:20:00Z"
        assertEquals("1m · Gate G13", bar(f, "2026-10-02T21:39:30Z", heard).text)
        // From 2:40 on it is still a minute: never a plan for a time that has passed.
        for (now in listOf("2026-10-02T21:40:00Z", "2026-10-02T21:55:00Z", "2026-10-02T22:20:00Z")) {
            assertEquals(now, "1m · Gate G13", bar(f, now, heard).text)
            assertEquals(now, Sym.FLIGHT_TAKEOFF, bar(f, now, heard).icon)
            assertEquals(now, "Leaves in 1\u00A0min", card(f, now, heard).headline)
            // "On time" is nobody's to say about a time that is over.
            assertNull(now, card(f, now, heard).badge)
        }
        // The last answer is over an hour old: nobody knows what became of it.
        val silent = bar(f, "2026-10-02T22:21:00Z", heard)
        assertEquals("LH 455 · no update", silent.text)
        assertEquals(Sym.FLIGHT, silent.icon)
        assertEquals(Tone.NORMAL, silent.tone)
        assertTrue(silent.active)
        assertEquals("LH 455: no update", silent.desc)
        val c = card(f, "2026-10-02T22:21:00Z", heard)
        assertEquals("No update", c.headline)
        assertNull(c.badge)
        assertNull(c.share)                                                        // no plane on the line: nobody knows where it is
        assertEquals("LH 455 SFO → FRA · No update", c.copy)
        // A delay that was known is still true once its time has passed.
        val late = lh455(sfo(planned = "2026-10-02T14:15", expected = "2026-10-02T14:40"))
        assertEquals("1m · +25m · Gate G13", bar(late, "2026-10-02T21:45:00Z", heard).text)
        assertEquals("Delayed 25\u00A0min", card(late, "2026-10-02T21:45:00Z", heard).badge)
        // Copied, the line says what the headline says in that state, to the character: never "Leaves 2:40 PM" about a time that has passed.
        assertEquals("LH 455 SFO → FRA · Leaves 2:40 PM · On time · Gate G13 · Lands Sat 10:25 AM", card(f, "2026-10-02T21:39:30Z", heard).copy)
        assertEquals("LH 455 SFO → FRA · Leaves in 1 min · Gate G13 · Lands Sat 10:25 AM", card(f, "2026-10-02T21:45:00Z", heard).copy)
        assertEquals("LH 455 SFO → FRA · Leaves in 1 min · Delayed 25 min · Gate G13 · Lands Sat 10:25 AM", card(late, "2026-10-02T21:45:00Z", heard).copy)
        assertEquals("LH 455 SFO → FRA · No update", card(late, "2026-10-02T22:21:00Z", heard).copy)
        for (now in listOf("2026-10-02T21:45:00Z", "2026-10-02T22:21:00Z")) for (of in listOf(f, late)) {
            val c = card(of, now, heard)
            assertTrue(now, c.copy.contains(" · " + c.headline.replace(NBSP, ' ')))
        }
        // With no gate to name the minute stands alone: a time that has passed is not said beside it.
        assertEquals("1m · 2:40 PM", bar(lh455(sfo(gate = null)), "2026-10-02T21:39:30Z", heard).text)
        assertEquals("1m", bar(lh455(sfo(gate = null)), "2026-10-02T21:45:00Z", heard).text)
        // An old answer about a flight whose time is still to come is "not live", as before.
        assertEquals("10m · not live", bar(f, "2026-10-02T21:30:00Z", "2026-10-02T20:29:00Z").text)
    }

    @Test fun aFlightWhoseClockNobodyGaveIsNotCountedDownTo() {
        val f = lh455(sfo(expected = "2026-10-02T14:40")).copy(loose = true)
        assertEquals("LH 455 · 2:40 PM", bar(f, near).text)
        assertEquals("Leaves 2:40 PM", card(f, near).headline)
        // The sentence about an hour's doubt is for a timetable's plan: these times are the airport's own, and right.
        assertFalse(card(f, near).loose)
        assertTrue(card(f.copy(timetable = true), near).loose)
        val air = inAir(fra(expected = "2026-10-03T10:25")).copy(loose = true)
        assertEquals("In the air", bar(air, "2026-10-03T07:00:00Z").text)
        assertEquals("In the air", card(air, "2026-10-03T07:00:00Z").headline)
        assertNull(card(air, "2026-10-03T07:00:00Z").share)
    }

    @Test fun lateBeforeItLeavesSaysByHowMuchAndTurnsToAWarning() {
        val late = lh455(sfo(planned = "2026-10-02T14:15", expected = "2026-10-02T14:40"))
        val b = bar(late, near)
        // "1h 37m · +25m · Gate G13" would be 24.
        assertEquals("1h 37m · +25m · G13", b.text)
        assertEquals(19, FlightText.length(b.text!!))
        assertEquals(Tone.WARN, b.tone)
        assertEquals("LH 455 to Frankfurt leaves in 1 hour 37 minutes, 25 minutes late, gate G13", b.desc)
        // "1h 37m · +1h 25m · G13" would be 22: the gate goes.
        assertEquals("1h 37m · +1h 25m", bar(lh455(sfo(planned = "2026-10-02T13:15", expected = "2026-10-02T14:40")), near).text)
        assertEquals("1h 37m · +25m", bar(lh455(sfo(planned = "2026-10-02T14:15", expected = "2026-10-02T14:40", gate = null)), near).text)
        // Late starts fifteen minutes from the plan.
        assertEquals("1h 37m · Gate G13", bar(lh455(sfo(planned = "2026-10-02T14:26", expected = "2026-10-02T14:40")), near).text)
        assertEquals("1h 37m · +15m · G13", bar(lh455(sfo(planned = "2026-10-02T14:25", expected = "2026-10-02T14:40")), near).text)
        // Leaving early is on time: the figure counts to the earlier time already, and nobody is told to hurry in a warning's color.
        val early = bar(lh455(sfo(planned = "2026-10-02T15:00", expected = "2026-10-02T14:40")), near)
        assertEquals("1h 37m · Gate G13", early.text)
        assertEquals(Tone.NORMAL, early.tone)
    }

    @Test fun inItsLastHalfHourACountdownTakesTheAccentUnlessItIsLate() {
        assertEquals(Tone.NORMAL, bar(lh455(), "2026-10-02T21:09:00Z").tone)      // 31 minutes
        assertEquals(Tone.ACCENT, bar(lh455(), "2026-10-02T21:10:00Z").tone)      // 30
        assertEquals(Tone.ACCENT, bar(lh455(), "2026-10-02T21:39:00Z").tone)
        val late = lh455(sfo(planned = "2026-10-02T14:15", expected = "2026-10-02T14:40"))
        assertEquals(Tone.WARN, bar(late, "2026-10-02T21:30:00Z").tone)
        // The same on the way down.
        val onTime = inAir(fra(expected = "2026-10-03T10:25"))                     // lands 08:25 UTC
        assertEquals(Tone.NORMAL, bar(onTime, "2026-10-03T07:54:00Z").tone)
        assertEquals(Tone.ACCENT, bar(onTime, "2026-10-03T07:55:00Z").tone)
        assertEquals(Tone.WARN, bar(inAir(fra(expected = "2026-10-03T10:45")), "2026-10-03T08:30:00Z").tone)
    }

    @Test fun inTheAirItCountsToTheLandingThenLateOrEarlyElseWhenItLands() {
        val late = bar(inAir(fra(expected = "2026-10-03T10:45")), "2026-10-03T06:40:00Z")
        assertEquals("2h 05m · +20m", late.text)
        assertEquals(13, FlightText.length(late.text!!))
        assertEquals(Sym.FLIGHT_LAND, late.icon)
        assertEquals(Tone.WARN, late.tone)
        assertTrue(late.active)
        val early = bar(inAir(fra(expected = "2026-10-03T10:07")), "2026-10-03T06:02:00Z")
        assertEquals("2h 05m · −18m", early.text)
        assertEquals('\u2212', early.text!![9])                                    // a real minus, not a hyphen
        assertEquals(Tone.NORMAL, early.tone)
        assertEquals("LH 455 to Frankfurt lands in 2 hours 5 minutes, 18 minutes early", early.desc)
        val onTime = bar(inAir(fra(expected = "2026-10-03T10:25")), "2026-10-03T06:20:00Z")
        assertEquals("2h 05m · 10:25 AM", onTime.text)
        assertEquals(17, FlightText.length(onTime.text!!))
        assertEquals("LH 455 to Frankfurt lands in 2 hours 5 minutes", onTime.desc)
        // With only the plan of the landing it is the same: the plan is when it lands.
        assertEquals("2h 05m · 10:25 AM", bar(inAir(fra()), "2026-10-03T06:20:00Z").text)
        // Past its expected time and still in the air: never less than a minute.
        assertEquals("1m · 10:25 AM", bar(inAir(fra()), "2026-10-03T09:00:00Z").text)
        // Nobody names a landing at all: the words, and nothing to count.
        assertEquals("In the air", bar(inAir(fra(planned = null)), "2026-10-03T06:20:00Z").text)
    }

    @Test fun landedSaysTheBeltOrTheTimeForAnHourAndThenIsThePlaneAlone() {
        val down = lh455(left, fra(actual = "2026-10-03T10:01", belt = "21"), FlightState.LANDED)      // landed 08:01 UTC
        val b = bar(down, "2026-10-03T08:21:00Z")
        assertEquals("Landed · Belt 21", b.text)
        assertEquals(16, FlightText.length(b.text!!))
        assertEquals(Sym.FLIGHT_LAND, b.icon)
        assertEquals(Tone.NORMAL, b.tone)
        assertTrue(b.active)
        assertEquals("LH 455 landed at 10:01 AM, belt 21", b.desc)
        val noBelt = bar(lh455(left, fra(actual = "2026-10-03T10:01"), FlightState.LANDED), "2026-10-03T08:21:00Z")
        assertEquals("Landed 10:01 AM", noBelt.text)
        assertEquals(15, FlightText.length(noBelt.text!!))
        assertEquals("LH 455 landed at 10:01 AM", noBelt.desc)
        // An hour after landing: the idle look, while the flight is still the item's (its tooltip says which).
        assertEquals("Landed · Belt 21", bar(down, "2026-10-03T09:00:59Z").text)
        val later = bar(down, "2026-10-03T09:01:00Z")
        assertEquals(Sym.FLIGHT, later.icon)
        assertNull(later.text)
        assertFalse(later.active)
        assertEquals("LH 455 · San Francisco → Frankfurt", later.tooltip)
        // A day after landing it is cleared: nothing followed any more.
        assertEquals("No flight tracked", bar(down, "2026-10-04T08:01:00Z").desc)
        assertNull(bar(down, "2026-10-04T08:01:00Z").tooltip)
    }

    @Test fun aFlightThatSleptThroughItsLandingIsLandedNotACountdownToNothing() {
        // Last heard of in the air; the lid opens seven hours after it was to land.
        val b = bar(inAir(fra(expected = "2026-10-03T10:25")), now = "2026-10-03T15:30:00Z", heard = "2026-10-03T06:00:00Z")
        assertEquals(Sym.FLIGHT, b.icon)
        assertNull(b.text)
        assertFalse(b.active)
        assertEquals("LH 455 landed at 10:25 AM", b.desc)
    }

    @Test fun aFlightTheServiceHasGoneOnFromHasLandedOnceItsTimeToLandHasPassed() {
        // Last heard in the air, to land 10:25 AM. Then the service answered with the next day's flight, and the asking ended.
        val t = tracked(inAir(fra(expected = "2026-10-03T10:25")), "2026-10-03T07:30:00Z")
        val ended = t.copy(ended = true)
        fun bar(of: Tracked, now: String) = FlightText.bar(of, null, at(now), 24, us)
        // Until its time it counts down by the clock.
        assertEquals("25m · 10:25 AM", bar(ended, "2026-10-03T08:00:00Z").text)
        // Nobody will ever say "landed" of it: its time to land says so. No "1m" for hours, no "Lands in 1 min".
        val down = bar(ended, "2026-10-03T08:26:00Z")
        assertEquals("Landed 10:25 AM", down.text)
        assertEquals(Sym.FLIGHT_LAND, down.icon)
        val c = FlightText.card(ended, at("2026-10-03T08:26:00Z"), us)!!
        assertEquals("Landed 1\u00A0min ago", c.headline)
        assertEquals(1.0, c.share!!, 0.0)
        // An hour on it is the plane alone, like any flight that landed.
        assertNull(bar(ended, "2026-10-03T09:26:00Z").text)
        // A flight that is still asked about goes by the service's word: a minute to go until it says "landed", or three hours pass.
        assertEquals("1m · 10:25 AM", bar(t, "2026-10-03T08:26:00Z").text)
    }

    @Test fun canceledIsTheCrossedOutPlaneAndAnAlertForAnHour() {
        val gone = lh455(state = FlightState.CANCELED)
        val b = bar(gone, near)
        assertEquals("LH 455 canceled", b.text)
        assertEquals(15, FlightText.length(b.text!!))
        assertEquals(Sym.AIRPLANEMODE_INACTIVE, b.icon)
        assertEquals(Tone.ALERT, b.tone)
        assertTrue(b.active)
        assertEquals("LH 455 is canceled", b.desc)
        // An hour after it was first seen the words and the glyph stay, and the alert goes.
        assertEquals(Tone.ALERT, bar(gone, "2026-10-02T21:02:59Z", heard = near).tone)
        val later = bar(gone, "2026-10-02T21:03:00Z", heard = near)
        assertEquals(Tone.NORMAL, later.tone)
        assertEquals("LH 455 canceled", later.text)
        assertEquals(Sym.AIRPLANEMODE_INACTIVE, later.icon)
        // It stays out until it is cleared, a day after it was to land, also days before it was to leave.
        assertTrue(bar(gone, "2026-09-29T12:00:00Z").active)
        assertTrue(bar(gone, "2026-10-04T08:24:00Z", heard = near).active)
        assertNull(bar(gone, "2026-10-04T08:25:00Z", heard = near).text)
    }

    @Test fun britishEnglishSpellsItCancelled() {
        val b = bar(lh455(state = FlightState.CANCELED), near, v = FlightVoices.british())
        assertEquals("LH 455 cancelled", b.text)
        assertEquals(16, FlightText.length(b.text!!))
        assertEquals("LH 455 is cancelled", b.desc)
        assertEquals("Cancelled", card(lh455(state = FlightState.CANCELED), near, v = FlightVoices.british()).headline)
    }

    @Test fun divertedIsThePlainPlaneWithItsWords() {
        val b = bar(inAir(fra(expected = "2026-10-03T10:25")).copy(state = FlightState.DIVERTED), "2026-10-03T06:20:00Z")
        assertEquals("LH 455 diverted", b.text)
        assertEquals(15, FlightText.length(b.text!!))
        assertEquals(Sym.FLIGHT, b.icon)
        assertEquals(Tone.ALERT, b.tone)
        assertTrue(b.active)
        assertEquals("LH 455 was diverted", b.desc)
    }

    @Test fun aPlanWhoseTimePassedWithNoWordSaysNoUpdate() {
        val plan = lh455(sfo(gate = null, terminal = null), timetable = true)
        val b = bar(plan, "2026-10-02T22:40:00Z", heard = morning)
        assertEquals("LH 455 · no update", b.text)
        assertEquals(18, FlightText.length(b.text!!))
        assertEquals(Sym.FLIGHT, b.icon)
        assertEquals(Tone.NORMAL, b.tone)
        assertTrue(b.active)
        assertEquals("LH 455: no update", b.desc)
        // Before its time it is a plan like any other, counted down to by its timetable.
        assertEquals("LH 455 · 2:40 PM", bar(plan, morning).text)
        assertEquals("1h 37m · 2:40 PM", bar(plan, near).text)
        // A long number closes up before the words go.
        assertEquals("DLH1234A · no update", bar(plan.copy(number = "DLH1234A"), "2026-10-02T22:40:00Z", heard = morning).text)
    }

    @Test fun anAnswerOverAnHourOldSaysNotLiveAndDropsWhatItCanHaveMadeWrong() {
        val late = lh455(sfo(planned = "2026-10-02T14:15", expected = "2026-10-02T14:40"))
        val b = bar(late, near, heard = "2026-10-02T16:40:00Z")                    // heard at 9:40 AM, three hours and more ago
        assertEquals("1h 37m · not live", b.text)
        assertEquals(17, FlightText.length(b.text!!))
        assertEquals(Sym.FLIGHT_TAKEOFF, b.icon)
        assertEquals(Tone.NORMAL, b.tone)
        assertTrue(b.active)
        assertEquals("LH 455 to Frankfurt leaves in 1 hour 37 minutes, not live, updated 9:40 AM", b.desc)
        // Exactly an hour old it is still live.
        assertEquals("1h 37m · +25m · G13", bar(late, near, heard = "2026-10-02T19:03:00Z").text)
        assertEquals("1h 37m · not live", bar(late, near, heard = "2026-10-02T19:02:59Z").text)
        // In the air the same, with the landing's glyph; the figure goes on counting by the clock.
        val air = bar(inAir(fra(expected = "2026-10-03T10:45")), "2026-10-03T06:40:00Z", heard = "2026-10-03T05:00:00Z")
        assertEquals("2h 05m · not live", air.text)
        assertEquals(Sym.FLIGHT_LAND, air.icon)
        assertEquals(Tone.NORMAL, air.tone)
        assertEquals("LH 455 to Frankfurt lands in 2 hours 5 minutes, not live, updated 10:00 PM", air.desc)
        // More than three hours before it leaves there is nothing to mark: a plan is a plan.
        assertEquals("LH 455 · 2:40 PM", bar(lh455(), morning, heard = "2026-10-01T16:00:00Z").text)
    }

    @Test fun theTooltipNamesTheFlight() {
        assertEquals("LH 455 · San Francisco → Frankfurt", bar(lh455(), near).tooltip)
        // Where the service names no city: the airport's letters.
        val bare = lh455(sfo().copy(city = ""), fra().copy(city = ""))
        assertEquals("LH 455 · SFO → FRA", bar(bare, near).tooltip)
    }

    // ---- the route line, which the item draws in its glyph's place unless it is shown as text alone

    /** [f] in the bar of an item that draws the line. [shown]: where the flight's plane was last drawn; null: nowhere yet. */
    private fun lined(f: Flight, now: String, heard: String = now, shown: Double? = null) = FlightText.bar(tracked(f, heard), null, at(now), 24, us, line = true, shown = shown)

    @Test fun withinThreeHoursOfLeavingThePlaneWaitsAtTheStartInTheTextsColor() {
        // On time by the service's word, or with only the plan: nothing is flown, and nothing is claimed.
        val onTime = lined(lh455(sfo(expected = "2026-10-02T14:40")), near)
        assertEquals(BarRoute(0f, Stands.NO_CLAIM), onTime.route)
        assertEquals("1h 37m · Gate G13", onTime.text)
        assertEquals(Tone.NORMAL, onTime.tone)
        assertEquals(BarRoute(0f, Stands.NO_CLAIM), lined(lh455(), near).route)
        // A timetable's plan is counted down to as well, and has its line.
        assertEquals(BarRoute(0f, Stands.NO_CLAIM), lined(lh455(sfo(gate = null, terminal = null), timetable = true), near).route)
        // The line comes with the countdown, three hours before: a minute earlier the chip is the glyph and its words.
        assertNull(lined(lh455(), "2026-10-02T18:39:00Z").route)
        assertEquals(BarRoute(0f, Stands.NO_CLAIM), lined(lh455(), "2026-10-02T18:40:00Z").route)
        // In the last half hour the words keep the bar's color: the accent is for the chip without the line.
        assertEquals(Tone.ACCENT, bar(lh455(), "2026-10-02T21:10:00Z").tone)
        assertEquals(Tone.NORMAL, lined(lh455(), "2026-10-02T21:10:00Z").tone)
        // Past its time to leave it is still at the start: nothing is flown until the service says it has left.
        val past = lined(lh455(sfo(expected = "2026-10-02T14:40")), "2026-10-02T21:55:00Z", heard = "2026-10-02T21:20:00Z")
        assertEquals("1m · Gate G13", past.text)
        assertEquals(BarRoute(0f, Stands.NO_CLAIM), past.route)
    }

    @Test fun lateBeforeItLeavesThePlaneTakesTheColorAndTheWordsKeepTheBars() {
        val late = lh455(sfo(planned = "2026-10-02T14:15", expected = "2026-10-02T14:40"))
        assertEquals(BarRoute(0f, Stands.LATE), lined(late, near).route)
        assertEquals("1h 37m · +25m · G13", lined(late, near).text)
        assertEquals(Tone.NORMAL, lined(late, near).tone)
        val veryLate = lh455(sfo(planned = "2026-10-02T13:15", expected = "2026-10-02T14:40"))
        assertEquals(BarRoute(0f, Stands.VERY_LATE), lined(veryLate, near).route)
        assertEquals("1h 37m · +1h 25m", lined(veryLate, near).text)
        assertEquals(Tone.NORMAL, lined(veryLate, near).tone)
        // 44 minutes behind is late, 45 is very late.
        assertEquals(Stands.LATE, lined(lh455(sfo(planned = "2026-10-02T13:56", expected = "2026-10-02T14:40")), near).route!!.stands)
        assertEquals(Stands.VERY_LATE, lined(lh455(sfo(planned = "2026-10-02T13:55", expected = "2026-10-02T14:40")), near).route!!.stands)
        // Shown as text alone, the item has no line, and its words are a warning as they always were.
        assertNull(bar(late, near).route)
        assertEquals(Tone.WARN, bar(late, near).tone)
        assertEquals(Tone.WARN, bar(veryLate, near).tone)
    }

    @Test fun inTheAirThePlaneStandsWhereTheClockPutsItInTheColorOfItsLanding() {
        // LH 455 left at 9:47 PM UTC. Expected at its planned 8:25 AM: 513 of 638 minutes are flown.
        val onTime = lined(inAir(fra(expected = "2026-10-03T10:25")), "2026-10-03T06:20:00Z")
        assertEquals(Stands.GOOD, onTime.route!!.stands)
        assertEquals(513f / 638f, onTime.route.share, 1e-6f)
        assertFalse(onTime.route.struck)
        assertFalse(onTime.route.whole)
        assertEquals("2h 05m · 10:25 AM", onTime.text)
        // Early is good news too, and the words say by how much.
        val early = lined(inAir(fra(expected = "2026-10-03T10:07")), "2026-10-03T06:02:00Z")
        assertEquals(Stands.GOOD, early.route!!.stands)
        assertEquals("2h 05m · −18m", early.text)
        // Twenty minutes behind, 533 of 658 minutes flown: the line is late, and the words keep the bar's color.
        val late = lined(inAir(fra(expected = "2026-10-03T10:45")), "2026-10-03T06:40:00Z")
        assertEquals(Stands.LATE, late.route!!.stands)
        assertEquals(533f / 658f, late.route.share, 1e-6f)
        assertEquals("2h 05m · +20m", late.text)
        assertEquals(Tone.NORMAL, late.tone)
        val veryLate = lined(inAir(fra(expected = "2026-10-03T11:20")), "2026-10-03T07:15:00Z")
        assertEquals(Stands.VERY_LATE, veryLate.route!!.stands)
        assertEquals("2h 05m · +55m", veryLate.text)
        assertEquals(Tone.NORMAL, veryLate.tone)
        // In its last half hour the words keep the bar's color here too.
        assertEquals(Tone.ACCENT, bar(inAir(fra(expected = "2026-10-03T10:25")), "2026-10-03T07:55:00Z").tone)
        assertEquals(Tone.NORMAL, lined(inAir(fra(expected = "2026-10-03T10:25")), "2026-10-03T07:55:00Z").tone)
        // It stands a little way in from the moment it has left, and never touches the far end while it flies, whatever the clock says.
        assertEquals(0.02f, lined(inAir(fra(expected = "2026-10-03T10:25")), "2026-10-02T21:48:00Z").route!!.share, 0f)
        assertEquals(0.98f, lined(inAir(fra(expected = "2026-10-03T10:25")), "2026-10-03T09:00:00Z").route!!.share, 0f)
    }

    @Test fun whereNobodyHasSaidHowItStandsTheLineHasTheTextsColorAndThePlaneStillGoesByTheClock() {
        // The last answer is over an hour old: twenty minutes late then, and anybody's guess now.
        val old = lined(inAir(fra(expected = "2026-10-03T10:45")), "2026-10-03T06:40:00Z", heard = "2026-10-03T05:00:00Z")
        assertEquals("2h 05m · not live", old.text)
        assertEquals(Stands.NO_CLAIM, old.route!!.stands)
        assertEquals(533f / 658f, old.route.share, 1e-6f)
        // Only the plan of its landing is known: no good news about a time the service never gave.
        val planOnly = lined(inAir(fra()), "2026-10-03T06:20:00Z")
        assertEquals("2h 05m · 10:25 AM", planOnly.text)
        assertEquals(Stands.NO_CLAIM, planOnly.route!!.stands)
        assertEquals(513f / 638f, planOnly.route.share, 1e-6f)
        // Before it leaves the same: a delay heard of over an hour ago colors nothing.
        val late = lh455(sfo(planned = "2026-10-02T14:15", expected = "2026-10-02T14:40"))
        assertEquals("1h 37m · not live", lined(late, near, heard = "2026-10-02T16:40:00Z").text)
        assertEquals(BarRoute(0f, Stands.NO_CLAIM), lined(late, near, heard = "2026-10-02T16:40:00Z").route)
    }

    @Test fun landedThePlaneStandsAtTheFarEndForItsHourInTheBar() {
        val down = lh455(left, fra(actual = "2026-10-03T10:01", belt = "21"), FlightState.LANDED)      // landed 08:01 UTC, 24 minutes before its plan
        val b = lined(down, "2026-10-03T08:21:00Z")
        assertEquals(BarRoute(1f, Stands.GOOD), b.route)
        assertEquals("Landed · Belt 21", b.text)
        assertEquals(Tone.NORMAL, b.tone)
        // By when it landed against its plan of 10:25 AM: up to fourteen minutes behind is good, then it is late, and very late from 45.
        fun landed(at: String, now: String) = lined(lh455(left, fra(actual = at), FlightState.LANDED), now).route
        assertEquals(BarRoute(1f, Stands.GOOD), landed("2026-10-03T10:39", "2026-10-03T08:45:00Z"))
        assertEquals(BarRoute(1f, Stands.LATE), landed("2026-10-03T10:40", "2026-10-03T08:45:00Z"))
        assertEquals(BarRoute(1f, Stands.LATE), landed("2026-10-03T11:09", "2026-10-03T09:15:00Z"))
        assertEquals(BarRoute(1f, Stands.VERY_LATE), landed("2026-10-03T11:10", "2026-10-03T09:15:00Z"))
        // No time of landing is known against the plan: the text's color.
        assertEquals(BarRoute(1f, Stands.NO_CLAIM), lined(lh455(left, fra(), FlightState.LANDED), "2026-10-03T08:30:00Z").route)
        // After its hour the chip is the plain plane again.
        assertEquals(BarRoute(1f, Stands.GOOD), lined(down, "2026-10-03T09:00:59Z").route)
        assertEquals(bar(down, "2026-10-03T09:01:00Z"), lined(down, "2026-10-03T09:01:00Z"))
        assertNull(lined(down, "2026-10-03T09:01:00Z").route)
    }

    @Test fun canceledTheStruckPlaneStandsAtTheStartOfALineThatIsOneColorFromEndToEnd() {
        val gone = lh455(state = FlightState.CANCELED)
        val news = lined(gone, near)
        assertEquals(BarRoute(0f, Stands.WILL_NOT_ARRIVE, struck = true, whole = true), news.route)
        assertEquals("LH 455 canceled", news.text)
        // The alert stays for its hour; after it the words have the bar's color and the line stays as it is.
        assertEquals(Tone.ALERT, news.tone)
        val later = lined(gone, "2026-10-02T21:03:00Z", heard = near)
        assertEquals(Tone.NORMAL, later.tone)
        assertEquals(news.route, later.route)
        // The crossed-out glyph is still what a menu lists the item by.
        assertEquals(Sym.AIRPLANEMODE_INACTIVE, news.icon)
        // Wherever a plane of this flight was drawn before: one that never leaves stands at the start.
        assertEquals(news.route, lined(gone, near, shown = 0.6).route)
        // The line is there until the flight is cleared, a day after it was to land.
        assertEquals(news.route, lined(gone, "2026-10-04T08:24:00Z", heard = near).route)
        assertNull(lined(gone, "2026-10-04T08:25:00Z", heard = near).route)
    }

    @Test fun divertedThePlaneStaysWhereItWasLastDrawnAndStandsInTheMiddleIfItNeverWas() {
        val diverted = inAir(fra(expected = "2026-10-03T10:25")).copy(state = FlightState.DIVERTED)
        val news = lined(diverted, "2026-10-03T06:20:00Z")
        assertEquals(BarRoute(0.5f, Stands.WILL_NOT_ARRIVE, struck = false, whole = true), news.route)
        assertEquals("LH 455 diverted", news.text)
        assertEquals(Tone.ALERT, news.tone)
        assertEquals(Tone.NORMAL, lined(diverted, "2026-10-03T07:20:00Z", heard = "2026-10-03T06:20:00Z").tone)
        // Nobody knows where it is going: it neither flies on with the clock nor goes back.
        assertEquals(0.8f, lined(diverted, "2026-10-03T06:20:00Z", shown = 0.8).route!!.share, 0f)
        assertEquals(0.8f, lined(diverted, "2026-10-03T08:00:00Z", heard = "2026-10-03T06:20:00Z", shown = 0.8).route!!.share, 0f)
        assertEquals(0.1f, lined(diverted, "2026-10-03T06:20:00Z", shown = 0.1).route!!.share, 0f)
    }

    @Test fun onTheLineThePlaneOnlyGoesForward() {
        // Where the times put it, wherever it was drawn further back: 513 of 638 minutes.
        val f = inAir(fra(expected = "2026-10-03T10:25"))
        assertEquals(513f / 638f, lined(f, "2026-10-03T06:20:00Z", shown = 0.5).route!!.share, 1e-6f)
        // The service now expects it an hour later, and by the times it is at 513 of 698: the plane waits where it was drawn.
        val slower = inAir(fra(expected = "2026-10-03T11:25"))
        assertEquals(513f / 698f, lined(slower, "2026-10-03T06:20:00Z").route!!.share, 1e-6f)
        assertEquals(513f / 638f, lined(slower, "2026-10-03T06:20:00Z", shown = 513.0 / 638.0).route!!.share, 1e-6f)
        // Once the clock has caught up it goes on from there.
        assertEquals(583f / 698f, lined(slower, "2026-10-03T07:30:00Z", shown = 513.0 / 638.0).route!!.share, 1e-6f)
        // Landed, it is at the far end, whatever was drawn.
        val down = lh455(left, fra(actual = "2026-10-03T10:01"), FlightState.LANDED)
        assertEquals(1f, lined(down, "2026-10-03T08:21:00Z", shown = 0.3).route!!.share, 0f)
    }

    @Test fun whereThereIsNoLineTheChipIsTheGlyphAndItsWordsAsItAlwaysWas() {
        // Nothing to follow, looking up, a flight that was asked for and not heard of yet.
        for (t in listOf(null, Tracked(), Tracked("LH455", "2026-10-02", null, failure = Failure.OFFLINE, failures = 1))) for (looking in listOf(null, "LH 455"))
            assertEquals(FlightText.bar(t, looking, at(morning), 24, us), FlightText.bar(t, looking, at(morning), 24, us, line = true, shown = 0.4))
        fun same(f: Flight, now: String, heard: String = now) {
            assertEquals(now, bar(f, now, heard), lined(f, now, heard))
            assertNull(now, lined(f, now, heard).route)
        }
        // More than three hours before it leaves, and a plan whose clocks may be out, which is never counted down to.
        same(lh455(), morning)
        same(lh455(sfo(expected = "2026-10-02T14:40")).copy(loose = true), near)
        // In the air with no time of landing, with clocks that cannot be read, with no time it left by, or with a landing
        // that would be before it left: a plane on a line would claim a place nobody can vouch for.
        same(inAir(fra(planned = null)), "2026-10-03T06:20:00Z")
        same(inAir(fra(expected = "2026-10-03T10:25")).copy(loose = true), "2026-10-03T06:20:00Z")
        val noStart = lh455(sfo(planned = null), fra(expected = "2026-10-03T10:45"), FlightState.IN_AIR)
        same(noStart, "2026-10-03T06:40:00Z")
        assertEquals("2h 05m · +20m", lined(noStart, "2026-10-03T06:40:00Z").text)
        assertEquals(Tone.WARN, lined(noStart, "2026-10-03T06:40:00Z").tone)      // with no line to say it, the words still do
        same(inAir(fra(expected = "2026-10-02T23:00")), "2026-10-02T22:00:00Z")
        // No update: a timetable's plan past its time, and a flight still called planned an hour after its time with no word.
        same(lh455(sfo(gate = null, terminal = null), timetable = true), "2026-10-02T22:40:00Z", heard = morning)
        same(lh455(sfo(expected = "2026-10-02T14:40")), "2026-10-02T22:21:00Z", heard = "2026-10-02T21:20:00Z")
        // Landed more than an hour ago, slept through its landing, and cleared.
        val down = lh455(left, fra(actual = "2026-10-03T10:01", belt = "21"), FlightState.LANDED)
        same(down, "2026-10-03T12:00:00Z")
        same(inAir(fra(expected = "2026-10-03T10:25")), "2026-10-03T15:30:00Z", heard = "2026-10-03T06:00:00Z")
        same(down, "2026-10-04T08:01:00Z")
    }

    @Test fun withTheLineTheWordsTheGlyphAndTheTooltipAreWhatTheyAreWithoutIt() {
        val late = lh455(sfo(planned = "2026-10-02T14:15", expected = "2026-10-02T14:40"))
        val down = lh455(left, fra(actual = "2026-10-03T10:50", belt = "21"), FlightState.LANDED)      // landed 08:50 UTC, 25 minutes behind
        val states = listOf(
            Triple(lh455(sfo(expected = "2026-10-02T14:40")), near, near),
            Triple(lh455(sfo(gate = null)), near, near),
            Triple(lh455(), "2026-10-02T21:10:00Z", "2026-10-02T21:10:00Z"),
            Triple(late, near, near),
            Triple(late, near, "2026-10-02T16:40:00Z"),
            Triple(lh455(sfo(planned = "2026-10-02T13:15", expected = "2026-10-02T14:40")), near, near),
            Triple(inAir(fra(expected = "2026-10-03T10:25")), "2026-10-03T06:20:00Z", "2026-10-03T06:20:00Z"),
            Triple(inAir(fra(expected = "2026-10-03T10:07")), "2026-10-03T06:02:00Z", "2026-10-03T06:02:00Z"),
            Triple(inAir(fra(expected = "2026-10-03T10:45")), "2026-10-03T06:40:00Z", "2026-10-03T06:40:00Z"),
            Triple(inAir(fra(expected = "2026-10-03T10:45")), "2026-10-03T06:40:00Z", "2026-10-03T05:00:00Z"),
            Triple(down, "2026-10-03T09:00:00Z", "2026-10-03T09:00:00Z"),
            Triple(lh455(left, fra(actual = "2026-10-03T10:50"), FlightState.LANDED), "2026-10-03T09:00:00Z", "2026-10-03T09:00:00Z"),
            Triple(lh455(state = FlightState.CANCELED), near, near),
            Triple(lh455(state = FlightState.CANCELED), "2026-10-02T21:03:00Z", near),
            Triple(inAir(fra(expected = "2026-10-03T10:25")).copy(state = FlightState.DIVERTED), "2026-10-03T06:20:00Z", "2026-10-03T06:20:00Z"),
        )
        for ((f, now, heard) in states) {
            val plain = bar(f, now, heard)
            val line = lined(f, now, heard)
            val what = "${plain.text} at $now"
            assertTrue(what, line.route != null && plain.route == null)
            assertEquals(what, plain.text, line.text)
            assertEquals(what, plain.icon, line.icon)
            assertEquals(what, plain.active, line.active)
            assertEquals(what, plain.tooltip, line.tooltip)
            // Their color is the bar's, whatever it is without the line; only the alert stays.
            assertEquals(what, if (plain.tone == Tone.ALERT) Tone.ALERT else Tone.NORMAL, line.tone)
        }
        // Every tone the chip has without the line was among them.
        assertEquals(Tone.entries.toSet(), states.map { (f, now, heard) -> bar(f, now, heard).tone }.toSet())
    }

    @Test fun whereTheLineIsGreenTheSpokenSentenceSaysOnTime() {
        // In the air and expected within fifteen minutes of its plan: no figure in the words says so, and the green is for the eye.
        val onTime = inAir(fra(expected = "2026-10-03T10:25"))
        assertEquals("LH 455 to Frankfurt lands in 2 hours 5 minutes, on time", lined(onTime, "2026-10-03T06:20:00Z").desc)
        assertEquals("LH 455 to Frankfurt lands in 2 hours 5 minutes, on time", lined(inAir(fra(expected = "2026-10-03T10:39")), "2026-10-03T06:34:00Z").desc)
        // Early is green too, and already says by how much.
        assertEquals("LH 455 to Frankfurt lands in 2 hours 5 minutes, 18 minutes early", lined(inAir(fra(expected = "2026-10-03T10:07")), "2026-10-03T06:02:00Z").desc)
        // Landed within fifteen minutes of its plan, or before it: on time, and then the belt.
        val down = lh455(left, fra(actual = "2026-10-03T10:01", belt = "21"), FlightState.LANDED)
        assertEquals("LH 455 landed at 10:01 AM, on time, belt 21", lined(down, "2026-10-03T08:21:00Z").desc)
        assertEquals("LH 455 landed at 10:30 AM, on time", lined(lh455(left, fra(actual = "2026-10-03T10:30"), FlightState.LANDED), "2026-10-03T08:40:00Z").desc)
        // Landed late, the sentence says by how much, with the line and without it (the words do too).
        val downLate = lh455(left, fra(actual = "2026-10-03T10:50", belt = "21"), FlightState.LANDED)
        assertEquals("LH 455 landed at 10:50 AM, 25 minutes late, belt 21", lined(downLate, "2026-10-03T09:00:00Z").desc)
        assertEquals("LH 455 landed at 11:25 AM, 1 hour late", lined(lh455(left, fra(actual = "2026-10-03T11:25"), FlightState.LANDED), "2026-10-03T09:40:00Z").desc)
        assertEquals("LH 455 landed at 10:50 AM, 25 minutes late, belt 21", bar(downLate, "2026-10-03T09:00:00Z").desc)
        // Where the line is not green and no later than its plan says, the sentence is what it was: late, not live, only the plan known, before it leaves.
        val late = inAir(fra(expected = "2026-10-03T10:45"))
        val quiet = listOf(
            Triple(late, "2026-10-03T06:40:00Z", "2026-10-03T06:40:00Z") to "LH 455 to Frankfurt lands in 2 hours 5 minutes, 20 minutes late",
            Triple(late, "2026-10-03T06:40:00Z", "2026-10-03T05:00:00Z") to "LH 455 to Frankfurt lands in 2 hours 5 minutes, not live, updated 10:00 PM",
            Triple(onTime, "2026-10-03T06:20:00Z", "2026-10-03T05:00:00Z") to "LH 455 to Frankfurt lands in 2 hours 5 minutes, not live, updated 10:00 PM",
            Triple(inAir(fra()), "2026-10-03T06:20:00Z", "2026-10-03T06:20:00Z") to "LH 455 to Frankfurt lands in 2 hours 5 minutes",
            Triple(lh455(left, fra(), FlightState.LANDED), "2026-10-03T08:30:00Z", "2026-10-03T08:30:00Z") to "LH 455 landed at 10:25 AM",
            Triple(lh455(sfo(expected = "2026-10-02T14:40")), near, near) to "LH 455 to Frankfurt leaves in 1 hour 37 minutes, gate G13",
            Triple(lh455(state = FlightState.CANCELED), near, near) to "LH 455 is canceled",
        )
        for ((state, sentence) in quiet) {
            val (f, now, heard) = state
            assertEquals(sentence, lined(f, now, heard).desc)
            assertEquals(sentence, bar(f, now, heard).desc)
        }
        // Without the line nothing is green, and after its hour in the bar a landed flight has no line: the sentence as it was.
        assertEquals("LH 455 to Frankfurt lands in 2 hours 5 minutes", bar(onTime, "2026-10-03T06:20:00Z").desc)
        assertEquals("LH 455 landed at 10:01 AM, belt 21", bar(down, "2026-10-03T08:21:00Z").desc)
        assertEquals("LH 455 landed at 10:01 AM, belt 21", lined(down, "2026-10-03T09:01:00Z").desc)
        // The two words, as they follow a sentence.
        assertEquals(", on time", us.say(FlightText.Word.FLIGHT_DESC_ON_TIME))
        assertEquals(", on time", FlightVoices.british().say(FlightText.Word.FLIGHT_DESC_ON_TIME))
    }

    @Test fun aLineThatIsLateAlwaysStandsBesideItsFigure() {
        // Color is never the only thing that says it: before a flight leaves, while it flies and for its hour after
        // landing, a line in the color of late or very late has "+25m" or "+25 min" in the words beside it, and a line
        // without that color has no such figure.
        for (late in -30L..150L) {
            val leaving = lined(lh455(sfo(planned = time("2026-10-02T14:40").minusMinutes(late).toString(), expected = "2026-10-02T14:40")), near)
            val landing = lined(inAir(fra(planned = time("2026-10-03T10:45").minusMinutes(late).toString(), expected = "2026-10-03T10:45")), "2026-10-03T06:40:00Z")
            val down = lined(lh455(left, fra(planned = time("2026-10-03T10:45").minusMinutes(late).toString(), actual = "2026-10-03T10:45", belt = "21"), FlightState.LANDED), "2026-10-03T09:00:00Z")
            for (b in listOf(leaving, landing, down)) {
                val colored = b.route!!.stands == Stands.LATE || b.route.stands == Stands.VERY_LATE
                assertEquals("$late: ${b.text}", colored, b.text!!.contains(" · +"))
                assertEquals("$late: ${b.desc}", colored, b.desc.contains(" late"))
            }
            // In the air a green line has "−18m" beside it, or else "on time" in its sentence.
            if (landing.route!!.stands == Stands.GOOD) assertTrue("$late: ${landing.desc}", landing.text!!.contains(" · −") || landing.desc.endsWith(", on time"))
        }
    }

    /** The words "Landed · Belt 21" said nothing of a late landing, and its line said it in a color only. */
    @Test fun aFlightThatLandedLateSaysByHowMuchInItsWords() {
        fun words(b: FlightText.Bar) = b.text?.replace('\u00A0', ' ')
        fun down(actual: String, belt: String? = "21") = lh455(left, fra(actual = actual, belt = belt), FlightState.LANDED)
        // 25 minutes behind its plan (10:25): as a headline would write it, with the line and without it. The belt is in the menu.
        assertEquals("Landed · +25 min", words(lined(down("2026-10-03T10:50"), "2026-10-03T09:00:00Z")))
        assertEquals("Landed · +25 min", words(bar(down("2026-10-03T10:50"), "2026-10-03T09:00:00Z")))
        assertEquals("Landed · +25 min", words(lined(down("2026-10-03T10:50", belt = null), "2026-10-03T09:00:00Z")))
        // From 15 minutes, as everywhere; under that the words are the ones they were.
        assertEquals("Landed · +15 min", words(lined(down("2026-10-03T10:40"), "2026-10-03T08:50:00Z")))
        assertEquals("Landed · Belt 21", words(lined(down("2026-10-03T10:39"), "2026-10-03T08:50:00Z")))
        // An hour and more: still within the bar's twenty characters, and the short figure where it would not be.
        assertEquals("Landed · +1 h 00 min", words(lined(down("2026-10-03T11:25"), "2026-10-03T09:40:00Z")))
        assertEquals("Landed · +10h 05m", words(lined(down("2026-10-03T20:30"), "2026-10-03T18:40:00Z")))
        // An answer over an hour old claims nothing, in color or in words.
        assertEquals("Landed · Belt 21", words(lined(down("2026-10-03T10:50"), "2026-10-03T09:10:00Z", heard = "2026-10-03T08:00:00Z")))
        // After its hour in the bar the plain plane, as before.
        assertNull(lined(down("2026-10-03T10:50"), "2026-10-03T10:00:00Z").text)
    }

    @Test fun anItemShownAsTextAloneHasNoLine() {
        // The line stands in the icon's place: beside the words or alone, and never where only words are shown.
        // The item asks for a line by this, and the strip draws one by it.
        assertTrue(Display.ICON_AND_TEXT.line)
        assertTrue(Display.ICON.line)
        assertFalse(Display.TEXT.line)
    }

    @Test fun aPlaneIsRememberedWhereItFlewAndNotWhereItStandsOnALineThatIsOver() {
        // In the air: where it was drawn is where the next drawing goes on from.
        assertEquals(0.5, FlightText.flown(BarRoute(0.5f, Stands.GOOD))!!, 1e-6)
        assertEquals(0.0, FlightText.flown(BarRoute(0f, Stands.LATE))!!, 1e-6)
        // Canceled or diverted, the plane stands at the start or where it was: no place it has flown to.
        assertNull(FlightText.flown(BarRoute(0.5f, Stands.WILL_NOT_ARRIVE, whole = true)))
        assertNull(FlightText.flown(BarRoute(0f, Stands.WILL_NOT_ARRIVE, struck = true, whole = true)))
        assertNull(FlightText.flown(null))
    }

    @Test fun inTheAirPastItsLandingTimeTheSentenceNoLongerSaysOnTime() {
        val onTime = inAir(fra(expected = "2026-10-03T10:25"))
        val before = lined(onTime, "2026-10-03T08:20:00Z")
        assertEquals(Stands.GOOD, before.route?.stands)
        assertTrue(before.desc, before.desc.endsWith(", on time"))
        // Fifteen minutes past the time it was to land, and the service has not said that it has.
        val past = lined(onTime, "2026-10-03T08:40:00Z")
        assertEquals(Stands.NO_CLAIM, past.route?.stands)
        assertFalse(past.desc, past.desc.contains("on time"))
        assertEquals(bar(onTime, "2026-10-03T08:40:00Z").desc, past.desc)
    }

    @Test fun theBarAndTheMenuNameAFlightsPlaneAlike() {
        // One plane for both lines: what either has drawn, the other goes on from.
        val t = tracked(inAir(fra(expected = "2026-10-03T10:25")), "2026-10-03T06:20:00Z")
        assertEquals("LH455 2026-10-02 SFO", FlightText.plane(t))
        assertEquals(FlightText.plane(t), FlightText.card(t, at("2026-10-03T06:20:00Z"), us)!!.plane)
        // Nothing followed, or nothing heard of it yet: there is no plane to name.
        assertNull(FlightText.plane(Tracked()))
        assertNull(FlightText.plane(Tracked("LH455", "2026-10-02", null)))
    }

    // ---- the rule: from so many hours before it leaves until an hour after it landed

    @Test fun theItemHasSomethingToSayFromTheRulesHoursBeforeUntilAnHourAfterLanding() {
        assertFalse(bar(lh455(), "2026-10-01T20:40:00Z").active)                   // 25 hours before
        assertFalse(bar(lh455(), "2026-10-01T21:39:00Z").active)
        assertTrue(bar(lh455(), "2026-10-01T21:40:00Z").active)                    // 24
        assertTrue(bar(lh455(), "2026-10-01T22:40:00Z").active)                    // 23
        // With the rule at three hours it waits for the countdown; at 48 it is out two days ahead.
        assertFalse(bar(lh455(), "2026-10-02T18:39:00Z", before = 3).active)
        assertTrue(bar(lh455(), "2026-10-02T18:40:00Z", before = 3).active)
        assertTrue(bar(lh455(), "2026-09-30T21:40:00Z", before = 48).active)
        val down = lh455(left, fra(actual = "2026-10-03T10:01"), FlightState.LANDED)                   // landed 08:01 UTC
        assertTrue(bar(down, "2026-10-03T09:00:00Z").active)                       // 59 minutes after
        assertFalse(bar(down, "2026-10-03T09:02:00Z").active)                      // 61
    }

    // ---- time zones

    @Test fun whereTheDeviceIsChangesNoCountdownAndNoAirportsTime() {
        val soon = lh455(sfo(expected = "2026-10-02T14:40"))
        val air = inAir(fra(expected = "2026-10-03T10:25"))
        for (zone in listOf("America/Los_Angeles", "Europe/Berlin", "Asia/Tokyo", "Asia/Kathmandu", "Pacific/Kiritimati", "Pacific/Pago_Pago", "UTC")) {
            val v = FlightVoices.us(ZoneId.of(zone))
            assertEquals(zone, "1h 37m · Gate G13", bar(soon, near, v = v).text)
            assertEquals(zone, "2h 05m · 10:25 AM", bar(air, "2026-10-03T06:20:00Z", v = v).text)
            // The airports' own times stand under the line, whatever the device's clock says; only whether their day is named is the device's.
            val c = card(air, "2026-10-03T06:20:00Z", v = v)
            assertTrue(zone, c.from.time.endsWith("2:47 PM"))
            assertTrue(zone, c.to.time.endsWith("10:25 AM"))
            assertEquals(zone, "Lands in 2\u00A0h 05\u00A0min", c.headline)
            assertTrue(zone, bar(lh455(), morning, v = v).text!!.endsWith("2:40 PM"))
        }
        // In Tokyo it is already Saturday when the flight leaves San Francisco on Friday: the day is named there, and not in San Francisco.
        assertEquals("LH 455 · 2:40 PM", bar(lh455(), morning, v = FlightVoices.us(la)).text)
        assertEquals("LH 455 · Fri 2:40 PM", bar(lh455(), morning, v = FlightVoices.us(ZoneId.of("Asia/Tokyo"))).text)
    }

    @Test fun aFlightAcrossTheDateLineThatLandsBeforeItLeftCountsRight() {
        // Tokyo to Honolulu: leaves Saturday at 8 PM and lands Saturday at 8 AM, seven hours later.
        val tokyo = FlightEnd("NRT", "Tokyo", time("2026-10-03T20:00"), actual = time("2026-10-03T20:00"), offset = 540)
        val honolulu = FlightEnd("HNL", "Honolulu", time("2026-10-03T08:00"), expected = time("2026-10-03T08:00"), offset = -600)
        val f = Flight("JL74", "Japan Airlines", tokyo, honolulu, FlightState.IN_AIR)
        val b = bar(f, "2026-10-03T15:55:00Z")
        assertEquals("2h 05m · 8:00 AM", b.text)
        val c = card(f, "2026-10-03T15:55:00Z")
        assertEquals("Lands in 2\u00A0h 05\u00A0min", c.headline)
        assertEquals(295.0 / 420.0, c.share!!, 1e-9)
        // And it is asked about, and cleared, by moments too.
        assertEquals(30 * 60_000L, FlightRules.every(tracked(f, "2026-10-03T15:55:00Z"), at("2026-10-03T15:55:00Z")))
        assertFalse(FlightRules.cleared(tracked(f, "2026-10-03T15:55:00Z"), at("2026-10-04T17:59:00Z")))
        assertTrue(FlightRules.cleared(tracked(f, "2026-10-03T15:55:00Z"), at("2026-10-04T18:00:00Z")))
    }

    // ---- the menu's card

    @Test fun theHeaderNamesTheFlightAndItsRoute() {
        val c = card(lh455(), near)
        assertEquals("LH 455 · Lufthansa", c.title)
        assertEquals("San Francisco → Frankfurt", c.route)
        assertEquals("DLH455", c.callsign)
        // Without an airline's name the number stands alone; without cities, the letters.
        val bare = card(lh455(sfo().copy(city = ""), fra().copy(city = "")).copy(airline = ""), near)
        assertEquals("LH 455", bare.title)
        assertEquals("SFO → FRA", bare.route)
    }

    @Test fun theHeadlineAndItsBadgeBeforeItLeaves() {
        val wednesday = "2026-09-30T19:00:00Z"
        // More than three hours off: when it leaves, with its day when that is not today.
        assertEquals("Leaves Fri 2:40 PM", card(lh455(), wednesday).headline)
        assertEquals("Leaves 2:40 PM", card(lh455(), morning).headline)
        assertEquals("Leaves Dec 24, 2:40 PM", card(lh455(sfo(planned = "2026-12-24T14:40"), fra(planned = "2026-12-25T10:25")), morning).headline)
        // The plan alone is "Planned"; with a time of the service's own, on time or delayed.
        card(lh455(), morning).let { assertEquals("Planned", it.badge); assertEquals(Kind.PLAIN, it.kind) }
        card(lh455(sfo(expected = "2026-10-02T14:40")), morning).let { assertEquals("On time", it.badge); assertEquals(Kind.GOOD, it.kind) }
        val late = lh455(sfo(planned = "2026-10-02T14:15", expected = "2026-10-02T14:40"))
        card(late, morning).let { assertEquals("Delayed 25\u00A0min", it.badge); assertEquals(Kind.LATE, it.kind) }
        // Within three hours: the countdown, written out, with two places for the minutes from an hour on.
        val c = card(late, near)
        assertEquals("Leaves in 1\u00A0h 37\u00A0min", c.headline)
        assertEquals("Delayed 25\u00A0min", c.badge)
        assertFalse(c.gone)
        assertEquals("Leaves in 1 hour 37 minutes. Delayed 25 minutes.", c.spoken)
        assertEquals("Leaves in 45\u00A0min", card(lh455(), "2026-10-02T20:55:00Z").headline)
        assertEquals("Leaves in 2\u00A0h 05\u00A0min", card(lh455(), "2026-10-02T19:35:00Z").headline)
        assertEquals("Delayed 1\u00A0h 25\u00A0min", card(lh455(sfo(planned = "2026-10-02T13:15", expected = "2026-10-02T14:40")), near).badge)
        // The plane rests at the start.
        assertEquals(0.0, c.share!!, 0.0)
    }

    @Test fun theHeadlineAndItsBadgeInTheAir() {
        val late = card(inAir(fra(expected = "2026-10-03T10:45")), "2026-10-03T06:40:00Z")
        assertEquals("Lands in 2\u00A0h 05\u00A0min", late.headline)
        assertEquals("Delayed 20\u00A0min", late.badge)
        assertEquals(Kind.LATE, late.kind)
        assertEquals("Lands in 2 hours 5 minutes. Delayed 20 minutes.", late.spoken)
        val early = card(inAir(fra(expected = "2026-10-03T10:07")), "2026-10-03T06:02:00Z")
        assertEquals("18\u00A0min early", early.badge)
        assertEquals(Kind.GOOD, early.kind)
        assertEquals("On time", card(inAir(fra(expected = "2026-10-03T10:25")), "2026-10-03T06:20:00Z").badge)
        // Only the plan of the landing: no badge, and the sentence is the headline alone.
        val planOnly = card(inAir(fra()), "2026-10-03T06:20:00Z")
        assertNull(planOnly.badge)
        assertEquals("Lands in 2 hours 5 minutes", planOnly.spoken)
        // No landing time at all.
        val blind = card(inAir(fra(planned = null)), "2026-10-03T06:20:00Z")
        assertEquals("In the air", blind.headline)
        assertNull(blind.badge)
        assertNull(blind.share)
    }

    @Test fun theHeadlineAndItsBadgeAfterLanding() {
        val down = lh455(left, fra(actual = "2026-10-03T10:01"), FlightState.LANDED, aircraft = "Boeing 747-8")   // 08:01 UTC, 24 minutes before its plan
        assertEquals("Just landed", card(down, "2026-10-03T08:01:30Z").headline)
        val c = card(down, "2026-10-03T08:21:00Z")
        assertEquals("Landed 20\u00A0min ago", c.headline)
        assertEquals("24\u00A0min early", c.badge)
        assertEquals("Landed 20 minutes ago. 24 minutes early.", c.spoken)
        assertEquals(1.0, c.share!!, 0.0)
        assertEquals("Landed 59\u00A0min ago", card(down, "2026-10-03T09:00:00Z").headline)
        // After an hour it is "Landed", and counts no more.
        assertEquals("Landed", card(down, "2026-10-03T09:01:00Z").headline)
        assertEquals("Landed", card(down, "2026-10-03T14:00:00Z").headline)
        assertEquals("25\u00A0min late", card(lh455(left, fra(actual = "2026-10-03T10:50"), FlightState.LANDED), "2026-10-03T09:00:00Z").badge)
        assertEquals("On time", card(lh455(left, fra(actual = "2026-10-03T10:30"), FlightState.LANDED), "2026-10-03T09:00:00Z").badge)
        // Only the plan is known: no badge.
        assertNull(card(lh455(left, fra(), FlightState.LANDED), "2026-10-03T09:00:00Z").badge)
        // One that slept through its landing: landed, the plane at the end, and whatever the last estimate said of it.
        val slept = card(inAir(fra(expected = "2026-10-03T10:45")), "2026-10-03T15:30:00Z", heard = "2026-10-03T06:00:00Z")
        assertEquals("Landed", slept.headline)
        assertEquals("20\u00A0min late", slept.badge)
        assertEquals(1.0, slept.share!!, 0.0)
    }

    @Test fun whatWillNotHappenIsAHeadlineAloneWithNoBadgeAndNoPlane() {
        val canceled = card(lh455(state = FlightState.CANCELED), near)
        assertEquals("Canceled", canceled.headline)
        assertTrue(canceled.gone)
        assertNull(canceled.badge)
        assertNull(canceled.share)
        assertEquals("Canceled", canceled.spoken)
        // Both times struck; nobody is told where to go.
        assertEquals(FlightText.End("SFO", "2:40 PM", true, "", "From San Francisco, SFO, was 2:40 PM."), canceled.from)
        assertEquals(FlightText.End("FRA", "Sat 10:25 AM", true, "", "To Frankfurt, FRA, was Sat 10:25 AM."), canceled.to)
        val diverted = card(inAir(fra(expected = "2026-10-03T10:25")).copy(state = FlightState.DIVERTED), "2026-10-03T06:20:00Z")
        assertEquals("Diverted", diverted.headline)
        assertTrue(diverted.gone)
        assertNull(diverted.badge)
        assertNull(diverted.share)
        assertFalse(diverted.from.struck)
        assertTrue(diverted.to.struck)
        val past = card(lh455(sfo(gate = null, terminal = null), timetable = true), "2026-10-02T22:40:00Z", heard = morning)
        assertEquals("From the timetable", past.headline)
        assertFalse(past.gone)
        assertNull(past.badge)
        assertNull(past.share)
    }

    @Test fun underTheLinesEndsStandWhereToGoAndThenWhereToMeetIt() {
        val before = card(lh455(sfo(planned = "2026-10-02T14:40", expected = "2026-10-02T15:05"), fra(expected = "2026-10-03T10:50")), morning)
        assertEquals(FlightText.End("SFO", "3:05 PM", false, "Gate G13 · Terminal 1", "From San Francisco, SFO, 3:05 PM, Gate G13, Terminal 1."), before.from)
        assertEquals(FlightText.End("FRA", "Sat 10:50 AM", false, "Terminal 1", "To Frankfurt, FRA, Sat 10:50 AM, Terminal 1."), before.to)
        // After it has left: the aircraft at the start; at the far end the terminal and the belt, or the gate while no belt is named.
        val air = card(inAir(fra(expected = "2026-10-03T10:50", gate = "Z69")), "2026-10-03T06:20:00Z")
        assertEquals("Boeing 747-8", air.from.words)
        assertEquals("Gate Z69 · Terminal 1", air.to.words)
        val down = card(lh455(left, fra(actual = "2026-10-03T10:01", gate = "Z69", belt = "21"), FlightState.LANDED), "2026-10-03T08:21:00Z")
        assertEquals("Terminal 1 · Belt 21", down.to.words)
        assertEquals("To Frankfurt, FRA, 10:01 AM, Terminal 1, Belt 21.", down.to.spoken)
        // What nobody names is not said, and an end with nothing under it has no words.
        val bare = card(lh455(sfo(gate = null, terminal = null).copy(city = ""), fra(terminal = null)), morning)
        assertEquals("", bare.from.words)
        assertEquals("", bare.to.words)
        assertEquals("From SFO, 2:40 PM.", bare.from.spoken)
        assertEquals("To Frankfurt, FRA, Sat 10:25 AM.", bare.to.spoken)
        // An end nobody names a time for is its city and its letters; with no city either, the letters once.
        assertEquals("To Frankfurt, FRA.", card(lh455(to = fra(planned = null, terminal = null)), morning).to.spoken)
        assertEquals("FRA", card(lh455(to = fra(planned = null, terminal = null).copy(city = "")), morning).to.spoken)
    }

    @Test fun aNumberSoldByAnotherAirlineSaysWhoFliesIt() {
        assertEquals("Operated as UA 945", card(lh455().copy(flownAs = "UA945"), near).operatedAs)
        assertNull(card(lh455(), near).operatedAs)
    }

    @Test fun aPlanFromTheTimetableAfterAClockChangeSaysItsTimesMayBeOff() {
        val plan = lh455(sfo(planned = "2026-10-28T14:40", gate = null, terminal = null), fra(planned = "2026-10-29T10:25", terminal = null), timetable = true).copy(loose = true)
        val c = card(plan, "2026-10-27T20:03:00Z")
        assertTrue(c.loose)
        assertEquals("Leaves Wed 2:40 PM", c.headline)
        assertEquals("Planned", c.badge)
        // No countdown, though it would leave within three hours by the timetable's clock.
        assertEquals("Leaves 2:40 PM", card(plan, "2026-10-28T20:03:00Z").headline)
        assertEquals("LH 455 · 2:40 PM", bar(plan, "2026-10-28T20:03:00Z").text)
        assertFalse(card(lh455(), near).loose)
    }

    @Test fun theNoteCreditsTheServiceAndSaysWhatTheLastAskCameTo() {
        val heard = "2026-10-02T16:40:00Z"                                         // 9:40 AM in San Francisco
        val t = tracked(lh455(), heard)
        fun note(t: Tracked) = FlightText.card(t, at(near), us)!!.note
        assertEquals("Times are each airport's own. Flight data by AirLabs · updated 9:40 AM", note(t))
        assertEquals("Times are each airport's own. Flight data by AirLabs · no connection, updated 9:40 AM", note(t.copy(failure = Failure.OFFLINE, failures = 1)))
        assertEquals("Times are each airport's own. Flight data by AirLabs · no answer, updated 9:40 AM", note(t.copy(failure = Failure.NO_ANSWER, failures = 1)))
        assertEquals("Times are each airport's own. Flight data by AirLabs · key not accepted, updated 9:40 AM", note(t.copy(failure = Failure.REFUSED)))
        assertEquals("Times are each airport's own. Flight data by AirLabs · no lookups left this month, updated 9:40 AM", note(t.copy(failure = Failure.USED_UP)))
        assertEquals("Times are each airport's own. Flight data by AirLabs · few lookups left, refresh by hand; updated 9:40 AM", note(t.copy(left = 19)))
        assertEquals("Times are each airport's own. Flight data by AirLabs · updated 9:40 AM", note(t.copy(left = 20)))
        // Only a refused key puts the way to the key first.
        assertTrue(FlightText.card(t.copy(failure = Failure.REFUSED), at(near), us)!!.changeKey)
        assertFalse(FlightText.card(t.copy(failure = Failure.USED_UP), at(near), us)!!.changeKey)
        // An answer from another day says which.
        assertEquals("Times are each airport's own. Flight data by AirLabs · updated Thu 9:40 AM", FlightText.card(tracked(lh455(), "2026-10-01T16:40:00Z"), at(near), us)!!.note)
        // A landed flight is asked about no more: few lookups left is nothing to say of it.
        val down = tracked(lh455(left, fra(actual = "2026-10-03T10:01"), FlightState.LANDED), "2026-10-03T08:10:00Z").copy(left = 3)
        assertEquals("Times are each airport's own. Flight data by AirLabs · updated 1:10 AM", FlightText.card(down, at("2026-10-03T08:21:00Z"), us)!!.note)
    }

    @Test fun theCopiedStatusIsOneLineWithAbsoluteTimes() {
        val before = lh455(sfo(planned = "2026-10-02T14:40", expected = "2026-10-02T15:05"), fra(expected = "2026-10-03T10:50"))
        assertEquals("LH 455 SFO → FRA · Leaves 3:05 PM · Delayed 25 min · Gate G13 · Lands Sat 10:50 AM", card(before, morning).copy)
        val air = lh455(sfo(actual = "2026-10-02T15:05"), fra(planned = "2026-10-03T10:30", expected = "2026-10-03T10:50"), FlightState.IN_AIR)
        assertEquals("LH 455 SFO → FRA · Left 3:05 PM · Delayed 20 min · Lands Sat 10:50 AM", card(air, "2026-10-03T02:00:00Z").copy)
        // Read in Honolulu, where it is still Friday when the flight lands on Saturday morning in Frankfurt.
        val down = lh455(sfo(actual = "2026-10-02T15:05"), fra(planned = null, actual = "2026-10-03T10:01", belt = "21"), FlightState.LANDED)
        assertEquals("LH 455 SFO → FRA · Left 3:05 PM · Landed Sat 10:01 AM · Belt 21", card(down, "2026-10-03T08:21:00Z", v = FlightVoices.us(ZoneId.of("Pacific/Honolulu"))).copy)
        assertEquals("LH 455 SFO → FRA · Canceled", card(lh455(state = FlightState.CANCELED), near).copy)
        // What is not known is left out, and a landing against its plan is said where it is known.
        assertEquals("LH 455 SFO → FRA · Leaves 2:40 PM · Planned · Lands Sat 10:25 AM", card(lh455(sfo(gate = null)), morning).copy)
        val late = lh455(left, fra(actual = "2026-10-03T10:50", belt = "4"), FlightState.LANDED)
        assertEquals("LH 455 SFO → FRA · Left Fri 2:47 PM · Landed 10:50 AM · 25 min late · Belt 4", card(late, "2026-10-03T09:00:00Z").copy)
        // Plain spaces on the clipboard: it is pasted into other apps.
        assertFalse('\u00A0' in card(before, morning).copy)
        // A device writes its times with a narrow no-break space before AM and PM: that one too.
        val device = FlightText.Voice(us.locale, us.zone, word = { us.say(it) }, plural = { c, n -> us.count(c, n) }, clock = { t, form -> us.time(t, form).replace(' ', '\u202F') })
        assertEquals("LH 455 SFO → FRA · Leaves 3:05 PM · Delayed 25 min · Gate G13 · Lands Sat 10:50 AM", card(before, morning, v = device).copy)
    }

    // ---- what a press of Track can come to

    @Test fun whatWentWrongInItsExactWords() {
        assertEquals("No flight LH 9999 found. Check the number.", FlightText.error(Failure.NOT_FOUND, "LH 9999", null, us))
        assertEquals("LH 455 doesn't fly on Sat, Oct 10.", FlightText.error(Failure.NOT_THAT_DAY, "LH 455", LocalDate.of(2026, 10, 10), us))
        assertEquals("AirLabs doesn't accept this key.", FlightText.error(Failure.REFUSED, "LH 455", null, us))
        assertEquals("This key's lookups for the month are used up.", FlightText.error(Failure.USED_UP, "LH 455", null, us))
        assertEquals("No connection", FlightText.error(Failure.OFFLINE, "LH 455", null, us))
        assertEquals("AirLabs didn't answer. Try again in a moment.", FlightText.error(Failure.NO_ANSWER, "LH 455", null, us))
        assertEquals("A flight number looks like LH 455 or DLH455.", FlightVoices.string("flight_err_not_number"))
        // The first two are the field's own (its text is what is wrong); the others stand under it as a message.
        assertEquals(listOf(true, false, false, true, false, false), Failure.entries.map { FlightText.ofTheField(it) })
    }

    @Test fun theDayChipsAreTheNextFlightTodayTomorrowAndFiveDaysMore() {
        val monday = LocalDate.of(2026, 10, 5)
        assertEquals(listOf(null, monday, monday.plusDays(1), monday.plusDays(2), monday.plusDays(3), monday.plusDays(4), monday.plusDays(5), monday.plusDays(6)), FlightText.days(monday))
        // The five after tomorrow are called by their weekday.
        assertEquals(listOf("Wed", "Thu", "Fri", "Sat", "Sun"), FlightText.days(monday).drop(3).map { us.time(it!!.atStartOfDay(), FlightText.TimeForm.DAY) })
    }

    @Test fun howManyLookupsAreLeftIsSaidAsACount() {
        assertEquals("About 940 lookups left this month.", us.count(FlightText.Count.FLIGHT_LOOKUPS_LEFT, 940))
        assertEquals("About 1 lookup left this month.", us.count(FlightText.Count.FLIGHT_LOOKUPS_LEFT, 1))
        assertEquals("About 0 lookups left this month.", us.count(FlightText.Count.FLIGHT_LOOKUPS_LEFT, 0))
    }

    // ---- the words themselves

    @Test fun everyWordIsInTheResources() {
        // A name here that the resources do not have would be a crash on a device; so would a sentence with a place too many.
        for (v in listOf(us, FlightVoices.british())) {
            for (w in FlightText.Word.entries) assertTrue(w.name, (if (w.name.startsWith("COMMON_DURATION")) v.say(w, 1, 2) else v.say(w, "a", "b", "c")).isNotEmpty())
            for (c in FlightText.Count.entries) assertTrue(c.name, v.count(c, 2).isNotEmpty())
        }
    }

    @Test fun theItemGivesEveryWordTheResourceOfItsName() {
        // These tests read the words from the resource files by name; the app gets them through a table in the item.
        // Read here as text: a word paired with another's resource would say the wrong thing on a device, and nowhere else.
        val item = java.io.File("src/main/java/com/meridian11/books/bentobar/items/FlightItem.kt").readText()
        val words = Regex("""Word\.([A-Z_0-9]+) -> R\.string\.([a-z_0-9]+)""").findAll(item).map { it.groupValues[1] to it.groupValues[2] }.toList()
        assertEquals(FlightText.Word.entries.map { it.name }.sorted(), words.map { it.first }.sorted())
        for ((word, resource) in words) assertEquals(word.lowercase(), resource)
        val counts = Regex("""Count\.([A-Z_0-9]+) -> R\.plurals\.([a-z_0-9]+)""").findAll(item).map { it.groupValues[1] to it.groupValues[2] }.toList()
        assertEquals(FlightText.Count.entries.map { it.name }.sorted(), counts.map { it.first }.sorted())
        for ((count, resource) in counts) assertEquals(count.lowercase(), resource)
    }

    @Test fun theAboutTextSaysWhenTheServiceIsAskedAsTheRulesHaveIt() {
        val about = FlightVoices.string("about_privacy_flight")
        // After a landing it slept through, the item asks once more (FlightRulesTest.asleepThroughTheLandingItIsAskedAboutOnceOnWaking),
        // and a new key asks about a flight that waited for one (aNewKeyAsksAtOnceOnlyAboutAFlightThatWaitedForOne): "never" was not true.
        assertFalse(about, about.contains("never asks"))
        assertTrue(about, about.contains(" Once it knows the flight has landed it stops asking; after sleeping through a landing it asks once more. "))
        assertTrue(about, about.contains("when you track a flight, when you press Refresh or save a new key, and while it follows the flight, "))
        // What it says of the pace is the rules' own numbers.
        assertTrue(about, about.contains("about every 3 hours until 3 hours before departure, then every 30 minutes or sooner until it lands; a failed try is repeated sooner."))
        assertEquals(java.time.Duration.ofHours(3), FlightRules.FAR)
        assertEquals(java.time.Duration.ofMinutes(30), FlightRules.NEAR)
    }

    @Test fun charactersAreCountedAsAReaderSeesThem() {
        assertEquals(20, FlightText.length("LH 455 · Fri 2:40 PM"))
        assertEquals(1, FlightText.length("e\u0301"))                             // a letter and its accent
        assertEquals(0, FlightText.length(""))
    }
}
