package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.data.Section
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplaysRulesTest {
    private val builtIn = DisplaysRules.Screen(id = 0, name = "Built-in", width = 2560, height = 1600, hz = 120, hdr = false, builtIn = true)
    private val uhd = DisplaysRules.Screen(id = 2, name = "Dell U2723QE", width = 3840, height = 2160, hz = 60, hdr = true, builtIn = false)
    private val qhd = DisplaysRules.Screen(id = 3, name = "LG UltraGear", width = 2560, height = 1440, hz = 144, hdr = false, builtIn = false)

    @Test fun resolutionShorthandRecognizesStandardModes() {
        assertEquals("4K", DisplaysRules.shortRes(3840, 2160))
        assertEquals("4K", DisplaysRules.shortRes(2160, 3840))
        assertEquals("5K", DisplaysRules.shortRes(5120, 2880))
        assertEquals("UWQHD", DisplaysRules.shortRes(3440, 1440))
        assertEquals("1440p", DisplaysRules.shortRes(2560, 1440))
        assertEquals("1080p", DisplaysRules.shortRes(1920, 1080))
        assertEquals("720p", DisplaysRules.shortRes(1280, 720))
        assertEquals("1600×900", DisplaysRules.shortRes(1600, 900))
    }

    @Test fun barReflectsConnectedExternalDisplaysAndMode() {
        val none = DisplaysRules.bar(listOf(builtIn), "full", "No external display") { n, s -> "$n: $s" }
        assertNull(none.text)
        assertFalse(none.active)

        val oneFull = DisplaysRules.bar(listOf(builtIn, uhd), "full", "No external display") { n, s -> "$n: $s" }
        assertEquals("4K 60Hz", oneFull.text)
        assertTrue(oneFull.active)

        val twoFull = DisplaysRules.bar(listOf(builtIn, uhd, qhd), "full", "No external display") { n, s -> "$n: $s" }
        assertEquals("2 · 4K 60Hz", twoFull.text)
        assertEquals("2: 4K 60Hz, 1440p 144Hz", twoFull.desc)

        val twoCount = DisplaysRules.bar(listOf(builtIn, uhd, qhd), "count", "No external display") { n, s -> "$n: $s" }
        assertEquals("2", twoCount.text)
    }

    @Test fun theKeepAwakeRuleComesFromTheLayoutWhateverIsSampled() {
        val awakeItem = ItemConfig("d1", "displays", options = mapOf("awake" to "true"))
        assertTrue(DisplaysRules.ruleOn(listOf(awakeItem)))
        // Folded behind ‹ without Show when: never sampled, and the promise holds all the same.
        assertTrue(DisplaysRules.ruleOn(listOf(awakeItem.copy(section = Section.HIDDEN, whenActive = false))))
        assertFalse("removed", DisplaysRules.ruleOn(emptyList()))
        assertFalse(DisplaysRules.ruleOn(listOf(ItemConfig("d2", "displays"))))
        assertFalse(DisplaysRules.ruleOn(listOf(ItemConfig("c", "clock", options = mapOf("awake" to "true")))))
        // Only the connection is left for wantAwake to add.
        assertTrue(DisplaysRules.wantAwake(listOf(awakeItem.copy(section = Section.HIDDEN)), listOf(builtIn, uhd)))
    }

    @Test fun theItemGoingIdleDoesNotEndKeepAwake() {
        // What used to happen: the bar hides, the ticker stops, onIdle cleared the rule and the screen timed out.
        val source = java.io.File("src/main/java/com/meridian11/books/bentobar/items/DisplaysItem.kt").readText()
        val onIdle = source.substringAfter("override fun onIdle()").substringBefore("override fun sample")
        assertFalse(onIdle, "keepingAwake" in onIdle.substringBefore("}"))
    }

    @Test fun wantAwakeRequiresActiveDisplaysItemWithAwakeOptionAndExternalScreen() {
        val awakeItem = ItemConfig("d1", "displays", options = mapOf("awake" to "true"))
        val plainItem = ItemConfig("d2", "displays")

        assertFalse(DisplaysRules.wantAwake(listOf(plainItem), listOf(builtIn, uhd)))
        assertFalse(DisplaysRules.wantAwake(listOf(awakeItem), listOf(builtIn)))
        assertFalse(DisplaysRules.wantAwake(emptyList(), listOf(builtIn, uhd)))
        assertTrue(DisplaysRules.wantAwake(listOf(awakeItem), listOf(builtIn, uhd)))
    }
}
