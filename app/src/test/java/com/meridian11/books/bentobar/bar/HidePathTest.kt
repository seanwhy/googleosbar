package com.meridian11.books.bentobar.bar

import com.meridian11.books.bentobar.SourceScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What only reading the code can show: the order in which the strip goes away is load-bearing (AGENTS.md), and what
 * the strip's window took with it is let go of on the way out. The controller is an accessibility service's, with no
 * way to run here.
 *
 * Since the bar has a strip for each area, what is read here is the order of calls and nothing else: what a rule
 * answers is a test of that rule (ArrangeTest, OwedTest, PopupRulesTest, PopupStateTest, KeepAwakeTest,
 * StripContentTest), and what the controller does with the answers is on the device list (docs/RELEASING.md).
 */
class HidePathTest {
    private val code = File("src/main/java/com/meridian11/books/bentobar/bar/BarService.kt").readText()
    private val colors = File("src/main/java/com/meridian11/books/bentobar/bar/ColorReader.kt").readText()
    private val menus = File("src/main/java/com/meridian11/books/bentobar/bar/MenuCoordinator.kt").readText()

    /** The one way off the screen: what check() runs when the strip is not to be shown, and what stop() runs. */
    private val hide = code.substringAfter("private fun leaveScreen() {").substringBefore("\n    }\n")
    private val stop = code.substringAfter("fun stop() {").substringBefore("The strip leaves the screen, whatever the reason")

    private val place = code.substringAfter("private fun place(").substringBefore("\n    }\n")
    /** What place() does once every strip it shows has its window. */
    private val placed = place.substringAfter("for (spot in BarRules.givingWayFirst(a.spots, ").substringAfter("\n        }\n")
    private val leave = code.substringAfter("private fun leave(strip: AreaStrip) {").substringBefore("\n    }\n")
    private val measured = code.substringAfter("private fun applyWidth(").substringBefore("\n    }\n")

    /** [steps] stand in [text] one after the other. */
    private fun inOrder(text: String, vararg steps: String) {
        var at = 0
        for (step in steps) {
            val i = text.indexOf(step, at)
            assertTrue("$step is missing, or out of order, in: $text", i >= 0)
            at = i + step.length
        }
    }

    @Test fun hidingAndStoppingShareTheOneWayOffTheScreen() {
        val hideBranch = code.substringAfter("\"bar hidden (statusBar=").substringBefore("/** adb `debug trace on|off`")
        assertTrue("the hide path runs leaveScreen()", Regex("""^\s+leaveScreen\(\)$""", RegexOption.MULTILINE).containsMatchIn(hideBranch))
        assertTrue("stop() runs leaveScreen()", Regex("""^\s+leaveScreen\(\)$""", RegexOption.MULTILINE).containsMatchIn(stop))
        // Nothing of the hide path is spelled out again beside it.
        for (step in listOf("menus.closeMenu(now = true)", "cancelDrag()", "strip.hide()", "colors.stripLeft()")) assertFalse(step, step in stop.replace("leaveScreen()", ""))
    }

    @Test fun theBarHidesInItsOneOrderAndLetsGoOfThePointerBeforeTheWindowGoes() {
        var at = 0
        for (step in listOf("closeMenu(now = true)", "hideTip()", "cancelDrag()", "hoverAreas = emptySet()", "main.removeCallbacks(expandOnHover)",
            "strip.hide()", "colors.stripLeft()", "awake.update()", "Ticker.stop(\"bar\")")) {
            val i = hide.indexOf(step, at)
            assertTrue("$step is missing, or out of order, in the hide path", i >= 0)
            at = i + step.length
        }
        // The readings still to come go with the strip: what stripLeft() does, in the colour reader.
        val left = colors.substringAfter("fun stripLeft()").substringBefore("\n")
        assertTrue(left, "watch.clear()" in left && "main.removeCallbacks(sample)" in left)
    }

    /** The window decision is pinned in BarRulesTest and the schedule in ColorWatchTest; these are the lines that make them do anything. */
    @Test fun theBarChangingLateAsksForAReadingWhereTheDecisionSaysSo() {
        val place = code.substringAfter("private fun place(").substringBefore("\n    }\n")
        val show = place.indexOf("strip.show")
        assertTrue("place() raises SLOW once the strip is back", show >= 0 && place.indexOf("colors.readSoon(ColorWatch.Cause.SLOW, 250)", show) > show)
        val check = code.substringAfter("private fun check(full: Boolean) {").substringBefore("private fun place(")
        assertTrue("a new status bar window asks for a quick reading", "if (d.read == BarRules.Read.NEW_BAR_WINDOW) colors.readSoon(ColorWatch.Cause.QUICK, 250)" in check)
        assertTrue("windows that moved are told to the colour reader", "else if (d.read == BarRules.Read.WINDOWS_MOVED) colors.windowsMoved()" in check)
    }

    /** A screenshot's answer can come after stop(): running it is what closes its buffer, so the thread it runs on stays. */
    @Test fun aScreenshotAnsweredAfterStopStillClosesItsBuffer() {
        val scan = File("src/main/java/com/meridian11/books/bentobar/bar/StatusBarScan.kt").readText()
        assertFalse("the answers' thread is never shut down", ".shutdown()" in code || ".shutdown()" in scan)
        assertFalse("no thread of the controller's own", "newSingleThreadExecutor" in code)
        assertTrue("one thread for the whole process", Regex("""private val \w+ = Executors\.newSingleThreadExecutor\(\)""").containsMatchIn(scan))
    }

    @Test fun whatTheHoverHeldOutFoldsAwayButWhatWasPinnedStays() {
        assertTrue(hide, Regex("""if \(!pinned && expanded\.value\) expanded\.value = false""").containsMatchIn(hide))
    }

    /** Hidden items folded by anything but the hide path stay folded: the pin goes with them, or the next start of the service opens them with no click. */
    @Test fun whatFoldsHiddenItemsAlsoClearsTheirPin() {
        val fold = code.substringAfter("private fun fold() {").substringBefore("\n    }\n")
        assertTrue(fold, "expanded.value = false" in fold && "pinned = false" in fold && "pinnedOpen = false" in fold)
        assertEquals("outside the fold, only the hide path turns them off (and there nothing was pinned)", 2,
            Regex("""expanded\.value = false""").findAll(code).count())
        val wheel = code.substringAfter("override fun wheel(up: Boolean) {").substringBefore("\n        }\n")
        assertTrue(wheel, "fold()" in wheel)
    }

    @Test fun stoppingLeavesNothingRevealedInTheProcesssTicker() {
        assertTrue(stop, "Ticker.revealHidden = false" in stop)
        // After closeMenu (in the way off the screen), which sets it from `expanded` when a popup was open.
        assertTrue(hide, "closeMenu(now = true)" in hide)
        assertTrue(stop.indexOf("leaveScreen()") < stop.indexOf("Ticker.revealHidden = false"))
    }

    @Test fun theDisplaysItemFollowsTheServiceSoKeepAwakeWhileConnectedHolds() {
        val onConfig = code.substringAfter("private fun onConfig() {").substringBefore("\n    }\n")
        assertTrue("onConfig must call DisplaysItem.follow(true): only it sets the rule's `ruled`", "DisplaysItem.follow(true)" in onConfig)
        val off = stop.indexOf("DisplaysItem.follow(false)")
        assertTrue("stop() must call DisplaysItem.follow(false)", off >= 0)
        assertTrue("follow(false) comes before Ticker.stop, in the way off the screen", off < stop.indexOf("leaveScreen()") && "Ticker.stop(\"bar\")" in hide)
    }

    /** A measure posted before the bar left arrives after it: it finds a bar that is gone, and places nothing. */
    @Test fun aMeasureThatComesLateNeverPutsBackABarThatHasLeft() {
        inOrder(hide, "showing = false", "strip.hide()")
        val asked = measured.indexOf("if (showing)")
        val placing = Regex("""\bplace\(""").find(measured)?.range?.first ?: -1
        assertTrue("the placing stands behind the question whether the bar is up: $measured", asked >= 0 && placing > asked)
    }

    /** A window that has not been measured has nothing to say about what it draws: it is not on show until it has. */
    @Test fun aStripsWindowStaysInvisibleFromItsShowUntilItsFirstMeasure() {
        val strip = File("src/main/java/com/meridian11/books/bentobar/bar/AreaStrip.kt").readText()
        inOrder(strip.substringAfter("fun show(").substringBefore("\n    }\n"), "window.show(content)", "window.setContentVisible(false)")
        inOrder(measured, "strip.pending = false", "strip.setContentVisible(")
    }

    /**
     * What the bar does once when it is back (keep awake, the reading it owes, the ticker) is done after every strip has
     * its window, and after the look at whether Android gave it one: not once per strip, and never for a refused window.
     */
    @Test fun whatTheBarDoesOnceWhenItIsBackComesAfterEveryStripHasItsWindow() {
        val loop = place.substringAfter("for (spot in BarRules.givingWayFirst(a.spots, ").substringBefore("\n        }\n")
        assertTrue(loop, "strip.show" in loop)
        for (once in listOf("awake.update()", "colors.readSoon(", "Ticker.start(", "BarStatus.STOPPED")) assertFalse("$once in $loop", once in loop)
        inOrder(placed, "BarStatus.STOPPED", "awake.update()", "if (a.first) {", "colors.readSoon(", "Ticker.start(")
    }

    /**
     * Strips come and go in a placing: which windows Android refused and whether the bar draws anything are asked once
     * they have, before the status is said. A bar with no window left then leaves the one way, before it says it stopped.
     */
    @Test fun whatIsUpIsAskedBeforeTheStatusIsSaidAndABarWithNoWindowLeftLeavesBeforeItSaysItStopped() {
        inOrder(placed, "refused = ", "BarStatus.current.value = status()", "leaveScreen()", "BarStatus.STOPPED")
        inOrder(placed, "stripEmpty = ", "BarStatus.current.value = status()")
    }

    /**
     * One strip going while the bar stays: what hangs from it goes before its window, and what it had no room for is let
     * go of before what the bar has no room for is published.
     */
    @Test fun aStripThatLeavesAloneTakesItsPopupAndItsDragBeforeItsWindow() {
        inOrder(leave, "menus.closeFor(strip)", "cancelDrag()", "strip.hide()")
        inOrder(leave, "strip.dropped = emptySet()", "publishOverflow()")
    }

    /** Which strips are on view is read from what they are: it is said again after a strip was measured or went, or the bar did. */
    @Test fun whichStripsAreOnViewIsSaidAgainAfterAStripIsMeasuredOrGone() {
        inOrder(measured, "strip.setContentVisible(", "noteOnView()")
        inOrder(leave, "strip.hide()", "noteOnView()")
        inOrder(hide, "strip.hide()", "noteOnView()")
    }

    /**
     * The window that keeps the screen on is about to go while the bar stays: the flag is on the next one, or the wake
     * lock holds, before it does.
     */
    @Test fun keepAwakeIsHandedOnBeforeTheWindowThatHeldItGoes() {
        inOrder(place, "placedAreas = a.spots", "awake.update()", "leave(strip)")
    }

    /**
     * A measure places the bar again before keep awake is told, and tells it once: told by the placing that is about to
     * change, it took a wake lock and let it go within one measure.
     */
    @Test fun aMeasurePlacesTheBarBeforeKeepAwakeIsToldAndTellsItOnce() {
        inOrder(measured, "place(placing.first, placing.second)", "awake.update()")
        assertEquals("one update for a measure, the placing's or its own", 1, Regex("""awake\.update\(\)""").findAll(measured).count())
    }

    /**
     * With no window carrying the flag for a moment, the screen could go off in it. Which window and when the wake lock
     * holds instead is `KeepAwakeRule.choose` (KeepAwakeTest); what only the code shows is that update() does as it says,
     * in that order.
     */
    @Test fun keepAwakesFlagIsOnTheStripThatCarriesItBeforeItLeavesAnyOther() {
        val update = File("src/main/java/com/meridian11/books/bentobar/bar/KeepAwake.kt").readText().substringAfter("fun update() {").substringBefore("\n    }\n")
        inOrder(update, "KeepAwakeRule.choose(", "flag(it, true)", "flag(window, false)")
        inOrder(update, "screenLock.acquire()", "flag(window, false)", "screenLock.release()")
    }

    /**
     * Which strip is the first, and which areas are owed, only name areas: they are said before any window goes or comes,
     * so a window that is added has the watch for presses outside the bar in its params already. Which strip that is
     * is PopupRules.watchingArea (PopupRulesTest).
     */
    @Test fun theFirstStripAndWhatIsOwedAreNamedBeforeAnyWindowGoesOrComes() {
        inOrder(place, "carrier = a.carrier", "watchOutside()", "leave(strip)")
        inOrder(place, "watchOutside()", "for (spot in BarRules.givingWayFirst(a.spots, ")
        inOrder(place, "owed.value = a.owed", "leave(strip)")
        inOrder(place, "owed.value = a.owed", "for (spot in BarRules.givingWayFirst(a.spots, ")
    }

    /**
     * A press outside the first strip's window that is on another strip is on the bar, and folds nothing: where it was is
     * asked before anything folds (PopupRules.pressedArea, PopupRulesTest).
     */
    @Test fun aPressOutsideIsAskedWhetherItIsOnAStripBeforeHiddenItemsFold() {
        inOrder(code.substringAfter("private fun outsideClick(ev: MotionEvent) {").substringBefore("\n    }\n"), "stripAt(", "fold()")
    }

    /**
     * The old one setting places nothing by itself: only the areas' own rules read it (data/), for a layout from before
     * there were areas and for one with no items.
     */
    @Test fun nothingInTheBarOrTheSettingsReadsWherePositionSays() {
        // A read of the setting, whatever holds the layout (cfg, it, c, config); not a pointer's place (c.position.x, down.position).
        val read = Regex("""(?<!\bdown)(?<!\bchange)(\.|::)position\b(?!\??\.[xy]\b)""")
        for (reads in listOf("cfg.position", "it.position", "c.position == Position.LEFT", "config?.position", "BarConfig::position"))
            assertTrue(reads, read.containsMatchIn(reads))
        for (pointer in listOf("c.position.x", "c?.position?.y", "r.hit(down.position, touch)", "change.position - change.previousPosition"))
            assertFalse(pointer, read.containsMatchIn(pointer))
        val files = with(SourceScan) { sources.filter { it.place().startsWith("bar/") || it.place().startsWith("ui/") } }
        assertTrue("${files.size} files", files.size > 20)
        for (file in files) for ((n, line) in SourceScan.code(file).lines().withIndex())
            assertFalse("${file.name}:${n + 1} reads Position's old setting: $line", read.containsMatchIn(line))
    }

    /** A press on ‹ and the pointer coming and going are one strip's, so their hooks can name an area: the script's list says it. */
    @Test fun theScriptsListSaysThatAChevronPressAndAHoverCanNameAnArea() {
        val header = File("../bento").readLines().takeWhile { it.startsWith("#") }.joinToString("\n")
        for (hook in listOf("chevron [left|middle|right]", "hover on|off [left|middle|right]")) assertTrue("the script's list says $hook", hook in header)
    }

    @Test fun anItemsMenuIsToldItIsOpeningBeforeItsFirstFrame() {
        val toggle = menus.substringAfter("fun toggleMenu(").substringBefore("val loc = strip.locationOnScreen()")
        val close = toggle.indexOf("closeMenu(dissolve = true)")
        assertTrue("toggleMenu closes the old popup first", close >= 0)
        assertTrue("menuOpening runs after closeMenu(dissolve = true)", toggle.indexOf("menuOpening", close) > close)
    }

    /**
     * A popup folding away turns round when its item is clicked again, where it stands. Under another area's strip that is
     * the wrong place (the ‹ menu has one key in every area): it is taken away at once, before the click is asked about,
     * and the popup opens anew under the strip that was clicked. Only its own key takes it away, and what the state then
     * answers is PopupStateTest's.
     */
    @Test fun aMenuStillLeavingFromAnotherAreaIsTakenAwayBeforeTheClickIsAskedAbout() {
        inOrder(menus.substringAfter("fun toggleMenu(").substringBefore("val loc = strip.locationOnScreen()"), "popups.dropLeaving(", "popups.toggle(")
    }

    /** A tooltip ends with its item: the LaunchedEffect that says "the hover ended" is cancelled, not run, when the item leaves the strip. */
    @Test fun aTooltipEndsWithItsItem() {
        val ui = File("src/main/java/com/meridian11/books/bentobar/bar/BarUi.kt").readText()
        assertTrue(ui, Regex("""onDispose \{ if \(hovered\) events\.itemHover\(entry\.item, bounds, false\) \}""").containsMatchIn(ui))
        val hover = code.substringAfter("override fun hover(inside: Boolean) {").substringBefore("\n        }\n")
        assertTrue("the pointer leaving the strip ends its tooltip: $hover", Regex("""if \(!inside\) menus\.hideTip\(\)""").containsMatchIn(hover))
        // Off one strip and still over another, the bar's hover goes on: the tooltip of the strip it left ends all the same,
        // before the hover is found to be nothing to the bar.
        inOrder(hover, "menus.hideTip(strip)", "if (bar == null) return")
    }

    @Test fun itemsThatLeftOrDidNotFitLeaveNoBoxBehind() {
        val ui = File("src/main/java/com/meridian11/books/bentobar/bar/BarUi.kt").readText()
        assertTrue("items and the chevron report going", ui.contains("onDispose { glow.gone(entry.item.id); events.gone(entry.item.id) }") &&
            ui.contains("onDispose { glow.gone(\"chevron\"); events.gone(\"chevron\") }"))
        assertTrue("items FitRow left out report going", Regex("""glow\.dropped\(ids\).*events::gone""").containsMatchIn(ui))
        assertTrue(Regex("""override fun gone\(id: String\) \{ placed -= id \}""").containsMatchIn(code))
        assertFalse("outside() is the same rule as the tested one", "StripHighlight.target(x, placed" in menus)
    }
}
