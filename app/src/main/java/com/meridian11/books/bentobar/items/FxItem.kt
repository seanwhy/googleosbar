package com.meridian11.books.bentobar.items

import androidx.compose.runtime.Composable
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.data.Online
import com.meridian11.books.bentobar.items.FxRules.FxTable
import com.meridian11.books.bentobar.util.Dates
import com.meridian11.books.bentobar.util.Now
import com.meridian11.books.bentobar.util.Sym
import java.time.ZoneId

/**
 * Exchange rates: reference foreign exchange rates from the European Central Bank via Frankfurter
 * (`api.frankfurter.dev`, no key required), rotating followed currency pairs in the bar and offering
 * an inline converter in its menu. No logging.
 */
object FxItem : ItemType("fx", R.string.item_fx_title, Sym.SWAP_VERT, R.string.item_fx_desc) {
    override val group = ItemGroup.ONLINE
    override val onlineNote = R.string.about_privacy_fx
    override val menuWidthDp = 340
    override val online = Online.Service.FRANKFURTER
    override val canBeActive = true

    private val move = Threshold("movePct", 1, 1..5) { com.meridian11.books.bentobar.util.Fmt.percent(it) }
    override val trigger = Trigger(R.string.trigger_fx, R.string.trigger_fx_short, move)

    internal val rates: Refresher<Unit, FxTable> = Background.refresher(
        type = type,
        service = online,
        every = { _, t -> FxRules.every(t) },
        load = { _, last -> FxLoad.load(last, Now.wall()) },
    )

    private val staged = Staged<FxTable>(type)

    internal fun isStaged(item: ItemConfig): Boolean = staged.of(item) != null

    internal fun table(ask: Boolean = true): FxTable? {
        staged.value?.let { return it }
        if (!Online.on(online)) return null
        if (ask) rates.want(Unit)
        return rates.peek(Unit)
    }

    internal fun opened(item: ItemConfig) {
        if (isStaged(item) || !Online.on(online)) return
        rates.menuOpened(Unit, FxRules.MENU_MS) { it.failure != null }
    }

    internal fun refresh() {
        if (staged.value != null || !Online.on(online)) return
        rates.refresh(Unit, floorMs = 10_000L)
    }

    override fun state(item: ItemConfig): ItemState {
        val pairs = FxRules.pairs(item)
        if (!Online.on(online) && !isStaged(item)) {
            val d = Env.str(if (Online.setUp(online)) R.string.fx_desc_off else R.string.fx_desc_not_set_up)
            return ItemState(icon = Sym.SWAP_VERT, filled = false, text = null, desc = d, tooltip = d)
        }
        if (pairs.isEmpty()) {
            val d = Env.str(R.string.fx_none_yet)
            return ItemState(icon = Sym.SWAP_VERT, filled = false, text = null, desc = d, tooltip = d)
        }
        val t = table()
        if (t == null || !t.hasRates) {
            val d = Env.str(if (t?.failure != null) R.string.fx_desc_no_rates else R.string.fx_desc_loading)
            return ItemState(icon = Sym.SWAP_VERT, filled = false, text = null, desc = d, tooltip = d)
        }
        val quotes = FxRules.quotesFor(pairs, t)
        val missing = FxRules.missing(pairs, t).joinToString(", ") { it.code }
        if (quotes.isEmpty()) {
            // A pair the bank doesn't publish (BGN since 2026) must not read as "unavailable" with nothing to show for why.
            val d = if (missing.isEmpty()) Env.str(R.string.fx_desc_no_rates) else Env.str(R.string.fx_desc_missing, missing)
            return ItemState(icon = Sym.SWAP_VERT, filled = false, text = null, desc = d, tooltip = d)
        }
        val now = Now.wall()
        val turns = quotes.map { q ->
            when {
                q.changePct >= 0.05 -> Env.str(R.string.fx_bar_up, q.pair.code, q.formattedRate, q.formattedPct)
                q.changePct <= -0.05 -> Env.str(R.string.fx_bar_down, q.pair.code, q.formattedRate, q.formattedPct)
                else -> Env.str(R.string.fx_bar_flat, q.pair.code, q.formattedRate)
            }
        }
        val turnSec = FxRules.turnSec(item)
        val idx = ((now / 1000L) / turnSec).mod(turns.size)
        val currentQuote = quotes[idx]
        val icon = when {
            currentQuote.changePct >= 0.05 -> Sym.TRENDING_UP
            currentQuote.changePct <= -0.05 -> Sym.TRENDING_DOWN
            else -> Sym.SWAP_VERT
        }
        val bigMove = FxRules.bigMove(quotes, t, move.shown(item).toDouble(), now)
        val summary = quotes.joinToString("; ") { "${it.pair.code} ${it.formattedRate}" }
        // The date goes with rates that are not today's: the bank publishes once a working day, and a bar's text has no room for one.
        val desc = (FxRules.notToday(t, now, ZoneId.systemDefault())?.let { Env.str(R.string.fx_desc_dated, summary, Dates.format("MMMd", it)) } ?: Env.str(R.string.fx_desc_live, summary)) +
            if (missing.isEmpty()) "" else " " + Env.str(R.string.fx_no_rate_for, missing)
        return ItemState(
            icon = icon,
            filled = true,
            text = turns[idx],
            turns = if (turns.size > 1) turns else null,
            tone = if (bigMove) Tone.ACCENT else Tone.NORMAL,
            active = bigMove,
            desc = desc,
            tooltip = desc,
            textLimit = FxRules.BAR_CHARS,
        )
    }

    override fun forgetFetched() {
        staged.value = null
    }

    override val menu: @Composable (ItemConfig, MenuHost) -> Unit = { item, host -> FxMenu(item, host) }
    override val options: @Composable (ItemConfig, (ItemConfig) -> Unit) -> Unit = { item, set -> FxOptions(item, set) }

    override val stages = listOf("calm", "big", "offline", "old").map { "stage $it" }

    override fun debug(args: List<String>): String? = when (args.firstOrNull()) {
        null -> "staged=${staged.value != null} on=${Online.on(online)}"
        "stage" -> {
            val s = FxRules.sample(args.getOrNull(1).orEmpty(), Now.wall())
            if (s == null) null else {
                staged.value = s
                Ticker.refresh(type)
                "staged ${args[1]}"
            }
        }
        "off" -> {
            staged.value = null
            Ticker.refresh(type)
            "staging off"
        }
        else -> null
    }
}
