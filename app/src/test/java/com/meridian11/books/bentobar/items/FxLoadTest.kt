package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.items.FxRules.FxTable
import com.meridian11.books.bentobar.net.FakeHttp
import com.meridian11.books.bentobar.net.Host
import com.meridian11.books.bentobar.net.HttpTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FxLoadTest : OnlineServiceContract<FxTable>() {
    private val now = 1_776_000_000_000L
    private val reply = """[
        {"date":"2026-04-10","base":"EUR","quote":"EUR","rate":1.0},
        {"date":"2026-04-10","base":"EUR","quote":"USD","rate":1.085},
        {"date":"2026-04-11","base":"EUR","quote":"EUR","rate":1.0},
        {"date":"2026-04-11","base":"EUR","quote":"USD","rate":1.092}]"""

    override val host = Host.FRANKFURTER
    override val path = FxLoad.PATH
    override val goodReply = reply
    override fun load(last: FxTable?) = FxLoad.load(last, now)
    override fun failure(v: FxTable) = v.failure?.name
    override fun misses(v: FxTable) = v.misses
    override fun waitSec(v: FxTable) = v.retryAfterSec
    override fun numbers(v: FxTable) = v.latest
    override fun every(v: FxTable) = FxRules.every(v)
    // Published in About and the README: literals on purpose, so that changing a constant fails here.
    override val freshMs: Long? = 60 * 60_000L
    override val retryMs = 15 * 60_000L
    override val slowMs = 60 * 60_000L

    @Test fun theConstantsAreThePublishedIntervals() {
        assertEquals(freshMs, FxRules.FRESH_MS as Long?)
        assertEquals(15 * 60_000L, FxRules.MENU_MS)
        assertEquals(retryMs, FxRules.RETRY_MS)
        assertEquals(slowMs, FxRules.SLOW_MS)
    }

    @Test fun theRequestIsTheEcbsDaysSinceTheStartDateAndNothingElse() {
        val r = FxLoad.request(now)
        assertEquals("/v2/rates", r.path)
        assertEquals(listOf("base" to "EUR", "from" to "2026-04-02", "providers" to "ECB"), r.query)
        assertNotNull(HttpTransport.address(r))
    }

    @Test fun aFailureKeepsTheRatesThatWereThere() = FakeHttp.use { net ->
        net.reply(Host.FRANKFURTER, FxLoad.PATH, reply)
        val table = FxLoad.load(null, now)!!
        assertTrue(table.hasRates)
        assertEquals(2, table.latest.size)
    }
}
