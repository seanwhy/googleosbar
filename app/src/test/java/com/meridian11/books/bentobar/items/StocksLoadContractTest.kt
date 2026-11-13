package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.data.Online
import com.meridian11.books.bentobar.net.Host
import org.junit.After
import org.junit.Before
import java.io.File

/** Stocks under the contract every online service keeps; what is its own is in [StocksLoadTest]. */
class StocksLoadContractTest : OnlineServiceContract<Quote>() {
    private val dir = File(System.getProperty("java.io.tmpdir"), "bentobar-stocks-contract-${System.nanoTime()}")
    private val now = 1791392400_000L

    @Before fun fresh() { Online.init(dir); Online.saveKey(Online.Service.FINNHUB, "a-key") }
    @After fun gone() { dir.deleteRecursively(); Online.init(File(dir, "empty")) }

    override val host = Host.FINNHUB
    override val path = Finnhub.QUOTE
    override val goodReply = """{"c":227.52,"d":2.7,"dp":1.2,"h":229.4,"l":222.1,"o":224,"pc":224.82,"t":1791392340}"""
    override fun load(last: Quote?) = StocksLoad.load("AAPL", last, now) { setOf("AAPL") }
    override fun failure(v: Quote) = v.failure?.name
    override fun misses(v: Quote) = v.misses
    override fun waitSec(v: Quote) = v.retryAfterSec
    override fun numbers(v: Quote) = v.price
    override fun every(v: Quote) = StocksRules.every(v, now)
    override val retryMs = StocksRules.RETRY_MS
    override val slowMs = StocksRules.SLOW_MS
}
