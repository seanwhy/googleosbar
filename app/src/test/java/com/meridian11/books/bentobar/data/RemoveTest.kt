package com.meridian11.books.bentobar.data

import com.meridian11.books.bentobar.items.Items
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the library's + makes, Remove from the bar, which deletes the item whatever was set up in it, and the one Undo. */
class RemoveTest {
    private fun fresh(type: String, id: String = type) = Items.of(type)!!.fresh(id, Position.RIGHT)
    private val clock = fresh("clock")
    private val cpu = fresh("cpu")
    private val media = fresh("media")
    private val unknown = ItemConfig("u", "from-a-newer-version", options = mapOf("depth" to "7"))
    private fun BarConfig.at(id: String) = items.single { it.id == id }
    private fun layout(vararg items: ItemConfig, mode: HiddenMode = HiddenMode.SHOW_ALL) = BarConfig(items = items.toList(), hiddenMode = mode)

    @Test fun thePlusPutsATypeInTheBarAndAQuietOneUnderTheLineWithItsRuleOn() {
        assertEquals(ItemConfig("clock", "clock", Section.SHOWN, area = Position.RIGHT), clock)
        assertEquals(Section.HIDDEN, media.section)
        assertTrue(media.whenActive)
        assertEquals(Position.LEFT, Items.of("clock")!!.fresh("x", Position.LEFT).area)
        assertEquals(Items.of("shortcut")!!.defaultOptions(), fresh("shortcut").options)
    }

    @Test fun aNewCountdownHasNoDate() {
        assertEquals(emptyMap<String, String>(), fresh("countdown").options)
    }

    @Test fun removeDeletesTheItemWhateverWasSetUpInIt() {
        val tokyo = clock.with("city", "Tokyo").copy(display = Display.ICON)
        val waiting = cpu.copy(section = Section.HIDDEN, whenActive = true).with("above", "50")
        val always = media.copy(section = Section.SHOWN, whenActive = false)
        for (mode in HiddenMode.entries) {
            val c = layout(waiting, tokyo, always, unknown, fresh("calendar"), mode = mode)
            for (item in c.items) {
                val after = c.without(item.id)
                assertEquals("${mode.name} ${item.id}", c.items - item, after.items)
                assertFalse("nothing waits anywhere", after.items.any { it.section == Section.OFF })
            }
        }
    }

    @Test fun removingWhatIsNotInTheLayoutChangesNothing() {
        val c = layout(clock, cpu)
        assertSame(c, c.without("nobody"))
    }

    @Test fun removingTheLastItemLeavesTheEmptyBarWhereItWas() {
        val c = BarConfig(items = listOf(clock.copy(area = Position.LEFT)), position = Position.RIGHT)
        assertEquals(Position.LEFT, c.without("clock").position)
    }

    @Test fun undoBringsBackTheExactLayout() {
        val layouts = listOf(
            layout(cpu, clock.with("city", "Tokyo"), fresh("calendar")),
            layout(cpu.copy(whenActive = true).with("above", "50"), clock),
            layout(cpu.copy(section = Section.HIDDEN, whenActive = true), clock.copy(display = Display.TEXT, area = Position.LEFT)),
            layout(clock, unknown),
            layout(media, clock, mode = HiddenMode.HOVER),
            BarConfig(items = listOf(clock.copy(area = Position.LEFT)), position = Position.RIGHT),
        )
        for (c in layouts) for (item in c.items) {
            val after = c.without(item.id)
            assertEquals(item.id, c, after.undone(c, after))
        }
    }

    @Test fun undoKeepsWhatIsNotLayoutAndGivesUpOnALayoutArrangedOtherwiseSince() {
        val c = layout(cpu, clock, fresh("calendar"))
        val after = c.placed("clock", Position.RIGHT, Part.UPPER, 0)
        assertEquals(listOf("clock", "cpu", "calendar"), after.items.map { it.id })
        // An option changed after the move stays; the move is taken back.
        val later = after.copy(spacing = 20, items = after.items.map { if (it.id == "clock") it.with("city", "Tokyo") else it })
        val back = later.undone(c, after)!!
        assertEquals(c.items.map { it.id }, back.items.map { it.id })
        assertEquals("Tokyo", back.at("clock").options["city"])
        assertEquals(20, back.spacing)
        // The bar's own menu hid an item meanwhile: nothing is taken back.
        assertNull(after.hide("cpu").undone(c, after))
        assertNull(after.without("calendar").undone(c, after))
        // An item the change brought in goes again.
        val more = c.copy(items = c.items + fresh("timer"))
        assertEquals(c, more.undone(c, more))
    }

    @Test fun undoTakesAnItemTheChangeBroughtInOutAgainWhateverWasDoneToItSince() {
        val c = layout(cpu, clock)
        // The +, a type dropped into the bar: a fresh item, which is then set up. Undo takes the change back and leaves nothing behind.
        val added = c.copy(items = listOf(fresh("timer")) + c.items)
        val setUp = added.copy(items = added.items.map { if (it.id == "timer") it.with("label", "Tea").copy(display = Display.ICON) else it })
        assertEquals(c, setUp.undone(c, added))
        // A copy that was changed since goes too.
        val tokyo = layout(cpu, clock.with("zone", "Asia/Tokyo"))
        val twice = tokyo.copy(items = tokyo.items + tokyo.at("clock").copy(id = "copy"))
        val paris = twice.copy(items = twice.items.map { if (it.id == "copy") it.with("zone", "Europe/Paris") else it })
        assertEquals(tokyo, paris.undone(tokyo, twice))
        // And one that is as the change left it: a copy, a quiet type dropped above the line.
        assertEquals(tokyo, twice.undone(tokyo, twice))
        val dropped = c.copy(items = listOf(media.copy(section = Section.SHOWN, whenActive = false)) + c.items)
        assertEquals(c, dropped.undone(c, dropped))
        // What else changed since stays, as with any Undo.
        assertEquals(c.copy(spacing = 20), dropped.copy(spacing = 20).undone(c, dropped))
    }
}
