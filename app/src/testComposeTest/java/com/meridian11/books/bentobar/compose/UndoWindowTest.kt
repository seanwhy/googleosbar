package com.meridian11.books.bentobar.compose

import android.app.Application
import android.view.KeyEvent
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.meridian11.books.bentobar.data.BarConfig
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.data.Kept
import com.meridian11.books.bentobar.data.Position
import com.meridian11.books.bentobar.data.Store
import com.meridian11.books.bentobar.data.Store.Consent
import com.meridian11.books.bentobar.data.fresh
import com.meridian11.books.bentobar.data.withoutArea
import com.meridian11.books.bentobar.items.Background
import com.meridian11.books.bentobar.items.Env
import com.meridian11.books.bentobar.items.FlightItem
import com.meridian11.books.bentobar.items.FlightLoad
import com.meridian11.books.bentobar.items.FlightRules.Tracked
import com.meridian11.books.bentobar.items.PrompterItem
import com.meridian11.books.bentobar.ui.BarKeys
import com.meridian11.books.bentobar.ui.MainActivity
import com.meridian11.books.bentobar.ui.Undo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Undo is the settings window's, and its reach ends with the window: closed for good, the change it kept can be taken
 * back no more, and what a removed item's type kept for it outside the layout (a Prompter's notes, the flight a Flight
 * follows) is deleted then. A window that is only made again (a rotation, a new size) or left for another keeps it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w1180dp-h780dp-mdpi")
class UndoWindowTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var before: BarConfig
    private var window: ActivityScenario<MainActivity>? = null

    private val clock = ItemConfig("a", "clock")
    private val prompter = ItemConfig("p", PrompterItem.type, area = Position.LEFT)
    private val flight = FlightItem.fresh("f", Position.LEFT)
    private val layout = listOf(prompter, flight, clock)
    private val script = "First the numbers, then what they mean for the plan."

    @Before fun env() { Env.init(app); before = Store.config.value; Store.setConsent(Consent.AGREED); Undo.last.value = null; Undo.over.value = null }

    @After fun close() {
        window?.close()
        Undo.last.value = null
        Undo.over.value = null
        for (id in listOf("p", "f")) { PrompterItem.forgetOwn(id); FlightItem.forgetOwn(id) }
        Store.update { before }
        settled()
        Store.setConsent(Consent.NOT_ASKED)
    }

    /** What the two types do on their own threads has been done. */
    private fun settled() {
        for (type in listOf(PrompterItem.type, FlightItem.type)) {
            val done = CountDownLatch(1)
            Background.executor(type).execute { done.countDown() }
            assertTrue(done.await(5, TimeUnit.SECONDS))
        }
        rule.waitForIdle()
    }

    private fun open() {
        window = ActivityScenario.launch(MainActivity::class.java)
        rule.waitForIdle()
    }

    private fun ids() = Store.config.value.items.map { it.id }
    private fun notes() = Kept.own("prompter").names()
    private fun flights() = Kept.own("flight").names()

    private fun ctrlZ(): Boolean {
        var taken = false
        window!!.onActivity { taken = it.onKeyDown(BarKeys.UNDO.keycode, KeyEvent(0, 0, KeyEvent.ACTION_DOWN, BarKeys.UNDO.keycode, 0, BarKeys.UNDO.modifiers)) }
        settled()
        return taken
    }

    /** A bar with a Prompter that has notes and a Flight that follows one, both in Left; then Left is removed in the window, one step of Undo. */
    private fun removedInTheWindow() {
        Store.update { BarConfig(items = layout, position = Position.RIGHT) }
        PrompterItem.edit("p", script)
        PrompterItem.save("p")
        assertTrue(FlightLoad.take("f", Tracked("XX455", "2026-10-02"), 1_000))
        settled()
        open()
        window!!.onActivity { assertTrue(Undo.change(area = Position.LEFT) { it.withoutArea(Position.LEFT) }) }
        settled()
        assertEquals(listOf("a"), ids())
        assertEquals("held while Undo can bring the item back", setOf("p"), notes())
        assertEquals(setOf("f"), flights())
    }

    @Test fun afterTheWindowWasClosedCtrlZInANewOneBringsNothingBackAndWhatTheRemovedItemsKeptIsGone() {
        removedInTheWindow()
        // Closed as Back or the caption's close button does it: the window finishes.
        window!!.onActivity { it.finish() }
        window!!.close()
        window = null
        settled()
        assertNull("Undo ended with the window", Undo.last.value)
        assertEquals("the Prompter's notes", emptySet<String>(), notes())
        assertNull(PrompterItem.script("p"))
        assertEquals("the flight the Flight followed", emptySet<String>(), flights())
        open()
        ctrlZ()
        assertEquals("nothing came back", listOf("a"), ids())
        assertEquals(emptySet<String>(), notes())
        assertEquals(emptySet<String>(), flights())
        rule.onNode(hasTestTag("row:p")).assertDoesNotExist()
        rule.onNode(hasTestTag("row:f")).assertDoesNotExist()
    }

    @Test fun afterARotationCtrlZStillBringsBothBackWithWhatTheyKept() {
        removedInTheWindow()
        // The window is made again, as for a rotation or a new size: it does not finish.
        window!!.recreate()
        settled()
        assertNotNull(Undo.last.value)
        assertEquals(setOf("p"), notes())
        assertEquals(setOf("f"), flights())
        assertTrue(ctrlZ())
        assertEquals(layout, Store.config.value.items)
        assertEquals(script, Kept.own("prompter").read("p")?.text)
        assertNotNull(Kept.own("flight").read("f"))
        rule.onNode(hasTestTag("row:p")).assertExists()
        rule.onNode(hasTestTag("row:f")).assertExists()
    }

    @Test fun aWindowThatIsOnlyLeftKeepsItsUndo() {
        removedInTheWindow()
        // Another window in front, the settings stopped behind it: nothing ends.
        window!!.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        settled()
        assertNotNull(Undo.last.value)
        assertEquals(setOf("p"), notes())
        assertEquals(setOf("f"), flights())
        window!!.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        assertTrue(ctrlZ())
        assertEquals(layout, Store.config.value.items)
    }
}
