package com.meridian11.books.bentobar.compose

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
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

    /** One item of every type, as a layout has them: a staged sample is shown by its type's first item outside Off. */
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
