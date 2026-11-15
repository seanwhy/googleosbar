package com.meridian11.books.bentobar.ui

import com.meridian11.books.bentobar.data.BarConfig
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.data.Position
import com.meridian11.books.bentobar.data.Section
import com.meridian11.books.bentobar.data.fresh
import com.meridian11.books.bentobar.items.ItemGroup
import com.meridian11.books.bentobar.items.Items
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The library's rules: it lists the types and nothing else, which of them the bar has, and what the filter shows. */
class LibraryTest {
    private fun fresh(type: String, id: String = type) = Items.of(type)!!.fresh(id, Position.RIGHT)
    private fun shelf(type: String, name: String, blurb: String) = Shelf(Items.of(type)!!, name, blurb, inBar = false)

    private val shelves = listOf(
        shelf("clock", "Clock", "The time in another city"),
        shelf("battery", "Battery details", "Power draw in watts, temperature, time to full"),
        shelf("heat", "Heat", "How warm the device runs"),
        shelf("weather", "Weather", "Temperature and rain for a city"),
    )
    private fun found(query: String) = shelves.matching(query).map { it.name }

    @Test fun theFilterMatchesANameAndADescription() {
        assertEquals(listOf("Clock"), found("clo"))
        assertEquals(listOf("Battery details"), found("WATTS"))
        assertEquals(listOf("Weather"), found(" rain "))
        assertEquals(listOf("Clock", "Weather"), found("city"))
        assertTrue(found("zzz").isEmpty())
    }

    @Test fun noFilterShowsEverything() {
        assertSame(shelves, shelves.matching(""))
        assertSame(shelves, shelves.matching("   "))
    }

    @Test fun everyTypeHasOneRowInTheListsOrderWhateverTheLayoutHolds() {
        val types = ItemGroup.entries.flatMap { Items.inGroup(it) }
        // Several items of a type, set up or not, one of a type this version doesn't know: the list is the types.
        val cfg = BarConfig(items = listOf(fresh("cpu"), fresh("clock", "c1").with("zone", "Europe/London"), fresh("clock", "c2").with("zone", "Asia/Tokyo"),
            fresh("timer").with("label", "Tea").copy(section = Section.HIDDEN, whenActive = true), ItemConfig("u", "from-a-newer-version")))
        for (layout in listOf(BarConfig(), cfg)) {
            val all = shelvesOf(layout, { it.type.uppercase() }, { "about " + it.type })
            assertEquals(types, all.map { it.type })
            assertEquals(types.map { it.type.uppercase() }, all.map { it.name })
            assertEquals(types.map { "about " + it.type }, all.map { it.blurb })
        }
    }

    @Test fun aTypeIsInYourBarWhileTheLayoutHasAnItemOfItShownOrHidden() {
        val cfg = BarConfig(items = listOf(fresh("cpu"), fresh("clock", "c1"), fresh("clock", "c2"), fresh("timer").copy(section = Section.HIDDEN, whenActive = true)))
        val inBar = shelvesOf(cfg, { "" }, { "" }).filter { it.inBar }.map { it.type.type }.toSet()
        assertEquals(setOf("cpu", "clock", "timer"), inBar)
        assertTrue(shelvesOf(BarConfig(), { "" }, { "" }).none { it.inBar })
    }
}
