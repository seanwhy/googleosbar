package com.meridian11.books.bentobar.items

import androidx.compose.runtime.Immutable
import com.meridian11.books.bentobar.net.Host
import com.meridian11.books.bentobar.net.Reply
import com.meridian11.books.bentobar.net.Request
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.util.Locale

// Finnhub, the stock service the Stocks item asks with the user's own key: its two requests, and what
// a reply or a failure comes to. Pure Kotlin. Nothing here is logged: a key and which stocks someone
// follows pass through this file.

/** A stock found by the search: its symbol as Finnhub writes it ("AAPL", "BRK.B") and the name it gave. */
@Immutable
data class Listing(val symbol: String, val name: String) {
    override fun toString() = "a stock"
}

/** The numbers of one quote reply, as the service sent them. [at]: the quote's own time, in seconds since 1970. */
@Immutable
data class QuoteNumbers(val price: Double, val change: Double?, val pct: Double?, val high: Double?, val low: Double?,
                        val prevClose: Double?, val at: Long)

object Finnhub {
    /** Where a key is got: the user's own act, in the browser. */
    const val SIGN_UP = "https://finnhub.io/register"
    /** The service's site, for the credit in the menu. */
    const val SITE = "https://finnhub.io/"
    const val QUOTE = "/api/v1/quote"
    const val SEARCH = "/api/v1/search"

    /** The most stocks a search lists. */
    const val MOST_FOUND = 8
    /** The longest name kept or shown of a stock, and the longest text sent as a search. */
    const val NAME_CHARS = 60
    const val QUERY_CHARS = 40

    /**
     * Why a try brought no numbers. [REFUSED]: the key was not taken (401). [NO_ACCESS]: the key may
     * not have this (403: outside the free plan). [NOT_FOUND]: the service knows no such stock (it
     * answers with zeros). [SLOW_DOWN]: too many requests (429).
     */
    enum class Failure { OFFLINE, NO_ANSWER, SLOW_DOWN, REFUSED, NO_ACCESS, NOT_FOUND }

    private val SYMBOL = Regex("[A-Z][A-Z0-9]{0,5}(\\.[A-Z])?")

    /**
     * [text] as a US symbol, upper case ("aapl" is "AAPL", "brk.b" is "BRK.B"), or null for what is
     * none: a symbol of another exchange ("SHOP.TO"), a name, anything longer than eight characters.
     * What a layout holds goes through here again before it is sent.
     */
    fun symbol(text: String): String? = text.trim().uppercase(Locale.ROOT).takeIf { it.length <= 8 && SYMBOL.matches(it) }

    /** A quote: the symbol, nothing else (the transport adds the user's key). */
    fun quoteRequest(symbol: String) = Request(Host.FINNHUB, QUOTE, listOf("symbol" to symbol))

    /** The search: the text of the field, limited to US listings (and the transport adds the user's key). No language, nothing of the user. */
    fun searchRequest(text: String) = Request(Host.FINNHUB, SEARCH, listOf("q" to text, "exchange" to "US"))

    /** What a failed request stands for. Null: it was not asked after all (the switch went off, the bar hid). */
    fun failure(reply: Reply.Failed): Failure? = Failures.kind(reply, Failure.OFFLINE, Failure.SLOW_DOWN, Failure.NO_ANSWER) { status ->
        when (status) {
            401 -> Failure.REFUSED
            403 -> Failure.NO_ACCESS
            else -> null
        }
    }

    /** What a quote reply says. */
    sealed interface Read {
        class Numbers(val numbers: QuoteNumbers) : Read
        /** The service knows no such stock: it answers with zeros and no time. */
        data object Unknown : Read
    }

    private fun JsonElement?.number(): Double? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }
    private fun JsonElement?.price(): Double? = number()?.takeIf { it > 0 && it < 1e9 }
    private fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    /**
     * A quote reply, or null for anything that is none: an error's body, a sign-in page, half a reply.
     * A value that is missing or makes no sense is left out; without a price there are no numbers.
     * It never throws.
     */
    fun quote(text: String): Read? = try {
        val o = Json.parseToJsonElement(text) as? JsonObject
        val price = o?.get("c").price()
        val at = o?.get("t").number()?.takeIf { it >= 1 && it < 1e11 }?.toLong()
        when {
            o == null || o["error"] != null -> null
            price == null && (o["c"].number() == 0.0) -> Read.Unknown
            price == null || at == null -> null
            else -> Read.Numbers(QuoteNumbers(price, o["d"].number()?.takeIf { kotlin.math.abs(it) < 1e9 },
                o["dp"].number()?.takeIf { kotlin.math.abs(it) < 1e6 }, o["h"].price(), o["l"].price(), o["pc"].price(), at))
        }
    } catch (e: Exception) {
        null
    } catch (e: StackOverflowError) {
        null
    }

    /**
     * The US stocks a search reply lists, [MOST_FOUND] at most, none twice; empty when nothing
     * matched; null for a reply of another shape. A listing of another exchange is left out, whatever
     * the reply says, since the free plan has no quotes for it.
     */
    fun listings(text: String): List<Listing>? = try {
        val o = Json.parseToJsonElement(text) as? JsonObject
        val result = o?.get("result")
        when {
            o == null || o["error"] != null -> null
            result == null -> emptyList()
            result !is JsonArray -> null
            else -> result.asSequence().take(100).mapNotNull { e ->
                val r = e as? JsonObject ?: return@mapNotNull null
                val symbol = r["symbol"].text()?.let { s -> symbol(s)?.takeIf { it == s } } ?: return@mapNotNull null
                Listing(symbol, TextRules.oneLine(r["description"].text().orEmpty(), NAME_CHARS).ifEmpty { symbol })
            }.distinctBy { it.symbol }.take(MOST_FOUND).toList()
        }
    } catch (e: Exception) {
        null
    } catch (e: StackOverflowError) {
        null
    }
}
