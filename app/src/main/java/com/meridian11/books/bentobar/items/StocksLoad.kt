package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.data.Kept
import com.meridian11.books.bentobar.data.Online
import com.meridian11.books.bentobar.net.Http
import com.meridian11.books.bentobar.net.Reply

// Everything of the Stocks item that goes online or keeps something: the two requests, what a reply or
// a failure becomes, the last quote of each stock on the device. No Android, and nothing here is
// logged: a key and the stocks someone follows pass through this file.

/** One press of Search: the text that is sent, and which field asked ([by]), so that an answer shows only where it was asked for. */
class StockQuery(val text: String, val by: String) {
    override fun toString() = "a search"
}

/** What a search came to. */
sealed interface StockFound {
    /** The US stocks the service found, eight at most. None: "Nothing found". */
    class Listings(val list: List<Listing>) : StockFound
    class Failed(val failure: Finnhub.Failure) : StockFound
    /** Not asked at all (no key, the switch off, nothing shows items): nothing is shown for it. */
    data object Unasked : StockFound
}

/**
 * What leaves the device: a stock's symbol for its quote, the text of a search, and the user's own
 * key, to api.finnhub.io, and nothing else. Never without a key, never while the service is switched
 * off: asked here before any request, though the request code would refuse too.
 *
 * What is kept, outside the layout (so in no backup and no copied settings): the last quote of each
 * stock the layout follows, as [Quote]. Switching the service off deletes it. The key itself is
 * `Online`'s and is added to the request by the transport; it goes into nothing that is kept, shown or said.
 */
object StocksLoad {
    private val service = Online.Service.FINNHUB

    /** The symbols the layout's Stocks items follow: what a quote is kept for. */
    fun symbols(items: List<ItemConfig>): Set<String> =
        items.filter { it.type == "stocks" }.flatMapTo(HashSet()) { StocksRules.stocks(it).map(Stock::symbol) }

    private fun mayAsk() = Online.hasKey(service) && Online.on(service)

    /**
     * Asks the service for [symbol]'s quote; blocks, so only a background load calls it. A good answer
     * is a new [Quote], kept on the device. A failure is a quote too: [last]'s numbers with what went
     * wrong. Null: it was not asked after all. [now]: the wall clock; [symbols]: the layout's right now.
     */
    fun load(symbol: String, last: Quote?, now: Long, symbols: () -> Set<String>): Quote? {
        if (!mayAsk()) return null
        val sym = Finnhub.symbol(symbol) ?: return null
        val reply = Http.get(Finnhub.quoteRequest(sym))
        // Without a network the request helper answers by itself and nothing leaves the device.
        val went = reply.sent
        return when (reply) {
            is Reply.Ok -> when (val read = Finnhub.quote(reply.text)) {
                is Finnhub.Read.Numbers -> {
                    val n = read.numbers
                    Quote(sym, n.price, n.change, n.pct, n.high, n.low, n.prevClose, n.at, now).also { keep(it, symbols()) }
                }
                Finnhub.Read.Unknown -> failed(sym, last, Finnhub.Failure.NOT_FOUND, went)
                // A reply that is no quote: a public network's sign-in page, half a reply.
                null -> failed(sym, last, Finnhub.Failure.NO_ANSWER, went)
            }
            is Reply.Failed -> Finnhub.failure(reply)?.let { failed(sym, last, it, went, reply.retryAfterSec ?: 0) }
        }
    }

    /** What a failed try leaves: the numbers that were there for [symbol] (never another's), with what went wrong. */
    fun failed(symbol: String, last: Quote?, failure: Finnhub.Failure, went: Boolean, waitSec: Long = 0): Quote {
        val known = last?.takeIf { it.symbol == symbol } ?: Quote(symbol)
        return known.copy(failure = failure, misses = known.misses + if (went) 1 else 0, retryAfterSec = waitSec)
    }

    /** Keeps a good quote for a restart, and removes what no item follows any more. While the service is off nothing is written. */
    private fun keep(q: Quote, symbols: Set<String>) {
        try {
            val kept = Kept.fetched(service)
            if (q.symbol in symbols) kept.write(q.symbol, StocksRules.keep(q), q.fetchedAt)
            kept.keepOnly(symbols)
        } catch (e: Exception) {
            // Nothing kept: the next start asks the service instead.
        }
    }

    /** "Deleted with the item": what is kept for a symbol that is not among [symbols] goes. Disk work, for a background thread. */
    fun tidy(symbols: Set<String>) {
        try { Kept.fetched(service).keepOnly(symbols) } catch (e: Exception) { /* nothing there to remove */ }
    }

    /** What was kept for [symbol] and how old it is by the wall clock, so that a restart shows the last quote and asks nobody while it is fresh. */
    fun kept(symbol: String, now: Long): Refresher.Restored<Quote>? = try {
        val kept = Kept.fetched(service)
        val entry = kept.read(symbol)
        val q = entry?.let { StocksRules.kept(it.text) }?.takeIf { it.symbol == symbol }
        if (entry != null && q == null) kept.remove(symbol)
        q?.let { Refresher.Restored(it, (now - it.fetchedAt).coerceAtLeast(0)) }
    } catch (e: Exception) {
        null
    }

    /** Asks the service for US stocks matching [text]; blocks. */
    fun search(text: String): StockFound {
        if (!mayAsk()) return StockFound.Unasked
        return when (val reply = Http.get(Finnhub.searchRequest(text))) {
            is Reply.Ok -> Finnhub.listings(reply.text)?.let { StockFound.Listings(it) } ?: StockFound.Failed(Finnhub.Failure.NO_ANSWER)
            is Reply.Failed -> Finnhub.failure(reply)?.let { StockFound.Failed(it) } ?: StockFound.Unasked
        }
    }

    /** The text a search sends for what was typed: one short line, or null for less than one character. */
    fun query(text: String): String? = TextRules.oneLine(text, Finnhub.QUERY_CHARS).trim().takeIf { it.isNotEmpty() }
}
