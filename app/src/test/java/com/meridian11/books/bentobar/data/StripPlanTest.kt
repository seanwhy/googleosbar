package com.meridian11.books.bentobar.data

import com.meridian11.books.bentobar.SourceScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What one area's strip and the settings preview show of a layout: the one function both ask. */
class StripPlanTest {
    private val clock = ItemConfig("c", "clock", Section.SHOWN)
    private val meeting = ItemConfig("e", "event", Section.SHOWN)
    private val memory = ItemConfig("m", "memory", Section.HIDDEN)
    private val timerRule = ItemConfig("t", "timer", Section.HIDDEN, whenActive = true)
    private val nobody = { _: ItemConfig -> false }
    private fun plan(c: BarConfig, area: Position, active: (ItemConfig) -> Boolean = nobody, expanded: Boolean = false, overflow: Boolean = false) =
        c.stripPlan(area, active, expanded, overflow)
    private fun ids(list: List<ItemConfig>) = list.map { it.id }
    private fun ItemConfig.at(area: Position, id: String = this.id) = copy(id = id, area = area)

    /** A layout with every item in one area, whichever it is, gets for that area what the one strip got. */
    private fun inEachArea(vararg items: ItemConfig, test: (items: List<ItemConfig>, area: Position) -> Unit) {
        for (area in Position.entries) test(items.map { it.at(area) }, area)
    }

    // ---- a layout in one area: the plan as it always was -------------------------------------------------

    @Test fun nextMeetingStaysAtTheFarLeft() = inEachArea(clock, meeting) { items, area ->
        // Its text changes width most, and at the outer edge that moves nothing else.
        assertEquals(listOf("e", "c"), ids(plan(BarConfig(items = items), area).visible))
    }

    @Test fun clickModeKeepsHiddenItemsBehindAChevronThatTheyKeepRoomFor() = inEachArea(clock, memory) { items, area ->
        val p = plan(BarConfig(hiddenMode = HiddenMode.CLICK, items = items), area)
        assertEquals(listOf("c"), ids(p.visible))
        assertEquals(listOf("m"), ids(p.hidden))
        assertEquals(emptyList<String>(), ids(p.revealed))
        assertTrue(p.chevron)
        assertTrue(p.chevronAlways)
        assertFalse(p.open)
    }

    @Test fun expandingRevealsWhatIsBehindTheChevronOnlyWhereThereIsOne() = inEachArea(clock, memory) { items, area ->
        val c = BarConfig(hiddenMode = HiddenMode.CLICK, items = items)
        val p = plan(c, area, expanded = true)
        assertTrue(p.open)
        assertEquals(listOf("m"), ids(p.revealed))
        val showAll = plan(c.copy(hiddenMode = HiddenMode.SHOW_ALL), area, expanded = true)
        assertFalse("Show everything has no ‹ to fold them back with", showAll.open)
        assertEquals(emptyList<String>(), ids(showAll.revealed))
    }

    @Test fun anItemOutOnItsOwnIsNotAlsoBehindTheChevron() = inEachArea(timerRule, clock) { items, area ->
        val p = plan(BarConfig(hiddenMode = HiddenMode.CLICK, items = items), area, active = { it.id == "t" })
        assertEquals(listOf("t", "c"), ids(p.visible))
        assertEquals(emptyList<String>(), ids(p.hidden))
    }

    @Test fun overflowBringsTheChevronWithoutKeepingRoomForIt() = inEachArea(clock) { items, area ->
        val c = BarConfig(items = items)
        assertFalse(plan(c, area).chevron)
        val p = plan(c, area, overflow = true)
        assertTrue(p.chevron)
        assertFalse(p.chevronAlways)
    }

    @Test fun whilePresentingOnlyAPresentingTypeWithSomethingToSayShowsAndTheChevronStays() =
        inEachArea(clock, ItemConfig("t", "timer", Section.HIDDEN)) { items, area ->
            val c = BarConfig(hiddenMode = HiddenMode.CLICK, presenting = true, items = items)
            val idle = plan(c, area, expanded = true)
            assertEquals(emptyList<String>(), ids(idle.visible))
            assertEquals(emptyList<String>(), ids(idle.hidden))
            assertEquals(emptyList<String>(), ids(idle.revealed))
            assertFalse(idle.open)
            assertTrue(idle.chevron)
            assertTrue(idle.chevronAlways)
            assertEquals(listOf("t"), ids(plan(c, area, active = { it.id == "t" }).visible))
        }

    @Test fun anItemOfATypeThisVersionDoesNotKnowDrawsNothing() {
        val future = ItemConfig("f", "from-the-future", Section.SHOWN)
        inEachArea(future, future.copy(id = "g", section = Section.HIDDEN)) { items, area ->
            val p = plan(BarConfig(hiddenMode = HiddenMode.CLICK, items = items), area)
            assertEquals(emptyList<String>(), ids(p.visible))
            assertEquals(emptyList<String>(), ids(p.hidden))
        }
    }

    /** The plan as it was before items had areas, word for word: one strip, planned from the whole list. */
    private fun asBeforeAreas(c: BarConfig, active: (ItemConfig) -> Boolean, expanded: Boolean, overflow: Boolean): StripPlan {
        val visible = c.items.filter { c.shows(it, active(it)) }.sortedBy { if (it.type == Store.PINNED_LEFT) 0 else 1 }
        val hidden = if (c.presenting) emptyList() else c.behindChevron(active)
        return StripPlan(visible, hidden,
            open = expanded && c.hiddenMode != HiddenMode.SHOW_ALL && !c.presenting,
            chevron = c.presenting || overflow || hidden.isNotEmpty(),
            chevronAlways = c.presenting || hidden.isNotEmpty())
    }

    /**
     * Every layout whose items in use are in one area, in every state the plan reads: the home area's plan is the plan the
     * one strip had. An item of an unknown type may be anywhere, since it is in no strip.
     */
    @Test fun aLayoutInOneAreaPlansItsHomeAreaExactlyAsTheOneStripWasPlanned() {
        val inUse = listOf(clock, meeting, memory, timerRule, ItemConfig("h", "timer", Section.HIDDEN), ItemConfig("b", "battery", Section.SHOWN))
        val notInUse = listOf(ItemConfig("f", "from-the-future", Section.SHOWN))
        val actives = listOf<(ItemConfig) -> Boolean>(nobody, { true }, { it.type == "timer" })
        var plans = 0
        for (area in Position.entries) for (elsewhere in Position.entries) for (mask in 0 until (1 shl inUse.size)) for (rest in 0..notInUse.size) {
            val items = notInUse.take(rest).map { it.at(elsewhere) } + inUse.filterIndexed { i, _ -> (mask shr i) and 1 == 1 }.map { it.at(area) }
            if (items.isEmpty()) continue
            for (mode in HiddenMode.entries) for (presenting in listOf(false, true)) {
                val c = BarConfig(hiddenMode = mode, presenting = presenting, items = items)
                for (active in actives) for (expanded in listOf(false, true)) for (overflow in listOf(false, true)) {
                    assertEquals("$c expanded=$expanded overflow=$overflow", asBeforeAreas(c, active, expanded, overflow), c.stripPlan(c.homeArea(), active, expanded, overflow))
                    plans++
                }
            }
        }
        assertTrue("$plans plans compared", plans > 10_000)
    }

    // ---- a layout in more than one area: each area its own -----------------------------------------------

    private val right = Position.RIGHT
    private val middle = Position.CENTER
    private val left = Position.LEFT

    @Test fun eachAreaGetsOnlyItsOwnVisibleAndHiddenItems() {
        val c = BarConfig(hiddenMode = HiddenMode.CLICK, items = listOf(
            clock.at(right), memory.at(left), clock.at(left, "c2"), memory.at(right, "m2"), clock.at(middle, "c3")))
        assertEquals(listOf("c"), ids(plan(c, right).visible))
        assertEquals(listOf("m2"), ids(plan(c, right).hidden))
        assertEquals(listOf("c2"), ids(plan(c, left).visible))
        assertEquals(listOf("m"), ids(plan(c, left).hidden))
        assertEquals(listOf("c3"), ids(plan(c, middle).visible))
        assertEquals(emptyList<String>(), ids(plan(c, middle).hidden))
        assertEquals("opened, an area brings out its own hidden items", listOf("m"), ids(plan(c, left, expanded = true).revealed))
    }

    @Test fun anAreaKeepsRoomForAChevronOnlyForItsOwnHiddenItems() {
        val c = BarConfig(hiddenMode = HiddenMode.CLICK, items = listOf(clock.at(right), memory.at(right), clock.at(left, "c2")))
        assertTrue(plan(c, right).chevron)
        assertTrue(plan(c, right).chevronAlways)
        assertFalse("nothing of Left's is behind a ‹", plan(c, left).chevron)
        assertFalse(plan(c, left).chevronAlways)
        // The caller says what did not fit (the area's own, or for the first placed area anything that has no room at all).
        val full = plan(c, left, overflow = true)
        assertTrue(full.chevron)
        assertFalse(full.chevronAlways)
    }

    @Test fun anOpenBarShowsAChevronAsOpenOnlyWhereTheAreaHasHiddenItems() {
        val c = BarConfig(hiddenMode = HiddenMode.CLICK, items = listOf(clock.at(right), memory.at(right), clock.at(left, "c2")))
        assertTrue(plan(c, right, expanded = true).open)
        // Left's ‹ can only stand for what does not fit: it has nothing to bring out, so it never looks open.
        val full = plan(c, left, expanded = true, overflow = true)
        assertTrue(full.chevron)
        assertFalse(full.open)
        assertEquals(emptyList<String>(), ids(full.revealed))
        assertFalse(plan(c, right).open)
    }

    /**
     * The first placed area's ‹ also stands for the items of an area with no room (the caller says so as overflow). It is a
     * ‹ for what does not fit, like any other: the plan keeps no room for it, and while another area has hidden items of its
     * own to bring out it never looks open, opened bar or not.
     */
    @Test fun theCarriersChevronForAnOwedAreaIsAnOverflowChevronAndNeverLooksOpenWhileAnotherAreaHasHiddenItems() {
        val c = BarConfig(hiddenMode = HiddenMode.CLICK, items = listOf(clock.at(left), clock.at(right, "c2"), memory.at(right)))
        assertFalse("Left has nothing behind a ‹ of its own", plan(c, left).chevron)
        for (expanded in listOf(false, true)) {
            val carrier = plan(c, left, expanded = expanded, overflow = true)
            assertTrue(carrier.chevron)
            assertFalse(carrier.chevronAlways)
            assertFalse("expanded=$expanded", carrier.open)
            assertEquals(listOf("c"), ids(carrier.visible))
            assertEquals(emptyList<String>(), ids(carrier.revealed))
        }
        // While presenting, the home area's ‹ on a carrier that is not the home area is the same ‹: Left is not in use,
        // so the Middle is home and Right is placed first.
        val presenting = BarConfig(presenting = true, items = listOf(clock.at(right), clock.at(middle, "c2")))
        val stand = plan(presenting, right, overflow = true)
        assertTrue(stand.chevron)
        assertFalse(stand.chevronAlways)
        assertFalse(stand.open)
        assertFalse("without the home area owed, Right has no ‹ while presenting", plan(presenting, right).chevron)
    }

    /** As it always was in one strip: the bar is open, its last hidden item went away, and a ‹ still drawn shows as open. */
    @Test fun anOpenBarWithNoHiddenItemAtAllShowsEveryChevronAsOpen() {
        val c = BarConfig(hiddenMode = HiddenMode.CLICK, items = listOf(clock.at(right), timerRule.at(right), clock.at(left, "c2")))
        val out = { item: ItemConfig -> item.id == "t" }
        for (area in listOf(right, left)) {
            assertTrue("$area", plan(c, area, active = out, expanded = true, overflow = true).open)
            assertFalse("$area folded", plan(c, area, active = out, overflow = true).open)
        }
        assertFalse("its rule ended: Right has a hidden item again and Left has none", plan(c, left, expanded = true, overflow = true).open)
        assertTrue(plan(c, right, expanded = true).open)
        assertFalse("Show everything has no ‹ to fold with, in any area",
            plan(c.copy(hiddenMode = HiddenMode.SHOW_ALL), left, active = out, expanded = true, overflow = true).open)
    }

    @Test fun whilePresentingOnlyTheHomeAreaHasTheChevronAndKeepsRoomForIt() {
        val timer = ItemConfig("t", "timer", Section.HIDDEN)
        val c = BarConfig(hiddenMode = HiddenMode.CLICK, presenting = true, items = listOf(clock.at(left), timer.at(right), memory.at(right)))
        assertEquals(left, c.homeArea())
        assertTrue(plan(c, left).chevron)
        assertTrue(plan(c, left).chevronAlways)
        assertFalse(plan(c, right).chevron)
        assertFalse(plan(c, right).chevronAlways)
        assertFalse("an area nothing is in", plan(c, middle).chevron)
        // What presents shows in its own area, and nothing waits behind a ‹ anywhere.
        val running = { item: ItemConfig -> item.id == "t" }
        assertEquals(listOf("t"), ids(plan(c, right, active = running).visible))
        assertEquals(emptyList<String>(), ids(plan(c, left, active = running).visible))
        assertEquals(emptyList<String>(), ids(plan(c, right, expanded = true).hidden))
        assertFalse(plan(c, right, active = running).chevron)
        assertFalse("presenting's ‹ only opens the menu", plan(c, left, expanded = true).open)
        // An area whose own item did not fit still gets a ‹ for it, with no room kept.
        val full = plan(c, right, active = running, overflow = true)
        assertTrue(full.chevron)
        assertFalse(full.chevronAlways)
    }

    @Test fun presentingsChevronFollowsTheHomeAreaWhereLeftIsNotInUse() {
        val c = BarConfig(presenting = true, items = listOf(clock.at(right), clock.at(middle, "c2"), clock.at(left, "c3").copy(type = "from-the-future")))
        assertEquals(middle, c.homeArea())
        assertTrue(plan(c, middle).chevron)
        assertTrue(plan(c, middle).chevronAlways)
        assertFalse(plan(c, right).chevron)
        assertFalse(plan(c, left).chevron)
    }

    @Test fun nextMeetingComesFirstInItsOwnAreaOnly() {
        val c = BarConfig(items = listOf(clock.at(right), clock.at(left, "c2"), meeting.at(left), meeting.at(right, "e2"), clock.at(middle, "c3")))
        assertEquals(listOf("e2", "c"), ids(plan(c, right).visible))
        assertEquals(listOf("e", "c2"), ids(plan(c, left).visible))
        assertEquals(listOf("c3"), ids(plan(c, middle).visible))
    }

    @Test fun anAreaWithNoItemPlansNothing() {
        val c = BarConfig(hiddenMode = HiddenMode.CLICK, items = listOf(clock.at(right), memory.at(right)))
        for (area in listOf(left, middle)) {
            val p = plan(c, area, expanded = true)
            assertEquals(emptyList<String>(), ids(p.visible))
            assertEquals(emptyList<String>(), ids(p.hidden))
            assertFalse(p.chevron)
            assertFalse(p.chevronAlways)
            assertFalse(p.open)
        }
    }

    // ---- what a press on ‹ does ------------------------------------------------------------------------------

    private fun opensMenu(presenting: Boolean = false, mode: HiddenMode = HiddenMode.CLICK, expanded: Boolean = false, bar: Boolean, area: Boolean = bar) =
        chevronOpensMenu(presenting, mode, expanded, barHasHidden = bar, areaHasHidden = area)

    @Test fun aChevronWithNothingBehindItOpensTheMenuAndOneWithHiddenItemsBringsThemOutAndFoldsThem() {
        assertTrue("while presenting", opensMenu(presenting = true, bar = true))
        assertTrue("while presenting, opened", opensMenu(presenting = true, expanded = true, bar = true))
        assertTrue("Show everything", opensMenu(mode = HiddenMode.SHOW_ALL, bar = true))
        assertTrue("there only for what does not fit", opensMenu(bar = false))
        for (mode in listOf(HiddenMode.CLICK, HiddenMode.HOVER)) {
            assertFalse("$mode: it brings them out", opensMenu(mode = mode, bar = true))
            assertFalse("$mode: it folds them", opensMenu(mode = mode, expanded = true, bar = true))
            assertFalse("$mode: the last one's rule ended in the open bar, and it folds", opensMenu(mode = mode, expanded = true, bar = false))
        }
    }

    /** Its press would bring out, or fold, items that are under another ‹: it has none itself, and is there for what does not fit. */
    @Test fun aChevronInAnAreaWithNoHiddenItemOpensTheMenuWhileAnotherAreaHasThem() {
        for (mode in listOf(HiddenMode.CLICK, HiddenMode.HOVER)) for (expanded in listOf(false, true))
            assertTrue("$mode expanded=$expanded", opensMenu(mode = mode, expanded = expanded, bar = true, area = false))
    }

    /** The first strip's ‹ stands for an area with no room whose items all wait behind ‹: a press opens the menu, bar open or not. */
    @Test fun aChevronThatStandsForAnOwedAreasHiddenItemsOpensTheMenu() {
        for (mode in listOf(HiddenMode.CLICK, HiddenMode.HOVER)) for (expanded in listOf(false, true)) {
            val c = BarConfig(hiddenMode = mode, items = listOf(clock.at(right), memory.at(left), timerRule.at(left)))
            val owed = setOf(left)
            val overflow = c.carrierOverflow(owed, c.owedIds(owed, nobody, expanded), nobody)
            assertTrue("$mode expanded=$expanded: the bar has a ‹", plan(c, right, expanded = expanded, overflow = overflow).chevron)
            val behind = c.behindChevron(nobody)
            assertTrue("$mode expanded=$expanded", chevronOpensMenu(c.presenting, mode, expanded, behind.isNotEmpty(), behind.any { it.area == right }))
        }
    }

    /** A layout in one area: what the bar has behind ‹ is what its one strip has, and the rule is the one there always was. */
    @Test fun withOneAreaAPressOnTheChevronDoesWhatItAlwaysDid() {
        var asked = 0
        for (presenting in listOf(false, true)) for (mode in HiddenMode.entries) for (expanded in listOf(false, true)) for (hidden in listOf(false, true)) {
            val asItWas = presenting || mode == HiddenMode.SHOW_ALL || (!expanded && !hidden)
            assertEquals("presenting=$presenting $mode expanded=$expanded hidden=$hidden", asItWas, chevronOpensMenu(presenting, mode, expanded, barHasHidden = hidden, areaHasHidden = hidden))
            asked++
        }
        assertEquals(2 * 3 * 2 * 2, asked)
    }

    /** What a ‹ looks like and what its press does are one rule: in an open bar it opens the menu exactly where it does not look open. */
    @Test fun inAnOpenBarAChevronThatDoesNotLookOpenOpensTheMenu() {
        val layouts = listOf(
            listOf(clock.at(right), memory.at(right), clock.at(left, "c2")),
            listOf(clock.at(right), clock.at(left, "c2"), memory.at(left)),
            listOf(clock.at(right), memory.at(right), clock.at(left, "c2"), memory.at(left, "m2"), clock.at(middle, "c3")),
            listOf(clock.at(right), timerRule.at(right), clock.at(left, "c2")),
            listOf(clock.at(right), clock.at(middle, "c2")),
        )
        var asked = 0
        for (items in layouts) for (mode in HiddenMode.entries) for (presenting in listOf(false, true)) for (active in listOf<(ItemConfig) -> Boolean>(nobody, { true })) {
            val c = BarConfig(hiddenMode = mode, presenting = presenting, items = items)
            val behind = c.behindChevron(active)
            for (area in Position.entries) {
                val open = plan(c, area, active = active, expanded = true, overflow = true).open
                assertEquals("$c $area", !open, chevronOpensMenu(presenting, mode, expanded = true, barHasHidden = behind.isNotEmpty(), areaHasHidden = behind.any { it.area == area }))
                asked++
            }
        }
        assertTrue("$asked asked", asked >= 150)
    }

    // ---- what only reading the code can show ---------------------------------------------------------------

    /** What the rule answers is tested above; the press asks it first, and only then opens the menu or turns the bar. */
    @Test fun aPressOnTheChevronAsksTheRuleBeforeItOpensTheMenuOrTurnsTheBar() {
        val bar = SourceScan.code(SourceScan.sources.single { it.name == "BarService.kt" })
        val press = bar.substringAfter("override fun chevron(at: Rect) {").substringBefore("\n        }\n")
        val asked = press.indexOf("chevronOpensMenu(")
        val menu = press.indexOf("chevronContext(at)", asked)
        val turned = press.indexOf("expanded.value = next", menu)
        assertTrue(press, asked >= 0 && menu > asked && turned > menu)
    }

    @Test fun theBarAndThePreviewAskTheSameFunctionAndNeitherChoosesForItself() {
        val code = { name: String -> SourceScan.code(SourceScan.sources.single { it.name == name }) }
        for (name in listOf("BarService.kt", "BarPreview.kt")) assertTrue(name, ".stripPlan(" in code(name))
        val preview = code("BarPreview.kt")
        for (rule in listOf("sectionShows", "behindChevron", "sortedBy", "PINNED_LEFT")) assertFalse("BarPreview.kt chooses by $rule", rule in preview)
        assertFalse("BarService.kt sorts the strip itself", "PINNED_LEFT" in code("BarService.kt"))
    }
}
