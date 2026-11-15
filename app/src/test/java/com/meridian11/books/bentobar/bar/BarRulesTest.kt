package com.meridian11.books.bentobar.bar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Whether the strip shows, hides or covers system icons, from window lists replayed through the rules that decide it.
 * The first group is one test per window-related finding in AGENTS.md's "Things learned the hard way", named after it.
 *
 * The lists are in the form of the `windows` debug hook. The states a real device was captured in (src/test/resources/windows:
 * a floating, a maximized and a full-screen app, the bar hidden by one, a system panel, a screenshot preview) are those
 * captures, on a 2560x1600 screen with a 54 px bar, titles replaced by the neutral ones. The rest are written out from what
 * docs/research/device-findings.md recorded on a 14-inch Googlebook (1920x1200, the bar 41 px tall): real sizes, made-up ids.
 */
class BarRulesTest {
    private val barBox = Box(0, 0, 1920, 41)
    private val free = Box(168, 0, 1681, 41)
    private val reading = BarSnapshot(BAR_ID, barBox, free, Box(27, 0, 95, 41), "dateTimeChip clock")
    private val bar = "10:t3:StatusBar:[0,0][1920,41]"
    private val strip = "12:t4:BentoBar:[1500,0][1681,41]"
    private val taskbar = "11:t3:Taskbar:[0,1068][1920,1200]"
    private val home = "20:t1:Home:[0,0][1920,1200]"
    private val floating = "30:t1:App:[200,200][1000,800]"
    private val maximized = "30:t1:App:[0,41][1920,1068]"
    private val fullScreen = "30:t1:App:[0,0][1920,1200]"
    private val dialog = "40:t1:Dialog:[600,300][1300,900]"
    private val shade = "50:t3:NotificationShade:[0,0][1920,1200]"
    private val preview = "60:t3:Screenshot preview:[0,0][1920,1200]"

    /** What the device's bar read as in the captures: the bar's size, the free spacer, the clock. */
    private val realBar = Box(0, 0, 2560, 54)
    private val realReading = BarSnapshot(1, realBar, Box(242, 0, 2243, 54), Box(45, 14, 97, 40), "dateTimeChip ImeIndicator ContextualCursor notificationIcons system_icons")

    /** A window list captured from a device, as `./bento debug windows` printed it. */
    private fun capture(name: String) = checkNotNull(javaClass.getResource("/windows/$name.txt")) { "no capture $name" }.readText().trim()

    private fun list(vararg windows: String) = windows.joinToString(" | ")

    private fun look(
        windows: String, last: BarSnapshot? = reading, now: BarSnapshot? = reading, neighbours: BarNeighbours = BarNeighbours(),
        enabled: Boolean = true, interactive: Boolean = true, locked: Boolean = false,
    ): BarDecision {
        val d = WindowDump(windows)
        return BarRules.decide(BarFacts(d.listed, now, last, enabled, interactive, locked, neighbours), d::saysScreenshot)
    }

    /** One look after another, what each remembers going to the next, the bar read the same throughout. */
    private fun replay(vararg lists: String, read: BarSnapshot = reading): List<BarDecision> {
        var neighbours = BarNeighbours()
        return lists.map { w -> look(w, last = read, now = read, neighbours = neighbours).also { neighbours = it.neighbours } }
    }

    /** [look] on the device's bar. */
    private fun lookReal(windows: String, neighbours: BarNeighbours = BarNeighbours()) = look(windows, last = realReading, now = realReading, neighbours = neighbours)

    // ---- the findings ------------------------------------------------------------------------

    @Test fun theBarChangesItsLookWithoutTellingAnyone() {
        // Maximized by the caption's button: the bar turns solid in the same window, with no event of its own. Restored: back.
        val reads = replay(capture("floating"), capture("maximized"), capture("maximized"), capture("floating"), read = realReading).map { it.read }
        // The first look reads too: on the real desktop the home screen is listed under the bar, and with nothing remembered that is a window
        // under it. (The composed list had no home screen, and the first look read nothing.)
        assertEquals(listOf(BarRules.Read.WINDOWS_MOVED, BarRules.Read.WINDOWS_MOVED, null, BarRules.Read.WINDOWS_MOVED), reads)
    }

    @Test fun theBarChangesItsLookWithoutTellingAnyone_aThirdLookIsTheWindowUnderTheBar() {
        // The keyboard's full-screen key: the same window, under the bar now, and no window against its edge.
        val reads = replay(capture("maximized"), capture("fullscreen"), capture("floating"), read = realReading).map { it.read }
        assertEquals(listOf(BarRules.Read.WINDOWS_MOVED, BarRules.Read.WINDOWS_MOVED, BarRules.Read.WINDOWS_MOVED), reads)
    }

    @Test fun theBarChangesLate_aNewStatusBarWindowIsACauseOfItsOwn() {
        val otherBar = reading.copy(windowId = 99)
        assertEquals(BarRules.Read.NEW_BAR_WINDOW, look(list(bar, strip), last = otherBar).read)
        // And it says so even with windows moving in the same look: it starts the longer readings.
        assertEquals(BarRules.Read.NEW_BAR_WINDOW, look(list(bar, strip, maximized), last = otherBar).read)
        // The first look of all, with no bar before it.
        assertEquals(BarRules.Read.NEW_BAR_WINDOW, look(list(bar, strip), last = null).read)
    }

    @Test fun theWindowListCanEndEarly() {
        // A dialog on top: the list holds it, the bar and our overlay, and nothing below. The home screen has not left.
        val looks = replay(list(bar, strip, home), list(bar, strip, dialog), list(bar, strip, home))
        assertEquals(listOf(BarRules.Read.WINDOWS_MOVED, null, null), looks.map { it.read })
        assertEquals("what is remembered survives the short list", looks[0].neighbours, looks[1].neighbours)
    }

    @Test fun leastPrivilege_aTitleIsAskedOfABigSystemWindowOverTheBarAndNoOther() {
        val windows = list(bar, strip, taskbar, floating, maximized, "70:t3:Panel:[1376,41][1826,266]", "71:t3:Toast:[0,0][400,41]", shade)
        val d = WindowDump(windows)
        BarRules.decide(BarFacts(d.listed, reading, reading, enabled = true, interactive = true, locked = false, BarNeighbours()), d::saysScreenshot)
        assertEquals("only the shade: big, a system window, over the free area", listOf(50), d.asked)
    }

    @Test fun leastPrivilege_oneCoverIsEnoughSoNoTitleIsAskedAfterIt() {
        val windows = list(bar, shade, "51:t3:Second:[0,0][1920,1200]")
        val d = WindowDump(windows)
        val decided = BarRules.decide(BarFacts(d.listed, reading, reading, enabled = true, interactive = true, locked = false, BarNeighbours()), d::saysScreenshot)
        assertEquals(50, decided.cover?.id)
        assertEquals(listOf(50), d.asked)
    }

    @Test fun withoutContentEventsTheNodeCacheGoesStale() {
        val same = WindowFact(BAR_ID, WindowFact.Kind.SYSTEM, barBox)
        assertTrue("a look at the same bar in the same place reads no node", BarRules.keepsReading(full = false, last = reading, bar = same))
        assertFalse("a full look always reads it", BarRules.keepsReading(full = true, last = reading, bar = same))
        assertFalse("nothing to keep", BarRules.keepsReading(full = false, last = null, bar = same))
        assertFalse("another window", BarRules.keepsReading(full = false, last = reading, bar = same.copy(id = 99)))
        assertFalse("the same window moved", BarRules.keepsReading(full = false, last = reading, bar = same.copy(box = Box(0, 0, 1920, 45))))

        // Every 2 s only the spacer is refreshed, and a full scan comes every 30 s whatever it says.
        assertEquals(BarRules.LightLook.SPACER, BarRules.lightLook(hasSpacer = true, sinceFullMs = 2_000))
        assertEquals(BarRules.LightLook.SPACER, BarRules.lightLook(hasSpacer = true, sinceFullMs = 30_000))
        assertEquals(BarRules.LightLook.FULL, BarRules.lightLook(hasSpacer = true, sinceFullMs = 30_001))
        // Without the spacer there is nothing cheap to refresh: the window list only, and a full scan every 10 s.
        assertEquals(BarRules.LightLook.WINDOWS, BarRules.lightLook(hasSpacer = false, sinceFullMs = 10_000))
        assertEquals(BarRules.LightLook.FULL, BarRules.lightLook(hasSpacer = false, sinceFullMs = 10_001))
    }

    @Test fun theStatusBarHidesAfterAnAppGoesFullscreen_theStripFollowsItsWindowAway() {
        // When an app hides the bar, the bar's window leaves the list; the overlay would otherwise stay on top of the app.
        // Real: the bar and the strip both leave the list (the system's own bar is hidden, then the strip follows it).
        val d = WindowDump(capture("fullscreen-hides-bar"))
        assertNull(BarRules.findBar(d.listed, 2560, 60))
        val gone = look(capture("fullscreen-hides-bar"), now = null)
        assertFalse(gone.show)
        assertEquals(BarStatus.NO_BAR, gone.status)
    }

    @Test fun playProtectsLiveThreatDetection_readingsOnlyOnAChangeAndOnlyWhileTheStripShows() {
        // Nothing moved, the strip showing: no screenshot of the bar.
        assertNull(lookReal(capture("maximized"), neighbours = replay(capture("maximized"), read = realReading).last().neighbours).read)
        // Something moved, the strip not showing: none either. The strip's return asks again.
        assertNull(look(list(bar, strip, maximized), locked = true).read)
        assertNull(look(list(bar, strip, maximized), enabled = false).read)
        assertNull(look(list(bar, shade, maximized)).read)
        // The same for a new status bar window.
        assertNull(look(list(bar, strip), last = reading.copy(windowId = 99), interactive = false).read)
    }

    // ---- the rest of the rules ----------------------------------------------------------------

    @Test fun aShadeOverTheBarHidesTheStrip() {
        val d = look(list(bar, strip, shade))
        assertFalse(d.show)
        assertEquals(BarStatus.COVERED, d.status)
        assertEquals(50, d.cover?.id)
    }

    @Test fun theDesktopPanelsOpenBelowTheBarAndDoNotHideIt() {
        val d = lookReal(capture("quick-panel"))
        assertTrue(d.show)
        assertNull(d.status)
        assertNull(d.cover)
    }

    @Test fun smallSystemUiNearTheTopDoesNotBlinkTheStripAway() {
        // Four times the bar's height is the line: below it a system window over the bar counts for nothing.
        assertTrue(look(list(bar, "71:t3:Toast:[300,0][800,163]")).show)
        assertFalse(look(list(bar, "71:t3:Toast:[300,0][800,164]")).show)
    }

    @Test fun aSystemWindowBesideTheFreeAreaDoesNotHideTheStrip() {
        assertTrue(look(list(bar, "71:t3:Tall:[0,0][168,900]")).show)
    }

    @Test fun anAppWindowOverTheBarDoesNotCountAsCover() {
        assertNull(lookReal(capture("fullscreen")).cover)
        assertTrue(lookReal(capture("fullscreen")).show)
    }

    @Test fun aCaptionWindowIsNeitherACoverNorANeighbour() {
        // The t7 window the system puts over an app's top edge: the floating app's caption sits at its top, the maximized app's just under the bar.
        for (name in listOf("floating", "maximized")) {
            val d = lookReal(capture(name))
            assertTrue(name, d.show)
            assertNull(name, d.cover)
            assertNull(name, d.status)
        }
        // Taken out of the list, the same list is remembered the same: the caption adds nothing to what the bar looks like.
        for (name in listOf("floating", "maximized")) {
            val with = capture(name)
            val without = with.split(" | ").filterNot { it.contains(":t7:") }.joinToString(" | ")
            assertTrue("$name has a caption", without != with)
            assertEquals(name, lookReal(without).neighbours, lookReal(with).neighbours)
        }
    }

    @Test fun aCaptionOverTheBarsPlaceOfAFullScreenAppHidesNothing() {
        // Full screen with the bar hidden: the caption's window is at the very top, in the bar's place, with no bar listed.
        val windows = capture("fullscreen-hides-bar")
        assertTrue("the capture holds a caption window over the top edge", windows.contains(":t7:Window:[0,0][2560,60]"))
        val d = look(windows, now = null)
        assertEquals(BarStatus.NO_BAR, d.status)
        assertFalse(d.show)
    }

    @Test fun theSystemsConfirmationOfFullScreenIsNoCoverWithoutABar() {
        // A big system window over the free area ([830,0][1730,364]), but there is no bar: the reason is the missing bar.
        assertEquals(BarStatus.NO_BAR, look(capture("fullscreen-hides-bar"), now = null).status)
    }

    @Test fun theBarIsNoCoverOfItself() {
        // A tall status bar window, while the system resizes it, must not hide the strip that sits on it.
        val tall = reading.copy(bar = Box(0, 0, 1920, 200), free = Box(168, 0, 1681, 200))
        assertNull(look("10:t3:StatusBar:[0,0][1920,800]", now = tall, last = tall).cover)
    }

    @Test fun theScreenshotPreviewIsBigButDoesNotCoverTheBar() {
        assertTrue(lookReal(capture("screenshot-preview")).show)
    }

    @Test fun aScreenshotPreviewOverAShadeHidesNothingOfTheShade() {
        val d = look(list(bar, preview, shade))
        assertFalse(d.show)
        assertEquals(50, d.cover?.id)
    }

    @Test fun whyTheStripIsNotShownIsSaidInOneOrder() {
        // Asleep wins over everything: locked, screen off.
        assertEquals(BarStatus.ASLEEP, look(list(bar, shade), interactive = false, enabled = false, now = null).status)
        assertEquals(BarStatus.ASLEEP, look(list(bar, shade), locked = true, enabled = false, now = null).status)
        // Then the user's own switch.
        assertEquals(BarStatus.HIDDEN_BY_USER, look(list(bar, shade), enabled = false, now = null).status)
        assertEquals(BarStatus.HIDDEN_BY_USER, look(list(bar, shade), enabled = false).status)
        // Then no bar; then a cover.
        assertEquals(BarStatus.NO_BAR, look(list(bar), now = null).status)
        assertEquals(BarStatus.COVERED, look(list(bar, shade)).status)
    }

    @Test fun whileTheStripIsShownNoStatusIsSaid() {
        val d = look(list(bar, strip))
        assertTrue(d.show)
        assertNull("placing the strip says SHOWN, NO_ROOM or STOPPED", d.status)
    }

    @Test fun onlyAppWindowsMakeTheBarLookDifferent() {
        // A big system window beside the free area: no cover, and nothing that gives the bar a look of its own.
        val right = reading.copy(free = Box(1500, 0, 1681, 41))
        val d = look(list(bar, strip, "71:t3:Tall:[0,0][800,900]"), now = right, last = right)
        assertTrue(d.show)
        assertNull(d.read)
    }

    @Test fun aBarTheLookCouldNotReadRemembersNoWindows() {
        val before = replay(list(bar, strip, maximized)).last().neighbours
        val blind = look(list(strip, maximized), now = null, neighbours = before)
        assertEquals("with no bar to measure against, what was remembered stands", before, blind.neighbours)
    }

    @Test fun aReadingLikeTheLastOneOfAnotherWindowIsANewWindow() {
        val other = reading.copy(windowId = 99)
        assertEquals(BarRules.Read.NEW_BAR_WINDOW, look(list("99:t3:StatusBar:[0,0][1920,41]"), now = other, last = reading).read)
    }

    @Test fun midChangeATreeWithoutNodesKeepsTheLastReadingOfThisSameBar() {
        val noTree = BarRules.parse(BAR_ID, barBox, emptyList())
        assertEquals(reading, BarRules.settled(noTree, reading))
        assertNull("another window's", BarRules.settled(noTree, reading.copy(windowId = 99)))
        assertNull("the same window at another place", BarRules.settled(noTree, reading.copy(bar = Box(0, 0, 1920, 45))))
        assertNull("nothing read before: stay hidden until a scan can read it", BarRules.settled(noTree, null))
        assertEquals("a real reading replaces it", reading.copy(summary = "new"), BarRules.settled(reading.copy(summary = "new"), reading))
        assertNull("a scan that threw keeps nothing", BarRules.settled(null, reading))
    }

    @Test fun theRulesFileHasNoAndroidInIt() {
        val code = File("src/main/java/com/meridian11/books/bentobar/bar/BarRules.kt").readText()
        assertFalse("a rule that needs Android belongs at the edge, in StatusBarScan or BarController", Regex("""(?m)^import android\.""").containsMatchIn(code))
    }

    private companion object { const val BAR_ID = 10 }

    // ---- what a layout change has to do with the bar ---------------------------------------------------

    private val layout = com.meridian11.books.bentobar.data.BarConfig(items = listOf(com.meridian11.books.bentobar.data.ItemConfig("t", "text")))

    /** A label typed letter by letter saves on every key: reading the bar's tree for each of them is the waste F64 names. */
    @Test fun aLabelTypedLetterByLetterIsNotGeometry() {
        val typed = layout.copy(items = layout.items.map { it.with("text", "h") })
        assertEquals(BarRules.geometry(layout), BarRules.geometry(typed))
    }

    @Test fun whatMovesTheStripOrWhatItHoldsIsGeometry() {
        val g = BarRules.geometry(layout)
        assertNotEquals(g, BarRules.geometry(layout.copy(enabled = false)))
        assertNotEquals(g, BarRules.geometry(layout.copy(items = layout.items.map { it.copy(area = com.meridian11.books.bentobar.data.Position.LEFT) })))
        assertNotEquals(g, BarRules.geometry(layout.copy(pill = com.meridian11.books.bentobar.data.Pill.SOLID)))
        assertNotEquals(g, BarRules.geometry(layout.copy(spacing = layout.spacing + 2)))
        assertNotEquals(g, BarRules.geometry(layout.copy(items = layout.items + com.meridian11.books.bentobar.data.ItemConfig("u", "text"))))
        assertNotEquals(g, BarRules.geometry(layout.copy(items = layout.items.map { it.copy(section = com.meridian11.books.bentobar.data.Section.HIDDEN) })))
        assertNotEquals(g, BarRules.geometry(layout.copy(items = emptyList())))
    }

    @Test fun whatTheStripOnlyDrawsIsNotGeometry() {
        val g = BarRules.geometry(layout)
        assertEquals(g, BarRules.geometry(layout.copy(color = com.meridian11.books.bentobar.data.ColorMode.DARK)))
        assertEquals(g, BarRules.geometry(layout.copy(textSize = com.meridian11.books.bentobar.data.TextSize.LARGE)))
        assertEquals(g, BarRules.geometry(layout.copy(pinnedOpen = true, autoCollapseSec = 5)))
    }

    @Test fun oneItemsAreaIsGeometryAndTheOldPositionAloneIsNot() {
        val two = layout.copy(items = layout.items + com.meridian11.books.bentobar.data.ItemConfig("u", "text"))
        val g = BarRules.geometry(two)
        assertNotEquals(g, BarRules.geometry(two.copy(items = two.items.map { if (it.id == "u") it.copy(area = com.meridian11.books.bentobar.data.Position.CENTER) else it })))
        // Since version 4 the items say where they are: the Position a layout had before is only what the migration reads.
        assertEquals(g, BarRules.geometry(two.copy(position = com.meridian11.books.bentobar.data.Position.LEFT)))
    }
}
