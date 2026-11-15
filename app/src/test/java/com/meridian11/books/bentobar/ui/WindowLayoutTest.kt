package com.meridian11.books.bentobar.ui

import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.bar.BarStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/** The settings window's one model: three layouts by width, one panel at most, and what the header's status line says. */
class WindowLayoutTest {
    @Test fun below760TheHeaderTakesTwoRowsAndThereIsNoPanel() {
        val l = windowLayout(700)
        assertEquals(WindowClass.NARROW, l.kind)
        assertEquals(0, l.panelDp)
        assertEquals(16, l.marginDp)
    }

    @Test fun from760To1099OnePaneAtFullWidth() {
        for (w in listOf(760, 960, 1099)) {
            val l = windowLayout(w)
            assertEquals("at $w", WindowClass.MID, l.kind)
            assertEquals("at $w", 0, l.panelDp)
            assertEquals("at $w", 24, l.marginDp)
        }
    }

    @Test fun from1100APaneAndOnePanel348Wide() {
        for (w in listOf(1100, 1499)) {
            val l = windowLayout(w)
            assertEquals("at $w", WindowClass.WIDE, l.kind)
            assertEquals("at $w", 348, l.panelDp)
            assertEquals("at $w", 24, l.marginDp)
        }
    }

    @Test fun from1500ThePanelIs420WideAndStillTheOnlyOne() {
        for (w in listOf(1500, 1600, 2400)) {
            val l = windowLayout(w)
            assertEquals("at $w", WindowClass.WIDE, l.kind)
            assertEquals("at $w", 420, l.panelDp)
        }
    }

    @Test fun aLargerTextSizeCountsTheWindowsWidthInTextSizes() {
        // At twice the text size a window of 1180 holds the words one of 590 does: one thing at a time, the header in two rows.
        assertEquals(WindowLayout(WindowClass.NARROW, 0), windowLayout(1180, 2f))
        assertEquals(WindowLayout(WindowClass.MID, 0), windowLayout(1180, 1.3f))
        assertEquals(WindowLayout(WindowClass.MID, 0), windowLayout(1600, 2f))
        // Where the panel still has its place beside the list it grows with its words.
        assertEquals(WindowLayout(WindowClass.WIDE, 452), windowLayout(1500, 1.3f))
        assertEquals(WindowLayout(WindowClass.WIDE, 696), windowLayout(2400, 2f))
        assertEquals(WindowLayout(WindowClass.WIDE, 840), windowLayout(3000, 2f))
    }

    @Test fun theStandardTextSizeAndASmallerOneChangeNothing() {
        for (w in listOf(700, 760, 1099, 1100, 1499, 1500)) {
            assertEquals("at $w", windowLayout(w), windowLayout(w, 1f))
            assertEquals("at $w", windowLayout(w), windowLayout(w, 0.85f))
        }
    }

    @Test fun theStatusLineSaysWhatTheBarIsDoing() {
        fun line(running: Boolean = true, enabled: Boolean = true, status: BarStatus = BarStatus.SHOWN) = statusLine(running, enabled, status)
        // Off: the one step left, which wins over everything the bar last said.
        assertEquals(R.string.bar_banner_title, line(running = false))
        assertEquals(R.string.bar_banner_title, line(running = false, enabled = false, status = BarStatus.NO_ROOM))
        assertEquals(R.string.status_hidden, line(enabled = false))
        assertEquals(R.string.status_hidden, line(enabled = false, status = BarStatus.NO_ROOM))
        assertEquals(R.string.status_starting, line(status = BarStatus.STOPPED))
        assertEquals(R.string.status_no_room, line(status = BarStatus.NO_ROOM))
        assertEquals(R.string.status_empty, line(status = BarStatus.EMPTY))
        assertEquals(R.string.status_no_bar, line(status = BarStatus.NO_BAR))
        assertEquals(R.string.status_covered, line(status = BarStatus.COVERED))
        assertEquals(R.string.status_live, line(status = BarStatus.SHOWN))
        assertEquals(R.string.status_live, line(status = BarStatus.HIDDEN_BY_USER))
        assertEquals(R.string.status_live, line(status = BarStatus.ASLEEP))
    }

    @Test fun whilePresentingModeIsOnTheLineSaysSoWhereverTheBarIsThere() {
        fun line(running: Boolean = true, enabled: Boolean = true, status: BarStatus = BarStatus.SHOWN) = statusLine(running, enabled, status, presenting = true)
        // The bar is there and all but empty: that is the mode, not a bar that has nothing or no room.
        for (s in listOf(BarStatus.SHOWN, BarStatus.EMPTY, BarStatus.NO_ROOM)) assertEquals("$s", R.string.status_presenting, line(status = s))
        // No bar at all, for a reason outside BentoBar, or BentoBar off or hidden: that is said first.
        assertEquals(R.string.status_no_bar, line(status = BarStatus.NO_BAR))
        assertEquals(R.string.status_covered, line(status = BarStatus.COVERED))
        assertEquals(R.string.status_starting, line(status = BarStatus.STOPPED))
        assertEquals(R.string.status_hidden, line(enabled = false))
        assertEquals(R.string.bar_banner_title, line(running = false))
        // The dot agrees: the bar's items are not on screen.
        assertEquals(StatusTone.WAITING, statusTone(running = true, enabled = true, status = BarStatus.SHOWN, presenting = true))
        assertEquals(StatusTone.STEP, statusTone(running = false, enabled = true, status = BarStatus.SHOWN, presenting = true))
    }

    @Test fun theDotAgreesWithTheLine() {
        assertEquals(StatusTone.STEP, statusTone(running = false, enabled = true, status = BarStatus.SHOWN))
        assertEquals(StatusTone.LIVE, statusTone(running = true, enabled = true, status = BarStatus.SHOWN))
        assertEquals(StatusTone.WAITING, statusTone(running = true, enabled = false, status = BarStatus.SHOWN))
        for (s in listOf(BarStatus.STOPPED, BarStatus.NO_ROOM, BarStatus.EMPTY, BarStatus.NO_BAR, BarStatus.COVERED))
            assertEquals("$s", StatusTone.WAITING, statusTone(running = true, enabled = true, status = s))
    }

    @Test fun theTabsAreBarLookSetupAndAbout() {
        // Adding is the Bar page's library: there is no page for it, and so no tab.
        assertEquals(listOf(Page.BAR, Page.LOOK, Page.SETUP, Page.ABOUT), tabs)
        assertEquals("every page has its tab", Page.entries.toSet(), tabs.toSet())
    }
}
