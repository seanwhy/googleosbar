package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.items.PrompterRules.Run
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The prompter's script moves up at the item's speed while it plays, and keeps its place through a pause. */
class PrompterRulesTest {
    @Test fun pausingKeepsThePlace() {
        // Speed 4 is 30 dp a second.
        val played = PrompterRules.play(Run(), 0)
        val paused = PrompterRules.pause(played, 4, 10_000)
        assertFalse(paused.playing)
        assertEquals(300f, PrompterRules.offset(paused, 4, 10_000), 0.01f)
        assertEquals("still while paused", 300f, PrompterRules.offset(paused, 4, 15_000), 0.01f)
        val again = PrompterRules.play(paused, 20_000)
        assertEquals(330f, PrompterRules.offset(again, 4, 21_000), 0.01f)
    }

    @Test fun fasterIsFaster() {
        var last = 0f
        for (speed in 1..10) {
            val moved = PrompterRules.offset(PrompterRules.play(Run(), 0), speed, 1_000)
            assertTrue("speed $speed", moved > last)
            last = moved
        }
        assertEquals(12f, PrompterRules.dpPerSecond(1), 0.01f)
        assertEquals(66f, PrompterRules.dpPerSecond(10), 0.01f)
        // A layout can say anything: what is read is held to the slider's range.
        assertEquals(PrompterRules.dpPerSecond(10), PrompterRules.dpPerSecond(99), 0.01f)
        assertEquals(10, PrompterRules.speed.of(ItemConfig("p", "prompter", options = mapOf("speed" to "99"))))
        assertEquals(4, PrompterRules.speed.of(ItemConfig("p", "prompter")))
    }

    @Test fun aChangeOfSpeedWhilePlayingKeepsThePlace() {
        val played = PrompterRules.play(Run(), 0)
        val rebased = PrompterRules.respeed(played, 4, 10_000)
        assertTrue(rebased.playing)
        assertEquals(300f, PrompterRules.offset(rebased, 10, 10_000), 0.01f)
        assertEquals(366f, PrompterRules.offset(rebased, 10, 11_000), 0.01f)
    }

    @Test fun itStopsAtTheEnd() {
        // The last line starts 500 dp into the script: the script has ended once that line is at the window's top.
        assertFalse(PrompterRules.ended(499f, 500f))
        assertTrue(PrompterRules.ended(500f, 500f))
        val long = PrompterRules.play(Run(), 0)
        assertEquals("never drawn past its end", 500f, PrompterRules.shown(long, 4, 60_000, 500f), 0.01f)
        assertEquals(0f, PrompterRules.shown(Run(offsetDp = -20f), 4, 0, 500f), 0.01f)
        // Played to its end, it stands there, paused.
        val stopped = PrompterRules.atEnd(500f)
        assertFalse(stopped.playing)
        assertTrue(PrompterRules.ended(PrompterRules.offset(stopped, 4, 99_000), 500f))
        // Played again from there, it starts from the top.
        val replayed = PrompterRules.play(stopped, 100_000, endDp = 500f)
        assertTrue(replayed.playing)
        assertEquals(0f, PrompterRules.offset(replayed, 4, 100_000), 0.01f)
        // A script of one line has nowhere to go: it never says it ended.
        assertFalse(PrompterRules.ended(0f, 0f))
    }

    @Test fun backToTheStartIsZeroAndPaused() {
        val r = PrompterRules.restart()
        assertFalse(r.playing)
        assertEquals(0f, PrompterRules.offset(r, 4, 50_000), 0.01f)
    }

    @Test fun theTextSizesAreThree() {
        assertEquals(18, PrompterRules.textSp("s"))
        assertEquals(24, PrompterRules.textSp("m"))
        assertEquals(32, PrompterRules.textSp("l"))
        assertEquals(24, PrompterRules.textSp("huge"))
        assertEquals("m", PrompterRules.size.of(ItemConfig("p", "prompter", options = mapOf("size" to "huge"))))
    }
}
