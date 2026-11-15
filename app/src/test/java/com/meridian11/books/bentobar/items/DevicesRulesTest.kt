package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.items.DevicesRules.Device
import com.meridian11.books.bentobar.items.DevicesRules.Kind
import com.meridian11.books.bentobar.items.DevicesRules.Seen
import com.meridian11.books.bentobar.util.Sym
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Device batteries: which devices are listed, which one the bar shows, and what it says, without a device. */
class DevicesRulesTest {
    private val text = HeatDevicesStrings("strings_devices.xml", "strings.xml")
    private val words = DevicesRules.Words(
        none = { text["devices_none"] },
        level = { name, percent -> text.plural("devices_desc", percent, name, percent) },
        low = { name, percent -> text.plural("devices_desc_low", percent, name, percent) },
        charging = { name, percent -> text.plural("devices_desc_charging", percent, name, percent) },
        unknown = { name -> text.format("devices_desc_unknown", name) },
        unnamed = { text["common_unknown"] },
    )

    // What Android's InputDevice says a device is (its sources) and what its keyboard is like.
    private val mouseSources = 0x2002
    private val keyboardSources = 0x101
    private val stylusSources = 0x4002
    private val alphabetic = 2
    private val someKeys = 1
    private val discharging = 3
    private val charging = 2

    private fun mouse(percent: Int?, charging: Boolean = false, name: String = "MX Master 3S") = Device("m:$name", name, Kind.MOUSE, percent, charging)
    private fun keyboard(percent: Int?, charging: Boolean = false, name: String = "Keychron K3") = Device("k:$name", name, Kind.KEYBOARD, percent, charging)
    private fun stylus(percent: Int?, charging: Boolean = false) = Device("s", "Stylus", Kind.STYLUS, percent, charging)
    private fun bar(vararg devices: Device, rule: Int = 20) = DevicesRules.bar(DevicesRules.order(devices.toList()), rule, words)

    // ---- what kind of device it is

    @Test fun aDevicesKindFollowsWhatAndroidSaysItIs() {
        assertEquals(Kind.MOUSE, DevicesRules.kind(mouseSources, 0))
        assertEquals(Kind.MOUSE, DevicesRules.kind(0x100008, 0)) // a touchpad
        assertEquals(Kind.MOUSE, DevicesRules.kind(0x10004, 0)) // a trackball
        assertEquals(Kind.KEYBOARD, DevicesRules.kind(keyboardSources, alphabetic))
        assertEquals(Kind.STYLUS, DevicesRules.kind(stylusSources, 0))
        assertEquals(Kind.STYLUS, DevicesRules.kind(0xC002, 0)) // a Bluetooth stylus
        assertEquals(Kind.GAMEPAD, DevicesRules.kind(0x401, 0))
        assertEquals(Kind.GAMEPAD, DevicesRules.kind(0x1000010, 0)) // a joystick
    }

    @Test fun aMouseWithAFewKeysIsStillAMouseAndKeysWithAPointerAreAKeyboard() {
        // Mice report their buttons as keys; only a keyboard with letters is a keyboard.
        assertEquals(Kind.MOUSE, DevicesRules.kind(mouseSources or keyboardSources, someKeys))
        assertEquals(Kind.KEYBOARD, DevicesRules.kind(mouseSources or keyboardSources, alphabetic))
        assertEquals(Kind.KEYBOARD, DevicesRules.kind(0x100008 or keyboardSources, alphabetic)) // a keyboard with a touchpad
    }

    @Test fun aStylusComesFirstThenAGamepadWhateverElseTheDeviceHas() {
        // A game controller has keys, a pad of arrows and often a touchpad; a pen's screen is a pointer too.
        assertEquals(Kind.GAMEPAD, DevicesRules.kind(0x401 or 0x201 or keyboardSources or mouseSources, alphabetic))
        assertEquals(Kind.STYLUS, DevicesRules.kind(stylusSources or 0x1002 or keyboardSources, alphabetic))
        assertEquals(Kind.STYLUS, DevicesRules.kind(stylusSources or 0x401, 0))
    }

    @Test fun anythingElseIsShownAsAMouse() {
        assertEquals(Kind.MOUSE, DevicesRules.kind(0, 0))
        assertEquals(Kind.MOUSE, DevicesRules.kind(keyboardSources, someKeys)) // a remote, a presenter
        assertEquals(Kind.MOUSE, DevicesRules.kind(0x400000, 0)) // a dial
    }

    @Test fun everyKindHasItsGlyph() {
        assertEquals(Sym.MOUSE, Kind.MOUSE.glyph)
        assertEquals(Sym.KEYBOARD, Kind.KEYBOARD.glyph)
        assertEquals(Sym.STYLUS, Kind.STYLUS.glyph)
        assertEquals(Sym.SPORTS_ESPORTS, Kind.GAMEPAD.glyph)
    }

    // ---- what a device says about its battery

    @Test fun aLevelIsAWholePercentage() {
        assertEquals(15, DevicesRules.percent(0.15f))
        assertEquals(85, DevicesRules.percent(0.85f))
        assertEquals(29, DevicesRules.percent(0.29f)) // 28.999998 in a float: rounded, not cut
        assertEquals(0, DevicesRules.percent(0f))
        assertEquals(100, DevicesRules.percent(1f))
        assertEquals(100, DevicesRules.percent(1.2f)) // more than full is full
    }

    @Test fun aLevelThatIsNoNumberIsUnknown() {
        assertNull(DevicesRules.percent(Float.NaN))
        assertNull(DevicesRules.percent(-1f))
        assertNull(DevicesRules.percent(Float.POSITIVE_INFINITY))
    }

    @Test fun chargingAndFullCountAsCharging() {
        assertTrue(DevicesRules.charging(charging))
        assertTrue(DevicesRules.charging(5)) // full, on its charger
        assertFalse(DevicesRules.charging(discharging))
        assertFalse(DevicesRules.charging(4)) // not charging
        assertFalse(DevicesRules.charging(1)) // unknown
        assertFalse(DevicesRules.charging(0))
    }

    // ---- which devices are listed

    @Test fun aDeviceThatReportsABatteryIsListedWithNameKindAndLevel() {
        val listed = DevicesRules.devices(listOf(Seen("a", "MX Master 3S", mouseSources, 0, present = true, capacity = 0.15f, status = discharging)))
        assertEquals(listOf(Device("a", "MX Master 3S", Kind.MOUSE, 15, charging = false)), listed)
    }

    @Test fun aDeviceWithoutABatteryIsNotListed() {
        // The built-in keyboard, a wired mouse, a receiver that passes no level on.
        val listed = DevicesRules.devices(listOf(
            Seen("builtin", "AT Translated Set 2 keyboard", keyboardSources, alphabetic, present = false, capacity = Float.NaN, status = 1),
            Seen("wired", "USB Optical Mouse", mouseSources, 0, present = false, capacity = 0.5f, status = discharging),
        ))
        assertEquals(emptyList<Device>(), listed)
    }

    @Test fun noDeviceAtAllIsAnEmptyList() = assertEquals(emptyList<Device>(), DevicesRules.devices(emptyList()))

    @Test fun aDeviceWithABatteryButNoLevelIsListedAsUnknown() {
        val listed = DevicesRules.devices(listOf(Seen("s", "Stylus", stylusSources, 0, present = true, capacity = Float.NaN, status = 1)))
        assertEquals(listOf(Device("s", "Stylus", Kind.STYLUS, null, charging = false)), listed)
    }

    @Test fun oneDeviceThatAndroidShowsAsTwoInputDevicesIsListedOnce() {
        // A keyboard with a touchpad: its keys and its pointer are two input devices with one descriptor.
        val listed = DevicesRules.devices(listOf(
            Seen("k3", "Keychron K3", keyboardSources, alphabetic, present = true, capacity = 0.85f, status = charging),
            Seen("k3", "Keychron K3 Mouse", mouseSources, 0, present = false, capacity = Float.NaN, status = 1),
        ))
        assertEquals(listOf(Device("k3", "Keychron K3", Kind.KEYBOARD, 85, charging = true)), listed)
    }

    @Test fun theTwoHalvesOfADeviceAreOneWhicheverOfThemHasTheBattery() {
        // The half Android lists first has no battery and the longer name.
        val pointerFirst = DevicesRules.devices(listOf(
            Seen("k3", "Keychron K3 Mouse", mouseSources, 0, present = false, capacity = Float.NaN, status = 1),
            Seen("k3", "Keychron K3", keyboardSources, alphabetic, present = true, capacity = 0.4f, status = discharging),
        ))
        assertEquals(listOf(Device("k3", "Keychron K3", Kind.KEYBOARD, 40, charging = false)), pointerFirst)
        // Both halves have it, and only one knows the level; either says it is charging.
        val both = DevicesRules.devices(listOf(
            Seen("pad", "Controller", 0x401, 0, present = true, capacity = Float.NaN, status = 1),
            Seen("pad", "Controller Touchpad", mouseSources, 0, present = true, capacity = 0.4f, status = charging),
        ))
        assertEquals(listOf(Device("pad", "Controller", Kind.GAMEPAD, 40, charging = true)), both)
    }

    @Test fun twoDevicesAndroidCannotTellApartAreOneRowThatNeverHidesTheLowOne() {
        // Android's own words: indistinguishable devices, "two keyboards made by the same manufacturer", can share a descriptor.
        fun twins(first: Pair<Float, Int>, second: Pair<Float, Int>) = DevicesRules.devices(listOf(
            Seen("twin", "Keyboard", keyboardSources, alphabetic, present = true, capacity = first.first, status = first.second),
            Seen("twin", "Keyboard", keyboardSources, alphabetic, present = true, capacity = second.first, status = second.second),
        )).single()
        assertEquals(10, twins(0.8f to discharging, 0.1f to discharging).percent)
        assertEquals(10, twins(0.1f to discharging, 0.8f to discharging).percent)
        // As in the bar: the one charging at 5% doesn't hide the one running out at 30%.
        val mixed = twins(0.05f to charging, 0.3f to discharging)
        assertEquals(30, mixed.percent)
        assertFalse(mixed.charging)
        val bothCharging = twins(0.4f to charging, 0.2f to charging)
        assertEquals(20, bothCharging.percent)
        assertTrue(bothCharging.charging)
        // Neither says its level: the row says so, and that it is charging if either is.
        val unknown = twins(Float.NaN to 1, Float.NaN to charging)
        assertNull(unknown.percent)
        assertTrue(unknown.charging)
    }

    @Test fun twoDevicesOfOneNameWithDescriptorsOfTheirOwnAreTwo() {
        val listed = DevicesRules.devices(listOf(
            Seen("left", "Controller", 0x401, 0, present = true, capacity = 0.9f, status = discharging),
            Seen("right", "Controller", 0x401, 0, present = true, capacity = 0.5f, status = discharging),
        ))
        assertEquals(listOf(50, 90), listed.map { it.percent })
    }

    @Test fun devicesWithoutADescriptorAreNeverTakenForOneAnother() {
        val listed = DevicesRules.devices(listOf(
            Seen("", "Mouse", mouseSources, 0, present = true, capacity = 0.3f, status = discharging),
            Seen("", "Keyboard", keyboardSources, alphabetic, present = true, capacity = 0.6f, status = discharging),
        ))
        assertEquals(listOf("Mouse", "Keyboard"), listed.map { it.name })
        assertEquals(listOf(Kind.MOUSE, Kind.KEYBOARD), listed.map { it.kind })
    }

    @Test fun aDevicesNameIsOneShortLine() {
        // A name comes from the device: it can be anything.
        val listed = DevicesRules.devices(listOf(Seen("a", "  My\nmouse\t ", mouseSources, 0, present = true, capacity = 0.5f, status = discharging),
            Seen("b", "x".repeat(500), mouseSources, 0, present = true, capacity = 0.6f, status = discharging)))
        assertEquals("My mouse", listed[0].name)
        assertEquals(DevicesRules.NAME_MAX, listed[1].name.length)
    }

    @Test fun aDeviceWithoutANameIsCalledUnknown() {
        val nameless = DevicesRules.devices(listOf(Seen("a", " ", mouseSources, 0, present = true, capacity = 0.85f, status = discharging)))
        assertEquals("", nameless.single().name)
        assertEquals("Unknown battery 85 percent", DevicesRules.desc(nameless.single(), words))
        assertEquals("Unknown", DevicesRules.bar(nameless, 20, words).tooltip)
    }

    @Test fun theListIsLowestFirstAndDevicesWithoutALevelLastByName() {
        val listed = DevicesRules.order(listOf(stylus(null), keyboard(85, charging = true), Device("z", "Zebra pen", Kind.STYLUS, null, false), mouse(15),
            Device("a", "alpha pad", Kind.GAMEPAD, null, false), keyboard(15, name = "Atreus")))
        assertEquals(listOf("Atreus", "MX Master 3S", "Keychron K3", "alpha pad", "Stylus", "Zebra pen"), listed.map { it.name })
    }

    // ---- which device the bar shows

    @Test fun theBarShowsTheLowestDevice() {
        assertEquals("MX Master 3S", DevicesRules.shown(DevicesRules.order(listOf(keyboard(85), mouse(15))))?.name)
        assertEquals(Sym.MOUSE, bar(keyboard(85), mouse(15)).glyph)
        assertEquals(Sym.KEYBOARD, bar(keyboard(12), mouse(15)).glyph)
        assertEquals("12%", bar(keyboard(12), mouse(15)).text)
    }

    @Test fun aChargingDeviceDoesNotHideOneThatIsRunningOut() {
        // The designer's case: a mouse charging at 8% and a keyboard dying at 12%.
        val shown = bar(mouse(8, charging = true), keyboard(12))
        assertEquals("12%", shown.text)
        assertEquals(Sym.KEYBOARD, shown.glyph)
        assertEquals(Tone.WARN, shown.tone)
        assertTrue(shown.active)
    }

    @Test fun whenAllAreChargingTheLowestOfThemIsShown() {
        val shown = bar(mouse(40, charging = true), keyboard(8, charging = true))
        assertEquals("8%", shown.text)
        assertEquals(Sym.KEYBOARD, shown.glyph)
        assertEquals(Tone.NORMAL, shown.tone)
        assertFalse(shown.active)
    }

    @Test fun aDeviceWithoutALevelNeverCountsAsLowest() {
        val shown = bar(stylus(null), keyboard(85))
        assertEquals("85%", shown.text)
        assertEquals(Sym.KEYBOARD, shown.glyph)
        assertNull(DevicesRules.shown(listOf(stylus(null))))
    }

    // ---- the bar, row by row of the designer's table

    @Test fun fineIsThePlainNumber() {
        val shown = bar(keyboard(85))
        assertEquals(DevicesRules.Bar(Sym.KEYBOARD, filled = true, text = "85%", tone = Tone.NORMAL, active = false,
            desc = "Keychron K3 battery 85 percent", tooltip = "Keychron K3"), shown)
    }

    @Test fun under20AndNotChargingIsAWarningAndTheItemComesOut() {
        val shown = bar(mouse(15))
        assertEquals(DevicesRules.Bar(Sym.MOUSE, filled = true, text = "15%", tone = Tone.WARN, active = true,
            desc = "MX Master 3S battery 15 percent, low", tooltip = "MX Master 3S"), shown)
    }

    @Test fun under10AndNotChargingIsAnAlert() {
        val shown = bar(mouse(8))
        assertEquals("8%", shown.text)
        assertEquals(Tone.ALERT, shown.tone)
        assertTrue(shown.active)
        assertEquals("MX Master 3S battery 8 percent, low", shown.desc)
    }

    @Test fun chargingAtAnyLevelIsNoWarning() {
        val shown = bar(mouse(8, charging = true))
        assertEquals("8%", shown.text)
        assertEquals(Tone.NORMAL, shown.tone)
        assertFalse(shown.active)
        assertTrue(shown.filled)
        assertEquals("MX Master 3S battery 8 percent, charging", shown.desc)
    }

    @Test fun noDeviceIsTheOutlinedMouseWithoutText() {
        assertEquals(DevicesRules.Bar(Sym.MOUSE, filled = false, text = null, tone = Tone.NORMAL, active = false,
            desc = "No device reports a battery", tooltip = null), bar())
    }

    @Test fun onlyDevicesWithoutALevelLookLikeNoneAndSayWhatIsKnown() {
        val shown = bar(stylus(null))
        assertEquals(Sym.MOUSE, shown.glyph)
        assertFalse(shown.filled)
        assertNull(shown.text)
        assertEquals(Tone.NORMAL, shown.tone)
        assertFalse(shown.active)
        assertEquals("Stylus, battery level unknown", shown.desc)
    }

    @Test fun theTonesChangeAt20And10() {
        assertEquals(Tone.NORMAL, bar(mouse(100)).tone)
        assertEquals(Tone.NORMAL, bar(mouse(20)).tone)
        assertEquals(Tone.WARN, bar(mouse(19)).tone)
        assertEquals(Tone.WARN, bar(mouse(10)).tone)
        assertEquals(Tone.ALERT, bar(mouse(9)).tone)
        assertEquals(Tone.ALERT, bar(mouse(0)).tone)
        assertEquals("0%", bar(mouse(0)).text)
        assertEquals("100%", bar(mouse(100)).text)
    }

    // ---- the rule

    @Test fun theItemComesOutBelowTheRulesNumber() {
        assertTrue(bar(mouse(19)).active)
        assertFalse(bar(mouse(20)).active)
        assertTrue(bar(mouse(15), rule = 20).active)
    }

    @Test fun withTheRuleAt10ADeviceAt15StaysHiddenAndKeepsItsWarningTone() {
        val shown = bar(mouse(15), rule = 10)
        assertFalse(shown.active)
        assertEquals(Tone.WARN, shown.tone)
        assertTrue(bar(mouse(9), rule = 10).active)
    }

    @Test fun withTheRuleAt50ADeviceAt35ComesOutInTheNormalTone() {
        val shown = bar(mouse(35), rule = 50)
        assertTrue(shown.active)
        assertEquals(Tone.NORMAL, shown.tone)
        assertEquals("MX Master 3S battery 35 percent", shown.desc) // "low" is under 20, whatever the rule says
    }

    @Test fun aChargingDeviceNeverBringsTheItemOut() {
        assertFalse(bar(mouse(3, charging = true), rule = 50).active)
    }

    // ---- what a screen reader hears, for the bar and for each row of the menu

    @Test fun everySentenceOfTheDesign() {
        assertEquals("MX Master 3S battery 85 percent", DevicesRules.desc(mouse(85), words))
        assertEquals("MX Master 3S battery 15 percent, low", DevicesRules.desc(mouse(15), words))
        assertEquals("MX Master 3S battery 8 percent, charging", DevicesRules.desc(mouse(8, charging = true), words))
        assertEquals("Keychron K3 battery 85 percent, charging", DevicesRules.desc(keyboard(85, charging = true), words))
        assertEquals("Stylus, battery level unknown", DevicesRules.desc(stylus(null), words))
        assertEquals("No device reports a battery", bar().desc)
    }

    @Test fun lowIsUnder20AndNotCharging() {
        assertTrue(DevicesRules.low(mouse(19)))
        assertFalse(DevicesRules.low(mouse(20)))
        assertFalse(DevicesRules.low(mouse(8, charging = true)))
        assertFalse(DevicesRules.low(stylus(null)))
    }

    // ---- when the devices are read

    @Test fun theDevicesAreAskedOnceAMinute() = assertEquals(60_000L, DevicesRules.EVERY_MS)

    @Test fun whatWasReadLongBeforeTheBarCameBackIsNotShownAgain() {
        assertFalse(DevicesRules.tooOld(null)) // nothing was read: nothing to drop
        assertFalse(DevicesRules.tooOld(0))
        assertFalse(DevicesRules.tooOld(65_000)) // a reading on its way out
        assertTrue(DevicesRules.tooOld(3 * 60_000L))
        assertTrue(DevicesRules.tooOld(8 * 3_600_000L)) // a night with the lid closed
    }

    @Test fun aChangeOfTheDevicesIsReadAtOnce() {
        var reads = 0
        val watch = DevicesWatch { reads++; true }
        watch.tick(1_000)
        assertEquals(0, reads) // nothing changed: the minute's own reading is not this one's business
        watch.changed(2_000)
        assertEquals(1, reads)
    }

    @Test fun aDeviceThatJustConnectedIsLookedAtOnceMoreAMomentLater() {
        var reads = 0
        val watch = DevicesWatch { reads++; true }
        watch.changed(10_000)
        watch.tick(11_000); watch.tick(14_000)
        assertEquals(1, reads)
        watch.tick(15_000) // it tells its level a little after it connects
        assertEquals(2, reads)
        watch.tick(16_000); watch.tick(60_000)
        assertEquals(2, reads)
    }

    @Test fun aReadingThatWasHeldBackIsAskedForAgainTheNextSecond() {
        // The loader refuses a second reading within a second of the last: a device with two halves reports twice.
        var reads = 0
        var refuse = true
        val watch = DevicesWatch { reads++; !refuse }
        watch.changed(10_000)
        watch.tick(11_000)
        assertEquals(2, reads)
        refuse = false
        watch.tick(12_000)
        assertEquals(3, reads)
        watch.tick(13_000)
        assertEquals(3, reads) // read: nothing is left to ask
        watch.tick(15_000)
        assertEquals(4, reads) // and the look a moment after the change still follows
    }

    @Test fun changesOneAfterAnotherMoveTheSecondLook() {
        var reads = 0
        val watch = DevicesWatch { reads++; true }
        watch.changed(10_000); watch.changed(13_000)
        watch.tick(15_000)
        assertEquals(2, reads)
        watch.tick(18_000)
        assertEquals(3, reads)
    }

    @Test fun nothingIsLeftToReadOnceTheWatchIsCleared() {
        var reads = 0
        var refuse = true
        val watch = DevicesWatch { reads++; !refuse }
        watch.changed(10_000)
        watch.clear()
        refuse = false
        watch.tick(11_000); watch.tick(15_000); watch.tick(60_000)
        assertEquals(1, reads)
    }

    // ---- the test hook's devices

    @Test fun stagedDevicesAreReadFromTheHooksWords() {
        val staged = DevicesRules.staged(listOf("mouse=15", "keyboard=85c", "stylus=?"))
        assertEquals(listOf(Device("staged:0", "Mouse", Kind.MOUSE, 15, false), Device("staged:1", "Keyboard", Kind.KEYBOARD, 85, true),
            Device("staged:2", "Stylus", Kind.STYLUS, null, false)), staged)
    }

    @Test fun aStagedDeviceCanHaveAName() {
        val staged = DevicesRules.staged(listOf("mouse:MX_Master_3S=8c", "gamepad=40", "controller:Pad=?"))
        assertEquals(listOf(Device("staged:0", "MX Master 3S", Kind.MOUSE, 8, true), Device("staged:1", "Controller", Kind.GAMEPAD, 40, false),
            Device("staged:2", "Pad", Kind.GAMEPAD, null, false)), staged)
    }

    @Test fun aStagedBatteryWithoutALevelCanBeWrittenWithoutAQuestionMark() {
        // A question mark is a pattern to the shell the hook is typed into.
        val staged = DevicesRules.staged(listOf("stylus=unknown", "mouse=unknownc"))
        assertEquals(listOf(Device("staged:1", "Mouse", Kind.MOUSE, null, true), Device("staged:0", "Stylus", Kind.STYLUS, null, false)), staged)
        assertEquals(listOf(Device("staged:0", "Stylus", Kind.STYLUS, null, false)), DevicesRules.staged(listOf("stylus=?")))
        assertEquals(DevicesRules.staged(listOf("stylus=?")), DevicesRules.staged(listOf("stylus=unknown")))
    }

    @Test fun stagedDevicesComeInTheMenusOrder() {
        assertEquals(listOf(8, 85, null), DevicesRules.staged(listOf("stylus=?", "keyboard=85", "mouse=8"))?.map { it.percent })
    }

    @Test fun stagingNoneIsTheEmptyState() {
        assertEquals(emptyList<Device>(), DevicesRules.staged(listOf("none")))
        assertEquals(Sym.MOUSE, DevicesRules.bar(DevicesRules.staged(listOf("none")).orEmpty(), 20, words).glyph)
    }

    @Test fun whatTheHookDoesNotUnderstandStagesNothing() {
        assertNull(DevicesRules.staged(emptyList()))
        assertNull(DevicesRules.staged(listOf("mouse")))
        assertNull(DevicesRules.staged(listOf("mouse=")))
        assertNull(DevicesRules.staged(listOf("=15")))
        assertNull(DevicesRules.staged(listOf("toaster=15")))
        assertNull(DevicesRules.staged(listOf("mouse=150")))
        assertNull(DevicesRules.staged(listOf("mouse=-5")))
        assertNull(DevicesRules.staged(listOf("mouse=fifteen")))
        assertNull(DevicesRules.staged(listOf("mouse=15", "none")))
    }

    @Test fun theCodeThatReadsDeviceNamesWritesNoLogLines() {
        // A device's name can be a person's ("Alex's mouse"): it is shown, and written nowhere.
        for (file in listOf("DevicesItem.kt", "DevicesRules.kt")) {
            val code = java.io.File("src/main/java/com/meridian11/books/bentobar/items", file).readText()
            for (way in listOf("Log.", "println(", "printStackTrace", "System.out", "System.err")) assertFalse("$file has $way", way in code)
        }
    }

    // ---- the words

    @Test fun theStringsFileHoldsNothingNobodyAsksFor() {
        assertEquals("a string in strings_devices.xml that no code shows", emptyList<String>(), StringFiles.unused("src/main/res/values/strings_devices.xml"))
    }

    @Test fun theCountedSentencesReadAlikeInEnglishInBothForms() {
        // Sentences with a number in them are plurals, as everywhere in the app; in English both forms read alike.
        val own = HeatDevicesStrings("strings_devices.xml")
        for (name in listOf("devices_desc", "devices_desc_low", "devices_desc_charging")) assertEquals(name, own.forms(name).first, own.forms(name).second)
    }

    @Test fun aLevelOfOnePercentReadsAsTheOthersDo() {
        assertEquals("MX Master 3S battery 1 percent, low", DevicesRules.desc(mouse(1), words))
        assertEquals("MX Master 3S battery 1 percent, charging", DevicesRules.desc(mouse(1, charging = true), words))
        assertEquals("MX Master 3S battery 0 percent, low", DevicesRules.desc(mouse(0), words))
        assertEquals("MX Master 3S battery 100 percent", DevicesRules.desc(mouse(100), words))
    }

    @Test fun theMenusWords() {
        assertEquals("1 device", text.plural("devices_count", 1))
        assertEquals("2 devices", text.plural("devices_count", 2))
        assertEquals("85% · Charging", text.format("devices_level_charging", "85%"))
        assertEquals("while a device's battery is below 20%", text.format("trigger_devices", "20%"))
        assertEquals("shows below 20%", text.format("trigger_devices_short", "20%"))
    }
}
