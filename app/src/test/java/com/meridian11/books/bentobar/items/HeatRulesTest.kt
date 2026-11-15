package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.util.Units
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Heat and the CPU item's rule: what the bar shows for each of Android's statuses, when the item comes out and how long it stays. */
class HeatRulesTest {
    private val text = HeatDevicesStrings("strings_heat.xml", "strings_cpu.xml", "strings.xml")
    private val barWords = listOf("heat_bar_warm", "heat_bar_hot", "heat_bar_very_hot", "heat_bar_critical", "heat_bar_too_hot")
    private val thermalWords = listOf("battery_thermal_normal", "battery_thermal_warm", "battery_thermal_moderate", "battery_thermal_severe",
        "battery_thermal_critical", "battery_thermal_shutdown")
    private val words = HeatRules.Words(
        bar = { step -> text[barWords[step - 1]] },
        thermal = { step -> text[thermalWords[step]] },
        tempWord = { temp, word -> text.format("heat_bar_temp_word", temp, word) },
        desc = { thermal -> text.format("heat_desc", thermal) },
        descTemp = { thermal, degrees -> text.plural("heat_desc_temp", degrees, thermal, degrees) },
    )

    private val none = 0
    private val light = 1
    private val moderate = 2
    private val severe = 3
    private val critical = 4
    private val emergency = 5
    private val shutdown = 6

    /** The bar with Show: State, at the default rule (hot) unless told. */
    private fun state(status: Int, rule: Int = 2, since: Long? = null) =
        HeatRules.bar(status, rule, since, showTemp = false, tempC = 34.2, fahrenheit = false, words = words)

    /** The bar with Show: Battery temperature. */
    private fun temp(status: Int, celsius: Double, fahrenheit: Boolean = false) =
        HeatRules.bar(status, 2, null, showTemp = true, tempC = celsius, fahrenheit = fahrenheit, words = words)

    // ---- the bar, row by row of the table (staged statuses 0 to 6)

    @Test fun normalIsTheGlyphAlone() {
        assertEquals(HeatRules.Bar(text = null, tone = Tone.NORMAL, active = false, desc = "Heat: Normal", tooltip = "Normal"), state(none))
    }

    @Test fun lightIsWarmAndNothingToComeOutFor() {
        assertEquals(HeatRules.Bar(text = "Warm", tone = Tone.NORMAL, active = false, desc = "Heat: Warm", tooltip = "Warm"), state(light))
    }

    @Test fun moderateIsHotAWarningAndTheItemComesOut() {
        assertEquals(HeatRules.Bar(text = "Hot", tone = Tone.WARN, active = true, desc = "Heat: Hot, slowing a little", tooltip = "Hot, slowing a little"),
            state(moderate))
    }

    @Test fun severeIsVeryHot() {
        assertEquals(HeatRules.Bar(text = "Very hot", tone = Tone.WARN, active = true, desc = "Heat: Hot, slowing down", tooltip = "Hot, slowing down"),
            state(severe))
    }

    @Test fun criticalIsAnAlert() {
        assertEquals(HeatRules.Bar(text = "Critical", tone = Tone.ALERT, active = true, desc = "Heat: Critical", tooltip = "Critical"), state(critical))
    }

    @Test fun emergencyAndShutdownAreTooHot() {
        val expected = HeatRules.Bar(text = "Too hot", tone = Tone.ALERT, active = true, desc = "Heat: Shutting down soon", tooltip = "Shutting down soon")
        assertEquals(expected, state(emergency))
        assertEquals(expected, state(shutdown))
    }

    @Test fun aStatusAndroidDoesNotDefineCountsAsTheNearestItDoes() {
        assertEquals(state(shutdown), state(9))
        assertEquals(state(none), state(-1))
    }

    @Test fun theWordsFitTheBar() {
        // The longest text the item has, against the 20 characters every new item keeps to.
        for (status in none..shutdown) for (f in listOf(false, true)) assertTrue((temp(status, 49.0, f).text?.length ?: 0) <= 20)
        assertEquals("44° · Very hot", temp(severe, 44.0).text)
        assertEquals(14, temp(severe, 44.0).text?.length)
    }

    // ---- Show: Battery temperature

    @Test fun withTheTemperatureTheNumberStandsAloneWhileNothingIsWrong() {
        assertEquals("34°", temp(none, 34.0).text)
        assertEquals("36°", temp(light, 36.0).text)
        assertEquals(Tone.NORMAL, temp(light, 36.0).tone)
    }

    @Test fun fromModerateOnTheWordJoinsTheNumber() {
        // A warning tone never stands on a bare number.
        assertEquals("41° · Hot", temp(moderate, 41.0).text)
        assertEquals("44° · Very hot", temp(severe, 44.0).text)
        assertEquals("47° · Critical", temp(critical, 47.0).text)
        assertEquals("49° · Too hot", temp(emergency, 49.0).text)
        assertEquals("49° · Too hot", temp(shutdown, 49.0).text)
        assertEquals(Tone.WARN, temp(moderate, 41.0).tone)
        assertEquals(Tone.ALERT, temp(critical, 47.0).tone)
        assertTrue(temp(moderate, 41.0).active)
    }

    @Test fun theTemperatureIsAWholeNumberInTheChosenUnit() {
        assertEquals("34°", temp(none, 34.0).text)
        assertEquals("93°", temp(none, 34.0, fahrenheit = true).text)
        assertEquals("34°", temp(none, 34.2).text)
        assertEquals("35°", temp(none, 34.6).text)
        assertEquals("106° · Hot", temp(moderate, 41.3, fahrenheit = true).text)
        assertEquals("−4°", temp(none, -4.4).text) // a real minus, in the cold
    }

    @Test fun aBatteryThatReportsZeroMeansShowState() {
        // Zero is what a battery that says nothing reads: no "0°", and nothing said about a temperature.
        assertEquals(state(none), temp(none, 0.0))
        assertEquals(state(moderate), temp(moderate, 0.0))
        assertEquals("Hot", temp(moderate, 0.0).text)
        assertNull(temp(none, 0.0, fahrenheit = true).text)
        assertEquals("Heat: Normal", temp(none, 0.0).desc)
    }

    @Test fun aTemperatureThatIsNoNumberIsLeftOutToo() {
        assertEquals(state(moderate), temp(moderate, Double.NaN))
        assertEquals(state(none), temp(none, Double.POSITIVE_INFINITY, fahrenheit = true))
    }

    @Test fun theOptionsOfAnItem() {
        val plain = ItemConfig("h", "heat")
        assertFalse(HeatRules.showsTemp(plain))
        assertEquals("system", HeatRules.unit(plain))
        assertTrue(HeatRules.showsTemp(plain.with("show", "temp")))
        assertFalse(HeatRules.showsTemp(plain.with("show", "state")))
        assertFalse(HeatRules.showsTemp(plain.with("show", "something else")))
        assertEquals("c", HeatRules.unit(plain.with("unit", "c")))
        assertEquals("f", HeatRules.unit(plain.with("unit", "f")))
        assertEquals("system", HeatRules.unit(plain.with("unit", "kelvin")))
    }

    @Test fun theItemsOwnUnitWinsOverTheSystems() {
        // Whatever the region says: °F shows 93°, °C shows 34°.
        val item = ItemConfig("h", "heat", options = mapOf("show" to "temp"))
        fun shown(i: ItemConfig) = HeatRules.bar(none, 2, null, HeatRules.showsTemp(i), 34.0, Units.fahrenheit(HeatRules.unit(i)), words).text
        assertEquals("93°", shown(item.with("unit", "f")))
        assertEquals("34°", shown(item.with("unit", "c")))
    }

    // ---- what a screen reader hears, and the tooltip

    @Test fun theSpokenLines() {
        assertEquals("Heat: Normal", state(none).desc)
        assertEquals("Heat: Hot, slowing a little", state(moderate).desc)
        assertEquals("Heat: Normal, battery 34 degrees", temp(none, 34.0).desc)
        assertEquals("Heat: Normal, battery 93 degrees", temp(none, 34.0, fahrenheit = true).desc)
        assertEquals("Heat: Hot, slowing a little, battery 41 degrees", temp(moderate, 41.3).desc)
    }

    @Test fun oneDegreeIsNotOneDegrees() {
        assertEquals("Heat: Normal, battery 1 degree", temp(none, 1.0).desc)
        assertEquals("Heat: Normal, battery 2 degrees", temp(none, 2.0).desc)
        assertEquals("Heat: Normal, battery 34 degrees", temp(none, 1.0, fahrenheit = true).desc) // 33.8 °F
    }

    @Test fun theTooltipIsTheMenusSubtitle() {
        assertEquals("Normal", state(none).tooltip)
        assertEquals("Hot, slowing a little", state(moderate).tooltip)
        assertEquals("Hot, slowing a little", temp(moderate, 41.0).tooltip)
    }

    @Test fun theTonesOfTheSixStatuses() {
        assertEquals(listOf(Tone.NORMAL, Tone.NORMAL, Tone.WARN, Tone.WARN, Tone.ALERT, Tone.ALERT, Tone.ALERT), (none..shutdown).map { HeatRules.tone(it) })
    }

    // ---- the rule: warm, hot, very hot

    @Test fun theDefaultRuleIsHiddenAtWarmAndOutAtHot() {
        assertFalse(HeatRules.active(none, 2, null))
        assertFalse(HeatRules.active(light, 2, null))
        assertTrue(HeatRules.active(moderate, 2, null))
        assertTrue(HeatRules.active(shutdown, 2, null))
    }

    @Test fun theRulesThreeStops() {
        fun outAt(rule: Int) = (none..shutdown).filter { HeatRules.active(it, rule, null) }
        assertEquals((light..shutdown).toList(), outAt(1)) // warm
        assertEquals((moderate..shutdown).toList(), outAt(2)) // hot
        assertEquals((severe..shutdown).toList(), outAt(3)) // very hot
    }

    @Test fun aRuleOutsideItsThreeStopsCountsAsTheNearest() {
        assertFalse(HeatRules.active(none, 0, null)) // never "always out"
        assertTrue(HeatRules.active(light, 0, null))
        assertTrue(HeatRules.active(severe, 9, null))
        assertFalse(HeatRules.active(moderate, 9, null))
    }

    // ---- the minute after cooling

    @Test fun theItemStaysAMinuteAfterCoolingBelowItsLevel() {
        assertTrue(HeatRules.active(none, 2, 0))
        assertTrue(HeatRules.active(none, 2, 59_999))
        assertFalse(HeatRules.active(none, 2, 60_000))
        assertFalse(HeatRules.active(light, 2, 3_600_000))
    }

    @Test fun whileItStaysItShowsTheStatusItHasByThen() {
        val cooled = state(none, since = 30_000)
        assertTrue(cooled.active)
        assertNull(cooled.text)
        assertEquals(Tone.NORMAL, cooled.tone)
        assertEquals("Heat: Normal", cooled.desc)
        assertEquals("Warm", state(light, since = 30_000).text)
        assertFalse(state(none, since = 61_000).active)
    }

    @Test fun hotThenNormalIsGoneSixtySecondsLater() {
        val hold = HeatHold()
        val t = 5_000_000L
        hold.note(light, t - 10_000)
        assertNull(hold.since(2, t - 10_000)) // warm is not what the default rule waits for
        hold.note(moderate, t - 1_000); hold.note(moderate, t)
        hold.note(none, t + 1_000)
        fun out(now: Long) = HeatRules.active(none, 2, hold.since(2, now))
        assertTrue(out(t + 1_000))
        assertTrue(out(t + 59_000))
        assertFalse(out(t + 60_000))
        assertFalse(out(t + 600_000))
    }

    @Test fun aStatusThatFlickersKeepsTheItemOut() {
        val hold = HeatHold()
        var now = 1_000_000L
        // Hot one second, warm for half a minute, again and again: each hot second starts the minute anew.
        repeat(5) {
            hold.note(moderate, now)
            repeat(30) { now += 1_000; hold.note(light, now); assertTrue(HeatRules.active(light, 2, hold.since(2, now))) }
            now += 1_000
        }
    }

    @Test fun eachOfTheRulesLevelsHasItsOwnMinute() {
        val hold = HeatHold()
        hold.note(severe, 100_000) // very hot, so hot and warm too
        hold.note(moderate, 110_000)
        hold.note(light, 120_000)
        hold.note(none, 130_000)
        assertEquals(30_000L, hold.since(3, 130_000))
        assertEquals(20_000L, hold.since(2, 130_000))
        assertEquals(10_000L, hold.since(1, 130_000))
        assertFalse(HeatRules.active(none, 3, hold.since(3, 161_000)))
        assertTrue(HeatRules.active(none, 1, hold.since(1, 161_000)))
    }

    @Test fun nothingIsHeldBeforeTheLevelWasSeenOrAfterAClear() {
        val hold = HeatHold()
        assertNull(hold.since(2, 1_000))
        hold.note(moderate, 1_000)
        assertEquals(0L, hold.since(2, 1_000))
        assertNull(hold.since(2, 500)) // a clock that went back holds nothing
        hold.clear()
        assertNull(hold.since(2, 2_000))
    }

    // ---- the heat level

    @Test fun theLevelIsAPercentageThatCanPass100() {
        assertEquals(84, HeatRules.percent(0.84f)) // 83.99999 in a float: rounded, not cut
        assertEquals("84%", HeatRules.percentText(0.84f))
        assertEquals("65%", HeatRules.percentText(0.65f))
        assertEquals("112%", HeatRules.percentText(1.12f))
        assertEquals("0%", HeatRules.percentText(0f))
        assertEquals("100%", HeatRules.percentText(1f))
    }

    @Test fun from100TheLevelIsDrawnAsOver() {
        assertFalse(HeatRules.over(0.99f))
        assertTrue(HeatRules.over(1f))
        assertTrue(HeatRules.over(1.12f))
        // By the number that is shown: what reads 100% is drawn as 100%.
        assertTrue(HeatRules.over(0.996f))
        assertFalse(HeatRules.over(0.994f))
    }

    @Test fun aReadingThatIsNoNumberIsNoLevel() {
        assertNull(HeatRules.reading(Float.NaN)) // the device has none, or was asked too soon
        assertNull(HeatRules.reading(Float.POSITIVE_INFINITY))
        assertEquals(0.84f, HeatRules.reading(0.84f))
        assertEquals(0f, HeatRules.reading(-0.2f))
        assertEquals(1.3f, HeatRules.reading(1.3f))
    }

    @Test fun theLevelIsAskedAtMostEveryFiveSeconds() {
        val level = HeatLevel()
        assertTrue(level.due(123_456)) // never asked: now
        level.took(0.5f, 100_000)
        assertFalse(level.due(100_000))
        assertFalse(level.due(101_000))
        assertFalse(level.due(104_000))
        assertFalse(level.due(104_499))
        assertTrue(level.due(105_000))
        var asked = 0
        for (now in 105_000L..165_000L step 1_000) if (level.due(now)) { level.took(0.5f, now); asked++ }
        assertEquals(13, asked) // one a second would have been 61
    }

    @Test fun aTickThatComesAMomentEarlyIsStillTheFifth() {
        // The ticker's seconds are a few milliseconds long or short. The fifth one counts, not the sixth:
        // a chart of sixty points is then five minutes, not six.
        val level = HeatLevel()
        level.took(0.5f, 100_000)
        assertTrue(level.due(104_998))
        assertTrue(level.due(104_500))
        val jitter = longArrayOf(-3, 4, -2, 0, 5)
        val askedAt = ArrayList<Long>()
        for (tick in 1..60) {
            val now = 100_000L + tick * 1_000 + jitter[tick % jitter.size]
            if (level.due(now)) { level.took(0.5f, now); askedAt += now }
        }
        assertEquals(12, askedAt.size)
        // And never close to Android's own limit of half a second between two calls.
        assertTrue(askedAt.zipWithNext { a, b -> b - a }.all { it >= 4_500 })
    }

    @Test fun aDeviceThatReportsNoLevelHasNone() {
        val level = HeatLevel()
        assertNull(level.level)
        for (now in 0L..60_000L step 5_000) level.took(Float.NaN, now)
        assertNull(level.level)
        assertEquals(emptyList<Double>(), level.chart())
    }

    @Test fun theLevelIsTheLastReadingAndTheChartHasOnePointForEach() {
        val level = HeatLevel()
        level.took(0.5f, 0); level.took(0.75f, 5_000); level.took(1.25f, 10_000)
        assertEquals(1.25f, level.level)
        assertEquals(listOf(0.5, 0.75, 1.25), level.chart())
    }

    @Test fun theChartHoldsFiveMinutes() {
        val level = HeatLevel()
        for (i in 0 until 100) level.took(i / 100f, i * 5_000L)
        assertEquals(60, level.chart().size)
        assertEquals(0.99, level.chart().last(), 1e-6)
        assertEquals(0.40, level.chart().first(), 1e-6)
    }

    @Test fun oneReadingThatFailsDoesNotTakeTheLevelAway() {
        val level = HeatLevel()
        level.took(0.5f, 0)
        level.took(Float.NaN, 5_000)
        assertEquals(0.5f, level.level)
        level.took(Float.NaN, 10_000)
        assertEquals(0.5f, level.level)
        level.took(Float.NaN, 15_000) // three in a row: the device has stopped saying
        assertNull(level.level)
        assertEquals(emptyList<Double>(), level.chart())
        level.took(0.25f, 20_000)
        assertEquals(0.25f, level.level)
        assertEquals(listOf(0.25), level.chart())
    }

    @Test fun aFailedReadingBetweenGoodOnesIsForgotten() {
        val level = HeatLevel()
        level.took(0.5f, 0); level.took(Float.NaN, 5_000); level.took(Float.NaN, 10_000); level.took(0.75f, 15_000)
        level.took(Float.NaN, 20_000); level.took(Float.NaN, 25_000)
        assertEquals(0.75f, level.level)
        assertEquals(listOf(0.5, 0.75), level.chart())
    }

    @Test fun whileNoItemIsLiveTheChartStartsOverAndTheNextAskingStillWaitsItsTurn() {
        val level = HeatLevel()
        level.took(0.5f, 0); level.took(0.75f, 5_000)
        level.idle()
        assertEquals(emptyList<Double>(), level.chart()) // points five seconds apart, or none: no line across the gap
        assertEquals(0.75f, level.level)
        assertFalse(level.due(6_000)) // Android refuses a second call in quick succession, whatever happened in between
        assertTrue(level.due(10_000))
    }

    @Test fun aStagedDeviceWithoutALevelLosesItAtOnce() {
        val level = HeatLevel()
        level.took(0.5f, 0); level.took(0.75f, 5_000)
        level.forget()
        assertNull(level.level)
        assertEquals(emptyList<Double>(), level.chart())
        assertFalse(level.due(6_000)) // the next asking still waits its turn
        level.took(Float.NaN, 10_000)
        assertNull(level.level)
        level.took(0.25f, 15_000)
        assertEquals(0.25f, level.level)
    }

    @Test fun theBatterysTemperatureInTheMenuHasOneDecimal() {
        assertEquals("41.3", HeatRules.oneDecimal(41.3, fahrenheit = false))
        assertEquals("106.3", HeatRules.oneDecimal(41.3, fahrenheit = true))
        assertEquals("34.0", HeatRules.oneDecimal(34.0, fahrenheit = false))
        assertEquals("41.3\u00A0°C", text.format("heat_temp_c", HeatRules.oneDecimal(41.3, fahrenheit = false)))
        assertEquals("106.3\u00A0°F", text.format("heat_temp_f", HeatRules.oneDecimal(41.3, fahrenheit = true)))
    }

    // ---- the CPU item's rule

    /** What 0.8 did: out at or above the rule's number, the warning tone from 90%, and no word about heat. */
    private fun asIn08(load: Double, limitPct: Int) = CpuRule.Shown(active = load >= limitPct / 100.0, hot = false, high = load >= 0.9)

    @Test fun aCpuItemSavedBy08BehavesAsIn08() {
        // Layouts saved by 0.8 have no "hot" option: with or without a number of their own.
        for (saved in listOf(ItemConfig("c", "cpu"), ItemConfig("c", "cpu", options = mapOf("activePct" to "80")), ItemConfig("c", "cpu", options = mapOf("activePct" to "35")))) {
            assertFalse(CpuRule.alsoHot(saved))
            val limit = saved.optInt("activePct", 80)
            for (status in none..shutdown) for (load in listOf(0.0, 0.1, 0.34, 0.35, 0.79, 0.8, 0.85, 0.899, 0.9, 0.95, 1.0)) {
                val shown = CpuRule.shown(load, limit, CpuRule.alsoHot(saved), status)
                assertEquals("load $load at status $status", asIn08(load, limit), shown)
                assertEquals(load >= 0.9, shown.warn)
            }
        }
    }

    @Test fun aDeviceWithoutCpuLoadSavedBy08NeverComesOut() {
        for (status in none..shutdown) assertEquals(CpuRule.Shown(active = false, hot = false, high = false), CpuRule.shown(null, 80, alsoHot = false, status = status))
    }

    @Test fun withTheSwitchOnTheItemComesOutWhenTheDeviceRunsHot() {
        // Staged Hot, the CPU at 10%: out, with the thermometer and the warning tone.
        val shown = CpuRule.shown(0.10, 80, alsoHot = true, status = moderate)
        assertTrue(shown.active)
        assertTrue(shown.hot)
        assertTrue(shown.warn)
        assertFalse(shown.high)
        for (status in moderate..shutdown) assertTrue(CpuRule.shown(0.10, 80, alsoHot = true, status = status).hot)
    }

    @Test fun withTheSwitchOnWarmIsNotHot() {
        for (status in listOf(none, light)) {
            assertEquals(asIn08(0.10, 80), CpuRule.shown(0.10, 80, alsoHot = true, status = status))
            assertEquals(asIn08(0.95, 80), CpuRule.shown(0.95, 80, alsoHot = true, status = status))
        }
    }

    @Test fun withTheSwitchOffAHotDeviceChangesNothing() {
        assertEquals(asIn08(0.10, 80), CpuRule.shown(0.10, 80, alsoHot = false, status = moderate))
        assertFalse(CpuRule.shown(0.10, 80, alsoHot = false, status = shutdown).active)
    }

    @Test fun aBusyCpuOnAHotDeviceIsBoth() {
        val shown = CpuRule.shown(0.95, 80, alsoHot = true, status = severe)
        assertEquals(CpuRule.Shown(active = true, hot = true, high = true), shown)
        assertTrue(shown.warn)
    }

    @Test fun withTheSwitchOnADeviceWithoutCpuLoadStillComesOutWhenHot() {
        assertEquals(CpuRule.Shown(active = true, hot = true, high = false), CpuRule.shown(null, 80, alsoHot = true, status = moderate))
        assertFalse(CpuRule.shown(null, 80, alsoHot = true, status = light).active)
    }

    @Test fun theSwitchIsOffUnlessItWasTurnedOn() {
        val item = ItemConfig("c", "cpu")
        assertTrue(CpuRule.alsoHot(item.with("hot", "true")))
        assertFalse(CpuRule.alsoHot(item.with("hot", "false")))
        assertFalse(CpuRule.alsoHot(item.with("hot", "yes")))
        assertFalse(CpuRule.alsoHot(item.with("hot", "")))
        assertFalse(CpuRule.alsoHot(item.with("hot", "true").with("hot", null))) // turned off again: the layout is as 0.8 saved it
        assertEquals(ItemConfig("c", "cpu"), item.with("hot", "true").with("hot", null))
    }

    @Test fun theCpuItemsWords() {
        assertEquals("CPU 12% busy, device hot", text.format("cpu_state_desc_hot", "12%"))
        assertEquals("while CPU load is above 80%, or the device runs hot", text.format("trigger_cpu_hot", "80%"))
        assertEquals("shows above 80% CPU, or when hot", text.format("trigger_cpu_hot_short", "80%"))
    }

    // ---- the words

    @Test fun theStringsFilesHoldNothingNobodyAsksFor() {
        for (file in listOf("strings_heat.xml", "strings_cpu.xml")) {
            assertEquals("a string in $file that no code shows", emptyList<String>(), StringFiles.unused("src/main/res/values/$file"))
        }
    }

    @Test fun aBatteryAtOneDegreeIsNotAtOneDegrees() {
        val (one, other) = HeatDevicesStrings("strings_heat.xml").forms("heat_desc_temp")
        assertTrue(one, one.endsWith("degree"))
        assertTrue(other, other.endsWith("degrees"))
    }

    @Test fun theRulesSentences() {
        assertEquals("while the device is at least hot, and for a minute after it cools", text.format("trigger_heat", text["heat_rule_hot"]))
        assertEquals("shows when hot", text.format("trigger_heat_short", text["heat_rule_hot"]))
        assertEquals("shows when very hot", text.format("trigger_heat_short", text["heat_rule_very_hot"]))
    }
}
