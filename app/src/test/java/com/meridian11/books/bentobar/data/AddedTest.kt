package com.meridian11.books.bentobar.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** Where a new item goes: into the home area unless the caller names one, and in the list at the left of its own area. */
class AddedTest {
    private fun item(id: String, type: String, area: Position, section: Section = Section.SHOWN) = ItemConfig(id, type, section, area = area)
    private fun List<ItemConfig>.adding(new: ItemConfig) = with(Store) { added(new) }.map { it.id }

    @After fun forget() { Store.open(MapPrefs()) { }; Store.update { BarConfig() } }

    @Test fun aNewItemInAOneAreaLayoutGoesIntoThatArea() {
        val meeting = item("e", Store.PINNED_LEFT, Position.LEFT)
        val clock = item("c", "clock", Position.LEFT)
        val memory = item("m", "memory", Position.LEFT, Section.HIDDEN)
        // The pure part: before the first Shown item of Left that is not Next meeting.
        assertEquals(listOf("e", "new", "c", "m"), listOf(meeting, clock, memory).adding(item("new", "timer", Position.LEFT)))
        // The default: no area named, so the home area, which in a Left-only layout is Left.
        Store.open(MapPrefs()) { }
        Store.update { BarConfig(items = listOf(meeting, clock, memory)) }
        val id = Store.add("timer")
        assertEquals(Position.LEFT, Store.config.value.items.single { it.id == id }.area)
        assertEquals(listOf("e", id, "c", "m"), Store.config.value.items.map { it.id })
    }

    @Test fun aShownItemGoesBeforeTheFirstShownItemOfItsOwnArea() {
        val right = item("r", "clock", Position.RIGHT)
        val left = item("l", "cpu", Position.LEFT)
        val left2 = item("l2", "memory", Position.LEFT)
        assertEquals(listOf("r", "new", "l", "l2"), listOf(right, left, left2).adding(item("new", "timer", Position.LEFT)))
        assertEquals(listOf("new", "r", "l", "l2"), listOf(right, left, left2).adding(item("new", "timer", Position.RIGHT)))
        // Next meeting keeps the far left of its own area only: in another area it is not in the way.
        val meeting = item("e", Store.PINNED_LEFT, Position.RIGHT)
        assertEquals(listOf("e", "new", "r", "l"), listOf(meeting, right, left).adding(item("new", "timer", Position.RIGHT)))
        assertEquals(listOf("e", "r", "new", "l"), listOf(meeting, right, left).adding(item("new", "timer", Position.LEFT)))
    }

    @Test fun withNoShownItemOfItsAreaToGoBeforeAShownItemGoesAtTheEnd() {
        val right = item("r", "clock", Position.RIGHT)
        val hidden = item("h", "cpu", Position.CENTER, Section.HIDDEN)
        assertEquals(listOf("r", "h", "new"), listOf(right, hidden).adding(item("new", "timer", Position.CENTER)))
        val meeting = item("e", Store.PINNED_LEFT, Position.LEFT)
        assertEquals(listOf("e", "new"), listOf(meeting).adding(item("new", "timer", Position.LEFT)))
        assertEquals(listOf("new"), emptyList<ItemConfig>().adding(item("new", "timer", Position.LEFT)))
    }

    @Test fun aHiddenItemGoesAtTheEndWhateverItsArea() {
        val left = item("l", "clock", Position.LEFT)
        val right = item("r", "cpu", Position.RIGHT)
        assertEquals(listOf("l", "r", "new"), listOf(left, right).adding(item("new", "timer", Position.LEFT, Section.HIDDEN)))
    }

    @Test fun anAreaTheCallerNamesIsTheNewItemsArea() {
        Store.open(MapPrefs()) { }
        Store.update { BarConfig(items = listOf(item("r", "clock", Position.RIGHT), item("l", "cpu", Position.LEFT))) }
        val id = Store.add("timer", area = Position.RIGHT)
        assertEquals(Position.RIGHT, Store.config.value.items.single { it.id == id }.area)
        assertEquals(listOf(id, "r", "l"), Store.config.value.items.map { it.id })
        // Without one it is the home area, here Left.
        val home = Store.add("memory", Section.HIDDEN)
        assertEquals(Position.LEFT, Store.config.value.items.single { it.id == home }.area)
    }
}
