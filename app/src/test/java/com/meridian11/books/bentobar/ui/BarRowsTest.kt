package com.meridian11.books.bentobar.ui

import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.data.BarConfig
import com.meridian11.books.bentobar.data.HiddenMode
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.data.Part
import com.meridian11.books.bentobar.data.Position
import com.meridian11.books.bentobar.data.Section
import com.meridian11.books.bentobar.data.hide
import com.meridian11.books.bentobar.data.without
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The lines of the bar's one list, and the place a removed row keeps open. */
class BarRowsTest {
    private fun item(id: String, area: Position = Position.RIGHT, section: Section = Section.SHOWN, rule: Boolean = false) =
        ItemConfig(id, "cpu", section, whenActive = rule, area = area)
    private val upperRight = Zone(Position.RIGHT, Part.UPPER)
    private val lowerRight = Zone(Position.RIGHT, Part.LOWER)

    /** The list as words: a head, an area's name, the line, a row's id, a slip. */
    private fun words(entries: List<Entry>) = entries.map {
        when (it) {
            is Entry.ListHead -> "head ${it.area}"
            is Entry.AreaHead -> "area ${it.area}" + if (it.first) " first" else ""
            is Entry.PartLine -> "line"
            Entry.SharedLine -> "one line"
            is Entry.Row -> it.item.id + if (it.zone.part == Part.LOWER) " below" else ""
            is Entry.Removed -> "slip ${it.slip.item.id}"
            Entry.Empty -> "empty"
            is Entry.EmptyLane -> "lane ${it.area}"
            is Entry.Blank -> "nothing ${it.area}"
            is Entry.Fold -> "fold ${it.area}"
            is Entry.Gone -> "gone ${it.area} ${it.items}"
            is Entry.NoRoom -> "no room ${it.area}"
            is Entry.Room -> if (it.zone.part == Part.LOWER) "room below" else "room"
        }
    }
    private fun lines(cfg: BarConfig, slip: Slip? = null, dragging: Boolean = false) = words(listEntries(cfg, slip, dragging))
    /** The lanes side by side, as the wide window has them. */
    private fun abreast(cfg: BarConfig, opened: Set<Position> = emptySet(), slip: Slip? = null, fold: Position? = null, noRoom: Set<Position> = emptySet()) =
        words(listEntries(cfg, slip, dragging = false, lanes = lanesOnScreen(cfg, opened), abreast = true, fold = fold, noRoom = noRoom))

    @Test fun withOneAreaTheListIsOneHeadTheRowsAndAlwaysTheLine() {
        val cfg = BarConfig(items = listOf(item("a"), item("w", section = Section.HIDDEN, rule = true), item("b")))
        assertEquals(listOf("head RIGHT", "a", "b", "line", "w below"), lines(cfg))
        assertEquals("the line is there with nothing under it: it is where a row is dropped", listOf("head RIGHT", "a", "line"), lines(BarConfig(items = listOf(item("a")))))
    }

    @Test fun inTheDefaultModeAHiddenItemWithoutARuleIsListedAboveAndWithTheChevronBelow() {
        val cfg = BarConfig(items = listOf(item("a"), item("h", section = Section.HIDDEN)))
        assertEquals(listOf("head RIGHT", "a", "h", "line"), lines(cfg))
        assertEquals(listOf("head RIGHT", "a", "line", "h below"), lines(cfg.copy(hiddenMode = HiddenMode.CLICK)))
    }

    @Test fun withSeveralAreasEachHasItsNameLeftFirstAndALineOnlyWhereSomethingWaits() {
        val cfg = BarConfig(items = listOf(item("r"), item("l", Position.LEFT), item("w", Position.LEFT, Section.HIDDEN, rule = true), item("m", Position.CENTER)))
        assertEquals(listOf("area LEFT first", "l", "line", "w below", "area CENTER", "m", "area RIGHT", "r"), lines(cfg))
        // While a row is carried every area shows where its lower part is, and every part ends in room to drop into.
        assertEquals(listOf("area LEFT first", "l", "room", "line", "w below", "room below", "area CENTER", "m", "room", "line", "room below",
            "area RIGHT", "r", "room", "line", "room below"), lines(cfg, dragging = true))
    }

    @Test fun whileSomethingIsCarriedAPartEndsInRoomUnlessWordsStandThere() {
        val one = BarConfig(items = listOf(item("a"), item("w", section = Section.HIDDEN, rule = true)))
        assertEquals(listOf("head RIGHT", "a", "room", "line", "w below", "room below"), lines(one, dragging = true))
        assertEquals("nothing grows before a pickup", listOf("head RIGHT", "a", "line", "w below"), lines(one))
        // Side by side an opened lane's words and "Nothing here" are the room: nothing is added to them.
        val cfg = BarConfig(items = listOf(item("r"), item("l", Position.LEFT)))
        assertEquals(listOf("area LEFT first", "l", "room", "one line", "nothing LEFT", "area CENTER", "lane CENTER", "nothing CENTER",
            "area RIGHT", "r", "room", "nothing RIGHT"),
            words(listEntries(cfg, null, dragging = true, lanes = lanesOnScreen(cfg, setOf(Position.CENTER)), abreast = true)))
    }

    @Test fun sideBySideTheLanesShareOneLineAndALowerPartWithoutRowsSaysSo() {
        val cfg = BarConfig(items = listOf(item("r"), item("l", Position.LEFT), item("w", Position.LEFT, Section.HIDDEN, rule = true), item("m", Position.CENTER)))
        assertEquals(listOf("area LEFT first", "l", "one line", "w below", "area CENTER", "m", "nothing CENTER", "area RIGHT", "r", "nothing RIGHT"), abreast(cfg))
        // Each line knows its lane; the one line and nothing else runs across them.
        val entries = listEntries(cfg, null, dragging = false, lanes = lanesOnScreen(cfg, emptySet()), abreast = true)
        assertEquals(listOf(Entry.SharedLine), entries.filter { it.lane == null })
        assertEquals(listOf(Position.LEFT, Position.CENTER, Position.RIGHT), entries.mapNotNull { it.lane }.distinct())
        // One lane is the one list, whatever the window: nothing of the lanes is in it.
        val one = BarConfig(items = listOf(item("a"), item("w", section = Section.HIDDEN, rule = true)))
        assertEquals(lines(one), abreast(one))
    }

    @Test fun anOpenedLaneSaysWhatItIsForInBothFormsAndIsGoneWithItsFirstRow() {
        val cfg = BarConfig(items = listOf(item("a"), item("b")))
        assertEquals(listOf("area LEFT first", "lane LEFT", "one line", "nothing LEFT", "area RIGHT", "a", "b", "nothing RIGHT"), abreast(cfg, setOf(Position.LEFT)))
        // In one column the empty lane has no lower part to show.
        assertEquals(listOf("area LEFT first", "lane LEFT", "area RIGHT", "a", "b"),
            words(listEntries(cfg, null, dragging = false, lanes = lanesOnScreen(cfg, setOf(Position.LEFT)))))
        val used = BarConfig(items = listOf(item("a"), item("b", Position.LEFT)))
        assertEquals(listOf("area LEFT first", "b", "one line", "nothing LEFT", "area RIGHT", "a", "nothing RIGHT"), abreast(used, setOf(Position.LEFT)))
    }

    @Test fun aClosedLaneIsSaidFirstAndAnAreaWithoutRoomSaysSoUnderItsHead() {
        val two = BarConfig(items = listOf(item("l", Position.LEFT), item("r")))
        assertEquals(listOf("fold CENTER", "area LEFT first", "l", "one line", "nothing LEFT", "area RIGHT", "r", "nothing RIGHT"), abreast(two, fold = Position.CENTER))
        assertEquals(listOf("area LEFT first", "no room LEFT", "l", "one line", "nothing LEFT", "area RIGHT", "r", "nothing RIGHT"), abreast(two, noRoom = setOf(Position.LEFT)))
        // The last lane but one closed: the one list again, with what closed above it.
        val one = BarConfig(items = listOf(item("r")))
        assertEquals(listOf("fold LEFT", "head RIGHT", "r", "line"), abreast(one, fold = Position.LEFT))
        assertEquals("with one lane nothing is said of room", listOf("head RIGHT", "r", "line"), abreast(one, noRoom = setOf(Position.RIGHT)))
    }

    @Test fun aRemovedAreaIsSaidFirstTooAlsoOverTheLanesThatStayAndOverAnEmptyBar() {
        val one = BarConfig(items = listOf(item("r")))
        assertEquals(listOf("gone LEFT 3", "head RIGHT", "r", "line"), words(listEntries(one, null, dragging = false, gone = Gone(Position.LEFT, 3))))
        val two = BarConfig(items = listOf(item("m", Position.CENTER), item("r")))
        assertEquals(listOf("gone LEFT 1", "area CENTER first", "m", "one line", "nothing CENTER", "area RIGHT", "r", "nothing RIGHT"),
            words(listEntries(two, null, dragging = false, lanes = lanesOnScreen(two, emptySet()), abreast = true, gone = Gone(Position.LEFT, 1))))
        assertEquals("the bar's last items went with it", listOf("gone LEFT 2", "head LEFT", "empty"),
            words(listEntries(BarConfig(position = Position.LEFT), null, dragging = false, gone = Gone(Position.LEFT, 2))))
    }

    @Test fun anEmptyBarSaysSoWhereItStands() {
        assertEquals(listOf("head LEFT", "empty"), lines(BarConfig(position = Position.LEFT)))
        assertEquals(listOf("head RIGHT", "empty"), lines(BarConfig(position = Position.RIGHT)))
    }

    @Test fun aRowsKeyIsItsIdWhereverItStandsAndNoTwoLinesShareAKey() {
        val cfg = BarConfig(items = listOf(item("r"), item("l", Position.LEFT), item("w", Position.LEFT, Section.HIDDEN, rule = true)))
        val keys = listEntries(cfg, null, dragging = true).map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        assertEquals(Entry.Row(cfg.items[0], upperRight).key, Entry.Row(cfg.items[0], Zone(Position.LEFT, Part.LOWER)).key)
    }

    // ---- The slip ----

    private val three = BarConfig(items = listOf(item("a"), item("b"), item("c")))
    private fun removal(before: BarConfig, id: String) = Undo.Kept(before, before.without(id), removed = id)

    @Test fun aRemovedRowsPlaceStaysOpenWhereItStood() {
        val kept = removal(three, "b")
        assertEquals(Slip(three.items[1], upperRight, 1), slipOf(kept, kept.after))
        assertEquals(listOf("head RIGHT", "a", "slip b", "c", "line"), lines(kept.after, slipOf(kept, kept.after)))
        val last = removal(three, "c")
        assertEquals(listOf("head RIGHT", "a", "b", "slip c", "line"), lines(last.after, slipOf(last, last.after)))
    }

    @Test fun theSlipOfTheLowerPartStandsUnderTheLine() {
        val cfg = BarConfig(items = listOf(item("a"), item("w", section = Section.HIDDEN, rule = true)))
        val kept = removal(cfg, "w")
        assertEquals(lowerRight, slipOf(kept, kept.after)?.zone)
        assertEquals(listOf("head RIGHT", "a", "line", "slip w"), lines(kept.after, slipOf(kept, kept.after)))
    }

    @Test fun theLastItemsSlipKeepsItsAreasHeadAndTheBarIsNotCalledEmpty() {
        val one = BarConfig(items = listOf(item("a", Position.LEFT)))
        val kept = removal(one, "a")
        assertEquals(listOf("head LEFT", "slip a", "line"), lines(kept.after, slipOf(kept, kept.after)))
    }

    @Test fun theOnlyItemOfOneOfTwoAreasClosesItsLaneAndThePageSaysThatInsteadOfTheRowsSlip() {
        val two = BarConfig(items = listOf(item("l", Position.LEFT), item("r")))
        val gone = removal(two, "l")
        assertEquals(Position.LEFT, foldOf(gone, gone.after, several = true))
        // What the page shows: no slip in a lane that is gone, the closing said above the one list.
        assertEquals(listOf("fold LEFT", "head RIGHT", "r", "line"), abreast(gone.after, fold = Position.LEFT))
    }

    @Test fun theSlipClosesWithTheNextChangeToTheArrangementAndIsNoneForAChangeThatRemovedNothing() {
        val kept = removal(three, "b")
        assertNull(slipOf(kept, kept.after.hide("a")))
        assertNull(slipOf(Undo.Kept(three, three.hide("a")), three.hide("a")))
        assertNull(slipOf(null, three))
        // An option changed since is no change to the arrangement: Undo is still offered.
        val optioned = kept.after.copy(items = kept.after.items.map { if (it.id == "a") it.copy(options = mapOf("k" to "v")) else it })
        assertEquals("b", slipOf(kept, optioned)?.item?.id)
    }

    // ---- Where an area is, in words ----

    @Test fun whereAnAreaIsFollowsTheSideTheClockIsDrawnOn() {
        fun words(mirrored: Boolean) = listOf(Position.LEFT, Position.CENTER, Position.RIGHT).map { whereRes(it, mirrored) }
        assertEquals(listOf(R.string.areas_left_where, R.string.areas_middle_where, R.string.areas_right_where), words(mirrored = false))
        // Mirrored, the clock is at the right and the system's icons at the left; Left is still the left end of the free space.
        assertEquals("Left is next to the system icons, Right after the clock",
            listOf(R.string.areas_right_where, R.string.areas_middle_where, R.string.areas_left_where), words(mirrored = true))
        assertEquals(false, barMirrored(androidx.compose.ui.unit.LayoutDirection.Ltr))
        assertEquals(true, barMirrored(androidx.compose.ui.unit.LayoutDirection.Rtl))
    }

    // ---- Order's words ----

    @Test fun ordinalsAreEnglishOnesWithTheTeensRight() {
        fun ends(vararg n: Int) = n.map { ordinalRes(it) }.toSet()
        assertEquals(setOf(R.string.order_st), ends(1, 21, 101))
        assertEquals(setOf(R.string.order_nd), ends(2, 22))
        assertEquals(setOf(R.string.order_rd), ends(3, 23))
        assertEquals(setOf(R.string.order_th), ends(4, 10, 11, 12, 13, 20, 111, 112, 113))
    }
}
