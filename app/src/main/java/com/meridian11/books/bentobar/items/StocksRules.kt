package com.meridian11.books.bentobar.items

import androidx.compose.runtime.Immutable
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.util.Sym
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.NumberFormat
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.round

// The Stocks item's rules: which stocks an item follows, what the bar shows and says, the menu's lines,
// when the market is open and when a quote is asked again. No Android: the moment, the locale, the
// clock's way of writing a time and the words all come in from outside, so every rule is a unit test.

/**
 * Every word these rules say, by the name of its text resource. The app reads them from its
 * resources, a test from the text files themselves.
 */
enum class SW(val res: String) {
    BAR("stocks_bar"), BAR_SHORT("stocks_bar_short"), UP("stocks_up"), DOWN("stocks_down"), FLAT("stocks_flat"), PERCENT("stocks_percent"),
    TOOLTIP_NOT_CURRENT("stocks_tooltip_not_current"),
    DESC("stocks_desc"), DESC_PRICE("stocks_desc_price"), DESC_NOT_CURRENT("stocks_desc_not_current"),
    DESC_UP("stocks_desc_up"), DESC_DOWN("stocks_desc_down"), DESC_FLAT("stocks_desc_flat"),
    DESC_ALL("stocks_desc_all"), LIST("stocks_list"),
    DESC_NO_KEY("stocks_desc_no_key"), DESC_OFF("stocks_desc_off"), DESC_NONE("stocks_desc_none"), DESC_LOADING("stocks_desc_loading"),
    DESC_NO_PRICES("stocks_desc_no_prices"),
    CHANGE("stocks_change"), RANGE("stocks_range"),
    MARKET_OPEN("stocks_market_open"), MARKET_CLOSED("stocks_market_closed"), NOTE("stocks_note"),
    UPDATED("common_updated"), UPDATED_OFFLINE("common_updated_offline"), UPDATED_NO_ANSWER("common_updated_no_answer"),
    UPDATED_NOT_CURRENT("stocks_updated_not_current"),
    NOT_CURRENT("stocks_not_current"), OFFLINE("common_no_connection"), NO_ANSWER("stocks_no_answer"), SLOW_DOWN("stocks_slow_down"), REFUSED("stocks_key_refused"),
    NO_ACCESS("stocks_no_access"), NOT_FOUND("stocks_not_found"), WAITING("stocks_waiting"),
    NOTHING_FOUND("stocks_nothing_found"), SEARCH_NO_ANSWER("stocks_search_no_answer"), SEARCH_SLOW("stocks_search_slow"),
}

/** Where the words come from: [say] is a text with its arguments put in. */
interface StocksWords {
    fun say(word: SW, vararg args: Any): String
}

/** How times are written: [clock] a time of day ("4:00 PM"), [dayClock] one with its weekday ("Mon 9:30 AM"); [here] is the device's zone. */
class StocksTimes(val here: ZoneId, val clock: (epochMs: Long, zone: ZoneId) -> String, val dayClock: (epochMs: Long, zone: ZoneId) -> String)

/** A stock an item follows: its symbol and the name the search gave it. */
@Immutable
data class Stock(val symbol: String, val name: String) {
    override fun toString() = "a stock"
}

/**
 * What is known of one stock: the last good numbers, and what came of the last try. The app's own
 * model, not the reply: this is what is kept on the device for a restart. Prices in US dollars.
 *
 * [at]: the quote's own time, in seconds since 1970 (the last trade the service knows of).
 * [fetchedAt]: the wall clock when the numbers were read, in milliseconds; 0 when there never were
 * any. [failure]: the last try failed (the numbers are then the ones from before). [misses]: tries in
 * a row that went out and failed. [retryAfterSec]: how long the service asked to be left alone.
 */
@Serializable
@Immutable
data class Quote(
    val symbol: String,
    val price: Double? = null,
    val change: Double? = null,
    val pct: Double? = null,
    val high: Double? = null,
    val low: Double? = null,
    val prevClose: Double? = null,
    val at: Long = 0,
    val fetchedAt: Long = 0,
    val failure: Finnhub.Failure? = null,
    val misses: Int = 0,
    val retryAfterSec: Long = 0,
) {
    override fun toString() = "a quote"

    /** The day's change in percent: the service's, or worked out from the change and the last close. */
    val dayPct: Double? get() = pct ?: if (change != null && prevClose != null && prevClose > 0) change / prevClose * 100 else null
}

/** Which of its states a Stocks item is in. */
sealed interface StocksStatus {
    /** No key saved on this install. */
    data object NoKey : StocksStatus
    /** A key, but the service is switched off in Setup. */
    data object Off : StocksStatus
    /** Set up, and no stock added yet. */
    data object NoStocks : StocksStatus
    data object Live : StocksStatus
}

/**
 * How one item wants its stocks shown: its stocks in order, the move in percent that brings it out,
 * the locale numbers are written in, and how long each stock stays in the bar before the next takes
 * its turn, in seconds.
 */
class StocksLook(val stocks: List<Stock>, val movePct: Int, val locale: Locale, val turnSec: Int = StocksRules.TURN_SEC)

/** What the bar shows. [turns]: every text the item takes turns showing, so that its place is as wide as the widest of them. */
class StocksBar(val icon: String, val filled: Boolean, val text: String? = null, val desc: String, val active: Boolean = false,
                val tone: Tone = Tone.NORMAL, val tooltip: String? = null, val turns: List<String>? = null)

/** One stock in the menu. A value that is not known is null and its place stays empty. [up]: which way the day went, null for no change. */
@Immutable
class StockLine(val symbol: String, val name: String, val price: String?, val change: String?, val up: Boolean?, val range: String?,
                val problem: String?, val desc: String)

/** The menu as text: every line it draws. */
@Immutable
class StocksView(val lines: List<StockLine>, val market: String, val note: String?)

/** The New York Stock Exchange's and Nasdaq's regular hours, Monday to Friday. Holidays are not in it: [StocksRules.every] sees them in the quotes. */
object Market {
    val ZONE: ZoneId = ZoneId.of("America/New_York")
    private val OPENS = LocalTime.of(9, 30)
    private val CLOSES = LocalTime.of(16, 0)

    private fun weekday(d: LocalDate) = d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY
    fun opensOn(d: LocalDate): Instant = d.atTime(OPENS).atZone(ZONE).toInstant()
    fun closesOn(d: LocalDate): Instant = d.atTime(CLOSES).atZone(ZONE).toInstant()
    fun day(at: Instant): LocalDate = at.atZone(ZONE).toLocalDate()

    fun open(at: Instant): Boolean = weekday(day(at)) && !at.isBefore(opensOn(day(at))) && at.isBefore(closesOn(day(at)))

    /** The next opening after [at]: today's while it is still to come, else the next weekday's. */
    fun nextOpen(at: Instant): Instant {
        var d = day(at)
        while (!weekday(d) || !opensOn(d).isAfter(at)) d = d.plusDays(1)
        return opensOn(d)
    }

    /** The last closing at or before [at]. */
    fun lastClose(at: Instant): Instant {
        var d = day(at)
        while (!weekday(d) || closesOn(d).isAfter(at)) d = d.minusDays(1)
        return closesOn(d)
    }
}

object StocksRules {
    /** The most stocks one item follows: each is a request of its own, every two minutes while the market is open. */
    const val MOST_STOCKS = 10
    /** How long each stock stays in the bar before the next takes its turn, in seconds: the item's choice, five unless it chose another. */
    const val TURN_SEC = 5
    /** The choices the settings offer. */
    val TURNS = listOf(3, 5, 10, 30, 60)
    /** The most characters the bar's text has; what is longer loses the price, then the change. */
    const val BAR_CHARS = 20

    private const val MIN_MS = 60_000L
    /** While the market is open, a quote is asked again after two minutes (while the bar is on screen). */
    const val OPEN_MS = 2 * MIN_MS
    /** Opening the menu (while the market is open) and Refresh ask again if a quote is older than this. */
    const val MENU_MS = 30_000L
    /** After the close the service settles the day's last price; the quote read after this is the day's. */
    const val SETTLE_MS = 5 * MIN_MS
    /** A quote that still has yesterday's time this long after the opening: a holiday or a quiet morning, asked every half hour. */
    private const val HOLIDAY_MS = 15 * MIN_MS
    const val QUIET_OPEN_MS = 30 * MIN_MS
    const val RETRY_MS = 5 * MIN_MS
    const val SLOW_MS = 5 * MIN_MS
    /** A stock the service doesn't know, or the free plan doesn't cover: asked again after six hours. */
    const val RARE_MS = 6 * 60 * MIN_MS

    private val json = Json { ignoreUnknownKeys = true }

    // ---- what a layout holds --------------------------------------------------------------------

    /**
     * The stocks [item] follows, in order: what its option holds, read carefully (a layout can come
     * from anywhere). A symbol that is no US symbol is left out, as is one that comes twice; ten at most.
     */
    fun stocks(item: ItemConfig): List<Stock> = try {
        (Json.parseToJsonElement(item.options["stocks"].orEmpty().ifEmpty { "[]" }) as? JsonArray).orEmpty().asSequence().take(100).mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val symbol = (o["s"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(Finnhub::symbol) ?: return@mapNotNull null
            Stock(symbol, TextRules.oneLine((o["n"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty(), Finnhub.NAME_CHARS).ifEmpty { symbol })
        }.distinctBy { it.symbol }.take(MOST_STOCKS).toList()
    } catch (e: Exception) {
        emptyList()
    } catch (e: StackOverflowError) {
        emptyList()
    }

    /** How long each of [item]'s stocks stays in the bar, in seconds: one of [TURNS]; anything else a layout holds is five. */
    fun turnSec(item: ItemConfig): Int = item.optInt("turnSec", TURN_SEC).takeIf { it in TURNS } ?: TURN_SEC

    /** [item] following [stocks], in that order. */
    fun withStocks(item: ItemConfig, stocks: List<Stock>): ItemConfig = item.with("stocks", if (stocks.isEmpty()) null else buildJsonArray {
        for (s in stocks.distinctBy { it.symbol }.take(MOST_STOCKS)) add(buildJsonObject { put("s", s.symbol); put("n", s.name) })
    }.toString())

    /** [item] with [found] added at the end; as it was if it follows it already or follows ten. */
    fun add(item: ItemConfig, found: Listing): ItemConfig {
        val now = stocks(item)
        return if (now.size >= MOST_STOCKS || now.any { it.symbol == found.symbol }) item else withStocks(item, now + Stock(found.symbol, found.name))
    }

    fun remove(item: ItemConfig, symbol: String): ItemConfig = withStocks(item, stocks(item).filter { it.symbol != symbol })

    /** [item] with [symbol] one place earlier ([by] -1) or later (1). */
    fun move(item: ItemConfig, symbol: String, by: Int): ItemConfig {
        val list = stocks(item).toMutableList()
        val i = list.indexOfFirst { it.symbol == symbol }
        val j = i + by
        if (i < 0 || j !in list.indices) return item
        list.add(j, list.removeAt(i))
        return withStocks(item, list)
    }

    // ---- numbers ---------------------------------------------------------------------------------

    /** One per locale and decimals: the bar's text is built every second per stock, and a NumberFormat costs more to make than to use. */
    private val formats = HashMap<Pair<Locale, Int>, NumberFormat>()

    private fun numberFormat(l: Locale, decimals: Int): NumberFormat = synchronized(formats) {
        formats.getOrPut(l to decimals) { NumberFormat.getNumberInstance(l).apply { minimumFractionDigits = decimals; maximumFractionDigits = decimals; isGroupingUsed = true } }
    }

    private fun fixed(v: Double, decimals: Int, l: Locale): String =
        numberFormat(l, decimals).let { synchronized(it) { it.format(v) } }

    /** The decimals of a price, and of a change in dollars beside it: four under a dollar, else two. */
    private fun cents(price: Double?) = if ((price ?: 1.0) < 1) 4 else 2

    /** A price in the menu: four decimals under a dollar, else two ("0.4512", "227.52", "1,234.56"). */
    fun price(v: Double, l: Locale): String = fixed(v, cents(v), l)

    /** A price in the bar, where room is short: no cents from a thousand dollars ("227.52", "1,235"). */
    fun barPrice(v: Double, l: Locale): String = fixed(v, if (v < 1) 4 else if (v < 1000) 2 else 0, l)

    /** A percentage without its sign: one decimal, none from a hundred ("1.2", "0.0", "142"). */
    fun percent(v: Double, l: Locale): String = fixed(abs(v), if (abs(v) < 99.95) 1 else 0, l)

    /** Which way [v] went once it is written with [decimals] (a percentage has one): what reads "0.0" or "0.00" went nowhere. */
    private fun way(v: Double?, decimals: Int = 1): Int = when {
        v == null -> 0
        round(abs(v) * 10.0.pow(decimals)) == 0.0 -> 0
        v > 0 -> 1
        else -> -1
    }

    /** "▲1.2%", "▼0.4%", "0.0%"; null without a change. */
    private fun move(pct: Double?, w: StocksWords, l: Locale): String? = pct?.let {
        when (way(it)) { 1 -> w.say(SW.UP, percent(it, l)); -1 -> w.say(SW.DOWN, percent(it, l)); else -> w.say(SW.FLAT, percent(0.0, l)) }
    }

    /** "up 1.2 percent", "unchanged". */
    private fun spokenMove(pct: Double?, w: StocksWords, l: Locale): String? = pct?.let {
        when (way(it)) { 1 -> w.say(SW.DESC_UP, percent(it, l)); -1 -> w.say(SW.DESC_DOWN, percent(it, l)); else -> w.say(SW.DESC_FLAT) }
    }

    private fun glyph(pct: Double?): String = when (way(pct)) { 1 -> Sym.TRENDING_UP; -1 -> Sym.TRENDING_DOWN; else -> Sym.TRENDING_FLAT }

    private fun fits(text: String) = TextRules.fits(text, BAR_CHARS)

    // ---- the state -------------------------------------------------------------------------------

    fun status(keyed: Boolean, on: Boolean, any: Boolean): StocksStatus = when {
        !keyed -> StocksStatus.NoKey
        !on -> StocksStatus.Off
        !any -> StocksStatus.NoStocks
        else -> StocksStatus.Live
    }

    /** While the market is open, a quote not read for ten minutes (five two-minute turns) is not live. */
    const val STALE_MS = 10 * MIN_MS

    private fun quietOpen(q: Quote, read: Instant, opened: Instant): Boolean =
        !read.isBefore(opened.plusMillis(HOLIDAY_MS)) && q.at > 0 && Instant.ofEpochSecond(q.at).isBefore(opened)

    /**
     * Whether [q] is too old to show as a live quote at [now]: while the market is open, it was read
     * before today's opening or more than [STALE_MS] ago (plus [QUIET_OPEN_MS] on a holiday or quiet
     * morning). Closed, the quote read after the last session's close stays until the next opening;
     * one from before that close (offline since) is not current once [STALE_MS] has passed.
     */
    fun stale(q: Quote, now: Long): Boolean {
        if (q.fetchedAt <= 0L) return true
        val at = Instant.ofEpochMilli(now)
        val read = Instant.ofEpochMilli(q.fetchedAt)
        if (!Market.open(at)) {
            val closed = Market.lastClose(at)
            val limit = if (quietOpen(q, read, Market.opensOn(Market.day(closed)))) QUIET_OPEN_MS + STALE_MS else STALE_MS
            return read.isBefore(closed) && now - q.fetchedAt > limit
        }
        val opened = Market.opensOn(Market.day(at))
        val limit = if (quietOpen(q, read, opened)) QUIET_OPEN_MS + STALE_MS else STALE_MS
        return read.isBefore(opened) || now - q.fetchedAt > limit
    }

    /** The day's move of a quote whose own time is today in New York; an older one (a weekend's Friday) moved on another day. */
    private fun todaysPct(q: Quote, now: Long): Double? =
        q.dayPct?.takeIf { q.at > 0 && Market.day(Instant.ofEpochSecond(q.at)) == Market.day(Instant.ofEpochMilli(now)) }

    /**
     * What the bar shows at [now]: one stock at a time, each for the item's [StocksLook.turnSec]
     * ("AAPL 227.52 ▲1.2%"). A stock without a price yet has no turn; one whose quote is [stale]
     * shows its price alone, with an outlined flat icon, no accent and a note in the tooltip and
     * the spoken words.
     */
    fun bar(status: StocksStatus, look: StocksLook, quotes: Map<String, Quote>, now: Long, w: StocksWords): StocksBar {
        fun quiet(desc: SW) = StocksBar(Sym.SHOW_CHART, filled = false, desc = w.say(desc))
        when (status) {
            StocksStatus.NoKey -> return quiet(SW.DESC_NO_KEY)
            StocksStatus.Off -> return quiet(SW.DESC_OFF)
            StocksStatus.NoStocks -> return quiet(SW.DESC_NONE)
            StocksStatus.Live -> {}
        }
        val l = look.locale
        val priced = look.stocks.mapNotNull { s -> quotes[s.symbol]?.takeIf { it.price != null }?.let { s to it } }
        if (priced.isEmpty()) {
            val failed = look.stocks.any { quotes[it.symbol]?.failure != null }
            return quiet(if (failed) SW.DESC_NO_PRICES else SW.DESC_LOADING)
        }
        val active = priced.any { (_, q) -> !stale(q, now) && todaysPct(q, now)?.let { abs(it) >= look.movePct } == true }
        val tone = if (active) Tone.ACCENT else Tone.NORMAL

        // Longer than twenty characters: first the price goes, then the change. A quote that is not current shows no change.
        val texts = priced.map { (s, q) ->
            val change = if (stale(q, now)) null else move(q.dayPct, w, l)
            listOfNotNull(change?.let { w.say(SW.BAR, s.symbol, barPrice(q.price!!, l), it) }, w.say(SW.BAR_SHORT, s.symbol, barPrice(q.price!!, l)).takeIf { change == null },
                change?.let { w.say(SW.BAR_SHORT, s.symbol, it) }).firstOrNull(::fits) ?: s.symbol
        }
        val i = ((now / (look.turnSec * 1000L)) % priced.size).toInt()
        val (shown, q) = priced[i]
        val qStale = stale(q, now)
        // Spoken, every stock at once: a screen reader can't wait for the turns.
        val spoken = priced.map { (s, sq) ->
            if (stale(sq, now)) w.say(SW.DESC_NOT_CURRENT, s.symbol, price(sq.price!!, l))
            else {
                val m = spokenMove(sq.dayPct, w, l)
                if (m != null) w.say(SW.DESC, s.symbol, price(sq.price!!, l), m) else w.say(SW.DESC_PRICE, s.symbol, price(sq.price!!, l))
            }
        }.reduce { a, b -> w.say(SW.LIST, a, b) }
        return StocksBar(if (qStale) Sym.TRENDING_FLAT else glyph(q.dayPct), filled = !qStale, text = texts[i], desc = w.say(SW.DESC_ALL, spoken), active = active, tone = tone,
            tooltip = if (qStale) w.say(SW.TOOLTIP_NOT_CURRENT, shown.name) else shown.name, turns = texts.takeIf { it.size > 1 })
    }

    // ---- the menu --------------------------------------------------------------------------------

    private fun problem(f: Finnhub.Failure?, w: StocksWords): String? = when (f) {
        null -> null
        Finnhub.Failure.OFFLINE -> w.say(SW.OFFLINE)
        Finnhub.Failure.NO_ANSWER -> w.say(SW.NO_ANSWER)
        Finnhub.Failure.SLOW_DOWN -> w.say(SW.SLOW_DOWN)
        Finnhub.Failure.REFUSED -> w.say(SW.REFUSED)
        Finnhub.Failure.NO_ACCESS -> w.say(SW.NO_ACCESS)
        Finnhub.Failure.NOT_FOUND -> w.say(SW.NOT_FOUND)
    }

    /** What a search that failed says: each failure has its own reason, so a refused or free-plan answer never reads as no answer. */
    fun searchProblem(f: Finnhub.Failure): SW = when (f) {
        Finnhub.Failure.OFFLINE -> SW.OFFLINE
        Finnhub.Failure.REFUSED -> SW.REFUSED
        Finnhub.Failure.NO_ACCESS -> SW.NO_ACCESS
        Finnhub.Failure.NOT_FOUND -> SW.NOTHING_FOUND
        Finnhub.Failure.SLOW_DOWN -> SW.SEARCH_SLOW
        Finnhub.Failure.NO_ANSWER -> SW.SEARCH_NO_ANSWER
    }

    private fun sign(way: Int) = when (way) { 1 -> "+"; -1 -> "−"; else -> "" }

    /**
     * "+1.23 (+0.5%)", "−0.40 (−0.1%)", "−0.20 (0.0%)": the day's change in dollars and in percent,
     * each with the sign of what it reads. The dollars have the price's decimals: four only for a stock
     * under a dollar, whatever the change.
     */
    private fun change(q: Quote, l: Locale, w: StocksWords): String? {
        val pct = q.dayPct ?: return null
        val percent = w.say(SW.PERCENT, sign(way(pct)) + percent(pct, l))
        val decimals = cents(q.price)
        return q.change?.let { w.say(SW.CHANGE, sign(way(it, decimals)) + fixed(abs(it), decimals, l), percent) } ?: percent
    }

    /** Which way the day went in the menu: as its dollars read, or as its percentage where they read nothing. Null for no change. */
    private fun up(q: Quote): Boolean? {
        val way = q.change?.let { way(it, cents(q.price)) }?.takeIf { it != 0 } ?: way(q.dayPct)
        return way.takeIf { it != 0 }?.let { it > 0 }
    }

    /** The market's line: open and when it closes, or closed and when it opens (with its weekday when that is not today), in the device's time. */
    fun market(now: Long, w: StocksWords, t: StocksTimes): String {
        val at = Instant.ofEpochMilli(now)
        return if (Market.open(at)) w.say(SW.MARKET_OPEN, t.clock(Market.closesOn(Market.day(at)).toEpochMilli(), t.here))
        else {
            val next = Market.nextOpen(at)
            val sameDay = next.atZone(t.here).toLocalDate() == at.atZone(t.here).toLocalDate()
            w.say(SW.MARKET_CLOSED, if (sameDay) t.clock(next.toEpochMilli(), t.here) else t.dayClock(next.toEpochMilli(), t.here))
        }
    }

    /** The menu at [now]: a line for each stock, the market, and the service's credit with the time of the last answer. */
    fun menu(look: StocksLook, quotes: Map<String, Quote>, now: Long, w: StocksWords, t: StocksTimes): StocksView {
        val l = look.locale
        val lines = look.stocks.map { s ->
            val q = quotes[s.symbol]
            val stale = q?.price != null && stale(q, now)
            val price = q?.price?.let { price(it, l) }
            val range = if (q?.low != null && q.high != null) w.say(SW.RANGE, price(q.low, l), price(q.high, l)) else null
            val problem = when {
                q == null -> w.say(SW.WAITING)
                q.failure != null -> problem(q.failure, w)
                stale -> w.say(SW.NOT_CURRENT)
                else -> null
            }
            val m = q?.takeUnless { stale }?.let { spokenMove(it.dayPct, w, l) }
            val desc = listOfNotNull(
                when { price != null && m != null -> w.say(SW.DESC, s.symbol, price, m); price != null -> w.say(SW.DESC_PRICE, s.symbol, price); else -> s.symbol },
                s.name.takeIf { it != s.symbol }, problem).reduce { a, b -> w.say(SW.LIST, a, b) }
            StockLine(s.symbol, s.name, price, q?.let { change(it, l, w) }, q?.takeIf { it.dayPct != null && !stale }?.let(::up), range, problem, desc)
        }
        val newest = quotes.values.filter { it.symbol in look.stocks.map(Stock::symbol) && it.fetchedAt > 0 }.maxByOrNull { it.fetchedAt }
        val failed = look.stocks.mapNotNull { quotes[it.symbol]?.failure }.firstOrNull { it == Finnhub.Failure.OFFLINE || it == Finnhub.Failure.NO_ANSWER }
        val anyStale = look.stocks.any { s -> quotes[s.symbol]?.let { it.price != null && stale(it, now) } == true }
        val note = newest?.let { q ->
            val time = t.clock(q.fetchedAt, t.here)
            w.say(SW.NOTE, when {
                failed == Finnhub.Failure.OFFLINE -> w.say(SW.UPDATED_OFFLINE, time)
                failed == Finnhub.Failure.NO_ANSWER -> w.say(SW.UPDATED_NO_ANSWER, time)
                anyStale -> w.say(SW.UPDATED_NOT_CURRENT, time)
                else -> w.say(SW.UPDATED, time)
            })
        }
        return StocksView(lines, market(now, w, t), note)
    }

    // ---- when to ask again -------------------------------------------------------------------------

    /**
     * How long [q] stays as it is before the service is asked again, in milliseconds since it was
     * read; null: not by itself (a refused key waits for another key). While the market is open, two
     * minutes. Closed, the quote read once after the close is the day's last and stays until the next
     * opening. A day the clock says is open but whose quotes keep yesterday's time is a holiday or a
     * quiet morning, and asks every half hour. After a failure: a minute without a network, and less and less often
     * (up to five minutes) while tries go out and reach nothing (a sign-in page, a name that can't be
     * found); five after no answer or being told to slow down (or as long as the service said), six
     * hours for a stock that is not there or not in the free plan.
     */
    fun every(q: Quote, now: Long): Long? = when (q.failure) {
        Finnhub.Failure.REFUSED -> null
        Finnhub.Failure.NO_ACCESS, Finnhub.Failure.NOT_FOUND -> RARE_MS
        Finnhub.Failure.SLOW_DOWN -> maxOf(SLOW_MS, q.retryAfterSec * 1000)
        Finnhub.Failure.NO_ANSWER -> RETRY_MS
        Finnhub.Failure.OFFLINE -> Pace.offline(q.misses, RETRY_MS)
        null -> fresh(q, now)
    }

    /**
     * How long after a try for [q] Refresh has to wait before it asks again: half a minute, and never
     * less than the service asked for after too many requests (five minutes where it named no time).
     */
    fun againAfter(q: Quote?): Long =
        maxOf(if (q?.failure == Finnhub.Failure.SLOW_DOWN) SLOW_MS else MENU_MS, (q?.retryAfterSec ?: 0) * 1000)

    private fun fresh(q: Quote, now: Long): Long {
        val at = Instant.ofEpochMilli(now)
        val read = Instant.ofEpochMilli(q.fetchedAt)
        if (Market.open(at)) {
            val opened = Market.opensOn(Market.day(at))
            if (read.isBefore(opened)) return 0
            return if (quietOpen(q, read, opened)) QUIET_OPEN_MS else OPEN_MS
        }
        val settled = Market.lastClose(at).plusMillis(SETTLE_MS)
        if (read.isBefore(settled)) return if (at.isBefore(settled)) Duration.between(read, settled).toMillis() else 0
        return Duration.between(read, Market.nextOpen(at)).toMillis()
    }

    // ---- what is kept on the device ----------------------------------------------------------------

    /** A quote as the text that is kept on the device: this model, never the reply. */
    fun keep(q: Quote): String = json.encodeToString(Quote.serializer(), q)

    /** What [keep] wrote, or null for anything else. Only numbers are kept, never what a try came to. */
    fun kept(text: String): Quote? = try {
        json.decodeFromString(Quote.serializer(), text).copy(failure = null, misses = 0, retryAfterSec = 0).takeIf { it.price != null }
    } catch (e: Exception) {
        null
    }
}
