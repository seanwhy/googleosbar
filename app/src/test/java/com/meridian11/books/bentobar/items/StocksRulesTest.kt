package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.util.Sym
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime
import java.util.Locale

/**
 * What the Stocks item shows in the bar and the menu, when the market is open and when a quote is
 * asked again, to the character. The words are the app's own, read from its text files. Times are
 * New York's, in October 2026: Wednesday the 7th, Friday the 9th, the weekend of the 10th and 11th.
 */
class StocksRulesTest {
    private val w = StocksFileWords()
    private val t = StocksFileWords.times(Market.ZONE)
    private val min = 60_000L
    private val hour = 60 * min

    private fun ny(day: Int, h: Int, m: Int = 0, month: Int = 10): Long =
        ZonedDateTime.of(2026, month, day, h, m, 0, 0, Market.ZONE).toInstant().toEpochMilli()

    private val wed11 = ny(7, 11)

    /** A quote read at [read], of a trade at [tradedAt]: price and change in percent, the change in dollars and the last close worked out from them. */
    private fun q(symbol: String, price: Double, pct: Double?, read: Long = wed11, tradedAt: Long = read - min, failure: Finnhub.Failure? = null): Quote {
        val prev = if (pct == null) null else price / (1 + pct / 100)
        return Quote(symbol, price, prev?.let { price - it }, pct, price * 1.01, price * 0.98, prev, tradedAt / 1000, read, failure)
    }

    private val aapl = Stock("AAPL", "APPLE INC")
    private val msft = Stock("MSFT", "MICROSOFT CORP")
    private val nvda = Stock("NVDA", "NVIDIA CORP")

    private fun look(vararg stocks: Stock, move: Int = 3, turnSec: Int = 5) = StocksLook(stocks.toList(), move, Locale.US, turnSec)

    private fun bar(look: StocksLook, vararg quotes: Quote, now: Long = wed11, status: StocksStatus = StocksStatus.Live) =
        StocksRules.bar(status, look, quotes.associateBy { it.symbol }, now, w)

    /** The moment of [now]'s round that shows the stock at [index] of [count], each for [sec] seconds. */
    private fun turn(index: Int, count: Int, now: Long = wed11, sec: Int = 5): Long {
        val base = now - now % (sec * 1000L * count)
        return base + index * sec * 1000L
    }

    // ---- the bar: one stock at a time ----------------------------------------------------------------

    @Test fun aStockIsItsSymbolPriceAndTheDaysMove() {
        val b = bar(look(aapl), q("AAPL", 227.52, 1.2))
        assertEquals("AAPL 227.52 ▲1.2%", b.text)
        assertEquals(Sym.TRENDING_UP, b.icon)
        assertTrue(b.filled)
        assertNull(b.turns)
        assertEquals("APPLE INC", b.tooltip)
        assertEquals("US stocks: AAPL 227.52, up 1.2 percent.", b.desc)
        assertEquals("MSFT 418.07 ▼0.4%", bar(look(msft), q("MSFT", 418.07, -0.4)).text)
        assertEquals(Sym.TRENDING_DOWN, bar(look(msft), q("MSFT", 418.07, -0.4)).icon)
    }

    @Test fun aMoveThatReadsZeroIsNoMove() {
        val b = bar(look(aapl), q("AAPL", 227.52, 0.04))
        assertEquals("AAPL 227.52 0.0%", b.text)
        assertEquals(Sym.TRENDING_FLAT, b.icon)
        assertEquals("US stocks: AAPL 227.52, unchanged.", b.desc)
        assertEquals("AAPL 227.52 ▼0.1%", bar(look(aapl), q("AAPL", 227.52, -0.06)).text)
        assertEquals(Sym.TRENDING_FLAT, bar(look(aapl), q("AAPL", 227.52, -0.05)).icon) // "0.0", rounded half to even
    }

    @Test fun pricesAreWrittenForTheRoomTheBarHas() {
        assertEquals("AZO 3,123 ▲0.4%", bar(look(Stock("AZO", "AUTOZONE")), q("AZO", 3123.4, 0.4)).text)
        assertEquals("SIRI 0.4512 ▼3.1%", bar(look(Stock("SIRI", "SIRIUS")), q("SIRI", 0.4512, -3.1)).text)
        assertEquals("GME 30.10 ▲142%", bar(look(Stock("GME", "GAMESTOP")), q("GME", 30.1, 142.0)).text)
        assertEquals("BRK.A 712,345 ▲0.1%", bar(look(Stock("BRK.A", "BERKSHIRE")), q("BRK.A", 712_345.0, 0.1)).text)
    }

    @Test fun longerThanTwentyCharactersThePriceGoes() {
        val long = Stock("ABCDEF.G", "A LONG ONE")
        assertEquals("ABCDEF.G ▲99.9%", bar(look(long), q("ABCDEF.G", 999.99, 99.9)).text)
        assertEquals("ABCDEF 999.99 ▲99.9%", bar(look(Stock("ABCDEF", "x")), q("ABCDEF", 999.99, 99.9)).text) // exactly twenty
    }

    @Test fun severalStocksTakeTurnsFiveSecondsEach() {
        val l = look(aapl, msft, nvda)
        val quotes = arrayOf(q("AAPL", 227.52, 1.2), q("MSFT", 418.07, -0.4), q("NVDA", 131.26, 2.06))
        val texts = (0 until 3).map { bar(l, *quotes, now = turn(it, 3)).text }
        assertEquals(listOf("AAPL 227.52 ▲1.2%", "MSFT 418.07 ▼0.4%", "NVDA 131.26 ▲2.1%"), texts)
        // The fourth turn is the first again, and every turn is the same for five seconds.
        assertEquals(texts[0], bar(l, *quotes, now = turn(3, 3)).text)
        assertEquals(texts[1], bar(l, *quotes, now = turn(1, 3) + 4_999).text)
        // Every text the item takes turns with, so that its place is as wide as the widest.
        assertEquals(texts, bar(l, *quotes).turns)
        // The glyph and the tooltip are the turn's; what is spoken is all of them at once.
        val second = bar(l, *quotes, now = turn(1, 3))
        assertEquals(Sym.TRENDING_DOWN, second.icon)
        assertEquals("MICROSOFT CORP", second.tooltip)
        assertEquals("US stocks: AAPL 227.52, up 1.2 percent; MSFT 418.07, down 0.4 percent; NVDA 131.26, up 2.1 percent.", second.desc)
    }

    @Test fun eachStockShowsForAsLongAsTheItemChose() {
        val l = look(aapl, msft, turnSec = 10)
        val quotes = arrayOf(q("AAPL", 227.52, 1.2), q("MSFT", 418.07, -0.4))
        assertEquals("AAPL 227.52 ▲1.2%", bar(l, *quotes, now = turn(0, 2, sec = 10)).text)
        assertEquals("AAPL 227.52 ▲1.2%", bar(l, *quotes, now = turn(0, 2, sec = 10) + 9_999).text)
        assertEquals("MSFT 418.07 ▼0.4%", bar(l, *quotes, now = turn(1, 2, sec = 10)).text)
        // Five seconds is the default; another choice is stored, and what is no choice is five.
        assertEquals(5, StocksRules.turnSec(item))
        assertEquals(30, StocksRules.turnSec(item.with("turnSec", "30")))
        assertEquals(60, StocksRules.turnSec(item.with("turnSec", "60")))
        for (bad in listOf("0", "7", "-5", "1000000", "x")) assertEquals(bad, 5, StocksRules.turnSec(item.with("turnSec", bad)))
        assertEquals(listOf(3, 5, 10, 30, 60), StocksRules.TURNS)
    }

    @Test fun aStockWithoutAPriceYetHasNoTurn() {
        val l = look(aapl, msft, nvda)
        val b = bar(l, q("AAPL", 227.52, 1.2), Quote("MSFT", failure = Finnhub.Failure.NO_ANSWER), q("NVDA", 131.26, 2.06))
        assertEquals(listOf("AAPL 227.52 ▲1.2%", "NVDA 131.26 ▲2.1%"), b.turns)
        // A stock that was priced before and failed since keeps its price.
        val kept = q("MSFT", 418.07, -0.4).copy(failure = Finnhub.Failure.OFFLINE)
        assertEquals(3, bar(l, q("AAPL", 227.52, 1.2), kept, q("NVDA", 131.26, 2.06)).turns!!.size)
    }

    @Test fun aQuoteWithoutAChangeIsItsPrice() {
        val b = bar(look(aapl), q("AAPL", 227.52, null))
        assertEquals("AAPL 227.52", b.text)
        assertEquals(Sym.TRENDING_FLAT, b.icon)
        assertEquals("US stocks: AAPL 227.52.", b.desc)
    }

    // ---- the bar: the states without prices ------------------------------------------------------------

    @Test fun withoutKeySwitchedOffOrEmptyItIsAnOutlinedChartWithoutText() {
        for ((status, desc) in listOf(StocksStatus.NoKey to "US stocks: not set up", StocksStatus.Off to "US stocks: off", StocksStatus.NoStocks to "US stocks: none added")) {
            val b = bar(look(aapl), q("AAPL", 227.52, 1.2), status = status)
            assertEquals(Sym.SHOW_CHART, b.icon)
            assertFalse(b.filled)
            assertNull(b.text)
            assertEquals(desc, b.desc)
            assertFalse(b.active)
        }
    }

    @Test fun noPriceYetIsLoadingAndOnlyFailuresAreNoPrices() {
        assertEquals("US stocks: loading", bar(look(aapl, msft)).desc)
        assertNull(bar(look(aapl, msft)).text)
        assertEquals("US stocks: no prices", bar(look(aapl, msft), Quote("AAPL", failure = Finnhub.Failure.REFUSED)).desc)
    }

    @Test fun theStatusIsTheKeyThenTheSwitchThenTheStocks() {
        assertEquals(StocksStatus.NoKey, StocksRules.status(keyed = false, on = true, any = true))
        assertEquals(StocksStatus.Off, StocksRules.status(keyed = true, on = false, any = true))
        assertEquals(StocksStatus.NoStocks, StocksRules.status(keyed = true, on = true, any = false))
        assertEquals(StocksStatus.Live, StocksRules.status(keyed = true, on = true, any = true))
    }

    // ---- show when a stock moves -------------------------------------------------------------------------

    @Test fun aStockThatMovesThreePercentTodayBringsTheItemOut() {
        assertFalse(bar(look(aapl, msft), q("AAPL", 227.52, 2.9), q("MSFT", 418.07, -0.4)).active)
        val b = bar(look(aapl, msft), q("AAPL", 227.52, 1.2), q("MSFT", 418.07, -3.0))
        assertTrue(b.active)
        assertEquals(Tone.ACCENT, b.tone)
        assertTrue(bar(look(aapl, move = 1), q("AAPL", 227.52, 1.0)).active)
    }

    @Test fun fridaysMoveDoesNotBringItOutOnSaturday() {
        val friday = q("AAPL", 227.52, 6.0, read = ny(9, 16, 10), tradedAt = ny(9, 16, 0))
        assertTrue(bar(look(aapl), friday, now = ny(9, 17)).active)
        val saturday = bar(look(aapl), friday, now = ny(10, 11))
        assertFalse(saturday.active)
        assertEquals("AAPL 227.52 ▲6.0%", saturday.text)
    }

    @Test fun anOldQuoteShowsAsNotCurrent() {
        // Ten minutes is still live; past that while the market is open, or read before today's opening, it claims no move or tone.
        assertFalse(StocksRules.stale(q("AAPL", 227.52, 5.0, read = wed11 - 10 * min), wed11))
        val old = q("AAPL", 227.52, 5.0, read = wed11 - 10 * min - 1)
        assertTrue(StocksRules.stale(old, wed11))
        val b = bar(look(aapl), old)
        assertEquals("AAPL 227.52", b.text)
        assertEquals(Sym.TRENDING_FLAT, b.icon)
        assertFalse(b.filled)
        assertFalse(b.active)
        assertEquals(Tone.NORMAL, b.tone)
        assertEquals("APPLE INC · not current", b.tooltip)
        assertEquals("US stocks: AAPL 227.52, not current.", b.desc)
        // Yesterday's quote still there after today's opening is not current either, even if read a moment before the bell.
        val beforeBell = q("AAPL", 227.52, 5.0, read = ny(7, 9, 28), tradedAt = ny(6, 16))
        assertFalse(StocksRules.stale(beforeBell, ny(7, 9, 29)))
        assertTrue(StocksRules.stale(beforeBell, ny(7, 9, 31)))
        assertEquals("AAPL 227.52", bar(look(aapl), beforeBell, now = ny(7, 9, 31)).text)
        // Closed, the quote read after the last close stays until the next opening; one from midday (offline since) is not the day's close.
        assertFalse(StocksRules.stale(q("AAPL", 227.52, 5.0, read = ny(7, 15, 58)), ny(7, 16, 4)))
        assertTrue(StocksRules.stale(old, ny(7, 18)))
        assertFalse(bar(look(aapl), old, now = ny(7, 18)).active)
        assertTrue(StocksRules.stale(old, ny(10, 12)))
        // In the menu, a stale quote has no color on its change, and says that it is not current.
        val v = StocksRules.menu(look(aapl), mapOf("AAPL" to old), wed11, w, t)
        assertNull(v.lines[0].up)
        assertEquals("Not current", v.lines[0].problem)
        assertEquals("AAPL 227.52; APPLE INC; Not current", v.lines[0].desc)
        assertEquals("Prices by Finnhub · not current, updated 10:49 AM", v.note)
    }

    // ---- the market's hours ----------------------------------------------------------------------------

    @Test fun theMarketIsOpenFromHalfPastNineToFourOnWeekdays() {
        assertTrue(Market.open(java.time.Instant.ofEpochMilli(ny(7, 9, 30))))
        assertFalse(Market.open(java.time.Instant.ofEpochMilli(ny(7, 9, 29))))
        assertTrue(Market.open(java.time.Instant.ofEpochMilli(ny(7, 15, 59))))
        assertFalse(Market.open(java.time.Instant.ofEpochMilli(ny(7, 16, 0))))
        assertFalse(Market.open(java.time.Instant.ofEpochMilli(ny(10, 12))))
        assertFalse(Market.open(java.time.Instant.ofEpochMilli(ny(11, 12))))
    }

    @Test fun theNextOpeningAndTheLastClosing() {
        fun at(ms: Long) = java.time.Instant.ofEpochMilli(ms)
        assertEquals(at(ny(7, 9, 30)), Market.nextOpen(at(ny(7, 8))))
        assertEquals(at(ny(8, 9, 30)), Market.nextOpen(at(ny(7, 17))))
        assertEquals(at(ny(12, 9, 30)), Market.nextOpen(at(ny(9, 17))))
        assertEquals(at(ny(12, 9, 30)), Market.nextOpen(at(ny(10, 12))))
        assertEquals(at(ny(9, 16)), Market.lastClose(at(ny(12, 8))))
        assertEquals(at(ny(7, 16)), Market.lastClose(at(ny(7, 17))))
        assertEquals(at(ny(6, 16)), Market.lastClose(at(ny(7, 12))))
    }

    @Test fun theMarketsLineSaysWhenItClosesOrOpens() {
        assertEquals("Market open · closes 4:00 PM", StocksRules.market(wed11, w, t))
        assertEquals("Market closed · opens 9:30 AM", StocksRules.market(ny(7, 8), w, t))
        assertEquals("Market closed · opens Thu 9:30 AM", StocksRules.market(ny(7, 17), w, t))
        assertEquals("Market closed · opens Mon 9:30 AM", StocksRules.market(ny(10, 12), w, t))
        // In the device's own time: a Googlebook in Los Angeles sees 6:30 AM.
        assertEquals("Market closed · opens 6:30 AM", StocksRules.market(ny(7, 8), w, StocksFileWords.times(java.time.ZoneId.of("America/Los_Angeles"))))
    }

    // ---- when a quote is asked again ---------------------------------------------------------------------

    @Test fun whileTheMarketIsOpenEveryTwoMinutes() {
        assertEquals(2 * min, StocksRules.every(q("AAPL", 227.52, 1.2), wed11))
        // A quote read right before the bell is asked again as soon as the market opens.
        assertEquals(0L, StocksRules.every(q("AAPL", 227.52, 1.2, read = ny(7, 9, 29), tradedAt = ny(6, 16)), ny(7, 9, 30)))
        // Just after the opening, a quote with yesterday's time is the morning before the first trade.
        assertEquals(2 * min, StocksRules.every(q("AAPL", 227.52, 1.2, read = ny(7, 9, 35), tradedAt = ny(6, 16)), ny(7, 9, 36)))
    }

    @Test fun closedTheQuoteReadAfterTheCloseStaysUntilTheNextOpening() {
        val evening = q("AAPL", 227.52, 1.2, read = ny(7, 16, 10), tradedAt = ny(7, 16))
        assertEquals(17 * hour + 20 * min, StocksRules.every(evening, ny(7, 20)))
        val friday = q("AAPL", 227.52, 1.2, read = ny(9, 16, 10), tradedAt = ny(9, 16))
        assertEquals(ny(12, 9, 30) - ny(9, 16, 10), StocksRules.every(friday, ny(10, 12)))
    }

    @Test fun aQuoteReadBeforeTheCloseIsAskedOnceTheDaySettled() {
        val before = q("AAPL", 227.52, 1.2, read = ny(7, 15, 58))
        assertEquals(7 * min, StocksRules.every(before, ny(7, 16, 2)))
        assertEquals(0L, StocksRules.every(before, ny(7, 16, 30)))
        // After a night with the lid closed, Wednesday's afternoon quote is asked for on Thursday morning.
        assertEquals(0L, StocksRules.every(before, ny(8, 8)))
    }

    @Test fun aQuietOpeningOrHolidayAsksEveryHalfHour() {
        // Thanksgiving, Thursday 26 November (or a stock halted in the morning): the clock says open, the quote still has Wednesday's close.
        val thanksgiving = q("AAPL", 227.52, 1.2, read = ny(26, 10, month = 11), tradedAt = ny(25, 16, month = 11))
        assertEquals(30 * min, StocksRules.every(thanksgiving, ny(26, 10, 1, month = 11)))
        assertFalse(StocksRules.stale(thanksgiving, ny(26, 10, 25, month = 11)))
        assertFalse(StocksRules.stale(thanksgiving.copy(failure = Finnhub.Failure.OFFLINE), ny(26, 10, 35, month = 11)))
        assertTrue(StocksRules.stale(thanksgiving, ny(26, 10, 41, month = 11)))
        assertFalse(StocksRules.stale(q("AAPL", 227.52, 1.2, read = ny(26, 15, 36, month = 11), tradedAt = ny(25, 16, month = 11)), ny(26, 16, 2, month = 11)))
    }

    @Test fun afterAFailureEachHasItsPace() {
        fun failed(f: Finnhub.Failure, wait: Long = 0) = Quote("AAPL", failure = f, retryAfterSec = wait)
        assertNull(StocksRules.every(failed(Finnhub.Failure.REFUSED), wed11))
        assertEquals(6 * hour, StocksRules.every(failed(Finnhub.Failure.NOT_FOUND), wed11))
        assertEquals(6 * hour, StocksRules.every(failed(Finnhub.Failure.NO_ACCESS), wed11))
        assertEquals(5 * min, StocksRules.every(failed(Finnhub.Failure.SLOW_DOWN), wed11))
        assertEquals(20 * min, StocksRules.every(failed(Finnhub.Failure.SLOW_DOWN, wait = 1200), wed11))
        assertEquals(5 * min, StocksRules.every(failed(Finnhub.Failure.NO_ANSWER), wed11))
        assertEquals(min, StocksRules.every(failed(Finnhub.Failure.OFFLINE), wed11))
    }

    @Test fun triesThatGoOutAndReachNothingComeLessAndLessOften() {
        // Without a network nothing goes out and the check is every minute; a network that doesn't reach Finnhub costs a try each time.
        fun offline(misses: Int) = StocksRules.every(Quote("AAPL", failure = Finnhub.Failure.OFFLINE, misses = misses), wed11)
        assertEquals(listOf(min, min, 2 * min, 4 * min, 5 * min, 5 * min), (0..5).map(::offline))
        assertEquals(5 * min, offline(40))
    }

    @Test fun refreshWaitsAsLongAsTheServiceAsked() {
        assertEquals(30_000L, StocksRules.againAfter(null))
        assertEquals(30_000L, StocksRules.againAfter(q("AAPL", 227.52, 1.2)))
        assertEquals(30_000L, StocksRules.againAfter(Quote("AAPL", failure = Finnhub.Failure.OFFLINE)))
        // Told to slow down: five minutes, or what the service named.
        assertEquals(5 * min, StocksRules.againAfter(Quote("AAPL", failure = Finnhub.Failure.SLOW_DOWN)))
        assertEquals(20 * min, StocksRules.againAfter(Quote("AAPL", failure = Finnhub.Failure.SLOW_DOWN, retryAfterSec = 1200)))
        assertEquals(2 * min, StocksRules.againAfter(Quote("AAPL", failure = Finnhub.Failure.NO_ANSWER, retryAfterSec = 120)))
    }

    // ---- the menu --------------------------------------------------------------------------------------

    @Test fun theMenuHasEachStocksDay() {
        val aq = Quote("AAPL", 227.52, 2.70, 1.2, 229.4, 222.1, 224.82, wed11 / 1000 - 60, wed11)
        val mq = Quote("MSFT", 418.07, -1.68, -0.4, 421.0, 416.5, 419.75, wed11 / 1000 - 60, wed11)
        val v = StocksRules.menu(look(aapl, msft, nvda), mapOf("AAPL" to aq, "MSFT" to mq), wed11, w, t)
        val a = v.lines[0]
        assertEquals("227.52", a.price)
        assertEquals("+2.70 (+1.2%)", a.change)
        assertEquals(true, a.up)
        assertEquals("Day 222.10 – 229.40", a.range)
        assertNull(a.problem)
        assertEquals("AAPL 227.52, up 1.2 percent; APPLE INC", a.desc)
        assertEquals("−1.68 (−0.4%)", v.lines[1].change)
        // A change under a dollar has the price's two decimals; only a stock under a dollar has four.
        fun changed(price: Double, by: Double) = StocksRules.menu(look(aapl), mapOf("AAPL" to Quote("AAPL", price, by, by / (price - by) * 100,
            prevClose = price - by, at = wed11 / 1000, fetchedAt = wed11)), wed11, w, t).lines[0].change
        assertEquals("+0.74 (+0.2%)", changed(333.63, 0.74))
        assertEquals("−0.0123 (−2.7%)", changed(0.4512, -0.0123))
        // Each number has the sign of what it reads: a fall of twenty cents on a $500 stock is no 0.0% rise.
        assertEquals("−0.20 (0.0%)", changed(500.0, -0.20))
        assertEquals("+0.04 (0.0%)", changed(300.0, 0.04))
        fun upOf(price: Double, by: Double) = StocksRules.menu(look(aapl), mapOf("AAPL" to Quote("AAPL", price, by, by / (price - by) * 100,
            prevClose = price - by, at = wed11 / 1000, fetchedAt = wed11)), wed11, w, t).lines[0].up
        assertEquals(false, upOf(500.0, -0.20))
        assertEquals(true, upOf(300.0, 0.04))
        assertNull(upOf(300.0, 0.004)) // "0.00 (0.0%)"
        assertEquals("0.00 (0.0%)", changed(300.0, 0.004))
        assertEquals(false, v.lines[1].up)
        // A stock without a price yet says that it waits.
        assertNull(v.lines[2].price)
        assertEquals("Waiting for a price", v.lines[2].problem)
        assertEquals("NVDA; NVIDIA CORP; Waiting for a price", v.lines[2].desc)
        assertEquals("Market open · closes 4:00 PM", v.market)
        assertEquals("Prices by Finnhub · updated 11:00 AM", v.note)
    }

    @Test fun theMenuSaysWhatWentWrong() {
        val v = StocksRules.menu(look(aapl, msft), mapOf("AAPL" to q("AAPL", 110.0, 10.0), "MSFT" to q("MSFT", 95.0, -5.0).copy(failure = Finnhub.Failure.OFFLINE)), wed11, w, t)
        // A stock that failed since keeps its numbers, and says why they are not new.
        assertEquals("95.00", v.lines[1].price)
        assertEquals("No connection", v.lines[1].problem)
        assertEquals("Prices by Finnhub · no connection, updated 11:00 AM", v.note)
        val refused = StocksRules.menu(look(aapl), mapOf("AAPL" to Quote("AAPL", failure = Finnhub.Failure.REFUSED)), wed11, w, t)
        assertEquals("Finnhub refused the key", refused.lines[0].problem)
        assertNull(refused.note)
    }

    @Test fun aFailedSearchSaysWhy() {
        fun msg(f: Finnhub.Failure) = w.say(StocksRules.searchProblem(f))
        assertEquals("No connection", msg(Finnhub.Failure.OFFLINE))
        assertEquals("Finnhub refused the key", msg(Finnhub.Failure.REFUSED))
        assertEquals("Not in Finnhub's free plan", msg(Finnhub.Failure.NO_ACCESS))
        assertEquals("Nothing found. Only US stocks and ETFs can be followed.", msg(Finnhub.Failure.NOT_FOUND))
        assertEquals("Too many requests. Try again in a minute.", msg(Finnhub.Failure.SLOW_DOWN))
        assertEquals("Finnhub didn't answer. Try again in a moment.", msg(Finnhub.Failure.NO_ANSWER))
    }

    // ---- what a layout holds ---------------------------------------------------------------------------

    private val item = ItemConfig("s", "stocks")

    @Test fun addingMovingAndRemovingStocks() {
        var i = StocksRules.add(item, Listing("AAPL", "APPLE INC"))
        i = StocksRules.add(i, Listing("MSFT", "MICROSOFT CORP"))
        i = StocksRules.add(i, Listing("AAPL", "APPLE INC")) // twice is once
        assertEquals(listOf(aapl, msft), StocksRules.stocks(i))
        assertEquals(listOf(msft, aapl), StocksRules.stocks(StocksRules.move(i, "MSFT", -1)))
        assertEquals(i, StocksRules.move(i, "AAPL", -1)) // the first can't go up
        assertEquals(listOf(msft), StocksRules.stocks(StocksRules.remove(i, "AAPL")))
        assertNull(StocksRules.remove(StocksRules.remove(i, "AAPL"), "MSFT").options["stocks"])
    }

    @Test fun tenStocksIsTheMost() {
        var i = item
        for (n in 0 until 12) i = StocksRules.add(i, Listing("S$n", "Stock $n"))
        assertEquals(10, StocksRules.stocks(i).size)
    }

    @Test fun aLayoutsStocksAreReadCarefully() {
        fun of(text: String) = StocksRules.stocks(item.with("stocks", text))
        assertEquals(emptyList<Stock>(), of("not json"))
        assertEquals(emptyList<Stock>(), of("{\"s\":\"AAPL\"}"))
        // Not US, no symbol at all, a number, twice: left out. A name that is missing is the symbol.
        assertEquals(listOf(aapl, Stock("MSFT", "MSFT")),
            of("""[{"s":"AAPL","n":"APPLE INC"},{"s":"SHOP.TO","n":"x"},{"n":"no symbol"},{"s":5},{"s":"aapl","n":"again"},{"s":"msft"}]"""))
        // A name from anywhere is one line, sixty characters at most.
        assertEquals("A B", of("""[{"s":"AAPL","n":"A\nB"}]""")[0].name)
        assertEquals(60, of("""[{"s":"AAPL","n":"${"x".repeat(500)}"}]""")[0].name.length)
    }

    @Test fun whatIsKeptIsTheModelAndNeverAFailure() {
        val kept = StocksRules.kept(StocksRules.keep(q("AAPL", 227.52, 1.2).copy(failure = Finnhub.Failure.OFFLINE, misses = 3)))!!
        assertEquals(227.52, kept.price!!, 0.0)
        assertNull(kept.failure)
        assertEquals(0, kept.misses)
        assertNull(StocksRules.kept("{}"))
        assertNull(StocksRules.kept(StocksRules.keep(Quote("AAPL", failure = Finnhub.Failure.REFUSED))))
    }

    @Test fun nothingPrintsWhichStock() {
        assertEquals("a quote", q("AAPL", 227.52, 1.2).toString())
        assertEquals("a stock", aapl.toString())
        assertEquals("a stock", Listing("AAPL", "APPLE INC").toString())
    }

    @Test fun cachedNumberFormatsDontMixLocalesOrDecimals() {
        assertEquals("227.52", StocksRules.price(227.52, java.util.Locale.US))
        assertEquals("227,52", StocksRules.price(227.52, java.util.Locale.GERMANY))
        assertEquals("0.4512", StocksRules.price(0.4512, java.util.Locale.US))
        assertEquals("227.52", StocksRules.price(227.52, java.util.Locale.US))
    }
}
