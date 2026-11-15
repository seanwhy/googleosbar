package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.SourceScan
import com.meridian11.books.bentobar.SourceScan.place
import com.meridian11.books.bentobar.data.BarConfig
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.data.Kept
import com.meridian11.books.bentobar.data.MapPrefs
import com.meridian11.books.bentobar.data.Online
import com.meridian11.books.bentobar.data.Store
import com.meridian11.books.bentobar.data.hide
import com.meridian11.books.bentobar.data.Position
import com.meridian11.books.bentobar.data.without
import com.meridian11.books.bentobar.data.withoutArea
import com.meridian11.books.bentobar.ui.Undo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A prompter's script is the user's own words: it is kept in memory and in the item's own
 * file in the no-backup directory (`Kept.own`), never in the layout (which Copy settings and a backup carry), never in
 * a log line or a debug hook's output. Other private texts have their own tests, beside their items.
 */
class PrivateTextTest {
    private val dir = File(System.getProperty("java.io.tmpdir"), "bentobar-private-${System.nanoTime()}")
    private val prefs = MapPrefs()
    private val clock = ItemConfig("c", "clock")
    private val prompter = ItemConfig("p", PrompterItem.type)
    private val script = "Good morning, everyone. The figures for the quarter came in on Friday, and they are better than we hoped.\n" +
        "First the numbers, then what they mean for the plan."

    private fun at(id: String) = Store.config.value.items.single { it.id == id }

    /** What a type does on its own thread has been done. */
    private fun settled(type: String) {
        val done = CountDownLatch(1)
        Background.executor(type).execute { done.countDown() }
        assertTrue(done.await(5, TimeUnit.SECONDS))
    }

    /** Nothing of [text] is in [item]'s options, nor in the layout as it is stored. */
    private fun notInTheLayout(item: ItemConfig, text: String) {
        for ((key, value) in item.options) {
            assertTrue("$key holds ${value.length} characters", value.length <= 64)
            assertFalse(key, value == text || text in value)
        }
        assertFalse("the stored layout holds it", prefs.map.values.any { it is String && text in it })
    }

    @Before fun fresh() {
        Online.init(dir)
        Store.open(prefs) { }
        Store.update { BarConfig(items = listOf(clock, prompter)) }
        Undo.last.value = null
    }

    @After fun gone() {
        Undo.last.value = null
        PrompterItem.forgetOwn("p")
        settled(PrompterItem.type)
        dir.deleteRecursively()
        Online.init(File(dir, "empty"))
    }

    @Test fun aPrompterScriptIsNotInTheLayout() {
        PrompterItem.edit("p", script)
        PrompterItem.save("p")
        // A change of its own options afterwards (the speed) does not carry it in either.
        Store.updateItem("p") { PrompterRules.speed.set(it, 7) }
        settled(PrompterItem.type)
        notInTheLayout(at("p"), script)
        assertEquals("kept in its own file", script, Kept.own("prompter").read("p")?.text)
        assertEquals("and in memory", script, PrompterItem.script("p"))
    }

    @Test fun aBlankScriptKeepsNothing() {
        PrompterItem.edit("p", script)
        PrompterItem.save("p")
        PrompterItem.edit("p", "  \n ")
        PrompterItem.save("p")
        settled(PrompterItem.type)
        assertNull(Kept.own("prompter").read("p"))
    }

    @Test fun noLogLineInFilesThatHoldPrivateText() {
        val files = SourceScan.sources.filter { f -> f.place().let { it.startsWith("items/Prompter") } }
        assertEquals(files.map { it.name }.toString(), 3, files.size)
        for (f in files) {
            val code = SourceScan.code(f)
            for (call in listOf("Log.", "println(", "printStackTrace", "System.out", "System.err")) assertFalse("${f.name} calls $call", call in code)
        }
    }

    @Test fun noDebugLineSaysTheText() {
        PrompterItem.edit("p", script)
        for (line in listOfNotNull(PrompterItem.debug(emptyList()))) assertFalse(line, script.take(20) in line)
        // Nor what the type is asked to say about its item in the bar, which the debug hooks print too.
        assertTrue("the type is discreet", PrompterItem.discreet)
    }

    @Test fun whatIsKeptForAnItemThatIsGoneIsSweptAtTheStart() {
        PrompterItem.edit("p", script)
        PrompterItem.save("p")
        // Left behind by items that went while the process ended (Undo is in memory, so it never let them go).
        Kept.own("prompter").write("gone", "an old script", 1)
        settled(PrompterItem.type)
        PrompterItem.settle(Settle.SERVICE_STARTED)
        settled(PrompterItem.type)
        assertEquals(setOf("p"), Kept.own("prompter").names())
    }

    @Test fun aRemovedPromptersNotesStayWhileUndoCanBringItBackAndGoWhenItNoLongerCan() {
        PrompterItem.edit("p", script)
        PrompterItem.save("p")
        settled(PrompterItem.type)
        // The settings' Remove: the item leaves the layout, as one step of Undo.
        assertTrue(Undo.change(removed = "p") { it.without("p") })
        assertEquals(listOf("c"), Store.config.value.items.map { it.id })
        // The bar's start in between keeps what Undo can still bring back.
        PrompterItem.settle(Settle.SERVICE_STARTED)
        settled(PrompterItem.type)
        assertEquals(setOf("p"), Kept.own("prompter").names())
        assertTrue(Undo.undo())
        settled(PrompterItem.type)
        assertEquals("back as it was", prompter, at("p"))
        assertEquals("with its notes", script, Kept.own("prompter").read("p")?.text)
        assertEquals(script, PrompterItem.script("p"))
        // Removed again: still there. Then something else changes: no Undo brings it back, and the notes go, from memory too.
        assertTrue(Undo.change(removed = "p") { it.without("p") })
        settled(PrompterItem.type)
        assertEquals(setOf("p"), Kept.own("prompter").names())
        assertTrue(Undo.change { it.hide("c") })
        settled(PrompterItem.type)
        assertEquals(emptySet<String>(), Kept.own("prompter").names())
        assertNull(PrompterItem.script("p"))
    }

    /** Closing the settings window ends Undo's reach as the next change does: the notes go then, not at some change days later. */
    @Test fun aRemovedPromptersNotesGoWhenTheSettingsWindowIsClosed() {
        PrompterItem.edit("p", script)
        PrompterItem.save("p")
        settled(PrompterItem.type)
        assertTrue(Undo.change(removed = "p") { it.without("p") })
        settled(PrompterItem.type)
        assertEquals(setOf("p"), Kept.own("prompter").names())
        Undo.end()
        settled(PrompterItem.type)
        assertEquals(emptySet<String>(), Kept.own("prompter").names())
        assertNull(PrompterItem.script("p"))
        assertFalse(Undo.undo())
        assertEquals(listOf("c"), Store.config.value.items.map { it.id })
    }

    @Test fun aRemovedAreasPromptersNotesStayWhileUndoCanBringItBackAndGoWhenItNoLongerCan() {
        Store.update { c -> c.copy(items = c.items.map { if (it.id == "p") it.copy(area = Position.LEFT) else it }) }
        PrompterItem.edit("p", script)
        PrompterItem.save("p")
        settled(PrompterItem.type)
        // Remove an area: the prompter goes with its area, as one step of Undo.
        assertTrue(Undo.change(area = Position.LEFT) { it.withoutArea(Position.LEFT) })
        assertEquals(listOf("c"), Store.config.value.items.map { it.id })
        PrompterItem.settle(Settle.SERVICE_STARTED)
        settled(PrompterItem.type)
        assertEquals(setOf("p"), Kept.own("prompter").names())
        assertTrue(Undo.undo())
        settled(PrompterItem.type)
        assertEquals("with its notes", script, Kept.own("prompter").read("p")?.text)
        assertTrue(Undo.change(area = Position.LEFT) { it.withoutArea(Position.LEFT) })
        settled(PrompterItem.type)
        assertEquals(setOf("p"), Kept.own("prompter").names())
        assertTrue(Undo.change { it.hide("c") })
        settled(PrompterItem.type)
        assertEquals(emptySet<String>(), Kept.own("prompter").names())
        assertNull(PrompterItem.script("p"))
    }

    @Test fun theBarsOwnMenuHasNoUndoSoItsRemoveTakesTheNotesAtOnce() {
        PrompterItem.edit("p", script)
        PrompterItem.save("p")
        settled(PrompterItem.type)
        Undo.removeForGood("p")
        settled(PrompterItem.type)
        assertEquals(listOf("c"), Store.config.value.items.map { it.id })
        assertEquals(emptySet<String>(), Kept.own("prompter").names())
        assertNull(PrompterItem.script("p"))
    }
}
