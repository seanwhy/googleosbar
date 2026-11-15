package com.meridian11.books.bentobar.bar

import com.meridian11.books.bentobar.data.Display
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.data.Pill
import com.meridian11.books.bentobar.data.Position
import com.meridian11.books.bentobar.items.BarRoute
import com.meridian11.books.bentobar.items.BarSlider
import com.meridian11.books.bentobar.items.ItemState
import com.meridian11.books.bentobar.items.Tone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Whether the strip has anything on the screen: the one rule behind an empty or blank strip's invisible window. It is
 * never decided from how many entries there are: an entry can draw nothing (findings F08 and F25).
 */
class StripContentTest {
    private fun draws(type: String = "text", display: Display = Display.ICON_AND_TEXT, state: ItemState) = StripContent.draws(type, display, state)
    private fun entry(id: String, state: ItemState, type: String = "text", display: Display = Display.ICON_AND_TEXT) =
        StripEntry(ItemConfig(id, type, display = display), state)
    private val blank = ItemState(icon = "", text = null)

    // ---- what an entry draws --------------------------------------------------------------------------

    /** A spacer is its gap, and draws only its line. */
    @Test fun aSpacerDrawsOnlyItsLine() {
        assertFalse(draws("spacer", state = ItemState(gapDp = 24, divider = false)))
        assertTrue(draws("spacer", state = ItemState(gapDp = 24, divider = true)))
    }

    /** A text item with no icon chosen, shown as an icon, has a place in the row and nothing in it. */
    @Test fun aTextItemShownAsTheIconItDoesNotHaveDrawsNothing() {
        assertFalse(draws(display = Display.ICON, state = ItemState(icon = "", text = "hello")))
        assertFalse(draws(display = Display.ICON, state = ItemState(icon = null, text = "hello")))
    }

    @Test fun anIconOrAnImageOrADayNumberIsDrawn() {
        assertTrue(draws(display = Display.ICON, state = ItemState(icon = "star")))
        assertTrue(draws(display = Display.ICON, state = ItemState(dayNumber = 9)))
    }

    /** Words are drawn, in any way that shows text; words made of nothing but spaces are not. */
    @Test fun blankTextIsNothing() {
        assertTrue(draws(display = Display.TEXT, state = ItemState(icon = "", text = "hello")))
        assertTrue(draws(display = Display.ICON_AND_TEXT, state = ItemState(icon = "", text = "hello")))
        assertFalse(draws(display = Display.TEXT, state = ItemState(icon = "", text = "   ")))
        assertFalse(draws(display = Display.TEXT, state = ItemState(icon = "", text = "")))
    }

    /** Shown as text alone an icon is not drawn, unless there are no words to show instead. */
    @Test fun anIconIsDrawnOnlyWhereItsItemShowsOne() {
        assertFalse(draws(display = Display.TEXT, state = ItemState(icon = "star", text = "   ")))
        assertTrue(draws(display = Display.TEXT, state = ItemState(icon = "star", text = "")))
    }

    @Test fun aSliderOrARouteLineIsDrawnWhereTheItemShowsOne() {
        val slider = ItemState(icon = "", slider = BarSlider(0.5f))
        assertTrue(draws(display = Display.TEXT, state = slider))
        assertFalse("shown as an icon only, it has none", draws(display = Display.ICON, state = slider))
        val route = ItemState(icon = "", route = BarRoute(0.3f))
        assertTrue(draws(display = Display.ICON, state = route))
        assertFalse("shown as text only, it has none", draws(display = Display.TEXT, state = route))
    }

    /** An alert's red capsule is drawn around the item, whatever is in it. */
    @Test fun anAlertsCapsuleIsDrawnWithNothingInIt() {
        assertTrue(draws(display = Display.ICON, state = ItemState(icon = "", tone = Tone.ALERT)))
    }

    // ---- what the strip draws -------------------------------------------------------------------------

    /** Finding F25: a transparent window for items that draw nothing is what Play Protect flags. */
    @Test fun aStripOfOnlyBlankItemsDrawsNothingHoweverManyThereAre() {
        val spacers = List(5) { entry("s$it", ItemState(gapDp = 16), type = "spacer") }
        assertFalse(StripContent.drawn(spacers, chevron = false, pill = Pill.NONE, home = true))
        assertFalse(StripContent.drawn(listOf(entry("t", blank, display = Display.ICON)), chevron = false, pill = Pill.NONE, home = true))
    }

    @Test fun oneThingDrawnIsEnough() {
        val blanks = listOf(entry("s", ItemState(gapDp = 16), type = "spacer"))
        assertTrue(StripContent.drawn(blanks + entry("t", ItemState(icon = "star"), display = Display.ICON), chevron = false, pill = Pill.NONE, home = true))
    }

    @Test fun nothingListedMeansNothingDrawn() {
        assertFalse(StripContent.drawn(emptyList(), chevron = false, pill = Pill.NONE, home = true))
    }

    @Test fun theChevronIsDrawn() {
        assertTrue(StripContent.drawn(emptyList(), chevron = true, pill = Pill.NONE, home = true))
    }

    /** With a pill, the strip's own padding keeps it on the screen as an empty capsule: the behaviour it has always had. */
    @Test fun aPillIsDrawnWithNothingInIt() {
        assertTrue(StripContent.drawn(emptyList(), chevron = false, pill = Pill.SUBTLE, home = true))
        assertTrue(StripContent.drawn(emptyList(), chevron = false, pill = Pill.SOLID, home = true))
    }

    /** The empty capsule is the bar's "I am here": one for the bar, in its home area's strip, and never a second one beside it. */
    @Test fun aPillWithNothingInItIsDrawnOnlyInTheHomeAreasStrip() {
        val blanks = listOf(entry("s", ItemState(gapDp = 16), type = "spacer"), entry("t", blank, display = Display.ICON))
        for (pill in listOf(Pill.SUBTLE, Pill.SOLID)) {
            assertFalse("$pill", StripContent.drawn(emptyList(), chevron = false, pill = pill, home = false))
            assertFalse("$pill around items that draw nothing", StripContent.drawn(blanks, chevron = false, pill = pill, home = false))
            assertTrue("$pill around items that draw nothing, at home", StripContent.drawn(blanks, chevron = false, pill = pill, home = true))
        }
        assertFalse("no pill, no capsule, at home or not", StripContent.drawn(emptyList(), chevron = false, pill = Pill.NONE, home = true))
    }

    /**
     * The home area is looked for Left, Middle, Right, room goes Left, Right, Middle: a Middle at home can have no room
     * while Right has a strip. The capsule is then the first strip's, so a bar with nothing to show still shows one.
     */
    @Test fun theEmptyCapsuleGoesToTheFirstStripWhenTheHomeAreaHasNoWindow() {
        assertEquals(Position.RIGHT, StripContent.capsuleArea(home = Position.CENTER, carrier = Position.RIGHT, owed = setOf(Position.CENTER)))
        assertEquals(Position.CENTER, StripContent.capsuleArea(home = Position.CENTER, carrier = Position.RIGHT, owed = emptySet()))
        assertEquals(Position.LEFT, StripContent.capsuleArea(home = Position.LEFT, carrier = Position.LEFT, owed = setOf(Position.RIGHT)))
        // The one-strip bar with no room for a ‹ places the home area alone: it is no owed area, and keeps the capsule.
        assertEquals(Position.CENTER, StripContent.capsuleArea(home = Position.CENTER, carrier = Position.CENTER, owed = setOf(Position.RIGHT)))
    }

    /** The strip asks [StripContent.capsuleArea], never the home area alone. */
    @Test fun theStripAsksWhereTheCapsuleGoes() {
        val host = File("src/main/java/com/meridian11/books/bentobar/bar/BarService.kt").readText()
        assertTrue("home = strip.area == StripContent.capsuleArea(cfg.homeArea(), carrier, owed.value)" in host)
    }

    /** What a strip does draw, it draws in any area: only the empty capsule asks where home is. */
    @Test fun anItemOrAChevronIsDrawnWhetherTheStripIsTheHomeAreasOrNot() {
        val star = listOf(entry("t", ItemState(icon = "star"), display = Display.ICON))
        for (pill in Pill.entries) for (home in listOf(false, true)) {
            assertTrue("$pill home=$home", StripContent.drawn(star, chevron = false, pill = pill, home = home))
            assertTrue("$pill home=$home", StripContent.drawn(emptyList(), chevron = true, pill = pill, home = home))
        }
    }

    // ---- what it comes to -----------------------------------------------------------------------------

    @Test fun nothingDrawnMeansEmptyWhateverTheWidth() {
        assertTrue(StripContent.empty(width = 120, drawn = false))
        assertTrue(StripContent.empty(width = 0, drawn = false))
        assertFalse(StripContent.empty(width = 120, drawn = true))
        assertTrue("no width at all has nothing to show", StripContent.empty(width = 0, drawn = true))
    }

    /** Finding F59: an empty strip is not "live" (it can have room or not: either way there is nothing to see). */
    @Test fun theStatusSaysEmptyBeforeItSaysAnythingElse() {
        assertEquals(BarStatus.EMPTY, StripContent.status(refused = false, empty = true, noRoom = true))
        assertEquals(BarStatus.EMPTY, StripContent.status(refused = false, empty = true, noRoom = false))
        assertEquals(BarStatus.NO_ROOM, StripContent.status(refused = false, empty = false, noRoom = true))
        assertEquals(BarStatus.SHOWN, StripContent.status(refused = false, empty = false, noRoom = false))
    }

    /** A window Android refused comes first: Settings never says "live", "empty" or "no room" of a bar that isn't all there. */
    @Test fun aRefusedWindowIsSaidBeforeAnythingAStripShows() {
        for (empty in listOf(true, false)) for (noRoom in listOf(true, false))
            assertEquals(BarStatus.STOPPED, StripContent.status(refused = true, empty = empty, noRoom = noRoom))
    }

    // ---- what only reading the code can show ------------------------------------------------------------

    private val ui = File("src/main/java/com/meridian11/books/bentobar/bar/BarUi.kt").readText()
    private val code = File("src/main/java/com/meridian11/books/bentobar/bar/BarService.kt").readText()

    /**
     * The whole risk of F25 is these two disagreeing: something ItemView draws that the rule doesn't know is a strip stuck
     * invisible, and the reverse is a see-through window. So each clause has one definition, in StripContent, and both use it.
     */
    @Test fun theStripAndTheRuleAgreeOnWhatAnItemDraws() {
        val view = ui.substringAfter("private fun ItemView(").substringBefore("private fun VolumeTrack(")
        val rule = File("src/main/java/com/meridian11/books/bentobar/bar/StripContent.kt").readText()
            .substringAfter("fun draws(").substringBefore("/** Whether the strip draws anything")
        assertFalse("the rule asks the definitions, and reads no fact of the item itself: $rule", Regex("""state\.\w""").containsMatchIn(rule))

        // The line of a spacer.
        assertTrue(rule, "line(state)" in rule)
        assertTrue(view, Regex("""entry\.item\.type == [^\n]*SpacerItem\.TYPE\) \{\s*Box\([^\n]*\{\s*if \(StripContent\.line\(s\)\) Box\(""").containsMatchIn(view))
        assertFalse("ItemView works out no line of its own", "s.divider" in view)

        // An alert's capsule.
        assertTrue(rule, "capsule(state)" in rule)
        assertTrue(view, "val alert = StripContent.capsule(s)" in view && "alert -> capsule(look.alertBg" in view)
        assertFalse("ItemView works out no capsule of its own", "s.tone == Tone.ALERT" in view)

        // The icon: nothing of it is drawn but behind the rule's own answer, whichever of the three it turns out to be.
        assertTrue(rule, "parts.iconDrawn" in rule)
        assertTrue(view, "val parts = StripContent.parts(s, entry.item.display)" in view)
        val icon = view.substringAfter("if (parts.iconDrawn) {", "").substringBefore("if (parts.gapAfterIcon)")
        for (drawn in listOf("""\bImage\(""", """\bDayBadge\(""", """\bSymIcon\(""")) {
            assertEquals(drawn, 1, Regex(drawn).findAll(view).count())
            assertEquals(drawn, 1, Regex(drawn).findAll(icon).count())
        }

        // The words: laid out where the rule says, and drawn unless they are nothing but spaces, which keep their place.
        assertTrue(rule, "parts.textDrawn" in rule)
        assertTrue(view, Regex("""val showText = parts\.text\b""").containsMatchIn(view))
        assertEquals(1, Regex("""\bText\(""").findAll(view).count())
        assertTrue(view, "Text(s.text!!" in view.substringAfter("if (showText) {", ""))
        val spaces = StripContent.parts(ItemState(icon = "", text = "   "), Display.TEXT)
        assertTrue(spaces.text)
        assertFalse(spaces.textDrawn)
    }

    /** Finding F08: while invisible nothing draws, and Compose's remeasure waits for a draw, so it is asked for by hand. */
    @Test fun aStripMadeInvisibleStillMeasuresWhatItsCompositionChanged() {
        val host = code.substringAfter("private fun StripHost(").substringBefore("private var stripEmpty")
        assertTrue(host, Regex("""SideEffect \{ if \(strip\.empty\) view\.requestLayout\(\) \}""").containsMatchIn(host))
        assertTrue(host, "StripContent.empty(w, drawn)" in host)
    }

    /** One rule, and the window is a single call: the exact-width invisible window stays. */
    @Test fun theStripGoesInvisibleOnlyThroughTheOneRule() {
        assertFalse("nothing asks how many entries there are", Regex("""\.isEmpty\(\)\s*\)?\s*strip""").containsMatchIn(code))
        val apply = code.substringAfter("private fun applyWidth(").substringBefore("private var dragFrom")
        assertTrue(apply, "strip.setContentVisible(!empty)" in apply)
        assertEquals(1, Regex("""setContentVisible""").findAll(code).count())
    }

    @Test fun debugDumpSaysWhetherTheStripIsEmpty() {
        assertTrue("empty=\$stripEmpty" in code)
    }
}

/**
 * The parts of an item for every combination that matters: icon or not, words or not, a slider, a route line, and how the item is set
 * to show. One table, because a third thing an item can draw in place of its text or icon must agree on all of them at once.
 */
class StripItemPartsTest {
    private data class Case(val icon: Boolean, val text: Boolean, val slider: Boolean, val route: Boolean, val display: Display)

    @Test fun everyCombinationOfAnItemsPartsLaysOutAsItMust() {
        val flags = listOf(false, true)
        for (icon in flags) for (text in flags) for (slider in flags) for (route in flags) for (display in Display.entries) {
            val case = Case(icon, text, slider, route, display)
            val s = ItemState(icon = if (icon) "I" else "", text = if (text) "words" else null,
                slider = if (slider) BarSlider(0.5f, 15) else null, route = if (route) BarRoute(0.4f) else null)
            val p = StripContent.parts(s, display)
            // A slider takes the words' place, except for an item shown as its icon alone; a route line takes the icon's, except text alone.
            assertEquals("$case slider", slider && display != Display.ICON, p.slider != null)
            assertEquals("$case route", route && display != Display.TEXT, p.route != null)
            assertEquals("$case the route line replaces the icon", p.route == null && (display != Display.TEXT || !text), p.icon)
            assertEquals("$case the slider replaces the words", p.slider == null && display != Display.ICON && text, p.text)
            assertEquals("$case gap after the route line", p.route != null && (p.text || p.slider != null), p.gapAfterRoute)
            assertEquals("$case gap after the icon", p.icon && icon && (p.text || p.slider != null), p.gapAfterIcon)
            assertEquals("$case the words keep to the left of what comes first", p.route != null || (p.icon && icon), p.leadInBeforeText)
        }
    }
}
