package com.meridian11.books.bentobar.bar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the status bar's tree says, from the nodes the walk found (the walk itself is the edge: `StatusBarScan.scan`).
 * The tree is the one docs/research/device-findings.md recorded on a 14-inch Googlebook.
 */
class StatusBarScanTest {
    private val bar = Box(0, 0, 1920, 41)
    private fun node(id: String, left: Int, right: Int, depth: Int = 1, text: String = "", described: Boolean = false, clickable: Boolean = false, children: Int = 0) =
        BarNode(id, Box(left, 0, right, 41), depth, text, described, clickable, children)

    private val recordedTree = listOf(
        BarNode("", bar, 0, children = 6),
        node("dateTimeChip", 27, 168, clickable = true, described = true, children = 2),
        node("clock", 27, 95, depth = 2, text = "9:19", described = true),
        node("date", 100, 168, depth = 2, text = "Sun, Sep 27"),
        node("DesktopStatusBarSpacer", 168, 1681),
        node("ImeIndicator", 1681, 1722, text = "US", described = true, clickable = true),
        node("ContextualCursor", 1722, 1763, described = true, clickable = true),
        node("notificationIcons", 1763, 1817, described = true, clickable = true),
        node("system_icons", 1817, 1893, described = true, clickable = true, children = 2),
        node("statusIcons", 1817, 1860, depth = 2, described = true),
        node("battery", 1860, 1893, depth = 2, described = true),
    )

    @Test fun theRecordedBarFindsItsSpacerAndItsClockAndNamesItsItems() {
        val s = BarRules.parse(7, bar, recordedTree)
        assertEquals(Box(168, 0, 1681, 41), s.free)
        assertEquals(Box(168, 0, 1681, 41), s.spacer)
        assertEquals(Box(27, 0, 95, 41), s.clock)
        assertEquals(7, s.windowId)
        // The spacer itself is not labelled, so it is no part of the fingerprint.
        assertTrue(s.summary, "dateTimeChip" in s.summary && "battery" in s.summary && "DesktopStatusBarSpacer" !in s.summary)
    }

    @Test fun aResourceNameCountsByItsEnd() {
        val named = recordedTree.map { if (it.id == "DesktopStatusBarSpacer") it.copy(id = "com.android.systemui:id/DesktopStatusBarSpacer") else it }
        assertEquals(Box(168, 0, 1681, 41), BarRules.parse(7, bar, named).free)
    }

    @Test fun aBarWithoutTheSpacerUsesItsWidestGap() {
        val noSpacer = recordedTree.filterNot { it.id == "DesktopStatusBarSpacer" }
        val s = BarRules.parse(7, bar, noSpacer)
        assertEquals(Box(168, 0, 1681, 41), s.free)
        assertNull(s.spacer)
    }

    @Test fun aSpacerSqueezedToNothingGivesWayToTheWidestGapAndIsStillRemembered() {
        // lightCheck compares against the spacer's own bounds: compared with the free area it would run a full scan every time.
        val squeezed = recordedTree.map { if (it.id == "DesktopStatusBarSpacer") it.copy(box = Box(168, 0, 168, 41)) else it }
        val s = BarRules.parse(7, bar, squeezed)
        assertEquals(Box(168, 0, 168, 41), s.spacer)
        assertEquals(Box(168, 0, 1681, 41), s.free)
    }

    @Test fun aNodeAsWideAsTheBarIsNoItem() {
        // A wrapper around the whole bar must not fill it: only leaf-ish pieces take room.
        val wrapper = node("wrapper", 0, 1920, described = true)
        val s = BarRules.parse(7, bar, listOf(BarNode("", bar, 0, children = 1), wrapper))
        assertEquals(bar, s.free)
    }

    @Test fun aPieceOfNoSizeTakesNoRoom() {
        val flat = BarNode("flat", Box(500, 0, 900, 0), 1, described = true)
        val narrow = node("narrow", 500, 500, described = true)
        assertEquals(bar, BarRules.parse(7, bar, listOf(BarNode("", bar, 0, children = 2), flat, narrow)).free)
    }

    @Test fun piecesThatOverlapAreOneStretch() {
        val nodes = listOf(BarNode("", bar, 0, children = 3), node("a", 0, 100, clickable = true), node("b", 50, 120, clickable = true), node("c", 1000, 1920, clickable = true))
        assertEquals(Box(120, 0, 1000, 41), BarRules.parse(7, bar, nodes).free)
    }

    @Test fun theClockIsTheFirstNodeThatSaysATime() {
        val nodes = listOf(BarNode("", bar, 0, children = 3), node("a", 0, 90, text = "Sep 27"), node("b", 100, 190, text = "21.05"), node("c", 200, 290, text = "9:19"))
        assertEquals(Box(100, 0, 190, 41), BarRules.parse(7, bar, nodes).clock)
    }

    @Test fun onlyNodesNearTheTopOfTheTreeNameTheBar() {
        val deep = listOf(BarNode("", bar, 0, children = 1), node("deepItem", 10, 90, depth = 7, clickable = true), node("shallowItem", 100, 190, depth = 6, clickable = true))
        assertEquals("shallowItem", BarRules.parse(7, bar, deep).summary)
    }

    @Test fun aWindowWithNoTreeSaysSo() {
        val s = BarRules.parse(7, bar, emptyList())
        assertEquals(BarRules.NO_TREE, s.summary)
        assertEquals(bar, s.free)
        assertNull(s.clock)
    }

    @Test fun aRootWithNoChildrenIsNoTree() {
        // SystemUI mid-change: a root and nothing under it. Read as a bar, all of it would be free, and the strip would cover its icons.
        val root = listOf(BarNode("", bar, 0, children = 0))
        assertEquals(BarRules.NO_TREE, BarRules.parse(7, bar, root).summary)
        // And so the last reading of this bar stands, as for a window with no tree at all.
        val last = BarSnapshot(7, bar, Box(168, 0, 1681, 41), null, "items")
        assertEquals(last, BarRules.settled(BarRules.parse(7, bar, root), last))
    }

    @Test fun aRootWithChildrenIsATreeEvenWithNothingLabelledInIt() {
        // Asking for labelled nodes would leave a bar with no spacer without the widest-gap fallback, and the strip would never show there.
        val unlabelled = listOf(BarNode("", bar, 0, children = 1), node("wrapper", 0, 1920))
        val s = BarRules.parse(7, bar, unlabelled)
        assertEquals("", s.summary)
        assertEquals(bar, s.free)
    }

    @Test fun theBarIsTheSystemWindowAlongTheTop() {
        val system = WindowFact(1, WindowFact.Kind.SYSTEM, bar)
        assertEquals(system, BarRules.findBar(listOf(system), 1920, 45))
        fun finds(f: WindowFact) = BarRules.findBar(listOf(f), 1920, 45)
        assertNull("an app's window at the top is no status bar", finds(WindowFact(1, WindowFact.Kind.APPLICATION, bar)))
        assertNull("not along the top", finds(WindowFact(1, WindowFact.Kind.SYSTEM, Box(0, 1, 1920, 42))))
        assertNull("too tall: it is a panel", finds(WindowFact(1, WindowFact.Kind.SYSTEM, Box(0, 0, 1920, 46))))
        assertNull("no height", finds(WindowFact(1, WindowFact.Kind.SYSTEM, Box(0, 0, 1920, 0))))
        assertNull("too narrow", finds(WindowFact(1, WindowFact.Kind.SYSTEM, Box(0, 0, 959, 41))))
        assertEquals("half the screen is enough", 1, finds(WindowFact(1, WindowFact.Kind.SYSTEM, Box(0, 0, 960, 41)))?.id)
    }

    @Test fun theFirstOfTwoBarsIsTheBar() {
        val first = WindowFact(1, WindowFact.Kind.SYSTEM, bar)
        val second = WindowFact(2, WindowFact.Kind.SYSTEM, bar)
        assertEquals(first, BarRules.findBar(listOf(first, second), 1920, 45))
    }

    @Test fun aBoxTouchingAnotherAtAnEdgeDoesNotIntersectIt() {
        val b = Box(100, 0, 200, 41)
        assertTrue(b.intersects(Box(150, 20, 300, 60)))
        assertEquals(false, b.intersects(Box(200, 0, 300, 41)))
        assertEquals(false, b.intersects(Box(100, 41, 200, 90)))
        // As Rect.intersects: a free area squeezed to no width, inside a wide window, still counts as under it.
        assertTrue(Box(150, 0, 150, 41).intersects(Box(0, 0, 1920, 1200)))
    }
}
