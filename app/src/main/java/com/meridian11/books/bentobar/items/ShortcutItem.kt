package com.meridian11.books.bentobar.items

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.ui.ChoiceRow
import com.meridian11.books.bentobar.util.Sym

/**
 * Shortcut: one click, one action ([ShortcutAction]): a screenshot, Report a bug (the Googlebook's
 * Feedback app), or one of the Tools menu's system actions. It has no menu: a click does it. Shown
 * as any item is, by its "Show as": the action's glyph and name, or either alone. The rules are in
 * [ShortcutRules]; this object only asks Android.
 */
object ShortcutItem : ItemType("shortcut", R.string.item_shortcut_title, Sym.SCREENSHOT_MONITOR, R.string.item_shortcut_desc) {
    override val group = ItemGroup.TOOLS
    // What it shows changes only when the Feedback app comes or goes.
    override val refreshMs = 60_000L

    override fun defaultOptions() = mapOf("action" to ShortcutAction.SCREENSHOT.id)

    private fun action(item: ItemConfig) = ShortcutRules.action(item.options["action"])

    /** Android's "report a bug" request, for the Googlebook's Feedback app. */
    private fun bugReport() = Intent(Intent.ACTION_BUG_REPORT).setPackage(ShortcutRules.FEEDBACK_PACKAGE)

    /** Whether the Feedback app is here to take it, as starting it would (the manifest names it, so BentoBar can see it). */
    private fun feedback(): Boolean =
        runCatching { Env.app.packageManager.resolveActivity(bugReport(), PackageManager.MATCH_DEFAULT_ONLY) != null }.getOrDefault(false)

    private val main by lazy { Handler(Looper.getMainLooper()) }

    override fun state(item: ItemConfig): ItemState {
        val action = action(item)
        val name = Env.str(action.label)
        val usable = ShortcutRules.usable(action, feedback())
        // A Report a bug without the Feedback app (a layout copied from a Googlebook) is dimmed and says why.
        val desc = if (usable) name else Env.str(R.string.shortcut_unavailable_desc, name)
        // The tooltip names the action, not the type: three shortcuts shown as icons are three different buttons.
        return ItemState(icon = action.glyph, filled = usable, text = name, desc = desc, tooltip = desc)
    }

    override fun onClick(item: ItemConfig): Boolean {
        val action = action(item)
        val global = action.global ?: return ShortcutRules.usable(action, feedback()) && Env.launch(bugReport(), quiet = true)
        val wait = ShortcutRules.waitMs(action)
        if (wait == 0L) return Env.global(global)
        // A screenshot: once BentoBar's own windows are gone.
        main.postDelayed({ Env.global(global) }, wait)
        return true
    }

    override val options: @Composable (ItemConfig, (ItemConfig) -> Unit) -> Unit = { item, set ->
        val feedback = remember { feedback() }
        val current = action(item)
        ChoiceRow(stringResource(R.string.shortcut_action), ShortcutRules.choices(current, feedback).map { it to stringResource(it.label) }, current) {
            set(item.with("action", it.id))
        }
    }
}
