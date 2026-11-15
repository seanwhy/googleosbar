package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.items.InUseRules.Click
import com.meridian11.books.bentobar.util.Sym
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InUseRulesTest {
    private val nothing = InUse()

    @Test fun aCallCountsFromWhenTheFirstOfThemCameOn() {
        val camera = InUseRules.step(nothing, camerasBusy = 1, ownCamera = false, recordings = 0, muted = false, now = 1_000)
        val both = InUseRules.step(camera, camerasBusy = 1, ownCamera = false, recordings = 1, muted = false, now = 5_000)
        assertTrue(both.camera && both.mic)
        assertEquals(1_000L, both.since)
        assertEquals("1:00", InUseRules.text(both, now = 61_000, time = true))
        assertEquals("1:02:11", InUseRules.text(both, now = 1_000 + 3_731_000, time = true))
        // The camera goes, the mic stays: still the same call, still counted from its start.
        val mic = InUseRules.step(both, camerasBusy = 0, ownCamera = false, recordings = 1, muted = false, now = 9_000)
        assertEquals(1_000L, mic.since)
        assertNull("the time is an option", InUseRules.text(both, now = 61_000, time = false))
    }

    @Test fun nothingInUseHasNoTimeAndIsNotActive() {
        val after = InUseRules.step(InUse(camera = true, since = 1_000), camerasBusy = 0, ownCamera = false, recordings = 0, muted = false, now = 5_000)
        assertFalse(after.camera || after.mic)
        assertEquals(0L, after.since)
        assertNull(InUseRules.text(after, now = 9_000, time = true))
        assertFalse(InUseRules.active(after))
        assertEquals(Tone.NORMAL, InUseRules.tone(after))
        assertEquals(Sym.VIDEOCAM, InUseRules.icon(after))
        // A camera that comes on again starts a new count.
        assertEquals(7_000L, InUseRules.step(after, 1, false, 0, false, 7_000).since)
    }

    @Test fun theMirrorsOwnCameraIsNotACall() {
        assertFalse(InUseRules.step(nothing, camerasBusy = 1, ownCamera = true, recordings = 0, muted = false, now = 1).camera)
        assertTrue(InUseRules.step(nothing, camerasBusy = 2, ownCamera = true, recordings = 0, muted = false, now = 1).camera)
        assertTrue(InUseRules.step(nothing, camerasBusy = 1, ownCamera = false, recordings = 0, muted = false, now = 1).camera)
    }

    @Test fun whatTheBarShowsFollowsWhatIsInUseAndAMuteComesFirst() {
        val camera = InUse(camera = true, since = 1)
        val mic = InUse(mic = true, since = 1)
        val muted = InUse(mic = true, since = 1, muted = true)
        assertEquals(Sym.VIDEOCAM, InUseRules.icon(camera))
        assertEquals(Sym.MIC, InUseRules.icon(mic))
        assertEquals(Sym.VIDEOCAM, InUseRules.icon(camera.copy(mic = true)))
        assertEquals(Sym.MIC_OFF, InUseRules.icon(muted.copy(camera = true)))
        assertEquals(Tone.ACCENT, InUseRules.tone(camera))
        assertEquals(Tone.WARN, InUseRules.tone(muted))
        // A muted microphone says so even while nothing records: the user must be able to see it.
        assertTrue(InUseRules.active(InUse(muted = true)))
        assertEquals(Tone.WARN, InUseRules.tone(InUse(muted = true)))
    }

    @Test fun aMuteStaysOursOnlyWhileTheMicrophoneIsMuted() {
        val ours = InUse(mic = true, since = 1, muted = true, ours = true)
        assertTrue(InUseRules.step(ours, 0, false, 1, muted = true, now = 2).ours)
        // Someone unmuted it: there is nothing of BentoBar's to give back any more.
        assertFalse(InUseRules.step(ours, 0, false, 1, muted = false, now = 2).ours)
        assertFalse("a mute found is not ours", InUseRules.step(nothing, 0, false, 1, muted = true, now = 2).ours)
    }

    @Test fun aClickOpensTheMenuUnlessMutingByClickIsOn() {
        val idle = InUse()
        val mic = InUse(mic = true, since = 1)
        val oursMuted = InUse(mic = true, since = 1, muted = true, ours = true)
        val theirsMuted = InUse(mic = true, since = 1, muted = true)
        for (s in listOf(idle, mic, oursMuted, theirsMuted)) assertEquals(Click.MENU, InUseRules.click(s, byClick = false))
        assertEquals(Click.MENU, InUseRules.click(idle, byClick = true))
        assertEquals(Click.MUTE, InUseRules.click(mic, byClick = true))
        assertEquals(Click.UNMUTE, InUseRules.click(oursMuted, byClick = true))
        // Somebody else's mute is shown and left alone.
        assertEquals(Click.MENU, InUseRules.click(theirsMuted, byClick = true))
        for (s in listOf(idle, mic, oursMuted)) assertEquals(Click.MENU, InUseRules.click(s, byClick = true, offer = false))
    }

    @Test fun aMuteOfOursIsUndoneWhenTheItemGoesButNotWhenTheBarHides() {
        val ours = InUse(muted = true, ours = true)
        assertTrue(InUseRules.undoMute(ours, Settle.LEFT_LAYOUT, placed = false))
        assertTrue(InUseRules.undoMute(ours, Settle.SERVICE_ENDING, placed = true))
        assertFalse(InUseRules.undoMute(ours, Settle.SERVICE_STARTED, placed = true))
        assertTrue(InUseRules.undoMute(ours, Settle.SERVICE_STARTED, placed = false))
        assertFalse("the bar hiding", InUseRules.undoMute(ours, null, placed = true))
        assertFalse(InUseRules.undoMute(ours, null, placed = false))
        val theirs = InUse(muted = true)
        for (why in listOf(Settle.LEFT_LAYOUT, Settle.SERVICE_ENDING, Settle.SERVICE_STARTED, null))
            for (placed in listOf(true, false)) assertFalse("$why $placed", InUseRules.undoMute(theirs, why, placed))
        // Ours, but someone unmuted it since: nothing to undo.
        assertFalse(InUseRules.undoMute(InUse(ours = true), Settle.LEFT_LAYOUT, placed = false))
    }
}
