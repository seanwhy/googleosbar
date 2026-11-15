package com.meridian11.books.bentobar.items

import android.content.Intent
import android.net.Uri
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.data.Online
import com.meridian11.books.bentobar.data.Store
import com.meridian11.books.bentobar.util.Dates
import com.meridian11.books.bentobar.util.Now
import com.meridian11.books.bentobar.util.Sym
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * US stocks: the prices of US stocks the user picks, one at a time in the bar, with each stock's day
 * in the menu. The prices come from Finnhub, with a key of the user's own. One of the item types that
 * go online ([online]), and only once a key was saved on this install.
 *
 * Where things are: what the bar and the menu say is [StocksRules]' (pure, unit-tested), the requests
 * and what is kept [StocksLoad]'s, the replies [Finnhub]'s, the menu and the settings are in
 * `StocksMenu.kt`. This object holds the loader for each stock ([quotes]: nothing is scheduled, a
 * stock is asked about when an item that follows it is looked at and its quote has grown old), the
 * search, and what a test stages in their place.
 *
 * The key is `Online`'s. It is read in [StocksLoad], for the request, and is in no state, no
 * description, no tooltip and nothing a debug hook prints.
 */
object StocksItem : ItemType("stocks", R.string.item_stocks_title, Sym.TRENDING_UP, R.string.item_stocks_desc) {
    override val group = ItemGroup.ONLINE
    override val onlineNote = R.string.about_privacy_stocks
    override val menuWidthDp = 340
    override val online = Online.Service.FINNHUB
    override val canBeActive = true
    // Which stocks someone follows is their own business: the debug hook that prints what an item shows says how long its text is.
    override val discreet = true

    /** How far a stock moves in a day, in percent, before the item comes out. */
    private val move = Threshold("movePct", 3, 1..10) { com.meridian11.books.bentobar.util.Fmt.percent(it) }
    override val trigger = Trigger(R.string.trigger_stocks, R.string.trigger_stocks_short, move)

    /** The last quote of each stock, by its symbol: one request per stock, shared by every item that follows it. */
    internal val quotes: Refresher<String, Quote> = Background.refresher(type, online,
        every = { _, q -> StocksRules.every(q, Now.wall()) },
        restore = { s -> StocksLoad.kept(s, Now.wall()) },
        load = { s, last -> StocksLoad.load(s, last, Now.wall()) { StocksLoad.symbols(Store.config.value.items) } })

    /** The search in the item's settings: one question at a time. */
    internal val search: Ask<StockQuery, StockFound> = Background.ask(type, online) { q -> StocksLoad.search(q.text) }

    /** The words, from the app's resources. */
    internal object Words : StocksWords {
        override fun say(word: SW, vararg args: Any): String = Env.str(stocksRes(word), *args)
    }

    /** Times as the system writes them, with its 12 or 24 hours. */
    internal fun times(): StocksTimes {
        val time = Dates.timeSkeleton(DateFormat.is24HourFormat(Env.app))
        return StocksTimes(ZoneId.systemDefault(), clock = { ms, zone -> Dates.format(time, ms, zone) }, dayClock = { ms, zone -> Dates.format("EEE$time", ms, zone) })
    }

    internal fun look(item: ItemConfig, stocks: List<Stock> = StocksRules.stocks(item)): StocksLook =
        StocksLook(stocks, move.shown(item), Locale.getDefault(), StocksRules.turnSec(item))

    internal fun status(item: ItemConfig, stocks: List<Stock> = StocksRules.stocks(item)): StocksStatus =
        staged?.takeIf { first()?.id == item.id }?.status ?: StocksRules.status(Online.hasKey(online), Online.on(online), stocks.isNotEmpty())

    /** The quotes of [item]'s stocks: the staged ones in a test, else the loader's, asking for those that are due. */
    internal fun quotesFor(item: ItemConfig, stocks: List<Stock> = StocksRules.stocks(item), ask: Boolean = true): Map<String, Quote> {
        staged?.let { if (first()?.id == item.id) return it.quotes }
        if (ask && Online.on(online) && Online.hasKey(online)) stocks.forEach { quotes.want(it.symbol) }
        return stocks.mapNotNull { s -> quotes.peek(s.symbol)?.let { s.symbol to it } }.toMap()
    }

    override fun state(item: ItemConfig): ItemState {
        val now = Now.wall()
        val stocks = StocksRules.stocks(item)
        val bar = StocksRules.bar(status(item, stocks), look(item, stocks), quotesFor(item, stocks), now, Words)
        return ItemState(icon = bar.icon, filled = bar.filled, text = bar.text, desc = bar.desc, active = bar.active, tone = bar.tone,
            tooltip = bar.tooltip, textLimit = StocksRules.BAR_CHARS, turns = bar.turns)
    }

    /** The menu opened: while the market is open, a quote older than half a minute is asked again. */
    internal fun opened(item: ItemConfig) {
        if (staged != null || !Online.on(online) || !Online.hasKey(online)) return
        val open = Market.open(Instant.ofEpochMilli(Now.wall()))
        for (s in StocksRules.stocks(item)) {
            val q = quotes.peek(s.symbol)
            if (q == null) quotes.want(s.symbol) else if (open && q.failure == null) quotes.refresh(s.symbol, floorMs = StocksRules.MENU_MS)
        }
    }

    /** Refresh in the menu: every stock of [item] now, unless it was tried too short a while ago for that ([StocksRules.againAfter]). */
    internal fun refresh(item: ItemConfig) {
        if (staged != null || !Online.on(online) || !Online.hasKey(online)) return
        for (s in StocksRules.stocks(item)) quotes.refresh(s.symbol, floorMs = StocksRules.againAfter(quotes.peek(s.symbol)))
    }

    // ---- the key -------------------------------------------------------------------------------------

    /** Saves the user's key and asks again for what the old one couldn't get. */
    internal fun saveKey(key: String): Boolean {
        if (!Online.saveKey(online, key)) return false
        if ((search.state.value as? Ask.State.Done)?.answer is StockFound.Failed) search.clear()
        for (item in Store.config.value.items) if (item.type == type) for (s in StocksRules.stocks(item)) {
            val q = quotes.peek(s.symbol)
            if (q?.failure == Finnhub.Failure.REFUSED || q?.failure == Finnhub.Failure.NO_ACCESS) quotes.refresh(s.symbol)
        }
        Ticker.refresh(type)
        return true
    }

    internal fun removeKey() = Online.removeKey(online)

    internal fun openSignUp() { Env.launch(Intent(Intent.ACTION_VIEW, Uri.parse(Finnhub.SIGN_UP))) }

    internal fun openSite() { Env.launch(Intent(Intent.ACTION_VIEW, Uri.parse(Finnhub.SITE))) }

    // ---- deleted with the item -----------------------------------------------------------------------

    private var seenItems: List<ItemConfig>? = null
    private val tidying = Background.executor(type)

    /**
     * The quotes of stocks no item follows any more are deleted: looked at whenever the layout's items
     * changed, from [sample] and from [onIdle]. Main thread.
     */
    private fun tidy() {
        val items = Store.config.value.items
        if (items === seenItems) return
        seenItems = items
        val symbols = StocksLoad.symbols(items)
        quotes.keepOnly(symbols)
        // On the disk too, in the background. On an install that never saved a key nothing was ever fetched.
        if (Online.setUp(online)) tidying.execute { StocksLoad.tidy(symbols) }
    }

    override fun sample(now: Long) = tidy()

    override fun onIdle() = tidy()

    override val menu: @Composable (ItemConfig, MenuHost) -> Unit = { item, host -> StocksMenu(item, host) }

    override val options: @Composable (ItemConfig, (ItemConfig) -> Unit) -> Unit = { item, set -> StocksOptions(item, set) }

    // ---- tests on a device (debug builds) ------------------------------------------------------------

    /** A made-up state for the first Stocks item: its status and quotes. Nothing is asked for it. */
    internal class Staged(val name: String, val status: StocksStatus, val quotes: Map<String, Quote>)

    @Volatile private var staged: Staged? = null

    private fun first(): ItemConfig? = Store.config.value.items.firstOrNull { it.type == type }

    /** Whether [item] shows a staged state. */
    internal fun isStaged(item: ItemConfig): Boolean = staged != null && first()?.id == item.id

    // The switch went off or the key was removed: the loader has forgotten already; a staged state goes too.
    override fun forgetFetched() { staged = null }

    /**
     * `./bento debug stocks stage up|down|mixed|big|closed|loading|offline|refused|nokey|none`, `stocks off`,
     * and `stocks` alone for where things stand. Staged quotes are for the symbols of the first Stocks
     * item, made up from its own symbols, so a test sets the stocks first (`set ID stocks=…`): every
     * state but `nokey` and `none` draws a row for each, and without any it is refused.
     */
    override val stages = listOf("up", "down", "mixed", "big", "closed", "loading", "offline", "refused", "nokey", "none").map { "stage $it" }

    override val stageOptions = mapOf("stocks" to """[{"s":"AAPL","n":"Apple"},{"s":"MSFT","n":"Microsoft"},{"s":"NVDA","n":"NVIDIA"}]""")

    override fun debug(args: List<String>): String? = when (args.firstOrNull()) {
        null -> {
            val items = Store.config.value.items.filter { it.type == type }
            "staged=${staged?.name ?: "none"} items=${items.size} symbols=${StocksLoad.symbols(items).size}" +
                " on=${Online.on(online)} keyed=${Online.hasKey(online)}" + (first()?.let { f ->
                    val stocks = StocksRules.stocks(f)
                    val q = quotesFor(f, stocks, ask = false)
                    " first=${status(f, stocks).javaClass.simpleName} quotes=${q.size} failures=${q.values.mapNotNull { it.failure }.toSet()}" +
                        " loading=${stocks.count { quotes.loading(it.symbol) }}"
                } ?: "")
        }
        "stage" -> {
            val f = first() ?: return null
            val symbols = StocksRules.stocks(f).map(Stock::symbol)
            val now = Now.wall()
            val at = now / 1000
            fun q(i: Int, pct: Double, failure: Finnhub.Failure? = null): Quote {
                val price = listOf(227.52, 418.07, 131.26, 64.18, 1834.5)[i % 5]
                val prev = price / (1 + pct / 100)
                return Quote(symbols[i], price, price - prev, pct, price * 1.01, price * 0.98, prev, at, now - 40_000, failure)
            }
            val all = symbols.indices
            val name = args.getOrNull(1).orEmpty()
            if (symbols.isEmpty() && name != "nokey" && name != "none") return null
            val made: Staged? = when (name) {
                "up" -> Staged(name, StocksStatus.Live, all.associate { symbols[it] to q(it, 0.8 + it * 0.4) })
                "down" -> Staged(name, StocksStatus.Live, all.associate { symbols[it] to q(it, -0.6 - it * 0.3) })
                "mixed" -> Staged(name, StocksStatus.Live, all.associate { symbols[it] to q(it, if (it % 2 == 0) 1.24 else -0.37) })
                "big" -> Staged(name, StocksStatus.Live, all.associate { symbols[it] to q(it, if (it == 0) -4.6 else 0.3) })
                "closed" -> Staged(name, StocksStatus.Live, all.associate { symbols[it] to q(it, 0.5).copy(at = at - 3 * 86_400) })
                "loading" -> Staged(name, StocksStatus.Live, emptyMap())
                "offline" -> Staged(name, StocksStatus.Live, all.associate { symbols[it] to q(it, 0.5, Finnhub.Failure.OFFLINE) })
                "refused" -> Staged(name, StocksStatus.Live, all.associate { symbols[it] to Quote(symbols[it], failure = Finnhub.Failure.REFUSED) })
                "nokey" -> Staged(name, StocksStatus.NoKey, emptyMap())
                "none" -> Staged(name, StocksStatus.NoStocks, emptyMap())
                else -> null
            }
            if (made == null) null else {
                staged = made
                Ticker.refresh(type)
                "staged $name for the first Stocks item"
            }
        }
        "off" -> {
            staged = null
            Ticker.refresh(type)
            "staging off"
        }
        else -> null
    }
}

/**
 * The text resource behind each of the rules' words. A unit test compares it to the names in [SW], so
 * the app can't say one text where the tests read another.
 */
fun stocksRes(word: SW): Int = when (word) {
    SW.BAR -> R.string.stocks_bar
    SW.BAR_SHORT -> R.string.stocks_bar_short
    SW.UP -> R.string.stocks_up
    SW.DOWN -> R.string.stocks_down
    SW.FLAT -> R.string.stocks_flat
    SW.PERCENT -> R.string.stocks_percent
    SW.TOOLTIP_NOT_CURRENT -> R.string.stocks_tooltip_not_current
    SW.DESC -> R.string.stocks_desc
    SW.DESC_PRICE -> R.string.stocks_desc_price
    SW.DESC_NOT_CURRENT -> R.string.stocks_desc_not_current
    SW.DESC_UP -> R.string.stocks_desc_up
    SW.DESC_DOWN -> R.string.stocks_desc_down
    SW.DESC_FLAT -> R.string.stocks_desc_flat
    SW.DESC_ALL -> R.string.stocks_desc_all
    SW.LIST -> R.string.stocks_list
    SW.DESC_NO_KEY -> R.string.stocks_desc_no_key
    SW.DESC_OFF -> R.string.stocks_desc_off
    SW.DESC_NONE -> R.string.stocks_desc_none
    SW.DESC_LOADING -> R.string.stocks_desc_loading
    SW.DESC_NO_PRICES -> R.string.stocks_desc_no_prices
    SW.CHANGE -> R.string.stocks_change
    SW.RANGE -> R.string.stocks_range
    SW.MARKET_OPEN -> R.string.stocks_market_open
    SW.MARKET_CLOSED -> R.string.stocks_market_closed
    SW.NOTE -> R.string.stocks_note
    SW.UPDATED -> R.string.common_updated
    SW.UPDATED_OFFLINE -> R.string.common_updated_offline
    SW.UPDATED_NO_ANSWER -> R.string.common_updated_no_answer
    SW.UPDATED_NOT_CURRENT -> R.string.stocks_updated_not_current
    SW.NOT_CURRENT -> R.string.stocks_not_current
    SW.OFFLINE -> R.string.common_no_connection
    SW.NO_ANSWER -> R.string.stocks_no_answer
    SW.SLOW_DOWN -> R.string.stocks_slow_down
    SW.REFUSED -> R.string.stocks_key_refused
    SW.NO_ACCESS -> R.string.stocks_no_access
    SW.NOT_FOUND -> R.string.stocks_not_found
    SW.WAITING -> R.string.stocks_waiting
    SW.NOTHING_FOUND -> R.string.stocks_nothing_found
    SW.SEARCH_NO_ANSWER -> R.string.stocks_search_no_answer
    SW.SEARCH_SLOW -> R.string.stocks_search_slow
}
