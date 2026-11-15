package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.data.BarConfig
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.data.Kept
import com.meridian11.books.bentobar.data.MapPrefs
import com.meridian11.books.bentobar.data.Section
import com.meridian11.books.bentobar.data.Store
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files

/**
 * The seam that gives BentoBar's mute back (Review Focus 1): the moments `settle` names reach [InUseItem], which undoes a
 * mute of its own, once, and nothing else; the bar hiding is none of them. On a fake microphone, with [Kept] in a folder of
 * the test's own, as a restart finds it.
 */
class InUseSettleTest {
    private val dir = Files.createTempDirectory("inuse").toFile()

    /** Android's microphone switch, as far as BentoBar sees it: [takes] false is a device that reads back unchanged. */
    private class FakeMic(val takes: Boolean = true) : InUseItem.MicMute {
        var state = false
        var sets = 0
        override var muted: Boolean
            get() = state
            set(value) { sets++; if (takes) state = value }
    }

    private val mic = FakeMic()
    private val before = InUseItem.mute

    /** A keeper as a process has it when it starts: it knows only what [Kept] knows. Disk work runs on the spot. */
    private fun keeper(on: FakeMic = mic) = MuteKeeper(on, { Kept.own("inuse") }, { it.run() }).also { InUseItem.mute = it }

    @Before fun fresh() {
        Kept.init(dir)
        Store.open(MapPrefs()) { }
        Store.update { BarConfig() }
        keeper()
    }

    @After fun gone() {
        InUseItem.mute = before
        Store.update { BarConfig() }
        dir.deleteRecursively()
    }

    private fun place() = Store.update { it.copy(items = listOf(ItemConfig("u", "inuse", Section.HIDDEN, whenActive = true))) }
    private fun removeIt() = Store.update { it.copy(items = emptyList()) }
    private fun keptOurs() = Kept.own("inuse").has("ours")

    @Test fun aMuteOfOursIsUndoneOnceWhenItsItemLeavesTheLayout() {
        place()
        assertTrue(InUseItem.mute.mute())
        assertTrue(mic.state)
        assertTrue("a restart still knows it is ours", keptOurs())
        removeIt()
        InUseItem.settle(Settle.LEFT_LAYOUT)
        assertFalse(mic.state)
        assertFalse(InUseItem.mute.ours)
        assertFalse(keptOurs())
        assertEquals(2, mic.sets)
        InUseItem.settle(Settle.LEFT_LAYOUT)
        InUseItem.settle(Settle.SERVICE_ENDING)
        assertEquals("a second settle does nothing", 2, mic.sets)
    }

    @Test fun theBarHidingLeavesTheMuteAlone() {
        place()
        InUseItem.mute.mute()
        InUseItem.onIdle()
        assertTrue(mic.state)
        assertTrue(InUseItem.mute.ours)
        assertEquals(1, mic.sets)
    }

    @Test fun theServiceEndingUndoesAMuteOfOursThoughTheItemIsPlaced() {
        place()
        InUseItem.mute.mute()
        InUseItem.settle(Settle.SERVICE_ENDING)
        assertFalse(mic.state)
        assertFalse(keptOurs())
    }

    @Test fun aStartWithNoSuchItemUndoesAMuteKeptFromBefore() {
        Kept.own("inuse").write("ours", "1", 0)
        mic.state = true
        keeper()
        InUseItem.settle(Settle.SERVICE_STARTED)
        assertFalse(mic.state)
        assertFalse(keptOurs())
    }

    @Test fun aStartWithTheItemPlacedKeepsTheMuteAndKnowsItIsOurs() {
        Kept.own("inuse").write("ours", "1", 0)
        mic.state = true
        place()
        keeper()
        InUseItem.settle(Settle.SERVICE_STARTED)
        assertTrue(mic.state)
        assertEquals(0, mic.sets)
        assertTrue("its menu can undo it", InUseItem.mute.ours)
        assertTrue(keptOurs())
    }

    @Test fun aMuteFoundIsNotOursAndIsLeftAlone() {
        mic.state = true
        for (why in Settle.entries) InUseItem.settle(why)
        place()
        for (why in Settle.entries) InUseItem.settle(why)
        assertTrue(mic.state)
        assertEquals(0, mic.sets)
    }

    @Test fun aMuteSomeoneElseUndidIsOursNoMore() {
        place()
        InUseItem.mute.mute()
        mic.state = false
        InUseItem.mute.seen(muted = false)
        assertFalse(InUseItem.mute.ours)
        assertFalse(keptOurs())
        // Someone mutes it again: theirs, and it stays when the item goes.
        mic.state = true
        removeIt()
        InUseItem.settle(Settle.LEFT_LAYOUT)
        assertTrue(mic.state)
    }

    @Test fun aMuteTheDeviceDoesNotTakeIsSaidAndNotKept() {
        val stubborn = FakeMic(takes = false)
        keeper(stubborn)
        place()
        assertFalse(InUseItem.mute.mute())
        assertFalse(InUseItem.mute.works)
        assertFalse(InUseItem.mute.ours)
        assertFalse(keptOurs())
    }

    @Test fun aMicrophoneMutedAlreadyIsNotMutedAgainNorTakenAsOurs() {
        mic.state = true
        assertFalse(InUseItem.mute.mute())
        assertEquals(0, mic.sets)
        assertFalse(InUseItem.mute.ours)
        assertTrue("nothing went wrong", InUseItem.mute.works)
    }
}
