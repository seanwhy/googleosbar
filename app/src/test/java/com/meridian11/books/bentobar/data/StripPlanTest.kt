package com.meridian11.books.bentobar.data

import com.meridian11.books.bentobar.SourceScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the strip and the settings preview show of a layout: the one function both ask. */
class StripPlanTest {
    private val clock = ItemConfig("c", "clock", Section.SHOWN)
    private val meeting = ItemConfig("e", "event", Section.SHOWN)
    private val memory = ItemConfig("m", "memory", Section.HIDDEN)
    private val timerRule = ItemConfig("t", "timer", Section.HIDDEN, whenActive = true)
    private val nobody = { _: ItemConfig -> false }
    private fun plan(c: BarConfig, active: (ItemConfig) -> Boolean = nobody, expanded: Boolean = false, overflow: Boolean = false) =
        c.stripPlan(active, expanded, overflow)
    private fun ids(list: List<ItemConfig>) = list.map { it.id }

    @Test fun nextMeetingStaysAtTheFarLeft() {
        // Its text changes width most, and at the outer edge that moves nothing else.
        val c = BarConfig(items = listOf(clock, meeting))
        assertEquals(listOf("e", "c"), ids(plan(c).visible))
    }

    @Test fun clickModeKeepsHiddenItemsBehindAChevronThatTheyKeepRoomFor() {
        val p = plan(BarConfig(hiddenMode = HiddenMode.CLICK, items = listOf(clock, memory)))
        assertEquals(listOf("c"), ids(p.visible))
        assertEquals(listOf("m"), ids(p.hidden))
        assertEquals(emptyList<String>(), ids(p.revealed))
        assertTrue(p.chevron)
        assertTrue(p.chevronAlways)
        assertFalse(p.open)
    }

    @Test fun expandingRevealsWhatIsBehindTheChevronOnlyWhereThereIsOne() {
        val c = BarConfig(hiddenMode = HiddenMode.CLICK, items = listOf(clock, memory))
        val p = plan(c, expanded = true)
        assertTrue(p.open)
        assertEquals(listOf("m"), ids(p.revealed))
        val showAll = plan(c.copy(hiddenMode = HiddenMode.SHOW_ALL), expanded = true)
        assertFalse("Show everything has no ‹ to fold them back with", showAll.open)
        assertEquals(emptyList<String>(), ids(showAll.revealed))
    }

    @Test fun anItemOutOnItsOwnIsNotAlsoBehindTheChevron() {
        val c = BarConfig(hiddenMode = HiddenMode.CLICK, items = listOf(timerRule, clock))
        val p = plan(c, active = { it.id == "t" })
        assertEquals(listOf("t", "c"), ids(p.visible))
        assertEquals(emptyList<String>(), ids(p.hidden))
    }

    @Test fun overflowBringsTheChevronWithoutKeepingRoomForIt() {
        val c = BarConfig(items = listOf(clock))
        assertFalse(plan(c).chevron)
        val p = plan(c, overflow = true)
        assertTrue(p.chevron)
        assertFalse(p.chevronAlways)
    }

    @Test fun whilePresentingOnlyAPresentingTypeWithSomethingToSayShowsAndTheChevronStays() {
        val timer = ItemConfig("t", "timer", Section.HIDDEN)
        val c = BarConfig(hiddenMode = HiddenMode.CLICK, presenting = true, items = listOf(clock, timer))
        val idle = plan(c, expanded = true)
        assertEquals(emptyList<String>(), ids(idle.visible))
        assertEquals(emptyList<String>(), ids(idle.hidden))
        assertEquals(emptyList<String>(), ids(idle.revealed))
        assertFalse(idle.open)
        assertTrue(idle.chevron)
        assertTrue(idle.chevronAlways)
        assertEquals(listOf("t"), ids(plan(c, active = { it.id == "t" }).visible))
    }

    @Test fun anItemOfATypeThisVersionDoesNotKnowDrawsNothing() {
        val future = ItemConfig("f", "from-the-future", Section.SHOWN)
        val p = plan(BarConfig(hiddenMode = HiddenMode.CLICK, items = listOf(future, future.copy(id = "g", section = Section.HIDDEN))))
        assertEquals(emptyList<String>(), ids(p.visible))
        assertEquals(emptyList<String>(), ids(p.hidden))
    }

    @Test fun theBarAndThePreviewAskTheSameFunctionAndNeitherChoosesForItself() {
        val code = { name: String -> SourceScan.code(SourceScan.sources.single { it.name == name }) }
        for (name in listOf("BarService.kt", "BarPage.kt")) assertTrue(name, ".stripPlan(" in code(name))
        val preview = code("BarPage.kt")
        for (rule in listOf("sectionShows", "behindChevron", "sortedBy", "PINNED_LEFT")) assertFalse("BarPage.kt chooses by $rule", rule in preview)
        assertFalse("BarService.kt sorts the strip itself", "PINNED_LEFT" in code("BarService.kt"))
    }
}
