package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.data.Kept
import com.meridian11.books.bentobar.data.Online
import com.meridian11.books.bentobar.data.Online.Service.FINNHUB
import com.meridian11.books.bentobar.data.Section
import com.meridian11.books.bentobar.net.FakeHttp
import com.meridian11.books.bentobar.net.Host
import com.meridian11.books.bentobar.net.Http
import com.meridian11.books.bentobar.net.Reply
import com.meridian11.books.bentobar.net.Request
import com.meridian11.books.bentobar.net.Why
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * What the Stocks item sends, to the letter, what it makes of every way a request can end, and what it
 * keeps. A fake stands in the network's place; the replies are shaped as Finnhub's.
 */
class StocksLoadTest {
    private val dir = File(System.getProperty("java.io.tmpdir"), "bentobar-stocks-${System.nanoTime()}")
    private val key = "a-key-nobody-may-see-123"
    private val now = 1791392400_000L
    private val quote = """{"c":227.52,"d":2.7,"dp":1.2,"h":229.4,"l":222.1,"o":224,"pc":224.82,"t":1791392340}"""
    private val unknown = """{"c":0,"d":null,"dp":null,"h":0,"l":0,"o":0,"pc":0,"t":0}"""
    private val found = """{"count":4,"result":[
        {"description":"APPLE INC","displaySymbol":"AAPL","symbol":"AAPL","type":"Common Stock"},
        {"description":"APPLE INC","displaySymbol":"AAPL.SW","symbol":"AAPL.SW","type":"Common Stock"},
        {"description":"APPLE HOSPITALITY REIT INC","displaySymbol":"APLE","symbol":"APLE","type":"REIT"},
        {"description":"APPLE INC","displaySymbol":"AAPL","symbol":"AAPL","type":"Common Stock"}]}"""

    @Before fun fresh() { Online.init(dir); Online.saveKey(FINNHUB, key) } // the key turns the service on
    @After fun gone() { dir.deleteRecursively(); Online.init(File(dir, "empty")) }

    private fun load(symbol: String = "AAPL", last: Quote? = null, symbols: Set<String> = setOf("AAPL", "MSFT")) = StocksLoad.load(symbol, last, now) { symbols }

    // ---- what is sent ---------------------------------------------------------------------------------

    @Test fun aQuoteRequestIsTheSymbolAndTheKeyAndNothingElse() = FakeHttp.use { net ->
        net.reply(Host.FINNHUB, Finnhub.QUOTE, quote)
        load()
        val r = net.asked.single()
        assertEquals(Host.FINNHUB, r.host)
        assertEquals("/api/v1/quote", r.path)
        assertEquals(listOf("symbol" to "AAPL"), r.query)
        // Printed, a request says its host and path only: never the key, never the stock.
        assertEquals("api.finnhub.io/api/v1/quote", r.toString())
    }

    @Test fun aSearchIsTheTextLimitedToTheUsAndTheKey() = FakeHttp.use { net ->
        net.reply(Host.FINNHUB, Finnhub.SEARCH, found)
        StocksLoad.search("apple")
        assertEquals(listOf("q" to "apple", "exchange" to "US"), net.asked.single().query)
        assertEquals("apple", StocksLoad.query("  apple\n"))
        assertNull(StocksLoad.query("  "))
        assertEquals(Finnhub.QUERY_CHARS, StocksLoad.query("x".repeat(500))!!.length)
    }

    @Test fun withoutAKeyOrSwitchedOffNothingIsAsked() = FakeHttp.use { net ->
        net.reply(Host.FINNHUB, Finnhub.QUOTE, quote)
        Online.turnOff(FINNHUB)
        assertNull(load())
        assertEquals(StockFound.Unasked, StocksLoad.search("apple"))
        Online.turnOn(FINNHUB)
        Online.removeKey(FINNHUB)
        assertNull(load())
        assertEquals(StockFound.Unasked, StocksLoad.search("apple"))
        assertEquals(emptyList<Request>(), net.asked)
        assertEquals(0, Http.sent(Host.FINNHUB))
    }

    @Test fun aSymbolThatIsNoUsSymbolIsNeverSent() = FakeHttp.use { net ->
        for (bad in listOf("SHOP.TO", "apple inc", "", "AAPL&token=x")) assertNull(bad, load(bad))
        assertEquals(emptyList<Request>(), net.asked)
    }

    // ---- what comes back --------------------------------------------------------------------------------

    @Test fun aGoodAnswerIsAQuoteReadNow() = FakeHttp.use { net ->
        net.reply(Host.FINNHUB, Finnhub.QUOTE, quote)
        val q = load()!!
        assertEquals(Quote("AAPL", 227.52, 2.7, 1.2, 229.4, 222.1, 224.82, 1791392340, now), q)
        assertNull(q.failure)
    }

    @Test fun theLastQuoteIsKeptAsTheAppsOwnModelNeverTheReply() = FakeHttp.use { net ->
        net.reply(Host.FINNHUB, Finnhub.QUOTE, quote)
        val q = load()!!
        val text = Kept.fetched(FINNHUB).read("AAPL")!!.text
        assertFalse(text.contains(key))
        assertFalse(text.contains("\"dp\""))
        assertEquals(q, StocksRules.kept(text))
        // A restart twenty minutes later shows it with its age, asking nobody.
        val back = StocksLoad.kept("AAPL", now + 20 * 60_000)!!
        assertEquals(q, back.value)
        assertEquals(20 * 60_000L, back.ageMs)
        assertEquals(1, net.asked.size)
        // What no item follows any more is not kept, and goes with the next load.
        net.reply(Host.FINNHUB, Finnhub.QUOTE, quote)
        load("MSFT", symbols = setOf("MSFT"))
        assertEquals(setOf("MSFT"), Kept.fetched(FINNHUB).names())
    }

    @Test fun switchedOffWhatWasFetchedIsGone() = FakeHttp.use { net ->
        net.reply(Host.FINNHUB, Finnhub.QUOTE, quote)
        load()
        Online.turnOff(FINNHUB)
        assertEquals(emptySet<String>(), Kept.fetched(FINNHUB).names())
    }

    // A fake answers in the order it was given answers, and repeats the last: each case here gets a fake of its own.
    @Test fun everyWayARequestCanFailIsAFailureThatKeepsTheNumbers() {
        val last = Quote("AAPL", 220.0, fetchedAt = now - 60_000)
        fun failsWith(status: Int, why: Why = Why.STATUS): Quote? = FakeHttp.use { net ->
            net.fail(Host.FINNHUB, Finnhub.QUOTE, why, status)
            load(last = last)
        }
        assertEquals(Finnhub.Failure.REFUSED, failsWith(401)!!.failure)
        assertEquals(Finnhub.Failure.NO_ACCESS, failsWith(403)!!.failure)
        assertEquals(Finnhub.Failure.SLOW_DOWN, failsWith(429)!!.failure)
        assertEquals(Finnhub.Failure.NO_ANSWER, failsWith(500)!!.failure)
        assertEquals(Finnhub.Failure.NO_ANSWER, failsWith(0, Why.TIMEOUT)!!.failure)
        assertEquals(Finnhub.Failure.OFFLINE, failsWith(0, Why.OFFLINE)!!.failure)
        assertEquals(220.0, failsWith(500)!!.price!!, 0.0)
        // Not asked after all: nothing to say, and what there is stays.
        assertNull(failsWith(0, Why.OFF))
        // Another stock's numbers are never taken for this one's.
        FakeHttp.use { net ->
            net.fail(Host.FINNHUB, Finnhub.QUOTE, Why.STATUS, 500)
            assertNull(load(last = Quote("MSFT", 418.0))!!.price)
        }
    }

    @Test fun aStockTheServiceDoesNotKnowIsNotFound() {
        FakeHttp.use { net ->
            net.reply(Host.FINNHUB, Finnhub.QUOTE, unknown)
            assertEquals(Finnhub.Failure.NOT_FOUND, load()!!.failure)
        }
        FakeHttp.use { net ->
            net.reply(Host.FINNHUB, Finnhub.QUOTE, "<html>Sign in to the network</html>")
            assertEquals(Finnhub.Failure.NO_ANSWER, load()!!.failure)
        }
    }

    @Test fun withoutANetworkNothingGoesOutAndTriesInARowAreCounted() = FakeHttp.use { net ->
        Http.connected = { false }
        val q = load()!!
        assertEquals(Finnhub.Failure.OFFLINE, q.failure)
        assertEquals(0, q.misses) // nothing went out
        assertEquals(emptyList<Request>(), net.asked)
        Http.connected = { true }
        net.fail(Host.FINNHUB, Finnhub.QUOTE, Why.STATUS, 500)
        assertEquals(1, load(last = q)!!.misses)
    }

    @Test fun toldToSlowDownItRemembersForHowLong() = FakeHttp.use { net ->
        net.fail(Host.FINNHUB, Finnhub.QUOTE, Why.STATUS, 429, retryAfterSec = 90)
        assertEquals(90L, load()!!.retryAfterSec)
    }

    // ---- the search ---------------------------------------------------------------------------------------

    @Test fun aSearchListsUsStocksOnceEach() {
        FakeHttp.use { net ->
            net.reply(Host.FINNHUB, Finnhub.SEARCH, found)
            val list = (StocksLoad.search("apple") as StockFound.Listings).list
            assertEquals(listOf(Listing("AAPL", "APPLE INC"), Listing("APLE", "APPLE HOSPITALITY REIT INC")), list)
        }
        FakeHttp.use { net ->
            net.reply(Host.FINNHUB, Finnhub.SEARCH, """{"count":0,"result":[]}""")
            assertEquals(emptyList<Listing>(), (StocksLoad.search("zzzz") as StockFound.Listings).list)
        }
    }

    @Test fun aSearchThatFailsSaysHow() {
        fun searched(set: (FakeHttp) -> Unit): Finnhub.Failure = FakeHttp.use { net -> set(net); (StocksLoad.search("apple") as StockFound.Failed).failure }
        assertEquals(Finnhub.Failure.REFUSED, searched { it.fail(Host.FINNHUB, Finnhub.SEARCH, Why.STATUS, 401) })
        assertEquals(Finnhub.Failure.OFFLINE, searched { it.fail(Host.FINNHUB, Finnhub.SEARCH, Why.OFFLINE) })
        assertEquals(Finnhub.Failure.NO_ANSWER, searched { it.reply(Host.FINNHUB, Finnhub.SEARCH, """{"error":"Invalid API key."}""") })
    }

    @Test fun theSymbolsOfALayoutAreItsStocksItemsShownOrHidden() {
        fun stocks(id: String, json: String, section: Section = Section.SHOWN) = ItemConfig(id, "stocks", section, options = mapOf("stocks" to json))
        val items = listOf(
            stocks("a", """[{"s":"AAPL","n":"APPLE INC"},{"s":"MSFT","n":"MICROSOFT"}]"""),
            stocks("b", """[{"s":"MSFT","n":"MICROSOFT"}]""", Section.HIDDEN),
            ItemConfig("d", "weather", options = mapOf("stocks" to """[{"s":"TSLA"}]""")),
        )
        assertEquals(setOf("AAPL", "MSFT"), StocksLoad.symbols(items))
    }
}
