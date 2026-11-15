package com.meridian11.books.bentobar.items

import android.os.PowerManager
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.data.Store
import com.meridian11.books.bentobar.items.BreaksRules.Watch
import com.meridian11.books.bentobar.ui.InfoRow
import com.meridian11.books.bentobar.ui.MenuCard
import com.meridian11.books.bentobar.ui.MenuDivider
import com.meridian11.books.bentobar.ui.MenuEntry
import com.meridian11.books.bentobar.ui.MenuNote
import com.meridian11.books.bentobar.ui.Meter
import com.meridian11.books.bentobar.ui.SliderRow
import com.meridian11.books.bentobar.ui.rememberTick
import com.meridian11.books.bentobar.util.Fmt
import com.meridian11.books.bentobar.util.Now
import com.meridian11.books.bentobar.util.Sym

/**
 * Break reminder: the time until the next break, and a warning when one is due (ruling R9). Work is time the screen is on
 * while the bar is sampled; a break is the screen off, or the bar not sampled, for the break's length. Only the clock and
 * whether the screen is on are read: no keys, no pointer, no other app. The count is in memory only, one for the device
 * (a break is the person's, whichever item shows it); a click while a break is due says "I took one". Rules: [BreaksRules].
 */
object BreaksItem : ItemType("breaks", R.string.item_breaks_title, Sym.RELAX, R.string.item_breaks_desc) {
    override val group = ItemGroup.TIME
    override val canBeActive = true
    override val trigger = Trigger(R.string.trigger_breaks, R.string.trigger_breaks_short)
    // The bar's figure is to the minute.
    override val refreshMs = 5_000L

    private const val MINUTE = 60_000L
    internal val every = IntOpt("every", 50, 20..120)
    internal val rest = IntOpt("rest", 5, 1..15)

    /** Whether the screen is on. A test gives it a fake. */
    internal var interactive: () -> Boolean = { Env.app.getSystemService(PowerManager::class.java)?.isInteractive ?: true }

    /** The device's count since the last break. Main thread writes it; the menu reads it. */
    @Volatile internal var watch = Watch()
    /** The test hook's count, shown instead of the real one (which goes on underneath); null: none staged. */
    @Volatile private var staged: Watch? = null

    private fun shown(): Watch = staged ?: watch

    /** The layout [restFor] was worked out for, and its answer: the layout changes far less often than once a second. */
    private var restOf: List<ItemConfig>? = null
    private var restFor = 0L

    /** A break's length: the shortest of this type's items, so the one that asks least is never kept waiting. */
    private fun restMs(): Long {
        val items = Store.config.value.items
        if (items !== restOf) {
            restFor = (items.filter { it.type == type }.minOfOrNull { rest.of(it) } ?: rest.default) * MINUTE
            restOf = items
        }
        return restFor
    }

    override fun sample(now: Long) {
        watch = BreaksRules.step(watch, now, interactive(), restMs())
    }

    private val words = BreaksRules.Words(
        minutes = { Env.str(R.string.common_bar_minutes, it) },
        hoursMinutes = { h, m -> Env.str(R.string.common_bar_hours_minutes, h, m) },
        due = { Env.str(R.string.breaks_due) },
    )

    override fun state(item: ItemConfig): ItemState {
        val w = shown()
        val everyMs = every.of(item) * MINUTE
        val due = BreaksRules.due(w, everyMs)
        // Said as the bar writes it: the worked time rounded down, the time to the next rounded up.
        val desc = if (due) Env.str(R.string.breaks_desc_due, Fmt.spokenMinutes(w.workedMs))
            else Env.str(R.string.breaks_desc, Fmt.spokenMinutes((everyMs - w.workedMs + MINUTE - 1) / MINUTE * MINUTE))
        return ItemState(icon = Sym.RELAX, text = BreaksRules.text(w, everyMs, words), tone = if (due) Tone.WARN else Tone.NORMAL,
            active = due, desc = desc, widthKey = "breaks")
    }

    /** "I took one": the count starts again now. */
    internal fun tookOne() {
        val now = Now.elapsed()
        if (staged != null) staged = BreaksRules.tookOne(now) else watch = BreaksRules.tookOne(now)
        Ticker.refresh(type)
    }

    // A break is due: the click is "I took one". Otherwise it opens the menu.
    override fun onClick(item: ItemConfig): Boolean {
        if (!BreaksRules.due(shown(), every.of(item) * MINUTE)) return false
        tookOne()
        return true
    }

    override val menu: @Composable (ItemConfig, MenuHost) -> Unit = { item, _ ->
        rememberTick()
        val w = shown()
        val everyMs = every.of(item) * MINUTE
        val due = BreaksRules.due(w, everyMs)
        val next = BreaksRules.length(everyMs - w.workedMs, words, up = true)
        MenuCard(Sym.RELAX, stringResource(R.string.item_breaks_title),
            if (due) stringResource(R.string.breaks_time_for_one) else stringResource(R.string.breaks_next_in, next),
            subtitleColor = if (due) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) {
            val color = if (due) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            InfoRow(stringResource(R.string.breaks_worked), BreaksRules.length(w.workedMs, words), color)
            Meter(w.workedMs.toFloat() / everyMs, color)
            Spacer(Modifier.height(4.dp))
            InfoRow(stringResource(R.string.breaks_next), if (due) stringResource(R.string.breaks_now) else next)
            MenuNote(stringResource(R.string.breaks_note, restMs() / MINUTE))
            MenuDivider()
            MenuEntry(Sym.CHECK, stringResource(R.string.breaks_took_one)) { tookOne() }
        }
    }

    override val options: @Composable (ItemConfig, (ItemConfig) -> Unit) -> Unit = { item, set ->
        val res = androidx.compose.ui.platform.LocalResources.current
        val minutes = { m: Int -> res.getString(R.string.common_bar_minutes, m) }
        SliderRow(stringResource(R.string.breaks_every), every.of(item), every.range, minutes) { set(every.set(item, it)) }
        SliderRow(stringResource(R.string.breaks_rest), rest.of(item), rest.range, minutes) { set(rest.set(item, it)) }
    }

    // ---- for tests on a device (debug builds)

    override val stages = listOf("stage early", "stage due")

    /**
     * `breaks`: the count. `breaks stage early|due`: 12 or 52 minutes worked, in place of the real count, which goes on
     * underneath ("I took one" starts the staged one again); `breaks off` ends it.
     */
    override fun debug(args: List<String>): String? = when {
        args.isEmpty() -> shown().let { "breaks: worked=${it.workedMs / 1000}s away=${it.awayMs / 1000}s staged=${staged != null}" }
        args == listOf("off") || args == listOf("stage", "off") -> { staged = null; Ticker.refresh(type); "breaks: staging off" }
        args.size != 2 || args[0] != "stage" -> null
        else -> {
            val minutes = when (args[1]) { "early" -> 12L; "due" -> 52L; else -> null }
            minutes?.let { staged = Watch(it * MINUTE, 0L, Now.elapsed()); Ticker.refresh(type); "breaks: staged ${args[1]}" }
        }
    }
}
