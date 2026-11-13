package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.util.Sym
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.abs

/**
 * Pure rules for the Exchange rates item: parsing Frankfurter's ECB rate table, cross-rate and
 * daily-change math for any currency pair, rotation in the bar, and inline conversion.
 * No Android imports, and no logging.
 */
object FxRules {
    const val FRESH_MS = 60 * 60_000L
    const val MENU_MS = 15 * 60_000L
    /** After no answer. */
    const val RETRY_MS = 15 * 60_000L
    /** After being told to slow down, unless the service named a longer time. */
    const val SLOW_MS = 60 * 60_000L
    const val BAR_CHARS = 20
    const val MOST_PAIRS = 6
    const val TURN_SEC = 5
    val TURNS = listOf(3, 5, 10, 30, 60)

    // What can be added, by what the central bank publishes. One it stops publishing (BGN, 2026) stays here: a layout that follows it keeps the pair, and the item says the rate is missing ([missing]).
    val CURRENCIES: Set<String> = setOf(
        "EUR", "USD", "GBP", "JPY", "CHF", "CAD", "AUD", "NZD", "CNY", "HKD", "SGD",
        "SEK", "NOK", "DKK", "KRW", "INR", "MXN", "BRL", "ZAR", "PLN", "CZK", "HUF",
        "TRY", "ILS", "THB", "MYR", "PHP", "IDR", "ISK", "RON", "BGN",
    )

    const val DEFAULT_PAIRS = "EUR/USD,USD/JPY,GBP/USD"

    data class FxPair(val base: String, val quote: String) {
        val code: String get() = "$base/$quote"
    }

    data class FxTable(
        /** Rates per 1 EUR on the latest date (`EUR` itself is 1.0). */
        val latest: Map<String, Double> = emptyMap(),
        /** Rates per 1 EUR on the previous business day, when available. */
        val previous: Map<String, Double> = emptyMap(),
        val date: String = "",
        val fetchedAt: Long = 0,
        val failure: Failure? = null,
        /** How many tries in a row went out and failed. */
        val misses: Int = 0,
        /** How long the service asked to be left alone. */
        val retryAfterSec: Long = 0,
    ) {
        val hasRates: Boolean get() = latest.size > 1
        override fun toString() = "FxTable(hasRates=$hasRates, failure=$failure)"
    }

    data class PairQuote(
        val pair: FxPair,
        val rate: Double,
        val changePct: Double,
        val formattedRate: String,
        val formattedPct: String,
    )

    class Bar(
        val icon: String,
        val filled: Boolean,
        val text: String?,
        val turns: List<String>?,
        val tone: Tone,
        val active: Boolean,
        val desc: String,
        val tooltip: String,
    )

    /** Parses a user-entered currency pair such as `"eur/usd"`, `"EUR USD"`, or `"EUR-USD"`. */
    fun parsePair(raw: String): FxPair? {
        val cleaned = raw.trim().uppercase(Locale.ROOT).replace('-', '/').replace(Regex("\\s+"), "/")
        val parts = cleaned.split('/').filter { it.isNotEmpty() }
        if (parts.size != 2) return null
        val (b, q) = parts
        if (b == q || b !in CURRENCIES || q !in CURRENCIES) return null
        return FxPair(b, q)
    }

    fun pairs(item: ItemConfig): List<FxPair> {
        val raw = item.options["pairs"] ?: DEFAULT_PAIRS
        if (raw.isBlank()) return emptyList()
        return raw.split(',').mapNotNull(::parsePair).distinct().take(MOST_PAIRS)
    }

    fun withPairs(item: ItemConfig, list: List<FxPair>): ItemConfig {
        val clean = list.distinct().take(MOST_PAIRS)
        return item.with("pairs", if (clean.isEmpty()) "" else clean.joinToString(",") { it.code })
    }

    fun addPair(item: ItemConfig, pair: FxPair): ItemConfig {
        val current = pairs(item)
        if (pair in current || current.size >= MOST_PAIRS) return item
        return withPairs(item, current + pair)
    }

    fun removePair(item: ItemConfig, pair: FxPair): ItemConfig = withPairs(item, pairs(item).filterNot { it == pair })

    fun movePair(item: ItemConfig, pair: FxPair, delta: Int): ItemConfig {
        val list = pairs(item).toMutableList()
        val i = list.indexOf(pair)
        val target = i + delta
        if (i < 0 || target !in list.indices) return item
        list[i] = list[target].also { list[target] = list[i] }
        return withPairs(item, list)
    }

    fun turnSec(item: ItemConfig): Int = item.optInt("turnSec", TURN_SEC).let { if (it in TURNS) it else TURN_SEC }

    /** Start date 10 calendar days before [nowMs] in UTC, so weekends/holidays still yield at least 2 business days. */
    fun startDate(nowMs: Long): String =
        Instant.ofEpochMilli(nowMs).atZone(ZoneOffset.UTC).toLocalDate().minusDays(10).toString()

    /**
     * Frankfurter's `/v2/rates` reply: a list with one row per currency and day (`date`, `base`, `quote`, `rate`), in no
     * order this relies on. The newest date gives the latest rates and the one before it the day's move. Null for
     * anything that is no such list: an error's body, a sign-in page, half a reply. A row that makes no sense is left out.
     */
    fun read(jsonText: String, now: Long): FxTable? = try {
        val rows = Json.parseToJsonElement(jsonText) as? JsonArray
        if (rows == null) null else {
            val days = HashMap<String, HashMap<String, Double>>()
            var base = "EUR"
            for (r in rows) {
                val o = r as? JsonObject ?: continue
                val date = (o["date"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { DATE.matches(it) } ?: continue
                val quote = (o["quote"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
                val rate = (o["rate"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it > 0.0 && it.isFinite() } ?: continue
                (o["base"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { base = it }
                days.getOrPut(date) { HashMap() }[quote] = rate
            }
            val dates = days.keys.sorted()
            val latest = dates.lastOrNull()?.let { days[it] }
            if (latest == null) null else {
                val day = HashMap(latest).apply { this[base] = 1.0 }
                val before = dates.getOrNull(dates.size - 2)?.let { d -> HashMap(days[d]!!).apply { this[base] = 1.0 } }.orEmpty()
                if (day.size <= 1) null else FxTable(day, before, dates.last(), fetchedAt = now)
            }
        }
    } catch (e: Exception) {
        null
    } catch (e: StackOverflowError) {
        null
    }

    private val DATE = Regex("""\d{4}-\d{2}-\d{2}""")

    // Figures in the device's own way of writing numbers, as Stocks does: a decimal comma where the keyboard offers one.
    fun formatRate(rate: Double, locale: Locale = Locale.getDefault()): String = when {
        rate >= 100.0 -> String.format(locale, "%.2f", rate)
        rate >= 10.0 -> String.format(locale, "%.3f", rate)
        else -> String.format(locale, "%.4f", rate)
    }

    fun formatPct(pct: Double, locale: Locale = Locale.getDefault()): String = String.format(locale, "%.2f", abs(pct))

    fun formatConverted(amount: Double, rate: Double, locale: Locale = Locale.getDefault()): String {
        val v = amount * rate
        return if (v >= 100.0) String.format(locale, "%,.2f", v) else String.format(locale, "%.2f", v)
    }

    /** An amount the user typed, for the line that repeats it: no grouping and no trailing zeros. */
    fun formatAmount(amount: Double, locale: Locale = Locale.getDefault()): String =
        NumberFormat.getNumberInstance(locale).apply { isGroupingUsed = false; maximumFractionDigits = 8 }.format(amount)

    fun quoteFor(pair: FxPair, table: FxTable, locale: Locale = Locale.getDefault()): PairQuote? {
        val baseRate = table.latest[pair.base] ?: return null
        val quoteRate = table.latest[pair.quote] ?: return null
        if (baseRate <= 0.0) return null
        val cross = quoteRate / baseRate
        val prevBase = table.previous[pair.base]
        val prevQuote = table.previous[pair.quote]
        val changePct = if (prevBase != null && prevQuote != null && prevBase > 0.0) {
            val prevCross = prevQuote / prevBase
            if (prevCross > 0.0) ((cross - prevCross) / prevCross) * 100.0 else 0.0
        } else 0.0
        return PairQuote(pair, cross, changePct, formatRate(cross, locale), formatPct(changePct, locale))
    }

    fun quotesFor(pairs: List<FxPair>, table: FxTable): List<PairQuote> =
        pairs.mapNotNull { quoteFor(it, table) }

    /** The pairs [table] has no rate for (BGN since the European Central Bank stopped publishing it): named by the item, never just left out. */
    fun missing(pairs: List<FxPair>, table: FxTable): List<FxPair> = pairs.filter { quoteFor(it, table) == null }

    /** The day of [table]'s rates, or null when it has none that can be read. */
    private fun dateOf(table: FxTable): LocalDate? = runCatching { LocalDate.parse(table.date) }.getOrNull()

    /**
     * A move counts as news only while its table is from today (UTC), or from yesterday when today is a weekday: the bank
     * publishes once a working day, so before it has published (early on a weekday) the newest table is yesterday's, but at the
     * weekend nothing new is coming and a Friday table's move is not this weekend's. The rate itself stays.
     */
    fun bigMove(quotes: List<PairQuote>, table: FxTable, thresholdPct: Double, now: Long): Boolean {
        val date = dateOf(table) ?: return false
        val today = Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).toLocalDate()
        val weekday = today.dayOfWeek != DayOfWeek.SATURDAY && today.dayOfWeek != DayOfWeek.SUNDAY
        return !date.isBefore(if (weekday) today.minusDays(1) else today) && quotes.any { abs(it.changePct) >= thresholdPct }
    }

    /** The day of [table]'s rates when that isn't today where the device is ([zone]), to go with the numbers; null when it is, or when the table has no date. */
    fun notToday(table: FxTable, now: Long, zone: ZoneId): LocalDate? =
        dateOf(table)?.takeIf { it != Instant.ofEpochMilli(now).atZone(zone).toLocalDate() }

    /**
     * The amount [raw] says, in the way [locale] writes numbers; null for anything else. Read by hand rather than with
     * NumberFormat, which takes "100,50" for 10050 where a comma groups thousands: a separator counts only where the
     * locale's grouping can stand ([WRITTEN]). A space groups where the locale's grouping is a kind of space.
     */
    fun parseAmount(raw: String, locale: Locale = Locale.getDefault()): Double? {
        val symbols = DecimalFormatSymbols.getInstance(locale)
        val groups = symbols.groupingSeparator
        val plain = StringBuilder(raw.length)
        for (c in raw.trim()) {
            val digit = Character.digit(c, 10)
            plain.append(when {
                digit >= 0 -> '0' + digit
                c == symbols.decimalSeparator -> '.'
                c == groups || (groups.isWhitespace() && c.isWhitespace()) -> ','
                else -> return null
            })
        }
        val text = plain.toString()
        if (!WRITTEN.matches(text)) return null
        return text.replace(",", "").toDoubleOrNull()?.takeIf { it > 0.0 && it <= 1_000_000_000.0 && it.isFinite() }
    }

    /** An amount with its separators made plain: whole, or grouped from the end in threes (twos, further left, in India), with its decimals. */
    private val WRITTEN = Regex("""\d+(\.\d*)?|\.\d+|\d{1,3}(,\d{2,3})*,\d{3}(\.\d*)?""")

    /** An hour after a good table; a failure at its own pace, as the other online items. */
    fun every(table: FxTable): Long = if (table.failure == null) FRESH_MS else Pace.after(table.failure, table.misses, table.retryAfterSec, RETRY_MS, SLOW_MS)

    /** Debug samples (`./bento debug fx stage calm|big|offline|old`). */
    fun sample(name: String, now: Long): FxTable? = when (name) {
        "calm" -> FxTable(
            latest = mapOf("EUR" to 1.0, "USD" to 1.0942, "JPY" to 163.40, "GBP" to 0.8340, "CHF" to 0.9380, "CAD" to 1.5020),
            previous = mapOf("EUR" to 1.0, "USD" to 1.0915, "JPY" to 162.90, "GBP" to 0.8355, "CHF" to 0.9370, "CAD" to 1.4990),
            date = LocalDate.now(ZoneOffset.UTC).toString(),
            fetchedAt = now - 120_000,
        )
        "big" -> FxTable(
            latest = mapOf("EUR" to 1.0, "USD" to 1.1120, "JPY" to 160.10, "GBP" to 0.8420, "CHF" to 0.9310, "CAD" to 1.5180),
            previous = mapOf("EUR" to 1.0, "USD" to 1.0910, "JPY" to 163.80, "GBP" to 0.8350, "CHF" to 0.9380, "CAD" to 1.5000),
            date = LocalDate.now(ZoneOffset.UTC).toString(),
            fetchedAt = now - 60_000,
        )
        "offline" -> FxTable(
            latest = mapOf("EUR" to 1.0, "USD" to 1.0942, "JPY" to 163.40, "GBP" to 0.8340),
            previous = mapOf("EUR" to 1.0, "USD" to 1.0915, "JPY" to 162.90, "GBP" to 0.8355),
            date = LocalDate.now(ZoneOffset.UTC).toString(),
            fetchedAt = now - 900_000,
            failure = Failure.OFFLINE,
        )
        "old" -> FxTable(
            latest = mapOf("EUR" to 1.0, "USD" to 1.0942, "JPY" to 163.40, "GBP" to 0.8340),
            previous = mapOf("EUR" to 1.0, "USD" to 1.0812, "JPY" to 162.90, "GBP" to 0.8355),
            date = LocalDate.now(ZoneOffset.UTC).minusDays(3).toString(),
            fetchedAt = now - 300_000,
        )
        else -> null
    }
}
