package com.meridian11.books.bentobar.data

import com.meridian11.books.bentobar.items.Items
import com.meridian11.books.bentobar.items.TickRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HiddenModeTest {
    private val timer = ItemConfig("t", "timer", Section.HIDDEN, whenActive = true)
    private val memory = ItemConfig("m", "memory", Section.HIDDEN)
    private val clock = ItemConfig("c", "clock", Section.SHOWN)
    private fun v2(chevron: Boolean, hover: Boolean = false) =
        BarConfig(version = 2, chevron = chevron, revealOnHover = hover, items = listOf(timer, memory, clock))

    @Test fun oldSwitchesMapToModes() {
        assertEquals(HiddenMode.CLICK, v2(chevron = true).migrateToV3().hiddenMode)
        assertEquals(HiddenMode.HOVER, v2(chevron = true, hover = true).migrateToV3().hiddenMode)
        assertEquals(HiddenMode.SHOW_ALL, v2(chevron = false).migrateToV3().hiddenMode)
    }

    @Test fun chevronOffKeepsTheBarAsItWas() {
        // With ‹ off, a hidden item without a rule never showed: it moves to Off (and so is not read: `readLayout`), rule items stay.
        val c = v2(chevron = false).migrateToV3()
        assertEquals(Section.OFF, c.items.first { it.id == "m" }.section)
        assertEquals(Section.HIDDEN, c.items.first { it.id == "t" }.section)
    }

    @Test fun showAllShowsRuleItemsOnlyWhenActive() {
        val c = BarConfig(hiddenMode = HiddenMode.SHOW_ALL, items = listOf(timer, memory, clock))
        assertFalse(c.shows(timer, active = false))
        assertTrue(c.shows(timer, active = true))
        assertTrue(c.shows(memory, active = false))
        assertTrue(c.shows(clock, active = false))
    }

    @Test fun clickModeKeepsHiddenItemsBehindTheChevron() {
        val c = BarConfig(hiddenMode = HiddenMode.CLICK, items = listOf(timer, memory, clock))
        assertFalse(c.shows(memory, active = false))
        assertTrue(c.shows(timer, active = true))
    }

    @Test fun everythingDrawnIsSampled() {
        // The ticker samples what couldShow() allows; an item drawn but not sampled freezes. Presenting is part of the same rule.
        for (presenting in listOf(false, true)) for (type in listOf("clock", "timer", "no such type"))
            for (mode in HiddenMode.entries) for (section in Section.entries) for (rule in listOf(false, true)) {
                val item = ItemConfig("x", type, section, whenActive = rule)
                val c = BarConfig(hiddenMode = mode, items = listOf(item), presenting = presenting)
                for (active in listOf(false, true)) {
                    if (c.shows(item, active)) assertTrue("$mode $section rule=$rule active=$active $type presenting=$presenting", c.couldShow(item))
                    if (c.shows(item, active)) assertTrue("$type $section", TickRules.needed(c, settings = false, revealHidden = false, focusItem = null).contains(item))
                }
            }
        // The case that froze: a hidden item without a rule under Show everything.
        assertTrue(BarConfig(hiddenMode = HiddenMode.SHOW_ALL).couldShow(memory))
        assertFalse(BarConfig(hiddenMode = HiddenMode.CLICK).couldShow(memory))
    }

    private val presentingBar = BarConfig(hiddenMode = HiddenMode.CLICK, presenting = true)
    private val shownClock = clock
    private val hiddenTimerNoRule = ItemConfig("h", "timer", Section.HIDDEN)

    @Test fun presentingDrawsOnlyPresentingTypesThatHaveSomethingToSay() {
        assertFalse(presentingBar.shows(shownClock, active = true))
        assertFalse(presentingBar.shows(shownClock, active = false))
        assertTrue(presentingBar.shows(hiddenTimerNoRule, active = true))
        assertFalse(presentingBar.shows(hiddenTimerNoRule, active = false))
    }

    @Test fun aRunningTimerThatIsHiddenAndNeverRevealedIsStillSampledWhilePresenting() {
        // Before the rule moved here, the strip drew it from a string set while the ticker never sampled it: frozen, or not there at all.
        val c = presentingBar.copy(items = listOf(hiddenTimerNoRule))
        assertEquals(listOf(hiddenTimerNoRule), TickRules.needed(c, settings = false, revealHidden = false, focusItem = null))
        assertEquals(emptyList<ItemConfig>(), TickRules.needed(c.copy(presenting = false), settings = false, revealHidden = false, focusItem = null))
    }

    @Test fun anItemOfATypeThisVersionDoesntKnowIsNotDrawnNotSampledNotListedBehindTheChevronAndStaysInTheLayout() {
        val future = ItemConfig("f", "from-the-future", Section.HIDDEN)
        val shown = future.copy(id = "g", section = Section.SHOWN)
        val c = BarConfig(hiddenMode = HiddenMode.CLICK, items = listOf(future, shown, clock))
        for (active in listOf(false, true)) {
            assertFalse(c.shows(shown, active))
            assertFalse(c.shows(future, active))
        }
        assertFalse(c.couldShow(shown))
        assertEquals(emptyList<ItemConfig>(), c.behindChevron { false })
        assertEquals(emptyList<ItemConfig>(), c.notDrawn { false }.filter { Items.of(it.type) == null })
        assertEquals(listOf(future, shown, clock), Store.parseLayout(layoutJson.encodeToString(BarConfig.serializer(), c))!!.items)
    }

    @Test fun theChevronMenuNeverListsAnItemThatIsDrawn() {
        val on = { _: ItemConfig -> false }
        // Show everything: the hidden item without a rule is in the bar, so only the waiting rule item is listed.
        val all = BarConfig(hiddenMode = HiddenMode.SHOW_ALL, items = listOf(timer, memory, clock))
        assertEquals(listOf(timer), all.notDrawn(on))
        assertEquals(emptyList<ItemConfig>(), all.behindChevron(on))
        // Click mode: both wait behind ‹, until the timer runs and comes out on its own.
        val click = all.copy(hiddenMode = HiddenMode.CLICK)
        assertEquals(listOf(timer, memory), click.behindChevron(on))
        assertEquals(listOf(memory), click.behindChevron { it.id == "t" })
        assertEquals(click.behindChevron(on), click.notDrawn(on))
    }

    @Test fun alreadyV3IsUntouched() {
        val c = BarConfig(hiddenMode = HiddenMode.HOVER)
        assertEquals(c, c.migrateToV3())
    }

    // ---- The two parts of the bar's list in settings ----

    private val event = ItemConfig("e", "event", Section.HIDDEN, whenActive = true)
    private val calendar = ItemConfig("k", "calendar", Section.SHOWN)
    private fun Parts.ids() = upper.map { it.id } to lower.map { it.id }

    @Test fun inTheDefaultModeTheLowerPartListsExactlyTheHiddenItemsWithARule() {
        val c = BarConfig(hiddenMode = HiddenMode.SHOW_ALL, items = listOf(timer, memory, clock))
        assertEquals(listOf("m", "c") to listOf("t"), c.parts(Position.RIGHT).ids())
        // A Hidden item without a rule is drawn all the time there: it is listed above, with what is always in the bar.
        assertEquals(Part.UPPER, c.partOf(memory))
        assertTrue(c.shows(memory, active = false))
        // What is listed above is drawn whatever it has to say; what is listed below only while its rule applies.
        for (item in c.items) assertEquals(item.id, c.partOf(item) == Part.UPPER, c.shows(item, active = false))
    }

    @Test fun withTheChevronTheLowerPartListsEveryHiddenItem() {
        for (mode in listOf(HiddenMode.CLICK, HiddenMode.HOVER)) {
            val c = BarConfig(hiddenMode = mode, items = listOf(timer, memory, clock))
            assertEquals(mode.name, listOf("c") to listOf("t", "m"), c.parts(Position.RIGHT).ids())
            // The rule's bit alone puts nothing under the line: an item that is Shown stays above with it.
            assertEquals(Part.UPPER, c.partOf(clock.copy(whenActive = true)))
        }
    }

    @Test fun aPartListsItsAreaInTheListsOrderWithNextMeetingFirst() {
        val left = clock.copy(id = "l", area = Position.LEFT)
        val c = BarConfig(items = listOf(timer, clock, left, event, calendar, ItemConfig("e2", "event", Section.SHOWN)))
        assertEquals(listOf("e2", "c", "k") to listOf("e", "t"), c.parts(Position.RIGHT).ids())
        assertEquals(listOf("l") to emptyList<String>(), c.parts(Position.LEFT).ids())
        assertEquals(emptyList<String>() to emptyList<String>(), c.parts(Position.CENTER).ids())
        // The upper part is what the strip draws while nothing has anything to say, in its order.
        assertEquals(c.stripPlan(Position.RIGHT, { false }, expanded = false, overflow = false).visible.map { it.id }, c.parts(Position.RIGHT).upper.map { it.id })
    }

    @Test fun theListHasRowsForTheAreasWithAnItemInTheBarLeftFirst() {
        val left = clock.copy(id = "l", area = Position.LEFT)
        assertEquals(listOf(Position.LEFT, Position.RIGHT), BarConfig(items = listOf(clock, left)).listAreas())
        assertEquals(listOf(Position.RIGHT), BarConfig(items = listOf(clock)).listAreas())
        // An item of a type this version doesn't know is in the layout, so it has a row and its area a place.
        assertEquals(listOf(Position.CENTER, Position.RIGHT), BarConfig(items = listOf(clock, ItemConfig("u", "from-a-newer-version", area = Position.CENTER))).listAreas())
        assertEquals(listOf(Position.CENTER), BarConfig(position = Position.CENTER).listAreas())
    }

    @Test fun onlyATypeWithARuleCanWaitUnderTheLineInTheDefaultModeAndAnythingBehindTheChevron() {
        val show = BarConfig(hiddenMode = HiddenMode.SHOW_ALL)
        assertTrue(show.canGoBelow(clock))
        assertFalse(show.canGoBelow(calendar))
        for (mode in listOf(HiddenMode.CLICK, HiddenMode.HOVER)) assertTrue(BarConfig(hiddenMode = mode).canGoBelow(calendar))
        for (mode in HiddenMode.entries) assertFalse(BarConfig(hiddenMode = mode).canGoBelow(ItemConfig("u", "from-a-newer-version")))
    }
}
