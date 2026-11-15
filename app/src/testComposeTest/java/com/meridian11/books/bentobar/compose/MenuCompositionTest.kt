package com.meridian11.books.bentobar.compose

import androidx.compose.ui.test.onNodeWithTag
import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.performClick
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.data.BarConfig
import com.meridian11.books.bentobar.data.Display
import com.meridian11.books.bentobar.data.HiddenMode
import com.meridian11.books.bentobar.data.Position
import com.meridian11.books.bentobar.data.fresh
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.meridian11.books.bentobar.bar.ItemContextMenu
import com.meridian11.books.bentobar.bar.ItemMenu
import com.meridian11.books.bentobar.bar.BentoBarMenu
import com.meridian11.books.bentobar.bar.MenuSurface
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.data.Section
import com.meridian11.books.bentobar.data.Store
import com.meridian11.books.bentobar.items.Env
import com.meridian11.books.bentobar.items.ItemType
import com.meridian11.books.bentobar.items.Items
import com.meridian11.books.bentobar.items.MenuHost
import com.meridian11.books.bentobar.items.SoundItem
import com.meridian11.books.bentobar.items.StocksItem
import com.meridian11.books.bentobar.ui.Undo
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Compose has no error boundary, and a menu runs in the accessibility service's own process: one exception while a menu composes
 * ends the service, the wake lock and any load in flight (finding F105). So every registered item type's menu is composed here,
 * under each state its debug hook can stage ([ItemType.stages]), in the real [MenuSurface]. A new item is covered by being in
 * [Items.all], a new staged state by being named in its type's `stages`. A stage that its hook refuses, or that leaves the menu
 * drawing what it drew before, fails here: a menu never brought to a state can't be shown not to throw in it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37])
class MenuCompositionTest {
    @get:Rule val rule = createComposeRule()

    /** What is composed. Null between cases: a menu that goes can end what it staged (Flight's choice of flights), so it goes before the next is staged. */
    private var shown by mutableStateOf<Case?>(null)

    /** The \u2039 menu's `everything` flag while it is composed; null otherwise. */
    private var barMenu by mutableStateOf<Boolean?>(null)

    private object Host : MenuHost {
        override fun close() {}
        override fun openItemSettings(id: String) {}
        override fun afterClose(action: () -> Unit) {}
    }

    private data class Case(val type: ItemType, val item: ItemConfig, val stage: String?, val context: Boolean = false) {
        override fun toString() = (if (context) "the right-click menu of " else "the menu of ") + type.type + (stage?.let { " under `$it`" } ?: "")
    }

    /** One item of every type, as a layout has them: a staged sample is shown by its type's first item. */
    private fun layout(): List<ItemConfig> {
        Env.init(ApplicationProvider.getApplicationContext<Application>())
        val items = Items.all.map { ItemConfig("compose-${it.type}", it.type, Section.SHOWN, options = it.defaultOptions() + it.stageOptions) }
        Store.update { it.copy(items = items) }
        return items
    }

    @Test fun everyItemMenuComposesUnderEveryStagedState() {
        val items = layout()
        rule.setContent {
            val c = shown
            barMenu?.let { all -> MenuSurface(300.dp, 2000.dp, null) { BentoBarMenu(Host, openItem = {}, hideBar = {}, everything = all) } }
            if (c != null) {
                MenuSurface(c.type.menuWidthDp.dp, 2000.dp, null) {
                    if (c.context) ItemContextMenu(c.item.id, Host) {} else ItemMenu(c.item.id, Host)
                }
            }
        }
        var composed = 0
        for (item in items) {
            val type = Items.of(item.type)!!
            var before = ""
            for (stage in listOf<String?>(null) + type.stages) {
                val case = Case(type, item, stage)
                compose(case)
                val now = drawn()
                if (stage != null) assertNotEquals("$case draws what the state before it drew, so it never reached its own", before, now)
                before = now
                composed++
            }
            compose(Case(type, item, null, context = true))
            type.debug(listOf("off"))
        }
        // The \u2039 menu, the one the shortcut opens, lists every item's row: a throw in any row ends the service too.
        for (everything in listOf(false, true)) {
            rule.runOnUiThread { shown = null; barMenu = everything }
            rule.waitForIdle()
            composed++
        }
        assertTrue("composed only $composed cases", composed > 100)
    }

    @Test fun aHookRefusesAStateItDoesNotKnowAndStagesNothing() {
        layout()
        for (type in Items.all) for (word in type.stages.map { it.substringBefore(' ') }.distinct())
            assertNull("${type.type} $word: a state it doesn't know", type.debug(listOf(word, "no-such-state")))
        assertNull("sound fixed: a state it doesn't know", SoundItem.debug(listOf("fixed", "no-such-state")))
    }

    @Test fun aStocksStageWithNoStocksToShowIsRefused() {
        layout()
        Store.update { c -> c.copy(items = c.items.map { if (it.type == StocksItem.type) it.copy(options = emptyMap()) else it }) }
        assertNull("no stocks to draw rows for", StocksItem.debug(listOf("stage", "up")))
        assertNotNull("this one needs no stocks", StocksItem.debug(listOf("stage", "none")))
        StocksItem.debug(listOf("off"))
    }

    // ---- The right-click menu's words and entries: Visibility's, per hidden mode, where the item stands and its type ----

    private fun str(id: Int) = Env.str(id)

    @After fun forget() { Store.update { BarConfig() } }

    /** The right-click menu of one [item] under [mode], and its lines. */
    private fun contextMenu(item: ItemConfig, mode: HiddenMode, others: List<ItemConfig> = emptyList()): List<String> {
        rule.runOnUiThread { shown = null }
        rule.waitForIdle()
        Store.update { it.copy(hiddenMode = mode, items = listOf(item) + others) }
        rule.runOnUiThread { shown = Case(Items.of(item.type)!!, item, null, context = true) }
        rule.waitForIdle()
        return drawn().lines()
    }

    private fun entry(label: String) = rule.onNode(hasText(label) and (hasClickAction() or isToggleable()))
    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertIsOnOrOff(on: Boolean) = if (on) assertIsOn() else assertIsOff()
    private fun stored(id: String) = Store.config.value.items.firstOrNull { it.id == id }

    private fun contextHost() {
        Env.init(ApplicationProvider.getApplicationContext<Application>())
        rule.setContent {
            view = androidx.compose.ui.platform.LocalView.current
            val c = shown
            if (c != null) MenuSurface(c.type.menuWidthDp.dp, 2000.dp, null) { ItemContextMenu(c.item.id, Host) {} }
        }
    }

    private var view: android.view.View? = null

    /** With `BENTOBAR_PICTURES=<directory>`, the menu as drawn, to look at beside its design; else nothing. */
    private fun picture(name: String) {
        val dir = System.getenv("BENTOBAR_PICTURES")?.takeIf { it.isNotBlank() } ?: return
        val v = view ?: return
        val bitmap = rule.runOnIdle { android.graphics.Bitmap.createBitmap(v.width, v.height, android.graphics.Bitmap.Config.ARGB_8888).also { v.draw(android.graphics.Canvas(it)) } }
        java.io.File(dir).mkdirs()
        java.io.File(dir, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun theRightClickMenuSaysWhatVisibilitySaysInEveryModeAndNeverOffOrHidden() {
        contextHost()
        val always = str(R.string.visibility_always); val whenActive = str(R.string.visibility_when_active)
        val behind = str(R.string.visibility_behind); val popsOut = str(R.string.context_pops_out)
        val inBar = str(R.string.context_always_in_bar); val remove = str(R.string.detail_remove)
        // (type, section, rule on) -> subtitle and the visibility entries, for the default mode and for ‹.
        val rule = "devices"; val plain = "calendar"
        assertTrue(Items.of(rule)!!.canBeActive); assertFalse(Items.of(plain)!!.canBeActive)
        data class Want(val type: String, val section: Section, val on: Boolean, val mode: HiddenMode, val subtitle: String, val has: List<String>, val hasNot: List<String>)
        val cases = listOf(
            Want(rule, Section.SHOWN, false, HiddenMode.SHOW_ALL, inBar, listOf(whenActive), listOf(always, behind, popsOut)),
            Want(rule, Section.HIDDEN, true, HiddenMode.SHOW_ALL, whenActive, listOf(whenActive), listOf(always, behind, popsOut, inBar)),
            // Hidden without a rule is drawn all the time in the default mode: always in the bar, the tick off.
            Want(rule, Section.HIDDEN, false, HiddenMode.SHOW_ALL, inBar, listOf(whenActive), listOf(always, behind, popsOut)),
            Want(rule, Section.SHOWN, true, HiddenMode.SHOW_ALL, inBar, listOf(whenActive), listOf(always, behind, popsOut)),
            Want(plain, Section.SHOWN, false, HiddenMode.SHOW_ALL, inBar, emptyList(), listOf(always, whenActive, behind, popsOut)),
            Want(plain, Section.HIDDEN, false, HiddenMode.SHOW_ALL, inBar, emptyList(), listOf(always, whenActive, behind, popsOut)),
        ) + listOf(HiddenMode.CLICK, HiddenMode.HOVER).flatMap { mode -> listOf(
            Want(rule, Section.SHOWN, false, mode, inBar, listOf(behind), listOf(always, whenActive, popsOut)),
            Want(rule, Section.HIDDEN, false, mode, behind, listOf(always, popsOut), listOf(whenActive, inBar)),
            Want(rule, Section.HIDDEN, true, mode, behind, listOf(always, popsOut), listOf(whenActive, inBar)),
            Want(plain, Section.SHOWN, false, mode, inBar, listOf(behind), listOf(always, whenActive, popsOut)),
            Want(plain, Section.HIDDEN, false, mode, behind, listOf(always), listOf(whenActive, popsOut, inBar)),
        ) }
        for (w in cases) {
            val type = Items.of(w.type)!!
            val lines = contextMenu(ItemConfig("x", w.type, w.section, whenActive = w.on), w.mode)
            val said = "$w drew $lines"
            if (w.type == rule && w.section == Section.HIDDEN && w.on && w.mode != HiddenMode.HOVER) picture(if (w.mode == HiddenMode.CLICK) "menu-behind" else "menu-when-active")
            assertEquals(said, listOf(type.title, w.subtitle), lines.take(2))
            val entries = lines.drop(2)
            for (t in w.has) assertTrue("$said: no \"$t\"", t in entries)
            for (t in w.hasNot) assertFalse("$said: \"$t\" is there", t in entries)
            assertEquals(said, remove, entries.last())
            // The tick is the entry's state, as a reader is told it: on exactly while the item waits on its rule.
            if (w.mode == HiddenMode.SHOW_ALL && type.canBeActive) entry(whenActive).assertIsOnOrOff(w.section == Section.HIDDEN && w.on)
            if (popsOut in w.has) entry(popsOut).assertIsOnOrOff(w.on)
            assertTrue(said, str(R.string.context_item_settings) in entries)
            for (old in listOf("Off", "Hidden", "Turn off", "Delete")) assertFalse("$said: it says \"$old\"", lines.any { Regex("\\b$old\\b").containsMatchIn(it) })
        }
        // ‹ is read out by name.
        val spoken = behind.replace("‹", str(R.string.chevron_spoken))
        assertTrue(spoken in contextMenu(ItemConfig("x", plain), HiddenMode.CLICK))
    }

    @Test fun theRightClickMenusEntriesAreTheSettingsCommands() {
        contextHost()
        val devices = ItemConfig("x", "devices", Section.SHOWN)
        // The default mode: one tick, on is the rule, off is Always.
        contextMenu(devices, HiddenMode.SHOW_ALL)
        entry(str(R.string.visibility_when_active)).performClick()
        rule.waitForIdle()
        assertEquals(devices.copy(section = Section.HIDDEN, whenActive = true), stored("x"))
        entry(str(R.string.visibility_when_active)).performClick()
        rule.waitForIdle()
        assertEquals(Section.SHOWN, stored("x")!!.section)
        // With ‹: Behind ‹ hides, Always shows, and the rule is the pop-out switch of an item that is behind.
        contextMenu(devices, HiddenMode.CLICK)
        entry(str(R.string.visibility_behind)).performClick()
        rule.waitForIdle()
        assertEquals(devices.copy(section = Section.HIDDEN), stored("x"))
        entry(str(R.string.context_pops_out)).performClick()
        rule.waitForIdle()
        assertEquals(devices.copy(section = Section.HIDDEN, whenActive = true), stored("x"))
        entry(str(R.string.context_pops_out)).performClick()
        rule.waitForIdle()
        assertEquals(devices.copy(section = Section.HIDDEN), stored("x"))
        entry(str(R.string.visibility_always)).performClick()
        rule.waitForIdle()
        assertEquals(devices, stored("x"))
    }

    @Test fun removeFromTheBarInTheRightClickMenuIsTheSettingsStep() {
        contextHost()
        val other = ItemConfig("other", "clock")
        // As the + made it: it leaves the layout.
        val fresh = Items.of("devices")!!.fresh("x", Position.RIGHT)
        contextMenu(fresh, HiddenMode.SHOW_ALL, listOf(other))
        entry(str(R.string.detail_remove)).performClick()
        rule.waitForIdle()
        assertNull(stored("x"))
        // Set up, it leaves the layout the same: nothing of it waits anywhere, and the bar's menu leaves no Undo.
        val setUp = fresh.copy(display = Display.ICON).with("some-option", "1")
        contextMenu(setUp, HiddenMode.SHOW_ALL, listOf(other))
        entry(str(R.string.detail_remove)).performClick()
        rule.waitForIdle()
        assertEquals(listOf(other), Store.config.value.items)
        assertNull(Undo.last.value)
    }

    /** The head's symbol stands on the card itself, as the design draws it: no tinted box around it. */
    @Test fun theRightClickMenusHeadHasItsSymbolOnTheCardsOwnGround() {
        contextHost()
        contextMenu(ItemConfig("x", "devices", Section.SHOWN), HiddenMode.SHOW_ALL)
        val box = rule.onNodeWithTag("menu-symbol", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val v = view!!
        val bitmap = rule.runOnIdle { android.graphics.Bitmap.createBitmap(v.width, v.height, android.graphics.Bitmap.Config.ARGB_8888).also { v.draw(android.graphics.Canvas(it)) } }
        val inside = bitmap.getPixel((box.left + 3).toInt(), box.center.y.toInt())
        val beside = bitmap.getPixel((box.left - 3).toInt(), box.center.y.toInt())
        assertEquals("the ground at the symbol's edge is the card's", beside, inside)
    }

    @Test fun theRightClickMenusLinesAreBareAndShowAsIsALabelAsWrittenOverOneChoiceOfThree() {
        contextHost()
        val devices = ItemConfig("x", "devices", Section.SHOWN)
        val showAs = str(R.string.display_show_as)
        val lines = contextMenu(devices, HiddenMode.SHOW_ALL)
        assertTrue("$lines", showAs in lines)
        assertFalse("a heading in capitals: $lines", showAs.uppercase() in lines)
        // Bare: a line's words start where the line does, with no symbol before them.
        val room = with(rule.density) { 12.dp.toPx() }
        for (label in listOf(str(R.string.common_open), str(R.string.visibility_when_active), str(R.string.context_item_settings), str(R.string.detail_remove))) {
            val line = entry(label).fetchSemanticsNode().boundsInRoot
            val words = rule.onNode(hasText(label) and !(hasClickAction() or isToggleable()), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue("\"$label\" starts ${words.left - line.left} px into its line", words.left - line.left <= room)
        }
        // Show as: three parts of one choice on one line, the item's own chosen, and a click chooses.
        val parts = listOf(R.string.display_icon_and_text to Display.ICON_AND_TEXT, R.string.display_text to Display.TEXT, R.string.display_icon to Display.ICON)
        fun part(id: Int) = rule.onNode(hasText(str(id)) and isSelectable())
        assertEquals("on one line", 1, parts.map { part(it.first).fetchSemanticsNode().boundsInRoot.top }.distinct().size)
        for ((id, display) in parts) {
            part(id).performClick()
            rule.waitForIdle()
            assertEquals(display, stored("x")!!.display)
            for ((other, _) in parts) if (other == id) part(other).assertIsSelected() else part(other).assertIsNotSelected()
        }
        // The tick at a line's end is for the eye: a reader is told on or off, and no mark.
        val ticked = contextMenu(devices.copy(section = Section.HIDDEN, whenActive = true), HiddenMode.SHOW_ALL)
        entry(str(R.string.visibility_when_active)).assertIsOn()
        assertFalse("$ticked", ticked.any { "✓" in it })
    }

    /** What the menu on screen says and what a screen reader is told of it, in the order it is drawn. */
    private fun drawn(): String {
        fun SemanticsNode.lines(): List<String> = listOfNotNull(config.getOrNull(SemanticsProperties.Text)?.joinToString(" "),
            config.getOrNull(SemanticsProperties.EditableText)?.text, config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" "),
            config.getOrNull(SemanticsProperties.StateDescription), config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)?.current?.toString()) + children.flatMap { it.lines() }
        return rule.onRoot(useUnmergedTree = true).fetchSemanticsNode().lines().joinToString("\n")
    }

    private fun compose(case: Case) {
        rule.runOnUiThread { shown = null }
        rule.waitForIdle()
        case.stage?.let { assertNotNull("$case: its hook refused it, so nothing was staged", case.type.debug(it.split(" "))) }
        try {
            rule.runOnUiThread { shown = case }
            rule.waitForIdle()
        } catch (t: Throwable) {
            throw AssertionError("$case threw while it composed: ${t.javaClass.simpleName}: ${t.message}", t)
        }
    }
}
