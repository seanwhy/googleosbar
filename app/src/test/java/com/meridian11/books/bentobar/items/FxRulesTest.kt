package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.data.ItemConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.meridian11.books.bentobar.net.FakeHttp
import com.meridian11.books.bentobar.net.Host
import java.util.Locale

class FxRulesTest {
    private val german = Locale.GERMANY
    private val french = Locale.FRANCE
    private val indian = Locale("en", "IN")

    /** The shape Frankfurter's `/v2/rates` answers with: one row per currency and day. */
    private val seriesJson = """
        [
          {"date": "2026-04-14", "base": "EUR", "quote": "EUR", "rate": 1.0},
          {"date": "2026-04-14", "base": "EUR", "quote": "USD", "rate": 1.0850},
          {"date": "2026-04-14", "base": "EUR", "quote": "GBP", "rate": 0.8540},
          {"date": "2026-04-14", "base": "EUR", "quote": "JPY", "rate": 162.10},
          {"date": "2026-04-15", "base": "EUR", "quote": "EUR", "rate": 1.0},
          {"date": "2026-04-15", "base": "EUR", "quote": "USD", "rate": 1.0920},
          {"date": "2026-04-15", "base": "EUR", "quote": "GBP", "rate": 0.8520},
          {"date": "2026-04-15", "base": "EUR", "quote": "JPY", "rate": 163.40}
        ]
    """.trimIndent()

    @Test fun parsesTimeSeriesAndComputesCrossRatesAndDailyChange() {
        val table = FxRules.read(seriesJson, now = 50_000L)
        assertNotNull(table)
        table!!
        assertEquals("2026-04-15", table.date)
        assertTrue(table.hasRates)

        val eurUsd = FxRules.quoteFor(FxRules.FxPair("EUR", "USD"), table, Locale.US)!!
        assertEquals(1.0920, eurUsd.rate, 0.0001)
        assertTrue(eurUsd.changePct > 0.6)
        assertEquals("1.0920", eurUsd.formattedRate)

        val usdJpy = FxRules.quoteFor(FxRules.FxPair("USD", "JPY"), table)!!
        assertEquals(163.40 / 1.0920, usdJpy.rate, 0.01)
    }

    @Test fun rejectsMalformedJson() {
        assertNull(FxRules.read("not json", 10_000L))
        assertNull(FxRules.read("""{"base":"EUR","rates":{}}""", 10_000L))
        assertNull(FxRules.read("[]", 10_000L))
        assertNull(FxRules.read("""[{"date":"2026-04-15","base":"EUR","quote":"EUR","rate":1.0}]""", 10_000L))
    }

    @Test fun parsesAndManagesFollowedCurrencyPairs() {
        assertEquals(FxRules.FxPair("EUR", "USD"), FxRules.parsePair(" eur / usd "))
        assertEquals(FxRules.FxPair("GBP", "JPY"), FxRules.parsePair("gbp-jpy"))
        assertNull(FxRules.parsePair("USD/USD"))
        assertNull(FxRules.parsePair("ABC/USD"))

        var item = ItemConfig("f1", "fx", options = mapOf("pairs" to "EUR/USD"))
        item = FxRules.addPair(item, FxRules.FxPair("USD", "JPY"))
        assertEquals(listOf(FxRules.FxPair("EUR", "USD"), FxRules.FxPair("USD", "JPY")), FxRules.pairs(item))

        item = FxRules.movePair(item, FxRules.FxPair("USD", "JPY"), -1)
        assertEquals(listOf(FxRules.FxPair("USD", "JPY"), FxRules.FxPair("EUR", "USD")), FxRules.pairs(item))

        item = FxRules.removePair(item, FxRules.FxPair("EUR", "USD"))
        assertEquals(listOf(FxRules.FxPair("USD", "JPY")), FxRules.pairs(item))
    }

    @Test fun formatsConvertedAmountsCleanly() {
        assertEquals("109.20", FxRules.formatConverted(100.0, 1.092, Locale.US))
        assertEquals("16,340.00", FxRules.formatConverted(100.0, 163.4, Locale.US))
        assertEquals(250.0, FxRules.parseAmount(" 250 ", Locale.US)!!, 0.001)
        assertNull(FxRules.parseAmount("-5", Locale.US))
    }

    @Test fun figuresAreWrittenInTheDevicesOwnWay() {
        assertEquals("109,20", FxRules.formatConverted(100.0, 1.092, german))
        assertEquals("16.340,00", FxRules.formatConverted(100.0, 163.4, german))
        assertEquals("1,0920", FxRules.formatRate(1.092, german))
        assertEquals("1.0920", FxRules.formatRate(1.092, Locale.US))
        assertEquals("0,65", FxRules.formatPct(-0.65, german))
        assertEquals("100,5", FxRules.formatAmount(100.5, german))
        assertEquals("100", FxRules.formatAmount(100.0, german))
        assertEquals("100.5", FxRules.formatAmount(100.5, Locale.US))
    }

    @Test fun aDecimalCommaIsADecimalComma() {
        assertEquals(100.5, FxRules.parseAmount("100,50", german)!!, 0.001)
        assertEquals(1000.5, FxRules.parseAmount("1.000,50", german)!!, 0.001)
    }

    @Test fun whereTheCommaGroupsThousandsItOnlyDoesThat() {
        assertEquals(1000.5, FxRules.parseAmount("1,000.50", Locale.US)!!, 0.001)
        assertEquals(1000.0, FxRules.parseAmount("1,000", Locale.US)!!, 0.001)
        // A comma that can't be grouping is no number here: better no figure than one a hundred times too large.
        assertNull(FxRules.parseAmount("100,50", Locale.US))
        assertNull(FxRules.parseAmount("1,0000", Locale.US))
        assertEquals(100000.5, FxRules.parseAmount("1,00,000.50", indian)!!, 0.001)
    }

    @Test fun whereTheDotGroupsThousandsItIsNoDecimalPoint() {
        assertNull(FxRules.parseAmount("100.50", german))
        assertEquals(1000.0, FxRules.parseAmount("1.000", german)!!, 0.001)
    }

    @Test fun aSpaceGroupsThousandsWhereTheLocaleDoesSo() {
        for (space in listOf(" ", "\u00A0", "\u202F")) assertEquals(space, 1000.5, FxRules.parseAmount("1${space}000,50", french)!!, 0.001)
        assertNull(FxRules.parseAmount("1 000.50", Locale.US))
    }

    @Test fun textThatIsNotWhollyANumberIsNotAnAmount() {
        for (text in listOf("", " ", "abc", "12abc", "1e5", "0x10", "NaN", "Infinity", ".", ",", "1,2,3", "0", "1000000001"))
            assertNull(text, FxRules.parseAmount(text, Locale.US))
        // Halfway through typing, a figure stays.
        assertEquals(100.0, FxRules.parseAmount("100.", Locale.US)!!, 0.001)
        assertEquals(0.5, FxRules.parseAmount(".5", Locale.US)!!, 0.001)
    }

    private val now = 1_776_000_000_000L

    @Test fun theRatesRequestIsOneTheTransportWillSend() {
        // No install could load a rate in 1.6 to 1.7.1: the transport refused the path and only a real device could tell.
        assertNotNull(com.meridian11.books.bentobar.net.HttpTransport.address(FxLoad.request(now)))
    }

    @Test fun ratesLoadThroughTheTransportsOwnRule() = FakeHttp.use { net ->
        net.reply(Host.FRANKFURTER, FxLoad.request(now).path, seriesJson)
        val table = FxLoad.load(null, now)
        assertEquals("EUR", net.query("base"))
        assertEquals("ECB", net.query("providers"))
        assertTrue(table!!.hasRates)
        assertNull(table.failure)
    }

    // ---- a Friday's move, an old table, a currency that isn't there ----------------------------

    private fun at(date: String, time: String = "12:00:00") = java.time.Instant.parse("${date}T${time}Z").toEpochMilli()
    private val big = FxRules.FxTable(mapOf("EUR" to 1.0, "USD" to 1.05), mapOf("EUR" to 1.0, "USD" to 1.0), date = "2026-10-30")
    private val usd = listOf(FxRules.FxPair("EUR", "USD"))

    @Test fun aMoveCountsWhileItsTableIsFromTodayOrOnAWeekdayYesterdayUtc() {
        val quotes = FxRules.quotesFor(usd, big)
        assertTrue("Friday's rates on Friday", FxRules.bigMove(quotes, big, 1.0, at("2026-10-30")))
        assertFalse("Saturday: no new table is coming, so Friday's move is not today's", FxRules.bigMove(quotes, big, 1.0, at("2026-10-31", "00:00:00")))
        assertTrue("a weekday morning, before the bank has published", FxRules.bigMove(quotes, FxRules.FxTable(big.latest, big.previous, date = "2026-11-02"), 1.0, at("2026-11-03", "08:00:00")))
        assertFalse("Sunday: Friday's move is not news", FxRules.bigMove(quotes, big, 1.0, at("2026-11-01", "00:00:00")))
        assertFalse("Monday morning, still Friday's table", FxRules.bigMove(quotes, big, 1.0, at("2026-11-02", "08:00:00")))
    }

    @Test fun anUnreadableTableDateCountsNoMoveButKeepsItsRate() {
        val noDate = big.copy(date = "")
        val quotes = FxRules.quotesFor(usd, noDate)
        assertEquals(1, quotes.size)
        assertFalse(FxRules.bigMove(quotes, noDate, 1.0, at("2026-10-30")))
    }

    @Test fun aSmallMoveIsNoNewsOnAnyDay() {
        val calm = big.copy(latest = mapOf("EUR" to 1.0, "USD" to 1.001))
        assertFalse(FxRules.bigMove(FxRules.quotesFor(usd, calm), calm, 1.0, at("2026-10-30")))
    }

    @Test fun theDateIsSaidOnlyWhenTheRatesAreNotTodays() {
        val zone = java.time.ZoneId.of("Europe/Zurich")
        assertNull(FxRules.notToday(big, at("2026-10-30"), zone))
        assertEquals(java.time.LocalDate.parse("2026-10-30"), FxRules.notToday(big, at("2026-11-02"), zone))
        // Late on the 30th in New York it is already the 31st in Zurich.
        assertNull(FxRules.notToday(big, at("2026-10-31", "01:00:00"), java.time.ZoneId.of("America/New_York")))
        assertNull(FxRules.notToday(big.copy(date = ""), at("2026-11-02"), zone))
    }

    @Test fun aCurrencyTheServiceDoesNotReturnIsNamedAndNeverDropped() {
        val table = FxRules.read(seriesJson, 50_000L)!!
        val bgn = FxRules.FxPair("EUR", "BGN")
        val pairs = listOf(FxRules.FxPair("EUR", "USD"), bgn)
        assertEquals(listOf(bgn), FxRules.missing(pairs, table))
        assertEquals(listOf(FxRules.FxPair("EUR", "USD")), FxRules.quotesFor(pairs, table).map { it.pair })
    }

    @Test fun ratesAreReadFromTheSecondToLastDayForTheMoveWhateverOrderTheyCameIn() {
        val out = """[{"date":"2026-04-15","base":"EUR","quote":"USD","rate":1.1},{"date":"2026-04-14","base":"EUR","quote":"USD","rate":1.0},{"date":"2026-04-13","base":"EUR","quote":"USD","rate":0.9}]"""
        val t = FxRules.read(out, 1L)!!
        assertEquals("2026-04-15", t.date)
        assertEquals(1.1, t.latest["USD"]!!, 0.001)
        assertEquals(1.0, t.previous["USD"]!!, 0.001)
        assertEquals("the base is itself at 1", 1.0, t.latest["EUR"]!!, 0.0)
    }

    @Test fun rowsThatMakeNoSenseAreLeftOut() {
        val out = """[{"date":"2026-04-15","base":"EUR","quote":"USD","rate":1.1},{"date":"yesterday","base":"EUR","quote":"GBP","rate":1.0},
            {"date":"2026-04-15","base":"EUR","quote":"JPY","rate":-1},{"date":"2026-04-15","base":"EUR","quote":"CHF","rate":"x"}, 7]"""
        val t = FxRules.read(out, 1L)!!
        assertEquals(setOf("EUR", "USD"), t.latest.keys)
    }
}
