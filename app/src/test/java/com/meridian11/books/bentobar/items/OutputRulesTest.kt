package com.meridian11.books.bentobar.items

import android.media.AudioDeviceInfo
import com.meridian11.books.bentobar.items.OutputRules.Kind
import com.meridian11.books.bentobar.items.OutputRules.Out
import com.meridian11.books.bentobar.util.Sym
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputRulesTest {
    private val speaker = Out(Kind.SPEAKER, "Laptop")
    private val buds = Out(Kind.BLUETOOTH, "Buds")
    private val wired = Out(Kind.WIRED, "Headphones")
    private val usb = Out(Kind.USB, "USB audio")
    private val monitor = Out(Kind.DISPLAY, "Monitor")
    private val dock = Out(Kind.OTHER, "Dock")

    @Test fun headphonesBeforeTheSpeaker() {
        assertEquals(buds, OutputRules.current(listOf(speaker, monitor, usb, wired, buds)))
        assertEquals(wired, OutputRules.current(listOf(speaker, monitor, usb, wired)))
        assertEquals(usb, OutputRules.current(listOf(speaker, monitor, usb)))
        assertEquals(monitor, OutputRules.current(listOf(speaker, monitor)))
        assertEquals(speaker, OutputRules.current(listOf(dock, speaker)))
        assertEquals("only what Android could not name", dock, OutputRules.current(listOf(dock)))
        assertNull(OutputRules.current(emptyList()))
    }

    @Test fun whereAndroidSaysTheSoundGoesComesBeforeTheGuess() {
        // The user picked the speaker in the output switcher while the buds stay connected.
        assertEquals(speaker, OutputRules.current(listOf(speaker, buds), routed = listOf(speaker)))
        // Two at once (a display that mirrors the sound): the guess's order between them.
        assertEquals(monitor, OutputRules.current(listOf(speaker, monitor), routed = listOf(speaker, monitor)))
        // A route that is not one of the listed outputs (a call's, a recording's) is no answer.
        assertEquals(buds, OutputRules.current(listOf(speaker, buds), routed = listOf(Out(Kind.OTHER, "Submix"))))
    }

    @Test fun theSpeakerAloneIsNotActive() {
        assertFalse(OutputRules.active(speaker))
        assertFalse(OutputRules.active(null))
        for (out in listOf(buds, wired, usb, monitor, dock)) assertTrue("$out", OutputRules.active(out))
    }

    @Test fun everyKindHasAGlyph() {
        val glyphs = Sym::class.java.declaredFields.filter { it.type == String::class.java }.map { it.isAccessible = true; it.get(null) as String }.toSet()
        for (kind in Kind.entries) {
            val glyph = OutputRules.icon(kind)
            assertTrue("$kind has no glyph", glyph.isNotEmpty() && glyph in glyphs)
        }
        assertEquals(Sym.HEADPHONES, OutputRules.icon(Kind.BLUETOOTH))
        assertEquals(Sym.HEADPHONES, OutputRules.icon(Kind.WIRED))
        assertEquals(Sym.USB, OutputRules.icon(Kind.USB))
        assertEquals(Sym.TV, OutputRules.icon(Kind.DISPLAY))
        assertEquals(Sym.SPEAKER, OutputRules.icon(Kind.SPEAKER))
        assertEquals(Sym.SPEAKER, OutputRules.icon(Kind.OTHER))
    }

    @Test fun aNameIsCutAtSixteen() {
        assertEquals("Buds", OutputRules.name(buds) { "word" })
        assertEquals("Sixteen chars ok", OutputRules.name(Out(Kind.BLUETOOTH, "Sixteen chars ok")) { "word" })
        val cut = OutputRules.name(Out(Kind.BLUETOOTH, "Somebody's very long headphones")) { "word" }
        assertEquals(16, cut.length)
        assertTrue(cut, cut.endsWith("…"))
        // A name from a device is text from outside: one line, nothing that turns it round.
        assertEquals("Buds Pro", OutputRules.name(Out(Kind.BLUETOOTH, "Buds\n‮Pro")) { "word" })
        // The speaker and a device with no name are called by their kind.
        assertEquals("Speaker", OutputRules.name(speaker) { if (it == Kind.SPEAKER) "Speaker" else "?" })
        assertEquals("USB", OutputRules.name(Out(Kind.USB, "  ")) { if (it == Kind.USB) "USB" else "?" })
    }

    @Test fun androidsTypesAreSortedIntoKindsAndTheCallPathsLeftOut() {
        assertEquals(Kind.SPEAKER, OutputRules.kind(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        for (t in listOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES)) assertEquals(Kind.WIRED, OutputRules.kind(t))
        for (t in listOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_HEARING_AID, AudioDeviceInfo.TYPE_BLE_BROADCAST)) assertEquals(Kind.BLUETOOTH, OutputRules.kind(t))
        for (t in listOf(AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY)) assertEquals(Kind.USB, OutputRules.kind(t))
        for (t in listOf(AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_HDMI_EARC)) assertEquals(Kind.DISPLAY, OutputRules.kind(t))
        assertEquals(Kind.OTHER, OutputRules.kind(AudioDeviceInfo.TYPE_DOCK))
        // The earpiece, a call's line, a headset's call path, the speaker's own safe twin and a recording's submix are not where music goes.
        for (t in listOf(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, AudioDeviceInfo.TYPE_TELEPHONY, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE, AudioDeviceInfo.TYPE_REMOTE_SUBMIX)) assertFalse("$t", OutputRules.listed(t))
        for (t in listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_USB_HEADSET)) assertTrue("$t", OutputRules.listed(t))
    }
}
